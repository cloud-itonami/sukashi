(ns sukashi.repository-contract-test
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [kotoba.lang.text :as str] [clojure.test :refer [deftest is]]))
(defn files [] (filter #(.isFile %) (file-seq (io/file "."))))
(deftest canonical-edn-is-readable
  (doseq [file (files) :when (str/ends-with? (.getName file) ".edn")]
    (is (some? (edn/read-string (slurp file))) (.getPath file))))
(deftest external-formats-are-wire-only
  (doseq [file (files) :let [path (.getPath file)] :when (re-find #"\.(?:json|jsonld|bpmn)$" path)]
    (is (or (str/includes? path "/wire/") (str/ends-with? path "/.well-known/did.json")) path)))
(deftest deprecated-runtime-is-absent
  (doseq [file (files)] (is (not (re-find #"\.(?:go|sh)$" (.getName file))) (.getPath file))))
