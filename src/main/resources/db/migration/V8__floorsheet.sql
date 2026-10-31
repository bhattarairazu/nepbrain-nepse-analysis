-- Floorsheet: every transaction of a session (buyer and seller broker), collected once per day after the close.
-- ShareSansar only serves the current session's floorsheet, so history builds up day by day. Rows are never deleted.

CREATE TABLE floorsheet_trade (
    contract_no  VARCHAR(30)    NOT NULL,
    trade_date   DATE           NOT NULL,
    symbol       VARCHAR(20)    NOT NULL,
    buyer        SMALLINT       NOT NULL,
    seller       SMALLINT       NOT NULL,
    quantity     DECIMAL(14, 2) NOT NULL,
    rate         DECIMAL(12, 2) NOT NULL,
    amount       DECIMAL(16, 2) NOT NULL,
    PRIMARY KEY (contract_no),
    KEY idx_fs_date_symbol (trade_date, symbol),
    KEY idx_fs_date_buyer (trade_date, buyer),
    KEY idx_fs_date_seller (trade_date, seller)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- One row per collected session: how complete it is compared with the day's market turnover.
CREATE TABLE floorsheet_day (
    trade_date     DATE           NOT NULL,
    fetched_at     DATETIME       NOT NULL,
    rows_stored    INT            NOT NULL,
    rows_reported  INT            NULL COMMENT 'total the site reported',
    total_amount   DECIMAL(18, 2) NULL,
    complete       TINYINT(1)     NOT NULL DEFAULT 0,
    note           VARCHAR(255)   NULL,
    PRIMARY KEY (trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Per session, per stock, per broker totals (self-trades, where buyer = seller, kept separately).
CREATE TABLE broker_stock_daily (
    trade_date   DATE           NOT NULL,
    symbol       VARCHAR(20)    NOT NULL,
    broker       SMALLINT       NOT NULL,
    buy_qty      DECIMAL(16, 2) NOT NULL DEFAULT 0,
    buy_amount   DECIMAL(18, 2) NOT NULL DEFAULT 0,
    sell_qty     DECIMAL(16, 2) NOT NULL DEFAULT 0,
    sell_amount  DECIMAL(18, 2) NOT NULL DEFAULT 0,
    self_qty     DECIMAL(16, 2) NOT NULL DEFAULT 0 COMMENT 'quantity traded with itself (buyer = seller)',
    PRIMARY KEY (trade_date, symbol, broker),
    KEY idx_bsd_broker (broker, trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
