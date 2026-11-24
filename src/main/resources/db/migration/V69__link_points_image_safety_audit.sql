-- Scene 12 belongs exclusively to automatic private Points image checks.
CREATE TEMPORARY TABLE points_image_audit_preflight (ok TINYINT NOT NULL CHECK(ok=1));
INSERT INTO points_image_audit_preflight SELECT IF((SELECT COUNT(*) FROM content_audit_log WHERE target_type=12)=0,1,0);
DROP TEMPORARY TABLE points_image_audit_preflight;

ALTER TABLE media_point_file
 ADD COLUMN safety_audit_log_id BIGINT UNSIGNED NULL COMMENT 'One automatic WeChat image check for this immutable retained object; NULL means not submitted or document exempt',
 ADD UNIQUE KEY uq_point_safety_audit(safety_audit_log_id),
 ADD CONSTRAINT fk_point_safety_audit FOREIGN KEY(safety_audit_log_id) REFERENCES content_audit_log(id),
 COMMENT='Private Points files, immutable retention, ownership and optional automatic image audit binding';
