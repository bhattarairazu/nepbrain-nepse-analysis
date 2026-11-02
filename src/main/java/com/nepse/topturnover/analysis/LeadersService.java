package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.BrokerAnalysisService.StockFlow;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Long-term leaders: the stocks that look like candidates to lead a bull run. Relative strength over 3, 6 and 12
 * months (ranked 1–99 against every liquid stock), distance from the 52-week high, the 30-week trend on weekly bars,
 * a long (26-week) base and its breakout on rising turnover, accumulation over 3 months, sector leadership and broker
 * accumulation, combined with fixed weights. A limited check of whether relative strength persisted in the stored history
 * is shown alongside: there is not enough history to backtest a year-long strategy.
 */
@Service
public class LeadersService {
    static final int M3 = 63, M6 = 125, M12 = 250, WEEKS_MA = 30, BASE_WEEKS = 26, FORWARD = 60, STEP = 5;
    static final double MIN_AVG_TURNOVER = 5_000_000, MAX_BASE_DEPTH = 0.35;

    public enum Stage {
        LEADER("Leader", "good", "relative strength in the top 20%, within 15% of its 52-week high, above a rising 30-week average"),
        EMERGING("Emerging", "good", "broke out of a 26-week base on rising turnover in the last 4 weeks, above its 30-week average"),
        BASING("Basing near the top", "warn", "in a 26-week base, within 10% of its top, relative strength above average: one to watch for a breakout"),
        NONE("No long-term setup", "", "");

        private final String title, tone, rule;

        Stage(String title, String tone, String rule) {
            this.title = title;
            this.tone = tone;
            this.rule = rule;
        }

        public String getTitle() { return title; }
        public String getTone() { return tone; }
        public String getRule() { return rule; }
    }

    public record Leader(String symbol, String sector, Stage stage, int score, int rsRank, double close, Double ret3Pct, Double ret6Pct, Double ret12Pct,
                         double fromHighPct, boolean fullYear, double weekMa30, boolean maRising, Double baseDepthPct, Double baseHigh, boolean baseBreakout,
                         double turnoverTrend, double upDownVolume, boolean sectorLeader, boolean brokerAccumulation, double avgTurnover,
                         List<String> reasons) {}

    public record Quintile(int quintile, String label, int observations, double avgForwardPct, double medianForwardPct, double beatMarketPct) {}

    public record Result(LocalDate lastDay, int sessions, int eligible, List<Leader> top, List<Leader> leaders, List<Leader> emerging,
                         List<Leader> basing, List<Quintile> persistence, int persistenceDays, String regimeVerdict, String regimeTone) {}

    private final BacktestService backtest;
    private final MarketRegimeService regime;
    private final BrokerAnalysisService brokers;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;
    private Object cachedData; // the price data the result was built from; new history reloads it
    private java.util.Map<String, Leader> all = java.util.Map.of();

