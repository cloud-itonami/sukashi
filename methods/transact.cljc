(ns sukashi.methods.transact
  "transact.py — sukashi kotoba Datomic transact bridge (ADR-2606071600; mirrors kabuto / ipaddress).
  1:1 Clojure port of `methods/transact.py`.

  Pushes the ad-tech supply-chain graph into a running kotoba node's Datom log via
  POST /xrpc/com.etzhayyim.apps.kotoba.datomic.transact, emitting datomic list-form datoms
  `[:db/add E A V]` (E = the entity's stable id). The merged graph
  (data/ad-supply-chain.merged.kotoba.edn else the seed) is the source; schema is installed
  from ad-supply-chain-ontology.kotoba.edn (:db/doc dropped — kotoba EDN reader rejects '|').
  Data is BATCHED to stay under the server's tx_edn size cap.

  AUTH (ADR-2605231525, no platform-held key): a write needs EITHER an operator JWT (KOTOBA_TOKEN,
  sub == operator_did) OR a CACAO authorising `datom:transact`. Without either it is a DRY RUN that
  prints the tx summary.

  CONSTITUTIONAL (sukashi G2/G4): public ad-tech transparency facts only; a fraud-protection +
  transparency map, NEVER a target-list, NEVER an ad-buying tool. Live write is G7-gated.

  House style: pure render fns; host/file/network I/O only behind #?(:clj …). Re-uses the actor's
  own sukashi-edn (load-edn + edn-str)."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]
            [sukashi.methods.kotoba :as kotoba]
            #?(:clj [clojure.java.io :as io])))

(def nsid-transact "com.etzhayyim.apps.kotoba.datomic.transact")
(def id-keys [":adtech/id" ":adauth.edge/id" ":adcreative/id"
              ":addelivery.edge/id" ":adfraud.signal/id"])
(def batch 3500)  ; datoms per tx (keeps tx_edn well under the 1 MiB server cap)

(defn edn-val
  "Mirror of Python edn_val: bool→true/false; number→str; vector→[…]; keyword-string kept
  verbatim; other string → edn-escaped quoted literal; else str→edn-escaped."
  [x]
  (cond
    (boolean? x) (if x "true" "false")
    (number? x)  (str x)
    (vector? x)  (str "[" (str/join " " (map edn-val x)) "]")
    (and (string? x) (str/starts-with? x ":")) x
    (string? x)  (edn/edn-str x)
    :else (edn/edn-str (str x))))

(defn rows-to-datoms
  "Flatten the graph rows into `[:db/add E A V]` strings. E = the first present id key; lists fan
  out. Map key iteration follows the EDN parse (insertion) order — Python dict order."
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
                  (reduce (fn [out item]
                            (conj out (str "[:db/add " (edn/edn-str e) " " k " " (edn-val item) "]")))
                          out items))))
            out
            (kotoba/row-keys r))))))
   []
   rows))

(defn schema-datoms
  "Render the ontology :attributes as EDN map literals, dropping :db/doc (mirrors schema_datoms)."
  [onto]
  (let [attrs (if (map? onto) (get onto ":attributes" []) [])]
    (mapv (fn [a]
            (str "{" (str/join " " (for [k (keys a) :when (not= k ":db/doc")]
                                     (str k " " (edn-val (get a k))))) "}"))
          attrs)))

(defn tx-edn
  "Wrap a datom-string seq into the tx_edn vector form (mirrors _tx_edn)."
  [datoms]
  (str "[\n " (str/join "\n " datoms) "\n]"))

(defn batches
  "Chunk datoms into BATCH-sized vectors; always at least one (possibly empty) batch."
  [datoms]
  (let [chunks (mapv vec (partition-all batch datoms))]
    (if (seq chunks) chunks [[]])))

