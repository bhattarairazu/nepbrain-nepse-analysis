package com.nepse.topturnover.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlWindowTest {
    private static final ZoneId NPT = ZoneId.of("Asia/Kathmandu");
    private static final Set<DayOfWeek> MON_FRI = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

    private static ZonedDateTime at(int y, int m, int d, int hh, int mm) {
        return ZonedDateTime.of(y, m, d, hh, mm, 0, 0, NPT);
    }

    private final CrawlWindow window = new CrawlWindow(MON_FRI, Set.of(LocalDate.of(2026, 11, 2)),
            LocalTime.of(15, 15), LocalTime.of(23, 30));

    @Test
    void allowsWeekdayAfterClose() {
        assertTrue(window.check(at(2026, 10, 30, 15, 30)).allowed()); // Friday
    }

    @Test
    void blocksDuringTradingHours() {
        assertFalse(window.check(at(2026, 10, 30, 14, 59)).allowed());
        assertFalse(window.check(at(2026, 10, 30, 15, 5)).allowed());
    }

    @Test
    void blocksWeekendAndHoliday() {
        assertFalse(window.check(at(2026, 10, 31, 16, 0)).allowed()); // Saturday
        assertFalse(window.check(at(2026, 11, 1, 16, 0)).allowed());  // Sunday
        assertFalse(window.check(at(2026, 11, 2, 16, 0)).allowed());  // listed holiday
    }

    @Test
    void readsHolidayFileWithRangesAndComments(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("holidays.txt");
        Files.writeString(f, "# comment\n2026-12-25  # Christmas\n\n2026-10-19..2026-10-23\n");
        Set<LocalDate> h = CrawlWindow.loadHolidays(f);
        assertEquals(6, h.size());
        assertTrue(h.contains(LocalDate.of(2026, 10, 21)));
    }
}
