package com.nepse.topturnover.crawler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.nepse.topturnover.model.MarketSnapshot;
import com.nepse.topturnover.model.StockQuote;

/**
 * Reads https://www.sharesansar.com/today-share-price.
 * The page has every traded stock with OHLC, volume, turnover and number of transactions.
 * Columns are located by header text, so a reordered table still parses.
 */
public final class SharesansarCrawler implements SourceCrawler {
    public static final String NAME = "sharesansar";
    private static final Pattern AS_OF = Pattern.compile("As of\\s*:?\\s*(\\d{4}-\\d{2}-\\d{2})");

    private final HttpFetcher http;
    private final String url;

    public SharesansarCrawler(HttpFetcher http, String url) {
        this.http = http;
        this.url = url;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public MarketSnapshot crawl() throws CrawlException {
        return parse(http.get(url));
    }

    public static MarketSnapshot parse(Document doc) throws CrawlException {
        Element table = doc.selectFirst("table#headFixed");
        if (table == null) {
            throw new CrawlException("ShareSansar: price table (table#headFixed) not found; the page layout may have changed");
        }
        Map<String, Integer> col = new HashMap<>();
        List<Element> headers = table.select("thead th");
        for (int i = 0; i < headers.size(); i++) {
            col.put(headers.get(i).text().trim().toLowerCase(Locale.ROOT), i);
        }
        for (String required : List.of("symbol", "close", "vol", "turnover")) {
            if (!col.containsKey(required)) {
                throw new CrawlException("ShareSansar: column '" + required + "' missing; headers were " + col.keySet());
            }
        }

        List<StockQuote> quotes = new ArrayList<>();
        for (Element tr : table.select("tbody tr")) {
            List<Element> td = tr.children().stream().filter(e -> e.tagName().equals("td")).toList();
            if (td.size() < headers.size()) continue;
            Element symCell = td.get(col.get("symbol"));
            String symbol = symCell.text().trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty()) continue;
            Element link = symCell.selectFirst("a");
            String name = link != null && !link.attr("title").isBlank() ? link.attr("title").trim() : null;

            quotes.add(new StockQuote(
                    symbol, name,
                    num(td, col, "open"), num(td, col, "high"), num(td, col, "low"), num(td, col, "close"),
                    num(td, col, "ltp"), num(td, col, "prev. close"), num(td, col, "vwap"),
                    num(td, col, "vol"), num(td, col, "turnover"), Numbers.toInt(num(td, col, "trans.")),
                    num(td, col, "diff %"), false));
        }
        return new MarketSnapshot(tradeDate(doc), NAME, quotes);
    }

    private static Double num(List<Element> td, Map<String, Integer> col, String header) {
        Integer i = col.get(header);
        return i == null ? null : Numbers.parse(td.get(i).text());
    }

    private static LocalDate tradeDate(Document doc) throws CrawlException {
        Matcher m = AS_OF.matcher(doc.text());
        if (m.find()) return LocalDate.parse(m.group(1));
        Element picker = doc.selectFirst("#fromdate");
        if (picker != null && picker.attr("value").matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(picker.attr("value"));
        }
        throw new CrawlException("ShareSansar: trading date ('As of') not found on the page");
    }
}
