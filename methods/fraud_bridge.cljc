(ns sukashi.methods.fraud-bridge
  "fraud_bridge.py — sukashi fraud-evidence bridge to akashi's malak candidate intake (G13).
  1:1 Clojure port of `methods/fraud_bridge.py`. ADR-2606071600.

  Maps sukashi :adfraud.signal/* records routed-to :akashi-malak into
  com.etzhayyim.akashi.malakEvidenceCandidate-shaped records. Structural expression of G13:
  sukashi NEVER runs its own malak import or makes an accusation — it emits CANDIDATE evidence
  (reviewStatus = \"candidate-only\") and hands it to akashi's existing review gate.

  Charter (G4/G13): every emitted record is non-adjudicating (nonAdjudicatingNotice = true),
  reviewStatus is locked to \"candidate-only\" (sukashi cannot escalate), and it is OFFLINE — this
  produces a record map, it POSTs nothing (no live handoff; G7/G11).

  House style: pure bridge-to-malak; host/file/JSON I/O only behind #?(:clj …). Re-uses the actor's
  own sukashi-edn (load-edn + classify). The emitted records are ordered maps so the JSON key order
  is byte-identical to the Python dict insertion order."
  (:require [sukashi.methods.sukashi-edn :as edn]
            #?(:clj [clojure.java.io :as io])))

(def attesting-did "did:web:etzhayyim.com:actor:sukashi")
;; sukashi's own published method note — the mandatory SECOND source (akashi requires sourceCids
;; minLength 2): the evidence bundle + the method.
(def method-note-cid "bafy-sukashi-methodnote-fraud-bridge-v0")

;; sukashi fraud-kind → akashi candidateType (akashi knownValues only).
(def kind->candidate
  {":phishing-landing" "public-phishing-url-match"
   ":scam-finance" "brand-abuse-report-match"
   ":fake-endorsement" "brand-abuse-report-match"
   ":counterfeit-goods" "brand-abuse-report-match"
   ":domain-spoof" "public-ioc-domain-match"
   ":typosquat-delivery" "public-ioc-domain-match"
   ":malvertising-redirect" "public-ioc-domain-match"
   ":unauthorized-reseller" "public-ioc-domain-match"
   ":sellers-json-mismatch" "public-ioc-domain-match"
   ":shared-fraud-infra" "public-ioc-domain-match"
   ":cloaking" "public-ioc-domain-match"})

(defn bridge-to-malak
  "Map :akashi-malak-routed sukashi signals → akashi malakEvidenceCandidate records.
  Only signals explicitly routed-to :akashi-malak are bridged. Returns a vector of ordered maps."
  [fraud-signals]
  (reduce
   (fn [out f]
     (if (not= (get f ":adfraud.signal/routed-to") ":akashi-malak")
       out
       (let [kind (get f ":adfraud.signal/kind" ":unknown")
             evidence (get f ":adfraud.signal/evidence-cid")
             source-cids0 (filterv some? [evidence method-note-cid])
             source-cids (if (< (count source-cids0) 2)
                           [(or evidence "bafy-sukashi-evidence-missing") method-note-cid]
                           source-cids0)
             rec (array-map
                  "createdAt" (get f ":adfraud.signal/observed-at" "1970-01-01T00:00:00Z")
                  "candidateType" (get kind->candidate kind "public-ioc-domain-match")
                  "sourceCids" source-cids
                  "methodNoteCid" method-note-cid
                  "reviewStatus" "candidate-only"   ; sukashi NEVER escalates (G13)
                  "nonAdjudicatingNotice" true      ; G4
                  "attestingDid" attesting-did)
             rec (if evidence (assoc rec "publicIndicatorCid" evidence) rec)]
         (conj out rec))))
   []
   fraud-signals))

;; ── host/file/JSON edge ───────────────────────────────────────────────────────
#?(:clj
   (defn -main
     "CLI entry: write a candidate-evidence fixture from the seed → out/akashi-malak-candidates.json."
     [& _argv]
     (let [here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
                    (io/file "20-actors" "sukashi"))
           seed (io/file here "data" "seed-ad-supply-chain.kotoba.edn")
           outdir (io/file here "out")
           fraud (:fraud (edn/classify (edn/load-edn seed)))
           candidates (bridge-to-malak fraud)
           outfile (io/file outdir "akashi-malak-candidates.json")]
       (.mkdirs outdir)
       (spit outfile (str ((requiring-resolve 'cheshire.core/generate-string)
                           candidates {:pretty true}) "\n"))
       (println (str "sukashi.fraud_bridge: " (count candidates) " candidate-evidence record(s) → akashi "
                     "malakEvidenceCandidate (reviewStatus=candidate-only; NO live import — G13). "
                     "wrote " outfile))
       0)))
