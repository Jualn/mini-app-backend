ALTER TABLE `media_attachment`
    MODIFY COLUMN `target_type` TINYINT NULL COMMENT '旧归属字段；新事件附件通过 event_attachment_link 关联',
    MODIFY COLUMN `target_id` BIGINT UNSIGNED NULL COMMENT '旧归属字段；新事件附件通过 event_attachment_link 关联',
    ADD COLUMN `kind` VARCHAR(16) NULL AFTER `type`,
    ADD COLUMN `registered` TINYINT(1) NOT NULL DEFAULT 0 AFTER `kind`,
    ADD COLUMN `registered_by` BIGINT UNSIGNED NULL AFTER `registered`;

UPDATE `media_attachment`
SET `kind` = CASE `type`
    WHEN 1 THEN 'LINK'
    WHEN 2 THEN 'IMAGE'
    WHEN 3 THEN 'PDF'
    WHEN 4 THEN 'WORD'
    ELSE 'LINK'
END,
    `registered` = 1
WHERE `kind` IS NULL;

ALTER TABLE `media_attachment`
    MODIFY COLUMN `kind` VARCHAR(16) NOT NULL,
    ADD CONSTRAINT `chk_media_attachment_kind`
        CHECK (`kind` IN ('IMAGE', 'POSTER', 'QR_CODE', 'PDF', 'WORD', 'LINK'));

CREATE TABLE `event_attachment_link` (
    `target_type` TINYINT NOT NULL COMMENT '2-Activity 3-PublicEvent',
    `target_id` BIGINT UNSIGNED NOT NULL,
    `attachment_id` BIGINT UNSIGNED NOT NULL,
    `display_order` INT UNSIGNED NOT NULL,
    PRIMARY KEY (`target_type`, `target_id`, `attachment_id`),
    UNIQUE KEY `uk_event_attachment_order` (`target_type`, `target_id`, `display_order`, `attachment_id`),
    KEY `idx_event_attachment_id` (`attachment_id`),
    CONSTRAINT `fk_event_attachment_attachment`
        FOREIGN KEY (`attachment_id`) REFERENCES `media_attachment` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_event_attachment_target_type` CHECK (`target_type` IN (2, 3))
) ENGINE=InnoDB COMMENT='Activity/PublicEvent 与可复用附件的引用关系';

INSERT IGNORE INTO `event_attachment_link` (`target_type`, `target_id`, `attachment_id`, `display_order`)
SELECT `target_type`, `target_id`, `id`, `sort_order`
FROM `media_attachment`
WHERE `target_type` IN (2, 3) AND `target_id` IS NOT NULL;
