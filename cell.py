#!/usr/bin/env python3
"""sukashi cell entry — kotodama-cell-runner contract (ADR-2605192415 §7.1, ADR-2606071600/601).

Registered in 50-infra/cluster/murakumo/cell-runner/cells.edn as SukashiObservatoryHeartbeatCell
(node issachar, cron 42 * * * *, healthz 13081) so the ad-supply-chain observatory actually RUNS as
a Tier-1 launchd daemon on the Murakumo Mac mini fleet (the same shape as MimamoriHeartbeatCell /
ShinkaHeartbeatCell). `fire()` runs ONE deterministic heartbeat (pattern 2606071600):

    observe the OFFLINE merged graph (seed + any bridged/crawled files) → classify →
    analyze auth-handshake integrity / delivery-infra concentration / scam-network clusters →
    PERSIST one content-addressed tx to the actor-local kotoba commit-DAG → chain verified.

NO external I/O in the heartbeat (G7): the live WORLDWIDE CRAWL (methods/crawl.py +
SUKASHI_OPERATOR_GATE) and the live-node push (methods/transact.py) stay separate, operator-gated
invocations — the always-on cell only re-analyzes + persists what has already been acquired. Every
persisted fraud signal stays :non-adjudicating + :synthesized (G4); the summary is aggregate-only.
"""
from __future__ import annotations

import pathlib
import sys

_HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE / "methods"))


def fire(log_path: str | None = None) -> dict:
    """One observatory heartbeat. Idempotent per log state (cycle derives from log length)."""
    from autorun import run_cycle
    from kotoba import LOG_DEFAULT, read_log, head_cid, verify_chain

    target = pathlib.Path(log_path) if log_path else LOG_DEFAULT
    cycle = len(read_log(target)) + 1          # resume-safe (continues the local commit-DAG)
    summary = run_cycle(cycle, None, target)   # graph_path None → offline merged graph / seed (G7)
    chain = verify_chain(target)
    summary["chain_ok"] = chain.get("ok")
    summary["head"] = head_cid(target)
    print(f"SukashiObservatoryHeartbeatCell cycle {summary['cycle']}: "
          f"{summary['adtech']} adtech · {summary['auth_edges']} auth edges · "
          f"{summary['fraud_signals']} fraud signals · {summary['scam_clusters']} clusters · "
          f"chain {'ok' if chain.get('ok') else 'BROKEN'} → {str(summary['cid'])[:16]}…")
    return summary


if __name__ == "__main__":
    fire()
