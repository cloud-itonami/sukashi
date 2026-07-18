(ns sukashi.methods.sukashi-edn
  "sukashi 透かし — shared minimal EDN reader + datom classifier.
  1:1 Clojure port of `methods/sukashi_edn.py` (ADR-2606071600).

  Same subset reader as the kabuto/watatsuna/tsumugi/inochi readers (vectors [],
  maps {}, :keyword, \"string\", number, bool, nil). Keywords are kept as
  \":ns/name\" strings (NOT clojure keywords) so the whole pipeline stays
  string-keyed, byte-for-byte the same as the Python port.

  House style: pure fns; file I/O only at #?(:clj) edges; closed reader subset."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

;; ── minimal EDN reader (subset) ──────────────────────────────────────────────
;; _TOK = re.compile(r'[\s,]+|;[^\n]*|(\[|\]|\{|\}|"(?:\\.|[^"\\])*"|[^\s,\[\]{}]+)')
(def ^:private tok-re
  #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn tokens
  "Lazy seq of significant tokens (group 1 of each tok-re match that captured)."
  [s]
  (let [m (re-matcher tok-re s)]
    ((fn step []
       (lazy-seq
        (when (.find m)
          (let [t (.group m 1)]
            (if (nil? t)
              (step)
              (cons t (step))))))))))

(defn atom-of
  "Port of _atom: \"…\" → unescaped string; true/false/nil → bool/nil; \":…\" kept as
  string; int → long; else float (double); else raw string."
  [t]
  (cond
    (str/starts-with? t "\"")
    (-> (subs t 1 (dec (count t)))
        (str/replace "\\\"" "\"")
        (str/replace "\\\\" "\\"))
    (= t "true") true
    (= t "false") false
    (= t "nil") nil
    (str/starts-with? t ":") t
    :else
    (let [as-long (try (Long/parseLong t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
      (if (not= as-long ::nan)
        as-long
        (let [as-dbl (try (Double/parseDouble t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
          (if (not= as-dbl ::nan) as-dbl t))))))

(def ^:private end-marker ::end)

(defn- parse-step
  "Consume one form from the token vector at index i. Returns [value next-i] or
  [end-marker next-i] when a closing ] or } is hit (matching _parse's _END sentinel)."
  [toks i]
  (let [t (nth toks i)
        i (inc i)]
    (cond
      (= t "[")
      (loop [i i, out []]
        (let [[x i] (parse-step toks i)]
          (if (= x end-marker)
            [out i]
            (recur i (conj out x)))))

      (= t "{")
      (loop [i i, out {}]
        (let [[k i] (parse-step toks i)]
          (if (= k end-marker)
            [out i]
            (let [[v i] (parse-step toks i)]
              (recur i (assoc out k v))))))

      (or (= t "]") (= t "}"))
      [end-marker i]

      :else
      [(atom-of t) i])))

(defn read-edn
  "Parse the first top-level form from EDN text (matches _parse(_tokens(text)))."
  [text]
  (let [toks (vec (tokens text))]
    (first (parse-step toks 0))))

#?(:clj
   (defn load-edn
     "Read + parse an EDN file (file I/O only at this edge). Mirrors load_edn(path)."
     [path]
     (read-edn (slurp (str path)))))

;; ── classify the flat datom vector into entity buckets ───────────────────────
(defn- adtech-assoc
  "assoc id→r into the adtech map, recording first-touch insertion order in ::order
  metadata (mirroring Python dict order; array-map order is lost beyond 8 keys)."
  [m id r]
  (let [had? (contains? m id)
        m' (assoc m id r)]
    (if had?
      (with-meta m' (meta m))
      (with-meta m' (update (meta m) ::order (fnil conj []) id)))))

(defn adtech-vals
  "adtech.values() in insertion order (::order metadata if present, else vals)."
  [adtech]
  (let [order (::order (meta adtech))]
    (if order (map #(get adtech %) order) (vals adtech))))

(defn classify
  "Return {:adtech adtech-by-id :auth [..] :creatives [..] :delivery [..] :fraud [..]}.

  adtech is keyed by :adtech/id (carrying ::order metadata = first-touch insertion order
  to mirror Python dict order); the rest are vectors in document order. Mirrors
  classify(rows) — non-maps are skipped, the elif chain dispatches on the first id key."
  [rows]
  (reduce
   (fn [acc r]
     (cond
       (not (map? r)) acc
       (contains? r ":adtech/id") (update acc :adtech adtech-assoc (get r ":adtech/id") r)
       (contains? r ":adauth.edge/id") (update acc :auth conj r)
       (contains? r ":adcreative/id") (update acc :creatives conj r)
       (contains? r ":addelivery.edge/id") (update acc :delivery conj r)
       (contains? r ":adfraud.signal/id") (update acc :fraud conj r)
       :else acc))
   {:adtech (with-meta {} {::order []}) :auth [] :creatives [] :delivery [] :fraud []}
   rows))

(defn edn-str
  "EDN-escape a string into a quoted EDN string literal. Mirrors edn_str(s)."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))
