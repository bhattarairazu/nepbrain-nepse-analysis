package com.nepse.topturnover.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** my_list_item: the stocks you follow by hand, with your own levels. You add and remove them; nothing else touches it. */
@Repository
public class MyListRepository {
    public record Item(long id, String symbol, String intent, LocalDate addedDate, Double addedPrice, Double buyBelow, Double stopPrice,
                       Double sellAbove, String note, LocalDateTime updatedAt) {}

    /** The stock's latest stored session (actual traded prices, not adjusted). */
    public record Latest(String symbol, String name, String sector, LocalDate date, double close, Double changePct, Double turnover) {}

    private static final String COLUMNS = "id, symbol, intent, added_date, added_price, buy_below, stop_price, sell_above, note, updated_at";

    private final JdbcTemplate jdbc;

    public MyListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Item> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM my_list_item ORDER BY FIELD(intent, 'SELL', 'BUY', 'HOLD', 'WATCH'), symbol", (rs, i) -> map(rs));
    }

    public Optional<Item> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM my_list_item WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    public boolean contains(String symbol) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM my_list_item WHERE symbol = ?", Integer.class, symbol);
        return n != null && n > 0;
    }

    /** Adds the stock; returns false if it is already on the list. */
    public boolean add(String symbol, String intent, LocalDate addedDate, Double addedPrice, Double buyBelow, Double stopPrice, Double sellAbove,
                       String note) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return jdbc.update("""
                INSERT IGNORE INTO my_list_item(symbol, intent, added_date, added_price, buy_below, stop_price, sell_above, note, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", symbol, intent, addedDate, addedPrice, buyBelow, stopPrice, sellAbove, trim(note), now, now) > 0;
    }

    public void update(long id, String intent, Double buyBelow, Double stopPrice, Double sellAbove, String note) {
        jdbc.update("UPDATE my_list_item SET intent = ?, buy_below = ?, stop_price = ?, sell_above = ?, note = ?, updated_at = ? WHERE id = ?",
                intent, buyBelow, stopPrice, sellAbove, trim(note), LocalDateTime.now().withNano(0), id);
    }

    public void remove(long id) {
        jdbc.update("DELETE FROM my_list_item WHERE id = ?", id);
    }

    /** Latest session per symbol for the given symbols. */
    public Map<String, Latest> latest(List<String> symbols) {
        Map<String, Latest> out = new HashMap<>();
        if (symbols.isEmpty()) return out;
        String in = String.join(",", symbols.stream().map(s -> "?").toList());
        jdbc.query("""
                SELECT p.symbol, s.name, s.sector, p.trade_date, p.close_price, p.change_pct, p.turnover
                FROM daily_price p JOIN (SELECT symbol, MAX(trade_date) d FROM daily_price WHERE symbol IN (%s) GROUP BY symbol) m
                  ON m.symbol = p.symbol AND m.d = p.trade_date
                LEFT JOIN stock s ON s.symbol = p.symbol""".formatted(in), rs -> {
            out.put(rs.getString(1), new Latest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                    rs.getDouble(5), rs.getObject(6, Double.class), rs.getObject(7, Double.class)));
        }, symbols.toArray());
        return out;
    }

    public List<String> symbols() {
        return jdbc.queryForList("SELECT DISTINCT symbol FROM daily_price ORDER BY symbol", String.class);
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.strip();
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private static Item map(ResultSet rs) throws SQLException {
        return new Item(rs.getLong("id"), rs.getString("symbol"), rs.getString("intent"), rs.getObject("added_date", LocalDate.class),
                rs.getObject("added_price", Double.class), rs.getObject("buy_below", Double.class), rs.getObject("stop_price", Double.class),
                rs.getObject("sell_above", Double.class), rs.getString("note"), rs.getObject("updated_at", LocalDateTime.class));
    }
}
