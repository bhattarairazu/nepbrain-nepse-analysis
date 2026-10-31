package com.nepse.topturnover.analysis;

import java.time.LocalDate;

/**
 * A buy setup one of the studies flagged at the close of {@code date}; the watchlist records it and follows what the
 * stock did next. {@code source} is the setup's key (see the watchlist's Source enum).
 */
public record WatchSignal(String source, String symbol, String sector, LocalDate date, double close, double score, String detail) {
    /** How far back each study reports its signals, so a fresh watchlist starts with the recent ones. */
    public static final int RECENT_SESSIONS = 20;
}
