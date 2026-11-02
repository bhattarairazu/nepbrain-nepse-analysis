package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Your nine rule sets, each in the versions you asked to test, run on every liquid stock with the stored history.
 * One position per stock per rule at a time: signal after the close, buy at the next open, sell at the open after the
 * exit condition (never before T+3, NEPSE settlement), minus 0.8% round-trip costs. Indicators use only data up to the
 * signal day. Each rule's testable window starts once its slowest indicator has enough history; the first 60% of that
 * window is the design period and the rest out-of-sample. "NEPSE index" is the app's equal-weight market of all
 * ordinary shares. The same rules at the latest close give today's buy signals and the positions still open.
 */
@Service
public class RuleSetService {
    static final int MIN_TRADES = 30, MIN_OOS_TRADES = 10;
    static final double TOP = 0.8, KEEP = 0.6; // rule 1: enter in the sector's top 20%, leave below its top 40%
    static final int MIN_SECTOR = 5;           // rule 1: sectors with fewer ranked stocks that day are skipped

    /**
     * One tested version of a rule set. Parameters by family: 1 a = index EMA, b = return lookback, c = rebalance
     * sessions; 2 a = Donchian sessions, x = turnover multiple; 3 a = dip window, x = dip level, y = exit RSI;
     * 4 x = ADX minimum; 7 a = squeeze lookback, x = volume multiple; 8 x = distance to the 52-week high; 9 a = time stop.
     */
    public enum Variant {
        R1A(1, "Index regime + sector strength", "EMA 100 · 3-month return · weekly", 100, 63, 5, 0, 0),
        R1B(1, "Index regime + sector strength", "EMA 200 · 6-month return · weekly", 200, 126, 5, 0, 0),
        R1C(1, "Index regime + sector strength", "EMA 100 · 3-month return · monthly", 100, 63, 20, 0, 0),
        R2A(2, "Donchian breakout + volume", "55-day high · turnover 1.5×", 55, 0, 0, 1.5, 0),
        R2B(2, "Donchian breakout + volume", "20-day high · turnover 1.5×", 20, 0, 0, 1.5, 0),
        R2C(2, "Donchian breakout + volume", "55-day high · turnover 2×", 55, 0, 0, 2.0, 0),
        R3A(3, "Trend filter + RSI pullback", "dip below 40 · exit RSI 70", 10, 0, 0, 40, 70),
        R3B(3, "Trend filter + RSI pullback", "dip below 45 · exit RSI 75", 10, 0, 0, 45, 75),
        R4A(4, "EMA cross + ADX", "ADX above 20", 0, 0, 0, 20, 0),
        R4B(4, "EMA cross + ADX", "ADX above 25", 0, 0, 0, 25, 0),
        R5(5, "MACD + trend + zero line", "MACD (12, 26, 9)", 0, 0, 0, 0, 0),
        R6(6, "Supertrend + RSI + OBV", "Supertrend (10, 3)", 0, 0, 0, 0, 0),
        R7(7, "Bollinger squeeze breakout", "6-month narrowest bands · volume 1.5×", 126, 0, 0, 1.5, 0),
        R8A(8, "Accumulation near highs", "within 5% of the 52-week high", 0, 0, 0, 0.05, 0),
        R8B(8, "Accumulation near highs", "within 10% of the 52-week high", 0, 0, 0, 0.10, 0),
        R9A(9, "Short-term mean reversion", "time stop 5 sessions", 5, 0, 0, 0, 0),
        R9B(9, "Short-term mean reversion", "time stop 7 sessions", 7, 0, 0, 0, 0);

        final int family, a, b, c;
        final double x, y;
        private final String title, version;

        Variant(int family, String title, String version, int a, int b, int c, double x, double y) {
            this.family = family; this.title = title; this.version = version;
            this.a = a; this.b = b; this.c = c; this.x = x; this.y = y;
        }

        public int getFamily() { return family; }
        public String getTitle() { return title; }
        public String getVersion() { return version; }

