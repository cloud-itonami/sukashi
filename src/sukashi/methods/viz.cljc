(ns sukashi.methods.viz
  "sukashi 透かし — ad-tech supply-chain + fraud-network visualization payload + viewer.
  ADR-2606071600 / 2606160842 (clojure port of viz/build_viz_data.py).

  Reads the ad-tech supply-chain graph (the SAME seed analyze.cljc reads), builds a
  force-graph NODE/EDGE payload that VISUALIZES the supply chain + the fraud clusters,
  and emits:

    1. viz/ad-supply-chain.json — the viz payload (the data CONTRACT: {nodes, links, meta}).
    2. viz/ad-supply-chain.htm  — a SELF-CONTAINED viewer (payload inlined into viz/template.htm,
       a hand-rolled inline canvas force-graph; opens via file://, no external CDN / fetch).

  A fraud-PROTECTION + ad-tech TRANSPARENCY surface, NEVER a target-list and NEVER an
  ad-buying / optimization tool (sukashi G2). Non-adjudicating (G4) — fraud nodes/edges are
  flagged observations, not verdicts. Every fraud example is a FICTIONAL illustrative entity.

  build-payload is pure; the file I/O (read seed, write json/htm, read template) is the
  #?(:clj) -main edge only."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]
            #?(:clj [clojure.java.io :as io])))

(defn kw*
  "':dsp' → 'dsp'; passthrough for non-keyword strings; \"\" for nil. (Python _kw.)"
  [v]
  (if (nil? v) "" (str/replace-first (str v) #"^:+" "")))

(defn- kw-or-nil [v]
  (let [s (kw* v)] (if (str/blank? s) nil s)))

(defn build-payload
  "1:1 port of build_payload(adtech, auth, creatives, delivery, fraud). adtech is the
  classify map's :adtech (id→rec, ::order metadata); the rest are vectors in doc order.
  Returns {:nodes [...] :links [...] :meta {...}} (string keys, JSON-shaped)."
  [adtech auth creatives delivery fraud]
  (let [fraud-subjects (disj (set (map #(get % ":adfraud.signal/subject") fraud)) nil)
        signals-by-subject
        (reduce (fn [m f]
                  (update m (get f ":adfraud.signal/subject") (fnil conj [])
                          {"kind" (kw* (get f ":adfraud.signal/kind"))
                           "confidence" (get f ":adfraud.signal/confidence")
                           "routed_to" (kw* (get f ":adfraud.signal/routed-to"))}))
                {} fraud)
        adtech-recs (edn/adtech-vals adtech)
        ;; ── NODES: one per ad-tech entity, plus one per creative ──
        adtech-nodes
        (mapv (fn [rec]
                (let [eid (get rec ":adtech/id")
                      flagged (or (= (get rec ":adtech/sourcing") ":synthesized")
                                  (contains? fraud-subjects eid))]
                  {"id" eid
                   "label" (get rec ":adtech/name" eid)
                   "group" (kw* (get rec ":adtech/role"))
                   "kind" "adtech"
                   "domain" (get rec ":adtech/domain")
                   "country" (get rec ":adtech/country")
                   "category" (kw-or-nil (get rec ":adtech/category"))
                   "sourcing" (kw* (get rec ":adtech/sourcing"))
                   "fraud" flagged
                   "signals" (get signals-by-subject eid [])}))
              adtech-recs)
        creative-nodes
        (mapv (fn [c]
                (let [cid (get c ":adcreative/id")
                      flagged (or (= (get c ":adcreative/sourcing") ":synthesized")
                                  (contains? fraud-subjects cid))]
                  {"id" cid
                   "label" (get c ":adcreative/headline" cid)
                   "group" "creative"
                   "kind" "creative"
                   "domain" (get c ":adcreative/landing-domain")
                   "category" (kw-or-nil (get c ":adcreative/category"))
                   "sourcing" (kw* (get c ":adcreative/sourcing"))
                   "fraud" flagged
                   "signals" (get signals-by-subject cid [])}))
              creatives)
        nodes (into adtech-nodes creative-nodes)
        node-ids (set (map #(get % "id") nodes))
        ;; ── EDGES ──
        ;; 1. authorization edges (ads.txt / sellers.json): publisher → seller.
        auth-links (keep (fn [e]
                           (let [pub (get e ":adauth.edge/publisher")
                                 sel (get e ":adauth.edge/seller")]
                             (when (and (node-ids pub) (node-ids sel))
                               (let [bad (and (boolean (get e ":adauth.edge/declared"))
                                              (not (boolean (get e ":adauth.edge/confirmed"))))]
                                 {"source" pub "target" sel "type" "auth"
                                  "relationship" (kw* (get e ":adauth.edge/relationship"))
                                  "account_id" (get e ":adauth.edge/account-id")
                                  "unconfirmed" bad}))))
                         auth)
        unconfirmed (count (filter #(get % "unconfirmed") auth-links))
        ;; 2. delivery edges: creative → serving advertiser/exchange + advertiser → creative.
        delivery-links
        (mapcat (fn [c]
                  (let [cid (get c ":adcreative/id")
                        via (get c ":adcreative/served-via")
                        adv (get c ":adcreative/advertiser")]
                    (cond-> []
                      (and via (node-ids via))
                      (conj {"source" cid "target" via "type" "served-via"
                             "relationship" "served-via" "unconfirmed" false})
                      (and adv (node-ids adv))
                      (conj {"source" adv "target" cid "type" "creative-of"
                             "relationship" "creative-of" "unconfirmed" false}))))
                creatives)
        ;; 3. shared-infra scam cluster: connect creatives sharing a serving ASN.
        creatives-by-asn
        (reduce (fn [m d]
                  (let [cid (get d ":addelivery.edge/creative")
                        asn (get d ":addelivery.edge/asn")]
                    (if (and (node-ids cid) asn)
                      (update m asn (fnil conj []) [cid (get d ":addelivery.edge/whois-org")])
                      m)))
                {} delivery)
        shared-infra-links
        (vec (for [[asn members] creatives-by-asn
                   i (range (count members))
                   j (range (inc i) (count members))]
               {"source" (first (nth members i))
                "target" (first (nth members j))
                "type" "shared-infra" "relationship" "shared-infra"
                "asn" asn "whois_org" (second (nth members i)) "unconfirmed" false}))
        links (vec (concat auth-links delivery-links shared-infra-links))
        fraud-nodes (count (filter #(get % "fraud") nodes))]
    {"nodes" nodes
     "links" links
     "meta" {"actor" "sukashi" "glyph" "透かし" "adr" "2606071600"
             "note" (str "fraud-PROTECTION + ad-tech transparency map; NOT a target-list, "
                         "NOT an ad-buying tool (G2). Non-adjudicating (G4). Fraud examples "
                         "are FICTIONAL illustrative entities.")
             "counts" {"nodes" (count nodes)
                       "adtech_nodes" (count adtech-recs)
                       "creative_nodes" (count creatives)
                       "fraud_nodes" fraud-nodes
                       "links" (count links)
                       "auth_edges" (count auth)
                       "unconfirmed_edges" unconfirmed
                       "shared_infra_links" (count shared-infra-links)
                       "fraud_signals" (count fraud)}}}))

#?(:clj
   (def ^:private here   ;; resolved at LOAD time (*file* is nil in a fn body)
     (-> *file* io/file .getParentFile .getParentFile (io/file "viz"))))

#?(:clj
   (defn -main
     "Build the viz payload + self-contained viewer. Arg: [seed-edn] (default the actor seed)."
     [& args]
     (let [json-str (requiring-resolve 'cheshire.core/generate-string)
           seed (if (and (first args) (not (str/starts-with? (first args) "--")))
                  (io/file (first args))
                  (io/file (.getParentFile here) "data" "seed-ad-supply-chain.kotoba.edn"))
           {:keys [adtech auth creatives delivery fraud]} (edn/classify (edn/load-edn seed))
           payload (build-payload adtech auth creatives delivery fraud)
           tmpl (slurp (io/file here "template.htm"))]
       (spit (io/file here "ad-supply-chain.json")
             (str (json-str payload {:pretty true}) "\n"))
       (spit (io/file here "ad-supply-chain.htm")
             (str/replace tmpl "__SUKASHI_DATA__" (json-str payload)))
       (let [c (get payload "meta")]
         (let [c (get c "counts")]
           (println (format (str "sukashi.viz: %d nodes (%d ad-tech + %d creative, %d fraud-flagged), "
                                 "%d links (%d unconfirmed auth, %d shared-infra) → "
                                 "ad-supply-chain.json + ad-supply-chain.htm")
                            (get c "nodes") (get c "adtech_nodes") (get c "creative_nodes")
                            (get c "fraud_nodes") (get c "links") (get c "unconfirmed_edges")
                            (get c "shared_infra_links"))))))))
