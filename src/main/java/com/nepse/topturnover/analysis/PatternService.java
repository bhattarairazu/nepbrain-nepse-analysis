package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;
import com.nepse.topturnover.repository.PatternRepository;
import com.nepse.topturnover.repository.PatternRepository.Row;

/**
 * Pattern scanner. The rules come from mining six years of stored prices (Jan 2021 - Nov 2026): each held up across the years,
 * and two of them only in a weak market (the equal-weight market below its 50-session EMA), which is part of the rule.
 * Every rule trades the same way: buy at the next open, sell at +10% (on the high) or on a close 7% below the entry, otherwise
 * after about 20 sessions; never before T+3; 0.8% round-trip costs. Picks are recorded after each daily crawl (LIVE) and can be
 * reconstructed for past sessions (REPLAY); both are kept. The first, one-year rule (QUIET_COIL) is kept as retired.
 */
@Service
public class PatternService {
    private static final Logger LOG = LoggerFactory.getLogger(PatternService.class);
    public static final double MIN_AVG_TURNOVER = 5_000_000, MIN_DAY_TURNOVER = 2_500_000;
    public static final double TARGET = 10, STOP = 7;
    public static final int HOLD = 20, MIN_HISTORY = 65, MARKET_EMA = 50;
    /** Warning levels: each was worse than the average stock in every one of the six years. */
    public static final double WARN_ABOVE_EMA200 = 16, WARN_ABOVE_LOW60 = 33, WARN_MACD_HIST = 0.47, WARN_ATR = 4.4;

    public enum Rule {
        TIGHT_RANGE60("Tight 60-day range", "T",
                "The price has stayed within about 6% of both its 60-session high and its 60-session low: three months of flat trading.",
                false, false,
                "Six years: 354 trades, about +2.6% a trade. With the market above its 50-day average +2.8% (61% won) and positive in all six years; "
                        + "with a weak market +2.2% (4 of 5 years). Found using 2021-2025 only, it then made +1.5% in 2026."),
        QUIET_COIL_WEAK("Quiet coil + rising turnover, weak market", "Q",
                "A calm stock in a tight 20-session range that has not jumped yet while its turnover rises; only when the whole market is "
                        + "below its 50-day average.",
                true, false,
                "Six years, weak market: 157 trades, +2.8% a trade, 65% won, no losing year (2022 +6.9, 2023 +1.6, 2024 +4.7, 2025 0.0, "
                        + "2026 +3.7). In a strong market only +0.7%, so the market filter is part of the rule."),
        DIP_NEAR_HIGH_WEAK("Dip near the 60-day high, weak market", "D",
                "A strong stock (within about 8% of its 60-session high) that has dipped to its lower Bollinger band; only when the whole "
                        + "market is below its 50-day average.",
                true, false,
                "Six years, weak market: 247 trades, +2.4% a trade, 62% won, positive in 4 of 5 years (2023 −1.5). In a strong market +0.6% "
                        + "and positive in only 2 of 6 years."),
        COILED_NEAR_HIGH("Coiled near the high, before a breakout", "A",
                "Three months of flat trading just under the 60-session high (like T), in a calm stock (ATR ≤ 3%) whose sector is not "
                        + "running (sector up ≤ 3.4% in 20 sessions). Bought before any breakout.",
                false, false,
                "Six years: 278 trades, +2.8% a trade, 63% won, positive in every year (2022 +5.5, 2023 +4.1, 2024 +4.9, 2025 +3.0, "
                        + "2026 +1.7). About 1 in 4 broke out within 10 sessions. Buying on the breakout itself lost −0.6% a trade."),
        PRESSING_HIGH("Pressing the 60-day high", "B",
                "Still 0–6% under the 60-session high, closing at the upper Bollinger band, without having run up first (≤ +5.8% in 20 "
                        + "sessions). Bought before the breakout.",
                false, false,
                "Six years: 815 trades, +2.2% a trade, 57% won, positive in every year (2021 +3.7, 2022 +3.1, 2023 +2.2, 2024 +4.3, "
                        + "2025 +2.2, 2026 +0.1). Half broke out within 10 sessions. Found on 2021-2024; 2025-26 +1.0%."),
        QUIET_COIL("Quiet coil (first one-year version, retired)", "q",
                "The first rule, found on one year of data, without the market filter.", false, true,
                "Over six years +1.2% a trade but it lost in 2021 and 2025; replaced by the weak-market version.");

