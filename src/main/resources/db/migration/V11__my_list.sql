-- My list: stocks you add and remove yourself (separate from the automatic signal watchlist), with your own plan for each.
CREATE TABLE my_list_item (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    symbol       VARCHAR(20)    NOT NULL,
    intent       VARCHAR(10)    NOT NULL DEFAULT 'WATCH' COMMENT 'WATCH | BUY | HOLD | SELL',
    added_date   DATE           NOT NULL COMMENT 'latest trading day when it was added',
    added_price  DECIMAL(12, 2) NULL     COMMENT 'close on that day',
    buy_below    DECIMAL(12, 2) NULL     COMMENT 'your buy zone: buy at or under this price',
    stop_price   DECIMAL(12, 2) NULL     COMMENT 'exit if it closes at or under this price',
    sell_above   DECIMAL(12, 2) NULL     COMMENT 'take profit at or above this price',
    note         VARCHAR(1000)  NULL,
    created_at   DATETIME       NOT NULL,
    updated_at   DATETIME       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_my_list_symbol (symbol)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
