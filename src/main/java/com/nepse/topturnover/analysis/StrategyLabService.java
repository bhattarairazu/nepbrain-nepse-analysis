package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;
import com.nepse.topturnover.journal.TradeCosts;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Portfolio-level backtests of two textbook strategies with fixed, untuned parameters, run as a real account:
 * starting capital, position limits, NEPSE charges and capital gains tax, T+2 settlement (nothing sold before T+3),
 * orders decided at the close and executed at the next open, bonus/right adjusted prices.
 *
 * A: Trend pullback (EMA 20/50 + MACD 12/26/9 + RSI 14), event-driven, max 5 positions.
 * B: Momentum rotation: every 20 sessions hold the 5 strongest stocks by 3-month return, cash when the market is weak.
 */
@Service
public class StrategyLabService {
    public static final double CAPITAL = 3_000_000;
    static final int MAX_POSITIONS = 5;
    static final int REBALANCE = 20;
    static final int WARMUP = 70;

    public enum Lab {
        TREND_PULLBACK("A · Trend pullback (EMA + MACD + RSI)",
                "Market above its 50-day EMA. Stock above a rising EMA 50, EMA 20 above EMA 50, MACD above signal and above 0, RSI(14) 45–70, "
                + "Rs 1 Cr+ average turnover. Buy when price dips to EMA 20 and holds (strongest 3-month performers first), next open, skip gap-ups > 3%. "
                + "Exit from T+3: close below EMA 50, trailing stop 3× ATR below the highest close, or the market closes below its 50-day EMA. 5 positions × 20%."),
        MOMENTUM_ROTATION("B · Momentum rotation (monthly)",
                "Every 20 sessions, if the market is above its 50-day EMA: hold the 5 liquid stocks with the best 3-month return (skipping the last week) "
                + "that are above EMA 50 with MACD above 0, equal weight. Otherwise cash. Emergency exit if a holding closes 15% below its entry."),
        BUY_HOLD("Benchmark · buy and hold the market", "Equal-weight index of all ordinary shares, bought at the start and held.");

        private final String title, rule;

        Lab(String title, String rule) {
            this.title = title;
            this.rule = rule;
        }

        public String getTitle() { return title; }
        public String getRule() { return rule; }
    }

    public record LabTrade(String symbol, LocalDate signal, LocalDate entryDate, double entry, LocalDate exitDate, double exit,
                           int held, String reason, double amount, double netPnl, double netPct) {}

    public record Holding(String symbol, LocalDate since, double entry, double lastClose, double changePct) {}

    public record LabResult(Lab lab, double finalValue, double returnPct, double maxDrawdownPct, int trades, double winRate,
                            double exposurePct, double designReturnPct, double outOfSampleReturnPct, double charges, double tax,
                            List<Double> equity, List<LabTrade> recentTrades, List<Holding> holdings, List<String> buySignalsToday) {}

    public record Result(LocalDate from, LocalDate split, LocalDate to, int sessions, List<LocalDate> dates, List<LabResult> labs) {}

    private final BacktestService backtest;
    private final TradeCosts costs;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;

    public StrategyLabService(BacktestService backtest, TradeCosts costs, BacktestRepository store) {
        this.backtest = backtest;
        this.costs = costs;
        this.store = store;
    }

