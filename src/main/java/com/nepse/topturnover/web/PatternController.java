package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.PatternService;
import com.nepse.topturnover.analysis.PatternService.Rule;
import com.nepse.topturnover.repository.PatternRepository;

/** Pattern scanner: today's picks per rule, the rules applied to stocks you name, and every past day's picks with how they did. */
@Controller
public class PatternController {
    private final PatternService patterns;

    public PatternController(PatternService patterns) {
        this.patterns = patterns;
    }

    @GetMapping("/pattern")
    public String today(Model model) {
        model.addAttribute("active", "pattern");
        model.addAttribute("rules", Rule.active());
        patterns.today().ifPresent(t -> model.addAttribute("t", t));
        return "pattern";
    }

    @PostMapping("/pattern/record")
    public String record(RedirectAttributes flash) {
        flash.addFlashAttribute("message", patterns.recordToday() + " new pick(s) recorded for the latest session.");
        return "redirect:/pattern";
    }

    @PostMapping("/pattern/replay")
    public String replay(@RequestParam(required = false) String rule, RedirectAttributes flash) {
        flash.addFlashAttribute("message", patterns.replayAll()
                + " past pick(s) reconstructed from stored prices (marked REPLAY). Sessions already recorded kept their record.");
        return "redirect:/pattern/history" + (rule == null ? "" : "?rule=" + rule);
    }

    @GetMapping("/pattern/check")
    public String check(@RequestParam(required = false) String symbols, Model model) {
        model.addAttribute("active", "patterncheck");
        model.addAttribute("symbols", symbols == null ? "" : symbols);
        model.addAttribute("rules", Rule.active());
        List<Object[]> results = new ArrayList<>();
        if (symbols != null) {
            Set<String> list = new LinkedHashSet<>();
            for (String s : symbols.toUpperCase(Locale.ROOT).split("[\\s,;]+")) if (!s.isBlank()) list.add(s.trim());
            for (String s : list) results.add(new Object[] {s, patterns.check(s).orElse(null)});
        }
        model.addAttribute("results", results);
        return "pattern-check";
    }

    @GetMapping("/pattern/history")
    public String history(@RequestParam(required = false) String rule,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        model.addAttribute("active", "patternhistory");
        Rule r = Rule.of(rule).orElse(Rule.active().get(0));
        model.addAttribute("rule", r);
        model.addAttribute("allRules", Rule.values());
        List<PatternRepository.DateCount> dates = patterns.dates(r);
        model.addAttribute("dates", dates);
        LocalDate d = date != null ? date : dates.isEmpty() ? null : dates.get(0).date();
        if (d != null) model.addAttribute("h", patterns.history(r, d));
        model.addAttribute("track", patterns.track(r));
        return "pattern-history";
    }
}
