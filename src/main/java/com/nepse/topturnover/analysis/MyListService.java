package com.nepse.topturnover.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.StockReportService.Point;
import com.nepse.topturnover.repository.MyListRepository;
import com.nepse.topturnover.repository.MyListRepository.Item;
import com.nepse.topturnover.repository.MyListRepository.Latest;

/**
 * My list: stocks you follow by hand. For each one: your intent and price levels, alerts when the latest close reaches
 * them, the change since you added it, and every study's one-line read, so a buy or sell decision has everything in one row.
 */
@Service
public class MyListService {
    public enum Intent {
        WATCH("Watching", ""), BUY("Want to buy", "good"), HOLD("Holding", "warn"), SELL("Want to sell", "bad");

        private final String label, tone;

        Intent(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }

        public String getLabel() { return label; }
        public String getTone() { return tone; }

        static Intent of(String s) {
            try { return valueOf(s); } catch (RuntimeException e) { return WATCH; }
        }
    }

    public record Alert(String tone, String text) {}

    public record Row(Item item, Intent intent, Latest latest, Double sinceAddedPct, Double toBuyPct, Double toStopPct, Double toSellPct,
                      List<Alert> alerts, List<Point> reads) {}

    public record Board(List<Row> rows, int alerts, List<String> symbols) {}

    private final MyListRepository repo;
    private final StockReportService reports;

    public MyListService(MyListRepository repo, StockReportService reports) {
        this.repo = repo;
        this.reports = reports;
    }

    public Board board() {
        List<Item> items = repo.all();
        Map<String, Latest> latest = repo.latest(items.stream().map(Item::symbol).toList());
        List<Row> rows = new ArrayList<>();
        for (Item it : items) {
            Latest l = latest.get(it.symbol());
            List<Point> reads;
            try {
                reads = reports.summary(it.symbol());
            } catch (RuntimeException e) {
                reads = List.of(new Point("warn", "Analysis", "not available: " + e.getMessage()));
            }
            rows.add(row(it, l, reads));
        }
        int alerts = rows.stream().mapToInt(r -> r.alerts().size()).sum();
        return new Board(rows, alerts, repo.symbols());
    }

    private static Row row(Item it, Latest l, List<Point> reads) {
        Intent intent = Intent.of(it.intent());
        List<Alert> alerts = new ArrayList<>();
        Double since = null, toBuy = null, toStop = null, toSell = null;
        if (l != null) {
            double c = l.close();
            if (it.addedPrice() != null && it.addedPrice() > 0) since = (c / it.addedPrice() - 1) * 100;
            if (it.buyBelow() != null) {
                toBuy = (c / it.buyBelow() - 1) * 100;
                if (c <= it.buyBelow() && intent != Intent.HOLD && intent != Intent.SELL) alerts.add(new Alert("good",
                        String.format(Locale.ROOT, "In your buy zone: closed %.2f, at or under %.2f.", c, it.buyBelow())));
            }
            if (it.stopPrice() != null) {
                toStop = (c / it.stopPrice() - 1) * 100;
                if (c <= it.stopPrice()) alerts.add(new Alert("bad", String.format(Locale.ROOT, "Closed %.2f, at or under your stop %.2f%s.", c, it.stopPrice(),
                        intent == Intent.HOLD || intent == Intent.SELL ? ": sell at the next open (once settled, T+2)" : ": your plan is broken")));
                else if (toStop < 2) alerts.add(new Alert("warn", String.format(Locale.ROOT, "Only %.1f%% above your stop %.2f.", toStop, it.stopPrice())));
            }
            if (it.sellAbove() != null) {
                toSell = (c / it.sellAbove() - 1) * 100;
                if (c >= it.sellAbove()) alerts.add(new Alert("good", String.format(Locale.ROOT, "Reached your sell level: closed %.2f, at or above %.2f.",
                        c, it.sellAbove())));
            }
        }
        // what the studies say that matters for the decision
        for (Point p : reads) {
            if (p.topic().equals("Big players") && p.tone().equals("bad") && (intent == Intent.BUY || intent == Intent.WATCH)) {
                alerts.add(new Alert("warn", "Big players: " + firstSentence(p.text()) + " Buying now means buying from them."));
            }
            if (p.topic().equals("Big players") && p.tone().equals("bad") && intent == Intent.HOLD) {
                alerts.add(new Alert("warn", "Big players: " + firstSentence(p.text()) + " Consider taking profit."));
            }
            if (p.topic().equals("Breakout") && p.text().contains("failed") && (intent == Intent.HOLD || intent == Intent.BUY)) {
                alerts.add(new Alert("bad", "Breakout failed: the price is back below the breakout level."));
            }
            if (p.topic().equals("Brokers") && p.tone().equals("bad")) {
                alerts.add(new Alert("warn", "Brokers: " + p.text()));
            }
        }
        return new Row(it, intent, l, since, toBuy, toStop, toSell, alerts, reads);
    }

    private static String firstSentence(String s) {
        int i = s.indexOf(':');
        return (i > 0 ? s.substring(0, i) : s).strip() + ".";
    }

    // ------------------------------------------------------------------ changes

    /** Adds a stock at its latest close; returns an error message, or empty on success. */
    public Optional<String> add(String symbol, String intent, Double buyBelow, Double stopPrice, Double sellAbove, String note) {
        String sym = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
        int dash = sym.indexOf(" — ");
        if (dash > 0) sym = sym.substring(0, dash).strip();
        if (sym.isEmpty()) return Optional.of("Enter a symbol.");
        Latest l = repo.latest(List.of(sym)).get(sym);
        if (l == null) return Optional.of("No stored prices for " + sym + ".");
        Optional<String> bad = check(buyBelow, stopPrice, sellAbove);
        if (bad.isPresent()) return bad;
        boolean added = repo.add(sym, Intent.of(intent).name(), l.date(), l.close(), positive(buyBelow), positive(stopPrice), positive(sellAbove), note);
        return added ? Optional.empty() : Optional.of(sym + " is already on your list: edit it there.");
    }

    public Optional<String> update(long id, String intent, Double buyBelow, Double stopPrice, Double sellAbove, String note) {
        if (repo.find(id).isEmpty()) return Optional.of("That stock is no longer on your list.");
        Optional<String> bad = check(buyBelow, stopPrice, sellAbove);
        if (bad.isPresent()) return bad;
        repo.update(id, Intent.of(intent).name(), positive(buyBelow), positive(stopPrice), positive(sellAbove), note);
        return Optional.empty();
    }

    public Optional<String> remove(long id) {
        Optional<Item> it = repo.find(id);
        it.ifPresent(x -> repo.remove(id));
        return it.map(Item::symbol);
    }

    public boolean contains(String symbol) {
        return repo.contains(symbol.toUpperCase(Locale.ROOT));
    }

    private static Optional<String> check(Double buyBelow, Double stopPrice, Double sellAbove) {
        Double b = positive(buyBelow), s = positive(stopPrice), t = positive(sellAbove);
        if (s != null && t != null && s >= t) return Optional.of("The stop must be below the sell level.");
        if (b != null && t != null && b >= t) return Optional.of("The buy level must be below the sell level.");
        return Optional.empty();
    }

    private static Double positive(Double v) {
        return v == null || v <= 0 ? null : v;
    }
}
