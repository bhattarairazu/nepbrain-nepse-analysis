package com.nepse.topturnover.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import com.nepse.topturnover.config.AppConfig;
import com.nepse.topturnover.crawler.CrawlException;
import com.nepse.topturnover.crawler.SourceCrawler;
import com.nepse.topturnover.crawler.SourceSelector;
import com.nepse.topturnover.db.SnapshotRepository;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.schedule.CrawlWindow;

/**
 * One crawl run: check the calendar/time guards, try the sources in order, validate the page,
 * and store the day. Safe to run many times a day; it stores each trading day once.
 */
public final class CrawlService {
    private static final Logger LOG = Logger.getLogger(CrawlService.class.getName());

    public enum Outcome { STORED, SKIPPED, FAILED }

    /**
     * @param force  ignore the day/time/holiday guards and overwrite an already stored day
     * @param source use only this source (null = config order)
     */
    public record Options(boolean force, String source) {}

    private final AppConfig cfg;
    private final CrawlWindow window;
    private final SourceSelector selector;
    private final SnapshotRepository repo;
    private final Random random;

    public CrawlService(AppConfig cfg, CrawlWindow window, SourceSelector selector, SnapshotRepository repo, Random random) {
        this.cfg = cfg;
        this.window = window;
        this.selector = selector;
        this.repo = repo;
        this.random = random;
    }

    public Outcome run(Options opt) throws SQLException {
        ZonedDateTime now = ZonedDateTime.now(cfg.zone());
        LocalDate today = now.toLocalDate();

        if (!opt.force()) {
            CrawlWindow.Decision d = window.check(now);
            if (!d.allowed()) {
                LOG.info("Not crawling: " + d.reason());
                return Outcome.SKIPPED;
            }
            if (repo.hasDay(today)) {
                LOG.info("Not crawling: data for " + today + " is already stored");
                return Outcome.SKIPPED;
            }
            jitter();
        }

        List<SourceCrawler> order = selector.order(opt.source());
        boolean fallback = opt.source() == null && cfg.fallbackEnabled();
        LOG.info("Source order: " + order.stream().map(SourceCrawler::name).toList() + (fallback ? " (with fallback)" : ""));

        for (SourceCrawler crawler : order) {
            try {
                LOG.info("Fetching " + crawler.name() + " ...");
                MarketSnapshot s = crawler.crawl();
                Outcome result = store(s, today, now, opt.force());
                if (result != null) return result;
            } catch (CrawlException e) {
                LOG.warning(crawler.name() + " failed: " + e.getMessage());
                repo.logCrawl(now, crawler.name(), "FAILED", null, e.getMessage());
                if (!fallback) break;
            }
        }
        LOG.severe("All sources failed; nothing stored");
        return Outcome.FAILED;
    }

    /** Validates and stores a snapshot. Returns null when the page is unusable (so the next source is tried). */
    private Outcome store(MarketSnapshot s, LocalDate today, ZonedDateTime now, boolean force) throws CrawlException, SQLException {
        if (s.quotes().size() < cfg.minRows()) {
            throw new CrawlException(s.source() + " returned only " + s.quotes().size()
                    + " rows (minimum " + cfg.minRows() + "); page looks partial");
        }
        if (s.tradeDate().isAfter(today)) {
            throw new CrawlException(s.source() + " shows a future date " + s.tradeDate() + "; check the computer clock/timezone");
        }
        if (!s.tradeDate().equals(today)) {
            if (repo.hasDay(s.tradeDate()) && !force) {
                String msg = "No trading session today: " + s.source() + " still shows " + s.tradeDate() + " (already stored)";
                LOG.info(msg);
                repo.logCrawl(now, s.source(), "SKIPPED", s.tradeDate(), msg);
                return Outcome.SKIPPED;
            }
            LOG.warning(s.source() + " shows " + s.tradeDate() + " instead of today; storing it because that day is missing");
        }
        if (!force && repo.looksStale(s)) {
            String msg = s.source() + " data for " + s.tradeDate() + " is identical to the previous stored day; treating it as a non-trading day";
            LOG.info(msg);
            repo.logCrawl(now, s.source(), "SKIPPED", s.tradeDate(), msg);
            return Outcome.SKIPPED;
        }
        if (repo.hasDay(s.tradeDate()) && !force) {
            LOG.info("Data for " + s.tradeDate() + " already stored");
            return Outcome.SKIPPED;
        }

        repo.save(s, cfg.topN(), now);
        String msg = String.format("Stored %s from %s: %d stocks, top %d by turnover and volume%s",
                s.tradeDate(), s.source(), s.quotes().size(), cfg.topN(),
                s.turnoverEstimated() ? " (turnover estimated as LTP x volume)" : "");
        LOG.info(msg);
        repo.logCrawl(now, s.source(), "STORED", s.tradeDate(), msg);
        return Outcome.STORED;
    }

    private void jitter() {
        int max = cfg.jitterMaxSeconds();
        if (max <= 0) return;
        int wait = random.nextInt(max + 1);
        LOG.info("Waiting " + wait + "s before fetching (random jitter)");
        try {
            Thread.sleep(wait * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
