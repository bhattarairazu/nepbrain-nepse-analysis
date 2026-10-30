package com.nepse.topturnover.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Typed access to config/crawler.properties. Relative paths resolve against the project folder. */
public final class AppConfig {

    public enum SourceMode { FIXED, RANDOM }

    private final Properties props;
    private final Path baseDir;

    private AppConfig(Properties props, Path baseDir) {
        this.props = props;
        this.baseDir = baseDir;
    }

    /** Loads the file; the project folder is taken to be the parent of its "config" directory. */
    public static AppConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Path configDir = file.toAbsolutePath().getParent();
        Path base = configDir.getFileName() != null && configDir.getFileName().toString().equals("config")
                ? configDir.getParent() : configDir;
        return new AppConfig(p, base);
    }

    public static AppConfig of(Properties props, Path baseDir) {
        return new AppConfig(props, baseDir);
    }

    // ---------- market calendar ----------
    public ZoneId zone() { return ZoneId.of(str("market.timezone", "Asia/Kathmandu")); }

    public Set<DayOfWeek> tradingDays() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String d : list("market.trading.days", "MON,TUE,WED,THU,FRI")) {
            days.add(Arrays.stream(DayOfWeek.values())
                    .filter(x -> x.name().startsWith(d.toUpperCase(Locale.ROOT)))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown day in market.trading.days: " + d)));
        }
        return days;
    }

    public LocalTime marketClose() { return LocalTime.parse(str("market.close.time", "15:00")); }
    public Path holidaysFile() { return path("market.holidays.file", "config/holidays.txt"); }

    // ---------- crawl window ----------
    public LocalTime windowStart() { return LocalTime.parse(str("crawl.window.start", "15:15")); }
    public LocalTime windowEnd() { return LocalTime.parse(str("crawl.window.end", "23:30")); }

    // ---------- sources ----------
    public List<String> sources() { return list("crawl.sources", "sharesansar,merolagani"); }
    public SourceMode sourceMode() { return SourceMode.valueOf(str("crawl.source.mode", "RANDOM").toUpperCase(Locale.ROOT)); }
    public boolean fallbackEnabled() { return Boolean.parseBoolean(str("crawl.fallback.enabled", "true")); }

    public String sourceUrl(String source) {
        String url = props.getProperty("source." + source + ".url");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Missing source." + source + ".url in config");
        return url.trim();
    }

    // ---------- http ----------
    public String userAgent() { return str("http.user.agent", "Mozilla/5.0"); }
    public int timeoutSeconds() { return integer("http.timeout.seconds", 30); }
    public int retries() { return integer("http.retries", 2); }
    public int retryDelaySeconds() { return integer("http.retry.delay.seconds", 20); }
    public int jitterMaxSeconds() { return integer("crawl.jitter.max.seconds", 30); }

    // ---------- data ----------
    public int topN() { return integer("crawl.top.n", 20); }
    public int minRows() { return integer("crawl.min.rows", 150); }
    public Path dbFile() { return path("db.file", "data/nepse.db"); }
    public Path logFile() { return path("log.file", "logs/crawler.log"); }
    public int daemonIntervalMinutes() { return integer("daemon.check.interval.minutes", 30); }

    // ---------- helpers ----------
    private String str(String key, String def) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private int integer(String key, int def) {
        String v = str(key, null);
        return v == null ? def : Integer.parseInt(v);
    }

    private List<String> list(String key, String def) {
        return Arrays.stream(str(key, def).split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Path path(String key, String def) {
        Path p = Path.of(str(key, def));
        return p.isAbsolute() ? p : baseDir.resolve(p).normalize();
    }
}
