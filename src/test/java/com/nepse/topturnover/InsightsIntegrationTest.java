package com.nepse.topturnover;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.nepse.topturnover.analysis.FeatureService;
import com.nepse.topturnover.analysis.InsightService;
import com.nepse.topturnover.analysis.Pattern;
import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Seeds the synthetic market into MySQL and checks the Ideas, Patterns, Sectors, Trade size and Market pages.
 * Enabled only when NEPSE_IT_DB_URL is set (see MySqlIntegrationTest). Empties that database.
 */
@SpringBootTest(properties = "nepse.crawl.enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "NEPSE_IT_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsIntegrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("NEPSE_IT_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("NEPSE_IT_DB_USER", "nepse"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("NEPSE_IT_DB_PASSWORD", ""));
    }

    @Autowired MarketDataRepository repo;
    @Autowired InsightService insights;
    @Autowired FeatureService features;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeAll
    void seed() {
        seedInto(jdbc, repo);
        features.evict();
    }

    /** Also used by DemoDataSeeder. */
    static void seedInto(JdbcTemplate jdbc, MarketDataRepository repo) {
        jdbc.update("DELETE FROM trading_day");
        jdbc.update("DELETE FROM stock");
        jdbc.update("DELETE FROM crawl_log");
        for (MarketSnapshot s : new SyntheticMarket().snapshots()) repo.save(s, 20, LocalDateTime.of(2026, 9, 1, 15, 30));
        repo.autoAssignSectors();
    }

    @Test
    void patternsAreFoundInTheDatabase() {
        var groups = insights.patterns(null).orElseThrow();
        assertTrue(symbols(groups, Pattern.BASE_BREAKOUT).contains("BRK"));
        assertTrue(symbols(groups, Pattern.CLIMAX_TOP).contains("CLX"));
        assertTrue(symbols(groups, Pattern.CAPITULATION).contains("CAP"));
        assertTrue(symbols(groups, Pattern.CHURN).containsAll(List.of("CHN", "BLK")));
        assertTrue(symbols(groups, Pattern.CIRCUIT_RUN).contains("OPR"));
    }

    @Test
    void routineKeepsTheBreakoutAndDropsTheOperatorRun() {
        var r = insights.routine(null).orElseThrow();
        assertTrue(r.ideas().stream().anyMatch(i -> i.f().symbol().equals("BRK")), r.ideas().toString());
        // warning patterns never become ideas, whether or not they were active enough to reach step 4
        assertTrue(r.ideas().stream().noneMatch(i -> List.of("OPR", "CLX").contains(i.f().symbol())));
        assertTrue(r.active() >= r.closedStrong() && r.closedStrong() >= r.kept());
        assertNotEquals("NA", r.regime().key());
    }

    @Test
    void sectorsAreGuessedAndFlowIsCounted() {
        assertEquals("Hydropower", jdbc.queryForObject("SELECT sector FROM stock WHERE symbol='BRK'", String.class));
        var flow = insights.sectorFlow(null);
        assertEquals(5, flow.weeks().size());
        assertTrue(flow.rows().stream().noneMatch(r -> r.sector().equals("Unassigned")));
        double share = flow.rows().stream().mapToDouble(r -> r.latestShare() == null ? 0 : r.latestShare()).sum();
        assertEquals(1.0, share, 1e-9);
    }

    @Test
    void newPagesRender() throws Exception {
        mvc.perform(get("/ideas")).andExpect(status().isOk()).andExpect(content().string(containsString("BRK")));
        mvc.perform(get("/patterns")).andExpect(status().isOk()).andExpect(content().string(containsString("OPR")));
        mvc.perform(get("/sectors")).andExpect(status().isOk());
        mvc.perform(get("/sectors").param("show", "all")).andExpect(status().isOk()).andExpect(content().string(containsString("AUTO")));
        mvc.perform(get("/trade-size")).andExpect(status().isOk()).andExpect(content().string(containsString("BLK")));
        mvc.perform(get("/market")).andExpect(status().isOk()).andExpect(content().string(containsString("Equal-weight")));
        mvc.perform(get("/stock/BRK")).andExpect(status().isOk()).andExpect(content().string(containsString("Pattern 1")));
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(post("/sectors/assign").param("symbol", "CHN").param("sector", "Commercial Bank"))
                .andExpect(status().is3xxRedirection());
        assertEquals("MANUAL", jdbc.queryForObject("SELECT sector_source FROM stock WHERE symbol='CHN'", String.class));
    }

    private static List<String> symbols(List<InsightService.PatternGroup> groups, Pattern p) {
        return groups.stream().filter(g -> g.pattern() == p).findFirst().orElseThrow()
                .stocks().stream().map(f -> f.symbol()).toList();
    }
}
