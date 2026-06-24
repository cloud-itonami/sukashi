# sukashi 透かし — agent reference

> Ad-tech supply-chain + delivery-infra + fraud-network observatory. Tier-B, R0 design-only. ADR-2606071600.
> Read the repo-root `CLAUDE.md` first; this file only adds actor-local rules.

## Identity

- **DID**: `did:web:etzhayyim.com:actor:sukashi` (resolvable via INFRA_ACTORS).
- **Glyph**: 透かし — a *watermark* / holding paper up to the light. ads.txt + sellers.json are
  the ad-tech industry's own authenticity watermark (who is genuinely authorized to sell whose
  inventory); sukashi holds the supply chain up to the light to *see through* deceptive ads.
- **Role**: the *ad-tech-supply-chain + fraud* face of the observation upper layer, the sibling of
  `akashi` 証 (platform ad-LIBRARY disclosure). akashi covers what platforms publish about ads;
  sukashi covers the layer akashi is constitutionally bounded away from — the programmatic
  supply chain (ads.txt/sellers.json), the serving infrastructure (IP/ASN/WHOIS), and the
  fraud networks that ride them. Reuses `tadori`'s ip-network + passive-dns ontologies
  (ADR-2606031600) for the delivery layer and the shared `org.corp.*` id space (kabuto/tsumugi)
  for listed ad-tech firms.

## Hard rules (constitutional — do not weaken)

1. **Observatory, NOT an ad network (G2).** sukashi NEVER serves, brokers, places, optimizes, or
   targets ads; it is never a demand or supply node; it is never a commercial ad-intel terminal.
   This is meta-observation OF advertising for FRAUD PROTECTION — it does not violate the Charter
   **広告排除** invariant, it serves it. Mirrors Charter Rider **§2(e)** + akashi **G9**.
2. **Public ad-tech transparency data only (G1).** ads.txt / app-ads.txt / sellers.json are public
   IAB web-standard files; creative content is as-published; WHOIS-org / DNS / IP / ASN are public
   record. **Forbidden inputs**: private user data, who-SAW / who-CLICKED an ad, first-party
   audience segments, RTB bidstream, impression-level PII.
3. **No adjudication (G4).** A `:adfraud.signal/*` is an evidence-bearing observation with a
   confidence + evidence-CID, **routed** to an actor that acts (`:akashi-malak` / `:kurashimori` /
   `:tasuke` / `:danjo`). It is NOT a verdict that an entity committed fraud (UPL / defamation
   boundary; sibling of danjo + akashi). Every signal carries `:adfraud.signal/non-adjudicating
   true`. **Real ad-tech firms carry NO fraud signal** — every fraud example in the seed is
   `:synthesized` on a CLEARLY-FICTIONAL entity (`.test` / `.example` + RFC-5737 doc IP ranges).
4. **Sourcing honesty (G5).** Every node/edge carries `:*/sourcing` ∈
   `:authoritative | :representative | :synthesized`. Public ads.txt/sellers.json/WHOIS facts are
   `:authoritative`; observed creatives/links are `:representative`; every fraud signal + every
   derived cluster/metric is `:synthesized`. Absence ≠ legitimacy — it means "not yet ingested".
5. **No personal PII (G9).** WHO-PAID is advertiser-disclosed **corporate** only; never a person.
   WHOIS keeps the registrant **organisation** only (personal registrants dropped — see
   `ingest.bridge_whois`). A deepfake-endorsement victim's likeness → encrypted-envelope
   evidence-CID, the person excluded from the public graph.
6. **kotoba-native (substrate boundary).** State = kotoba Datom log. No SQL / RisingWave / Lance as
   canonical store. Delivery-infra refs (`:ip`/`:asn`/`:domain`) are ids in tadori's existing
   ontologies — sukashi does NOT re-model the network layer.
7. **Browser-native render (G10).** `/search`, `/actors`, and the fraud-network viz run in the
   in-browser **kotoba-wasm node** (ADR-2606013600) — client-side query, no server round-trip.
8. **No git-lfs (G8).** Creative media / deepfake evidence → DataLad → IPFS under
   `80-data/ad-supply-chain`.
