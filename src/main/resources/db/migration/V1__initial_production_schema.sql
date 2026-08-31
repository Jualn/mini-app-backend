SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `user_profile` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `openid`         VARCHAR(64)     NOT NULL COMMENT '微信openid，唯一标识',
  `mp_openid`      VARCHAR(64)     DEFAULT NULL COMMENT '服务号openid，关注服务号后关联',
  `unionid`        VARCHAR(64)     DEFAULT NULL COMMENT '微信unionid（开放平台）',
  `nickname`       VARCHAR(64)     DEFAULT NULL COMMENT '用户昵称',
  `avatar_url`     VARCHAR(512)    DEFAULT NULL COMMENT '头像URL（COS）',
  `background_url` VARCHAR(512)    DEFAULT NULL COMMENT '个人主页背景图URL（COS）',
  `bio`            VARCHAR(200)    DEFAULT NULL COMMENT '个人简介',
  `gender`         TINYINT         DEFAULT 0 COMMENT '性别：0-未知 1-男 2-女',
  `phone`          VARCHAR(64)     DEFAULT NULL COMMENT '手机号（AES加密存储）',
  `role`           TINYINT         NOT NULL DEFAULT 1 COMMENT '角色：1-普通用户 2-运营 3-管理员',
  `status`         TINYINT         NOT NULL DEFAULT 1 COMMENT '账号状态：1-正常 2-禁言 3-封禁',
  `ban_reason`     VARCHAR(255)    DEFAULT NULL COMMENT '封禁原因',
  `ban_expire_at`  DATETIME        DEFAULT NULL COMMENT '禁言到期时间，NULL表示永久',
  `last_login_at`  DATETIME        DEFAULT NULL COMMENT '最后登录时间',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`     DATETIME        DEFAULT NULL COMMENT '注销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_status` (`status`),
  KEY `idx_deleted_at` (`deleted_at`)
) ENGINE=InnoDB COMMENT='用户基础信息表';

CREATE TABLE `user_agreement` (
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '用户ID（主键，一对一）',
  `version`    VARCHAR(16)     NOT NULL COMMENT '协议版本号，如 v1.0，前端写死当前版本',
  `agreed_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同意时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB COMMENT='用户协议签署记录';


CREATE TABLE `media_attachment` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '附件ID',
  `target_type`   TINYINT         NOT NULL COMMENT '关联内容类型：1-帖子 2-活动 3-考试信息',
  `target_id`     BIGINT UNSIGNED NOT NULL COMMENT '关联内容ID',
  `type`          TINYINT         NOT NULL COMMENT '附件类型：1-公众号/外部链接 2-图片 3-PDF文件 4-Word文件',
  `url`           VARCHAR(512)    NOT NULL COMMENT '资源URL：图片/PDF存COS地址，外链存原始URL',
  `original_name` VARCHAR(255)    DEFAULT NULL COMMENT '原始文件名（PDF上传时记录，如"2025年报名须知.pdf"）',
  `sort_order`    TINYINT         NOT NULL DEFAULT 0 COMMENT '展示顺序，从0开始',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`, `sort_order`)
) ENGINE=InnoDB COMMENT='通用附件表（图片/PDF/外链，多态关联）';

