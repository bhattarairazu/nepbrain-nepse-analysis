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
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Textbook trading rules backtested with their own exit rules on every stock:
 * signal after the close of day i, buy at the open of i+1, exit at the open after the exit condition,
 * the stop-loss or the maximum holding period, minus round-trip costs. Only ordinary shares with Rs 1 Cr+ traded
 * on the signal day. Indicators use only data up to the signal day.
 */
@Service
public class StandardStrategyService {

    public enum Rule {
        RSI2_PULLBACK("RSI(2) pullback (Connors)", "Mean reversion",
                "Buy: RSI(2) < 10 while the close is above its 50-day average. Sell: close above its 5-day average.", 0.08, 10),
        RSI14_OVERSOLD("RSI(14) oversold bounce", "Mean reversion",
                "Buy: RSI(14) crosses back above 30. Sell: RSI(14) above 55.", 0.08, 15),
        BOLLINGER_BOUNCE("Bollinger Band bounce", "Mean reversion",
                "Buy: yesterday closed below the lower band (20, 2σ), today closes back inside. Sell: close at or above the 20-day average.", 0.08, 15),
        EMA_CROSS("EMA 20/50 golden cross", "Trend following",
                "Buy: 20-day EMA crosses above the 50-day EMA. Sell: it crosses back below.", 0.10, 40),
        MACD_CROSS("MACD signal-line cross", "Momentum",
                "Buy: MACD (12, 26) crosses above its 9-day signal line. Sell: it crosses back below.", 0.08, 25),
        DONCHIAN_20("Donchian 20-day breakout (Turtle)", "Trend following",
                "Buy: close above the highest high of the previous 20 sessions. Sell: close below the lowest low of the previous 10.", 0.08, 30),
        RANDOM("Benchmark: random entry", "Benchmark",
                "Buys a random liquid stock-day (same number as the RSI(2) rule) and holds 5 sessions. A rule must beat this to have an edge.", 0.08, 5);

        private final String title, style, description;
        private final double stop;
        private final int maxHold;

        Rule(String title, String style, String description, double stop, int maxHold) {
            this.title = title;
            this.style = style;
            this.description = description;
            this.stop = stop;
            this.maxHold = maxHold;
        }

        public String getTitle() { return title; }
        public String getStyle() { return style; }
        public String getDescription() { return description; }
        public double getStop() { return stop; }
        public int getMaxHold() { return maxHold; }
    }

    public record Trade(Rule rule, String symbol, LocalDate signal, LocalDate entryDate, double entry, LocalDate exitDate,
                        double exit, int held, String exitReason, double netPct, double marketPct) {}

    public record RuleResult(Rule rule, int trades, double winRate, double avgNet, double medianNet, double avgWin,
                             double avgLoss, double avgHeld, double avgVsMarket, Double firstHalf, Double secondHalf,
                             double profitFactor, double totalCompound, String verdict, String tone,
                             List<Trade> recent, List<String> signalsToday) {}

    public record Result(LocalDate from, LocalDate to, int sessions, double marketBuyHoldPct, List<RuleResult> rules) {}

    private final BacktestService backtest;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;
    private Object cachedData; // the price data the result was built from; new history reloads it

    public StandardStrategyService(BacktestService backtest, BacktestRepository store) {
        this.backtest = backtest;
        this.store = store;
    }

