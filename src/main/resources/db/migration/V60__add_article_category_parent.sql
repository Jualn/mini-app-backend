-- Existing categories remain top-level; no content, IDs, names or revisions are changed.
ALTER TABLE article_category
 ADD COLUMN parent_id BIGINT UNSIGNED NULL,
 ADD INDEX idx_article_category_parent (parent_id),
 ADD CONSTRAINT fk_article_category_parent FOREIGN KEY (parent_id) REFERENCES article_category(id);
