package com.nepse.topturnover;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import com.nepse.topturnover.config.AppConfig;
import com.nepse.topturnover.crawler.HttpFetcher;
import com.nepse.topturnover.crawler.SourceSelector;
import com.nepse.topturnover.db.Database;
import com.nepse.topturnover.db.SnapshotRepository;
import com.nepse.topturnover.schedule.CrawlWindow;
import com.nepse.topturnover.service.CrawlService;

/**
 * Command line entry point.
 *
 * <pre>
 *   crawl            run once now (respects trading days, holidays and the after-close window)
 *   daemon           keep running and crawl automatically each trading day after the close
 *   report           print the stored top-N lists (latest day, or --date=YYYY-MM-DD)
 *   init-db          create the database file and tables
 *   import-sectors   load a CSV of symbol,sector into the stock table (--file=path)
 *
 *   --config=path        config file (default config/crawler.properties)
 *   --source=name        only use sharesansar or merolagani for this run
 *   --force              skip day/time guards and overwrite an existing day
 * </pre>
 */
public final class App {
    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        Map<String, String> opts = new HashMap<>();
        String command = "crawl";
        for (String a : args) {
            if (a.startsWith("--")) {
                String[] kv = a.substring(2).split("=", 2);
                opts.put(kv[0], kv.length > 1 ? kv[1] : "true");
            } else {
                command = a.toLowerCase(Locale.ROOT);
            }
        }
        if (opts.containsKey("help")) command = "help";

        try {
            System.exit(run(command, opts));
        } catch (Exception e) {
            LOG.severe(e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(3);
        }
    }

    static int run(String command, Map<String, String> opts) throws Exception {
        if (command.equals("help")) {
            printHelp();
            return 0;
        }
        AppConfig cfg = AppConfig.load(Path.of(opts.getOrDefault("config", "config/crawler.properties")));
        setupLogging(cfg.logFile());

        try (Connection conn = Database.open(cfg.dbFile())) {
            SnapshotRepository repo = new SnapshotRepository(conn);
            switch (command) {
                case "init-db" -> {
                    LOG.info("Database ready at " + cfg.dbFile());
                    return 0;
                }
                case "report" -> {
                    return report(repo, opts.get("date"));
                }
                case "import-sectors" -> {
                    return importSectors(repo, cfg, opts.get("file"));
                }
                case "crawl", "daemon" -> {
                    CrawlService service = buildService(cfg, repo);
                    String source = opts.get("source");
                    CrawlService.Options o = new CrawlService.Options(opts.containsKey("force"), source);
                    if (command.equals("crawl")) {
                        return service.run(o) == CrawlService.Outcome.FAILED ? 2 : 0;
                    }
                    daemon(service, new CrawlService.Options(false, source), cfg.daemonIntervalMinutes());
                    return 0;
                }
                default -> {
                    System.err.println("Unknown command: " + command);
                    printHelp();
                    return 1;
                }
            }
        }
    }

    private static CrawlService buildService(AppConfig cfg, SnapshotRepository repo) throws IOException {
        CrawlWindow window = new CrawlWindow(cfg.tradingDays(), CrawlWindow.loadHolidays(cfg.holidaysFile()),
                cfg.windowStart(), cfg.windowEnd());
        HttpFetcher http = new HttpFetcher(cfg.userAgent(), cfg.timeoutSeconds(), cfg.retries(), cfg.retryDelaySeconds());
        Random random = new Random();
        return new CrawlService(cfg, window, new SourceSelector(cfg, http, random), repo, random);
    }

    private static void daemon(CrawlService service, CrawlService.Options o, int intervalMinutes) throws InterruptedException {
        LOG.info("Daemon started; checking every " + intervalMinutes + " minute(s). Press Ctrl+C to stop.");
        while (true) {
            try {
                service.run(o);
            } catch (Exception e) {
                LOG.severe("Crawl run failed: " + e.getMessage());
            }
            Thread.sleep(intervalMinutes * 60_000L);
        }
    }

    private static int report(SnapshotRepository repo, String date) throws Exception {
        LocalDate day = date != null ? LocalDate.parse(date) : repo.latestDay().orElse(null);
        if (day == null) {
            System.out.println("No data stored yet. Run: crawl");
            return 0;
        }
        for (String type : List.of("TURNOVER", "VOLUME")) {
            List<SnapshotRepository.RankRow> rows = repo.topList(day, type);
            System.out.printf("%nTop %d by %s on %s%n", rows.size(), type.toLowerCase(Locale.ROOT), day);
            System.out.printf("%4s  %-10s %18s %10s %8s%n", "#", "Symbol", type.equals("TURNOVER") ? "Turnover (Rs)" : "Volume", "Close", "Chg %");
            for (SnapshotRepository.RankRow r : rows) {
                System.out.printf("%4d  %-10s %,18.0f %10s %8s%n", r.rank(), r.symbol(), r.value(),
                        r.close() == null ? "-" : String.format("%.2f", r.close()),
                        r.changePct() == null ? "-" : String.format("%+.2f", r.changePct()));
            }
        }
        return 0;
    }

    private static int importSectors(SnapshotRepository repo, AppConfig cfg, String file) throws Exception {
        if (file == null) {
            System.err.println("Usage: import-sectors --file=sectors.csv   (lines: SYMBOL,Sector Name)");
            return 1;
        }
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            String[] p = line.split(",", 2);
            if (p.length < 2 || p[0].isBlank() || p[0].trim().equalsIgnoreCase("symbol")) continue;
            map.put(p[0].trim().toUpperCase(Locale.ROOT), p[1].trim().replace("\"", ""));
        }
        int n = repo.importSectors(map, ZonedDateTime.now(cfg.zone()).toLocalDate());
        LOG.info("Sectors set for " + n + " stocks");
        return 0;
    }

    private static void setupLogging(Path logFile) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %5$s%6$s%n");
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        SimpleFormatter fmt = new SimpleFormatter();
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(fmt);
        root.addHandler(console);
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        FileHandler file = new FileHandler(logFile.toString(), true);
        file.setFormatter(fmt);
        root.addHandler(file);
    }

    private static void printHelp() {
        System.out.println("""
                NEPSE top turnover / volume crawler

                Usage: java -jar target/nepse-crawler.jar <command> [options]

                Commands:
                  crawl            Crawl once now (only on trading days, after the close window opens)
                  daemon           Stay running and crawl automatically every trading day
                  report           Print stored top lists (latest day or --date=YYYY-MM-DD)
                  init-db          Create the database and tables
                  import-sectors   Load sectors from a CSV (--file=sectors.csv, lines: SYMBOL,Sector)

                Options:
                  --config=path    Config file (default config/crawler.properties)
                  --source=name    Use only sharesansar or merolagani for this run
                  --force          Ignore day/time/holiday guards and overwrite an existing day
                """);
    }
}
