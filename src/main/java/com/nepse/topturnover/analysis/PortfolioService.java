package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Replays stored backtest trades as a real account would: limited capital, a fixed share of the account per trade,
 * a maximum number of open positions, no second position in the same stock, capital gains tax on each winning trade.
 * Signals that arrive when the money is already invested are skipped, which is what separates a per-trade backtest
 * from what an account actually earns.
 */
@Service
public class PortfolioService {
    public static final double CGT = 0.075; // individual, held under a year

    public enum Period { ALL, DESIGN, OUT_OF_SAMPLE, CUSTOM }
    public enum Priority { RANDOM, ALPHABETICAL, RULE_ORDER }

    /** A strategy from the latest stored run of its kind, e.g. "ADVANCED:BB_DRYUP". */
    public record Option(String key, String kind, String strategy, String title, int trades, Double avgNet, String verdict) {}

    public record Settings(double capital, double positionPct, int maxPositions, boolean tax, Period period,
                           LocalDate from, LocalDate to, List<String> strategies, Priority priority) {}

    public record TakenTrade(String strategy, String symbol, LocalDate entry, LocalDate exit, double amount, double netPct,
                             double profit, double tax) {}

    public record Point(LocalDate date, double equity, double market) {}

    public record Result(Settings settings, LocalDate from, LocalDate to, double finalValue, double returnPct, Double marketPct,
                         int signals, int taken, int skipped, int winners, double taxPaid, double maxDrawdownPct,
                         double avgAll, double avgTaken, double avgSkipped, List<Point> equity, List<TakenTrade> trades) {}

    private record Signal(String strategy, String symbol, LocalDate entry, LocalDate exit, double net, int order) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final BacktestService backtest;

    public PortfolioService(NamedParameterJdbcTemplate jdbc, BacktestService backtest) {
        this.jdbc = jdbc;
        this.backtest = backtest;
    }

    /** Strategies available to simulate: the latest run of each kind that uses adjusted prices and respects T+2. */
    public List<Option> options() {
        List<Option> out = new ArrayList<>();
        latestRuns().forEach((kind, runId) -> jdbc.query("""
                SELECT strategy, title, trades, avg_net_pct, verdict FROM backtest_result WHERE run_id = :r ORDER BY title""",
                Map.of("r", runId), rs -> {
                    double avg = rs.getDouble(4);
                    out.add(new Option(kind + ":" + rs.getString(1), kind, rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.wasNull() ? null : avg, rs.getString(5)));
                }));
        return out;
    }

    /** kind -> run id (CUSTOM: the 5-session, no-market-filter run). */
    private Map<String, Long> latestRuns() {
        Map<String, Long> runs = new LinkedHashMap<>();
        for (String kind : List.of("ADVANCED", "STANDARD", "CUSTOM")) {
            String extra = kind.equals("CUSTOM") ? " AND horizon = 5 AND market_filter = 0" : "";
            List<Long> id = jdbc.queryForList("SELECT id FROM backtest_run WHERE kind = :k AND price_adjusted = 1 AND t2_settlement = 1" + extra
                    + " ORDER BY data_to DESC, sessions DESC, id DESC LIMIT 1", Map.of("k", kind), Long.class);
            if (!id.isEmpty()) runs.put(kind, id.get(0));
        }
        return runs;
    }

