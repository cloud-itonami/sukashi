(require '[clojure.test :as test])
(def test-namespaces
  '[sukashi.methods.test-autorun sukashi.tests.test-crawl
    sukashi.tests.test-sukashi sukashi.tests.test-transact-headers sukashi.tests.test-viz
    sukashi.viz.test-build-viz-data sukashi.murakumo-test
    sukashi.repository-contract-test])
(doseq [namespace test-namespaces] (require namespace))
(let [result (apply test/run-tests test-namespaces)]
  (println "==> sukashi:" (select-keys result [:test :pass :fail :error]))
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
