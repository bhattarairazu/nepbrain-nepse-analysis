package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.repository.FloorsheetRepository;

/**
 * Broker analysis from the stored floorsheet: who is accumulating or distributing which stocks over the last N sessions,
 * how concentrated the buying is, and where one broker dominates both sides. Self-trades (same broker on both sides)
 * are left out of net figures and reported separately.
 */
@Service
public class BrokerAnalysisService {
    /** Ignore stocks with less traded value than this over the window (noise). */
    public static final double MIN_AMOUNT = 5e7; // Rs 5 crore

    public record BrokerLine(int broker, double buyQty, double buyAmount, double sellQty, double sellAmount, double selfQty,
                             int daysNetBuying, int daysActive) {
        public double netQty() { return buyQty - sellQty; }
        public double netAmount() { return buyAmount - sellAmount; }
        public Double avgBuy() { return buyQty > 0 ? buyAmount / buyQty : null; }
        public Double avgSell() { return sellQty > 0 ? sellAmount / sellQty : null; }
    }

    public enum Signal {
        ACCUMULATION("Accumulation", "good", "A few brokers keep buying more than they sell."),
        DISTRIBUTION("Distribution", "bad", "A few brokers keep selling more than they buy."),
        OPERATOR("Operator warning", "warn", "One broker dominates both buying and selling, or many self-trades.");

        private final String label, tone, meaning;

        Signal(String label, String tone, String meaning) {
            this.label = label;
            this.tone = tone;
            this.meaning = meaning;
        }

        public String getLabel() { return label; }
        public String getTone() { return tone; }
        public String getMeaning() { return meaning; }
    }

    public record StockFlow(String symbol, String sector, double volume, double amount, Double priceChangePct, Double lastClose,
                            double top3NetBuyShare, double top3NetSellShare, double topBuyerShare, double selfShare,
                            BrokerLine topBuyer, BrokerLine topSeller, Double topBuyersAvgPrice, List<Signal> signals) {}

    public record MarketView(List<LocalDate> days, List<StockFlow> accumulation, List<StockFlow> distribution,
                             List<StockFlow> operator, int stocks) {}

    public record StockView(String symbol, List<LocalDate> days, StockFlow flow, List<BrokerLine> brokers) {}

    public record BrokerStock(String symbol, String sector, double buyQty, double sellQty, double netQty, double netAmount,
                              Double avgBuy, Double lastClose, int daysNetBuying) {}

    public record BrokerView(int broker, List<LocalDate> days, double buyAmount, double sellAmount,
                             List<BrokerStock> accumulating, List<BrokerStock> distributing) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final FloorsheetRepository floorsheet;

    public BrokerAnalysisService(NamedParameterJdbcTemplate jdbc, FloorsheetRepository floorsheet) {
        this.jdbc = jdbc;
        this.floorsheet = floorsheet;
    }

    public List<LocalDate> days(int sessions) {
        return floorsheet.days(sessions);
    }

    // ------------------------------------------------------------------ market view

    public MarketView market(int sessions) {
        List<LocalDate> days = days(sessions);
        if (days.isEmpty()) return new MarketView(days, List.of(), List.of(), List.of(), 0);
        Map<String, Map<Integer, BrokerLine>> bySymbol = lines(days, null, null);
        Map<String, String> sectors = sectors();
        Map<String, double[]> prices = prices(days);
        List<StockFlow> flows = new ArrayList<>();
        bySymbol.forEach((sym, lines) -> {
            StockFlow f = flow(sym, sectors.get(sym), lines, prices.get(sym), days.size());
            if (f != null && f.amount() >= MIN_AMOUNT) flows.add(f);
        });
        return new MarketView(days,
                flows.stream().filter(f -> f.signals().contains(Signal.ACCUMULATION))
                        .sorted(Comparator.comparingDouble(StockFlow::top3NetBuyShare).reversed()).limit(25).toList(),
                flows.stream().filter(f -> f.signals().contains(Signal.DISTRIBUTION))
                        .sorted(Comparator.comparingDouble(StockFlow::top3NetSellShare).reversed()).limit(25).toList(),
                flows.stream().filter(f -> f.signals().contains(Signal.OPERATOR))
                        .sorted(Comparator.comparingDouble(StockFlow::topBuyerShare).reversed()).limit(25).toList(),
                flows.size());
    }