        private final String title, letter, idea, evidence;
        private final boolean weakMarket, retired;

        Rule(String title, String letter, String idea, boolean weakMarket, boolean retired, String evidence) {
            this.title = title; this.letter = letter; this.idea = idea; this.weakMarket = weakMarket; this.retired = retired; this.evidence = evidence;
        }

        public String getTitle() { return title; }
        public String getLetter() { return letter; }
        public String getIdea() { return idea; }
        public String getEvidence() { return evidence; }
        public boolean isWeakMarket() { return weakMarket; }
        public boolean isRetired() { return retired; }
        public String getCode() { return name(); }

        public static List<Rule> active() { return Arrays.stream(values()).filter(r -> !r.retired).toList(); }

        public static Optional<Rule> of(String code) {
            return Arrays.stream(values()).filter(r -> r.name().equals(code)).findFirst();
        }
    }

    /** One condition of a rule for one stock-day. */
    public record Check(String name, String need, Double value, String shown, boolean pass) {}

    /** The indicator values of one stock at the close of one session (all from data up to that close). */
    public record Metrics(double close, double turnover, Double avgTurnover20, double atrPct, double range20Pct, double ret5Pct, Double macdHistPct,
                          double fromLow60Pct, double fromHigh60Pct, Double turnoverX, Double updownVol, Double rsi14, Double pctB, Double vsEma200Pct,
                          Double marketVsEma50Pct, Double ret20Pct, Double underLevelPct, Double sectorRet20Pct) {
        public boolean liquid() { return avgTurnover20 != null && avgTurnover20 >= MIN_AVG_TURNOVER && turnover >= MIN_DAY_TURNOVER; }
        public boolean weakMarket() { return marketVsEma50Pct != null && marketVsEma50Pct < 0; }

        /** Warning signs: each was followed by worse-than-average trades in every one of the six years. */
        public List<String> warnings() {
            List<String> w = new ArrayList<>();
            if (vsEma200Pct != null && vsEma200Pct > WARN_ABOVE_EMA200) w.add(String.format("%.0f%% above its 200-day average", vsEma200Pct));
            if (fromLow60Pct > WARN_ABOVE_LOW60) w.add(String.format("%.0f%% above its 60-day low", fromLow60Pct));
            if (macdHistPct != null && macdHistPct > WARN_MACD_HIST) w.add("just jumped (MACD histogram " + String.format("%.2f%%", macdHistPct) + ")");
            if (atrPct > WARN_ATR) w.add(String.format("very jumpy (daily moves %.1f%%)", atrPct));
            return w;
        }
    }

    /** A rule applied to one stock at the close of one session. */
    public record Eval(Rule rule, String symbol, String name, String sector, LocalDate date, double close, boolean match, List<Check> checks,
                       Metrics x, String note) {
        public long passed() { return checks.stream().filter(Check::pass).count(); }
        public double target() { return close * (1 + TARGET / 100); }
        public double stop() { return close * (1 - STOP / 100); }
        public Double turnoverX() { return x == null ? null : x.turnoverX(); }
        public double turnover() { return x == null ? 0 : x.turnover(); }
        public Check firstMiss() { return checks.stream().filter(c -> !c.pass()).findFirst().orElse(null); }
    }

    public enum Status {
        WAITING("Buy at the next open", "neutral"), OPEN("Open", "warn"), TARGET("Target hit", "good"), STOP("Stopped out", "bad"),
        TIME("Time exit", "neutral"), GONE("No later prices", "neutral");

        private final String title, tone;

        Status(String title, String tone) { this.title = title; this.tone = tone; }

        public String getTitle() { return title; }
        public String getTone() { return tone; }
    }

    /** What happened to a pick: bought at the next open, sold by the rule's exit (or still open at the latest close). */
    public record Outcome(Status status, LocalDate entryDate, Double entry, LocalDate exitDate, Double exit, Double netPct, int sessions,
                          Double bestPct, Double worstPct, double lastClose, LocalDate lastDate) {
        public boolean closed() { return status == Status.TARGET || status == Status.STOP || status == Status.TIME; }
    }

