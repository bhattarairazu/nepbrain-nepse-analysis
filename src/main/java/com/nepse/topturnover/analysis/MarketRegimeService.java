package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.nepse.topturnover.analysis.BacktestService.Market;
import com.nepse.topturnover.analysis.BacktestService.Series;
import com.nepse.topturnover.analysis.StandardStrategyService.Ind;

/**
 * Is a bull run under way? A daily checklist from the stored prices: the equal-weight market against its 50- and
 * 200-session averages, breadth (share of stocks above their own averages), new 52-week highs against lows, the
 * advance/decline balance, turnover expansion and how many sectors are rising, plus a ranking of sectors by strength.
 * Macro conditions (interest rates, liquidity, NRB policy) are listed for the user to check by hand.
 */
@Service
public class MarketRegimeService {
    static final int LONG = 200, MID = 50, YEAR = 250, CHART = 250;
    static final double MIN_TURNOVER = 1_000_000;

    public enum Status { PASS, WATCH, FAIL }

    public record Check(String name, Status status, String value, String why) {}

    public record SectorRow(String sector, int stocks, double ret20Pct, double ret60Pct, Double ret120Pct, double above50Pct, int newHighs,
                            int rank, boolean leader) {}

    public record Chart(List<String> dates, List<Double> index, List<Double> ma50, List<Double> ma200, List<Double> breadth50,
                        List<Double> turnover20) {}

    public record Result(LocalDate lastDay, int sessions, double index, double ma50, Double ma200, double above50Pct, Double above200Pct,
                         int newHighs, int newLows, int highLowUniverse, int advDecl20, double turnover20, double turnover60, Double turnover120,
                         List<Check> checks, int passes, String verdict, String verdictTone, String verdictText, List<SectorRow> sectors,
                         Chart chart) {}

    private final BacktestService backtest;
    private Result cached;
    private LocalDate cachedFor;

    public MarketRegimeService(BacktestService backtest) {
        this.backtest = backtest;
    }

