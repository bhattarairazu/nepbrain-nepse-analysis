package com.nepse.topturnover.crawler;

/** Parses numbers as printed on Nepali market sites ("1,23,456.50", "-0.56%", "-"). */
public final class Numbers {
    private Numbers() {}

    public static Double parse(String text) {
        if (text == null) return null;
        String t = text.replace(",", "").replace("%", "").replace(" ", "").trim();
        if (t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("n/a")) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer toInt(Double d) {
        return d == null ? null : (int) Math.round(d);
    }
}
