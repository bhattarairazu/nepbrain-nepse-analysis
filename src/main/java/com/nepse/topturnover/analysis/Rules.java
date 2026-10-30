package com.nepse.topturnover.analysis;

/**
 * Every threshold used by the pattern, trade-size and idea rules, in one place so they are easy to tune
 * and can be shown on the "Rules" section of each page.
 */
public final class Rules {
    private Rules() {}

    // ---- relative volume
    public static final int AVG_SESSIONS = 20;          // "normal" = average of the previous 20 sessions
    public static final int MIN_HISTORY = 5;            // fewer earlier sessions -> no RVOL
    public static final double RVOL_ACTIVE = 2.0;       // "unusual activity"

    // ---- pattern 1: volume spike after a quiet base
    public static final int BASE_MAX_SESSIONS = 40;
    public static final int BASE_MIN_SESSIONS = 15;
    public static final double BASE_MAX_RANGE = 0.15;   // base high / base low - 1
    public static final double BREAKOUT_RVOL = 2.0;
    public static final double BASE_FORMING_GAP = 0.03; // close within 3% of the base high = breakout may be near

    // ---- pattern 2: climax after a run-up
    public static final int TREND_LOOKBACK = 60;
    public static final int TREND_MIN_SESSIONS = 40;
    public static final double RUN_UP = 0.50;           // close at least 50% above the 60-session low
    public static final double CLIMAX_RVOL = 2.0;
    public static final double LONG_WICK = 0.40;        // wick = 40%+ of the day's range
    public static final double CLOSE_NEAR_LOW = 0.30;

    // ---- pattern 3: capitulation after a long fall
    public static final double LONG_FALL = -0.30;       // close at least 30% below the 60-session high
    public static final double CAPITULATION_RVOL = 2.5;
    public static final double CLOSE_NEAR_HIGH = 0.70;

    // ---- pattern 4: high volume, price barely moves
    public static final double CHURN_RVOL = 2.0;
    public static final double CHURN_MAX_MOVE = 1.0;    // |change %| below this

    // ---- pattern 5: upper-circuit run on falling volume
    public static final double UPPER_CIRCUIT_PCT = 9.5; // NEPSE daily limit is +/-10%
    public static final int CIRCUIT_WINDOW = 5;
    public static final int CIRCUIT_MIN_DAYS = 3;
    public static final double CIRCUIT_VOLUME_FADE = 0.8; // last circuit day volume <= 80% of the first

    // ---- average trade size
    public static final double ATS_RISING = 1.3;        // 5-session avg trade size vs previous 20
    public static final double BLOCK_DEAL_ATS = 4.0;    // today's avg trade size vs normal
    public static final double FRENZY_TRADES = 3.0;     // number of trades vs normal
    public static final double FRENZY_MAX_ATS = 0.7;

    // ---- daily routine / ideas
    public static final double STRONG_CLOSE = 0.70;     // close in the top 30% of the day's range
    public static final double EXTENDED_ABOVE_EMA50 = 0.25;
    public static final double DEFAULT_STOP = 0.08;     // suggested stop: 8% below the close at most
    public static final int STRONG_SCORE = 60;
    public static final int WATCH_SCORE = 40;
    public static final double HEAVY_RVOL_FOR_SELLING = 1.5; // "avoid": down 1%+ on this much volume
}
