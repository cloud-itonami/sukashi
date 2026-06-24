# sukashi fleet continuous-operation (ADR-2606161645)

Two paths to run the sukashi observatory heartbeat **continuously**, kotoba-native. Background: the
Murakumo Mac minis sit at the login window with **no Aqua GUI session** (console-user=root, verified
2026-06-16) — so a per-user **LaunchAgent cannot run** and **crontab is TCC-blocked over SSH**. That
is also why the canonical `kotodama-cell-runner` LaunchAgent is not actually running fleet-wide.

## A — fleet LaunchDaemon (the canonical headless fix; operator + sudo)

`com.etzhayyim.sukashi-heartbeat.daemon.plist` is a **system-domain LaunchDaemon** (not an Agent),
so it runs headlessly without a GUI session. Per node (operator, at the console or an SSH session
with sudo):

```bash
# prerequisite: sukashi deployed to ~/sukashi-run on the node (see `bb sukashi:fleet-stage`)
sed -e "s/@@USER@@/issachar/g" -e "s#@@HOME@@#/Users/issachar#g" \
    com.etzhayyim.sukashi-heartbeat.daemon.plist | sudo tee /Library/LaunchDaemons/com.etzhayyim.sukashi-heartbeat.plist
sudo launchctl bootstrap system /Library/LaunchDaemons/com.etzhayyim.sukashi-heartbeat.plist
```

The cell is pure-stdlib `python3` (no `uv`/venv). It does **no live crawl** (G7); it re-analyzes the
already-acquired graph and appends one content-addressed tx to the actor-local kotoba commit-DAG.

(Alternative to a LaunchDaemon: enable **auto-login** for the tribe user at the console so an Aqua
session exists — then the canonical `cell-runner/install.sh` LaunchAgent path works too.)

## B — local driver + /loop (interim pseudo-daemon; works today, no console access)

`tools/fleet_drive.py` (`bb sukashi:fleet-drive`) runs FROM an interactive machine (the founder's
mac, which HAS a GUI session): each tick it SSHes the sukashi-assigned nodes, runs the heartbeat,
and records each run as a `:fleet.run/*` datom on a LOCAL kotoba ops Datom log (`data/fleet-ops.kotoba.edn`)
— kotoba is the canonical record of what ran where.

**Durable (recommended for B):** install `com.etzhayyim.sukashi-fleet-drive.agent.plist` as a
per-user **LaunchAgent** on that interactive machine — it loads because the machine is logged into
the Aqua GUI (the headless fleet Macs are not, which is why they need the LaunchDaemon, path A).
See the plist header for the `sed` install; it fires hourly at `:42`, survives terminal close, and
is reversible (`launchctl unload` + `rm`). Its `WorkingDirectory` must be a CURRENT checkout/worktree
(with `bb.edn` + `tools/fleet_drive.py`) that persists — do not `worktree cleanup` it while loaded.
(Cloud schedule is NOT an option: a cloud runner has no Tailscale access to the private fleet.)

**Quick/ephemeral:** `/loop 1h bb sukashi:fleet-drive` — runs only while the terminal stays open.

Either way this is honest interim continuity (alive only while the driving machine is up); **A is
the real fleet daemon**.

## kotoba-native design

Both paths converge on the kotoba Datom log as canonical state:
- the **per-node** heartbeat → the node-local `data/sukashi.datoms.kotoba.edn` commit-DAG.
- the **fleet** driver → the `:fleet.run/*` ops commit-DAG (node · cell · cycle · head-cid · chain-ok
  · status · as-of), tamper-evident via `verify_chain`.
Both are gitignored (operational/node state), append-only, deterministic.
