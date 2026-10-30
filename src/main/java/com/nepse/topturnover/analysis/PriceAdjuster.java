package com.nepse.topturnover.analysis;

/**
 * Backward adjustment for bonus shares, right shares and splits.
 *
 * On an ex-date the site reports an adjusted "previous close" that is lower than the stock's actual previous close.
 * factor = reported previous close / actual previous close. Every earlier price is multiplied by the product of the
 * factors of all later ex-dates, and every earlier volume divided by it, so returns across ex-dates are real returns
 * instead of an artificial 10-30% "crash". Arrays are oldest first and are changed in place.
 */
public final class PriceAdjuster {
    /** Smaller differences are rounding, not a corporate action. */
    static final double MIN_ADJUSTMENT = 0.995;
    /** Anything below this is treated as bad data, not a corporate action. */
    static final double MAX_ADJUSTMENT = 0.30;

    private PriceAdjuster() {}

    /** @return the number of ex-dates adjusted */
    public static int adjust(double[] open, double[] high, double[] low, double[] close, double[] volume, double[] prevClose) {
        int n = close.length, events = 0;
        double[] factor = new double[n];
        for (int i = 0; i < n; i++) {
            factor[i] = 1;
            if (i == 0 || Double.isNaN(prevClose[i]) || Double.isNaN(close[i - 1]) || close[i - 1] <= 0) continue;
            double f = prevClose[i] / close[i - 1];
            if (f < MIN_ADJUSTMENT && f > MAX_ADJUSTMENT) {
                factor[i] = f;
                events++;
            }
        }
        if (events == 0) return 0;
        double mult = 1;
        for (int k = n - 1; k >= 0; k--) {
            if (k + 1 < n) mult *= factor[k + 1];
            if (mult == 1) continue;
            open[k] *= mult;
            high[k] *= mult;
            low[k] *= mult;
            close[k] *= mult;
            if (volume != null) volume[k] /= mult;
        }
        return events;
    }
}