    public synchronized Optional<Result> run() {
        Market m = backtest.data();
        if (m == null || m.dates.length < MID + 20) return Optional.empty();
        LocalDate last = m.dates[m.dates.length - 1];
        if (cached != null && last.equals(cachedFor)) return Optional.of(cached);
        int T = m.dates.length, lt = T - 1;

        double[] idx = m.index, ema50 = Ind.ema(idx, MID), sma200 = T >= LONG ? Ind.sma(idx, LONG) : null;
        int[] above50 = new int[T], counted50 = new int[T], above200 = new int[T], counted200 = new int[T], adv = new int[T], dec = new int[T];
        double[] turnover = new double[T];
        int newHighs = 0, newLows = 0, hlUniverse = 0;
        Map<String, List<Series>> bySector = new HashMap<>();

        for (Series s : m.series.values()) {
            if (!Sectors.isEquity(s.sector)) continue;
            int n = s.size();
            for (int i = 0; i < n; i++) {
                int g = s.globalIndex[i];
                turnover[g] += Double.isNaN(s.turnover[i]) ? 0 : s.turnover[i];
                if (s.change[i] > 0) adv[g]++;
                else if (s.change[i] < 0) dec[g]++;
            }
            if (n < MID + 5) continue;
            bySector.computeIfAbsent(s.sector, k -> new ArrayList<>()).add(s);
            double[] e50 = Ind.ema(s.close, MID), m200 = n >= LONG ? Ind.sma(s.close, LONG) : null;
            for (int i = MID; i < n; i++) {
                if (!(s.turnover[i] >= MIN_TURNOVER) || Double.isNaN(e50[i])) continue;
                int g = s.globalIndex[i];
                counted50[g]++;
                if (s.close[i] > e50[i]) above50[g]++;
                if (m200 != null && !Double.isNaN(m200[i])) {
                    counted200[g]++;
                    if (s.close[i] > m200[i]) above200[g]++;
                }
            }
            // 52-week (250-session) highs and lows on the latest session, for stocks with a year of history that traded it
            int li = n - 1;
            if (s.globalIndex[li] == lt && n > YEAR) {
                hlUniverse++;
                double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
                for (int k = li - YEAR; k < li; k++) { hi = Math.max(hi, s.high[k]); lo = Math.min(lo, s.low[k]); }
                if (s.high[li] >= hi) newHighs++;
                if (s.low[li] <= lo) newLows++;
            }
        }

        double a50 = counted50[lt] > 0 ? 100.0 * above50[lt] / counted50[lt] : 0;
        Double a200 = counted200[lt] > 20 ? 100.0 * above200[lt] / counted200[lt] : null;
        int ad20 = 0;
        for (int g = Math.max(0, T - 20); g < T; g++) ad20 += adv[g] - dec[g];
        double to20 = avg(turnover, T - 20, T), to60 = avg(turnover, T - 60, T);
        Double to120 = T >= 120 ? avg(turnover, T - 120, T) : null;

        List<SectorRow> sectors = sectors(m, bySector, lt);
        long risingSectors = sectors.stream().filter(x -> x.ret60Pct() > 0).count();

        // ---- the checklist
        List<Check> checks = new ArrayList<>();
        double ix = idx[lt];
        if (sma200 != null && !Double.isNaN(sma200[lt])) {
            double d = (ix / sma200[lt] - 1) * 100;
            checks.add(new Check("Market above its 200-session average", d > 2 ? Status.PASS : d > -2 ? Status.WATCH : Status.FAIL,
                    String.format(Locale.ROOT, "%+.1f%% from it", d), "the long-term trend: bull runs stay above it"));
            double slope = (sma200[lt] / sma200[Math.max(LONG - 1, lt - 20)] - 1) * 100;
            checks.add(new Check("200-session average rising", slope > 0.5 ? Status.PASS : slope > -0.5 ? Status.WATCH : Status.FAIL,
                    String.format(Locale.ROOT, "%+.1f%% in 20 sessions", slope), "the long-term trend has turned up, not just bounced"));
            checks.add(new Check("50-session average above the 200-session", ema50[lt] > sma200[lt] ? Status.PASS : Status.FAIL,
                    String.format(Locale.ROOT, "%.0f vs %.0f", ema50[lt], sma200[lt]), "the medium trend leads the long one (the \"golden cross\")"));
        } else {
            checks.add(new Check("Market above its 200-session average", Status.WATCH, "needs 200 sessions", "not enough history yet"));
        }
        checks.add(new Check("Market above its 50-session average", ix > ema50[lt] ? Status.PASS : Status.FAIL,
                String.format(Locale.ROOT, "%+.1f%% from it", (ix / ema50[lt] - 1) * 100), "the medium-term trend"));
        checks.add(new Check("Breadth: stocks above their 50-session average", a50 >= 60 ? Status.PASS : a50 >= 40 ? Status.WATCH : Status.FAIL,
                String.format(Locale.ROOT, "%.0f%% of %d", a50, counted50[lt]), "a real bull run lifts most stocks, not a handful"));
        if (a200 != null) checks.add(new Check("Breadth: stocks above their 200-session average", a200 >= 50 ? Status.PASS : a200 >= 35 ? Status.WATCH : Status.FAIL,
                String.format(Locale.ROOT, "%.0f%% of %d", a200, counted200[lt]), "long-term participation"));
        if (hlUniverse > 20) checks.add(new Check("New 52-week highs beat new lows", newHighs > newLows && newHighs >= hlUniverse * 0.03 ? Status.PASS
                : newHighs >= newLows ? Status.WATCH : Status.FAIL, newHighs + " highs, " + newLows + " lows", "leaders breaking out to new highs"));
        checks.add(new Check("More stocks rising than falling (20 sessions)", ad20 > 0 ? Status.PASS : ad20 > -100 ? Status.WATCH : Status.FAIL,
                String.format(Locale.ROOT, "%+d advances − declines", ad20), "the advance/decline balance"));
        double exp = to120 != null ? to20 / to120 : to20 / to60;
        checks.add(new Check("Turnover expanding", exp >= 1.2 ? Status.PASS : exp >= 1.0 ? Status.WATCH : Status.FAIL,
                String.format(Locale.ROOT, "20-session average %.1f× the %d-session", exp, to120 != null ? 120 : 60), "new money coming into the market"));
        checks.add(new Check("Most sectors rising (60 sessions)", risingSectors * 2 > sectors.size() ? Status.PASS
                : risingSectors * 3 > sectors.size() ? Status.WATCH : Status.FAIL, risingSectors + " of " + sectors.size() + " sectors up", "broad, not one-sector, strength"));

        int passes = (int) checks.stream().filter(c -> c.status() == Status.PASS).count();
        int fails = (int) checks.stream().filter(c -> c.status() == Status.FAIL).count();
        String verdict, tone, text;
        if (passes >= checks.size() - 1) { verdict = "Bull run under way"; tone = "good"; text = "Nearly every sign agrees: trend, breadth, new highs and turnover. Leaders usually keep leading at this stage."; }
        else if (passes * 2 >= checks.size()) { verdict = "Early signs"; tone = "warn"; text = "More than half the signs are positive. Early bull runs look like this, but so do strong bounces: build positions in stages."; }
        else if (fails * 2 >= checks.size()) { verdict = "Not yet: downtrend"; tone = "bad"; text = "Most signs are negative. Long-term buying now means catching a falling market; wait for breadth and the trend to turn."; }
        else { verdict = "Mixed"; tone = ""; text = "Some signs have turned, others not. Watch breadth and turnover: they usually turn before the 200-session trend."; }

        // ---- chart (last 250 sessions)
        int from = Math.max(0, T - CHART);
        List<String> dates = new ArrayList<>();
        List<Double> ci = new ArrayList<>(), c50 = new ArrayList<>(), c200 = new ArrayList<>(), cb = new ArrayList<>(), ct = new ArrayList<>();
        for (int g = from; g < T; g++) {
            dates.add(m.dates[g].toString());
            ci.add(round(idx[g]));
            c50.add(Double.isNaN(ema50[g]) ? null : round(ema50[g]));
            c200.add(sma200 == null || Double.isNaN(sma200[g]) ? null : round(sma200[g]));
            cb.add(counted50[g] > 0 ? round(100.0 * above50[g] / counted50[g]) : null);
            ct.add(g >= 19 ? round(avg(turnover, g - 19, g + 1) / 1e7) : null);
        }

        cached = new Result(last, T, ix, ema50[lt], sma200 == null || Double.isNaN(sma200[lt]) ? null : sma200[lt], a50, a200, newHighs, newLows, hlUniverse,
                ad20, to20, to60, to120, checks, passes, verdict, tone, text, sectors, new Chart(dates, ci, c50, c200, cb, ct));
        cachedFor = last;
        return Optional.of(cached);
    }

