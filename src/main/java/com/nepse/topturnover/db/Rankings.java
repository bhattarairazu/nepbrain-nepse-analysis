package com.nepse.topturnover.db;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.nepse.topturnover.model.StockQuote;

/** Picks the top-N stocks of a snapshot by turnover or volume. */
public final class Rankings {
    private Rankings() {}

    public static List<StockQuote> topBy(List<StockQuote> quotes, Function<StockQuote, Double> metric, int n) {
        return quotes.stream()
                .filter(q -> metric.apply(q) != null && metric.apply(q) > 0)
                .sorted(Comparator.comparing(metric, Comparator.reverseOrder()).thenComparing(StockQuote::symbol))
                .limit(n)
                .toList();
    }
}
