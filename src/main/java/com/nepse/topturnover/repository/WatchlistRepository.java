package com.nepse.topturnover.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** watchlist_item: setups flagged by the studies or added by hand, and how each one played out. Nothing is deleted. */
@Repository
public class WatchlistRepository {
    public record Item(long id, String symbol, String sector, String source, LocalDate signalDate, double signalClose, Double score,
                       String detail, double targetPct, double stopPct, int window, String status, LocalDate entryDate, Double entryPrice,
                       LocalDate exitDate, Double exitPrice, Double netPct, String note, boolean archived, LocalDateTime addedAt) {}

    private static final String COLUMNS = """
            id, symbol, sector, source, signal_date, signal_close, score, detail, target_pct, stop_pct, window_sessions, status,
            entry_date, entry_price, exit_date, exit_price, net_pct, note, archived, added_at""";

    private final JdbcTemplate jdbc;

    public WatchlistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Item> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM watchlist_item ORDER BY signal_date DESC, id DESC", (rs, i) -> map(rs));
    }

    /** Adds a signal unless the same stock, setup and day is already there; returns true if it was new. */
    public boolean addIfNew(String symbol, String sector, String source, LocalDate signalDate, double signalClose, Double score, String detail,
                            double targetPct, double stopPct, int window, String note) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return jdbc.update("""
                INSERT IGNORE INTO watchlist_item(symbol, sector, source, signal_date, signal_close, score, detail, target_pct, stop_pct,
                    window_sessions, note, added_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                symbol, sector, source, signalDate, signalClose, score, trim(detail), targetPct, stopPct, window, trim(note), now, now) > 0;
    }

    public void updateOutcome(long id, String status, LocalDate entryDate, Double entryPrice, LocalDate exitDate, Double exitPrice, Double netPct) {
        jdbc.update("""
                UPDATE watchlist_item SET status = ?, entry_date = ?, entry_price = ?, exit_date = ?, exit_price = ?, net_pct = ?, updated_at = ?
                WHERE id = ?""", status, entryDate, entryPrice, exitDate, exitPrice, netPct, LocalDateTime.now().withNano(0), id);
    }

    public void setArchived(long id, boolean archived) {
        jdbc.update("UPDATE watchlist_item SET archived = ?, updated_at = ? WHERE id = ?", archived, LocalDateTime.now().withNano(0), id);
    }

    public void setNote(long id, String note) {
        jdbc.update("UPDATE watchlist_item SET note = ?, updated_at = ? WHERE id = ?", trim(note), LocalDateTime.now().withNano(0), id);
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.strip();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static Item map(ResultSet rs) throws SQLException {
        return new Item(rs.getLong("id"), rs.getString("symbol"), rs.getString("sector"), rs.getString("source"),
                rs.getObject("signal_date", LocalDate.class), rs.getDouble("signal_close"), rs.getObject("score", Double.class),
                rs.getString("detail"), rs.getDouble("target_pct"), rs.getDouble("stop_pct"), rs.getInt("window_sessions"), rs.getString("status"),
                rs.getObject("entry_date", LocalDate.class), rs.getObject("entry_price", Double.class), rs.getObject("exit_date", LocalDate.class),
                rs.getObject("exit_price", Double.class), rs.getObject("net_pct", Double.class), rs.getString("note"), rs.getBoolean("archived"),
                rs.getObject("added_at", LocalDateTime.class));
    }
}
