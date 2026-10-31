package com.nepse.topturnover.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BaseBreakoutService.Base;
import com.nepse.topturnover.analysis.BigPlayerService.Footprint;
import com.nepse.topturnover.analysis.BigPlayerService.Stage;
import com.nepse.topturnover.analysis.BigPlayerService.StageEvent;
import com.nepse.topturnover.analysis.BrokerAnalysisService.BrokerLine;
import com.nepse.topturnover.analysis.BrokerAnalysisService.StockView;
import com.nepse.topturnover.analysis.BreakoutPullbackService.BreakoutLine;
import com.nepse.topturnover.analysis.MoveStudyService.StockMoves;
import com.nepse.topturnover.analysis.WatchlistService.View;
import com.nepse.topturnover.journal.JournalTrade;
import com.nepse.topturnover.journal.TradeCosts;
import com.nepse.topturnover.repository.JournalRepository;
import com.nepse.topturnover.repository.MyListRepository;

/**
 * Everything the app knows about one stock, from every study: big-player stage, sideways base, breakouts and pullbacks,
 * the move study's lift score and the stock's move record, the floorsheet's brokers, the watchlist and your journal,
 * with a plain-language summary at the top.
 */
@Service
public class StockReportService {
    /** A summary line: tone is good / bad / warn / "" (neutral). */
    public record Point(String tone, String topic, String text) {}

    public record JournalLine(JournalTrade t, Double netPct, Double netPnl) {}

    public record Report(String symbol, List<Point> summary, Footprint footprint, List<StageEvent> stageHistory, Base base,
                         List<BaseBreakoutService.Event> baseHistory, List<BreakoutLine> breakouts, StockMoves moves, StockView brokers,
                         List<BrokerLine> topBuyers, List<BrokerLine> topSellers, List<View> watch, List<JournalLine> journal,
                         boolean onMyList) {}

    private final BigPlayerService players;
    private final BaseBreakoutService bases;
    private final BreakoutPullbackService pullbacks;
    private final MoveStudyService moves;
    private final BrokerAnalysisService brokers;
    private final WatchlistService watchlist;
    private final JournalRepository journal;
    private final TradeCosts costs;
    private final MyListRepository myList;

    public StockReportService(BigPlayerService players, BaseBreakoutService bases, BreakoutPullbackService pullbacks, MoveStudyService moves,
                              BrokerAnalysisService brokers, WatchlistService watchlist, JournalRepository journal, TradeCosts costs,
                              MyListRepository myList) {
        this.players = players;
        this.bases = bases;
        this.pullbacks = pullbacks;
        this.moves = moves;
        this.brokers = brokers;
        this.watchlist = watchlist;
        this.journal = journal;
        this.costs = costs;
        this.myList = myList;
    }

    public Report report(String symbol) {
        String sym = symbol.toUpperCase(Locale.ROOT);
        Footprint fp = players.today(sym).orElse(null);
        List<StageEvent> stages = players.history(sym);
        Base base = bases.current(sym).orElse(null);
        List<BaseBreakoutService.Event> baseHist = bases.history(sym);
        List<BreakoutLine> breakouts = pullbacks.history(sym);
        StockMoves mv = moves.stock(sym).orElse(null);
        StockView bv = brokers.days(BigPlayerService.BROKER_WINDOW).isEmpty() ? null : brokers.stock(sym, BigPlayerService.BROKER_WINDOW);
        List<BrokerLine> buyers = bv == null ? List.of() : bv.brokers().stream().filter(b -> b.netQty() > 0).limit(5).toList();
        List<BrokerLine> sellers = bv == null ? List.of() : bv.brokers().reversed().stream().filter(b -> b.netQty() < 0).limit(5).toList();
        List<View> watch = watchlist.forSymbol(sym);
        List<JournalLine> trades = journal.all().stream().filter(t -> t.symbol().equalsIgnoreCase(sym)).map(this::journalLine).toList();
        return new Report(sym, summary(fp, stages, base, breakouts, mv, bv, watch, trades), fp, stages, base, baseHist, breakouts, mv, bv,
                buyers, sellers, watch, trades, myList.contains(sym));
    }