        public String getEntry() {
            return switch (family) {
                case 1 -> "Equal-weight NEPSE above its " + a + " EMA, the stock in the top 20% of its sector by " + (b == 63 ? "3" : "6")
                        + "-month return and above its 50 EMA. Checked every " + (c == 5 ? "week (5 sessions)" : "month (20 sessions)") + ".";
                case 2 -> "Close above the highest high of the previous " + a + " sessions on turnover ≥ " + fmt(x) + "× its 20-session average.";
                case 3 -> "Close above the 200 EMA; RSI(14) dipped below " + fmt(x) + " in the last " + a + " sessions and now crosses back above 50.";
                case 4 -> "20 EMA crosses above the 50 EMA with ADX(14) above " + fmt(x) + " and +DI above −DI.";
                case 5 -> "MACD crosses above its signal line while MACD is above zero, close above the 200 EMA.";
                case 6 -> "Supertrend (10, 3) turns bullish, RSI(14) above 50, OBV above its 20-session average.";
                case 7 -> "Bollinger (20, 2) band width at its narrowest of the past " + a + " sessions within the last 5, then a close above the upper band on volume ≥ "
                        + fmt(x) + "× the 20-session average.";
                case 8 -> "Chaikin Money Flow (20) above 0, OBV at a 60-session high, close within " + Math.round(x * 100)
                        + "% of the 52-week high (highest high of up to 250 sessions, at least 120 stored).";
                case 9 -> "Close above the 200 SMA and RSI(2) below 10 or a close below the lower Bollinger band (20, 2).";
                default -> "";
            };
        }

        public String getExit() {
            return switch (family) {
                case 1 -> "Close below the 50 EMA, or out of the sector's top 40% on a check day.";
                case 2 -> "Close below the lowest low of the previous 20 sessions, or below the highest high since entry minus 3 × ATR(14).";
                case 3 -> "Close below the 20 EMA, RSI(14) above " + fmt(y) + ", or a close 2 × ATR(14) below the entry.";
                case 4 -> "20 EMA crosses back below the 50 EMA, or ADX turns down from its 10-session high.";
                case 5 -> "MACD crosses below its signal line, or a close below the 50 EMA.";
                case 6 -> "Supertrend turns bearish.";
                case 7 -> "Close back below the 20 SMA.";
                case 8 -> "Close below the 50 EMA.";
                case 9 -> "Close above the 5 SMA, or after " + a + " sessions.";
                default -> "";
            };
        }

        /** Stock sessions needed before the rule can fire. */
        int warmup() {
            return switch (family) {
                case 1 -> Math.max(Math.max(a, b + 1), 50);
                case 2 -> Math.max(a + 1, 21);
                case 3, 5, 9 -> 200;
                case 4 -> 50;
                case 6 -> 21;
                case 7 -> 20 + a + 5;
                case 8 -> 120;
                default -> 0;
            };
        }

        private static String fmt(double v) {
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
    }

    public record Trade(Variant v, String symbol, String sector, LocalDate signal, LocalDate entryDate, double entry, LocalDate exitDate,
                        double exit, int held, String reason, double netPct, double marketPct, boolean design) {}

    /** A position the rule is still holding at the latest close. */
    public record Holding(Variant v, String symbol, String sector, LocalDate entryDate, double entry, double close, int held,
                          double openPct, Double exitLine, String exitNote, String sellNow) {}

    /** A buy signal at the latest close: buy at the next open. */
    public record Pick(Variant v, String symbol, String sector, double close, Double changePct, double turnover, Double exitLine,
                       String exitNote, String why) {}

    public record Period(int n, double winPct, double avgPct, double medianPct) {
        static Period of(List<Trade> l) {
            if (l.isEmpty()) return new Period(0, 0, 0, 0);
            double[] net = l.stream().mapToDouble(Trade::netPct).sorted().toArray();
            return new Period(net.length, 100.0 * Arrays.stream(net).filter(d -> d > 0).count() / net.length,
                    Arrays.stream(net).average().orElse(0), net[net.length / 2]);
        }
    }

    public record VariantResult(Variant v, LocalDate testFrom, LocalDate split, Period all, Period design, Period oos, double avgHeld,
                                double profitFactor, double avgVsMarket, int openCount, Double openAvgPct, String verdict, String tone,
                                List<Trade> recent, List<Pick> picks, List<Holding> holdings) {
        public boolean passed() { return "good".equals(tone); }
    }

    /** A stock with buy signals from one or more rule sets today. */
    public record Combined(String symbol, String sector, double close, double turnover, Set<Integer> families, List<Variant> variants,
                           boolean anyPassed) {}

    /** Rule 1's market filter at the latest close: the equal-weight index against its 100 and 200 EMA. */
    public record Regime(double index, Double ema100, Double ema200) {
        public boolean above100() { return ema100 != null && index > ema100; }
        public boolean above200() { return ema200 != null && index > ema200; }
    }