    public synchronized void evict() {
        cached = null;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < 60) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor) && m == cachedData) return Optional.of(cached);

        Map<Rule, List<Trade>> trades = new HashMap<>();
        Map<Rule, List<String>> today = new HashMap<>();
        for (Rule r : Rule.values()) { trades.put(r, new ArrayList<>()); today.put(r, new ArrayList<>()); }
        List<int[]> liquidDays = new ArrayList<>(); // {series#, index} for the random benchmark
        List<Series> all = new ArrayList<>(m.series.values());

        for (int sn = 0; sn < all.size(); sn++) {
            Series s = all.get(sn);
            if (!Sectors.isEquity(s.sector) || s.size() < 30) continue;
            Ind ind = new Ind(s.close, s.high, s.low);
            for (int i = 1; i < s.size(); i++) {
                if (!(s.turnover[i] >= BacktestService.MIN_TURNOVER) || Double.isNaN(s.close[i])) continue;
                liquidDays.add(new int[] {sn, i});
                for (Rule r : Rule.values()) {
                    if (r == Rule.RANDOM || !entry(r, ind, s, i)) continue;
                    if (i == s.size() - 1) today.get(r).add(s.symbol);
                    Trade t = simulate(m, s, ind, r, i);
                    if (t != null) trades.get(r).add(t);
                }
            }
        }
        // random benchmark: same count as the RSI(2) rule (at least 200), fixed seed so results are repeatable
        Random rnd = new Random(42);
        int want = Math.max(200, trades.get(Rule.RSI2_PULLBACK).size());
        for (int k = 0; k < want * 3 && trades.get(Rule.RANDOM).size() < want && !liquidDays.isEmpty(); k++) {
            int[] pick = liquidDays.get(rnd.nextInt(liquidDays.size()));
            Series s = all.get(pick[0]);
            Trade t = simulate(m, s, new Ind(s.close, s.high, s.low), Rule.RANDOM, pick[1]);
            if (t != null) trades.get(Rule.RANDOM).add(t);
        }

        LocalDate mid = m.dates[m.dates.length / 2];
        List<RuleResult> results = new ArrayList<>();
        for (Rule r : Rule.values()) results.add(summarise(r, trades.get(r), today.get(r), mid));
        double bh = (m.index[m.index.length - 1] / m.index[0] - 1) * 100;
        cached = new Result(m.dates[0], last, m.dates.length, bh, results);
        cachedFor = last;
        cachedData = m;
        List<BacktestRepository.TradeRow> rows = new ArrayList<>();
        trades.forEach((rule, list) -> list.forEach(t -> rows.add(new BacktestRepository.TradeRow(rule.name(), t.symbol(), t.signal(),
                t.entryDate(), t.entry(), t.exitDate(), t.exit(), t.held(), t.exitReason(), t.netPct(), t.marketPct()))));
        store.saveIfNew(new BacktestRepository.RunKey("STANDARD", m.dates[0], last, m.dates.length, null, false),
                BacktestService.COST_PCT, bh,
                results.stream().map(x -> new BacktestRepository.SummaryRow(x.rule().name(), x.rule().getTitle(), x.trades(),
                        x.winRate(), x.avgNet(), x.medianNet(), x.avgVsMarket(), x.firstHalf(), x.secondHalf(), x.profitFactor(),
                        x.verdict())).toList(), rows);
        return Optional.of(cached);
    }

    // ------------------------------------------------------------------ rules

    static boolean entry(Rule r, Ind x, Series s, int i) {
        double c = s.close[i];
        return switch (r) {
            case RSI2_PULLBACK -> ok(x.rsi2[i], x.sma50[i]) && x.rsi2[i] < 10 && c > x.sma50[i];
            case RSI14_OVERSOLD -> ok(x.rsi14[i], x.rsi14[i - 1]) && x.rsi14[i - 1] < 30 && x.rsi14[i] >= 30;
            case BOLLINGER_BOUNCE -> ok(x.lower[i], x.lower[i - 1]) && s.close[i - 1] < x.lower[i - 1] && c > x.lower[i];
            case EMA_CROSS -> ok(x.ema20[i], x.ema50[i], x.ema20[i - 1], x.ema50[i - 1])
                    && x.ema20[i - 1] <= x.ema50[i - 1] && x.ema20[i] > x.ema50[i];
            case MACD_CROSS -> ok(x.macd[i], x.signal[i], x.macd[i - 1], x.signal[i - 1])
                    && x.macd[i - 1] <= x.signal[i - 1] && x.macd[i] > x.signal[i];
            case DONCHIAN_20 -> i >= 20 && c > Indicators.max(s.high, i - 20, i);
            case RANDOM -> false;
        };
    }

    /** True when the exit condition is met at the close of day j. */
    static boolean exit(Rule r, Ind x, Series s, int j) {
        double c = s.close[j];
        return switch (r) {
            case RSI2_PULLBACK -> ok(x.sma5[j]) && c > x.sma5[j];
            case RSI14_OVERSOLD -> ok(x.rsi14[j]) && x.rsi14[j] > 55;
            case BOLLINGER_BOUNCE -> ok(x.sma20[j]) && c >= x.sma20[j];
            case EMA_CROSS -> ok(x.ema20[j], x.ema50[j]) && x.ema20[j] < x.ema50[j];
            case MACD_CROSS -> ok(x.macd[j], x.signal[j]) && x.macd[j] < x.signal[j];
            case DONCHIAN_20 -> j >= 10 && c < Indicators.min(s.low, j - 10, j);
            case RANDOM -> false;
        };
    }

    /**
     * Entry at the open of i+1 (session T); exit at the open after the exit/stop condition or after maxHold sessions,
     * never before T+3 (T+2 settlement).
     */
    private static Trade simulate(Market m, Series s, Ind x, Rule r, int i) {
        int e = i + 1;
        if (e >= s.size()) return null;
        double entry = s.open[e] > 0 ? s.open[e] : s.close[i];
        for (int j = e; j < s.size(); j++) {
            int held = j - e + 1;
            if (j + 1 < e + BacktestService.EARLIEST_SALE) continue; // T+2: shares can't be sold before session T+3
            String why = null;
            if (s.close[j] <= entry * (1 - r.getStop())) why = "stop";
            else if (exit(r, x, s, j)) why = "rule";
            else if (held >= r.getMaxHold()) why = "time";
            if (why == null) continue;
            if (j + 1 >= s.size()) return null; // exit would happen after the last stored session
            double exitPx = s.open[j + 1] > 0 ? s.open[j + 1] : s.close[j];
            double net = (exitPx / entry - 1) * 100 - BacktestService.COST_PCT;
            double mkt = (m.index[s.globalIndex[j]] / m.index[s.globalIndex[i]] - 1) * 100;
            return new Trade(r, s.symbol, s.dates[i], s.dates[e], entry, s.dates[j + 1], exitPx, held, why, net, mkt);
        }
        return null; // still open
    }

    private static RuleResult summarise(Rule r, List<Trade> list, List<String> today, LocalDate mid) {
        int n = list.size();
        if (n == 0) return new RuleResult(r, 0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, "No trades", "neutral", List.of(), today);
        double[] net = list.stream().mapToDouble(Trade::netPct).sorted().toArray();
        List<Trade> wins = list.stream().filter(t -> t.netPct() > 0).toList();
        List<Trade> losses = list.stream().filter(t -> t.netPct() <= 0).toList();
        double gain = wins.stream().mapToDouble(Trade::netPct).sum(), loss = -losses.stream().mapToDouble(Trade::netPct).sum();
        double win = 100.0 * wins.size() / n, avg = Arrays.stream(net).average().orElse(0);
        double vsMkt = list.stream().mapToDouble(t -> t.netPct() - t.marketPct()).average().orElse(0);
        Double h1 = avgOrNull(list.stream().filter(t -> t.signal().isBefore(mid)).toList());
        Double h2 = avgOrNull(list.stream().filter(t -> !t.signal().isBefore(mid)).toList());
        double compound = 1;
        for (Trade t : list.stream().sorted(Comparator.comparing(Trade::signal)).toList()) compound *= 1 + t.netPct() / 100 * 0.1; // 10% of capital per trade
        String verdict, tone;
        if (n < 30) { verdict = "Too few trades"; tone = "neutral"; }
        else if (win > 50 && avg > 0 && net[n / 2] > 0 && h1 != null && h1 > 0 && h2 != null && h2 > 0) { verdict = "Profitable and consistent"; tone = "good"; }
        else if (avg > 0) { verdict = "Profitable on average, not consistent"; tone = "warn"; }
        else if (win > 50) { verdict = "Wins often but loses money"; tone = "warn"; }
        else { verdict = "Loses money"; tone = "bad"; }
        return new RuleResult(r, n, win, avg, net[n / 2],
                wins.stream().mapToDouble(Trade::netPct).average().orElse(0),
                losses.stream().mapToDouble(Trade::netPct).average().orElse(0),
                list.stream().mapToInt(Trade::held).average().orElse(0), vsMkt, h1, h2,
                loss > 0 ? gain / loss : Double.POSITIVE_INFINITY, (compound - 1) * 100, verdict, tone,
                list.stream().sorted(Comparator.comparing(Trade::signal).reversed()).limit(10).toList(), today);
    }

    private static Double avgOrNull(List<Trade> l) {
        return l.isEmpty() ? null : l.stream().mapToDouble(Trade::netPct).average().orElse(0);
    }

    private static boolean ok(double... v) {
        for (double d : v) if (Double.isNaN(d)) return false;
        return true;
    }

    // ------------------------------------------------------------------ indicator arrays (each value uses only data up to that day)

    static final class Ind {
        final double[] sma5, sma20, sma50, ema20, ema50, rsi2, rsi14, lower, macd, signal;

        Ind(double[] c, double[] h, double[] l) {
            sma5 = sma(c, 5); sma20 = sma(c, 20); sma50 = sma(c, 50);
            ema20 = ema(c, 20); ema50 = ema(c, 50);
            rsi2 = rsi(c, 2); rsi14 = rsi(c, 14);
            lower = new double[c.length];
            for (int i = 0; i < c.length; i++) {
                if (Double.isNaN(sma20[i])) { lower[i] = Double.NaN; continue; }
                double v = 0;
                for (int k = i - 19; k <= i; k++) v += (c[k] - sma20[i]) * (c[k] - sma20[i]);
                lower[i] = sma20[i] - 2 * Math.sqrt(v / 20);
            }
            double[] e12 = ema(c, 12), e26 = ema(c, 26);
            macd = new double[c.length];
            for (int i = 0; i < c.length; i++) macd[i] = Double.isNaN(e26[i]) ? Double.NaN : e12[i] - e26[i];
            signal = emaSkippingNaN(macd, 9);
        }

        static double[] sma(double[] c, int n) {
            double[] o = new double[c.length];
            double s = 0;
            for (int i = 0; i < c.length; i++) {
                s += c[i];
                if (i >= n) s -= c[i - n];
                o[i] = i >= n - 1 ? s / n : Double.NaN;
            }
            return o;
        }

        static double[] ema(double[] c, int n) {
            double[] o = new double[c.length];
            double k = 2.0 / (n + 1), e = 0;
            for (int i = 0; i < c.length; i++) {
                if (i < n - 1) { e += c[i]; o[i] = Double.NaN; continue; }
                if (i == n - 1) { e = (e + c[i]) / n; o[i] = e; continue; }
                e = c[i] * k + e * (1 - k);
                o[i] = e;
            }
            return o;
        }

        static double[] emaSkippingNaN(double[] v, int n) {
            double[] o = new double[v.length];
            Arrays.fill(o, Double.NaN);
            int start = 0;
            while (start < v.length && Double.isNaN(v[start])) start++;
            if (v.length - start < n) return o;
            double[] tail = Arrays.copyOfRange(v, start, v.length);
            double[] e = ema(tail, n);
            System.arraycopy(e, 0, o, start, e.length);
            return o;
        }

        /** Wilder's RSI. */
        static double[] rsi(double[] c, int n) {
            double[] o = new double[c.length];
            Arrays.fill(o, Double.NaN);
            if (c.length <= n) return o;
            double g = 0, l = 0;
            for (int i = 1; i <= n; i++) { double d = c[i] - c[i - 1]; if (d > 0) g += d; else l -= d; }
            g /= n; l /= n;
            o[n] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
            for (int i = n + 1; i < c.length; i++) {
                double d = c[i] - c[i - 1];
                g = (g * (n - 1) + Math.max(d, 0)) / n;
                l = (l * (n - 1) + Math.max(-d, 0)) / n;
                o[i] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
            }
            return o;
        }
    }
}
