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

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Exit;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Outcome;
import com.nepse.topturnover.analysis.BrokerAnalysisService.StockFlow;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Top 5: the stocks that best fit "sideways, regularly in the top-20 turnover and volume lists, turnover and volume
 * rising sharply, price still flat". Every liquid ordinary share is scored each session with fixed weights (100 max);
 * stocks already being pushed up (near-circuit runs, +15% in 10 sessions) are excluded. The five best scores of the
 * latest session are the recommendations, with a plan. The same formula's daily top 5 is run over the stored history.
 */
@Service
public class RecommendationService {
    static final int BASE = 30, LIST_WINDOW = 20, SHORT = 5, BASELINE = 60, TOP = 5, MIN_GAP = 10;
    public static final int MIN_SCORE = 50;
    static final double MAX_DEPTH = 0.15, MAX_DRIFT = 0.08, MIN_AVG_TURNOVER = 5_000_000;
    public static final double TARGET = 7, STOP = 5;
    public static final int WINDOW = 10;

    public record Pick(int rank, String symbol, String sector, LocalDate date, int score, double close, double baseLow, double baseHigh,
                       double depthPct, double ret10Pct, int topTurnoverDays, int topVolumeDays, double turnoverSurge, double volumeSurge,
                       double upDownVolume, double bigTicket, boolean brokerAccumulation, double stopPrice, double targetPrice,
                       List<String> reasons, List<String> cautions) {}

    public record Stat(int n, int days, double hitPct, double avgPct, double medianPct, double winPct) {}

    public record Row(String name, String rule, Exit exit, Stat design, Stat oos) {}

    public record PastPick(LocalDate date, int rank, String symbol, int score, Double netPct, String exit) {}

    public record Result(LocalDate from, LocalDate split, LocalDate to, int sessions, LocalDate lastDay, List<Pick> top, List<Pick> nearMisses,
                         int scored, List<Row> rows, List<PastPick> recent, boolean brokersUsed, List<WatchSignal> signals) {}

    private final BacktestService backtest;
    private final BrokerAnalysisService brokers;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;
    private Object cachedData; // the price data the result was built from; new history reloads it
    private int cachedFloorsheetDays = -1;

    public RecommendationService(BacktestService backtest, BrokerAnalysisService brokers, BacktestRepository store) {
        this.backtest = backtest;
        this.brokers = brokers;
        this.store = store;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < BASELINE + 30) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        List<LocalDate> fsDays = brokers.days(BigPlayerService.BROKER_WINDOW);
        if (cached != null && last.equals(cachedFor) && m == cachedData && fsDays.size() == cachedFloorsheetDays) return Optional.of(cached);
        int T = m.dates.length, split = (int) Math.round(T * AdvancedStrategyService.DESIGN_SHARE);

        // floorsheet accumulation (latest window only: not enough history to use it in the backtest)
        Set<String> accumulated = new HashSet<>();
        if (fsDays.size() >= 5) for (StockFlow f : brokers.market(BigPlayerService.BROKER_WINDOW).accumulation()) accumulated.add(f.symbol());