    public record Result(LocalDate from, LocalDate to, int sessions, double marketPct, List<VariantResult> rows, List<Combined> combined,
                         int liquidStocks, Regime regime) {
        public List<VariantResult> passed() { return rows.stream().filter(VariantResult::passed).toList(); }
    }

    private final BacktestService backtest;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;
    private Object cachedData; // the price data the result was built from; new history reloads it

    public RuleSetService(BacktestService backtest, BacktestRepository store) {
        this.backtest = backtest;
        this.store = store;
    }

    public synchronized void evict() {
        cached = null;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < 60) return Optional.empty();
        int T = m.dates.length;
        LocalDate last = m.dates[T - 1];
        if (cached != null && last.equals(cachedFor) && m == cachedData) return Optional.of(cached);

        double[] idx100 = Ind.ema(m.index, 100), idx200 = Ind.ema(m.index, 200);
        List<Ctx> stocks = new ArrayList<>();
        for (Series s : m.series.values()) if (Sectors.isEquity(s.sector) && s.size() >= 30) stocks.add(new Ctx(s));
        sectorRanks(stocks, T, 63);
        sectorRanks(stocks, T, 126);

        Map<Variant, List<Trade>> trades = new LinkedHashMap<>();
        Map<Variant, List<Pick>> picks = new HashMap<>();
        Map<Variant, List<Holding>> holds = new HashMap<>();
        for (Variant v : Variant.values()) { trades.put(v, new ArrayList<>()); picks.put(v, new ArrayList<>()); holds.put(v, new ArrayList<>()); }
        int liquidToday = 0;

        for (Ctx x : stocks) {
            Series s = x.s;
            boolean tradedToday = s.globalIndex[x.n - 1] == T - 1;
            if (tradedToday && liquid(s, x.n - 1)) liquidToday++;
            for (Variant v : Variant.values()) {
                double[] idxEma = v.a == 200 ? idx200 : idx100;
                int split = split(v, T);
                int i = Math.max(v.warmup(), 1);
                while (i < x.n) {
                    if (!liquid(s, i) || !entry(v, x, i, m, idxEma, T)) { i++; continue; }
                    if (i == x.n - 1) {
                        if (tradedToday) picks.get(v).add(new Pick(v, s.symbol, s.sector, s.close[i], finite(s.change[i]), s.turnover[i],
                                exitLine(v, x, i, s.close[i], s.close[i], i), exitNote(v, x, i), why(v, x, i)));
                        break;
                    }
                    int e = i + 1;
                    double entry = s.open[e] > 0 ? s.open[e] : s.close[i];
                    double atrAtSignal = x.atr14[i], highest = Double.NEGATIVE_INFINITY;
                    int j = e, exitAt = -1;
                    String why = null;
                    for (; j < x.n; j++) {
                        highest = Math.max(highest, hi(s, j));
                        if (j + 1 < e + BacktestService.EARLIEST_SALE) continue; // T+2 settlement: first sale in session T+3
                        why = exit(v, x, j, entry, atrAtSignal, highest, j - e + 1, T);
                        if (why != null) { exitAt = j; break; }
                    }
                    if (exitAt < 0 || exitAt + 1 >= x.n) { // still held at the latest close
                        int lt = x.n - 1;
                        if (tradedToday) holds.get(v).add(new Holding(v, s.symbol, s.sector, s.dates[e], entry, s.close[lt], lt - e + 1,
                                (s.close[lt] / entry - 1) * 100 - BacktestService.COST_PCT,
                                exitLine(v, x, lt, entry, highest, i), exitNote(v, x, lt), exitAt == lt ? why : null));
                        break;
                    }
                    double exitPx = s.open[exitAt + 1] > 0 ? s.open[exitAt + 1] : s.close[exitAt];
                    double net = (exitPx / entry - 1) * 100 - BacktestService.COST_PCT;
                    double mkt = (m.index[s.globalIndex[exitAt + 1]] / m.index[s.globalIndex[i]] - 1) * 100;
                    trades.get(v).add(new Trade(v, s.symbol, s.sector, s.dates[i], s.dates[e], entry, s.dates[exitAt + 1], exitPx,
                            exitAt - e + 1, why, net, mkt, s.globalIndex[i] < split));
                    i = exitAt + 1; // flat again from the exit session's close
                }
            }
        }

