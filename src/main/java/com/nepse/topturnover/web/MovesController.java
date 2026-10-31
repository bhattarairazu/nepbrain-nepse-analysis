package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.MoveStudyService;

/** Move study: what the stock-days before a tradeable +7% move had in common, and a rare-trade account built on it. */
@Controller
public class MovesController {
    private final MoveStudyService study;

    public MovesController(MoveStudyService study) {
        this.study = study;
    }

    @GetMapping("/moves")
    public String moves(Model model) {
        model.addAttribute("active", "moves");
        model.addAttribute("target", MoveStudyService.TARGET * 100);
        model.addAttribute("stop", MoveStudyService.STOP * 100);
        model.addAttribute("window", MoveStudyService.WINDOW);
        study.run().ifPresent(r -> model.addAttribute("r", r));
        return "moves";
    }
}
