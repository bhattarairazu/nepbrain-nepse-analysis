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
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;
import com.nepse.topturnover.journal.TradeCosts;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Breakout-then-pullback test. A breakout is a close above the previous 20-day high on at least 1.5× average volume
 * (Rs 1 Cr+ turnover). Entries compared, all at the next open with T+2 settlement:
 * the breakout day itself; the first pullback that holds the breakout level on lighter volume; a real retest
 * (first a run 5%+ above the level, then a low back within 3% of it, 3+ sessions later, closing above it on volume
 * under 70% of the breakout day's); and the retest followed by a close above the retest day's high.
 * Variants and exits were fixed before looking at results; design = first 60% of sessions.
 */
@Service
public class BreakoutPullbackService {
    static final int LOOKBACK = 20, MIN_GAP = 10;
    static final double MIN_RVOL = 1.5;

    public enum Entry {
        BREAKOUT("Breakout day", "buy at the open after the breakout"),
        PULLBACK("Pullback holds", "first day within 10 sessions whose low comes within 3% of the breakout level, closes no more than 3% under it, on lighter average volume than the breakout day"),
        PULLBACK_RVOL2("Pullback · breakout RVOL ≥ 2", "as above, the breakout on at least 2× average volume"),
        PULLBACK_STRONG("Pullback · strong breakout close", "as above, the breakout day closed in the top 30% of its range"),
        PULLBACK_MKT_UP("Pullback · market up", "as above, with the market above its 50-day EMA on the pullback day"),
        RETEST("Real retest", "the stock first ran 5%+ above the breakout level; 3+ sessions after the breakout its low returns within 3% of the level, it closes at or above the level, average volume since the breakout under 70% of the breakout day"),
        RETEST_TURN("Retest + turn up", "a real retest followed within 3 sessions by a close above the retest day's high");

        private final String title, rule;

        Entry(String title, String rule) {
            this.title = title;
            this.rule = rule;
        }

        public String getTitle() { return title; }
        public String getRule() { return rule; }
    }

    public enum Exit {
        SHORT("+7% / −5% / 10 sessions", 0.07, 0.05, 10),
        LONG("+10% / −7% / 20 sessions", 0.10, 0.07, 20);

        final double target, stop;
        final int window;
        private final String title;

        Exit(String title, double target, double stop, int window) {
            this.title = title;
            this.target = target;
            this.stop = stop;
            this.window = window;
        }

        public String getTitle() { return title; }
    }

    public record Stat(int n, double hitPct, double avgPct, double medianPct) {}

    public record Row(Entry entry, Exit exit, Stat design, Stat oos) {}

    public record Trade(String symbol, String sector, LocalDate breakout, LocalDate signal, LocalDate entryDate, double entry,
                        LocalDate exitDate, double exit, int held, String reason, boolean design, double netPct) {}

    public record Account(String key, String title, String rule, double finalValue, double returnPct, double designReturnPct,
                          double oosReturnPct, int trades, double winRate, List<Trade> list) {}

    public record Setup(String symbol, String sector, LocalDate breakout, double level, double rvol, double lastClose,
                        double fromLevelPct, String state) {}

    public record Result(LocalDate from, LocalDate split, LocalDate to, int sessions, int breakouts, int pullbacks, int retests,
                         int turns, List<Row> rows, List<Account> accounts, List<Trade> recent, List<Setup> watch) {}

    /** One breakout and the signal day (index into the series) of each entry type, -1 if it never came. */
    private static final class Event {
        final Series s;
        final int b;
        final double level, rvol, closeInRange;
        final int[] signal = new int[Entry.values().length];
        boolean failed;

        Event(Series s, int b, double level, double rvol, double closeInRange) {
            this.s = s; this.b = b; this.level = level; this.rvol = rvol; this.closeInRange = closeInRange;
            Arrays.fill(signal, -1);
        }
    }

    /** What a trade from the open after signal day i did. */
    record Outcome(int entryI, double entry, int exitI, double exit, boolean hit, String reason, double ret) {}

    private final BacktestService backtest;
    private final TradeCosts costs;
    private final BacktestRepository store;
    private Result cached;
    private LocalDate cachedFor;

    public BreakoutPullbackService(BacktestService backtest, TradeCosts costs, BacktestRepository store) {
        this.backtest = backtest;
        this.costs = costs;
        this.store = store;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < 80) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor)) return Optional.of(cached);

        int T = m.dates.length, split = (int) Math.round(T * AdvancedStrategyService.DESIGN_SHARE);
        double[] mEma = Ind.ema(m.index, 50);
        List<Event> events = events(m);

        List<Row> rows = new ArrayList<>();
        for (Entry e : Entry.values()) {
            for (Exit x : Exit.values()) {
                List<Double> dr = new ArrayList<>(), or = new ArrayList<>();
                int dh = 0, oh = 0;
                for (Event ev : events) {
                    int i = ev.signal[e.ordinal()];
                    if (i < 0 || !qualifies(e, ev, i, m, mEma)) continue;
                    Outcome o = outcome(ev.s, i, x);
                    if (o == null) continue;
                    boolean design = ev.s.globalIndex[i] < split;
                    (design ? dr : or).add(o.ret() * 100);
                    if (o.hit()) { if (design) dh++; else oh++; }
                }
                rows.add(new Row(e, x, stat(dr, dh), stat(or, oh)));
            }
        }

        List<Account> accounts = List.of(
                account(m, events, mEma, Entry.PULLBACK, split, "PB_PULLBACK_M"),
                account(m, events, mEma, Entry.RETEST, split, "PB_RETEST_M"),
                account(m, events, mEma, Entry.RETEST_TURN, split, "PB_TURN_M"));

        List<Trade> recent = new ArrayList<>();
        for (Event ev : events) {
            int i = ev.signal[Entry.PULLBACK.ordinal()];
            if (i < 0) continue;
            Outcome o = outcome(ev.s, i, Exit.SHORT);
            if (o != null) recent.add(trade(m, ev, i, o, split, o.ret() * 100));
        }
        recent.sort(Comparator.comparing(Trade::signal).reversed());

        cached = new Result(m.dates[0], m.dates[split], last, T, events.size(), count(events, Entry.PULLBACK), count(events, Entry.RETEST),
                count(events, Entry.RETEST_TURN), rows, accounts, recent.stream().limit(40).toList(), watch(m, events));
        cachedFor = last;
        save(cached);
        return Optional.of(cached);
    }

    // ------------------------------------------------------------------ detection

    private List<Event> events(Market m) {
        List<Event> out = new ArrayList<>();
        for (Series s : m.series.values()) {
            int n = s.size();
            if (!Sectors.isEquity(s.sector) || n < LOOKBACK + 10) continue;
            double[] o = s.open, h = s.high, l = s.low, c = s.close, v = s.volume;
            int lastB = -MIN_GAP;
            for (int b = LOOKBACK + 5; b < n; b++) {
                double level = max(h, b - LOOKBACK, b);
                Double avg = Indicators.mean(v, b - LOOKBACK, b, 10);
                if (avg == null || avg <= 0 || !(c[b] > level) || v[b] / avg < MIN_RVOL || !(s.turnover[b] >= BacktestService.MIN_TURNOVER) || b - lastB < MIN_GAP) continue;
                lastB = b;
                double rng = h[b] - l[b];
                Event ev = new Event(s, b, level, v[b] / avg, rng > 0 ? (c[b] - l[b]) / rng : 0.5);
                ev.signal[Entry.BREAKOUT.ordinal()] = b;
                // first pullback that holds
                for (int p = b + 1; p < Math.min(b + 11, n); p++) {
                    if (c[p] < level * 0.97) { ev.failed = true; break; }
                    if (l[p] <= level * 1.03 && mean(v, b + 1, p + 1) < v[b]) {
                        for (Entry e : List.of(Entry.PULLBACK, Entry.PULLBACK_RVOL2, Entry.PULLBACK_STRONG, Entry.PULLBACK_MKT_UP)) ev.signal[e.ordinal()] = p;
                        break;
                    }
                }
                // real retest after a run, then a turn up
                double runHigh = c[b];
                for (int p = b + 1; p < Math.min(b + 16, n); p++) {
                    if (c[p] < level * 0.97) break;
                    if (p - b >= 3 && runHigh >= level * 1.05 && l[p] <= level * 1.03 && c[p] >= level && mean(v, b + 1, p + 1) < v[b] * 0.7) {
                        ev.signal[Entry.RETEST.ordinal()] = p;
                        for (int k = p + 1; k < Math.min(p + 4, n); k++) {
                            if (c[k] < level * 0.97) break;
                            if (c[k] > h[p]) { ev.signal[Entry.RETEST_TURN.ordinal()] = k; break; }
                        }
                        break;
                    }
                    runHigh = Math.max(runHigh, h[p]);
                }
                out.add(ev);
            }
        }
        return out;
    }

    private static boolean qualifies(Entry e, Event ev, int i, Market m, double[] mEma) {
        return switch (e) {
            case PULLBACK_RVOL2 -> ev.rvol >= 2;
            case PULLBACK_STRONG -> ev.closeInRange >= 0.7;
            case PULLBACK_MKT_UP -> {
                int t = ev.s.globalIndex[i];
                yield !Double.isNaN(mEma[t]) && m.index[t] > mEma[t];
            }
            default -> true;
        };
    }

    /** Buy at the open after day i; target sells from T+3, a close below the stop (from T+2's close) sells at the next open, else the last close. */
    static Outcome outcome(Series s, int i, Exit x) {
        int e = i + 1, n = s.size();
        if (e + x.window >= n || !(s.open[e] > 0)) return null;
        double entry = s.open[e];
        for (int k = e; k < e + x.window; k++) {
            if (k - e >= BacktestService.EARLIEST_SALE && s.high[k] >= entry * (1 + x.target)) {
                double px = Math.max(entry * (1 + x.target), s.open[k]);
                return new Outcome(e, entry, k, px, true, "target", px / entry - 1 - BacktestService.COST_PCT / 100);
            }
            if (k + 1 - e >= BacktestService.EARLIEST_SALE && s.close[k] <= entry * (1 - x.stop)) {
                double px = s.open[k + 1] > 0 ? s.open[k + 1] : s.close[k];
                return new Outcome(e, entry, k + 1, px, false, "stop", px / entry - 1 - BacktestService.COST_PCT / 100);
            }
        }
        int k = e + x.window - 1;
        return new Outcome(e, entry, k, s.close[k], false, "time", s.close[k] / entry - 1 - BacktestService.COST_PCT / 100);
    }

    // ------------------------------------------------------------------ accounts, watchlist

    /** Rs 30 lakh, one trade at a time, at most one new trade per calendar month; the strongest breakout volume wins a tie. */
    private Account account(Market m, List<Event> events, double[] mEma, Entry e, int split, String key) {
        Map<Integer, List<Event>> byDay = new HashMap<>();
        for (Event ev : events) {
            int i = ev.signal[e.ordinal()];
            if (i >= 0 && qualifies(e, ev, i, m, mEma)) byDay.computeIfAbsent(ev.s.globalIndex[i], k -> new ArrayList<>()).add(ev);
        }
        double cap = StrategyLabService.CAPITAL, equity = cap, designEnd = cap;
        int busyUntil = -1;
        Set<String> used = new HashSet<>();
        List<Trade> list = new ArrayList<>();
        for (int t = 0; t < m.dates.length; t++) {
            List<Event> day = byDay.get(t);
            if (day == null || t <= busyUntil) continue;
            LocalDate d = m.dates[t];
            if (used.contains(d.getYear() + "-" + d.getMonthValue())) continue;
            Event ev = day.stream().max(Comparator.comparingDouble(x -> x.rvol)).get();
            int i = ev.signal[e.ordinal()];
            Outcome o = outcome(ev.s, i, Exit.SHORT);
            if (o == null) continue;
            used.add(d.getYear() + "-" + d.getMonthValue());
            double amount = equity / 1.005, bc = costs.buyCharges(amount), qty = amount / o.entry();
            double gross = qty * o.exit(), sc = costs.sellCharges(gross), gain = gross - sc - amount - bc;
            double tx = gain > 0 ? gain * costs.cgtShortPct() / 100 : 0, before = equity;
            equity = equity - amount - bc + gross - sc - tx;
            if (t < split) designEnd = equity;
            list.add(trade(m, ev, i, o, split, (equity / before - 1) * 100));
            busyUntil = ev.s.globalIndex[o.exitI()];
        }
        long wins = list.stream().filter(x -> x.netPct() > 0).count();
        return new Account(key, e.getTitle() + " · max 1 trade a month",
                "Rs 30 lakh in one stock at a time, +7% / −5% / 10 sessions, NEPSE charges and 7.5% capital gains tax.",
                equity, (equity / cap - 1) * 100, (designEnd / cap - 1) * 100, (equity / designEnd - 1) * 100, list.size(),
                list.isEmpty() ? 0 : 100.0 * wins / list.size(), list.stream().sorted(Comparator.comparing(Trade::signal).reversed()).toList());
    }

    private static Trade trade(Market m, Event ev, int i, Outcome o, int split, double netPct) {
        Series s = ev.s;
        return new Trade(s.symbol, s.sector, s.dates[ev.b], s.dates[i], s.dates[o.entryI()], o.entry(), s.dates[o.exitI()], o.exit(),
                s.globalIndex[o.exitI()] - s.globalIndex[o.entryI()], o.reason(), s.globalIndex[i] < split, netPct);
    }

    /** Breakouts of the last 15 sessions and where each one stands now. */
    private static List<Setup> watch(Market m, List<Event> events) {
        int T = m.dates.length;
        List<Setup> out = new ArrayList<>();
        for (Event ev : events) {
            Series s = ev.s;
            int lastI = s.size() - 1;
            if (s.globalIndex[ev.b] < T - 15 || s.globalIndex[lastI] < T - 1) continue;
            String state;
            if (ev.signal[Entry.RETEST_TURN.ordinal()] >= 0) state = "retest + turn up";
            else if (ev.signal[Entry.RETEST.ordinal()] >= 0) state = "real retest: wait for a turn up";
            else if (ev.failed || s.close[lastI] < ev.level * 0.97) state = "failed: back below the level";
            else if (ev.signal[Entry.PULLBACK.ordinal()] >= 0) state = "pulled back and holding";
            else state = "above the level, no pullback yet";
            out.add(new Setup(s.symbol, s.sector, s.dates[ev.b], ev.level, ev.rvol, s.close[lastI], (s.close[lastI] / ev.level - 1) * 100, state));
        }
        out.sort(Comparator.comparing(Setup::breakout).reversed());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static int count(List<Event> events, Entry e) {
        return (int) events.stream().filter(ev -> ev.signal[e.ordinal()] >= 0).count();
    }

    private static Stat stat(List<Double> r, int hits) {
        if (r.isEmpty()) return new Stat(0, 0, 0, 0);
        double[] a = r.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new Stat(a.length, 100.0 * hits / a.length, Arrays.stream(a).average().orElse(0), a[a.length / 2]);
    }

    private static double max(double[] a, int from, int to) {
        double v = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) v = Math.max(v, a[k]);
        return v;
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        for (int k = from; k < to; k++) s += a[k];
        return to > from ? s / (to - from) : Double.NaN;
    }

    private void save(Result r) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        List<BacktestRepository.TradeRow> trades = new ArrayList<>();
        for (Row x : r.rows()) {
            sums.add(new BacktestRepository.SummaryRow("PB_" + x.entry().name() + "_" + x.exit().name(), x.entry().getTitle() + " · " + x.exit().getTitle(),
                    x.design().n() + x.oos().n(), null, null, null, null, x.design().avgPct(), x.oos().avgPct(), null,
                    String.format(Locale.ROOT, "hit %.1f%% design / %.1f%% out-of-sample", x.design().hitPct(), x.oos().hitPct())));
        }
        for (Account a : r.accounts()) {
            sums.add(new BacktestRepository.SummaryRow(a.key(), a.title(), a.trades(), a.winRate(),
                    a.list().stream().mapToDouble(Trade::netPct).average().orElse(0), null, null, a.designReturnPct(), a.oosReturnPct(), null,
                    String.format(Locale.ROOT, "Rs %.0f final", a.finalValue())));
            for (Trade t : a.list()) {
                trades.add(new BacktestRepository.TradeRow(a.key(), t.symbol(), t.signal(), t.entryDate(), t.entry(), t.exitDate(), t.exit(),
                        t.held(), t.reason(), t.netPct(), null));
            }
        }
        store.saveIfNew(new BacktestRepository.RunKey("PULLBACK", r.from(), r.to(), r.sessions(), Exit.SHORT.window, false),
                BacktestService.COST_PCT, null, sums, trades);
    }
}
