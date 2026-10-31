package com.nepse.topturnover.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Stock search from the top bar: an exact or single match opens the stock's page, otherwise a list of matches. */
@Controller
public class SearchController {
    private final JdbcTemplate jdbc;

    public SearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String q, Model model) {
        String u = q.strip().toUpperCase(Locale.ROOT);
        // "NABIL — Nabil Bank Limited" from the suggestion list
        int dash = u.indexOf(" — ");
        if (dash > 0) u = u.substring(0, dash).strip();
        if (u.isEmpty()) return "redirect:/signals";
        List<Map<String, Object>> hits = jdbc.queryForList("""
                SELECT s.symbol, s.name, s.sector FROM stock s
                WHERE (s.symbol = ? OR s.symbol LIKE ? OR UPPER(s.name) LIKE ?)
                  AND EXISTS (SELECT 1 FROM daily_price p WHERE p.symbol = s.symbol)
                ORDER BY s.symbol = ? DESC, s.symbol LIKE ? DESC, s.symbol LIMIT 50""",
                u, "%" + u + "%", "%" + u + "%", u, u + "%");
        if (!hits.isEmpty() && (hits.get(0).get("symbol").equals(u) || hits.size() == 1)) {
            return "redirect:/stock/" + hits.get(0).get("symbol");
        }
        model.addAttribute("active", "");
        model.addAttribute("q", q.strip());
        model.addAttribute("hits", hits);
        return "search";
    }

    /** Symbols and names for the search box's suggestions. */
    @GetMapping("/api/symbols")
    @ResponseBody
    public List<String> symbols() {
        return jdbc.queryForList("""
                SELECT CONCAT(s.symbol, IF(s.name IS NULL OR s.name = '', '', CONCAT(' — ', s.name))) FROM stock s
                WHERE EXISTS (SELECT 1 FROM daily_price p WHERE p.symbol = s.symbol) ORDER BY s.symbol""", String.class);
    }
}