CREATE TABLE `post` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '发帖用户ID',
  `title`         VARCHAR(128)    DEFAULT NULL COMMENT '帖子标题（可选）',
  `content`       TEXT            NOT NULL COMMENT '帖子正文，纯文字',
  `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：0-草稿 1-审核中 2-已发布 3-审核拒绝 4-已删除',
  `audit_status`  TINYINT         NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核 1-通过 2-拒绝',
  `reject_reason` VARCHAR(255)    DEFAULT NULL COMMENT '审核拒绝原因',
  `is_pinned`     TINYINT         NOT NULL DEFAULT 0 COMMENT '是否置顶：1-是',
  `is_featured`   TINYINT         NOT NULL DEFAULT 0 COMMENT '是否精华/推荐：1-是',
  `comment_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数（冗余，异步更新）',
  `like_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数（冗余，异步更新）',
  `share_count`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '分享数（冗余，异步更新）',
  `view_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '浏览量（冗余，异步更新）',
  `published_at`  DATETIME        DEFAULT NULL COMMENT '发布时间',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`    DATETIME        DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status_published` (`status`, `published_at`),
  KEY `idx_pinned_published` (`is_pinned`, `published_at`),
  KEY `idx_deleted_at` (`deleted_at`),
  FULLTEXT KEY `idx_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='帖子主表';

CREATE TABLE `activity` (
  `id`                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `user_id`             BIGINT UNSIGNED  NOT NULL COMMENT '发布者用户ID',
  `title`               VARCHAR(128)     NOT NULL COMMENT '活动标题',
  `content`             TEXT             NOT NULL COMMENT '活动详情，纯文字',
  `location`            VARCHAR(255)     DEFAULT NULL COMMENT '活动地点',
  `category`            TINYINT          NOT NULL DEFAULT 0 COMMENT '活动分类：0-其他 1-文体比赛 2-志愿公益 3-思政主题 4-学术讲座 5-体育运动',
  `organizer`           VARCHAR(128)     DEFAULT NULL COMMENT '主办/承办单位',
  `audience_scope`      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '参与范围位掩码：bit0-全院 bit1-信息 bit2-理工 bit3-财经 bit4-人文 bit5-基础',
  `contact_info`        VARCHAR(512)     DEFAULT NULL COMMENT '联系人JSON，如[{"name":"xx","phone":"xxx"}]',
  `join_method`         VARCHAR(255)     DEFAULT NULL COMMENT '参与方式说明（如"扫描下方二维码加群"）',
  `qrcode_url`          VARCHAR(512)     DEFAULT NULL COMMENT '报名二维码图片URL（COS，可能频繁更换）',
  `start_time`          DATETIME         NOT NULL COMMENT '活动开始时间',
  `end_time`            DATETIME         NOT NULL COMMENT '活动结束时间',
  `enroll_deadline`     DATETIME         DEFAULT NULL COMMENT '报名截止时间',
  `max_participants`    INT UNSIGNED     DEFAULT NULL COMMENT '最大参与人数，NULL表示不限',
  `status`              TINYINT          NOT NULL DEFAULT 1 COMMENT '状态：0-草稿 1-审核中 2-报名中 3-进行中 4-已结束 5-已取消 6-审核拒绝 7-已删除',
  `audit_status`        TINYINT          NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核 1-通过 2-拒绝',
  `reject_reason`       VARCHAR(255)     DEFAULT NULL COMMENT '审核拒绝原因',
  `is_pinned`           TINYINT          NOT NULL DEFAULT 0 COMMENT '是否置顶：0-否 1-是',
  `comment_count`       INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '评论数（冗余）',
  `like_count`          INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '点赞数（冗余）',
  `view_count`          INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '浏览量（冗余）',
  `published_at`        DATETIME         DEFAULT NULL COMMENT '发布时间',
  `created_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`          DATETIME         DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_category_status` (`category`, `status`),
  KEY `idx_status_start` (`status`, `start_time`),
  KEY `idx_deleted_at` (`deleted_at`),
  FULLTEXT KEY `idx_search` (`title`, `content`, `organizer`) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='活动主表';

CREATE TABLE `activity_enrollment` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `activity_id`  BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `type`         TINYINT         NOT NULL DEFAULT 2 COMMENT '类型：1-正式报名（预留） 2-仅订阅通知',
  `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1-有效 2-已取消',
  `notify_enable` TINYINT        NOT NULL DEFAULT 1 COMMENT '是否开启该活动通知：1-开启',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_user` (`activity_id`, `user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_activity_notify` (`activity_id`, `notify_enable`, `status`)
) ENGINE=InnoDB COMMENT='活动订阅表';


