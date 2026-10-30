package com.nepse.topturnover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.nepse.topturnover.analysis.AnalysisModels.DayAnalysis;
import com.nepse.topturnover.analysis.AnalysisModels.MoneyFlow;
import com.nepse.topturnover.analysis.AnalysisModels.TopListRow;
import com.nepse.topturnover.analysis.AnalysisService;
import com.nepse.topturnover.analysis.Signal;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;
import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Runs Flyway, the repository, the analysis and every page against a real MySQL database.
 * Enabled only when NEPSE_IT_DB_URL is set, e.g.
 *   NEPSE_IT_DB_URL=jdbc:mysql://127.0.0.1:3306/nepse_it NEPSE_IT_DB_USER=nepse NEPSE_IT_DB_PASSWORD=... mvn test
 * The database's tables are emptied, so never point it at your real data.
 */
@SpringBootTest(properties = "nepse.crawl.enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "NEPSE_IT_DB_URL", matches = ".+")
class MySqlIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("NEPSE_IT_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("NEPSE_IT_DB_USER", "nepse"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("NEPSE_IT_DB_PASSWORD", ""));
    }

    @Autowired MarketDataRepository repo;
    @Autowired AnalysisService analysis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM trading_day");
        jdbc.update("DELETE FROM stock");
        jdbc.update("DELETE FROM crawl_log");
    }

    /**
     * 30 stocks. S1..S30 have volume 1000*(31-i). On the last day, S25 gets a 10x volume burst
     * with a +4% move (accumulation) and S2 drops 3% on 3x volume (distribution).
     */
    private static MarketSnapshot day(int index, boolean last) {
        List<StockQuote> q = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            double close = 100 + i;
            double volume = 1000.0 * (31 - i);
            double change = 0.2;
            if (last && i == 25) { volume *= 10; change = 4.0; close *= 1.04; }
            if (last && i == 2) { volume *= 3; change = -3.0; close *= 0.97; }
            q.add(new StockQuote("S" + i, "Stock " + i, close, close * 1.01, close * 0.99, close, close, close, close,
                    volume, close * volume, 50, change));
        }
        return new MarketSnapshot(START.plusDays(index), "test", q);
    }

    private void seed(int days) {
        for (int d = 0; d < days; d++) repo.save(day(d, d == days - 1), 20, LocalDateTime.of(2026, 10, 30, 15, 30));
    }

    @Test
    void storesFullTableAndTopLists() {
        seed(1);
        assertEquals(30, jdbc.queryForObject("SELECT COUNT(*) FROM daily_price", Integer.class));
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(*) FROM top_ranking", Integer.class));
        // seed(1) stores a "last day": S2 (3 x 29,000) and S25 (10 x 6,000) bursts rank ahead of S1 (30,000)
        assertEquals(List.of("S2", "S25", "S1"), jdbc.queryForList(
                "SELECT symbol FROM top_ranking WHERE list_type='VOLUME' ORDER BY rank_no LIMIT 3", String.class));
        repo.save(day(0, true), 20, LocalDateTime.now()); // replacing a day must not duplicate rows
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(*) FROM top_ranking", Integer.class));
        assertTrue(repo.looksStale(new MarketSnapshot(START.plusDays(1), "test", day(0, true).quotes())));
    }

    @Test
    void dayAnalysisComputesRelativeVolumeSignalsAndPersistence() {
        seed(25);
        DayAnalysis a = analysis.day(null).orElseThrow();
        assertEquals(START.plusDays(24), a.date());
        assertEquals(20, a.historySessions());

        TopListRow burst = a.volume().stream().filter(r -> r.symbol().equals("S25")).findFirst().orElseThrow();
        assertEquals(10.0, burst.rvol(), 1e-6);
        assertEquals(Signal.ACCUMULATION, burst.signal());
        assertTrue(burst.newEntry(), "S25 is normally outside the top 20 by volume");
        assertEquals(1, burst.streak());

        TopListRow seller = a.volume().stream().filter(r -> r.symbol().equals("S2")).findFirst().orElseThrow();
        assertEquals(Signal.DISTRIBUTION, seller.signal());
        assertEquals(10, seller.appearances10());
        assertFalse(seller.newEntry());
        assertEquals(21, seller.streak()); // every stored session within the 21-session window

        TopListRow steady = a.volume().stream().filter(r -> r.symbol().equals("S5")).findFirst().orElseThrow();
        assertEquals(1.0, steady.rvol(), 1e-6);
        assertEquals(Signal.NEUTRAL, steady.signal());
        assertNotNull(a.topTurnoverShare());
    }

    @Test
    void firstDayHasNoRelativeVolumeYet() {
        seed(1);
        DayAnalysis a = analysis.day(null).orElseThrow();
        assertEquals(0, a.historySessions());
        assertNull(a.turnover().get(0).rvol());
        assertFalse(a.turnover().get(0).newEntry());
    }

    @Test
    void moneyFlowCountsAppearances() {
        seed(12);
        MoneyFlow f = analysis.moneyFlow("VOLUME", 10, null).orElseThrow();
        assertEquals(10, f.sessionsUsed());
        assertEquals("S1", f.rows().get(0).symbol());
        assertEquals(10, f.rows().get(0).appearances());
    }

    @Test
    void everyPageRenders() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("No data yet")));
        seed(25);
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Accumulation")));
        mvc.perform(get("/").param("date", START.plusDays(3).toString())).andExpect(status().isOk());
        mvc.perform(get("/money-flow").param("type", "VOLUME").param("days", "20")).andExpect(status().isOk());
        mvc.perform(get("/market")).andExpect(status().isOk());
        mvc.perform(get("/stock/S25")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Stock 25")));
        mvc.perform(get("/stock/NOPE")).andExpect(status().isOk());
        mvc.perform(get("/crawl")).andExpect(status().isOk());
    }
}