    public LeadersService(BacktestService backtest, MarketRegimeService regime, BrokerAnalysisService brokers, BacktestRepository store) {
        this.backtest = backtest;
        this.regime = regime;
        this.brokers = brokers;
        this.store = store;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < M6 + 10) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor) && m == cachedData) return Optional.of(cached);
        int T = m.dates.length, lt = T - 1;

        MarketRegimeService.Result reg = regime.run().orElse(null);
        Set<String> leadSectors = new HashSet<>();
        if (reg != null) reg.sectors().stream().filter(MarketRegimeService.SectorRow::leader).forEach(x -> leadSectors.add(x.sector()));
        Set<String> accumulated = new HashSet<>();
        if (brokers.days(BigPlayerService.BROKER_WINDOW).size() >= 5) {
            for (StockFlow f : brokers.market(BigPlayerService.BROKER_WINDOW).accumulation()) accumulated.add(f.symbol());
        }

        // ---- raw measures for every liquid stock trading on the latest session
        record Raw(Series s, Double r3, Double r6, Double r12, double rs) {}
        List<Raw> raws = new ArrayList<>();
        for (Series s : m.series.values()) {
            int i = s.size() - 1;
            if (!Sectors.isEquity(s.sector) || i < M6 || s.globalIndex[i] != lt || !(avg(s.turnover, i - 59, i + 1) >= MIN_AVG_TURNOVER)) continue;
            Double r3 = ret(s.close, i, M3), r6 = ret(s.close, i, M6), r12 = ret(s.close, i, M12);
            double rs = 0.4 * r3 + 0.4 * r6 + 0.2 * (r12 != null ? r12 : r6);
            raws.add(new Raw(s, r3, r6, r12, rs));
        }
        double[] sortedRs = raws.stream().mapToDouble(Raw::rs).sorted().toArray();

        List<Leader> out = new ArrayList<>();
        for (Raw r : raws) {
            Series s = r.s();
            int i = s.size() - 1, rank = percentile(sortedRs, r.rs());
            int span = Math.min(M12, i);
            double hi52 = Double.NEGATIVE_INFINITY;
            for (int k = i - span; k <= i; k++) hi52 = Math.max(hi52, s.high[k]);
            double fromHigh = (s.close[i] / hi52 - 1) * 100;

            Weeks w = weeks(s);
            int nw = w.close.length;
            double ma30 = nw >= WEEKS_MA ? mean(w.close, nw - WEEKS_MA, nw) : mean(w.close, 0, nw);
            double ma30Before = nw >= WEEKS_MA + 4 ? mean(w.close, nw - WEEKS_MA - 4, nw - 4) : Double.NaN;
            boolean rising = !Double.isNaN(ma30Before) && ma30 > ma30Before;
            boolean above = s.close[i] > ma30;

            // 26-week base ending 5 weeks ago, and whether the last 4 weeks broke above it on rising turnover
            Double depth = null, baseHigh = null;
            boolean breakout = false, nearTop = false;
            if (nw >= BASE_WEEKS + 5) {
                int bFrom = nw - BASE_WEEKS - 4, bTo = nw - 4;
                double bh = max(w.high, bFrom, bTo), bl = min(w.low, bFrom, bTo);
                if (bl > 0 && bh / bl - 1 <= MAX_BASE_DEPTH) {
                    depth = (bh / bl - 1) * 100;
                    baseHigh = bh;
                    double toBase = mean(w.turnover, bFrom, bTo), toRecent = mean(w.turnover, bTo, nw);
                    breakout = s.close[i] > bh && max(w.close, bTo, nw) > bh && toRecent >= 1.5 * toBase;
                    nearTop = !breakout && s.close[i] >= bh * 0.90 && s.close[i] <= bh * 1.02;
                }
            }
            double toTrend = avg(s.turnover, i - 19, i + 1) / avg(s.turnover, Math.max(0, i - 119), i + 1);
            double up = 0, down = 0;
            for (int k = Math.max(1, i - 59); k <= i; k++) { if (s.close[k] > s.close[k - 1]) up += s.volume[k]; else if (s.close[k] < s.close[k - 1]) down += s.volume[k]; }
            double upDown = down > 0 ? up / down : 3;
            boolean sectorLead = leadSectors.contains(s.sector), acc = accumulated.contains(s.symbol);

            Stage stage = Stage.NONE;
            if (rank >= 80 && fromHigh >= -15 && above && rising) stage = Stage.LEADER;
            else if (breakout && above) stage = Stage.EMERGING;
            else if (nearTop && rank >= 50) stage = Stage.BASING;

            List<String> why = new ArrayList<>();
            int sc = (int) Math.round(rank * 0.35);
            why.add("relative strength " + rank + "/99" + (r.r6() != null ? String.format(Locale.ROOT, " (%+.0f%% in 6 months)", r.r6() * 100) : ""));
            if (fromHigh >= -5) { sc += 15; why.add("at or near its 52-week high"); }
            else if (fromHigh >= -15) { sc += 10; why.add(String.format(Locale.ROOT, "%.0f%% under its 52-week high", -fromHigh)); }
            else if (fromHigh >= -25) sc += 5;
            if (above && rising) { sc += 15; why.add("above a rising 30-week average"); }
            else if (above) { sc += 8; why.add("above a flat 30-week average"); }
            if (breakout) { sc += 15; why.add(String.format(Locale.ROOT, "broke out of a 26-week base (%.0f%% deep) on rising turnover", depth)); }
            else if (nearTop) { sc += 8; why.add(String.format(Locale.ROOT, "near the top of a 26-week base (%.0f%% deep)", depth)); }
            if (upDown >= 1.3) { sc += 10; why.add(String.format(Locale.ROOT, "accumulation over 3 months (up/down volume %.1f)", upDown)); }
            else if (upDown >= 1.1) sc += 5;
            if (sectorLead) { sc += 5; why.add("in a leading sector"); }
            if (acc) { sc += 5; why.add("brokers accumulating (floorsheet)"); }
            if (toTrend >= 1.5) why.add(String.format(Locale.ROOT, "turnover %.1f× its 6-month average", toTrend));
            out.add(new Leader(s.symbol, s.sector, stage, Math.min(100, sc), rank, s.close[i], pct(r.r3()), pct(r.r6()), pct(r.r12()), fromHigh, span >= M12,
                    ma30, rising, depth, baseHigh, breakout, toTrend, upDown, sectorLead, acc, avg(s.turnover, i - 59, i + 1), why));
        }
        Comparator<Leader> byScore = Comparator.comparingInt(Leader::score).reversed().thenComparing(Comparator.comparingInt(Leader::rsRank).reversed());
        out.sort(byScore);

        Persistence p = persistence(m);
        cached = new Result(last, T, out.size(), out.stream().limit(30).toList(),
                out.stream().filter(x -> x.stage() == Stage.LEADER).toList(),
                out.stream().filter(x -> x.stage() == Stage.EMERGING).toList(),
                out.stream().filter(x -> x.stage() == Stage.BASING).toList(),
                p.quintiles, p.days, reg == null ? null : reg.verdict(), reg == null ? "" : reg.verdictTone());
        all = out.stream().collect(java.util.stream.Collectors.toMap(Leader::symbol, x -> x, (a, b) -> a));
        cachedFor = last;
        cachedData = m;
        save(cached);
        return Optional.of(cached);
    }

    /** One stock's long-term view (empty if it is not liquid enough or has under 6 months of history). */
    public synchronized Optional<Leader> stock(String symbol) {
        run();
        return Optional.ofNullable(all.get(symbol));
    }

    // ------------------------------------------------------------------ persistence check

    private record Persistence(List<Quintile> quintiles, int days) {}

    /** Every 5 sessions: rank liquid stocks by 6-month return, then measure the next 60 sessions. Did strong stay strong? */
    private static Persistence persistence(Market m) {
        int T = m.dates.length;
        List<List<double[]>> q = new ArrayList<>();
        for (int k = 0; k < 5; k++) q.add(new ArrayList<>());
        int days = 0;
        for (int g = M6; g + FORWARD < T; g += STEP) {
            List<double[]> day = new ArrayList<>(); // {past, forward}
            for (Series s : m.series.values()) {
                if (!Sectors.isEquity(s.sector)) continue;
                int i = s.indexOf(g), j = s.indexOf(g + FORWARD);
                if (i < M6 || j < 0 || !(avg(s.turnover, i - 59, i + 1) >= MIN_AVG_TURNOVER)) continue;
                Double past = ret(s.close, i, M6);
                if (past == null || !(s.close[i] > 0)) continue;
                day.add(new double[] {past, s.close[j] / s.close[i] - 1});
            }
            if (day.size() < 25) continue;
            days++;
            double mkt = m.index[g + FORWARD] / m.index[g] - 1;
            day.sort(Comparator.comparingDouble(x -> x[0]));
            for (int k = 0; k < day.size(); k++) {
                int b = Math.min(4, k * 5 / day.size());
                q.get(b).add(new double[] {day.get(k)[1], day.get(k)[1] > mkt ? 1 : 0});
            }
        }
        String[] labels = {"Weakest 20% (6-month return)", "Weak", "Middle", "Strong", "Strongest 20%"};
        List<Quintile> out = new ArrayList<>();
        for (int b = 4; b >= 0; b--) {
            List<double[]> r = q.get(b);
            double[] f = r.stream().mapToDouble(x -> x[0] * 100).sorted().toArray();
            out.add(new Quintile(b + 1, labels[b], f.length, f.length == 0 ? 0 : Arrays.stream(f).average().orElse(0), f.length == 0 ? 0 : f[f.length / 2],
                    r.isEmpty() ? 0 : 100.0 * r.stream().filter(x -> x[1] == 1).count() / r.size()));
        }
        return new Persistence(out, days);
    }

    // ------------------------------------------------------------------ weekly bars

    private record Weeks(double[] high, double[] low, double[] close, double[] turnover) {}

    private static Weeks weeks(Series s) {
        List<double[]> w = new ArrayList<>();
        int key = Integer.MIN_VALUE;
        for (int i = 0; i < s.size(); i++) {
            LocalDate d = s.dates[i];
            int k = d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            if (k != key) { w.add(new double[] {s.high[i], s.low[i], s.close[i], 0}); key = k; }
            double[] b = w.get(w.size() - 1);
            b[0] = Math.max(b[0], s.high[i]);
            b[1] = Math.min(b[1], s.low[i]);
            b[2] = s.close[i];
            b[3] += Double.isNaN(s.turnover[i]) ? 0 : s.turnover[i];
        }
        int n = w.size();
        double[] h = new double[n], l = new double[n], c = new double[n], t = new double[n];
        for (int k = 0; k < n; k++) { h[k] = w.get(k)[0]; l[k] = w.get(k)[1]; c[k] = w.get(k)[2]; t[k] = w.get(k)[3]; }
        return new Weeks(h, l, c, t);
    }

    // ------------------------------------------------------------------ helpers

    private static Double ret(double[] c, int i, int back) {
        return i - back >= 0 && c[i - back] > 0 ? c[i] / c[i - back] - 1 : null;
    }

    private static Double pct(Double v) {
        return v == null ? null : v * 100;
    }

    private static int percentile(double[] sorted, double v) {
        int below = 0;
        for (double x : sorted) if (x < v) below++;
        return Math.max(1, Math.min(99, (int) Math.round(99.0 * below / Math.max(1, sorted.length - 1))));
    }

    private static double avg(double[] a, int from, int to) {
        return mean(a, Math.max(0, from), to);
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        int n = 0;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) { s += a[k]; n++; }
        return n > 0 ? s / n : Double.NaN;
    }

    private static double max(double[] a, int from, int to) {
        double v = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) v = Math.max(v, a[k]);
        return v;
    }

    private static double min(double[] a, int from, int to) {
        double v = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) v = Math.min(v, a[k]);
        return v;
    }

    private void save(Result r) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        for (Quintile q : r.persistence()) {
            sums.add(new BacktestRepository.SummaryRow("LEADERS_RS_Q" + q.quintile(), q.label() + " · next 60 sessions", q.observations(), q.beatMarketPct(),
                    q.avgForwardPct(), q.medianForwardPct(), null, null, null, null,
                    String.format(Locale.ROOT, "%d ranking days, before costs", r.persistenceDays())));
        }
        store.saveIfNew(new BacktestRepository.RunKey("LEADERS", backtest.data().dates[0], r.lastDay(), r.sessions(), FORWARD, false),
                BacktestService.COST_PCT, null, sums, List.of());
    }
}