CREATE TABLE `exam_info` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '考试信息ID',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '发布者用户ID',
  `title`              VARCHAR(128)    NOT NULL COMMENT '考试名称，如"2025年下半年计算机等级考试"',
  `category`           TINYINT         NOT NULL DEFAULT 0 COMMENT '考试分类：0-其他 1-英语类 2-普通话 3-计算机 4-考研 5-考公 6-教师资格 7-职业资格',
  `content`            TEXT            NOT NULL COMMENT '考试详情，纯文字整合信息',
  `registration_start` DATETIME        DEFAULT NULL COMMENT '报名开始时间',
  `registration_end`   DATETIME        DEFAULT NULL COMMENT '报名截止时间',
  `exam_date`          DATE            DEFAULT NULL COMMENT '考试日期（或第一天）',
  `exam_date_end`      DATE            DEFAULT NULL COMMENT '考试结束日期（多天考试时填写）',
  `official_url`       VARCHAR(512)    DEFAULT NULL COMMENT '官方报名/信息链接',
  `status`             TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：0-草稿 1-审核中 2-已发布 3-报名已截止 4-考试已结束 5-审核拒绝 6-已删除',
  `audit_status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待审核 1-通过 2-拒绝',
  `reject_reason`      VARCHAR(255)    DEFAULT NULL COMMENT '审核拒绝原因',
  `is_pinned`          TINYINT         NOT NULL DEFAULT 0 COMMENT '是否置顶：0-否 1-是',
  `comment_count`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数（冗余）',
  `like_count`         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数（冗余）',
  `view_count`         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '浏览量（冗余）',
  `published_at`       DATETIME        DEFAULT NULL COMMENT '发布时间',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`         DATETIME        DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_category_status` (`category`, `status`),
  KEY `idx_registration_end` (`registration_end`),
  KEY `idx_exam_date` (`exam_date`),
  KEY `idx_deleted_at` (`deleted_at`)
) ENGINE=InnoDB COMMENT='考试信息主表';

CREATE TABLE `exam_subscription` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `exam_info_id`  BIGINT UNSIGNED NOT NULL COMMENT '考试信息ID',
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `notify_enable` TINYINT         NOT NULL DEFAULT 1 COMMENT '是否开启通知：1-开启',
  `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '1-有效 2-已取消',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_exam_user` (`exam_info_id`, `user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_exam_notify` (`exam_info_id`, `notify_enable`, `status`)
) ENGINE=InnoDB COMMENT='考试信息订阅表';

CREATE TABLE `timeline` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `target_type` TINYINT         NOT NULL COMMENT '2-活动 3-考试信息',
  `target_id`   BIGINT UNSIGNED NOT NULL COMMENT '关联内容ID',
  `label`       VARCHAR(64)     NOT NULL COMMENT '节点名称，如"初赛"、"报名截止"、"成绩公布"',
  `description` VARCHAR(255)    DEFAULT NULL COMMENT '补充说明',
  `start_time`  DATETIME        DEFAULT NULL COMMENT '开始时间',
  `end_time`    DATETIME        DEFAULT NULL COMMENT '结束时间，单点时间只填start_time即可',
  `sort_order`  TINYINT         NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`, `sort_order`)
) ENGINE=InnoDB COMMENT='活动/考试时间线节点';

CREATE TABLE `comment` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `target_type`  TINYINT         NOT NULL COMMENT '目标类型：1-帖子 2-活动 3-考试信息',
  `target_id`    BIGINT UNSIGNED NOT NULL COMMENT '目标内容ID',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '评论者用户ID',
  `parent_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT '父评论ID，NULL表示一级评论',
  `reply_to_uid` BIGINT UNSIGNED DEFAULT NULL COMMENT '回复目标用户ID（展示"回复@xxx"用）',
  `content`      TEXT            NOT NULL COMMENT '评论内容，纯文字',
  `image_url`    VARCHAR(512)    DEFAULT NULL COMMENT '评论配图URL（最多1张）',
  `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：0-待审核 1-正常 2-审核拒绝 3-用户删除 4-管理员删除',
  `audit_status` TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待审核 1-通过 2-拒绝',
  `like_count`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数（冗余）',
  `reply_count`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '子评论数（冗余）',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`   DATETIME        DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`, `status`, `created_at`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_deleted_at` (`deleted_at`),
  KEY `idx_parent_status_id` (`parent_id`, `status` ,`deleted_at`,`id` DESC),
  KEY `idx_comment_query` (`target_id`,`target_type`,`status`,`parent_id`,`deleted_at`,`id` DESC)
) ENGINE=InnoDB COMMENT='通用评论表（多态）';

CREATE TABLE `like_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '点赞用户ID',
  `target_type` TINYINT         NOT NULL COMMENT '目标类型：1-帖子 2-活动 3-考试信息 4-评论',
  `target_id`   BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`, `target_type`, `target_id`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB COMMENT='点赞记录表';

CREATE TABLE `share_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '分享用户ID',
  `target_type` TINYINT         NOT NULL COMMENT '目标类型：1-帖子 2-活动 3-考试信息',
  `target_id`   BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
  `platform`    TINYINT         DEFAULT 1 COMMENT '分享平台：1-微信好友 2-朋友圈',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB COMMENT='分享记录表';