    /** A recorded pick and its outcome; repeat = the same rule already held this stock from an earlier pick, so it is not a new trade. */
    public record Pick(Row row, Outcome outcome, boolean repeat) {
        Pick(Row row, Outcome outcome) { this(row, outcome, false); }

        public Rule rule() { return Rule.of(row.rule()).orElse(null); }
    }

    /** Results counting one position per stock and rule at a time: picks made while the stock was already held are left out. */
    public record Summary(int picks, int repeats, int trades, int closed, int open, int wins, double winPct, double avgNetPct, double medianNetPct,
                          int targets, int stops) {
        static Summary of(List<Pick> all) {
            List<Pick> picks = all.stream().filter(p -> !p.repeat()).toList();
            List<Double> net = picks.stream().filter(p -> p.outcome().closed()).map(p -> p.outcome().netPct()).sorted().toList();
            int wins = (int) net.stream().filter(d -> d > 0).count();
            return new Summary(all.size(), all.size() - picks.size(), picks.size(), net.size(),
                    (int) picks.stream().filter(p -> p.outcome().status() == Status.OPEN).count(), wins,
                    net.isEmpty() ? 0 : 100.0 * wins / net.size(), net.stream().mapToDouble(d -> d).average().orElse(0),
                    net.isEmpty() ? 0 : net.get(net.size() / 2),
                    (int) picks.stream().filter(p -> p.outcome().status() == Status.TARGET).count(),
                    (int) picks.stream().filter(p -> p.outcome().status() == Status.STOP).count());
        }
    }

    /**
     * Marks picks made while an earlier pick of the same stock by the same rule was still held (bought and not yet sold) as repeats.
     * The list must hold every pick of each stock and rule it contains.
     */
    static List<Pick> markRepeats(List<Pick> picks) {
        Map<String, LocalDate> heldUntil = new java.util.HashMap<>();
        Map<Pick, Boolean> repeat = new java.util.IdentityHashMap<>();
        picks.stream().sorted(Comparator.comparing((Pick p) -> p.row().rule() + "|" + p.row().symbol()).thenComparing(p -> p.row().date())).forEach(p -> {
            String key = p.row().rule() + "|" + p.row().symbol();
            LocalDate until = heldUntil.get(key);
            boolean rep = until != null && p.row().date().isBefore(until);
            repeat.put(p, rep);
            if (!rep) {
                Outcome o = p.outcome();
                heldUntil.put(key, o.exitDate() != null ? o.exitDate() : LocalDate.MAX); // open or waiting: still held
            }
        });
        return picks.stream().map(p -> new Pick(p.row(), p.outcome(), repeat.get(p))).toList();
    }

    public static Summary summarise(List<Pick> picks) {
        return Summary.of(picks);
    }

    /** One rule's picks at the latest close. */
    public record RuleToday(Rule rule, boolean active, List<Eval> matches, List<Eval> nearMisses, boolean recorded) {}

    public record Today(LocalDate date, double marketVsEma50Pct, List<RuleToday> rules, int scanned) {
        public boolean weakMarket() { return marketVsEma50Pct < 0; }
        public int picks() { return rules.stream().mapToInt(r -> r.matches().size()).sum(); }
    }

    /** One session of a stock: which rules matched. */
    public record Day(LocalDate date, List<Rule> matched, Metrics x) {}

    public record StockCheck(String symbol, String name, String sector, LocalDate date, Metrics now, List<Eval> rules, List<Day> recent,
                             List<Pick> past, Map<Rule, Summary> pastByRule) {}

    public record History(Rule rule, LocalDate date, List<Pick> picks, Summary summary, LocalDate latest) {}

    /** A rule's track record: live picks, and replayed picks per calendar year. */
    public record Track(Rule rule, Summary live, Summary replay, Map<Integer, Summary> replayByYear) {}

    private final BacktestService backtest;
    private final PatternRepository repo;

    public PatternService(BacktestService backtest, PatternRepository repo) {
        this.backtest = backtest;
        this.repo = repo;
    }

    // ------------------------------------------------------------------ pages

