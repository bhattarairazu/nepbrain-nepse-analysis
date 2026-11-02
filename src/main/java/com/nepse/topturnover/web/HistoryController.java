package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.repository.MarketDataRepository;
import com.nepse.topturnover.service.HistoryService;

/** Data → Fetch history: download past sessions from ShareSansar for a chosen date range. */
@Controller
public class HistoryController {
    /** Earliest date offered by the "everything" preset; the job stops by itself where the site's history ends. */
    static final LocalDate SITE_START = LocalDate.of(2010, 1, 1);

    private final HistoryService history;
    private final MarketDataRepository repo;
    private final NepseProperties props;

    public HistoryController(HistoryService history, MarketDataRepository repo, NepseProperties props) {
        this.history = history;
        this.repo = repo;
        this.props = props;
    }

    @GetMapping("/data/history")
    public String page(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "false") boolean recheck, Model model) {
        LocalDate today = ZonedDateTime.now(props.market().zone()).toLocalDate();
        LocalDate earliest = repo.earliestDay().orElse(null), latest = repo.latestDay().orElse(null);
        model.addAttribute("active", "history");
        model.addAttribute("today", today);
        model.addAttribute("earliest", earliest);
        model.addAttribute("latest", latest);
        model.addAttribute("siteStart", SITE_START);
        model.addAttribute("before", earliest != null ? earliest.minusDays(1) : today);
        model.addAttribute("years", repo.sessionsByYear());
        model.addAttribute("job", history.status());
        model.addAttribute("delay", props.history().delaySeconds());
        model.addAttribute("from", from != null ? from : earliest != null ? earliest.minusYears(1) : today.minusYears(1));
        model.addAttribute("to", to != null ? to : today);
        model.addAttribute("recheck", recheck);
        if (from != null && to != null && !from.isAfter(to)) model.addAttribute("plan", history.plan(from, to.isAfter(today) ? today : to, recheck));
        return "data-history";
    }

    @PostMapping("/data/history/fetch")
    public String fetch(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue = "false") boolean recheck, RedirectAttributes flash) {
        String why = history.startRange(from, to, recheck);
        flash.addFlashAttribute("message", why == null ? "Started in the background. You can leave this page; progress shows here." : why);
        flash.addFlashAttribute("ok", why == null);
        return "redirect:/data/history?from=" + from + "&to=" + to + (recheck ? "&recheck=true" : "");
    }

    @PostMapping("/data/history/stop")
    public String stop(RedirectAttributes flash) {
        history.stop();
        flash.addFlashAttribute("message", "Stopping after the current request.");
        flash.addFlashAttribute("ok", true);
        return "redirect:/data/history";
    }
}
