package com.nepse.topturnover.crawler;

import com.nepse.topturnover.model.MarketSnapshot;

/** One website that publishes the full end-of-day share price table. */
public interface SourceCrawler {
    String name();

    MarketSnapshot crawl() throws CrawlException;
}
