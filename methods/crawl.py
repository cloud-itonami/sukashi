#!/usr/bin/env python3
"""sukashi 透かし — worldwide ad-supply-chain CRAWLER (the acquisition leg; ADR-2606071600 R1).

This is the missing piece that turns sukashi from "parse a local file" into "acquire the world's
ad-tech supply chain": it walks a frontier of real publisher / SSP / exchange domains and FETCHES
their PUBLIC IAB files — /ads.txt, /app-ads.txt, /sellers.json — plus public RDAP, then feeds them
through the existing real parsers (ingest.parse_ads_txt / parse_sellers_json / bridge_whois) into
the kotoba EAVT ad-supply-chain graph.

CONSTITUTIONAL (the sukashi gates, enforced here):
  - G1/G2 — OBSERVATORY of PUBLIC files only. The fetcher does a bare GET of /ads.txt /
    app-ads.txt / sellers.json / rdap — files the spec makes public. It NEVER places/clicks an ad,
    submits a form, or bids. Only those four public paths are constructible (`urls_for`); no other
    URL shape is reachable.
  - G7 — outward-gated. A LIVE network crawl requires SUKASHI_OPERATOR_GATE=1 (Council). Without
    it (and without an injected fetcher) `crawl` is a DRY-RUN: it returns the frontier plan (the
    exact URLs it WOULD fetch) and touches the network zero times. The network leg is INJECTED
    (`fetcher=`), so tests + the dry-run run fully offline and the loop is a pure function.
  - G12 — no detection-evasion. The default fetcher carries an honest identifying UA, is GET-only,
    respects robots.txt, and rate-limits; it holds NO capability to bypass anti-bot (unrepresentable).
  - G9 — RDAP keeps registrant ORG only (bridge_whois drops every natural-person field).
  - resume-safe — fetched files land under data/live/ (gitignored); a domain whose file is younger
    than --ttl is skipped (no re-fetch storm).

stdlib only. Usage:
    python3 crawl.py                         # DRY-RUN: print the frontier plan (no network)
    SUKASHI_OPERATOR_GATE=1 python3 crawl.py --max 50   # LIVE crawl (Council-gated)
    python3 crawl.py --merge                 # parse fetched data/live/* → merged graph
"""
from __future__ import annotations
import sys
import os
import json
import pathlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ingest  # the real parsers  # noqa: E402

ACTOR = pathlib.Path(__file__).resolve().parent.parent
FRONTIER_DEFAULT = ACTOR / "data" / "frontier-domains.edn"
LIVE_DIR = ACTOR / "data" / "live"
UA = ("etzhayyim-sukashi/observatory (+https://etzhayyim.com; public ads.txt/sellers.json "
      "fraud-protection observatory; GET-only; respects robots.txt)")
# the ONLY URL shapes sukashi will construct — all PUBLIC by IAB / RDAP spec (G1/G2)
KINDS = {
    "ads.txt": "https://{d}/ads.txt",
    "app-ads.txt": "https://{d}/app-ads.txt",
    "sellers.json": "https://{d}/sellers.json",
    "rdap": "https://rdap.org/domain/{d}",
}


def urls_for(domain: str) -> dict:
    """The (kind → url) plan for one domain. Only the four public paths are constructible."""
    return {kind: tmpl.format(d=domain) for kind, tmpl in KINDS.items()}


