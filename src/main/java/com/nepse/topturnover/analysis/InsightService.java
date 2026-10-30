package com.nepse.topturnover.analysis;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.AnalysisModels.MarketDay;
import com.nepse.topturnover.analysis.IdeaScorer.Idea;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.repository.MarketDataRepository;

/** Builds the Ideas, Patterns, Sectors, Trade size and market-regime views from {@link FeatureService}. */
@Service
public class InsightService {

    // ------------------------------------------------------------------ result models

    public record Dropped(StockFeatures f, String reason) {}

    public record Routine(LocalDate date, int historySessions, MarketRegime regime,
                          int universe, int active, int closedStrong, int kept,
                          List<Idea> ideas, List<StockFeatures> forming, List<Dropped> dropped,
                          List<SectorRow> sectorsThisWeek, boolean sectorsAssigned) {
        public List<Idea> tier(IdeaScorer.Tier t) {
            return ideas.stream().filter(i -> i.tier() == t).toList();
        }
    }

    public record SectorRow(String sector, List<Integer> slots, List<Double> share, Double changePts, int stocks) {
        public Double latestShare() { return share.isEmpty() ? null : share.get(share.size() - 1); }
    }

    public record SectorFlow(LocalDate date, List<LocalDate> weeks, List<Integer> sessionsPerWeek, List<SectorRow> rows,
                             int assignedStocks, int totalStocks) {}

    public record MarketRegime(String key, String label, String tone, String explanation,
                               Double index10Pct, Double turnoverTrend, Double drawdown60Pct) {}

    public record PatternGroup(Pattern pattern, List<StockFeatures> stocks) {}

    // ------------------------------------------------------------------

    private final FeatureService features;
    private final NamedParameterJdbcTemplate jdbc;
    private final MarketDataRepository repo;
    private final NepseProperties props;

    public InsightService(FeatureService features, NamedParameterJdbcTemplate jdbc, MarketDataRepository repo, NepseProperties props) {
        this.features = features;
        this.jdbc = jdbc;
        this.repo = repo;
        this.props = props;
    }

    /** The daily routine: active today -> closed up near the high -> drop extended/risky -> score. */
    public Optional<Routine> routine(LocalDate date) {
        return features.snapshot(date).map(snap -> {
            List<StockFeatures> universe = snap.equities();
            List<StockFeatures> active = universe.stream()
                    .filter(f -> f.turnoverRank() != null || (f.rvol() != null && f.rvol() >= Rules.RVOL_ACTIVE)).toList();
            List<StockFeatures> strong = active.stream().filter(StockFeatures::closedUpNearHigh).toList();

            SectorFlow flow = sectorFlow(snap.date());
            Map<String, Integer> sectorRank = recentSectorRanks(snap.date());

            List<Dropped> dropped = new ArrayList<>();
            List<Idea> ideas = new ArrayList<>();
            for (StockFeatures f : strong) {
                String why = IdeaScorer.dropReason(f);
                if (why != null) dropped.add(new Dropped(f, why));
                else ideas.add(IdeaScorer.score(f, f.sector() == null ? null : sectorRank.get(f.sector())));
            }
            ideas.sort(Comparator.comparingInt(Idea::score).reversed().thenComparing(i -> i.f().symbol()));
            List<StockFeatures> forming = universe.stream()
                    .filter(f -> f.baseForming() && !f.has(Pattern.BASE_BREAKOUT) && f.turnover() != null)
                    .sorted(Comparator.comparing(StockFeatures::turnover).reversed())
                    .limit(15).toList();

            return new Routine(snap.date(), snap.historySessions(), regime(snap.date()),
                    universe.size(), active.size(), strong.size(), ideas.size(), ideas, forming, dropped,
                    flow.rows().stream().limit(6).toList(), flow.assignedStocks() > 0);
        });
    }

    public Optional<List<PatternGroup>> patterns(LocalDate date) {
        return features.snapshot(date).map(snap -> {
            List<PatternGroup> out = new ArrayList<>();
            for (Pattern p : Pattern.values()) {
                out.add(new PatternGroup(p, snap.equities().stream().filter(f -> f.has(p))
                        .sorted(Comparator.comparing((StockFeatures f) -> f.turnover() == null ? 0 : f.turnover()).reversed())
                        .toList()));
            }
            return out;
        });
    }

    public Optional<FeatureService.Snapshot> snapshot(LocalDate date) {
        return features.snapshot(date);
    }

    // ------------------------------------------------------------------ sector money flow

