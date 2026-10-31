package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.WatchlistService;

/** Watchlist: every flagged setup and hand-picked stock, when it triggered, and how it is doing against its plan. */
@Controller
public class WatchlistController {
    private final WatchlistService watchlist;

    public WatchlistController(WatchlistService watchlist) {
        this.watchlist = watchlist;
    }

    @GetMapping("/watchlist")
    public String board(@RequestParam(defaultValue = "false") boolean archived, Model model) {
        model.addAttribute("active", "watchlist");
        model.addAttribute("archived", archived);
        model.addAttribute("b", watchlist.board(archived));
        model.addAttribute("defaultTarget", WatchlistService.DEFAULT_TARGET);
        model.addAttribute("defaultStop", WatchlistService.DEFAULT_STOP);
        model.addAttribute("defaultWindow", WatchlistService.DEFAULT_WINDOW);
        return "watchlist";
    }

    @PostMapping("/watchlist/add")
    public String add(@RequestParam String symbol, @RequestParam(required = false) String note, @RequestParam(required = false) Double target,
                      @RequestParam(required = false) Double stop, @RequestParam(required = false) Integer window, RedirectAttributes ra) {
        ra.addFlashAttribute("message", watchlist.addManual(symbol, note, target, stop, window)
                .orElse(symbol.strip().toUpperCase(java.util.Locale.ROOT) + " added to the watchlist."));
        return "redirect:/watchlist";
    }

    @PostMapping("/watchlist/{id}/archive")
    public String archive(@PathVariable long id, @RequestParam(defaultValue = "true") boolean archived) {
        watchlist.archive(id, archived);
        return "redirect:/watchlist" + (archived ? "" : "?archived=true");
    }

    @PostMapping("/watchlist/{id}/note")
    public String note(@PathVariable long id, @RequestParam(required = false) String note) {
        watchlist.note(id, note);
        return "redirect:/watchlist";
    }
}
