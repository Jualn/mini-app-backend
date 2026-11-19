-- Additive only. Historical intents have no trustworthy creation-time template evidence.
ALTER TABLE notification_delivery
    ADD COLUMN template_snapshot_version SMALLINT UNSIGNED NULL,
    ADD COLUMN template_snapshot JSON NULL;
