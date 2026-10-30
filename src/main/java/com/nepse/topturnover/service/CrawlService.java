package com.nepse.topturnover.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.crawler.CrawlException;
import com.nepse.topturnover.crawler.SharesansarCrawler;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.repository.MarketDataRepository;
import com.nepse.topturnover.schedule.CrawlWindow;

/**
 * One crawl run: check the calendar and after-close window, fetch ShareSansar, validate the page and
 * store the day. Safe to call many times a day; each trading day is fetched and stored once.
 */
@Service
public class CrawlService {
    private static final Logger LOG = LoggerFactory.getLogger(CrawlService.class);

    public enum Status { STORED, SKIPPED, FAILED }

    public record Result(Status status, String message) {}

    private final NepseProperties props;
    private final SharesansarCrawler crawler;
    private final MarketDataRepository repo;
    private final CrawlWindow window;
    private final ReentrantLock lock = new ReentrantLock();

    public CrawlService(NepseProperties props, SharesansarCrawler crawler, MarketDataRepository repo) throws IOException {
        this.props = props;
        this.crawler = crawler;
        this.repo = repo;
        this.window = new CrawlWindow(props.market().days(), CrawlWindow.loadHolidays(props.market().holidays()),
                props.crawl().start(), props.crawl().end());
    }

    /**
     * @param replaceToday re-fetch and overwrite today's stored data (still only inside the after-close window)
     * @param jitter       wait a random few seconds first (scheduled runs); manual runs skip it
     */
    public Result run(boolean replaceToday, boolean jitter) {
        if (!lock.tryLock()) return new Result(Status.SKIPPED, "A crawl is already running");
        try {
            return doRun(replaceToday, jitter);
        } finally {
            lock.unlock();
        }
    }

    private Result doRun(boolean replaceToday, boolean jitter) {
        ZonedDateTime now = ZonedDateTime.now(props.market().zone());
        LocalDateTime nowLocal = now.toLocalDateTime().withNano(0);
        LocalDate today = now.toLocalDate();

        CrawlWindow.Decision d = window.check(now);
        if (!d.allowed()) return skipped("Not crawling: " + d.reason(), false, nowLocal, null);
        if (!replaceToday && repo.hasDay(today)) return skipped("Data for " + today + " is already stored", false, nowLocal, today);

        if (jitter && props.crawl().jitterMaxSeconds() > 0) {
            int wait = ThreadLocalRandom.current().nextInt(props.crawl().jitterMaxSeconds() + 1);
            LOG.info("Waiting {}s before fetching", wait);
            try {
                Thread.sleep(wait * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        MarketSnapshot s;
        try {
            LOG.info("Fetching {}", props.crawl().url());
            s = crawler.crawl();
            if (s.quotes().size() < props.crawl().minRows()) {
                throw new CrawlException("Page returned only " + s.quotes().size() + " rows (minimum "
                        + props.crawl().minRows() + "); it looks partial");
            }
            if (s.tradeDate().isAfter(today)) {
                throw new CrawlException("Page shows a future date " + s.tradeDate() + "; check the computer's clock");
            }
        } catch (CrawlException e) {
            LOG.warn("Crawl failed: {}", e.getMessage());
            repo.logCrawl(nowLocal, SharesansarCrawler.NAME, "FAILED", null, e.getMessage());
            return new Result(Status.FAILED, e.getMessage());
        }

        if (!s.tradeDate().equals(today) && repo.hasDay(s.tradeDate())) {
            return skipped("No trading session today: the page still shows " + s.tradeDate(), true, nowLocal, s.tradeDate());
        }
        if (repo.looksStale(s)) {
            return skipped("Data for " + s.tradeDate() + " repeats the previous session; treating today as a non-trading day",
                    true, nowLocal, s.tradeDate());
        }

        repo.save(s, props.analysis().topN(), nowLocal);
        String msg = String.format("Stored %s: %d stocks, top %d by turnover and by volume",
                s.tradeDate(), s.quotes().size(), props.analysis().topN());
        LOG.info(msg);
        repo.logCrawl(nowLocal, SharesansarCrawler.NAME, "STORED", s.tradeDate(), msg);
        return new Result(Status.STORED, msg);
    }

    private Result skipped(String msg, boolean log, LocalDateTime at, LocalDate tradeDate) {
        LOG.info(msg);
        if (log) repo.logCrawl(at, SharesansarCrawler.NAME, "SKIPPED", tradeDate, msg);
        return new Result(Status.SKIPPED, msg);
    }
}
