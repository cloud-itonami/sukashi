#!/usr/bin/env python3
"""sukashi 透かし — fleet staging (ADR-2606161645). Deploys the pure-stdlib sukashi actor to a
fleet node's ~/sukashi-run via git-archive + tar-over-ssh (no scp/sftp needed; works over Tailscale
SSH). The node then runs the heartbeat with system python3 — no uv/venv/monorepo-clone required.

Observational/own-fleet only: it copies the member's OWN actor to the member's OWN Mac minis. The
heartbeat there does NO live crawl (G7). Network leg INJECTED for tests.

    python3 fleet_stage.py --nodes issachar          # stage to issachar:~/sukashi-run
    python3 fleet_stage.py --nodes issachar,dan --ref origin/main
"""
from __future__ import annotations
import sys
import pathlib
import subprocess

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from fleet_drive import parse_tailscale_status  # reuse the resolver  # noqa: E402

ACTOR_REL = "20-actors/sukashi"
REMOTE_DIR = "~/sukashi-run"
# tar of `20-actors/sukashi/...` → strip 2 leading path components → ~/sukashi-run/{methods,data,cell.py}
STAGE_REMOTE = (f"rm -rf {REMOTE_DIR} && mkdir -p {REMOTE_DIR} && "
                f"tar -C {REMOTE_DIR} --strip-components=2 -xf - && echo STAGED")


def stage_node(node: str, ip: str, *, ref: str = "origin/main", runner=None) -> dict:
    """git archive <ref> 20-actors/sukashi | ssh node@ip 'extract to ~/sukashi-run'. Returns status."""
    if runner is not None:                       # injected (tests): no git/ssh
        return runner(node, ip, ref)
    try:
        arch = subprocess.run(["git", "archive", ref, ACTOR_REL], capture_output=True, timeout=60)
        if arch.returncode != 0:
            return {"node": node, "status": ":error", "reason": arch.stderr.decode()[:120]}
        p = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
             "-o", "StrictHostKeyChecking=accept-new", f"{node}@{ip}", STAGE_REMOTE],
            input=arch.stdout, capture_output=True, timeout=90)
        ok = p.returncode == 0 and b"STAGED" in p.stdout
        return {"node": node, "status": ":ok" if ok else ":error",
                "reason": "" if ok else (p.stdout + p.stderr).decode()[:160]}
    except Exception as e:  # noqa: BLE001
        return {"node": node, "status": ":unreachable", "reason": f"{type(e).__name__}: {e}"}


def stage(targets: list, *, status_text: str, ref: str = "origin/main", runner=None) -> list:
    nodes = parse_tailscale_status(status_text)
    out = []
    for node in targets:
        ip = nodes.get(node)
        out.append({"node": node, "status": ":unreachable", "reason": "not-in-tailnet"}
                   if not ip else stage_node(node, ip, ref=ref, runner=runner))
    return out


def main(argv):
    targets = (argv[argv.index("--nodes") + 1].split(",")) if "--nodes" in argv else ["issachar"]
    ref = argv[argv.index("--ref") + 1] if "--ref" in argv else "origin/main"
    try:
        status_text = subprocess.run(["tailscale", "status"], capture_output=True, text=True,
                                     timeout=20).stdout
    except Exception:  # noqa: BLE001
        status_text = ""
    for r in stage(targets, status_text=status_text, ref=ref):
        print(f"  {r['node']:10} {r['status']:13} {r.get('reason','')}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
