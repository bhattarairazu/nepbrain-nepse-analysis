-- Runs before this migration let some trades be sold within two sessions of buying, which NEPSE's T+2 settlement
-- does not allow (t2_settlement = 0). They are kept for the record; runs that respect T+2 are stored with t2_settlement = 1.
ALTER TABLE backtest_run
    ADD COLUMN t2_settlement TINYINT(1) NOT NULL DEFAULT 0 AFTER price_adjusted,
    DROP INDEX uq_backtest_run,
    ADD UNIQUE KEY uq_backtest_run (kind, data_from, data_to, sessions, horizon, market_filter, price_adjusted, t2_settlement);
