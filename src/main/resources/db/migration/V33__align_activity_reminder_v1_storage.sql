-- Expand only: preserve existing timeline values and reminder recovery evidence.
ALTER TABLE timeline
    MODIFY COLUMN label VARCHAR(120) NOT NULL,
    MODIFY COLUMN description VARCHAR(2000) NULL,
    MODIFY COLUMN time_description VARCHAR(500) NULL;

-- Freeze the receiver scan bound on first execution; retry scans the same bounded population.
ALTER TABLE notify_plan
    ADD COLUMN recipient_upper_user_id BIGINT UNSIGNED NULL;

-- Activity titles already allow 200 characters; retain them in future reminder snapshots.
ALTER TABLE notify_plan MODIFY COLUMN title VARCHAR(200) NOT NULL;
ALTER TABLE notification MODIFY COLUMN title VARCHAR(200) NOT NULL;
