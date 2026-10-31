package com.nepse.topturnover.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.crawler.Numbers;
import com.nepse.topturnover.repository.FloorsheetRepository;
import com.nepse.topturnover.repository.FloorsheetRepository.DayStatus;
import com.nepse.topturnover.repository.FloorsheetRepository.Row;
import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Collects the current session's floorsheet from ShareSansar's floorsheet page, page by page, the same way the page's
 * own table loads it, with a pause between requests. Runs once after the daily price crawl; later scheduled checks
 * retry while the day is missing or incomplete. Requests are made openly and gently; nothing tries to disguise them.
 */
@Service
public class FloorsheetService {
    private static final Logger LOG = LoggerFactory.getLogger(FloorsheetService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Status(boolean running, String message, LocalDateTime at) {}

    private final NepseProperties props;
    private final FloorsheetRepository repo;
    private final MarketDataRepository market;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nepse-floorsheet");
        t.setDaemon(true);
        return t;
    });
    private volatile Status status = new Status(false, "Not run yet", null);
    private volatile LocalDate refusedOn; // after a 403/429, no more attempts that day

    public FloorsheetService(NepseProperties props, FloorsheetRepository repo, MarketDataRepository market) {
        this.props = props;
        this.repo = repo;
        this.market = market;
    }

    public Status status() {
        return status;
    }

    /** Starts a background collection unless one is running or the latest session is already complete. */
    public synchronized boolean startIfNeeded(boolean force) {
        if (props.floorsheet() == null || !props.floorsheet().enabled() || status.running()) return false;
        if (!force && LocalDate.now(props.market().zone()).equals(refusedOn)) return false;
        LocalDate latest = market.latestDay().orElse(null);
        if (!force && latest != null && repo.status(latest).map(DayStatus::complete).orElse(false)) return false;
        status = new Status(true, "Starting…", now());
        executor.submit(() -> {
            try {
                collect();
            } catch (RuntimeException e) {
                LOG.error("Floorsheet collection failed", e);
                status = new Status(false, "Failed: " + e.getMessage(), now());
            }
        });
        return true;
    }

    private void collect() {
        NepseProperties.Floorsheet c = props.floorsheet();
        NepseProperties.Crawl crawl = props.crawl();
        Connection session = Jsoup.newSession().userAgent(crawl.userAgent()).timeout(crawl.timeoutSeconds() * 1000)
                .maxBodySize(0).ignoreContentType(true).header("Accept-Language", "en-US,en;q=0.9");
        try {
            // open the page like a visitor would: it tells us which session the floorsheet is for
            Document page = session.newRequest(c.url()).get();
            Element dateInput = page.selectFirst("#frm_floorsheet input[name=date]");
            LocalDate day = dateInput != null && dateInput.val().matches("\\d{4}-\\d{2}-\\d{2}")
                    ? LocalDate.parse(dateInput.val()) : market.latestDay().orElse(LocalDate.now(props.market().zone()));
            if (repo.status(day).map(DayStatus::complete).orElse(false)) {
                status = new Status(false, "Floorsheet for " + day + " is already complete", now());
                return;
            }

            int start = 0, pages = 0, reported = -1, stored = repo.count(day);
            while (pages < c.maxPages()) {
                pause(c.delaySeconds());
                String url = c.url() + "?draw=" + (pages + 1) + "&start=" + start + "&length=" + c.pageSize() + "&company=&buyer=&seller=";
                JsonNode json = JSON.readTree(session.newRequest(url)
                        .header("X-Requested-With", "XMLHttpRequest").referrer(c.url()).execute().body());
                reported = json.path("recordsTotal").asInt(reported);
                JsonNode data = json.path("data");
                if (!data.isArray() || data.isEmpty()) break;
                List<Row> rows = new ArrayList<>(data.size());
                for (JsonNode r : data) {
                    Row row = row(r, day);
                    if (row != null) rows.add(row);
                }
                repo.insert(rows);
                pages++;
                start += data.size();
                status = new Status(true, String.format(Locale.ROOT, "Collecting %s: %d of %d trades", day, start, reported), now());
                if (reported >= 0 && start >= reported) break;
            }

            stored = repo.count(day);
            double total = repo.totalAmount(day);
            Double turnover = market.dayTurnover(day).orElse(null);
            boolean complete = stored > 0 && turnover != null && turnover > 0
                    && Math.abs(total / turnover - 1) * 100 <= c.completeTolerancePct();
            String note = stored == 0 ? "The site returned no trades (no session yet, or not published yet)"
                    : turnover == null ? "No price data for this session to check completeness against"
                    : String.format(Locale.ROOT, "Floorsheet total is %.1f%% of the day's turnover", total / turnover * 100);
            repo.saveStatus(new DayStatus(day, now(), stored, reported < 0 ? null : reported, total, complete, note));
            if (stored > 0) repo.aggregate(day);
            status = new Status(false, String.format(Locale.ROOT, "%s: %d trades stored (%s). %s", day, stored,
                    complete ? "complete" : "incomplete, will retry at the next scheduled check", note), now());
            LOG.info(status.message());
        } catch (HttpStatusException e) {
            if (e.getStatusCode() == 403 || e.getStatusCode() == 429) refusedOn = LocalDate.now(props.market().zone());
            String msg = e.getStatusCode() == 403 || e.getStatusCode() == 429
                    ? "The site refused the request (HTTP " + e.getStatusCode() + "); stopped and will not retry today"
                    : "HTTP " + e.getStatusCode() + " from the floorsheet page";
            status = new Status(false, msg, now());
            LOG.warn(msg);
        } catch (IOException e) {
            status = new Status(false, "Network error: " + e.getMessage(), now());
            LOG.warn("Floorsheet network error: {}", e.getMessage());
        }
    }

    private static Row row(JsonNode r, LocalDate day) {
        String contract = text(r, "contract_no");
        String symbol = text(r, "symbol");
        Double buyer = Numbers.parse(text(r, "buyer")), seller = Numbers.parse(text(r, "seller"));
        Double qty = Numbers.parse(text(r, "quantity")), rate = Numbers.parse(text(r, "rate")), amount = Numbers.parse(text(r, "amount"));
        if (contract == null || symbol == null || buyer == null || seller == null || qty == null || rate == null) return null;
        String sym = org.jsoup.Jsoup.parse(symbol).text().trim().toUpperCase(Locale.ROOT); // symbol may arrive as a link
        return new Row(contract.trim(), day, sym, buyer.intValue(), seller.intValue(), qty, rate, amount != null ? amount : qty * rate);
    }

    private static String text(JsonNode r, String field) {
        JsonNode n = r.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static void pause(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(props.market().zone()).withNano(0);
    }
}
