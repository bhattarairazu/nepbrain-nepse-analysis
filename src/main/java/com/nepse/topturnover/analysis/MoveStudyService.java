package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;
import com.nepse.topturnover.journal.TradeCosts;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Event study: which liquid stock-days were followed by a tradeable +7% move, and what did those days have in common?
 *
 * A "move" means: signal at the close of day t, buy at the next open, and the high reaches +7% within 10 sessions
 * (selling allowed only from T+3) before a close 5% below the entry. Feature lifts are measured on the design period
 * (first 60%) and shown next to the out-of-sample hit rate of the same bucket, so a pattern that only existed in
 * the design period is visible. A lift score (naive Bayes over the strongest design features) drives a deliberately
 * rare account: one position at a time, at most one new trade per month or per quarter.
 */
@Service
public class MoveStudyService {
    public static final double TARGET = 0.07, STOP = 0.05;
    public static final int WINDOW = 10;
    static final int MIN_HISTORY = 60, SCORE_FEATURES = 10, MIN_BUCKET = 30;
    static final double SCORE_PERCENTILE = 0.97, HELD_LIFT = 1.1;

    public enum Feature {
        CHG1("Day change", "% change on the signal day", false),
        RET5("5-day return", "close vs 5 sessions earlier", false),
        RET20("20-day return", "close vs 20 sessions earlier", false),
        RET60("60-day return", "close vs 60 sessions earlier", false),
        RVOL("Relative volume", "volume ÷ 20-day average volume", false),
        CLOSE_IN_RANGE("Close in day's range", "0 = closed at the low, 1 = at the high", false),
        UPPER_WICK("Upper wick", "share of the day's range above the body", false),
        DIST_HI20("Distance from 20-day high", "close ÷ highest high of the previous 20 sessions − 1", false),
        RANGE20("20-day range", "20-day high ÷ 20-day low − 1 (base tightness)", false),
        DIST_HI60("Distance from 60-day high", "close ÷ highest high of the previous 60 sessions − 1", false),
        ABOVE_EMA20("Distance from EMA 20", "close ÷ EMA 20 − 1", false),
        ABOVE_EMA50("Distance from EMA 50", "close ÷ EMA 50 − 1", false),
        EMA50_SLOPE("EMA 50 slope", "EMA 50 vs 5 sessions earlier", false),
        RSI14("RSI 14", "", false),
        RSI2("RSI 2", "", false),
        MACD_HIST("MACD histogram", "(MACD − signal) ÷ close", false),
        BB_PCTB("Bollinger %b", "0 = on the lower band, 1 = on the upper band", false),
        BB_WIDTH("Bollinger width", "band width ÷ 20-day average", false),
        ATR_PCT("ATR %", "14-day average true range ÷ close (volatility)", false),
        UP_STREAK("Up-day streak", "consecutive higher closes", false),
        LOG_PRICE("Price level", "log10 of the close (2 = Rs 100, 3 = Rs 1,000)", false),
        TOP20_TURNOVER("In top-20 turnover", "1 = on the day's top-20 turnover list", true),
        MARKET_UP("Market above 50-day EMA", "1 = equal-weight market above its 50-day EMA", true),
        MARKET_RET5("Market 5-day return", "equal-weight market vs 5 sessions earlier", false),
        BREADTH_OS("Market oversold breadth", "share of liquid stocks closing below the lower Bollinger band", false);

        private final String title, help;
        private final boolean binary;

        Feature(String title, String help, boolean binary) {
            this.title = title;
            this.help = help;
            this.binary = binary;
        }

        public String getTitle() { return title; }
        public String getHelp() { return help; }
    }

    public record Stat(int n, int days, double hitPct, double avgPct, double medianPct) {}

    public record Bucket(String range, int designN, double designHitPct, double lift, int oosN, double oosHitPct, double oosLift) {}

    public record FeatureLift(Feature feature, double spread, boolean inScore, boolean held, List<Bucket> buckets) {}

