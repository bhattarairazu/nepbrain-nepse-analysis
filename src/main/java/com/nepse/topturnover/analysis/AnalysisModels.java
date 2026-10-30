package com.nepse.topturnover.analysis;

import java.time.LocalDate;
import java.util.List;

/** Read models shown on the dashboard pages. */
public final class AnalysisModels {
    private AnalysisModels() {}

    /** One row of a day's top-20 turnover or volume list. */
    public record TopListRow(
            int rank,
            String symbol,
            String name,
            double value,
            Double close,
            Double changePct,
            Double volume,
            Double turnover,
            Integer trades,
            Double rvol,             // volume / average volume of previous sessions
            Double relTurnover,      // turnover / average turnover of previous sessions
            Double closeInRange,     // 0 = closed at the day's low, 1 = at the high
            Double avgTradeSize,     // turnover / number of transactions, Rs
            int streak,              // consecutive sessions in this list, including today
            int appearances10,       // sessions in this list out of the last 10
            boolean newEntry,        // not in this list in any of the previous 10 sessions
            boolean inBothLists,
            Signal signal) {

        public boolean closedNearHigh() { return closeInRange != null && closeInRange >= 0.75; }
        public boolean closedNearLow() { return closeInRange != null && closeInRange <= 0.25; }
    }

    public record DayAnalysis(
            LocalDate date,
            int historySessions,         // earlier sessions available for averages (max 20)
            int stockCount,
            double marketTurnover,
            Double marketTurnoverVsAvg,
            double marketVolume,
            Double topTurnoverShare,     // share of market turnover taken by the top-N turnover list
            Double topVolumeShare,
            List<TopListRow> turnover,
            List<TopListRow> volume) {

        public long count(Signal s) {
            return turnover.stream().filter(r -> r.signal() == s).count();
        }

        public long newEntries() {
            return turnover.stream().filter(TopListRow::newEntry).count();
        }

        public long inBoth() {
            return turnover.stream().filter(TopListRow::inBothLists).count();
        }
    }

    /** A stock's presence in a list over a window of sessions. */
    public record FlowRow(
            String symbol,
            String name,
            int appearances,
            double avgRank,
            int bestRank,
            double totalValue,
            Double priceChangePct,   // from the close before the window to the last close in the window
            Double lastClose,
            Integer latestRank) {}

    public record MoneyFlow(String listType, int sessionsRequested, int sessionsUsed, LocalDate from, LocalDate to,
                            List<FlowRow> rows) {}

    public record MarketDay(LocalDate date, double turnover, double volume, Long trades, int stockCount,
                            Double turnoverAvg20, Double topTurnoverShare, Double topVolumeShare) {}

    public record StockDay(LocalDate date, Double close, Double changePct, Double volume, Double turnover,
                           Integer trades, Integer turnoverRank, Integer volumeRank) {}

    public record StockView(String symbol, String name, String sector, List<StockDay> days,
                            int turnoverAppearances, int volumeAppearances, Double avgTurnover20, Double avgVolume20) {
        public StockDay latest() { return days.isEmpty() ? null : days.get(days.size() - 1); }
    }
}
