package com.nepse.topturnover.journal;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.nepse.topturnover.config.NepseProperties;

/** NEPSE charges for one buy and one sell: tiered broker commission, SEBON fee, DP charge, capital gains tax. */
@Component
public class TradeCosts {

    /** Everything about a trade's money, worked out from the journal row (and a price for open trades). */
    public record Breakdown(double buyAmount, double buyCharges, double buyCost, double sellAmount, double sellCharges,
                            double grossPnl, double tax, double netPnl, double netPct, Double rMultiple, long daysHeld,
                            double charges) {}

    private record Tier(double upTo, double pct) {}

    private final List<Tier> tiers = new ArrayList<>();
    private final NepseProperties.Costs c;

    public TradeCosts(NepseProperties props) {
        this.c = props.costs() != null ? props.costs()
                : new NepseProperties.Costs("50000:0.36,500000:0.33,2000000:0.31,10000000:0.27,max:0.24", 10, 0.015, 25, 7.5, 5);
        for (String part : c.commissionTiers().split(",")) {
            String[] kv = part.trim().split(":");
            double upTo = kv[0].trim().toLowerCase(Locale.ROOT).equals("max") ? Double.MAX_VALUE : Double.parseDouble(kv[0].trim());
            tiers.add(new Tier(upTo, Double.parseDouble(kv[1].trim())));
        }
    }

    public double commission(double amount) {
        for (Tier t : tiers) {
            if (amount <= t.upTo()) return Math.max(c.minCommission(), amount * t.pct() / 100);
        }
        return Math.max(c.minCommission(), amount * tiers.get(tiers.size() - 1).pct() / 100);
    }

    public double buyCharges(double amount) {
        return commission(amount) + amount * c.sebonPct() / 100;
    }

    public double sellCharges(double amount) {
        return commission(amount) + amount * c.sebonPct() / 100 + c.dpCharge();
    }

    /**
     * @param sellPrice exit price for a closed trade, or the latest close to value an open one
     * @param sellDate  exit date, or the latest session date for an open trade (decides the tax rate)
     * @param withTax   false for open positions (tax is only due when you actually sell)
     */
    public Breakdown of(JournalTrade t, double sellPrice, java.time.LocalDate sellDate, boolean withTax) {
        double buyAmount = t.entryPrice() * t.quantity();
        double buyCharges = buyCharges(buyAmount);
        double buyCost = buyAmount + buyCharges;
        double sellAmount = sellPrice * t.quantity();
        double sellCharges = sellCharges(sellAmount);
        double gross = sellAmount - sellCharges - buyCost;
        long days = ChronoUnit.DAYS.between(t.entryDate(), sellDate);
        double rate = days >= 365 ? c.cgtLongPct() : c.cgtShortPct();
        double tax = withTax && gross > 0 ? gross * rate / 100 : 0;
        double net = gross - tax;
        Double r = t.stopPrice() != null && t.stopPrice() < t.entryPrice()
                ? net / ((t.entryPrice() - t.stopPrice()) * t.quantity()) : null;
        return new Breakdown(buyAmount, buyCharges, buyCost, sellAmount, sellCharges, gross, tax, net,
                buyCost > 0 ? net / buyCost * 100 : 0, r, days, buyCharges + sellCharges);
    }

    public double cgtShortPct() { return c.cgtShortPct(); }
    public double cgtLongPct() { return c.cgtLongPct(); }
}
