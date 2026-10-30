package com.nepse.topturnover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nepse.topturnover.repository.MarketDataRepository;

/**
 * Fills a separate database with the synthetic market so the UI can be tried before real history exists:
 *   NEPSE_DEMO_DB_URL=jdbc:mysql://localhost:3306/nepse_demo NEPSE_IT_DB_USER=... NEPSE_IT_DB_PASSWORD=... mvn test -Dtest=DemoDataSeeder
 * Then start the app with --spring.datasource.url=...nepse_demo. Never point it at nepse_analysis: it empties the tables.
 */
@SpringBootTest(properties = "nepse.crawl.enabled=false")
@EnabledIfEnvironmentVariable(named = "NEPSE_DEMO_DB_URL", matches = ".+")
class DemoDataSeeder {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("NEPSE_DEMO_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("NEPSE_IT_DB_USER", "nepse"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("NEPSE_IT_DB_PASSWORD", ""));
    }

    @Autowired MarketDataRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seed() {
        InsightsIntegrationTest.seedInto(jdbc, repo);
    }
}
