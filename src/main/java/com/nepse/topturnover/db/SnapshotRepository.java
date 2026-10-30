package com.nepse.topturnover.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/** All reads and writes of crawled data. */
public final class SnapshotRepository {

    public record RankRow(int rank, String symbol, double value, Double close, Double changePct) {}

    private final Connection conn;

    public SnapshotRepository(Connection conn) {
        this.conn = conn;
    }

    public boolean hasDay(LocalDate day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM trading_day WHERE trade_date = ?")) {
            ps.setString(1, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Optional<LocalDate> latestDay() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(trade_date) FROM trading_day");
             ResultSet rs = ps.executeQuery()) {
            String d = rs.next() ? rs.getString(1) : null;
            return Optional.ofNullable(d).map(LocalDate::parse);
        }
    }

    /**
     * True when the snapshot is (almost) identical to the previous stored day: the site is still
     * showing the last session, which happens on unlisted holidays.
     */
    public boolean looksStale(MarketSnapshot s) throws SQLException {
        String prev;
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(trade_date) FROM trading_day WHERE trade_date < ?")) {
            ps.setString(1, s.tradeDate().toString());
            try (ResultSet rs = ps.executeQuery()) {
                prev = rs.next() ? rs.getString(1) : null;
            }
        }
        if (prev == null) return false;
        Map<String, double[]> last = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT symbol, close, volume FROM daily_price WHERE trade_date = ?")) {
            ps.setString(1, prev);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) last.put(rs.getString(1), new double[] {rs.getDouble(2), rs.getDouble(3)});
            }
        }
        if (last.isEmpty()) return false;
        long same = s.quotes().stream().filter(q -> {
            double[] p = last.get(q.symbol());
            return p != null && q.close() != null && q.volume() != null && p[0] == q.close() && p[1] == q.volume();
        }).count();
        return same >= 0.95 * s.quotes().size();
    }

    /** Stores the full snapshot plus the top-N lists in one transaction. Replaces the day if it exists. */
    public void save(MarketSnapshot s, int topN, ZonedDateTime crawledAt) throws SQLException {
        String day = s.tradeDate().toString();
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM trading_day WHERE trade_date = ?")) {
                ps.setString(1, day);
                ps.executeUpdate(); // cascades to daily_price and top_ranking
            }
            insertDay(s, crawledAt);
            insertPrices(s);
            upsertStocks(s);
            insertRanking(day, "TURNOVER", Rankings.topBy(s.quotes(), StockQuote::turnover, topN), StockQuote::turnover);
            insertRanking(day, "VOLUME", Rankings.topBy(s.quotes(), StockQuote::volume, topN), StockQuote::volume);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    private void insertDay(MarketSnapshot s, ZonedDateTime crawledAt) throws SQLException {
        double turnover = s.quotes().stream().map(StockQuote::turnover).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        double volume = s.quotes().stream().map(StockQuote::volume).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        List<Integer> trades = s.quotes().stream().map(StockQuote::trades).filter(Objects::nonNull).toList();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO trading_day(trade_date, source, crawled_at, stock_count, total_turnover, total_volume, total_trades, turnover_estimated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, s.tradeDate().toString());
            ps.setString(2, s.source());
            ps.setString(3, crawledAt.toOffsetDateTime().toString());
            ps.setInt(4, s.quotes().size());
            ps.setDouble(5, turnover);
            ps.setDouble(6, volume);
            if (trades.isEmpty()) ps.setNull(7, Types.INTEGER);
            else ps.setLong(7, trades.stream().mapToLong(Integer::longValue).sum());
            ps.setInt(8, s.turnoverEstimated() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void insertPrices(MarketSnapshot s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO daily_price(trade_date, symbol, open, high, low, close, ltp, prev_close, vwap,
                                                   volume, turnover, trades, change_pct, turnover_estimated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (StockQuote q : s.quotes()) {
                ps.setString(1, s.tradeDate().toString());
                ps.setString(2, q.symbol());
                real(ps, 3, q.open());
                real(ps, 4, q.high());
                real(ps, 5, q.low());
                real(ps, 6, q.close());
                real(ps, 7, q.ltp());
                real(ps, 8, q.prevClose());
                real(ps, 9, q.vwap());
                real(ps, 10, q.volume());
                real(ps, 11, q.turnover());
                if (q.trades() == null) ps.setNull(12, Types.INTEGER); else ps.setInt(12, q.trades());
                real(ps, 13, q.changePct());
                ps.setInt(14, q.turnoverEstimated() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void upsertStocks(MarketSnapshot s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO stock(symbol, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
                ON CONFLICT(symbol) DO UPDATE SET
                    name = COALESCE(excluded.name, stock.name),
                    first_seen = MIN(stock.first_seen, excluded.first_seen),
                    last_seen = MAX(stock.last_seen, excluded.last_seen)""")) {
            String day = s.tradeDate().toString();
            for (StockQuote q : s.quotes()) {
                ps.setString(1, q.symbol());
                ps.setString(2, q.name());
                ps.setString(3, day);
                ps.setString(4, day);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertRanking(String day, String type, List<StockQuote> top,
                               java.util.function.Function<StockQuote, Double> metric) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO top_ranking(trade_date, list_type, rank, symbol, value) VALUES (?, ?, ?, ?, ?)")) {
            int rank = 1;
            for (StockQuote q : top) {
                ps.setString(1, day);
                ps.setString(2, type);
                ps.setInt(3, rank++);
                ps.setString(4, q.symbol());
                ps.setDouble(5, metric.apply(q));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<RankRow> topList(LocalDate day, String listType) throws SQLException {
        List<RankRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT r.rank, r.symbol, r.value, p.close, p.change_pct
                FROM top_ranking r JOIN daily_price p ON p.trade_date = r.trade_date AND p.symbol = r.symbol
                WHERE r.trade_date = ? AND r.list_type = ? ORDER BY r.rank""")) {
            ps.setString(1, day.toString());
            ps.setString(2, listType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RankRow(rs.getInt(1), rs.getString(2), rs.getDouble(3), nullableDouble(rs, 4), nullableDouble(rs, 5)));
                }
            }
        }
        return out;
    }

    public void logCrawl(ZonedDateTime runAt, String source, String status, LocalDate tradeDate, String message) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO crawl_log(run_at, source, status, trade_date, message) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, runAt.toOffsetDateTime().toString());
            ps.setString(2, source);
            ps.setString(3, status);
            ps.setString(4, tradeDate == null ? null : tradeDate.toString());
            ps.setString(5, message);
            ps.executeUpdate();
        }
    }

    /** Applies a symbol -> sector map; returns the number of stocks updated or inserted. */
    public int importSectors(Map<String, String> sectors, LocalDate today) throws SQLException {
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO stock(symbol, sector, first_seen, last_seen) VALUES (?, ?, ?, ?)
                ON CONFLICT(symbol) DO UPDATE SET sector = excluded.sector""")) {
            for (var e : sectors.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.setString(3, today.toString());
                ps.setString(4, today.toString());
                n += ps.executeUpdate();
            }
        }
        return n;
    }

    private static Double nullableDouble(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static void real(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) ps.setNull(i, Types.REAL); else ps.setDouble(i, v);
    }
}
