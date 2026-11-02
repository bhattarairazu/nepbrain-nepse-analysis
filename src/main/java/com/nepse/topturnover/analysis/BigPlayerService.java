package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Exit;
import com.nepse.topturnover.analysis.BreakoutPullbackService.Outcome;
import com.nepse.topturnover.analysis.BrokerAnalysisService.BrokerLine;
import com.nepse.topturnover.analysis.BrokerAnalysisService.Signal;
import com.nepse.topturnover.analysis.BrokerAnalysisService.StockFlow;
import com.nepse.topturnover.repository.BacktestRepository;

/**
 * Big-player footprints. Price and volume show large orders (average trade size well above the stock's normal),
 * turnover surges, circuit runs and how far the price has already gone; each stock is put in a stage: quiet build-up
 * (heavy, large-ticket trading while the price is flat), markup (being pushed up on volume), or pumped (a big run with
 * signs of selling). The floorsheet adds who traded: a few brokers buying steadily while the price is flat, a top buyer
 * that has turned seller, or one broker on both sides. The stages' rules are fixed and run over the stored history.
 */
@Service
public class BigPlayerService {
    static final int SHORT = 5, BASELINE = 60, MIN_GAP = 10;
    static final double MIN_AVG_TURNOVER = 5_000_000, CIRCUIT = 9;
    /** Floorsheet windows (sessions): the build-up, and the recent check on the top buyer. */
    public static final int BROKER_WINDOW = 20, BROKER_RECENT = 3;

    public enum Stage {
        QUIET("Quiet build-up", "good", "average trade size ≥ 1.5× its 60-day median and turnover ≥ 1.5× its 60-day average over the last 5 sessions, "
                + "while the price moved less than 4% in 10 sessions: large orders are being absorbed without moving the price"),
        MARKUP("Being marked up", "warn", "two or more +9% (near upper circuit) days in the last 10 sessions, or +15% in 10 sessions, "
                + "on turnover ≥ 1.5× normal: the push is under way, entries now are late"),
        PUMPED("Pumped: distribution risk", "bad", "up 30%+ in 20 sessions, and today closed in the lower half of its range, fell 9%+, or traded 3× normal "
                + "turnover while closing below its open: the big players may be selling into the rally");

        private final String title, tone, rule;

        Stage(String title, String tone, String rule) {
            this.title = title;
            this.tone = tone;
            this.rule = rule;
        }

        public String getTitle() { return title; }
        public String getTone() { return tone; }
        public String getRule() { return rule; }
    }

    /** One stock's footprint at the close of a day. */
    public record Footprint(String symbol, String sector, LocalDate date, Stage stage, int score, double close, double bigTicket,
                            double turnoverSurge, double ret10Pct, double ret20Pct, int circuitUps, int circuitDowns, double avgTradeSize,
                            double avgTurnover, double turnoverToday, List<String> reasons) {}

    /** Floorsheet view of one stock over the broker window, with what the price did. */
    public record BrokerFootprint(String symbol, String sector, String kind, String tone, double top3NetBuyShare, double topBuyerShare,
                                  double selfShare, Integer topBuyer, Double topBuyerNetQty, Double topBuyersAvgPrice, Double lastClose,
                                  Double priceChangePct, Double recentTopBuyerNetQty, String note) {}

    public record Stat(int n, int days, double hitPct, double avgPct, double medianPct) {}

    public record Row(Stage stage, Exit exit, Stat design, Stat oos) {}

    public record StageEvent(String symbol, LocalDate date, Stage stage, int score, Double netPct, String exit) {}

    public record Result(LocalDate from, LocalDate split, LocalDate to, int sessions, LocalDate lastDay, List<Footprint> quiet,
                         List<Footprint> markup, List<Footprint> pumped, List<Row> rows, int floorsheetDays, LocalDate floorsheetFrom,
                         LocalDate floorsheetTo, List<BrokerFootprint> brokerQuiet, List<BrokerFootprint> brokerMoving,
                         List<BrokerFootprint> brokerTurning, List<BrokerFootprint> operator, List<WatchSignal> signals) {}

    private final BacktestService backtest;
    private final BrokerAnalysisService brokers;
    private final BacktestRepository store;
    private Result cached;
    private Map<String, Footprint> todayAll = Map.of();
    private List<StageEvent> stageEvents = List.of();
    private LocalDate cachedFor;
    private Object cachedData; // the price data the result was built from; new history reloads it
    private int cachedFloorsheetDays = -1;

