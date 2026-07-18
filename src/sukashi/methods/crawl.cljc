(ns sukashi.methods.crawl
  "sukashi 透かし — worldwide ad-supply-chain CRAWLER (acquisition leg; ADR-2606071600).

  Clojure/bb port of `methods/crawl.py`. Keeps crawl.py (additive port).

  Walks a frontier of real publisher / SSP / exchange domains and, when live (G7 gate),
  fetches their PUBLIC IAB files (/ads.txt, /app-ads.txt, /sellers.json, public RDAP).
  The parsing leg delegates to sukashi.methods.ingest (parse-ads-txt / parse-sellers-json /
  bridge-whois), which is the Clojure port of ingest.py.

  CONSTITUTIONAL INVARIANTS (enforced here):
    G1/G2 — Observatory of PUBLIC files only. The URL builder (`urls-for`) emits ONLY the
             four public IAB paths; no other URL shape is representable.
    G7     — Live network crawl requires SUKASHI_OPERATOR_GATE=1 (env) or gate=true, or an
             injected fetcher. Without it, `crawl` is a DRY-RUN: returns the frontier plan
             with zero network touches.
    G12    — No detection-evasion. The default fetcher uses an honest identifying UA, is
             GET-only, and holds no anti-bot bypass capability.
    G9     — RDAP keeps registrant ORG only (bridge-whois in ingest drops personal fields).

  The `fetcher` parameter is injectable (tests pass a stub; default = babashka.http-client).
  NO network/subprocess I/O at load/require time."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]
            [sukashi.methods.ingest :as ingest]
            #?(:clj [clojure.java.io :as io])))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ua
  "etzhayyim-sukashi/observatory (+https://etzhayyim.com; public ads.txt/sellers.json fraud-protection observatory; GET-only; respects robots.txt)")

;; The ONLY URL shapes sukashi will construct — all PUBLIC by IAB / RDAP spec (G1/G2).
(def kinds
  {"ads.txt"      "https://{d}/ads.txt"
   "app-ads.txt"  "https://{d}/app-ads.txt"
   "sellers.json" "https://{d}/sellers.json"
   "rdap"         "https://rdap.org/domain/{d}"})

;; ── Default fetcher (declared first so crawl can reference it) ───────────────

#?(:clj
   (defn default-fetcher
     "Respectful GET of a PUBLIC file → {:status int :body str}. GET-only, honest UA (G12).
     Uses babashka.http-client (available in bb). Only invoked when live (G7 gate)."
     [url]
     (try
       (let [resp (babashka.http-client/get url
                                            {:headers {"User-Agent" ua
                                                       "Accept"     "text/plain, application/json"}
                                             :as      :string
                                             :throw   false})]
         {:status (:status resp) :body (or (:body resp) "")})
       (catch Exception _
         {:status 0 :body ""}))))

;; ── Pure helpers ─────────────────────────────────────────────────────────────

(defn urls-for
  "The (kind → url) plan for one domain. Only the four public paths are constructible (G1/G2)."
  [domain]
  (reduce-kv (fn [m k tmpl] (assoc m k (str/replace tmpl "{d}" domain)))
             {} kinds))

