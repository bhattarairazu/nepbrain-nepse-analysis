package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.DiscoveryService;
import com.nepse.topturnover.repository.DiscoveryRepository;

/** Pattern scanner → Run analysis now: search all stored history for the best rules, on demand. */
@Controller
public class DiscoveryController {
    private final DiscoveryService discovery;
    private final DiscoveryRepository repo;

    public DiscoveryController(DiscoveryService discovery, DiscoveryRepository repo) {
        this.discovery = discovery;
        this.repo = repo;
    }

    @GetMapping("/discover")
    public String page(@RequestParam(required = false) Long run, Model model) {
        model.addAttribute("active", "discover");
        model.addAttribute("status", discovery.status());
        var runs = repo.runs(30);
        model.addAttribute("runs", runs);
        Long id = run != null ? run : runs.isEmpty() ? null : runs.get(0).id();
        if (id != null) repo.load(id).ifPresent(r -> model.addAttribute("r", r));
        model.addAttribute("features", DiscoveryService.FEATURES);
        return "discover";
    }

    @PostMapping("/discover/run")
    public String run(@RequestParam(defaultValue = "10") double target, @RequestParam(defaultValue = "7") double stop,
                      @RequestParam(defaultValue = "20") int sessions, RedirectAttributes flash) {
        target = Math.max(2, Math.min(target, 50));
        stop = Math.max(2, Math.min(stop, 30));
        sessions = Math.max(3, Math.min(sessions, 120));
        boolean started = discovery.start(new DiscoveryService.Settings(target, stop, sessions));
        flash.addFlashAttribute("message", started ? "Analysis started in the background; this page refreshes until it is done."
                : "An analysis is already running.");
        return "redirect:/discover";
    }
}