        List<VariantResult> rows = new ArrayList<>();
        for (Variant v : Variant.values()) rows.add(summarise(v, m, T, trades.get(v), picks.get(v), holds.get(v)));
        double bh = (m.index[T - 1] / m.index[0] - 1) * 100;
        cached = new Result(m.dates[0], last, T, bh, rows, combine(rows), liquidToday,
                new Regime(m.index[T - 1], finite(idx100[T - 1]), finite(idx200[T - 1])));
        cachedFor = last;
        cachedData = m;
        save(cached, trades);
        return Optional.of(cached);
    }

    /** Every closed trade and each version's summary go to backtest_run / backtest_result / backtest_trade (kind RULESETS). */
    private void save(Result r, Map<Variant, List<Trade>> trades) {
        List<BacktestRepository.TradeRow> rows = new ArrayList<>();
        trades.forEach((v, list) -> list.forEach(t -> rows.add(new BacktestRepository.TradeRow(v.name(), t.symbol(), t.signal(), t.entryDate(),
                t.entry(), t.exitDate(), t.exit(), t.held(), t.reason(), t.netPct(), t.marketPct()))));
        store.saveIfNew(new BacktestRepository.RunKey("RULESETS", r.from(), r.to(), r.sessions(), null, false), BacktestService.COST_PCT, r.marketPct(),
                r.rows().stream().map(x -> new BacktestRepository.SummaryRow(x.v().name(), x.v().getTitle() + " · " + x.v().getVersion(),
                        x.all().n(), x.all().winPct(), x.all().avgPct(), x.all().medianPct(), x.avgVsMarket(),
                        x.design().n() > 0 ? x.design().avgPct() : null, x.oos().n() > 0 ? x.oos().avgPct() : null,
                        x.profitFactor(), x.verdict())).toList(), rows);
    }

    // ------------------------------------------------------------------ rules

    static boolean entry(Variant v, Ctx x, int i, Market m, double[] idxEma, int T) {
        Series s = x.s;
        double c = s.close[i];
        int g = s.globalIndex[i];
        return switch (v.family) {
            case 1 -> rebalance(g, T, v.c) && ok(idxEma[g], x.ema50[i]) && m.index[g] > idxEma[g] && c > x.ema50[i]
                    && x.rank(v.b)[i] >= TOP;
            case 2 -> ok(x.toAvg20[i]) && c > Indicators.max(s.high, i - v.a, i) && s.turnover[i] >= v.x * x.toAvg20[i];
            case 3 -> ok(x.ema200[i], x.rsi14[i], x.rsi14[i - 1]) && c > x.ema200[i] && x.rsi14[i - 1] <= 50 && x.rsi14[i] > 50
                    && Indicators.min(x.rsi14, i - v.a, i) < v.x;
            case 4 -> ok(x.ema20[i], x.ema50[i], x.ema20[i - 1], x.ema50[i - 1], x.adx[i]) && x.ema20[i - 1] <= x.ema50[i - 1]
                    && x.ema20[i] > x.ema50[i] && x.adx[i] > v.x && x.pdi[i] > x.mdi[i];
            case 5 -> ok(x.macd[i], x.signal[i], x.macd[i - 1], x.signal[i - 1], x.ema200[i]) && x.macd[i - 1] <= x.signal[i - 1]
                    && x.macd[i] > x.signal[i] && x.macd[i] > 0 && c > x.ema200[i];
            case 6 -> ok(x.stLine[i], x.stLine[i - 1], x.rsi14[i], x.obvAvg20[i]) && x.stUp[i] && !x.stUp[i - 1] && x.rsi14[i] > 50
                    && x.obv[i] > x.obvAvg20[i];
            case 7 -> ok(x.upper[i], x.volAvg20[i]) && x.squeezedWithin(i, 5, v.a) && c > x.upper[i] && s.volume[i] >= v.x * x.volAvg20[i];
            case 8 -> ok(x.cmf20[i], x.ema50[i]) && x.cmf20[i] > 0 && x.obv[i] >= Indicators.max(x.obv, i - 60, i)
                    && c >= x.high52(i) * (1 - v.x);
            case 9 -> ok(x.sma200[i], x.rsi2[i], x.lower[i]) && c > x.sma200[i] && (x.rsi2[i] < 10 || c < x.lower[i]);
            default -> false;
        };
    }

