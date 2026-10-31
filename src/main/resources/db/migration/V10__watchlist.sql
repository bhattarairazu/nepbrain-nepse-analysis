-- Watchlist: every buy setup the studies flagged (and stocks added by hand), with what happened after the signal.
-- Rows are never deleted by the application; "archived" only hides them. One row per stock, setup and signal day.
CREATE TABLE watchlist_item (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    symbol          VARCHAR(20)    NOT NULL,
    sector          VARCHAR(80)    NULL,
    source          VARCHAR(20)    NOT NULL COMMENT 'BASE_BREAKOUT | BASE_COILING | PULLBACK_HOLD | RETEST_TURN | MOVE_SCORE | WASHOUT | MANUAL',
    signal_date     DATE           NOT NULL COMMENT 'the close that triggered it; the buy is at the next open',
    signal_close    DECIMAL(12, 2) NOT NULL,
    score           DECIMAL(9, 3)  NULL,
    detail          VARCHAR(500)   NULL,
    target_pct      DECIMAL(6, 2)  NOT NULL DEFAULT 7,
    stop_pct        DECIMAL(6, 2)  NOT NULL DEFAULT 5,
    window_sessions INT            NOT NULL DEFAULT 10,
    status          VARCHAR(12)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | OPEN | STOP_DUE | TARGET | STOPPED | EXPIRED',
    entry_date      DATE           NULL,
    entry_price     DECIMAL(12, 2) NULL,
    exit_date       DATE           NULL,
    exit_price      DECIMAL(12, 2) NULL,
    net_pct         DECIMAL(9, 3)  NULL COMMENT 'after 0.8% round-trip charges, before tax',
    note            VARCHAR(500)   NULL,
    archived        TINYINT(1)     NOT NULL DEFAULT 0,
    added_at        DATETIME       NOT NULL,
    updated_at      DATETIME       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_watchlist_signal (symbol, source, signal_date),
    KEY idx_watchlist_status (archived, status),
    KEY idx_watchlist_date (signal_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
