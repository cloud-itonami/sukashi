(ns sukashi.methods.analyze
  "sukashi 透かし — ad-tech supply-chain integrity + fraud-network concentration analyzer.
  1:1 Clojure port of `methods/analyze.py` (ADR-2606071600).

  Reads a kotoba-EDN ad-tech graph (:adtech/* entities, :adauth.edge/* ads.txt/sellers.json
  authorization edges, :adcreative/* creatives, :addelivery.edge/* serving-infrastructure
  edges, :adfraud.signal/* fraud signals) and emits:

    1. an AGGREGATE-FIRST ad-tech transparency + fraud-protection report (out/intel-report.md)
    2. the derived concentration + fraud-cluster datoms (out/ad-fraud-clusters.kotoba.edn),
       flagged :derived — never re-ingested as authoritative fact.

  CONSTITUTIONAL framing (sukashi G2/G3/G4): a fraud-PROTECTION + ad-tech TRANSPARENCY map,
  NEVER a target-list and NEVER an ad-buying/targeting/optimization/detection-evasion tool.
  sukashi does NOT adjudicate (G4) — it surfaces signals + clusters routed to actors that act.

  House style: Python ':…' keyword strings stay strings; pure fns; file I/O only at
  #?(:clj) edges. Accumulation maps carry ::order metadata = first-touch insertion order so
  stable sort-by ties exactly the Python dict iteration order. Float formatting mirrors
  Python str()/round(): round() uses HALF_EVEN via exact BigDecimal.(double); the report +
  derived datoms are byte-identical to the Python emit."
  (:require [clojure.string :as str]
            [sukashi.methods.sukashi-edn :as edn]
            #?(:clj [clojure.java.io :as io])))

;; ── numeric helpers (Python parity) ─────────────────────────────────────────

(defn pyround
  "Python3 round(x, ndigits): HALF_EVEN (banker's) rounding of the exact value of the
  IEEE double x, scaled to ndigits. Implemented via the EXACT BigDecimal of the double
  (BigDecimal. (double x)) so the round matches CPython's float round bit-for-bit."
  [x ndigits]
  #?(:clj
     (-> (java.math.BigDecimal. (double x))
         (.setScale (int ndigits) java.math.RoundingMode/HALF_EVEN)
         (.doubleValue))
     :cljs
     (let [f (Math/pow 10 ndigits) y (* (double x) f)
           r (Math/round y)
           r (if (== 0.5 (Math/abs (- y (Math/floor y))))
               (let [d (Math/floor y)] (if (even? (long d)) d (inc d)))
               r)]
       (/ r f))))

(defn pynum
  "Render a number the way Python's f-string / str() does: an integer (long) prints with
  no decimal point; a double prints via shortest round-trip repr (= Clojure's str on a
  Double, which matches CPython repr for our magnitudes)."
  [v]
  (cond
    (integer? v) (str v)
    (double? v) (str v)
    :else (str v)))

;; ── ordered accumulation maps (mirror Python defaultdict insertion order) ────

(defn- omap [] (with-meta {} {::order []}))

(defn- otouch
  "Ensure k exists in ordered-map m (recording first-touch order); value defaults to init."
  [m k init]
  (if (contains? m k)
    m
    (with-meta (assoc m k init) (update (meta m) ::order conj k))))

(defn- oupdate
  "update ordered-map m at k with (f current), recording first-touch order; default 0."
  ([m k f] (oupdate m k f 0))
  ([m k f init]
   (let [m (otouch m k init)]
     (with-meta (assoc m k (f (get m k))) (meta m)))))

(defn- oadd-set
  "Add v to the set at k in ordered-map m (set defaults to #{}), recording first-touch order."
  [m k v]
  (let [m (otouch m k #{})]
    (with-meta (assoc m k (conj (get m k) v)) (meta m))))

(defn order-of
  "First-touch insertion order of an ordered-map (::order metadata), else (keys m)."
  [m]
  (or (::order (meta m)) (keys m)))

(defn oitems
  "[k v] pairs of an ordered-map in first-touch insertion order."
  [m]
  (map (fn [k] [k (get m k)]) (order-of m)))

(defn- truthy?
  "Python truthiness for the values we test (nil/false → false; everything else → true)."
  [v]
  (not (or (nil? v) (false? v))))

(defn- ->float
  "float(x or 0.0): 0.0 when x is nil/false; else (double x)."
  [x]
  (if (truthy? x) (double x) 0.0))

;; ── analyzer ────────────────────────────────────────────────────────────────

(defn analyze
  "1:1 port of analyze(adtech, auth, creatives, delivery, fraud). Returns a map keyed by the
  same names the Python dict uses (string keys). Accumulators carry ::order so the stable
  sorts below tie exactly Python's insertion-ordered dict iteration."
  [adtech auth creatives delivery fraud]
  ;; ── supply-chain authorization integrity ──
  (let [{:keys [seller-fan-out publisher-sellers seller-declared seller-unconfirmed reseller-edges]}
        (reduce
         (fn [acc e]
           (let [s (get e ":adauth.edge/seller")
                 p (get e ":adauth.edge/publisher")]
             (if (or (not (truthy? s)) (not (truthy? p)))
               acc
               (let [acc (-> acc
                             (update :seller-fan-out oadd-set s p)
                             (update :publisher-sellers oadd-set p s))
                     acc (if (truthy? (get e ":adauth.edge/declared"))
                           (let [acc (update acc :seller-declared oupdate s inc)]
                             (if (not (truthy? (get e ":adauth.edge/confirmed")))
                               (update acc :seller-unconfirmed oupdate s inc)
                               acc))
                           acc)]
                 (if (= (get e ":adauth.edge/relationship") ":reseller")
                   (update acc :reseller-edges inc)
                   acc)))))
         {:seller-fan-out (omap) :publisher-sellers (omap)
          :seller-declared (omap) :seller-unconfirmed (omap) :reseller-edges 0}
         auth)

        ;; unconfirmed-rate per seller (the unauthorized/spoofed-inventory surface)
        unconfirmed-rate
        (->> (oitems seller-declared)
             (keep (fn [[s dec]]
                     (let [unc (get seller-unconfirmed s 0)
                           rate (if (and dec (not= 0 dec)) (pyround (/ (double unc) dec) 3) 0.0)]
                       (when (> unc 0) [s unc dec rate]))))
             (sort-by (fn [r] [(- (nth r 3)) (- (nth r 1))]))
             (vec))

        ;; account-id collisions across DIFFERENT publishers selling to the SAME seller
        acct-claims
        (reduce
         (fn [m e]
           (let [s (get e ":adauth.edge/seller")
                 acct (get e ":adauth.edge/account-id")
                 p (get e ":adauth.edge/publisher")]
             (if (and (truthy? s) (truthy? acct) (truthy? p))
               (oadd-set m [s acct] p)
               m)))
         (omap) auth)
        acct-collisions
        (->> (oitems acct-claims)
             (keep (fn [[[s acct] ps]] (when (> (count ps) 1) [s acct (vec (sort ps))])))
             (sort-by (fn [r] (- (count (nth r 2)))))
             (vec))

        seller-fan-rank
        (->> (oitems seller-fan-out)
             (map (fn [[s ps]] [s (count ps)]))
             (sort-by (fn [r] (- (nth r 1))))
             (vec))

        ;; MATURITY — seller betweenness centrality ∝ C(fan-in, 2)
        seller-betweenness
        (->> (oitems seller-fan-out)
             (keep (fn [[s ps]]
                     (let [n (count ps)]
                       (when (>= n 2) [s n (quot (* n (- n 1)) 2)]))))
             (sort-by (fn [r] (- (nth r 2))))
             (vec))

        ;; ── delivery-infrastructure concentration ──
        {:keys [creative-fraud subj-fraud subj-kinds fraud-kind-count]}
        (reduce
         (fn [acc f]
           (let [subj (get f ":adfraud.signal/subject")
                 conf (->float (get f ":adfraud.signal/confidence"))
                 kind (let [k (get f ":adfraud.signal/kind")] (if (truthy? k) k ":unknown"))
                 acc (-> acc
                         (update :subj-fraud oupdate subj #(+ % conf) 0.0)
                         (update :subj-kinds oadd-set subj kind)
                         (update :fraud-kind-count oupdate kind inc))]
             (if (and (truthy? subj) (str/starts-with? (str subj) "adc."))
               (update acc :creative-fraud oupdate subj #(+ % conf) 0.0)
               acc)))
         {:creative-fraud (omap) :subj-fraud (omap) :subj-kinds (omap) :fraud-kind-count (omap)}
         fraud)

        cre-by-id (reduce (fn [m c] (assoc m (get c ":adcreative/id") c)) {} creatives)

        {:keys [asn-load registrar-load whois-load asn-members registrar-members whois-members]}
        (reduce
         (fn [acc d]
           (let [cre (get d ":addelivery.edge/creative")
                 w (get creative-fraud cre 0.0)
                 asn (get d ":addelivery.edge/asn")
                 reg (get d ":addelivery.edge/registrar")
                 org (get d ":addelivery.edge/whois-org")
                 acc (if (truthy? asn)
                       (cond-> (update acc :asn-load oupdate asn #(+ % w) 0.0)
                         (truthy? cre) (update :asn-members oadd-set asn cre))
                       acc)
                 acc (if (truthy? reg)
                       (cond-> (update acc :registrar-load oupdate reg #(+ % w) 0.0)
                         (truthy? cre) (update :registrar-members oadd-set reg cre))
                       acc)
                 acc (if (truthy? org)
                       (cond-> (update acc :whois-load oupdate org #(+ % w) 0.0)
                         (truthy? cre) (update :whois-members oadd-set org cre))
                       acc)]
             acc))
         {:asn-load (omap) :registrar-load (omap) :whois-load (omap)
          :asn-members (omap) :registrar-members (omap) :whois-members (omap)}
         delivery)

        infra-rank
        (->> (oitems asn-load)
             (keep (fn [[a v]] (when (> v 0) [a (pyround v 2) (count (get asn-members a #{}))])))
             (sort-by (fn [r] (- (nth r 1))))
             (vec))
        registrar-rank
        (->> (oitems registrar-load)
             (keep (fn [[r v]] (when (> v 0) [r (pyround v 2) (count (get registrar-members r #{}))])))
             (sort-by (fn [row] (- (nth row 1))))
             (vec))
        whois-rank
        (->> (oitems whois-load)
             (keep (fn [[o v]] (when (> v 0) [o (pyround v 2) (count (get whois-members o #{}))])))
             (sort-by (fn [r] (- (nth r 1))))
             (vec))

        ;; ── fraud clusters: creatives sharing serving infra (ASN ∧ registrar ∧ whois-org) ──
        {:keys [infra-to-creatives infra-meta]}
        (reduce
         (fn [acc d]
           (let [cre (get d ":addelivery.edge/creative")
                 asn (get d ":addelivery.edge/asn")
                 reg (get d ":addelivery.edge/registrar")
                 org (get d ":addelivery.edge/whois-org")]
             (if (or (not (truthy? cre)) (<= (get creative-fraud cre 0.0) 0))
               acc
               (let [k [asn reg org]]
                 (-> acc
                     (update :infra-to-creatives oadd-set k cre)
                     (update :infra-meta assoc k {:asn asn :registrar reg :whois-org org}))))))
         {:infra-to-creatives (omap) :infra-meta {}}
         delivery)

        clusters
        (->> (oitems infra-to-creatives)
             (keep (fn [[k cres]]
                     (when (>= (count cres) 2)
                       (let [advertisers (->> cres
                                              (map #(get-in cre-by-id [% ":adcreative/advertiser"]))
                                              (remove nil?)
                                              (set)
                                              (sort)
                                              (vec))
                             conf-sum (pyround (reduce + 0.0 (map #(get creative-fraud % 0.0) cres)) 2)
                             ;; MULTI-SIGNAL CORROBORATION: distinct fraud-signal kinds across members
                             kinds (reduce (fn [s c] (into s (get subj-kinds c #{}))) #{} cres)
                             corroboration (count kinds)
                             m (get infra-meta k)
                             rank-score (pyround (* (count cres) conf-sum
                                                    (+ 1 (* 0.5 (max 0 (- corroboration 1))))) 2)]
                         {:asn (:asn m) :registrar (:registrar m) :whois-org (:whois-org m)
                          :creatives (vec (sort cres)) :advertisers advertisers
                          :conf-sum conf-sum :members (count cres)
                          :kinds (->> kinds
                                      (map #(str/replace (str %) #"^:+" ""))
                                      (sort) (vec))
                          :corroboration corroboration :rank-score rank-score}))))
             (sort-by (fn [c] (- (:rank-score c))))
             (vec))

        ;; ── fraud-signal load by advertiser category ──
        adv-cat (reduce (fn [m a]
                          (assoc m (get a ":adtech/id")
                                 (let [c (get a ":adtech/category")] (if (truthy? c) c ":unknown"))))
                        {} (edn/adtech-vals adtech))
        category-load
        (reduce
         (fn [m f]
           (let [subj (get f ":adfraud.signal/subject")
                 conf (->float (get f ":adfraud.signal/confidence"))
                 cre (get cre-by-id subj)
                 cat (cond
                       cre (let [c (get cre ":adcreative/category")]
                             (if (truthy? c) c (get adv-cat (get cre ":adcreative/advertiser"))))
                       (contains? adv-cat subj) (get adv-cat subj)
                       :else nil)]
             (oupdate m (if (truthy? cat) cat ":unknown") #(+ % conf) 0.0)))
         (omap) fraud)
        category-rank
        (->> (oitems category-load)
             (sort-by (fn [[_ v]] (- v)))
             (vec))

        ;; ── routing tally ──
        routed
        (reduce
         (fn [m f]
           (oupdate m (let [r (get f ":adfraud.signal/routed-to")] (if (truthy? r) r ":unrouted")) inc))
         (omap) fraud)]

    {"seller_fan_rank" seller-fan-rank
     "seller_betweenness" seller-betweenness
     "publisher_sellers" (into {} (map (fn [[p s]] [p (count s)]) (oitems publisher-sellers)))
     "unconfirmed_rate" unconfirmed-rate
     "acct_collisions" acct-collisions
     "reseller_edges" reseller-edges
     "infra_rank" infra-rank
     "registrar_rank" registrar-rank
     "whois_rank" whois-rank
     "clusters" clusters
     "category_rank" category-rank
     "fraud_kind_count" fraud-kind-count
     "routed" routed
     "subj_fraud" subj-fraud}))

;; ── report rendering (matches render_report's f-strings) ─────────────────────

(defn aname
  "adtech.get(aid, {}).get(':adtech/name', aid)."
  [adtech aid]
  (get-in adtech [aid ":adtech/name"] aid))

(defn- lstrip-colon
  "str(x).lstrip(':') — strip ALL leading ':' chars (Python lstrip is char-set based)."
  [x]
  (str/replace (str x) #"^:+" ""))

(defn render-report
  "1:1 port of render_report — returns the markdown report (trailing newline)."
  [adtech auth creatives delivery fraud a]
  (let [L (transient [])
        P (fn [s] (conj! L s))]
    (P "# sukashi 透かし — ad-tech supply-chain integrity + fraud-network report")
    (P "")
    (P (str "> ADR-2606071600 · **aggregate-first** · ad-tech fraud-PROTECTION + TRANSPARENCY map "
            "(NOT a target-list, NOT an ad-buying tool; sukashi G2). sukashi does NOT adjudicate (G4) — "
            "every fraud signal is an evidence-bearing observation **routed** to an actor that acts "
            "(akashi malak bridge / kurashimori / tasuke / danjo). All fraud examples are attached to "
            "CLEARLY-FICTIONAL illustrative entities (`.test`/`.example` + RFC-5737 doc IP ranges); real "
            "ad-tech firms carry NO fraud signal (non-adjudication)."))
    (P "")
    (let [roles (reduce (fn [m e]
                          (oupdate m (let [r (get e ":adtech/role")] (if (truthy? r) r ":unknown")) inc))
                        (omap) (edn/adtech-vals adtech))]
      (P (str "- ad-tech entities: **" (count adtech) "**  ·  authorization edges (ads.txt/sellers.json): "
              "**" (count auth) "**  ·  creatives: **" (count creatives) "**  ·  delivery edges: **"
              (count delivery) "**  ·  fraud signals: **" (count fraud) "**"))
      (P (str "- roles covered: "
              (str/join ", "
                        (map (fn [[r n]] (str "`" (lstrip-colon r) "` " n))
                             (sort-by (fn [[_ n]] (- n)) (oitems roles)))))))
    (P "")

    ;; ── authorization-handshake integrity ──
    (P "## Authorization-handshake integrity — unauthorized / unconfirmed sellers")
    (P "")
    (P (str "Per seller: declared edges (a publisher's ads.txt names it) that are NOT confirmed in the "
            "seller's sellers.json. A declared-but-unconfirmed edge is the **unauthorized / spoofed-"
            "inventory surface** — the headline ad-tech-fraud signal. Routed to de-spoofing + takedown-"
            "referral, never to interdiction (G2)."))
    (P "")
    (P "| seller | unconfirmed | declared | unconfirmed-rate |")
    (P "|---|---:|---:|---:|")
    (doseq [[s unc dec rate] (get a "unconfirmed_rate")]
      (P (str "| " (aname adtech s) " | " unc " | " dec " | " (pynum rate) " |")))
    (when (empty? (get a "unconfirmed_rate"))
      (P "| (none in seed — every declared edge confirmed) | | | |"))
    (P "")

    ;; ── account-id collisions ──
    (P "## Account-id collisions — publisher-impersonation (domain-spoof) surface")
    (P "")
    (P (str "One seller account-id claimed by MORE THAN ONE publisher domain = a candidate "
            "publisher-impersonation: a spoofed domain claims a legitimate publisher's account to "
            "monetize counterfeit inventory. Routed to the affected publisher + exchange, never a target (G2)."))
    (P "")
    (P "| seller | account-id | claiming publishers |")
    (P "|---|---|---|")
    (doseq [[s acct ps] (get a "acct_collisions")]
      (P (str "| " (aname adtech s) " | `" acct "` | "
              (str/join ", " (map #(aname adtech %) ps)) " |")))
    (when (empty? (get a "acct_collisions"))
      (P "| (none in seed) | | |"))
    (P "")

    ;; ── delivery-infrastructure concentration ──
    (P "## Delivery-infrastructure concentration — where scam delivery piles up")
    (P "")
    (P (str "Σ fraud-weighted creative delivery per hosting ASN (fraud weight = Σ confidence of the "
            "creative's signals). High = scam advertising concentrates onto one hosting network — a "
            "takedown-referral + resilience priority, NEVER a target-list (G2). Reuses ip-network-"
            "ontology `:asn` ids (tadori substrate, ADR-2606031600)."))
    (P "")
    (P "| hosting ASN | Σ fraud-weighted delivery | scam creatives |")
    (P "|---|---:|---:|")
    (doseq [[asn load n] (get a "infra_rank")]
      (P (str "| `" (lstrip-colon asn) "` | " (pynum load) " | " n " |")))
    (when (empty? (get a "infra_rank"))
      (P "| (none in seed) | | |"))
    (P "")
    (P "Registrar concentration (a fraud-cluster co-occurrence key):")
    (P "")
    (P "| registrar | Σ fraud-weighted delivery | scam creatives |")
    (P "|---|---:|---:|")
    (doseq [[reg load n] (get a "registrar_rank")]
      (P (str "| " reg " | " (pynum load) " | " n " |")))
    (when (empty? (get a "registrar_rank"))
      (P "| (none in seed) | | |"))
    (P "")
    (P (str "WHOIS registrant-organisation concentration (public WHOIS, ORG-only per G9 — a "
            "fraud-cluster co-occurrence key; one registrant org behind many scam creatives):"))
    (P "")
    (P "| registrant org (WHOIS) | Σ fraud-weighted delivery | scam creatives |")
    (P "|---|---:|---:|")
    (doseq [[org load n] (get a "whois_rank")]
      (P (str "| " org " | " (pynum load) " | " n " |")))
    (when (empty? (get a "whois_rank"))
      (P "| (none in seed) | | |"))
    (P "")

    ;; ── fraud clusters ──
    (P "## Candidate scam-ad networks — creatives sharing serving infrastructure")
    (P "")
    (P (str "Creatives that share serving infrastructure (ASN ∧ registrar ∧ WHOIS-org) AND each carry "
            "≥1 fraud signal = a candidate scam-ad **network**. Ranked by members × Σ confidence. "
            "AGGREGATE-FIRST + NON-ADJUDICATING (G4): a candidate for protection actors to investigate, "
            "never a verdict. Routed to akashi's malak evidence bridge."))
    (P "")
    (P "| shared ASN | registrar | WHOIS-org | creatives | distinct fraud kinds (corroboration) | Σ confidence | rank |")
    (P "|---|---|---|---:|---|---:|---:|")
    (doseq [c (get a "clusters")]
      (let [kinds (str (str/join ", " (map #(str "`" % "`") (:kinds c)))
                       " (" (:corroboration c) ")")]
        (P (str "| `" (lstrip-colon (:asn c)) "` | " (:registrar c) " | " (:whois-org c) " | "
                (:members c) " | " kinds " | " (pynum (:conf-sum c)) " | " (pynum (:rank-score c)) " |"))))
    (when (empty? (get a "clusters"))
      (P "| (none in seed) | | | | | | |"))
    (P "")
    (P (str "> **Multi-signal corroboration**: distinct fraud-signal kinds across a cluster's "
            "creatives. Independent kinds (e.g. scam-finance + fake-endorsement + counterfeit-goods on "
            "one bulletproof host) corroborate a single operation more strongly than repeats — the rank "
            "weights corroboration so multi-kind networks surface first. Still NON-ADJUDICATING (G4): a "
            "stronger candidate for protection actors, never a verdict."))
    (P "")

    ;; ── fraud by category ──
    (P "## Fraud-signal load by advertiser category — high-risk verticals")
    (P "")
    (P (str "Σ fraud-signal confidence per advertiser category — which verticals (finance / crypto / "
            "health-supplement / gambling) the scam surface concentrates in. A consumer-protection "
            "prioritization signal (routed to kurashimori), never a target-list (G2)."))
    (P "")
    (P "| category | Σ fraud confidence |")
    (P "|---|---:|")
    (doseq [[cat load] (get a "category_rank")]
      (P (str "| `" (lstrip-colon cat) "` | " (pynum (pyround load 2)) " |")))
    (when (empty? (get a "category_rank"))
      (P "| (none in seed) | |"))
    (P "")

    ;; ── fraud-kind + routing tallies ──
    (P "## Fraud-signal taxonomy + routing — who acts (sukashi does not)")
    (P "")
    (P "| fraud kind | count |   | routed to | count |")
    (P "|---|---:|---|---|---:|")
    (let [kinds (->> (oitems (get a "fraud_kind_count")) (sort-by (fn [[_ n]] (- n))) (vec))
          routes (->> (oitems (get a "routed")) (sort-by (fn [[_ n]] (- n))) (vec))]
      (doseq [i (range (max (count kinds) (count routes)))]
        (let [lk (if (< i (count kinds)) (str "`" (lstrip-colon (nth (nth kinds i) 0)) "`") "")
              lkn (if (< i (count kinds)) (str (nth (nth kinds i) 1)) "")
              rk (if (< i (count routes)) (str "`" (lstrip-colon (nth (nth routes i) 0)) "`") "")
              rkn (if (< i (count routes)) (str (nth (nth routes i) 1)) "")]
          (P (str "| " lk " | " lkn " |  | " rk " | " rkn " |")))))
    (P "")
    (P (str "> Routing legend: `akashi-malak` = handed to akashi's `com.etzhayyim.akashi."
            "malakEvidenceCandidate` bridge (evidence-only, never an accusation); `kurashimori` = "
            "consumer-protection concierge; `tasuke` = cybercrime-victim support; `danjo` = public "
            "accountability. **sukashi observes; these actors act.**"))
    (P "")

    ;; ── most-systemic legit sellers ──
    (P "## Seller fan-out — most-authorized sellers (transparency signal)")
    (P "")
    (P (str "# distinct publishers that authorize each seller in their ads.txt. High = a systemic "
            "seller many sites depend on — a transparency signal (where supply-chain power concentrates), "
            "never a target (G2)."))
    (P "")
    (P "| seller | publishers authorizing |")
    (P "|---|---:|")
    (doseq [[s n] (take 15 (get a "seller_fan_rank"))]
      (P (str "| " (aname adtech s) " | " n " |")))
    (P "")

    ;; ── seller betweenness centrality ──
    (P "## Seller betweenness centrality — supply-chain bridge ranking")
    (P "")
    (P (str "In the publisher↔seller authorization graph, a seller bridges every PAIR of publishers "
            "that both authorize it (betweenness ∝ C(fan-in, 2)). High = a seller that structurally "
            "sits between much of the supply graph — where supply-chain dependency concentrates. A "
            "transparency / resilience signal, never a target-list (G2)."))
    (P "")
    (P "| seller | publishers (fan-in) | betweenness (publisher-pairs bridged) |")
    (P "|---|---:|---:|")
    (doseq [[s fan btw] (take 15 (get a "seller_betweenness"))]
      (P (str "| " (aname adtech s) " | " fan " | " btw " |")))
    (when (empty? (get a "seller_betweenness"))
      (P "| (none in seed — no seller shared by ≥2 publishers) | | |"))
    (P "")

    (P "---")
    (P (str "*Generated by `sukashi/methods/analyze.py`. HONEST: R0 bounded seed; real ad-tech firms "
            "+ genuinely-public ads.txt/sellers.json facts are :representative/:authoritative and carry "
            "NO fraud signal; every fraud example is :synthesized on a CLEARLY-FICTIONAL illustrative "
            "entity. Full-web ads.txt / sellers.json / WHOIS crawl is G7 Council + operator gated. "
            "sukashi is an observatory, NOT an ad network (G2); it does NOT adjudicate (G4).*"))
    (str (str/join "\n" (persistent! L)) "\n")))

;; ── derived datoms (matches render_datoms) ───────────────────────────────────

(defn render-datoms
  "1:1 port of render_datoms — returns the DERIVED concentration + cluster datoms EDN."
  [a]
  (let [L (transient [])
        P (fn [s] (conj! L s))
        es edn/edn-str]
    (P ";; sukashi — DERIVED ad-tech concentration + fraud-cluster datoms (ADR-2606071600).")
    (P ";; :derived — NOT fact. Recomputed from the seed graph; do not re-ingest as :authoritative.")
    (P "[")
    (doseq [[s unc dec rate] (get a "unconfirmed_rate")]
      (P (str " {:adsupply/seller " (es s) " :adsupply/unconfirmed " unc " "
              ":adsupply/declared " dec " :adsupply/unconfirmed-rate " (pynum rate) " :adsupply/derived true}")))
    (doseq [[s n] (get a "seller_fan_rank")]
      (P (str " {:adsupply/seller " (es s) " :adsupply/seller-fan-out " n " :adsupply/derived true}")))
    (doseq [[s fan btw] (get a "seller_betweenness")]
      (P (str " {:adsupply/seller " (es s) " :adsupply/seller-betweenness " btw " "
              ":adsupply/seller-fan-in " fan " :adsupply/derived true}")))
    (doseq [[asn load n] (get a "infra_rank")]
      (P (str " {:adsupply/asn " (es asn) " :adsupply/infra-concentration " (pynum load) " "
              ":adsupply/scam-creatives " n " :adsupply/derived true}")))
    (doseq [[reg load n] (get a "registrar_rank")]
      (P (str " {:adsupply/registrar " (es reg) " :adsupply/registrar-fraud-load " (pynum load) " "
              ":adsupply/registrar-cooccurrence " n " :adsupply/derived true}")))
    (doseq [[org load n] (get a "whois_rank")]
      (P (str " {:adsupply/whois-org " (es org) " :adsupply/whois-fraud-load " (pynum load) " "
              ":adsupply/whois-cooccurrence " n " :adsupply/derived true}")))
    (doseq [c (get a "clusters")]
      (P (str " {:adfraud/cluster " (es (str (:asn c) "|" (:registrar c))) " "
              ":adfraud/cluster-asn " (es (:asn c)) " :adfraud/cluster-registrar " (es (:registrar c)) " "
              ":adfraud/cluster-members " (:members c) " :adfraud/cluster-confidence " (pynum (:conf-sum c)) " "
              ":adfraud/cluster-corroboration " (:corroboration c) " "
              ":adfraud/network-rank " (pynum (:rank-score c)) " :adfraud/derived true}")))
    (doseq [[cat load] (get a "category_rank")]
      (P (str " {:adfraud/category " (es (lstrip-colon cat)) " "
              ":adfraud/category-load " (pynum (pyround load 2)) " :adfraud/derived true}")))
    (P "]")
    (str (str/join "\n" (persistent! L)) "\n")))

;; ── I/O edge ──────────────────────────────────────────────────────────────

#?(:clj
   (defn -main
     "CLI entry: load a seed EDN graph → out/intel-report.md + out/ad-fraud-clusters.kotoba.edn."
     [& argv]
     (let [argv (vec argv)
           here (or (when (and *file* (.exists (io/file *file*)))
                      (-> *file* io/file .getParentFile .getParentFile))
                    (io/file "20-actors" "sukashi"))
           seed0 (if (and (seq argv) (not (str/starts-with? (first argv) "--")))
                   (io/file (first argv))
                   (io/file here "data" "seed-ad-supply-chain.kotoba.edn"))
           merged (io/file here "data" "ad-supply-chain.merged.kotoba.edn")
           seed (if (and (= (.getPath seed0) (.getPath (io/file here "data" "seed-ad-supply-chain.kotoba.edn")))
                         (.exists merged))
                  merged seed0)
           outdir (if (some #{"--out"} argv)
                    (io/file (nth argv (inc (.indexOf argv "--out"))))
                    (io/file here "out"))
           rows (edn/load-edn seed)
           {:keys [adtech auth creatives delivery fraud]} (edn/classify rows)
           a (analyze adtech auth creatives delivery fraud)]
       (.mkdirs outdir)
       (spit (io/file outdir "intel-report.md") (render-report adtech auth creatives delivery fraud a))
       (spit (io/file outdir "ad-fraud-clusters.kotoba.edn") (render-datoms a))
       (println (str "sukashi: " (count adtech) " ad-tech entities, " (count auth) " auth edges, "
                     (count creatives) " creatives, " (count delivery) " delivery edges, "
                     (count fraud) " fraud signals"))
       (println (str "unauthorized/unconfirmed sellers: " (count (get a "unconfirmed_rate")) "  ·  "
                     "account-id collisions (spoof surface): " (count (get a "acct_collisions")) "  ·  "
                     "candidate scam-ad networks: " (count (get a "clusters"))))
       (when (seq (get a "infra_rank"))
         (let [top (first (get a "infra_rank"))]
           (println (str "top scam-delivery ASN: " (lstrip-colon (nth top 0))
                         " (fraud-weight " (pynum (nth top 1)) ", " (nth top 2) " creatives)"))))
       (println (str "wrote " (io/file outdir "intel-report.md") " + "
                     (io/file outdir "ad-fraud-clusters.kotoba.edn")))
       0)))
