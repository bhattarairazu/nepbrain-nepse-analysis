package com.nepse.topturnover.journal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.repository.JournalRepository;

/** Turns logged trades into money figures, open-position status and a review of what works for you. */
@Service
public class JournalService {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH);
    public static final int RELIABLE_AFTER = 30;

    /** A trade with its numbers. For open trades the numbers value it at the latest stored close, before tax. */
    public record View(JournalTrade t, TradeCosts.Breakdown b, Double lastClose, LocalDate lastDate, boolean stopBroken,
                       boolean targetReached) {}

    public record Group(String key, int trades, int wins, double winRate, double netPnl, double avgNetPct, Double avgR,
                        double profitFactor) {}

    public record Review(int closed, int open, double netPnl, double winRate, double profitFactor, Double avgR,
                         double avgNetPct, double charges, double tax, Double avgDaysWinners, Double avgDaysLosers,
                         Map<String, List<Group>> breakdowns, List<String> insights) {}

    private final JournalRepository repo;
    private final TradeCosts costs;

    public JournalService(JournalRepository repo, TradeCosts costs) {
        this.repo = repo;
        this.costs = costs;
    }

    public List<View> views() {
        List<View> out = new ArrayList<>();
        for (JournalTrade t : repo.all()) out.add(view(t));
        return out;
    }

    public View view(JournalTrade t) {
        if (!t.isOpen()) {
            return new View(t, costs.of(t, t.exitPrice(), t.exitDate(), true), null, null, false, false);
        }
        Object[] last = repo.latestClose(t.symbol()).orElse(null);
        double price = last != null ? (double) last[1] : t.entryPrice();
        LocalDate date = last != null ? (LocalDate) last[0] : t.entryDate();
        return new View(t, costs.of(t, price, date.isBefore(t.entryDate()) ? t.entryDate() : date, false),
                last != null ? price : null, last != null ? date : null,
                t.stopPrice() != null && last != null && price <= t.stopPrice(),
                t.targetPrice() != null && last != null && price >= t.targetPrice());
    }

    /** Review of trades closed between from and to (inclusive; nulls = no limit). */
    public Review review(List<View> all, LocalDate from, LocalDate to) {
        List<View> closed = all.stream().filter(v -> !v.t().isOpen())
                .filter(v -> from == null || !v.t().exitDate().isBefore(from))
                .filter(v -> to == null || !v.t().exitDate().isAfter(to)).toList();
        int open = (int) all.stream().filter(v -> v.t().isOpen()).count();
        Group total = group("All", closed);

        Map<String, List<Group>> b = new LinkedHashMap<>();
        b.put("Rule", by(closed, v -> blank(v.t().rule(), "No rule given")));
        b.put("Market regime at entry", by(closed, v -> blank(v.t().marketRegime(), "Not recorded")));
        b.put("Sector", by(closed, v -> blank(v.t().sector(), "Unknown")));
        b.put("Followed the plan", by(closed, v -> v.t().followedPlan() == null ? "Not recorded" : v.t().followedPlan() ? "Yes" : "No"));
        b.put("Mood when buying", by(closed, v -> blank(v.t().moodEntry(), "Not recorded")));
        b.put("Mood when selling", by(closed, v -> blank(v.t().moodExit(), "Not recorded")));
        b.put("Exit reason", by(closed, v -> blank(v.t().exitReason(), "Not recorded")));
        b.put("Month closed", by(closed, v -> MONTH.format(v.t().exitDate())).stream()
                .sorted(Comparator.comparing(Group::key).reversed()).toList());
        List<Group> mistakes = new ArrayList<>();
        Map<String, List<View>> byMistake = new LinkedHashMap<>();
        for (View v : closed) {
            List<String> tags = v.t().mistakeList();
            if (tags.isEmpty()) byMistake.computeIfAbsent("No mistakes logged", k -> new ArrayList<>()).add(v);
            for (String tag : tags) byMistake.computeIfAbsent(tag, k -> new ArrayList<>()).add(v);
        }
        byMistake.forEach((k, list) -> mistakes.add(group(k, list)));
        mistakes.sort(Comparator.comparingDouble(Group::netPnl));
        b.put("Mistake", mistakes);

        double charges = closed.stream().mapToDouble(v -> v.b().charges()).sum();
        double tax = closed.stream().mapToDouble(v -> v.b().tax()).sum();
        Double daysWin = avg(closed.stream().filter(v -> v.b().netPnl() > 0).mapToDouble(v -> v.b().daysHeld()).toArray());
        Double daysLoss = avg(closed.stream().filter(v -> v.b().netPnl() <= 0).mapToDouble(v -> v.b().daysHeld()).toArray());

        return new Review(closed.size(), open, total.netPnl(), total.winRate(), total.profitFactor(), total.avgR(),
                total.avgNetPct(), charges, tax, daysWin, daysLoss, b, insights(closed, b, charges, tax, daysWin, daysLoss));
    }

    // ------------------------------------------------------------------ insights in plain words

    private static List<String> insights(List<View> closed, Map<String, List<Group>> b, double charges, double tax,
                                         Double daysWin, Double daysLoss) {
        List<String> out = new ArrayList<>();
        if (closed.isEmpty()) {
            out.add("No closed trades yet. Log each trade when you buy, and close it in the journal when you sell.");
            return out;
        }
        if (closed.size() < RELIABLE_AFTER) {
            out.add(String.format(Locale.ROOT, "%d closed trade(s) so far. Patterns become reliable after about %d; read these as early hints.",
                    closed.size(), RELIABLE_AFTER));
        }
        Group yes = find(b.get("Followed the plan"), "Yes"), no = find(b.get("Followed the plan"), "No");
        if (yes != null && no != null) {
            out.add(String.format(Locale.ROOT, "When you followed your plan: %d trades, %.0f%% winners, %s net. When you didn't: %d trades, %.0f%% winners, %s net.",
                    yes.trades(), yes.winRate(), rs(yes.netPnl()), no.trades(), no.winRate(), rs(no.netPnl())));
        }
        b.get("Mistake").stream().filter(g -> !g.key().equals("No mistakes logged") && g.netPnl() < 0).findFirst().ifPresent(g ->
                out.add(String.format(Locale.ROOT, "Your most expensive mistake: \"%s\" (%d trade(s), %s net).", g.key(), g.trades(), rs(g.netPnl()))));
        List<Group> rules = b.get("Rule").stream().filter(g -> g.trades() >= 3).toList();
        if (rules.size() >= 2) {
            Group best = rules.stream().max(Comparator.comparingDouble(Group::netPnl)).get();
            Group worst = rules.stream().min(Comparator.comparingDouble(Group::netPnl)).get();
            out.add(String.format(Locale.ROOT, "Best rule for you: %s (%s over %d trades). Weakest: %s (%s over %d trades).",
                    best.key(), rs(best.netPnl()), best.trades(), worst.key(), rs(worst.netPnl()), worst.trades()));
        }
        b.get("Market regime at entry").stream().filter(g -> g.trades() >= 2).min(Comparator.comparingDouble(Group::avgNetPct))
                .filter(g -> g.avgNetPct() < 0).ifPresent(g -> out.add(String.format(Locale.ROOT,
                        "Trades bought during \"%s\" averaged %+.2f%% each: consider not buying in that market.", g.key(), g.avgNetPct())));
        b.get("Mood when buying").stream().filter(g -> g.trades() >= 2 && !g.key().equals("Not recorded"))
                .min(Comparator.comparingDouble(Group::avgNetPct)).filter(g -> g.avgNetPct() < 0)
                .ifPresent(g -> out.add(String.format(Locale.ROOT, "Trades bought while feeling \"%s\" averaged %+.2f%%: a signal to pause before buying.",
                        g.key(), g.avgNetPct())));
        if (daysWin != null && daysLoss != null && daysLoss > daysWin * 1.3) {
            out.add(String.format(Locale.ROOT, "You hold losers longer (%.0f days) than winners (%.0f days): cut losses at the stop and let winners run.",
                    daysLoss, daysWin));
        }
        double grossProfit = closed.stream().mapToDouble(v -> v.b().grossPnl()).filter(x -> x > 0).sum();
        if (grossProfit > 0) {
            out.add(String.format(Locale.ROOT, "Charges (%s) and tax (%s) took %.0f%% of your winning trades' profit: fewer, larger trades keep more.",
                    rs(charges), rs(tax), (charges + tax) / grossProfit * 100));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Group> by(List<View> list, Function<View, String> key) {
        Map<String, List<View>> m = new LinkedHashMap<>();
        for (View v : list) m.computeIfAbsent(key.apply(v), k -> new ArrayList<>()).add(v);
        List<Group> out = new ArrayList<>();
        m.forEach((k, l) -> out.add(group(k, l)));
        out.sort(Comparator.comparingDouble(Group::netPnl).reversed());
        return out;
    }

    private static Group group(String key, List<View> l) {
        int n = l.size();
        int wins = (int) l.stream().filter(v -> v.b().netPnl() > 0).count();
        double net = l.stream().mapToDouble(v -> v.b().netPnl()).sum();
        double gain = l.stream().mapToDouble(v -> v.b().netPnl()).filter(x -> x > 0).sum();
        double loss = -l.stream().mapToDouble(v -> v.b().netPnl()).filter(x -> x <= 0).sum();
        double[] rs = l.stream().filter(v -> v.b().rMultiple() != null).mapToDouble(v -> v.b().rMultiple()).toArray();
        return new Group(key, n, wins, n == 0 ? 0 : 100.0 * wins / n, net,
                n == 0 ? 0 : l.stream().mapToDouble(v -> v.b().netPct()).average().orElse(0), avg(rs),
                loss > 0 ? gain / loss : (gain > 0 ? Double.POSITIVE_INFINITY : 0));
    }

    private static Group find(List<Group> l, String key) {
        return l.stream().filter(g -> g.key().equals(key)).findFirst().orElse(null);
    }

    private static Double avg(double[] a) {
        return a.length == 0 ? null : java.util.Arrays.stream(a).average().orElse(0);
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String rs(double v) {
        return String.format(Locale.ROOT, "Rs %,.0f", v);
    }
}
