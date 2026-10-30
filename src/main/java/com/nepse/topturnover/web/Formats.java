package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

/** Number and date formatting for templates, used as ${@fmt.cr(x)}. Uses Nepali lakh/crore conventions. */
@Component("fmt")
public class Formats {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** Rupees as "39.16 Cr" or "9.74 Arba". */
    public String money(Double rs) {
        if (rs == null) return "—";
        if (Math.abs(rs) >= 1e9) return String.format(Locale.ROOT, "%.2f Arba", rs / 1e9);
        if (Math.abs(rs) >= 1e7) return String.format(Locale.ROOT, "%.2f Cr", rs / 1e7);
        if (Math.abs(rs) >= 1e5) return String.format(Locale.ROOT, "%.2f L", rs / 1e5);
        return grouped(rs, 0);
    }

    /** Integer with Indian grouping: 12,34,567. */
    public String count(Double v) {
        return v == null ? "—" : grouped(v, 0);
    }

    public String count(Long v) {
        return v == null ? "—" : grouped(v.doubleValue(), 0);
    }

    public String count(Integer v) {
        return v == null ? "—" : grouped(v.doubleValue(), 0);
    }

    public String price(Double v) {
        return v == null ? "—" : grouped(v, 2);
    }

    /** Percent value already in percent units (1.5 -> "+1.50%"). */
    public String pct(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%+.2f%%", v);
    }

    /** Fraction as percent (0.42 -> "42%"). */
    public String share(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    public String ratio(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.1f×", v);
    }

    public String decimal(Double v, int places) {
        return v == null ? "—" : String.format(Locale.ROOT, "%." + places + "f", v);
    }

    /** CSS class for a signed number. */
    public String tone(Double v) {
        if (v == null || v == 0) return "";
        return v > 0 ? "pos" : "neg";
    }

    /** Inline background for heatmap cells: green for positive, red for negative, full colour at |v| >= full. */
    public String heat(Double v, double full) {
        if (v == null || v == 0 || full <= 0) return "";
        double a = Math.min(Math.abs(v) / full, 1) * 0.55;
        return String.format(Locale.ROOT, "background: rgba(%s, %.2f)", v > 0 ? "18, 134, 74" : "207, 51, 41", a);
    }

    /** Inline background for a 0..1 share (blue). */
    public String shade(Double v, double full) {
        if (v == null || v <= 0 || full <= 0) return "";
        return String.format(Locale.ROOT, "background: rgba(47, 91, 234, %.2f)", Math.min(v / full, 1) * 0.5);
    }

    public String day(LocalDate d) {
        return d == null ? "—" : DAY.format(d);
    }

    public String shortDay(LocalDate d) {
        return d == null ? "" : SHORT.format(d);
    }

    private static String grouped(double v, int decimals) {
        String sign = v < 0 ? "-" : "";
        String s = String.format(Locale.ROOT, "%." + decimals + "f", Math.abs(v));
        String intPart = decimals > 0 ? s.substring(0, s.indexOf('.')) : s;
        String frac = decimals > 0 ? s.substring(s.indexOf('.')) : "";
        if (intPart.length() <= 3) return sign + intPart + frac;
        String last3 = intPart.substring(intPart.length() - 3);
        String rest = intPart.substring(0, intPart.length() - 3);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) b.append(',');
            b.append(rest.charAt(i));
        }
        return sign + b + "," + last3 + frac;
    }
}
