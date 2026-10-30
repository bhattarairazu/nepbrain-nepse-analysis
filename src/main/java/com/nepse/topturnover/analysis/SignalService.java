package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.AnalysisModels.MarketDay;
import com.nepse.topturnover.analysis.IdeaScorer.Idea;
import com.nepse.topturnover.analysis.SectorAnalysisService.Analysis;
import com.nepse.topturnover.analysis.SectorAnalysisService.Grouping;
import com.nepse.topturnover.analysis.SectorAnalysisService.Period;
import com.nepse.topturnover.analysis.SectorAnalysisService.Quadrant;
import com.nepse.topturnover.analysis.SectorAnalysisService.SectorStat;
import com.nepse.topturnover.analysis.SectorAnalysisService.StockStat;

/**
 * Turns the analysis into four plain answers: can I buy at all, what qualifies now (with entry, stop, targets),
 * what to set alerts for (exact trigger prices), and where sector money is rotating. Rule-based signals for
 * research, not personal advice.
 */
@Service
public class SignalService {

    public record MarketGate(String verdict, String tone, String advice, String detail) {}

    public record TradePlan(StockFeatures f, String setup, String trigger, Double entry, Double entryMax, Double stop,
                            Double target1, Double target2, Double riskPct, Double distancePct, List<String> why, int score) {}

    public record SectorPick(String sector, String status, String tone, String reason, Double return1m, Double return1w,
                             Double shareChangePts, List<StockStat> stocks) {}

    public record Warning(StockFeatures f, String reason) {}

    public record Signals(LocalDate date, int historySessions, MarketGate gate, List<TradePlan> buyNow,
                          List<TradePlan> nextEntries, List<SectorPick> leaders, List<SectorPick> rotation,
                          List<SectorPick> fading, List<Warning> avoid) {}

    private static final int MAX_ROWS = 10;

    private final InsightService insights;
    private final SectorAnalysisService sectors;

    public SignalService(InsightService insights, SectorAnalysisService sectors) {
        this.insights = insights;
        this.sectors = sectors;
    }

    public Optional<Signals> signals() {
        return insights.routine(null).map(r -> {
            FeatureService.Snapshot snap = insights.snapshot(r.date()).orElseThrow();
            MarketGate gate = gate(r.regime(), r.date());

            // ---- buy setups now
            List<TradePlan> buy = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (Idea i : r.ideas()) {
                boolean breakout = i.f().has(Pattern.BASE_BREAKOUT);
                if (i.tier() == IdeaScorer.Tier.STRONG || (breakout && i.score() >= Rules.WATCH_SCORE)) {
                    buy.add(planNow(i, breakout));
                    used.add(i.f().symbol());
                }
            }
            buy.sort(Comparator.comparingInt(TradePlan::score).reversed());

            // ---- next entries: base forming, pullback in an uptrend, strong but unconfirmed
            List<TradePlan> next = new ArrayList<>();
            for (StockFeatures f : r.forming()) {
                if (used.add(f.symbol())) next.add(planBase(f));
            }
            snap.equities().stream().filter(SignalService::isPullback)
                    .sorted(Comparator.comparingInt(StockFeatures::turnoverAppearances10).reversed())
                    .forEach(f -> { if (used.add(f.symbol())) next.add(planPullback(f)); });
            for (Idea i : r.ideas()) {
                if (i.tier() == IdeaScorer.Tier.WATCH && used.add(i.f().symbol())) next.add(planConfirm(i));
            }
            next.sort(Comparator.comparing((TradePlan p) -> p.distancePct() == null ? 99 : Math.abs(p.distancePct())));

            // ---- avoid / take profits
            List<Warning> avoid = new ArrayList<>();
            for (StockFeatures f : snap.equities()) {
                if (f.has(Pattern.CLIMAX_TOP)) avoid.add(new Warning(f, "Climax after a big run-up (pattern 2): take profits, don't chase"));
                else if (f.has(Pattern.CIRCUIT_RUN)) avoid.add(new Warning(f, "Upper-circuit run on fading volume (pattern 5): operator risk"));
                else if (f.turnoverRank() != null && f.changePct() != null && f.changePct() <= -1 && f.rvol() != null && f.rvol() >= Rules.HEAVY_RVOL_FOR_SELLING) {
                    avoid.add(new Warning(f, String.format(Locale.ROOT, "Heavy selling: %+.1f%% on %.1f× volume while in the top-20 turnover", f.changePct(), f.rvol())));
                }
            }
            avoid.sort(Comparator.comparing((Warning w) -> w.f().turnover() == null ? 0 : w.f().turnover()).reversed());

            List<SectorPick> leaders = new ArrayList<>(), rotation = new ArrayList<>(), fading = new ArrayList<>();
            Set<String> avoidSymbols = new HashSet<>();
            avoid.forEach(w -> avoidSymbols.add(w.f().symbol()));
            rotation(leaders, rotation, fading, avoidSymbols);

            return new Signals(r.date(), r.historySessions(), gate, limit(buy), limit(next), leaders, rotation, fading, limit(avoid));
        });
    }

