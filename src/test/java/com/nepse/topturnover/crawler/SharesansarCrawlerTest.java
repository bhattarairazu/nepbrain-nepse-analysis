package com.nepse.topturnover.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/** Parses the real ShareSansar page saved on 2026-10-30. */
class SharesansarCrawlerTest {

    private static Document fixture() throws Exception {
        return fixture("sharesansar-2026-10-30.html");
    }

    private static Document fixture(String name) throws Exception {
        try (InputStream in = SharesansarCrawlerTest.class.getResourceAsStream("/fixtures/" + name)) {
            return Jsoup.parse(in, "UTF-8", "https://www.sharesansar.com/");
        }
    }

    @Test
    void parsesFullPriceTable() throws Exception {
        MarketSnapshot s = SharesansarCrawler.parse(fixture());
        assertEquals(LocalDate.of(2026, 10, 30), s.tradeDate());
        assertTrue(s.quotes().size() > 300, "rows: " + s.quotes().size());

        StockQuote adbl = s.quotes().stream().filter(q -> q.symbol().equals("ADBL")).findFirst().orElseThrow();
        assertEquals(350.0, adbl.open());
        assertEquals(355.0, adbl.high());
        assertEquals(343.0, adbl.low());
        assertEquals(347.0, adbl.close());
        assertEquals(348.9, adbl.prevClose());
        assertEquals(366409.0, adbl.volume());
        assertEquals(127696988.5, adbl.turnover());
        assertEquals(605, adbl.trades());
        assertEquals(-0.54, adbl.changePct());
    }

    @Test
    void parsesAPastDateFromTheDateFilter() throws Exception {
        MarketSnapshot s = SharesansarCrawler.parse(fixture("sharesansar-history-2026-10-29.html"));
        assertEquals(LocalDate.of(2026, 10, 29), s.tradeDate());
        assertTrue(s.quotes().size() > 300, "rows: " + s.quotes().size());
        assertTrue(s.quotes().stream().allMatch(q -> q.turnover() != null && q.volume() != null));
    }

    @Test
    void parsesOneSectorFromTheSectorFilter() throws Exception {
        MarketSnapshot s = SharesansarCrawler.parse(fixture("sharesansar-sector-hydropower-2026-10-30.html"));
        assertEquals(112, s.quotes().size());
        assertTrue(s.quotes().stream().anyMatch(q -> q.symbol().equals("AHPC")));
        assertTrue(s.quotes().stream().noneMatch(q -> q.symbol().equals("ADBL")), "a bank must not be in the hydropower list");
    }

    @Test
    void failsClearlyWhenLayoutChanges() {
        Document doc = Jsoup.parse("<html><body><p>As of : 2026-10-30</p><table id='other'></table></body></html>");
        CrawlException e = assertThrows(CrawlException.class, () -> SharesansarCrawler.parse(doc));
        assertTrue(e.getMessage().contains("layout"));
    }
}
