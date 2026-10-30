package com.nepse.topturnover.analysis;

import java.time.LocalDate;

/** One stock's stored sessions, oldest first. Missing numbers are NaN. */
public record StockSeries(
        String symbol,
        String name,
        String sector,
        LocalDate[] dates,
        double[] open,
        double[] high,
        double[] low,
        double[] close,
        double[] volume,
        double[] turnover,
        double[] trades,
        double[] changePct) {

    public int size() { return dates.length; }
    public int last() { return dates.length - 1; }
}
