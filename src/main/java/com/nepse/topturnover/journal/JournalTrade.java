package com.nepse.topturnover.journal;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** One trade as you logged it (a row of journal_trade). Exit fields are null while the position is open. */
public record JournalTrade(
        Long id,
        String symbol,
        String sector,
        String rule,
        String marketRegime,
        LocalDate entryDate,
        double entryPrice,
        int quantity,
        Double stopPrice,
        Double targetPrice,
        String moodEntry,
        String entryNotes,
        LocalDate exitDate,
        Double exitPrice,
        String exitReason,
        String moodExit,
        Boolean followedPlan,
        String mistakes,
        String lesson) {

    public boolean isOpen() {
        return exitDate == null || exitPrice == null;
    }

    public List<String> mistakeList() {
        if (mistakes == null || mistakes.isBlank()) return List.of();
        return Arrays.stream(mistakes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static final List<String> MOODS = List.of(
            "Calm", "Confident", "Unsure", "Anxious", "FOMO (fear of missing out)", "Greedy", "Revenge (after a loss)", "Bored");

    public static final List<String> MISTAKES = List.of(
            "Chased the entry", "No written plan", "Ignored market regime", "Position too big", "Moved the stop",
            "Didn't sell at the stop", "Sold too early", "Averaged down", "Bought on a tip or rumour",
            "Ignored book closure / news", "Traded an illiquid stock");

    public static final List<String> EXIT_REASONS = List.of("TARGET", "STOP", "RULE", "TIME", "MANUAL");

    public static final List<String> REGIMES = List.of(
            "Healthy rally", "Weak rally", "Sideways", "Quiet decline", "Selling drying up", "Heavy selling",
            "Short-term trend up", "Short-term trend down", "Unknown");
}
