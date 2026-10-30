package com.nepse.topturnover.analysis;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Sector performance over any date range, built from the stored daily table of every stock.
 * Sector "index" = equal-weight average of its stocks' daily % changes, chained. It tracks the sector's
 * direction but is not the official NEPSE sub-index (which is weighted by market value).
 */
@Service
public class SectorAnalysisService {

    public enum Grouping { WEEKLY, MONTHLY }

    public enum Quadrant {
        LEADING("Leading", "good", "Beating the market and taking a growing share of turnover."),
        IMPROVING("Improving", "warn", "Money flowing in faster than the price has moved yet: often the next trending sector."),
        WEAKENING("Weakening", "warn", "Still ahead of the market, but its share of turnover is shrinking."),
        LAGGING("Lagging", "bad", "Behind the market and losing share of turnover.");

        private final String label;
        private final String tone;
        private final String meaning;

        Quadrant(String label, String tone, String meaning) {
            this.label = label;
            this.tone = tone;
            this.meaning = meaning;
        }

        public String getLabel() { return label; }
        public String getTone() { return tone; }
        public String getMeaning() { return meaning; }

        /** relReturn = sector return - market return (pct points); shareChange in pct points, null if unknown. */
        public static Quadrant of(double relReturn, Double shareChange) {
            boolean ahead = relReturn > 0;
            if (shareChange == null) return ahead ? LEADING : LAGGING;
            boolean gaining = shareChange > 0;
            if (ahead) return gaining ? LEADING : WEAKENING;
            return gaining ? IMPROVING : LAGGING;
        }
    }

    public record Period(LocalDate from, LocalDate to, int sessions, Integer prevSessions, String preset) {}

    public record Bucket(String label, LocalDate start, LocalDate end, int sessions) {}

    public record SectorStat(String sector, int stocks, double returnPct, double relReturnPct, Double prevReturnPct,
                             double turnover, double turnoverShare, Double shareChangePts, double breadthUpPct,
                             int top20Slots, Quadrant quadrant, int score) {}

    public record Series(String name, List<Double> index, List<Double> bucketReturn, List<Double> bucketShare,
                         List<Integer> bucketSlots) {}

    public record Analysis(Period period, Grouping grouping, double marketReturnPct, double marketTurnover,
                           List<SectorStat> sectors, List<Bucket> buckets, List<LocalDate> dates,
                           Map<String, Series> series, Series market, int unassignedStocks) {
        public Optional<SectorStat> sector(String name) {
            return sectors.stream().filter(s -> s.sector().equals(name)).findFirst();
        }
    }

    public record StockStat(String symbol, String name, double returnPct, double relToSectorPct, double turnover,
                            Double turnoverGrowth, double shareOfSector, int sessionsTraded, int upDays, int top20Slots,
                            Double lastClose, Double lastChangePct, List<Pattern> patterns, int score,
                            List<String> highlights) {}

    public record SectorDetail(Analysis analysis, SectorStat stat, List<StockStat> stocks) {}

    // ------------------------------------------------------------------

    private static final double MAX_DAILY_MOVE = 10.5; // ignore listing-day jumps and bad rows in averages
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketDataRepository repo;
    private final FeatureService features;

