package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.RecommendationService;

/** Top 5: the stocks that best fit the sideways-accumulation formula at the latest close, and how its picks did before. */
@Controller
public class Top5Controller {
    private final RecommendationService top5;

    public Top5Controller(RecommendationService top5) {
        this.top5 = top5;
    }

    @GetMapping("/top5")
    public String top5(Model model) {
        model.addAttribute("active", "top5");
        model.addAttribute("minScore", RecommendationService.MIN_SCORE);
        model.addAttribute("target", RecommendationService.TARGET);
        model.addAttribute("stop", RecommendationService.STOP);
        model.addAttribute("window", RecommendationService.WINDOW);
        top5.run().ifPresent(r -> model.addAttribute("r", r));
        return "top5";
    }
}