    public record Mover(String symbol, String sector, int moves, int days, double hitPct) {}

    public record RuleTest(String name, String rule, Stat design, Stat oos) {}

    public record Pick(String symbol, String sector, LocalDate signal, LocalDate entryDate, double entry, LocalDate exitDate,
                       double exit, int held, String reason, double score, boolean design, double netPct) {}

    public record Account(String key, String title, String rule, double finalValue, double returnPct, double designReturnPct,
                          double oosReturnPct, int designTrades, int oosTrades, double winRate, double charges, double tax,
                          List<Pick> trades) {}

    public record Candidate(String symbol, String sector, double close, double score, boolean qualifies, List<String> notes) {}

    public record PastSignal(LocalDate date, double score, boolean moved, double netPct) {}

    public record StockMoves(Double scoreToday, boolean qualifies, int rankToday, int stocksToday, List<String> notes, int moves,
                             int liquidDays, double moveRatePct, double baseRatePct, double threshold, List<PastSignal> topScoreDays) {}

    public record Result(LocalDate from, LocalDate split, LocalDate to, int sessions, int stockDays, int designDays, int oosDays,
                         Stat designBase, Stat oosBase, List<Mover> movers, List<FeatureLift> lifts, List<RuleTest> rules,
                         double scoreThreshold, List<Account> accounts, LocalDate lastDay, List<Candidate> candidates,
                         List<WatchSignal> signals) {}

    /** One liquid stock-day: its features and, when the future is known, what a trade from the next open did. */
    private static final class Row {
        final Series s;
        final int t, i;
        final double[] f = new double[Feature.values().length];
        boolean labelled, success;
        double ret, entry, exit, score;
        int entryT, exitT;
        String reason;

        Row(Series s, int t, int i) {
            this.s = s; this.t = t; this.i = i;
        }

        double v(Feature x) { return f[x.ordinal()]; }
    }

    private final BacktestService backtest;
    private final TradeCosts costs;
    private final BacktestRepository store;
    private Result cached;
    private List<Row> allRows = List.of();
    private double cachedBase, cachedThr;
    private LocalDate cachedFor;