    /** The exit reason at the close of day j (at most 10 characters, stored with the trade), or null to keep holding. */
    static String exit(Variant v, Ctx x, int j, double entry, double atrAtSignal, double highest, int held, int T) {
        Series s = x.s;
        double c = s.close[j];
        return switch (v.family) {
            case 1 -> ok(x.ema50[j]) && c < x.ema50[j] ? "50 EMA"
                    : rebalance(s.globalIndex[j], T, v.c) && ok(x.rank(v.b)[j]) && x.rank(v.b)[j] < KEEP ? "rank" : null;
            case 2 -> j >= 20 && c < Indicators.min(s.low, j - 20, j) ? "20d low"
                    : ok(x.atr14[j]) && c < highest - 3 * x.atr14[j] ? "chandelier" : null;
            case 3 -> ok(x.ema20[j]) && c < x.ema20[j] ? "20 EMA"
                    : ok(x.rsi14[j]) && x.rsi14[j] > v.y ? "RSI high"
                    : ok(atrAtSignal) && c < entry - 2 * atrAtSignal ? "ATR stop" : null;
            case 4 -> ok(x.ema20[j], x.ema50[j]) && x.ema20[j] < x.ema50[j] ? "EMA cross"
                    : ok(x.adx[j], x.adx[j - 1]) && x.adx[j] < x.adx[j - 1] && x.adx[j - 1] >= Indicators.max(x.adx, j - 10, j) ? "ADX peak" : null;
            case 5 -> ok(x.macd[j], x.signal[j]) && x.macd[j] < x.signal[j] ? "MACD"
                    : ok(x.ema50[j]) && c < x.ema50[j] ? "50 EMA" : null;
            case 6 -> ok(x.stLine[j]) && !x.stUp[j] ? "ST flip" : null;
            case 7 -> ok(x.sma20[j]) && c < x.sma20[j] ? "20 SMA" : null;
            case 8 -> ok(x.ema50[j]) && c < x.ema50[j] ? "50 EMA" : null;
            case 9 -> ok(x.sma5[j]) && c > x.sma5[j] ? "5 SMA" : held >= v.a ? "time" : null;
            default -> null;
        };
    }

    /** The price whose break would end the position at the close of day i (null when the exit is not a price level). */
    static Double exitLine(Variant v, Ctx x, int i, double entry, double highest, int signalIdx) {
        return switch (v.family) {
            case 1, 5, 8 -> finite(x.ema50[i]);
            case 2 -> {
                double low = i >= 19 ? Indicators.min(x.s.low, i - 19, i + 1) : Double.NaN;
                double ch = Math.max(highest, hi(x.s, i)) - 3 * x.atr14[i];
                yield finite(Double.isNaN(low) ? ch : Double.isNaN(ch) ? low : Math.max(low, ch));
            }
            case 3 -> finite(Math.max(x.ema20[i], entry - 2 * x.atr14[signalIdx]));
            case 6 -> finite(x.stLine[i]);
            case 7 -> finite(x.sma20[i]);
            default -> null;
        };
    }

    static String exitNote(Variant v, Ctx x, int i) {
        return switch (v.family) {
            case 1 -> "close below the 50 EMA, or out of the sector's top 40%";
            case 2 -> "close below the 20-day low or the 3 × ATR chandelier";
            case 3 -> "close below the 20 EMA or the 2 × ATR stop; or RSI above " + Variant.fmt(v.y);
            case 4 -> "20 EMA back below the 50 EMA, or ADX turning down";
            case 5 -> "close below the 50 EMA, or MACD crossing down";
            case 6 -> "close below the Supertrend line";
            case 7 -> "close below the 20 SMA";
            case 8 -> "close below the 50 EMA";
            case 9 -> "sell on a close above the 5 SMA (≈ " + String.format("%.1f", x.sma5[i]) + ") or after " + v.a + " sessions";
            default -> "";
        };
    }

