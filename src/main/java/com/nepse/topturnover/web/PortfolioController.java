package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.nepse.topturnover.analysis.PortfolioService;
import com.nepse.topturnover.analysis.PortfolioService.Period;
import com.nepse.topturnover.analysis.PortfolioService.Priority;
import com.nepse.topturnover.analysis.PortfolioService.Settings;

/** "What would my account have done?": replays stored backtest trades with real capital limits and tax. */
@Controller
public class PortfolioController {
    private static final List<String> DEFAULT_RULES = List.of("ADVANCED:BB_DRYUP", "ADVANCED:BB_SECTOR");

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/backtest/portfolio")
    public String portfolio(@RequestParam(defaultValue = "3000000") double capital,
                            @RequestParam(defaultValue = "10") double positionPct,
                            @RequestParam(defaultValue = "10") int maxPositions,
                            @RequestParam(defaultValue = "true") boolean tax,
                            @RequestParam(defaultValue = "ALL") Period period,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) List<String> rules,
                            @RequestParam(defaultValue = "RANDOM") Priority priority,
                            Model model) {
        Settings s = new Settings(Math.max(10_000, capital), Math.max(1, Math.min(100, positionPct)),
                Math.max(1, Math.min(50, maxPositions)), tax, period, from, to,
                rules == null || rules.isEmpty() ? DEFAULT_RULES : rules, priority);
        model.addAttribute("active", "backtest");
        model.addAttribute("s", s);
        model.addAttribute("options", portfolio.options());
        model.addAttribute("periods", Period.values());
        model.addAttribute("priorities", Priority.values());
        portfolio.simulate(s).ifPresent(r -> {
            model.addAttribute("r", r);
            Map<String, Object> chart = new HashMap<>();
            chart.put("dates", r.equity().stream().map(p -> p.date().toString()).toList());
            chart.put("equity", r.equity().stream().map(p -> p.equity()).toList());
            chart.put("market", r.equity().stream().map(p -> s.capital() * p.market() / 100).toList());
            model.addAttribute("chart", chart);
        });
        return "portfolio";
    }
}
