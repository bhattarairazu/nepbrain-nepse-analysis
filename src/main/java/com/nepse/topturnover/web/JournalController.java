package com.nepse.topturnover.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.AdvancedStrategyService;
import com.nepse.topturnover.analysis.InsightService;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.journal.JournalService;
import com.nepse.topturnover.journal.JournalService.View;
import com.nepse.topturnover.journal.JournalTrade;
import com.nepse.topturnover.repository.JournalRepository;

/** Trade journal: log buys and sells, see open positions, and review what works for you. */
@Controller
public class JournalController {
    private final JournalRepository repo;
    private final JournalService journal;
    private final InsightService insights;
    private final NepseProperties props;

    public JournalController(JournalRepository repo, JournalService journal, InsightService insights, NepseProperties props) {
        this.repo = repo;
        this.journal = journal;
        this.insights = insights;
        this.props = props;
    }

    @GetMapping("/journal")
    public String journal(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          Model model) {
        List<View> all = journal.views();
        model.addAttribute("active", "journal");
        model.addAttribute("open", all.stream().filter(v -> v.t().isOpen()).toList());
        model.addAttribute("closed", all.stream().filter(v -> !v.t().isOpen())
                .filter(v -> from == null || !v.t().exitDate().isBefore(from))
                .filter(v -> to == null || !v.t().exitDate().isAfter(to)).toList());
        model.addAttribute("review", journal.review(all, from, to));
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "journal";
    }

    @GetMapping("/journal/new")
    public String newTrade(@RequestParam(required = false) String symbol, @RequestParam(required = false) String rule,
                           @RequestParam(required = false) Double stop, @RequestParam(required = false) Double target,
                           @RequestParam(required = false) Double price, Model model) {
        String sym = symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
        Double last = sym == null ? null : repo.latestClose(sym).map(r -> (Double) r[1]).orElse(null);
        String regime = insights.regime(null).label();
        JournalTrade t = new JournalTrade(null, sym, sym == null ? null : repo.sectorOf(sym).orElse(null), rule,
                JournalTrade.REGIMES.contains(regime) ? regime : "Unknown", today(), price != null ? price : last != null ? last : 0,
                0, stop, target, null, null, null, null, null, null, null, null, null);
        form(model, t, "Log a buy");
        return "journal-form";
    }

    @GetMapping("/journal/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        JournalTrade t = repo.find(id).orElseThrow();
        form(model, t, "Edit trade #" + id);
        return "journal-form";
    }

    @PostMapping("/journal/save")
    public String save(@RequestParam(required = false) Long id,
                       @RequestParam String symbol, @RequestParam(required = false) String sector,
                       @RequestParam(required = false) String rule, @RequestParam(required = false) String marketRegime,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate entryDate,
                       @RequestParam double entryPrice, @RequestParam int quantity,
                       @RequestParam(required = false) Double stopPrice, @RequestParam(required = false) Double targetPrice,
                       @RequestParam(required = false) String moodEntry, @RequestParam(required = false) String entryNotes,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate exitDate,
                       @RequestParam(required = false) Double exitPrice, @RequestParam(required = false) String exitReason,
                       @RequestParam(required = false) String moodExit, @RequestParam(required = false) String followedPlan,
                       @RequestParam(required = false) List<String> mistakes, @RequestParam(required = false) String lesson,
                       RedirectAttributes flash) {
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        String sec = sector == null || sector.isBlank() ? repo.sectorOf(sym).orElse(null) : sector;
        JournalTrade t = new JournalTrade(id, sym, sec, trim(rule), trim(marketRegime), entryDate, entryPrice, quantity,
                stopPrice, targetPrice, trim(moodEntry), trim(entryNotes), exitDate, exitPrice, trim(exitReason), trim(moodExit),
                plan(followedPlan), mistakes == null ? null : String.join(", ", mistakes), trim(lesson));
        long saved = repo.save(t);
        flash.addFlashAttribute("message", (id == null ? "Logged " : "Updated ") + sym + " (trade #" + saved + ").");
        return "redirect:/journal";
    }

    @GetMapping("/journal/{id}/close")
    public String closeForm(@PathVariable long id, Model model) {
        JournalTrade t = repo.find(id).orElseThrow();
        Double last = repo.latestClose(t.symbol()).map(r -> (Double) r[1]).orElse(null);
        model.addAttribute("active", "journal");
        model.addAttribute("t", t);
        model.addAttribute("v", journal.view(t));
        model.addAttribute("exitDate", t.exitDate() != null ? t.exitDate() : today());
        model.addAttribute("exitPrice", t.exitPrice() != null ? t.exitPrice() : last);
        options(model);
        return "journal-close";
    }

