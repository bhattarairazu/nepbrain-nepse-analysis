package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.FeatureCalculator.ListContext;

/**
 * Replays every stored session with only the data known that day (no look-ahead), applies each strategy,
 * and measures what happened next: buy at the next session's open, sell at the close h sessions later,
 * minus round-trip costs, compared with the equal-weight market over the same days.
 */
@Service
public class BacktestService {
    /** Approximate NEPSE round-trip cost: broker commission both sides, SEBON fee, DP charge. */
    public static final double COST_PCT = 0.8;
    public static final int MIN_HISTORY = 20;
    public static final double MIN_TURNOVER = 1e7; // Rs 1 crore traded on the signal day (liquidity)

    // confidence bar for the Recommendations section
    public static final int CONF_MIN_TRADES = 30;
    public static final double CONF_MIN_WIN = 55;
    public static final double CONF_MIN_EXCESS = 1.0;

    public enum Strategy {
        VOLUME_SURGE("Volume surge at the day's high",
                "Volume ≥ 2.5× normal, up ≥ 1.5%, close in the top 20% of the day's range, at a 10-session closing high, Rs 1 Cr+ traded."),
        BASE_BREAKOUT("Breakout from a quiet base (pattern 1)",
                "Close above a tight 15–40 session range (≤ 15%) on ≥ 2× volume."),
        STRONG_IDEA("Ideas: 'Research now' tier",
                "Active today, closed up near the high, not extended, Ideas score ≥ 60."),
        ACCUMULATION("Accumulation in the top-20 turnover",
                "In the day's top-20 turnover, up ≥ 1% on ≥ 1.5× volume, close in the top 30% of the range."),
        BASE_TRIGGER("Base breakout trigger (next-entry list)",
                "Tight base within 3% of its high; buys only if the next session closes above the base high on ≥ 2× volume."),
        PULLBACK_TRIGGER("Pullback trigger (next-entry list)",
                "Actively traded uptrend resting near its 20-day EMA; buys only if the next session closes above today's high."),
        TOP20_ALL("Baseline: every top-20 turnover stock",
                "Every stock in the day's top-20 turnover, no other filter. Shows whether the filters add anything.");

        private final String title;
        private final String rule;

        Strategy(String title, String rule) {
            this.title = title;
            this.rule = rule;
        }

        public String getTitle() { return title; }
        public String getRule() { return rule; }
    }

    public record Trade(Strategy strategy, String symbol, String sector, LocalDate signalDate, LocalDate entryDate,
                        double entry, LocalDate exitDate, double exit, double netPct, double marketPct, double excessPct) {}

    public record StrategyResult(Strategy strategy, int trades, double winRate, double avgNet, double medianNet,
                                 double avgExcess, Double excessFirstHalf, Double excessSecondHalf, double profitFactor,
                                 double best, double worst, String verdict, String tone, List<Trade> bestTrades,
                                 List<Trade> latest) {
        public boolean isConfident() { return "Confident".equals(verdict); }
    }

    public record CaseStudy(String symbol, LocalDate date, StockFeatures f, List<Strategy> fired,
                            Map<Integer, Double> forward, List<String> notes) {}

    public record Signal(Strategy strategy, StockFeatures f) {}

    /** A latest-session signal from a strategy that passed the confidence bar, with a suggested stop. */
    public record Recommendation(Signal signal, StrategyResult record, double stop) {}