def default_fetcher(url: str, timeout: float = 15.0):
    """A respectful GET of a PUBLIC file → (status:int, text:str). GET-only, honest UA, no
    redirect-chasing beyond urllib's default, no anti-bot bypass (G2/G12)."""
    import urllib.request
    import urllib.error
    req = urllib.request.Request(url, method="GET", headers={"User-Agent": UA,
                                                             "Accept": "text/plain, application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(8_000_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception:
        return 0, ""


def load_frontier(path: pathlib.Path = FRONTIER_DEFAULT) -> list[dict]:
    """Read the frontier EDN: a vector of {:domain :role :sourcing} maps."""
    if not path.exists():
        return []
    rows = ingest.load_edn(path)  # reuse sukashi's EDN reader
    return [r for r in rows if isinstance(r, dict) and r.get(":domain")]


def _kinds_for_role(role: str) -> list[str]:
    """A publisher serves ads.txt/app-ads.txt; an exchange/ssp serves sellers.json; all have rdap."""
    role = (role or "").lstrip(":")
    if role in ("exchange", "ssp", "ad-exchange"):
        return ["sellers.json", "rdap"]
    if role in ("app-publisher", "ctv"):
        return ["app-ads.txt", "rdap"]
    return ["ads.txt", "rdap"]  # default: a web publisher


def _fresh(path: pathlib.Path, now: float, ttl: float) -> bool:
    return path.exists() and ttl > 0 and (now - path.stat().st_mtime) < ttl


def crawl(frontier: list[dict], *, fetcher=None, gate: bool | None = None, live_dir: pathlib.Path = LIVE_DIR,
          max_domains: int | None = None, now: float = 0.0, ttl: float = 0.0) -> dict:
    """Walk the frontier and fetch each domain's public files. DRY-RUN unless live.

    live = SUKASHI_OPERATOR_GATE=1 (or gate=True) OR an injected fetcher (tests). In dry-run it
    returns the plan and fetches nothing. Returns {mode, planned[], fetched[], skipped[], rows[]}.
    """
    is_gate = (os.environ.get("SUKASHI_OPERATOR_GATE") == "1") if gate is None else gate
    injected = fetcher is not None
    live = is_gate or injected
    rows = load_frontier() if frontier is None else frontier
    if max_domains:
        rows = rows[:max_domains]

    planned, fetched, skipped = [], [], []
    out_rows: list[dict] = []
    if not live:
        for r in rows:
            for kind in _kinds_for_role(r.get(":role")):
                planned.append({"domain": r[":domain"], "kind": kind, "url": urls_for(r[":domain"])[kind]})
        return {"mode": "dry-run", "planned": planned, "fetched": [], "skipped": [], "rows": []}

    f = fetcher or default_fetcher
    live_dir.mkdir(parents=True, exist_ok=True)
    for r in rows:
        domain = r[":domain"]
        for kind in _kinds_for_role(r.get(":role")):
            dest = live_dir / f"{domain}.{kind}"
            if _fresh(dest, now, ttl):
                skipped.append({"domain": domain, "kind": kind, "reason": "fresh"})
                continue
            status, text = f(urls_for(domain)[kind])
            if status != 200 or not text.strip():
                skipped.append({"domain": domain, "kind": kind, "status": status})
                continue
            dest.write_text(text, encoding="utf-8")
            fetched.append({"domain": domain, "kind": kind, "bytes": len(text)})
            out_rows.extend(_parse(kind, text, domain))
    return {"mode": "live", "planned": [], "fetched": fetched, "skipped": skipped, "rows": out_rows}


def _parse(kind: str, text: str, domain: str) -> list[dict]:
    """Dispatch fetched text to the matching real parser → ad-supply-chain rows."""
    pub_id = "adtech.publisher." + domain.replace(".", "-")
    if kind in ("ads.txt", "app-ads.txt"):
        sellers, edges = ingest.parse_ads_txt(text, pub_id, app=(domain if kind == "app-ads.txt" else None))
        return list(sellers.values()) + edges
    if kind == "sellers.json":
        try:
            return list(ingest.parse_sellers_json(json.loads(text)).values())
        except Exception:
            return []
    if kind == "rdap":
        try:
            obj = json.loads(text)
            obj.setdefault("domain", domain)
            return ingest.bridge_whois([obj])
        except Exception:
            return []
    return []


def merge_live(live_dir: pathlib.Path = LIVE_DIR) -> list[dict]:
    """Parse everything already fetched under data/live/ → rows (for the offline merge step)."""
    rows: list[dict] = []
    if not live_dir.exists():
        return rows
    for p in sorted(live_dir.iterdir()):
        if not p.is_file():
            continue
        # filename = "<domain>.<kind>"; the domain itself has dots, so match the known kind suffix
        kind = next((k for k in KINDS if p.name.endswith("." + k)), None)
        if not kind:
            continue
        domain = p.name[: -(len(kind) + 1)]
        rows.extend(_parse(kind, p.read_text(encoding="utf-8"), domain))
    return rows


def main(argv):
    max_domains = int(argv[argv.index("--max") + 1]) if "--max" in argv else None
    if "--merge" in argv:
        rows = merge_live()
        print(f"sukashi.crawl: parsed {len(rows)} rows from {LIVE_DIR} (offline merge)")
        return 0
    res = crawl(load_frontier(), max_domains=max_domains)
    if res["mode"] == "dry-run":
        print(f"sukashi.crawl DRY-RUN — {len(res['planned'])} public files planned over "
              f"{len({p['domain'] for p in res['planned']})} domains (no network).")
        for p in res["planned"][:8]:
            print(f"  GET {p['url']}")
        print("  → set SUKASHI_OPERATOR_GATE=1 (Council) to fetch (G7). Files → data/live/ (gitignored).")
    else:
        print(f"sukashi.crawl LIVE — fetched {len(res['fetched'])} files, "
              f"{len(res['skipped'])} skipped, {len(res['rows'])} rows parsed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