    // ------------------------------------------------------------------ market gate

    private MarketGate gate(InsightService.MarketRegime regime, LocalDate date) {
        switch (regime.key()) {
            case "HEALTHY":
                return new MarketGate("Market supports buying", "good", "Normal position sizes on the setups below.", regime.explanation());
            case "HEAVY_SELLING":
                return new MarketGate("Stay mostly in cash", "bad", "Most breakouts fail during heavy selling. Watch the lists; don't buy yet.", regime.explanation());
            case "NA": {
                List<MarketDay> m = insights.marketDays(10, date);
                if (m.size() < 6) return new MarketGate("Not enough history", "neutral", "Treat every setup as lower confidence.", regime.explanation());
                MarketDay last = m.get(m.size() - 1), back = m.get(m.size() - 6);
                double idx5 = (last.equalWeightIndex() / back.equalWeightIndex() - 1) * 100;
                int adv = 0, dec = 0;
                for (MarketDay d : m.subList(m.size() - 5, m.size())) {
                    adv += d.advancers() == null ? 0 : d.advancers();
                    dec += d.decliners() == null ? 0 : d.decliners();
                }
                String detail = String.format(Locale.ROOT, "Short check (full regime needs 25 sessions): market %+.1f%% over 5 sessions, %d advances vs %d declines.", idx5, adv, dec);
                if (idx5 > 1 && adv > dec) return new MarketGate("Short-term trend up", "good", "Setups below are workable; keep positions modest until more history builds.", detail);
                if (idx5 < -1 && dec > adv) return new MarketGate("Short-term trend down", "bad", "Wait; prefer watching the next-entry list.", detail);
                return new MarketGate("Be selective", "warn", "Only the strongest setups, half-size positions.", detail);
            }
            default:
                return new MarketGate("Be selective", "warn", "Only the strongest setups, half-size positions.", regime.label() + ": " + regime.explanation());
        }
    }

    // ------------------------------------------------------------------ trade plans

    private static TradePlan planNow(Idea i, boolean breakout) {
        StockFeatures f = i.f();
        double entry = f.close(), stop = i.stop();
        double r = entry - stop;
        List<String> why = i.reasons().stream().filter(IdeaScorer.Reason::isPositive).limit(3).map(IdeaScorer.Reason::text).toList();
        return new TradePlan(f, breakout ? "Breakout from a quiet base" : "Strong close on heavy activity",
                "Buy near the last close; skip if it opens more than 3% higher",
                entry, entry * 1.03, stop, entry + 2 * r, entry + 3 * r, r / entry * 100, 0.0, why, i.score());
    }