CREATE TABLE `view_count_cache` (
  `target_type` TINYINT         NOT NULL COMMENT '1-帖子 2-活动 3-考试信息',
  `target_id`   BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
  `view_count`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '总浏览量',
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`target_type`, `target_id`)
) ENGINE=InnoDB COMMENT='浏览量聚合缓存';

CREATE TABLE `view_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '用户ID，NULL表示未登录访客',
  `target_type` TINYINT         NOT NULL,
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target_user` (`target_type`, `target_id`, `user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB COMMENT='浏览明细记录（定期清理）';

CREATE TABLE `notification` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '接收用户ID',
  `type`        TINYINT         NOT NULL COMMENT '通知类型：1-评论 2-回复 3-点赞 4-活动提醒 5-考试提醒 6-审核结果 7-系统广播',
  `title`       VARCHAR(128)    NOT NULL COMMENT '通知标题',
  `content`     VARCHAR(512)    NOT NULL COMMENT '通知内容',
  `target_type` TINYINT         DEFAULT NULL COMMENT '关联内容类型：1-帖子 2-活动 3-考试 4-评论，点击跳转用',
  `target_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '关联内容ID',
  `sender_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '触发者用户ID，NULL=系统',
  `is_read`     TINYINT         NOT NULL DEFAULT 0 COMMENT '0-未读 1-已读',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_unread` (`user_id`, `is_read`, `created_at`),  -- 查未读列表
  KEY `idx_created_at`  (`created_at`)                         -- 定期清理用
) ENGINE=InnoDB COMMENT='个人通知收件箱';

