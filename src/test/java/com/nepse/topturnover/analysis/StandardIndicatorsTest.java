package com.nepse.topturnover.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.nepse.topturnover.analysis.StandardStrategyService.Ind;

class StandardIndicatorsTest {

    @Test
    void movingAveragesAndRsiMatchHandCalculations() {
        double[] c = {10, 11, 12, 13, 14, 15};
        double[] sma3 = Ind.sma(c, 3);
        assertTrue(Double.isNaN(sma3[1]));
        assertEquals(11.0, sma3[2], 1e-12);
        assertEquals(14.0, sma3[5], 1e-12);
        double[] ema3 = Ind.ema(c, 3);
        assertEquals(11.0, ema3[2], 1e-12);           // seeded with the SMA
        assertEquals(13 * 0.5 + 11 * 0.5, ema3[3], 1e-12);
        assertEquals(100.0, Ind.rsi(c, 2)[5], 1e-12);   // only gains
        double[] down = {15, 14, 13, 12, 11};
        assertEquals(0.0, Ind.rsi(down, 2)[4], 1e-12);  // only losses
    }

    @Test
    void indicatorsNeverUseFutureValues() {
        double[] a = new double[60], b = new double[60];
        for (int i = 0; i < 60; i++) { a[i] = 100 + Math.sin(i / 3.0) * 5; b[i] = a[i]; }
        for (int i = 40; i < 60; i++) b[i] = 50; // change only the "future" part
        Ind x = new Ind(a, a, a), y = new Ind(b, b, b);
        for (int i = 0; i < 40; i++) {
            assertEquals(x.rsi14[i], y.rsi14[i], 1e-12);
            assertEquals(x.ema20[i], y.ema20[i], 1e-12);
            assertEquals(x.lower[i], y.lower[i], 1e-12);
            assertEquals(x.macd[i], y.macd[i], 1e-12);
            assertEquals(x.signal[i], y.signal[i], 1e-12);
        }
    }

    @Test
    void rsiOversoldEntryFiresOnTheCrossOnly() {
        double[] c = new double[40];
        for (int i = 0; i < 30; i++) c[i] = 100 - i * 2;  // long fall -> RSI(14) far below 30
        for (int i = 30; i < 40; i++) c[i] = c[29] + (i - 29) * 6; // sharp rebound -> crosses back above 30
        Ind x = new Ind(c, c, c);
        int crosses = 0;
        for (int i = 15; i < 40; i++) if (x.rsi14[i - 1] < 30 && x.rsi14[i] >= 30) crosses++;
        assertEquals(1, crosses);
        assertFalse(x.rsi14[29] >= 30);
    }
}