    private static TradePlan planBase(StockFeatures f) {
        double trigger = f.baseHigh() * 1.005;
        double stop = Math.max(f.baseLow(), trigger * (1 - Rules.DEFAULT_STOP));
        double r = trigger - stop;
        return new TradePlan(f, "Base about to break out",
                String.format(Locale.ROOT, "Buy if it closes above %.2f on 2× normal volume", trigger),
                trigger, trigger * 1.03, stop, trigger + 2 * r, trigger + 3 * r, r / trigger * 100,
                (trigger / f.close() - 1) * 100,
                List.of(String.format(Locale.ROOT, "%d-session base, %.1f%% range", f.baseSessions(), f.baseRangePct() * 100)), 0);
    }

    /** In an uptrend, actively traded, and resting quietly near its 20-day EMA. */
    static boolean isPullback(StockFeatures f) {
        if (f.ema20() == null || f.close() == null || f.high() == null || f.changePct() == null) return false;
        if (f.has(Pattern.CLIMAX_TOP) || f.has(Pattern.CIRCUIT_RUN) || f.has(Pattern.CHURN)) return false;
        boolean uptrend = f.ema50() != null ? f.close() > f.ema50() && Boolean.TRUE.equals(f.ema50Rising()) : f.turnoverAppearances10() >= 5;
        boolean nearEma = f.close() >= f.ema20() * 0.98 && f.close() <= f.ema20() * 1.03;
        boolean quiet = f.changePct() <= 0.5 && (f.rvol() == null || f.rvol() < 1.2);
        return uptrend && nearEma && quiet && f.turnoverAppearances10() >= 3;
    }

    private static TradePlan planPullback(StockFeatures f) {
        double trigger = f.high() * 1.005;
        double stop = Math.max(f.low10() != null ? f.low10() * 0.99 : 0, trigger * (1 - Rules.DEFAULT_STOP));
        double r = trigger - stop;
        return new TradePlan(f, "Pullback to the 20-day EMA",
                String.format(Locale.ROOT, "Buy if the next session closes above today's high (%.2f)", trigger),
                trigger, trigger * 1.03, stop, trigger + 2 * r, trigger + 3 * r, r / trigger * 100,
                (trigger / f.close() - 1) * 100,
                List.of("In the top-20 turnover " + f.turnoverAppearances10() + " of the last 10 sessions",
                        String.format(Locale.ROOT, "Resting %.1f%% from its 20-day EMA on light volume", (f.close() / f.ema20() - 1) * 100)), 0);
    }

    private static TradePlan planConfirm(Idea i) {
        StockFeatures f = i.f();
        double trigger = (f.high() != null ? f.high() : f.close()) * 1.005;
        double stop = Math.max(f.low10() != null ? f.low10() * 0.99 : 0, trigger * (1 - Rules.DEFAULT_STOP));
        double r = trigger - stop;
        List<String> why = i.reasons().stream().filter(IdeaScorer.Reason::isPositive).limit(2).map(IdeaScorer.Reason::text).toList();
        return new TradePlan(f, "Promising, needs confirmation",
                String.format(Locale.ROOT, "Buy on a second strong day: close above %.2f", trigger),
                trigger, trigger * 1.03, stop, trigger + 2 * r, trigger + 3 * r, r / trigger * 100,
                (trigger / f.close() - 1) * 100, why, i.score());
    }

    // ------------------------------------------------------------------ sector rotation

