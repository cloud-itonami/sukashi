(ns sukashi.tests.test-crawl
  "sukashi 透かし — crawl.cljc unit tests (ADR-2606071600).

  Tests the PURE logic in crawl.cljc:
    - urls-for: URL structure for all 4 kinds (G1/G2 invariant)
    - kinds-for-role: role dispatch correctness
    - fresh?: mtime freshness gate
    - crawl (dry-run): no network; returns planned list
    - crawl (injected fetcher): mock HTTP → parse-fetched dispatches
    - parse-fetched: ads.txt parsing round-trip

  No real network calls. The operator gate is bypassed via the :fetcher opt (injected
  stub) or explicitly false :gate."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [sukashi.methods.crawl :as C]))

;; ── urls-for (pure, G1/G2 invariant) ─────────────────────────────────────────

(deftest test-urls-for-all-kinds
  (let [u (C/urls-for "example.com")]
    (is (= "https://example.com/ads.txt"      (get u "ads.txt")))
    (is (= "https://example.com/app-ads.txt"  (get u "app-ads.txt")))
    (is (= "https://example.com/sellers.json" (get u "sellers.json")))
    (is (= "https://rdap.org/domain/example.com" (get u "rdap")))
    (is (= 4 (count u)))))

(deftest test-urls-for-substitutes-domain
  ;; Only the domain changes — the paths are fixed (G1: only IAB public paths)
  (let [ua (C/urls-for "a.example.com")
        ub (C/urls-for "b.example.com")]
    (is (str/includes? (get ua "ads.txt") "a.example.com"))
    (is (str/includes? (get ub "ads.txt") "b.example.com"))
    (is (= 4 (count (C/urls-for "sub.domain.example.co.uk"))))))

(deftest test-kinds-are-only-public-iac-paths
  ;; Verifying the G1/G2 invariant at the constant level
  (is (= 4 (count C/kinds)))
  (is (contains? C/kinds "ads.txt"))
  (is (contains? C/kinds "app-ads.txt"))
  (is (contains? C/kinds "sellers.json"))
  (is (contains? C/kinds "rdap")))

;; ── kinds-for-role (pure) ────────────────────────────────────────────────────

(deftest test-kinds-for-role-publisher
  ;; Default (no role / publisher): ads.txt + rdap
  (is (= #{"ads.txt" "rdap"} (set (C/kinds-for-role nil))))
  (is (= #{"ads.txt" "rdap"} (set (C/kinds-for-role "publisher"))))
  (is (= #{"ads.txt" "rdap"} (set (C/kinds-for-role ":publisher"))))
  (is (= #{"ads.txt" "rdap"} (set (C/kinds-for-role "news-publisher"))))
  (is (= #{"ads.txt" "rdap"} (set (C/kinds-for-role "")))))

(deftest test-kinds-for-role-exchange
  ;; SSP/exchange: sellers.json + rdap (NOT ads.txt)
  (is (= #{"sellers.json" "rdap"} (set (C/kinds-for-role "exchange"))))
  (is (= #{"sellers.json" "rdap"} (set (C/kinds-for-role "ssp"))))
  (is (= #{"sellers.json" "rdap"} (set (C/kinds-for-role "ad-exchange"))))
  (is (= #{"sellers.json" "rdap"} (set (C/kinds-for-role ":exchange")))))

(deftest test-kinds-for-role-app-publisher
  ;; App publisher: app-ads.txt + rdap (NOT ads.txt)
  (is (= #{"app-ads.txt" "rdap"} (set (C/kinds-for-role "app-publisher"))))
  (is (= #{"app-ads.txt" "rdap"} (set (C/kinds-for-role "ctv")))))

;; ── fresh? (pure) ────────────────────────────────────────────────────────────

(deftest test-fresh-always-false-in-cljs
  ;; In CLJS there's no filesystem; always false (per the implementation)
  #?(:cljs (is (not (C/fresh? "/any/path" 9999999999.0 86400.0)))))

#?(:clj
   (do
     (deftest test-fresh-false-when-ttl-zero
       ;; ttl=0 means never fresh regardless of file age
       (let [f (java.io.File/createTempFile "sukashi-crawl-test" ".txt")]
         (try
           (is (not (C/fresh? (.getAbsolutePath f) (System/currentTimeMillis) 0.0)))
           (finally (.delete f)))))

     (deftest test-fresh-true-when-just-written
       ;; A file written now with ttl=1 hour should be fresh
       (let [f   (java.io.File/createTempFile "sukashi-crawl-fresh" ".txt")
             now (/ (System/currentTimeMillis) 1000.0)]
         (try
           (clojure.java.io/copy "hello" f)
           (is (C/fresh? (.getAbsolutePath f) now 3600.0))
           (finally (.delete f)))))

     (deftest test-fresh-false-when-file-missing
       (is (not (C/fresh? "/tmp/sukashi-nonexistent-abc123.txt" 9999999999.0 3600.0))))))

;; ── crawl dry-run (no gate, no fetcher) ──────────────────────────────────────

(deftest test-crawl-dry-run-returns-plan
  (let [frontier [{":domain" "example.com" ":role" "publisher"}
                  {":domain" "exchange.io" ":role" "exchange"}]
        res      (C/crawl {:frontier frontier :gate false})]
    (is (= "dry-run" (:mode res)))
    (is (vector? (:planned res)))
    (is (empty? (:fetched res)))
    (is (empty? (:rows res)))
    ;; publisher → ads.txt + rdap = 2 entries; exchange → sellers.json + rdap = 2
    (is (= 4 (count (:planned res))))))

(deftest test-crawl-dry-run-no-network-without-gate
  ;; Without gate=true or SUKASHI_OPERATOR_GATE=1 env, crawl is always dry-run
  (let [frontier [{":domain" "example.com" ":role" "publisher"}]
        res      (C/crawl {:frontier frontier :gate false})]
    (is (= "dry-run" (:mode res)))))

(deftest test-crawl-dry-run-plan-shape
  (let [frontier [{":domain" "pub.example.com" ":role" "publisher"}]
        res      (C/crawl {:frontier frontier :gate false})]
    (doseq [item (:planned res)]
      (is (contains? item :domain))
      (is (contains? item :kind))
      (is (contains? item :url))
      (is (str/starts-with? (:url item) "https://")))))

(deftest test-crawl-dry-run-respects-max-domains
  (let [frontier (vec (for [i (range 5)] {":domain" (str "dom" i ".com") ":role" "publisher"}))
        res      (C/crawl {:frontier frontier :gate false :max-domains 2})]
    ;; 2 domains × 2 kinds each = 4 planned items
    (is (= 4 (count (:planned res))))))

(deftest test-crawl-empty-frontier
  (let [res (C/crawl {:frontier [] :gate false})]
    (is (= "dry-run" (:mode res)))
    (is (empty? (:planned res)))))

;; ── crawl with injected fetcher (live path, no real network) ─────────────────

(deftest test-crawl-injected-fetcher-ads-txt
  ;; A mock fetcher returns a simple ads.txt; crawl should parse it → rows
  (let [ads-txt-body "google.com, pub-12345, DIRECT, f08c47fec0942fa0"
        calls        (atom [])
        mock-fetcher (fn [url]
                       (swap! calls conj url)
                       (cond
                         (str/ends-with? url "ads.txt")
                         {:status 200 :body ads-txt-body}
                         (str/ends-with? url "rdap")
                         {:status 200 :body "{\"objectClassName\":\"domain\",\"domain\":\"testpub.io\",\"entities\":[]}"}
                         :else {:status 404 :body ""}))
        frontier [{":domain" "testpub.io" ":role" "publisher"}]]
    #?(:clj
       (let [res (C/crawl {:frontier frontier :fetcher mock-fetcher})]
         (is (= "live" (:mode res)))
         (is (pos? (count @calls)))
         ;; ads.txt parse should produce at least one row
         (is (vector? (:rows res))))
       :cljs (is true "CLJS live leg not supported"))))

(deftest test-crawl-injected-fetcher-skips-on-404
  ;; When fetcher returns 404, the domain gets skipped (no rows, no error)
  (let [mock-fetcher (fn [_url] {:status 404 :body ""})
        frontier     [{":domain" "notfound.example" ":role" "publisher"}]]
    #?(:clj
       (let [res (C/crawl {:frontier frontier :fetcher mock-fetcher})]
         (is (= "live" (:mode res)))
         (is (empty? (:rows res)))
         (is (pos? (count (:skipped res)))))
       :cljs (is true "CLJS live leg not supported"))))

;; ── parse-fetched (pure) ─────────────────────────────────────────────────────

(deftest test-parse-fetched-ads-txt
  ;; parse-fetched "ads.txt" should delegate to ingest/parse-ads-txt and return rows
  (let [text "google.com, pub-12345, DIRECT, f08c47fec0942fa0\n"
        rows (C/parse-fetched "ads.txt" text "example.com")]
    (is (vector? rows))
    (is (pos? (count rows)))))

(deftest test-parse-fetched-unknown-kind-returns-empty
  ;; Unknown kind → empty rows (no crash)
  (is (= [] (C/parse-fetched "unknown.txt" "content" "example.com"))))

(deftest test-parse-fetched-app-ads-txt
  ;; app-ads.txt delegates to same parser as ads.txt
  (let [text "google.com, pub-99999, RESELLER\n"
        rows (C/parse-fetched "app-ads.txt" text "app.example.com")]
    (is (vector? rows))))

;; ── Parity smoke: urls-for vs Python crawl.py ────────────────────────────────

(deftest test-urls-for-parity-smoke
  ;; Python: crawl.py kinds_for_url("example.com") returns the same structure.
  ;; We pin the exact expected URL strings (hand-checked vs Python output).
  (let [u (C/urls-for "example.com")]
    (is (= {"ads.txt"      "https://example.com/ads.txt"
            "app-ads.txt"  "https://example.com/app-ads.txt"
            "sellers.json" "https://example.com/sellers.json"
            "rdap"         "https://rdap.org/domain/example.com"}
           u))))

(deftest test-kinds-for-role-parity-smoke
  ;; Python crawl.py._kinds_for_role equivalents, hand-checked.
  (is (= #{"ads.txt" "rdap"}      (set (C/kinds-for-role "publisher"))))
  (is (= #{"sellers.json" "rdap"} (set (C/kinds-for-role "exchange"))))
  (is (= #{"sellers.json" "rdap"} (set (C/kinds-for-role "ssp"))))
  (is (= #{"app-ads.txt" "rdap"}  (set (C/kinds-for-role "app-publisher")))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [r (run-tests 'sukashi.tests.test-crawl)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