    /** Every active rule at the latest stored close: matches, and the liquid stocks that missed by one condition. */
    public Optional<Today> today() {
        Market m = backtest.data();
        if (m == null) return Optional.empty();
        int last = m.dates.length - 1;
        double[] mkt = marketEma(m);
        double mktVs = (m.index[last] / mkt[last] - 1) * 100;
        Map<Rule, List<Eval>> all = new LinkedHashMap<>();
        Rule.active().forEach(r -> all.put(r, new ArrayList<>()));
        int scanned = 0;
        for (Series s : m.series.values()) {
            int i = s.indexOf(last);
            if (i < MIN_HISTORY || !Sectors.isEquity(s.sector)) continue;
            Metrics x = metrics(m, mkt, s, i);
            scanned++;
            for (Rule r : Rule.active()) all.get(r).add(eval(r, s, i, x));
        }
        List<RuleToday> rules = new ArrayList<>();
        for (Rule r : Rule.active()) {
            List<Eval> l = all.get(r);
            List<Eval> matches = l.stream().filter(Eval::match)
                    .sorted(Comparator.comparing(Eval::turnoverX, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
            List<Eval> near = l.stream().filter(e -> !e.match() && e.checks().get(0).pass() && e.passed() == e.checks().size() - 1)
                    .sorted(Comparator.comparing(Eval::turnover).reversed()).limit(15).toList();
            boolean recorded = matches.isEmpty() || !repo.onDate(r.name(), m.dates[last]).isEmpty();
            rules.add(new RuleToday(r, !r.weakMarket || mktVs < 0, matches, near, recorded));
        }
        return Optional.of(new Today(m.dates[last], mktVs, rules, scanned));
    }

    /** Every rule for one stock: now, the last 30 sessions, and every past pick with its outcome. */
    public Optional<StockCheck> check(String symbol) {
        Market m = backtest.data();
        if (m == null) return Optional.empty();
        Series s = m.series.get(symbol);
        if (s == null) return Optional.empty();
        double[] mkt = marketEma(m);
        int n = s.size();
        if (n - 1 < MIN_HISTORY) {
            return Optional.of(new StockCheck(s.symbol, s.name, s.sector, s.dates[n - 1], null, List.of(), List.of(), List.of(), Map.of()));
        }
        Metrics now = metrics(m, mkt, s, n - 1);
        List<Eval> rules = Rule.active().stream().map(r -> eval(r, s, n - 1, now)).toList();
        List<Day> recent = new ArrayList<>();
        for (int i = n - 1; i >= Math.max(MIN_HISTORY, n - 30); i--) {
            Metrics x = metrics(m, mkt, s, i);
            final int day = i;
            recent.add(new Day(s.dates[i], Rule.active().stream().filter(r -> eval(r, s, day, x).match()).toList(), x));
        }
        // stored picks of every rule, plus replayed picks of the active rules that are not stored yet
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Rule r : Rule.values()) for (Row row : repo.forSymbol(r.name(), symbol)) rows.put(r.name() + row.date(), row);
        for (int i = MIN_HISTORY; i < n; i++) {
            Metrics x = metrics(m, mkt, s, i);
            for (Rule r : Rule.active()) {
                Eval e = eval(r, s, i, x);
                if (e.match()) rows.putIfAbsent(r.name() + e.date(), row(e, "REPLAY", null));
            }
        }
        List<Pick> past = markRepeats(rows.values().stream()
                .map(r -> new Pick(r, outcome(s, s.indexOf(Arrays.binarySearch(m.dates, r.date())), r.close()))).toList())
                .stream().sorted(Comparator.comparing((Pick p) -> p.row().date()).reversed()).toList();
        Map<Rule, Summary> byRule = new LinkedHashMap<>();
        for (Rule r : Rule.values()) {
            List<Pick> l = past.stream().filter(p -> p.row().rule().equals(r.name())).toList();
            if (!l.isEmpty()) byRule.put(r, Summary.of(l));
        }
        return Optional.of(new StockCheck(s.symbol, s.name, s.sector, s.dates[n - 1], now, rules, recent, past, byRule));
    }

    /** What a rule recommended on a session and how each pick did since. */
    public History history(Rule rule, LocalDate date) {
        Market m = backtest.data();
        List<Pick> picks = new ArrayList<>();
        LocalDate latest = m == null ? null : m.dates[m.dates.length - 1];
        for (Row r : repo.onDate(rule.name(), date))
            for (Row x : repo.forSymbol(rule.name(), r.symbol())) if (!x.date().isAfter(date)) picks.add(new Pick(x, outcomeFor(m, x)));
        List<Pick> marked = markRepeats(picks).stream().filter(p -> p.row().date().equals(date))
                .sorted(Comparator.comparing((Pick p) -> p.row().turnoverX(), Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        return new History(rule, date, marked, Summary.of(marked), latest);
    }

    /** A rule's track record: live picks, all replayed picks, and replayed picks per year. */
    public Track track(Rule rule) {
        Market m = backtest.data();
        List<Pick> all = markRepeats(repo.between(rule.name(), LocalDate.of(1990, 1, 1), LocalDate.of(2999, 1, 1)).stream()
                .map(r -> new Pick(r, outcomeFor(m, r))).toList());
        List<Pick> replay = all.stream().filter(p -> p.row().source().equals("REPLAY")).toList();
        Map<Integer, Summary> byYear = new TreeMap<>(Comparator.reverseOrder());
        replay.stream().map(p -> p.row().date().getYear()).distinct()
                .forEach(y -> byYear.put(y, Summary.of(replay.stream().filter(p -> p.row().date().getYear() == y).toList())));
        return new Track(rule, Summary.of(all.stream().filter(p -> p.row().source().equals("LIVE")).toList()), Summary.of(replay), byYear);
    }

    public List<PatternRepository.DateCount> dates(Rule rule) {
        return repo.dates(rule.name());
    }

    // ------------------------------------------------------------------ recording

    /** Called after the daily crawl stored a session: records that session's picks of every active rule (LIVE). */
    public int recordAfterCrawl(LocalDate session) {
        backtest.evict(); // prices for the session were just stored (or replaced)
        Market m = backtest.data();
        if (m == null || !m.dates[m.dates.length - 1].equals(session)) return 0;
        int n = recordToday();
        LOG.info("Pattern scanner: {} new pick(s) on {}", n, session);
        return n;
    }

    /** Records the latest session's picks now, as LIVE: they are recorded before the next session is known. */
    public int recordToday() {
        Today t = today().orElse(null);
        if (t == null) return 0;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return repo.insertNew(t.rules().stream().flatMap(r -> r.matches().stream()).map(e -> row(e, "LIVE", now)).toList());
    }

    /** Reconstructs the picks of every active rule for every stored session (REPLAY); sessions already recorded keep their record. */
    public int replayAll() {
        Market m = backtest.data();
        if (m == null) return 0;
        double[] mkt = marketEma(m);
        List<Row> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDate latest = m.dates[m.dates.length - 1];
        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector)) continue;
            for (int i = MIN_HISTORY; i < s.size(); i++) {
                Metrics x = metrics(m, mkt, s, i);
                if (!x.liquid()) continue;
                for (Rule r : Rule.active()) {
                    Eval e = eval(r, s, i, x);
                    // the latest session's picks are recorded before anything later is known: they count as live
                    if (e.match()) rows.add(row(e, e.date().equals(latest) ? "LIVE" : "REPLAY", now));
                }
            }
        }
        return repo.insertNew(rows);
    }

