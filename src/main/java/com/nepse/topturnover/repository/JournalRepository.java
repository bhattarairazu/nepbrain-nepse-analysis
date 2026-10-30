package com.nepse.topturnover.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.nepse.topturnover.journal.JournalTrade;

/** journal_trade: your own logged trades. */
@Repository
public class JournalRepository {
    private static final String COLUMNS = """
            id, symbol, sector, rule_name, market_regime, entry_date, entry_price, quantity, stop_price, target_price,
            mood_entry, entry_notes, exit_date, exit_price, exit_reason, mood_exit, followed_plan, mistakes, lesson""";

    private final JdbcTemplate jdbc;

    public JournalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<JournalTrade> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM journal_trade ORDER BY entry_date DESC, id DESC", (rs, i) -> map(rs));
    }

    public Optional<JournalTrade> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM journal_trade WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    public long save(JournalTrade t) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Object[] values = {t.symbol(), t.sector(), t.rule(), t.marketRegime(), t.entryDate(), t.entryPrice(), t.quantity(),
                t.stopPrice(), t.targetPrice(), t.moodEntry(), t.entryNotes(), t.exitDate(), t.exitPrice(), t.exitReason(),
                t.moodExit(), t.followedPlan(), t.mistakes(), t.lesson()};
        if (t.id() == null) {
            Object[] insert = concat(values, now, now);
            org.springframework.jdbc.support.GeneratedKeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO journal_trade(symbol, sector, rule_name, market_regime, entry_date, entry_price, quantity, stop_price,
                            target_price, mood_entry, entry_notes, exit_date, exit_price, exit_reason, mood_exit, followed_plan, mistakes,
                            lesson, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", java.sql.Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < insert.length; i++) ps.setObject(i + 1, insert[i]);
                return ps;
            }, key);
            return key.getKey().longValue();
        }
        jdbc.update("""
                UPDATE journal_trade SET symbol = ?, sector = ?, rule_name = ?, market_regime = ?, entry_date = ?, entry_price = ?,
                    quantity = ?, stop_price = ?, target_price = ?, mood_entry = ?, entry_notes = ?, exit_date = ?, exit_price = ?,
                    exit_reason = ?, mood_exit = ?, followed_plan = ?, mistakes = ?, lesson = ?, updated_at = ?
                WHERE id = ?""", concat(values, now, t.id()));
        return t.id();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM journal_trade WHERE id = ?", id);
    }

    /** Latest stored close for a symbol: {date, close}, or empty. */
    public Optional<Object[]> latestClose(String symbol) {
        return jdbc.query("SELECT trade_date, close_price FROM daily_price WHERE symbol = ? ORDER BY trade_date DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getDouble(2)}, symbol).stream().findFirst();
    }

    public Optional<String> sectorOf(String symbol) {
        return jdbc.queryForList("SELECT sector FROM stock WHERE symbol = ?", String.class, symbol).stream()
                .filter(s -> s != null).findFirst();
    }

    public List<String> symbols() {
        return jdbc.queryForList("SELECT symbol FROM stock ORDER BY symbol", String.class);
    }

    private static Object[] concat(Object[] a, Object... b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static JournalTrade map(ResultSet rs) throws SQLException {
        return new JournalTrade(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class), rs.getDouble(7), rs.getInt(8), dbl(rs, 9), dbl(rs, 10), rs.getString(11),
                rs.getString(12), rs.getObject(13, LocalDate.class), dbl(rs, 14), rs.getString(15), rs.getString(16),
                (Boolean) rs.getObject(17, Boolean.class), rs.getString(18), rs.getString(19));
    }

    private static Double dbl(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }
}
