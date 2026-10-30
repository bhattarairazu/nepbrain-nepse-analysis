-- Dates the site confirmed had no trading session (holidays, closures). The backfill never requests them again.
CREATE TABLE non_trading_day (
    trade_date  DATE         NOT NULL,
    checked_at  DATETIME     NOT NULL,
    note        VARCHAR(255) NULL,
    PRIMARY KEY (trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
