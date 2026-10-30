package com.nepse.topturnover.model;

import java.time.LocalDate;
import java.util.List;

/** All stocks for one trading day, as read from one source website. */
public record MarketSnapshot(LocalDate tradeDate, String source, List<StockQuote> quotes) {

    public boolean turnoverEstimated() {
        return quotes.stream().anyMatch(StockQuote::turnoverEstimated);
    }
}
