package com.nepse.topturnover.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Listed shares per company (table company_shares), used for market cap. */
@Repository
public class CompanyRepository {
    public record Shares(String symbol, Long listedShares, Double paidUp, String status, LocalDateTime fetchedAt) {}

    private final JdbcTemplate jdbc;

    public CompanyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String symbol, Long listedShares, Double paidUp, String status, LocalDateTime at) {
        jdbc.update("REPLACE INTO company_shares (symbol, listed_shares, paid_up, status, fetched_at) VALUES (?, ?, ?, ?, ?)",
                symbol, listedShares, paidUp, status, at);
    }

    public Map<String, Shares> all() {
        Map<String, Shares> out = new HashMap<>();
        jdbc.query("SELECT symbol, listed_shares, paid_up, status, fetched_at FROM company_shares", rs -> {
            long n = rs.getLong(2);
            Long shares = rs.wasNull() ? null : n;
            double p = rs.getDouble(3);
            Double paid = rs.wasNull() ? null : p;
            out.put(rs.getString(1), new Shares(rs.getString(1), shares, paid, rs.getString(4), rs.getObject(5, LocalDateTime.class)));
        });
        return out;
    }

    /** Stocks seen since the given day, with their sector (null when unknown). */
    public Map<String, String> activeStocks(LocalDate since) {
        Map<String, String> out = new java.util.TreeMap<>();
        jdbc.query("SELECT symbol, sector FROM stock WHERE last_seen >= ?", rs -> { out.put(rs.getString(1), rs.getString(2)); }, since);
        return out;
    }

    public List<Object[]> counts() {
        return jdbc.query("SELECT status, COUNT(*), MAX(fetched_at) FROM company_shares GROUP BY status",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getObject(3, LocalDateTime.class)});
    }
}
