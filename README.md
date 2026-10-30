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
| **★ Ideas** | The daily routine, automated: market regime → active today (top-20 turnover or volume ≥ 2× normal) → closed up near the high → extended/risky moves removed → candidates scored 0–100 with every reason shown, a suggested stop, and links to the chart and the ShareSansar floorsheet. Also "setups forming" (tight bases near a breakout) and what was removed and why. |
| **Patterns** | The five volume patterns for every ordinary share: 1 breakout from a quiet base, 2 climax after a run-up, 3 capitulation at the lows, 4 high volume with no price progress, 5 upper-circuit run on fading volume, each with the evidence. |
| **Sectors** | Sector analysis for any period (1 week to 1 year, or custom from–to dates), grouped weekly or monthly: return vs market, turnover share and its change, breadth, top-20 days, a trend score, and Leading / Improving / Weakening / Lagging status; sector index chart and heatmaps. Click a sector for its top trending stocks. Sector assignments are under Sectors → Manage sectors. |
| **Trade size** | Turnover ÷ trades: larger trades building (bigger investors), retail frenzy, possible block deals, and a table for the top 20. |
| **Daily lists** | Top 20 by turnover and by volume with RVOL, price + volume signal, close position, streaks and new entries. |
| **Money flow** | Which stocks keep appearing over 5/10/20/60 sessions. |
| **Market** | Regime (index direction vs turnover), equal-weight index and breadth, turnover vs its average, top-20 concentration. |

Scores rank stocks **to research**; they are not buy signals. All thresholds live in
`src/main/java/com/nepse/topturnover/analysis/Rules.java`.

### History needed
| Feature | Earlier sessions needed |
|---|---|
| Relative volume, signals, trade-size classes | 5 (reliable after 20) |
| Quiet base / breakout (pattern 1) | 15 |
| Market regime | 25 |
| Run-up and long-fall patterns (2, 3), 50-day EMA | 40–50 |
| 200-day average | 200 |

Until then the pages say what is missing.

### Sectors and the index
- ShareSansar's price table has no sector column, so after each crawl the app **guesses sectors from company names**
  (about 94% of stocks on a real page). Guesses are marked AUTO. Fix the rest on the Sectors page or import a
  `SYMBOL,Sector` CSV. Your choices are marked MANUAL and are never overwritten.
- nepalstock.com is not used: its data API is token-protected and blocks automated browsers, and getting around that would
  defeat protections NEPSE put there deliberately.
- The official NEPSE index and sub-indices aren't on the crawled page, so the Market and Sectors pages use **equal-weight indices**
  (average daily % change of the stocks). They show direction but are not the official numbers.

### Try the UI with demo data
To see every page working before real history exists, fill a separate database with a synthetic market:
```bash
mysql -uroot -p -e "CREATE DATABASE nepse_demo"
NEPSE_DEMO_DB_URL=jdbc:mysql://localhost:3306/nepse_demo NEPSE_IT_DB_USER=root NEPSE_IT_DB_PASSWORD=... mvn test -Dtest=DemoDataSeeder
java -jar target/nepse-topturnover.jar --server.port=8081 --spring.datasource.url=jdbc:mysql://localhost:3306/nepse_demo --nepse.crawl.enabled=false
```
Fictional symbols (BRK, CLX, CAP, CHN, BLK, OPR, RTL, INST) each show one planted pattern. Never seed `nepse_analysis`.

## Database
| Table | Contents |
|---|---|
| `trading_day` | One row per session: source, crawl time, stock count, market totals |
| `daily_price` | **Every stock, every session**: OHLC, LTP, prev close, VWAP, volume, turnover, trades, change % |
| `top_ranking` | Top 20 by `TURNOVER` and by `VOLUME` per session |
| `stock` | Symbol, company name, sector (guessed or yours) and its source (AUTO/MANUAL), first/last seen |
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
