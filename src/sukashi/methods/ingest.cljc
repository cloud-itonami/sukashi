(ns sukashi.methods.ingest
  "ingest.py — sukashi ad-tech supply-chain ingest bridge (R1 scaffold; offline default).
  1:1 Clojure port of `methods/ingest.py`. ADR-2606071600.

  Normalizes the PUBLIC ad-tech web-standard files (ads.txt / app-ads.txt / sellers.json /
  RDAP-WHOIS) into the kotoba EAVT ad-supply-chain vocabulary and dedup-merges with the curated
  seed (seed wins). The real parsers are genuinely functional; what is GATED is LIVE FETCH at web
  scale (G7 Council + operator: SUKASHI_OPERATOR_GATE=1). Default offline run bridges a local
  --in file and re-emits the merged graph.

  sukashi is an OBSERVATORY (G2): fetch is observational only (GET/HEAD of a PUBLIC file). WHOIS
  keeps the registrant ORG only — personal-registrant fields are dropped (G9).

  House style: pure parsers (parse-ads-txt / parse-sellers-json / bridge-whois / emit); host/file/
  JSON I/O only behind #?(:clj …). Keyword-strings kept verbatim; map order preserved to mirror
  Python dict insertion order. Re-uses the actor's own sukashi-edn (load-edn + edn-str)."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]
            #?(:clj [clojure.java.io :as io])))

;; Documented full-web endpoints — NOT fetched unless the operator gate is set.
(def sources
  {"adstxt" "https://<publisher>/ads.txt (IAB Tech Lab ads.txt 1.1)"
   "appads" "https://<developer-domain>/app-ads.txt (IAB Tech Lab app-ads.txt; pass --app <bundle>)"
   "sellersjson" "https://<exchange>/sellers.json (IAB Tech Lab sellers.json 1.0)"
   "whois" "RDAP (https://rdap.org/domain/<d>) / WHOIS — registrant ORG only (G9 PII guard)"})

;; WHOIS/RDAP fields that may carry a natural person — never ingested (G9).
(def pii-drop ["registrant_name" "registrantName" "name" "email" "phone" "street" "address"])

(def id-keys [":adtech/id" ":adauth.edge/id" ":adcreative/id"
              ":addelivery.edge/id" ":adfraud.signal/id"])

(defn- slugify
  "re.sub(r'[^a-z0-9]+', '-', s.lower()).strip('-')."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+" "")
      (str/replace #"-+$" "")))

(defn seller-id-from-domain
  "_seller_id_from_domain: 'adtech.ssp.' + slug(domain)."
  [domain]
  (str "adtech.ssp." (slugify domain)))

(defn parse-ads-txt
  "Parse an ads.txt / app-ads.txt body → [sellers edges]. sellers is an ordered map keyed by
  seller-id (first-touch wins, mirroring dict.setdefault); edges is a vector in document order.
  Sourcing is :authoritative. `app` (optional) marks an app-ads.txt body with the store bundle id."
  ([text publisher-id] (parse-ads-txt text publisher-id nil))
  ([text publisher-id app]
   (loop [lines (str/split-lines text)
          sellers (array-map)
          edges []]
     (if (empty? lines)
       [sellers edges]
       (let [raw (first lines)
             line (-> (first (str/split raw #"#" 2)) (str/trim))
             head (first (str/split line #","))]
         (if (or (str/blank? line) (str/includes? head "="))
           (recur (rest lines) sellers edges)
           (let [parts (mapv str/trim (str/split line #","))]
             (if (< (count parts) 3)
               (recur (rest lines) sellers edges)
               (let [domain (nth parts 0)
                     account (nth parts 1)
                     rel (str/lower-case (nth parts 2))
                     cert (when (> (count parts) 3) (nth parts 3))
                     seller-id (seller-id-from-domain domain)
                     sellers (if (contains? sellers seller-id)
                               sellers
                               (assoc sellers seller-id
                                      {":adtech/id" seller-id ":adtech/name" domain
                                       ":adtech/role" ":ssp" ":adtech/domain" domain
                                       ":adtech/sourcing" ":authoritative"}))
                     eid (cond-> (str "adauth." publisher-id "->" domain ":" account ":" rel)
                           app (str "@" app))
                     edge (cond-> {":adauth.edge/id" eid
                                   ":adauth.edge/publisher" publisher-id
                                   ":adauth.edge/seller" seller-id
                                   ":adauth.edge/account-id" account
                                   ":adauth.edge/relationship" (str ":" (if (= rel "direct") "direct" "reseller"))
                                   ":adauth.edge/declared" true
                                   ":adauth.edge/confirmed" false
                                   ":adauth.edge/sourcing" ":authoritative"}
                            cert (assoc ":adauth.edge/cert-authority" cert)
                            app (assoc ":adauth.edge/app" app))]
                 (recur (rest lines) sellers (conj edges edge)))))))))))

(defn- truthy? [v] (not (or (nil? v) (false? v) (= v "") (= v 0))))

(defn parse-sellers-json
  "Parse a sellers.json object (a map with a 'sellers' list) → ordered map of :adtech seller dicts.
  Confidential sellers carry no name/domain (G9)."
  [obj]
  (reduce
   (fn [out s]
     (let [sid (str/trim (str (get s "seller_id" "")))]
       (if (str/blank? sid)
         out
         (let [dom (get s "domain" "")
               aid (str "adtech.ssp." (slugify (if (truthy? dom) dom sid)))
               rec (cond-> {":adtech/id" aid ":adtech/role" ":ssp"
                            ":adtech/seller-id" sid ":adtech/sourcing" ":authoritative"}
                     (not (get s "is_confidential"))
                     (as-> r (cond-> r
                               (truthy? (get s "name")) (assoc ":adtech/name" (get s "name"))
                               (truthy? dom) (assoc ":adtech/domain" dom))))
               st (str/lower-case (str (get s "seller_type" "")))
               rec (if (contains? #{"publisher" "intermediary" "both"} st)
                     (assoc rec ":adtech/seller-type" (str ":" st))
                     rec)]
           (assoc out aid rec)))))
   (array-map)
   (get obj "sellers" [])))

(defn bridge-whois
  "Map RDAP/WHOIS JSON records → :addelivery.edge dicts (registrant ORG only; G9 drops PII)."
  [records]
  (reduce
   (fn [out r]
     (let [domain (or (get r "domain") (get r "ldhName"))]
       (if-not domain
         out
         (let [org0 (or (get r "registrant_org") (get r "org") (get r "registrantOrganization"))
               org (if (and (not (truthy? org0)) (some #(contains? r %) pii-drop)) nil org0)
               d (cond-> {":addelivery.edge/id" (str "deliv.whois." domain)
                          ":addelivery.edge/landing-domain" domain
                          ":addelivery.edge/sourcing" ":authoritative"}
                   (truthy? org) (assoc ":addelivery.edge/whois-org" org)
                   (truthy? (get r "registrar")) (assoc ":addelivery.edge/registrar" (get r "registrar")))]
           (conj out d)))))
   []
   records))

(defn emit
  "Render one entity dict as an EDN map literal (mirrors emit())."
  [d]
  (str "{" (str/join " "
                     (map (fn [k]
                            (let [v (get d k)]
                              (cond
                                (boolean? v) (str k " " (if v "true" "false"))
                                (number? v) (str k " " v)
                                (and (string? v) (str/starts-with? v ":")) (str k " " v)
                                :else (str k " " (edn/edn-str v)))))
                          (keys d)))
       "}"))

(defn entity-id
  "First present id key's value (mirrors the next(...) id lookup)."
  [d]
  (some (fn [k] (when (contains? d k) (get d k))) id-keys))

;; ── host/file/JSON edge ───────────────────────────────────────────────────────
#?(:clj
   (defn- read-json [path]
     ((requiring-resolve 'cheshire.core/parse-string) (slurp (str path)))))

#?(:clj
   (defn -main
     "CLI entry: bridge a local --in file (offline default) → merged graph EDN. Live fetch is G7
     gated (SUKASHI_OPERATOR_GATE=1)."
     [& argv]
     (let [argv (vec argv)
           getenv (fn [k] (System/getenv k))
           operator-gate (= "1" (getenv "SUKASHI_OPERATOR_GATE"))
           arg-after (fn [flag dflt] (let [i (.indexOf argv flag)] (if (>= i 0) (nth argv (inc i)) dflt)))
           here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
                    (io/file "20-actors" "sukashi"))
           seed (io/file here "data" "seed-ad-supply-chain.kotoba.edn")
           outp (io/file (if (some #{"--out"} argv)
                           (arg-after "--out" nil)
                           (str (io/file here "data" "ad-supply-chain.merged.kotoba.edn"))))
           source (when (some #{"--source"} argv) (arg-after "--source" nil))
           infile (when (some #{"--in"} argv) (arg-after "--in" nil))
           publisher (if (some #{"--publisher"} argv) (arg-after "--publisher" nil) "adtech.publisher.ingested")
           app (when (some #{"--app"} argv) (arg-after "--app" nil))
           bridged
           (cond
             (and source (contains? sources source) (not infile) (not operator-gate))
             (do (println (str "sukashi.ingest: source '" source "' = " (get sources source)))
                 (println (str "  → G7 GATED: live full-web fetch requires SUKASHI_OPERATOR_GATE=1 (Council). "
                               "Provide a local --in file to bridge offline; emitting seed only."))
                 [])
             (and (contains? #{"adstxt" "appads"} source) infile)
             (let [[sellers edges] (parse-ads-txt (slurp infile) publisher app)
                   kind (if (or (= source "appads") app) "app-ads.txt" "ads.txt")]
               (println (str "sukashi.ingest: bridged " (count sellers) " sellers + " (count edges) " "
                             kind " auth edges from " infile (if app (str " [app=" app "]") "") " (:authoritative)"))
               (into (vec (vals sellers)) edges))
             (and (= source "sellersjson") infile)
             (let [sellers (parse-sellers-json (read-json infile))]
               (println (str "sukashi.ingest: bridged " (count sellers) " sellers.json entries from "
                             infile " (:authoritative)"))
               (vec (vals sellers)))
             (and (= source "whois") infile)
             (let [obj (read-json infile)
                   recs (cond (vector? obj) obj
                              (map? obj) (get obj "records" [obj])
                              :else [obj])
                   out (bridge-whois recs)]
               (println (str "sukashi.ingest: bridged " (count out) " WHOIS records from " infile
                             " (registrant ORG only, PII dropped — G9)"))
               out)
             source
             (do (println (str "sukashi.ingest: unknown source '" source "'. Known: "
                               (str/join ", " (keys sources)))) [])
             :else [])
           seed-rows (edn/load-edn seed)
           seed-ids (reduce (fn [s r]
                              (if (map? r)
                                (reduce (fn [s k] (if (contains? r k) (conj s (get r k)) s)) s id-keys)
                                s))
                            #{} seed-rows)
           extra (reduce (fn [out d]
                           (let [eid (entity-id d)]
                             (if (and eid (not (contains? seed-ids eid))) (conj out d) out)))
                         [] bridged)
           seed-text (str/trimr (slurp seed))]
       (.mkdirs (.getParentFile outp))
       (if (seq extra)
         (let [body (str/trimr (subs seed-text 0 (str/last-index-of seed-text "]")))
               lines (str/join "\n" (map #(str " " (emit %)) extra))]
           (spit outp (str body "\n ;; ── bridged (ingest) ──\n" lines "\n]\n")))
         (spit outp (str seed-text "\n")))
       (println (str "sukashi.ingest: merged graph → " outp " ("
                     (count seed-ids) " seed ids + " (count extra) " bridged)")))))
