package com.nepse.topturnover.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ShareSansar's sector names, and a best-effort guess of a stock's sector from its company name. */
public final class Sectors {
    private Sectors() {}

    public static final List<String> ALL = List.of(
            "Commercial Bank", "Development Bank", "Finance", "Microfinance", "Hydropower",
            "Life Insurance", "Non-Life Insurance", "Hotel & Tourism", "Manufacturing and Processing",
            "Investment", "Trading", "Others",
            "Mutual Fund", "Corporate Debentures", "Government Bonds", "Preference Share", "Promoter Share");

    /** Not ordinary shares: excluded from ideas, patterns and the market index. */
    public static final Set<String> NON_EQUITY = Set.of(
            "Mutual Fund", "Corporate Debentures", "Government Bonds", "Preference Share", "Promoter Share");

    public static boolean isEquity(String sector) {
        return sector == null || !NON_EQUITY.contains(sector);
    }

    /** Guess from the company name, or null when unsure. Order matters: specific words first. */
    public static String guess(String companyName) {
        if (companyName == null || companyName.isBlank()) return null;
        String n = companyName.toLowerCase(Locale.ROOT);
        if (n.contains("promoter")) return "Promoter Share";
        if (n.contains("preference")) return "Preference Share";
        if (n.contains("debenture") || n.contains("bond") || n.contains("rinpatra")) return "Corporate Debentures";
        if (n.contains("mutual fund") || n.contains("yojana") || n.contains("scheme") || n.endsWith(" fund") || n.contains(" kosh")) return "Mutual Fund";
        if (n.contains("laghubitta") || n.contains("microfinance") || n.contains("micro finance")) return "Microfinance";
        if (n.contains("life insurance") || n.contains("jeevan bima")) return "Life Insurance";
        if (n.contains("insurance") || n.contains("bima") || n.contains("beema")) return "Non-Life Insurance";
        if (n.contains("agricultural development bank")) return "Commercial Bank";
        if (n.contains("bikas bank") || n.contains("development bank")) return "Development Bank";
        if (n.contains("infrastructure bank")) return "Investment";
        if (n.contains("finance")) return "Finance";
        if (n.contains("bank")) return "Commercial Bank";
        if (n.contains("investment") || n.contains("holding") || n.contains("capital")
                || n.contains("infrastructure and development")) return "Investment";
        if (n.contains("hydro") || n.contains("power") || n.contains("energy") || n.contains("urja")
                || n.contains("jalavidhyut") || n.contains("jalvidhyut") || n.contains("jalavidyut") || n.contains("jalbidhyut")
                || n.contains("jal vidhyut") || n.contains("bidyut") || n.contains("khola") || n.contains("solar")) return "Hydropower";
        if (n.contains("hotel") || n.contains("resort") || n.contains("tourism") || n.contains("airline") || n.contains("cable car")
                || n.contains(" hills") || n.contains("darshan")) {
            return "Hotel & Tourism";
        }
        if (n.contains("cement") || n.contains("mill") || n.contains("industr") || n.contains("udhyog") || n.contains("manufactur")
                || n.contains("brewer") || n.contains("distiller") || n.contains("pharma") || n.contains("steel") || n.contains("sugar")
                || n.contains("noodle") || n.contains("biscuit") || n.contains("textile") || n.contains("mineral")
                || n.contains("bottlers") || n.contains("colour") || n.contains("unilever") || n.contains("panel")) {
            return "Manufacturing and Processing";
        }
        if (n.contains("trading") || n.contains("salt") || n.contains("bitaran") || n.contains("supplier") || n.contains("bazar")) return "Trading";
        if (n.contains("doorsanchar") || n.contains("telecom")) return "Others";
        return null;
    }
}
