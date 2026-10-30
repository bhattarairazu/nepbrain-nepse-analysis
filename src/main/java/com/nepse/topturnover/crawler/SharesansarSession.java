package com.nepse.topturnover.crawler;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Uses the date and sector filter on ShareSansar's "Today's Share Price" page, the same way the page's own
 * Search button does: open the page once (cookie + form token), then post the form for a date and sector.
 * Callers are responsible for spacing requests out.
 */
public final class SharesansarSession {
    private final Connection session;
    private final String pageUrl;
    private final String ajaxUrl;
    private final String token;
    private final Map<String, String> sectors = new LinkedHashMap<>();

    private SharesansarSession(Connection session, String pageUrl, String ajaxUrl, String token) {
        this.session = session;
        this.pageUrl = pageUrl;
        this.ajaxUrl = ajaxUrl;
        this.token = token;
    }

    public static SharesansarSession open(String pageUrl, String ajaxUrl, String userAgent, int timeoutSeconds) throws CrawlException {
        Connection s = Jsoup.newSession().userAgent(userAgent).timeout(timeoutSeconds * 1000).maxBodySize(0)
                .header("Accept-Language", "en-US,en;q=0.9");
        Document page = execute(() -> s.newRequest(pageUrl).get(), pageUrl);
        Element tokenInput = page.selectFirst("form#frm_todayshareprice input[name=_token]");
        if (tokenInput == null) throw new CrawlException("ShareSansar: the date/sector form was not found; the page layout may have changed");
        SharesansarSession session = new SharesansarSession(s, pageUrl, ajaxUrl, tokenInput.val());
        for (Element o : page.select("form#frm_todayshareprice select[name=sector] option")) {
            if (!o.val().equals("all_sec")) session.sectors.put(o.val(), o.text().trim());
        }
        return session;
    }

    /** Sector filter options: form value -> sector name (e.g. "7" -> "Hydropower"). */
    public Map<String, String> sectors() {
        return sectors;
    }

    /** The full price table for one date (all sectors). */
    public Document day(LocalDate date) throws CrawlException {
        return post("all_sec", date);
    }

    /** One sector's price table for a date. */
    public Document sector(String sectorValue, LocalDate date) throws CrawlException {
        return post(sectorValue, date);
    }

    private Document post(String sector, LocalDate date) throws CrawlException {
        return execute(() -> session.newRequest(ajaxUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .referrer(pageUrl)
                .data("_token", token)
                .data("sector", sector)
                .data("date", date.toString())
                .post(), ajaxUrl);
    }

    private interface Call {
        Document run() throws IOException;
    }

    /** One retry on network errors; a refusal (403/429) is never retried. */
    private static Document execute(Call call, String url) throws CrawlException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return call.run();
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 403 || e.getStatusCode() == 429) {
                    throw new CrawlException(url + " refused the request (HTTP " + e.getStatusCode() + "); stopping", e);
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new CrawlException("Could not fetch " + url + ": " + (last == null ? "interrupted" : last.getMessage()), last);
    }
}