    public MoveStudyService(BacktestService backtest, TradeCosts costs, BacktestRepository store) {
        this.backtest = backtest;
        this.costs = costs;
        this.store = store;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < MIN_HISTORY + 60) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor)) return Optional.of(cached);

        int T = m.dates.length, split = (int) Math.round(T * AdvancedStrategyService.DESIGN_SHARE);
        List<Row> rows = rows(m);
        List<Row> labelled = rows.stream().filter(r -> r.labelled).toList();
        List<Row> des = labelled.stream().filter(r -> r.t < split).toList(), oos = labelled.stream().filter(r -> r.t >= split).toList();
        if (des.size() < 500 || oos.isEmpty()) return Optional.empty();
        double base = des.stream().filter(r -> r.success).count() / (double) des.size();
        double oosBase = oos.stream().filter(r -> r.success).count() / (double) oos.size();

        // ---- feature lifts (edges and rates from the design period only)
        List<FeatureLift> lifts = new ArrayList<>();
        Map<Feature, double[]> edges = new HashMap<>();
        Map<Feature, double[]> rates = new HashMap<>();
        for (Feature x : Feature.values()) {
            double[] e = edges(des, x);
            int nb = e.length + 1;
            int[] dn = new int[nb], dh = new int[nb], on = new int[nb], oh = new int[nb];
            for (Row r : des) { int b = bucket(r.v(x), e); if (b >= 0) { dn[b]++; if (r.success) dh[b]++; } }
            for (Row r : oos) { int b = bucket(r.v(x), e); if (b >= 0) { on[b]++; if (r.success) oh[b]++; } }
            double[] rate = new double[nb];
            List<Bucket> buckets = new ArrayList<>();
            double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;
            int bestB = -1;
            for (int b = 0; b < nb; b++) {
                rate[b] = dn[b] >= MIN_BUCKET ? dh[b] / (double) dn[b] : base;
                double lift = rate[b] / base;
                double oRate = on[b] > 0 ? oh[b] / (double) on[b] : Double.NaN;
                buckets.add(new Bucket(label(x, e, b), dn[b], rate[b] * 100, lift, on[b], oRate * 100, oRate / oosBase));
                if (dn[b] >= MIN_BUCKET) {
                    if (lift > best) { best = lift; bestB = b; }
                    worst = Math.min(worst, lift);
                }
            }
            boolean held = bestB >= 0 && on[bestB] >= MIN_BUCKET && oh[bestB] / (double) on[bestB] >= oosBase * HELD_LIFT;
            edges.put(x, e);
            rates.put(x, rate);
            lifts.add(new FeatureLift(x, bestB < 0 ? 0 : best - worst, false, held, buckets));
        }
        lifts.sort(Comparator.comparingDouble(FeatureLift::spread).reversed());
        Set<Feature> scored = new HashSet<>();
        for (int k = 0; k < lifts.size(); k++) {
            FeatureLift l = lifts.get(k);
            if (k < SCORE_FEATURES) {
                scored.add(l.feature());
                lifts.set(k, new FeatureLift(l.feature(), l.spread(), true, l.held(), l.buckets()));
            }
        }

        // ---- lift score for every row (labelled or not)
        for (Row r : rows) {
            double sc = 0;
            for (Feature x : scored) {
                int b = bucket(r.v(x), edges.get(x));
                if (b >= 0) sc += Math.log(rates.get(x)[b] / base);
            }
            r.score = sc;
        }
        double[] ds = des.stream().mapToDouble(r -> r.score).sorted().toArray();
        double thr = ds[(int) Math.min(ds.length - 1, Math.floor(ds.length * SCORE_PERCENTILE))];

        // ---- frequent movers
        Map<String, int[]> mv = new HashMap<>();
        for (Row r : labelled) {
            int[] a = mv.computeIfAbsent(r.s.symbol, k -> new int[2]);
            a[1]++;
            if (r.success) a[0]++;
        }
        List<Mover> movers = mv.entrySet().stream().filter(e -> e.getValue()[0] > 0)
                .map(e -> new Mover(e.getKey(), m.series.get(e.getKey()).sector, e.getValue()[0], e.getValue()[1], 100.0 * e.getValue()[0] / e.getValue()[1]))
                .sorted(Comparator.comparingInt(Mover::moves).reversed()).limit(20).toList();

        // ---- rule tests
        Predicate<Row> r1 = r -> r.v(Feature.MARKET_UP) == 0 && r.v(Feature.DIST_HI20) <= -0.108 && r.v(Feature.BB_PCTB) <= 0.265;
        Predicate<Row> washout = r -> r.v(Feature.BREADTH_OS) >= 0.25 && r.v(Feature.MARKET_RET5) <= -0.04;
        Predicate<Row> topScore = r -> r.score >= thr;
        List<RuleTest> rules = new ArrayList<>();
        rule(rules, "All liquid stock-days", "the base rate: every stock-day with Rs 1 Cr+ turnover", r -> true, des, oos);
        rule(rules, "Market down", "equal-weight market below its 50-day EMA", r -> r.v(Feature.MARKET_UP) == 0, des, oos);
        rule(rules, "R1 · oversold in a down market", "market below its 50-day EMA, stock ≥ 10.8% below its 20-day high, Bollinger %b ≤ 0.27", r1, des, oos);
        rule(rules, "R2 · R1 + volatile", "R1 and ATR ≥ 4.6% of the price", r1.and(r -> r.v(Feature.ATR_PCT) >= 0.046), des, oos);
        rule(rules, "Washout day", "≥ 25% of liquid stocks closed below the lower band and the market fell ≥ 4% in 5 sessions", washout, des, oos);
        rule(rules, "Top lift score", "lift score in the top 3% of the design period", topScore, des, oos);
        rule(rules, "Momentum day", "relative volume ≥ 2, close in the top 30% of the range, up ≥ 3%",
                r -> r.v(Feature.RVOL) >= 2 && r.v(Feature.CLOSE_IN_RANGE) >= 0.7 && r.v(Feature.CHG1) >= 3, des, oos);
        rule(rules, "Breakout in an up market", "close above the 20-day high, relative volume ≥ 1.5, market above its 50-day EMA",
                r -> r.v(Feature.DIST_HI20) > 0 && r.v(Feature.RVOL) >= 1.5 && r.v(Feature.MARKET_UP) == 1, des, oos);

        // ---- rare accounts: one position at a time, at most one entry per month / quarter
        List<Account> accounts = new ArrayList<>();
        accounts.add(account(m, "MOVE_SCORE_M", "Top lift score · max 1 trade a month",
                "Best-scoring stock of the day when its lift score is in the top 3% of the design period. One position at a time, at most one new trade per calendar month.",
                labelled, topScore, false, 1, split));
        accounts.add(account(m, "MOVE_SCORE_Q", "Top lift score · max 1 trade a quarter",
                "Same as above, at most one new trade per calendar quarter.", labelled, topScore, true, 1, split));
        accounts.add(account(m, "MOVE_WASHOUT", "Washout day · max 1 trade a quarter",
                "Only on washout days (≥ 25% of liquid stocks below the lower band, market −4% in 5 sessions): buy the best-scoring stock. At most one trade per quarter.",
                labelled, washout, true, 1, split));
        accounts.add(account(m, "MOVE_WASHOUT_5", "Washout day · 5 stocks · max 1 entry a quarter",
                "Same washout days, but the account is split equally across the 5 best-scoring stocks of that day, so one unlucky pick cannot decide the result.",
                labelled, washout, true, 5, split));

        // ---- the latest session's best setups
        int lt = T - 1;
        List<Candidate> cands = rows.stream().filter(r -> r.t == lt).sorted(Comparator.comparingDouble((Row r) -> r.score).reversed()).limit(12)
                .map(r -> new Candidate(r.s.symbol, r.s.sector, r.s.close[r.i], r.score, r.score >= thr, notes(r))).toList();

        // ---- recent signals for the watchlist: best 3 lift scores a day, best 5 stocks of a washout day
        List<WatchSignal> signals = new ArrayList<>();
        Map<Integer, List<Row>> recentDays = new HashMap<>();
        for (Row r : rows) if (r.t >= T - WatchSignal.RECENT_SESSIONS) recentDays.computeIfAbsent(r.t, k -> new ArrayList<>()).add(r);
        for (List<Row> day : recentDays.values()) {
            day.sort(Comparator.comparingDouble((Row r) -> r.score).reversed());
            day.stream().filter(topScore).limit(3).forEach(r -> signals.add(signal("MOVE_SCORE", r)));
            day.stream().filter(washout).limit(5).forEach(r -> signals.add(signal("WASHOUT", r)));
        }

        cached = new Result(m.dates[0], m.dates[split], last, T, labelled.size(), des.size(), oos.size(), stat(des), stat(oos), movers, lifts, rules,
                thr, accounts, last, cands, signals);
        allRows = rows;
        cachedBase = (base * des.size() + oosBase * oos.size()) / (des.size() + oos.size());
        cachedThr = thr;
        cachedFor = last;
        save(cached);
        return Optional.of(cached);
    }

    /** The stock in the move study: today's lift score and rank, how often it moved, and its past top-score days. */
    public synchronized Optional<StockMoves> stock(String symbol) {
        if (run().isEmpty()) return Optional.empty();
        int lt = allRows.stream().mapToInt(r -> r.t).max().orElse(-1);
        List<Row> today = allRows.stream().filter(r -> r.t == lt).sorted(Comparator.comparingDouble((Row r) -> r.score).reversed()).toList();
        Row mine = null;
        int rank = 0;
        for (int k = 0; k < today.size(); k++) if (today.get(k).s.symbol.equals(symbol)) { mine = today.get(k); rank = k + 1; }
        List<Row> rows = allRows.stream().filter(r -> r.s.symbol.equals(symbol)).toList();
        if (rows.isEmpty()) return Optional.empty();
        List<Row> lab = rows.stream().filter(r -> r.labelled).toList();
        int moves = (int) lab.stream().filter(r -> r.success).count();
        List<PastSignal> top = lab.stream().filter(r -> r.score >= cachedThr).sorted(Comparator.comparingInt((Row r) -> r.t).reversed()).limit(15)
                .map(r -> new PastSignal(r.s.dates[r.i], r.score, r.success, r.ret * 100)).toList();
        return Optional.of(new StockMoves(mine == null ? null : mine.score, mine != null && mine.score >= cachedThr, rank, today.size(),
                mine == null ? List.of() : notes(mine), moves, lab.size(), lab.isEmpty() ? 0 : 100.0 * moves / lab.size(), cachedBase * 100, cachedThr, top));
    }

    // ------------------------------------------------------------------ features and labels

    private List<Row> rows(Market m) {
        int T = m.dates.length;
        double[] mEma = Ind.ema(m.index, 50);
        List<Row> rows = new ArrayList<>();
        for (Series s : m.series.values()) {
            int n = s.size();
            if (!Sectors.isEquity(s.sector) || n < MIN_HISTORY + 20) continue;
            double[] o = s.open, h = s.high, l = s.low, c = s.close, v = s.volume;
            Ind x = new Ind(c, h, l);
            double[] tr = new double[n], atr = new double[n];
            Arrays.fill(atr, Double.NaN);
            for (int i = 1; i < n; i++) tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
            for (int i = 14; i < n; i++) {
                double sum = 0;
                for (int k = i - 13; k <= i; k++) sum += tr[k];
                atr[i] = sum / 14 / c[i];
            }
            int streak = 0;
            for (int i = 1; i < n; i++) {
                streak = c[i] > c[i - 1] ? streak + 1 : 0;
                if (i < MIN_HISTORY || !(s.turnover[i] >= BacktestService.MIN_TURNOVER) || !(c[i] > 0)) continue;
                int t = s.globalIndex[i];
                Row r = new Row(s, t, i);
                double avgV = avg(v, i - 20, i, 10), hi20 = max(h, i - 20, i), lo20 = min(l, i - 20, i), hi60 = max(h, i - 60, i);
                double rng = h[i] - l[i];
                double sd = (x.sma20[i] - x.lower[i]) / 2;
                set(r, Feature.CHG1, s.change[i]);
                set(r, Feature.RET5, c[i] / c[i - 5] - 1);
                set(r, Feature.RET20, c[i] / c[i - 20] - 1);
                set(r, Feature.RET60, c[i] / c[i - 60] - 1);
                set(r, Feature.RVOL, avgV > 0 ? v[i] / avgV : Double.NaN);
                set(r, Feature.CLOSE_IN_RANGE, rng > 0 ? (c[i] - l[i]) / rng : Double.NaN);
                set(r, Feature.UPPER_WICK, rng > 0 ? (h[i] - Math.max(o[i], c[i])) / rng : Double.NaN);
                set(r, Feature.DIST_HI20, c[i] / hi20 - 1);
                set(r, Feature.RANGE20, lo20 > 0 ? hi20 / lo20 - 1 : Double.NaN);
                set(r, Feature.DIST_HI60, c[i] / hi60 - 1);
                set(r, Feature.ABOVE_EMA20, c[i] / x.ema20[i] - 1);
                set(r, Feature.ABOVE_EMA50, c[i] / x.ema50[i] - 1);
                set(r, Feature.EMA50_SLOPE, x.ema50[i] / x.ema50[i - 5] - 1);
                set(r, Feature.RSI14, x.rsi14[i]);
                set(r, Feature.RSI2, x.rsi2[i]);
                set(r, Feature.MACD_HIST, (x.macd[i] - x.signal[i]) / c[i]);
                set(r, Feature.BB_PCTB, sd > 0 ? (c[i] - x.lower[i]) / (4 * sd) : Double.NaN);
                set(r, Feature.BB_WIDTH, x.sma20[i] > 0 ? 4 * sd / x.sma20[i] : Double.NaN);
                set(r, Feature.ATR_PCT, atr[i]);
                set(r, Feature.UP_STREAK, streak);
                set(r, Feature.LOG_PRICE, Math.log10(c[i]));
                set(r, Feature.TOP20_TURNOVER, m.turnoverRank.getOrDefault(t, Map.of()).containsKey(s.symbol) ? 1 : 0);
                set(r, Feature.MARKET_UP, Double.isNaN(mEma[t]) ? Double.NaN : m.index[t] > mEma[t] ? 1 : 0);
                set(r, Feature.MARKET_RET5, t >= 5 ? m.index[t] / m.index[t - 5] - 1 : Double.NaN);
                label(r, s, T);
                rows.add(r);
            }
        }
        // breadth needs every stock of the day first
        int[] cnt = new int[T], os = new int[T];
        for (Row r : rows) {
            double b = r.v(Feature.BB_PCTB);
            if (Double.isNaN(b)) continue;
            cnt[r.t]++;
            if (b < 0) os[r.t]++;
        }
        for (Row r : rows) set(r, Feature.BREADTH_OS, cnt[r.t] > 0 ? os[r.t] / (double) cnt[r.t] : Double.NaN);
        return rows;
    }

    /** Buy at the next open; +7% limit from T+3; a close 5% below entry (from T+2's close) sells at the next open; else the 10th close. */
    private static void label(Row r, Series s, int T) {
        int e = r.i + 1, n = s.size();
        if (e + WINDOW >= n || s.globalIndex[e] != r.t + 1 || s.globalIndex[e + WINDOW] - r.t > WINDOW + 3) return; // future unknown or stock suspended
        double entry = s.open[e] > 0 ? s.open[e] : s.close[r.i];
        double target = entry * (1 + TARGET), stop = entry * (1 - STOP);
        r.entry = entry;
        r.entryT = s.globalIndex[e];
        for (int k = e; k < e + WINDOW; k++) {
            if (k - e >= BacktestService.EARLIEST_SALE && s.high[k] >= target) {
                finish(r, s, k, Math.max(target, s.open[k]), true, "target");
                return;
            }
            if (k + 1 - e >= BacktestService.EARLIEST_SALE && s.close[k] <= stop) {
                finish(r, s, k + 1, s.open[k + 1] > 0 ? s.open[k + 1] : s.close[k], false, "stop");
                return;
            }
        }
        finish(r, s, e + WINDOW - 1, s.close[e + WINDOW - 1], false, "time");
    }

    private static void finish(Row r, Series s, int k, double px, boolean success, String reason) {
        r.labelled = true;
        r.success = success;
        r.exit = px;
        r.exitT = s.globalIndex[k];
        r.reason = reason;
        r.ret = px / r.entry - 1 - BacktestService.COST_PCT / 100;
    }

    private static void set(Row r, Feature x, double v) {
        r.f[x.ordinal()] = Double.isInfinite(v) ? Double.NaN : v;
    }

    private static double avg(double[] a, int from, int to, int min) {
        Double d = Indicators.mean(a, from, to, min);
        return d == null ? Double.NaN : d;
    }

    private static double max(double[] a, int from, int to) {
        double v = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) v = Math.max(v, a[k]);
        return v == Double.NEGATIVE_INFINITY ? Double.NaN : v;
    }

    private static double min(double[] a, int from, int to) {
        double v = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) v = Math.min(v, a[k]);
        return v == Double.POSITIVE_INFINITY ? Double.NaN : v;
    }

    // ------------------------------------------------------------------ lift buckets

    /** Quintile edges of the design values (binary features: a single edge at 0.5). */
    private static double[] edges(List<Row> des, Feature x) {
        if (x.binary) return new double[] {0.5};
        double[] v = des.stream().mapToDouble(r -> r.v(x)).filter(d -> !Double.isNaN(d)).sorted().toArray();
        if (v.length < 50) return new double[0];
        return Arrays.stream(new double[] {0.2, 0.4, 0.6, 0.8}).map(q -> v[(int) Math.floor(q * (v.length - 1))]).distinct().toArray();
    }

    private static int bucket(double v, double[] e) {
        if (Double.isNaN(v)) return -1;
        int b = 0;
        while (b < e.length && v > e[b]) b++;
        return b;
    }

    private static String label(Feature x, double[] e, int b) {
        if (x.binary) return b == 0 ? "No" : "Yes";
        String lo = b == 0 ? "below" : fmt(x, e[b - 1]), hi = b == e.length ? "above" : fmt(x, e[b]);
        if (b == 0) return "≤ " + hi;
        if (b == e.length) return "> " + lo;
        return lo + " … " + hi;
    }

    private static String fmt(Feature x, double v) {
        return switch (x) {
            case RSI14, RSI2, UP_STREAK, CHG1 -> String.format(Locale.ROOT, "%.1f", v);
            case RVOL, CLOSE_IN_RANGE, UPPER_WICK, BB_PCTB, LOG_PRICE -> String.format(Locale.ROOT, "%.2f", v);
            default -> String.format(Locale.ROOT, "%.1f%%", v * 100);
        };
    }

    private static WatchSignal signal(String source, Row r) {
        return new WatchSignal(source, r.s.symbol, r.s.sector, r.s.dates[r.i], r.s.close[r.i], r.score,
                String.format(Locale.ROOT, "lift score %.2f: %s", r.score, String.join(", ", notes(r))));
    }

    private static List<String> notes(Row r) {
        List<String> n = new ArrayList<>();
        n.add(String.format(Locale.ROOT, "%.0f%% below 20d high", -r.v(Feature.DIST_HI20) * 100));
        n.add(String.format(Locale.ROOT, "%%b %.2f", r.v(Feature.BB_PCTB)));
        n.add(String.format(Locale.ROOT, "RSI %.0f", r.v(Feature.RSI14)));
        n.add(String.format(Locale.ROOT, "ATR %.1f%%", r.v(Feature.ATR_PCT) * 100));
        return n;
    }

    // ------------------------------------------------------------------ rule statistics and the rare accounts

    private static void rule(List<RuleTest> out, String name, String rule, Predicate<Row> p, List<Row> des, List<Row> oos) {
        out.add(new RuleTest(name, rule, stat(des.stream().filter(p).toList()), stat(oos.stream().filter(p).toList())));
    }

    private static Stat stat(List<Row> x) {
        if (x.isEmpty()) return new Stat(0, 0, 0, 0, 0);
        double[] r = x.stream().mapToDouble(a -> a.ret).sorted().toArray();
        long hits = x.stream().filter(a -> a.success).count();
        int days = (int) x.stream().mapToInt(a -> a.t).distinct().count();
        return new Stat(x.size(), days, 100.0 * hits / x.size(), Arrays.stream(r).average().orElse(0) * 100, r[r.length / 2] * 100);
    }

    private Account account(Market m, String key, String title, String rule, List<Row> labelled, Predicate<Row> p, boolean quarterly,
                            int stocks, int split) {
        Map<Integer, List<Row>> byDay = new HashMap<>();
        for (Row r : labelled) if (p.test(r)) byDay.computeIfAbsent(r.t, k -> new ArrayList<>()).add(r);
        double equity = StrategyLabService.CAPITAL, designEnd = equity, charges = 0, tax = 0;
        int busyUntil = -1;
        Set<String> used = new HashSet<>();
        List<Pick> picks = new ArrayList<>();
        for (int t = 0; t < m.dates.length; t++) {
            List<Row> day = byDay.get(t);
            if (day == null || t <= busyUntil) continue;
            LocalDate d = m.dates[t];
            String period = quarterly ? d.getYear() + "Q" + ((d.getMonthValue() - 1) / 3) : d.getYear() + "-" + d.getMonthValue();
            if (!used.add(period)) continue;
            List<Row> chosen = day.stream().sorted(Comparator.comparingDouble((Row r) -> r.score).reversed()).limit(stocks).toList();
            double slice = equity / chosen.size(), after = 0;
            for (Row r : chosen) {
                double amount = slice / 1.005, bc = costs.buyCharges(amount), qty = amount / r.entry;
                double gross = qty * r.exit, sc = costs.sellCharges(gross), gain = gross - sc - amount - bc;
                double tx = gain > 0 ? gain * costs.cgtShortPct() / 100 : 0;
                double back = slice - amount - bc + gross - sc - tx;
                after += back;
                charges += bc + sc;
                tax += tx;
                picks.add(new Pick(r.s.symbol, r.s.sector, d, m.dates[r.entryT], r.entry, m.dates[r.exitT], r.exit, r.exitT - r.entryT,
                        r.reason, r.score, t < split, (back / slice - 1) * 100));
                busyUntil = Math.max(busyUntil, r.exitT);
            }
            equity = after;
            if (t < split) designEnd = equity;
        }
        double cap = StrategyLabService.CAPITAL;
        long wins = picks.stream().filter(x -> x.netPct() > 0).count();
        int dTrades = (int) picks.stream().filter(Pick::design).count();
        return new Account(key, title, rule, equity, (equity / cap - 1) * 100, (designEnd / cap - 1) * 100, (equity / designEnd - 1) * 100,
                dTrades, picks.size() - dTrades, picks.isEmpty() ? 0 : 100.0 * wins / picks.size(), charges, tax,
                picks.stream().sorted(Comparator.comparing(Pick::signal).reversed()).toList());
    }

    private void save(Result r) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        List<BacktestRepository.TradeRow> trades = new ArrayList<>();
        for (Account a : r.accounts()) {
            sums.add(new BacktestRepository.SummaryRow(a.key(), a.title(), a.trades().size(), a.winRate(),
                    a.trades().stream().mapToDouble(Pick::netPct).average().orElse(0), null, null, a.designReturnPct(), a.oosReturnPct(), null,
                    String.format(Locale.ROOT, "Rs %.0f final", a.finalValue())));
            for (Pick p : a.trades()) {
                trades.add(new BacktestRepository.TradeRow(a.key(), p.symbol(), p.signal(), p.entryDate(), p.entry(), p.exitDate(), p.exit(),
                        p.held(), p.reason(), p.netPct(), null));
            }
        }
        for (RuleTest t : r.rules()) {
            String k = "MOVE_RULE_" + t.name().replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
            sums.add(new BacktestRepository.SummaryRow(k.substring(0, Math.min(40, k.length())), t.name(), t.design().n() + t.oos().n(), null,
                    null, null, null, t.design().avgPct(), t.oos().avgPct(), null,
                    String.format(Locale.ROOT, "hit %.1f%% design / %.1f%% out-of-sample", t.design().hitPct(), t.oos().hitPct())));
        }
        store.saveIfNew(new BacktestRepository.RunKey("MOVES", r.from(), r.to(), r.sessions(), WINDOW, false),
                BacktestService.COST_PCT, null, sums, trades);
    }
}