        // score every liquid stock on every session
        Map<Integer, List<Pick>> byDay = new HashMap<>();
        Map<String, Series> seriesOf = new HashMap<>();
        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector) || s.size() < BASELINE + 10) continue;
            seriesOf.put(s.symbol, s);
            for (int i = BASELINE + SHORT; i < s.size(); i++) {
                int g = s.globalIndex[i];
                Pick p = score(m, s, i, g == T - 1 && accumulated.contains(s.symbol));
                if (p != null) byDay.computeIfAbsent(g, k -> new ArrayList<>()).add(p);
            }
        }
        Comparator<Pick> best = Comparator.comparingInt(Pick::score).reversed().thenComparing(Pick::turnoverSurge, Comparator.reverseOrder());

        // backtest: the daily top 5 (score >= MIN_SCORE), one pick per stock per 10 sessions
        String[][] names = {
                {"Daily top 5", "the five best scores of each session (score ≥ " + MIN_SCORE + "), bought at the next open"},
                {"Daily top 1", "only the best score of each session"},
                {"Score 70+", "every pick with a score of at least 70"}};
        List<List<double[]>> rets = new ArrayList<>();
        for (int k = 0; k < names.length * Exit.values().length; k++) rets.add(new ArrayList<>());
        List<PastPick> recent = new ArrayList<>();
        List<WatchSignal> signals = new ArrayList<>();
        Map<String, Integer> lastPicked = new HashMap<>();
        for (int g = 0; g < T; g++) {
            List<Pick> day = byDay.get(g);
            if (day == null) continue;
            List<Pick> top = day.stream().filter(p -> p.score() >= MIN_SCORE).sorted(best).limit(TOP).toList();
            for (int r = 0; r < top.size(); r++) {
                Pick p = top.get(r);
                Integer prev = lastPicked.get(p.symbol());
                if (prev != null && g - prev < MIN_GAP) continue;
                lastPicked.put(p.symbol(), g);
                Series s = seriesOf.get(p.symbol());
                int i = s.indexOf(g);
                boolean design = g < split;
                Outcome first = BreakoutPullbackService.outcome(s, i, Exit.SHORT);
                if (g >= T - 30) recent.add(new PastPick(p.date(), r + 1, p.symbol(), p.score(), first == null ? null : first.ret() * 100,
                        first == null ? null : first.reason()));
                if (g >= T - WatchSignal.RECENT_SESSIONS) signals.add(new WatchSignal("RECOMMENDED", p.symbol(), p.sector(), p.date(), p.close(), p.score(),
                        "Top 5 #" + (r + 1) + ", score " + p.score() + ": " + String.join(", ", p.reasons())));
                for (Exit x : Exit.values()) {
                    Outcome o = BreakoutPullbackService.outcome(s, i, x);
                    if (o == null) continue;
                    double[] row = {design ? 1 : 0, o.ret() * 100, o.hit() ? 1 : 0, g};
                    rets.get(x.ordinal()).add(row);
                    if (r == 0) rets.get(Exit.values().length + x.ordinal()).add(row);
                    if (p.score() >= 70) rets.get(2 * Exit.values().length + x.ordinal()).add(row);
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int k = 0; k < names.length; k++) {
            for (Exit x : Exit.values()) {
                List<double[]> r = rets.get(k * Exit.values().length + x.ordinal());
                rows.add(new Row(names[k][0], names[k][1], x, stat(r, true), stat(r, false)));
            }
        }

        List<Pick> today = byDay.getOrDefault(T - 1, List.of()).stream().sorted(best).toList();
        List<Pick> top = new ArrayList<>(), near = new ArrayList<>();
        for (Pick p : today) {
            if (p.score() >= MIN_SCORE && top.size() < TOP) top.add(rank(p, top.size() + 1));
            else if (near.size() < 10) near.add(p);
        }
        recent.sort(Comparator.comparing(PastPick::date).reversed().thenComparingInt(PastPick::rank));
        cached = new Result(m.dates[0], m.dates[split], last, T, last, top, near, today.size(), rows, recent, !accumulated.isEmpty(), signals);
        cachedFor = last;
        cachedData = m;
        cachedFloorsheetDays = fsDays.size();
        save(cached);
        return Optional.of(cached);
    }

    /** The formula for one stock at the close of day i; null if it is not liquid, not sideways, or already being pushed up. */
    private static Pick score(Market m, Series s, int i, boolean brokerAccumulation) {
        double[] h = s.high, l = s.low, c = s.close, v = s.volume, to = s.turnover, tr = s.trades, ch = s.change;
        double avgTo = mean(to, i - BASELINE, i), avgV = mean(v, i - BASELINE, i);
        if (!(avgTo >= MIN_AVG_TURNOVER) || !(to[i] >= BacktestService.MIN_TURNOVER) || !(c[i] > 0) || !(avgV > 0)) return null;
        int from = i - BASE;
        double hi = max(h, from, i), lo = min(l, from, i);
        if (!(lo > 0) || !(c[from] > 0)) return null;
        double depth = hi / lo - 1, drift = c[i - 1] / c[from] - 1, ret10 = c[i] / c[i - 10] - 1;
        if (depth > MAX_DEPTH || Math.abs(drift) > MAX_DRIFT) return null;      // not sideways
        int ups = 0;
        for (int k = i - 9; k <= i; k++) if (ch[k] >= 9) ups++;
        if (ups >= 2 || ret10 >= 0.15) return null;                           // already being marked up

        int topTo = 0, topVol = 0;
        for (int k = Math.max(0, s.globalIndex[i] - LIST_WINDOW + 1); k <= s.globalIndex[i]; k++) {
            if (m.turnoverRank.getOrDefault(k, Map.of()).containsKey(s.symbol)) topTo++;
            if (m.volumeRank.getOrDefault(k, Map.of()).containsKey(s.symbol)) topVol++;
        }
        double to5 = 0, v5 = 0, tr5 = 0;
        for (int k = i - SHORT + 1; k <= i; k++) { to5 += to[k]; v5 += v[k]; tr5 += tr[k]; }
        double toSurge = to5 / SHORT / avgTo, volSurge = v5 / SHORT / avgV;
        double up = 0, down = 0;
        for (int k = from + 1; k <= i; k++) { if (c[k] > c[k - 1]) up += v[k]; else if (c[k] < c[k - 1]) down += v[k]; }
        double upDown = down > 0 ? up / down : 3;
        double[] ats = new double[BASELINE];
        int n = 0;
        for (int k = i - SHORT - BASELINE + 1; k <= i - SHORT; k++) if (k >= 0 && tr[k] > 0 && to[k] > 0) ats[n++] = to[k] / tr[k];
        double bigTicket = Double.NaN;
        if (n >= 30 && tr5 > 0) { Arrays.sort(ats, 0, n); bigTicket = (to5 / tr5) / ats[n / 2]; }

        List<String> why = new ArrayList<>(), caution = new ArrayList<>();
        int sc = 0;
        if (depth <= 0.08) { sc += 20; why.add(String.format(Locale.ROOT, "very tight sideways range (%.0f%%)", depth * 100)); }
        else if (depth <= 0.12) { sc += 12; why.add(String.format(Locale.ROOT, "tight sideways range (%.0f%%)", depth * 100)); }
        else { sc += 6; why.add(String.format(Locale.ROOT, "sideways (%.0f%% range)", depth * 100)); }
        sc += topTo >= 10 ? 15 : topTo >= 5 ? 10 : topTo >= 2 ? 5 : 0;
        sc += topVol >= 10 ? 15 : topVol >= 5 ? 10 : topVol >= 2 ? 5 : 0;
        if (topTo + topVol > 0) why.add("top-20 turnover " + topTo + "/" + LIST_WINDOW + ", volume " + topVol + "/" + LIST_WINDOW + " sessions");
        if (toSurge >= 3) sc += 15; else if (toSurge >= 2) sc += 10; else if (toSurge >= 1.5) sc += 5;
        if (volSurge >= 3) sc += 10; else if (volSurge >= 2) sc += 7; else if (volSurge >= 1.5) sc += 4;
        if (toSurge >= 1.5 || volSurge >= 1.5) why.add(String.format(Locale.ROOT, "turnover %.1f×, volume %.1f× normal (5 days)", toSurge, volSurge));
        if (Math.abs(ret10) <= 0.03) { sc += 10; why.add(String.format(Locale.ROOT, "price still flat (%+.1f%% in 10 sessions)", ret10 * 100)); }
        else if (Math.abs(ret10) <= 0.05) { sc += 5; why.add(String.format(Locale.ROOT, "price barely moved (%+.1f%%)", ret10 * 100)); }
        if (upDown >= 1.5) { sc += 10; why.add(String.format(Locale.ROOT, "accumulation: up-day volume %.1f× down-day", upDown)); }
        else if (upDown >= 1.2) { sc += 5; why.add(String.format(Locale.ROOT, "mild accumulation (%.1f)", upDown)); }
        else if (upDown < 1) caution.add(String.format(Locale.ROOT, "more volume on down days (%.1f): could be distribution", upDown));
        if (bigTicket >= 1.5) { sc += 5; why.add(String.format(Locale.ROOT, "big orders (trade size %.1f× normal)", bigTicket)); }
        if (brokerAccumulation) { sc += 10; why.add("floorsheet: a few brokers accumulating"); }
        double pos = hi > lo ? (c[i] - lo) / (hi - lo) : 0.5;
        if (pos > 1) caution.add("already closed above the range high: a breakout, entry is later than the formula intends");
        if (ret10 <= -0.05) caution.add(String.format(Locale.ROOT, "falling inside the range (%+.1f%% in 10 sessions)", ret10 * 100));

        double stop = c[i] * (1 - STOP / 100), target = c[i] * (1 + TARGET / 100);
        return new Pick(0, s.symbol, s.sector, s.dates[i], Math.min(100, sc), c[i], lo, hi, depth * 100, ret10 * 100, topTo, topVol, toSurge, volSurge,
                upDown, bigTicket, brokerAccumulation, stop, target, why, caution);
    }

    private static Pick rank(Pick p, int r) {
        return new Pick(r, p.symbol(), p.sector(), p.date(), p.score(), p.close(), p.baseLow(), p.baseHigh(), p.depthPct(), p.ret10Pct(),
                p.topTurnoverDays(), p.topVolumeDays(), p.turnoverSurge(), p.volumeSurge(), p.upDownVolume(), p.bigTicket(), p.brokerAccumulation(),
                p.stopPrice(), p.targetPrice(), p.reasons(), p.cautions());
    }

    // ------------------------------------------------------------------ helpers

    private static Stat stat(List<double[]> rows, boolean design) {
        double[] r = rows.stream().filter(x -> (x[0] == 1) == design).mapToDouble(x -> x[1]).sorted().toArray();
        if (r.length == 0) return new Stat(0, 0, 0, 0, 0, 0);
        long hits = rows.stream().filter(x -> (x[0] == 1) == design && x[2] == 1).count();
        int days = (int) rows.stream().filter(x -> (x[0] == 1) == design).mapToDouble(x -> x[3]).distinct().count();
        long wins = Arrays.stream(r).filter(x -> x > 0).count();
        return new Stat(r.length, days, 100.0 * hits / r.length, Arrays.stream(r).average().orElse(0), r[r.length / 2], 100.0 * wins / r.length);
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

    private void save(Result r) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        for (Row x : r.rows()) {
            String key = "TOP5_" + x.name().replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT) + "_" + x.exit().name();
            sums.add(new BacktestRepository.SummaryRow(key.substring(0, Math.min(40, key.length())), x.name() + " · " + x.exit().getTitle(),
                    x.design().n() + x.oos().n(), x.oos().winPct(), null, x.oos().medianPct(), null, x.design().avgPct(), x.oos().avgPct(), null,
                    String.format(Locale.ROOT, "hit %.1f%% design / %.1f%% out-of-sample", x.design().hitPct(), x.oos().hitPct())));
        }
        store.saveIfNew(new BacktestRepository.RunKey("TOP5", r.from(), r.to(), r.sessions(), Exit.SHORT.window, false),
                BacktestService.COST_PCT, null, sums, List.of());
    }
}
