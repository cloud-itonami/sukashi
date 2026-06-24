(ns sukashi.tests.test-sukashi
  "sukashi 透かし — invariant + analyzer tests (ADR-2606071600).
  1:1 Clojure port of tests/test_sukashi.py.

  Pins the actor's STRUCTURAL charter invariants in code (the never-an-ad-network /
  no-targeting / no-detection-evasion gates are constitutional framing, asserted here as
  the seed/report invariants the Python suite enforces) plus the analyzer's correctness on
  the seed graph + the schema/manifest cross-checks.

  SCOPE NOTE — three Python TestCases exercise sibling modules NOT in this port's unit:
    - TestIngestParsers          → methods/ingest.py        (ads.txt/app-ads.txt/WHOIS parsers)
    - TestAkashiMalakBridge      → methods/fraud_bridge.py  (malak evidence bridge)
    - TestTransactReadiness      → methods/transact.py      (kotoba transact dry-run)
  Those depend on the unported ingest / fraud_bridge / transact modules, so their assertions
  are intentionally deferred here (mirroring the rasen/inochi precedent of porting the pure
  analyzer unit). The autorun tests (methods/test_autorun.py) are likewise deferred. Every
  PURE seed-integrity + analyzer + schema/manifest assertion is ported 1:1."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [clojure.set]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [sukashi.methods.sukashi-edn :as edn]
            [sukashi.methods.analyze :as A]))

(def actor-dir (-> *file* io/file .getParentFile .getParentFile))
(def root (-> actor-dir .getParentFile .getParentFile))
(def schema (io/file root "00-contracts" "schemas" "ad-supply-chain-ontology.kotoba.edn"))
(def seed (io/file actor-dir "data" "seed-ad-supply-chain.kotoba.edn"))
(def manifest (io/file actor-dir "manifest.jsonld"))
(def lex-dir (io/file root "00-contracts" "lexicons" "com" "etzhayyim" "sukashi"))

(defn graph
  "classify(load_edn(SEED)) → {:adtech :auth :creatives :delivery :fraud}."
  []
  (edn/classify (edn/load-edn seed)))

;; ── TestSeedIntegrity ─────────────────────────────────────────────────────

(deftest test-seed-parses-and-classifies
  (let [{:keys [adtech auth fraud]} (graph)]
    (is (>= (count adtech) 20))
    (is (>= (count auth) 6))
    (is (>= (count fraud) 4))))