    static String why(Variant v, Ctx x, int i) {
        Series s = x.s;
        return switch (v.family) {
            case 1 -> "top " + Math.max(1, Math.round((1 - x.rank(v.b)[i]) * 100)) + "% of its sector";
            case 2 -> String.format("turnover %.1f× average", s.turnover[i] / x.toAvg20[i]);
            case 3 -> String.format("RSI %.0f → %.0f", Indicators.min(x.rsi14, i - v.a, i), x.rsi14[i]);
            case 4 -> String.format("ADX %.0f, +DI %.0f vs −DI %.0f", x.adx[i], x.pdi[i], x.mdi[i]);
            case 5 -> String.format("MACD %.2f", x.macd[i]);
            case 6 -> String.format("RSI %.0f", x.rsi14[i]);
            case 7 -> String.format("volume %.1f× average", s.volume[i] / x.volAvg20[i]);
            case 8 -> String.format("CMF %.2f, %.1f%% below the 52-week high", x.cmf20[i], (1 - s.close[i] / x.high52(i)) * 100);
            case 9 -> x.rsi2[i] < 10 ? String.format("RSI(2) %.0f", x.rsi2[i]) : "below the lower band";
            default -> "";
        };
    }

    /** Rebalance days counted back from the latest session, so the latest session is always one. */
    static boolean rebalance(int g, int T, int every) {
        return (T - 1 - g) % every == 0;
    }

    static int split(Variant v, int T) {
        int w = Math.min(v.warmup(), T - 1);
        return w + (int) Math.round((T - w) * AdvancedStrategyService.DESIGN_SHARE);
    }

    private static boolean liquid(Series s, int i) {
        return s.turnover[i] >= BacktestService.MIN_TURNOVER && !Double.isNaN(s.close[i]);
    }

    // ------------------------------------------------------------------ results

    private static VariantResult summarise(Variant v, Market m, int T, List<Trade> list, List<Pick> picks, List<Holding> holds) {
        int w = Math.min(v.warmup(), T - 1), split = Math.min(split(v, T), T - 1);
        Period all = Period.of(list), design = Period.of(list.stream().filter(Trade::design).toList()),
                oos = Period.of(list.stream().filter(t -> !t.design()).toList());
        double gain = list.stream().filter(t -> t.netPct() > 0).mapToDouble(Trade::netPct).sum();
        double loss = -list.stream().filter(t -> t.netPct() <= 0).mapToDouble(Trade::netPct).sum();
        String verdict, tone;
        if (all.n() < MIN_TRADES) { verdict = "Too few trades to judge"; tone = "neutral"; }
        else if (oos.n() < MIN_OOS_TRADES && all.avgPct() <= 0) { verdict = "Loses money; too few recent trades"; tone = "bad"; }
        else if (oos.n() < MIN_OOS_TRADES) { verdict = "Too few out-of-sample trades"; tone = "neutral"; }
        else if (design.avgPct() > 0 && oos.avgPct() > 0 && oos.medianPct() > 0) { verdict = "Profitable in both periods"; tone = "good"; }
        else if (oos.avgPct() > 0) { verdict = "Positive out-of-sample only"; tone = "warn"; }
        else if (design.avgPct() > 0) { verdict = "Worked in design, failed out-of-sample"; tone = "bad"; }
        else { verdict = "Loses money"; tone = "bad"; }
        List<Pick> p = picks.stream().sorted(Comparator.comparingDouble(Pick::turnover).reversed()).toList();
        List<Holding> h = holds.stream().sorted(Comparator.comparing(Holding::entryDate).reversed()).toList();
        return new VariantResult(v, m.dates[w], m.dates[split], all, design, oos,
                list.stream().mapToInt(Trade::held).average().orElse(0), loss > 0 ? gain / loss : (gain > 0 ? 99 : 0),
                list.stream().mapToDouble(t -> t.netPct() - t.marketPct()).average().orElse(0),
                h.size(), h.isEmpty() ? null : h.stream().mapToDouble(Holding::openPct).average().orElse(0), verdict, tone,
                list.stream().sorted(Comparator.comparing(Trade::signal).reversed()).limit(15).toList(), p, h);
    }

    private static List<Combined> combine(List<VariantResult> rows) {
        Map<String, List<Pick>> by = new LinkedHashMap<>();
        Map<Variant, Boolean> passed = new HashMap<>();
        for (VariantResult r : rows) {
            passed.put(r.v(), r.passed());
            for (Pick p : r.picks()) by.computeIfAbsent(p.symbol(), k -> new ArrayList<>()).add(p);
        }
        List<Combined> out = new ArrayList<>();
        by.forEach((sym, l) -> {
            Set<Integer> fam = new LinkedHashSet<>();
            l.forEach(p -> fam.add(p.v().family));
            Pick f = l.get(0);
            out.add(new Combined(sym, f.sector(), f.close(), f.turnover(), fam, l.stream().map(Pick::v).toList(),
                    l.stream().anyMatch(p -> passed.get(p.v()))));
        });
        out.sort(Comparator.comparingInt((Combined c) -> -c.families().size()).thenComparing(c -> -c.turnover()));
        return out;
    }

