package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Exit;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Outcome;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Sideways stocks near a breakout. A base is the previous 30 sessions with a high/low range of at most 15% and a net
 * drift of at most 8%, with Rs 50 lakh+ average turnover. Each liquid stock is scored on how tight the base is, the
 * Bollinger squeeze, up-day vs down-day volume (accumulation), where the close sits in the base, volume dry-up and
 * rising turnover, and put in a phase: breaking out, coiling at the top, or base forming.
 * The same rules, fixed in advance, are run over the stored history and compared with ordinary 20-day breakouts.
 */
@Service
public class BaseBreakoutService {
    static final int BASE = 30, SQUEEZE_LOOKBACK = 100, MIN_GAP = 10;
    static final double MAX_DEPTH = 0.15, MAX_DRIFT = 0.08, MIN_BASE_TURNOVER = 5_000_000, MIN_RVOL = 1.5;
    static final double GOOD_SCORE = 60;

    public enum Phase {
        BREAKOUT("Breaking out", "closed above the base high on 1.5×+ volume with Rs 1 Cr+ traded"),
        READY("Coiling at the top", "within 3% of the base high, Bollinger width in the narrowest 30% of the last 100 sessions, up-day volume ≥ down-day volume"),
        BASE("Base forming", "sideways, but not yet pressing the top of the range");

        private final String title, help;

        Phase(String title, String help) {
            this.title = title;
            this.help = help;
        }

        public String getTitle() { return title; }
        public String getHelp() { return help; }
    }

    /** Everything known about one stock's base at the close of day i. */
    public record Base(String symbol, String sector, LocalDate date, Phase phase, double close, double baseHigh, double baseLow,
                       double depthPct, double driftPct, double positionInBase, double toHighPct, double squeezePct,
                       double upDownVolume, double dryUp, double rvol, double turnoverTrend, double avgTurnover, double turnover,
                       int score, List<String> reasons) {}

    public record Stat(int n, double hitPct, double avgPct, double medianPct) {}

    public record Row(String name, String rule, Exit exit, Stat design, Stat oos) {}

    public record Event(String symbol, String sector, LocalDate signal, String kind, int score, double baseDepthPct, double rvol,
                        LocalDate entryDate, double entry, LocalDate exitDate, double exit, String reason, double netPct, boolean design) {}

    public record Result(LocalDate from, LocalDate split, LocalDate to, int sessions, LocalDate lastDay, List<Base> breaking,
                         List<Base> ready, List<Base> forming, List<Row> rows, List<Event> recent, List<WatchSignal> signals) {}

    private final BacktestService backtest;
    private final BacktestRepository store;
    private Result cached;
    private Map<String, Base> current = Map.of();
    private List<Event> allEvents = List.of();
    private LocalDate cachedFor;

