-- Immutable safe projections. Original owner journals and their retention remain unchanged.
CREATE TABLE admin_audit_guard (
 id TINYINT NOT NULL COMMENT 'Singleton allocation/commit serialization guard',
 PRIMARY KEY(id)
) ENGINE=InnoDB COMMENT='Serializes management audit allocation until owner transaction commits';
INSERT INTO admin_audit_guard(id) VALUES(1);
CREATE TABLE admin_audit_record (
 sequence BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Internal committed high-water order, never a public identity',
 id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable owner event identity plus projection kind',
 kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DUTY or OPERATION projection',
 source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Duty source enum or canonical command operationId',
 operation VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Canonical command operationId or original owner event operation',
 actor_user VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Original actor User ID; NULL only for evidenced system events',
 target_user VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Account whose original fact changed',
 target_person VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Person association fixed by source event, never current binding',
 target_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Owning business object type',
 target_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Stable owning business object ID',
 result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SUCCEEDED,REJECTED,FAILED,UNKNOWN',
 recorded_at DATETIME(6) NOT NULL COMMENT 'Immutable event recording instant in UTC, microseconds',
 payload JSON NOT NULL COMMENT 'Closed safe public projection, never original requests, names or materials',
 PRIMARY KEY(sequence), UNIQUE KEY uk_admin_audit_id(id),
 KEY idx_admin_audit_kind_time(kind,recorded_at,id),
 KEY idx_admin_audit_user(kind,target_user,recorded_at,id),
 KEY idx_admin_audit_person(kind,target_person,recorded_at,id),
 KEY idx_admin_audit_operation(kind,operation,recorded_at,id),
 CONSTRAINT chk_admin_audit_kind CHECK(kind IN ('DUTY','OPERATION')),
 CONSTRAINT chk_admin_audit_result CHECK(result IN ('SUCCEEDED','REJECTED','FAILED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
 COMMENT='Append-only ROOT management audit safe projections accepted atomically by source owners';
