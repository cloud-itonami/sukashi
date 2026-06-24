(ns sukashi.methods.test-autorun
  "test_autorun.py — sukashi autonomous observatory heartbeat + kotoba Datom-log invariants.
  ADR-2606071600. 1:1 Clojure port of methods/test_autorun.py (clojure.test/deftest+is mirroring
  the ok() asserts).

  Guards the autonomy + persistence + non-adjudication contract that lets sukashi run on the fleet:
    - the loop persists one content-addressed tx per heartbeat to an append-only log;
    - the log is a verifiable commit-DAG (every CID recomputes; tamper is detected);
    - it is deterministic / resume-safe (same cycles → same CIDs) and append-only;
    - derived :adsupply/* + :adfraud/* signals are flagged :derived (recomputed-on-read);
    - G4 non-adjudication: every persisted fraud-signal carries :non-adjudicating true +
      :sourcing :synthesized — no real entity is implicated;
    - it does NO external I/O (offline ingest, local persist — G7/G11 stay gated)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [sukashi.methods.autorun :as autorun]
            [sukashi.methods.kotoba :as kotoba]))

#?(:clj
   (defn- tmp-log []
     (let [f (java.io.File/createTempFile "tmp" ".datoms.kotoba.edn")]
       (.delete f)
       f)))

(deftest test-heartbeat-persists
  (let [log (tmp-log)]
    (try
      (let [res (autorun/run-autonomous 3 nil log)]
        (is (= 3 (get res "log_length")) "one tx per heartbeat")
        (is (every? #(> (get % "datoms") 0) (get res "beats")) "every heartbeat persisted datoms")
        (is (get (get res "chain") "ok") "commit-DAG verifies (chain OK)")
        (is (str/starts-with? (get res "head_cid") "b") "head CID is content-addressed"))
      (finally (.delete log)))))

(deftest test-deterministic-resume-safe
  (let [a (tmp-log) b (tmp-log)]
    (try
      (let [ra (autorun/run-autonomous 3 nil a)
            rb (autorun/run-autonomous 3 nil b)]
        (is (= (mapv #(get % "cid") (get ra "beats"))
               (mapv #(get % "cid") (get rb "beats")))
            "same cycles → same CIDs (deterministic / resume-safe)"))
      (finally (.delete a) (.delete b)))))

(deftest test-append-only-and-tamper
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 nil log)
      (let [first-log (kotoba/read-log log)]
        (autorun/run-cycle 2 nil log)
        (let [second-log (kotoba/read-log log)]
          (is (= (count second-log) (inc (count first-log)))
              "second heartbeat appends, does not rewrite")
          (is (= (get (nth second-log 1) ":tx/prev") (get (nth first-log 0) ":tx/cid"))
              "tx 2 links tx 1's CID (commit-DAG)")
          ;; tamper an earlier tx and confirm the chain breaks at index 0
          (let [lines (str/split-lines (slurp log))
                tampered (mapv (fn [ln]
                                 (if (str/includes? ln ":tx/id 1 ")
                                   (str/replace-first ln
                                                      ":adsupply/derived true"
                                                      ":adsupply/derived false")
                                   ln))
                               lines)]
            (spit log (str (str/join "\n" tampered) "\n"))
            (let [v (kotoba/verify-chain log)]
              (is (and (not (get v "ok")) (= 0 (get v "broken_at")))
                  "tampering an earlier tx breaks the chain")))))
      (finally (.delete log)))))

(deftest test-g4-fraud-signals-non-adjudicating
  ;; the defining sukashi invariant: every persisted fraud-signal is non-adjudicating + synthesized.
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 nil log)
      (let [tx (nth (kotoba/read-log log) 0)
            datoms (get tx ":tx/datoms")
            sig-entities (set (for [d datoms :when (str/starts-with? (str (nth d 2)) ":adfraud.signal/")]
                                (nth d 1)))]
        (is (> (count sig-entities) 0) "fraud-signal entities are persisted")
        (doseq [e sig-entities]
          (let [attrs (into {} (for [d datoms :when (= (nth d 1) e)] [(nth d 2) (nth d 3)]))]
            (is (true? (get attrs ":adfraud.signal/non-adjudicating"))
                (str "fraud signal " e " carries :non-adjudicating true (G4)"))
            (is (= ":synthesized" (get attrs ":adfraud.signal/sourcing"))
                (str "fraud signal " e " is :synthesized (G4 — no real entity implicated)")))))
      (finally (.delete log)))))

(deftest test-derived-flagged
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 nil log)
      (let [tx (nth (kotoba/read-log log) 0)
            datoms (get tx ":tx/datoms")
            derived-attrs (filter #(contains? #{":adsupply/derived" ":adfraud/derived"} (nth % 2)) datoms)]
        (is (> (count derived-attrs) 0) "derived :adsupply/* + :adfraud/* signals are persisted")
        (is (every? #(true? (nth % 3)) derived-attrs) "every derived flag is true (recomputed-on-read)")
        (let [ops (set (map #(nth % 0) datoms))]
          (is (= #{":db/add"} ops) "every datom is append-only :db/add (no :db/retract)")))
      (finally (.delete log)))))

(deftest test-no-external-io
  (let [methods-dir autorun/here   ; the actor's methods/ dir (absolute, resolved by autorun)
        src (str (slurp (io/file methods-dir "autorun.cljc"))
                 (slurp (io/file methods-dir "kotoba.cljc")))]
    (doseq [banned ["urllib" "http.client" "socket" "requests" "subprocess"]]
      (is (not (str/includes? src banned))
          (str "autorun/kotoba does no external I/O (no `" banned "`)")))))

;; ── parity assertion (not in the Python suite; pin byte-/CID-identity vs python3) ──
(deftest test-cid-matches-python
  (let [log (tmp-log)]
    (try
      (let [res (autorun/run-autonomous 3 nil log)
            cids (mapv #(get % "cid") (get res "beats"))]
        (is (= ["b8a6b53e2c169ad8aad658cc520dbc759987e1c5ee8437c4028d1f0cf4a8f1ce8"
                "bce186d10fc889684fd0eeb0612c42c17bfa6f75b0ede8ce8aa812eafba939e9a"
                "be2f80c63015c9c6b4947d8c0486ab3cace38eb6bb3cfdb85e94acbf70baa0780"]
               cids)
            "tx CIDs reproduce python3 autorun.py byte-for-byte"))
      (finally (.delete log)))))

#?(:clj
   (do
     (defn -main [& _] (run-tests 'sukashi.methods.test-autorun))
     (when (= *file* (System/getProperty "babashka.file")) (-main))))
