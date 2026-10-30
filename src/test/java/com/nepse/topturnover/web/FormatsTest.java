package com.nepse.topturnover.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FormatsTest {
    private final Formats fmt = new Formats();

    @Test
    void usesLakhCroreGrouping() {
        assertEquals("3,66,409", fmt.count(366409.0));
        assertEquals("66,409", fmt.count(66409.0));
        assertEquals("12,34,567", fmt.count(1234567.0));
        assertEquals("1,23,45,678", fmt.count(12345678.0));
        assertEquals("1,240.36", fmt.price(1240.36));
        assertEquals("999", fmt.count(999.0));
    }

    @Test
    void formatsMoneyInNepaliUnits() {
        assertEquals("39.16 Cr", fmt.money(391_628_050.0));
        assertEquals("9.74 Arba", fmt.money(9_740_000_000.0));
        assertEquals("3.37 L", fmt.money(337_173.6));
        assertEquals("—", fmt.money(null));
    }

    @Test
    void formatsSignedValues() {
        assertEquals("+7.00%", fmt.pct(7.0));
        assertEquals("-0.54%", fmt.pct(-0.54));
        assertEquals("pos", fmt.tone(1.0));
        assertEquals("neg", fmt.tone(-1.0));
        assertEquals("2.3×", fmt.ratio(2.31));
    }
}
