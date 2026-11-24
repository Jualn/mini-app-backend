-- Preserve explicit, unambiguous bindings. Reject conflicts before changing the directory.
CREATE TEMPORARY TABLE article_scene_root_preflight (
    ok TINYINT NOT NULL CHECK (ok = 1)
);

INSERT INTO article_scene_root_preflight
SELECT IF(
    NOT EXISTS (
        SELECT 1 FROM article_category
        WHERE scene_codes IS NOT NULL AND (
            JSON_TYPE(scene_codes) <> 'ARRAY'
            OR (JSON_LENGTH(scene_codes) <> 0 AND (
                parent_id IS NOT NULL
                OR JSON_LENGTH(scene_codes) <> 1
                OR JSON_UNQUOTE(JSON_EXTRACT(scene_codes, '$[0]')) COLLATE utf8mb4_0900_bin NOT IN (
                    'PARTY_NEWS', 'RED_BOOK_CORNER', 'RED_CLOUD_CLASSROOM',
                    'COMPETITION_RESOURCES', 'E_SAFETY', 'LEARNING_MATERIALS'
                )
            ))
        )
    )
    AND NOT EXISTS (
        SELECT 1 FROM article_category
        WHERE JSON_LENGTH(scene_codes) = 1
        GROUP BY JSON_UNQUOTE(JSON_EXTRACT(scene_codes, '$[0]')) COLLATE utf8mb4_0900_bin
        HAVING COUNT(*) > 1
    )
    AND (SELECT COUNT(*) FROM article_category)
        + 6 - (SELECT COUNT(*) FROM article_category WHERE JSON_LENGTH(scene_codes) = 1) <= 100,
    1, 0
);
DROP TEMPORARY TABLE article_scene_root_preflight;

-- Actor 0 identifies migration-created directory rows. Existing names and article IDs are preserved.
INSERT INTO article_category
    (name, display_order, enabled, revision, created_at, updated_at, created_by, updated_by, scene_codes)
SELECT CASE WHEN existing.id IS NULL THEN seed.name ELSE CONCAT(seed.name, '（固定入口）') END,
       seed.display_order, TRUE, 1,
       DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 8 HOUR), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 8 HOUR),
       0, 0, JSON_ARRAY(seed.scene_code)
FROM (
    SELECT 'PARTY_NEWS' AS scene_code, '党建资讯' AS name, 10 AS display_order
    UNION ALL SELECT 'RED_BOOK_CORNER', '红色书角', 20
    UNION ALL SELECT 'RED_CLOUD_CLASSROOM', '红色云课堂', 30
    UNION ALL SELECT 'COMPETITION_RESOURCES', '竞赛资源库', 40
    UNION ALL SELECT 'E_SAFETY', 'E·安全', 50
    UNION ALL SELECT 'LEARNING_MATERIALS', '学习资料', 60
) seed
LEFT JOIN article_category existing ON existing.name = seed.name COLLATE utf8mb4_0900_bin
WHERE NOT EXISTS (
    SELECT 1 FROM article_category mapped
    WHERE JSON_CONTAINS(mapped.scene_codes, JSON_QUOTE(seed.scene_code))
);

ALTER TABLE article_category
    ADD COLUMN fixed_scene_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
        GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(scene_codes, '$[0]'))) STORED,
    ADD UNIQUE KEY uk_article_category_fixed_scene (fixed_scene_code),
    ADD CONSTRAINT chk_article_category_fixed_scene CHECK (
        scene_codes IS NULL OR (
            JSON_TYPE(scene_codes) = 'ARRAY'
            AND (JSON_LENGTH(scene_codes) = 0 OR (
                JSON_LENGTH(scene_codes) = 1 AND parent_id IS NULL
                AND fixed_scene_code IS NOT NULL AND fixed_scene_code IN (
                    'PARTY_NEWS', 'RED_BOOK_CORNER', 'RED_CLOUD_CLASSROOM',
                    'COMPETITION_RESOURCES', 'E_SAFETY', 'LEARNING_MATERIALS'
                )
            ))
        )
    );