    // ------------------------------------------------------------------ one stock

    public StockView stock(String symbol, int sessions) {
        List<LocalDate> days = days(sessions);
        if (days.isEmpty()) return new StockView(symbol, days, null, List.of());
        Map<Integer, BrokerLine> lines = lines(days, symbol, null).getOrDefault(symbol, Map.of());
        StockFlow f = lines.isEmpty() ? null : flow(symbol, sectors().get(symbol), lines, prices(days).get(symbol), days.size());
        List<BrokerLine> sorted = lines.values().stream().sorted(Comparator.comparingDouble(BrokerLine::netQty).reversed()).toList();
        return new StockView(symbol, days, f, sorted);
    }

    // ------------------------------------------------------------------ one broker

    public BrokerView broker(int broker, int sessions) {
        List<LocalDate> days = days(sessions);
        if (days.isEmpty()) return new BrokerView(broker, days, 0, 0, List.of(), List.of());
        Map<String, Map<Integer, BrokerLine>> bySymbol = lines(days, null, broker);
        Map<String, String> sectors = sectors();
        Map<String, double[]> prices = prices(days);
        List<BrokerStock> rows = new ArrayList<>();
        double buy = 0, sell = 0;
        for (var e : bySymbol.entrySet()) {
            BrokerLine l = e.getValue().get(broker);
            if (l == null) continue;
            buy += l.buyAmount();
            sell += l.sellAmount();
            double[] p = prices.get(e.getKey());
            rows.add(new BrokerStock(e.getKey(), sectors.get(e.getKey()), l.buyQty(), l.sellQty(), l.netQty(), l.netAmount(),
                    l.avgBuy(), p == null ? null : p[1], l.daysNetBuying()));
        }
        return new BrokerView(broker, days, buy, sell,
                rows.stream().filter(r -> r.netAmount() > 0).sorted(Comparator.comparingDouble(BrokerStock::netAmount).reversed()).limit(30).toList(),
                rows.stream().filter(r -> r.netAmount() < 0).sorted(Comparator.comparingDouble(BrokerStock::netAmount)).limit(30).toList());
    }

    // ------------------------------------------------------------------ signal rules

    static StockFlow flow(String symbol, String sector, Map<Integer, BrokerLine> lines, double[] price, int sessions) {
        double volume = lines.values().stream().mapToDouble(BrokerLine::buyQty).sum();
        double amount = lines.values().stream().mapToDouble(BrokerLine::buyAmount).sum();
        double self = lines.values().stream().mapToDouble(BrokerLine::selfQty).sum();
        if (volume <= 0) return null;
        List<BrokerLine> byNet = lines.values().stream().sorted(Comparator.comparingDouble(BrokerLine::netQty).reversed()).toList();
        List<BrokerLine> topBuyers = byNet.stream().filter(l -> l.netQty() > 0).limit(3).toList();
        List<BrokerLine> topSellers = byNet.reversed().stream().filter(l -> l.netQty() < 0).limit(3).toList();
        double top3Buy = topBuyers.stream().mapToDouble(BrokerLine::netQty).sum() / volume;
        double top3Sell = -topSellers.stream().mapToDouble(BrokerLine::netQty).sum() / volume;
        BrokerLine biggestBuyer = lines.values().stream().max(Comparator.comparingDouble(BrokerLine::buyQty)).orElseThrow();
        double topBuyerShare = biggestBuyer.buyQty() / volume;
        double topBuyerSellShare = biggestBuyer.sellQty() / volume;
        double selfShare = self / (volume + self);
        double boughtQty = topBuyers.stream().mapToDouble(BrokerLine::buyQty).sum();
        Double topAvg = boughtQty > 0 ? topBuyers.stream().mapToDouble(BrokerLine::buyAmount).sum() / boughtQty : null;
        Double change = price == null || price[0] <= 0 ? null : (price[1] / price[0] - 1) * 100;

        List<Signal> signals = new ArrayList<>();
        int needDays = Math.max(1, (int) Math.ceil(sessions * 0.6));
        boolean steadyBuyers = !topBuyers.isEmpty() && topBuyers.get(0).daysNetBuying() >= needDays;
        if (top3Buy >= 0.30 && steadyBuyers) signals.add(Signal.ACCUMULATION);
        if (top3Sell >= 0.30 && (change == null || change >= -2)) signals.add(Signal.DISTRIBUTION);
        if ((topBuyerShare >= 0.40 && topBuyerSellShare >= 0.25) || selfShare >= 0.10) signals.add(Signal.OPERATOR);
        return new StockFlow(symbol, sector, volume, amount, change, price == null ? null : price[1], top3Buy, top3Sell,
                topBuyerShare, selfShare, topBuyers.isEmpty() ? null : topBuyers.get(0), topSellers.isEmpty() ? null : topSellers.get(0),
                topAvg, signals);
    }