    /** Just the one-line reads of the studies (no watchlist or journal): cheap enough for a list of stocks. */
    public List<Point> summary(String symbol) {
        String sym = symbol.toUpperCase(Locale.ROOT);
        StockView bv = brokers.days(BigPlayerService.BROKER_WINDOW).isEmpty() ? null : brokers.stock(sym, BigPlayerService.BROKER_WINDOW);
        return summary(players.today(sym).orElse(null), players.history(sym), bases.current(sym).orElse(null), pullbacks.history(sym),
                moves.stock(sym).orElse(null), bv, List.of(), List.of());
    }

    private JournalLine journalLine(JournalTrade t) {
        if (t.isOpen() || t.quantity() <= 0) return new JournalLine(t, null, null);
        TradeCosts.Breakdown b = costs.of(t, t.exitPrice(), t.exitDate(), true);
        return new JournalLine(t, b.netPct(), b.netPnl());
    }

    // ------------------------------------------------------------------ summary

    private static List<Point> summary(Footprint fp, List<StageEvent> stages, Base base, List<BreakoutLine> breakouts, StockMoves mv,
                                       StockView bv, List<View> watch, List<JournalLine> trades) {
        List<Point> out = new ArrayList<>();

        // big players
        if (fp == null) out.add(new Point("", "Big players", "Not enough liquidity or history for a footprint (needs Rs 50 lakh+ average turnover)."));
        else if (fp.stage() == Stage.QUIET) out.add(new Point("good", "Big players",
                "Quiet build-up (footprint " + fp.score() + "): " + String.join(", ", fp.reasons()) + ". Worth a look at the brokers; on its own it did not beat costs in the backtest."));
        else if (fp.stage() == Stage.MARKUP) out.add(new Point("bad", "Big players",
                "Being marked up: " + String.join(", ", fp.reasons()) + ". Buying during markup was the worst entry of every setup tested."));
        else if (fp.stage() == Stage.PUMPED) out.add(new Point("bad", "Big players",
                "Pumped, with signs of selling: " + String.join(", ", fp.reasons()) + ". Avoid new buys."));
        else out.add(new Point("", "Big players", String.format(Locale.ROOT, "No big-player stage. Trade size %.1f× and turnover %.1f× normal, %+.1f%% in 10 sessions.",
                fp.bigTicket(), fp.turnoverSurge(), fp.ret10Pct())));
        long recentStages = stages.stream().filter(e -> e.stage() != Stage.QUIET).limit(3).count();
        if (!stages.isEmpty() && recentStages > 0 && stages.get(0).stage() != Stage.QUIET) {
            StageEvent e = stages.get(0);
            if (fp == null || fp.stage() != e.stage()) out.add(new Point("warn", "Big players", "Last flagged " + e.stage().getTitle().toLowerCase(Locale.ROOT) + " on " + e.date() + "."));
        }

        // sideways base
        if (base == null) out.add(new Point("", "Sideways base", "Not in a sideways base (30 sessions within a 15% range)."));
        else {
            String tone = base.phase() == BaseBreakoutService.Phase.BREAKOUT || base.phase() == BaseBreakoutService.Phase.READY ? "good" : "";
            out.add(new Point(tone, "Sideways base", String.format(Locale.ROOT, "%s: base %.1f → %.1f (%.1f%% range), score %d, %+.1f%% from the top. %s.",
                    base.phase().getTitle(), base.baseLow(), base.baseHigh(), base.depthPct(), base.score(), base.toHighPct(), String.join(", ", base.reasons()))));
        }

        // breakouts
        breakouts.stream().filter(b -> b.state() != null).findFirst().ifPresentOrElse(b -> out.add(new Point(
                b.state().startsWith("failed") ? "bad" : b.state().startsWith("retest + turn") || b.state().startsWith("pulled") ? "good" : "",
                "Breakout", String.format(Locale.ROOT, "Broke out on %s above %.1f on %.1f× volume; now %s.", b.breakout(), b.level(), b.rvol(), b.state()))),
                () -> {
                    if (!breakouts.isEmpty()) {
                        long closed = breakouts.stream().filter(b -> b.pullbackNetPct() != null).count();
                        double avg = breakouts.stream().filter(b -> b.pullbackNetPct() != null).mapToDouble(BreakoutLine::pullbackNetPct).average().orElse(0);
                        out.add(new Point("", "Breakout", closed == 0
                                ? String.format(Locale.ROOT, "No recent breakout. %d in the stored history, none with a closed pullback trade.", breakouts.size())
                                : String.format(Locale.ROOT, "No recent breakout. %d in the stored history; buying their pullbacks averaged %+.1f%% over %d trade(s).",
                                breakouts.size(), avg, closed)));
                    } else out.add(new Point("", "Breakout", "No high-volume 20-day breakout in the stored history."));
                });

        // move study
        if (mv != null) {
            String score = mv.scoreToday() == null ? "not liquid enough today for a lift score"
                    : String.format(Locale.ROOT, "lift score %.2f today, rank %d of %d (threshold %.2f%s)", mv.scoreToday(), mv.rankToday(), mv.stocksToday(),
                    mv.threshold(), mv.qualifies() ? ": qualifies" : "");
            double rel = mv.moveRatePct() - mv.baseRatePct();
            boolean few = mv.liquidDays() < 30;
            out.add(new Point(few ? "" : rel >= 5 ? "good" : rel <= -5 ? "bad" : "", "Move record", few
                    ? String.format(Locale.ROOT, "Only %d liquid day(s) (Rs 1 Cr+ traded) in the stored history: too few to judge its move record; %s.", mv.liquidDays(), score)
                    : String.format(Locale.ROOT, "Gave a tradeable +7%% move after %.0f%% of its %d liquid days (all stocks: %.0f%%); %s.",
                    mv.moveRatePct(), mv.liquidDays(), mv.baseRatePct(), score)));
        }

        // floorsheet
        if (bv == null || bv.flow() == null) out.add(new Point("", "Brokers", bv == null ? "No floorsheet collected yet." : "No floorsheet trades in the collected sessions."));
        else {
            var f = bv.flow();
            String what = f.signals().isEmpty() ? "no accumulation, distribution or operator signal"
                    : String.join(", ", f.signals().stream().map(x -> x.getLabel().toLowerCase(Locale.ROOT)).toList());
            String tone = f.signals().contains(BrokerAnalysisService.Signal.OPERATOR) || f.signals().contains(BrokerAnalysisService.Signal.DISTRIBUTION) ? "bad"
                    : f.signals().contains(BrokerAnalysisService.Signal.ACCUMULATION) ? "good" : "";
            out.add(new Point(tone, "Brokers", String.format(Locale.ROOT, "Over %d collected sessions: top 3 brokers net bought %.0f%% and net sold %.0f%% of the volume%s; %s.",
                    bv.days().size(), f.top3NetBuyShare() * 100, f.top3NetSellShare() * 100,
                    f.topBuyer() == null ? "" : " (top buyer: broker " + f.topBuyer().broker() + ")", what)));
        }

        // watchlist and journal
        watch.stream().filter(v -> v.status().isActive()).findFirst().ifPresent(v -> out.add(new Point(v.status().getTone(), "Watchlist",
                v.source().getTitle() + " signal on " + v.item().signalDate() + ": " + v.verdict())));
        long closedW = watch.stream().filter(v -> !v.status().isActive() && v.netPct() != null).count();
        if (closedW > 0) {
            double avg = watch.stream().filter(v -> !v.status().isActive() && v.netPct() != null).mapToDouble(View::netPct).average().orElse(0);
            out.add(new Point("", "Watchlist", String.format(Locale.ROOT, "%d past watchlist signal(s) closed, average %+.1f%%.", closedW, avg)));
        }
        trades.stream().filter(j -> j.t().isOpen()).findFirst().ifPresent(j -> out.add(new Point("warn", "Your journal",
                "You hold it: bought " + j.t().entryDate() + " at " + String.format(Locale.ROOT, "%.2f", j.t().entryPrice())
                        + (j.t().stopPrice() == null ? "" : String.format(Locale.ROOT, ", stop %.2f", j.t().stopPrice())) + ".")));
        return out;
    }

    /** Symbols starting with or containing the query, exact match first. */
    public static List<String> match(List<String> symbols, String q) {
        String u = q == null ? "" : q.strip().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return List.of();
        return symbols.stream().filter(s -> s.contains(u))
                .sorted(Comparator.comparing((String s) -> !s.equals(u)).thenComparing(s -> !s.startsWith(u)).thenComparing(s -> s)).toList();
    }
}
