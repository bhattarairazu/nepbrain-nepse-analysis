package com.nepse.topturnover.analysis;

/**
 * What a stock's price move and its relative volume say together (the classic price + volume reading).
 * Relative volume (RVOL) = today's volume / its average over the previous sessions (up to 20).
 */
public enum Signal {
    ACCUMULATION("Accumulation", "good", "Price up 1%+ on at least 1.5x its normal volume: buyers are in control."),
    DISTRIBUTION("Distribution", "bad", "Price down 1%+ on at least 1.5x its normal volume: heavy selling."),
    CHURN("Churn", "warn", "Price flat (under 1%) on at least 2x volume: shares changing hands without progress, or a block deal."),
    WEAK_RALLY("Weak rally", "warn", "Price up 1%+ on below-normal volume: few buyers behind the move."),
    QUIET_PULLBACK("Quiet pullback", "neutral", "Price down 1%+ on below-normal volume: little selling pressure."),
    UP("Up", "neutral", "Price up 1%+. Not enough history yet to judge volume."),
    DOWN("Down", "neutral", "Price down 1%+. Not enough history yet to judge volume."),
    NEUTRAL("Neutral", "neutral", "No clear message from price and volume.");

    public static final double MOVE_PCT = 1.0;
    public static final double HEAVY_RVOL = 1.5;
    public static final double CHURN_RVOL = 2.0;

    private final String label;
    private final String tone;
    private final String meaning;

    Signal(String label, String tone, String meaning) {
        this.label = label;
        this.tone = tone;
        this.meaning = meaning;
    }

    public String getLabel() { return label; }
    public String getTone() { return tone; }
    public String getMeaning() { return meaning; }

    /** @param rvol null when there isn't enough history to compare against */
    public static Signal classify(Double changePct, Double rvol) {
        if (changePct == null) return NEUTRAL;
        boolean up = changePct >= MOVE_PCT;
        boolean down = changePct <= -MOVE_PCT;
        if (rvol == null) return up ? UP : down ? DOWN : NEUTRAL;
        if (up && rvol >= HEAVY_RVOL) return ACCUMULATION;
        if (down && rvol >= HEAVY_RVOL) return DISTRIBUTION;
        if (!up && !down && rvol >= CHURN_RVOL) return CHURN;
        if (up && rvol < 1.0) return WEAK_RALLY;
        if (down && rvol < 1.0) return QUIET_PULLBACK;
        return NEUTRAL;
    }
}
