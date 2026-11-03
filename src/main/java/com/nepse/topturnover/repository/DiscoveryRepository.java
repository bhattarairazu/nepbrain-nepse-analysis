package com.nepse.topturnover.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.nepse.topturnover.analysis.DiscoveryService.Found;
import com.nepse.topturnover.analysis.DiscoveryService.Result;
import com.nepse.topturnover.analysis.DiscoveryService.Settings;
import com.nepse.topturnover.analysis.DiscoveryService.Stats;

/** discovery_run / discovery_rule: every "Run analysis now" result. There are deliberately no delete methods. */
@Repository
public class DiscoveryRepository {
    public record RunInfo(long id, LocalDateTime at, LocalDate from, LocalDate to, int sessions, int rules) {}

    private final JdbcTemplate jdbc;

    public DiscoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long save(Result r) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO discovery_run(run_at, data_from, data_to, sessions, stocks, stock_days, find_to, choose_to, target_pct, stop_pct,
                        max_sessions, base_find_avg, base_choose_avg, base_check_avg, base_find_win, base_choose_win, base_check_win, duration_ms, base_by_year)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            Object[] v = {r.at(), r.from(), r.to(), r.sessions(), r.stocks(), r.stockDays(), r.findTo(), r.chooseTo(), r.settings().targetPct(),
                    r.settings().stopPct(), r.settings().maxSessions(), r.baseFind().avgPct(), r.baseChoose().avgPct(), r.baseCheck().avgPct(),
                    r.baseFind().winPct(), r.baseChoose().winPct(), r.baseCheck().winPct(), r.millis(), years(r.baseByYear())};
            for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        List<Object[]> rows = new ArrayList<>();
        for (Found f : r.rules()) {
            rows.add(new Object[] {id, f.rank(), String.join("\n", f.conditions()), f.spec(), f.find().n(), f.find().winPct(), f.find().avgPct(),
                    f.find().medianPct(), f.choose().n(), f.choose().winPct(), f.choose().avgPct(), f.choose().medianPct(), f.check().n(),
                    f.check().winPct(), f.check().avgPct(), f.check().medianPct(), cut(String.join(",", f.today()), 2000), years(f.byYear()),
                    enc(f.weakMarket()), enc(f.strongMarket())});
        }
        jdbc.batchUpdate("""
                INSERT INTO discovery_rule(run_id, rank_no, rule_text, rule_spec, find_n, find_win, find_avg, find_median, choose_n, choose_win,
                    choose_avg, choose_median, check_n, check_win, check_avg, check_median, today_symbols, by_year, weak_market, strong_market)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        return id;
    }

    public List<RunInfo> runs(int limit) {
        return jdbc.query("""
                SELECT r.id, r.run_at, r.data_from, r.data_to, r.sessions, (SELECT COUNT(*) FROM discovery_rule x WHERE x.run_id = r.id)
                FROM discovery_run r ORDER BY r.id DESC LIMIT ?""",
                (rs, i) -> new RunInfo(rs.getLong(1), rs.getObject(2, LocalDateTime.class), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getInt(5), rs.getInt(6)), limit);
    }

    public Optional<Result> load(long id) {
        List<Found> rules = jdbc.query("SELECT * FROM discovery_rule WHERE run_id = ? ORDER BY rank_no", (rs, i) -> new Found(rs.getInt("rank_no"),
                Arrays.asList(rs.getString("rule_text").split("\n")), rs.getString("rule_spec"), stats(rs, "find"), stats(rs, "choose"),
                stats(rs, "check"), rs.getString("today_symbols") == null || rs.getString("today_symbols").isBlank() ? List.of()
                        : Arrays.asList(rs.getString("today_symbols").split(",")), parseYears(rs.getString("by_year")), dec(rs.getString("weak_market")),
                dec(rs.getString("strong_market"))), id);
        return jdbc.query("SELECT * FROM discovery_run WHERE id = ?", (rs, i) -> new Result(rs.getLong("id"), rs.getObject("run_at", LocalDateTime.class),
                rs.getObject("data_from", LocalDate.class), rs.getObject("data_to", LocalDate.class), rs.getInt("sessions"), rs.getInt("stocks"),
                rs.getInt("stock_days"), rs.getObject("find_to", LocalDate.class), rs.getObject("choose_to", LocalDate.class),
                new Settings(rs.getDouble("target_pct"), rs.getDouble("stop_pct"), rs.getInt("max_sessions")),
                base(rs, "find"), base(rs, "choose"), base(rs, "check"), rs.getLong("duration_ms"), rules, parseYears(rs.getString("base_by_year"))), id)
                .stream().findFirst();
    }

    private static Stats stats(ResultSet rs, String p) throws SQLException {
        return new Stats(rs.getInt(p + "_n"), d(rs, p + "_win"), d(rs, p + "_avg"), d(rs, p + "_median"));
    }

    private static Stats base(ResultSet rs, String p) throws SQLException {
        return new Stats(0, d(rs, "base_" + p + "_win"), d(rs, "base_" + p + "_avg"), 0);
    }

    private static double d(ResultSet rs, String c) throws SQLException {
        double v = rs.getDouble(c);
        return rs.wasNull() ? Double.NaN : v;
    }

    private static String enc(Stats s) {
        return s == null ? null : s.n() + ":" + r2(s.winPct()) + ":" + r2(s.avgPct()) + ":" + r2(s.medianPct());
    }

    private static Stats dec(String v) {
        if (v == null || v.isBlank()) return null;
        String[] p = v.split(":");
        return new Stats(Integer.parseInt(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
    }

    private static String years(java.util.Map<Integer, Stats> m) {
        if (m == null) return null;
        return cut(String.join(";", m.entrySet().stream().map(e -> e.getKey() + "=" + enc(e.getValue())).toList()), 1000);
    }

    private static java.util.Map<Integer, Stats> parseYears(String v) {
        java.util.Map<Integer, Stats> m = new java.util.TreeMap<>();
        if (v == null || v.isBlank()) return m;
        for (String part : v.split(";")) {
            String[] kv = part.split("=");
            m.put(Integer.parseInt(kv[0]), dec(kv[1]));
        }
        return m;
    }

    private static double r2(double v) {
        return Double.isFinite(v) ? Math.round(v * 100) / 100.0 : 0;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
