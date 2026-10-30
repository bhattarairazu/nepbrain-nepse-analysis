package com.nepse.topturnover.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/** Parses real pages saved on 2026-10-30. */
class CrawlerParseTest {

    private static Document fixture(String name) throws Exception {
        try (InputStream in = CrawlerParseTest.class.getResourceAsStream("/fixtures/" + name)) {
            return Jsoup.parse(in, "UTF-8", "https://example.test/");
        }
    }

    private static StockQuote find(MarketSnapshot s, String symbol) {
        return s.quotes().stream().filter(q -> q.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void parsesSharesansarTable() throws Exception {
        MarketSnapshot s = SharesansarCrawler.parse(fixture("sharesansar-2026-10-30.html"));
        assertEquals(LocalDate.of(2026, 10, 30), s.tradeDate());
        assertTrue(s.quotes().size() > 300, "rows: " + s.quotes().size());

        StockQuote adbl = find(s, "ADBL");
        assertEquals(350.0, adbl.open());
        assertEquals(355.0, adbl.high());
        assertEquals(343.0, adbl.low());
        assertEquals(347.0, adbl.close());
        assertEquals(366409.0, adbl.volume());
        assertEquals(127696988.5, adbl.turnover());
        assertEquals(605, adbl.trades());
        assertEquals(348.9, adbl.prevClose());
        assertFalse(adbl.turnoverEstimated());
    }

    @Test
    void parsesMerolaganiTableAndFixesShuffledPriceColumns() throws Exception {
        MarketSnapshot s = MerolaganiCrawler.parse(fixture("merolagani-2026-10-30.html"), LocalTime.of(15, 0));
        assertEquals(LocalDate.of(2026, 10, 30), s.tradeDate());
        assertTrue(s.quotes().size() > 300);

        // ShareSansar for the same day: open 863.5, high 863.9, low 851
        StockQuote aclbsl = find(s, "ACLBSL");
        assertEquals(863.5, aclbsl.open(), 1e-9);
        assertEquals(863.9, aclbsl.high());
        assertEquals(851.0, aclbsl.low());
        assertEquals(854.0, aclbsl.close());
        assertEquals(394.0, aclbsl.volume());
        assertEquals(854.0 * 394, aclbsl.turnover(), 1e-6);
        assertTrue(aclbsl.turnoverEstimated());
        assertEquals("Aarambha Chautari Laghubitta Bittiya Sanstha Limited", aclbsl.name());
    }

    @Test
    void sourcesAgreeOnVolume() throws Exception {
        MarketSnapshot ss = SharesansarCrawler.parse(fixture("sharesansar-2026-10-30.html"));
        MarketSnapshot ml = MerolaganiCrawler.parse(fixture("merolagani-2026-10-30.html"), LocalTime.of(15, 0));
        assertEquals(find(ss, "ADBL").volume(), find(ml, "ADBL").volume());
        assertEquals(find(ss, "ADBL").close(), find(ml, "ADBL").close());
    }

    @Test
    void rejectsMerolaganiIntradayData() throws Exception {
        Document doc = fixture("merolagani-2026-10-30.html");
        doc.body().prepend("<div>Live Trading As of 2026/10/30 13:45:00</div>");
        assertThrows(CrawlException.class, () -> MerolaganiCrawler.parse(doc, LocalTime.of(15, 0)));
    }

    @Test
    void rejectsChangedLayout() {
        Document doc = Jsoup.parse("<html><body><p>As of : 2026-10-30</p><table id='other'></table></body></html>");
        assertThrows(CrawlException.class, () -> SharesansarCrawler.parse(doc));
    }
}
