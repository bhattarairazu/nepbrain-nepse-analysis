-- Notes: your own dated notes (usually for the next session), optionally about one stock, each with conditions to check.
-- A condition is either a tick box you check yourself (MANUAL) or a price condition the app checks against stored prices (PRICE).
CREATE TABLE note (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    for_date    DATE          NOT NULL COMMENT 'the session the note is for',
    symbol      VARCHAR(20)   NULL,
    title       VARCHAR(200)  NOT NULL,
    body        TEXT          NULL,
    status      VARCHAR(10)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN | DONE',
    decision    VARCHAR(500)  NULL     COMMENT 'what you decided when closing it',
    created_at  DATETIME      NOT NULL,
    updated_at  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_note_date (status, for_date),
    KEY idx_note_symbol (symbol)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE note_condition (
    id        BIGINT         NOT NULL AUTO_INCREMENT,
    note_id   BIGINT         NOT NULL,
    position  INT            NOT NULL,
    kind      VARCHAR(10)    NOT NULL COMMENT 'MANUAL | PRICE',
    text      VARCHAR(300)   NOT NULL COMMENT 'as you wrote it',
    symbol    VARCHAR(20)    NULL,
    metric    VARCHAR(10)    NULL     COMMENT 'CLOSE | HIGH | LOW | CHANGE | TURNOVER | VOLUME',
    op        VARCHAR(2)     NULL     COMMENT '>= or <=',
    value     DECIMAL(18, 2) NULL,
    checked   TINYINT(1)     NOT NULL DEFAULT 0 COMMENT 'manual conditions: ticked by you',
    PRIMARY KEY (id),
    KEY idx_note_condition_note (note_id),
    CONSTRAINT fk_note_condition_note FOREIGN KEY (note_id) REFERENCES note (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
