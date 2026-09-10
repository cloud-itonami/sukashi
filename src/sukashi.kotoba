;; mesh.clj — sukashi 透かし KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:sukashi (ad-tech supply-chain + fraud-network
;; observatory). Observatory on-kse pattern (ADR-2606230001 §4): observes
;; intermediary→path routing edges (from public ads.txt/sellers.json) as Datom
;; assertions and derives fraud-exposure concentration via Datalog. The full
;; programmatic-ecosystem analysis stays in the actor's existing methods.
;;
;; Posture: a fraud-PROTECTION observatory, NEVER an ad network / buying /
;; targeting / optimization / detection-evasion tool. Real firms carry no fraud
;; signal; fraud examples are synthesized fictional entities.
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns sukashi)

(defn observe []
  ;; observe — declared supply paths from public ads.txt / sellers.json.
  (kqe-assert! "sukashi" "ssp" "routes" "inventory-a")
  (kqe-assert! "sukashi" "reseller" "routes" "inventory-a")
  (kqe-assert! "sukashi" "exchange" "routes" "inventory-b")
  ;; derive — supply-path concentration → fraud-protection map (Datalog).
  (kqe-query "fraud-protection(?p) :- routes(?p)."))

(defn run [ctx] (observe))
(defn on-kse [topic payload] (observe))
