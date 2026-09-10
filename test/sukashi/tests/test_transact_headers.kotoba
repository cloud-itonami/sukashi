(ns sukashi.tests.test-transact-headers
  "sukashi 透かし — transact request headers (ADR-2608124000, \"clients first\").

  kotoba-server's require_internal_trust gate returns success while
  KOTOBA_INTERNAL_SECRET is unset, and it is unset across the fleet — so sending
  the x-internal-trust header today changes nothing on the wire. These tests pin
  the shape NOW so that arming the server later is a one-variable decision
  rather than a fleet-wide outage.

  This namespace is NEW: test_sukashi.cljc says outright that the transact
  assertions were not ported, so `sukashi.methods.transact` had no test coverage
  at all before this. What is covered here is the header decision only —
  `request-headers` is pure, so it needs no network.

  The secret values below are obviously synthetic. Nothing here reads, writes or
  generates a real credential."
  (:require [clojure.test :refer [deftest is run-tests]]
            [sukashi.methods.transact :as transact]))

(def ^:private synthetic-trust "synthetic-internal-trust-not-a-real-secret")
(def ^:private synthetic-token "synthetic-operator-token")

(deftest internal-trust-header-present-when-configured
  (let [h (transact/request-headers synthetic-token synthetic-trust)]
    (is (= synthetic-trust (get h "x-internal-trust"))
        "the configured value is sent verbatim")
    ;; positive control — the pre-existing headers are untouched
    (is (= (str "Bearer " synthetic-token) (get h "Authorization")))
    (is (= "application/json" (get h "Content-Type")))))

(deftest internal-trust-header-absent-when-unconfigured
  (doseq [trust [nil "" "   "]]
    (let [h (transact/request-headers synthetic-token trust)]
      (is (not (contains? h "x-internal-trust"))
          (str "omitted entirely for " (pr-str trust) " — never an empty string"))
      ;; positive control — omitting trust must not disturb anything else
      (is (= (str "Bearer " synthetic-token) (get h "Authorization")))
      (is (= "application/json" (get h "Content-Type")))))
  ;; the no-op property, asserted: with no secret the header map is exactly what
  ;; this code has always produced.
  (is (= {"Content-Type" "application/json"
          "Authorization" (str "Bearer " synthetic-token)}
         (transact/request-headers synthetic-token nil))))

(deftest authorization-still-omitted-without-a-token
  ;; positive control in the other direction: the bearer keeps its own rules, and
  ;; a configured trust secret is NOT a substitute for an operator token.
  (is (= {"Content-Type" "application/json"}
         (transact/request-headers nil nil)))
  (let [h (transact/request-headers nil synthetic-trust)]
    (is (not (contains? h "Authorization")))
    (is (= synthetic-trust (get h "x-internal-trust")))))

(deftest internal-trust-absence-is-reported-not-silent
  (is (= "x-internal-trust" transact/internal-trust-header))
  (is (= "KOTOBA_INTERNAL_SECRET" transact/internal-trust-env)
      "the same variable the server and the Cloudflare gateway read")
  (with-redefs [transact/internal-trust (constantly nil)]
    (is (= :unconfigured (transact/internal-trust-status))))
  (with-redefs [transact/internal-trust (constantly synthetic-trust)]
    (is (= :configured (transact/internal-trust-status)))))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (let [{:keys [fail error]} (run-tests 'sukashi.tests.test-transact-headers)]
       (System/exit (if (zero? (+ fail error)) 0 1)))))
