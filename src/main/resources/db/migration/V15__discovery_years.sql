-- Run analysis now: each rule's results per calendar year and by market state (equal-weight market below / above its 50-session EMA),
-- and the baseline per year. Stats are stored as "n:win:avg:median"; per-year lists as "year=stats;year=stats".
ALTER TABLE discovery_rule
    ADD COLUMN by_year       VARCHAR(1000) NULL,
    ADD COLUMN weak_market   VARCHAR(80)   NULL,
    ADD COLUMN strong_market VARCHAR(80)   NULL;
ALTER TABLE discovery_run
    ADD COLUMN base_by_year  VARCHAR(1000) NULL;
