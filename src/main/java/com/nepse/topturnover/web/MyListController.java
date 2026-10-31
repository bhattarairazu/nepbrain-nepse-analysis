package com.nepse.topturnover.web;

import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.MyListService;

/** My list: the stocks you add and remove yourself, with your levels and every study's read. */
@Controller
public class MyListController {
    private final MyListService list;

    public MyListController(MyListService list) {
        this.list = list;
    }

    @GetMapping("/my-list")
    public String board(Model model) {
        model.addAttribute("active", "mylist");
        model.addAttribute("b", list.board());
        model.addAttribute("intents", MyListService.Intent.values());
        return "my-list";
    }

    @PostMapping("/my-list/add")
    public String add(@RequestParam String symbol, @RequestParam(defaultValue = "WATCH") String intent,
                      @RequestParam(required = false) Double buyBelow, @RequestParam(required = false) Double stopPrice,
                      @RequestParam(required = false) Double sellAbove, @RequestParam(required = false) String note,
                      @RequestParam(required = false) String back, RedirectAttributes ra) {
        String sym = symbol.strip().toUpperCase(Locale.ROOT);
        ra.addFlashAttribute("message", list.add(symbol, intent, buyBelow, stopPrice, sellAbove, note).orElse(sym.split(" ")[0] + " added to your list."));
        return "redirect:" + (back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/my-list");
    }

    @PostMapping("/my-list/{id}/save")
    public String save(@PathVariable long id, @RequestParam(defaultValue = "WATCH") String intent,
                       @RequestParam(required = false) Double buyBelow, @RequestParam(required = false) Double stopPrice,
                       @RequestParam(required = false) Double sellAbove, @RequestParam(required = false) String note, RedirectAttributes ra) {
        ra.addFlashAttribute("message", list.update(id, intent, buyBelow, stopPrice, sellAbove, note).orElse("Saved."));
        return "redirect:/my-list";
    }

    @PostMapping("/my-list/{id}/remove")
    public String remove(@PathVariable long id, RedirectAttributes ra) {
        ra.addFlashAttribute("message", list.remove(id).map(s -> s + " removed from your list.").orElse("Already removed."));
        return "redirect:/my-list";
    }
}
