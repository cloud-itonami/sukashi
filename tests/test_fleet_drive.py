#!/usr/bin/env python3
"""sukashi 透かし — kotoba-native fleet driver tests (ADR-2606161645). Pure stdlib; ssh + tailscale
are INJECTED so every test runs OFFLINE."""
import sys
import pathlib
import tempfile

ACTOR_DIR = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ACTOR_DIR / "tools"))
sys.path.insert(0, str(ACTOR_DIR / "methods"))

import fleet_drive as fd  # noqa: E402
import kotoba  # noqa: E402

STATUS = """100.86.235.122  25mbair   com-junkawasaki@  macOS  -
100.89.204.30   issachar  com-junkawasaki@  macOS  -
100.98.142.59   dan       com-junkawasaki@  macOS  -
100.117.208.83  jacob     com-junkawasaki@  macOS  offline, last seen 7h ago
"""
HEARTBEAT_OUT = ("SukashiObservatoryHeartbeatCell cycle 3: 74 adtech · 28 auth edges · "
                 "12 fraud signals · 1 clusters · chain ok → bce186d10fc88968fd0e…\n")


def test_parse_tailscale_status():
    nodes = fd.parse_tailscale_status(STATUS)
    assert nodes["issachar"] == "100.89.204.30"
    assert nodes["dan"] == "100.98.142.59"
    assert "jacob" in nodes  # offline still has an IP row


def test_parse_heartbeat():
    hb = fd.parse_heartbeat(HEARTBEAT_OUT)
    assert hb["cycle"] == 3 and hb["chain_ok"] is True
    assert hb["cid"].startswith("bce186d10fc88968")


def test_drive_ok_records_fleet_run_datom():
    calls = []
    def runner(node, ip):
        calls.append((node, ip))
        return True, HEARTBEAT_OUT
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "ops.kotoba.edn"
        res = fd.drive(["issachar"], status_text=STATUS, runner=runner, as_of=2606161646, log_path=log)
        assert calls == [("issachar", "100.89.204.30")]
        assert res["results"][0]["status"] == ":ok"
        # the run is recorded as kotoba datoms (kotoba-native ops log)
        txs = kotoba.read_log(log)
        flat = [d for tx in txs for d in tx[":tx/datoms"]]
        assert any(d[2] == ":fleet.run/node" and d[3] == ":issachar" for d in flat)
        assert any(d[2] == ":fleet.run/head-cid" for d in flat)
        assert any(d[2] == ":fleet.run/status" and d[3] == ":ok" for d in flat)
        assert kotoba.verify_chain(log)["ok"]


def test_drive_unreachable_node_records_status():
    def runner(node, ip):
        return False, "ConnectTimeout"
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "ops.kotoba.edn"
        res = fd.drive(["issachar"], status_text=STATUS, runner=runner, as_of=2606161647, log_path=log)
        assert res["results"][0]["status"] == ":unreachable"
        flat = [d for tx in kotoba.read_log(log) for d in tx[":tx/datoms"]]
        assert any(d[2] == ":fleet.run/status" and d[3] == ":unreachable" for d in flat)


def test_node_not_in_tailnet_is_unreachable():
    res_log = pathlib.Path(tempfile.mkdtemp()) / "ops.kotoba.edn"
    res = fd.drive(["ghost"], status_text=STATUS, runner=lambda n, i: (True, HEARTBEAT_OUT),
                   as_of=2606161648, log_path=res_log)
    assert res["results"][0]["status"] == ":unreachable"


def test_ops_log_is_appendonly_chain():
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "ops.kotoba.edn"
        fd.drive(["issachar"], status_text=STATUS, runner=lambda n, i: (True, HEARTBEAT_OUT),
                 as_of=2606161646, log_path=log)
        fd.drive(["issachar"], status_text=STATUS, runner=lambda n, i: (True, HEARTBEAT_OUT),
                 as_of=2606161647, log_path=log)
        assert len(kotoba.read_log(log)) == 2
        assert kotoba.verify_chain(log)["ok"]


def test_determinism():
    with tempfile.TemporaryDirectory() as dr1, tempfile.TemporaryDirectory() as dr2:
        a = fd.drive(["issachar"], status_text=STATUS, runner=lambda n, i: (True, HEARTBEAT_OUT),
                     as_of=2606161646, log_path=pathlib.Path(dr1) / "o.edn")
        b = fd.drive(["issachar"], status_text=STATUS, runner=lambda n, i: (True, HEARTBEAT_OUT),
                     as_of=2606161646, log_path=pathlib.Path(dr2) / "o.edn")
        assert a["tx_cid"] == b["tx_cid"], "ops tx is not deterministic"


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"\n{len(fns)} passed")
