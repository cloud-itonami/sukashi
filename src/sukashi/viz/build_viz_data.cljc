(ns sukashi.viz.build-viz-data
  "1:1 port of viz/build_viz_data.py build_payload (ADR-2606071600). Reads the same ad-tech
  supply-chain graph analyze reads (via sukashi-edn classify) and builds the force-graph
  NODE/EDGE payload {nodes, links, meta} that VISUALIZES the supply chain + fraud clusters.

  A fraud-PROTECTION + ad-tech TRANSPARENCY surface, NEVER a target-list / ad-buying tool (G2);
  non-adjudicating (G4) — fraud nodes/edges are flagged observations, not verdicts.

  Ported: the pure-stdlib `_kw` helper + `build_payload`. OMITTED (render/IO legs, not ported):
  `main()` file-writing + the self-contained HTML_TEMPLATE viewer. The dead `cre_by_id` local in
  the Python (built but never read) is dropped — it has no effect on the payload."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]))

(defn kw*
  "':dsp' → 'dsp'; passthrough for non-keyword strings; '' for nil. Mirrors _kw (lstrip ':')."
  [v]
  (if (nil? v) "" (str/replace (str v) #"^:+" "")))

(defn- kw-or-nil
  "_kw(x) or None — empty string collapses to nil."
  [v]
  (let [k (kw* v)] (when-not (= k "") k)))

(defn build-payload
  "Mirrors build_payload(adtech, auth, creatives, delivery, fraud) → {\"nodes\" \"links\" \"meta\"}."
  [adtech auth creatives delivery fraud]
  (let [;; fraud-flag set: subject of any :adfraud.signal (None discarded)
        fraud-subjects (disj (set (map #(get % ":adfraud.signal/subject") fraud)) nil)
        ;; signals indexed by subject (document order), for tooltips
        signals-by-subject (reduce (fn [m f]
                                     (update m (get f ":adfraud.signal/subject") (fnil conj [])
                                             {"kind" (kw* (get f ":adfraud.signal/kind"))
                                              "confidence" (get f ":adfraud.signal/confidence")
                                              "routed_to" (kw* (get f ":adfraud.signal/routed-to"))}))
                                   {} fraud)
        is-fraud (fn [eid rec]
                   (or (= (get rec ":adtech/sourcing") ":synthesized")
                       (contains? fraud-subjects eid)))
        ;; ── NODES: one per ad-tech entity (insertion order), then one per creative ──
        adtech-nodes (mapv (fn [rec]
                             (let [eid (get rec ":adtech/id")]
                               {"id" eid
                                "label" (get rec ":adtech/name" eid)
                                "group" (kw* (get rec ":adtech/role"))
                                "kind" "adtech"
                                "domain" (get rec ":adtech/domain")
                                "country" (get rec ":adtech/country")
                                "category" (kw-or-nil (get rec ":adtech/category"))
                                "sourcing" (kw* (get rec ":adtech/sourcing"))
                                "fraud" (is-fraud eid rec)
                                "signals" (get signals-by-subject eid [])}))
                           (edn/adtech-vals adtech))
        creative-nodes (mapv (fn [c]
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
        auth-links (reduce (fn [acc e]
                             (let [pub (get e ":adauth.edge/publisher")
                                   sel (get e ":adauth.edge/seller")]
                               (if (or (not (contains? node-ids pub)) (not (contains? node-ids sel)))
                                 acc
                                 (let [declared (boolean (get e ":adauth.edge/declared"))
                                       confirmed (boolean (get e ":adauth.edge/confirmed"))
                                       bad (and declared (not confirmed))]
                                   (conj acc {"source" pub "target" sel "type" "auth"
                                              "relationship" (kw* (get e ":adauth.edge/relationship"))
                                              "account_id" (get e ":adauth.edge/account-id")
                                              "unconfirmed" bad})))))
                           [] auth)
        unconfirmed (count (filter #(get % "unconfirmed") auth-links))
        ;; 2. delivery edges: creative → served-via, and advertiser → creative
        delivery-links (reduce (fn [acc c]
                                 (let [cid (get c ":adcreative/id")
                                       via (get c ":adcreative/served-via")
                                       adv (get c ":adcreative/advertiser")
                                       acc (if (and via (contains? node-ids via))
                                             (conj acc {"source" cid "target" via "type" "served-via"
                                                        "relationship" "served-via" "unconfirmed" false})
                                             acc)]
                                   (if (and adv (contains? node-ids adv))
                                     (conj acc {"source" adv "target" cid "type" "creative-of"
                                                "relationship" "creative-of" "unconfirmed" false})
                                     acc)))
                               [] creatives)
        ;; 3. shared-infra scam cluster: connect creatives that share a serving ASN.
        {:keys [order by-asn]}
        (reduce (fn [{:keys [order by-asn]} d]
                  (let [cid (get d ":addelivery.edge/creative")
                        asn (get d ":addelivery.edge/asn")]
                    (if (and (contains? node-ids cid) asn)
                      {:order (if (contains? by-asn asn) order (conj order asn))
                       :by-asn (update by-asn asn (fnil conj [])
                                       [cid (get d ":addelivery.edge/whois-org")])}
                      {:order order :by-asn by-asn})))
                {:order [] :by-asn {}} delivery)
        shared-links (vec (for [asn order
                                :let [members (get by-asn asn)
                                      cids (mapv first members)]
                                i (range (count cids))
                                j (range (inc i) (count cids))]
                            {"source" (nth cids i) "target" (nth cids j)
                             "type" "shared-infra" "relationship" "shared-infra"
                             "asn" asn "whois_org" (second (nth members i)) "unconfirmed" false}))
        links (into (into auth-links delivery-links) shared-links)
        fraud-nodes (count (filter #(get % "fraud") nodes))]
    {"nodes" nodes
     "links" links
     "meta" {"actor" "sukashi"
             "glyph" "透かし"
             "adr" "2606071600"
             "note" (str "fraud-PROTECTION + ad-tech transparency map; NOT a target-list, "
                         "NOT an ad-buying tool (G2). Non-adjudicating (G4). Fraud examples "
                         "are FICTIONAL illustrative entities.")
             "counts" {"nodes" (count nodes)
                       "adtech_nodes" (count adtech)
                       "creative_nodes" (count creatives)
                       "fraud_nodes" fraud-nodes
                       "links" (count links)
                       "auth_edges" (count auth)
                       "unconfirmed_edges" unconfirmed
                       "shared_infra_links" (count shared-links)
                       "fraud_signals" (count fraud)}}}))
