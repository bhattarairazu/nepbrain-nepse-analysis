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
    private volatile boolean stopRequested;

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

        Conn conn = new Conn();
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
                requests++;
                update(target, stored, already, skipped, "Fetching " + d + " …", log);
                switch (fetchDay(conn, d, log)) {
                    case STORED -> { stored++; have++; }
                    case NO_SESSION, STALE -> skipped++;
                }
            } catch (CrawlException e) {
                repo.logCrawl(now().withNano(0), SOURCE_HISTORY, "FAILED", d, e.getMessage());
                log.add(d + ": " + e.getMessage());
                update(target, stored, already, skipped, "Stopped: " + e.getMessage(), log);
                break;
            }
            d = d.minusDays(1);
        }
        int guessed = afterNewDays();
        String done = String.format("Done: %d session(s) stored now, %d already stored, %d date(s) without a session%s.",
                stored, already, skipped, guessed > 0 ? ", sectors guessed for " + guessed + " new stock(s)" : "");
        if (have < target) done += " Reached the request limit before " + target + " sessions; run it again to continue.";
        status = new Status(status.job(), false, status.started(), now(), target, stored, already, skipped, done, List.copyOf(log));
        LOG.info(done);
    }

    // ------------------------------------------------------------------ date range

    /** What a range fetch would do: calendar days, sessions already stored, dates to request, dates known to have no session. */
    public record Plan(LocalDate from, LocalDate to, int stored, int toFetch, int knownNoSession, long minutes) {}

    public Plan plan(LocalDate from, LocalDate to, boolean recheck) {
        Set<LocalDate> holidays = holidays(), have = repo.storedDays(from, to), none = repo.nonTradingDays(from, to);
        int stored = 0, fetch = 0, known = 0;
        for (LocalDate d = to; !d.isBefore(from); d = d.minusDays(1)) {
            if (have.contains(d)) stored++;
            else if (!props.history().wasTradingWeekday(d) || holidays.contains(d)) continue;
            else if (none.contains(d) && !recheck) known++;
            else fetch++;
        }
        return new Plan(from, to, stored, fetch, known, Math.round(fetch * (props.history().delaySeconds() + 1.5) / 60.0));
    }

    /** Starts fetching every missing session from `to` back to `from`; returns null when started, otherwise why not. */
    public synchronized String startRange(LocalDate from, LocalDate to, boolean recheck) {
        if (status.running()) return "Another history job is already running.";
        LocalDate today = ZonedDateTime.now(props.market().zone()).toLocalDate();
        if (from == null || to == null) return "Choose both dates.";
        if (to.isAfter(today)) to = today;
        if (from.isAfter(to)) return "The from date must be on or before the to date.";
        Plan p = plan(from, to, recheck);
        stopRequested = false;
        status = new Status("Fetch " + from + " → " + to, true, now(), null, p.toFetch(), 0, p.stored(), 0, "Starting…", List.of());
        LocalDate f = from, t = to;
        executor.submit(() -> runSafely(() -> fetchRange(f, t, recheck, p)));
        return null;
    }

    /** Asks the running job to stop after the current request. */
    public void stop() {
        if (status.running()) stopRequested = true;
    }

    /**
     * Requests each missing trading weekday from `to` back to `from`, newest first, one every delay-seconds.
     * Stored sessions, weekends, listed holidays and (unless recheck) dates already found to have no session are skipped
     * without a request. Stops on request, on a site error, or after {@link #EMPTY_STREAK} dates in a row without a
     * session (the start of the site's history). Running it again continues where it stopped.
     */
    void fetchRange(LocalDate from, LocalDate to, boolean recheck, Plan plan) {
        Set<LocalDate> holidays = holidays(), have = repo.storedDays(from, to), none = recheck ? Set.of() : repo.nonTradingDays(from, to);
        Conn conn = new Conn();
        List<String> log = new ArrayList<>();
        int stored = 0, skipped = 0, empty = 0, done = 0;
        String end = null;
        for (LocalDate d = to; !d.isBefore(from); d = d.minusDays(1)) {
            if (have.contains(d) || !props.history().wasTradingWeekday(d) || holidays.contains(d) || none.contains(d)) continue;
            if (stopRequested) { end = "Stopped by you at " + d + "; run it again to continue."; break; }
            try {
                update(plan.toFetch(), stored, plan.stored(), skipped, "Fetching " + d + " (" + (done + 1) + " of " + plan.toFetch() + ") …", log);
                done++;
                if (fetchDay(conn, d, log) == DayResult.STORED) { stored++; empty = 0; repo.clearNonTradingDay(d); }
                else { skipped++; empty++; }
            } catch (CrawlException e) {
                repo.logCrawl(now().withNano(0), SOURCE_HISTORY, "FAILED", d, e.getMessage());
                log.add(d + ": " + e.getMessage());
                end = "Stopped on an error at " + d + ": " + e.getMessage() + ". Run it again to continue.";
                break;
            }
            if (log.size() > 300) log.subList(0, log.size() - 300).clear();
            if (empty >= EMPTY_STREAK) {
                end = "No sessions for " + EMPTY_STREAK + " trading days in a row before " + d.plusDays(1)
                        + ": the site's history seems to start around here.";
                break;
            }
        }
        int guessed = afterNewDays();
        String msg = String.format("%s%d session(s) stored, %d already stored, %d date(s) without a session%s.",
                end == null ? "Done: " : end + " ", stored, plan.stored(), skipped,
                guessed > 0 ? ", sectors guessed for " + guessed + " new stock(s)" : "");
        repo.logCrawl(now().withNano(0), SOURCE_HISTORY, stored > 0 ? "STORED" : "SKIPPED", to, "Range " + from + " → " + to + ": " + msg);
        status = new Status(status.job(), false, status.started(), now(), plan.toFetch(), stored, plan.stored(), skipped, msg, List.copyOf(log));
        LOG.info(msg);
    }

    // ------------------------------------------------------------------ one day

    static final int EMPTY_STREAK = 15;

    enum DayResult { STORED, NO_SESSION, STALE }

    /** The site session, opened on first use and reopened when its token expires. */
    private final class Conn {
        SharesansarSession session;

        Document day(LocalDate d) throws CrawlException {
            if (session == null) session = open();
            else pause();
            try {
                return session.day(d);
            } catch (SharesansarSession.ExpiredException expired) {
                session = open(); // token expired during a long run: start a fresh session and retry once
                return session.day(d);
            }
        }
    }

    /** Fetches and stores one date's full price table. */
    private DayResult fetchDay(Conn conn, LocalDate d, List<String> log) throws CrawlException {
        Document doc = conn.day(d);
        MarketSnapshot s;
        try {
            s = SharesansarCrawler.parse(doc);
        } catch (CrawlException e) {
            repo.markNonTradingDay(d, "no price table");
            log.add(d + ": no price table (market closed?)");
            return DayResult.NO_SESSION;
        }
        if (!s.tradeDate().equals(d) || s.quotes().size() < props.crawl().minRows()) {
            repo.markNonTradingDay(d, "site shows " + s.tradeDate() + ", " + s.quotes().size() + " rows");
            log.add(d + ": no session (site shows " + s.tradeDate() + ", " + s.quotes().size() + " rows)");
            return DayResult.NO_SESSION;
        }
        if (repo.looksStale(s)) {
            log.add(d + ": same data as the previous session, skipped");
            return DayResult.STALE;
        }
        repo.save(s, props.analysis().topN(), now().withNano(0));
        String msg = "Stored " + d + ": " + s.quotes().size() + " stocks";
        repo.logCrawl(now().withNano(0), SOURCE_HISTORY, "STORED", d, msg);
        log.add(msg);
        return DayResult.STORED;
    }

    /** After new sessions: guess sectors for new stocks and drop cached analysis. */
    private int afterNewDays() {
        int guessed = repo.autoAssignSectors();
        features.evict();
        backtest.evict();
        standard.evict();
        advanced.evict();
        lab.evict();
        return guessed;
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