    @PostMapping("/journal/{id}/close")
    public String close(@PathVariable long id,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate exitDate,
                        @RequestParam double exitPrice, @RequestParam(required = false) String exitReason,
                        @RequestParam(required = false) String moodExit, @RequestParam(required = false) String followedPlan,
                        @RequestParam(required = false) List<String> mistakes, @RequestParam(required = false) String lesson,
                        RedirectAttributes flash) {
        JournalTrade t = repo.find(id).orElseThrow();
        repo.save(new JournalTrade(t.id(), t.symbol(), t.sector(), t.rule(), t.marketRegime(), t.entryDate(), t.entryPrice(),
                t.quantity(), t.stopPrice(), t.targetPrice(), t.moodEntry(), t.entryNotes(), exitDate, exitPrice, trim(exitReason),
                trim(moodExit), plan(followedPlan), mistakes == null ? null : String.join(", ", mistakes), trim(lesson)));
        View v = journal.view(repo.find(id).orElseThrow());
        flash.addFlashAttribute("message", String.format(Locale.ROOT, "Closed %s: net %s Rs %,.0f (%+.2f%%) after charges and tax.",
                t.symbol(), v.b().netPnl() >= 0 ? "profit" : "loss", Math.abs(v.b().netPnl()), v.b().netPct()));
        return "redirect:/journal";
    }

    @PostMapping("/journal/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes flash) {
        repo.find(id).ifPresent(t -> {
            repo.delete(id);
            flash.addFlashAttribute("message", "Deleted trade #" + id + " (" + t.symbol() + ").");
        });
        return "redirect:/journal";
    }

    @GetMapping("/journal/export.csv")
    public ResponseEntity<byte[]> export() {
        StringBuilder csv = new StringBuilder("id,symbol,sector,rule,market_regime,entry_date,entry_price,quantity,stop,target,mood_entry,"
                + "exit_date,exit_price,exit_reason,mood_exit,followed_plan,mistakes,buy_cost,charges,tax,net_pnl,net_pct,r_multiple,days_held,"
                + "entry_notes,lesson\n");
        for (View v : journal.views()) {
            JournalTrade t = v.t();
            List<Object> row = new ArrayList<>(List.of(t.id(), t.symbol(), n(t.sector()), n(t.rule()), n(t.marketRegime()), t.entryDate(),
                    t.entryPrice(), t.quantity(), n(t.stopPrice()), n(t.targetPrice()), n(t.moodEntry()), n(t.exitDate()), n(t.exitPrice()),
                    n(t.exitReason()), n(t.moodExit()), n(t.followedPlan()), n(t.mistakes())));
            if (t.isOpen()) row.addAll(List.of("", "", "", "", "", "", "")); else row.addAll(List.of(
                    r2(v.b().buyCost()), r2(v.b().charges()), r2(v.b().tax()), r2(v.b().netPnl()), r2(v.b().netPct()),
                    v.b().rMultiple() == null ? "" : r2(v.b().rMultiple()), v.b().daysHeld()));
            row.add(n(t.entryNotes()));
            row.add(n(t.lesson()));
            csv.append(String.join(",", row.stream().map(JournalController::cell).toList())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"trade-journal.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    private void form(Model model, JournalTrade t, String title) {
        model.addAttribute("active", "journal");
        model.addAttribute("t", t);
        model.addAttribute("title", title);
        model.addAttribute("symbols", repo.symbols());
        options(model);
    }

    private void options(Model model) {
        List<String> rules = new ArrayList<>();
        for (AdvancedStrategyService.Setup s : AdvancedStrategyService.Setup.values()) {
            if (s != AdvancedStrategyService.Setup.RANDOM) rules.add(s.getTitle());
        }
        rules.addAll(List.of("Breakout from a quiet base", "Pullback to the 20-day EMA", "Base about to break out",
                "Own idea / discretionary", "Long-term investment"));
        model.addAttribute("rules", rules);
        model.addAttribute("regimes", JournalTrade.REGIMES);
        model.addAttribute("moods", JournalTrade.MOODS);
        model.addAttribute("mistakeTags", JournalTrade.MISTAKES);
        model.addAttribute("exitReasons", JournalTrade.EXIT_REASONS);
    }

    private LocalDate today() {
        return ZonedDateTime.now(props.market().zone()).toLocalDate();
    }

    private static Boolean plan(String v) {
        if (v == null || v.isBlank()) return null;
        return v.equalsIgnoreCase("yes");
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Object n(Object o) {
        return o == null ? "" : o;
    }

    private static String r2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String cell(Object o) {
        String s = String.valueOf(o);
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
