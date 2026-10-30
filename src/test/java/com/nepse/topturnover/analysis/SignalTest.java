package com.nepse.topturnover.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignalTest {

    @Test
    void readsPriceAndVolumeTogether() {
        assertEquals(Signal.ACCUMULATION, Signal.classify(3.0, 2.0));
        assertEquals(Signal.DISTRIBUTION, Signal.classify(-2.5, 1.6));
        assertEquals(Signal.CHURN, Signal.classify(0.3, 2.4));
        assertEquals(Signal.WEAK_RALLY, Signal.classify(1.8, 0.7));
        assertEquals(Signal.QUIET_PULLBACK, Signal.classify(-1.2, 0.8));
        assertEquals(Signal.NEUTRAL, Signal.classify(1.2, 1.2));
        assertEquals(Signal.NEUTRAL, Signal.classify(0.2, 1.0));
    }

    @Test
    void withoutHistoryOnlyDirectionIsShown() {
        assertEquals(Signal.UP, Signal.classify(2.0, null));
        assertEquals(Signal.DOWN, Signal.classify(-2.0, null));
        assertEquals(Signal.NEUTRAL, Signal.classify(0.5, null));
        assertEquals(Signal.NEUTRAL, Signal.classify(null, 3.0));
    }
}
