-- Additive transition only. No inferred Organization mapping or historical signature backfill.
ALTER TABLE article MODIFY publisher_id BIGINT UNSIGNED NULL,
 ADD publisher_org_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD publisher_name_snapshot VARCHAR(120) NULL,
 ADD KEY idx_article_publisher_org (publisher_org_id,updated_at,id),
 ADD CONSTRAINT fk_article_publisher_org FOREIGN KEY(publisher_org_id) REFERENCES person_organization(id);
CREATE TABLE article_write_guard (id TINYINT NOT NULL PRIMARY KEY) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO article_write_guard VALUES(1);
-- Offline, approved evidence only; no public mapping/claim endpoint and no automatic rows.
CREATE TABLE article_publisher_organization_mapping (
 publisher_id BIGINT UNSIGNED PRIMARY KEY,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 approved_by BIGINT UNSIGNED NOT NULL, approved_at DATETIME(6) NOT NULL,
 evidence_reference VARCHAR(500) NOT NULL,
 CONSTRAINT fk_article_mapping_publisher FOREIGN KEY(publisher_id) REFERENCES article_publisher(id),
 CONSTRAINT fk_article_mapping_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT ck_article_mapping_evidence CHECK(CHAR_LENGTH(TRIM(evidence_reference))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE article_organization_audit (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, article_id BIGINT UNSIGNED NOT NULL,
 actor BIGINT UNSIGNED NOT NULL, operation VARCHAR(24) NOT NULL,
 old_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 new_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 old_signature VARCHAR(120) NULL, new_signature VARCHAR(120) NULL,
 authorization_sources JSON NOT NULL, evaluated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_article_org_audit_article FOREIGN KEY(article_id) REFERENCES article(id),
 CONSTRAINT ck_article_org_audit_sources CHECK(JSON_TYPE(authorization_sources)='ARRAY' AND JSON_LENGTH(authorization_sources)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- Per-article historical signature evidence is distinct from a Publisher-to-Organization mapping.
CREATE TABLE article_publisher_migration_evidence (
 article_id BIGINT UNSIGNED PRIMARY KEY,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 original_signature VARCHAR(120) NOT NULL,
 approved_by BIGINT UNSIGNED NOT NULL, approved_at DATETIME(6) NOT NULL,
 evidence_reference VARCHAR(500) NOT NULL,
 CONSTRAINT fk_article_signature_evidence FOREIGN KEY(article_id) REFERENCES article(id),
 CONSTRAINT fk_article_signature_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT ck_article_signature_evidence CHECK(CHAR_LENGTH(TRIM(evidence_reference))>0 AND CHAR_LENGTH(TRIM(original_signature))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
