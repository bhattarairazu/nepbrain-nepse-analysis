package com.nepse.topturnover;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.nepse.topturnover.analysis.StockSeries;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/**
 * Deterministic fake market for tests and screenshots. 40 ordinary random-walk stocks plus one stock
 * per pattern, planted on the last session:
 * BRK base breakout, CLX climax after a run-up, CAP capitulation, CHN churn, BLK block deal,
 * OPR upper-circuit run on fading volume, RTL retail frenzy, INST larger trades building.
 */
public final class SyntheticMarket {
    public static final int DAYS = 70;

    private final List<LocalDate> dates = new ArrayList<>();
    private final Map<String, List<StockQuote>> bySymbol = new LinkedHashMap<>();
    private final Random rnd = new Random(7);

    private static final String[][] NORMAL = {
            {"Bank Limited", "BK"}, {"Hydropower Limited", "HP"}, {"Laghubitta Bittiya Sanstha Limited", "MF"},
            {"Life Insurance Company Limited", "LI"}, {"Finance Limited", "FN"}, {"Bikas Bank Limited", "DB"},
            {"Cement Industries Limited", "MP"}, {"Hotels Limited", "HT"}};
    private static final String[] WORDS = {"Alpha", "Bravo", "Coral", "Delta", "Everest"};