;; ── host/network/file edge ───────────────────────────────────────────────────
#?(:clj
   (defn- post
     "POST a JSON body to url (+ optional Bearer token). Returns [status parsed-or-raw-map].
     Network edge only. Requires a JSON codec on the classpath via the slurp-style reader is not
     available in bb; uses java HttpURLConnection + the cheshire codec used elsewhere in the suite."
     [url body token]
     (let [conn (doto ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))
                  (.setRequestMethod "POST")
                  (.setDoOutput true)
                  (.setConnectTimeout 60000)
                  (.setReadTimeout 60000)
                  (.setRequestProperty "Content-Type" "application/json"))
           _ (when token (.setRequestProperty conn "Authorization" (str "Bearer " token)))
           payload ((requiring-resolve 'cheshire.core/generate-string) body)]
       (with-open [os (.getOutputStream conn)]
         (.write os (.getBytes ^String payload "UTF-8")))
       (let [status (.getResponseCode conn)
             stream (if (>= status 400) (.getErrorStream conn) (.getInputStream conn))
             txt (if stream (slurp stream) "")]
         (try
           [status ((requiring-resolve 'cheshire.core/parse-string) (if (str/blank? txt) "{}" txt))]
           (catch Exception _ [status {"error" txt}]))))))

#?(:clj
   (defn -main
     "CLI entry (dry-run by default; live write needs --graph + KOTOBA_TOKEN/CACAO; G7-gated)."
     [& argv]
     (let [argv (vec argv)
           getenv (fn [k] (System/getenv k))
           arg-after (fn [flag dflt] (let [i (.indexOf argv flag)] (if (>= i 0) (nth argv (inc i)) dflt)))
           here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
                    (io/file "20-actors" "sukashi"))
           root (-> here .getParentFile .getParentFile)
           schema-file (io/file root "00-contracts" "schemas" "ad-supply-chain-ontology.kotoba.edn")
           url (or (getenv "KOTOBA_URL") "http://127.0.0.1:8077")
           graph (if (some #{"--graph"} argv) (arg-after "--graph" nil) (getenv "SUKASHI_GRAPH_CID"))
           cacao (if (some #{"--cacao"} argv) (arg-after "--cacao" nil) (getenv "KOTOBA_CACAO_B64"))
           merged0 (io/file here "data" "ad-supply-chain.merged.kotoba.edn")
           merged (if (.exists merged0) merged0 (io/file here "data" "seed-ad-supply-chain.kotoba.edn"))
           schema (schema-datoms (kotoba/load-edn-ordered schema-file))
           data (rows-to-datoms (kotoba/load-edn-ordered merged))
           bs (batches data)]
       (println (str "sukashi.transact: graph=" (or graph "(unset)")))
       (println (str "  schema tx: " (count schema) " attrs  ·  data: " (count data)
                     " datoms in " (count bs) " batch(es)"))
       (let [token (getenv "KOTOBA_TOKEN")
             live (and (boolean graph) (or (boolean cacao) (boolean token))
                       (not (some #{"--dry-run"} argv)))]
         (if-not live
           (do (println "  DRY RUN — provide --graph <CID> + KOTOBA_TOKEN operator JWT (or --cacao) to write.")
               0)
           (let [send (fn [name datoms fatal]
                        (let [body (cond-> {"graph" graph "tx_edn" (tx-edn datoms)}
                                     cacao (assoc "cacao_b64" cacao))
                              [st resp] (post (str url "/xrpc/" nsid-transact) body token)]
                          (if (not= st 200)
                            (let [msg (str "!! transact " name " → " st ": " (subs (str resp) 0 (min 160 (count (str resp)))))]
                              (if fatal
                                (do (binding [*out* *err*] (println msg)) false)
                                (do (println (str msg "  (best-effort; continuing)")) true)))
                            (do (println (str "  ok " name ": datom_count=" (get resp "datom_count" "?")
                                              " tx_cid=" (let [c (str (get resp "tx_cid" "?"))]
                                                           (subs c 0 (min 20 (count c)))) "…"))
                                true))))]
             (if-not (send "schema" schema false)
               1
               (loop [i 1, bsv bs, total 0]
                 (if (empty? bsv)
                   (do (println (str "  ✓ " total " ad-supply-chain datoms committed to " graph)) 0)
                   (if-not (send (str "data[" i "/" (count bs) "]") (first bsv) true)
                     1
                     (recur (inc i) (rest bsv) (+ total (count (first bsv))))))))))))))
