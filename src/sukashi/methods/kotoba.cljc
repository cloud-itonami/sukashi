(ns sukashi.methods.kotoba
  "kotoba.py — sukashi kotoba Datom-log writer (local, content-addressed). ADR-2606071600
  + ADR-2605262130 + ADR-2605312345. 1:1 Clojure port of `methods/kotoba.py`.

  The local, autonomous-loop write path: a self-driving heartbeat appends content-addressed
  transactions to a local append-only EDN log with NO external I/O. Canonical state = the kotoba
  Datom log (content-addressed EAVT assertions, append-only). `methods/transact.cljc` is the OTHER
  write path (an HTTP push into a running node, operator-gated).

    - graph-datoms(rows)   → EAVT assertions for every entity (adtech / auth-edge / creative /
                             delivery-edge / fraud-signal). E = the entity's id; lists fan out.
    - derived-datoms(a)    → EAVT assertions for the analyzer's derived :adsupply/* + :adfraud/*
                             signals, flagged :derived true (recomputed-on-read, never re-ingested).
    - make-tx / append-tx / read-log / head-cid / verify-chain — content-addressed commit-DAG.

  EAVT = [op entity attribute value]; op is :db/add only (append-only — no :db/retract).
  Deterministic: the caller supplies tx-id + as-of (no wall clock) → resume-safe.

  House style (mirrors sukashi.methods.analyze / danjo.methods.kotoba): map keys stay verbatim
  string keys, Python ':ns/name' keyword strings stay literal strings; pure fns; file I/O only
  behind #?(:clj …). The tx CID reproduces Python `'b' + hashlib.sha256(json.dumps({'prev':…,
  'datoms':…}, ensure_ascii=False, sort_keys=True, separators=(',',':')).encode('utf-8'))
  .hexdigest()` byte-for-byte. (The Python `__main__` heartbeat printer is omitted — it is the
  autorun.cljc -main concern.) Re-uses the actor's own sukashi-edn reader for log read-back."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]
            #?(:clj [clojure.java.io :as io])))

;; ── sha-256 host seam ─────────────────────────────────────────────────────────
(def ^:dynamic *sha256-hex*
  "String → lowercase hex sha-256 digest (UTF-8). Rebind on hosts without MessageDigest."
  #?(:clj (fn [^String s]
            (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                             (.getBytes s "UTF-8"))]
              (str/join (map #(let [h (Integer/toHexString (bit-and % 0xff))]
                                (if (= 1 (count h)) (str "0" h) h))
                             d))))
     :default (fn [_] (throw (ex-info "bind sukashi.methods.kotoba/*sha256-hex* on this host" {})))))

(def id-keys
  "Entity-id attribute keys, in priority order (mirrors Python ID_KEYS)."
  [":adtech/id" ":adauth.edge/id" ":adcreative/id" ":addelivery.edge/id" ":adfraud.signal/id"])

;; ── order-preserving EDN reader ───────────────────────────────────────────────
;; sukashi-edn's reader builds maps via assoc into {}, which silently promotes to an unordered
;; hash-map beyond 8 keys (losing Python dict insertion order — which IS the datom-emit order, and
;; thus the tx-CID preimage). This reader is byte-identical to sukashi-edn except that every map
;; carries ::order metadata = first-touch key insertion order, so graph datoms emit in Python order.
(defn- ordered-assoc [m k v]
  (if (contains? m k)
    (assoc m k v)
    (vary-meta (assoc m k v) update ::order (fnil conj []) k)))

(defn row-keys
  "Keys of a row in first-touch insertion order (::order metadata if present, else (keys r))."
  [r]
  (or (::order (meta r)) (keys r)))

(defn- parse-step-ordered [toks i]
  (let [t (nth toks i), i (inc i)]
    (cond
      (= t "[")
      (loop [i i, out []]
        (let [[x i] (parse-step-ordered toks i)]
          (if (= x ::end) [out i] (recur i (conj out x)))))
      (= t "{")
      (loop [i i, out (with-meta {} {::order []})]
        (let [[k i] (parse-step-ordered toks i)]
          (if (= k ::end)
            [out i]
            (let [[v i] (parse-step-ordered toks i)]
              (recur i (ordered-assoc out k v))))))
      (or (= t "]") (= t "}")) [::end i]
      :else [(edn/atom-of t) i])))

(defn read-edn-ordered
  "Parse the first top-level EDN form, preserving map key insertion order via ::order metadata."
  [text]
  (let [toks (vec (edn/tokens text))]
    (first (parse-step-ordered toks 0))))

#?(:clj
   (defn load-edn-ordered
     "Read + parse an EDN file, preserving map key insertion order (file I/O edge)."
     [path]
     (read-edn-ordered (slurp (str path)))))

;; ── EAVT assertion ────────────────────────────────────────────────────────────
(defn add
  "One append-only EAVT assertion: [:db/add <entity> <attr> <value>]."
  [entity attr value]
  [":db/add" entity attr value])

(defn graph-datoms
  "Flatten the ad-supply-chain graph into append-only EAVT assertions. E = the entity's id;
  cardinality-many list values fan out (mirrors transact.rows_to_datoms). Map key iteration
  follows the EDN parse (insertion) order — Python dict order — via ::order metadata when the row
  was read by load-edn-ordered."
  [rows]
  (reduce
   (fn [out r]
     (if-not (map? r)
       out
       (let [e (some (fn [k] (when (contains? r k) (get r k))) id-keys)]
         (if (nil? e)
           out
           (reduce
            (fn [out k]
              (if (some #{k} id-keys)
                out
                (let [v (get r k)
                      items (if (vector? v) v [v])]
                  (reduce (fn [out item] (conj out (add e k item))) out items))))
            out
            (row-keys r))))))
   []
   rows))

(defn- lstrip-colon
  "str(x).lstrip(':') — strip ALL leading ':' chars."
  [x]
  (str/replace (str x) #"^:+" ""))

(defn pyround2
  "Python3 round(x, 2): HALF_EVEN over the exact value of the IEEE double x."
  [x]
  #?(:clj (-> (java.math.BigDecimal. (double x))
              (.setScale 2 java.math.RoundingMode/HALF_EVEN)
              (.doubleValue))
     :cljs (let [y (* (double x) 100.0)
                 r (Math/round y)
                 r (if (== 0.5 (Math/abs (- y (Math/floor y))))
                     (let [d (Math/floor y)] (if (even? (long d)) d (inc d)))
                     r)]
             (/ r 100.0))))

(defn derived-datoms
  "Flatten the analyzer's derived :adsupply/* + :adfraud/* signals into EAVT assertions, each
  flagged :derived true (aggregate observatory signals recomputed on read, never re-ingested as
  fact — G4 non-adjudication). `a` is the map returned by analyze/analyze. `prefix` = adsupply."
  ([a] (derived-datoms a "adsupply"))
  ([a prefix]
   (let [out (transient [])
         P (fn [d] (conj! out d))]
     (doseq [[s unc dec rate] (get a "unconfirmed_rate")]
       (let [e (str prefix "-unconf-" s)]
         (P (add e ":adsupply/seller" s)) (P (add e ":adsupply/unconfirmed" unc))
         (P (add e ":adsupply/declared" dec)) (P (add e ":adsupply/unconfirmed-rate" rate))
         (P (add e ":adsupply/derived" true))))
     (doseq [[s n] (get a "seller_fan_rank")]
       (let [e (str prefix "-fanout-" s)]
         (P (add e ":adsupply/seller" s)) (P (add e ":adsupply/seller-fan-out" n))
         (P (add e ":adsupply/derived" true))))
     (doseq [[s fan btw] (get a "seller_betweenness")]
       (let [e (str prefix "-btw-" s)]
         (P (add e ":adsupply/seller" s)) (P (add e ":adsupply/seller-betweenness" btw))
         (P (add e ":adsupply/seller-fan-in" fan)) (P (add e ":adsupply/derived" true))))
     (doseq [[asn load n] (get a "infra_rank")]
       (let [e (str prefix "-asn-" asn)]
         (P (add e ":adsupply/asn" asn)) (P (add e ":adsupply/infra-concentration" load))
         (P (add e ":adsupply/scam-creatives" n)) (P (add e ":adsupply/derived" true))))
     (doseq [[reg load n] (get a "registrar_rank")]
       (let [e (str prefix "-reg-" reg)]
         (P (add e ":adsupply/registrar" reg)) (P (add e ":adsupply/registrar-fraud-load" load))
         (P (add e ":adsupply/registrar-cooccurrence" n)) (P (add e ":adsupply/derived" true))))
     (doseq [[org load n] (get a "whois_rank")]
       (let [e (str prefix "-whois-" org)]
         (P (add e ":adsupply/whois-org" org)) (P (add e ":adsupply/whois-fraud-load" load))
         (P (add e ":adsupply/whois-cooccurrence" n)) (P (add e ":adsupply/derived" true))))
     (doseq [c (get a "clusters")]
       (let [e (str "adfraud-cluster-" (:asn c) "|" (:registrar c))]
         (P (add e ":adfraud/cluster-asn" (:asn c)))
         (P (add e ":adfraud/cluster-registrar" (:registrar c)))
         (P (add e ":adfraud/cluster-members" (:members c)))
         (P (add e ":adfraud/cluster-confidence" (:conf-sum c)))
         (P (add e ":adfraud/cluster-corroboration" (:corroboration c)))
         (P (add e ":adfraud/network-rank" (:rank-score c)))
         (P (add e ":adfraud/derived" true))))
     (doseq [[cat load] (get a "category_rank")]
       (let [e (str "adfraud-cat-" (lstrip-colon cat))]
         (P (add e ":adfraud/category" (lstrip-colon cat)))
         (P (add e ":adfraud/category-load" (pyround2 load)))
         (P (add e ":adfraud/derived" true))))
     (persistent! out))))

;; ── canonical JSON for the CID preimage ──────────────────────────────────────
;; Mirrors _canonical's json.dumps({"prev":…,"datoms":…}, ensure_ascii=False, sort_keys=True,
;; separators=(",",":")). ensure_ascii=FALSE → non-ASCII emitted RAW, not \uXXXX.
(defn- json-escape-utf8 ^String [^String s]
  (str/escape s {\" "\\\"" \\ "\\\\"
                 \backspace "\\b" \tab "\\t" \newline "\\n" \formfeed "\\f" \return "\\r"}))

(defn- canonical-json-utf8 ^String [v]
  (cond
    (string? v)     (str "\"" (json-escape-utf8 v) "\"")
    (boolean? v)    (if v "true" "false")
    (nil? v)        "null"
    (integer? v)    (str v)
    (number? v)     (str v)
    (map? v)        (str "{" (str/join "," (map (fn [k] (str "\"" (json-escape-utf8 (str k)) "\":"
                                                             (canonical-json-utf8 (get v k))))
                                                (sort (keys v)))) "}")
    (sequential? v) (str "[" (str/join "," (map canonical-json-utf8 v)) "]")
    :else (throw (ex-info "canonical-json-utf8: unsupported value" {:value v}))))

(defn- canonical [datoms prev-cid]
  ;; sort_keys=True orders the top map's keys alphabetically: "datoms" < "prev".
  (canonical-json-utf8 {"prev" prev-cid "datoms" datoms}))

(defn tx-cid
  "Content address = sha256 over (prev-cid, datoms) → a commit-DAG."
  ([datoms] (tx-cid datoms ""))
  ([datoms prev-cid]
   (str "b" (*sha256-hex* (canonical datoms prev-cid)))))

(defn make-tx
  "Assemble one content-addressed transaction map (string :tx/* keys, mirrors Python)."
  [datoms & {:keys [tx-id as-of prev-cid] :or {prev-cid ""}}]
  {":tx/id"     tx-id
   ":tx/as-of"  as-of
   ":tx/prev"   prev-cid
   ":tx/cid"    (tx-cid datoms prev-cid)
   ":tx/count"  (count datoms)
   ":tx/datoms" datoms})

;; ── EDN value rendering (mirrors _edn_val) ───────────────────────────────────
(defn- json-dumps-str
  "json.dumps(s, ensure_ascii=False) — a double-quoted, escaped JSON string."
  [^String s]
  (str "\"" (json-escape-utf8 s) "\""))

(defn- edn-val ^String [v]
  (cond
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)        ; repr(int) == str(int)
    (number? v)  (str v)        ; repr(float): Clojure str on a Double == CPython repr for our magnitudes
    (string? v)  (if (str/starts-with? v ":") v (json-dumps-str v))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (json-dumps-str (str v))))

(defn- tx-to-edn ^String [tx]
  (let [datoms (str/join " " (map (fn [d] (str "[" (str/join " " (map edn-val d)) "]"))
                                  (get tx ":tx/datoms")))]
    (str "{:tx/id " (get tx ":tx/id")
         " :tx/as-of " (get tx ":tx/as-of")
         " :tx/prev " (json-dumps-str (get tx ":tx/prev"))
         " :tx/cid " (json-dumps-str (get tx ":tx/cid"))
         " :tx/count " (get tx ":tx/count")
         " :tx/datoms [" datoms "]}")))

(def ^:private log-header
  (str ";; sukashi kotoba Datom log — append-only EAVT transactions "
       "(content-addressed DAG). Observatory only; fraud signals are "
       ":synthesized + non-adjudicating. DO NOT hand-edit. ADR-2606071600.\n"))

#?(:clj
   (defn append-tx
     "Append ONE transaction to the append-only log (never rewrites). Returns the tx CID."
     [tx log-path]
     (let [f (io/file (str log-path))]
       (when-let [parent (.getParentFile f)] (.mkdirs parent))
       (when-not (.exists f) (spit f log-header))
       (spit f (str (tx-to-edn tx) "\n") :append true)
       (get tx ":tx/cid"))))

#?(:clj
   (defn read-log
     "Read the append-only log → vector of tx maps (uses the shared sukashi-edn reader).
     Skips blank + ;-comment lines."
     [log-path]
     (let [f (io/file (str log-path))]
       (if-not (.exists f)
         []
         (->> (str/split-lines (slurp f))
              (map str/trim)
              (remove (fn [l] (or (str/blank? l) (str/starts-with? l ";"))))
              (mapv edn/read-edn))))))

#?(:clj
   (defn head-cid
     "The CID of the last tx in the log (\"\" if empty)."
     [log-path]
     (let [txs (read-log log-path)]
       (if (seq txs) (get (last txs) ":tx/cid") ""))))

#?(:clj
   (defn verify-chain
     "Recompute every CID from its datoms + prev; verify the DAG is intact. {ok length broken-at}."
     [log-path]
     (let [txs (read-log log-path)]
       (loop [i 0, prev "", ts txs]
         (if (empty? ts)
           {"ok" true "length" (count txs) "broken_at" -1}
           (let [tx (first ts)
                 expect (tx-cid (get tx ":tx/datoms" []) prev)]
             (if (or (not= (get tx ":tx/cid") expect) (not= (get tx ":tx/prev") prev))
               {"ok" false "length" (count txs) "broken_at" i}
               (recur (inc i) (get tx ":tx/cid") (rest ts)))))))))
