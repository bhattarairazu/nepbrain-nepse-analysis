package com.nepse.topturnover.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nepse.topturnover.analysis.SectorAnalysisService.Quadrant;

class SectorRulesTest {

    @Test
    void quadrantsCombineRelativeReturnAndTurnoverShare() {
        assertEquals(Quadrant.LEADING, Quadrant.of(2.0, 1.5));
        assertEquals(Quadrant.WEAKENING, Quadrant.of(2.0, -1.0));
        assertEquals(Quadrant.IMPROVING, Quadrant.of(-1.0, 2.0));
        assertEquals(Quadrant.LAGGING, Quadrant.of(-1.0, -0.5));
        assertEquals(Quadrant.LEADING, Quadrant.of(0.5, null));
        assertEquals(Quadrant.LAGGING, Quadrant.of(-0.5, null));
    }

    @Test
    void percentilesRankFromZeroToOneWithTiesSharingTheMiddle() {
        record V(String k, double v) {}
        Map<String, Double> p = SectorAnalysisService.percentiles(
                List.of(new V("a", 1), new V("b", 5), new V("c", 5), new V("d", 9)), V::v, V::k);
        assertEquals(0.0, p.get("a"));
        assertEquals(0.5, p.get("b"));
        assertEquals(0.5, p.get("c"));
        assertEquals(1.0, p.get("d"));
    }
}
