package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.AnalysisModels.MarketDay;
import com.nepse.topturnover.analysis.AnalysisService;
import com.nepse.topturnover.analysis.FeatureService;
import com.nepse.topturnover.analysis.InsightService;
import com.nepse.topturnover.analysis.Signal;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.repository.MarketDataRepository;
import com.nepse.topturnover.service.CrawlService;

@Controller
public class DashboardController {
    private final AnalysisService analysis;
    private final MarketDataRepository repo;
    private final CrawlService crawlService;
    private final NepseProperties props;
    private final InsightService insights;
    private final FeatureService features;

    public DashboardController(AnalysisService analysis, MarketDataRepository repo, CrawlService crawlService, NepseProperties props,
                               InsightService insights, FeatureService features) {
        this.analysis = analysis;
        this.insights = insights;
        this.features = features;
        this.repo = repo;
        this.crawlService = crawlService;
        this.props = props;
    }

    /** Daily top-20 turnover and volume lists. */
    @GetMapping("/")
    public String day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        model.addAttribute("active", "day");
        model.addAttribute("sessions", analysis.recentSessions(120));
        model.addAttribute("signals", Signal.values());
        model.addAttribute("minHistory", AnalysisService.MIN_HISTORY);
        model.addAttribute("topN", props.analysis().topN());
        analysis.day(date).ifPresent(d -> model.addAttribute("a", d));
        return "day";
    }

    /** Which stocks keep appearing in the lists over the last N sessions. */
    @GetMapping("/money-flow")
    public String moneyFlow(@RequestParam(defaultValue = "TURNOVER") String type,
                            @RequestParam(defaultValue = "10") int days,
                            Model model) {
        String listType = type.toUpperCase(Locale.ROOT).equals("VOLUME") ? "VOLUME" : "TURNOVER";
        int n = Math.max(2, Math.min(days, 250));
        model.addAttribute("active", "flow");
        model.addAttribute("type", listType);
        model.addAttribute("days", n);
        model.addAttribute("topN", props.analysis().topN());
        analysis.moneyFlow(listType, n, null).ifPresent(f -> model.addAttribute("flow", f));
        return "money-flow";
    }

    /** Market turnover and how concentrated it is in the top 20. */
    @GetMapping("/market")
    public String market(@RequestParam(defaultValue = "60") int days, Model model) {
        int n = Math.max(5, Math.min(days, 2500));
        List<MarketDay> all = insights.marketDays(n, null);
        List<MarketDay> rows = all.subList(Math.max(0, all.size() - n), all.size());
        model.addAttribute("regime", insights.regime(null));
        model.addAttribute("active", "market");
        model.addAttribute("days", n);
        model.addAttribute("rows", rows);
        model.addAttribute("chartRows", rows.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("date", r.date().toString());
            m.put("turnover", r.turnover());
            m.put("turnoverAvg20", r.turnoverAvg20());
            m.put("topTurnoverShare", r.topTurnoverShare());
            m.put("topVolumeShare", r.topVolumeShare());
            m.put("index", r.equalWeightIndex());
            m.put("advancers", r.advancers());
            m.put("decliners", r.decliners());
            return m;
        }).toList());
        model.addAttribute("topN", props.analysis().topN());
        return "market";
    }

    @GetMapping("/stock/{symbol}")
    public String stock(@PathVariable String symbol, @RequestParam(defaultValue = "120") int days, Model model) {
        int n = Math.max(5, Math.min(days, 2500));
        model.addAttribute("active", "");
        model.addAttribute("days", n);
        model.addAttribute("symbol", symbol.toUpperCase(Locale.ROOT));
        analysis.stock(symbol.toUpperCase(Locale.ROOT), n).ifPresent(s -> {
            model.addAttribute("s", s);
            features.snapshot(null).map(snap -> snap.features().get(s.symbol())).ifPresent(f -> model.addAttribute("sf", f));
            model.addAttribute("chartRows", s.days().stream().map(d -> {
                Map<String, Object> m = new HashMap<>();
                m.put("date", d.date().toString());
                m.put("close", d.close());
                m.put("turnover", d.turnover());
                m.put("inTop", d.turnoverRank() != null);
                return m;
            }).toList());
        });
        return "stock";
    }

    @GetMapping("/crawl")
    public String crawl(Model model) {
        model.addAttribute("active", "crawl");
        model.addAttribute("log", repo.recentCrawls(50));
        model.addAttribute("crawl", props.crawl());
        model.addAttribute("market", props.market());
        model.addAttribute("latest", repo.latestDay().orElse(null));
        return "crawl";
    }

    @PostMapping("/crawl/run")
    public String runCrawl(@RequestParam(defaultValue = "false") boolean replace, RedirectAttributes flash) {
        CrawlService.Result r = crawlService.run(replace, false);
        flash.addFlashAttribute("result", r);
        return "redirect:/crawl";
    }
}
