package com.nepse.topturnover.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/** Writes crawled days and answers the crawler's bookkeeping questions. */
@Repository
public class MarketDataRepository {

    public record CrawlLogEntry(LocalDateTime runAt, String source, String status, LocalDate tradeDate, String message) {}

    private final JdbcTemplate jdbc;

    public MarketDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasDay(LocalDate day) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM trading_day WHERE trade_date = ?", Integer.class, day);
        return n != null && n > 0;
    }

    public Optional<LocalDate> latestDay() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT MAX(trade_date) FROM trading_day", LocalDate.class));
    }

    /** The most recent `n` stored sessions on or before `day`, newest first. */
    public List<LocalDate> sessionsUpTo(LocalDate day, int n) {
        return jdbc.queryForList(
                "SELECT trade_date FROM trading_day WHERE trade_date <= ? ORDER BY trade_date DESC LIMIT ?",
                LocalDate.class, day, n);
    }

    /**
     * True when the snapshot repeats the previous stored session (same close and volume for 95%+ of stocks).
     * The site keeps showing the last session on holidays, so this stops a holiday being stored as a new day.
     */
    public boolean looksStale(MarketSnapshot s) {
        LocalDate prev = jdbc.queryForObject("SELECT MAX(trade_date) FROM trading_day WHERE trade_date < ?",
                LocalDate.class, s.tradeDate());
        if (prev == null) return false;
        Map<String, double[]> last = new HashMap<>();
        jdbc.query("SELECT symbol, close_price, volume FROM daily_price WHERE trade_date = ?",
                rs -> { last.put(rs.getString(1), new double[] {rs.getDouble(2), rs.getDouble(3)}); }, prev);
        if (last.isEmpty()) return false;
        long same = s.quotes().stream().filter(q -> {
            double[] p = last.get(q.symbol());
            return p != null && q.close() != null && q.volume() != null
                    && Math.abs(p[0] - q.close()) < 0.005 && Math.abs(p[1] - q.volume()) < 0.005;
        }).count();
        return same >= 0.95 * s.quotes().size();
    }

    /** Stores the full table and the top-N lists in one transaction, replacing the day if it already exists. */
    @Transactional
    public void save(MarketSnapshot s, int topN, LocalDateTime crawledAt) {
        LocalDate day = s.tradeDate();
        jdbc.update("DELETE FROM trading_day WHERE trade_date = ?", day); // cascades to prices and rankings

        List<Integer> trades = s.quotes().stream().map(StockQuote::trades).filter(Objects::nonNull).toList();
        jdbc.update("""
                INSERT INTO trading_day(trade_date, source, crawled_at, stock_count, total_turnover, total_volume, total_trades)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                day, s.source(), crawledAt, s.quotes().size(),
                sum(s.quotes(), StockQuote::turnover), sum(s.quotes(), StockQuote::volume),
                trades.isEmpty() ? null : trades.stream().mapToLong(Integer::longValue).sum());

        List<Object[]> prices = new ArrayList<>();
        for (StockQuote q : s.quotes()) {
            prices.add(new Object[] {day, q.symbol(), q.open(), q.high(), q.low(), q.close(), q.ltp(), q.prevClose(),
                    q.vwap(), q.volume(), q.turnover(), q.trades(), q.changePct()});
        }
        jdbc.batchUpdate("""
                INSERT INTO daily_price(trade_date, symbol, open_price, high_price, low_price, close_price, ltp, prev_close,
                                        vwap, volume, turnover, trades, change_pct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", prices);

        List<Object[]> stocks = new ArrayList<>();
        for (StockQuote q : s.quotes()) stocks.add(new Object[] {q.symbol(), q.name(), day, day});
        jdbc.batchUpdate("""
                INSERT INTO stock(symbol, name, first_seen, last_seen) VALUES (?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE name = COALESCE(new.name, stock.name),
                                        first_seen = LEAST(stock.first_seen, new.first_seen),
                                        last_seen = GREATEST(stock.last_seen, new.last_seen)""", stocks);

        insertRanking(day, "TURNOVER", topBy(s.quotes(), StockQuote::turnover, topN), StockQuote::turnover);
        insertRanking(day, "VOLUME", topBy(s.quotes(), StockQuote::volume, topN), StockQuote::volume);
    }

    /** Top-N stocks of a snapshot by a metric, largest first (ties broken by symbol). */
    public static List<StockQuote> topBy(List<StockQuote> quotes, Function<StockQuote, Double> metric, int n) {
        return quotes.stream()
                .filter(q -> metric.apply(q) != null && metric.apply(q) > 0)
                .sorted(Comparator.comparing(metric, Comparator.reverseOrder()).thenComparing(StockQuote::symbol))
                .limit(n)
                .toList();
    }

    private void insertRanking(LocalDate day, String type, List<StockQuote> top, Function<StockQuote, Double> metric) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            rows.add(new Object[] {day, type, i + 1, top.get(i).symbol(), metric.apply(top.get(i))});
        }
        jdbc.batchUpdate("INSERT INTO top_ranking(trade_date, list_type, rank_no, symbol, metric_value) VALUES (?, ?, ?, ?, ?)", rows);
    }

    public void logCrawl(LocalDateTime runAt, String source, String status, LocalDate tradeDate, String message) {
        jdbc.update("INSERT INTO crawl_log(run_at, source, status, trade_date, message) VALUES (?, ?, ?, ?, ?)",
                runAt, source, status, tradeDate,
                message != null && message.length() > 1000 ? message.substring(0, 1000) : message);
    }

    public List<CrawlLogEntry> recentCrawls(int limit) {
        return jdbc.query("SELECT run_at, source, status, trade_date, message FROM crawl_log ORDER BY id DESC LIMIT ?",
                (rs, i) -> new CrawlLogEntry(rs.getObject(1, LocalDateTime.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5)), limit);
    }

    // ---------------------------------------------------------------- sectors

    public record StockRow(String symbol, String name, String sector, String sectorSource, LocalDate lastSeen) {}

    /** @param filter "unassigned", "auto", or anything else for all stocks */
    public List<StockRow> stocks(String filter) {
        String where = switch (filter == null ? "" : filter) {
            case "unassigned" -> "WHERE sector IS NULL";
            case "auto" -> "WHERE sector_source = 'AUTO'";
            default -> "";
        };
        return jdbc.query("SELECT symbol, name, sector, sector_source, last_seen FROM stock " + where
                        + " ORDER BY sector IS NULL DESC, sector, symbol",
                (rs, i) -> new StockRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class)));
    }

    public void setSector(String symbol, String sector) {
        boolean clear = sector == null || sector.isBlank();
        jdbc.update("UPDATE stock SET sector = ?, sector_source = ? WHERE symbol = ?",
                clear ? null : sector, clear ? null : "MANUAL", symbol);
    }

    /** Guesses a sector from the company name for stocks that have none. Never touches existing sectors. */
    public int autoAssignSectors() {
        List<Object[]> updates = new ArrayList<>();
        jdbc.query("SELECT symbol, name FROM stock WHERE sector IS NULL", rs -> {
            String guess = com.nepse.topturnover.analysis.Sectors.guess(rs.getString(2));
            if (guess != null) updates.add(new Object[] {guess, rs.getString(1)});
        });
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE stock SET sector = ?, sector_source = 'AUTO' WHERE symbol = ? AND sector IS NULL", updates);
        }
        return updates.size();
    }

    /** Sets sectors from symbol -> sector pairs (CSV import); counts as manual. */
    public int importSectors(Map<String, String> sectors) {
        int n = 0;
        for (var e : sectors.entrySet()) {
            n += jdbc.update("UPDATE stock SET sector = ?, sector_source = 'MANUAL' WHERE symbol = ?", e.getValue(), e.getKey());
        }
        return n;
    }

    private static Double sum(List<StockQuote> quotes, Function<StockQuote, Double> f) {
        return quotes.stream().map(f).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    }
}
