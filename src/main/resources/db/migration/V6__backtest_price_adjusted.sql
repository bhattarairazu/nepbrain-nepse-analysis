-- Runs before this migration used prices NOT adjusted for bonus/right shares (price_adjusted = 0).
-- They are kept for the record; adjusted runs are stored alongside with price_adjusted = 1.
ALTER TABLE backtest_run
    ADD COLUMN price_adjusted TINYINT(1) NOT NULL DEFAULT 0 AFTER market_filter,
    DROP INDEX uq_backtest_run,
    ADD UNIQUE KEY uq_backtest_run (kind, data_from, data_to, sessions, horizon, market_filter, price_adjusted);
