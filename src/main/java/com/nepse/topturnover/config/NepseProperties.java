package com.nepse.topturnover.config;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All nepse.* settings from application.properties. Values are parsed here so the config can stay plain text. */
@ConfigurationProperties(prefix = "nepse")
public record NepseProperties(Market market, Crawl crawl, Analysis analysis, History history) {

    public record Market(String timezone, String tradingDays, String closeTime, String holidaysFile) {
        public ZoneId zone() { return ZoneId.of(timezone); }
        public LocalTime close() { return LocalTime.parse(closeTime); }
        public Path holidays() { return Path.of(holidaysFile); }

        /** "MON,TUE,WED" (or full day names) -> set of days. */
        public Set<DayOfWeek> days() {
            Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
            for (String d : tradingDays.split(",")) {
                String key = d.trim().toUpperCase(Locale.ROOT);
                if (key.isEmpty()) continue;
                out.add(Arrays.stream(DayOfWeek.values()).filter(x -> x.name().startsWith(key)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown day in nepse.market.trading-days: " + d)));
            }
            return out;
        }
    }

    public record Crawl(boolean enabled, String cron, String windowStart, String windowEnd, String url,
                        String userAgent, int timeoutSeconds, int retries, int retryDelaySeconds,
                        int jitterMaxSeconds, int minRows) {
        public LocalTime start() { return LocalTime.parse(windowStart); }
        public LocalTime end() { return LocalTime.parse(windowEnd); }
    }

    public record Analysis(int topN) {}

    /** Backfilling past sessions and loading sectors through the page's own date/sector filter. */
    public record History(String ajaxUrl, int delaySeconds, int maxSessions, int sectorRefreshDays) {}
}