9. **Murakumo-only (G6).** Any LLM narration/classification routes through Murakumo (ADR-2605215000).
10. **Outward-gated INGEST (G7).** Live full-web crawl of ads.txt / sellers.json / WHOIS / DNS
    requires `SUKASHI_OPERATOR_GATE=1` + Council. R0 ships a bounded seed + a single-file offline
    bridge only.
11. **No-interaction / no-detection-evasion (G12).** Fetch is observational GET/HEAD of PUBLIC files
    only. sukashi never places/clicks an ad, never spends, never submits a landing-page form, never
    bypasses anti-bot — and crucially holds **no capability that would help an advertiser EVADE
    fraud detection** (weaponizable use is structurally unrepresentable).
12. **Complements akashi, does not duplicate (G13).** Fraud evidence is handed to akashi's existing
    `com.etzhayyim.akashi.malakEvidenceCandidate` bridge (evidence-only, never an accusation);
    sukashi does not run its own malak import.

## Vocabulary

`00-contracts/schemas/ad-supply-chain-ontology.kotoba.edn`:
- `:adtech/*` — an ad-tech entity (advertiser/agency/dsp/ad-exchange/ssp/ad-network/publisher/
  verification/data-broker), reusing `org.corp.*` for listed firms.
- `:adauth.edge/*` — first-class authorization edge from ads.txt/app-ads.txt/sellers.json
  (publisher → seller, `:direct`/`:reseller`, `declared` + `confirmed` two-sided handshake).
- `:adcreative/*` — an observed creative (who broadcasts what).
- `:addelivery.edge/*` — first-class edge: creative/landing → serving infra (`:ip`/`:asn`/
  WHOIS-org), reusing ip-network + passive-dns ontologies.
- `:adfraud.signal/*` — an evidence-bearing, non-adjudicating fraud signal, routed to an actor.
- derived (`:adsupply/*`, `:adfraud/*`) — unconfirmed-rate, infra-concentration, scam-network
  clusters, category load. Computed by `analyze.py`, flagged `:derived`, never re-ingested.

## Cells

- `cell:sukashi.crawl` → `methods/crawl.py` — the worldwide ACQUISITION leg. Walks a frontier of
  real publisher / SSP / exchange domains (`data/frontier-domains.edn`) and FETCHES their PUBLIC
  IAB files (`/ads.txt`, `/app-ads.txt`, `/sellers.json`, public RDAP) → feeds the `ingest` parsers
  → kotoba rows. **DRY-RUN unless `SUKASHI_OPERATOR_GATE=1`** (G7); the network leg is INJECTED
  (`fetcher=`, tests run offline); GET-only honest-UA robots-respecting, no detection-evasion
  (G2/G12); RDAP keeps registrant ORG only (G9); resume-safe (data/live/ gitignored, fresh-skip).
  I/O-coupled → `.py` (the ingest.py boundary, ADR-2606131800); the *analyzer* is `.cljc`.
- `cell:sukashi.ingest` → `methods/ingest.py` — real ads.txt/sellers.json/WHOIS parsers → kotoba
  EAVT bridge (offline default; live G7-gated). WHOIS keeps registrant ORG only (G9).
- `cell:sukashi.analyze` → `methods/analyze.py` (stdlib). authorization-handshake integrity
  (unconfirmed-rate) → account-id collision (domain-spoof surface) → delivery-infra concentration
  (ASN/registrar) → shared-infra scam-ad-network clustering → category load → routing tally.
  Aggregate-first. Idempotent.
- `cell:sukashi.transact` → `methods/transact.py` — kotoba `datomic.transact` save-path. Dry-run
  default; live write needs operator JWT or CACAO (no platform-held key, ADR-2605231525).
- `cell:sukashi.viz` → `methods/viz.cljc` (`bb sukashi:viz`; template `viz/template.htm`) — self-contained ad-tech supply-chain + fraud
  force-graph (browser-native via the kotoba-wasm node; inlined payload = offline data contract).
- `cell:sukashi.fraud-bridge` (design) → hands `:routed-to :akashi-malak` signals to akashi's
  `malakEvidenceCandidate` bridge (candidate-evidence only; G11/G13).

## Lexicons (kotoba-native)

`com.etzhayyim.sukashi.{registerAdtech,registerAuthEdge,registerCreative,registerFraudSignal,publishIntelReport,socialPost}`
— `00-contracts/lexicons/com/etzhayyim/sukashi/`. `confidenceBp` is integer basis points (0..1000)
to avoid floats, mirroring kabuto's `criticalityBp`.

