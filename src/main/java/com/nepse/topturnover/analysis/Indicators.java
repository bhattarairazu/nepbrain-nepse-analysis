package com.nepse.topturnover.analysis;

/** Plain moving-average and RSI calculations over a price array (oldest first). */
public final class Indicators {
    private Indicators() {}

    /** EMA of the first `end` values (exclusive), seeded with the SMA of the first n. Null if too short. */
    public static Double ema(double[] c, int n, int end) {
        if (end < n) return null;
        double k = 2.0 / (n + 1);
        double e = 0;
        for (int i = 0; i < n; i++) e += c[i];
        e /= n;
        for (int i = n; i < end; i++) e = c[i] * k + e * (1 - k);
        return e;
    }

    public static Double sma(double[] c, int n, int end) {
        if (end < n) return null;
        double s = 0;
        for (int i = end - n; i < end; i++) s += c[i];
        return s / n;
    }

    /** Wilder's RSI over the first `end` values. Null if fewer than n+1 values. */
    public static Double rsi(double[] c, int n, int end) {
        if (end < n + 1) return null;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = c[i] - c[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= n;
        loss /= n;
        for (int i = n + 1; i < end; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
        }
        if (loss == 0) return 100.0;
        return 100 - 100 / (1 + gain / loss);
    }

    /** Mean of a[from..to) ignoring NaN; null when fewer than minCount values. */
    public static Double mean(double[] a, int from, int to, int minCount) {
        double s = 0;
        int n = 0;
        for (int i = Math.max(0, from); i < to; i++) {
            if (!Double.isNaN(a[i])) { s += a[i]; n++; }
        }
        return n >= minCount && n > 0 ? s / n : null;
    }

    public static double max(double[] a, int from, int to) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, from); i < to; i++) if (!Double.isNaN(a[i])) m = Math.max(m, a[i]);
        return m;
    }

    public static double min(double[] a, int from, int to) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, from); i < to; i++) if (!Double.isNaN(a[i])) m = Math.min(m, a[i]);
        return m;
    }
}
