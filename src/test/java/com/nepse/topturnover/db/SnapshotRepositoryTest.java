package com.nepse.topturnover.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

class SnapshotRepositoryTest {
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 30, 15, 30, 0, 0, ZoneId.of("Asia/Kathmandu"));

    private static MarketSnapshot snapshot(LocalDate day, double priceShift) {
        List<StockQuote> q = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            double close = 100 + i + priceShift;
            double volume = 1000.0 * (31 - i);           // S1 has the most volume
            double turnover = close * volume * (i % 3 == 0 ? 5 : 1); // every 3rd stock gets 5x turnover
            q.add(new StockQuote("S" + i, "Stock " + i, close, close, close, close, close, close, close,
                    volume, turnover, 10, 0.5, false));
        }
        return new MarketSnapshot(day, "test", q);
    }

    @Test
    void storesFullTableAndTopLists(@TempDir Path dir) throws Exception {
        try (Connection c = Database.open(dir.resolve("t.db"))) {
            SnapshotRepository repo = new SnapshotRepository(c);
            LocalDate day = LocalDate.of(2026, 10, 30);
            repo.save(snapshot(day, 0), 20, NOW);

            assertTrue(repo.hasDay(day));
            assertEquals(day, repo.latestDay().orElseThrow());
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM daily_price")) {
                assertEquals(30, rs.getInt(1));
            }
            List<SnapshotRepository.RankRow> vol = repo.topList(day, "VOLUME");
            assertEquals(20, vol.size());
            assertEquals("S1", vol.get(0).symbol());
            List<SnapshotRepository.RankRow> to = repo.topList(day, "TURNOVER");
            assertEquals(20, to.size());
            assertEquals("S3", to.get(0).symbol()); // highest 5x-boosted stock with most volume
            for (int i = 1; i < to.size(); i++) assertTrue(to.get(i - 1).value() >= to.get(i).value());

            // saving the same day again replaces it instead of duplicating
            repo.save(snapshot(day, 0), 20, NOW);
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM top_ranking")) {
                assertEquals(40, rs.getInt(1));
            }
        }
    }

    @Test
    void detectsStaleRepeatOfPreviousDay(@TempDir Path dir) throws Exception {
        try (Connection c = Database.open(dir.resolve("t.db"))) {
            SnapshotRepository repo = new SnapshotRepository(c);
            repo.save(snapshot(LocalDate.of(2026, 10, 29), 0), 20, NOW);
            assertTrue(repo.looksStale(snapshot(LocalDate.of(2026, 10, 30), 0)));
            assertFalse(repo.looksStale(snapshot(LocalDate.of(2026, 10, 30), 1.5)));
        }
    }

    @Test
    void analysisViewsWork(@TempDir Path dir) throws Exception {
        try (Connection c = Database.open(dir.resolve("t.db"))) {
            SnapshotRepository repo = new SnapshotRepository(c);
            LocalDate d = LocalDate.of(2026, 10, 1);
            for (int i = 0; i < 25; i++) repo.save(snapshot(d.plusDays(i), i), 20, NOW);
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT fwd_ret_5d_pct, rvol FROM v_top_list_performance WHERE trade_date='2026-10-01' AND symbol='S1' AND list_type='VOLUME'")) {
                assertTrue(rs.next());
                assertEquals(Math.round((106.0 / 101.0 - 1) * 10000) / 100.0, rs.getDouble(1), 1e-9);
            }
        }
    }
}
