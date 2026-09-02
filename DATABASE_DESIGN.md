# 企业级微信小程序数据库设计文档

> 本文描述当前仓库目标结构和字段业务语义，不代表生产库已经迁移到该状态。
> 可执行结构历史以 `src/main/resources/db/migration/` 为准。

## 数据库版本状态

| 范围 | 版本 | 表数量 | 状态 |
|---|---|---:|---|
| 生产首次初始化基线 | `V1` | 20 | 已确认，与 `V1__initial_production_schema.sql` 一致 |
| 当前生产与仓库目标结构 | `V3` | 21 | V2、V3 已于 2026-08-31 迁移成功 |
| 关注与私信设计 | 未分配版本 | 3 | 未来候选，不属于当前目标结构 |

生产、测试和开发环境是否已经到达某个版本，以各自的
`flyway_schema_history` 和迁移验收记录为准，不能仅由本文推断。

> **项目概述**：面向企业内部/社区用户的微信小程序，含帖子广场、活动管理、考试信息三大模块，支持评论、点赞、分享、订阅通知等功能。活动与考试均为中转信息平台，不在小程序内直接报名，报名方式为扫码加群等线下形式。
> **技术栈**：MySQL 8.0+，ECS 2GB内存 / 2vCPU / 3M带宽，文件存储推荐腾讯云COS
> **预期规模**：百人至千人级别，单人开发维护
> **文档版本**：v1.5
> **更新日期**：2026-08-31
>
> co3s2cph9r90gr3ofrpxougf3iuon3t0

---

## 目录