    /** Each stock's percentile of its lookback return within its sector, per session (1 = best, NaN = not ranked). */
    private static void sectorRanks(List<Ctx> stocks, int T, int lookback) {
        Map<String, List<Ctx>> bySector = new HashMap<>();
        for (Ctx x : stocks) bySector.computeIfAbsent(x.s.sector == null ? "?" : x.s.sector, k -> new ArrayList<>()).add(x);
        for (Ctx x : stocks) { double[] r = new double[x.n]; Arrays.fill(r, Double.NaN); x.ranks.put(lookback, r); }
        for (List<Ctx> group : bySector.values()) {
            for (int g = 0; g < T; g++) {
                List<double[]> day = new ArrayList<>(); // {return, stock#, local index}
                for (int k = 0; k < group.size(); k++) {
                    Ctx x = group.get(k);
                    int i = x.s.indexOf(g);
                    if (i < lookback) continue;
                    double r = x.s.close[i] / x.s.close[i - lookback] - 1;
                    if (Double.isFinite(r)) day.add(new double[] {r, k, i});
                }
                if (day.size() < MIN_SECTOR) continue;
                day.sort(Comparator.comparingDouble(d -> d[0]));
                for (int q = 0; q < day.size(); q++) {
                    double[] d = day.get(q);
                    group.get((int) d[1]).ranks.get(lookback)[(int) d[2]] = (double) q / (day.size() - 1);
                }
            }
        }
    }

