#!/usr/bin/env python3
"""sukashi 透かし — kotoba-native fleet driver (the (B) pseudo-daemon). ADR-2606161645.

The fleet Mac minis have no Aqua GUI session (console-user=root), so a per-user LaunchAgent cannot
run there and crontab is TCC-blocked over SSH (verified 2026-06-16). Until the operator enables the
GUI-independent LaunchDaemon path (A, see deploy/), this driver gives continuous operation FROM a
machine that IS interactive (the founder's mac): on each tick it SSHes the sukashi-assigned fleet
nodes, runs the heartbeat (`python3 ~/sukashi-run/cell.py`), and RECORDS each run as a
content-addressed `:fleet.run/*` datom on a LOCAL kotoba ops Datom log — kotoba is the canonical
record of what ran where (ADR-2605312345). Drive it on an interval with `/loop` (B) or a local
LaunchAgent.

Constitutional: the SSH leg is observational/own-fleet only (runs the member's OWN actor on the
member's OWN Mac minis); the heartbeat itself does no live crawl (G7 keeps acquisition gated). The
network leg (`runner`) and the tailscale status (`status_text`) are INJECTED → offline tests, no
wall clock (as_of injected) → deterministic commit-DAG.

stdlib only. Usage:
    python3 fleet_drive.py                 # drive the default targets, append to the ops log
    python3 fleet_drive.py --nodes issachar,dan
"""
from __future__ import annotations
import re
import sys
import pathlib
import subprocess

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "methods"))
import kotoba  # noqa: E402

ACTOR = pathlib.Path(__file__).resolve().parent.parent
OPS_LOG = ACTOR / "data" / "fleet-ops.kotoba.edn"
# sukashi cells are placed on issachar (ingest/weave) + dan (persist) in fleet.edn
DEFAULT_TARGETS = ["issachar"]
REMOTE_CMD = "cd ~/sukashi-run && python3 cell.py"
BASE_AS_OF = 2606161645

_HB_RE = re.compile(r"cycle\s+(\d+):.*chain\s+(ok|BROKEN).*?→\s*([0-9a-f]+)")


def parse_tailscale_status(text: str) -> dict:
    """Parse `tailscale status` → {node: ip}. Lines: '<ip> <node> <user> <os> <status...>'."""
    out = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 2 and re.match(r"^\d+\.\d+\.\d+\.\d+$", parts[0]):
            out[parts[1]] = parts[0]
    return out


def parse_heartbeat(output: str) -> dict:
    """Pull {cycle, chain_ok, cid} from the cell's stdout line (last match wins)."""
    m = None
    for m in _HB_RE.finditer(output):
        pass
    if not m:
        return {"cycle": None, "chain_ok": None, "cid": None}
    return {"cycle": int(m.group(1)), "chain_ok": m.group(2) == "ok", "cid": m.group(3)}


def default_runner(node: str, ip: str, timeout: float = 60.0) -> tuple[bool, str]:
    """SSH the node as the tribe user and run the heartbeat. Returns (reachable, combined-output)."""
    try:
        p = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
             "-o", "StrictHostKeyChecking=accept-new", f"{node}@{ip}", REMOTE_CMD],
            capture_output=True, text=True, timeout=timeout)
        return p.returncode == 0, (p.stdout + p.stderr)
    except Exception as e:  # noqa: BLE001 — unreachable / timeout
        return False, f"{type(e).__name__}: {e}"


def drive(targets: list, *, status_text: str, runner=None, as_of: int = BASE_AS_OF,
          log_path: pathlib.Path = OPS_LOG) -> dict:
    """Run the heartbeat on each reachable target node; append one :fleet.run/* tx to the ops log.

    runner / status_text are INJECTED (tests pass fakes → no ssh, no network)."""
    f = runner or default_runner
    nodes = parse_tailscale_status(status_text)
    results, datoms = [], []
    for node in targets:
        ip = nodes.get(node)
        if not ip:
            results.append({"node": node, "status": ":unreachable", "reason": "not-in-tailnet"})
            status = ":unreachable"
            hb = {"cycle": None, "chain_ok": None, "cid": None}
        else:
            ok, output = f(node, ip)
            hb = parse_heartbeat(output)
            status = ":ok" if (ok and hb["cid"]) else (":error" if ok else ":unreachable")
            results.append({"node": node, "ip": ip, "status": status, **hb})
        e = f"fleet.run.{node}.{as_of}"
        d = [kotoba._add(e, ":fleet.run/node", ":" + node),
             kotoba._add(e, ":fleet.run/cell", "SukashiObservatoryHeartbeatCell"),
             kotoba._add(e, ":fleet.run/as-of", as_of),
             kotoba._add(e, ":fleet.run/status", status)]
        if hb["cid"]:
            d.append(kotoba._add(e, ":fleet.run/head-cid", hb["cid"]))
        if hb["cycle"] is not None:
            d.append(kotoba._add(e, ":fleet.run/cycle", hb["cycle"]))
        if hb["chain_ok"] is not None:
            d.append(kotoba._add(e, ":fleet.run/chain-ok", bool(hb["chain_ok"])))
        datoms.extend(d)
    tx = kotoba.make_tx(datoms, tx_id=len(kotoba.read_log(log_path)) + 1, as_of=as_of,
                        prev_cid=kotoba.head_cid(log_path))
    kotoba.append_tx(tx, log_path)
    return {"results": results, "tx_cid": tx[":tx/cid"], "ops_head": kotoba.head_cid(log_path)}


def main(argv):
    targets = (argv[argv.index("--nodes") + 1].split(",")) if "--nodes" in argv else DEFAULT_TARGETS
    try:
        status_text = subprocess.run(["tailscale", "status"], capture_output=True, text=True,
                                     timeout=20).stdout
    except Exception:  # noqa: BLE001
        status_text = ""
    as_of = BASE_AS_OF + len(kotoba.read_log(OPS_LOG)) + 1
    res = drive(targets, status_text=status_text, as_of=as_of)
    for r in res["results"]:
        print(f"  {r['node']:10} {r['status']:13} "
              f"{('cycle ' + str(r.get('cycle')) + ' → ' + (r.get('cid') or '')[:16]) if r.get('cid') else ''}")
    print(f"sukashi fleet-drive → ops tx {res['tx_cid'][:16]}… (kotoba ops log: {OPS_LOG.name})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
