(ns sukashi.tests.test-viz
  "sukashi 透かし viz payload invariants. ADR-2606071600 / 2606160842.
  Covers the cljc port of viz/build_viz_data.py (build-payload over the seed graph)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [sukashi.methods.sukashi-edn :as edn]
            [sukashi.methods.viz :as viz]))

(defn- payload []
  (let [{:keys [adtech auth creatives delivery fraud]}
        (edn/classify (edn/load-edn (io/resource "sukashi/data/seed-ad-supply-chain.kotoba.edn")))]
    (viz/build-payload adtech auth creatives delivery fraud)))

(deftest payload-shape
  (let [p (payload)]
    (is (= #{"nodes" "links" "meta"} (set (keys p))))
    (is (pos? (count (get p "nodes"))))
    (is (pos? (count (get p "links"))))
    (is (= "sukashi" (get-in p ["meta" "actor"])))))

(deftest counts-are-consistent
  (let [p (payload)
        c (get-in p ["meta" "counts"])]
    (is (= (count (get p "nodes")) (get c "nodes")))
    (is (= (count (get p "links")) (get c "links")))
    (is (= (+ (get c "adtech_nodes") (get c "creative_nodes")) (get c "nodes")))
    (is (= (get c "fraud_nodes") (count (filter #(get % "fraud") (get p "nodes")))))))

(deftest links-reference-existing-nodes
  (let [p (payload)
        ids (set (map #(get % "id") (get p "nodes")))]
    (is (every? (fn [l] (and (ids (get l "source")) (ids (get l "target")))) (get p "links")))))

(deftest g4-fraud-flags-are-grounded
  ;; a node is fraud-flagged ONLY if it is :synthesized OR the subject of a fraud signal —
  ;; never an unexplained accusation (non-adjudicating, fictional examples only).
  (let [p (payload)]
    (doseq [n (filter #(get % "fraud") (get p "nodes"))]
      (is (or (= "synthesized" (get n "sourcing")) (seq (get n "signals")))
          (str "fraud node " (get n "id") " is grounded in :synthesized or a signal")))))

(deftest kw-strips-leading-colon
  (is (= "dsp" (viz/kw* ":dsp")))
  (is (= "" (viz/kw* nil)))
  (is (= "plain" (viz/kw* "plain"))))
