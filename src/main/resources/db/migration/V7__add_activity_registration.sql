-- Additive only: existing subscriptions are not registrations.
ALTER TABLE activity
    ADD COLUMN form_schema JSON NULL,
    ADD COLUMN registration_limit INT UNSIGNED NULL,
    ADD CONSTRAINT chk_activity_registration_limit CHECK (registration_limit IS NULL OR registration_limit > 0);

CREATE TABLE activity_registration (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    form_data JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    submitted_at DATETIME NOT NULL,
    cancelled_at DATETIME NULL,
    invalidated_at DATETIME NULL,
    invalidated_by BIGINT UNSIGNED NULL,
    invalid_reason VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_registration_user (activity_id, user_id),
    KEY idx_activity_registration_status (activity_id, status, submitted_at, id),
    KEY idx_activity_registration_user_status (user_id, status, id),
    CONSTRAINT fk_registration_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES user_profile(id),
    CONSTRAINT chk_registration_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT chk_registration_invalidation CHECK (status <> 3 OR
        (invalidated_at IS NOT NULL AND invalidated_by IS NOT NULL AND invalid_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
