package com.nepse.topturnover.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriceAdjusterTest {

    @Test
    void bonusExDateIsRemovedFromEarlierPrices() {
        // 20% bonus: price falls from 600 to 500 on the ex-date; the site reports an adjusted previous close of 500
        double[] c = {590, 600, 505, 510};
        double[] o = c.clone(), h = c.clone(), l = c.clone();
        double[] v = {1000, 1000, 1200, 1200};
        double[] prev = {Double.NaN, 590, 500, 505};
        assertEquals(1, PriceAdjuster.adjust(o, h, l, c, v, prev));
        double f = 500.0 / 600.0;
        assertEquals(590 * f, c[0], 1e-9);
        assertEquals(500.0, c[1], 1e-9);           // the day before the ex-date now equals the adjusted previous close
        assertEquals(505.0, c[2], 1e-9);           // ex-date onwards unchanged
        assertEquals(1000 / f, v[0], 1e-9);        // more shares after a bonus -> earlier volume scaled up
        assertEquals(1.0, c[2] / c[1], 0.011);     // the "crash" is gone: +1% move on the ex-date
    }

    @Test
    void twoExDatesCompound() {
        double[] c = {100, 100, 80, 80, 40};
        double[] o = c.clone(), h = c.clone(), l = c.clone();
        double[] prev = {Double.NaN, 100, 80, 80, 40};
        assertEquals(2, PriceAdjuster.adjust(o, h, l, c, null, prev));
        assertEquals(40.0, c[0], 1e-9);
        assertEquals(40.0, c[3], 1e-9);
    }

    @Test
    void roundingAndBadDataAreIgnored() {
        double[] c = {100, 100.2, 30};
        double[] prev = {Double.NaN, 99.8, 20}; // 0.4% difference = rounding; 20/100.2 = bad data
        assertEquals(0, PriceAdjuster.adjust(c.clone(), c.clone(), c.clone(), c, null, prev));
    }
}
