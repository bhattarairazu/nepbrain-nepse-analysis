package com.nepse.topturnover.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** pattern_signal: the scanner's picks, kept permanently. There are deliberately no delete methods. */
@Repository
public class PatternRepository {
    public record Row(String rule, LocalDate date, String symbol, String sector, double close, double targetPct, double stopPct, int maxSessions,
                      Double atrPct, Double range20Pct, Double ret5Pct, Double macdHistPct, Double fromLow60Pct, Double turnoverX,
                      Double updownVol, Double rsi14, Double turnover, String source, LocalDateTime recordedAt) {}

    /** Picks per session: {date, picks, live picks}. */
    public record DateCount(LocalDate date, int picks, int live) {}

    private final JdbcTemplate jdbc;

    public PatternRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the rows that are not already stored (a session's first record wins). Returns how many were new. */
    public int insertNew(List<Row> rows) {
        List<Object[]> args = new ArrayList<>();
        for (Row r : rows) {
            args.add(new Object[] {r.rule(), r.date(), r.symbol(), r.sector(), r.close(), r.targetPct(), r.stopPct(), r.maxSessions(),
                    r.atrPct(), r.range20Pct(), r.ret5Pct(), r.macdHistPct(), r.fromLow60Pct(), r.turnoverX(), r.updownVol(), r.rsi14(),
                    r.turnover(), r.source(), r.recordedAt()});
        }
        int n = 0;
        for (int c : jdbc.batchUpdate("""
                INSERT IGNORE INTO pattern_signal(rule, signal_date, symbol, sector, close_price, target_pct, stop_pct, max_sessions, atr_pct,
                    range20_pct, ret5_pct, macd_hist_pct, from_low60_pct, turnover_x, updown_vol, rsi14, turnover, source, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", args)) {
            if (c > 0) n++;
        }
        return n;
    }

    public List<Row> onDate(String rule, LocalDate date) {
        return jdbc.query(SELECT + " WHERE rule = ? AND signal_date = ? ORDER BY turnover_x DESC", PatternRepository::row, rule, date);
    }

    public List<Row> forSymbol(String rule, String symbol) {
        return jdbc.query(SELECT + " WHERE rule = ? AND symbol = ? ORDER BY signal_date DESC", PatternRepository::row, rule, symbol);
    }

    public List<Row> between(String rule, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT + " WHERE rule = ? AND signal_date BETWEEN ? AND ? ORDER BY signal_date DESC, turnover_x DESC",
                PatternRepository::row, rule, from, to);
    }

    public List<DateCount> dates(String rule) {
        return jdbc.query("""
                SELECT signal_date, COUNT(*), SUM(source = 'LIVE') FROM pattern_signal WHERE rule = ?
                GROUP BY signal_date ORDER BY signal_date DESC""",
                (rs, i) -> new DateCount(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3)), rule);
    }

    private static final String SELECT = """
            SELECT rule, signal_date, symbol, sector, close_price, target_pct, stop_pct, max_sessions, atr_pct, range20_pct, ret5_pct,
                   macd_hist_pct, from_low60_pct, turnover_x, updown_vol, rsi14, turnover, source, recorded_at FROM pattern_signal""";

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getDouble(6),
                rs.getDouble(7), rs.getInt(8), d(rs, 9), d(rs, 10), d(rs, 11), d(rs, 12), d(rs, 13), d(rs, 14), d(rs, 15), d(rs, 16), d(rs, 17),
                rs.getString(18), rs.getObject(19, LocalDateTime.class));
    }

    private static Double d(ResultSet rs, int i) throws SQLException {
        return rs.getObject(i, Double.class);
    }
}
