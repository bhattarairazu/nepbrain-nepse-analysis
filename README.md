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
| **▶ Signals** | The short answer page: (1) can I buy today (market verdict), (2) **buy setups now** with entry, max entry, stop, targets at 2× and 3× risk and why, (3) **next possible entries** with an exact trigger price to set an alert for (base about to break out, pullback to the 20-day EMA, or needs a second strong day), (4) **sector rotation**: money is here now / rotating in next / losing steam, each with leading stocks, (5) stocks to avoid or take profits on. |
| **Ideas** | The daily routine, automated: market regime → active today (top-20 turnover or volume ≥ 2× normal) → closed up near the high → extended/risky moves removed → candidates scored 0–100 with every reason shown, a suggested stop, and links to the chart and the ShareSansar floorsheet. Also "setups forming" (tight bases near a breakout) and what was removed and why. |
| **Patterns** | The five volume patterns for every ordinary share: 1 breakout from a quiet base, 2 climax after a run-up, 3 capitulation at the lows, 4 high volume with no price progress, 5 upper-circuit run on fading volume, each with the evidence. |
| **Sectors** | Sector analysis for any period (1 week to 1 year, or custom from–to dates), grouped weekly or monthly: return vs market, turnover share and its change, breadth, top-20 days, a trend score, and Leading / Improving / Weakening / Lagging status; sector index chart and heatmaps. Click a sector for its top trending stocks. Sector assignments are under Sectors → Manage sectors. |
| **Trade size** | Turnover ÷ trades: larger trades building (bigger investors), retail frenzy, possible block deals, and a table for the top 20. |
| **Daily lists** | Top 20 by turnover and by volume with RVOL, price + volume signal, close position, streaks and new entries. |
| **Money flow** | Which stocks keep appearing over 5/10/20/60 sessions. |
| **Market** | Regime (index direction vs turnover), equal-weight index and breadth, turnover vs its average, top-20 concentration. |

Scores rank stocks **to research**; they are not buy signals. All thresholds live in
`src/main/java/com/nepse/topturnover/analysis/Rules.java`.

### Hover chart on every stock
Hover any stock link for a quick candlestick chart (with 20/50-day EMA and volume) drawn from the stored data.
**⤢** enlarges it, **NepseAlpha ↗** opens `https://nepsealpha.com/nepse-chart?symbol=…` in one reused browser tab, and
**Details →** opens the stock page. The NepseAlpha tab tries to show their chart inside the popup, but NepseAlpha sends
`X-Frame-Options: SAMEORIGIN` and sits behind a Cloudflare check, so it usually stays blank; use the ↗ button.

### History needed
| Feature | Earlier sessions needed |
|---|---|
| Relative volume, signals, trade-size classes | 5 (reliable after 20) |
| Quiet base / breakout (pattern 1) | 15 |
| Market regime | 25 |
| Run-up and long-fall patterns (2, 3), 50-day EMA | 40–50 |
| 200-day average | 200 |

Until then the pages say what is missing.

### Backfill and official sectors
Both use the date and sector filter on ShareSansar's own "Today's Share Price" page (the same request its Search button
makes), one request every `nepse.history.delay-seconds` (4 s):
- **Data → Backfill past sessions**: makes sure the last N trading sessions (default 20, max 60) are stored. Already-stored
  days are kept; weekends, listed holidays and days the site has no session for (e.g. Dashain) are skipped.
  About 20–25 requests for 20 sessions.
- **Sectors → Manage sectors → Load sectors from ShareSansar**: one request per sector (~17) sets every traded stock's
  official sector (source SHARESANSAR). Runs automatically once a week after the daily crawl. MANUAL choices are never
  overwritten; stocks that didn't trade on the loaded day keep their name guess until they trade.

Top turnover and top volume are always ranked by the app from each day's full table, never copied from a list.

### Sectors and the index
- New stocks get a sector **guessed from the company name** (marked AUTO) until the official load (SHARESANSAR) replaces it. Fix the rest on the Sectors page or import a
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

## Backtest data kept in MySQL
- Every backtest run is stored permanently: `backtest_run`, `backtest_result` (one row per strategy) and `backtest_trade`
  (every simulated trade). Nothing deletes them; an identical rerun isn't stored twice. Runs made before prices were adjusted
  for bonus/right shares are kept and marked `price_adjusted = 0`.
- `data/export/` holds the history collected for the backtests (285 sessions, 23 Jul 2025 → 30 Oct 2026) and the backtest runs.
  To load them into your database (start the app once first so Flyway creates the tables):
  ```bash
  mysql -uroot -p nepse_analysis < data/export/market_history_2025-07-23_to_2026-10-30.sql
  mysql -uroot -p nepse_analysis < data/export/backtest_runs_2026-10-31.sql
  ```
  Both use `INSERT IGNORE`, so rows you already have (and your MANUAL sectors) are kept.
- Prices are adjusted for bonus/right shares in all analysis (`PriceAdjuster`), using the site's adjusted previous close.
- The backfill follows NEPSE's calendar (Sunday–Thursday before 6 Apr 2026, Monday–Friday since) and remembers
  confirmed non-trading dates in `non_trading_day`, so they are never requested again.

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
