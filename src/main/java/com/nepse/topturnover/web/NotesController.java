package com.nepse.topturnover.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.notes.NotesService;

/** Notes: your dated notes and the conditions to check before deciding. */
@Controller
public class NotesController {
    private final NotesService notes;

    public NotesController(NotesService notes) {
        this.notes = notes;
    }

    @GetMapping("/notes")
    public String board(@RequestParam(required = false) String symbol, @RequestParam(required = false) String prefill, Model model) {
        model.addAttribute("active", "notes");
        model.addAttribute("b", notes.board(symbol));
        model.addAttribute("prefill", prefill);
        return "notes";
    }

    @PostMapping("/notes/add")
    public String add(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate forDate,
                      @RequestParam(required = false) String symbol, @RequestParam String title, @RequestParam(required = false) String body,
                      @RequestParam(required = false) String conditions, @RequestParam(required = false) String back, RedirectAttributes ra) {
        ra.addFlashAttribute("message", notes.create(forDate, symbol, title, body, conditions).orElse("Note saved."));
        return "redirect:" + safe(back);
    }

    @PostMapping("/notes/{id}/save")
    public String save(@PathVariable long id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate forDate,
                       @RequestParam(required = false) String symbol, @RequestParam String title, @RequestParam(required = false) String body,
                       @RequestParam(required = false) String conditions, @RequestParam(required = false) String back, RedirectAttributes ra) {
        ra.addFlashAttribute("message", notes.update(id, forDate, symbol, title, body, conditions).orElse("Note updated."));
        return "redirect:" + safe(back);
    }

    @PostMapping("/notes/{id}/done")
    public String done(@PathVariable long id, @RequestParam(required = false) String decision, @RequestParam(required = false) String back) {
        notes.done(id, decision);
        return "redirect:" + safe(back);
    }

    @PostMapping("/notes/{id}/reopen")
    public String reopen(@PathVariable long id, @RequestParam(required = false) String back) {
        notes.reopen(id);
        return "redirect:" + safe(back);
    }

    @PostMapping("/notes/condition/{id}")
    public String check(@PathVariable long id, @RequestParam(defaultValue = "false") boolean checked, @RequestParam(required = false) String back) {
        notes.check(id, checked);
        return "redirect:" + safe(back);
    }

    @PostMapping("/notes/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam(required = false) String back, RedirectAttributes ra) {
        notes.delete(id);
        ra.addFlashAttribute("message", "Note deleted.");
        return "redirect:" + safe(back);
    }

    /** Only same-site paths, so a form cannot redirect elsewhere. */
    private static String safe(String back) {
        return back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/notes";
    }
}
