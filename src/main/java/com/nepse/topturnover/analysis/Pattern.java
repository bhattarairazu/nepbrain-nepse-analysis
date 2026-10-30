package com.nepse.topturnover.analysis;

/** The five volume patterns. Numbers match the study notes. */
public enum Pattern {
    BASE_BREAKOUT(1, "Volume spike after a quiet base", "good",
            "Volume 2× normal or more and the price closes above a tight sideways range.",
            "Often the start of a trend. This is the best setup."),
    CLIMAX_TOP(2, "Volume spike after a big run-up", "bad",
            "A stock already up 50%+ gets 2× volume or its highest volume, with a long upper wick or a close near the low.",
            "Often a climax top, where early buyers sell to late buyers."),
    CAPITULATION(3, "Volume spike at the lows", "warn",
            "After a fall of 30%+, huge volume with a long lower wick or a close near the day's high.",
            "Possible capitulation: panicked sellers may be exhausted. Wait for confirmation before buying."),
    CHURN(4, "High volume, price barely moves", "warn",
            "2× volume or more but the price moves less than 1%.",
            "Churning or distribution (big holders selling into buying), or a block deal. Check the floorsheet."),
    CIRCUIT_RUN(5, "Upper-circuit run on falling volume", "bad",
            "3 or more upper-circuit days in the last 5 sessions, with volume fading.",
            "Few sellers, often an operator-driven move. It can reverse sharply.");

    private final int number;
    private final String title;
    private final String tone;
    private final String lookFor;
    private final String meaning;

    Pattern(int number, String title, String tone, String lookFor, String meaning) {
        this.number = number;
        this.title = title;
        this.tone = tone;
        this.lookFor = lookFor;
        this.meaning = meaning;
    }

    public int getNumber() { return number; }
    public String getTitle() { return title; }
    public String getTone() { return tone; }
    public String getLookFor() { return lookFor; }
    public String getMeaning() { return meaning; }
}
