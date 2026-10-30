package com.nepse.topturnover.model;

/**
 * One stock's end-of-day figures for a single trading day.
 * Any number may be null when the source page doesn't provide it.
 *
 * @param turnoverEstimated true when turnover was computed as LTP x volume (Merolagani has no turnover column)
 */
public record StockQuote(
        String symbol,
        String name,
        Double open,
        Double high,
        Double low,
        Double close,
        Double ltp,
        Double prevClose,
        Double vwap,
        Double volume,
        Double turnover,
        Integer trades,
        Double changePct,
        boolean turnoverEstimated) {
}
