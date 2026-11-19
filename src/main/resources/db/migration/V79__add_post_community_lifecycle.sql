-- No historical deletion source or hidden reply is inferred by this migration.
ALTER TABLE post
 ADD COLUMN community_version BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'True only for NORMAL submissions accepted by the community writer',
 ADD COLUMN accepted_at DATETIME(6) NULL COMMENT 'Current accepted submission time in Asia/Shanghai',
 ADD COLUMN temporary_visible_until DATETIME(6) NULL COMMENT 'Fixed deadline granted only after synchronous preflight checks',
 ADD COLUMN admin_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Proven management hide; independent of author deletion';
CREATE TABLE post_creation_receipt (
 actor_id BIGINT NOT NULL COMMENT 'Authenticated account owning the intent',
 operation VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'POST or COMMENT creation scope',
 request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Caller intent key; retained without automatic expiry',
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA256 of normalized command',
 target_id BIGINT NULL COMMENT 'Accepted owner resource ID; set in the same transaction',
 created_at DATETIME(6) NOT NULL COMMENT 'Intent acceptance time Asia/Shanghai',
 PRIMARY KEY(actor_id,operation,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Private permanent community creation intent receipts; no content snapshots';
CREATE TABLE post_management_history (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Stable management effect identity',
 post_id BIGINT NOT NULL COMMENT 'Post owner resource',
 operator_id BIGINT NOT NULL COMMENT 'Authenticated management account',
 action VARCHAR(24) NOT NULL COMMENT 'TAKE_DOWN, RESTORE or CHANGE_CATEGORY',
 reason VARCHAR(500) NOT NULL COMMENT 'Required management reason',
 revision BIGINT NOT NULL COMMENT 'Editable revision affected by this effect',
 created_at DATETIME(6) NOT NULL COMMENT 'Effect time Asia/Shanghai',
 PRIMARY KEY(id), KEY idx_post_management(post_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Proven management effects committed with post state';
CREATE TABLE post_favorite (
 actor_id BIGINT NOT NULL COMMENT 'Account owning the private relation',
 post_id BIGINT NOT NULL COMMENT 'NORMAL post, retained when target becomes unavailable',
 favorited_at DATETIME(6) NOT NULL COMMENT 'First favorite time Asia/Shanghai; replay does not change it',
 PRIMARY KEY(actor_id,post_id), KEY idx_post_favorite_page(actor_id,favorited_at,post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Current private post favorite relations without content snapshots';
CREATE TABLE post_view_history (
 actor_id BIGINT NOT NULL COMMENT 'Account owning the private history',
 post_id BIGINT NOT NULL COMMENT 'NORMAL post identity',
 last_viewed_at DATETIME(6) NOT NULL COMMENT 'Last valid opening Asia/Shanghai; visible for exactly 90 days',
 PRIMARY KEY(actor_id,post_id), KEY idx_post_history_page(actor_id,last_viewed_at,post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Recent valid post openings, independent of public daily deduplication';
CREATE TABLE post_view_day (
 actor_id BIGINT NOT NULL COMMENT 'Authenticated viewing account',
 post_id BIGINT NOT NULL COMMENT 'Viewed NORMAL post',
 view_day DATE NOT NULL COMMENT 'Asia/Shanghai business day',
 PRIMARY KEY(actor_id,post_id,view_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable daily public count facts; history clearing never removes these';
CREATE TABLE post_share_event (
 actor_id BIGINT NOT NULL COMMENT 'Authenticated sharing account',
 event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Caller stable observable action identity',
 post_id BIGINT NOT NULL COMMENT 'Shared NORMAL post',
 action VARCHAR(32) NOT NULL COMMENT 'WECHAT_SHARE_INVOKED or POSTER_SAVED',
 platform INT NULL COMMENT 'Legacy platform 1 chat or 2 moments',
 PRIMARY KEY(actor_id,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stable observable post share intent deduplication; no delivery claim';

ALTER TABLE comment ADD COLUMN community_thread BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'New NORMAL reply may survive root removal; historical replies are never auto exposed';

ALTER TABLE report ADD COLUMN outcome VARCHAR(32) NULL COMMENT 'Fixed sanitized resolution outcome; historical unknown remains null';
CREATE TABLE report_resolution (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Immutable resolution identity',
 target_type VARCHAR(16) NOT NULL COMMENT 'post or comment case namespace',
 target_id BIGINT NOT NULL COMMENT 'Case target resource',
 decision VARCHAR(16) NOT NULL COMMENT 'violation or normal',
 content_action VARCHAR(16) NOT NULL COMMENT 'Actual NONE or TAKE_DOWN effect',
 outcome VARCHAR(32) NOT NULL COMMENT 'Sanitized result fixed at resolution',
 handler_id BIGINT NOT NULL COMMENT 'Authorized handler account',
 remark VARCHAR(500) NOT NULL COMMENT 'Internal decision reason',
 handled_at DATETIME(6) NOT NULL COMMENT 'Resolution time Asia/Shanghai',
 report_ids JSON NOT NULL COMMENT 'Exact covered report IDs at this resolution',
 PRIMARY KEY(id),KEY idx_report_resolution(target_type,target_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Fixed report case resolution facts; later reports are not automatically closed';
