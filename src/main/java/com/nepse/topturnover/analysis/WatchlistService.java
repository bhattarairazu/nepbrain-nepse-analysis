package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Entry;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Exit;
import com.nepse.topturnover.repository.WatchlistRepository;
import com.nepse.topturnover.repository.WatchlistRepository.Item;

/**
 * The watchlist: records every buy setup the studies flag (Sideways bases, Breakout pullback, Move study) and stocks
 * added by hand, then follows each one with the same plan the backtests used: buy at the open after the signal,
 * sell at +target% from T+3, sell at the next open after a close stop% below the entry, or at the close of the
 * last session of the window. Each item gets plain-language notes on how it is doing, and each setup's live record
 * is set against what its backtest promised.
 */
@Service
public class WatchlistService {
    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);
    public static final double DEFAULT_TARGET = 7, DEFAULT_STOP = 5;
    public static final int DEFAULT_WINDOW = 10, MIN_TO_JUDGE = 20;

    public enum Source {
        BASE_BREAKOUT("Base breakout", "Sideways bases", "/bases"),
        BASE_COILING("Coiling at the top", "Sideways bases", "/bases"),
        PULLBACK_HOLD("Pullback holds", "Breakout pullback", "/pullback"),
        RETEST_TURN("Retest + turn up", "Breakout pullback", "/pullback"),
        MOVE_SCORE("Top lift score", "Move study", "/moves"),
        WASHOUT("Washout day", "Move study", "/moves"),
        PLAYER_QUIET("Quiet build-up", "Big players", "/players"),
        BROKER_ACCUM("Quiet broker accumulation", "Big players", "/players"),
        MANUAL("Added by hand", "You", "/watchlist");

        private final String title, page, href;

        Source(String title, String page, String href) {
            this.title = title;
            this.page = page;
            this.href = href;
        }

        public String getTitle() { return title; }
        public String getPage() { return page; }
        public String getHref() { return href; }

        static Source of(String key) {
            try { return valueOf(key); } catch (RuntimeException e) { return MANUAL; }
        }
    }

    public enum Status {
        PENDING("Buy at next open", ""), OPEN("Holding", ""), STOP_DUE("Closed below stop: sell", "bad"),
        TARGET("Target reached", "good"), STOPPED("Stopped out", "bad"), EXPIRED("Time exit", "warn"), NO_DATA("No price data", "warn");

        private final String label, tone;

        Status(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }

        public String getLabel() { return label; }
        public String getTone() { return tone; }
        public boolean isActive() { return this == PENDING || this == OPEN || this == STOP_DUE; }
    }

    /** One watchlist item with its live evaluation. */
    public record View(Item item, Source source, Status status, LocalDate entryDate, Double entry, LocalDate exitDate, Double exit,
                       double lastClose, LocalDate lastDate, Double changePct, Double netPct, Double bestPct, Double worstPct,
                       Double marketPct, int held, int left, LocalDate sellableFrom, double targetPrice, double stopPrice,
                       String verdict, List<String> insights) {
        public boolean isTriggeredOn(LocalDate d) { return item.signalDate().equals(d); }
    }

    /** A setup's live record against its backtest. */
    public record Scorecard(Source source, int signals, int active, int closed, int wins, double winRate, double avgNetPct,
                            int open, double openAvgPct, String backtest, String verdict) {}

    public record Board(LocalDate lastDay, List<View> triggeredToday, List<View> active, List<View> closed, List<Scorecard> scorecards,
                        int added, List<String> symbols) {}

    private final BacktestService backtest;
    private final BaseBreakoutService bases;
    private final BreakoutPullbackService pullbacks;
    private final MoveStudyService moves;
    private final BigPlayerService players;
    private final WatchlistRepository repo;

    public WatchlistService(BacktestService backtest, BaseBreakoutService bases, BreakoutPullbackService pullbacks, MoveStudyService moves,
                            BigPlayerService players, WatchlistRepository repo) {
        this.backtest = backtest;
        this.bases = bases;
        this.pullbacks = pullbacks;
        this.moves = moves;
        this.players = players;
        this.repo = repo;
    }

    /** Evening refresh, after the day's prices are in (the crawl window ends at 23:30). */
    @Scheduled(cron = "0 45 23 * * MON-FRI", zone = "${nepse.market.timezone}")
    public void nightly() {
        try {
            log.info("Watchlist: {} new signals recorded", capture());
        } catch (RuntimeException e) {
            log.warn("Watchlist refresh failed: {}", e.toString());
        }
    }

    /** Records the studies' recent signals; returns how many were new. */
    public synchronized int capture() {
        List<WatchSignal> all = new ArrayList<>();
        bases.run().ifPresent(r -> all.addAll(r.signals()));
        pullbacks.run().ifPresent(r -> all.addAll(r.signals()));
        moves.run().ifPresent(r -> all.addAll(r.signals()));
        players.run().ifPresent(r -> all.addAll(r.signals()));
        int added = 0;
        for (WatchSignal s : all) {
            if (repo.addIfNew(s.symbol(), s.sector(), s.source(), s.date(), s.close(), s.score(), s.detail(),
                    DEFAULT_TARGET, DEFAULT_STOP, DEFAULT_WINDOW, null)) added++;
        }
        return added;
    }

    /** Adds a stock by hand, as if it was a signal at the latest close. */
    public Optional<String> addManual(String symbol, String note, Double targetPct, Double stopPct, Integer window) {
        Market m = backtest.data();
        String sym = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
        Series s = m == null ? null : m.series.get(sym);
        if (s == null || s.size() == 0) return Optional.of("Unknown symbol: " + sym);
        int last = s.size() - 1;
        double tgt = targetPct == null || targetPct <= 0 ? DEFAULT_TARGET : targetPct, stp = stopPct == null || stopPct <= 0 ? DEFAULT_STOP : stopPct;
        int win = window == null || window < 3 ? DEFAULT_WINDOW : Math.min(window, 120);
        boolean added = repo.addIfNew(sym, s.sector, Source.MANUAL.name(), s.dates[last], s.close[last], null, "added by hand", tgt, stp, win, note);
        return added ? Optional.empty() : Optional.of(sym + " is already on the watchlist for " + s.dates[last]);
    }

    public void archive(long id, boolean archived) {
        repo.setArchived(id, archived);
    }

    public void note(long id, String note) {
        repo.setNote(id, note);
    }

    // ------------------------------------------------------------------ the board

    public Board board(boolean showArchived) {
        int added = capture();
        Market m = backtest.data();
        LocalDate lastDay = m == null || m.dates.length == 0 ? null : m.dates[m.dates.length - 1];
        List<View> views = new ArrayList<>();
        for (Item it : repo.all()) {
            if (it.archived() && !showArchived) continue;
            View v = evaluate(m, it);
            views.add(v);
            persist(it, v);
        }
        List<View> today = views.stream().filter(v -> lastDay != null && v.isTriggeredOn(lastDay)).toList();
        List<View> active = views.stream().filter(v -> v.status().isActive() || v.status() == Status.NO_DATA)
                .sorted(Comparator.comparing((View v) -> v.status().ordinal()).thenComparing(v -> v.item().signalDate(), Comparator.reverseOrder()))
                .toList();
        List<View> closed = views.stream().filter(v -> !v.status().isActive() && v.status() != Status.NO_DATA)
                .sorted(Comparator.comparing((View v) -> v.exitDate() == null ? v.item().signalDate() : v.exitDate()).reversed()).toList();
        List<String> symbols = m == null ? List.of() : m.series.values().stream().filter(s -> Sectors.isEquity(s.sector)).map(s -> s.symbol).sorted().toList();
        return new Board(lastDay, today, active, closed, scorecards(views), added, symbols);
    }

    /** Follows one item through the stored prices with the backtests' plan. */
    private View evaluate(Market m, Item it) {
        Source src = Source.of(it.source());
        double tgtPrice = it.signalClose() * (1 + it.targetPct() / 100), stpPrice = it.signalClose() * (1 - it.stopPct() / 100);
        Series s = m == null ? null : m.series.get(it.symbol());
        int si = s == null ? -1 : indexOn(s, m, it.signalDate());
        if (si < 0) {
            return new View(it, src, Status.NO_DATA, null, null, null, null, it.signalClose(), it.signalDate(), null, null, null, null, null, 0,
                    it.window(), null, tgtPrice, stpPrice, "No stored prices for this stock on the signal day.", List.of());
        }
        int n = s.size(), last = n - 1, e = si + 1;
        double lastClose = s.close[last];
        if (e > last) {
            List<String> notes = new ArrayList<>();
            notes.add("Signal at the close of " + it.signalDate() + ". Plan: buy at the next open, target +" + fmt(it.targetPct())
                    + "%, stop −" + fmt(it.stopPct()) + "% (closing basis), at most " + it.window() + " sessions.");
            notes.add("Skip it if the stock opens more than 3% above " + price(it.signalClose()) + ": the backtests never chased gaps.");
            return new View(it, src, Status.PENDING, null, null, null, null, lastClose, s.dates[last], null, null, null, null, null, 0,
                    it.window(), null, tgtPrice, stpPrice, "Triggered at the latest close: buy at the next open.", notes);
        }
        double entry = s.open[e] > 0 ? s.open[e] : s.close[si];
        double target = entry * (1 + it.targetPct() / 100), stop = entry * (1 - it.stopPct() / 100);
        int end = Math.min(last, e + it.window() - 1);
        Status status = Status.OPEN;
        int exitI = -1;
        double exit = Double.NaN;
        for (int k = e; k <= end; k++) {
            if (k - e >= BacktestService.EARLIEST_SALE && s.high[k] >= target) { status = Status.TARGET; exitI = k; exit = Math.max(target, s.open[k]); break; }
            if (k + 1 - e >= BacktestService.EARLIEST_SALE && s.close[k] <= stop) {
                if (k + 1 <= last) { status = Status.STOPPED; exitI = k + 1; exit = s.open[k + 1] > 0 ? s.open[k + 1] : s.close[k]; }
                else status = Status.STOP_DUE;
                break;
            }
        }
        if (status == Status.OPEN && e + it.window() - 1 <= last) { status = Status.EXPIRED; exitI = e + it.window() - 1; exit = s.close[exitI]; }

        int upTo = exitI >= 0 ? exitI : last;
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY, vol = 0;
        for (int k = e; k <= upTo; k++) { best = Math.max(best, s.high[k]); worst = Math.min(worst, s.low[k]); vol += s.volume[k]; }
        double valueNow = exitI >= 0 ? exit : lastClose;
        double change = (valueNow / entry - 1) * 100, net = change - BacktestService.COST_PCT;
        double bestPct = (best / entry - 1) * 100, worstPct = (worst / entry - 1) * 100;
        int gE = s.globalIndex[e], gEnd = s.globalIndex[upTo];
        double marketPct = (m.index[gEnd] / (e > 0 ? m.index[s.globalIndex[e - 1]] : m.index[gE]) - 1) * 100;
        int held = upTo - e + 1, left = Math.max(0, it.window() - held);
        LocalDate sellable = e + BacktestService.EARLIEST_SALE <= last ? s.dates[e + BacktestService.EARLIEST_SALE] : null;
        Double avgBefore = Indicators.mean(s.volume, si - 19, si + 1, 10);
        double volRatio = avgBefore == null || avgBefore <= 0 ? Double.NaN : vol / held / avgBefore;

        List<String> notes = new ArrayList<>();
        String verdict = switch (status) {
            case TARGET -> String.format(Locale.ROOT, "Worked: reached +%s%% on session %d (net %+.1f%% after charges).", fmt(it.targetPct()), held, net);
            case STOPPED -> String.format(Locale.ROOT, "Failed: closed %s%% below the entry, sold at the next open (net %+.1f%%).", fmt(it.stopPct()), net);
            case STOP_DUE -> String.format(Locale.ROOT, "Closed %+.1f%% from the entry, below the −%s%% stop: sell at the next open.", change, fmt(it.stopPct()));
            case EXPIRED -> String.format(Locale.ROOT, "Time exit after %d sessions at %+.1f%% (net %+.1f%%): the move never came.", held, change, net);
            default -> {
                double toTarget = (target / lastClose - 1) * 100, aboveStop = (lastClose / stop - 1) * 100;
                if (change >= it.targetPct() * 0.6) yield String.format(Locale.ROOT, "On track: %+.1f%%, %.1f%% to the target, %d sessions left.", change, toTarget, left);
                if (change >= 0) yield String.format(Locale.ROOT, "Slightly up (%+.1f%%), %.1f%% short of the target, %d sessions left.", change, toTarget, left);
                if (aboveStop < 2) yield String.format(Locale.ROOT, "Under pressure: %+.1f%%, only %.1f%% above the stop.", change, aboveStop);
                yield String.format(Locale.ROOT, "Behind: %+.1f%% since the entry, %.1f%% above the stop, %d sessions left.", change, aboveStop, left);
            }
        };
        notes.add(String.format(Locale.ROOT, "Bought %s at %s (open after the %s signal). Best %+.1f%%, worst %+.1f%% so far.",
                s.dates[e], price(entry), it.signalDate(), bestPct, worstPct));
        if (status == Status.OPEN) {
            notes.add(sellable == null || !sellable.isAfter(s.dates[last])
                    ? "Settled (T+2): can be sold now." : "Locked by T+2 settlement: sellable from " + sellable + ".");
            notes.add(String.format(Locale.ROOT, "Plan: sell at %s (+%s%%), or at the next open if a close is below %s (−%s%%).",
                    price(target), fmt(it.targetPct()), price(stop), fmt(it.stopPct())));
        }
        double rel = change - marketPct;
        notes.add(String.format(Locale.ROOT, "Market %+.1f%% over the same sessions: the stock is %s by %.1f points.", marketPct,
                rel >= 0 ? "ahead" : "behind", Math.abs(rel)));
        if (!Double.isNaN(volRatio)) {
            notes.add(String.format(Locale.ROOT, "Volume since the entry %.1f× its usual level: %s", volRatio,
                    volRatio >= 1.3 ? "buyers are still active." : volRatio >= 0.8 ? "normal interest." : "interest is fading."));
        }
        if ((src == Source.BASE_BREAKOUT || src == Source.PULLBACK_HOLD || src == Source.RETEST_TURN) && status.isActive()
                && lastClose < it.signalClose() * 0.97) {
            notes.add("Back more than 3% below the signal close: the breakout is failing.");
        }
        if (status.isActive() && bestPct >= it.targetPct() * 0.7 && change < bestPct / 2) {
            notes.add(String.format(Locale.ROOT, "It got to %+.1f%% and gave half of it back.", bestPct));
        }
        return new View(it, src, status, s.dates[e], entry, exitI >= 0 ? s.dates[exitI] : null, exitI >= 0 ? exit : null, lastClose,
                s.dates[last], change, net, bestPct, worstPct, marketPct, held, left, sellable, target, stop, verdict, notes);
    }

    private void persist(Item it, View v) {
        if (v.status() == Status.NO_DATA) return;
        Double entry = v.entry() == null ? null : round(v.entry()), exit = v.exit() == null ? null : round(v.exit());
        Double net = v.netPct() == null || v.status().isActive() ? null : Math.round(v.netPct() * 1000) / 1000.0;
        boolean same = v.status().name().equals(it.status()) && Objects.equals(v.entryDate(), it.entryDate()) && Objects.equals(entry, it.entryPrice())
                && Objects.equals(v.exitDate(), it.exitDate()) && Objects.equals(exit, it.exitPrice()) && Objects.equals(net, it.netPct());
        if (!same) repo.updateOutcome(it.id(), v.status().name(), v.entryDate(), entry, v.exitDate(), exit, net);
    }

    // ------------------------------------------------------------------ scorecards

    private List<Scorecard> scorecards(List<View> views) {
        Map<Source, String> bt = backtests();
        List<Scorecard> out = new ArrayList<>();
        for (Source src : Source.values()) {
            List<View> mine = views.stream().filter(v -> v.source() == src && !v.item().archived()).toList();
            if (mine.isEmpty() && src == Source.MANUAL) continue;
            List<View> closed = mine.stream().filter(v -> !v.status().isActive() && v.status() != Status.NO_DATA && v.netPct() != null).toList();
            int wins = (int) closed.stream().filter(v -> v.netPct() > 0).count();
            double avg = closed.stream().mapToDouble(View::netPct).average().orElse(0);
            int active = (int) mine.stream().filter(v -> v.status().isActive()).count();
            // winners close fast at the target while losers stay open until the stop or the time limit, so early closed
            // results flatter a setup: count the open positions at their current value too
            List<View> open = mine.stream().filter(v -> (v.status() == Status.OPEN || v.status() == Status.STOP_DUE) && v.netPct() != null).toList();
            double openAvg = open.stream().mapToDouble(View::netPct).average().orElse(0);
            int n = closed.size() + open.size();
            double all = n == 0 ? 0 : (closed.stream().mapToDouble(View::netPct).sum() + open.stream().mapToDouble(View::netPct).sum()) / n;
            String verdict;
            if (n == 0) verdict = mine.isEmpty() ? "No signals yet." : "Nothing bought yet.";
            else if (closed.size() < MIN_TO_JUDGE) verdict = String.format(Locale.ROOT,
                    "Too early to judge (%d closed; needs %d). Closed and open together: avg %+.1f%% over %d trades.", closed.size(), MIN_TO_JUDGE, all, n);
            else verdict = String.format(Locale.ROOT, "%s: closed avg %+.1f%% over %d trades; with the open ones %+.1f%%.",
                    all > 0 ? "Paying so far" : "Not paying", avg, closed.size(), all);
            out.add(new Scorecard(src, mine.size(), active, closed.size(), wins, closed.isEmpty() ? 0 : 100.0 * wins / closed.size(), avg,
                    open.size(), openAvg, bt.getOrDefault(src, "—"), verdict));
        }
        return out;
    }

    /** What each setup's backtest showed out-of-sample with the same +7% / −5% / 10-session plan. */
    private Map<Source, String> backtests() {
        Map<Source, String> bt = new LinkedHashMap<>();
        bases.run().ifPresent(r -> r.rows().stream().filter(x -> x.exit() == Exit.SHORT).forEach(x -> {
            if (x.name().equals("Base breakout")) bt.put(Source.BASE_BREAKOUT, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()));
            if (x.name().equals("Coiling at the top")) bt.put(Source.BASE_COILING, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()));
        }));
        pullbacks.run().ifPresent(r -> r.rows().stream().filter(x -> x.exit() == Exit.SHORT).forEach(x -> {
            if (x.entry() == Entry.PULLBACK) bt.put(Source.PULLBACK_HOLD, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()));
            if (x.entry() == Entry.RETEST_TURN) bt.put(Source.RETEST_TURN, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()));
        }));
        moves.run().ifPresent(r -> r.rules().forEach(x -> {
            if (x.name().equals("Top lift score")) bt.put(Source.MOVE_SCORE, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()) + " on " + days(x.oos().days()));
            if (x.name().equals("Washout day")) bt.put(Source.WASHOUT, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()) + " on " + days(x.oos().days()));
        }));
        players.run().ifPresent(r -> r.rows().stream().filter(x -> x.exit() == Exit.SHORT && x.stage() == BigPlayerService.Stage.QUIET)
                .forEach(x -> bt.put(Source.PLAYER_QUIET, line(x.oos().n(), x.oos().hitPct(), x.oos().avgPct()))));
        bt.put(Source.BROKER_ACCUM, "not backtested yet: needs a few months of floorsheet history");
        return bt;
    }

    private static String days(int d) {
        return d == 1 ? "a single day" : d + " days";
    }

    private static String line(int n, double hit, double avg) {
        return n == 0 ? "no out-of-sample trades" : String.format(Locale.ROOT, "%d trades, %.0f%% hit target, avg %+.1f%%", n, hit, avg);
    }

    // ------------------------------------------------------------------ helpers

    private static int indexOn(Series s, Market m, LocalDate d) {
        for (int g = m.dates.length - 1; g >= 0; g--) if (m.dates[g].equals(d)) return s.indexOf(g);
        return -1;
    }

    private static Double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String price(double v) {
        return String.format(Locale.ROOT, "Rs %,.2f", v);
    }
}
