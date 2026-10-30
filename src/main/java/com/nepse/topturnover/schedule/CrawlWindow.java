package com.nepse.topturnover.schedule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a crawl may run now: only on trading days, never on listed holidays,
 * and only inside the after-close window (never during trading hours).
 */
public final class CrawlWindow {

    public record Decision(boolean allowed, String reason) {
        static Decision allow() { return new Decision(true, "inside crawl window"); }
        static Decision deny(String reason) { return new Decision(false, reason); }
    }

    private final Set<DayOfWeek> tradingDays;
    private final Set<LocalDate> holidays;
    private final LocalTime start;
    private final LocalTime end;

    public CrawlWindow(Set<DayOfWeek> tradingDays, Set<LocalDate> holidays, LocalTime start, LocalTime end) {
        this.tradingDays = tradingDays;
        this.holidays = holidays;
        this.start = start;
        this.end = end;
    }

    /** `now` must already be in market time (Asia/Kathmandu). */
    public Decision check(ZonedDateTime now) {
        LocalDate day = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (!tradingDays.contains(day.getDayOfWeek())) {
            return Decision.deny(day.getDayOfWeek() + " is not a trading day");
        }
        if (holidays.contains(day)) {
            return Decision.deny(day + " is listed as a market holiday");
        }
        if (time.isBefore(start)) {
            return Decision.deny("too early: data is final only after " + start + " (market time now " + time.withNano(0) + ")");
        }
        if (time.isAfter(end)) {
            return Decision.deny("after the crawl window end " + end);
        }
        return Decision.allow();
    }

    /** Reads holidays.txt: one YYYY-MM-DD or YYYY-MM-DD..YYYY-MM-DD per line, '#' starts a comment. */
    public static Set<LocalDate> loadHolidays(Path file) throws IOException {
        Set<LocalDate> out = new HashSet<>();
        if (!Files.exists(file)) return out;
        int lineNo = 0;
        for (String raw : Files.readAllLines(file)) {
            lineNo++;
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                if (line.contains("..")) {
                    String[] parts = line.split("\\.\\.");
                    LocalDate from = LocalDate.parse(parts[0].trim());
                    LocalDate to = LocalDate.parse(parts[1].trim());
                    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) out.add(d);
                } else {
                    out.add(LocalDate.parse(line));
                }
            } catch (RuntimeException e) {
                throw new IOException(file + " line " + lineNo + ": cannot parse '" + raw.trim() + "'", e);
            }
        }
        return out;
    }
}
