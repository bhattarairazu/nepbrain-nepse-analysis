package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.nepse.topturnover.analysis.BacktestService;
import com.nepse.topturnover.analysis.BacktestService.CaseStudy;

/** Strategy backtests, a "what did the rules say that day" lookup, and recommendations only from proven strategies. */
@Controller
public class BacktestController {
    private final BacktestService backtest;

    public BacktestController(BacktestService backtest) {
        this.backtest = backtest;
    }

    @GetMapping("/backtest")
    public String backtest(@RequestParam(defaultValue = "5") int h,
                           @RequestParam(defaultValue = "false") boolean filter,
                           @RequestParam(required = false) String symbols,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           Model model) {
        int horizon = List.of(2, 5, 10).contains(h) ? h : 5;
        model.addAttribute("active", "backtest");
        model.addAttribute("h", horizon);
        model.addAttribute("cost", BacktestService.COST_PCT);
        model.addAttribute("bar", new int[] {BacktestService.CONF_MIN_TRADES, (int) BacktestService.CONF_MIN_WIN});
        model.addAttribute("barExcess", BacktestService.CONF_MIN_EXCESS);
        model.addAttribute("filter", filter);
        backtest.run(horizon, filter).ifPresent(r -> model.addAttribute("r", r));

        List<CaseStudy> cases = new ArrayList<>();
        if (symbols != null && date != null) {
            for (String s : symbols.split("[,\\s]+")) {
                if (!s.isBlank()) backtest.caseStudy(s.trim().toUpperCase(Locale.ROOT), date).ifPresent(cases::add);
            }
        }
        model.addAttribute("cases", cases);
        model.addAttribute("symbols", symbols == null ? "" : symbols);
        model.addAttribute("caseDate", date);
        return "backtest";
    }
}
