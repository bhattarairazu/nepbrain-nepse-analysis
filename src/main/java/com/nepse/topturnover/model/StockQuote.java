package com.nepse.topturnover.model;

/** One stock's end-of-day figures for a single trading day. Any number may be null when the page doesn't provide it. */
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
        Double changePct) {
}
