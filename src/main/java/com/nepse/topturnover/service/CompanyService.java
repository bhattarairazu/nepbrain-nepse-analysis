package com.nepse.topturnover.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.Sectors;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.crawler.CrawlException;
import com.nepse.topturnover.crawler.HttpFetcher;
import com.nepse.topturnover.repository.CompanyRepository;
import com.nepse.topturnover.repository.MarketDataRepository;
import com.nepse.topturnover.service.HistoryService.Status;

/**
 * Reads each company's listed shares from its public ShareSansar company page (nepse.company.url + symbol), one page every
 * nepse.history.delay-seconds, so market cap can be worked out from the latest close. Runs as one background job; stops at
 * the first refusal (403/429).
 */
@Service
public class CompanyService {
    private static final Logger LOG = LoggerFactory.getLogger(CompanyService.class);
    public static final String SOURCE = "sharesansar-company";
    /** Shares are refreshed when older than this; bonus and right issues change them a few times a year. */
    public static final int REFRESH_DAYS = 30;

    private final NepseProperties props;
    private final CompanyRepository repo;
    private final MarketDataRepository market;
    private final String baseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nepse-company");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = new Status(null, false, null, null, 0, 0, 0, 0, null, List.of());
    private volatile boolean stopRequested;

    public CompanyService(NepseProperties props, CompanyRepository repo, MarketDataRepository market,
                          @Value("${nepse.company.url:https://www.sharesansar.com/company/}") String baseUrl) {
        this.props = props;
        this.repo = repo;
        this.market = market;
        this.baseUrl = baseUrl;
    }

    public Status status() {
        return status;
    }

    /** Stocks still to fetch: traded in the last 60 days, equity, and without shares younger than REFRESH_DAYS (all of them when everything). */
    public List<String> toFetch(boolean everything) {
        LocalDate latest = market.latestDay().orElse(LocalDate.now());
        Map<String, CompanyRepository.Shares> have = repo.all();
        LocalDateTime fresh = LocalDateTime.now().minusDays(REFRESH_DAYS);
        List<String> out = new ArrayList<>();
        repo.activeStocks(latest.minusDays(60)).forEach((symbol, sector) -> {
            if (!Sectors.isEquity(sector)) return;
            CompanyRepository.Shares s = have.get(symbol);
            if (everything || s == null || s.fetchedAt().isBefore(fresh)) out.add(symbol);
        });
        return out;
    }

    public synchronized boolean start(boolean everything) {
        if (status.running()) return false;
        List<String> symbols = toFetch(everything);
        stopRequested = false;
        status = new Status("Fetch listed shares", true, LocalDateTime.now(), null, symbols.size(), 0, 0, 0, "Starting…", List.of());
        executor.submit(() -> {
            try {
                run(symbols);
            } catch (RuntimeException e) {
                LOG.error("Listed shares job failed", e);
                finish("Failed: " + e.getMessage(), status.log());
            }
        });
        return true;
    }

    public void stop() {
        stopRequested = true;
    }

    private void run(List<String> symbols) {
        HttpFetcher http = new HttpFetcher(props.crawl().userAgent(), props.crawl().timeoutSeconds(), 1, props.crawl().retryDelaySeconds());
        List<String> log = new ArrayList<>();
        int ok = 0, missing = 0, done = 0;
        for (String symbol : symbols) {
            if (stopRequested) { log.add("Stopped by you."); break; }
            if (done > 0) pause();
            update(symbols.size(), ok, missing, "Reading " + symbol + " (" + (done + 1) + " of " + symbols.size() + ") …", log);
            try {
                Document doc = http.get(baseUrl + symbol.toLowerCase());
                Long shares = number(doc, "Listed Shares");
                Double paidUp = shares == null ? null : decimal(doc, "Paid Up");
                repo.save(symbol, shares, paidUp, shares == null ? "NO_DATA" : "OK", LocalDateTime.now().withNano(0));
                if (shares == null) { missing++; log.add(symbol + ": page has no listed shares"); } else ok++;
            } catch (HttpFetcher.NotFoundException e) {
                repo.save(symbol, null, null, "NOT_FOUND", LocalDateTime.now().withNano(0));
                missing++;
                log.add(symbol + ": no company page");
            } catch (CrawlException e) {
                log.add(symbol + ": " + e.getMessage());
                market.logCrawl(LocalDateTime.now().withNano(0), SOURCE, "FAILED", null, e.getMessage());
                finish("Stopped: " + e.getMessage(), log);
                return;
            }
            done++;
        }
        String msg = String.format("Done: listed shares stored for %d companies, %d without a page or figure.", ok, missing);
        market.logCrawl(LocalDateTime.now().withNano(0), SOURCE, "STORED", null, msg);
        LOG.info(msg);
        status = new Status(status.job(), false, status.started(), LocalDateTime.now(), symbols.size(), ok, 0, missing, msg, List.copyOf(log));
    }

    /** The number in the cell next to the one labelled `label` (e.g. "284,098,483.00 units"). */
    static Long number(Document doc, String label) {
        Double d = decimal(doc, label);
        return d == null || d <= 0 ? null : Math.round(d);
    }

    static Double decimal(Document doc, String label) {
        for (Element td : doc.select("td")) {
            if (!td.text().trim().equalsIgnoreCase(label)) continue;
            Element value = td.nextElementSibling();
            if (value == null) return null;
            String digits = value.text().replaceAll("[^0-9.]", "");
            if (digits.isEmpty()) return null;
            try {
                return Double.parseDouble(digits.startsWith(".") ? digits.substring(1) : digits);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private void pause() {
        try {
            Thread.sleep(props.history().delaySeconds() * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void update(int target, int ok, int missing, String message, List<String> log) {
        status = new Status(status.job(), true, status.started(), null, target, ok, 0, missing, message, List.copyOf(log));
    }

    private void finish(String message, List<String> log) {
        status = new Status(status.job(), false, status.started(), LocalDateTime.now(), status.target(), status.stored(),
                status.alreadyStored(), status.skipped(), message, List.copyOf(log));
    }
}
