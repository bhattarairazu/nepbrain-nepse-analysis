package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.List;

/** Everything computed for one stock on one session. Null = not enough history (or not on the page). */
public record StockFeatures(
        String symbol,
        String name,
        String sector,
        LocalDate date,
        int sessions,                 // stored sessions for this stock up to the date
        Double close,
        Double changePct,
        Double volume,
        Double turnover,
        Double trades,
        // activity
        Double rvol,
        Double closeInRange,          // 0 = closed at the low, 1 = at the high
        Double upperWick,             // share of the day's range
        Double lowerWick,
        // trade size
        Double avgTradeSize,
        Double avgTradeSizeNormal,    // previous 20 sessions
        Double tradeSizeTrend,        // 5-session avg trade size / normal
        Double tradesRatio,           // trades / normal trades
        TradeSizeClass tradeSizeClass,
        // structure
        Integer baseSessions,
        Double baseHigh,
        Double baseLow,
        Double baseRangePct,          // fraction, 0.12 = 12%
        Double runUp60,               // close / 60-session low - 1
        Double drawdown60,            // close / 60-session high - 1
        int upperCircuitDays5,
        // trend
        Double ema50,
        Boolean ema50Rising,
        Double sma200,
        Double rsi14,
        // lists
        Integer turnoverRank,
        Integer volumeRank,
        int turnoverAppearances10,
        // results
        List<PatternHit> patterns,
        boolean baseForming) {

    public record PatternHit(Pattern pattern, String evidence) {}

    public boolean has(Pattern p) {
        return patterns.stream().anyMatch(h -> h.pattern() == p);
    }

    public boolean closedUpNearHigh() {
        return changePct != null && changePct > 0 && closeInRange != null && closeInRange >= Rules.STRONG_CLOSE;
    }

    public Double aboveEma50() {
        return ema50 == null || close == null ? null : close / ema50 - 1;
    }
}
