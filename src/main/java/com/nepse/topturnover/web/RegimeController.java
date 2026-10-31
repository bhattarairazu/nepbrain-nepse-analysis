package com.nepse.topturnover.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.LeadersService;
import com.nepse.topturnover.analysis.MarketRegimeService;

/** Bull-run checklist and the long-term leaders screen. */
@Controller
public class RegimeController {
    private final MarketRegimeService regime;
    private final LeadersService leaders;

    public RegimeController(MarketRegimeService regime, LeadersService leaders) {
        this.regime = regime;
        this.leaders = leaders;
    }

    @GetMapping("/regime")
    public String regime(Model model) {
        model.addAttribute("active", "regime");
        regime.run().ifPresent(r -> {
            model.addAttribute("r", r);
            Map<String, Object> chart = new HashMap<>();
            chart.put("dates", r.chart().dates());
            chart.put("index", r.chart().index());
            chart.put("ma50", r.chart().ma50());
            chart.put("ma200", r.chart().ma200());
            chart.put("breadth", r.chart().breadth50());
            chart.put("turnover", r.chart().turnover20());
            model.addAttribute("chart", chart);
        });
        return "regime";
    }

    @GetMapping("/leaders")
    public String leaders(Model model) {
        model.addAttribute("active", "leaders");
        model.addAttribute("stages", LeadersService.Stage.values());
        leaders.run().ifPresent(r -> model.addAttribute("r", r));
        return "leaders";
    }
}