    public synchronized void evict() {
        cached = null;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < WARMUP + 40) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor)) return Optional.of(cached);

        Ctx c = new Ctx(m);
        int start = WARMUP, T = m.dates.length;
        int split = start + (int) Math.round((T - start) * AdvancedStrategyService.DESIGN_SHARE);
        List<LocalDate> dates = new ArrayList<>();
        for (int t = start; t < T; t++) dates.add(m.dates[t]);

        List<LabResult> labs = new ArrayList<>();
        labs.add(simulate(c, Lab.TREND_PULLBACK, start, split));
        labs.add(simulate(c, Lab.MOMENTUM_ROTATION, start, split));
        labs.add(buyHold(c, start, split));
        cached = new Result(m.dates[start], m.dates[split], last, T - start, dates, labs);
        cachedFor = last;
        save(cached);
        return Optional.of(cached);
    }

    // ------------------------------------------------------------------ shared market context

    /** Indicator arrays per stock and the market regime, computed once (each value uses only data up to that day). */
    static final class Ctx {
        final Market m;
        final Map<String, Ind> ind = new HashMap<>();
        final boolean[] marketUp;

        Ctx(Market m) {
            this.m = m;
            for (Series s : m.series.values()) if (Sectors.isEquity(s.sector) && s.size() >= 60) ind.put(s.symbol, new Ind(s.close, s.high, s.low));
            double[] ema = Ind.ema(m.index, 50);
            marketUp = new boolean[m.index.length];
            for (int t = 0; t < m.index.length; t++) marketUp[t] = !Double.isNaN(ema[t]) && m.index[t] > ema[t];
        }

        double avgTurnover(Series s, int i) {
            Double a = Indicators.mean(s.turnover, i - 19, i + 1, 10);
            return a == null ? 0 : a;
        }

        double ret63(Series s, int i) {
            return i >= 68 && s.close[i - 68] > 0 ? s.close[i - 5] / s.close[i - 68] - 1 : Double.NaN;
        }
    }

    private static boolean ok(double... v) {
        for (double d : v) if (Double.isNaN(d)) return false;
        return true;
    }

    /** Strategy A entry, known at the close of day i. */
    static boolean pullbackEntry(Ctx c, Series s, Ind x, int i) {
        if (i < 60 || !ok(x.ema20[i], x.ema50[i], x.ema50[i - 5], x.macd[i], x.signal[i], x.rsi14[i])) return false;
        if (c.avgTurnover(s, i) < BacktestService.MIN_TURNOVER) return false;
        double cl = s.close[i];
        boolean trend = cl > x.ema50[i] && x.ema20[i] > x.ema50[i] && x.ema50[i] > x.ema50[i - 5];
        boolean momentum = x.macd[i] > x.signal[i] && x.macd[i] > 0 && x.rsi14[i] >= 45 && x.rsi14[i] <= 70;
        boolean pullback = s.low[i] <= x.ema20[i] * 1.02 && cl >= x.ema20[i];
        return trend && momentum && pullback;
    }

    /** Strategy B eligibility, known at the close of day i. */
    static boolean rotationEligible(Ctx c, Series s, Ind x, int i) {
        if (i < 68 || !ok(x.ema50[i], x.macd[i], x.rsi14[i])) return false;
        return c.avgTurnover(s, i) >= BacktestService.MIN_TURNOVER && s.close[i] > x.ema50[i] && x.macd[i] > 0 && x.rsi14[i] < 80
                && !Double.isNaN(c.ret63(s, i));
    }

    // ------------------------------------------------------------------ account simulation

    private static final class Position {
        final Series s;
        final int entryT;
        final LocalDate signal;
        final double entry, qty, cost, atr;
        double highest;

        Position(Series s, int entryT, LocalDate signal, double entry, double qty, double cost, double atr) {
            this.s = s; this.entryT = entryT; this.signal = signal; this.entry = entry; this.qty = qty; this.cost = cost; this.atr = atr;
            this.highest = entry;
        }
    }

    private LabResult simulate(Ctx c, Lab lab, int start, int split) {
        Market m = c.m;
        int T = m.dates.length;
        double cash = CAPITAL, charges = 0, tax = 0;
        Map<String, Position> pos = new LinkedHashMap<>();
        Map<String, String> sellOrders = new LinkedHashMap<>();  // symbol -> reason, executed at the next open
        List<Object[]> buyOrders = new ArrayList<>();            // {Series, signal day index i, signal close}
        List<LabTrade> trades = new ArrayList<>();
        List<Double> equity = new ArrayList<>();
        int invested = 0;
        double designEnd = CAPITAL;

        for (int t = start; t < T; t++) {
            // ---- 1. execute yesterday's orders at today's open (sells first, then buys)
            for (var it = sellOrders.entrySet().iterator(); it.hasNext(); ) {
                var o = it.next();
                Position p = pos.get(o.getKey());
                int i = p == null ? -1 : p.s.indexOf(t);
                if (p == null) { it.remove(); continue; }
                if (i < 0 || t - p.entryT < BacktestService.EARLIEST_SALE) continue; // not traded today / still locked by T+2
                double px = p.s.open[i] > 0 ? p.s.open[i] : p.s.close[i];
                double gross = px * p.qty, sc = costs.sellCharges(gross), gain = gross - sc - p.cost;
                double tx = gain > 0 ? gain * costs.cgtShortPct() / 100 : 0;
                cash += gross - sc - tx;
                charges += sc;
                tax += tx;
                trades.add(new LabTrade(p.s.symbol, p.signal, m.dates[p.entryT], p.entry, m.dates[t], px, t - p.entryT, o.getValue(),
                        p.cost, gain - tx, (gain - tx) / p.cost * 100));
                pos.remove(o.getKey());
                it.remove();
            }
            for (Object[] o : buyOrders) {
                Series s = (Series) o[0];
                int i = s.indexOf(t);
                if (i < 0 || pos.containsKey(s.symbol) || pos.size() >= MAX_POSITIONS) continue;
                double px = s.open[i] > 0 ? s.open[i] : s.close[i];
                if (px > (double) o[2] * 1.03) continue; // gapped up: don't chase
                double equityNow = cash + marketValue(pos, t);
                double amount = Math.min(equityNow / MAX_POSITIONS, cash / (1 + 0.005));
                if (amount < equityNow / MAX_POSITIONS * 0.5) continue;
                double bc = costs.buyCharges(amount);
                cash -= amount + bc;
                charges += bc;
                double atr = AdvancedStrategyService.atrPct(s, i - 1 >= 1 ? i - 1 : i) * px;
                pos.put(s.symbol, new Position(s, t, m.dates[(int) o[1]], px, amount / px, amount + bc, atr));
            }
            buyOrders.clear();

            // ---- 2. decisions at today's close
            boolean up = c.marketUp[t];
            for (Position p : pos.values()) {
                int i = p.s.indexOf(t);
                if (i < 0) continue;
                double cl = p.s.close[i];
                p.highest = Math.max(p.highest, cl);
                Ind x = c.ind.get(p.s.symbol);
                String why = null;
                if (lab == Lab.TREND_PULLBACK) {
                    if (!up) why = "market below its 50-day EMA";
                    else if (x != null && ok(x.ema50[i]) && cl < x.ema50[i]) why = "close below EMA 50";
                    else if (cl < p.highest - 3 * p.atr) why = "trailing stop (3× ATR)";
                } else if (cl < p.entry * 0.85) {
                    why = "emergency stop (−15%)";
                }
                if (why != null) sellOrders.putIfAbsent(p.s.symbol, why);
            }
            boolean rebalanceDay = (t - start) % REBALANCE == 0;
            if (lab == Lab.MOMENTUM_ROTATION && rebalanceDay) {
                List<Series> target = new ArrayList<>();
                if (up) {
                    record Cand(Series s, double score) {}
                    List<Cand> cands = new ArrayList<>();
                    for (Series s : m.series.values()) {
                        Ind x = c.ind.get(s.symbol);
                        int i = s.indexOf(t);
                        if (x == null || i < 0 || !rotationEligible(c, s, x, i)) continue;
                        cands.add(new Cand(s, c.ret63(s, i)));
                    }
                    cands.sort(Comparator.comparingDouble(Cand::score).reversed());
                    for (int k = 0; k < Math.min(MAX_POSITIONS, cands.size()); k++) target.add(cands.get(k).s());
                }
                List<String> keep = target.stream().map(s -> s.symbol).toList();
                for (Position p : pos.values()) if (!keep.contains(p.s.symbol)) sellOrders.putIfAbsent(p.s.symbol, up ? "rotated out" : "market below its 50-day EMA");
                for (Series s : target) {
                    if (!pos.containsKey(s.symbol)) buyOrders.add(new Object[] {s, s.indexOf(t), s.close[s.indexOf(t)]});
                }
            }
            if (lab == Lab.TREND_PULLBACK && up && pos.size() - sellOrders.size() < MAX_POSITIONS) {
                record Cand(Series s, int i, double score) {}
                List<Cand> cands = new ArrayList<>();
                for (Series s : m.series.values()) {
                    Ind x = c.ind.get(s.symbol);
                    int i = s.indexOf(t);
                    if (x == null || i < 0 || pos.containsKey(s.symbol) || !pullbackEntry(c, s, x, i)) continue;
                    double r = c.ret63(s, i);
                    cands.add(new Cand(s, i, Double.isNaN(r) ? -1 : r));
                }
                cands.sort(Comparator.comparingDouble(Cand::score).reversed());
                for (Cand k : cands) buyOrders.add(new Object[] {k.s(), k.i(), k.s().close[k.i()]});
            }

            // ---- 3. mark to market
            double eq = cash + marketValue(pos, t);
            equity.add(eq);
            if (!pos.isEmpty()) invested++;
            if (t == split) designEnd = eq;
        }

        // value open positions at the last close (before selling charges and tax)
        List<Holding> holdings = new ArrayList<>();
        for (Position p : pos.values()) {
            double lc = lastClose(p.s, T - 1);
            holdings.add(new Holding(p.s.symbol, m.dates[p.entryT], p.entry, lc, (lc / p.entry - 1) * 100));
        }
        List<String> signalsToday = buyOrders.stream().map(o -> ((Series) o[0]).symbol).distinct().limit(10).toList();
        double end = equity.get(equity.size() - 1);
        long wins = trades.stream().filter(x -> x.netPnl() > 0).count();
        return new LabResult(lab, end, (end / CAPITAL - 1) * 100, maxDrawdown(equity), trades.size(),
                trades.isEmpty() ? 0 : 100.0 * wins / trades.size(), 100.0 * invested / equity.size(),
                (designEnd / CAPITAL - 1) * 100, (end / designEnd - 1) * 100, charges, tax, equity,
                trades.stream().sorted(Comparator.comparing(LabTrade::entryDate).reversed()).toList(), holdings, signalsToday);
    }

    private LabResult buyHold(Ctx c, int start, int split) {
        double[] idx = c.m.index;
        List<Double> equity = new ArrayList<>();
        for (int t = start; t < idx.length; t++) equity.add(CAPITAL * idx[t] / idx[start]);
        double end = equity.get(equity.size() - 1), mid = CAPITAL * idx[split] / idx[start];
        return new LabResult(Lab.BUY_HOLD, end, (end / CAPITAL - 1) * 100, maxDrawdown(equity), 0, 0, 100,
                (mid / CAPITAL - 1) * 100, (end / mid - 1) * 100, 0, 0, equity, List.of(), List.of(), List.of());
    }

    private static double marketValue(Map<String, Position> pos, int t) {
        double v = 0;
        for (Position p : pos.values()) v += p.qty * lastClose(p.s, t);
        return v;
    }

    private static double lastClose(Series s, int t) {
        for (int k = t; k >= 0; k--) {
            int i = s.indexOf(k);
            if (i >= 0) return s.close[i];
        }
        return s.close[0];
    }

    private static double maxDrawdown(List<Double> eq) {
        double peak = eq.isEmpty() ? 0 : eq.get(0), dd = 0;
        for (double v : eq) { peak = Math.max(peak, v); dd = Math.min(dd, v / peak - 1); }
        return dd * 100;
    }

    private void save(Result r) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        List<BacktestRepository.TradeRow> rows = new ArrayList<>();
        for (LabResult l : r.labs()) {
            sums.add(new BacktestRepository.SummaryRow(l.lab().name(), l.lab().getTitle(), l.trades(), l.winRate(), l.returnPct(), null,
                    null, l.designReturnPct(), l.outOfSampleReturnPct(), null,
                    String.format(java.util.Locale.ROOT, "Rs %.0f final, max drawdown %.1f%%", l.finalValue(), l.maxDrawdownPct())));
            for (LabTrade t : l.recentTrades()) {
                rows.add(new BacktestRepository.TradeRow(l.lab().name(), t.symbol(), t.signal(), t.entryDate(), t.entry(), t.exitDate(),
                        t.exit(), t.held(), t.reason().length() > 10 ? t.reason().substring(0, 10) : t.reason(), t.netPct(), null));
            }
        }
        store.saveIfNew(new BacktestRepository.RunKey("LAB", r.from(), r.to(), r.sessions(), null, false),
                BacktestService.COST_PCT, null, sums, rows);
    }
}