    /** Share of top-20 turnover slots per sector for the current week and the 4 weeks before it. */
    public SectorFlow sectorFlow(LocalDate requested) {
        LocalDate day = requested != null ? requested : repo.latestDay().orElse(LocalDate.now());
        LocalDate thisWeek = day.with(DayOfWeek.MONDAY);
        LocalDate firstWeek = thisWeek.minusWeeks(4);
        List<LocalDate> weeks = new ArrayList<>();
        for (LocalDate w = firstWeek; !w.isAfter(thisWeek); w = w.plusWeeks(1)) weeks.add(w);

        Map<LocalDate, Integer> sessionsPerWeek = new TreeMap<>();
        jdbc.query("SELECT trade_date FROM trading_day WHERE trade_date BETWEEN :f AND :t",
                new MapSqlParameterSource().addValue("f", firstWeek).addValue("t", day),
                rs -> { sessionsPerWeek.merge(rs.getObject(1, LocalDate.class).with(DayOfWeek.MONDAY), 1, Integer::sum); });

        Map<String, int[]> slots = new HashMap<>();
        jdbc.query("""
                SELECT r.trade_date, COALESCE(s.sector, 'Unassigned') FROM top_ranking r
                LEFT JOIN stock s ON s.symbol = r.symbol
                WHERE r.list_type = 'TURNOVER' AND r.trade_date BETWEEN :f AND :t""",
                new MapSqlParameterSource().addValue("f", firstWeek).addValue("t", day),
                rs -> {
                    int w = weeks.indexOf(rs.getObject(1, LocalDate.class).with(DayOfWeek.MONDAY));
                    if (w >= 0) slots.computeIfAbsent(rs.getString(2), k -> new int[weeks.size()])[w]++;
                });

        Map<String, Integer> stockCounts = new HashMap<>();
        jdbc.query("SELECT COALESCE(sector, 'Unassigned'), COUNT(*) FROM stock GROUP BY COALESCE(sector, 'Unassigned')",
                rs -> { stockCounts.put(rs.getString(1), rs.getInt(2)); });

        int topN = props.analysis().topN();
        List<Integer> spw = weeks.stream().map(w -> sessionsPerWeek.getOrDefault(w, 0)).toList();
        List<SectorRow> rows = new ArrayList<>();
        for (var e : slots.entrySet()) {
            List<Integer> s = new ArrayList<>();
            List<Double> share = new ArrayList<>();
            for (int i = 0; i < weeks.size(); i++) {
                s.add(e.getValue()[i]);
                share.add(spw.get(i) > 0 ? (double) e.getValue()[i] / (topN * spw.get(i)) : null);
            }
            Double first = null;
            for (Double x : share) if (x != null) { first = x; break; }
            Double last = share.get(share.size() - 1);
            Double change = first != null && last != null && spw.stream().filter(n -> n > 0).count() > 1 ? (last - first) * 100 : null;
            rows.add(new SectorRow(e.getKey(), s, share, change, stockCounts.getOrDefault(e.getKey(), 0)));
        }
        rows.sort(Comparator.comparing((SectorRow r) -> r.latestShare() == null ? -1 : r.latestShare()).reversed()
                .thenComparing(SectorRow::sector));
        int total = stockCounts.values().stream().mapToInt(Integer::intValue).sum();
        return new SectorFlow(day, weeks, spw, rows, total - stockCounts.getOrDefault("Unassigned", 0), total);
    }

