package com.nepse.topturnover.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permanent storage of backtest runs, strategy summaries and every simulated trade.
 * There are deliberately no delete methods.
 */
@Repository
public class BacktestRepository {

    public record RunKey(String kind, LocalDate from, LocalDate to, int sessions, Integer horizon, boolean marketFilter) {}

    public record SummaryRow(String strategy, String title, int trades, Double winRate, Double avgNet, Double medianNet,
                             Double avgVsMarket, Double firstHalf, Double secondHalf, Double profitFactor, String verdict) {}

    public record TradeRow(String strategy, String symbol, LocalDate signal, LocalDate entryDate, double entry,
                           LocalDate exitDate, double exit, Integer held, String exitReason, double netPct, Double marketPct) {}

    public record SavedRun(long id, LocalDateTime runAt, String kind, LocalDate from, LocalDate to, int sessions,
                           Integer horizon, boolean marketFilter, int trades, boolean priceAdjusted) {}

    private final JdbcTemplate jdbc;

    public BacktestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a run unless an identical one exists. Returns the run id (existing or new). */
    @Transactional
    public long saveIfNew(RunKey k, double costPct, Double marketReturnPct, List<SummaryRow> summaries, List<TradeRow> trades) {
        List<Long> existing = jdbc.queryForList("""
                SELECT id FROM backtest_run WHERE kind = ? AND data_from = ? AND data_to = ? AND sessions = ?
                  AND horizon <=> ? AND market_filter = ? AND price_adjusted = 1""", Long.class,
                k.kind(), k.from(), k.to(), k.sessions(), k.horizon(), k.marketFilter());
        if (!existing.isEmpty()) return existing.get(0);

        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO backtest_run(run_at, kind, data_from, data_to, sessions, horizon, market_filter, price_adjusted, cost_pct, market_return_pct)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, LocalDateTime.now().withNano(0));
            ps.setString(2, k.kind());
            ps.setObject(3, k.from());
            ps.setObject(4, k.to());
            ps.setInt(5, k.sessions());
            ps.setObject(6, k.horizon());
            ps.setBoolean(7, k.marketFilter());
            ps.setDouble(8, costPct);
            ps.setObject(9, marketReturnPct);
            return ps;
        }, key);
        long runId = key.getKey().longValue();

        List<Object[]> s = new ArrayList<>();
        for (SummaryRow r : summaries) {
            s.add(new Object[] {runId, r.strategy(), r.title(), r.trades(), finite(r.winRate()), finite(r.avgNet()), finite(r.medianNet()),
                    finite(r.avgVsMarket()), finite(r.firstHalf()), finite(r.secondHalf()), finite(r.profitFactor()), r.verdict()});
        }
        jdbc.batchUpdate("""
                INSERT INTO backtest_result(run_id, strategy, title, trades, win_rate, avg_net_pct, median_net_pct, avg_vs_market,
                                            first_half, second_half, profit_factor, verdict)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", s);

        List<Object[]> t = new ArrayList<>();
        for (TradeRow r : trades) {
            t.add(new Object[] {runId, r.strategy(), r.symbol(), r.signal(), r.entryDate(), r.entry(), r.exitDate(), r.exit(),
                    r.held(), r.exitReason(), r.netPct(), finite(r.marketPct())});
        }
        jdbc.batchUpdate("""
                INSERT INTO backtest_trade(run_id, strategy, symbol, signal_date, entry_date, entry_price, exit_date, exit_price,
                                           held, exit_reason, net_pct, market_pct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", t);
        return runId;
    }

    public List<SavedRun> recentRuns(int limit) {
        return jdbc.query("""
                SELECT r.id, r.run_at, r.kind, r.data_from, r.data_to, r.sessions, r.horizon, r.market_filter,
                       (SELECT COUNT(*) FROM backtest_trade t WHERE t.run_id = r.id), r.price_adjusted
                FROM backtest_run r ORDER BY r.id DESC LIMIT ?""",
                (rs, i) -> new SavedRun(rs.getLong(1), rs.getObject(2, LocalDateTime.class), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getInt(6),
                        (Integer) rs.getObject(7), rs.getBoolean(8), rs.getInt(9), rs.getBoolean(10)), limit);
    }

    private static Double finite(Double d) {
        return d == null || d.isNaN() || d.isInfinite() ? null : d;
    }
}
