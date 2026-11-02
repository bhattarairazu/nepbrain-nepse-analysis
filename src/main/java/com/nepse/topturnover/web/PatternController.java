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
import com.nepse.topturnover.repository.PatternRepository;

/** Pattern scanner: today's picks, the rule applied to stocks you name, and every past day's picks with how they did. */
@Controller
public class PatternController {
    private final PatternService patterns;

    public PatternController(PatternService patterns) {
        this.patterns = patterns;
    }

    @GetMapping("/pattern")
    public String today(Model model) {
        model.addAttribute("active", "pattern");
        patterns.today().ifPresent(t -> model.addAttribute("t", t));
        return "pattern";
    }

    @PostMapping("/pattern/record")
    public String record(RedirectAttributes flash) {
        flash.addFlashAttribute("message", patterns.recordToday() + " new pick(s) recorded for the latest session.");
        return "redirect:/pattern";
    }

    @PostMapping("/pattern/replay")
    public String replay(RedirectAttributes flash) {
        flash.addFlashAttribute("message", patterns.replayAll()
                + " past pick(s) reconstructed from stored prices (marked REPLAY). Sessions already recorded kept their record.");
        return "redirect:/pattern/history";
    }

    @GetMapping("/pattern/check")
    public String check(@RequestParam(required = false) String symbols, Model model) {
        model.addAttribute("active", "patterncheck");
        model.addAttribute("symbols", symbols == null ? "" : symbols);
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
    public String history(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        model.addAttribute("active", "patternhistory");
        List<PatternRepository.DateCount> dates = patterns.dates();
        model.addAttribute("dates", dates);
        LocalDate d = date != null ? date : dates.isEmpty() ? null : dates.get(0).date();
        if (d != null) model.addAttribute("h", patterns.history(d));
        if (!dates.isEmpty()) {
            List<PatternService.Pick> all = patterns.between(dates.get(dates.size() - 1).date(), dates.get(0).date());
            model.addAttribute("allSummary", summary(all, null));
            model.addAttribute("liveSummary", summary(all, "LIVE"));
            model.addAttribute("replaySummary", summary(all, "REPLAY"));
            model.addAttribute("replayAfter", PatternService.summarise(all.stream()
                    .filter(p -> p.row().source().equals("REPLAY") && !p.row().date().isBefore(PatternService.FOUND_UNTIL)).toList()));
            model.addAttribute("foundUntil", PatternService.FOUND_UNTIL);
        }
        return "pattern-history";
    }

    private static PatternService.Summary summary(List<PatternService.Pick> all, String source) {
        return PatternService.summarise(source == null ? all : all.stream().filter(p -> p.row().source().equals(source)).toList());
    }
}
