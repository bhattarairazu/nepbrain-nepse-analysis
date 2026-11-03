package com.nepse.topturnover.analysis;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.repository.DiscoveryRepository;

/**
 * Pattern discovery over all stored history, run on demand. Every liquid stock-day gets ~28 indicator values (volume and
 * turnover, EMA, RSI, MACD, Bollinger, OBV, CMF, trade size, range, market and sector moves), using only data up to that day,
 * and the result of a bracket trade (buy at the next open, target / stop / time exit, T+2, 0.8% costs).
 * The history is split in time: the first half to FIND rules (beam search over combinations of up to four conditions,
 * thresholds at the find period's quintiles), the next quarter to CHOOSE the best ones, the last quarter as an untouched
 * final CHECK. Rule results count one position per stock at a time. Every run is stored.
 */
@Service
public class DiscoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryService.class);
    static final double MIN_AVG_TURNOVER = 5_000_000, MIN_DAY_TURNOVER = 2_500_000;
    static final int MIN_HISTORY = 65, BEAM = 25, DEPTH = 4, TOP = 10;
    static final double FIND_SHARE = 0.5, CHOOSE_SHARE = 0.75;
    static final double[] QUANTILES = {0.2, 0.4, 0.6, 0.8};

    /** The indicators the search can use. */
    public static final String[][] FEATURES = {
            {"atr", "ATR(14), % of price"}, {"range20", "20-session high-low range, %"}, {"ret5", "5-session change, %"},
            {"ret20", "20-session change, %"}, {"ret60", "60-session change, %"}, {"vsEma20", "close vs 20 EMA, %"},
            {"vsEma50", "close vs 50 EMA, %"}, {"ema20vs50", "20 EMA vs 50 EMA, %"}, {"ema20slope", "20 EMA change over 5 sessions, %"},
            {"rsi14", "RSI(14)"}, {"rsi14chg", "RSI(14) change over 5 sessions"}, {"rsi2", "RSI(2)"},
            {"macdHist", "MACD histogram, % of price"}, {"macdHistChg", "MACD histogram change over 3 sessions, % of price"},
            {"pctB", "Bollinger %b (0 = lower band, 1 = upper)"}, {"bbWidthRank", "Bollinger width vs last 100 sessions, percentile"},
            {"volX", "volume today ÷ 20-session average"}, {"turnoverX", "turnover last 5 sessions ÷ the 60 before"},
            {"upDownVol", "up-day ÷ down-day volume, 20 sessions"}, {"obvSlope", "OBV change over 20 sessions ÷ their volume"},
            {"cmf", "Chaikin Money Flow (20)"}, {"closeInRange", "close within the day's range (0 = low, 1 = high)"},
            {"tradeSizeX", "average trade size, 5 sessions ÷ the 60 before"}, {"fromLow60", "% above the 60-session low"},
            {"fromHigh60", "% below the 60-session high (negative)"}, {"market20", "market (equal-weight) 20-session change, %"},
            {"sector20", "sector average 20-session change, %"}, {"vsSector20", "stock minus sector 20-session change, %"},
            {"vsEma200", "close vs 200 EMA, %"}, {"marketVsEma50", "market (equal-weight) vs its 50-session EMA, %"}};
    static final int F = FEATURES.length;

    public record Settings(double targetPct, double stopPct, int maxSessions) {}

    public record Stats(int n, double winPct, double avgPct, double medianPct) {
        static final Stats NONE = new Stats(0, 0, 0, 0);
    }

    /** One discovered rule: its conditions and results in the three periods, and the stocks matching it at the latest close. */
    /** One discovered rule: its conditions, results in the three periods, per calendar year and by market state, and today's matches. */
    public record Found(int rank, List<String> conditions, String spec, Stats find, Stats choose, Stats check, List<String> today,
                        Map<Integer, Stats> byYear, Stats weakMarket, Stats strongMarket) {
        public long yearsPositive() { return byYear.values().stream().filter(x -> x.n() >= 5 && x.avgPct() > 0).count(); }
        public long yearsJudged() { return byYear.values().stream().filter(x -> x.n() >= 5).count(); }
    }

    public record Result(Long id, LocalDateTime at, LocalDate from, LocalDate to, int sessions, int stocks, int stockDays, LocalDate findTo,
                         LocalDate chooseTo, Settings settings, Stats baseFind, Stats baseChoose, Stats baseCheck, long millis, List<Found> rules,
                         Map<Integer, Stats> baseByYear) {}

    public record Status(boolean running, String stage, int pct, String message, LocalDateTime started) {
        static final Status IDLE = new Status(false, null, 0, null, null);
    }

    private final BacktestService backtest;
    private final DiscoveryRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nepse-discovery");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = Status.IDLE;

    public DiscoveryService(BacktestService backtest, DiscoveryRepository repo) {
        this.backtest = backtest;
        this.repo = repo;
    }

    public Status status() {
        return status;
    }

    public synchronized boolean start(Settings s) {
        if (status.running()) return false;
        status = new Status(true, "Starting", 0, null, LocalDateTime.now().withNano(0));
        executor.submit(() -> {
            try {
                Result r = run(s);
                status = new Status(false, "Done", 100, r == null ? "Not enough stored history." : "Finished in "
                        + Duration.ofMillis(r.millis()).toSeconds() + " s: " + r.rules().size() + " rules.", status.started());
            } catch (RuntimeException e) {
                LOG.error("Discovery failed", e);
                status = new Status(false, "Failed", 0, "Failed: " + e.getMessage(), status.started());
            }
        });
        return true;
    }

    private void stage(String stage, int pct) {
        status = new Status(true, stage, pct, null, status.started());
    }

    // ------------------------------------------------------------------ the run

    Result run(Settings set) {
        long t0 = System.currentTimeMillis();
        stage("Loading prices", 2);
        backtest.evict(); // use everything stored right now
        Market m = backtest.data();
        if (m == null || m.dates.length < MIN_HISTORY + 60) return null;
        int T = m.dates.length;

        stage("Computing indicators for every stock-day", 10);
        Rows rows = build(m, set);
        if (rows.n < 500) return null;

        // periods by session (signal date)
        int first = rows.global[0], last = rows.global[0];
        for (int r = 0; r < rows.n; r++) { first = Math.min(first, rows.global[r]); last = Math.max(last, rows.global[r]); }
        int findTo = first + (int) ((last - first) * FIND_SHARE), chooseTo = first + (int) ((last - first) * CHOOSE_SHARE);
        BitSet known = new BitSet(rows.n), find = new BitSet(rows.n), choose = new BitSet(rows.n), check = new BitSet(rows.n);
        for (int r = 0; r < rows.n; r++) {
            if (Double.isNaN(rows.net[r])) continue;
            known.set(r);
            (rows.global[r] < findTo ? find : rows.global[r] < chooseTo ? choose : check).set(r);
        }

        // calendar years and market state (equal-weight market below / above its 50-session EMA), for the result breakdowns
        Map<Integer, BitSet> years = new java.util.TreeMap<>();
        BitSet weak = new BitSet(rows.n), strong = new BitSet(rows.n);
        int mk = Arrays.asList(FEATURES).indexOf(Arrays.stream(FEATURES).filter(f -> f[0].equals("marketVsEma50")).findFirst().get());
        for (int r = known.nextSetBit(0); r >= 0; r = known.nextSetBit(r + 1)) {
            years.computeIfAbsent(m.dates[rows.global[r]].getYear(), y -> new BitSet(rows.n)).set(r);
            float mv = rows.f[mk][r];
            if (!Float.isNaN(mv)) (mv < 0 ? weak : strong).set(r);
        }
        Map<Integer, Stats> baseByYear = new java.util.TreeMap<>();
        years.forEach((y, bits) -> baseByYear.put(y, base(rows, bits)));

        stage("Building candidate conditions", 30);
        List<Cond> conds = conditions(rows, find);
        int minFind = Math.max(100, find.cardinality() / 200);

        // beam search on the find period
        Map<String, Rule> pool = new HashMap<>();
        List<Rule> beam = new ArrayList<>();
        for (Cond c : conds) {
            Rule r = new Rule(List.of(c), c.bits);
            if (r.score(rows, find, minFind)) beam.add(r);
        }
        beam = top(beam);
        beam.forEach(r -> pool.put(r.key(), r));
        for (int d = 2; d <= DEPTH; d++) {
            stage("Searching combinations of " + d + " conditions", 30 + d * 12);
            List<Rule> next = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Rule b : beam) {
                for (Cond c : conds) {
                    if (b.uses(c.feature)) continue;
                    List<Cond> cs = new ArrayList<>(b.conds);
                    cs.add(c);
                    Rule r = new Rule(cs, null);
                    if (!seen.add(r.key())) continue;
                    BitSet bits = (BitSet) b.bits.clone();
                    bits.and(c.bits);
                    r.bits = bits;
                    if (r.score(rows, find, minFind)) next.add(r);
                }
            }
            beam = top(next);
            beam.forEach(r -> pool.putIfAbsent(r.key(), r));
        }

        stage("Choosing the best rules on the next period", 85);
        List<Found> found = new ArrayList<>();
        record Ranked(Rule rule, Stats find, Stats choose) {}
        List<Ranked> ranked = new ArrayList<>();
        for (Rule r : pool.values()) {
            Stats f = trades(rows, r.bits, find), c = trades(rows, r.bits, choose);
            if (f.n() >= 20 && f.avgPct() > 0 && c.n() >= 10) ranked.add(new Ranked(r, f, c));
        }
        // lower confidence-style ranking on the choose period: average, penalised for few trades
        ranked.sort(Comparator.comparingDouble((Ranked x) -> x.choose().avgPct() - 8 / Math.sqrt(x.choose().n())).reversed());
        Set<String> used = new HashSet<>();
        int rank = 0;
        for (Ranked x : ranked) {
            if (rank >= TOP) break;
            String sig = x.rule().signature(); // skip near-duplicates (same features)
            if (!used.add(sig)) continue;
            Stats chk = trades(rows, x.rule().bits, check);
            Map<Integer, Stats> byYear = new java.util.TreeMap<>();
            years.forEach((y, bits) -> byYear.put(y, trades(rows, x.rule().bits, bits)));
            found.add(new Found(++rank, x.rule().texts(), x.rule().spec(), x.find(), x.choose(), chk, today(rows, x.rule(), T - 1), byYear,
                    trades(rows, x.rule().bits, weak), trades(rows, x.rule().bits, strong)));
        }

        Result res = new Result(null, LocalDateTime.now().withNano(0), m.dates[first], m.dates[T - 1], T, rows.stocks, known.cardinality(),
                m.dates[findTo], m.dates[chooseTo], set, base(rows, find), base(rows, choose), base(rows, check),
                System.currentTimeMillis() - t0, found, baseByYear);
        stage("Saving", 97);
        long id = repo.save(res);
        LOG.info("Discovery run {}: {} stock-days, {} rules in {} ms", id, known.cardinality(), found.size(), res.millis());
        return res;
    }

    // ------------------------------------------------------------------ rows and indicators

    /** All liquid stock-days, stock by stock in date order: features, trade result and when the position would be closed. */
    static final class Rows {
        int n, stocks;
        float[][] f = new float[F][];
        double[] net;
        int[] global, exitGlobal, stock;
        String[] symbols;
        int[] symbolOf;

        void grow(int cap) {
            for (int k = 0; k < F; k++) f[k] = f[k] == null ? new float[cap] : Arrays.copyOf(f[k], cap);
            net = net == null ? new double[cap] : Arrays.copyOf(net, cap);
            global = global == null ? new int[cap] : Arrays.copyOf(global, cap);
            exitGlobal = exitGlobal == null ? new int[cap] : Arrays.copyOf(exitGlobal, cap);
            stock = stock == null ? new int[cap] : Arrays.copyOf(stock, cap);
        }
    }

    static Rows build(Market m, Settings set) {
        int T = m.dates.length;
        double[] mkt = PatternService.marketEma(m);
        List<Series> list = m.series.values().stream().filter(s -> Sectors.isEquity(s.sector) && s.size() > MIN_HISTORY).toList();
        // sector average 20-session change per session
        Map<String, double[]> secSum = new HashMap<>();
        Map<String, int[]> secCnt = new HashMap<>();
        for (Series s : list) {
            double[] sum = secSum.computeIfAbsent(String.valueOf(s.sector), k -> new double[T]);
            int[] cnt = secCnt.computeIfAbsent(String.valueOf(s.sector), k -> new int[T]);
            for (int i = 20; i < s.size(); i++) {
                double r = (s.close[i] / s.close[i - 20] - 1) * 100;
                if (Double.isFinite(r)) { sum[s.globalIndex[i]] += r; cnt[s.globalIndex[i]]++; }
            }
        }
        Rows rows = new Rows();
        rows.grow(Math.max(1024, list.size() * 200));
        rows.symbols = new String[list.size()];
        for (int sn = 0; sn < list.size(); sn++) {
            Series s = list.get(sn);
            rows.symbols[sn] = s.symbol;
            RuleSetService.Ctx x = new RuleSetService.Ctx(s);
            double[] size = new double[s.size()]; // average trade size per session
            for (int k = 0; k < s.size(); k++) size[k] = s.trades[k] > 0 ? nz(s.turnover[k]) / s.trades[k] : Double.NaN;
            double[] sum = secSum.get(String.valueOf(s.sector));
            int[] cnt = secCnt.get(String.valueOf(s.sector));
            boolean any = false;
            for (int i = MIN_HISTORY; i < s.size(); i++) {
                double c = s.close[i], to = nz(s.turnover[i]);
                Double avg20 = Indicators.mean(s.turnover, i - 19, i + 1, 10);
                if (avg20 == null || avg20 < MIN_AVG_TURNOVER || to < MIN_DAY_TURNOVER || !(c > 0)) continue;
                if (rows.n == rows.net.length) rows.grow(rows.n * 2);
                int r = rows.n++;
                any = true;
                int g = s.globalIndex[i];
                double[] v = features(m, mkt, s, x, size, i, sum == null ? Double.NaN : cnt[g] > 0 ? sum[g] / cnt[g] : Double.NaN);
                for (int k = 0; k < F; k++) rows.f[k][r] = (float) v[k];
                double[] out = bracket(s, i, set);
                rows.net[r] = out == null ? Double.NaN : out[0];
                rows.exitGlobal[r] = out == null ? Integer.MAX_VALUE : s.globalIndex[(int) out[1]];
                rows.global[r] = g;
                rows.stock[r] = sn;
            }
            if (any) rows.stocks++;
        }
        return rows;
    }

    static double[] features(Market m, double[] mkt, Series s, RuleSetService.Ctx x, double[] size, int i, double sector20) {
        double c = s.close[i];
        double hi20 = Double.NEGATIVE_INFINITY, lo20 = Double.POSITIVE_INFINITY, lo60 = Double.POSITIVE_INFINITY, hi60 = Double.NEGATIVE_INFINITY;
        for (int k = i - 19; k <= i; k++) { hi20 = Math.max(hi20, hi(s, k)); lo20 = Math.min(lo20, lo(s, k)); }
        for (int k = i - 59; k <= i; k++) { lo60 = Math.min(lo60, lo(s, k)); hi60 = Math.max(hi60, hi(s, k)); }
        double up = 0, dn = 0, volSum = 0;
        for (int k = i - 19; k <= i; k++) {
            double v = nz(s.volume[k]);
            volSum += v;
            if (s.close[k] > s.close[k - 1]) up += v; else if (s.close[k] < s.close[k - 1]) dn += v;
        }
        Double t5 = Indicators.mean(s.turnover, i - 4, i + 1, 3), t60 = Indicators.mean(s.turnover, i - 64, i - 4, 40);
        Double z5 = Indicators.mean(size, i - 4, i + 1, 3), z60 = Indicators.mean(size, i - 64, i - 4, 30);
        double hist = x.macd[i] - x.signal[i], hist3 = x.macd[i - 3] - x.signal[i - 3];
        double width = x.width[i];
        int below = 0, seen = 0;
        for (int k = Math.max(0, i - 100); k < i; k++) if (!Double.isNaN(x.width[k])) { seen++; if (x.width[k] < width) below++; }
        double ret20 = (c / s.close[i - 20] - 1) * 100;
        int g = s.globalIndex[i];
        double h = hi(s, i), l = lo(s, i);
        return new double[] {
                x.atr14[i] / c * 100, (hi20 / lo20 - 1) * 100, (c / s.close[i - 5] - 1) * 100, ret20, (c / s.close[i - 60] - 1) * 100,
                (c / x.ema20[i] - 1) * 100, (c / x.ema50[i] - 1) * 100, (x.ema20[i] / x.ema50[i] - 1) * 100, (x.ema20[i] / x.ema20[i - 5] - 1) * 100,
                x.rsi14[i], x.rsi14[i] - x.rsi14[i - 5], x.rsi2[i],
                hist / c * 100, (hist - hist3) / c * 100,
                (c - x.lower[i]) / (x.upper[i] - x.lower[i]), seen >= 40 ? 100.0 * below / seen : Double.NaN,
                Double.isNaN(x.volAvg20[i]) ? Double.NaN : nz(s.volume[i]) / x.volAvg20[i],
                t5 != null && t60 != null && t60 > 0 ? t5 / t60 : Double.NaN,
                dn > 0 ? up / dn : Double.NaN, volSum > 0 ? (x.obv[i] - x.obv[i - 20]) / volSum : Double.NaN, x.cmf20[i],
                h > l ? (c - l) / (h - l) : Double.NaN,
                z5 != null && z60 != null && z60 > 0 ? z5 / z60 : Double.NaN,
                (c / lo60 - 1) * 100, (c / hi60 - 1) * 100,
                g >= 20 ? (m.index[g] / m.index[g - 20] - 1) * 100 : Double.NaN, sector20, ret20 - sector20,
                (c / x.ema200[i] - 1) * 100, Double.isNaN(mkt[g]) ? Double.NaN : (m.index[g] / mkt[g] - 1) * 100};
    }

    /** {net %, exit index}: buy at the next open, target on the high, stop on the close, time exit; null while still open. */
    static double[] bracket(Series s, int i, Settings set) {
        int n = s.size(), e = i + 1;
        if (e >= n) return null;
        double entry = s.open[e] > 0 ? s.open[e] : s.close[i];
        int lastJ = e + set.maxSessions() + 2;
        for (int j = e + BacktestService.EARLIEST_SALE; j < n && j <= lastJ; j++) {
            if (hi(s, j) >= entry * (1 + set.targetPct() / 100)) return new double[] {set.targetPct() - BacktestService.COST_PCT, j};
            if (s.close[j] <= entry * (1 - set.stopPct() / 100)) return new double[] {(s.close[j] / entry - 1) * 100 - BacktestService.COST_PCT, j};
            if (j == lastJ) return new double[] {(s.close[j] / entry - 1) * 100 - BacktestService.COST_PCT, j};
        }
        return null;
    }

    // ------------------------------------------------------------------ conditions and rules

    static final class Cond {
        final int feature;
        final boolean atMost;
        final double threshold;
        final BitSet bits;

        Cond(int feature, boolean atMost, double threshold, BitSet bits) {
            this.feature = feature; this.atMost = atMost; this.threshold = threshold; this.bits = bits;
        }

        String text() {
            if (FEATURES[feature][0].equals("fromHigh60")) // "≥ -8.3% from the high" reads better as "within 8.3% of the high"
                return atMost ? "at least " + fmt(-threshold) + "% below the 60-session high" : "within " + fmt(-threshold) + "% of the 60-session high";
            return FEATURES[feature][1] + (atMost ? " ≤ " : " ≥ ") + fmt(threshold);
        }

        String spec() { return FEATURES[feature][0] + (atMost ? "<=" : ">=") + threshold; }

        boolean test(float v) { return !Float.isNaN(v) && (atMost ? v <= threshold : v >= threshold); }
    }

    /** "feature ≤ q" and "feature ≥ q" at the find period's quintile edges. */
    static List<Cond> conditions(Rows rows, BitSet find) {
        List<Cond> out = new ArrayList<>();
        for (int k = 0; k < F; k++) {
            float[] v = rows.f[k];
            double[] d = find.stream().mapToDouble(r -> v[r]).filter(Double::isFinite).sorted().toArray();
            if (d.length < 200) continue;
            Set<Double> done = new HashSet<>();
            for (double q : QUANTILES) {
                double th = round(d[(int) (q * (d.length - 1))]);
                if (!done.add(th)) continue;
                for (boolean atMost : new boolean[] {true, false}) {
                    BitSet b = new BitSet(rows.n);
                    for (int r = 0; r < rows.n; r++) {
                        float x = v[r];
                        if (!Float.isNaN(x) && (atMost ? x <= th : x >= th)) b.set(r);
                    }
                    out.add(new Cond(k, atMost, th, b));
                }
            }
        }
        return out;
    }

    static final class Rule {
        final List<Cond> conds;
        BitSet bits;
        double score;

        Rule(List<Cond> conds, BitSet bits) {
            this.conds = conds.stream().sorted(Comparator.comparingInt((Cond c) -> c.feature).thenComparing(c -> c.atMost)).toList();
            this.bits = bits;
        }

        boolean uses(int feature) { return conds.stream().anyMatch(c -> c.feature == feature); }

        String key() { return String.join("&", conds.stream().map(Cond::spec).toList()); }

        String signature() { return String.join("&", conds.stream().map(c -> c.feature + (c.atMost ? "-" : "+")).toList()); }

        String spec() { return key(); }

        List<String> texts() { return conds.stream().map(Cond::text).toList(); }

        /** Find-period score: average result per stock-day minus a penalty for few cases. */
        boolean score(Rows rows, BitSet find, int minN) {
            BitSet b = (BitSet) bits.clone();
            b.and(find);
            int n = b.cardinality();
            if (n < minN) return false;
            double sum = 0, sq = 0;
            for (int r = b.nextSetBit(0); r >= 0; r = b.nextSetBit(r + 1)) { double x = rows.net[r]; sum += x; sq += x * x; }
            double mean = sum / n, sd = Math.sqrt(Math.max(0, sq / n - mean * mean));
            score = mean - 2 * sd / Math.sqrt(n);
            return true;
        }
    }

    private static List<Rule> top(List<Rule> l) {
        return l.stream().sorted(Comparator.comparingDouble((Rule r) -> r.score).reversed()).limit(BEAM).toList();
    }

    /** Trades in a period, one position per stock at a time (rows are stock by stock in date order). */
    static Stats trades(Rows rows, BitSet bits, BitSet period) {
        BitSet b = (BitSet) bits.clone();
        b.and(period);
        List<Double> net = new ArrayList<>();
        int stock = -1, busyUntil = -1;
        for (int r = b.nextSetBit(0); r >= 0; r = b.nextSetBit(r + 1)) {
            if (rows.stock[r] != stock) { stock = rows.stock[r]; busyUntil = -1; }
            if (rows.global[r] <= busyUntil) continue;
            net.add(rows.net[r]);
            busyUntil = rows.exitGlobal[r];
        }
        return stats(net);
    }

    /** Every liquid stock-day of a period (the baseline). */
    static Stats base(Rows rows, BitSet period) {
        List<Double> net = new ArrayList<>();
        for (int r = period.nextSetBit(0); r >= 0; r = period.nextSetBit(r + 1)) net.add(rows.net[r]);
        return stats(net);
    }

    static Stats stats(List<Double> net) {
        if (net.isEmpty()) return Stats.NONE;
        double[] d = net.stream().mapToDouble(x -> x).sorted().toArray();
        return new Stats(d.length, 100.0 * Arrays.stream(d).filter(x -> x > 0).count() / d.length, Arrays.stream(d).average().orElse(0), d[d.length / 2]);
    }

    /** Stocks matching the rule at the latest close. */
    static List<String> today(Rows rows, Rule rule, int lastGlobal) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < rows.n; r++) {
            if (rows.global[r] != lastGlobal) continue;
            final int row = r;
            if (rule.conds.stream().allMatch(c -> c.test(rows.f[c.feature][row]))) out.add(rows.symbols[rows.stock[r]]);
        }
        return out;
    }

    private static double round(double v) {
        double a = Math.abs(v);
        double step = a >= 100 ? 1 : a >= 10 ? 0.1 : a >= 1 ? 0.01 : 0.001;
        return Math.round(v / step) * step;
    }

    static String fmt(double v) {
        String s = String.format("%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    private static double hi(Series s, int i) { return Double.isNaN(s.high[i]) ? s.close[i] : s.high[i]; }

    private static double lo(Series s, int i) { return Double.isNaN(s.low[i]) ? s.close[i] : s.low[i]; }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }
}
