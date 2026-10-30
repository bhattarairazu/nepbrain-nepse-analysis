package com.nepse.topturnover.crawler;

import java.io.IOException;
import java.util.logging.Logger;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Fetches a page with jsoup, retrying network errors but never retrying a refusal (403/429). */
public final class HttpFetcher {
    private static final Logger LOG = Logger.getLogger(HttpFetcher.class.getName());

    private final String userAgent;
    private final int timeoutMs;
    private final int retries;
    private final long retryDelayMs;

    public HttpFetcher(String userAgent, int timeoutSeconds, int retries, int retryDelaySeconds) {
        this.userAgent = userAgent;
        this.timeoutMs = timeoutSeconds * 1000;
        this.retries = retries;
        this.retryDelayMs = retryDelaySeconds * 1000L;
    }

    public Document get(String url) throws CrawlException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return Jsoup.connect(url)
                        .userAgent(userAgent)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(timeoutMs)
                        .maxBodySize(0)
                        .get();
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 403 || e.getStatusCode() == 429) {
                    throw new CrawlException(url + " refused the request (HTTP " + e.getStatusCode() + "); not retrying", e);
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < retries) {
                LOG.warning("Fetch failed (" + last.getMessage() + "), retrying in " + retryDelayMs / 1000 + "s");
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new CrawlException("Could not fetch " + url + " after " + (retries + 1) + " attempt(s): "
                + (last == null ? "interrupted" : last.getMessage()), last);
    }
}
