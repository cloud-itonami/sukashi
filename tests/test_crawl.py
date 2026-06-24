#!/usr/bin/env python3
"""sukashi 透かし — crawler (acquisition leg) tests (ADR-2606071600 R1). Pure stdlib; the network
leg is INJECTED so every test runs OFFLINE (no real fetch, no operator gate)."""
import sys
import pathlib
import tempfile

ACTOR_DIR = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ACTOR_DIR / "methods"))

import crawl  # noqa: E402

ADS_TXT = "google.com, pub-123, DIRECT, f08c47fec0942fa0\npubmatic.com, 99, RESELLER\n# comment\n"
SELLERS_JSON = '{"sellers":[{"seller_id":"99","name":"PubMatic","domain":"pubmatic.com","seller_type":"intermediary"}]}'
RDAP = '{"ldhName":"pubmatic.com","registrant_org":"PubMatic Inc","registrar":"MarkMonitor","registrant_name":"John Doe"}'

FRONTIER = [
    {":domain": "nytimes.com", ":role": ":publisher", ":sourcing": ":authoritative"},
    {":domain": "pubmatic.com", ":role": ":exchange", ":sourcing": ":authoritative"},
]


def _fake_fetcher(calls):
    def f(url):
        calls.append(url)
        if url.endswith("/ads.txt"):
            return 200, ADS_TXT
        if url.endswith("/sellers.json"):
            return 200, SELLERS_JSON
        if "rdap.org" in url:
            return 200, RDAP
        return 404, ""
    return f


def test_urls_only_construct_public_paths():
    """G1/G2: only the four PUBLIC IAB/RDAP paths are constructible — no other URL shape."""
    u = crawl.urls_for("example.com")
    assert set(u) == {"ads.txt", "app-ads.txt", "sellers.json", "rdap"}
    assert u["ads.txt"] == "https://example.com/ads.txt"
    assert u["sellers.json"] == "https://example.com/sellers.json"
    assert u["rdap"] == "https://rdap.org/domain/example.com"


def test_dry_run_touches_network_zero_times():
    """G7: with no gate and no injected fetcher, crawl is a DRY-RUN — it plans, never fetches."""
    calls = []
    # even though we *could* pass a fetcher, dry-run is when gate=False AND fetcher=None
    res = crawl.crawl(FRONTIER, fetcher=None, gate=False)
    assert res["mode"] == "dry-run"
    assert res["fetched"] == [] and res["rows"] == []
    assert len(res["planned"]) >= 2
    assert all(p["url"].startswith("https://") for p in res["planned"])
    assert calls == []  # the fetcher was never built/called


def test_role_to_kinds_mapping():
    plan_pub = crawl._kinds_for_role(":publisher")
    plan_exch = crawl._kinds_for_role(":exchange")
    assert "ads.txt" in plan_pub and "sellers.json" not in plan_pub
    assert "sellers.json" in plan_exch and "ads.txt" not in plan_exch
    assert "rdap" in plan_pub and "rdap" in plan_exch


def test_live_crawl_injected_fetcher_parses_rows():
    """With an injected fetcher (offline), a live crawl fetches + parses into real EAVT rows."""
    with tempfile.TemporaryDirectory() as dr:
        calls = []
        res = crawl.crawl(FRONTIER, fetcher=_fake_fetcher(calls), live_dir=pathlib.Path(dr))
        assert res["mode"] == "live"
        assert res["fetched"], "nothing fetched with an injected fetcher"
        # publisher → ads.txt sellers+edges; exchange → sellers.json sellers; both → rdap delivery
        kinds = {d["kind"] for d in res["fetched"]}
        assert "ads.txt" in kinds and "sellers.json" in kinds and "rdap" in kinds
        # rows include an auth edge from the parsed ads.txt and an adtech seller
        assert any(":adauth.edge/id" in r for r in res["rows"]), "no auth edge parsed"
        assert any(":adtech/id" in r for r in res["rows"]), "no adtech seller parsed"
        # files actually written to the live dir
        assert (pathlib.Path(dr) / "nytimes.com.ads.txt").exists()


def test_g9_rdap_keeps_org_drops_person():
    """G9: the RDAP bridge keeps registrant ORG only — the natural-person field never enters."""
    with tempfile.TemporaryDirectory() as dr:
        res = crawl.crawl([{":domain": "pubmatic.com", ":role": ":exchange"}],
                          fetcher=_fake_fetcher([]), live_dir=pathlib.Path(dr))
        deliv = [r for r in res["rows"] if ":addelivery.edge/whois-org" in r]
        assert deliv and deliv[0][":addelivery.edge/whois-org"] == "PubMatic Inc"
        for r in res["rows"]:
            for person in (":addelivery.edge/registrant-name", "registrant_name", "name", "email"):
                assert person not in r, f"G9 violation: person field {person} entered the graph"


def test_resume_skips_fresh_files():
    """resume-safe: a file younger than ttl is skipped (no re-fetch storm)."""
    with tempfile.TemporaryDirectory() as dr:
        live = pathlib.Path(dr)
        (live / "pubmatic.com.sellers.json").write_text(SELLERS_JSON, encoding="utf-8")
        calls = []
        res = crawl.crawl([{":domain": "pubmatic.com", ":role": ":exchange"}],
                          fetcher=_fake_fetcher(calls), live_dir=live, now=1000.0, ttl=10_000.0)
        assert any(s.get("reason") == "fresh" and s["kind"] == "sellers.json" for s in res["skipped"])
        assert "https://pubmatic.com/sellers.json" not in calls  # not re-fetched


def test_merge_live_parses_fetched_dir():
    with tempfile.TemporaryDirectory() as dr:
        live = pathlib.Path(dr)
        (live / "nytimes.com.ads.txt").write_text(ADS_TXT, encoding="utf-8")
        rows = crawl.merge_live(live)
        assert any(":adauth.edge/id" in r for r in rows)


def test_max_domains_caps_frontier():
    res = crawl.crawl(FRONTIER, fetcher=None, gate=False, max_domains=1)
    assert len({p["domain"] for p in res["planned"]}) == 1


def test_frontier_file_loads_real_domains():
    fr = crawl.load_frontier()
    assert len(fr) >= 20, f"frontier too small: {len(fr)}"
    assert all(r.get(":domain") for r in fr)
    roles = {r.get(":role") for r in fr}
    assert {":publisher", ":exchange"} <= roles, f"frontier missing core roles: {roles}"


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"\n{len(fns)} passed")
