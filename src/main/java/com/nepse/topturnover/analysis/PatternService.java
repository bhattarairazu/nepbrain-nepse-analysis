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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.repository.PatternRepository;
import com.nepse.topturnover.repository.PatternRepository.Row;

/**
 * Pattern scanner: "quiet coil + rising turnover", the rule found by mining the stored history (Nov 2025 - Jun 2026 to find it,
 * Jun - Oct 2026 to check it). A liquid stock that moves little (ATR <= 3%), sits in a tight 20-session range (<= 12.5%),
 * has not popped yet (5-session change <= +0.6%, MACD histogram <= 0.13% of price, <= 17% above its 60-session low) while
 * its turnover rises (last 5 sessions >= 1.3x the 60 before). Trade: buy at the next open, sell at +10% (on the high) or on a
 * close 7% below the entry, otherwise after about 20 sessions; never before T+3; 0.8% round-trip costs.
 * Picks are recorded after each daily crawl (LIVE) and can be reconstructed for past sessions (REPLAY); both are kept.
 */
@Service
public class PatternService {
    private static final Logger LOG = LoggerFactory.getLogger(PatternService.class);
    public static final String RULE = "QUIET_COIL";
    public static final double MAX_ATR = 3.0, MAX_RANGE = 12.5, MAX_RET5 = 0.6, MAX_MACD_HIST = 0.13, MAX_FROM_LOW = 17, MIN_TURNOVER_X = 1.3;
    public static final double MIN_AVG_TURNOVER = 5_000_000, MIN_DAY_TURNOVER = 2_500_000;
    public static final double TARGET = 10, STOP = 7;
    public static final int HOLD = 20, MIN_HISTORY = 65;
    /** The rule was found on sessions before this date; later sessions were only used to check it. */
    public static final LocalDate FOUND_UNTIL = LocalDate.of(2026, 6, 10);

    /** One condition of the rule for one stock-day. */
    public record Check(String name, String need, Double value, String shown, boolean pass) {}

