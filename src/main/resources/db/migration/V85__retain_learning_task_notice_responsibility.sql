CREATE TABLE learning_task_notice (
    occurrence VARCHAR(200) NOT NULL COMMENT 'LearningTask business occurrence; immutable notification acceptance identity',
    task_id BIGINT NOT NULL COMMENT 'Owning LearningTask ID',
    assignment_id BIGINT NOT NULL COMMENT 'Stable responsibility assignment ID',
    type VARCHAR(64) NOT NULL COMMENT 'LearningTask notification business type',
    subject_type VARCHAR(16) NOT NULL COMMENT 'PERSON or ACCOUNT responsibility at occurrence',
    person_id VARCHAR(128) NULL COMMENT 'Stable Person responsibility ID when PERSON',
    subject_user_id VARCHAR(128) NULL COMMENT 'Selected account responsibility ID when ACCOUNT',
    delivery_user_id BIGINT NULL COMMENT 'Determinable actual delivery User at occurrence; not reassigned later',
    binding_id VARCHAR(128) NULL COMMENT 'Trusted Person binding at occurrence when determinable',
    availability VARCHAR(64) NOT NULL COMMENT 'AVAILABLE accepted for IN_APP; other codes durably record safe skipped delivery',
    created_at DATETIME(6) NOT NULL COMMENT 'Occurrence acceptance time in Asia/Shanghai',
    PRIMARY KEY (occurrence),
    KEY idx_learning_notice_assignment (assignment_id)
) ENGINE=InnoDB COMMENT='Retained LearningTask notification responsibility and delivery decision; independent of 90 day inbox visibility';

CREATE TABLE article_safety_unpublish (
    article_id BIGINT NOT NULL COMMENT 'Article stopped under existing unpublish authority',
    article_revision BIGINT NOT NULL COMMENT 'Resulting strong Article version',
    actor BIGINT NOT NULL COMMENT 'Authorized User making the explicit safety decision',
    reason VARCHAR(1000) NOT NULL COMMENT 'Explicit safety reason; retained by Article owner',
    created_at DATETIME(6) NOT NULL COMMENT 'Safety decision time in Asia/Shanghai',
    PRIMARY KEY (article_id,article_revision)
) ENGINE=InnoDB COMMENT='Immutable explicit Article safety unpublish reasons; joins existing organization authorization audit by Article revision and actor';
