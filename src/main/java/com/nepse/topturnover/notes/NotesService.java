package com.nepse.topturnover.notes;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.repository.NoteRepository;
import com.nepse.topturnover.repository.NoteRepository.Condition;
import com.nepse.topturnover.repository.NoteRepository.Day;
import com.nepse.topturnover.repository.NoteRepository.NewCondition;
import com.nepse.topturnover.repository.NoteRepository.Note;
import com.nepse.topturnover.schedule.CrawlWindow;

/**
 * Notes: dated notes, usually for the next session, optionally about one stock, each with conditions. A condition
 * written like "NABIL close >= 560", "API turnover > 5 cr" or "NABIL above 560" is checked against the stored prices
 * of every session from the note's date on; anything else is a tick box you check yourself.
 */
@Service
public class NotesService {
    public enum Metric {
        CLOSE("close"), HIGH("high"), LOW("low"), CHANGE("day change %"), TURNOVER("turnover"), VOLUME("volume");

        private final String label;

        Metric(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }
    }

    /** A condition with its live check: met = null for tick boxes you have not checked and price conditions without data. */
    public record ConditionView(Condition c, Boolean met, String detail, LocalDate firstMet) {}

    public record NoteView(Note note, List<ConditionView> conditions, int met, int total, boolean due, boolean overdue) {
        public boolean allMet() { return total > 0 && met == total; }
    }

    public record Board(LocalDate today, LocalDate nextSession, List<NoteView> due, List<NoteView> upcoming, List<NoteView> done, String symbol) {}

