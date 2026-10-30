package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Loads up to ~1 year of stored sessions for every stock and computes {@link StockFeatures} for one session.
 * The result is cached per session and cleared when a crawl stores new data or sectors change.
 */
@Service
public class FeatureService {
    /** Enough for the 200-day average plus a margin. */
    public static final int LOOKBACK_SESSIONS = 260;

    public record Snapshot(LocalDate date, int historySessions, Map<String, StockFeatures> features) {
        public List<StockFeatures> equities() {
            return features.values().stream().filter(f -> Sectors.isEquity(f.sector())).toList();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketDataRepository repo;
    private final Map<LocalDate, Snapshot> cache = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LocalDate, Snapshot> e) {
            return size() > 3;
        }
    };

    public FeatureService(NamedParameterJdbcTemplate jdbc, MarketDataRepository repo) {
        this.jdbc = jdbc;
        this.repo = repo;
    }

    public synchronized void evict() {
        cache.clear();
    }

    /** Features for the given session (or the latest one). Empty when nothing is stored. */
    public synchronized Optional<Snapshot> snapshot(LocalDate requested) {
        LocalDate anchor = requested != null ? requested : repo.latestDay().orElse(null);
        if (anchor == null) return Optional.empty();
        List<LocalDate> sessions = repo.sessionsUpTo(anchor, LOOKBACK_SESSIONS);
        if (sessions.isEmpty()) return Optional.empty();
        LocalDate day = sessions.get(0);
        Snapshot cached = cache.get(day);
        if (cached != null) return Optional.of(cached);
        Snapshot s = load(day, sessions);
        cache.put(day, s);
        return Optional.of(s);
    }

    private Snapshot load(LocalDate day, List<LocalDate> sessions) {
        LocalDate from = sessions.get(sessions.size() - 1);

        Map<String, String[]> info = new HashMap<>();
        jdbc.query("SELECT symbol, name, sector FROM stock", rs -> {
            info.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
        });

        Map<String, Integer> tRank = new HashMap<>(), vRank = new HashMap<>(), appearances = new HashMap<>();
        jdbc.query("SELECT list_type, symbol, rank_no FROM top_ranking WHERE trade_date = :d", Map.of("d", day), rs -> {
            (rs.getString(1).equals("TURNOVER") ? tRank : vRank).put(rs.getString(2), rs.getInt(3));
        });
        List<LocalDate> last10 = sessions.subList(0, Math.min(10, sessions.size()));
        jdbc.query("SELECT symbol, COUNT(*) FROM top_ranking WHERE list_type = 'TURNOVER' AND trade_date IN (:d) GROUP BY symbol",
                Map.of("d", last10), rs -> { appearances.put(rs.getString(1), rs.getInt(2)); });

        record Row(LocalDate d, double o, double h, double l, double c, double v, double t, double n, double ch, double pc) {}
        Map<String, List<Row>> bySymbol = new LinkedHashMap<>();
        jdbc.query("""
                SELECT symbol, trade_date, open_price, high_price, low_price, close_price, volume, turnover, trades, change_pct, prev_close
                FROM daily_price WHERE trade_date BETWEEN :from AND :to ORDER BY symbol, trade_date""",
                new MapSqlParameterSource().addValue("from", from).addValue("to", day),
                rs -> {
                    bySymbol.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Row(
                            rs.getObject(2, LocalDate.class), d(rs, 3), d(rs, 4), d(rs, 5), d(rs, 6), d(rs, 7), d(rs, 8), d(rs, 9), d(rs, 10), d(rs, 11)));
                });

        Map<String, StockFeatures> out = new LinkedHashMap<>();
        for (var e : bySymbol.entrySet()) {
            List<Row> rows = e.getValue();
            if (!rows.get(rows.size() - 1).d().equals(day) || Double.isNaN(rows.get(rows.size() - 1).c())) continue;
            int n = rows.size();
            LocalDate[] dates = new LocalDate[n];
            double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n],
                    v = new double[n], t = new double[n], tr = new double[n], ch = new double[n];
            for (int i = 0; i < n; i++) {
                Row r = rows.get(i);
                dates[i] = r.d(); o[i] = r.o(); h[i] = r.h(); l[i] = r.l(); c[i] = r.c();
                v[i] = r.v(); t[i] = r.t(); tr[i] = r.n(); ch[i] = r.ch();
            }
            fillGaps(c);
            double[] pc = rows.stream().mapToDouble(Row::pc).toArray();
            PriceAdjuster.adjust(o, h, l, c, v, pc); // bonus/right shares
            String[] meta = info.getOrDefault(e.getKey(), new String[] {null, null});
            StockSeries series = new StockSeries(e.getKey(), meta[0], meta[1], dates, o, h, l, c, v, t, tr, ch);
            out.put(e.getKey(), FeatureCalculator.compute(series, new FeatureCalculator.ListContext(
                    tRank.get(e.getKey()), vRank.get(e.getKey()), appearances.getOrDefault(e.getKey(), 0))));
        }
        return new Snapshot(day, sessions.size() - 1, out);
    }

    /** Carries the previous close over a missing value so averages and RSI stay defined. */
    private static void fillGaps(double[] c) {
        for (int i = 1; i < c.length; i++) if (Double.isNaN(c[i])) c[i] = c[i - 1];
        for (int i = c.length - 2; i >= 0; i--) if (Double.isNaN(c[i])) c[i] = c[i + 1];
    }

    private static double d(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? Double.NaN : v;
    }
}
