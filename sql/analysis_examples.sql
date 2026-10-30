-- Ad-hoc queries for MySQL Workbench / IntelliJ / mysql CLI:
--   mysql -unepse -p nepse_analysis < sql/analysis_examples.sql
-- The dashboard already shows most of this; these are starting points for your own rules.

-- 1. Latest session's top 20 by turnover with price change
SELECT r.rank_no, r.symbol, ROUND(r.metric_value / 1e7, 2) AS turnover_cr, p.close_price, p.change_pct, p.trades
FROM top_ranking r
JOIN daily_price p ON p.trade_date = r.trade_date AND p.symbol = r.symbol
WHERE r.list_type = 'TURNOVER' AND r.trade_date = (SELECT MAX(trade_date) FROM trading_day)
ORDER BY r.rank_no;

-- 2. Persistent money flow: in the top-20 turnover on at least 5 of the last 10 sessions
SELECT symbol, COUNT(*) AS days_in_top20, ROUND(AVG(rank_no), 1) AS avg_rank, ROUND(SUM(metric_value) / 1e7, 2) AS turnover_cr
FROM top_ranking
WHERE list_type = 'TURNOVER'
  AND trade_date IN (SELECT trade_date FROM (SELECT trade_date FROM trading_day ORDER BY trade_date DESC LIMIT 10) t)
GROUP BY symbol
HAVING COUNT(*) >= 5
ORDER BY days_in_top20 DESC, avg_rank;

-- 3. Relative volume of every stock on the latest session (today's volume / previous 20-session average)
WITH ranked AS (
    SELECT symbol, trade_date, volume,
           AVG(volume) OVER (PARTITION BY symbol ORDER BY trade_date ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING) AS avg_vol_20
    FROM daily_price
)
SELECT symbol, volume, ROUND(avg_vol_20) AS avg_vol_20, ROUND(volume / NULLIF(avg_vol_20, 0), 2) AS rvol
FROM ranked
WHERE trade_date = (SELECT MAX(trade_date) FROM trading_day)
ORDER BY rvol DESC
LIMIT 20;

-- 4. What happened after a top-20 turnover appearance: return 5, 10 and 20 sessions later
--    (use after a few months of data; returns are not adjusted for bonus/right shares)
WITH fwd AS (
    SELECT trade_date, symbol, close_price, change_pct,
           LEAD(close_price, 5)  OVER w AS close_5,
           LEAD(close_price, 10) OVER w AS close_10,
           LEAD(close_price, 20) OVER w AS close_20
    FROM daily_price
    WINDOW w AS (PARTITION BY symbol ORDER BY trade_date)
)
SELECT COUNT(*) AS signals,
       ROUND(AVG((f.close_5  / f.close_price - 1) * 100), 2) AS avg_5d_pct,
       ROUND(AVG((f.close_10 / f.close_price - 1) * 100), 2) AS avg_10d_pct,
       ROUND(AVG((f.close_20 / f.close_price - 1) * 100), 2) AS avg_20d_pct,
       ROUND(100 * AVG(f.close_10 > f.close_price), 1)       AS win_rate_10d_pct
FROM top_ranking r
JOIN fwd f ON f.trade_date = r.trade_date AND f.symbol = r.symbol
WHERE r.list_type = 'TURNOVER' AND f.change_pct >= 2 AND f.close_20 IS NOT NULL;

-- 5. Crawl health
SELECT run_at, status, trade_date, message FROM crawl_log ORDER BY id DESC LIMIT 15;