    public BaseBreakoutService(BacktestService backtest, BacktestRepository store) {
        this.backtest = backtest;
        this.store = store;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < SQUEEZE_LOOKBACK + 20) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor)) return Optional.of(cached);

        int T = m.dates.length, split = (int) Math.round(T * AdvancedStrategyService.DESIGN_SHARE);
        List<Base> today = new ArrayList<>();
        // history: [kind][exit] -> design / oos returns
        String[] kinds = {"BASE_BREAKOUT", "BASE_BREAKOUT_SCORE", "READY", "PLAIN_BREAKOUT"};
        List<List<double[]>> rets = new ArrayList<>();
        for (int k = 0; k < kinds.length * Exit.values().length; k++) rets.add(new ArrayList<>());
        List<Event> recent = new ArrayList<>();
        List<WatchSignal> signals = new ArrayList<>();

        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector) || s.size() < SQUEEZE_LOOKBACK + 10) continue;
            double[] width = bbWidth(s.close);
            int lastBase = -MIN_GAP, lastReady = -MIN_GAP, lastPlain = -MIN_GAP;
            for (int i = SQUEEZE_LOOKBACK; i < s.size(); i++) {
                Base b = base(s, i, width);
                boolean isLast = s.globalIndex[i] == T - 1;
                if (isLast && b != null) today.add(b);
                // plain 20-day breakout (no base requirement), for comparison
                Double avgV = Indicators.mean(s.volume, i - 20, i, 10);
                boolean plain = avgV != null && avgV > 0 && s.close[i] > max(s.high, i - 20, i) && s.volume[i] / avgV >= MIN_RVOL
                        && s.turnover[i] >= BacktestService.MIN_TURNOVER;
                List<Integer> hits = new ArrayList<>();
                if (b != null && b.phase() == Phase.BREAKOUT && i - lastBase >= MIN_GAP) {
                    lastBase = i;
                    hits.add(0);
                    if (b.score() >= GOOD_SCORE) hits.add(1);
                }
                if (b != null && b.phase() == Phase.READY && i - lastReady >= MIN_GAP) {
                    lastReady = i;
                    hits.add(2);
                }
                if (plain && i - lastPlain >= MIN_GAP) {
                    lastPlain = i;
                    hits.add(3);
                }
                if (hits.isEmpty()) continue;
                if (s.globalIndex[i] >= T - WatchSignal.RECENT_SESSIONS && (hits.contains(0) || hits.contains(2))) {
                    signals.add(new WatchSignal(hits.contains(0) ? "BASE_BREAKOUT" : "BASE_COILING", s.symbol, s.sector, s.dates[i], s.close[i],
                            b.score(), String.format(Locale.ROOT, "base %.1f → %.1f (%.0f%% range), score %d: %s", b.baseLow(), b.baseHigh(),
                            b.depthPct(), b.score(), String.join(", ", b.reasons()))));
                }
                boolean design = s.globalIndex[i] < split;
                for (Exit x : Exit.values()) {
                    Outcome o = BreakoutPullbackService.outcome(s, i, x);
                    if (o == null) continue;
                    for (int k : hits) rets.get(k * Exit.values().length + x.ordinal()).add(new double[] {design ? 1 : 0, o.ret() * 100, o.hit() ? 1 : 0});
                    if (x == Exit.SHORT && (hits.contains(0) || hits.contains(2))) {
                        recent.add(new Event(s.symbol, s.sector, s.dates[i], hits.contains(0) ? "breakout" : "coiling", b.score(), b.depthPct(), b.rvol(),
                                s.dates[o.entryI()], o.entry(), s.dates[o.exitI()], o.exit(), o.reason(), o.ret() * 100, design));
                    }
                }
            }
        }

        String[][] names = {
                {"Base breakout", "30-session sideways base (range ≤ 15%, drift ≤ 8%) and a close above its high on 1.5×+ volume"},
                {"Base breakout · score ≥ 60", "as above, with a base score of at least 60 (tight, squeezed, accumulation)"},
                {"Coiling at the top", "buy while it is still inside the base: within 3% of the high, squeezed, up-volume ≥ down-volume"},
                {"Plain 20-day breakout", "any close above the 20-day high on 1.5×+ volume, no base needed (for comparison)"}};
        List<Row> rows = new ArrayList<>();
        for (int k = 0; k < kinds.length; k++) {
            for (Exit x : Exit.values()) {
                List<double[]> r = rets.get(k * Exit.values().length + x.ordinal());
                rows.add(new Row(names[k][0], names[k][1], x, stat(r, true), stat(r, false)));
            }
        }

        today.sort(Comparator.comparingInt(Base::score).reversed());
        recent.sort(Comparator.comparing(Event::signal).reversed());
        cached = new Result(m.dates[0], m.dates[split], last, T, last,
                today.stream().filter(b -> b.phase() == Phase.BREAKOUT).toList(),
                today.stream().filter(b -> b.phase() == Phase.READY).toList(),
                today.stream().filter(b -> b.phase() == Phase.BASE).limit(40).toList(),
                rows, recent.stream().limit(60).toList(), signals);
        current = today.stream().collect(java.util.stream.Collectors.toMap(Base::symbol, b -> b, (a, b) -> a));
        allEvents = List.copyOf(recent);
        cachedFor = last;
        save(cached, kinds);
        return Optional.of(cached);
    }

    /** The stock's sideways base at the latest close, if it is in one. */
    public synchronized Optional<Base> current(String symbol) {
        run();
        return Optional.ofNullable(current.get(symbol));
    }

    /** The stock's past base breakouts and coiling signals with their outcomes, newest first. */
    public synchronized List<Event> history(String symbol) {
        run();
        return allEvents.stream().filter(e -> e.symbol().equals(symbol)).toList();
    }

    /** The base ending the day before i, judged at the close of day i; null if the stock is not sideways or not liquid. */
    private static Base base(Series s, int i, double[] width) {
        int from = i - BASE;
        double[] h = s.high, l = s.low, c = s.close, v = s.volume, to = s.turnover;
        double hi = max(h, from, i), lo = min(l, from, i);
        if (!(lo > 0) || !(c[from] > 0)) return null;
        double depth = hi / lo - 1, drift = c[i - 1] / c[from] - 1;
        double avgTo = mean(to, from, i);
        if (depth > MAX_DEPTH || Math.abs(drift) > MAX_DRIFT || !(avgTo >= MIN_BASE_TURNOVER)) return null;

        double up = 0, down = 0;
        for (int k = from + 1; k < i; k++) {
            if (c[k] > c[k - 1]) up += v[k];
            else if (c[k] < c[k - 1]) down += v[k];
        }
        double upDown = down > 0 ? up / down : 3;
        double avgV = mean(v, from, i), rvol = avgV > 0 ? v[i] / avgV : Double.NaN;
        double dryUp = avgV > 0 ? mean(v, i - 10, i) / avgV : Double.NaN;
        double toTrend = avgTo > 0 ? mean(to, i - 4, i + 1) / avgTo : Double.NaN;
        double pos = hi > lo ? Math.min(1.5, (c[i] - lo) / (hi - lo)) : 0.5;
        double toHigh = c[i] / hi - 1;
        double squeeze = percentile(width, i, SQUEEZE_LOOKBACK);

        Phase phase;
        if (c[i] > hi && rvol >= MIN_RVOL && to[i] >= BacktestService.MIN_TURNOVER) phase = Phase.BREAKOUT;
        else if (c[i] <= hi && toHigh >= -0.03 && squeeze <= 0.3 && upDown >= 1) phase = Phase.READY;
        else phase = Phase.BASE;

        // descriptive score, fixed weights (100 max)
        List<String> why = new ArrayList<>();
        int score = 0;
        if (depth <= 0.08) { score += 25; why.add(String.format(Locale.ROOT, "very tight base (%.0f%%)", depth * 100)); }
        else if (depth <= 0.12) { score += 15; why.add(String.format(Locale.ROOT, "tight base (%.0f%%)", depth * 100)); }
        else score += 5;
        if (squeeze <= 0.1) { score += 20; why.add("strong squeeze"); }
        else if (squeeze <= 0.3) { score += 12; why.add("squeeze"); }
        if (upDown >= 1.5) { score += 20; why.add(String.format(Locale.ROOT, "accumulation (up/down vol %.1f)", upDown)); }
        else if (upDown >= 1.2) { score += 12; why.add(String.format(Locale.ROOT, "mild accumulation (%.1f)", upDown)); }
        else if (upDown >= 1) score += 5;
        else why.add(String.format(Locale.ROOT, "distribution (up/down vol %.1f)", upDown));
        if (pos >= 0.9) { score += 15; why.add("closing at the top of the base"); }
        else if (pos >= 0.75) { score += 8; why.add("upper part of the base"); }
        if (dryUp <= 0.8) { score += 10; why.add("volume dried up in the last 10 sessions"); }
        if (toTrend >= 1.3) { score += 10; why.add(String.format(Locale.ROOT, "turnover rising (%.1f× in 5 days)", toTrend)); }

        return new Base(s.symbol, s.sector, s.dates[i], phase, c[i], hi, lo, depth * 100, drift * 100, pos, toHigh * 100, squeeze * 100,
                upDown, dryUp, rvol, toTrend, avgTo, to[i], score, why);
    }

    /** Bollinger band width (4 standard deviations ÷ 20-day average). */
    private static double[] bbWidth(double[] c) {
        double[] w = new double[c.length];
        Arrays.fill(w, Double.NaN);
        for (int i = 19; i < c.length; i++) {
            double m = mean(c, i - 19, i + 1), var = 0;
            for (int k = i - 19; k <= i; k++) var += (c[k] - m) * (c[k] - m);
            if (m > 0) w[i] = 4 * Math.sqrt(var / 20) / m;
        }
        return w;
    }

    /** Share of the previous n values that were narrower than today's (0 = narrowest). */
    private static double percentile(double[] a, int i, int n) {
        if (Double.isNaN(a[i])) return 1;
        int below = 0, count = 0;
        for (int k = Math.max(0, i - n); k < i; k++) {
            if (Double.isNaN(a[k])) continue;
            count++;
            if (a[k] < a[i]) below++;
        }
        return count < 40 ? 1 : below / (double) count;
    }

    private static Stat stat(List<double[]> rows, boolean design) {
        double[] r = rows.stream().filter(x -> (x[0] == 1) == design).mapToDouble(x -> x[1]).sorted().toArray();
        if (r.length == 0) return new Stat(0, 0, 0, 0);
        long hits = rows.stream().filter(x -> (x[0] == 1) == design && x[2] == 1).count();
        return new Stat(r.length, 100.0 * hits / r.length, Arrays.stream(r).average().orElse(0), r[r.length / 2]);
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        int n = 0;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) { s += a[k]; n++; }
        return n > 0 ? s / n : Double.NaN;
    }

    private static double max(double[] a, int from, int to) {
        double v = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) v = Math.max(v, a[k]);
        return v;
    }

    private static double min(double[] a, int from, int to) {
        double v = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) v = Math.min(v, a[k]);
        return v;
    }

    private void save(Result r, String[] kinds) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        int k = 0;
        for (Row x : r.rows()) {
            sums.add(new BacktestRepository.SummaryRow("BASE_" + kinds[k / Exit.values().length] + "_" + x.exit().name(), x.name() + " · " + x.exit().getTitle(),
                    x.design().n() + x.oos().n(), null, null, null, null, x.design().avgPct(), x.oos().avgPct(), null,
                    String.format(Locale.ROOT, "hit %.1f%% design / %.1f%% out-of-sample", x.design().hitPct(), x.oos().hitPct())));
            k++;
        }
        List<BacktestRepository.TradeRow> trades = r.recent().stream().map(e -> new BacktestRepository.TradeRow("BASE_" + e.kind().toUpperCase(Locale.ROOT),
                e.symbol(), e.signal(), e.entryDate(), e.entry(), e.exitDate(), e.exit(), null, e.reason(), e.netPct(), null)).toList();
        store.saveIfNew(new BacktestRepository.RunKey("BASES", r.from(), r.to(), r.sessions(), Exit.SHORT.window, false),
                BacktestService.COST_PCT, null, sums, trades);
    }
}
