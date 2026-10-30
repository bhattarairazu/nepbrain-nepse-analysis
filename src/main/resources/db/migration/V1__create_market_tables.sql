-- NEPSE market data, version 1.
--
-- Every trading day stores the FULL ShareSansar price table (~350-400 stocks), not only the
-- top 20, so later studies can measure what happened to a stock after it entered a list,
-- compare its activity with its own history, and run new rules on old data.
--
-- Size for 15 years: ~400 stocks x ~245 sessions x 15 years = ~1.5 million daily_price rows
-- (a few hundred MB in InnoDB). The (trade_date, symbol) primary key and the (symbol, trade_date)
-- index cover both "one day, all stocks" and "one stock, its history" queries.
-- Never edit this file once applied; add V2__..., V3__... migrations instead.

CREATE TABLE trading_day (
    trade_date      DATE           NOT NULL,
    source          VARCHAR(32)    NOT NULL,
    crawled_at      DATETIME       NOT NULL COMMENT 'Nepal time',
    stock_count     SMALLINT       NOT NULL,
    total_turnover  DECIMAL(18, 2) NULL     COMMENT 'Rs',
    total_volume    DECIMAL(18, 2) NULL     COMMENT 'shares',
    total_trades    INT            NULL,
    PRIMARY KEY (trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE stock (
    symbol      VARCHAR(20)  NOT NULL,
    name        VARCHAR(200) NULL,
    sector      VARCHAR(80)  NULL,
    first_seen  DATE         NOT NULL,
    last_seen   DATE         NOT NULL,
    PRIMARY KEY (symbol),
    KEY idx_stock_sector (sector)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE daily_price (
    trade_date   DATE           NOT NULL,
    symbol       VARCHAR(20)    NOT NULL,
    open_price   DECIMAL(12, 2) NULL,
    high_price   DECIMAL(12, 2) NULL,
    low_price    DECIMAL(12, 2) NULL,
    close_price  DECIMAL(12, 2) NULL,
    ltp          DECIMAL(12, 2) NULL,
    prev_close   DECIMAL(12, 2) NULL,
    vwap         DECIMAL(12, 2) NULL,
    volume       DECIMAL(16, 2) NULL COMMENT 'shares traded',
    turnover     DECIMAL(18, 2) NULL COMMENT 'Rs',
    trades       INT            NULL COMMENT 'number of transactions',
    change_pct   DECIMAL(8, 2)  NULL,
    PRIMARY KEY (trade_date, symbol),
    KEY idx_daily_price_symbol_date (symbol, trade_date),
    CONSTRAINT fk_daily_price_day FOREIGN KEY (trade_date) REFERENCES trading_day (trade_date) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE top_ranking (
    trade_date    DATE                        NOT NULL,
    list_type     ENUM ('TURNOVER', 'VOLUME') NOT NULL,
    rank_no       TINYINT UNSIGNED            NOT NULL,
    symbol        VARCHAR(20)                 NOT NULL,
    metric_value  DECIMAL(18, 2)              NOT NULL COMMENT 'turnover in Rs or volume in shares',
    PRIMARY KEY (trade_date, list_type, rank_no),
    KEY idx_top_ranking_symbol (symbol, trade_date),
    CONSTRAINT fk_top_ranking_day FOREIGN KEY (trade_date) REFERENCES trading_day (trade_date) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Bonus / right shares / splits, entered by you, for adjusting long-term price studies.
CREATE TABLE corporate_action (
    symbol       VARCHAR(20)                                                    NOT NULL,
    ex_date      DATE                                                           NOT NULL,
    action_type  ENUM ('BONUS', 'RIGHT', 'SPLIT', 'CASH_DIVIDEND', 'MERGER')    NOT NULL,
    ratio_pct    DECIMAL(8, 2)                                                  NULL,
    note         VARCHAR(255)                                                   NULL,
    PRIMARY KEY (symbol, ex_date, action_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE crawl_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    run_at      DATETIME      NOT NULL COMMENT 'Nepal time',
    source      VARCHAR(32)   NULL,
    status      VARCHAR(16)   NOT NULL COMMENT 'STORED | SKIPPED | FAILED',
    trade_date  DATE          NULL,
    message     VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_crawl_log_run_at (run_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