    /** Sectors ranked by 60-session return (equal weight of their stocks); the top three are the leaders. */
    private static List<SectorRow> sectors(Market m, Map<String, List<Series>> bySector, int lt) {
        List<SectorRow> rows = new ArrayList<>();
        bySector.forEach((sector, list) -> {
            double r20 = 0, r60 = 0, r120 = 0;
            int n20 = 0, n60 = 0, n120 = 0, above = 0, counted = 0, highs = 0;
            for (Series s : list) {
                int i = s.size() - 1;
                if (s.globalIndex[i] < lt - 5) continue; // not trading lately
                if (i >= 20 && s.close[i - 20] > 0) { r20 += s.close[i] / s.close[i - 20] - 1; n20++; }
                if (i >= 60 && s.close[i - 60] > 0) { r60 += s.close[i] / s.close[i - 60] - 1; n60++; }
                if (i >= 120 && s.close[i - 120] > 0) { r120 += s.close[i] / s.close[i - 120] - 1; n120++; }
                double[] e = Ind.ema(s.close, MID);
                if (!Double.isNaN(e[i])) { counted++; if (s.close[i] > e[i]) above++; }
                if (i > YEAR) {
                    double hi = Double.NEGATIVE_INFINITY;
                    for (int k = i - YEAR; k < i; k++) hi = Math.max(hi, s.high[k]);
                    if (s.high[i] >= hi) highs++;
                }
            }
            if (n60 < 3) return;
            rows.add(new SectorRow(sector, n60, n20 > 0 ? r20 / n20 * 100 : 0, r60 / n60 * 100, n120 > 0 ? (Double) (r120 / n120 * 100) : null,
                    counted > 0 ? 100.0 * above / counted : 0, highs, 0, false));
        });
        rows.sort(Comparator.comparingDouble(SectorRow::ret60Pct).reversed());
        List<SectorRow> out = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            SectorRow r = rows.get(k);
            out.add(new SectorRow(r.sector(), r.stocks(), r.ret20Pct(), r.ret60Pct(), r.ret120Pct(), r.above50Pct(), r.newHighs(), k + 1, k < 3));
        }
        return out;
    }

    private static double avg(double[] a, int from, int to) {
        double s = 0;
        int n = 0;
        for (int k = Math.max(0, from); k < to; k++) { s += a[k]; n++; }
        return n > 0 ? s / n : 0;
    }

    private static Double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
