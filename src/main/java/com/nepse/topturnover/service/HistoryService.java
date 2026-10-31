package com.nepse.topturnover.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService;
import com.nepse.topturnover.analysis.AdvancedStrategyService;
import com.nepse.topturnover.analysis.FeatureService;
import com.nepse.topturnover.analysis.StandardStrategyService;
import com.nepse.topturnover.analysis.StrategyLabService;
import com.nepse.topturnover.analysis.Sectors;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.crawler.CrawlException;
import com.nepse.topturnover.crawler.SharesansarCrawler;
import com.nepse.topturnover.crawler.SharesansarSession;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;
import com.nepse.topturnover.repository.MarketDataRepository;
import com.nepse.topturnover.schedule.CrawlWindow;

/**
 * Two background jobs that use the date/sector filter of ShareSansar's price page:
 * backfilling past trading sessions, and loading every stock's official sector.
 * One job runs at a time; requests are spaced by nepse.history.delay-seconds.
 */
@Service
public class HistoryService {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryService.class);
    public static final String SOURCE_HISTORY = "sharesansar-history";
    public static final String SOURCE_SECTORS = "sharesansar-sectors";

    /** Progress of the current or last job, shown on the Data and Manage sectors pages. */
    public record Status(String job, boolean running, LocalDateTime started, LocalDateTime finished,
                         int target, int stored, int alreadyStored, int skipped, String message, List<String> log) {
        static Status idle() {
            return new Status(null, false, null, null, 0, 0, 0, 0, null, List.of());
        }
    }

    private final NepseProperties props;
    private final MarketDataRepository repo;
    private final FeatureService features;
    private final BacktestService backtest;
    private final StandardStrategyService standard;
    private final AdvancedStrategyService advanced;
    private final StrategyLabService lab;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nepse-history");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = Status.idle();

    public HistoryService(NepseProperties props, MarketDataRepository repo, FeatureService features, BacktestService backtest,
                          StandardStrategyService standard, AdvancedStrategyService advanced,
                          StrategyLabService lab) {
        this.lab = lab;
        this.advanced = advanced;
        this.backtest = backtest;
        this.standard = standard;
        this.props = props;
        this.repo = repo;
        this.features = features;
    }

    public Status status() {
        return status;
    }

    public synchronized boolean startBackfill(int sessions) {
        if (status.running()) return false;
        int n = Math.max(1, Math.min(sessions, props.history().maxSessions()));
        status = new Status("Backfill " + n + " sessions", true, now(), null, n, 0, 0, 0, "Starting…", List.of());
        executor.submit(() -> runSafely(() -> backfill(n)));
        return true;
    }

    public synchronized boolean startSectorLoad() {
        if (status.running()) return false;
        status = new Status("Load sectors", true, now(), null, 0, 0, 0, 0, "Starting…", List.of());
        executor.submit(() -> runSafely(this::loadSectors));
        return true;
    }

    private void runSafely(Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            LOG.error("History job failed", e);
            finish("Failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ backfill

    /**
     * Makes sure the last `target` trading sessions are stored, newest first. Days already stored are kept;
     * weekends and listed holidays are skipped without a request; a date the site has no data for is skipped.
     */
    public void backfill(int target) {
        Set<LocalDate> holidays = holidays();
        ZonedDateTime now = ZonedDateTime.now(props.market().zone());
        LocalDate d = now.toLocalTime().isBefore(props.crawl().start()) ? now.toLocalDate().minusDays(1) : now.toLocalDate();

        SharesansarSession session = null;
        int have = 0, stored = 0, already = 0, skipped = 0, requests = 0;
        int maxRequests = target * 2 + 15;
        List<String> log = new ArrayList<>();
        while (have < target && requests < maxRequests) {
            // NEPSE's calendar for that date (Sunday-Thursday before April 2026), listed holidays,
            // and dates the site already confirmed had no session are skipped without a request.
            if (!props.history().wasTradingWeekday(d) || holidays.contains(d) || repo.isKnownNonTradingDay(d)) {
                d = d.minusDays(1);
                continue;
            }
            if (repo.hasDay(d)) {
                have++;
                already++;
                d = d.minusDays(1);
                continue;
            }
            try {
                if (session == null) {
                    session = open();
                } else {
                    pause();
                }
                requests++;
                update(target, stored, already, skipped, "Fetching " + d + " …", log);
                Document doc;
                try {
                    doc = session.day(d);
                } catch (SharesansarSession.ExpiredException expired) {
                    session = open(); // token expired during a long run: start a fresh session and retry once
                    doc = session.day(d);
                }
                MarketSnapshot s;
                try {
                    s = SharesansarCrawler.parse(doc);
                } catch (CrawlException e) {
                    skipped++;
                    repo.markNonTradingDay(d, "no price table");
                    log.add(d + ": no price table (market closed?)");
                    d = d.minusDays(1);
                    continue;
                }
                if (!s.tradeDate().equals(d) || s.quotes().size() < props.crawl().minRows()) {
                    skipped++;
                    repo.markNonTradingDay(d, "site shows " + s.tradeDate() + ", " + s.quotes().size() + " rows");
                    log.add(d + ": no session (site shows " + s.tradeDate() + ", " + s.quotes().size() + " rows)");
                } else if (repo.looksStale(s)) {
                    skipped++;
                    log.add(d + ": same data as the previous session, skipped");
                } else {
                    repo.save(s, props.analysis().topN(), now().withNano(0));
                    String msg = "Backfilled " + d + ": " + s.quotes().size() + " stocks";
                    repo.logCrawl(now().withNano(0), SOURCE_HISTORY, "STORED", d, msg);
                    log.add(msg);
                    stored++;
                    have++;
                }
            } catch (CrawlException e) {
                repo.logCrawl(now().withNano(0), SOURCE_HISTORY, "FAILED", d, e.getMessage());
                log.add(d + ": " + e.getMessage());
                update(target, stored, already, skipped, "Stopped: " + e.getMessage(), log);
                break;
            }
            d = d.minusDays(1);
        }
        int guessed = repo.autoAssignSectors();
        features.evict();
        backtest.evict();
        standard.evict();
        advanced.evict();
        lab.evict();
        String done = String.format("Done: %d session(s) stored now, %d already stored, %d date(s) without a session%s.",
                stored, already, skipped, guessed > 0 ? ", sectors guessed for " + guessed + " new stock(s)" : "");
        if (have < target) done += " Reached the request limit before " + target + " sessions; run it again to continue.";
        status = new Status(status.job(), false, status.started(), now(), target, stored, already, skipped, done, List.copyOf(log));
        LOG.info(done);
    }

    // ------------------------------------------------------------------ sectors

    /** Reads each sector's stock list through the page's sector filter and applies it (never overriding MANUAL). */
    public void loadSectors() {
        LocalDate date = repo.latestDay().orElse(ZonedDateTime.now(props.market().zone()).toLocalDate());
        List<String> log = new ArrayList<>();
        Map<String, String> bySymbol = new HashMap<>();
        try {
            SharesansarSession session = open();
            Map<String, String> sectorOptions = new java.util.LinkedHashMap<>(session.sectors());
            int done = 0;
            for (var e : sectorOptions.entrySet()) {
                String sector = e.getValue();
                if (!Sectors.ALL.contains(sector)) {
                    log.add("Unknown sector name on the site: " + sector + " (skipped)");
                    continue;
                }
                pause();
                update(sectorOptions.size(), done, 0, 0, "Loading " + sector + " …", log);
                MarketSnapshot s;
                try {
                    Document doc;
                    try {
                        doc = session.sector(e.getKey(), date);
                    } catch (SharesansarSession.ExpiredException expired) {
                        session = open();
                        doc = session.sector(e.getKey(), date);
                    }
                    s = SharesansarCrawler.parse(doc);
                } catch (CrawlException ex) {
                    log.add(sector + ": no table");
                    continue;
                }
                for (StockQuote q : s.quotes()) bySymbol.put(q.symbol(), sector);
                log.add(sector + ": " + s.quotes().size() + " stocks");
                done++;
            }
        } catch (CrawlException e) {
            repo.logCrawl(now().withNano(0), SOURCE_SECTORS, "FAILED", date, e.getMessage());
            finish("Stopped: " + e.getMessage(), log);
            return;
        }
        int[] r = repo.applySourceSectors(bySymbol);
        features.evict();
        backtest.evict();
        standard.evict();
        advanced.evict();
        lab.evict();
        String msg = String.format("Loaded sectors for %d stocks from ShareSansar (%d changed from before); MANUAL choices kept.", r[0], r[1]);
        repo.logCrawl(now().withNano(0), SOURCE_SECTORS, "STORED", date, msg);
        LOG.info(msg);
        finish(msg, log);
    }

    /** Loads sectors if the last successful load is older than nepse.history.sector-refresh-days. */
    public void loadSectorsIfStale() {
        LocalDateTime last = repo.lastSectorLoad().orElse(null);
        if (last == null || last.isBefore(now().minusDays(props.history().sectorRefreshDays()))) startSectorLoad();
    }

    // ------------------------------------------------------------------ helpers

    private SharesansarSession open() throws CrawlException {
        return SharesansarSession.open(props.crawl().url(), props.history().ajaxUrl(), props.crawl().userAgent(),
                props.crawl().timeoutSeconds());
    }

    private void pause() {
        try {
            Thread.sleep(props.history().delaySeconds() * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Set<LocalDate> holidays() {
        try {
            return CrawlWindow.loadHolidays(props.market().holidays());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private void update(int target, int stored, int already, int skipped, String message, List<String> log) {
        status = new Status(status.job(), true, status.started(), null, target, stored, already, skipped, message, List.copyOf(log));
    }

    private void finish(String message) {
        finish(message, status.log());
    }

    private void finish(String message, List<String> log) {
        status = new Status(status.job(), false, status.started(), now(), status.target(), status.stored(),
                status.alreadyStored(), status.skipped(), message, List.copyOf(log));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(props.market().zone());
    }
}