(defn kinds-for-role
  "A publisher serves ads.txt; an exchange/ssp serves sellers.json; all have rdap."
  [role]
  (let [r (-> (or role "") (str/replace #"^:" ""))]
    (cond
      (#{"exchange" "ssp" "ad-exchange"} r) ["sellers.json" "rdap"]
      (#{"app-publisher" "ctv"} r)          ["app-ads.txt" "rdap"]
      :else                                  ["ads.txt" "rdap"])))

(defn fresh?
  "True if the cached file exists, ttl > 0, and its mtime is within ttl seconds of `now`."
  [path now ttl]
  #?(:clj  (and (pos? ttl) (.exists (io/file path))
                (< (- now (/ (.lastModified (io/file path)) 1000.0)) ttl))
     :cljs false))

(defn parse-fetched
  "Dispatch fetched text to the matching real parser → ad-supply-chain rows (pure, no IO).
  Delegates to sukashi.methods.ingest for the actual parsing."
  [kind text domain]
  (let [pub-id (str "adtech.publisher." (str/replace domain "." "-"))]
    (case kind
      ("ads.txt" "app-ads.txt")
      (let [app (when (= kind "app-ads.txt") domain)
            [sellers edges] (ingest/parse-ads-txt text pub-id app)]
        (into (vec (vals sellers)) edges))

      "sellers.json"
      #?(:clj
         (try
           (let [parsed ((requiring-resolve 'cheshire.core/parse-string) text)]
             (vec (vals (ingest/parse-sellers-json parsed))))
           (catch Exception _ []))
         :cljs [])

      "rdap"
      #?(:clj
         (try
           (let [obj ((requiring-resolve 'cheshire.core/parse-string) text)
                 obj (if (contains? obj "domain") obj (assoc obj "domain" domain))]
             (ingest/bridge-whois [obj]))
           (catch Exception _ []))
         :cljs [])

      [])))

(defn load-frontier
  "Read the frontier EDN: a vector of {:domain :role :sourcing} maps from a file.
  Returns [] if the file does not exist."
  [path]
  #?(:clj
     (let [f (io/file path)]
       (if-not (.exists f)
         []
         (let [rows (edn/load-edn f)]
           (filterv #(and (map? %) (contains? % ":domain")) rows))))
     :cljs []))

;; ── Core crawl ───────────────────────────────────────────────────────────────

(defn crawl
  "Walk the frontier and fetch each domain's public files. DRY-RUN unless live.

  live = SUKASHI_OPERATOR_GATE=1 (env) OR gate=true OR an injected fetcher.
  Returns {:mode :planned :fetched :skipped :rows}.

  Options:
    :frontier      seq of {:domain :role :sourcing} maps (required)
    :fetcher       fn[url] → {:status int :body str}  (only used when live; default = default-fetcher)
    :gate          override the env check (boolean or nil → read env)
    :live-dir      path string for cached files (only used when live)
    :max-domains   truncate frontier to this many domains
    :now           current time in epoch seconds (default 0 = no freshness check)
    :ttl           cache TTL in seconds (default 0 = never fresh)"
  [{:keys [frontier fetcher gate live-dir max-domains now ttl]
    :or   {now 0.0 ttl 0.0}}]
  (let [is-gate #?(:clj (if (nil? gate)
                          (= "1" (System/getenv "SUKASHI_OPERATOR_GATE"))
                          (boolean gate))
                   :cljs (boolean gate))
        injected  (some? fetcher)
        live?     (or is-gate injected)
        rows      (cond->> (vec (or frontier []))
                    max-domains (take max-domains)
                    true        vec)]

    (if-not live?
      ;; DRY-RUN: return the plan, zero network
      (let [planned (vec (for [r rows
                               k (kinds-for-role (get r ":role"))]
                           {:domain (get r ":domain")
                            :kind   k
                            :url    (get (urls-for (get r ":domain")) k)}))]
        {:mode "dry-run" :planned planned :fetched [] :skipped [] :rows []})

      ;; LIVE: fetch + parse
      #?(:clj
         (let [f        (or fetcher default-fetcher)
               ld       (or live-dir "data/live")
               _        (.mkdirs (io/file ld))
               fetched  (atom [])
               skipped  (atom [])
               out-rows (atom [])]
           (doseq [r    rows
                   :let [domain (get r ":domain")]
                   k    (kinds-for-role (get r ":role"))]
             (let [dest-path (str ld "/" domain "." k)]
               (if (fresh? dest-path now ttl)
                 (swap! skipped conj {:domain domain :kind k :reason "fresh"})
                 (let [url              (get (urls-for domain) k)
                       {:keys [status body]} (f url)]
                   (if (or (not= status 200) (str/blank? body))
                     (swap! skipped conj {:domain domain :kind k :status status})
                     (do
                       (spit (io/file dest-path) body :encoding "UTF-8")
                       (swap! fetched  conj {:domain domain :kind k :bytes (count body)})
                       (swap! out-rows into (parse-fetched k body domain))))))))
           {:mode "live" :planned [] :fetched @fetched :skipped @skipped :rows @out-rows})
         :cljs {:mode "live" :planned [] :fetched [] :skipped [] :rows []}))))

(defn merge-live
  "Parse everything already fetched under live-dir → rows (offline merge step)."
  [live-dir]
  #?(:clj
     (let [dir (io/file live-dir)]
       (if-not (.exists dir)
         []
         (vec
           (for [f      (sort-by #(.getName ^java.io.File %) (.listFiles dir))
                 :when  (.isFile ^java.io.File f)
                 :let   [n    (.getName ^java.io.File f)
                         kind (some (fn [k] (when (str/ends-with? n (str "." k)) k))
                                    (keys kinds))
                         domain (when kind (subs n 0 (- (count n) (inc (count kind)))))]
                 :when  (and kind domain)
                 row    (parse-fetched kind (slurp f :encoding "UTF-8") domain)]
             row))))
     :cljs []))
