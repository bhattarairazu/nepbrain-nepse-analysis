-- Records whether a stock's sector was guessed from its company name (AUTO) or set by you (MANUAL),
-- so guesses can be reviewed on the Sectors page and never overwrite your own choices.
ALTER TABLE stock
    ADD COLUMN sector_source ENUM ('AUTO', 'MANUAL') NULL AFTER sector;
