-- Example analysis queries. Run with:
--   sqlite3 data/nepse.db < sql/analysis_examples.sql
-- or open data/nepse.db in a GUI such as DB Browser for SQLite.
--
-- Forward returns use raw prices. Before studying periods that include bonus/right shares,
-- record them in corporate_action and adjust, or exclude trades that span an ex-date.

.headers on
.mode column

-- 1. Latest day's top 20 by turnover, with relative volume and average trade size
SELECT rank, symbol, ROUND(value / 1e7, 2) AS turnover_cr, close, change_pct, rvol, avg_trade_size
FROM v_top_list_performance
WHERE list_type = 'TURNOVER' AND trade_date = (SELECT MAX(trade_date) FROM trading_day)
ORDER BY rank;

-- 2. Persistent money flow: stocks in the top 20 turnover on at least 5 of the last 10 sessions
WITH last10 AS (SELECT trade_date FROM trading_day ORDER BY trade_date DESC LIMIT 10)
SELECT symbol, COUNT(*) AS days_in_top20, ROUND(AVG(rank), 1) AS avg_rank,
       ROUND(SUM(value) / 1e7, 2) AS turnover_10d_cr
FROM top_ranking
WHERE list_type = 'TURNOVER' AND trade_date IN (SELECT trade_date FROM last10)
GROUP BY symbol
HAVING COUNT(*) >= 5
ORDER BY days_in_top20 DESC, avg_rank;

-- 3. New entrants: in today's top 20 turnover but in none of the previous 10 sessions
WITH days AS (SELECT trade_date FROM trading_day ORDER BY trade_date DESC LIMIT 11),
     today AS (SELECT MAX(trade_date) AS d FROM days)
SELECT r.rank, r.symbol, ROUND(r.value / 1e7, 2) AS turnover_cr, p.change_pct
FROM top_ranking r
JOIN daily_price p ON p.trade_date = r.trade_date AND p.symbol = r.symbol
WHERE r.list_type = 'TURNOVER' AND r.trade_date = (SELECT d FROM today)
  AND r.symbol NOT IN (SELECT symbol FROM top_ranking
                       WHERE list_type = 'TURNOVER'
                         AND trade_date IN (SELECT trade_date FROM days WHERE trade_date < (SELECT d FROM today)))
ORDER BY r.rank;

-- 4. Rule study: top-20 turnover + price up >= 2% + relative volume >= 2x.
--    How did these signals perform 5, 10 and 20 sessions later?
SELECT COUNT(*)                                                     AS signals,
       ROUND(AVG(fwd_ret_5d_pct), 2)                                AS avg_5d_pct,
       ROUND(AVG(fwd_ret_10d_pct), 2)                               AS avg_10d_pct,
       ROUND(AVG(fwd_ret_20d_pct), 2)                               AS avg_20d_pct,
       ROUND(100.0 * SUM(fwd_ret_10d_pct > 0) / COUNT(fwd_ret_10d_pct), 1) AS win_rate_10d_pct
FROM v_top_list_performance
WHERE list_type = 'TURNOVER' AND change_pct >= 2 AND rvol >= 2
  AND fwd_ret_20d_pct IS NOT NULL;

-- 5. Same rule, compared with every top-20 appearance (is the filter adding value?)
SELECT CASE WHEN change_pct >= 2 AND rvol >= 2 THEN 'rule' ELSE 'other top-20' END AS bucket,
       COUNT(*) AS n,
       ROUND(AVG(fwd_ret_10d_pct), 2) AS avg_10d_pct,
       ROUND(100.0 * SUM(fwd_ret_10d_pct > 0) / COUNT(*), 1) AS win_rate_pct
FROM v_top_list_performance
WHERE list_type = 'TURNOVER' AND fwd_ret_10d_pct IS NOT NULL
GROUP BY bucket;

-- 6. Market turnover trend: daily total vs its 20-session average
SELECT trade_date, ROUND(total_turnover / 1e9, 2) AS turnover_arba,
       ROUND(total_turnover / AVG(total_turnover) OVER (ORDER BY trade_date ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING), 2) AS vs_20d_avg
FROM trading_day
ORDER BY trade_date DESC
LIMIT 20;

-- 7. Sector money flow (after import-sectors): count of top-20 turnover slots per sector, last 10 sessions
WITH last10 AS (SELECT trade_date FROM trading_day ORDER BY trade_date DESC LIMIT 10)
SELECT COALESCE(s.sector, '(no sector)') AS sector, COUNT(*) AS top20_slots,
       ROUND(SUM(r.value) / 1e7, 2) AS turnover_cr
FROM top_ranking r LEFT JOIN stock s ON s.symbol = r.symbol
WHERE r.list_type = 'TURNOVER' AND r.trade_date IN (SELECT trade_date FROM last10)
GROUP BY sector
ORDER BY top20_slots DESC;

-- 8. Crawl health: last 15 attempts
SELECT run_at, source, status, trade_date, message FROM crawl_log ORDER BY id DESC LIMIT 15;
