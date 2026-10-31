package com.nepse.topturnover.web;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.nepse.topturnover.notes.NotesService;

/** Values every page's menu needs: how many open notes are due today or overdue. */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class GlobalModel {
    private final NotesService notes;

    public GlobalModel(NotesService notes) {
        this.notes = notes;
    }

    @ModelAttribute
    public void menu(Model model) {
        model.addAttribute("notesDue", notes.dueCount());
    }
}
