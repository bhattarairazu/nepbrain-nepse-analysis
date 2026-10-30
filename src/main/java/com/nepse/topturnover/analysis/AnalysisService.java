package com.nepse.topturnover.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.AnalysisModels.DayAnalysis;
import com.nepse.topturnover.analysis.AnalysisModels.FlowRow;
import com.nepse.topturnover.analysis.AnalysisModels.MarketDay;
import com.nepse.topturnover.analysis.AnalysisModels.MoneyFlow;
import com.nepse.topturnover.analysis.AnalysisModels.StockDay;
import com.nepse.topturnover.analysis.AnalysisModels.StockView;
import com.nepse.topturnover.analysis.AnalysisModels.TopListRow;
import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Top-20 turnover and volume analysis. Deliberately limited to what these lists can tell you:
 * relative activity, price + volume signal, close position, trade size, persistence and concentration.
 */
@Service
public class AnalysisService {
    /** Previous sessions used for "normal" volume/turnover. */
    public static final int AVG_SESSIONS = 20;
    /** Fewer earlier sessions than this and relative volume isn't shown. */
    public static final int MIN_HISTORY = 5;
    public static final int PERSISTENCE_SESSIONS = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketDataRepository repo;

    public AnalysisService(NamedParameterJdbcTemplate jdbc, MarketDataRepository repo) {
        this.jdbc = jdbc;
        this.repo = repo;
    }

    public Optional<LocalDate> latestDay() {
        return repo.latestDay();
    }

    public List<LocalDate> recentSessions(int n) {
        return repo.latestDay().map(d -> repo.sessionsUpTo(d, n)).orElse(List.of());
    }

    // ------------------------------------------------------------------ day view

    public Optional<DayAnalysis> day(LocalDate requested) {
        LocalDate anchor = requested != null ? requested : repo.latestDay().orElse(null);
        if (anchor == null) return Optional.empty();
        List<LocalDate> sessions = repo.sessionsUpTo(anchor, AVG_SESSIONS + 1);  // newest first
        if (sessions.isEmpty()) return Optional.empty();
        LocalDate day = sessions.get(0);
        List<LocalDate> prior = sessions.subList(1, sessions.size());

        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT total_turnover, total_volume, stock_count FROM trading_day WHERE trade_date = :d", Map.of("d", day));
        double marketTurnover = num(totals.get("total_turnover"));
        double marketVolume = num(totals.get("total_volume"));
        Double avgMarketTurnover = prior.size() >= MIN_HISTORY ? jdbc.queryForObject(
                "SELECT AVG(total_turnover) FROM trading_day WHERE trade_date IN (:d)", Map.of("d", prior), Double.class) : null;

        record Raw(String type, int rank, String symbol, double value, String name, Double close, Double change,
                   Double volume, Double turnover, Integer trades, Double high, Double low) {}
        List<Raw> raw = jdbc.query("""
                SELECT r.list_type, r.rank_no, r.symbol, r.metric_value, s.name, p.close_price, p.change_pct,
                       p.volume, p.turnover, p.trades, p.high_price, p.low_price
                FROM top_ranking r
                JOIN daily_price p ON p.trade_date = r.trade_date AND p.symbol = r.symbol
                LEFT JOIN stock s ON s.symbol = r.symbol
                WHERE r.trade_date = :d
                ORDER BY r.list_type, r.rank_no""", Map.of("d", day),
                (rs, i) -> new Raw(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getDouble(4), rs.getString(5),
                        dbl(rs, 6), dbl(rs, 7), dbl(rs, 8), dbl(rs, 9), integer(rs, 10), dbl(rs, 11), dbl(rs, 12)));

        Set<String> symbols = new LinkedHashSet<>();
        raw.forEach(r -> symbols.add(r.symbol()));
        Map<String, double[]> averages = averages(symbols, prior);
        Map<String, Map<String, Set<LocalDate>>> membership = membership(symbols, sessions);
        Set<String> inTurnover = new HashSet<>(), inVolume = new HashSet<>();
        raw.forEach(r -> (r.type().equals("TURNOVER") ? inTurnover : inVolume).add(r.symbol()));

        List<TopListRow> turnoverRows = new ArrayList<>(), volumeRows = new ArrayList<>();
        for (Raw r : raw) {
            double[] avg = averages.get(r.symbol());
            Double rvol = avg != null && avg[0] > 0 && r.volume() != null ? r.volume() / avg[0] : null;
            Double relTurnover = avg != null && avg[1] > 0 && r.turnover() != null ? r.turnover() / avg[1] : null;
            Double closeInRange = r.high() != null && r.low() != null && r.close() != null && r.high() > r.low()
                    ? (r.close() - r.low()) / (r.high() - r.low()) : null;
            Double avgTrade = r.trades() != null && r.trades() > 0 && r.turnover() != null ? r.turnover() / r.trades() : null;

            Set<LocalDate> present = membership.getOrDefault(r.type(), Map.of()).getOrDefault(r.symbol(), Set.of());
            int streak = 0;
            while (streak < sessions.size() && present.contains(sessions.get(streak))) streak++;
            int appearances10 = (int) sessions.subList(0, Math.min(PERSISTENCE_SESSIONS, sessions.size()))
                    .stream().filter(present::contains).count();
            boolean newEntry = sessions.size() > 1 && sessions.subList(1, Math.min(PERSISTENCE_SESSIONS + 1, sessions.size()))
                    .stream().noneMatch(present::contains);

            TopListRow row = new TopListRow(r.rank(), r.symbol(), r.name(), r.value(), r.close(), r.change(),
                    r.volume(), r.turnover(), r.trades(), rvol, relTurnover, closeInRange, avgTrade,
                    streak, appearances10, newEntry, inTurnover.contains(r.symbol()) && inVolume.contains(r.symbol()),
                    Signal.classify(r.change(), rvol));
            (r.type().equals("TURNOVER") ? turnoverRows : volumeRows).add(row);
        }

        double topTurnover = turnoverRows.stream().mapToDouble(TopListRow::value).sum();
        double topVolume = volumeRows.stream().mapToDouble(TopListRow::value).sum();
        return Optional.of(new DayAnalysis(day, prior.size(), (int) num(totals.get("stock_count")),
                marketTurnover, avgMarketTurnover != null && avgMarketTurnover > 0 ? marketTurnover / avgMarketTurnover : null,
                marketVolume,
                marketTurnover > 0 ? topTurnover / marketTurnover : null,
                marketVolume > 0 ? topVolume / marketVolume : null,
                turnoverRows, volumeRows));
    }

