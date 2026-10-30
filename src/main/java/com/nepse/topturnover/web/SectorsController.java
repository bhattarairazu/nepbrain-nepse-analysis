package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.nepse.topturnover.analysis.SectorAnalysisService;
import com.nepse.topturnover.analysis.SectorAnalysisService.Analysis;
import com.nepse.topturnover.analysis.SectorAnalysisService.Grouping;
import com.nepse.topturnover.analysis.SectorAnalysisService.Quadrant;
import com.nepse.topturnover.repository.MarketDataRepository;

/** Sector analysis over a chosen period, and the top trending stocks inside one sector. */
@Controller
public class SectorsController {
    public static final List<String[]> PRESETS = List.of(
            new String[] {"1w", "1 week"}, new String[] {"1m", "1 month"}, new String[] {"3m", "3 months"},
            new String[] {"6m", "6 months"}, new String[] {"1y", "1 year"}, new String[] {"custom", "Custom dates"});

    private final SectorAnalysisService sectors;
    private final MarketDataRepository repo;

    public SectorsController(SectorAnalysisService sectors, MarketDataRepository repo) {
        this.sectors = sectors;
        this.repo = repo;
    }

    @GetMapping("/sectors")
    public String analysis(@RequestParam(defaultValue = "3m") String preset,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(defaultValue = "WEEKLY") Grouping group,
                           Model model) {
        common(model, preset, group);
        sectors.period(preset, from, to).flatMap(p -> sectors.analyse(p, group)).ifPresent(a -> {
            model.addAttribute("a", a);
            model.addAttribute("chart", chart(a, null));
        });
        model.addAttribute("quadrants", Quadrant.values());
        return "sector-analysis";
    }

    @GetMapping("/sector")
    public String detail(@RequestParam String name,
                         @RequestParam(defaultValue = "3m") String preset,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "WEEKLY") Grouping group,
                         Model model) {
        common(model, preset, group);
        model.addAttribute("name", name);
        sectors.period(preset, from, to).flatMap(p -> sectors.detail(name, p, group)).ifPresent(d -> {
            model.addAttribute("d", d);
            model.addAttribute("chart", chart(d.analysis(), name));
        });
        return "sector-detail";
    }

    private void common(Model model, String preset, Grouping group) {
        model.addAttribute("active", "sectors");
        model.addAttribute("presets", PRESETS);
        model.addAttribute("preset", preset);
        model.addAttribute("group", group);
        model.addAttribute("latest", repo.latestDay().orElse(null));
        model.addAttribute("earliest", repo.earliestDay().orElse(null));
    }

    /** Plain lists for Chart.js: dates, market line, one line per sector (or just the chosen one). */
    private static Map<String, Object> chart(Analysis a, String only) {
        Map<String, Object> c = new HashMap<>();
        c.put("dates", a.dates().stream().map(LocalDate::toString).toList());
        c.put("market", a.market().index());
        List<Map<String, Object>> lines = new ArrayList<>();
        a.sectors().forEach(s -> {
            if (only != null && !only.equals(s.sector())) return;
            Map<String, Object> l = new HashMap<>();
            l.put("name", s.sector());
            l.put("index", a.series().get(s.sector()).index());
            l.put("bucketReturn", a.series().get(s.sector()).bucketReturn());
            l.put("bucketShare", a.series().get(s.sector()).bucketShare());
            lines.add(l);
        });
        c.put("sectors", lines);
        c.put("buckets", a.buckets().stream().map(b -> b.label()).toList());
        return c;
    }
}