    private void rotation(List<SectorPick> leaders, List<SectorPick> rotation, List<SectorPick> fading, Set<String> avoid) {
        Optional<Period> p1m = sectors.period("1m", null, null), p1w = sectors.period("1w", null, null);
        if (p1m.isEmpty() || p1w.isEmpty()) return;
        Analysis m = sectors.analyse(p1m.get(), Grouping.WEEKLY).orElse(null);
        Analysis w = sectors.analyse(p1w.get(), Grouping.WEEKLY).orElse(null);
        if (m == null || w == null) return;
        Map<String, Integer> rankM = ranks(m), rankW = ranks(w);
        Map<String, SectorStat> statW = new HashMap<>();
        w.sectors().forEach(s -> statW.put(s.sector(), s));

        for (SectorStat s : m.sectors()) {
            SectorStat sw = statW.get(s.sector());
            if (sw == null) continue;
            int rm = rankM.get(s.sector()), rw = rankW.get(s.sector());
            if (s.quadrant() == Quadrant.LEADING && leaders.size() < 3 && sw.quadrant() != Quadrant.LAGGING) {
                String week = switch (sw.quadrant()) {
                    case LEADING -> "still leading this week";
                    case WEAKENING -> String.format(Locale.ROOT, "still ahead this week (%+.1f%%), though its turnover share dipped", sw.returnPct());
                    default -> "money still arriving this week";
                };
                leaders.add(pick(s, sw, "Money is here now", "good",
                        String.format(Locale.ROOT, "%+.1f pts vs market over the month with a growing turnover share; %s",
                                s.relReturnPct(), week), p1m.get(), avoid));
            } else if ((s.quadrant() == Quadrant.LEADING || s.quadrant() == Quadrant.WEAKENING)
                    && (sw.quadrant() == Quadrant.LAGGING || sw.quadrant() == Quadrant.WEAKENING)) {
                fading.add(new SectorPick(s.sector(), "Losing steam", "bad",
                        String.format(Locale.ROOT, "Led over the month (%+.1f%%) but %s this week (%+.1f%%)", s.returnPct(),
                                sw.quadrant().getLabel().toLowerCase(Locale.ROOT), sw.returnPct()),
                        s.returnPct(), sw.returnPct(), sw.shareChangePts(), List.of()));
            }
            if (s.quadrant() == Quadrant.LEADING && sw.quadrant() != Quadrant.LAGGING) continue;
            if (fading.stream().anyMatch(f -> f.sector().equals(s.sector()))) continue;
            String reason = null;
            if (s.quadrant() == Quadrant.IMPROVING) {
                reason = String.format(Locale.ROOT, "Turnover share up %.1f pts over the month while the price lags: money arriving early", s.shareChangePts());
            } else if (rw <= 3 && rm > 3) {
                reason = String.format(Locale.ROOT, "Jumped to #%d this week from #%d over the month", rw, rm);
            } else if (sw.quadrant() == Quadrant.LEADING && (s.quadrant() == Quadrant.LAGGING || s.quadrant() == Quadrant.WEAKENING)) {
                reason = String.format(Locale.ROOT, "Turned up this week (%+.1f%%, turnover share rising) after a weak month", sw.returnPct());
            }
            if (reason != null && rotation.size() < 3) rotation.add(pick(s, sw, "Rotating in", "warn", reason, p1w.get(), avoid));
        }
    }

    private SectorPick pick(SectorStat month, SectorStat week, String status, String tone, String reason, Period stocksPeriod,
                            Set<String> avoid) {
        List<StockStat> top = sectors.detail(month.sector(), stocksPeriod, Grouping.WEEKLY)
                .map(d -> d.stocks().stream()
                        .filter(x -> !x.patterns().contains(Pattern.CLIMAX_TOP) && !x.patterns().contains(Pattern.CIRCUIT_RUN)
                                && !avoid.contains(x.symbol()))
                        .limit(3).toList())
                .orElse(List.of());
        return new SectorPick(month.sector(), status, tone, reason, month.returnPct(), week.returnPct(), month.shareChangePts(), top);
    }

    private static Map<String, Integer> ranks(Analysis a) {
        Map<String, Integer> r = new HashMap<>();
        for (int i = 0; i < a.sectors().size(); i++) r.put(a.sectors().get(i).sector(), i + 1);
        return r;
    }

    private static <T> List<T> limit(List<T> l) {
        return l.size() > MAX_ROWS ? l.subList(0, MAX_ROWS) : l;
    }
}