    /** Sector rank by top-20 turnover slots over the last 5 sessions (1 = most). */
    public Map<String, Integer> recentSectorRanks(LocalDate day) {
        List<LocalDate> last5 = repo.sessionsUpTo(day, 5);
        Map<String, Integer> counts = new HashMap<>();
        if (last5.isEmpty()) return counts;
        jdbc.query("""
                SELECT s.sector, COUNT(*) FROM top_ranking r JOIN stock s ON s.symbol = r.symbol
                WHERE r.list_type = 'TURNOVER' AND r.trade_date IN (:d) AND s.sector IS NOT NULL
                GROUP BY s.sector""", Map.of("d", last5), rs -> { counts.put(rs.getString(1), rs.getInt(2)); });
        List<String> ordered = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) rank.put(ordered.get(i), i + 1);
        return rank;
    }

    // ------------------------------------------------------------------ market: equal-weight index + regime

    /** Daily equal-weight index (avg % change of ordinary shares, chained from 1,000), breadth and totals. */
    public List<MarketDay> marketDays(int sessionsWanted, LocalDate upTo) {
        LocalDate day = upTo != null ? upTo : repo.latestDay().orElse(null);
        if (day == null) return List.of();
        List<LocalDate> sessions = repo.sessionsUpTo(day, sessionsWanted + 60);
        if (sessions.isEmpty()) return List.of();
        LocalDate from = sessions.get(sessions.size() - 1);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("f", from).addValue("t", day)
                .addValue("ne", Sectors.NON_EQUITY);

        Map<LocalDate, double[]> breadth = new HashMap<>();
        jdbc.query("""
                SELECT p.trade_date, AVG(p.change_pct), SUM(p.change_pct > 0), SUM(p.change_pct < 0)
                FROM daily_price p LEFT JOIN stock s ON s.symbol = p.symbol
                WHERE p.trade_date BETWEEN :f AND :t AND ABS(p.change_pct) <= 10.5
                  AND (s.sector IS NULL OR s.sector NOT IN (:ne))
                GROUP BY p.trade_date""", p,
                rs -> { breadth.put(rs.getObject(1, LocalDate.class), new double[] {rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)}); });
        Map<LocalDate, double[]> topSums = new HashMap<>();
        jdbc.query("""
                SELECT trade_date, SUM(CASE WHEN list_type = 'TURNOVER' THEN metric_value END),
                       SUM(CASE WHEN list_type = 'VOLUME' THEN metric_value END)
                FROM top_ranking WHERE trade_date BETWEEN :f AND :t GROUP BY trade_date""", p,
                rs -> { topSums.put(rs.getObject(1, LocalDate.class), new double[] {rs.getDouble(2), rs.getDouble(3)}); });
        List<Object[]> days = jdbc.query("""
                SELECT trade_date, total_turnover, total_volume, total_trades, stock_count
                FROM trading_day WHERE trade_date BETWEEN :f AND :t ORDER BY trade_date""", p,
                (rs, i) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getInt(5)});

        List<MarketDay> all = new ArrayList<>();
        double index = 1000;
        for (int i = 0; i < days.size(); i++) {
            Object[] d = days.get(i);
            LocalDate date = (LocalDate) d[0];
            double turnover = (double) d[1], volume = (double) d[2];
            double[] b = breadth.get(date);
            if (i > 0 && b != null) index *= 1 + b[0] / 100;
            Double avg20 = i >= Rules.MIN_HISTORY
                    ? days.subList(Math.max(0, i - Rules.AVG_SESSIONS), i).stream().mapToDouble(x -> (double) x[1]).average().orElse(0)
                    : null;
            double[] top = topSums.get(date);
            all.add(new MarketDay(date, turnover, volume, (Long) d[3], (int) d[4], avg20,
                    top != null && turnover > 0 ? top[0] / turnover : null,
                    top != null && volume > 0 ? top[1] / volume : null,
                    index, b == null ? null : (int) b[1], b == null ? null : (int) b[2]));
        }
        return all;
    }

    /** Reads index direction against turnover over the last 10 sessions (needs 25+ stored sessions). */
    public MarketRegime regime(LocalDate day) {
        List<MarketDay> m = marketDays(0, day);
        int L = m.size() - 1;
        if (L < 25) {
            return new MarketRegime("NA", "Not enough history", "neutral",
                    "Needs 25 stored sessions to compare the index with turnover (" + (L + 1) + " so far).", null, null, null);
        }
        double idx10 = m.get(L).equalWeightIndex() / m.get(L - 10).equalWeightIndex() - 1;
        double t5 = m.subList(L - 4, L + 1).stream().mapToDouble(MarketDay::turnover).average().orElse(0);
        double t20 = m.subList(Math.max(0, L - 24), L - 4).stream().mapToDouble(MarketDay::turnover).average().orElse(0);
        double trend = t20 > 0 ? t5 / t20 : 1;
        double hi60 = m.subList(Math.max(0, L - 59), L + 1).stream().mapToDouble(MarketDay::equalWeightIndex).max().orElse(1);
        double dd60 = m.get(L).equalWeightIndex() / hi60 - 1;

        String key, label, tone, text;
        if (idx10 > 0.01 && trend >= 1.0) {
            key = "HEALTHY"; label = "Healthy rally"; tone = "good";
            text = "Prices rising and turnover rising: new money supports the move.";
        } else if (idx10 > 0.01) {
            key = "WEAK_RALLY"; label = "Weak rally"; tone = "warn";
            text = "Prices rising but turnover falling: the rally may not last.";
        } else if (idx10 < -0.01 && trend >= 1.0) {
            key = "HEAVY_SELLING"; label = "Heavy selling"; tone = "bad";
            text = "Prices falling on high turnover: be careful with new buys.";
        } else if (idx10 < -0.01 && trend <= 0.7 && dd60 <= -0.10) {
            key = "DRYING_UP"; label = "Selling drying up"; tone = "warn";
            text = "Turnover drying up after a long fall: sellers may be exhausted, often near a bottom.";
        } else if (idx10 < -0.01) {
            key = "QUIET_DECLINE"; label = "Quiet decline"; tone = "neutral";
            text = "Prices drifting lower on normal or falling turnover.";
        } else {
            key = "SIDEWAYS"; label = "Sideways"; tone = "neutral";
            text = "No clear direction over the last 10 sessions.";
        }
        return new MarketRegime(key, label, tone, text, idx10 * 100, trend, dd60 * 100);
    }
}
