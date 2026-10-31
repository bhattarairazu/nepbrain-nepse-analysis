package com.nepse.topturnover.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Raw floorsheet trades, per-day collection status, and per-day broker x stock totals. Nothing is deleted. */
@Repository
public class FloorsheetRepository {

    public record Row(String contractNo, LocalDate date, String symbol, int buyer, int seller, double quantity, double rate,
                      double amount) {}

    public record DayStatus(LocalDate date, LocalDateTime fetchedAt, int rowsStored, Integer rowsReported, Double totalAmount,
                            boolean complete, String note) {}

    private final JdbcTemplate jdbc;

    public FloorsheetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts trades, ignoring contract numbers already stored. */
    public void insert(List<Row> rows) {
        List<Object[]> args = new ArrayList<>(rows.size());
        for (Row r : rows) {
            args.add(new Object[] {r.contractNo(), r.date(), r.symbol(), r.buyer(), r.seller(), r.quantity(), r.rate(), r.amount()});
        }
        jdbc.batchUpdate("""
                INSERT IGNORE INTO floorsheet_trade(contract_no, trade_date, symbol, buyer, seller, quantity, rate, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", args);
    }

    /** Rebuilds the broker x stock totals for one session from its raw trades. */
    @Transactional
    public void aggregate(LocalDate day) {
        jdbc.update("DELETE FROM broker_stock_daily WHERE trade_date = ?", day); // derived data, rebuilt from the raw trades
        jdbc.update("""
                INSERT INTO broker_stock_daily(trade_date, symbol, broker, buy_qty, buy_amount, sell_qty, sell_amount, self_qty)
                SELECT trade_date, symbol, broker, SUM(bq), SUM(ba), SUM(sq), SUM(sa), SUM(selfq) FROM (
                    SELECT trade_date, symbol, buyer AS broker,
                           CASE WHEN buyer <> seller THEN quantity ELSE 0 END bq, CASE WHEN buyer <> seller THEN amount ELSE 0 END ba,
                           0 sq, 0 sa, CASE WHEN buyer = seller THEN quantity ELSE 0 END selfq
                    FROM floorsheet_trade WHERE trade_date = ?
                    UNION ALL
                    SELECT trade_date, symbol, seller AS broker, 0, 0,
                           CASE WHEN buyer <> seller THEN quantity ELSE 0 END, CASE WHEN buyer <> seller THEN amount ELSE 0 END, 0
                    FROM floorsheet_trade WHERE trade_date = ?
                ) x GROUP BY trade_date, symbol, broker""", day, day);
    }

    public int count(LocalDate day) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM floorsheet_trade WHERE trade_date = ?", Integer.class, day);
        return n == null ? 0 : n;
    }

    public double totalAmount(LocalDate day) {
        Double v = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM floorsheet_trade WHERE trade_date = ?", Double.class, day);
        return v == null ? 0 : v;
    }

    public void saveStatus(DayStatus s) {
        jdbc.update("""
                INSERT INTO floorsheet_day(trade_date, fetched_at, rows_stored, rows_reported, total_amount, complete, note)
                VALUES (?, ?, ?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE fetched_at = n.fetched_at, rows_stored = n.rows_stored, rows_reported = n.rows_reported,
                    total_amount = n.total_amount, complete = n.complete, note = n.note""",
                s.date(), s.fetchedAt(), s.rowsStored(), s.rowsReported(), s.totalAmount(), s.complete(), s.note());
    }

    public Optional<DayStatus> status(LocalDate day) {
        return jdbc.query("SELECT trade_date, fetched_at, rows_stored, rows_reported, total_amount, complete, note FROM floorsheet_day WHERE trade_date = ?",
                (rs, i) -> status(rs), day).stream().findFirst();
    }

    public List<DayStatus> recent(int n) {
        return jdbc.query("SELECT trade_date, fetched_at, rows_stored, rows_reported, total_amount, complete, note FROM floorsheet_day ORDER BY trade_date DESC LIMIT ?",
                (rs, i) -> status(rs), n);
    }

    /** Sessions that have floorsheet data, newest first. */
    public List<LocalDate> days(int n) {
        return jdbc.queryForList("SELECT DISTINCT trade_date FROM broker_stock_daily ORDER BY trade_date DESC LIMIT ?", LocalDate.class, n);
    }

    private static DayStatus status(java.sql.ResultSet rs) throws java.sql.SQLException {
        int reported = rs.getInt(4);
        boolean reportedNull = rs.wasNull();
        double amount = rs.getDouble(5);
        boolean amountNull = rs.wasNull();
        return new DayStatus(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDateTime.class), rs.getInt(3),
                reportedNull ? null : reported, amountNull ? null : amount, rs.getBoolean(6), rs.getString(7));
    }
}
