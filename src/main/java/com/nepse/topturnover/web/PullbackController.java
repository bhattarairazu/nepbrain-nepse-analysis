package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.BreakoutPullbackService;

/** Breakout pullback: does buying the pullback after a high-volume breakout beat buying the breakout itself? */
@Controller
public class PullbackController {
    private final BreakoutPullbackService study;

    public PullbackController(BreakoutPullbackService study) {
        this.study = study;
    }

    @GetMapping("/pullback")
    public String pullback(Model model) {
        model.addAttribute("active", "pullback");
        study.run().ifPresent(r -> model.addAttribute("r", r));
        return "pullback";
    }
}
