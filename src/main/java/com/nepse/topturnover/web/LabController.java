package com.nepse.topturnover.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.StrategyLabService;

/** Strategy lab: textbook strategies run as a real Rs 30 lakh account. */
@Controller
public class LabController {
    private final StrategyLabService lab;

    public LabController(StrategyLabService lab) {
        this.lab = lab;
    }

    @GetMapping("/lab")
    public String lab(Model model) {
        model.addAttribute("active", "lab");
        model.addAttribute("capital", StrategyLabService.CAPITAL);
        lab.run().ifPresent(r -> {
            model.addAttribute("r", r);
            Map<String, Object> chart = new HashMap<>();
            chart.put("dates", r.dates().stream().map(Object::toString).toList());
            chart.put("lines", r.labs().stream().map(l -> Map.of("name", l.lab().getTitle(), "values", l.equity())).toList());
            model.addAttribute("chart", chart);
        });
        return "lab";
    }
}