1. [设计原则与规范](#设计原则与规范)

2. [数据库总览](#数据库总览)

3. [用户模块](#用户模块)

4. [合规与协议模块](#合规与协议模块)

5. [通用附件模块](#通用附件模块)

6. [帖子广场模块](#帖子广场模块)

7. [活动模块](#活动模块)

8. [考试信息模块](#考试信息模块)

9. [时间线模块](#时间线模块)

10. [通用评论模块](#通用评论模块)

11. [互动行为模块](#互动行为模块)

12. [通知与订阅模块](#通知与订阅模块)

13. [内容审核模块](#内容审核模块)

14. [举报功能模块](#举报功能模块)

15. [设置模块](#设置模块)

16. [索引设计汇总](#索引设计汇总)

17. [扩展性预留设计](#扩展性预留设计)

18. [性能与运维建议](#性能与运维建议)

    

---

## 设计原则与规范

### 命名规范

| 规则     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 表名     | 全小写，下划线分隔，如 `user_profile`                        |
| 字段名   | 全小写，下划线分隔，如 `created_at`                          |
| 主键     | 统一使用 `id BIGINT UNSIGNED AUTO_INCREMENT`                 |
| 时间字段 | 统一使用 `DATETIME` 类型，`created_at` / `updated_at` / `deleted_at` |
| 软删除   | 所有业务表均使用 `deleted_at` 软删除，不做物理删除           |
| 字符集   | 统一 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`                |
| 枚举值   | 使用 `TINYINT` 存储，注释说明含义，避免 ENUM 类型            |

### 通用字段约定

所有业务表均包含以下基础字段：

```
id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
deleted_at  DATETIME        DEFAULT NULL COMMENT '软删除时间，NULL表示未删除'
```

### 服务器配置，以及全局`mysql`配置

```ini
[mysqld]
# =========================
# 字符集
# =========================
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci

# =========================
# 时区
# =========================
default-time-zone='Asia/Shanghai'

# =========================
# 2GiB 小服务器保守配置
# =========================
max_connections=80
innodb_buffer_pool_size=256M
table_open_cache=400
performance_schema=OFF

# =========================
# 日志与安全
# =========================
skip-name-resolve

[client]
default-character-set=utf8mb4
```



### 资源约束说明

当前服务器配置为 2GB内存、2vCPU、3M带宽，建议：

- MySQL `innodb_buffer_pool_size` 设置为 512MB～768MB
- 避免大事务和全表扫描，所有查询须走索引
- 图片/文件资源不存数据库，仅存腾讯云COS的URL链接
- 带宽限制下，通知推送使用异步队列，避免接口阻塞
- 文件上传采用小程序端直传COS（前端直接传COS，后端只存URL），不经过ECS中转，节省带宽

### 文件存储说明（腾讯云COS）

| 文件类型  | 存储路径规范                              | 示例                           |
|-------|-------------------------------------|------------------------------|
| 用户头像  | `avatar/{user_id}/{timestamp}.jpg`  | `avatar/1001/1712800000.jpg` |
| 用户背景图 | `bg/{user_id}/{timestamp}.jpg`      | `bg/1001/1712800000.jpg`     |
| 帖子图片  | `post/{post_id}/{sort}.jpg`         | `post/2001/0.jpg`            |
| 活动附件  | `activity/{activity_id}/{filename}` | `activity/3001/notice.pdf`   |
| 考试附件  | `exam/{exam_id}/{filename}`         | `exam/4001/schedule.pdf`     |

数据库中只存完整URL或相对路径，CDN域名统一配置在应用层，便于后续切换域名。

---

## 数据库总览

### 模块关系图

```
┌──────────────────────────────────────────────────────────┐
│                    用户与权限模块                         │
│           user_profile  |  user_agreement                │
└───────────────────────┬──────────────────────────────────┘
                        │ 用户主体
        ┌───────────────┼──────────────────┐
        ▼               ▼                  ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│  帖子广场模块 │ │  活动模块     │ │  考试信息模块      │
│  post        │ │  activity    │ │  exam_info        │
└──────┬───────┘ │  activity_   │ └────────┬─────────┘
       │         │  enrollment  │          │		┌─────────▼──────────┐
       │         └──────┬───────┘          │		│   时间线模块       │  	       	
       │                │                  │		│  	timeline 		 │
       └────────────────┼──────────────────┘	     └────────────────────┘
                        │ 多态关联 target_type + target_id
              ┌─────────▼──────────┐
              │   通用附件模块       │  ← 图片/PDF/公众号链接统一管理
              │  media_attachment  │
              └────────────────────┘
                        │
              ┌─────────▼──────────┐
              │   通用评论模块       │
              │   comment          │
              └─────────┬──────────┘
                        │
         ┌──────────────┼──────────────┐
         ▼              ▼              ▼
  ┌─────────────┐ ┌──────────┐ ┌──────────────────┐
  │  互动行为    │ │ 通知订阅  │ │   内容审核模块     │
  │  like/share │ │ subscribe│ │  content_audit    │
  │  view_log   │ │ notify   │ └──────────────────┘
  └─────────────┘ └──────────┘
```

### 当前目标表清单（共 21 张）

| 序号 | 表名                  | 说明                                  |
| ---- | --------------------- | ------------------------------------- |
| 1    | `user_profile`        | 用户基础信息（含头像、背景图、简介）  |
| 2    | `user_agreement`      | 用户协议签署记录                      |
| 3    | `media_attachment`    | **通用附件表**（图片/PDF/外链，多态） |
| 4    | `media_upload_record` | COS 上传临时记录与对象生命周期        |
| 5    | `post`                | 帖子主表                              |
| 6    | `activity`            | 活动主表                              |
| 7    | `activity_enrollment` | 活动订阅/报名                         |
| 8    | `exam_info`           | 考试信息主表                          |
| 9    | `exam_subscription`   | 考试订阅                              |
| 10   | `comment`             | 通用评论表（多态）                    |
| 11   | `like_record`         | 点赞记录                              |
| 12   | `share_record`        | 分享记录                              |
| 13   | `view_count_cache`    | 浏览量聚合缓存                        |
| 14   | `view_log`            | 浏览明细（可定期清理）                |
| 15   | `notification`        | 通知消息表                            |
| 16   | `content_audit_log`   | 内容审核日志                          |
| 17   | `user_setting`        | 用户设置                              |
| 18   | `timeline`            | 时间线表                              |
| 19   | `notify_plan`         | 通知计划                              |
| 20   | `report`              | 举报功能（多态）                      |
| 21   | `search_doc`          | 帖子与活动全文搜索文档                |

---

##  用户模块

用户表 `user_profile`

```sql
CREATE TABLE `user_profile` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `openid`         VARCHAR(64)     NOT NULL COMMENT '微信openid，唯一标识',
  `mp_openid`      VARCHAR(64)     DEFAULT NULL COMMENT '服务号openid，关注服务号后关联',
  `unionid`        VARCHAR(64)     DEFAULT NULL COMMENT '微信unionid（开放平台）',
  `nickname`       VARCHAR(64)     DEFAULT NULL COMMENT '用户昵称',
  `avatar_url`     VARCHAR(512)    DEFAULT NULL COMMENT '头像URL（COS）',
  `avatar_object_key` VARCHAR(512) DEFAULT NULL COMMENT '头像COS对象键',
  `background_url` VARCHAR(512)    DEFAULT NULL COMMENT '个人主页背景图URL（COS）',
  `background_object_key` VARCHAR(512) DEFAULT NULL COMMENT '背景图COS对象键',
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
```

**字段说明：**

| 字段               | 说明                              |
|------------------|---------------------------------|
| `openid`         | 微信小程序唯一标识，登录时从微信服务端获取，不可更改      |
| `phone`          | AES-256 加密后存储，不可直接明文查询          |
| `bio`            | 限200字，前端做长度校验，后端截断兜底            |
| `background_url` | 用户个人主页的背景图，存COS URL；未设置时前端展示默认图 |
| `status`         | 禁言状态下用户不可发帖/评论，封禁状态无法登录         |

---

## 合规与协议模块

用户协议签署记录 `user_agreement`

```sql
CREATE TABLE `user_agreement` (
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '用户ID（主键，一对一）',
  `version`    VARCHAR(16)     NOT NULL COMMENT '协议版本号，如 v1.0，前端写死当前版本',
  `agreed_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同意时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB COMMENT='用户协议签署记录';
```

**业务说明：** 用户首次登录，或协议版本升级后，检查用户是否已签署当前生效版本，未签署则强制弹出协议页。

---

## 通用附件模块

### 设计决策说明

**为什么用一张通用附件表，而不是每个模块单独建图片表？**

| 对比维度     | 通用附件表（本方案）   | 各模块独立图片表         |
|----------|--------------|------------------|
| 表数量      | 1张           | 至少3张（帖子/活动/考试各一） |
| 新增文件类型   | 只改 `type` 枚举 | 每张表都要加字段         |
| 支持PDF/外链 | ✅ 天然支持       | ❌ 需要改表结构         |
| 维护成本     | 低，单人友好       | 较高               |
| 适用规模     | **千人以下** ✅   | 万人以上，或需文件去重/复用   |

**什么规模该升级为独立文件资产库？** 当出现以下任意场景时考虑拆分：用户量超过1万且文件数量庞大需要控制存储成本、需要同一文件被多处引用（去重复用）、需要后台统一审核/管理所有媒体文件、需要按用户统计存储用量配额。目前规模用通用附件表完全足够。

---

### 通用附件表 `media_attachment`

```sql
CREATE TABLE `media_attachment` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '附件ID',
  `target_type`   TINYINT         NOT NULL COMMENT '关联内容类型：1-帖子 2-活动 3-考试信息',
  `target_id`     BIGINT UNSIGNED NOT NULL COMMENT '关联内容ID',
  `type`          TINYINT         NOT NULL COMMENT '附件类型：1-公众号/外部链接 2-图片 3-PDF文件 4-Word文件',
  `object_key`    VARCHAR(512)    DEFAULT NULL COMMENT 'COS对象键；外链类型为空',
  `url`           VARCHAR(512)    NOT NULL COMMENT '资源URL：图片/PDF存COS地址，外链存原始URL',
  `original_name` VARCHAR(255)    DEFAULT NULL COMMENT '原始文件名（PDF上传时记录，如"2025年报名须知.pdf"）',
  `sort_order`    TINYINT         NOT NULL DEFAULT 0 COMMENT '展示顺序，从0开始',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`, `sort_order`)
) ENGINE=InnoDB COMMENT='通用附件表（图片/PDF/外链，多态关联）';
```

### 上传临时记录表 `media_upload_record`

```sql
CREATE TABLE `media_upload_record` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `object_key`      VARCHAR(512)    NOT NULL COMMENT 'COS对象键',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '申请上传的小程序用户ID',
  `target_type`     TINYINT         NOT NULL COMMENT '计划绑定的业务类型',
  `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0-PENDING 1-BOUND 2-CLEANING',
  `bound_target_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定后的业务目标ID',
  `cleanup_after`   DATETIME        NOT NULL COMMENT '允许清理的最早时间',
  `retry_count`     INT UNSIGNED    NOT NULL DEFAULT 0,
  `last_error`      VARCHAR(512)    DEFAULT NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_media_upload_object_key` (`object_key`),
  KEY `idx_media_upload_cleanup` (`status`, `cleanup_after`),
  KEY `idx_media_upload_user_type` (`user_id`, `target_type`)
) ENGINE=InnoDB COMMENT='COS上传临时记录与绑定状态';
```

**历史 URL 数据迁移原则：**

- `media_attachment.url`、`comment.image_url`、`user_profile.avatar_url/background_url`
  中，只有域名与路径前缀均能确认属于当前 COS 存储桶的记录，才允许从 URL 提取并回填对象键。
- 外部链接、微信头像以及来源不明的历史 URL 保持对象键为 `NULL`，不得进入自动删除流程。
- 上线顺序为：先增加对象键字段和 `media_upload_record` 表，再部署应用；新写入数据由应用同时保存 URL 与对象键。
- 历史数据回填应单独执行可核对、可分批的迁移脚本，并在回填前抽样比对 URL 与对象键；不要在应用启动时隐式批量转换。

**type 枚举值详解：**

| type值 | 含义     | url内容       | original_name |
|-------|--------|-------------|---------------|
| 1     | 图片     | COS图片URL    | NULL          |
| 2     | PDF文件  | COS PDF URL | 原始文件名         |
| 3     | 公众号/外链 | 公众号文章或官网URL | NULL          |

**示例数据：**

```
-- 活动配了1张封面图 + 1个PDF通知 + 1个公众号文章链接
INSERT INTO media_attachment VALUES
(1, 2, 1001, 1, 'https://cos.../activity/1001/cover.jpg', NULL, NULL, 245600, 'image/jpeg', 750, 400, 0, NOW()),
(2, 2, 1001, 2, 'https://cos.../activity/1001/notice.pdf', '2025年活动报名须知.pdf', '报名须知', 512000, 'application/pdf', NULL, NULL, 1, NOW()),
(3, 2, 1001, 3, 'https://mp.weixin.qq.com/s/xxxxx', NULL, '查看官方公告', NULL, NULL, NULL, NULL, 2, NOW());
```

---

## 帖子广场模块

### 帖子主表 `post`

```sql
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
```

> 帖子的图片通过 `media_attachment`（`target_type=1`）关联，不在本表存字段。

---

## 活动模块

### 活动主表 `activity`

```sql
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
```

**`audience_scope` 使用说明**

| 学科部   | 位    | 值  |
|-------|------|----|
| 全院    | bit0 | 1  |
| 信息学科部 | bit1 | 2  |
| 理工学科部 | bit2 | 4  |
| 财经学科部 | bit3 | 8  |
| 人文学科部 | bit4 | 16 |
| 基础学科部 | bit5 | 32 |

> 存的时候前端把选中的标签值加总：只选信息=2，信息+理工=6，全院=1（或者直接存63表示全选）。
>
> 命中方法： 如果是信息学科部	值 & 2 进行位运算，未命中就是 0 命中就是 2，可以通过位移操作依次判断，1 << i，就是将1的二进制数 位移 i 位（0就是不动），1 << 1 = 2（00000010），1 << 2 = 4（00000100） 

**字段说明：**

| 字段            | 说明                                       |
|---------------|------------------------------------------|
| `join_method` | 文字描述报名方式，如"扫描下方二维码加群"，配合附件中的二维码图片使用      |
| `qrcode_url`  | 单独存报名二维码，因为二维码可能单独更换（微信群满员换新群），不混入普通附件排序 |

> 活动的封面图、PDF附件、公众号文章链接均通过 `media_attachment`（`target_type=2`）关联。

---

### 活动订阅表 `activity_enrollment`

```sql
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
```

**业务说明：** 当前活动不在小程序内报名，`type` 主要为 `2`（订阅通知）。`type=1` 预留，万一后续某活动需要小程序内收集报名信息时直接启用。`idx_activity_notify` 索引专为「给某活动的订阅用户批量推送通知」这一查询场景设计。

---

## 考试信息模块

### 考试信息主表 `exam_info`

```sql
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
```

**`category` 枚举值：**

| 值 | 考试类型 | 示例          |
|---|------|-------------|
| 0 | 其他   | 各类证书考试      |
| 1 | 英语类  | 四六级、托福、雅思   |
| 2 | 普通话  | 普通话水平测试     |
| 3 | 计算机  | 全国计算机等级考试   |
| 4 | 考研   | 全国硕士研究生招生考试 |
| 5 | 考公   | 国考、省考公务员考试  |
| 6 | 教师资格 | 教师资格证考试     |
| 7 | 职业资格 | 会计、法律等职业资格  |

**新增字段说明：**

| 字段                | 说明                          |
|-------------------|-----------------------------|
| `category`        | 支持按考试类型筛选，是核心查询维度           |
| `admit_card_date` | 准考证打印时间，这类节点用户容易忘，订阅后重点推送   |
| `exam_date_end`   | 考研等多天考试，显示考试时间段用            |
| `official_url`    | 官网链接，用户点击后跳转，小程序内嵌webview展示 |

> 考试的附件（截图/PDF/公众号原文）通过 `media_attachment`（`target_type=3`）关联。

---

### 考试订阅表 `exam_subscription`

```sql
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
```

**`notify_types` 可选节点：**

| 值            | 说明      | 触发时机                    |
|--------------|---------|-------------------------|
| `reg_start`  | 报名开始提醒  | `registration_start` 当天 |
| `reg_end`    | 报名截止提醒  | `registration_end` 前1天  |
| `admit_card` | 准考证打印提醒 | `admit_card_date` 当天    |
| `exam_day`   | 考试日期提醒  | `exam_date` 前1天         |
| `result_day` | 成绩发布提醒  | `result_date` 当天        |

---

## 时间线模块

考试和活动通用，用 `target_type` 区分。

```sql
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
```

------

## 通用评论模块

评论表采用**多态设计**，`target_type + target_id` 统一关联帖子、活动、考试信息，支持二级回复，不做更深层嵌套。

### 评论表 `comment`

```sql
CREATE TABLE `comment` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `target_type`  TINYINT         NOT NULL COMMENT '目标类型：1-帖子 2-活动 3-考试信息',
  `target_id`    BIGINT UNSIGNED NOT NULL COMMENT '目标内容ID',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '评论者用户ID',
  `parent_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT '父评论ID，NULL表示一级评论',
  `reply_to_uid` BIGINT UNSIGNED DEFAULT NULL COMMENT '回复目标用户ID（展示"回复@xxx"用）',
  `content`      TEXT            NOT NULL COMMENT '评论内容，纯文字',
  `image_url`    VARCHAR(512)    DEFAULT NULL COMMENT '评论配图URL（最多1张）',
  `image_object_key` VARCHAR(512) DEFAULT NULL COMMENT '评论配图COS对象键',
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
```

**层级设计：**

```
一级评论（parent_id = NULL）
  └── 二级回复（parent_id = 一级评论ID，reply_to_uid = 被回复者）
       └── 不再嵌套第三级，前端展示"回复@用户名：内容"
```

---

##  互动行为模块

### 点赞记录 `like_record`

```sql
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
```

---

###  分享记录 `share_record`

```sql
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
```

---

### 浏览量

```sql
-- 浏览量聚合缓存（轻量，供列表展示直接读取）
CREATE TABLE `view_count_cache` (
  `target_type` TINYINT         NOT NULL COMMENT '1-帖子 2-活动 3-考试信息',
  `target_id`   BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
  `view_count`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '总浏览量',
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`target_type`, `target_id`)
) ENGINE=InnoDB COMMENT='浏览量聚合缓存';

-- 浏览明细（用于统计UV、防刷，可按月归档后清理）
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
```

---

##  通知与订阅模块

**核心设计决策**

```
只维护两张表：

notification    → 用户的私人收件箱，每人每条通知一行
                  所有通知路径最终都落这里
                  is_read 是用户维度的，必须每人一行

notify_plan     → 定时/广播计划，内容级，无 user_id
                  到期时 fan-out 写每个用户的 notification
                  异步队列分批写，DB 感受到的是平稳的小批量写入

subscribe_template 表删除 → 改为 WxNotifyTemplate 枚举（静态配置）
notify_queue 表删除       → 功能合并进 notify_plan
```

------

### notification（个人通知收件箱）

```sql
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
```

**为什么每人一条：**

- `is_read` 是"用户A 读了这条"，不是"这条通知被读了"
- `WHERE user_id = ? AND is_read = 0` 必须走索引，数组/JSON 无法索引
- 1000 用户 × 活动提醒，fan-out 写 1000 条异步完成，DB 无压力

**定期清理（90天）：**
```sql
DELETE FROM notification
WHERE is_read = 1 AND created_at < DATE_SUB(NOW(), INTERVAL 90 DAY)
LIMIT 500;  -- 每次小批量，不锁表
```

------

### notify_plan（通知计划，内容级调度）

```sql
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
```

**notify_plan 的两种创建时机：**

| 触发事件     | source_type | send_at          | scope   |
|----------|-------------|------------------|---------|
| 活动审核通过   | 1           | 开始前1天、前1小时（自动计算） | 0（订阅用户） |
| 考试发布     | 2           | 报名截止前1天（自动计算）    | 0（订阅用户） |
| 管理员发系统公告 | 3           | 立即（等于创建时间）       | 1（全员）   |
| 管理员发定向通知 | 3           | 指定时间             | 1 或 0   |

------

**各场景完整流程**

场景A：点赞 / 评论 / 回复

```
用户操作
  ↓
InteractionService / CommentService
  → targetValidator.assertExists()
  → 写 DB（like_record / comment）
  → 更新 Redis 计数
  → queueProducer.send(NotifyPayload)  ← 异步，不阻塞接口
        receiverId = 内容作者
        type       = 1/2/3
        wxValues   = [内容标题, 操作摘要]
  ↓
NotifyHandler 消费
  → 检查用户通知开关（user_setting 缓存）
  → 写 notification
  → Redis unread +1
  → WxNotifyTemplate.getByNotifyType(type) 获取模板
  → wechatPushService.pushIfSubscribed()
```

场景B：审核结果通知

```
管理员审核 / 微信自动审核回调
  ↓
AuditService.processResult()
  → 更新内容状态
  → targetValidator.invalidateExists()  ← 失效存在性缓存
  → redisService.delete(postDetail)     ← 失效详情缓存
  → queueProducer.send(NotifyPayload)
        receiverId = 内容发布者
        type       = 6
        wxValues   = [内容标题, "审核通过"/"审核拒绝", 拒绝原因或""]
  ↓
NotifyHandler 消费（同场景A）
```

场景C：活动/考试提醒（订阅用户）

```
活动审核通过
  ↓
ActivityService.onAuditPass(activityId)
  → 查 activity 的 start_time
  → 自动生成 notify_plan 记录（2条）：
      plan1: send_at = start_time - 1天,  scene = "活动明天开始"
      plan2: send_at = start_time - 1小时, scene = "活动1小时后开始"
  → 每条 plan 入 NOTIFY_DELAY_ZSET
      ZADD NOTIFY_DELAY_ZSET <send_at时间戳> "plan:{planId}"
  ↓
  （时间到）
  ↓
RedisDelayQueueConsumer 轮询（每30秒）
  → ZRANGEBYSCORE NOTIFY_DELAY_ZSET 0 <now> LIMIT 0 50
  → 取出到期的 "plan:{planId}"
  → queueProducer.send(BroadcastPlanPayload{planId})
  → ZREM（成功后移除）
  ↓
ContentBroadcastHandler 消费
  → 查 notify_plan 获取 sourceId/scope/title/content/scene
  → 分页查 activity_enrollment（status=1, notify_enable=1）的 user_id
  → 每人发一条 QUEUE_MAIN（NotifyPayload）
  → planMapper.updateStatus(planId, 1)
  ↓
NotifyHandler 消费（每人独立，同场景A）
```

场景D：考试提醒（与场景C对称）

```
考试发布/审核通过
  ↓
ExamService.onPublished(examId)
  → 查 exam_info 的 registration_end_time / exam_date
  → 生成 notify_plan：
      plan1: send_at = registration_end - 1天, scene = "报名明天截止"
      plan2: send_at = exam_date - 1天,        scene = "考试明天开始"
  → 入 NOTIFY_DELAY_ZSET
  ↓
后续流程与场景C完全相同，scope=0 查 exam_subscription
```

场景E：管理员发系统广播

```
管理员在后台填写公告内容，选择"立即发送"或指定时间
  ↓
AdminNotifyService.createBroadcast(title, content, sendAt)
  → 写 notify_plan：
      source_type = 3（系统广播）
      source_id   = NULL
      notify_type = 7
      scope       = 1（全员）
      send_at     = 指定时间（立即则等于 now）
  → 入 NOTIFY_DELAY_ZSET（sendAt 时间戳）
  ↓
到期后 ContentBroadcastHandler 消费
  → scope=1 → 分页查全员 user_id
  → 每人发 NotifyPayload（type=7）
  ↓
NotifyHandler 消费（同场景A）
```

------

## 内容审核模块

###  内容审核日志 `content_audit_log`

```sql
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
  `idempotency_key` VARCHAR(64)   DEFAULT NULL COMMENT '管理员写命令幂等键，仅人工审核使用',
  `final_result`  TINYINT         NOT NULL COMMENT '最终结论：1-通过 2-拒绝',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_admin_user_id` (`admin_user_id`),
  UNIQUE KEY `uk_audit_idempotency_key` (`idempotency_key`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB COMMENT='内容审核日志';
```

**审核流程：**

```
用户提交内容
    │
    ▼
调用微信 msgSecCheck（文字）/ mediaCheckAsync（图片/PDF缩略图）
    │
    ├── 正常 → 自动通过 → status 更新为「已发布」
    │
    └── 违规/疑似 → 管理员人工复核
                         │
                         ├── 通过 → 发布 + 通知用户审核通过
                         └── 拒绝 → 附原因 + 通知用户审核拒绝
```

---

## 举报功能模块

### `report`表

```sql
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
```

**三个索引的用途**

| 索引                 | 用于                                              |
| -------------------- | ------------------------------------------------- |
| `uk_reporter_target` | 防止同一用户重复举报同一内容，直接数据库层保障    |
| `idx_target`         | 查询某条帖子/评论被举报了几次，管理员查看举报详情 |
| `idx_status`         | 管理员后台拉「待处理举报列表」，按时间排序        |

------

**与现有设计的衔接**

举报处理后如果决定删除内容，走已有的人工审核流程即可——在 `content_audit_log` 里新增一条 `audit_source=2`（管理员人工）的记录，`report` 表只负责记录"用户举报了什么"，不重复承担审核日志的职责。

```
用户举报
    │
    ▼
report 表新增一条记录（status=0 待处理）
    │
    ▼
管理员后台查看举报列表
    │
    ├── 内容没问题 → report.status=2（正常已处理）
    │
    └── 内容违规   → report.status=1（违规已处理）
                        + content_audit_log 记录人工审核
                        + 对应 post/comment 的 status 改为删除
```

------

**后续扩展说明**

初期 `target_type` 只用 `1-帖子 2-评论`，将来要支持举报活动或用户，直接新增枚举值，**不需要改表结构**，和你现有的多态设计完全一致。

------

##  设置模块

###  用户设置表 `user_setting`

```sql
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
```

---

## 搜索功能设计

### MySQL 配置补充

在 `my.cnf` / `my.ini` 中添加：

ini

```ini
[mysqld]
ngram_token_size = 2   # bigram 分词，支持中文，"计算机"→ 计算/算机
```

> 修改后需重启 MySQL，并重建 FULLTEXT 索引（新建表不受影响）。

------

### search_doc 表

#### 建表 DDL

```sql
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
```

#### 字段说明

| 字段           | 说明                                             |
| -------------- | ------------------------------------------------ |
| `target_type`  | 1=帖子，2=活动。后续扩展考试信息直接加枚举值     |
| `target_id`    | 原表 ID，搜索命中后用此字段回查原表拿完整数据    |
| `search_text`  | 合并写入，不存原表全文（见下方写入规则）         |
| `status`       | 冗余原表 status，过滤未发布/已删除内容，避免联表 |
| `published_at` | 冗余原表 published_at，作为游标排序主键          |

#### search_text 写入规则

```
帖子 (target_type=1)：
  search_text = content 前 300 字

活动 (target_type=2)：
  search_text = title + " " + location（可空）+ " " + content 前 300 字
```

> 只取前 300 字的原因：活动 content 千字以上，全量写入会使 ngram 索引膨胀； 用户搜索命中的关键词大概率在标题、地点、正文开头，截断不影响实际召回率。

#### 索引说明

| 索引名       | 字段                                                | 用途                             |
| ------------ | --------------------------------------------------- | -------------------------------- |
| `uk_target`  | `(target_type, target_id)`                          | UPSERT 唯一键，防止重复写入      |
| `idx_cursor` | `(target_type, status, published_at DESC, id DESC)` | 游标翻页过滤，配合 FULLTEXT 使用 |
| `ft_search`  | `search_text`                                       | 全文检索，ngram 中文分词         |

------

### 搜索 SQL

#### 首次搜索（无游标）

```sql
SELECT id, target_id, published_at
FROM search_doc
WHERE target_type   = :type          -- 1=帖子 2=活动
  AND status        = 1
  AND MATCH(search_text) AGAINST(:keyword IN BOOLEAN MODE)
ORDER BY published_at DESC, id DESC
LIMIT 20;
```

#### 下拉加载（带游标）

游标由上一页最后一条的 `published_at` + `id` 组成，客户端携带，服务端透传即可。

```sql
SELECT id, target_id, published_at
FROM search_doc
WHERE target_type   = :type
  AND status        = 1
  AND MATCH(search_text) AGAINST(:keyword IN BOOLEAN MODE)
  AND (
    published_at  < :lastPublishedAt
    OR (published_at = :lastPublishedAt AND id < :lastId)
  )
ORDER BY published_at DESC, id DESC
LIMIT 20;
```

#### Tab 计数（切换 Tab 前拿各类型总数）

```sql
SELECT target_type, COUNT(*) AS cnt
FROM search_doc
WHERE status = 1
  AND MATCH(search_text) AGAINST(:keyword IN BOOLEAN MODE)
GROUP BY target_type;
```

返回示例：`{post: 12, activity: 5}`，前端用于展示 Tab 上的数量角标。

------

### Redis 策略

游标分页下每次请求的游标都不同，**不缓存分页结果**，只缓存 Tab 计数。

| Key                           | 格式                                 | TTL                    | 用途                                      |
| ----------------------------- | ------------------------------------ | ---------------------- | ----------------------------------------- |
| `search:count:{md5(keyword)}` | `{1:12, 2:5}` JSON                   | 5 分钟                 | Tab 数量角标，避免每次切 Tab 都重查 COUNT |
| `search:hot`                  | ZSet，member=keyword，score=搜索次数 | 永久（定期清理低频词） | 热搜榜                                    |

> keyword 先 trim + lowercase 再 md5，保证"计算机"和"  计算机  "命中同一个缓存 key。

**失效时机：**

- 有新帖子/活动发布或被删除 → 对应 `search:count:*` 全部 DEL（或等 TTL 自然过期，5 分钟可接受）
- 热搜 ZSet 每天凌晨清理 score < 阈值的词

------

### 同步机制

#### 触发时机

| 原表操作           | search_doc 操作            |
| ------------------ | -------------------------- |
| 发布帖子/活动      | INSERT（UPSERT）           |
| 编辑帖子/活动内容  | UPDATE search_text         |
| 下架/删除帖子/活动 | UPDATE status = 原表新状态 |
| 恢复发布           | UPDATE status = 1          |

#### UPSERT SQL

```sql
INSERT INTO search_doc
  (target_type, target_id, search_text, status, published_at)
VALUES
  (:type, :targetId, :searchText, :status, :publishedAt)
ON DUPLICATE KEY UPDATE
  search_text  = VALUES(search_text),
  status       = VALUES(status),
  published_at = VALUES(published_at),
  updated_at   = NOW();
```

#### Spring Boot 实现建议

```java
// 写原表成功后，异步同步，失败不影响主流程
@Async
public void syncToSearchDoc(Post post) {
    String searchText = StringUtils.left(post.getContent(), 300);
    searchDocMapper.upsert(1, post.getId(), searchText,
                           post.getStatus(), post.getPublishedAt());
}

@Async
public void syncToSearchDoc(Activity activity) {
    String searchText = activity.getTitle()
        + " " + StringUtils.defaultString(activity.getLocation())
        + " " + StringUtils.left(activity.getContent(), 300);
    searchDocMapper.upsert(2, activity.getId(), searchText,
                           activity.getStatus(), activity.getPublishedAt());
}
```

> `@Async` 失败时打日志即可，不阻断业务。如果对搜索实时性要求高，可改为同步写，捕获异常后重试。

------

### 扩展说明

后续如需加入**考试信息**搜索：

1. `search_doc` 表无需改动
2. 同步逻辑新增 `target_type = 3` 的分支
3. `search_text = title + " " + category + " " + description 前 300 字`
4. 搜索 SQL 的 `target_type` 传 3 即可



## 索引设计汇总

| 查询场景                  | 表                    | 使用索引               |
| ------------------------- | --------------------- | ---------------------- |
| 广场列表（最新）          | `post`                | `idx_status_published` |
| 广场置顶+最新             | `post`                | `idx_pinned_published` |
| 我的帖子                  | `post`                | `idx_user_id`          |
| 软删除过滤                | `post`                | `idx_deleted_at`       |
| 内容搜索                  | `post`                | `idx_content`          |
| 活动列表（按分类+状态）   | `activity`            | `idx_category_status`  |
| 活动列表（按状态+时间）   | `activity`            | `idx_status_start`     |
| 「我发布的活动」列表      | `activity`            | `idx_user_id`          |
| 软删除过滤                | `activity`            | `idx_deleted_at`       |
| 活动搜索                  | `activity`            | `idx_search`           |
| 考试列表（按分类+状态）   | `exam_info`           | `idx_category_status`  |
| 时间线列表（按类别+排序） | `timeline`            | `idx_target`           |
| 考试报名截止排序          | `exam_info`           | `idx_registration_end` |
| 按考试日期排序/筛选       | `exam_info`           | `idx_exam_date`        |
| 软删除过滤                | `exam_info`           | `idx_deleted_at`       |
| 获取某内容所有附件        | `media_attachment`    | `idx_target`           |
| 评论列表                  | `comment`             | `idx_target`           |
| 已删除的评论              | `comment`             | `idx_deleted_at`       |
| 父评论                    | `comment`             | `idx_parent_id`        |
| 用户评论                  | `comment`             | `idx_user_id`          |
| 一级评论分页查询          | `comment`             | `idx_comment_query`    |
| 查询预览子评论            | `comment`             | `idx_parent_status_id` |
| 用户是否点赞              | `like_record`         | `uk_user_target`       |
| 批量给活动订阅者推通知    | `activity_enrollment` | `idx_activity_notify`  |
| 判断用户是否已订阅某活动  | `activity_enrollment` | `uk_activity_user`     |
| 用户的活动订阅列表        | `activity_enrollment` | \```idx_user_id`       |
| 批量给考试订阅者推通知    | `exam_subscription`   | `idx_exam_notify`      |
| 判断用户是否已订阅某考试  | `exam_subscription`   | `uk_exam_user`         |
| 用户的考试订阅列表        | `exam_subscription`   | `idx_user_id`          |
| 未读通知列表              | `notification`        | `idx_user_unread`      |
| 90天定期清理              | `notification`        | `idx_created_at`       |
| 轮询到期计划              | `notify_plan`         | `idx_status_send_at`   |
| 按内容批量作废            | `notify_plan`         | `idx_source`           |

**通用注意事项：**
- 所有查询均需加 `AND deleted_at IS NULL` 条件，已在相关表建 `idx_deleted_at` 索引
- 列表分页推荐**游标分页**（`WHERE id < last_id LIMIT 20`），禁止大偏移 `LIMIT 1000, 20`
- 点赞/评论计数建议引入 Redis 缓存，攒够N条后批量回写，避免高频 UPDATE

---

## 扩展性预留设计

本节只有设计候选，不计入当前 21 张目标表，也不进入 `V1`—`V3`。真正开发
相关功能时，应重新审查结构并创建新的 Flyway 版本，不能直接复制本节到生产。

### 关注功能（预留，建表备用）

```sql
CREATE TABLE `user_follow` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `follower_id`  BIGINT UNSIGNED NOT NULL COMMENT '关注者ID',
  `following_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注者ID',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow` (`follower_id`, `following_id`),
  KEY `idx_following_id` (`following_id`)
) ENGINE=InnoDB COMMENT='用户关注关系（预留）';
```

###  私信功能（预留，建表备用）

```sql
CREATE TABLE `chat_session` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_a_id`   BIGINT UNSIGNED NOT NULL COMMENT '较小用户ID',
  `user_b_id`   BIGINT UNSIGNED NOT NULL COMMENT '较大用户ID',
  `last_msg`    VARCHAR(255)    DEFAULT NULL,
  `last_msg_at` DATETIME        DEFAULT NULL,
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`user_a_id`, `user_b_id`)
) COMMENT='私信会话（预留）';

CREATE TABLE `chat_message` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT UNSIGNED NOT NULL,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `content`    TEXT            NOT NULL,
  `status`     TINYINT         NOT NULL DEFAULT 1 COMMENT '1-正常 2-撤回',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_created` (`session_id`, `created_at`)
) COMMENT='私信消息（预留）';
```

### 已预留但无需改表的功能

| 功能       | 预留位置                         | 启用方式                     |
|----------|------------------------------|--------------------------|
| 隐私权限     | `user_setting.privacy_*` 字段  | 直接启用字段，前端展示开关            |
| 正式小程序内报名 | `activity_enrollment.type=1` | 更改默认值，增加报名信息字段           |
| 视频附件     | `media_attachment.type`      | 新增 `type=4`，url 存视频CDN地址 |
| 新增考试类别   | `exam_info.category` 枚举      | 直接新增枚举值，无需改表             |

---

##  性能与运维建议

###  MySQL 配置（2GB内存）

```ini
[mysqld]
innodb_buffer_pool_size = 640M
innodb_log_file_size    = 256M
max_connections         = 150
slow_query_log          = 1
slow_query_log_file     = /var/log/mysql/slow.log
long_query_time         = 1
```

### 数据归档策略

| 表                   | 建议                   |
|---------------------|----------------------|
| `view_log`          | 保留近30天明细，每月归档后删除早期数据 |
| `notification`      | 已读超90天的记录定期清理        |
| `content_audit_log` | 全量保留，按年分区存储          |
| `share_record`      | 按季度汇总后可清理明细          |

### COS 直传方案（节省ECS带宽）

```
小程序端                  业务服务端（ECS）              腾讯云COS
    │                           │                          │
    │── 1. 请求上传凭证 ────────>│                          │
    │<── 2. 返回临时密钥/签名 ───│                          │
    │                           │                          │
    │── 3. 直接上传文件 ─────────────────────────────────>│
    │<── 4. 返回文件URL ──────────────────────────────────│
    │                           │                          │
    │── 5. 提交URL给业务接口 ──>│                          │
    │                           │── 6. 写入 media_attachment│
```

文件流量不经过ECS，3M带宽全部留给API接口使用。

### 安全建议

- 手机号字段使用 AES-256-CBC 加密，密钥存环境变量
- 数据库账号权限分离：应用账号仅 DML 权限，禁止 DROP/ALTER
- COS Bucket 设为私有读写，通过后端签名URL或CDN Token鉴权下发文件链接
- 定期执行 `ANALYZE TABLE` 维护索引统计信息

---

*文档版本 v1.5 | 如有业务变更请同步维护本文档与 Flyway 迁移*
