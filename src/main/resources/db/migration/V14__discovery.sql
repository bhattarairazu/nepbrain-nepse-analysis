-- Pattern discovery runs (Run analysis now): each run's settings and periods, and the top rules it found with their results
-- in the find, choose and check periods. Kept permanently; nothing in the application deletes from these tables.
CREATE TABLE discovery_run (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    run_at          DATETIME      NOT NULL,
    data_from       DATE          NOT NULL,
    data_to         DATE          NOT NULL,
    sessions        INT           NOT NULL,
    stocks          INT           NOT NULL,
    stock_days      INT           NOT NULL,
    find_to         DATE          NOT NULL COMMENT 'rules were searched on signals before this date',
    choose_to       DATE          NOT NULL COMMENT 'the best were chosen on signals before this date; later = final check',
    target_pct      DECIMAL(5, 2) NOT NULL,
    stop_pct        DECIMAL(5, 2) NOT NULL,
    max_sessions    INT           NOT NULL,
    base_find_avg   DECIMAL(9, 3) NULL,
    base_choose_avg DECIMAL(9, 3) NULL,
    base_check_avg  DECIMAL(9, 3) NULL,
    base_find_win   DECIMAL(6, 2) NULL,
    base_choose_win DECIMAL(6, 2) NULL,
    base_check_win  DECIMAL(6, 2) NULL,
    duration_ms     BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_discovery_run_at (run_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE discovery_rule (
    run_id        BIGINT        NOT NULL,
    rank_no       INT           NOT NULL,
    rule_text     VARCHAR(1000) NOT NULL COMMENT 'one condition per line',
    rule_spec     VARCHAR(600)  NOT NULL,
    find_n        INT           NOT NULL,
    find_win      DECIMAL(6, 2) NULL,
    find_avg      DECIMAL(9, 3) NULL,
    find_median   DECIMAL(9, 3) NULL,
    choose_n      INT           NOT NULL,
    choose_win    DECIMAL(6, 2) NULL,
    choose_avg    DECIMAL(9, 3) NULL,
    choose_median DECIMAL(9, 3) NULL,
    check_n       INT           NOT NULL,
    check_win     DECIMAL(6, 2) NULL,
    check_avg     DECIMAL(9, 3) NULL,
    check_median  DECIMAL(9, 3) NULL,
    today_symbols VARCHAR(2000) NULL COMMENT 'stocks matching at the latest close of the run',
    PRIMARY KEY (run_id, rank_no),
    CONSTRAINT fk_discovery_rule_run FOREIGN KEY (run_id) REFERENCES discovery_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
