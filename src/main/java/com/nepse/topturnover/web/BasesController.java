package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.BaseBreakoutService;

/** Sideways bases: stocks moving sideways that are breaking out or coiling at the top of their range. */
@Controller
public class BasesController {
    private final BaseBreakoutService bases;

    public BasesController(BaseBreakoutService bases) {
        this.bases = bases;
    }

    @GetMapping("/bases")
    public String bases(Model model) {
        model.addAttribute("active", "bases");
        model.addAttribute("phases", BaseBreakoutService.Phase.values());
        bases.run().ifPresent(r -> model.addAttribute("r", r));
        return "bases";
    }
}
