# NEPSE Top Turnover Analysis

A Java crawler (jsoup + SQLite) that runs each NEPSE trading day after the market closes.
It reads the full share-price table from **ShareSansar** or **Merolagani**, stores every stock's
end-of-day data, and saves the **top 20 by turnover** and **top 20 by volume** for later rule-based analysis.

## Requirements
- Java 21+ and Maven 3.9+ (`java -version`, `mvn -version`)
- Optional: `sqlite3` (built into macOS) or DB Browser for SQLite to explore the data

## Build
```bash
mvn package            # builds target/nepse-crawler.jar and runs the tests
```

## Commands
```bash
java -jar target/nepse-crawler.jar crawl                      # crawl once (guards apply)
java -jar target/nepse-crawler.jar crawl --source=sharesansar # use one site only this run
java -jar target/nepse-crawler.jar crawl --force              # ignore guards, overwrite today
java -jar target/nepse-crawler.jar daemon                     # keep running, crawl every trading day
java -jar target/nepse-crawler.jar report                     # print latest top-20 lists
java -jar target/nepse-crawler.jar report --date=2026-10-30
java -jar target/nepse-crawler.jar import-sectors --file=sectors.csv   # lines: SYMBOL,Sector
```
`scripts/run-crawler.sh <command>` does the same from any directory and builds the jar if needed.

## When it crawls
A run stores data only if **all** of these hold (otherwise it logs why and exits):
1. Today is a trading day (`market.trading.days`, default MON–FRI; NEPSE moved to Mon–Fri in April 2026).
2. Today is not listed in `config/holidays.txt`.
3. Nepal time is inside `crawl.window.start`–`crawl.window.end` (default 15:15–23:30), so it never runs during trading hours.
4. Today's data isn't stored yet.

Unlisted holidays are detected automatically: if the site still shows the previous session's date,
or data identical to the last stored day, nothing is stored.

## Choosing the website (config/crawler.properties)
| Setting | Meaning |
|---|---|
| `crawl.sources=sharesansar,merolagani` | Sites to use, in priority order |
| `crawl.source.mode=FIXED` | Always try them in the listed order |
| `crawl.source.mode=RANDOM` | Shuffle the order on each run |
| `crawl.fallback.enabled=true` | If the first site fails, try the next |
| `crawl.jitter.max.seconds=30` | Random 0–30s wait before fetching |

**Source differences.** ShareSansar has real turnover and the number of transactions. Merolagani has
no turnover column, so turnover is estimated as LTP × volume (flagged `turnover_estimated = 1`;
about 0.6% off on average in testing) and transactions are empty. Prices and volume are identical.
For exact turnover and trade counts, use `crawl.source.mode=FIXED` with sharesansar listed first.

## Scheduling on macOS
```bash
cp scripts/com.nepse.topturnover.crawler.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.nepse.topturnover.crawler.plist
```
It runs Mon–Fri at 15:20, 16:00, 17:30 and 19:00. Later runs skip once the day is stored, so they act as
retries if a site was down. The times are your Mac's local time (your Mac is set to Nepal time).
Alternative: leave `java -jar target/nepse-crawler.jar daemon` running.

## Database (data/nepse.db)
| Table | Contents |
|---|---|
| `trading_day` | One row per session: source, crawl time, stock count, market totals |
| `daily_price` | **Every stock, every day**: OHLC, LTP, prev close, VWAP, volume, turnover, trades, change % |
| `top_ranking` | Top 20 by `TURNOVER` and by `VOLUME` per day (rank, symbol, value) |
| `stock` | Symbol master: name, sector (via `import-sectors`), first/last seen |
| `corporate_action` | Bonus/right/split records you add, for adjusting long-term returns |
| `crawl_log` | Every crawl attempt with status and message |

Views for analysis:
- `v_price_forward`: each price row with the close 1/5/10/20 sessions later and 20-day average volume and turnover
- `v_top_list_performance`: each top-20 appearance with relative volume, average trade size and forward returns

The full table is stored, not just the top 20, because studies need data from outside the lists:
forward returns, relative volume against a stock's own average, and entries and exits from the lists.
Sizing: about 1.5 million `daily_price` rows over 15 years (roughly 150–250 MB), which SQLite handles easily.
The schema is plain SQL and can move to PostgreSQL later.

## Analysis
`sql/analysis_examples.sql` has ready-made queries: persistent top-20 stocks, new entrants, a rule
back-test (top-20 turnover + price up ≥2% + relative volume ≥2× → forward 5/10/20-day returns and
win rate), market turnover trend and sector money flow.
```bash
sqlite3 data/nepse.db < sql/analysis_examples.sql
```
Rule studies only become meaningful after a few months of data, and forward returns need 20 more sessions.

## Being a good citizen
The crawler makes one request per trading day, never retries a refusal (HTTP 403/429), and identifies
itself with a normal browser user agent. Use the data for personal analysis and respect each site's terms of use.
If a site's layout changes, the run fails with a clear message, falls back to the other site, and the
parser tests in `src/test` show what changed.
