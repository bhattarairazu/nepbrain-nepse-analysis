package com.nepse.topturnover.analysis;

/** What the average trade size (turnover / number of trades) says about who is trading. */
public enum TradeSizeClass {
    LARGER_TRADES("Larger trades building", "good",
            "Average trade size over the last 5 sessions is 1.3× or more its normal level: bigger investors are likely active."),
    RETAIL_FRENZY("Retail frenzy", "warn",
            "3× the usual number of trades but smaller trades than normal: retail crowds piling in, common near short-term tops."),
    BLOCK_DEAL("Possible block deal", "neutral",
            "Today's average trade size is 4× normal or more: one or two huge trades. Ignore it as a trend signal."),
    NORMAL("Normal", "neutral", "Trade size close to its usual level."),
    UNKNOWN("Not enough data", "neutral", "Needs 5+ earlier sessions with transaction counts.");

    private final String label;
    private final String tone;
    private final String meaning;

    TradeSizeClass(String label, String tone, String meaning) {
        this.label = label;
        this.tone = tone;
        this.meaning = meaning;
    }

    public String getLabel() { return label; }
    public String getTone() { return tone; }
    public String getMeaning() { return meaning; }
}