    private static final Pattern PRICE = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9]{1,14})\\s+(close|price|closes|high|low|change%?|chg%?|turnover|volume|vol)?\\s*"
            + "(>=|<=|>|<|=>|=<|above|below|over|under|at least|at most)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(%|cr|crore|crores|lakh|lakhs|l|k)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final NoteRepository repo;
    private final NepseProperties props;

    public NotesService(NoteRepository repo, NepseProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public Board board(String symbol) {
        String sym = blank(symbol) ? null : symbol.strip().toUpperCase(Locale.ROOT);
        LocalDate today = today();
        List<NoteView> due = new ArrayList<>(), upcoming = new ArrayList<>(), done = new ArrayList<>();
        for (Note n : repo.all(sym)) {
            NoteView v = view(n, today);
            if (n.status().equals("DONE")) done.add(v);
            else if (n.forDate().isAfter(today)) upcoming.add(0, v);   // soonest first
            else due.add(v);
        }
        return new Board(today, nextSession(today), due, upcoming, done.stream().limit(60).toList(), sym);
    }

    /** Open and recent notes about one stock (for its page). */
    public List<NoteView> forSymbol(String symbol) {
        LocalDate today = today();
        return repo.all(symbol.toUpperCase(Locale.ROOT)).stream().limit(10).map(n -> view(n, today)).toList();
    }

    public int dueCount() {
        try {
            return repo.countDue(today());
        } catch (RuntimeException e) {
            return 0; // the table may not exist yet during a first start
        }
    }

    // ------------------------------------------------------------------ changes

    public Optional<String> create(LocalDate forDate, String symbol, String title, String body, String conditions) {
        Optional<String> bad = validate(symbol, title);
        if (bad.isPresent()) return bad;
        repo.create(forDate == null ? nextSession(today()) : forDate, sym(symbol), title.strip(), trimBody(body), parse(conditions, sym(symbol), List.of()));
        return Optional.empty();
    }

    public Optional<String> update(long id, LocalDate forDate, String symbol, String title, String body, String conditions) {
        Optional<Note> old = repo.find(id);
        if (old.isEmpty()) return Optional.of("That note no longer exists.");
        Optional<String> bad = validate(symbol, title);
        if (bad.isPresent()) return bad;
        repo.update(id, forDate == null ? old.get().forDate() : forDate, sym(symbol), title.strip(), trimBody(body),
                parse(conditions, sym(symbol), old.get().conditions()));
        return Optional.empty();
    }

    public void done(long id, String decision) {
        repo.setStatus(id, "DONE", blank(decision) ? null : decision.strip().substring(0, Math.min(500, decision.strip().length())));
    }

    public void reopen(long id) {
        repo.setStatus(id, "OPEN", null);
    }

    public void check(long conditionId, boolean checked) {
        repo.setChecked(conditionId, checked);
    }

    public void delete(long id) {
        repo.delete(id);
    }

    /** The conditions of a note as editable text, one per line. */
    public static String conditionsText(Note n) {
        return String.join("\n", n.conditions().stream().map(Condition::text).toList());
    }

    // ------------------------------------------------------------------ evaluation

    private NoteView view(Note n, LocalDate today) {
        List<ConditionView> cv = new ArrayList<>();
        for (Condition c : n.conditions()) cv.add(evaluate(c, n.forDate()));
        int met = (int) cv.stream().filter(x -> Boolean.TRUE.equals(x.met())).count();
        boolean open = n.status().equals("OPEN");
        return new NoteView(n, cv, met, cv.size(), open && !n.forDate().isAfter(today), open && n.forDate().isBefore(today));
    }

    /** Price conditions: checked on the latest session, and the first session (from the note's date on) when it was met. */
    private ConditionView evaluate(Condition c, LocalDate from) {
        if (!"PRICE".equals(c.kind())) return new ConditionView(c, c.checked(), c.checked() ? "ticked" : "not ticked yet", null);
        Metric m = Metric.valueOf(c.metric());
        Optional<Day> last = repo.latest(c.symbol());
        if (last.isEmpty()) return new ConditionView(c, null, "no stored prices for " + c.symbol(), null);
        LocalDate first = null;
        for (Day d : repo.days(c.symbol(), from)) {
            if (holds(d, m, c.op(), c.value())) { first = d.date(); break; }
        }
        Day d = last.get();
        Double v = value(d, m);
        boolean now = holds(d, m, c.op(), c.value());
        String detail = v == null ? "no " + m.getLabel() + " on " + d.date()
                : String.format(Locale.ROOT, "%s %s on %s", m.getLabel(), fmt(m, v), d.date())
                + (first != null && !first.equals(d.date()) ? (now ? "" : "; was met on " + first) : "");
        return new ConditionView(c, v == null ? null : now, detail, first);
    }

    private static boolean holds(Day d, Metric m, String op, Double target) {
        Double v = value(d, m);
        if (v == null || target == null) return false;
        return ">=".equals(op) ? v >= target : v <= target;
    }

    private static Double value(Day d, Metric m) {
        return switch (m) {
            case CLOSE -> d.close();
            case HIGH -> d.high();
            case LOW -> d.low();
            case CHANGE -> d.changePct();
            case TURNOVER -> d.turnover();
            case VOLUME -> d.volume();
        };
    }

    private static String fmt(Metric m, double v) {
        return switch (m) {
            case CHANGE -> String.format(Locale.ROOT, "%+.2f%%", v);
            case TURNOVER -> v >= 1e7 ? String.format(Locale.ROOT, "Rs %.2f cr", v / 1e7) : String.format(Locale.ROOT, "Rs %.1f lakh", v / 1e5);
            case VOLUME -> String.format(Locale.ROOT, "%,.0f", v);
            default -> String.format(Locale.ROOT, "%.2f", v);
        };
    }

    // ------------------------------------------------------------------ parsing

    /**
     * One condition per line. "NABIL close >= 560", "NABIL above 560" (close), "API turnover > 5 cr", "SHL volume >= 50k",
     * "NABIL change >= 2%", and on a note about one stock simply "close above 560", become price conditions;
     * any other line is a tick box. Ticks survive edits when the line is unchanged.
     */
    List<NewCondition> parse(String text, String noteSymbol, List<Condition> old) {
        List<NewCondition> out = new ArrayList<>();
        if (blank(text)) return out;
        for (String raw : text.split("\\R")) {
            String line = raw.strip().replaceFirst("^[-*•\\[\\] ]+", "").strip();
            if (line.isEmpty()) continue;
            if (line.length() > 300) line = line.substring(0, 300);
            // "close above 560" on a note about one stock means that stock
            String forParse = noteSymbol != null && startsWithMetricOrOp(line) ? noteSymbol + " " + line : line;
            Matcher mt = PRICE.matcher(forParse);
            if (mt.matches() && repo.knownSymbol(mt.group(1).toUpperCase(Locale.ROOT))) {
                Metric metric = metric(mt.group(2));
                String op = op(mt.group(3));
                double value = Double.parseDouble(mt.group(4).replace(",", "")) * unit(mt.group(5), metric);
                out.add(new NewCondition("PRICE", line, mt.group(1).toUpperCase(Locale.ROOT), metric.name(), op, value, false));
            } else {
                String l = line;
                boolean ticked = old.stream().anyMatch(o -> "MANUAL".equals(o.kind()) && o.checked() && o.text().equals(l));
                out.add(new NewCondition("MANUAL", line, null, null, null, null, ticked));
            }
        }
        return out;
    }

    private static boolean startsWithMetricOrOp(String line) {
        return line.matches("(?i)^(close|price|closes|high|low|change%?|chg%?|turnover|volume|vol|above|below|over|under)\\b.*")
                || line.matches("^(>=|<=|>|<|=>|=<).*");
    }

    private static Metric metric(String s) {
        if (s == null) return Metric.CLOSE;
        s = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("high")) return Metric.HIGH;
        if (s.startsWith("low")) return Metric.LOW;
        if (s.startsWith("ch")) return Metric.CHANGE;
        if (s.startsWith("turn")) return Metric.TURNOVER;
        if (s.startsWith("vol")) return Metric.VOLUME;
        return Metric.CLOSE;
    }

    private static String op(String s) {
        s = s.toLowerCase(Locale.ROOT);
        return s.startsWith(">") || s.equals("=>") || s.equals("above") || s.equals("over") || s.equals("at least") ? ">=" : "<=";
    }

    private static double unit(String u, Metric m) {
        if (u == null) return 1;
        u = u.toLowerCase(Locale.ROOT);
        if (u.startsWith("cr")) return 1e7;
        if (u.startsWith("l")) return 1e5;
        if (u.equals("k")) return 1e3;
        return 1; // %
    }

    // ------------------------------------------------------------------ dates and checks

    /** Today in Nepal. */
    public LocalDate today() {
        return LocalDate.now(ZoneId.of(props.market().timezone()));
    }

    /** The next trading day after today (market days and the holidays file). */
    public LocalDate nextSession(LocalDate today) {
        Set<DayOfWeek> days = props.market().days();
        Set<LocalDate> holidays;
        try {
            holidays = CrawlWindow.loadHolidays(props.market().holidays());
        } catch (IOException | RuntimeException e) {
            holidays = Set.of();
        }
        LocalDate d = today.plusDays(1);
        for (int k = 0; k < 30 && (!days.contains(d.getDayOfWeek()) || holidays.contains(d)); k++) d = d.plusDays(1);
        return d;
    }

    private Optional<String> validate(String symbol, String title) {
        if (blank(title)) return Optional.of("Give the note a title.");
        if (title.strip().length() > 200) return Optional.of("Keep the title under 200 characters.");
        String s = sym(symbol);
        if (s != null && !repo.knownSymbol(s)) return Optional.of("No stored prices for " + s + ".");
        return Optional.empty();
    }

    private static String sym(String s) {
        if (blank(s)) return null;
        String u = s.strip().toUpperCase(Locale.ROOT);
        int dash = u.indexOf(" — ");
        return dash > 0 ? u.substring(0, dash).strip() : u;
    }

    private static String trimBody(String b) {
        if (blank(b)) return null;
        b = b.strip();
        return b.length() > 20_000 ? b.substring(0, 20_000) : b;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