    // ------------------------------------------------------------------ the rules

    static Metrics metrics(Market m, double[] mkt, Series s, int i) {
        RuleSetService.Ctx x = ctx(s);
        double c = s.close[i], to = nz(s.turnover[i]);
        double hi20 = Double.NEGATIVE_INFINITY, lo20 = Double.POSITIVE_INFINITY, lo60 = Double.POSITIVE_INFINITY, hi60 = Double.NEGATIVE_INFINITY;
        for (int k = i - 19; k <= i; k++) { hi20 = Math.max(hi20, hi(s, k)); lo20 = Math.min(lo20, lo(s, k)); }
        for (int k = i - 59; k <= i; k++) { lo60 = Math.min(lo60, lo(s, k)); hi60 = Math.max(hi60, hi(s, k)); }
        double hist = (x.macd[i] - x.signal[i]) / c * 100;
        Double t5 = Indicators.mean(s.turnover, i - 4, i + 1, 3), t60 = Indicators.mean(s.turnover, i - 64, i - 4, 40);
        double up = 0, dn = 0;
        for (int k = i - 19; k <= i; k++) {
            double v = nz(s.volume[k]);
            if (s.close[k] > s.close[k - 1]) up += v; else if (s.close[k] < s.close[k - 1]) dn += v;
        }
        double band = x.upper[i] - x.lower[i];
        int g = s.globalIndex[i];
        double level = Double.NEGATIVE_INFINITY; // highest high of the 60 sessions before this one: the breakout level
        for (int k = i - 60; k <= i - 1; k++) level = Math.max(level, hi(s, k));
        double[] sec = s.sector == null ? null : sectorRet20(m).get(s.sector);
        return new Metrics(c, to, Indicators.mean(s.turnover, i - 19, i + 1, 10), x.atr14[i] / c * 100, (hi20 / lo20 - 1) * 100,
                (c / s.close[i - 5] - 1) * 100, Double.isNaN(hist) ? null : hist, (c / lo60 - 1) * 100, (c / hi60 - 1) * 100,
                t5 != null && t60 != null && t60 > 0 ? t5 / t60 : null, dn > 0 ? up / dn : null, finite(x.rsi14[i]),
                band > 0 ? (c - x.lower[i]) / band : null, finite((c / x.ema200[i] - 1) * 100),
                Double.isNaN(mkt[g]) ? null : (m.index[g] / mkt[g] - 1) * 100, finite((c / s.close[i - 20] - 1) * 100),
                level > 0 ? (c / level - 1) * 100 : null, sec == null ? null : finite(sec[g]));
    }

