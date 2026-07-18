(ns sukashi.cell
  "sukashi cell entry — kotodama-cell-runner contract (ADR-2605192415 §7.1, ADR-2606071600/601).
  1:1 port of cell.py.

  Registered in 50-infra/cluster/murakumo/cell-runner/cells.edn as SukashiObservatoryHeartbeatCell
  (node issachar, cron 42 * * * *, healthz 13081) so the ad-supply-chain observatory actually RUNS
  as a Tier-1 launchd daemon on the Murakumo Mac mini fleet. `fire` runs ONE deterministic
  heartbeat (pattern 2606071600):

    observe the OFFLINE merged graph (seed + any bridged/crawled files) → classify →
    analyze auth-handshake integrity / delivery-infra concentration / scam-network clusters →
    PERSIST one content-addressed tx to the actor-local kotoba commit-DAG → chain verified.

  NO external I/O in the heartbeat (G7): the live WORLDWIDE CRAWL (methods/crawl + the operator
  gate) and the live-node push (methods/transact) stay separate, operator-gated invocations — the
  always-on cell only re-analyzes + persists what has already been acquired. Every persisted fraud
  signal stays :non-adjudicating + :synthesized (G4); the summary is aggregate-only."
  (:require [sukashi.methods.autorun :as autorun]
            [sukashi.methods.kotoba :as kotoba]))

(defn fire
  "One observatory heartbeat. Idempotent per log state (cycle derives from log length).
  graph_path None → offline merged graph / seed (G7)."
  ([] (fire nil))
  ([log-path]
   (let [target  (or log-path autorun/log-default)
         cycle   (inc (count (kotoba/read-log target)))   ; resume-safe (continues the commit-DAG)
         summary (autorun/run-cycle cycle nil target)      ; offline merged graph / seed (G7)
         chain   (kotoba/verify-chain target)
         summary (assoc summary
                        "chain_ok" (get chain "ok")
                        "head"     (kotoba/head-cid target))]
     (println (str "SukashiObservatoryHeartbeatCell cycle " (get summary "cycle") ": "
                   (get summary "adtech") " adtech · " (get summary "auth_edges") " auth edges · "
                   (get summary "fraud_signals") " fraud signals · "
                   (get summary "scam_clusters") " clusters · "
                   "chain " (if (get chain "ok") "ok" "BROKEN") " → "
                   (subs (str (get summary "cid")) 0 (min 16 (count (str (get summary "cid"))))) "…"))
     summary)))