CREATE TABLE `notify_plan` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '计划ID',
  `source_type` TINYINT         NOT NULL COMMENT '来源类型：1-活动 2-考试 3-系统广播（管理员手动创建）',
  `source_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '关联内容ID，系统广播时为NULL',
  `notify_type` TINYINT         NOT NULL COMMENT '对应 notification.type',
  `title`       VARCHAR(128)    NOT NULL COMMENT '通知标题',
  `content`     VARCHAR(512)    NOT NULL COMMENT '通知内容',
  `scope`       TINYINT         NOT NULL DEFAULT 0 COMMENT '发送范围：0-已订阅用户 1-全员',
  `scene`       VARCHAR(64)     DEFAULT NULL COMMENT '场景标签，如"活动开始前1小时"，供日志/展示用',
  `send_at`     DATETIME        NOT NULL COMMENT '计划发送时间；立即发则等于 created_at',
  `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待发 1-已发 2-已取消',
  `created_by`  BIGINT UNSIGNED DEFAULT NULL COMMENT '创建人，NULL=系统自动生成',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_send_at` (`status`, `send_at`),   -- 轮询到期计划
  KEY `idx_source`         (`source_type`, `source_id`)  -- 取消时按内容批量作废
) ENGINE=InnoDB COMMENT='通知计划（内容级，fan-out 时按 scope 查订阅用户）';

CREATE TABLE `content_audit_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `target_type`   TINYINT         NOT NULL COMMENT '内容类型：1-帖子 2-活动 3-考试信息 4-评论',
  `target_id`     BIGINT UNSIGNED NOT NULL COMMENT '内容ID',
  `audit_source`  TINYINT         NOT NULL COMMENT '审核来源：1-微信安全API自动 2-管理员人工',
  `wx_trace_id`   VARCHAR(64)     DEFAULT NULL COMMENT '微信审核traceId',
  `wx_result`     TINYINT         DEFAULT NULL COMMENT '微信审核结果：0-正常 1-违规',
  `wx_detail`     JSON            DEFAULT NULL COMMENT '微信审核原始JSON响应',
  `admin_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '人工审核员ID',
  `admin_action`  TINYINT         DEFAULT NULL COMMENT '人工操作：1-通过 2-拒绝',
  `admin_remark`  VARCHAR(255)    DEFAULT NULL COMMENT '人工审核备注',
  `final_result`  TINYINT         NOT NULL COMMENT '最终结论：1-通过 2-拒绝',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_admin_user_id` (`admin_user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB COMMENT='内容审核日志';

CREATE TABLE `report` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `reporter_id`    BIGINT UNSIGNED NOT NULL COMMENT '举报人用户ID',
  `target_type`    TINYINT         NOT NULL COMMENT '举报对象类型：1-帖子 2-评论',
  `target_id`      BIGINT UNSIGNED NOT NULL COMMENT '举报对象ID',
  `reason`         TINYINT         NOT NULL COMMENT '举报原因：1-违规违法 2-色情低俗 3-广告骚扰 4-虚假信息 5-其他',
  `remark`         VARCHAR(255)    DEFAULT NULL COMMENT '补充说明（可选）',
  `status`         TINYINT         NOT NULL DEFAULT 0 COMMENT '处理状态：0-待处理 1-违规已处理 2-正常已处理',
  `handler_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '处理管理员ID',
  `handle_remark`  VARCHAR(255)    DEFAULT NULL COMMENT '处理备注',
  `handled_at`     DATETIME        DEFAULT NULL COMMENT '处理时间',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reporter_target` (`reporter_id`, `target_type`, `target_id`),
  KEY `idx_target`  (`target_type`, `target_id`),
  KEY `idx_status`  (`status`, `created_at`)
) ENGINE=InnoDB COMMENT='举报记录表';

CREATE TABLE `user_setting` (
  `user_id`                BIGINT UNSIGNED NOT NULL COMMENT '用户ID（主键，与user_profile一对一）',
  -- 通知开关
  `notify_comment`         TINYINT NOT NULL DEFAULT 0 COMMENT '有人评论我的内容：1-开',
  `notify_reply`           TINYINT NOT NULL DEFAULT 0 COMMENT '有人回复我的评论：1-开',
  `notify_like`            TINYINT NOT NULL DEFAULT 0 COMMENT '有人点赞我的内容：1-开',
  `notify_activity_remind` TINYINT NOT NULL DEFAULT 0 COMMENT '订阅的活动提醒：1-开',
  `notify_exam_remind`     TINYINT NOT NULL DEFAULT 0 COMMENT '订阅的考试提醒：1-开',
  `notify_system`          TINYINT NOT NULL DEFAULT 1 COMMENT '系统通知：1-开',
  `notify_audit_result`    TINYINT NOT NULL DEFAULT 1 COMMENT '内容审核结果通知：1-开',
  -- 隐私设置（预留，关注/私信功能上线时启用）
  `privacy_show_likes`     TINYINT NOT NULL DEFAULT 1 COMMENT '公开我的点赞列表：1-是（预留）',
  `privacy_allow_follow`   TINYINT NOT NULL DEFAULT 1 COMMENT '允许被关注：1-是（预留）',
  `privacy_allow_message`  TINYINT NOT NULL DEFAULT 1 COMMENT '允许私信：1-是（预留）',
  `updated_at`             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB COMMENT='用户个性化设置';

CREATE TABLE `search_doc` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT       COMMENT '主键',
  `target_type`  TINYINT         NOT NULL                      COMMENT '内容类型：1-帖子 2-活动',
  `target_id`    BIGINT UNSIGNED NOT NULL                      COMMENT '对应原表主键ID',
  `search_text`  TEXT            NOT NULL                      COMMENT '合并搜索内容（见字段说明）',
  `status`       TINYINT         NOT NULL                      COMMENT '冗余原表状态：1-已发布 其他-不参与搜索',
  `published_at` DATETIME        NOT NULL                      COMMENT '冗余发布时间，游标排序用',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_target`              (`target_type`, `target_id`),
  KEY           `idx_cursor`          (`target_type`, `status`, `published_at` DESC, `id` DESC),
  FULLTEXT KEY  `ft_search`           (`search_text`) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='全文搜索文档表';

SET FOREIGN_KEY_CHECKS = 1;