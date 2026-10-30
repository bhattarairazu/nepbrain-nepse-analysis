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
        try (InputStream in = SharesansarCrawlerTest.class.getResourceAsStream("/fixtures/sharesansar-2026-10-30.html")) {
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
    void failsClearlyWhenLayoutChanges() {
        Document doc = Jsoup.parse("<html><body><p>As of : 2026-10-30</p><table id='other'></table></body></html>");
        CrawlException e = assertThrows(CrawlException.class, () -> SharesansarCrawler.parse(doc));
        assertTrue(e.getMessage().contains("layout"));
    }
}
