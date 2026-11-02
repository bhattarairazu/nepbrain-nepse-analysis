-- Pattern scanner: every stock the "quiet coil + rising turnover" rule picked, per session, kept permanently so past
-- recommendations can be checked against what happened afterwards. Nothing in the application deletes from this table.
CREATE TABLE pattern_signal (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    rule            VARCHAR(30)    NOT NULL,
    signal_date     DATE           NOT NULL COMMENT 'the session whose close produced the pick; buy at the next open',
    symbol          VARCHAR(20)    NOT NULL,
    sector          VARCHAR(80)    NULL,
    close_price     DECIMAL(12, 2) NOT NULL,
    target_pct      DECIMAL(5, 2)  NOT NULL,
    stop_pct        DECIMAL(5, 2)  NOT NULL,
    max_sessions    INT            NOT NULL,
    atr_pct         DECIMAL(9, 3)  NULL,
    range20_pct     DECIMAL(9, 3)  NULL,
    ret5_pct        DECIMAL(9, 3)  NULL,
    macd_hist_pct   DECIMAL(9, 3)  NULL,
    from_low60_pct  DECIMAL(9, 3)  NULL,
    turnover_x      DECIMAL(9, 3)  NULL COMMENT 'last 5 sessions average turnover / the 60 before',
    updown_vol      DECIMAL(9, 3)  NULL,
    rsi14           DECIMAL(6, 2)  NULL,
    turnover        DECIMAL(18, 2) NULL,
    source          VARCHAR(10)    NOT NULL COMMENT 'LIVE = recorded right after that day''s crawl, REPLAY = reconstructed later from stored history',
    recorded_at     DATETIME       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pattern_signal (rule, signal_date, symbol),
    KEY idx_pattern_signal_date (signal_date),
    KEY idx_pattern_signal_symbol (symbol, signal_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
