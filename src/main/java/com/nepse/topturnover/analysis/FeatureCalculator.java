package com.nepse.topturnover.analysis;

import static com.nepse.topturnover.analysis.Indicators.max;
import static com.nepse.topturnover.analysis.Indicators.mean;
import static com.nepse.topturnover.analysis.Indicators.min;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nepse.topturnover.analysis.StockFeatures.PatternHit;

/** Turns a stock's stored history into features, the five patterns and a trade-size class. Pure: no database. */
public final class FeatureCalculator {
    private FeatureCalculator() {}

    /** List membership of the stock on the analysis date, supplied by the caller. */
    public record ListContext(Integer turnoverRank, Integer volumeRank, int turnoverAppearances10) {
        public static final ListContext NONE = new ListContext(null, null, 0);
    }

    public static StockFeatures compute(StockSeries s, ListContext lists) {
        int L = s.last();
        double[] c = s.close(), v = s.volume(), h = s.high(), l = s.low(), o = s.open();
        double close = c[L], change = s.changePct()[L];

        // ---- activity
        Double avgVol = mean(v, L - Rules.AVG_SESSIONS, L, Rules.MIN_HISTORY);
        Double rvol = avgVol != null && avgVol > 0 && !Double.isNaN(v[L]) ? v[L] / avgVol : null;
        double range = h[L] - l[L];
        boolean hasRange = !Double.isNaN(range) && range > 0;
        Double cir = hasRange ? (close - l[L]) / range : null;
        double bodyTop = Double.isNaN(o[L]) ? close : Math.max(o[L], close);
        double bodyBottom = Double.isNaN(o[L]) ? close : Math.min(o[L], close);
        Double upperWick = hasRange ? (h[L] - bodyTop) / range : null;
        Double lowerWick = hasRange ? (bodyBottom - l[L]) / range : null;

        // ---- trade size
        double[] ats = new double[s.size()];
        for (int i = 0; i <= L; i++) {
            double t = s.trades()[i];
            ats[i] = !Double.isNaN(t) && t > 0 && !Double.isNaN(s.turnover()[i]) ? s.turnover()[i] / t : Double.NaN;
        }
        Double atsNormal = mean(ats, L - Rules.AVG_SESSIONS, L, Rules.MIN_HISTORY);
        Double ats5 = mean(ats, L - 4, L + 1, 3);
        Double atsToday = Double.isNaN(ats[L]) ? null : ats[L];
        Double tradesNormal = mean(s.trades(), L - Rules.AVG_SESSIONS, L, Rules.MIN_HISTORY);
        Double tradesRatio = tradesNormal != null && tradesNormal > 0 && !Double.isNaN(s.trades()[L]) ? s.trades()[L] / tradesNormal : null;
        Double atsTrend = atsNormal != null && atsNormal > 0 && ats5 != null ? ats5 / atsNormal : null;
        TradeSizeClass tsc = tradeSizeClass(atsToday, atsNormal, atsTrend, tradesRatio);

        // ---- structure
        int prior = L; // sessions before today
        Integer baseSessions = null;
        Double baseHigh = null, baseLow = null, baseRange = null;
        if (prior >= Rules.BASE_MIN_SESSIONS) {
            int n = Math.min(Rules.BASE_MAX_SESSIONS, prior);
            baseSessions = n;
            baseHigh = max(h, L - n, L);
            baseLow = min(l, L - n, L);
            baseRange = baseLow > 0 ? baseHigh / baseLow - 1 : null;
        }
        Double runUp = null, drawdown = null;
        if (prior >= Rules.TREND_MIN_SESSIONS) {
            double lo = min(c, L - Rules.TREND_LOOKBACK, L), hi = max(c, L - Rules.TREND_LOOKBACK, L);
            runUp = lo > 0 ? close / lo - 1 : null;
            drawdown = hi > 0 ? close / hi - 1 : null;
        }
        List<Integer> circuitDays = new ArrayList<>();
        for (int i = Math.max(0, L - Rules.CIRCUIT_WINDOW + 1); i <= L; i++) {
            if (!Double.isNaN(s.changePct()[i]) && s.changePct()[i] >= Rules.UPPER_CIRCUIT_PCT) circuitDays.add(i);
        }

        // ---- trend
        Double ema20 = Indicators.ema(c, 20, L + 1);
        Double ema50 = Indicators.ema(c, 50, L + 1);
        double low10v = min(l, L - 9, L + 1);
        Double low10 = Double.isInfinite(low10v) ? null : low10v;
        Double ema50Before = Indicators.ema(c, 50, L - 4);
        Boolean rising = ema50 != null && ema50Before != null ? ema50 > ema50Before : null;
        Double sma200 = Indicators.sma(c, 200, L + 1);
        Double rsi = Indicators.rsi(c, 14, L + 1);

        // ---- patterns
        List<PatternHit> hits = new ArrayList<>();
        if (baseRange != null && rvol != null && baseRange <= Rules.BASE_MAX_RANGE
                && close > baseHigh && rvol >= Rules.BREAKOUT_RVOL && change > 0) {
            hits.add(new PatternHit(Pattern.BASE_BREAKOUT, String.format(Locale.ROOT,
                    "%d-session base, range %.1f%%; closed %.1f%% above the base high %.2f on %.1f× volume",
                    baseSessions, baseRange * 100, (close / baseHigh - 1) * 100, baseHigh, rvol)));
        }
        if (runUp != null && runUp >= Rules.RUN_UP) {
            boolean recordVolume = !Double.isNaN(v[L]) && v[L] >= max(v, L - Rules.TREND_LOOKBACK, L);
            boolean heavy = (rvol != null && rvol >= Rules.CLIMAX_RVOL) || recordVolume;
            boolean rejection = (upperWick != null && upperWick >= Rules.LONG_WICK) || (cir != null && cir <= Rules.CLOSE_NEAR_LOW);
            if (heavy && rejection) {
                hits.add(new PatternHit(Pattern.CLIMAX_TOP, String.format(Locale.ROOT,
                        "Up %.0f%% from its 60-session low; %s; %s",
                        runUp * 100, recordVolume ? "highest volume in 60 sessions" : String.format(Locale.ROOT, "%.1f× volume", rvol),
                        upperWick != null && upperWick >= Rules.LONG_WICK
                                ? String.format(Locale.ROOT, "upper wick %.0f%% of the range", upperWick * 100)
                                : String.format(Locale.ROOT, "closed in the bottom %.0f%% of the range", cir * 100))));
            }
        }
        if (drawdown != null && drawdown <= Rules.LONG_FALL && rvol != null && rvol >= Rules.CAPITULATION_RVOL
                && ((lowerWick != null && lowerWick >= Rules.LONG_WICK) || (cir != null && cir >= Rules.CLOSE_NEAR_HIGH))) {
            hits.add(new PatternHit(Pattern.CAPITULATION, String.format(Locale.ROOT,
                    "Down %.0f%% from its 60-session high; %.1f× volume; %s",
                    -drawdown * 100, rvol,
                    lowerWick != null && lowerWick >= Rules.LONG_WICK
                            ? String.format(Locale.ROOT, "lower wick %.0f%% of the range", lowerWick * 100)
                            : String.format(Locale.ROOT, "closed in the top %.0f%% of the range", (1 - cir) * 100))));
        }
        if (rvol != null && rvol >= Rules.CHURN_RVOL && !Double.isNaN(change) && Math.abs(change) < Rules.CHURN_MAX_MOVE) {
            hits.add(new PatternHit(Pattern.CHURN, String.format(Locale.ROOT, "%.1f× volume but the price moved only %+.2f%%%s",
                    rvol, change, tsc == TradeSizeClass.BLOCK_DEAL ? "; trade size suggests a block deal" : "")));
        }
        if (circuitDays.size() >= Rules.CIRCUIT_MIN_DAYS) {
            double first = v[circuitDays.get(0)], last = v[circuitDays.get(circuitDays.size() - 1)];
            if (!Double.isNaN(first) && !Double.isNaN(last) && first > 0 && last <= first * Rules.CIRCUIT_VOLUME_FADE) {
                hits.add(new PatternHit(Pattern.CIRCUIT_RUN, String.format(Locale.ROOT,
                        "%d upper-circuit days in the last %d sessions; volume fell %.0f%% from the first to the last",
                        circuitDays.size(), Rules.CIRCUIT_WINDOW, (1 - last / first) * 100)));
            }
        }
        boolean forming = baseRange != null && baseRange <= Rules.BASE_MAX_RANGE && close <= baseHigh
                && close >= baseHigh * (1 - Rules.BASE_FORMING_GAP);

        return new StockFeatures(s.symbol(), s.name(), s.sector(), s.dates()[L], s.size(),
                nn(close), nn(h[L]), low10, nn(change), nn(v[L]), nn(s.turnover()[L]), nn(s.trades()[L]),
                rvol, cir, upperWick, lowerWick,
                atsToday, atsNormal, atsTrend, tradesRatio, tsc,
                baseSessions, baseHigh, baseLow, baseRange, runUp, drawdown, circuitDays.size(),
                ema20, ema50, rising, sma200, rsi,
                lists.turnoverRank(), lists.volumeRank(), lists.turnoverAppearances10(),
                List.copyOf(hits), forming);
    }

    static TradeSizeClass tradeSizeClass(Double today, Double normal, Double trend, Double tradesRatio) {
        if (normal == null || normal <= 0 || today == null) return TradeSizeClass.UNKNOWN;
        if (today >= normal * Rules.BLOCK_DEAL_ATS) return TradeSizeClass.BLOCK_DEAL;
        if (tradesRatio != null && tradesRatio >= Rules.FRENZY_TRADES && today <= normal * Rules.FRENZY_MAX_ATS) {
            return TradeSizeClass.RETAIL_FRENZY;
        }
        if (trend != null && trend >= Rules.ATS_RISING) return TradeSizeClass.LARGER_TRADES;
        return TradeSizeClass.NORMAL;
    }

    private static Double nn(double d) {
        return Double.isNaN(d) ? null : d;
    }
}
