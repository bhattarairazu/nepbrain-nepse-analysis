package com.nepse.topturnover.crawler;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/**
 * Reads https://merolagani.com/LatestMarket.aspx ("Live Trading" table).
 *
 * Quirks handled here:
 *  - The page has no turnover column, so turnover is estimated as LTP x quantity (flagged as estimated).
 *  - The headers say Open/High/Low but the cells are not in that order, so the three prices are
 *    assigned by value: highest = high, lowest = low, the remaining one = open.
 *  - It is a live page; data is accepted only if its "As of" time is at or after the market close.
 */
public final class MerolaganiCrawler implements SourceCrawler {
    public static final String NAME = "merolagani";
    private static final Pattern AS_OF = Pattern.compile("As of\\s*(\\d{4})/(\\d{1,2})/(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})");

    private final HttpFetcher http;
    private final String url;
    private final LocalTime marketClose;

    public MerolaganiCrawler(HttpFetcher http, String url, LocalTime marketClose) {
        this.http = http;
        this.url = url;
        this.marketClose = marketClose;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public MarketSnapshot crawl() throws CrawlException {
        return parse(http.get(url), marketClose);
    }

    public static MarketSnapshot parse(Document doc, LocalTime marketClose) throws CrawlException {
        Matcher m = AS_OF.matcher(doc.text());
        if (!m.find()) throw new CrawlException("Merolagani: 'As of' timestamp not found on the page");
        LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        LocalTime asOf = LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        if (asOf.isBefore(marketClose)) {
            throw new CrawlException("Merolagani shows intraday data (as of " + date + " " + asOf + "); refusing to store it");
        }

        Element table = doc.selectFirst("table.live-trading");
        if (table == null) {
            throw new CrawlException("Merolagani: live trading table (table.live-trading) not found; the page layout may have changed");
        }

        List<StockQuote> quotes = new ArrayList<>();
        for (Element tr : table.select("tbody tr")) {
            List<Element> td = tr.children().stream().filter(e -> e.tagName().equals("td")).toList();
            if (td.size() < 7) continue;
            String symbol = td.get(0).text().trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty()) continue;

            Double ltp = Numbers.parse(td.get(1).text());
            Double changePct = Numbers.parse(td.get(2).text());
            List<Double> prices = List.of(td.get(3), td.get(4), td.get(5)).stream()
                    .map(e -> Numbers.parse(e.text())).filter(Objects::nonNull).toList();
            Double volume = Numbers.parse(td.get(6).text());

            Double high = prices.stream().max(Double::compare).orElse(null);
            Double low = prices.stream().min(Double::compare).orElse(null);
            Double open = prices.size() == 3 ? prices.get(0) + prices.get(1) + prices.get(2) - high - low : null;
            Double prevClose = ltp != null && changePct != null && changePct > -100
                    ? Math.round(ltp / (1 + changePct / 100) * 100) / 100.0 : null;
            Double turnover = ltp != null && volume != null ? ltp * volume : null;

            quotes.add(new StockQuote(symbol, companyName(td.get(0)), open, high, low, ltp, ltp, prevClose, null,
                    volume, turnover, null, changePct, true));
        }
        return new MarketSnapshot(date, NAME, quotes);
    }

    /** Link title looks like "ADBL (Agriculture Development Bank Limited)". */
    private static String companyName(Element cell) {
        Element a = cell.selectFirst("a");
        if (a == null) return null;
        String t = a.attr("title");
        int open = t.indexOf('(');
        int close = t.lastIndexOf(')');
        return open >= 0 && close > open ? t.substring(open + 1, close).trim() : null;
    }
}