    /** The rule applied to one stock at the close of one session. */
    public record Eval(String symbol, String name, String sector, LocalDate date, double close, boolean match, List<Check> checks,
                       Double atrPct, Double range20Pct, Double ret5Pct, Double macdHistPct, Double fromLow60Pct, Double turnoverX,
                       Double updownVol, Double rsi14, double turnover, String note) {
        public long passed() { return checks.stream().filter(Check::pass).count(); }
        public double target() { return close * (1 + TARGET / 100); }
        public double stop() { return close * (1 - STOP / 100); }
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

    /** A recorded pick and its outcome; repeat = the same stock was already held from an earlier pick, so it is not a new trade. */
    public record Pick(Row row, Outcome outcome, boolean repeat) {
        Pick(Row row, Outcome outcome) { this(row, outcome, false); }
    }

    /** Results counting one position per stock at a time: picks made while the stock was already held are left out. */
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
     * Marks picks made while an earlier pick of the same stock was still held (bought and not yet sold) as repeats.
     * The list must hold every pick of each stock it contains.
     */
    static List<Pick> markRepeats(List<Pick> picks) {
        Map<String, LocalDate> heldUntil = new java.util.HashMap<>();
        Map<Pick, Boolean> repeat = new java.util.IdentityHashMap<>();
        picks.stream().sorted(Comparator.comparing((Pick p) -> p.row().symbol()).thenComparing(p -> p.row().date())).forEach(p -> {
            LocalDate until = heldUntil.get(p.row().symbol());
            boolean rep = until != null && p.row().date().isBefore(until);
            repeat.put(p, rep);
            if (!rep) {
                Outcome o = p.outcome();
                heldUntil.put(p.row().symbol(), o.exitDate() != null ? o.exitDate() : LocalDate.MAX); // open or waiting: still held
            }
        });
        return picks.stream().map(p -> new Pick(p.row(), p.outcome(), repeat.get(p))).toList();
    }

    public static Summary summarise(List<Pick> picks) {
        return Summary.of(picks);
    }

    public record Today(LocalDate date, List<Eval> matches, List<Eval> nearMisses, int scanned, boolean recorded) {}

    public record StockCheck(Eval now, List<Eval> recent, List<Pick> past, Summary pastSummary) {}

    public record History(LocalDate date, List<Pick> picks, Summary summary, LocalDate latest) {}

    private final BacktestService backtest;
    private final PatternRepository repo;

    public PatternService(BacktestService backtest, PatternRepository repo) {
        this.backtest = backtest;
        this.repo = repo;
    }

    // ------------------------------------------------------------------ pages

    /** The rule at the latest stored close: matches, and the stocks that missed by one condition. */
    public Optional<Today> today() {
        Market m = backtest.data();
        if (m == null) return Optional.empty();
        int last = m.dates.length - 1;
        List<Eval> all = new ArrayList<>();
        for (Series s : m.series.values()) {
            int i = s.indexOf(last);
            if (i < 0 || !Sectors.isEquity(s.sector)) continue;
            Eval e = evaluate(s, i);
            if (e.note() == null) all.add(e);
        }
        List<Eval> matches = all.stream().filter(Eval::match).sorted(Comparator.comparing(Eval::turnoverX, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        List<Eval> near = all.stream().filter(e -> !e.match() && e.checks().get(0).pass() && e.passed() == e.checks().size() - 1)
                .sorted(Comparator.comparing(Eval::turnover).reversed()).limit(25).toList();
        boolean recorded = !repo.onDate(RULE, m.dates[last]).isEmpty() || matches.isEmpty();
        return Optional.of(new Today(m.dates[last], matches, near, all.size(), recorded));
    }

    /** The rule for one stock: now, over the last sessions, and every past pick with its outcome. */
    public Optional<StockCheck> check(String symbol) {
        Market m = backtest.data();
        if (m == null) return Optional.empty();
        Series s = m.series.get(symbol);
        if (s == null) return Optional.empty();
        int n = s.size();
        Eval now = evaluate(s, n - 1);
        List<Eval> recent = new ArrayList<>();
        for (int i = n - 1; i >= Math.max(0, n - 30); i--) recent.add(evaluate(s, i));
        List<Pick> past = new ArrayList<>();
        Map<LocalDate, Row> stored = new LinkedHashMap<>();
        for (Row r : repo.forSymbol(RULE, symbol)) stored.put(r.date(), r);
        for (int i = n - 1; i >= MIN_HISTORY; i--) { // replayed picks not stored yet are shown too, marked as replay
            Eval e = evaluate(s, i);
            if (e.match() && !stored.containsKey(e.date())) stored.put(e.date(), row(e, "REPLAY", null));
        }
        stored.values().stream().sorted(Comparator.comparing(Row::date).reversed())
                .forEach(r -> past.add(new Pick(r, outcome(s, s.indexOf(Arrays.binarySearch(m.dates, r.date())), r.close()))));
        List<Pick> marked = markRepeats(past);
        return Optional.of(new StockCheck(now, recent, marked, Summary.of(marked)));
    }

    /** What the scanner recommended on a session and how each pick did since. */
    public History history(LocalDate date) {
        Market m = backtest.data();
        List<Pick> picks = new ArrayList<>();
        LocalDate latest = m == null ? null : m.dates[m.dates.length - 1];
        List<Row> day = repo.onDate(RULE, date);
        for (Row r : day) for (Row x : repo.forSymbol(RULE, r.symbol())) if (!x.date().isAfter(date)) picks.add(new Pick(x, outcomeFor(m, x)));
        List<Pick> marked = markRepeats(picks).stream().filter(p -> p.row().date().equals(date))
                .sorted(Comparator.comparing((Pick p) -> p.row().turnoverX(), Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        return new History(date, marked, Summary.of(marked), latest);
    }

    /** Every recorded pick between two dates with its outcome (for the overall track record). */
    public List<Pick> between(LocalDate from, LocalDate to) {
        Market m = backtest.data();
        return markRepeats(repo.between(RULE, from, to).stream().map(r -> new Pick(r, outcomeFor(m, r))).toList());
    }

    public List<PatternRepository.DateCount> dates() {
        return repo.dates(RULE);
    }

    // ------------------------------------------------------------------ recording

    /** Called after the daily crawl stored a session: records that session's picks (LIVE). */
    public int recordAfterCrawl(LocalDate session) {
        backtest.evict(); // prices for the session were just stored (or replaced)
        Market m = backtest.data();
        if (m == null || !m.dates[m.dates.length - 1].equals(session)) return 0;
        Today t = today().orElse(null);
        if (t == null) return 0;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int n = repo.insertNew(t.matches().stream().map(e -> row(e, "LIVE", now)).toList());
        LOG.info("Pattern scanner: {} pick(s) on {} ({} new)", t.matches().size(), session, n);
        return n;
    }

    /** Records today's picks now (the Today page's button), as LIVE: they are recorded before the next session is known. */
    public int recordToday() {
        Market m = backtest.data();
        if (m == null) return 0;
        Today t = today().orElse(null);
        return t == null ? 0 : repo.insertNew(t.matches().stream().map(e -> row(e, "LIVE", LocalDateTime.now().withNano(0))).toList());
    }

    /** Reconstructs the picks of every stored session (REPLAY); sessions already recorded keep their record. */
    public int replayAll() {
        Market m = backtest.data();
        if (m == null) return 0;
        List<Row> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDate latest = m.dates[m.dates.length - 1];
        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector)) continue;
            for (int i = MIN_HISTORY; i < s.size(); i++) {
                Eval e = evaluate(s, i);
                // the latest session's picks are recorded before anything later is known: they count as live
                if (e.match()) rows.add(row(e, e.date().equals(latest) ? "LIVE" : "REPLAY", now));
            }
        }
        return repo.insertNew(rows);
    }

    // ------------------------------------------------------------------ the rule

    static Eval evaluate(Series s, int i) {
        double c = s.close[i], to = nz(s.turnover[i]);
        if (i < MIN_HISTORY) {
            return new Eval(s.symbol, s.name, s.sector, s.dates[i], c, false, List.of(), null, null, null, null, null, null, null, null, to,
                    "Needs " + MIN_HISTORY + " stored sessions (has " + (i + 1) + ")");
        }
        RuleSetService.Ctx x = ctx(s);
        double atr = x.atr14[i] / c * 100;
        double hi20 = Double.NEGATIVE_INFINITY, lo20 = Double.POSITIVE_INFINITY, lo60 = Double.POSITIVE_INFINITY;
        for (int k = i - 19; k <= i; k++) { hi20 = Math.max(hi20, hi(s, k)); lo20 = Math.min(lo20, lo(s, k)); }
        for (int k = i - 59; k <= i; k++) lo60 = Math.min(lo60, lo(s, k));
        double range = (hi20 / lo20 - 1) * 100, ret5 = (c / s.close[i - 5] - 1) * 100;
        double hist = (x.macd[i] - x.signal[i]) / c * 100, fromLow = (c / lo60 - 1) * 100;
        Double t5 = Indicators.mean(s.turnover, i - 4, i + 1, 3), t60 = Indicators.mean(s.turnover, i - 64, i - 4, 40);
        Double tx = t5 != null && t60 != null && t60 > 0 ? t5 / t60 : null;
        Double avg20 = Indicators.mean(s.turnover, i - 19, i + 1, 10);
        double up = 0, dn = 0;
        for (int k = i - 19; k <= i; k++) {
            double v = nz(s.volume[k]);
            if (s.close[k] > s.close[k - 1]) up += v; else if (s.close[k] < s.close[k - 1]) dn += v;
        }
        Double updown = dn > 0 ? up / dn : null;
        List<Check> checks = List.of(
                new Check("Liquid", "average turnover ≥ Rs 50 L (20 sessions) and ≥ Rs 25 L today", avg20, money(avg20) + " avg · " + money(to) + " today",
                        avg20 != null && avg20 >= MIN_AVG_TURNOVER && to >= MIN_DAY_TURNOVER),
                new Check("Quiet: small daily moves", "ATR(14) ≤ 3% of price", atr, pct(atr), atr <= MAX_ATR),
                new Check("Tight range", "20-session high-low range ≤ 12.5%", range, pct(range), range <= MAX_RANGE),
                new Check("Not popped: 5 sessions", "5-session change ≤ +0.6%", ret5, signed(ret5), ret5 <= MAX_RET5),
                new Check("Not popped: MACD", "MACD histogram ≤ 0.13% of price", Double.isNaN(hist) ? null : hist, signed(hist),
                        !Double.isNaN(hist) && hist <= MAX_MACD_HIST),
                new Check("Not extended", "≤ 17% above its 60-session low", fromLow, signed(fromLow), fromLow <= MAX_FROM_LOW),
                new Check("Money coming in", "last 5 sessions' turnover ≥ 1.3× the 60 before", tx, tx == null ? "—" : String.format("%.2f×", tx),
                        tx != null && tx >= MIN_TURNOVER_X));
        boolean match = checks.stream().allMatch(Check::pass);
        return new Eval(s.symbol, s.name, s.sector, s.dates[i], c, match, checks, atr, range, ret5, Double.isNaN(hist) ? null : hist, fromLow, tx,
                updown, Double.isNaN(x.rsi14[i]) ? null : x.rsi14[i], to, null);
    }

    /** Buy at the open after the signal; target on the high, stop on the close, time exit; no sale before T+3. */
    static Outcome outcome(Series s, int i, double signalClose) {
        int n = s.size(), lt = n - 1;
        if (i < 0) return new Outcome(Status.GONE, null, null, null, null, null, 0, null, null, signalClose, null);
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
        return new Row(RULE, e.date(), e.symbol(), e.sector(), e.close(), TARGET, STOP, HOLD, e.atrPct(), e.range20Pct(), e.ret5Pct(),
                e.macdHistPct(), e.fromLow60Pct(), e.turnoverX(), e.updownVol(), e.rsi14(), e.turnover(), source, at);
    }

    // indicator arrays per stock are reused while the same price data is loaded
    private static final Map<Series, RuleSetService.Ctx> CTX = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static RuleSetService.Ctx ctx(Series s) {
        return CTX.computeIfAbsent(s, RuleSetService.Ctx::new);
    }

    private static double hi(Series s, int i) { return Double.isNaN(s.high[i]) ? s.close[i] : s.high[i]; }

    private static double lo(Series s, int i) { return Double.isNaN(s.low[i]) ? s.close[i] : s.low[i]; }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    private static String pct(double v) { return String.format("%.2f%%", v); }

    private static String signed(double v) { return Double.isNaN(v) ? "—" : String.format("%+.2f%%", v); }

    private static String money(Double v) {
        if (v == null) return "—";
        return v >= 1e7 ? String.format("Rs %.2f Cr", v / 1e7) : String.format("Rs %.1f L", v / 1e5);
    }
}
