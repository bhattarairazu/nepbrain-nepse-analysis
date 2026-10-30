package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Smart Money Concepts (adapted to daily candles) and combinations of the standard rules, tested walk-forward:
 * the first {@link #DESIGN_SHARE} of sessions is the design period, the rest is out-of-sample. A rule "passes" only if
 * it wins more than half its trades and makes money after costs in BOTH periods, with enough trades, and beats random entries.
 *
 * Execution model for NEPSE: buy at the next session's open. A profit target is a limit sell, filled the day the high
 * reaches it (at the open if the stock gaps above it). NEPSE TMS has no stop orders, so a stop is checked at the close
 * and sold at the next open. Exit rules and the time limit also sell at the next open. Prices are bonus/right adjusted.
 */
@Service
public class AdvancedStrategyService {
    public static final double DESIGN_SHARE = 0.6;
    public static final int MIN_TRADES = 30, MIN_TRADES_OUT = 15;

    public enum Setup {
        SMC_SWEEP("SMC: liquidity sweep and reclaim", "SMC",
                "Low dips below the lowest low of the previous 20 sessions (stop hunt) but the close is back above it, in the upper half of the day's range, on ≥ 1.2× volume. Stop under the sweep low, target 2× risk."),
        SMC_BOS_OB("SMC: break of structure + order block retest", "SMC",
                "Close breaks the last confirmed swing high; within 10 sessions price dips into the last down-candle before the break (order block) and closes above its low. Stop under the order block, target 2× risk."),
        SMC_FVG("SMC: fair value gap retest", "SMC",
                "A ≥ 3% up-day leaves a gap (day-3 high below day-1 low, gap ≥ 1%); within 10 sessions price trades back into the gap and closes above its bottom. Stop under the gap, target 2× risk."),
        SMC_CHOCH("SMC: change of character", "SMC",
                "Stock in a downtrend (below its 50-day EMA or down ≥ 8% in 20 sessions) closes above its last confirmed swing high. Stop under the last swing low, target 2× risk."),
        BB_UPTREND("Bollinger bounce + uptrend", "Combination",
                "Bollinger bounce while the close is above a rising 50-day average. Exit at the 20-day average, 8% stop, 15 sessions max."),
        BB_DRYUP("Bollinger bounce + volume dry-up", "Combination",
                "Bollinger bounce on below-normal volume (sellers exhausted). Exit at the 20-day average, 8% stop, 15 sessions max."),
        RSI2_BB("RSI(2) + Bollinger double oversold", "Combination",
                "RSI(2) < 10 and the close below the lower Bollinger band at the same time. Exit on a close above the 5-day average, 8% stop, 10 sessions max."),
        BB_SECTOR("Bollinger bounce + strong sector", "Combination",
                "Bollinger bounce in a stock whose sector is in the top 3 by 20-session return. Exit at the 20-day average, 8% stop, 15 sessions max."),
        BB_MARKET("Bollinger bounce + rising market", "Combination",
                "Bollinger bounce only when the equal-weight market is above its 20-session average. Exit at the 20-day average, 8% stop, 15 sessions max."),
        SWEEP_MARKET("SMC sweep + rising market", "Combination",
                "Liquidity sweep and reclaim only when the market is above its 20-session average. Stop under the sweep low, target 2× risk."),
        RANDOM("Benchmark: random entry", "Benchmark",
                "Random liquid stock-day, 8% stop, target 2× risk, 15 sessions max. A rule must beat this to have an edge.");

        private final String title, family, rule;

        Setup(String title, String family, String rule) {
            this.title = title;
            this.family = family;
            this.rule = rule;
        }

        public String getTitle() { return title; }
        public String getFamily() { return family; }
        public String getRule() { return rule; }
    }

    /** How a trade is managed: absolute stop (or % below entry), optional R-multiple target, optional exit rule, time limit. */
    record Plan(Double stopPrice, double stopPct, Double rTarget, ExitRule exit, int maxHold) {}

    interface ExitRule {
        boolean test(Ind x, Series s, int j);
    }

    public record Trade(Setup setup, String symbol, LocalDate signal, LocalDate entryDate, double entry, LocalDate exitDate,
                        double exit, int held, String exitReason, double netPct, boolean outOfSample) {}

    public record Stats(int trades, double winRate, double avgNet, double medianNet, double profitFactor) {
        static Stats of(List<Trade> l) {
            if (l.isEmpty()) return new Stats(0, 0, 0, 0, 0);
            double[] n = l.stream().mapToDouble(Trade::netPct).sorted().toArray();
            double gain = Arrays.stream(n).filter(v -> v > 0).sum(), loss = -Arrays.stream(n).filter(v -> v <= 0).sum();
            return new Stats(n.length, 100.0 * Arrays.stream(n).filter(v -> v > 0).count() / n.length,
                    Arrays.stream(n).average().orElse(0), n[n.length / 2], loss > 0 ? gain / loss : Double.POSITIVE_INFINITY);
        }
    }

    public record SetupResult(Setup setup, Stats all, Stats design, Stats outOfSample, double avgHeld,
                              Map<String, Long> exitReasons, String verdict, String tone, List<Trade> recent, List<String> signalsToday) {
        public boolean isPassed() { return "Passed".equals(verdict); }
    }

    public record Result(LocalDate from, LocalDate splitDate, LocalDate to, int sessions, List<SetupResult> setups) {
        public List<SetupResult> passed() {
            return setups.stream().filter(SetupResult::isPassed).toList();
        }
    }

    // ------------------------------------------------------------------

    private final BacktestService backtest;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;

    public AdvancedStrategyService(BacktestService backtest, BacktestRepository store) {
        this.backtest = backtest;
        this.store = store;
    }

    public synchronized void evict() {
        cached = null;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < 80) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor)) return Optional.of(cached);

        int split = (int) Math.round(m.dates.length * DESIGN_SHARE);
        LocalDate splitDate = m.dates[split];
        int[][] sectorRank = sectorRanks(m);
        Map<String, Integer> sectorIndex = sectorIndex(m);

        Map<Setup, List<Trade>> trades = new HashMap<>();
        Map<Setup, List<String>> today = new HashMap<>();
        for (Setup st : Setup.values()) { trades.put(st, new ArrayList<>()); today.put(st, new ArrayList<>()); }
        List<int[]> liquid = new ArrayList<>();
        List<Series> all = new ArrayList<>(m.series.values());

        for (int sn = 0; sn < all.size(); sn++) {
            Series s = all.get(sn);
            if (!Sectors.isEquity(s.sector) || s.size() < 60) continue;
            Ind x = new Ind(s.close, s.high, s.low);
            Structure st = new Structure(s);
            Integer sec = s.sector == null ? null : sectorIndex.get(s.sector);
            for (int i = 25; i < s.size(); i++) {
                if (!(s.turnover[i] >= BacktestService.MIN_TURNOVER) || Double.isNaN(s.close[i])) continue;
                liquid.add(new int[] {sn, i});
                boolean marketUp = BacktestService.marketUp(m, s.globalIndex[i]);
                boolean strongSector = sec != null && sectorRank[s.globalIndex[i]][sec] > 0 && sectorRank[s.globalIndex[i]][sec] <= 3;
                for (Setup setup : Setup.values()) {
                    if (setup == Setup.RANDOM) continue;
                    Plan p = plan(setup, x, st, s, i, marketUp, strongSector);
                    if (p == null) continue;
                    if (i == s.size() - 1) today.get(setup).add(s.symbol);
                    Trade t = simulate(s, x, setup, p, i, splitDate);
                    if (t != null) trades.get(setup).add(t);
                }
            }
        }
        Random rnd = new Random(42);
        int want = 600;
        for (int k = 0; k < want * 3 && trades.get(Setup.RANDOM).size() < want && !liquid.isEmpty(); k++) {
            int[] pick = liquid.get(rnd.nextInt(liquid.size()));
            Series s = all.get(pick[0]);
            Trade t = simulate(s, new Ind(s.close, s.high, s.low), Setup.RANDOM, new Plan(null, 0.08, 2.0, null, 15), pick[1], splitDate);
            if (t != null) trades.get(Setup.RANDOM).add(t);
        }

        Stats random = Stats.of(trades.get(Setup.RANDOM));
        List<SetupResult> results = new ArrayList<>();
        for (Setup setup : Setup.values()) results.add(summarise(setup, trades.get(setup), today.get(setup), random));
        cached = new Result(m.dates[0], splitDate, last, m.dates.length, results);
        cachedFor = last;
        save(cached, trades);
        return Optional.of(cached);
    }

    // ------------------------------------------------------------------ watchlist: what to look at next

    /** A stock that triggered a rule at the last close, or is one step away from triggering it. */
    public record WatchItem(Setup setup, String verdict, String tone, Integer oosTrades, Double oosWin, Double oosAvg,
                            String symbol, String name, String sector, boolean triggered, String condition,
                            double close, double trigger, double distancePct, Double stop, Double target, double turnover) {}

    /** Watch items for the latest session, rules that passed first, nearest triggers first. */
    public synchronized List<WatchItem> watchlist() {
        Optional<Result> res = run();
        Market m = backtest.data();
        if (res.isEmpty() || m == null) return List.of();
        Map<Setup, SetupResult> bySetup = new HashMap<>();
        res.get().setups().forEach(r -> bySetup.put(r.setup(), r));
        int T = m.dates.length - 1;
        boolean marketUp = BacktestService.marketUp(m, T);
        int[][] ranks = sectorRanks(m);
        Map<String, Integer> sectorIdx = sectorIndex(m);

        List<WatchItem> out = new ArrayList<>();
        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector) || s.size() < 60) continue;
            int i = s.size() - 1;
            if (s.globalIndex[i] != T || !(s.turnover[i] >= BacktestService.MIN_TURNOVER)) continue;
            Ind x = new Ind(s.close, s.high, s.low);
            Structure st = new Structure(s);
            Integer sec = s.sector == null ? null : sectorIdx.get(s.sector);
            boolean strongSector = sec != null && ranks[T][sec] > 0 && ranks[T][sec] <= 3;
            double c = s.close[i];
            for (Setup setup : Setup.values()) {
                if (setup == Setup.RANDOM) continue;
                SetupResult r = bySetup.get(setup);
                Plan p = plan(setup, x, st, s, i, marketUp, strongSector);
                if (p != null) {
                    double stop = p.stopPrice() != null ? p.stopPrice() : c * (1 - p.stopPct());
                    Double target = p.rTarget() != null ? c + p.rTarget() * (c - stop) : null;
                    out.add(item(setup, r, s, true, "Triggered at the last close: buy at the next open (skip if it opens below the stop)",
                            c, c, stop, target));
                    continue;
                }
                WatchItem w = settingUp(setup, r, x, st, s, i, marketUp, strongSector);
                if (w != null && Math.abs(w.distancePct()) <= 5) out.add(w);
            }
        }
        out.sort(Comparator.comparing((WatchItem w) -> !w.triggered())
                .thenComparing(w -> !"Passed".equals(w.verdict()))
                .thenComparing(w -> w.setup().ordinal())
                .thenComparingDouble(w -> Math.abs(w.distancePct())));
        return out;
    }

    private static WatchItem item(Setup setup, SetupResult r, Series s, boolean triggered, String condition,
                                  double close, double trigger, Double stop, Double target) {
        int i = s.size() - 1;
        return new WatchItem(setup, r == null ? "" : r.verdict(), r == null ? "neutral" : r.tone(),
                r == null ? null : r.outOfSample().trades(), r == null ? null : r.outOfSample().winRate(),
                r == null ? null : r.outOfSample().avgNet(), s.symbol, s.name, s.sector, triggered, condition,
                close, trigger, (trigger / close - 1) * 100, stop, target, s.turnover[i]);
    }

    /** One step away from the rule: the condition and price that would trigger it at the next close. */
    private static WatchItem settingUp(Setup setup, SetupResult r, Ind x, Structure st, Series s, int i,
                                       boolean marketUp, boolean strongSector) {
        double c = s.close[i];
        String f = "%.2f";
        switch (setup) {
            case BB_UPTREND, BB_DRYUP, BB_SECTOR, BB_MARKET -> {
                if (Double.isNaN(x.lower[i]) || !(c < x.lower[i])) return null;
                String extra;
                if (setup == Setup.BB_UPTREND) {
                    if (!(ok(x.sma50[i], x.sma50[i - 5]) && x.sma50[i] > x.sma50[i - 5])) return null;
                    extra = " while staying above the 50-day average (" + String.format(f, x.sma50[i]) + ")";
                } else if (setup == Setup.BB_DRYUP) {
                    Double avgVol = Indicators.mean(s.volume, i - 19, i + 1, 10);
                    extra = avgVol == null ? "" : " on volume below " + String.format("%,.0f", avgVol) + " shares";
                } else if (setup == Setup.BB_SECTOR) {
                    if (!strongSector) return null;
                    extra = " (its sector is top-3 now)";
                } else {
                    if (!marketUp) return null;
                    extra = " while the market stays above its 20-session average";
                }
                double trig = x.lower[i];
                return item(setup, r, s, false, "Closed below the lower Bollinger band. Buy if the next session closes back above about "
                        + String.format(f, trig) + extra + ".", c, trig, trig * 0.92, null);
            }
            case RSI2_BB -> {
                if (!ok(x.rsi2[i], x.lower[i]) || x.rsi2[i] > 25 || c > x.lower[i] * 1.02) return null;
                return item(setup, r, s, false, String.format("RSI(2) %.0f, close near the lower band. Triggers on a close below about %.2f with RSI(2) under 10.",
                        x.rsi2[i], x.lower[i]), c, x.lower[i], x.lower[i] * 0.92, null);
            }
            case SMC_SWEEP, SWEEP_MARKET -> {
                if (setup == Setup.SWEEP_MARKET && !marketUp) return null;
                double low20 = Indicators.min(s.low, i - 19, i + 1);
                if (Double.isInfinite(low20) || c > low20 * 1.04) return null;
                return item(setup, r, s, false, String.format("Within 4%% of its 20-session low (%.2f). Buy if a session dips below it and closes back above it, in the upper half of its range, on 1.2× normal volume.", low20),
                        c, low20, low20 * 0.98, null);
            }
            case SMC_BOS_OB -> {
                for (int b = i; b >= Math.max(1, i - 9); b--) {
                    Integer sh = st.lastSwingHigh(b - 1);
                    if (sh == null || !(s.close[b] > s.high[sh] && s.close[b - 1] <= s.high[sh])) continue;
                    int ob = -1;
                    for (int k = b - 1; k >= Math.max(0, b - 10); k--) if (s.close[k] < s.open[k]) { ob = k; break; }
                    if (ob < 0) return null;
                    double hi = s.high[ob], lo = s.low[ob];
                    for (int k = b + 1; k <= i; k++) if (s.low[k] <= hi) return null;
                    double stop = lo * 0.99;
                    return item(setup, r, s, false, String.format("Broke structure on %s. Order block %.2f–%.2f not retested yet: buy if price dips into it and closes above %.2f.",
                            s.dates[b], lo, hi, lo), c, hi, stop, hi + 2 * (hi - stop));
                }
                return null;
            }
            case SMC_FVG -> {
                for (int k = i; k >= Math.max(2, i - 9); k--) {
                    double bottom = s.high[k - 2], top = s.low[k];
                    if (!(top > bottom * 1.01) || s.close[k - 1] / s.close[k - 2] - 1 < 0.03) continue;
                    for (int j = k + 1; j <= i; j++) if (s.low[j] <= top) return null;
                    double stop = bottom * 0.99;
                    return item(setup, r, s, false, String.format("Unfilled fair value gap %.2f–%.2f (from %s). Buy if price dips into it and closes above %.2f.",
                            bottom, top, s.dates[k - 1], bottom), c, top, stop, top + 2 * (top - stop));
                }
                return null;
            }
            case SMC_CHOCH -> {
                boolean down = (!Double.isNaN(x.ema50[i]) && c < x.ema50[i]) || c / s.close[i - 19] - 1 <= -0.08;
                Integer sh = st.lastSwingHigh(i), sl = st.lastSwingLow(i);
                if (!down || sh == null || sl == null || i - sh > 30 || c > s.high[sh]) return null;
                double level = s.high[sh], stop = s.low[sl] * 0.99;
                if (!(stop < level)) return null;
                return item(setup, r, s, false, String.format("In a downtrend. A close above the last swing high %.2f would be a change of character.", level),
                        c, level, stop, level + 2 * (level - stop));
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ setups

    private static Plan plan(Setup setup, Ind x, Structure st, Series s, int i, boolean marketUp, boolean strongSector) {
        ExitRule bbExit = (xx, ss, j) -> !Double.isNaN(xx.sma20[j]) && ss.close[j] >= xx.sma20[j];
        return switch (setup) {
            case SMC_SWEEP -> sweep(s, i);
            case SWEEP_MARKET -> marketUp ? sweep(s, i) : null;
            case SMC_BOS_OB -> orderBlockRetest(st, s, i);
            case SMC_FVG -> fvgRetest(s, i);
            case SMC_CHOCH -> choch(x, st, s, i);
            case BB_UPTREND -> bb(x, s, i) && ok(x.sma50[i], x.sma50[i - 5]) && s.close[i] > x.sma50[i] && x.sma50[i] > x.sma50[i - 5]
                    ? new Plan(null, 0.08, null, bbExit, 15) : null;
            case BB_DRYUP -> {
                Double avgVol = Indicators.mean(s.volume, i - 20, i, 10);
                yield bb(x, s, i) && avgVol != null && s.volume[i] < avgVol ? new Plan(null, 0.08, null, bbExit, 15) : null;
            }
            case RSI2_BB -> ok(x.rsi2[i], x.lower[i]) && x.rsi2[i] < 10 && s.close[i] < x.lower[i]
                    ? new Plan(null, 0.08, null, (xx, ss, j) -> !Double.isNaN(xx.sma5[j]) && ss.close[j] > xx.sma5[j], 10) : null;
            case BB_SECTOR -> bb(x, s, i) && strongSector ? new Plan(null, 0.08, null, bbExit, 15) : null;
            case BB_MARKET -> bb(x, s, i) && marketUp ? new Plan(null, 0.08, null, bbExit, 15) : null;
            case RANDOM -> null;
        };
    }

    private static boolean bb(Ind x, Series s, int i) {
        return ok(x.lower[i], x.lower[i - 1]) && s.close[i - 1] < x.lower[i - 1] && s.close[i] > x.lower[i];
    }

    /** Sell-side liquidity sweep: new 20-session low intraday, close reclaimed above it, upper half of the range, volume. */
    static Plan sweep(Series s, int i) {
        double prevLow = Indicators.min(s.low, i - 20, i);
        double range = s.high[i] - s.low[i];
        Double avgVol = Indicators.mean(s.volume, i - 20, i, 10);
        if (Double.isInfinite(prevLow) || range <= 0 || avgVol == null) return null;
        boolean swept = s.low[i] < prevLow && s.close[i] > prevLow;
        boolean strongClose = (s.close[i] - s.low[i]) / range >= 0.5;
        if (swept && strongClose && s.volume[i] >= 1.2 * avgVol) return new Plan(s.low[i] * 0.995, 0, 2.0, null, 15);
        return null;
    }

    /** Break of structure, then the first retest of the order block within 10 sessions. */
    static Plan orderBlockRetest(Structure st, Series s, int i) {
        for (int b = i - 1; b >= Math.max(1, i - 10); b--) {
            Integer sh = st.lastSwingHigh(b - 1);
            if (sh == null || !(s.close[b] > s.high[sh] && s.close[b - 1] <= s.high[sh])) continue; // BOS on day b
            int ob = -1;
            for (int k = b - 1; k >= Math.max(0, b - 10); k--) if (s.close[k] < s.open[k]) { ob = k; break; }
            if (ob < 0) return null;
            double obHigh = s.high[ob], obLow = s.low[ob];
            for (int k = b + 1; k < i; k++) if (s.low[k] <= obHigh) return null; // only the first retest counts
            if (s.low[i] <= obHigh && s.close[i] > obLow && s.close[i] > obHigh * 0.98) return new Plan(obLow * 0.99, 0, 2.0, null, 15);
            return null;
        }
        return null;
    }

    /** Bullish fair value gap from a displacement day, first retest within 10 sessions. */
    static Plan fvgRetest(Series s, int i) {
        for (int k = i - 1; k >= Math.max(2, i - 10); k--) {
            double gapBottom = s.high[k - 2], gapTop = s.low[k];
            boolean displacement = s.close[k - 1] / s.close[k - 2] - 1 >= 0.03;
            if (!(gapTop > gapBottom * 1.01) || !displacement) continue;
            for (int j = k + 1; j < i; j++) if (s.low[j] <= gapTop) return null; // already retested
            if (s.low[i] <= gapTop && s.close[i] > gapBottom) return new Plan(gapBottom * 0.99, 0, 2.0, null, 15);
            return null;
        }
        return null;
    }

    /** Change of character: in a downtrend, the close breaks above the last confirmed swing high. */
    static Plan choch(Ind x, Structure st, Series s, int i) {
        boolean downtrend = (!Double.isNaN(x.ema50[i - 1]) && s.close[i - 1] < x.ema50[i - 1])
                || s.close[i - 1] / s.close[i - 20] - 1 <= -0.08;
        Integer sh = st.lastSwingHigh(i - 1), sl = st.lastSwingLow(i - 1);
        if (!downtrend || sh == null || sl == null || i - sh > 30) return null;
        double level = s.high[sh];
        if (s.close[i] > level && s.close[i - 1] <= level && s.low[sl] < s.close[i]) return new Plan(s.low[sl] * 0.99, 0, 2.0, null, 20);
        return null;
    }

    private static boolean ok(double... v) {
        for (double d : v) if (Double.isNaN(d)) return false;
        return true;
    }

    // ------------------------------------------------------------------ simulation

    private static Trade simulate(Series s, Ind x, Setup setup, Plan p, int i, LocalDate splitDate) {
        int e = i + 1;
        if (e >= s.size()) return null;
        double entry = s.open[e] > 0 ? s.open[e] : s.close[i];
        double stop = p.stopPrice() != null ? p.stopPrice() : entry * (1 - p.stopPct());
        if (!(stop < entry)) return null; // opened below the stop: no trade
        Double target = p.rTarget() != null ? entry + p.rTarget() * (entry - stop) : null;
        for (int j = e; j < s.size(); j++) {
            int held = j - e + 1;
            if (target != null && s.high[j] >= target) {
                double px = s.open[j] >= target ? s.open[j] : target; // limit sell fills at the target (or a gap above it)
                return trade(setup, s, i, e, entry, j, px, held, "target", splitDate);
            }
            String why = null;
            if (s.close[j] <= stop) why = "stop";
            else if (p.exit() != null && p.exit().test(x, s, j)) why = "rule";
            else if (held >= p.maxHold()) why = "time";
            if (why == null) continue;
            if (j + 1 >= s.size()) return null; // would exit after the last stored session: still open
            double px = s.open[j + 1] > 0 ? s.open[j + 1] : s.close[j];
            return trade(setup, s, i, e, entry, j + 1, px, held, why, splitDate);
        }
        return null;
    }

    private static Trade trade(Setup setup, Series s, int i, int e, double entry, int exitIdx, double exitPx, int held,
                               String why, LocalDate splitDate) {
        double net = (exitPx / entry - 1) * 100 - BacktestService.COST_PCT;
        return new Trade(setup, s.symbol, s.dates[i], s.dates[e], entry, s.dates[exitIdx], exitPx, held, why, net,
                !s.dates[i].isBefore(splitDate));
    }

    private static SetupResult summarise(Setup setup, List<Trade> list, List<String> today, Stats random) {
        Stats all = Stats.of(list);
        Stats design = Stats.of(list.stream().filter(t -> !t.outOfSample()).toList());
        Stats out = Stats.of(list.stream().filter(Trade::outOfSample).toList());
        Map<String, Long> reasons = new HashMap<>();
        list.forEach(t -> reasons.merge(t.exitReason(), 1L, Long::sum));
        String verdict, tone;
        if (setup == Setup.RANDOM) { verdict = "Benchmark"; tone = "neutral"; }
        else if (all.trades() < MIN_TRADES || out.trades() < MIN_TRADES_OUT) { verdict = "Too few trades"; tone = "neutral"; }
        else if (design.winRate() > 50 && design.avgNet() > 0 && out.winRate() > 50 && out.avgNet() > 0 && all.avgNet() > random.avgNet()) {
            verdict = "Passed"; tone = "good";
        } else if (design.avgNet() > 0 && out.avgNet() <= 0) { verdict = "Worked in design, failed out-of-sample"; tone = "bad"; }
        else if (all.avgNet() > random.avgNet()) { verdict = "Beats random, still not profitable"; tone = "warn"; }
        else { verdict = "No edge"; tone = "bad"; }
        return new SetupResult(setup, all, design, out, list.stream().mapToInt(Trade::held).average().orElse(0), reasons,
                verdict, tone, list.stream().sorted(Comparator.comparing(Trade::signal).reversed()).limit(10).toList(), today);
    }

    private void save(Result r, Map<Setup, List<Trade>> trades) {
        List<BacktestRepository.SummaryRow> sums = r.setups().stream().map(x -> new BacktestRepository.SummaryRow(
                x.setup().name(), x.setup().getTitle(), x.all().trades(), x.all().winRate(), x.all().avgNet(), x.all().medianNet(),
                null, x.design().avgNet(), x.outOfSample().avgNet(), x.all().profitFactor(), x.verdict())).toList();
        List<BacktestRepository.TradeRow> rows = new ArrayList<>();
        trades.forEach((setup, list) -> list.forEach(t -> rows.add(new BacktestRepository.TradeRow(setup.name(), t.symbol(), t.signal(),
                t.entryDate(), t.entry(), t.exitDate(), t.exit(), t.held(), t.exitReason(), t.netPct(), null))));
        store.saveIfNew(new BacktestRepository.RunKey("ADVANCED", r.from(), r.to(), r.sessions(), null, false),
                BacktestService.COST_PCT, null, sums, rows);
    }

    // ------------------------------------------------------------------ market structure

    /** Confirmed swing points: a swing high at k needs two lower highs on each side, so it is known only at k + 2. */
    static final class Structure {
        private final int[] lastHigh, lastLow; // latest swing index confirmed by day d (or -1)

        Structure(Series s) {
            int n = s.size();
            lastHigh = new int[n];
            lastLow = new int[n];
            int h = -1, l = -1;
            for (int d = 0; d < n; d++) {
                int k = d - 2;
                if (k >= 2) {
                    if (s.high[k] > s.high[k - 1] && s.high[k] > s.high[k - 2] && s.high[k] > s.high[k + 1] && s.high[k] > s.high[k + 2]) h = k;
                    if (s.low[k] < s.low[k - 1] && s.low[k] < s.low[k - 2] && s.low[k] < s.low[k + 1] && s.low[k] < s.low[k + 2]) l = k;
                }
                lastHigh[d] = h;
                lastLow[d] = l;
            }
        }

        Integer lastSwingHigh(int d) { return d < 0 || lastHigh[d] < 0 ? null : lastHigh[d]; }
        Integer lastSwingLow(int d) { return d < 0 || lastLow[d] < 0 ? null : lastLow[d]; }
    }

    // ------------------------------------------------------------------ sector strength (20-session return rank, known on each day)

    private static Map<String, Integer> sectorIndex(Market m) {
        Map<String, Integer> idx = new HashMap<>();
        for (Series s : m.series.values()) if (s.sector != null && Sectors.isEquity(s.sector)) idx.putIfAbsent(s.sector, idx.size());
        return idx;
    }

    /** rank[t][sector] = 1 for the best 20-session return on day t, 0 when unknown. */
    private static int[][] sectorRanks(Market m) {
        Map<String, Integer> idx = sectorIndex(m);
        int T = m.dates.length, S = idx.size();
        double[][] sum = new double[T][S];
        int[][] cnt = new int[T][S];
        for (Series s : m.series.values()) {
            Integer k = s.sector == null ? null : idx.get(s.sector);
            if (k == null) continue;
            for (int i = 0; i < s.size(); i++) {
                double c = s.change[i];
                if (!Double.isNaN(c) && Math.abs(c) <= 10.5) { sum[s.globalIndex[i]][k] += c; cnt[s.globalIndex[i]][k]++; }
            }
        }
        double[][] level = new double[T][S];
        for (int k = 0; k < S; k++) {
            double lv = 100;
            for (int t = 0; t < T; t++) {
                if (cnt[t][k] > 0) lv *= 1 + sum[t][k] / cnt[t][k] / 100;
                level[t][k] = lv;
            }
        }
        int[][] rank = new int[T][S];
        for (int t = 20; t < T; t++) {
            final int tt = t;
            Integer[] order = new Integer[S];
            for (int k = 0; k < S; k++) order[k] = k;
            Arrays.sort(order, Comparator.comparingDouble((Integer k) -> -(level[tt][k] / level[tt - 20][k])));
            for (int r = 0; r < S; r++) rank[t][order[r]] = r + 1;
        }
        return rank;
    }
}