    static Eval eval(Rule r, Series s, int i, Metrics x) {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Liquid", "average turnover ≥ Rs 50 L (20 sessions) and ≥ Rs 25 L today", x.avgTurnover20(),
                money(x.avgTurnover20()) + " avg · " + money(x.turnover()) + " today", x.liquid()));
        switch (r) {
            case TIGHT_RANGE60 -> {
                checks.add(new Check("Near its 60-day high", "within 6.4% of the 60-session high", x.fromHigh60Pct(), signed(x.fromHigh60Pct()),
                        x.fromHigh60Pct() >= -6.4));
                checks.add(new Check("Near its 60-day low", "at most 6.4% above the 60-session low", x.fromLow60Pct(), signed(x.fromLow60Pct()),
                        x.fromLow60Pct() <= 6.4));
            }
            case QUIET_COIL_WEAK, QUIET_COIL -> {
                checks.add(new Check("Quiet: small daily moves", "ATR(14) ≤ 3% of price", x.atrPct(), pct(x.atrPct()), x.atrPct() <= 3.0));
                checks.add(new Check("Tight range", "20-session high-low range ≤ 12.5%", x.range20Pct(), pct(x.range20Pct()), x.range20Pct() <= 12.5));
                checks.add(new Check("Not popped: 5 sessions", "5-session change ≤ +0.6%", x.ret5Pct(), signed(x.ret5Pct()), x.ret5Pct() <= 0.6));
                checks.add(new Check("Not popped: MACD", "MACD histogram ≤ 0.13% of price", x.macdHistPct(),
                        x.macdHistPct() == null ? "—" : signed(x.macdHistPct()), x.macdHistPct() != null && x.macdHistPct() <= 0.13));
                checks.add(new Check("Not extended", "≤ 17% above its 60-session low", x.fromLow60Pct(), signed(x.fromLow60Pct()), x.fromLow60Pct() <= 17));
                checks.add(new Check("Money coming in", "last 5 sessions' turnover ≥ 1.3× the 60 before", x.turnoverX(),
                        x.turnoverX() == null ? "—" : String.format("%.2f×", Math.floor(x.turnoverX() * 100) / 100), x.turnoverX() != null && x.turnoverX() >= 1.3));
            }
            case COILED_NEAR_HIGH -> {
                checks.add(new Check("Near its 60-day high", "within 6.4% of the 60-session high", x.fromHigh60Pct(), signed(x.fromHigh60Pct()),
                        x.fromHigh60Pct() >= -6.4));
                checks.add(new Check("Near its 60-day low", "at most 6.4% above the 60-session low", x.fromLow60Pct(), signed(x.fromLow60Pct()),
                        x.fromLow60Pct() <= 6.4));
                checks.add(new Check("Calm stock", "ATR(14) ≤ 3% of price", x.atrPct(), pct(x.atrPct()), x.atrPct() <= 3.0));
                checks.add(new Check("Sector not running", "sector's median stock up ≤ 3.4% in 20 sessions", x.sectorRet20Pct(),
                        x.sectorRet20Pct() == null ? "—" : signed(x.sectorRet20Pct()), x.sectorRet20Pct() != null && x.sectorRet20Pct() <= 3.4));
            }
            case PRESSING_HIGH -> {
                checks.add(new Check("Just under the breakout level", "0–6% under the highest high of the previous 60 sessions",
                        x.underLevelPct(), x.underLevelPct() == null ? "—" : signed(x.underLevelPct()),
                        x.underLevelPct() != null && x.underLevelPct() <= 0 && x.underLevelPct() >= -6));
                checks.add(new Check("At the upper band", "Bollinger %b ≥ 0.99 (closing at the upper band)", x.pctB(),
                        x.pctB() == null ? "—" : String.format("%.2f", x.pctB()), x.pctB() != null && x.pctB() >= 0.994));
                checks.add(new Check("Has not run up", "20-session change ≤ +5.8%", x.ret20Pct(), x.ret20Pct() == null ? "—" : signed(x.ret20Pct()),
                        x.ret20Pct() != null && x.ret20Pct() <= 5.76));
            }
            case DIP_NEAR_HIGH_WEAK -> {
                checks.add(new Check("Strong stock", "within 8.3% of its 60-session high", x.fromHigh60Pct(), signed(x.fromHigh60Pct()),
                        x.fromHigh60Pct() >= -8.32));
                checks.add(new Check("Dipped to the lower band", "Bollinger %b ≤ 0.22 (0 = lower band, 1 = upper)", x.pctB(),
                        x.pctB() == null ? "—" : String.format("%.2f", x.pctB()), x.pctB() != null && x.pctB() <= 0.22));
            }
        }
        if (r.weakMarket) {
            checks.add(new Check("Weak market", "equal-weight market below its 50-session average", x.marketVsEma50Pct(),
                    x.marketVsEma50Pct() == null ? "—" : signed(x.marketVsEma50Pct()), x.weakMarket()));
        }
        return new Eval(r, s.symbol, s.name, s.sector, s.dates[i], s.close[i], checks.stream().allMatch(Check::pass), checks, x, null);
    }

    /** Buy at the open after the signal; target on the high, stop on the close, time exit; no sale before T+3. */
    static Outcome outcome(Series s, int i, double signalClose) {
        if (s == null || i < 0) return new Outcome(Status.GONE, null, null, null, null, null, 0, null, null, signalClose, null);
        int n = s.size(), lt = n - 1;
        int e = i + 1;
        if (e >= n) return new Outcome(Status.WAITING, null, null, null, null, null, 0, null, null, s.close[lt], s.dates[lt]);
        double entry = s.open[e] > 0 ? s.open[e] : s.close[i];
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;
        int lastJ = e + HOLD + 2;
        for (int j = e; j < n && j <= lastJ; j++) {
            best = Math.max(best, (hi(s, j) / entry - 1) * 100);
            worst = Math.min(worst, (s.close[j] / entry - 1) * 100);
            if (j < e + BacktestService.EARLIEST_SALE) continue;
            if (hi(s, j) >= entry * (1 + TARGET / 100))
                return new Outcome(Status.TARGET, s.dates[e], entry, s.dates[j], entry * (1 + TARGET / 100), TARGET - BacktestService.COST_PCT,
                        j - e + 1, best, worst, s.close[lt], s.dates[lt]);
            if (s.close[j] <= entry * (1 - STOP / 100))
                return new Outcome(Status.STOP, s.dates[e], entry, s.dates[j], s.close[j], (s.close[j] / entry - 1) * 100 - BacktestService.COST_PCT,
                        j - e + 1, best, worst, s.close[lt], s.dates[lt]);
            if (j == lastJ)
                return new Outcome(Status.TIME, s.dates[e], entry, s.dates[j], s.close[j], (s.close[j] / entry - 1) * 100 - BacktestService.COST_PCT,
                        j - e + 1, best, worst, s.close[lt], s.dates[lt]);
        }
        return new Outcome(Status.OPEN, s.dates[e], entry, null, null, (s.close[lt] / entry - 1) * 100 - BacktestService.COST_PCT, lt - e + 1,
                best, worst, s.close[lt], s.dates[lt]);
    }

    private static Outcome outcomeFor(Market m, Row r) {
        Series s = m == null ? null : m.series.get(r.symbol());
        if (s == null) return outcome(null, -1, r.close());
        int g = Arrays.binarySearch(m.dates, r.date());
        return outcome(s, g < 0 ? -1 : s.indexOf(g), r.close());
    }

    private static Row row(Eval e, String source, LocalDateTime at) {
        Metrics x = e.x();
        return new Row(e.rule().name(), e.date(), e.symbol(), e.sector(), e.close(), TARGET, STOP, HOLD, x.atrPct(), x.range20Pct(), x.ret5Pct(),
                x.macdHistPct(), x.fromLow60Pct(), x.turnoverX(), x.updownVol(), x.rsi14(), x.turnover(), source, at);
    }

    // indicator arrays per stock, and the market's EMA, are reused while the same price data is loaded
    private static final Map<Series, RuleSetService.Ctx> CTX = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<Market, double[]> MARKET = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<Market, Map<String, double[]>> SECTOR = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Per sector, per market session: the median 20-session change of the sector's stocks that traded that session (NaN when unknown). */
    static Map<String, double[]> sectorRet20(Market m) {
        return SECTOR.computeIfAbsent(m, k -> {
            Map<String, List<double[]>> bySector = new java.util.HashMap<>();
            int days = k.dates.length;
            for (Series s : k.series.values()) {
                if (s.sector == null || !Sectors.isEquity(s.sector)) continue;
                double[] r = new double[days];
                Arrays.fill(r, Double.NaN);
                for (int i = 20; i < s.size(); i++) if (s.close[i - 20] > 0) r[s.globalIndex[i]] = (s.close[i] / s.close[i - 20] - 1) * 100;
                bySector.computeIfAbsent(s.sector, x -> new ArrayList<>()).add(r);
            }
            Map<String, double[]> out = new java.util.HashMap<>();
            bySector.forEach((sector, rows) -> {
                double[] med = new double[days];
                double[] buf = new double[rows.size()];
                for (int g = 0; g < days; g++) {
                    int n = 0;
                    for (double[] r : rows) if (!Double.isNaN(r[g])) buf[n++] = r[g];
                    if (n == 0) { med[g] = Double.NaN; continue; }
                    Arrays.sort(buf, 0, n);
                    med[g] = n % 2 == 1 ? buf[n / 2] : (buf[n / 2 - 1] + buf[n / 2]) / 2;
                }
                out.put(sector, med);
            });
            return out;
        });
    }

    private static RuleSetService.Ctx ctx(Series s) {
        return CTX.computeIfAbsent(s, RuleSetService.Ctx::new);
    }

    static double[] marketEma(Market m) {
        return MARKET.computeIfAbsent(m, k -> Ind.ema(k.index, MARKET_EMA));
    }

    private static double hi(Series s, int i) { return Double.isNaN(s.high[i]) ? s.close[i] : s.high[i]; }

    private static double lo(Series s, int i) { return Double.isNaN(s.low[i]) ? s.close[i] : s.low[i]; }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    private static Double finite(double v) { return Double.isFinite(v) ? v : null; }

    private static String pct(double v) { return String.format("%.2f%%", v); }

    private static String signed(double v) { return Double.isNaN(v) ? "—" : String.format("%+.2f%%", v); }

    private static String money(Double v) {
        if (v == null) return "—";
        return v >= 1e7 ? String.format("Rs %.2f Cr", v / 1e7) : String.format("Rs %.1f L", v / 1e5);
    }
}