## Run

**bb is the standard runner — no `.sh` in this repo.** Tasks live in the root `bb.edn`.

```bash
# from the repo root:
bb sukashi:crawl                  # DRY-RUN: print the frontier plan (no network). The acquisition leg.
SUKASHI_OPERATOR_GATE=1 bb sukashi:crawl --max 50   # LIVE worldwide crawl (Council-gated, G7) → data/live/
bb test:sukashi                   # python invariant/heartbeat/crawler + cljc analyzer suites

# pure reports (methods are python/.cljc, not scripts):
cd 20-actors/sukashi
python3 methods/crawl.py --merge                 # parse fetched data/live/* → rows
python3 methods/ingest.py --source adstxt --in data/live/nytimes.com.ads.txt --publisher <id>  # bridge a fetched file
python3 methods/analyze.py                       # → out/intel-report.md + out/ad-fraud-clusters.kotoba.edn
bb sukashi:viz                                   # → viz/ad-supply-chain.htm (open in a browser)
python3 methods/autorun.py --cycles 3 --fresh    # AUTONOMOUS heartbeat → LOCAL kotoba Datom log
python3 methods/transact.py                      # dry-run; --graph <CID> + KOTOBA_TOKEN to write (G7)
```

### Autonomous on the Murakumo fleet (ADR-2606071600)

`methods/autorun.py` is the self-driving observatory heartbeat — the same shape shionome /
ipaddress / yabai use. Each cycle it runs the whole pipeline ITSELF (observe offline merged graph →
classify → analyze auth-handshake integrity / delivery-infra concentration / scam-network clusters →
PERSIST a content-addressed transaction to the append-only **local** kotoba Datom log,
`methods/kotoba.py`), linking the previous tx's CID into a verifiable commit-DAG. Deterministic /
resume-safe; NO external I/O. Constitutional posture holds by construction: OBSERVATORY not an ad
network (G2); every persisted fraud signal stays `:non-adjudicating true` + `:synthesized` (G4) —
no real entity is implicated. **Fleet placement** is the k3s spec `50-infra/murakumo/fleet.edn` (`sukashi_adsupply_ingest` /
`sukashi_fraud_weave` / `sukashi_adsupply_persist`). The actually-running Tier-1 **launchd** daemon
is registered in `50-infra/cluster/murakumo/cell-runner/cells.edn` as
**`SukashiObservatoryHeartbeatCell`** (module `sukashi.cell`, entry `fire`, node `issachar`, cron
`42 * * * *`, healthz 13081) — installed per-node via `cell-runner/install.sh --node issachar`
(the actual launchd load is the operator step). `cell.py::fire()` runs ONE offline heartbeat
(`autorun.run_cycle`). The worldwide **crawl** (`methods/crawl.py` + `SUKASHI_OPERATOR_GATE`, G7)
and the live-node push (`transact.py`, G11) stay separate operator-gated invocations. Invariants guarded by
`methods/test_autorun.py` (commit-DAG verify, tamper-detect, determinism, append-only,
derived-flagging, **G4 fraud-signals-non-adjudicating**, no-external-I/O).

`python3 methods/analyze.py` with no argument runs the **seed** graph (or the merged graph if an
ingest has been run); no live fetch needed.

## Honesty (R0)

Bounded illustrative seed of **30** real ad-tech entities (DSP/exchange/SSP/network/verification/
publisher), **8** public ads.txt/sellers.json authorization edges, **4** creatives, **4** delivery
edges, and **6** fraud signals — **not** exhaustive coverage (grows each `/loop` iteration toward
broader real public-file coverage). Real firms + genuinely-public ads.txt/sellers.json facts are
`:authoritative`/`:representative` and carry **NO** fraud signal (G4 non-adjudication). **Every
fraud example is `:synthesized` on a CLEARLY-FICTIONAL entity** (obviously-fake names + `.test`/
`.example` domains + RFC-5737 documentation IP ranges) so no real entity is implicated. Full-web
ads.txt / sellers.json / WHOIS crawl is the **R1** goal — **G7** Council + operator gated. Live
atproto posting is **G11** operator-gated. Live malak handoff goes through akashi's review gate.
