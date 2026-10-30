package com.nepse.topturnover.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scores a stock that passed the daily routine filters. The score only ranks candidates for further
 * research (chart, floorsheet, fundamentals); it is not a buy signal. Every point comes with a reason.
 */
public final class IdeaScorer {
    private IdeaScorer() {}

    public enum Tier {
        STRONG("Research now", "good"), WATCH("Watch", "warn"), LOW("Low priority", "neutral");

        private final String label;
        private final String tone;

        Tier(String label, String tone) {
            this.label = label;
            this.tone = tone;
        }

        public String getLabel() { return label; }
        public String getTone() { return tone; }
    }

    public record Reason(int points, String text) {
        public boolean isPositive() { return points > 0; }
    }

    public record Idea(StockFeatures f, int score, Tier tier, List<Reason> reasons, Double stop, Double stopPct, Integer sectorRank) {}

    /** @param sectorRank 1 = sector with most top-20 turnover slots in the last 5 sessions; null if unknown */
    public static Idea score(StockFeatures f, Integer sectorRank) {
        List<Reason> r = new ArrayList<>();
        if (f.has(Pattern.BASE_BREAKOUT)) r.add(new Reason(30, "Breakout from a quiet base (pattern 1)"));
        if (f.turnoverRank() != null) r.add(new Reason(15, "In today's top-20 turnover (#" + f.turnoverRank() + ")"));
        if (f.rvol() != null && f.rvol() >= 3) r.add(new Reason(15, fmt("Volume %.1f× normal", f.rvol())));
        else if (f.rvol() != null && f.rvol() >= Rules.RVOL_ACTIVE) r.add(new Reason(10, fmt("Volume %.1f× normal", f.rvol())));
        if (f.closeInRange() != null && f.closeInRange() >= 0.85) r.add(new Reason(10, "Closed at the top of the day's range"));
        if (sectorRank != null && sectorRank <= 3) r.add(new Reason(10, "Sector is #" + sectorRank + " for money flow this week"));
        if (f.tradeSizeClass() == TradeSizeClass.LARGER_TRADES) r.add(new Reason(10, fmt("Average trade size rising (%.1f× normal)", f.tradeSizeTrend())));
        if (f.turnoverAppearances10() >= 3) r.add(new Reason(5, "In the top-20 turnover " + f.turnoverAppearances10() + " of the last 10 sessions"));
        if (f.ema50() != null && f.close() > f.ema50() && Boolean.TRUE.equals(f.ema50Rising())) {
            r.add(new Reason(10, "Above a rising 50-day EMA"));
        } else if (f.ema50() != null && f.close() < f.ema50()) {
            r.add(new Reason(-10, "Below its 50-day EMA"));
        }
        if (f.sma200() != null) {
            r.add(f.close() > f.sma200() ? new Reason(5, "Above its 200-day average") : new Reason(-10, "Below its 200-day average"));
        }
        if (f.rsi14() != null && f.rsi14() >= 50 && f.rsi14() <= 70) r.add(new Reason(5, fmt("RSI %.0f: healthy momentum", f.rsi14())));
        if (f.rsi14() != null && f.rsi14() > 75) r.add(new Reason(-10, fmt("RSI %.0f: overbought", f.rsi14())));
        if (f.tradeSizeClass() == TradeSizeClass.RETAIL_FRENZY) r.add(new Reason(-10, "Retail frenzy: many small trades"));
        if (f.tradeSizeClass() == TradeSizeClass.BLOCK_DEAL) r.add(new Reason(-10, "Possible block deal: volume may not be a trend"));
        if (f.has(Pattern.CHURN)) r.add(new Reason(-10, "High volume but little price progress (pattern 4)"));
        if (f.has(Pattern.CAPITULATION)) r.add(new Reason(-5, "Capitulation pattern: wait for confirmation (pattern 3)"));

        int score = Math.max(0, Math.min(100, r.stream().mapToInt(Reason::points).sum()));
        Tier tier = score >= Rules.STRONG_SCORE ? Tier.STRONG : score >= Rules.WATCH_SCORE ? Tier.WATCH : Tier.LOW;

        Double stop = null, stopPct = null;
        if (f.close() != null) {
            double floor = f.close() * (1 - Rules.DEFAULT_STOP);
            double s = f.has(Pattern.BASE_BREAKOUT) && f.baseHigh() != null ? Math.max(floor, f.baseHigh() * 0.97) : floor;
            stop = Math.min(s, f.close() * 0.97);
            stopPct = (stop / f.close() - 1) * 100;
        }
        return new Idea(f, score, tier, List.copyOf(r), stop, stopPct, sectorRank);
    }

    /** Why a stock that was active today is removed from the candidate list, or null to keep it. */
    public static String dropReason(StockFeatures f) {
        if (f.has(Pattern.CLIMAX_TOP)) return "Climax after a big run-up (pattern 2)";
        if (f.has(Pattern.CIRCUIT_RUN)) return "Upper-circuit run on falling volume (pattern 5)";
        if (f.has(Pattern.CAPITULATION) && (f.changePct() == null || f.changePct() <= 0)) return "Capitulation: wait for confirmation (pattern 3)";
        if (f.runUp60() != null && f.runUp60() >= Rules.RUN_UP) return fmt("Extended: up %.0f%% from its 60-session low", f.runUp60() * 100);
        Double above = f.aboveEma50();
        if (above != null && above >= Rules.EXTENDED_ABOVE_EMA50) return fmt("Extended: %.0f%% above its 50-day EMA", above * 100);
        return null;
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
