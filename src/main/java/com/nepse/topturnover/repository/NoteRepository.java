package com.nepse.topturnover.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** note and note_condition: your own notes and the conditions you want checked. */
@Repository
public class NoteRepository {
    public record Note(long id, LocalDate forDate, String symbol, String title, String body, String status, String decision,
                       LocalDateTime createdAt, LocalDateTime updatedAt, List<Condition> conditions) {}

    public record Condition(long id, int position, String kind, String text, String symbol, String metric, String op, Double value,
                            boolean checked) {}

    /** A condition to store (id and position are assigned on save). */
    public record NewCondition(String kind, String text, String symbol, String metric, String op, Double value, boolean checked) {}

    /** One stored session of a stock (actual traded prices). */
    public record Day(LocalDate date, double close, double high, double low, Double changePct, Double turnover, Double volume) {}

    private final JdbcTemplate jdbc;

    public NoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Notes, newest date first; optionally only those about one stock. */
    public List<Note> all(String symbol) {
        String where = symbol == null ? "" : " WHERE n.symbol = ? OR EXISTS (SELECT 1 FROM note_condition c WHERE c.note_id = n.id AND c.symbol = ?)";
        Object[] args = symbol == null ? new Object[0] : new Object[] {symbol, symbol};
        Map<Long, Note> notes = new LinkedHashMap<>();
        jdbc.query("SELECT n.id, n.for_date, n.symbol, n.title, n.body, n.status, n.decision, n.created_at, n.updated_at FROM note n" + where
                + " ORDER BY n.for_date DESC, n.id DESC", rs -> { Note x = note(rs, new ArrayList<>()); notes.put(x.id(), x); }, args);
        if (notes.isEmpty()) return List.of();
        String in = String.join(",", notes.keySet().stream().map(String::valueOf).toList());
        jdbc.query("SELECT id, note_id, position, kind, text, symbol, metric, op, value, checked FROM note_condition WHERE note_id IN (" + in + ") ORDER BY note_id, position",
                rs -> { notes.get(rs.getLong("note_id")).conditions().add(condition(rs)); });
        return List.copyOf(notes.values());
    }

    public Optional<Note> find(long id) {
        return all(null).stream().filter(n -> n.id() == id).findFirst();
    }

    public int countDue(LocalDate today) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM note WHERE status = 'OPEN' AND for_date <= ?", Integer.class, today);
        return n == null ? 0 : n;
    }

    public long create(LocalDate forDate, String symbol, String title, String body, List<NewCondition> conditions) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO note(for_date, symbol, title, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, forDate); ps.setString(2, symbol); ps.setString(3, title); ps.setString(4, body); ps.setObject(5, now); ps.setObject(6, now);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        saveConditions(id, conditions);
        return id;
    }

    public void update(long id, LocalDate forDate, String symbol, String title, String body, List<NewCondition> conditions) {
        jdbc.update("UPDATE note SET for_date = ?, symbol = ?, title = ?, body = ?, updated_at = ? WHERE id = ?",
                forDate, symbol, title, body, LocalDateTime.now().withNano(0), id);
        jdbc.update("DELETE FROM note_condition WHERE note_id = ?", id);
        saveConditions(id, conditions);
    }

    public void setStatus(long id, String status, String decision) {
        jdbc.update("UPDATE note SET status = ?, decision = ?, updated_at = ? WHERE id = ?", status, decision, LocalDateTime.now().withNano(0), id);
    }

    public void setChecked(long conditionId, boolean checked) {
        jdbc.update("UPDATE note_condition SET checked = ? WHERE id = ? AND kind = 'MANUAL'", checked, conditionId);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM note WHERE id = ?", id);
    }

    /** The stock's sessions from a date on, oldest first. */
    public List<Day> days(String symbol, LocalDate from) {
        return jdbc.query("""
                SELECT trade_date, close_price, high_price, low_price, change_pct, turnover, volume FROM daily_price
                WHERE symbol = ? AND trade_date >= ? ORDER BY trade_date""",
                (rs, i) -> new Day(rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getObject(5, Double.class),
                        rs.getObject(6, Double.class), rs.getObject(7, Double.class)), symbol, from);
    }

    /** The stock's latest stored session. */
    public Optional<Day> latest(String symbol) {
        return jdbc.query("""
                SELECT trade_date, close_price, high_price, low_price, change_pct, turnover, volume FROM daily_price
                WHERE symbol = ? ORDER BY trade_date DESC LIMIT 1""",
                (rs, i) -> new Day(rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getObject(5, Double.class),
                        rs.getObject(6, Double.class), rs.getObject(7, Double.class)), symbol).stream().findFirst();
    }

    public Optional<LocalDate> latestSession() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT MAX(trade_date) FROM trading_day", LocalDate.class));
    }

    public boolean knownSymbol(String symbol) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM daily_price WHERE symbol = ? LIMIT 1", Integer.class, symbol);
        return n != null && n > 0;
    }

    private void saveConditions(long noteId, List<NewCondition> conditions) {
        int pos = 0;
        for (NewCondition c : conditions) {
            jdbc.update("INSERT INTO note_condition(note_id, position, kind, text, symbol, metric, op, value, checked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    noteId, pos++, c.kind(), c.text(), c.symbol(), c.metric(), c.op(), c.value(), c.checked());
        }
    }

    private static Note note(ResultSet rs, List<Condition> conditions) throws SQLException {
        return new Note(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getObject(8, LocalDateTime.class), rs.getObject(9, LocalDateTime.class), conditions);
    }

    private static Condition condition(ResultSet rs) throws SQLException {
        return new Condition(rs.getLong("id"), rs.getInt("position"), rs.getString("kind"), rs.getString("text"), rs.getString("symbol"),
                rs.getString("metric"), rs.getString("op"), rs.getObject("value", Double.class), rs.getBoolean("checked"));
    }
}
