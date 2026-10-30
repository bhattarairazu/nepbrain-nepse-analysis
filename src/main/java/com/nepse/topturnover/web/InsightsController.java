package com.nepse.topturnover.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.AnalysisService;
import com.nepse.topturnover.analysis.FeatureService;
import com.nepse.topturnover.analysis.IdeaScorer;
import com.nepse.topturnover.analysis.InsightService;
import com.nepse.topturnover.analysis.Rules;
import com.nepse.topturnover.analysis.Sectors;
import com.nepse.topturnover.analysis.StockFeatures;
import com.nepse.topturnover.analysis.TradeSizeClass;
import com.nepse.topturnover.repository.MarketDataRepository;
import com.nepse.topturnover.service.HistoryService;

/** Ideas, Patterns, Sectors and Trade size pages. */
@Controller
public class InsightsController {
    private final InsightService insights;
    private final AnalysisService analysis;
    private final MarketDataRepository repo;
    private final FeatureService features;
    private final HistoryService history;

    public InsightsController(InsightService insights, AnalysisService analysis, MarketDataRepository repo, FeatureService features,
                              HistoryService history) {
        this.history = history;
        this.insights = insights;
        this.analysis = analysis;
        this.repo = repo;
        this.features = features;
    }

    private void common(Model model, String active) {
        model.addAttribute("active", active);
        model.addAttribute("sessions", analysis.recentSessions(120));
    }

    @GetMapping("/ideas")
    public String ideas(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        common(model, "ideas");
        model.addAttribute("tiers", IdeaScorer.Tier.values());
        insights.routine(date).ifPresent(r -> model.addAttribute("r", r));
        return "ideas";
    }

    @GetMapping("/patterns")
    public String patterns(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        common(model, "patterns");
        insights.snapshot(date).ifPresent(s -> {
            model.addAttribute("snap", s);
            model.addAttribute("groups", insights.patterns(s.date()).orElse(List.of()));
        });
        model.addAttribute("rules", rules());
        return "patterns";
    }

    @GetMapping("/trade-size")
    public String tradeSize(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        common(model, "tradesize");
        insights.snapshot(date).ifPresent(s -> {
            model.addAttribute("snap", s);
            Map<TradeSizeClass, List<StockFeatures>> byClass = new HashMap<>();
            for (TradeSizeClass c : List.of(TradeSizeClass.LARGER_TRADES, TradeSizeClass.RETAIL_FRENZY, TradeSizeClass.BLOCK_DEAL)) {
                byClass.put(c, s.equities().stream().filter(f -> f.tradeSizeClass() == c)
                        .sorted(Comparator.comparing((StockFeatures f) -> f.turnover() == null ? 0 : f.turnover()).reversed())
                        .toList());
            }
            model.addAttribute("byClass", byClass);
            model.addAttribute("classes", List.of(TradeSizeClass.LARGER_TRADES, TradeSizeClass.RETAIL_FRENZY, TradeSizeClass.BLOCK_DEAL));
            model.addAttribute("top", s.features().values().stream().filter(f -> f.turnoverRank() != null)
                    .sorted(Comparator.comparing(StockFeatures::turnoverRank)).toList());
        });
        return "trade-size";
    }

    @GetMapping("/sectors/manage")
    public String manageSectors(@RequestParam(defaultValue = "unassigned") String show, Model model) {
        common(model, "sectors");
        model.addAttribute("flow", insights.sectorFlow(null));
        model.addAttribute("show", show);
        model.addAttribute("stocks", repo.stocks(show));
        model.addAttribute("allSectors", Sectors.ALL);
        model.addAttribute("job", history.status());
        model.addAttribute("lastLoad", repo.lastSectorLoad().orElse(null));
        return "sectors-manage";
    }

    @PostMapping("/sectors/assign")
    public String assign(@RequestParam String symbol, @RequestParam(required = false) String sector,
                         @RequestParam(defaultValue = "unassigned") String show, RedirectAttributes flash) {
        if (sector != null && !sector.isBlank() && !Sectors.ALL.contains(sector)) {
            flash.addFlashAttribute("message", "Unknown sector: " + sector);
        } else {
            repo.setSector(symbol, sector);
            features.evict();
            flash.addFlashAttribute("message", symbol + " → " + (sector == null || sector.isBlank() ? "no sector" : sector));
        }
        return "redirect:/sectors/manage?show=" + show + "#stocks";
    }

    @PostMapping("/sectors/load")
    public String loadSectors(RedirectAttributes flash) {
        flash.addFlashAttribute("message", history.startSectorLoad()
                ? "Loading sectors from ShareSansar in the background (about a minute)."
                : "Another history job is already running.");
        return "redirect:/sectors/manage?show=all";
    }

    @PostMapping("/sectors/auto")
    public String auto(RedirectAttributes flash) {
        int n = repo.autoAssignSectors();
        features.evict();
        flash.addFlashAttribute("message", "Guessed sectors for " + n + " stock(s) from their company names. Review the ones marked AUTO.");
        return "redirect:/sectors/manage?show=auto#stocks";
    }

    @PostMapping("/sectors/import")
    public String importCsv(@RequestParam("file") MultipartFile file, RedirectAttributes flash) throws IOException {
        Map<String, String> map = new HashMap<>();
        int skipped = 0;
        for (String line : new String(file.getBytes(), StandardCharsets.UTF_8).split("\\r?\\n")) {
            String[] p = line.split(",", 2);
            if (p.length < 2 || p[0].isBlank() || p[0].trim().equalsIgnoreCase("symbol")) continue;
            String sector = p[1].trim().replace("\"", "");
            if (!Sectors.ALL.contains(sector)) { skipped++; continue; }
            map.put(p[0].trim().toUpperCase(Locale.ROOT), sector);
        }
        int n = repo.importSectors(map);
        features.evict();
        flash.addFlashAttribute("message", "Imported " + n + " sector(s)" + (skipped > 0 ? "; skipped " + skipped + " line(s) with unknown sector names" : ""));
        return "redirect:/sectors/manage?show=all#stocks";
    }

    /** Rule thresholds shown on the Patterns page. */
    private static Map<String, String> rules() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("Normal volume", "average of the previous " + Rules.AVG_SESSIONS + " sessions (needs " + Rules.MIN_HISTORY + "+)");
        m.put("Quiet base", Rules.BASE_MIN_SESSIONS + "–" + Rules.BASE_MAX_SESSIONS + " sessions, high/low range ≤ " + (int) (Rules.BASE_MAX_RANGE * 100) + "%");
        m.put("Breakout", "close above the base high on ≥ " + Rules.BREAKOUT_RVOL + "× volume");
        m.put("Run-up / long fall", "close ≥ " + (int) (Rules.RUN_UP * 100) + "% above / ≥ " + (int) (-Rules.LONG_FALL * 100) + "% below the " + Rules.TREND_LOOKBACK + "-session low / high (needs " + Rules.TREND_MIN_SESSIONS + "+ sessions)");
        m.put("Long wick", "≥ " + (int) (Rules.LONG_WICK * 100) + "% of the day's high–low range");
        m.put("Churn", "≥ " + Rules.CHURN_RVOL + "× volume with a move under " + Rules.CHURN_MAX_MOVE + "%");
        m.put("Upper circuit", "daily gain ≥ " + Rules.UPPER_CIRCUIT_PCT + "%; ≥ " + Rules.CIRCUIT_MIN_DAYS + " in " + Rules.CIRCUIT_WINDOW + " sessions with the last day's volume ≤ " + (int) (Rules.CIRCUIT_VOLUME_FADE * 100) + "% of the first");
        return m;
    }
}
