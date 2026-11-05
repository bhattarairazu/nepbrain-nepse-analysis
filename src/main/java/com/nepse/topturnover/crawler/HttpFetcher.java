package com.nepse.topturnover.crawler;

import java.io.IOException;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fetches a page with jsoup, retrying network errors but never retrying a refusal (403/429) or a missing page (404). */
public final class HttpFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(HttpFetcher.class);

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
                if (e.getStatusCode() == 404) throw new NotFoundException(url + " not found (HTTP 404)", e);
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < retries) {
                LOG.warn("Fetch failed (" + last.getMessage() + "), retrying in " + retryDelayMs / 1000 + "s");
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

    /** The page does not exist; retrying will not help. */
    public static final class NotFoundException extends CrawlException {
        NotFoundException(String message, Throwable cause) { super(message, cause); }
    }
}