(deftest test-listed-org-crosslinks-to-org-corp-space
  ;; every :adtech/listed-org must point into org.corp.* and only sit on a real (non-fictional) firm
  (let [{:keys [adtech]} (graph)
        links (->> (edn/adtech-vals adtech)
                   (filter #(contains? % ":adtech/listed-org"))
                   (mapv (fn [e] [(get e ":adtech/id") (get e ":adtech/listed-org")])))]
    (is (>= (count links) 12))
    (doseq [[aid org] links]
      (is (str/starts-with? org "org.corp.") (str aid " listed-org must be org.corp.*"))
      (is (not= (get-in adtech [aid ":adtech/sourcing"]) ":synthesized")
          (str aid ": only real firms carry a listed-org cross-link")))))

(deftest test-seed-coverage-breadth
  ;; Maturity: ≥60 real ad-tech entities spanning the full role taxonomy.
  (let [{:keys [adtech]} (graph)]
    (is (>= (count adtech) 60))
    (let [roles (set (map #(get % ":adtech/role") (edn/adtech-vals adtech)))]
      (doseq [r [":dsp" ":ad-exchange" ":ssp" ":ad-network" ":publisher"
                 ":verification" ":data-broker" ":ad-server" ":cmp" ":advertiser"]]
        (is (contains? roles r) (str "role " r " should be represented in the seed"))))
    ;; the vast majority must be real; fictional ones are few
    (let [synth (filter #(= ":synthesized" (get % ":adtech/sourcing")) (edn/adtech-vals adtech))]
      (is (< (count synth) (quot (count adtech) 5)) "fictional entities must stay a small minority"))))

(deftest test-authorization-graph-density
  ;; Maturity: ≥25 auth edges; the ONLY declared-but-unconfirmed edges are the fictional fraud surfaces.
  (let [{:keys [auth]} (graph)]
    (is (>= (count auth) 25))
    (let [unconfirmed (filter #(and (get % ":adauth.edge/declared")
                                    (not (get % ":adauth.edge/confirmed"))) auth)
          confirmed (filter #(get % ":adauth.edge/confirmed") auth)]
      (is (<= (count unconfirmed) 3))
      (is (>= (count confirmed) 20)))))

(deftest test-every-node-and-edge-has-sourcing
  (let [{:keys [adtech auth creatives delivery fraud]} (graph)]
    (doseq [[bucket k] [[(edn/adtech-vals adtech) ":adtech/sourcing"]
                        [auth ":adauth.edge/sourcing"]
                        [creatives ":adcreative/sourcing"]
                        [delivery ":addelivery.edge/sourcing"]
                        [fraud ":adfraud.signal/sourcing"]]]
      (doseq [r bucket]
        (is (contains? r k) (str "missing " k " on " r))
        (is (contains? #{":authoritative" ":representative" ":synthesized"} (get r k)))))))

(deftest test-fraud-archetype-coverage
  ;; Maturity: ≥12 fraud signals spanning the FULL 11-kind taxonomy, all :synthesized.
  (let [{:keys [fraud]} (graph)]
    (is (>= (count fraud) 12))
    (let [kinds (set (map #(get % ":adfraud.signal/kind") fraud))
          expected #{":unauthorized-reseller" ":domain-spoof" ":sellers-json-mismatch"
                     ":scam-finance" ":fake-endorsement" ":phishing-landing"
                     ":malvertising-redirect" ":counterfeit-goods" ":cloaking"
                     ":typosquat-delivery" ":shared-fraud-infra"}]
      (is (= kinds expected) "every fraud kind in the taxonomy must have an example"))))

(deftest test-g4-every-fraud-signal-is-non-adjudicating-and-routed
  (let [{:keys [fraud]} (graph)]
    (doseq [f fraud]
      (is (true? (get f ":adfraud.signal/non-adjudicating"))
          "G4: fraud signal must carry :non-adjudicating true")
      (is (contains? #{":akashi-malak" ":kurashimori" ":tasuke" ":danjo"}
                     (get f ":adfraud.signal/routed-to"))
          "G4: every fraud signal must be routed to an actor that acts"))))

(deftest test-g5-every-fraud-signal-is-synthesized
  (let [{:keys [fraud]} (graph)]
    (doseq [f fraud]
      (is (contains? #{":synthesized" ":representative"} (get f ":adfraud.signal/sourcing"))
          "G5: sukashi-computed fraud signals are :synthesized (or third-party :representative)"))))

(deftest test-g4-real-firms-carry-no-fraud-signal
  ;; Non-adjudication: a fraud signal's subject must NOT be a :representative real ad-tech firm.
  (let [{:keys [adtech creatives fraud]} (graph)
        cre-by-id (reduce (fn [m c] (assoc m (get c ":adcreative/id") c)) {} creatives)]
    (doseq [f fraud]
      (let [subj (get f ":adfraud.signal/subject")
            entity0 (get adtech subj)
            entity (if (and (nil? entity0) (contains? cre-by-id subj))
                     (get adtech (get-in cre-by-id [subj ":adcreative/advertiser"]))
                     entity0)]
        (when (some? entity)
          (is (= (get entity ":adtech/sourcing") ":synthesized")
              (str "G4: fraud signal must not implicate a real (:representative) firm: " subj)))))))

(deftest test-g9-no-personal-whois-fields-in-seed
  ;; Delivery edges expose registrant ORG only — never a personal-registrant attribute.
  (let [{:keys [delivery]} (graph)
        forbidden [":addelivery.edge/whois-name" ":addelivery.edge/whois-email"
                   ":addelivery.edge/whois-phone" ":addelivery.edge/registrant-person"]]
    (doseq [d delivery]
      (doseq [k forbidden]
        (is (not (contains? d k)) (str "G9: personal WHOIS field " k " must never appear"))))))

(deftest test-fraud-examples-use-reserved-test-domains
  ;; G5 honesty: scam creatives live on RFC-2606 reserved TLDs, never a real domain.
  (let [{:keys [creatives]} (graph)]
    (doseq [c creatives]
      (when (= (get c ":adcreative/sourcing") ":synthesized")
        (let [dom (get c ":adcreative/landing-domain" "")]
          (is (or (str/ends-with? dom ".test") (str/ends-with? dom ".example"))
              (str "synthesized scam creative must use a reserved domain: " dom)))))))

;; ── TestAnalyzer ──────────────────────────────────────────────────────────

(defn analyzed
  "Run analyze over the seed graph (the setUp of TestAnalyzer)."
  []
  (let [{:keys [adtech auth creatives delivery fraud]} (graph)]
    {:g {:adtech adtech :auth auth :creatives creatives :delivery delivery :fraud fraud}
     :a (A/analyze adtech auth creatives delivery fraud)}))

(deftest test-detects-unauthorized-reseller
  ;; Fly-By-Night SSP: 1+ declared edge, unconfirmed → unconfirmed-rate 1.0
  (let [a (:a (analyzed))
        rates (into {} (map (fn [[s _ _ rate]] [s rate]) (get a "unconfirmed_rate")))]
    (is (contains? rates "adtech.ssp.fly-by-night"))
    (is (= (get rates "adtech.ssp.fly-by-night") 1.0))))

(deftest test-detects-account-id-collision-spoof
  ;; pub-1001 claimed by both the legit and the spoofed publisher.
  (let [a (:a (analyzed))
        colls (set (map (fn [[s acct _]] [s acct]) (get a "acct_collisions")))]
    (is (contains? colls ["adtech.ad-exchange.google-adx" "pub-1001"]))))

(deftest test-detects-shared-infra-scam-network
  ;; 3 scam creatives share asn.64666 + registrar + whois-org → one cluster, ≥3 members.
  (let [a (:a (analyzed))]
    (is (>= (count (get a "clusters")) 1))
    (let [top (first (get a "clusters"))]
      (is (>= (:members top) 3))
      (is (= (:asn top) "asn.64666")))))

(deftest test-cluster-multi-signal-corroboration
  ;; The bulletproofhost cluster's 3 creatives carry 3 DISTINCT fraud kinds → corroboration >= 3.
  (let [a (:a (analyzed))
        top (first (get a "clusters"))]
    (is (>= (:corroboration top) 3))
    (is (= (:corroboration top) (count (:kinds top))))
    ;; corroboration weighting makes rank exceed the naive members×conf product.
    (is (> (:rank-score top) (* (:members top) (:conf-sum top))))))

(deftest test-delivery-infra-concentration-ranks-scam-asn-first
  (let [a (:a (analyzed))]
    (is (seq (get a "infra_rank")))
    (is (= (nth (first (get a "infra_rank")) 0) "asn.64666"))))

(deftest test-seller-betweenness-centrality
  ;; google-adx authorized by 3 publishers → betweenness = C(3,2) = 3, highest in the seed.
  (let [a (:a (analyzed))
        btw (into {} (map (fn [[s fan b]] [s [fan b]]) (get a "seller_betweenness")))]
    (is (contains? btw "adtech.ad-exchange.google-adx"))
    (let [[fan b] (get btw "adtech.ad-exchange.google-adx")]
      (is (= b (quot (* fan (- fan 1)) 2))))
    (is (= (nth (first (get a "seller_betweenness")) 0) "adtech.ad-exchange.google-adx"))))

(deftest test-registrar-and-whois-cooccurrence-ranking
  ;; The 3 scam creatives share registrar CheapDomains-Example AND whois-org BulletproofHost-Example.
  (let [a (:a (analyzed))
        reg (into {} (map (fn [[r _ n]] [r n]) (get a "registrar_rank")))
        who (into {} (map (fn [[o _ n]] [o n]) (get a "whois_rank")))]
    (is (= (get reg "CheapDomains-Example") 3))
    (is (= (get who "BulletproofHost-Example") 3))
    ;; whois ranking is fraud-weighted descending
    (let [loads (map (fn [[_ load _]] load) (get a "whois_rank"))]
      (is (= loads (reverse (sort loads)))))))

(deftest test-category-load-surfaces-high-risk-verticals
  (let [a (:a (analyzed))
        cats (set (map (fn [[c _]] (str/replace (str c) #"^:+" "")) (get a "category_rank")))]
    (is (seq (clojure.set/intersection #{"crypto" "finance" "health-supplement"} cats)))))

(deftest test-routing-tally-covers-all-signals
  (let [{:keys [a g]} (analyzed)]
    (is (= (reduce + (vals (get a "routed"))) (count (:fraud g))))))

(deftest test-render-report-is-nonempty-and-flags-non-adjudication
  (let [{:keys [a g]} (analyzed)
        report (A/render-report (:adtech g) (:auth g) (:creatives g) (:delivery g) (:fraud g) a)]
    (is (str/includes? report "does NOT adjudicate"))
    (is (str/includes? report "NOT a target-list"))))

;; ── TestSchemaAndManifest ─────────────────────────────────────────────────

(deftest test-schema-loads-and-has-core-entities
  (let [onto (edn/load-edn schema)
        idents (set (map #(get % ":db/ident") (get onto ":attributes")))]
    (doseq [core [":adtech/id" ":adauth.edge/id" ":adcreative/id"
                  ":addelivery.edge/id" ":adfraud.signal/id"
                  ":adfraud.signal/non-adjudicating"]]
      (is (contains? idents core)))))

(deftest test-manifest-declares-13-gates-and-matches-lexicons
  (let [m (json/parse-string (slurp manifest))]
    (is (= (get m "status") "R0-design-only"))
    (is (= (get m "tier") "B"))
    (is (>= (count (get m "gates")) 13))
    ;; G2 must assert observatory-not-network (the Charter 広告排除 invariant)
    (is (str/includes? (str/lower-case (get-in m ["gates" "G2"])) "observatory"))
    (when (.exists lex-dir)
      (let [on-disk (set (map (fn [f] (str/replace (.getName f) #"\.json$" ""))
                              (filter #(str/ends-with? (.getName %) ".json") (.listFiles lex-dir))))
            declared (set (map (fn [ns] (last (str/split ns #"\.")))
                               (get m "lexiconNamespaces")))]
        (is (clojure.set/subset? declared (clojure.set/union on-disk declared)))))))
