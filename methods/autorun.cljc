(ns sukashi.methods.autorun
  "autorun.py — sukashi AUTONOMOUS ad-tech-supply-chain + fraud-observatory heartbeat on the
  kotoba Datom log. ADR-2606071600. 1:1 Clojure port of `methods/autorun.py`.

  Each heartbeat the actor runs its whole OBSERVATORY pipeline ITSELF, with no human in the loop:
    observe (load the OFFLINE merged ad-supply-chain graph, G7: no live crawl) → classify
      → analyze (authorization-handshake integrity / account-id collision / delivery-infra
        concentration / shared-infra scam-network clustering / category load — aggregate-first, G4
        non-adjudicating, fraud signals :synthesized on fictional entities only)
      → PERSIST a content-addressed transaction to the append-only LOCAL kotoba Datom log
        (graph datoms + derived :adsupply/* + :adfraud/* signals), linking the previous tx's CID.

  Constitutional posture is preserved by construction: OBSERVATORY not an ad network (G2); public
  IAB transparency files only (G1); REAL firms carry NO fraud signal, every signal is
  non-adjudicating + :synthesized (G4); no personal PII (G9). Deterministic / resume-safe (cycle
  drives tx-id + as-of) and append-only. Live full-web crawl (ingest + SUKASHI_OPERATOR_GATE) and
  the live-node push (transact) stay Council Lv6+ + operator gated. No external I/O.

  House style: requires only the GOOD sibling .cljc ports (analyze + kotoba + sukashi-edn).
  (The Python `__main__` argparse demo printer is preserved behind #?(:clj …) as -main.)"
  (:require [sukashi.methods.analyze :as analyze]
            [sukashi.methods.kotoba :as kotoba]
            [sukashi.methods.sukashi-edn :as edn]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str])))

(def base-as-of 20260608)

#?(:clj (def here (-> *file* io/file .getAbsoluteFile .getParentFile)))
#?(:clj (def data (when here (io/file (.getParentFile here) "data"))))
#?(:clj (def merged (when data (io/file data "ad-supply-chain.merged.kotoba.edn"))))
#?(:clj (def seed (when data (io/file data "seed-ad-supply-chain.kotoba.edn"))))
#?(:clj (def log-default (when data (io/file data "sukashi.datoms.kotoba.edn"))))

#?(:clj
   (defn- graph-path
     "_graph_path: explicit override else MERGED if it exists else SEED."
     [graph-path]
     (cond
       (some? graph-path) (io/file (str graph-path))
       (and merged (.exists merged)) merged
       :else seed)))

#?(:clj
   (defn run-cycle
     "One autonomous heartbeat: observe → classify → analyze → persist a content-addressed Datom
     transaction (graph + derived :adsupply/* + :adfraud/* signals). cycle drives tx-id + as-of."
     ([cycle] (run-cycle cycle nil log-default))
     ([cycle graph log-path]
      (let [rows (kotoba/load-edn-ordered (graph-path graph)) ; observe — OFFLINE merged graph (G7: no live crawl)
            {:keys [adtech auth creatives delivery fraud]} (edn/classify rows)
            a (analyze/analyze adtech auth creatives delivery fraud) ; aggregate observatory signal (G4)
            datoms (into (kotoba/graph-datoms rows) (kotoba/derived-datoms a))
            tx (kotoba/make-tx datoms :tx-id cycle :as-of (+ base-as-of cycle)
                               :prev-cid (kotoba/head-cid log-path))
            cid (kotoba/append-tx tx log-path)        ; PERSIST to append-only LOCAL kotoba log
            top-cluster (first (get a "clusters"))]
        {"cycle" cycle
         "adtech" (count adtech)
         "auth_edges" (count auth)
         "delivery_edges" (count delivery)
         "fraud_signals" (count fraud)
         "scam_clusters" (count (get a "clusters"))
         "top_cluster_members" (if top-cluster (:members top-cluster) 0)
         "datoms" (count datoms)
         "cid" cid}))))

#?(:clj
   (defn run-autonomous
     ([] (run-autonomous 3 nil log-default))
     ([cycles] (run-autonomous cycles nil log-default))
     ([cycles graph log-path]
      (let [beats (mapv #(run-cycle % graph log-path) (range 1 (inc cycles)))]
        {"cycles" cycles
         "beats" beats
         "log_length" (count (kotoba/read-log log-path))
         "head_cid" (kotoba/head-cid log-path)
         "chain" (kotoba/verify-chain log-path)}))))

#?(:clj
   (defn -main
     "CLI entry: run N autonomous heartbeats → LOCAL kotoba Datom log. --cycles/--graph/--log/--fresh
     (file I/O at the edge)."
     [& argv]
     (let [argv (vec argv)
           arg-after (fn [flag dflt] (let [i (.indexOf argv flag)]
                                       (if (>= i 0) (nth argv (inc i)) dflt)))
           cycles (let [v (arg-after "--cycles" nil)] (if v (Long/parseLong v) 3))
           graph (when (some #{"--graph"} argv) (arg-after "--graph" nil))
           log-path (io/file (arg-after "--log" (str log-default)))]
       (when (and (some #{"--fresh"} argv) (.exists log-path)) (.delete log-path))
       (let [res (run-autonomous cycles graph log-path)]
         (println (str "# sukashi — AUTONOMOUS ad-supply-chain + fraud observatory over the kotoba "
                       "Datom log (offline ingest, LOCAL persist; live crawl / live-node push stays "
                       "G7/G11-gated)\n"))
         (doseq [bt (get res "beats")]
           (println (str "  ♥ cycle " (get bt "cycle") ": " (get bt "adtech") " adtech / "
                         (get bt "auth_edges") " auth-edges / " (get bt "delivery_edges") " delivery / "
                         (get bt "fraud_signals") " fraud-sig · scam-clusters " (get bt "scam_clusters")
                         " (top " (get bt "top_cluster_members") " members) +" (get bt "datoms")
                         " datoms → cid " (subs (get bt "cid") 0 14) "…")))
         (let [ch (get res "chain")]
           (println (str "\n  log: " (get res "log_length") " tx · head "
                         (subs (get res "head_cid") 0 14) "… · chain "
                         (if (get ch "ok") "OK ✓" (str "BROKEN at " (get ch "broken_at")))
                         " · observatory-only / non-adjudicating (G2/G4)")))))))
