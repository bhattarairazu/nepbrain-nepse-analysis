-- Sectors can now come from ShareSansar's own sector filter (SHARESANSAR), besides name guesses (AUTO)
-- and your edits (MANUAL). Order of trust: MANUAL > SHARESANSAR > AUTO.
ALTER TABLE stock
    MODIFY COLUMN sector_source ENUM ('AUTO', 'MANUAL', 'SHARESANSAR') NULL;
