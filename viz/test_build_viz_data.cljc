(ns sukashi.viz.test-build-viz-data
  "Tests for the sukashi viz payload builder (ADR-2606071600 port of build_viz_data.build_payload).
  A precise hand fixture exercises every branch — fraud flagging (synthesized + signal-subject),
  the unconfirmed auth edge + the skipped edge (endpoint not a node), served-via/creative-of
  delivery edges, shared-infra ASN pairing, _kw stripping, category-or-nil — and a seed run pins
  the aggregate counts against the trusted sukashi-edn classify."
  (:require [clojure.test :refer [deftest is]]
            [sukashi.methods.sukashi-edn :as edn]
            [sukashi.viz.build-viz-data :as v]))

(defn- node-by-id [p id] (first (filter #(= id (get % "id")) (get p "nodes"))))

(def ^:private adtech-fix
  ;; ordered (insertion order preserved via classify ::order; here a plain vec fed directly)
  [{":adtech/id" "pub1" ":adtech/role" ":publisher" ":adtech/name" "Pub One" ":adtech/sourcing" ":authoritative"}
   {":adtech/id" "ssp1" ":adtech/role" ":ssp" ":adtech/sourcing" ":synthesized"}
   {":adtech/id" "dsp1" ":adtech/role" ":dsp" ":adtech/sourcing" ":representative" ":adtech/category" ":video"}])

(def ^:private creatives-fix
  [{":adcreative/id" "cr1" ":adcreative/headline" "Buy now" ":adcreative/served-via" "dsp1"
    ":adcreative/advertiser" "pub1" ":adcreative/sourcing" ":representative"}
   {":adcreative/id" "cr2" ":adcreative/sourcing" ":synthesized"}])

(def ^:private auth-fix
  [{":adauth.edge/id" "a1" ":adauth.edge/publisher" "pub1" ":adauth.edge/seller" "ssp1"
    ":adauth.edge/relationship" ":direct" ":adauth.edge/declared" true ":adauth.edge/confirmed" false}
   {":adauth.edge/id" "a2" ":adauth.edge/publisher" "pub1" ":adauth.edge/seller" "dsp1"
    ":adauth.edge/declared" true ":adauth.edge/confirmed" true}
   {":adauth.edge/id" "a3" ":adauth.edge/publisher" "pub1" ":adauth.edge/seller" "nonexistent"}])

(def ^:private delivery-fix
  [{":addelivery.edge/id" "d1" ":addelivery.edge/creative" "cr1" ":addelivery.edge/asn" "AS64500" ":addelivery.edge/whois-org" "OrgX"}
   {":addelivery.edge/id" "d2" ":addelivery.edge/creative" "cr2" ":addelivery.edge/asn" "AS64500" ":addelivery.edge/whois-org" "OrgY"}])

(def ^:private fraud-fix
  [{":adfraud.signal/id" "f1" ":adfraud.signal/subject" "cr1" ":adfraud.signal/kind" ":misleading-claim"
    ":adfraud.signal/confidence" 0.8 ":adfraud.signal/routed-to" ":kurashimori"}])

(defn- build-fix []
  ;; adtech must be the ordered map classify produces; feed via classify on the flat row vec.
  (let [{:keys [adtech]} (edn/classify adtech-fix)]
    (v/build-payload adtech auth-fix creatives-fix delivery-fix fraud-fix)))

(deftest test-fixture-counts
  (let [c (get-in (build-fix) ["meta" "counts"])]
    (is (= 5 (get c "nodes")))            ; 3 adtech + 2 creative
    (is (= 3 (get c "adtech_nodes")))
    (is (= 2 (get c "creative_nodes")))
    (is (= 3 (get c "fraud_nodes")))      ; ssp1(synth) + cr2(synth) + cr1(signal-subject)
    (is (= 5 (get c "links")))            ; 2 auth + 2 delivery + 1 shared-infra
    (is (= 3 (get c "auth_edges")))       ; len(auth) incl. the skipped a3
    (is (= 1 (get c "unconfirmed_edges")))
    (is (= 1 (get c "shared_infra_links")))
    (is (= 1 (get c "fraud_signals")))))

(deftest test-fixture-node-fields-and-fraud
  (let [p (build-fix)]
    (is (= "ssp" (get (node-by-id p "ssp1") "group")))           ; _kw strip
    (is (= true (get (node-by-id p "ssp1") "fraud")))            ; synthesized
    (is (= false (get (node-by-id p "pub1") "fraud")))
    (is (= "video" (get (node-by-id p "dsp1") "category")))      ; kw-or-nil
    (is (nil? (get (node-by-id p "pub1") "category")))           ; absent → nil
    (is (= true (get (node-by-id p "cr1") "fraud")))            ; signal subject
    (is (= [{"kind" "misleading-claim" "confidence" 0.8 "routed_to" "kurashimori"}]
           (get (node-by-id p "cr1") "signals")))))

(deftest test-fixture-edges
  (let [links (get (build-fix) "links")
        by-type (group-by #(get % "type") links)]
    ;; auth: a1 unconfirmed (declared & !confirmed), a2 confirmed, a3 skipped (seller not a node)
    (is (= 2 (count (get by-type "auth"))))
    (let [a1 (first (filter #(= "ssp1" (get % "target")) (get by-type "auth")))]
      (is (= "direct" (get a1 "relationship")))
      (is (= true (get a1 "unconfirmed"))))
    (is (= false (get (first (filter #(= "dsp1" (get % "target")) (get by-type "auth"))) "unconfirmed")))
    ;; delivery: cr1 served-via dsp1 + advertiser pub1 → creative-of pub1→cr1
    (is (= 1 (count (get by-type "served-via"))))
    (is (= 1 (count (get by-type "creative-of"))))
    (is (= "pub1" (get (first (get by-type "creative-of")) "source")))
    ;; shared-infra: cr1 & cr2 share AS64500 → one pair, whois_org of the i-th member (cr1=OrgX)
    (let [si (first (get by-type "shared-infra"))]
      (is (= "cr1" (get si "source")))
      (is (= "cr2" (get si "target")))
      (is (= "AS64500" (get si "asn")))
      (is (= "OrgX" (get si "whois_org"))))))

(deftest test-seed-aggregate-counts
  ;; Pin the real seed payload counts against the trusted classify (golden via bb run).
  (let [rows (edn/load-edn "20-actors/sukashi/data/seed-ad-supply-chain.kotoba.edn")
        {:keys [adtech auth creatives delivery fraud]} (edn/classify rows)
        c (get-in (v/build-payload adtech auth creatives delivery fraud) ["meta" "counts"])]
    (is (= 83 (get c "nodes")))
    (is (= 74 (get c "adtech_nodes")))
    (is (= 9 (get c "creative_nodes")))
    (is (= 18 (get c "fraud_nodes")))
    (is (= 44 (get c "links")))
    (is (= 28 (get c "auth_edges")))
    (is (= 3 (get c "unconfirmed_edges")))
    (is (= 3 (get c "shared_infra_links")))
    (is (= 12 (get c "fraud_signals")))))
