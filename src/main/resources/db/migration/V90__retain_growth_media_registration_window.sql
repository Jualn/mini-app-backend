ALTER TABLE growth_media_context
    ADD COLUMN registered_at DATETIME(6) NULL COMMENT 'Beijing first completed technical registration; replay never renews it',
    ADD COLUMN unbound_expires_at DATETIME(6) NULL COMMENT 'Beijing registered_at plus 24 hours for unbound candidates; NULL after formal takeover';