    private static boolean ok(double... v) {
        for (double d : v) if (Double.isNaN(d)) return false;
        return true;
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static double hi(Series s, int i) {
        return Double.isNaN(s.high[i]) ? s.close[i] : s.high[i];
    }

    // ------------------------------------------------------------------ indicators (each value uses only data up to that day)

    static final class Ctx {
        final Series s;
        final int n;
        final double[] ema20, ema50, ema200, sma5, sma20, sma200, rsi2, rsi14, upper, lower, width, macd, signal,
                atr14, adx, pdi, mdi, obv, obvAvg20, cmf20, toAvg20, volAvg20, stLine;
        final boolean[] stUp, squeezeLow126;
        final Map<Integer, double[]> ranks = new HashMap<>();

        Ctx(Series s) {
            this.s = s;
            n = s.size();
            double[] c = s.close, h = new double[n], l = new double[n];
            for (int i = 0; i < n; i++) {
                h[i] = Double.isNaN(s.high[i]) ? c[i] : s.high[i];
                l[i] = Double.isNaN(s.low[i]) ? c[i] : s.low[i];
            }
            Ind ind = new Ind(c, h, l);
            ema20 = ind.ema20; ema50 = ind.ema50; sma5 = ind.sma5; sma20 = ind.sma20; rsi2 = ind.rsi2; rsi14 = ind.rsi14;
            lower = ind.lower; macd = ind.macd; signal = ind.signal;
            ema200 = Ind.ema(c, 200); sma200 = Ind.sma(c, 200);
            upper = new double[n]; width = new double[n];
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(lower[i])) { upper[i] = Double.NaN; width[i] = Double.NaN; continue; }
                upper[i] = 2 * sma20[i] - lower[i];
                width[i] = (upper[i] - lower[i]) / sma20[i];
            }
            squeezeLow126 = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(width[i]) || i < 126 || Double.isNaN(width[i - 126])) continue;
                squeezeLow126[i] = width[i] <= Indicators.min(width, i - 126, i);
            }
            atr14 = atr(h, l, c, 14);
            adx = new double[n]; pdi = new double[n]; mdi = new double[n];
            adx(h, l, c, 14);
            obv = new double[n];
            for (int i = 1; i < n; i++) {
                double v = Double.isNaN(s.volume[i]) ? 0 : s.volume[i];
                obv[i] = obv[i - 1] + Math.signum(c[i] - c[i - 1]) * v;
            }
            obvAvg20 = Ind.sma(obv, 20);
            cmf20 = new double[n];
            Arrays.fill(cmf20, Double.NaN);
            for (int i = 19; i < n; i++) {
                double mfv = 0, vol = 0;
                for (int k = i - 19; k <= i; k++) {
                    double v = Double.isNaN(s.volume[k]) ? 0 : s.volume[k];
                    if (h[k] > l[k]) mfv += ((c[k] - l[k]) - (h[k] - c[k])) / (h[k] - l[k]) * v;
                    vol += v;
                }
                if (vol > 0) cmf20[i] = mfv / vol;
            }
            toAvg20 = prevAvg(s.turnover, 20);
            volAvg20 = prevAvg(s.volume, 20);
            stLine = new double[n];
            stUp = new boolean[n];
            supertrend(h, l, c, 10, 3);
        }

        double[] rank(int lookback) {
            return ranks.get(lookback);
        }

        boolean squeezedWithin(int i, int within, int lookback) {
            boolean[] sq = squeezeLow126; // only the 126-session version is tested
            for (int k = Math.max(0, i - within); k < i; k++) if (sq[k]) return true;
            return false;
        }

        /** Highest high of up to the last 250 sessions including today. */
        double high52(int i) {
            return Indicators.max(s.high, Math.max(0, i - 249), i + 1);
        }

        /** Average of the previous n sessions (today excluded); NaN with fewer than 3/4 of them known. */
        static double[] prevAvg(double[] a, int n) {
            double[] o = new double[a.length];
            Arrays.fill(o, Double.NaN);
            for (int i = n; i < a.length; i++) {
                Double m = Indicators.mean(a, i - n, i, n * 3 / 4);
                if (m != null && m > 0) o[i] = m;
            }
            return o;
        }

        /** Wilder's average true range. */
        static double[] atr(double[] h, double[] l, double[] c, int n) {
            double[] o = new double[c.length];
            Arrays.fill(o, Double.NaN);
            double a = 0;
            for (int i = 1; i < c.length; i++) {
                double tr = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
                if (i <= n) { a += tr; if (i == n) { a /= n; o[i] = a; } }
                else { a = (a * (n - 1) + tr) / n; o[i] = a; }
            }
            return o;
        }

        /** Wilder's ADX with +DI and −DI. */
        private void adx(double[] h, double[] l, double[] c, int p) {
            Arrays.fill(adx, Double.NaN); Arrays.fill(pdi, Double.NaN); Arrays.fill(mdi, Double.NaN);
            double tr = 0, pm = 0, mm = 0, dxSum = 0, a = 0;
            for (int i = 1; i < n; i++) {
                double up = h[i] - h[i - 1], dn = l[i - 1] - l[i];
                double pdm = up > dn && up > 0 ? up : 0, mdm = dn > up && dn > 0 ? dn : 0;
                double t = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
                if (i <= p) { tr += t; pm += pdm; mm += mdm; if (i < p) continue; }
                else { tr = tr - tr / p + t; pm = pm - pm / p + pdm; mm = mm - mm / p + mdm; }
                double pd = tr > 0 ? 100 * pm / tr : 0, md = tr > 0 ? 100 * mm / tr : 0;
                pdi[i] = pd; mdi[i] = md;
                double dx = pd + md > 0 ? 100 * Math.abs(pd - md) / (pd + md) : 0;
                if (i < 2 * p - 1) dxSum += dx;
                else if (i == 2 * p - 1) { a = (dxSum + dx) / p; adx[i] = a; }
                else { a = (a * (p - 1) + dx) / p; adx[i] = a; }
            }
        }

        /** Supertrend: the trailing line and whether the trend is up. */
        private void supertrend(double[] h, double[] l, double[] c, int p, double mult) {
            Arrays.fill(stLine, Double.NaN);
            double[] a = atr(h, l, c, p);
            double fu = Double.NaN, fl = Double.NaN;
            boolean up = true;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(a[i])) continue;
                double mid = (h[i] + l[i]) / 2, bu = mid + mult * a[i], bl = mid - mult * a[i];
                if (Double.isNaN(fu)) { fu = bu; fl = bl; up = c[i] >= mid; }
                else {
                    fu = bu < fu || c[i - 1] > fu ? bu : fu;
                    fl = bl > fl || c[i - 1] < fl ? bl : fl;
                    if (up && c[i] < fl) up = false;
                    else if (!up && c[i] > fu) up = true;
                }
                stUp[i] = up;
                stLine[i] = up ? fl : fu;
            }
        }
    }
}
