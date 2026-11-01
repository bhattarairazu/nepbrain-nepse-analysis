package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.RuleSetService;

/** My rule sets: the backtest of each rule and the stocks that fit them at the latest close, as two tabs. */
@Controller
public class RuleSetController {
    private final RuleSetService rules;

    public RuleSetController(RuleSetService rules) {
        this.rules = rules;
    }

    @GetMapping("/rules")
    public String backtest(Model model) {
        return page(model, "rules", "rules");
    }

    @GetMapping("/rules/picks")
    public String picks(Model model) {
        return page(model, "rulepicks", "rule-picks");
    }

    private String page(Model model, String active, String view) {
        model.addAttribute("active", active);
        rules.run().ifPresent(r -> model.addAttribute("r", r));
        return view;
    }
}
