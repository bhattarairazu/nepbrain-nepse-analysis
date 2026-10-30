package com.nepse.topturnover.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.nepse.topturnover.crawler.SharesansarCrawler;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

class SectorsTest {

    @Test
    void guessesCommonNepaliCompanyNames() {
        assertEquals("Microfinance", Sectors.guess("Aarambha Chautari Laghubitta Bittiya Sanstha Limited"));
        assertEquals("Commercial Bank", Sectors.guess("Agricultural Development Bank Limited"));
        assertEquals("Development Bank", Sectors.guess("Lumbini Bikas Bank Limited"));
        assertEquals("Finance", Sectors.guess("Central Finance Limited"));
        assertEquals("Hydropower", Sectors.guess("Ghalemdi Hydro Limited"));
        assertEquals("Life Insurance", Sectors.guess("Nepal Life Insurance Company Limited"));
        assertEquals("Non-Life Insurance", Sectors.guess("Sagarmatha Lumbini Insurance Co. Limited"));
        assertEquals("Manufacturing and Processing", Sectors.guess("Shivam Cements Limited"));
        assertEquals("Investment", Sectors.guess("Nepal Infrastructure Bank Limited"));
        assertEquals("Corporate Debentures", Sectors.guess("Nabil Debenture 2085"));
        assertEquals("Mutual Fund", Sectors.guess("NIC Asia Growth Fund"));
        assertNull(Sectors.guess("Some Unknown Name"));
        assertTrue(Sectors.isEquity(null));
        assertTrue(!Sectors.isEquity("Mutual Fund"));
    }

    /** Most names on a real ShareSansar page should get a guess; prints the rest for review. */
    @Test
    void coversMostOfARealPage() throws Exception {
        MarketSnapshot s;
        try (InputStream in = SectorsTest.class.getResourceAsStream("/fixtures/sharesansar-2026-10-30.html")) {
            s = SharesansarCrawler.parse(Jsoup.parse(in, "UTF-8", "https://www.sharesansar.com/"));
        }
        Map<String, Integer> counts = new TreeMap<>();
        int unknown = 0;
        for (StockQuote q : s.quotes()) {
            String g = Sectors.guess(q.name());
            if (g == null) unknown++;
            counts.merge(g == null ? "(none)" : g, 1, Integer::sum);
        }
        System.out.println("Sector guesses on the fixture: " + counts);
        assertTrue(unknown < s.quotes().size() * 0.15, "too many unguessed names: " + unknown);
    }
}