    public record Result(LocalDate from, LocalDate to, int sessions, int horizon, int stocks,
                         List<StrategyResult> strategies, List<Signal> latestSignals, LocalDate latestDate,
                         boolean marketFilter, boolean marketUpToday) {
        public List<StrategyResult> confident() {
            return strategies.stream().filter(StrategyResult::isConfident).toList();
        }

        public List<Recommendation> recommendations() {
            List<Recommendation> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Signal s : latestSignals) {
                if (s.strategy() == Strategy.TOP20_ALL || !seen.add(s.f().symbol())) continue;
                strategies.stream().filter(r -> r.strategy() == s.strategy() && r.isConfident()).findFirst().ifPresent(r -> {
                    double close = s.f().close(), floor = close * (1 - Rules.DEFAULT_STOP);
                    double stop = s.f().low10() != null ? Math.max(s.f().low10() * 0.99, floor) : floor;
                    out.add(new Recommendation(s, r, Math.min(stop, close * 0.97)));
                });
            }
            return out;
        }
    }

    // ------------------------------------------------------------------

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, Result> cache = new HashMap<>();
    private Market cachedMarket;
    private LocalDate cachedFor;

    public BacktestService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized void evict() {
        cache.clear();
        cachedMarket = null;
        cachedFor = null;
    }

    public synchronized Optional<Result> run(int horizon) {
        return run(horizon, false);
    }

    /** @param marketFilter only take signals on days the equal-weight market closes above its 20-session average */
    public synchronized Optional<Result> run(int horizon, boolean marketFilter) {
        Market m = market();
        if (m == null || m.dates.length < MIN_HISTORY + horizon + 2) return Optional.empty();
        String key = m.dates[m.dates.length - 1] + ":" + horizon + ":" + marketFilter;
        Result cached = cache.get(key);
        if (cached != null) return Optional.of(cached);

        Map<Strategy, List<Trade>> trades = new LinkedHashMap<>();
        for (Strategy s : Strategy.values()) trades.put(s, new ArrayList<>());
        List<Signal> latest = new ArrayList<>();
        int last = m.dates.length - 1;

        for (int t = MIN_HISTORY; t <= last; t++) {
            if (marketFilter && !marketUp(m, t)) continue;
            for (Series s : m.series.values()) {
                int i = s.indexOf(t);
                if (i < MIN_HISTORY) continue;
                StockFeatures f = features(m, s, i, t);
                for (Strategy st : Strategy.values()) {
                    if (!fires(st, f, s, i)) continue;
                    if (t == last) latest.add(new Signal(st, f));
                    boolean trigger = st == Strategy.BASE_TRIGGER || st == Strategy.PULLBACK_TRIGGER;
                    if (trigger) {
                        // the condition must happen on the next session; then buy the session after
                        if (i + 1 >= s.size() || !triggered(st, f, s, i)) continue;
                        Trade tr = trade(m, s, st, i + 1, horizon);
                        if (tr != null) trades.get(st).add(tr);
                    } else {
                        Trade tr = trade(m, s, st, i, horizon);
                        if (tr != null) trades.get(st).add(tr);
                    }
                }
            }
        }

        LocalDate mid = m.dates[(MIN_HISTORY + last) / 2];
        List<StrategyResult> results = new ArrayList<>();
        trades.forEach((st, list) -> results.add(summarise(st, list, mid)));
        latest.sort(Comparator.comparing((Signal x) -> x.strategy().ordinal()).thenComparing(x -> -(x.f().turnover() == null ? 0 : x.f().turnover())));
        Result r = new Result(m.dates[MIN_HISTORY], m.dates[last], m.dates.length, horizon, m.series.size(),
                results, latest, m.dates[last], marketFilter, marketUp(m, last));
        cache.put(key, r);
        return Optional.of(r);
    }

    /** What every strategy said about one stock on one day, and what happened afterwards. */
    public synchronized Optional<CaseStudy> caseStudy(String symbol, LocalDate date) {
        Market m = market();
        if (m == null) return Optional.empty();
        Series s = m.series.get(symbol);
        if (s == null) return Optional.empty();
        int t = Arrays.binarySearch(m.dates, date);
        if (t < 0) return Optional.empty();
        int i = s.indexOf(t);
        if (i < 0) return Optional.empty();
        List<String> notes = new ArrayList<>();
        if (i < MIN_HISTORY) notes.add("Only " + i + " earlier sessions stored for this stock; strategies need " + MIN_HISTORY + ".");
        StockFeatures f = features(m, s, i, t);
        List<Strategy> fired = new ArrayList<>();
        if (i >= MIN_HISTORY) for (Strategy st : Strategy.values()) if (fires(st, f, s, i)) fired.add(st);
        Map<Integer, Double> fwd = new LinkedHashMap<>();
        for (int h : new int[] {1, 2, 5, 10}) {
            if (i + h < s.size()) fwd.put(h, (s.close[i + h] / s.close[i] - 1) * 100);
        }
        if (f.turnover() != null && f.turnover() < MIN_TURNOVER) notes.add("Traded less than Rs 1 Cr that day, below the liquidity filter.");
        return Optional.of(new CaseStudy(symbol, date, f, fired, fwd, notes));
    }

    // ------------------------------------------------------------------ strategy rules

    static boolean fires(Strategy st, StockFeatures f, Series s, int i) {
        if (!Sectors.isEquity(f.sector()) || f.close() == null || f.changePct() == null) return false;
        boolean liquid = f.turnover() != null && f.turnover() >= MIN_TURNOVER;
        return switch (st) {
            case VOLUME_SURGE -> liquid && f.rvol() != null && f.rvol() >= 2.5 && f.changePct() >= 1.5
                    && f.closeInRange() != null && f.closeInRange() >= 0.8
                    && f.close() >= Indicators.max(s.close, i - 10, i) && !warning(f);
            case BASE_BREAKOUT -> liquid && f.has(Pattern.BASE_BREAKOUT);
            case STRONG_IDEA -> {
                boolean active = f.turnoverRank() != null || (f.rvol() != null && f.rvol() >= Rules.RVOL_ACTIVE);
                yield liquid && active && f.closedUpNearHigh() && IdeaScorer.dropReason(f) == null
                        && IdeaScorer.score(f, null).tier() == IdeaScorer.Tier.STRONG;
            }
            case ACCUMULATION -> f.turnoverRank() != null && f.changePct() >= 1 && f.rvol() != null && f.rvol() >= 1.5
                    && f.closeInRange() != null && f.closeInRange() >= 0.7 && !warning(f);
            case BASE_TRIGGER -> liquid && f.baseForming() && !f.has(Pattern.BASE_BREAKOUT);
            case PULLBACK_TRIGGER -> SignalService.isPullback(f);
            case TOP20_ALL -> f.turnoverRank() != null;
        };
    }

    /** Equal-weight market above its average of the previous 20 sessions on session t. */
    static boolean marketUp(Market m, int t) {
        if (t < 20) return false;
        double avg = 0;
        for (int k = t - 20; k < t; k++) avg += m.index[k];
        return m.index[t] > avg / 20;
    }

    private static boolean warning(StockFeatures f) {
        return f.has(Pattern.CLIMAX_TOP) || f.has(Pattern.CIRCUIT_RUN);
    }

    /** Did the next session meet the trigger condition? (Uses session i+1, which is in the future of the signal day.) */
    private static boolean triggered(Strategy st, StockFeatures f, Series s, int i) {
        double nextClose = s.close[i + 1];
        if (st == Strategy.BASE_TRIGGER) {
            Double avgVol = Indicators.mean(s.volume, i + 1 - Rules.AVG_SESSIONS, i + 1, Rules.MIN_HISTORY);
            return f.baseHigh() != null && nextClose > f.baseHigh() * 1.005 && avgVol != null && s.volume[i + 1] >= 2 * avgVol;
        }
        return f.high() != null && nextClose > f.high() * 1.005;
    }

    // ------------------------------------------------------------------ trade simulation

    /** Signal known after the close of session i: buy at the open of i+1, sell at the close of i+h. */
    private static Trade trade(Market m, Series s, Strategy st, int i, int h) {
        if (i + h >= s.size()) return null;
        int entryIdx = i + 1;
        double entry = s.open[entryIdx] > 0 ? s.open[entryIdx] : s.close[i];
        double exit = s.close[i + h];
        if (entry <= 0 || exit <= 0) return null;
        double net = (exit / entry - 1) * 100 - COST_PCT;
        int tEntry = s.globalIndex[entryIdx], tExit = s.globalIndex[i + h];
        double mkt = (m.index[tExit] / m.index[tEntry - 1] - 1) * 100;
        return new Trade(st, s.symbol, s.sector, s.dates[i], s.dates[entryIdx], entry, s.dates[i + h], exit, net, mkt, net - mkt);
    }

    private static StrategyResult summarise(Strategy st, List<Trade> list, LocalDate mid) {
        int n = list.size();
        if (n == 0) return new StrategyResult(st, 0, 0, 0, 0, 0, null, null, 0, 0, 0, "No trades", "neutral", List.of(), List.of());
        double[] net = list.stream().mapToDouble(Trade::netPct).sorted().toArray();
        double wins = list.stream().filter(t -> t.netPct() > 0).count();
        double gain = list.stream().filter(t -> t.netPct() > 0).mapToDouble(Trade::netPct).sum();
        double loss = -list.stream().filter(t -> t.netPct() <= 0).mapToDouble(Trade::netPct).sum();
        double avgEx = list.stream().mapToDouble(Trade::excessPct).average().orElse(0);
        Double ex1 = half(list.stream().filter(t -> t.signalDate().isBefore(mid)).toList());
        Double ex2 = half(list.stream().filter(t -> !t.signalDate().isBefore(mid)).toList());
        double win = wins / n * 100, avg = Arrays.stream(net).average().orElse(0), median = net[n / 2];

        String verdict, tone;
        if (n >= CONF_MIN_TRADES && win >= CONF_MIN_WIN && avgEx >= CONF_MIN_EXCESS && median > 0 && ex1 != null && ex1 > 0 && ex2 != null && ex2 > 0) {
            verdict = "Confident"; tone = "good";
        } else if (avgEx > 0 && n >= 10) {
            verdict = "Promising, not proven"; tone = "warn";
        } else if (n < 10) {
            verdict = "Too few trades"; tone = "neutral";
        } else {
            verdict = "Doesn't beat the market"; tone = "bad";
        }
        List<Trade> best = list.stream().sorted(Comparator.comparingDouble(Trade::netPct).reversed()).limit(8).toList();
        List<Trade> latest = list.stream().sorted(Comparator.comparing(Trade::signalDate).reversed()).limit(8).toList();
        return new StrategyResult(st, n, win, avg, median, avgEx, ex1, ex2, loss > 0 ? gain / loss : Double.POSITIVE_INFINITY,
                net[n - 1], net[0], verdict, tone, best, latest);
    }

    private static Double half(List<Trade> trades) {
        return trades.isEmpty() ? null : trades.stream().mapToDouble(Trade::excessPct).average().orElse(0);
    }

    // ------------------------------------------------------------------ data

    /** Every stock's full stored history, aligned to the global session calendar. */
    static final class Series {
        final String symbol, name, sector;
        final LocalDate[] dates;
        final int[] globalIndex;
        final double[] open, high, low, close, volume, turnover, trades, change;
        private final Map<Integer, Integer> byGlobal = new HashMap<>();

        Series(String symbol, String name, String sector, int n) {
            this.symbol = symbol; this.name = name; this.sector = sector;
            dates = new LocalDate[n]; globalIndex = new int[n];
            open = new double[n]; high = new double[n]; low = new double[n]; close = new double[n];
            volume = new double[n]; turnover = new double[n]; trades = new double[n]; change = new double[n];
        }

        int size() { return dates.length; }

        int indexOf(int global) { return byGlobal.getOrDefault(global, -1); }
    }

    static final class Market {
        LocalDate[] dates;
        double[] index; // equal-weight market, chained
        Map<String, Series> series = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> turnoverRank = new HashMap<>(), volumeRank = new HashMap<>();
    }

    private StockFeatures features(Market m, Series s, int i, int t) {
        int n = i + 1;
        StockSeries prefix = new StockSeries(s.symbol, s.name, s.sector, Arrays.copyOf(s.dates, n),
                Arrays.copyOf(s.open, n), Arrays.copyOf(s.high, n), Arrays.copyOf(s.low, n), Arrays.copyOf(s.close, n),
                Arrays.copyOf(s.volume, n), Arrays.copyOf(s.turnover, n), Arrays.copyOf(s.trades, n), Arrays.copyOf(s.change, n));
        int app = 0;
        for (int k = Math.max(0, t - 9); k <= t; k++) if (m.turnoverRank.getOrDefault(k, Map.of()).containsKey(s.symbol)) app++;
        return FeatureCalculator.compute(prefix, new ListContext(
                m.turnoverRank.getOrDefault(t, Map.of()).get(s.symbol), m.volumeRank.getOrDefault(t, Map.of()).get(s.symbol), app));
    }

    private Market market() {
        LocalDate latest = jdbc.queryForObject("SELECT MAX(trade_date) FROM trading_day", Map.of(), LocalDate.class);
        if (latest == null) return null;
        if (cachedMarket != null && latest.equals(cachedFor)) return cachedMarket;
        Market m = new Market();
        List<LocalDate> dates = jdbc.queryForList("SELECT trade_date FROM trading_day ORDER BY trade_date", Map.of(), LocalDate.class);
        m.dates = dates.toArray(LocalDate[]::new);
        Map<LocalDate, Integer> pos = new HashMap<>();
        for (int k = 0; k < m.dates.length; k++) pos.put(m.dates[k], k);

        record Row(String sym, String name, String sector, int t, double o, double h, double l, double c, double v, double to, double tr, double ch) {}
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.symbol, s.name, s.sector, p.trade_date, p.open_price, p.high_price, p.low_price, p.close_price,
                       p.volume, p.turnover, p.trades, p.change_pct
                FROM daily_price p LEFT JOIN stock s ON s.symbol = p.symbol ORDER BY p.symbol, p.trade_date""", Map.of(), rs -> {
            rows.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                    pos.get(rs.getObject(4, LocalDate.class)), d(rs, 5), d(rs, 6), d(rs, 7), d(rs, 8), d(rs, 9), d(rs, 10), d(rs, 11), d(rs, 12)));
        });
        double[] sum = new double[m.dates.length];
        int[] cnt = new int[m.dates.length];
        for (List<Row> list : rows.values()) {
            Row first = list.get(0);
            Series s = new Series(first.sym(), first.name(), first.sector(), list.size());
            for (int k = 0; k < list.size(); k++) {
                Row r = list.get(k);
                s.dates[k] = m.dates[r.t()]; s.globalIndex[k] = r.t(); s.byGlobal.put(r.t(), k);
                s.open[k] = r.o(); s.high[k] = r.h(); s.low[k] = r.l(); s.close[k] = r.c();
                s.volume[k] = r.v(); s.turnover[k] = r.to(); s.trades[k] = r.tr(); s.change[k] = r.ch();
                if (Sectors.isEquity(r.sector()) && !Double.isNaN(r.ch()) && Math.abs(r.ch()) <= 10.5) { sum[r.t()] += r.ch(); cnt[r.t()]++; }
            }
            for (int k = 1; k < s.size(); k++) if (Double.isNaN(s.close[k])) s.close[k] = s.close[k - 1];
            m.series.put(s.symbol, s);
        }
        m.index = new double[m.dates.length];
        double level = 1000;
        for (int k = 0; k < m.dates.length; k++) {
            if (k > 0 && cnt[k] > 0) level *= 1 + sum[k] / cnt[k] / 100;
            m.index[k] = level;
        }
        jdbc.query("SELECT trade_date, list_type, symbol, rank_no FROM top_ranking", Map.of(), rs -> {
            Integer t = pos.get(rs.getObject(1, LocalDate.class));
            if (t == null) return;
            (rs.getString(2).equals("TURNOVER") ? m.turnoverRank : m.volumeRank)
                    .computeIfAbsent(t, k -> new HashMap<>()).put(rs.getString(3), rs.getInt(4));
        });
        cachedMarket = m;
        cachedFor = latest;
        return m;
    }

    private static double d(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? Double.NaN : v;
    }
}