    public BigPlayerService(BacktestService backtest, BrokerAnalysisService brokers, BacktestRepository store) {
        this.backtest = backtest;
        this.brokers = brokers;
        this.store = store;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < BASELINE + 30) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        List<LocalDate> fsDays = brokers.days(BROKER_WINDOW);
        if (cached != null && last.equals(cachedFor) && m == cachedData && fsDays.size() == cachedFloorsheetDays) return Optional.of(cached);

        int T = m.dates.length, split = (int) Math.round(T * AdvancedStrategyService.DESIGN_SHARE);
        List<Footprint> today = new ArrayList<>();
        List<WatchSignal> signals = new ArrayList<>();
        List<StageEvent> events = new ArrayList<>();
        int stages = Stage.values().length, exits = Exit.values().length;
        List<List<double[]>> rets = new ArrayList<>();
        for (int k = 0; k < stages * exits; k++) rets.add(new ArrayList<>());

        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector) || s.size() < BASELINE + 20) continue;
            int[] lastSeen = new int[stages];
            Arrays.fill(lastSeen, -MIN_GAP);
            for (int i = BASELINE + SHORT; i < s.size(); i++) {
                Footprint f = footprint(s, i);
                if (f == null) continue;
                int g = s.globalIndex[i];
                if (g == T - 1) today.add(f);
                if (f.stage() == null || i - lastSeen[f.stage().ordinal()] < MIN_GAP) continue;
                lastSeen[f.stage().ordinal()] = i;
                if (f.stage() == Stage.QUIET && g >= T - WatchSignal.RECENT_SESSIONS) {
                    signals.add(new WatchSignal("PLAYER_QUIET", s.symbol, s.sector, s.dates[i], s.close[i], f.score(),
                            "big-player footprint " + f.score() + ": " + String.join(", ", f.reasons())));
                }
                boolean design = g < split;
                Outcome first = BreakoutPullbackService.outcome(s, i, Exit.SHORT);
                events.add(new StageEvent(s.symbol, s.dates[i], f.stage(), f.score(), first == null ? null : first.ret() * 100, first == null ? null : first.reason()));
                for (Exit x : Exit.values()) {
                    Outcome o = BreakoutPullbackService.outcome(s, i, x);
                    if (o != null) rets.get(f.stage().ordinal() * exits + x.ordinal()).add(new double[] {design ? 1 : 0, o.ret() * 100, o.hit() ? 1 : 0, g});
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Stage st : Stage.values()) {
            for (Exit x : Exit.values()) {
                List<double[]> r = rets.get(st.ordinal() * exits + x.ordinal());
                rows.add(new Row(st, x, stat(r, true), stat(r, false)));
            }
        }
        Comparator<Footprint> byScore = Comparator.comparingInt(Footprint::score).reversed();

        // ---- floorsheet
        List<BrokerFootprint> bQuiet = new ArrayList<>(), bMoving = new ArrayList<>(), bTurning = new ArrayList<>(), bOperator = new ArrayList<>();
        if (!fsDays.isEmpty()) {
            BrokerAnalysisService.MarketView mv = brokers.market(BROKER_WINDOW);
            for (StockFlow f : mv.accumulation()) {
                Double recent = recentNet(f, fsDays.size());
                double ch = f.priceChangePct() == null ? 0 : f.priceChangePct();
                if (recent != null && recent < 0) {
                    bTurning.add(broker(f, "Top buyer now selling", "bad", recent,
                            "the broker that bought most over the window was a net seller in the last " + BROKER_RECENT + " sessions"));
                } else if (Math.abs(ch) <= 5) {
                    BrokerFootprint b = broker(f, "Quiet broker accumulation", "good", recent,
                            "a few brokers kept buying while the price stayed within ±5%");
                    bQuiet.add(b);
                    if (fsDays.size() >= 5) signals.add(new WatchSignal("BROKER_ACCUM", f.symbol(), f.sector(), fsDays.get(0),
                            f.lastClose() == null ? 0 : f.lastClose(), f.top3NetBuyShare() * 100,
                            String.format(Locale.ROOT, "top 3 brokers net bought %.0f%% of the volume over %d sessions, price %+.1f%%",
                                    f.top3NetBuyShare() * 100, fsDays.size(), ch)));
                } else {
                    bMoving.add(broker(f, ch > 0 ? "Accumulated and rising" : "Accumulated while falling", ch > 0 ? "warn" : "", recent,
                            ch > 0 ? "the buying is already showing in the price" : "buyers are absorbing a falling price"));
                }
            }
            for (StockFlow f : mv.operator()) {
                bOperator.add(broker(f, "Operator warning", "bad", null, f.selfShare() >= 0.10
                        ? String.format(Locale.ROOT, "%.0f%% of the volume was the same broker on both sides", f.selfShare() * 100)
                        : "one broker dominates both buying and selling"));
            }
        }
        signals.removeIf(sig -> sig.source().equals("BROKER_ACCUM") && sig.close() <= 0);

        cached = new Result(m.dates[0], m.dates[split], last, T, last,
                today.stream().filter(f -> f.stage() == Stage.QUIET).sorted(byScore).toList(),
                today.stream().filter(f -> f.stage() == Stage.MARKUP).sorted(byScore).toList(),
                today.stream().filter(f -> f.stage() == Stage.PUMPED).sorted(byScore).toList(),
                rows, fsDays.size(), fsDays.isEmpty() ? null : fsDays.get(fsDays.size() - 1), fsDays.isEmpty() ? null : fsDays.get(0),
                bQuiet, bMoving, bTurning, bOperator, signals);
        todayAll = today.stream().collect(java.util.stream.Collectors.toMap(Footprint::symbol, f -> f, (a, b) -> a));
        events.sort(Comparator.comparing(StageEvent::date).reversed());
        stageEvents = events;
        cachedFor = last;
        cachedData = m;
        cachedFloorsheetDays = fsDays.size();
        save(cached);
        return Optional.of(cached);
    }

    /** The stock's footprint at the latest close (null stage = no big-player stage). */
    public synchronized Optional<Footprint> today(String symbol) {
        run();
        return Optional.ofNullable(todayAll.get(symbol));
    }

    /** The stock's past stage signals, newest first. */
    public synchronized List<StageEvent> history(String symbol) {
        run();
        return stageEvents.stream().filter(e -> e.symbol().equals(symbol)).toList();
    }

    // ------------------------------------------------------------------ price and volume footprint

    private static Footprint footprint(Series s, int i) {
        double[] c = s.close, o = s.open, h = s.high, l = s.low, to = s.turnover, tr = s.trades, ch = s.change;
        double avgTo60 = mean(to, i - BASELINE, i);
        if (!(avgTo60 >= MIN_AVG_TURNOVER) || !(c[i] > 0) || !(c[i - 20] > 0)) return null;
        // average trade size: last 5 sessions vs the median of the previous 60
        double[] ats = new double[BASELINE];
        int n = 0;
        for (int k = i - SHORT - BASELINE + 1; k <= i - SHORT; k++) if (k >= 0 && tr[k] > 0 && to[k] > 0) ats[n++] = to[k] / tr[k];
        if (n < 30) return null;
        Arrays.sort(ats, 0, n);
        double median = ats[n / 2], tr5 = 0, to5 = 0;
        for (int k = i - SHORT + 1; k <= i; k++) { tr5 += tr[k]; to5 += to[k]; }
        double ats5 = tr5 > 0 ? to5 / tr5 : Double.NaN, bigTicket = ats5 / median;
        double surge = (to5 / SHORT) / avgTo60;
        double ret10 = c[i] / c[i - 10] - 1, ret20 = c[i] / c[i - 20] - 1;
        int ups = 0, downs = 0;
        for (int k = i - 9; k <= i; k++) {
            if (ch[k] >= CIRCUIT) ups++;
            if (ch[k] <= -CIRCUIT) downs++;
        }
        double rng = h[i] - l[i], cir = rng > 0 ? (c[i] - l[i]) / rng : 0.5;

        Stage stage = null;
        if (ret20 >= 0.30 && (cir < 0.5 || ch[i] <= -CIRCUIT || (to[i] >= 3 * avgTo60 && c[i] < o[i]))) stage = Stage.PUMPED;
        else if ((ups >= 2 || ret10 >= 0.15) && surge >= 1.5) stage = Stage.MARKUP;
        else if (bigTicket >= 1.5 && surge >= 1.5 && Math.abs(ret10) <= 0.04) stage = Stage.QUIET;

        List<String> why = new ArrayList<>();
        int score = 0;
        if (bigTicket >= 2) { score += 30; why.add(String.format(Locale.ROOT, "trade size %.1f× normal", bigTicket)); }
        else if (bigTicket >= 1.5) { score += 20; why.add(String.format(Locale.ROOT, "trade size %.1f× normal", bigTicket)); }
        if (surge >= 3) { score += 30; why.add(String.format(Locale.ROOT, "turnover %.1f× normal", surge)); }
        else if (surge >= 1.5) { score += 20; why.add(String.format(Locale.ROOT, "turnover %.1f× normal", surge)); }
        if (Math.abs(ret10) <= 0.04) { score += 20; why.add(String.format(Locale.ROOT, "price flat (%+.1f%% in 10 sessions)", ret10 * 100)); }
        if (ups >= 2) { score += 10; why.add(ups + " near-circuit up days"); }
        if (downs >= 1) why.add(downs + " near-circuit down day" + (downs > 1 ? "s" : ""));
        if (ret20 >= 0.30) why.add(String.format(Locale.ROOT, "already %+.0f%% in 20 sessions", ret20 * 100));
        if (cir >= 0.7) { score += 10; why.add("closed near the day's high"); }
        else if (cir < 0.3) why.add("closed near the day's low");
        return new Footprint(s.symbol, s.sector, s.dates[i], stage, Math.min(100, score), c[i], bigTicket, surge, ret10 * 100, ret20 * 100, ups,
                downs, ats5, avgTo60, to[i], why);
    }

    // ------------------------------------------------------------------ floorsheet helpers

    /** Net quantity of the window's top buyer over the last few sessions (null if it did not trade the stock then). */
    private Double recentNet(StockFlow f, int windowDays) {
        if (f.topBuyer() == null || windowDays <= BROKER_RECENT) return null;
        int b = f.topBuyer().broker();
        return brokers.stock(f.symbol(), BROKER_RECENT).brokers().stream().filter(x -> x.broker() == b).findFirst().map(BrokerLine::netQty).orElse(null);
    }

    private static BrokerFootprint broker(StockFlow f, String kind, String tone, Double recent, String note) {
        BrokerLine top = f.topBuyer();
        return new BrokerFootprint(f.symbol(), f.sector(), kind, tone, f.top3NetBuyShare() * 100, f.topBuyerShare() * 100, f.selfShare() * 100,
                top == null ? null : top.broker(), top == null ? null : top.netQty(), f.topBuyersAvgPrice(), f.lastClose(), f.priceChangePct(),
                recent, note + (f.signals().contains(Signal.OPERATOR) && !kind.equals("Operator warning") ? "; also an operator warning" : ""));
    }

    // ------------------------------------------------------------------ helpers

    private static Stat stat(List<double[]> rows, boolean design) {
        double[] r = rows.stream().filter(x -> (x[0] == 1) == design).mapToDouble(x -> x[1]).sorted().toArray();
        if (r.length == 0) return new Stat(0, 0, 0, 0, 0);
        long hits = rows.stream().filter(x -> (x[0] == 1) == design && x[2] == 1).count();
        int days = (int) rows.stream().filter(x -> (x[0] == 1) == design).mapToDouble(x -> x[3]).distinct().count();
        return new Stat(r.length, days, 100.0 * hits / r.length, Arrays.stream(r).average().orElse(0), r[r.length / 2]);
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        int n = 0;
        for (int k = Math.max(0, from); k < to; k++) if (!Double.isNaN(a[k])) { s += a[k]; n++; }
        return n > 0 ? s / n : Double.NaN;
    }

    private void save(Result r) {
        List<BacktestRepository.SummaryRow> sums = new ArrayList<>();
        for (Row x : r.rows()) {
            sums.add(new BacktestRepository.SummaryRow("PLAYER_" + x.stage().name() + "_" + x.exit().name(), x.stage().getTitle() + " · " + x.exit().getTitle(),
                    x.design().n() + x.oos().n(), null, null, null, null, x.design().avgPct(), x.oos().avgPct(), null,
                    String.format(Locale.ROOT, "hit %.1f%% design / %.1f%% out-of-sample", x.design().hitPct(), x.oos().hitPct())));
        }
        store.saveIfNew(new BacktestRepository.RunKey("PLAYERS", r.from(), r.to(), r.sessions(), Exit.SHORT.window, false),
                BacktestService.COST_PCT, null, sums, List.of());
    }
}
