-- Canonical Section has no content-category enum; title and format carry its display semantics.
ALTER TABLE event_section
    DROP COLUMN section_type;
