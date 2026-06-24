# sukashi 透かし — ad-tech supply-chain + delivery-infra + fraud-network observatory

> Tier-B religious-corp actor · R0 design-only · ADR-2606071600
> Sibling of `akashi` 証 (platform ad-library disclosure). sukashi covers the layer akashi is
> constitutionally bounded away from: the programmatic ad-tech **supply chain**, the **delivery
> infrastructure** (IP/DNS/WHOIS/ASN), and the **fraud networks** that ride them.

## What it is

sukashi is a **kotoba-native observatory** over the programmatic advertising ecosystem, built for
**fraud protection + ad-tech transparency**. It ingests the advertising industry's own PUBLIC
authenticity files — `ads.txt` / `app-ads.txt` (a publisher declares which sellers may sell its
inventory) and `sellers.json` (an exchange declares its seller accounts) — and holds them as a
content-addressed knowledge graph in the kotoba Datom log. The **gaps in the two-sided handshake**
(declared in ads.txt but not confirmed in sellers.json, or one account-id claimed by two domains)
are exactly where spoofed / unauthorized / scam inventory lives — and that is what sukashi surfaces.

It binds the ads people actually see (`:adcreative`) to **where they are served from**
(`:addelivery.edge` → IP / ASN / WHOIS-org), reusing `tadori`'s `ip-network` + `passive-dns`
ontologies rather than re-modelling the network layer. Where scam creatives **share serving
infrastructure** (same ASN + registrar + WHOIS-org) and each carry a fraud signal, sukashi flags a
candidate **scam-ad network**.

## What it answers (the original ask)

| Ask | How sukashi answers it |
|---|---|
| ingest ad-network 出稿状況 | `:adcreative` + `:adauth.edge` from public ads.txt/sellers.json (the ad-tech supply chain) |
| どの企業/組織がどういう情報を発信しているか | `:adtech` (advertiser) → `:adcreative` (headline/category/media) |
| supply chain / depends / follow graph + 可視化 | `:adauth.edge` authorization graph + `:addelivery.edge` + `viz/ad-supply-chain.htm` force-graph |
| 詐欺広告・詐欺 actor の特定 | `:adfraud.signal` + shared-infra `:adfraud/cluster` (candidate scam-ad networks) |
| 配信元の IP / DNS / WHOIS / 組織状況 | `:addelivery.edge` → `:ip`/`:asn` (ip-network) + `:domain` (passive-dns) + WHOIS-org/registrar |
| kotoba datomic + IPFS 永続化 | `methods/transact.py` → kotoba `datomic.transact`; media/evidence → DataLad→IPFS |
| 分析 | `methods/analyze.py` aggregate-first concentration + integrity + fraud-cluster metrics |
| loop で成熟度改善 | `MATURITY.md` + the self-paced maturity loop (grows coverage + metrics each iteration) |

## What it is NOT (constitutional)

- **NOT an ad network / exchange / DSP / SSP / buying / targeting / optimization tool.** sukashi is
  an observatory; it never serves, brokers, places, or optimizes an ad (G2). The Charter **広告排除**
  invariant is *served*, not violated — this is observation OF advertising, for fraud protection.
- **NOT an adjudicator.** A fraud signal is an evidence-bearing observation **routed** to an actor
  that acts (akashi's malak bridge / kurashimori / tasuke / danjo) — never a verdict (G4). Real
  firms carry no fraud signal; every fraud example is fictional + `:synthesized`.
- **NOT a surveillance tool.** No who-saw / who-clicked data, no audience segments, no personal PII;
  WHOIS keeps the registrant **organisation** only (G1/G9).
- **NOT a detection-evasion tool.** It holds no capability to help anyone place deceptive ads or
  evade fraud detection (G12 — weaponizable use structurally unrepresentable).

## Layout

```
20-actors/sukashi/
├── CLAUDE.md            # actor-local constitutional rules (read after repo-root CLAUDE.md)
├── README.md            # this file
├── MATURITY.md          # R0→R1 maturity ladder (the /loop tracks this)
├── manifest.jsonld      # actor manifest (cells, lexicons, G1-G13 gates)
├── data/
│   └── seed-ad-supply-chain.kotoba.edn   # bounded real seed (fraud = fictional :synthesized)
├── methods/
│   ├── sukashi_edn.py   # minimal EDN reader + classifier (stdlib)
│   ├── ingest.py        # ads.txt / sellers.json / WHOIS parsers → EAVT (offline default, G7)
│   ├── analyze.py       # aggregate-first integrity + concentration + fraud-cluster analyzer
│   └── transact.py      # kotoba datomic.transact save-path (dry-run default, no-server-key)
├── viz/
│   ├── build_viz_data.py            # self-contained force-graph builder
│   └── ad-supply-chain.{json,htm}   # the inlined payload + offline viewer
└── tests/
    └── test_sukashi.py  # 16 invariant + analyzer tests
```

Vocabulary: `00-contracts/schemas/ad-supply-chain-ontology.kotoba.edn`.
Lexicons: `00-contracts/lexicons/com/etzhayyim/sukashi/`.

## Quickstart

```bash
cd 20-actors/sukashi
python3 methods/analyze.py        # → out/intel-report.md + out/ad-fraud-clusters.kotoba.edn
python3 viz/build_viz_data.py     # → open viz/ad-supply-chain.htm in a browser
./run_tests.sh                    # 16 tests
```

License: Apache 2.0 + etzhayyim Charter Compliance Rider v2.0 (see repo-root `/CHARTER-RIDER.md`).
