package com.nepse.topturnover.model;

import java.time.LocalDate;
import java.util.List;

/** All stocks for one trading day, as read from the source website. */
public record MarketSnapshot(LocalDate tradeDate, String source, List<StockQuote> quotes) {
}