    // ------------------------------------------------------------------ data

    /** symbol -> broker -> totals over the days (optionally one symbol, or only stocks one broker traded). */
    private Map<String, Map<Integer, BrokerLine>> lines(List<LocalDate> days, String symbol, Integer broker) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("d", days);
        String where = "trade_date IN (:d)";
        if (symbol != null) { where += " AND symbol = :s"; p.addValue("s", symbol); }
        if (broker != null) { where += " AND symbol IN (SELECT DISTINCT symbol FROM broker_stock_daily WHERE broker = :b AND trade_date IN (:d))"; p.addValue("b", broker); }
        Map<String, Map<Integer, double[]>> acc = new LinkedHashMap<>();
        Map<String, Map<Integer, int[]>> daysCount = new HashMap<>();
        jdbc.query("SELECT symbol, broker, buy_qty, buy_amount, sell_qty, sell_amount, self_qty FROM broker_stock_daily WHERE " + where,
                p, rs -> {
                    String sym = rs.getString(1);
                    int b = rs.getInt(2);
                    double bq = rs.getDouble(3), ba = rs.getDouble(4), sq = rs.getDouble(5), sa = rs.getDouble(6), self = rs.getDouble(7);
                    double[] a = acc.computeIfAbsent(sym, k -> new HashMap<>()).computeIfAbsent(b, k -> new double[5]);
                    a[0] += bq; a[1] += ba; a[2] += sq; a[3] += sa; a[4] += self;
                    int[] c = daysCount.computeIfAbsent(sym, k -> new HashMap<>()).computeIfAbsent(b, k -> new int[2]);
                    if (bq > sq) c[0]++;
                    c[1]++;
                });
        Map<String, Map<Integer, BrokerLine>> out = new LinkedHashMap<>();
        acc.forEach((sym, m) -> {
            Map<Integer, BrokerLine> lm = new HashMap<>();
            m.forEach((b, a) -> {
                int[] c = daysCount.get(sym).get(b);
                lm.put(b, new BrokerLine(b, a[0], a[1], a[2], a[3], a[4], c[0], c[1]));
            });
            out.put(sym, lm);
        });
        return out;
    }

    private Map<String, String> sectors() {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT symbol, sector FROM stock", rs -> { m.put(rs.getString(1), rs.getString(2)); });
        return m;
    }

    /** symbol -> {close before the window (prev close of the first day), last close in the window}. */
    private Map<String, double[]> prices(List<LocalDate> days) {
        LocalDate first = days.get(days.size() - 1), last = days.get(0);
        Map<String, double[]> m = new HashMap<>();
        Set<String> seen = new HashSet<>();
        jdbc.query("SELECT symbol, trade_date, prev_close, close_price FROM daily_price WHERE trade_date IN (:a, :b)",
                new MapSqlParameterSource().addValue("a", first).addValue("b", last), rs -> {
                    double[] p = m.computeIfAbsent(rs.getString(1), k -> new double[2]);
                    LocalDate d = rs.getObject(2, LocalDate.class);
                    if (d.equals(first)) p[0] = rs.getDouble(3);
                    if (d.equals(last)) { p[1] = rs.getDouble(4); seen.add(rs.getString(1)); }
                });
        m.keySet().retainAll(seen);
        return m;
    }
}
