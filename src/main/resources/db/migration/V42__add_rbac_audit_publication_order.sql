-- Internal high-water mark only: no public ID, personnel, source, or catalog changes.
-- Governance serializes successful writers using the ROOT mutex before inserting Change.
ALTER TABLE rbac_authorization_change
    ADD COLUMN publication_order BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ADD UNIQUE KEY uk_rbac_change_publication_order (publication_order);