    /** symbol -> {avg volume, avg turnover} over the given sessions; only when enough sessions exist. */
    private Map<String, double[]> averages(Set<String> symbols, List<LocalDate> prior) {
        Map<String, double[]> out = new HashMap<>();
        if (symbols.isEmpty() || prior.size() < MIN_HISTORY) return out;
        jdbc.query("""
                SELECT symbol, AVG(volume), AVG(turnover), COUNT(*) FROM daily_price
                WHERE symbol IN (:s) AND trade_date IN (:d) GROUP BY symbol""",
                new MapSqlParameterSource().addValue("s", symbols).addValue("d", prior),
                rs -> {
                    if (rs.getInt(4) >= MIN_HISTORY) out.put(rs.getString(1), new double[] {rs.getDouble(2), rs.getDouble(3)});
                });
        return out;
    }

    /** list type -> symbol -> sessions in which the symbol was in that list. */
    private Map<String, Map<String, Set<LocalDate>>> membership(Set<String> symbols, List<LocalDate> sessions) {
        Map<String, Map<String, Set<LocalDate>>> out = new HashMap<>();
        if (symbols.isEmpty()) return out;
        jdbc.query("SELECT list_type, symbol, trade_date FROM top_ranking WHERE symbol IN (:s) AND trade_date IN (:d)",
                new MapSqlParameterSource().addValue("s", symbols).addValue("d", sessions),
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                            .computeIfAbsent(rs.getString(2), k -> new HashSet<>())
                            .add(rs.getObject(3, LocalDate.class));
                });
        return out;
    }

    // ------------------------------------------------------------------ money flow (persistence)

    public Optional<MoneyFlow> moneyFlow(String listType, int sessionsWanted, LocalDate end) {
        LocalDate anchor = end != null ? end : repo.latestDay().orElse(null);
        if (anchor == null) return Optional.empty();
        List<LocalDate> sessions = repo.sessionsUpTo(anchor, sessionsWanted);
        if (sessions.isEmpty()) return Optional.empty();
        LocalDate to = sessions.get(0), from = sessions.get(sessions.size() - 1);

        record Agg(String symbol, String name, int n, double avgRank, int best, double total) {}
        List<Agg> aggs = jdbc.query("""
                SELECT r.symbol, s.name, COUNT(*), AVG(r.rank_no), MIN(r.rank_no), SUM(r.metric_value)
                FROM top_ranking r LEFT JOIN stock s ON s.symbol = r.symbol
                WHERE r.list_type = :t AND r.trade_date IN (:d)
                GROUP BY r.symbol, s.name
                ORDER BY COUNT(*) DESC, AVG(r.rank_no) ASC""",
                new MapSqlParameterSource().addValue("t", listType).addValue("d", sessions),
                (rs, i) -> new Agg(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getDouble(4), rs.getInt(5), rs.getDouble(6)));

        Map<String, Double> startBase = new HashMap<>(), endClose = new HashMap<>();
        Map<String, Integer> latestRank = new HashMap<>();
        if (!aggs.isEmpty()) {
            List<String> syms = aggs.stream().map(Agg::symbol).toList();
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", syms).addValue("from", from).addValue("to", to);
            jdbc.query("""
                    SELECT trade_date, symbol, close_price, prev_close FROM daily_price
                    WHERE symbol IN (:s) AND trade_date IN (:from, :to)""", p, rs -> {
                LocalDate d = rs.getObject(1, LocalDate.class);
                Double close = dbl(rs, 3), prev = dbl(rs, 4);
                if (d.equals(from)) startBase.put(rs.getString(2), prev != null ? prev : close);
                if (d.equals(to)) endClose.put(rs.getString(2), close);
            });
            jdbc.query("SELECT symbol, rank_no FROM top_ranking WHERE list_type = :t AND trade_date = :to AND symbol IN (:s)",
                    p.addValue("t", listType), rs -> { latestRank.put(rs.getString(1), rs.getInt(2)); });
        }

        List<FlowRow> rows = aggs.stream().map(a -> {
            Double base = startBase.get(a.symbol()), last = endClose.get(a.symbol());
            Double change = base != null && last != null && base > 0 ? (last / base - 1) * 100 : null;
            return new FlowRow(a.symbol(), a.name(), a.n(), a.avgRank(), a.best(), a.total(), change, last, latestRank.get(a.symbol()));
        }).toList();
        return Optional.of(new MoneyFlow(listType, sessionsWanted, sessions.size(), from, to, rows));
    }

    // ------------------------------------------------------------------ market activity

    public List<MarketDay> market(int sessionsWanted) {
        List<MarketDay> all = new ArrayList<>();
        Map<LocalDate, double[]> topSums = new HashMap<>();
        jdbc.query("""
                SELECT trade_date, SUM(CASE WHEN list_type = 'TURNOVER' THEN metric_value END),
                       SUM(CASE WHEN list_type = 'VOLUME' THEN metric_value END)
                FROM top_ranking GROUP BY trade_date ORDER BY trade_date DESC LIMIT :n""",
                Map.of("n", sessionsWanted + AVG_SESSIONS),
                rs -> { topSums.put(rs.getObject(1, LocalDate.class), new double[] {rs.getDouble(2), rs.getDouble(3)}); });
        List<Object[]> days = jdbc.query("""
                SELECT trade_date, total_turnover, total_volume, total_trades, stock_count
                FROM trading_day ORDER BY trade_date DESC LIMIT :n""",
                Map.of("n", sessionsWanted + AVG_SESSIONS),
                (rs, i) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getInt(5)});
        Collections.reverse(days); // oldest first
        for (int i = 0; i < days.size(); i++) {
            Object[] d = days.get(i);
            LocalDate date = (LocalDate) d[0];
            double turnover = (double) d[1], volume = (double) d[2];
            Double avg20 = null;
            if (i >= MIN_HISTORY) {
                avg20 = days.subList(Math.max(0, i - AVG_SESSIONS), i).stream().mapToDouble(x -> (double) x[1]).average().orElse(0);
            }
            double[] top = topSums.get(date);
            all.add(new MarketDay(date, turnover, volume, (Long) d[3], (int) d[4], avg20,
                    top != null && turnover > 0 ? top[0] / turnover : null,
                    top != null && volume > 0 ? top[1] / volume : null));
        }
        return all.subList(Math.max(0, all.size() - sessionsWanted), all.size());
    }

    // ------------------------------------------------------------------ single stock

    public Optional<StockView> stock(String symbol, int sessionsWanted) {
        List<Map<String, Object>> info = jdbc.queryForList("SELECT symbol, name, sector FROM stock WHERE symbol = :s", Map.of("s", symbol));
        if (info.isEmpty()) return Optional.empty();

        List<StockDay> days = new ArrayList<>(jdbc.query("""
                SELECT p.trade_date, p.close_price, p.change_pct, p.volume, p.turnover, p.trades,
                       MAX(CASE WHEN r.list_type = 'TURNOVER' THEN r.rank_no END),
                       MAX(CASE WHEN r.list_type = 'VOLUME' THEN r.rank_no END)
                FROM daily_price p
                LEFT JOIN top_ranking r ON r.trade_date = p.trade_date AND r.symbol = p.symbol
                WHERE p.symbol = :s
                GROUP BY p.trade_date, p.close_price, p.change_pct, p.volume, p.turnover, p.trades
                ORDER BY p.trade_date DESC LIMIT :n""",
                Map.of("s", symbol, "n", sessionsWanted),
                (rs, i) -> new StockDay(rs.getObject(1, LocalDate.class), dbl(rs, 2), dbl(rs, 3), dbl(rs, 4), dbl(rs, 5),
                        integer(rs, 6), integer(rs, 7), integer(rs, 8))));
        Collections.reverse(days);

        int tApp = (int) days.stream().filter(d -> d.turnoverRank() != null).count();
        int vApp = (int) days.stream().filter(d -> d.volumeRank() != null).count();
        List<StockDay> last20 = days.subList(Math.max(0, days.size() - AVG_SESSIONS), days.size());
        Double avgT = last20.isEmpty() ? null : last20.stream().filter(d -> d.turnover() != null).mapToDouble(StockDay::turnover).average().orElse(0);
        Double avgV = last20.isEmpty() ? null : last20.stream().filter(d -> d.volume() != null).mapToDouble(StockDay::volume).average().orElse(0);
        Map<String, Object> s = info.get(0);
        return Optional.of(new StockView((String) s.get("symbol"), (String) s.get("name"), (String) s.get("sector"),
                days, tApp, vApp, avgT, avgV));
    }

    // ------------------------------------------------------------------ helpers

    private static Double dbl(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, int i) throws SQLException {
        int v = rs.getInt(i);
        return rs.wasNull() ? null : v;
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }
}
