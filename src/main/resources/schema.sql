-- NEPSE top turnover / volume analysis database (SQLite).
--
-- Every trading day stores the FULL price table (all ~350-400 listed stocks), not only the
-- top 20. Rule studies need it: forward returns of a top-20 stock, its relative volume
-- against its own 20-day average, and entries/exits from the lists all need the days and
-- stocks that were not in the top 20.
--
-- Size estimate for 15 years: ~400 stocks x ~245 sessions x 15 years = ~1.5 million rows in
-- daily_price (roughly 150-250 MB). SQLite handles this easily; the schema is plain SQL and
-- can be moved to PostgreSQL later without changes to the table design.
--
-- Dates are ISO text (YYYY-MM-DD, AD, Nepal time) so they sort and compare correctly.

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER PRIMARY KEY,
    applied_at  TEXT NOT NULL
);

-- One row per stored trading session.
CREATE TABLE IF NOT EXISTS trading_day (
    trade_date          TEXT PRIMARY KEY,
    source              TEXT NOT NULL,                -- sharesansar | merolagani
    crawled_at          TEXT NOT NULL,                -- ISO timestamp, Nepal time
    stock_count         INTEGER NOT NULL,
    total_turnover      REAL,
    total_volume        REAL,
    total_trades        INTEGER,
    turnover_estimated  INTEGER NOT NULL DEFAULT 0    -- 1 = turnover computed as LTP x volume
);

-- Master list of symbols. sector is empty until you import a sector map (import-sectors).
CREATE TABLE IF NOT EXISTS stock (
    symbol      TEXT PRIMARY KEY,
    name        TEXT,
    sector      TEXT,
    first_seen  TEXT NOT NULL,
    last_seen   TEXT NOT NULL
);

-- Full end-of-day table: one row per stock per trading day.
CREATE TABLE IF NOT EXISTS daily_price (
    trade_date          TEXT NOT NULL REFERENCES trading_day(trade_date) ON DELETE CASCADE,
    symbol              TEXT NOT NULL,
    open                REAL,
    high                REAL,
    low                 REAL,
    close               REAL,
    ltp                 REAL,
    prev_close          REAL,
    vwap                REAL,
    volume              REAL,                         -- shares traded
    turnover            REAL,                         -- rupees
    trades              INTEGER,                      -- number of transactions
    change_pct          REAL,
    turnover_estimated  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (trade_date, symbol)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_daily_price_symbol ON daily_price(symbol, trade_date);

-- The daily top-N lists (top 20 by turnover and top 20 by volume).
CREATE TABLE IF NOT EXISTS top_ranking (
    trade_date  TEXT NOT NULL REFERENCES trading_day(trade_date) ON DELETE CASCADE,
    list_type   TEXT NOT NULL CHECK (list_type IN ('TURNOVER', 'VOLUME')),
    rank        INTEGER NOT NULL,
    symbol      TEXT NOT NULL,
    value       REAL NOT NULL,
    PRIMARY KEY (trade_date, list_type, rank)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_top_ranking_symbol ON top_ranking(symbol, trade_date);

-- Bonus / right shares / splits. Not filled by the crawler; add rows yourself so long-term
-- studies can adjust prices (a 20% bonus otherwise looks like a 17% crash on the ex-date).
CREATE TABLE IF NOT EXISTS corporate_action (
    symbol       TEXT NOT NULL,
    ex_date      TEXT NOT NULL,
    action_type  TEXT NOT NULL CHECK (action_type IN ('BONUS', 'RIGHT', 'SPLIT', 'CASH_DIVIDEND', 'MERGER')),
    ratio_pct    REAL,                                -- e.g. 20 for a 20% bonus
    note         TEXT,
    PRIMARY KEY (symbol, ex_date, action_type)
);

-- Audit trail of every crawl attempt.
CREATE TABLE IF NOT EXISTS crawl_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    run_at      TEXT NOT NULL,
    source      TEXT,
    status      TEXT NOT NULL,                        -- STORED | SKIPPED | FAILED
    trade_date  TEXT,
    message     TEXT
);
CREATE INDEX IF NOT EXISTS idx_crawl_log_run_at ON crawl_log(run_at);

-- Prices with forward closes and 20-day averages, for rule back-tests.
CREATE VIEW IF NOT EXISTS v_price_forward AS
SELECT p.trade_date, p.symbol, p.open, p.high, p.low, p.close, p.prev_close, p.volume, p.turnover,
       p.trades, p.change_pct,
       LEAD(p.close, 1)  OVER w AS close_next_1d,
       LEAD(p.close, 5)  OVER w AS close_next_5d,
       LEAD(p.close, 10) OVER w AS close_next_10d,
       LEAD(p.close, 20) OVER w AS close_next_20d,
       AVG(p.volume)   OVER (w ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING) AS avg_volume_20d,
       AVG(p.turnover) OVER (w ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING) AS avg_turnover_20d
FROM daily_price p
WINDOW w AS (PARTITION BY p.symbol ORDER BY p.trade_date);

-- Every top-list appearance with relative volume and what the price did afterwards.
CREATE VIEW IF NOT EXISTS v_top_list_performance AS
SELECT r.trade_date, r.list_type, r.rank, r.symbol, s.sector, r.value,
       f.close, f.change_pct, f.trades,
       ROUND(f.volume / NULLIF(f.avg_volume_20d, 0), 2)      AS rvol,
       ROUND(f.turnover / NULLIF(f.trades, 0), 0)            AS avg_trade_size,
       ROUND((f.close_next_1d  / f.close - 1) * 100, 2)      AS fwd_ret_1d_pct,
       ROUND((f.close_next_5d  / f.close - 1) * 100, 2)      AS fwd_ret_5d_pct,
       ROUND((f.close_next_10d / f.close - 1) * 100, 2)      AS fwd_ret_10d_pct,
       ROUND((f.close_next_20d / f.close - 1) * 100, 2)      AS fwd_ret_20d_pct
FROM top_ranking r
JOIN v_price_forward f ON f.trade_date = r.trade_date AND f.symbol = r.symbol
LEFT JOIN stock s ON s.symbol = r.symbol;
