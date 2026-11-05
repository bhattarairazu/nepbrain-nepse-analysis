-- Listed shares of each company, read from its public ShareSansar company page. Market cap = latest close x listed shares.
-- One row per symbol, replaced on each refresh.
CREATE TABLE company_shares (
    symbol          VARCHAR(20)    NOT NULL,
    listed_shares   BIGINT         NULL,
    paid_up         DECIMAL(10, 2) NULL COMMENT 'face value per share, Rs',
    status          VARCHAR(10)    NOT NULL COMMENT 'OK, NOT_FOUND (no company page), NO_DATA (page without listed shares)',
    fetched_at      DATETIME       NOT NULL,
    PRIMARY KEY (symbol)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
