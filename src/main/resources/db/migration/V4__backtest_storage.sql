-- Backtest results are kept permanently: every run, each strategy's summary, and every simulated trade.
-- Nothing in the application deletes from these tables. An identical run (same kind, settings and data range)
-- is not stored twice.

CREATE TABLE backtest_run (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    run_at             DATETIME      NOT NULL COMMENT 'Nepal time',
    kind               VARCHAR(20)   NOT NULL COMMENT 'STANDARD = textbook rules with exits, CUSTOM = app rules with fixed holding period',
    data_from          DATE          NOT NULL,
    data_to            DATE          NOT NULL,
    sessions           INT           NOT NULL,
    horizon            INT           NULL     COMMENT 'holding sessions (CUSTOM runs)',
    market_filter      TINYINT(1)    NOT NULL DEFAULT 0,
    cost_pct           DECIMAL(5, 2) NOT NULL,
    market_return_pct  DECIMAL(9, 3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_backtest_run (kind, data_from, data_to, sessions, horizon, market_filter),
    KEY idx_backtest_run_to (data_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE backtest_result (
    run_id         BIGINT         NOT NULL,
    strategy       VARCHAR(40)    NOT NULL,
    title          VARCHAR(120)   NOT NULL,
    trades         INT            NOT NULL,
    win_rate       DECIMAL(6, 2)  NULL,
    avg_net_pct    DECIMAL(9, 3)  NULL,
    median_net_pct DECIMAL(9, 3)  NULL,
    avg_vs_market  DECIMAL(9, 3)  NULL,
    first_half     DECIMAL(9, 3)  NULL,
    second_half    DECIMAL(9, 3)  NULL,
    profit_factor  DECIMAL(12, 3) NULL,
    verdict        VARCHAR(60)    NOT NULL,
    PRIMARY KEY (run_id, strategy),
    CONSTRAINT fk_backtest_result_run FOREIGN KEY (run_id) REFERENCES backtest_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE backtest_trade (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    run_id       BIGINT         NOT NULL,
    strategy     VARCHAR(40)    NOT NULL,
    symbol       VARCHAR(20)    NOT NULL,
    signal_date  DATE           NOT NULL,
    entry_date   DATE           NOT NULL,
    entry_price  DECIMAL(12, 2) NOT NULL,
    exit_date    DATE           NOT NULL,
    exit_price   DECIMAL(12, 2) NOT NULL,
    held         INT            NULL,
    exit_reason  VARCHAR(10)    NULL COMMENT 'rule | stop | time (STANDARD runs)',
    net_pct      DECIMAL(9, 3)  NOT NULL COMMENT 'after costs',
    market_pct   DECIMAL(9, 3)  NULL,
    PRIMARY KEY (id),
    KEY idx_backtest_trade_run (run_id, strategy),
    KEY idx_backtest_trade_symbol (symbol, signal_date),
    CONSTRAINT fk_backtest_trade_run FOREIGN KEY (run_id) REFERENCES backtest_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
