package com.nepse.topturnover.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Triggers the crawl on the nepse.crawl.cron schedule (Nepal time). Disable with nepse.crawl.enabled=false. */
@Component
@ConditionalOnProperty(prefix = "nepse.crawl", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrawlScheduler {
    private final CrawlService crawlService;

    public CrawlScheduler(CrawlService crawlService) {
        this.crawlService = crawlService;
    }

    @Scheduled(cron = "${nepse.crawl.cron}", zone = "${nepse.market.timezone}")
    public void scheduledCrawl() {
        crawlService.run(false, true);
    }
}