    public SectorAnalysisService(NamedParameterJdbcTemplate jdbc, MarketDataRepository repo, FeatureService features) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.features = features;
    }

    /** Resolves a preset (1w, 1m, 3m, 6m, 1y) or a custom from/to into stored sessions. Empty if none. */
    public Optional<Period> period(String preset, LocalDate from, LocalDate to) {
        LocalDate latest = repo.latestDay().orElse(null);
        if (latest == null) return Optional.empty();
        LocalDate end = to != null && to.isBefore(latest) ? to : latest;
        String p = preset == null ? "3m" : preset;
        LocalDate start = switch (p) {
            case "1w" -> end.minusDays(6);
            case "1m" -> end.minusMonths(1).plusDays(1);
            case "6m" -> end.minusMonths(6).plusDays(1);
            case "1y" -> end.minusYears(1).plusDays(1);
            case "custom" -> from != null ? from : end.minusMonths(3).plusDays(1);
            default -> end.minusMonths(3).plusDays(1);
        };
        if (start.isAfter(end)) start = end;
        List<LocalDate> sessions = sessionsBetween(start, end);
        if (sessions.isEmpty()) return Optional.empty();
        Integer prev = jdbc.queryForObject("SELECT COUNT(*) FROM trading_day WHERE trade_date < :f",
                Map.of("f", sessions.get(0)), Integer.class);
        return Optional.of(new Period(sessions.get(0), sessions.get(sessions.size() - 1), sessions.size(),
                prev == null ? 0 : Math.min(prev, sessions.size()), p));
    }

    public Optional<Analysis> analyse(Period period, Grouping grouping) {
        List<LocalDate> sessions = sessionsBetween(period.from(), period.to());
        if (sessions.isEmpty()) return Optional.empty();
        List<LocalDate> prevSessions = previousSessions(sessions.get(0), sessions.size());
        Data data = load(prevSessions.isEmpty() ? sessions.get(0) : prevSessions.get(0), period.to());

        Map<String, Integer> sectorStocks = new HashMap<>();
        int[] unassigned = {0};
        jdbc.query("SELECT sector, COUNT(*) FROM stock WHERE sector IS NULL OR sector NOT IN (:ne) GROUP BY sector",
                Map.of("ne", Sectors.NON_EQUITY), rs -> {
                    if (rs.getString(1) == null) unassigned[0] = rs.getInt(2);
                    else sectorStocks.put(rs.getString(1), rs.getInt(2));
                });

        List<Bucket> buckets = buckets(sessions, grouping);
        Series market = series("Market", data, null, sessions, buckets);
        double marketReturn = chained(data.dailyAvg(null), sessions) * 100;
        double marketTurnover = sum(data.dailyTurnover(null), sessions);
        double prevMarketTurnover = sum(data.dailyTurnover(null), prevSessions);

        Map<String, Series> series = new LinkedHashMap<>();
        List<SectorStat> raw = new ArrayList<>();
        for (String sector : data.sectors()) {
            if (sector.equals(UNASSIGNED)) continue;
            double ret = chained(data.dailyAvg(sector), sessions) * 100;
            Double prevRet = prevSessions.isEmpty() ? null : chained(data.dailyAvg(sector), prevSessions) * 100;
            double turnover = sum(data.dailyTurnover(sector), sessions);
            double share = marketTurnover > 0 ? turnover / marketTurnover : 0;
            Double prevShare = prevMarketTurnover > 0 ? sum(data.dailyTurnover(sector), prevSessions) / prevMarketTurnover : null;
            Double shareChange = prevShare == null ? null : (share - prevShare) * 100;
            List<Double> stockReturns = data.stockReturns(sector, sessions);
            double breadth = stockReturns.isEmpty() ? 0 : 100.0 * stockReturns.stream().filter(r -> r > 0).count() / stockReturns.size();
            int slots = data.slots(sector, sessions);
            raw.add(new SectorStat(sector, sectorStocks.getOrDefault(sector, 0), ret, ret - marketReturn, prevRet,
                    turnover, share, shareChange, breadth, slots, Quadrant.of(ret - marketReturn, shareChange), 0));
            series.put(sector, series(sector, data, sector, sessions, buckets));
        }

        // score: percentile of return (40%), share change (30%), breadth (30%)
        Map<String, Double> pRet = percentiles(raw, SectorStat::returnPct);
        Map<String, Double> pShare = percentiles(raw, s -> s.shareChangePts() == null ? 0 : s.shareChangePts());
        Map<String, Double> pBreadth = percentiles(raw, SectorStat::breadthUpPct);
        List<SectorStat> stats = raw.stream().map(s -> new SectorStat(s.sector(), s.stocks(), s.returnPct(), s.relReturnPct(),
                        s.prevReturnPct(), s.turnover(), s.turnoverShare(), s.shareChangePts(), s.breadthUpPct(), s.top20Slots(),
                        s.quadrant(), (int) Math.round(40 * pRet.get(s.sector()) + 30 * pShare.get(s.sector()) + 30 * pBreadth.get(s.sector()))))
                .sorted(Comparator.comparingInt(SectorStat::score).reversed().thenComparing(SectorStat::returnPct, Comparator.reverseOrder()))
                .toList();

        return Optional.of(new Analysis(period, grouping, marketReturn, marketTurnover, stats, buckets, sessions,
                series, market, unassigned[0]));
    }

    /** The sector's stocks over the period, ranked by a trend score. */
    public Optional<SectorDetail> detail(String sector, Period period, Grouping grouping) {
        Optional<Analysis> a = analyse(period, grouping);
        if (a.isEmpty() || a.get().sector(sector).isEmpty()) return Optional.empty();
        Analysis analysis = a.get();
        SectorStat stat = analysis.sector(sector).get();
        List<LocalDate> sessions = analysis.dates();
        List<LocalDate> prevSessions = previousSessions(sessions.get(0), sessions.size());
        Data data = load(prevSessions.isEmpty() ? sessions.get(0) : prevSessions.get(0), period.to());

        Map<String, List<Pattern>> patterns = new HashMap<>();
        features.snapshot(period.to()).ifPresent(snap -> snap.features().values().forEach(f -> {
            if (sector.equals(f.sector())) patterns.put(f.symbol(), f.patterns().stream().map(StockFeatures.PatternHit::pattern).toList());
        }));

        record Raw(String symbol, String name, double ret, double turnover, Double growth, int traded, int up, int slots,
                   Double close, Double change) {}
        List<Raw> raws = new ArrayList<>();
        for (Data.StockRows s : data.stocksIn(sector)) {
            List<Data.Row> inPeriod = s.rows().stream().filter(r -> !r.date().isBefore(period.from()) && !r.date().isAfter(period.to())).toList();
            if (inPeriod.isEmpty()) continue;
            double ret = 1;
            int up = 0;
            for (Data.Row r : inPeriod) {
                if (!Double.isNaN(r.change()) && Math.abs(r.change()) <= MAX_DAILY_MOVE) ret *= 1 + r.change() / 100;
                if (r.change() > 0) up++;
            }
            double turnover = inPeriod.stream().mapToDouble(Data.Row::turnover).filter(x -> !Double.isNaN(x)).sum();
            double prevTurnover = s.rows().stream().filter(r -> r.date().isBefore(period.from()))
                    .mapToDouble(Data.Row::turnover).filter(x -> !Double.isNaN(x)).sum();
            Data.Row last = inPeriod.get(inPeriod.size() - 1);
            raws.add(new Raw(s.symbol(), s.name(), (ret - 1) * 100, turnover,
                    prevTurnover > 0 ? turnover / prevTurnover : null, inPeriod.size(), up,
                    data.slotsFor(s.symbol(), sessions), nan(last.close()), nan(last.change())));
        }
        double sectorTurnover = raws.stream().mapToDouble(Raw::turnover).sum();

        Map<String, Double> pRet = percentiles(raws, Raw::ret, Raw::symbol);
        Map<String, Double> pGrowth = percentiles(raws, r -> r.growth() == null ? 1 : r.growth(), Raw::symbol);
        Map<String, Double> pSlots = percentiles(raws, r -> r.slots(), Raw::symbol);
        Map<String, Double> pUp = percentiles(raws, r -> r.traded() == 0 ? 0 : (double) r.up() / r.traded(), Raw::symbol);

        List<StockStat> stocks = raws.stream().map(r -> {
            int score = (int) Math.round(40 * pRet.get(r.symbol()) + 30 * pGrowth.get(r.symbol())
                    + 15 * pSlots.get(r.symbol()) + 15 * pUp.get(r.symbol()));
            List<String> hl = new ArrayList<>();
            List<Pattern> pts = patterns.getOrDefault(r.symbol(), List.of());
            if (pts.contains(Pattern.CLIMAX_TOP) || pts.contains(Pattern.CIRCUIT_RUN)) {
                score = Math.max(0, score - 25); // a warning pattern outweighs a big return
                hl.add("warning pattern: −25");
            }
            if (r.ret() - stat.returnPct() > 0) hl.add(String.format(Locale.ROOT, "%+.1f pts vs sector", r.ret() - stat.returnPct()));
            if (r.growth() != null && r.growth() >= 1.5) hl.add(String.format(Locale.ROOT, "turnover %.1f× previous period", r.growth()));
            if (r.slots() > 0) hl.add(r.slots() + " day(s) in top-20 turnover");
            return new StockStat(r.symbol(), r.name(), r.ret(), r.ret() - stat.returnPct(), r.turnover(), r.growth(),
                    sectorTurnover > 0 ? r.turnover() / sectorTurnover : 0, r.traded(), r.up(), r.slots(), r.close(), r.change(),
                    pts, score, hl);
        }).sorted(Comparator.comparingInt(StockStat::score).reversed().thenComparing(StockStat::returnPct, Comparator.reverseOrder())).toList();

        return Optional.of(new SectorDetail(analysis, stat, stocks));
    }

    // ------------------------------------------------------------------ data loading

    private static final String UNASSIGNED = "Unassigned";

    /** Every equity row in [from, to] with its sector, plus top-20 turnover slots. */
    private Data load(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("f", from).addValue("t", to).addValue("ne", Sectors.NON_EQUITY);
        Map<String, Data.StockRows> stocks = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.symbol, s.name, COALESCE(s.sector, 'Unassigned'), p.trade_date, p.change_pct, p.turnover, p.close_price
                FROM daily_price p LEFT JOIN stock s ON s.symbol = p.symbol
                WHERE p.trade_date BETWEEN :f AND :t AND (s.sector IS NULL OR s.sector NOT IN (:ne))
                ORDER BY p.symbol, p.trade_date""", p, rs -> {
            Data.StockRows sr = stocks.computeIfAbsent(rs.getString(1),
                    k -> new Data.StockRows(k, rsString(rs, 2), rsString(rs, 3), new ArrayList<>()));
            sr.rows().add(new Data.Row(rs.getObject(4, LocalDate.class), d(rs, 5), d(rs, 6), d(rs, 7)));
        });
        Map<String, Map<LocalDate, Integer>> slots = new HashMap<>();
        Map<String, Map<LocalDate, Integer>> symbolSlots = new HashMap<>();
        jdbc.query("""
                SELECT r.trade_date, COALESCE(s.sector, 'Unassigned'), r.symbol FROM top_ranking r
                LEFT JOIN stock s ON s.symbol = r.symbol
                WHERE r.list_type = 'TURNOVER' AND r.trade_date BETWEEN :f AND :t""", p, rs -> {
            LocalDate d = rs.getObject(1, LocalDate.class);
            slots.computeIfAbsent(rs.getString(2), k -> new HashMap<>()).merge(d, 1, Integer::sum);
            symbolSlots.computeIfAbsent(rs.getString(3), k -> new HashMap<>()).merge(d, 1, Integer::sum);
        });
        return new Data(stocks, slots, symbolSlots);
    }

    private static String rsString(java.sql.ResultSet rs, int i) {
        try {
            return rs.getString(i);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** In-memory rows for a date range, with the per-day aggregates the analysis needs. */
    private record Data(Map<String, StockRows> stocks, Map<String, Map<LocalDate, Integer>> sectorSlots,
                        Map<String, Map<LocalDate, Integer>> symbolSlots) {
        record Row(LocalDate date, double change, double turnover, double close) {}

        record StockRows(String symbol, String name, String sector, List<Row> rows) {}

        List<String> sectors() {
            return stocks.values().stream().map(StockRows::sector).distinct().sorted().toList();
        }

        List<StockRows> stocksIn(String sector) {
            return stocks.values().stream().filter(s -> s.sector().equals(sector)).toList();
        }

        /** Equal-weight average daily change for a sector (null sector = whole market). */
        Map<LocalDate, Double> dailyAvg(String sector) {
            Map<LocalDate, double[]> acc = new HashMap<>();
            for (StockRows s : stocks.values()) {
                if (sector != null && !s.sector().equals(sector)) continue;
                for (Row r : s.rows()) {
                    if (Double.isNaN(r.change()) || Math.abs(r.change()) > MAX_DAILY_MOVE) continue;
                    double[] a = acc.computeIfAbsent(r.date(), k -> new double[2]);
                    a[0] += r.change();
                    a[1]++;
                }
            }
            Map<LocalDate, Double> out = new HashMap<>();
            acc.forEach((d, a) -> out.put(d, a[0] / a[1]));
            return out;
        }

        Map<LocalDate, Double> dailyTurnover(String sector) {
            Map<LocalDate, Double> out = new HashMap<>();
            for (StockRows s : stocks.values()) {
                if (sector != null && !s.sector().equals(sector)) continue;
                for (Row r : s.rows()) if (!Double.isNaN(r.turnover())) out.merge(r.date(), r.turnover(), Double::sum);
            }
            return out;
        }

        /** Each stock's chained return over the sessions (stocks with at least one session). */
        List<Double> stockReturns(String sector, List<LocalDate> sessions) {
            LocalDate from = sessions.get(0), to = sessions.get(sessions.size() - 1);
            List<Double> out = new ArrayList<>();
            for (StockRows s : stocksIn(sector)) {
                double r = 1;
                boolean any = false;
                for (Row row : s.rows()) {
                    if (row.date().isBefore(from) || row.date().isAfter(to)) continue;
                    any = true;
                    if (!Double.isNaN(row.change()) && Math.abs(row.change()) <= MAX_DAILY_MOVE) r *= 1 + row.change() / 100;
                }
                if (any) out.add(r - 1);
            }
            return out;
        }

        int slots(String sector, List<LocalDate> sessions) {
            Map<LocalDate, Integer> m = sectorSlots.getOrDefault(sector, Map.of());
            return sessions.stream().mapToInt(d -> m.getOrDefault(d, 0)).sum();
        }

        int slotsFor(String symbol, List<LocalDate> sessions) {
            Map<LocalDate, Integer> m = symbolSlots.getOrDefault(symbol, Map.of());
            return sessions.stream().mapToInt(d -> m.getOrDefault(d, 0)).sum();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Series series(String name, Data data, String sector, List<LocalDate> sessions, List<Bucket> buckets) {
        Map<LocalDate, Double> avg = data.dailyAvg(sector);
        Map<LocalDate, Double> turnover = data.dailyTurnover(sector);
        Map<LocalDate, Double> total = data.dailyTurnover(null);
        List<Double> index = new ArrayList<>();
        double level = 100;
        for (LocalDate d : sessions) {
            level *= 1 + avg.getOrDefault(d, 0.0) / 100;
            index.add(level);
        }
        List<Double> bRet = new ArrayList<>(), bShare = new ArrayList<>();
        List<Integer> bSlots = new ArrayList<>();
        for (Bucket b : buckets) {
            List<LocalDate> in = sessions.stream().filter(d -> !d.isBefore(b.start()) && !d.isAfter(b.end())).toList();
            bRet.add(chained(avg, in) * 100);
            double t = sum(turnover, in), all = sum(total, in);
            bShare.add(all > 0 ? t / all : null);
            bSlots.add(sector == null ? 0 : data.slots(sector, in));
        }
        return new Series(name, index, bRet, bShare, bSlots);
    }

    private List<Bucket> buckets(List<LocalDate> sessions, Grouping g) {
        Map<LocalDate, List<LocalDate>> groups = new LinkedHashMap<>();
        for (LocalDate d : sessions) {
            LocalDate key = g == Grouping.MONTHLY ? d.withDayOfMonth(1) : d.with(DayOfWeek.MONDAY);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }
        List<Bucket> out = new ArrayList<>();
        groups.forEach((k, days) -> out.add(new Bucket(
                g == Grouping.MONTHLY ? MONTH.format(k) : "Wk " + WEEK.format(k),
                days.get(0), days.get(days.size() - 1), days.size())));
        return out;
    }

    private List<LocalDate> sessionsBetween(LocalDate from, LocalDate to) {
        return jdbc.queryForList("SELECT trade_date FROM trading_day WHERE trade_date BETWEEN :f AND :t ORDER BY trade_date",
                new MapSqlParameterSource().addValue("f", from).addValue("t", to), LocalDate.class);
    }

    /** The n stored sessions immediately before `first`, oldest first. */
    private List<LocalDate> previousSessions(LocalDate first, int n) {
        List<LocalDate> prev = new ArrayList<>(jdbc.queryForList(
                "SELECT trade_date FROM trading_day WHERE trade_date < :f ORDER BY trade_date DESC LIMIT :n",
                new MapSqlParameterSource().addValue("f", first).addValue("n", n), LocalDate.class));
        java.util.Collections.reverse(prev);
        return prev;
    }

    private static double chained(Map<LocalDate, Double> dailyPct, List<LocalDate> days) {
        double r = 1;
        for (LocalDate d : days) r *= 1 + dailyPct.getOrDefault(d, 0.0) / 100;
        return r - 1;
    }

    private static double sum(Map<LocalDate, Double> m, List<LocalDate> days) {
        return days.stream().mapToDouble(d -> m.getOrDefault(d, 0.0)).sum();
    }

    private static Map<String, Double> percentiles(List<SectorStat> list, ToDoubleFunction<SectorStat> f) {
        return percentiles(list, f, SectorStat::sector);
    }

    /** 0..1 rank of each item's value among all items (ties share the average rank). */
    static <T> Map<String, Double> percentiles(List<T> list, ToDoubleFunction<T> f, java.util.function.Function<T, String> key) {
        Map<String, Double> out = new HashMap<>();
        int n = list.size();
        for (T a : list) {
            if (n <= 1) { out.put(key.apply(a), 1.0); continue; }
            double v = f.applyAsDouble(a);
            long below = list.stream().filter(b -> f.applyAsDouble(b) < v).count();
            long equal = list.stream().filter(b -> f.applyAsDouble(b) == v).count() - 1;
            out.put(key.apply(a), (below + equal / 2.0) / (n - 1));
        }
        return out;
    }

    private static double d(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? Double.NaN : v;
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
