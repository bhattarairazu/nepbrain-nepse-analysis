package com.nepse.topturnover.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.nepse.topturnover.SyntheticMarket;
import com.nepse.topturnover.analysis.FeatureCalculator.ListContext;

class FeatureCalculatorTest {
    private final SyntheticMarket market = new SyntheticMarket();

    private StockFeatures f(String symbol) {
        return FeatureCalculator.compute(market.series(symbol), ListContext.NONE);
    }

    @Test
    void detectsEachPlantedPattern() {
        assertTrue(f("BRK").has(Pattern.BASE_BREAKOUT), f("BRK").patterns().toString());
        assertTrue(f("CLX").has(Pattern.CLIMAX_TOP), f("CLX").patterns().toString());
        assertTrue(f("CAP").has(Pattern.CAPITULATION), f("CAP").patterns().toString());
        assertTrue(f("CHN").has(Pattern.CHURN));
        assertTrue(f("BLK").has(Pattern.CHURN));
        assertTrue(f("OPR").has(Pattern.CIRCUIT_RUN), f("OPR").patterns().toString());
    }

    @Test
    void plantedStocksDoNotTriggerTheWrongPatterns() {
        assertFalse(f("BRK").has(Pattern.CLIMAX_TOP));
        assertFalse(f("CAP").has(Pattern.BASE_BREAKOUT));
        assertFalse(f("CHN").has(Pattern.BASE_BREAKOUT));
        assertEquals(1, f("BRK").patterns().size());
    }

    @Test
    void classifiesTradeSize() {
        assertEquals(TradeSizeClass.BLOCK_DEAL, f("BLK").tradeSizeClass());
        assertEquals(TradeSizeClass.RETAIL_FRENZY, f("RTL").tradeSizeClass());
        assertEquals(TradeSizeClass.LARGER_TRADES, f("INST").tradeSizeClass());
        assertEquals(TradeSizeClass.NORMAL, f("CHN").tradeSizeClass());
    }

    @Test
    void computesActivityAndTrend() {
        StockFeatures brk = f("BRK");
        assertEquals(3.0, brk.rvol(), 0.4);
        assertTrue(brk.closeInRange() > 0.9);
        assertNotNull(brk.ema50());
        assertNull(brk.sma200(), "70 sessions is not enough for a 200-day average");
        assertNotNull(brk.rsi14());
        assertTrue(brk.closedUpNearHigh());
    }

    @Test
    void ideaScoringRewardsBreakoutAndDropsWarnings() {
        IdeaScorer.Idea idea = IdeaScorer.score(f("BRK"), 1);
        assertTrue(idea.score() >= 50, idea.toString());
        assertTrue(idea.reasons().stream().anyMatch(r -> r.text().contains("quiet base")));
        assertTrue(idea.stop() < f("BRK").close());
        assertTrue(IdeaScorer.dropReason(f("OPR")).contains("pattern 5"));
        assertTrue(IdeaScorer.dropReason(f("CLX")).contains("pattern 2"));
        assertNull(IdeaScorer.dropReason(f("BRK")));
    }

    @Test
    void indicatorsMatchHandCalculations() {
        double[] c = {1, 2, 3, 4, 5, 6};
        assertEquals(5.0, Indicators.sma(c, 3, 6));
        assertNull(Indicators.sma(c, 7, 6));
        assertEquals(100.0, Indicators.rsi(c, 3, 6)); // only gains
        assertEquals(5.0, Indicators.ema(new double[] {5, 5, 5, 5}, 2, 4));
    }
}
