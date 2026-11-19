-- Preserve bound private snapshots while durably reclaiming expired client-writable source objects.
ALTER TABLE media_point_file ADD COLUMN source_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_point_media_source_cleanup ON media_point_file(state,source_deleted,upload_expires_at);