    public SyntheticMarket() {
        LocalDate d = LocalDate.of(2026, 6, 1);
        while (dates.size() < DAYS) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(d);
            d = d.plusDays(1);
        }
        for (String[] kind : NORMAL) {
            for (String w : WORDS) normal(w.substring(0, 2).toUpperCase() + kind[1], w + " " + kind[0]);
        }
        breakout();
        climax();
        capitulation();
        churn("CHN", "Churn Bank Limited", 1.0);
        churn("BLK", "Block Life Insurance Company Limited", 0.5);
        operator();
        retail();
        institution();
    }

    public List<LocalDate> dates() { return dates; }

    public List<MarketSnapshot> snapshots() {
        List<MarketSnapshot> out = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            List<StockQuote> q = new ArrayList<>();
            for (List<StockQuote> s : bySymbol.values()) q.add(s.get(i));
            out.add(new MarketSnapshot(dates.get(i), "synthetic", q));
        }
        return out;
    }

    public StockSeries series(String symbol) {
        List<StockQuote> q = bySymbol.get(symbol);
        int n = q.size();
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n],
                t = new double[n], tr = new double[n], ch = new double[n];
        for (int i = 0; i < n; i++) {
            StockQuote x = q.get(i);
            o[i] = x.open(); h[i] = x.high(); l[i] = x.low(); c[i] = x.close(); v[i] = x.volume();
            t[i] = x.turnover(); tr[i] = x.trades(); ch[i] = x.changePct();
        }
        return new StockSeries(symbol, q.get(0).name(), null, dates.toArray(LocalDate[]::new), o, h, l, c, v, t, tr, ch);
    }

    // ------------------------------------------------------------------ builders

    /** Adds one session; open/high/low default around the close. tradesFactor scales the usual ~100 shares per trade. */
    private void add(String sym, String name, double prev, double close, double volume, double tradesFactor,
                     Double open, Double high, Double low) {
        double o = open != null ? open : prev * (1 + (rnd.nextDouble() - 0.5) * 0.004);
        double hi = high != null ? high : Math.max(o, close) * (1 + rnd.nextDouble() * 0.006);
        double lo = low != null ? low : Math.min(o, close) * (1 - rnd.nextDouble() * 0.006);
        int trades = (int) Math.max(1, Math.round(volume / 100 * tradesFactor));
        bySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(new StockQuote(sym, name,
                r2(o), r2(hi), r2(lo), r2(close), r2(close), r2(prev), r2(close),
                Math.round(volume) * 1.0, r2(close * Math.round(volume)), trades, r2((close / prev - 1) * 100)));
    }

    private double noisyVol(double base) {
        return base * (0.8 + rnd.nextDouble() * 0.4);
    }

    private void normal(String sym, String name) {
        double p = 200 + rnd.nextInt(600), base = 20_000 + rnd.nextInt(80_000);
        for (int i = 0; i < DAYS; i++) {
            double next = p * (1 + (rnd.nextDouble() - 0.5) * 0.02);
            add(sym, name, p, next, noisyVol(base), 1, null, null, null);
            p = next;
        }
    }

    private void breakout() {
        double p = 500, base = 60_000;
        for (int i = 0; i < DAYS - 1; i++) {
            double next = 500 * (1 + (rnd.nextDouble() - 0.5) * 0.02);
            add("BRK", "Breakout Hydropower Limited", p, next, noisyVol(base), 1, null, null, null);
            p = next;
        }
        add("BRK", "Breakout Hydropower Limited", p, 530, base * 3, 1, 506.0, 532.0, 504.0);
    }

    private void climax() {
        double p = 200, base = 60_000;
        for (int i = 0; i < DAYS - 1; i++) {
            double next = i < 9 ? 200 * (1 + (rnd.nextDouble() - 0.5) * 0.01) : p * 1.0102;
            add("CLX", "Climax Cement Industries Limited", p, next, noisyVol(base), 1, null, null, null);
            p = next;
        }
        add("CLX", "Climax Cement Industries Limited", p, p * 0.978, base * 3, 1, p, p * 1.055, p * 0.972);
    }

    private void capitulation() {
        double p = 300, base = 50_000;
        for (int i = 0; i < DAYS - 1; i++) {
            double next = i < 9 ? 300 * (1 + (rnd.nextDouble() - 0.5) * 0.01) : p * 0.9917;
            add("CAP", "Downfall Finance Limited", p, next, noisyVol(base), 1, null, null, null);
            p = next;
        }
        double close = p * 1.012;
        add("CAP", "Downfall Finance Limited", p, close, base * 3.5, 1, p * 0.99, close * 1.005, p * 0.92);
    }

    private void churn(String sym, String name, double tradesFactor) {
        double p = 400, base = 50_000;
        for (int i = 0; i < DAYS - 1; i++) {
            double next = p * (1 + (rnd.nextDouble() - 0.5) * 0.02);
            add(sym, name, p, next, noisyVol(base), 1, null, null, null);
            p = next;
        }
        add(sym, name, p, p * 1.002, base * (tradesFactor < 1 ? 2.2 : 2.5), tradesFactor < 1 ? 0.22 : 1, null, null, null);
    }

    private void operator() {
        double p = 150, base = 20_000;
        double[] fade = {90_000, 70_000, 50_000, 30_000};
        for (int i = 0; i < DAYS; i++) {
            int k = i - (DAYS - 4);
            double next = k >= 0 ? p * 1.099 : p * (1 + (rnd.nextDouble() - 0.5) * 0.02);
            add("OPR", "Operator Power Company Limited", p, next, k >= 0 ? fade[k] : noisyVol(base), 1, null, null, null);
            p = next;
        }
    }

    private void retail() {
        double p = 900, base = 30_000;
        for (int i = 0; i < DAYS - 1; i++) {
            double next = p * (1 + (rnd.nextDouble() - 0.5) * 0.02);
            add("RTL", "Retail Laghubitta Bittiya Sanstha Limited", p, next, noisyVol(base), 1, null, null, null);
            p = next;
        }
        add("RTL", "Retail Laghubitta Bittiya Sanstha Limited", p, p * 1.03, base * 2.5, 2.0, null, null, null);
    }

    private void institution() {
        double p = 600, base = 80_000;
        for (int i = 0; i < DAYS; i++) {
            double next = p * (1 + (rnd.nextDouble() - 0.5) * 0.02);
            add("INST", "Institution Hotels Limited", p, next, noisyVol(base), i >= DAYS - 5 ? 0.55 : 1, null, null, null);
            p = next;
        }
    }

    private static double r2(double x) {
        return Math.round(x * 100) / 100.0;
    }
}