    public Optional<Result> simulate(Settings s) {
        Map<String, Long> runs = latestRuns();
        if (runs.isEmpty() || s.strategies().isEmpty()) return Optional.empty();

        List<LocalDate> days = jdbc.queryForList("SELECT trade_date FROM trading_day ORDER BY trade_date", Map.of(), LocalDate.class);
        if (days.isEmpty()) return Optional.empty();
        LocalDate split = days.get((int) Math.round(days.size() * AdvancedStrategyService.DESIGN_SHARE));
        LocalDate from = switch (s.period()) {
            case OUT_OF_SAMPLE -> split;
            case CUSTOM -> s.from() != null ? s.from() : days.get(0);
            default -> days.get(0);
        };
        LocalDate to = switch (s.period()) {
            case DESIGN -> split;
            case CUSTOM -> s.to() != null ? s.to() : days.get(days.size() - 1);
            default -> days.get(days.size() - 1);
        };

        List<Signal> signals = new ArrayList<>();
        for (int k = 0; k < s.strategies().size(); k++) {
            String[] key = s.strategies().get(k).split(":", 2);
            Long run = key.length == 2 ? runs.get(key[0]) : null;
            if (run == null) continue;
            final int order = k;
            jdbc.query("""
                    SELECT strategy, symbol, entry_date, exit_date, net_pct FROM backtest_trade
                    WHERE run_id = :r AND strategy = :s AND entry_date >= :f AND exit_date <= :t""",
                    new MapSqlParameterSource().addValue("r", run).addValue("s", key[1]).addValue("f", from).addValue("t", to),
                    rs -> {
                        signals.add(new Signal(key[0] + ":" + rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                                rs.getObject(4, LocalDate.class), rs.getDouble(5), order));
                    });
        }
        if (signals.isEmpty()) return Optional.empty();
        order(signals, s.priority());

        // ---- replay
        double cash = s.capital(), taxPaid = 0;
        List<Map.Entry<Signal, Double>> open = new ArrayList<>();
        List<TakenTrade> taken = new ArrayList<>();
        List<Signal> skipped = new ArrayList<>();
        Map<LocalDate, Double> realisedByDate = new HashMap<>();
        int winners = 0;
        for (Signal sig : signals) {
            // close everything that exited on or before this entry date
            for (var it = open.iterator(); it.hasNext(); ) {
                var p = it.next();
                if (!p.getKey().exit().isAfter(sig.entry())) {
                    double[] r = settle(p.getKey(), p.getValue(), s.tax());
                    cash += p.getValue() + r[0] - r[1];
                    taxPaid += r[1];
                    if (r[0] > 0) winners++;
                    taken.add(new TakenTrade(p.getKey().strategy(), p.getKey().symbol(), p.getKey().entry(), p.getKey().exit(),
                            p.getValue(), p.getKey().net(), r[0], r[1]));
                    it.remove();
                }
            }
            boolean held = open.stream().anyMatch(p -> p.getKey().symbol().equals(sig.symbol()));
            double equityAtCost = cash + open.stream().mapToDouble(Map.Entry::getValue).sum();
            double size = Math.min(equityAtCost * s.positionPct() / 100, cash);
            if (held || open.size() >= s.maxPositions() || size < equityAtCost * s.positionPct() / 100 * 0.5) {
                skipped.add(sig);
                continue;
            }
            cash -= size;
            open.add(Map.entry(sig, size));
        }
        for (var p : open) {
            double[] r = settle(p.getKey(), p.getValue(), s.tax());
            cash += p.getValue() + r[0] - r[1];
            taxPaid += r[1];
            if (r[0] > 0) winners++;
            taken.add(new TakenTrade(p.getKey().strategy(), p.getKey().symbol(), p.getKey().entry(), p.getKey().exit(),
                    p.getValue(), p.getKey().net(), r[0], r[1]));
        }
        taken.sort(Comparator.comparing(TakenTrade::entry));

        // ---- equity curve (open positions at cost until they exit) and market
        List<Point> curve = curve(s.capital(), taken, days, from, to);
        double peak = s.capital(), dd = 0;
        for (Point p : curve) { peak = Math.max(peak, p.equity()); dd = Math.min(dd, p.equity() / peak - 1); }
        Double market = curve.isEmpty() ? null : (curve.get(curve.size() - 1).market() / 100 - 1) * 100;

        return Optional.of(new Result(s, from, to, cash, (cash / s.capital() - 1) * 100, market, signals.size(), taken.size(),
                skipped.size(), winners, taxPaid, dd * 100,
                signals.stream().mapToDouble(Signal::net).average().orElse(0),
                taken.stream().mapToDouble(TakenTrade::netPct).average().orElse(0),
                skipped.stream().mapToDouble(Signal::net).average().orElse(0),
                curve, taken));
    }

    /** {profit, tax} for a closed position. */
    private static double[] settle(Signal sig, double amount, boolean tax) {
        double profit = amount * sig.net() / 100;
        return new double[] {profit, tax && profit > 0 ? profit * CGT : 0};
    }

    private static void order(List<Signal> signals, Priority p) {
        Random rnd = new Random(42);
        Map<Signal, Double> tiebreak = new HashMap<>();
        signals.forEach(x -> tiebreak.put(x, rnd.nextDouble()));
        Comparator<Signal> sameDay = switch (p) {
            case ALPHABETICAL -> Comparator.comparing(Signal::symbol);
            case RULE_ORDER -> Comparator.comparingInt(Signal::order).thenComparing(tiebreak::get);
            default -> Comparator.comparing(tiebreak::get);
        };
        signals.sort(Comparator.comparing(Signal::entry).thenComparing(sameDay));
    }

    private List<Point> curve(double capital, List<TakenTrade> trades, List<LocalDate> days, LocalDate from, LocalDate to) {
        BacktestService.Market m = backtest.data();
        Map<LocalDate, Double> index = new HashMap<>();
        if (m != null) for (int i = 0; i < m.dates.length; i++) index.put(m.dates[i], m.index[i]);
        List<LocalDate> span = days.stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
        if (span.isEmpty()) return List.of();
        double base = index.getOrDefault(span.get(0), 1.0);
        List<Point> out = new ArrayList<>();
        for (LocalDate d : span) {
            double realised = 0;
            for (TakenTrade t : trades) if (!t.exit().isAfter(d)) realised += t.profit() - t.tax();
            out.add(new Point(d, capital + realised, index.getOrDefault(d, base) / base * 100));
        }
        return Collections.unmodifiableList(out);
    }
}
