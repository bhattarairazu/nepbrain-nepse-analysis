# NEPSE Top Turnover Analysis

A Spring Boot app that:
1. **Crawls** ShareSansar's [Today's Share Price](https://www.sharesansar.com/today-share-price) once per trading day after the market closes (jsoup).
2. **Stores** every stock's end-of-day data plus the daily **top 20 by turnover** and **top 20 by volume** in **MySQL** (schema managed by **Flyway**).
3. **Shows** a Thymeleaf dashboard for analysing those lists.

## Why ShareSansar only
- ShareSansar's `robots.txt` allows all crawlers, and its terms page contains no clause against automated access.
- Merolagani's terms explicitly prohibit "any automated data collection methods, including … scripts, APIs, screen scraping, data mining, robots … regardless of their intended purposes", so it is not used.
- The app makes **one page request per trading day**, never during trading hours, never retries a refusal (HTTP 403/429), and the data is for personal analysis. Don't republish it.
  This is a practical reading of the published terms, not legal advice.

## Requirements
Java 21+, Maven 3.9+, MySQL 8.0.19+ (tested on 9.6).

## First-time setup
```bash
cd /Applications/Works/projectfiles/nepse-topturnover-analysis
mysql -uroot -p < scripts/mysql-setup.sql     # creates database nepse_analysis and user nepse
mvn package                                   # builds target/nepse-topturnover.jar and runs tests
scripts/run-app.sh                            # starts on http://localhost:8080
```
Flyway creates the tables on first start. `config/application.properties` holds your local database
password (generated for you; the same one is in `scripts/mysql-setup.sql`). Both files are git-ignored.

To keep it running in the background, starting at login:
```bash
cp scripts/com.nepse.topturnover.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.nepse.topturnover.plist
```

## When it crawls
The in-app scheduler (`nepse.crawl.cron`, default 15:20, 16:20 … 19:20 Nepal time, Mon–Fri) only fetches when:
1. today is a trading day (`nepse.market.trading-days`, Mon–Fri since April 2026),
2. today isn't in `config/holidays.txt`,
3. it's inside the after-close window (`nepse.crawl.window-start`–`window-end`, 15:15–23:30),
4. today isn't stored yet.

Unlisted holidays are caught automatically: if the page still shows the previous session's date or identical
data, nothing is stored. The **Data & crawler** page shows the log and has a "Crawl now" button, which follows the same rules.
The Mac must be on and the app running at crawl time. A missed day can't be fetched later from this page.

## Dashboard
| Page | What it answers |
|---|---|
| **Daily lists** | Top 20 by turnover and by volume for any session, with relative volume (RVOL), price + volume signal, close position in the day's range, average trade size, streak, appearances in the last 10 sessions, new entries, and stocks in both lists |
| **Money flow** | Which stocks keep appearing over the last 5/10/20/60 sessions, and how their price moved meanwhile |
| **Market activity** | Total turnover vs its 20-session average, and the share taken by the top 20 |
| **Stock page** | Price and turnover chart and the stock's daily rank history |

Signals (from price change and RVOL):
- **Accumulation**: price up 1%+ on 1.5× or more normal volume
- **Distribution**: price down 1%+ on 1.5× or more normal volume
- **Churn**: price flat on 2× or more volume
- **Weak rally**: price up 1%+ on below-normal volume
- **Quiet pullback**: price down 1%+ on below-normal volume

RVOL and signals appear after **5 stored sessions** and are most reliable after 20.

## Database
| Table | Contents |
|---|---|
| `trading_day` | One row per session: source, crawl time, stock count, market totals |
| `daily_price` | **Every stock, every session**: OHLC, LTP, prev close, VWAP, volume, turnover, trades, change % |
| `top_ranking` | Top 20 by `TURNOVER` and by `VOLUME` per session |
| `stock` | Symbol, company name, sector (fill in yourself), first/last seen |
| `corporate_action` | Bonus/right/split records you add, for adjusting long-term studies |
| `crawl_log` | Every fetch attempt and its outcome |

Every stock is stored so future rule studies (returns after appearing in a list, new indicators on old data) are possible.
15 years is about 1.5 million `daily_price` rows. To change the schema, add `src/main/resources/db/migration/V2__description.sql`;
never edit an applied migration. Example queries are in `sql/analysis_examples.sql`.

## Tests
```bash
mvn test                                     # parser, calendar, signals, formatting
NEPSE_IT_DB_URL=jdbc:mysql://localhost:3306/nepse_it NEPSE_IT_DB_USER=nepse NEPSE_IT_DB_PASSWORD=... mvn test
```
The second form also runs Flyway, the repository, the analysis and every page against a real MySQL database.
It **empties the tables** in that database, so point it at a separate `nepse_it` database, never `nepse_analysis`.
