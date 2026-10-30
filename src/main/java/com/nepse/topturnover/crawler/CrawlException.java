package com.nepse.topturnover.crawler;

/** A source could not be fetched or its page could not be understood. */
public class CrawlException extends Exception {
    public CrawlException(String message) { super(message); }
    public CrawlException(String message, Throwable cause) { super(message, cause); }
}
