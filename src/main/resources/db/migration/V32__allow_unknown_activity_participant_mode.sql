-- Unknown draft participation is absence, not an inferred individual participation.
-- Preserve historical 0 (unknown) and 3 (either); do not reinterpret or backfill rows.
ALTER TABLE activity
    MODIFY COLUMN participant_mode TINYINT NULL DEFAULT NULL
        COMMENT 'NULL unknown,0 legacy unknown,1 individual,2 team,3 legacy either';
