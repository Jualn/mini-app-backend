# 数据库演进入口

## 日常业务独立操作 V101

`V101__add_independent_business_operations.sql` 发布29项细分操作角色及举报目标处置权限，更新受控目录摘要，不改写旧角色成员、既有授权或委派上限。与对应Provider和管理端目录投影匹配发布；旧Provider不能与新摘要混用。新增角色仍依赖其使用权限在目标环境真实可用，不能由静态READY推定数据库已经发布。

## 通知首版 V98

`V98__add_notification_first_release.sql` 新建27组合的稀疏偏好事实 `notification_preference_v1`，不导入旧字段、旧owner或旧偏好；旧表保留。Delivery新增通知类型和接收偏好类别用于去个人化监测，未知历史保持NULL并使涉及的汇总返回503，不猜测回填。新写入Notification使用V1标记，旧行不进入首版V/R；无通知历史迁移、删除或重建授权。

按目标环境既有迁移流程应用V98并使用配套首版Provider与消费者；不能运行旧通知writer/设置adapter。迁移本身不发送通知，不更改用户选择或已读。独占MySQL验证和V1–V98重建不表示业务库已执行，验证命令见[通知首版](../../../../governance/commands.md#通知首版验证)。

## 旧权限目录退役 V95

`V95__retire_legacy_permission_definitions.sql` 移除 13 项旧权限或无管理实现的占位权限，以及异步恢复操作员。迁移校验 V94 目录摘要，若发现其他角色仍引用退役权限，或存在异步恢复角色的历史 Assignment，则停止。部署须配套 `rbac-v1-unified-final-r9` 和 `unified-duty-v1-final-r10` 的 Provider，迁移期间停止旧 Provider 提供授权读取。已参与学习任务的例外撤销保留登记并标记为延期，独立授权和任务范围的发布条件见 Contracts 的 RBAC V1 文档。

## 统一授权正式来源 V91

`V91__register_unified_final_authorization.sql` 将 V88 的已知目录摘要推进到正式 V1，登记独立 REGISTRATION_DISPOSER_V1 的 read/dispose 成员（REGISTERED / NOT_READY），扩展首次报名作废原因为 500 字。保留旧 Role 成员、Authority、ROOT、历史 Assignment、依赖证明、幂等收据和作废事实，不自动修复来源或发布新切片。

部署前只读核实来源覆盖、所有 ROOT 及其下游，确认唯一合法 ROOT 的真实扫码登录/治理后再切换配套 Provider 与消费者。新代码只接受 ASSIGNMENT 且按逐项支持/ENABLED/READY 派生 ROOT 能力；不得与 LEGACY writer 混跑。多 ROOT、异常来源及缺失证明须按明确处置计划处理，不能由迁移猜测修复。回退保留历史列、处置事实和审计，不能退回可恢复管理员作废报名的旧 writer。

## Points 纠错 V88

`V88__add_points_correction.sql` 保留原始审核及唯一GRANT，追加CORRECTION_GRANT/correctionId/basis、正向节点冲正唯一约束和通知结果证据，并发布匹配的Points角色/权限目录。未知目录摘要仍失败关闭，不创建Assignment、组织资格或人员绑定。部署需停止旧Points writer/旧通知worker，按既有目标环境迁移流程应用，并同步二进制、目录与受支持客户端；不能混跑缺少basis或不认识新kind的writer。

当前切片按已批准未上线直接替换，无旧积分数据/客户端/Job载荷桥接。旧不满足依据约束的记录不得猜测回填或删除；失败保留现场、迁移历史和证据，再按已确认范围处理。隔离验证不表示业务库已执行。验证入口见 [积分纠错专项](../../../../governance/commands.md#积分纠错-r6-验证)。

## Activity V1 / V84

`V84__add_activity_v1_lifecycle_and_history.sql` 增加显式持续性开关（默认 false）、删除操作者、首次发布冻结 fields、最近公开最小摘要及本人报名游标索引。复用 Timeline，不增加主时间列，不改报名状态和订阅关系。升级只回填可证明的已公开摘要和已版本化 fields；历史隐藏内容不猜为公开，主时间未知保持未知。V84 不等于目标业务库已执行。

部署前按目标既有流程核对 V83、迁移历史、备份和配套二进制。只读盘点真实 ACTIVITY_START 精度/歧义、开始后报名配置及内部状态3的可信取消/作废时间；持续性须运营逐项确认，不能按较晚 END 自动开启。缺可信公开摘要使用不可用占位；缺可信取消时间保持可诊断失败，不能补当前时间。Provider 先支持新读取，与固定受支持客户端统一切换写语义，禁止新旧准入规则随机混跑；必要时维护切换。

隔离验证使用独占 MySQL 8.4、实例 UUID 和全新受限 schema，覆盖 V83→V84 带旧数据升级、V1→V84 空库重放、validate 及重复 migrate。步骤见 [Activity V1 验证](../../../../governance/commands.md#activity-v1-backend-验证)。不从隔离结果推导服务器或业务库已迁移。

## 导师问答证据 V82

V82 增加 Post 的独立 question_version 与永久 question_content_locked，以及 Comment 的原问题版本、不可变导师归属 JSON、首次公开时间与私有结果提示。仅新受理 QA 生成版本与历史证明；不从当前 Person 自动回填旧回答身份或猜测首次公开，未知历史读取/恢复按契约暂不可确认。问题/正式回答/二级讨论仍使用既有 Post/Comment 与 Person 资格来源，不新增角色或资格体系。

这是 QA writer 的 breaking 切换：迁移前备份并停止受影响旧 writer/审核 worker；部署后读取方须适配归属、当前认证、两种权限和 thread，正式回答 writer 必须发送所读 questionVersion。不得混跑缺少资格/版本复核的旧 JAR。回退保留证据与内容保护列，不能删除历史或改成 NORMAL 绕过。迁移成功不证明目标已部署或客户端已验收；实际业务库仍须单独获准执行。

社区媒体受控读取复用既有 media_upload_record 的文件ID、核验事实与绑定状态，不新增DDL或改已应用迁移。对象键统一为 `post|comment/upload/...` 与 `post|comment/retained/...`，并路由到共享私有桶；读取方切换和实际对象核验须独立执行，不能从新Provider推断旧COS副本失效。引用与不支持键的只读核查入口为 [preflight](validation/community_media_preflight.sql)，发布核对见 [runbook §14.4](../../../../docs/operations/runbook.md#144-社区媒体私有读取切换)。

## Article 统一内容 V83

`V83__add_article_content_lifecycle.sql` 增加 article 的独立文件/来源及成对删除标记、article_category 场景映射、按 User 归属的 article_favorite 和全部 LearningTask 引用索引。旧 JSON 字段 NULL 表示本次扩展前未配置，不从名称、正文或旧 OfficialContent 推断。无账号/组织初始化、权限发布或历史数据复制；收藏不设级联外键，保留目标缺失时取消关系的能力。

部署前备份并核对现有迁移管理方式，停止混跑旧 Article、任务引用和媒体读取写入者，再按目标库流程应用 V83 与匹配二进制。旧 writer 不具备任务引用互斥和媒体范围，不能直接回退旧 JAR。出现失败保留原库、迁移记录和证据，按备份恢复已确认一致状态，不删历史或盲 repair。业务库未因本地验证而迁移。空库及 V82 升级验证入口见 [Article 专项命令](../../../../governance/commands.md#article-focused-validation)。

## 普通社区生命周期 V79–V81

V79 将 NORMAL 同步前置检查后的受理与临时截止、管理隐藏和作者删除分开，新增永久局部创建收据、收藏、最近浏览、每日去重、分享事件和举报结案事实；V80 追加可证明的评论管理效果。旧删除来源、旧待审临时公开资格和旧隐藏回复均不猜测回填。新 `community_thread` 适用于新受理的 NORMAL / MENTOR_QA 共用线程回复。历史已结案举报缺少固定 outcome 时本人结果读取返回暂不可确认，不从当前目标反推过去效果；需有证据的专项处置。

V81 将已知 `2a08a320…` 目录整体推进至 Contracts 普通社区目标的 `620bcf59…`，只同步 post:curate / post:dispose / post:restore 说明；不更改角色成员、来源、职责/目标、期限或 READY/grantable 门禁，旧 post:restore 继续 DISABLED。未知摘要或其他字段差异仍由真实目录校验拒绝，不用该迁移修复任意来源。

部署须停止旧创建/删除/审核/互动及举报写入者，核对消费方 thread、纯图、本人资源与新通知目标支持，再按目标库既有迁移流程应用。Backend 文件入口已按实时公开资格裁决，已有 COS 公有直链的撤回需要另行私有化迁移。新收据与业务效果同事务，浏览去重与计数/最近历史同事务；历史清空不撤销每日事实。业务库、历史来源核实及远端对象处理不由隔离测试替代。专项命令见 [commands](../../../../governance/commands.md#普通社区验证)。

## 文件候选与登记幂等 V74

`V74__add_attachment_candidates.sql` 为 Attachment 增加 CANDIDATE/FORMAL、固定 expires_at 与删除墓碑；现存记录默认 FORMAL，不推断上传者，不将无引用历史正式附件纳入临时清理。登记意图按 operator_id/request_key 唯一，永久保留 fingerprint/attachment_id；已使用的键不重置、不复活已过期或删除资源。当前实现未裁剪这些意图墓碑。

上传 status=0/1/2 继续表示待绑定/正式绑定/清理中，新增 3 表示已清理且保留登记溯源。候选登记可关联 status=0 上传，保存时与清理竞争同一上传行；清理过的登记行保留墓碑，普通无登记临时行仍删除。普通业务绑定只接受服务端 VERIFIED 快照，源对象可按原期限清理，资料字段保留原 objectKey 与独立生效快照的既有分工。

应用 V74 必须先排空旧 writer/cleanup，客户端完成 PUT/分片必需头、登记幂等与候选表示适配，再一致切换登记/保存/清理。不能回滚旧 JAR 让候选变永久或允许已清理候选重新绑定。真实 COS、安全策略、旧凭证退出、CORS/微信域名和上线窗口需另验；SQL 文件不证明目标已迁移。测试入口见 [commands](../../../../governance/commands.md#文件基础-v74-验证)。

## 业务时间对齐 V73

V73 只将旧积分图片自动审核的 `content_audit_log.created_at` 从已确认的 UTC 写入转换为北京时间；精度与其他审核场景不变，已有 UTC 所有权的 Points / 私有材料 / Person / RBAC 数据不变。先停 writer、核验旧写入路径并保存按 ID 的原值，再迁移并部署匹配代码；其他来源或已手动转换的数据必须先确认，禁止全库平移。新连接使用明确 `+08:00` 会话，不能靠改连接时区修复 `DATETIME` 旧值。历史核验、精确恢复和混跑限制见 [runbook §14.3](../../../../docs/operations/runbook.md#143-积分图片微信检查恢复)，当前映射见 [架构 §1.1](../../../../docs/architecture.md#11-时间语义与存储边界)。

## 积分材料 V69 / V70

`V69__link_points_image_safety_audit.sql` 为私有文件增加可空且唯一的自动审核日志外键；scene=12 为积分图片自动检查，targetId 为原上传 User。迁移前检查 scene=12 无未知历史，保留既有文件、申请和审核事实；不自动补写安全结论。先迁移后部署匹配代码，真实微信与私有对象下载/回调验证见 [runbook §14.3](../../../../docs/operations/runbook.md#143-积分图片微信检查恢复)。V70 仅扩展私有材料 kind 约束允许 VIDEO，保留10MiB、归属和生命周期约束；不改写历史材料或审核记录。PDF/Word/VIDEO 暂免内容审核不免格式、大小与私有访问校验。

## 积分受理组织管理 V67

`V67__add_points_acceptance_management.sql` 新增 `points:acceptance-manage` / `POINTS_ACCEPTANCE_STEWARD_V1` 的独立已实现目录切片及永久 `point_acceptance_command` 审计/幂等回执，复用既有 `point_acceptance_evidence` 与当前指针。启停无账务、成员关系或 Assignment 回填，不自动启用组织。Provider 目录必须同步部署 `rbac-v1-unified-r3` / `unified-duty-v1-r4`；停止混用旧目录二进制。迁移后先由 ROOT 在积分受理组织页面逐组织明确资格，或在授权中心授予独立管理职责。

目标库须先核对 V66 结构与实际迁移管理方式、备份及配套二进制，再按其既有流程执行 V67–V68。没有 Flyway 历史的手动管理库不能直接从 V1 自动 migrate。验证新表/外键/唯一性、定义状态、无自动资格行以及完整资格维护→原申报读取链路。新隔离 schema 的通过结果不表示业务库或服务器已升级。

## 积分目录摘要修复 V68

V67 已被执行时保留原文件，使用 `V68__align_points_acceptance_catalog_digest.sql` 将既有权限/角色的已知旧摘要推进到当前目录摘要。V67 新条目与旧条目摘要混用会使真实 `RbacCatalog.verify` 拒绝整份授权来源，表现为扫码后暂时无法确认登录状态。V68 仅改摘要，不改生命周期、readiness、grantable、角色成员、Assignment 或组织受理资格。手动管理库先核对实例身份，保存定义表快照，持有 Person authority 与 ROOT governance 锁，在同一事务中执行；提交前用实际全局目录校验器验证完整成员集合，并比较除摘要外的字段未变化。未知摘要或非摘要差异应停止，不覆盖来源。

隔离回归须同时覆盖：V1–V68 重放/validate、V67 混合摘要被真实校验器拒绝、执行 V68 后通过，以及无自动 Assignment/组织资格。不能再用会话/权限见证替身通过代替全局目录迁移验证。

## 独立 Person 旧结构清理 V66

手动执行 `V66__retire_user_anchored_person_storage.sql`，保留已经执行的 V1–V65 原文件。先选定目标数据库，可设置 `@person_v66_expected_database` 约束库名；停止相关 writer、保存可恢复的一致备份，再完整执行 V66 并部署配套代码和匹配的消费者。V66 是 breaking，不能混跑仍以 User 为 Person 的二进制。

V66 替换七张旧定义表：person_fact_current、person_membership、person_local_membership、party_roster_import、party_roster_entry、party_identity_application、mentor_profile。退役关联旧事实/来源事件与命令收据、旧花名册完整性；未知不会转为核实空集。保留组织、User、RBAC、独立批准与 V65 档案/标识/绑定聚合。表名或表数不能判断是否应删除，审计、收据、范围与完整性仍有独立职责。

Points 的主体、余额锁和唯一性改为 Person，保留原提交 User/Binding 与受理依据证明。已有旧 Points 申请/流水/命令，旧 PERSON 批准、周期依赖任命、无法解释的正式回答证明或旧筛选任务会在破坏性 DDL 前阻断；不能猜测迁移或绕过预检。已有明确账号任务责任保留为 ACCOUNT，提交人不改写，不因之后绑定吞并。旧组织增加受控类型，默认 UNCLASSIFIED。

MySQL DDL 不能使整份切换事务回滚。预检拒绝时保留原库；若 DDL 中途失败，保留失败库和日志，根据原始备份恢复完整一致状态后处理，不以重跑 V65 或删 Flyway 历史作为回退。脚本只对已完成且必要约束齐全的 V66 返回 ALREADY_APPLIED；部分切换停止，不能据此认定可自动恢复。执行后核对实际 SHOW CREATE TABLE、约束、行、必要链路及二进制版本。

本次独占新测试 schema 的空库和升级验证不代表主开发库或服务器已执行。主开发库/服务器继续按各自实际 Vxx 执行记录管理。命令和证据层级见[专项命令](../../../../governance/commands.md#独立person聚合专项验证)。

## 独立 Person 聚合 V65

`V65__add_independent_person_aggregates.sql` 建立 person_profile、专业班级登记、标识/保护值历史/永久占用、账号绑定和双方槽版本。Person UUID 与 User BIGINT 分开，加密与匹配沿用既有安全 owner。当前绑定两侧唯一，结束保留历史，恢复档案不复活绑定。V65 不回填 User、不改 Role/Permission/Duty；不能单独作为旧消费者的兼容桥，当前完整切换依赖 V66 和配套源码。

以下 V1–V64 章节说明历史演进；人员主体与旧结构的当前边界以上述 V66 为准，历史验证不代替当前目标验收。

## 导师管理目录发布 V64

`V64__publish_implemented_mentor_management.sql` 按本轮明确启用请求发布已由持久资格 owner 实现的 `mentor:qualification-manage` 与 `MENTOR_REGISTRAR`。仅初始 REGISTERED/NOT_READY 条目变为 ENABLED/READY/grantable 并清理占位原因，已有显式 DISABLED 等环境状态保持。无新表、无角色 Assignment、无导师资格或 Person 事实回填；正式回答继续依赖独立核准、有效期、账号及可信来源，相关内容仍按原 Post/Comment owner 可见性与审核规则处理。真实目标须执行 V64 才获得此目录发布状态；迁移文件不代表目标已迁移。

工作簿与专业班级修订复用 V63 加密 JSON，不新增表。旧预览须重新映射；正式旧值不得自动拼接、猜测或覆盖历史收据。如目标已存旧五列正式基准，切换前必须核对原始来源和明确转换方案，保持旧修订服务直至转换和消费者同步完成。新版本不将无法解读的旧数据当空值放行。

## 党建名单与导师资格 V63

`V63__add_party_roster_and_mentor_qualification.sql` 增加四个必要资源：`party_roster_import` 保存独立于 User 的私有工作簿、指纹、映射版本、预览和提交来源；`party_roster_entry` 保存未注册人员基准、HMAC 学号索引及可空 User 关联；`party_identity_application` 保存一次成功领取的来源版本与结果；`mentor_profile` 保存真实资料、当前业务资格及共同版本。既有 User、Person 身份/成员、组织及 RBAC 均继续复用；历史和成功收据复用 `person_foundation_event` / `person_command_receipt`，不新增平行审计或答疑内容表。

数据库唯一约束限制同一固定命名空间只有一个 ACTIVE 学号摘要、每个 User 至多一个基准关联、每个 User/基准至多一次成功认证。外键保留 User、组织、批次和事件引用；Person 共同 gate、CAS 与事务保护全批提交和领取。事件增加真实核实方式、来源引用、来源版本及原核准者，保留旧 source 约束（ROOT_OWNER 仍仅用于 SCOPE）；不回填旧历史。收据 key 列扩展 UTF-8 二进制比较以承载新增 Contract 的非空白 key，既有 ASCII 值不改变。

上传恢复继续复用 `person_command_receipt`：仅 createPartyRosterImport 可登记 status=202、event_id=NULL 的未完成命令；200/201 成功收据仍必须引用事件。登记不创建批次或伪造成功审计，完成时与批次/事件同事务将标记转为永久成功收据；已知解析失败只删除仍为202的同一指纹登记。创建者、operation、原 organizationId、key 的唯一隔离不变。批次列表按创建事件时间及 ID 降序，创建收据只返回恢复状态/成功 importId，不新增业务真值或表。

部署须先备份和 Flyway validate，在明确目标执行 V63 后部署理解新来源的完整后端。旧 Person Reader 只接受 LOCAL_MANUAL，产生名单事实后不可混跑或直接回退旧 Reader。迁移不发布目录、不改 Assignment、不核实旧 UNKNOWN、不创建 User。MySQL DDL 中断按既有恢复流程检查实际列/约束和 Flyway 历史，不盲 repair/重跑；回退先停新增入口，保留基准、关联、Person 事实、事件和收据。

配置 `PEOPLE_QUALIFICATION_ENCRYPTION_KEY` 与 `PEOPLE_QUALIFICATION_MATCH_KEY` 为独立的 Base64 编码 32 字节随机密钥，前者加密私有工作簿/字段，后者用于确定性匹配及命令指纹；缺失或格式无效时相关操作 503。不得在配置库提交密钥、输出到日志或直接换 key 使旧数据失联；备份恢复须同时恢复一致密钥版本。未提交批次 24 小时后不能提交；正式来源、映射、基准、核实证据和永久收据不自动清理，后续保留策略不能破坏历史可追溯性。

独占 MySQL 验证与实际业务目标迁移分别报告。测试 schema 不证明业务库已部署；联调还需独占 Redis、真实普通/admin 会话、正式目录 readiness/Assignment、Person 目标范围及经核对的真实来源。命令见[专项验证](../../../../governance/commands.md#党建名单与导师资格验证)。

## 统一业务职责目录 V62

`V62__expand_unified_business_duty_catalog.sql` 增量登记 ARTICLE_CATEGORY_STEWARD_V1、POST_DICTIONARY_STEWARD_V1、POST_MODERATOR_V1 及精确成员，更新77 Permission/25 Role目录 digest 到 `ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424`。旧成员、可用状态、Assignment、来源和原审计不改，新角色 NOT_READY/grantable=false。21个新Duty只在受控JSON及 owning policy 中登记，不新建平行授权表。自动候选审核 scene=11 先作碰撞 preflight，已有不明数据时停止，不能清理历史让迁移通过。

在明确目标先备份、Flyway validate、执行 `validation/unified_v1_preflight.sql` 中适用的来源/历史检查，再单实例迁移并切换完全匹配的目录二进制。该脚本V62后全量可执行；升级前只运行已具备列/表的语句并单独核旧digest。完整个人范围单源收紧必须先盘点，不能自动补PERSON/PLATFORM维持访问。不同digest二进制禁止混跑；保留所有新增事实、来源、撤销与审计，不回退允许拼接的旧查询。新模板的受控发布、真实权限回填、ROOT变更和生产部署不由迁移授权。空库V1→V62、V61带既有READY状态/成员/Assignment/审计升级、validate/重复migrate由 UnifiedR3MigrationTest 在专用MySQL验证；真实目标未执行。实现矩阵与未决依赖见 [RBAC](../../../../docs/rbac-foundation.md)。

## Article 统一组织署名 V61

`V61__bind_article_organization_publisher.sql` 增加可空 publisher_org_id / publisher_name_snapshot，保留旧 publisher_id 外键但允许 NULL，新增组织外键、组织列表索引、Article 自有 write guard 与同事务授权审计。映射表 article_publisher_organization_mapping 只承接经批准的旧 Publisher→Organization 对应；article_publisher_migration_evidence 单独承接每篇历史原署名依据。两表默认空，不以 ID、名称、现成员或当前组织名自动补映射/历史签名，不改原文章、分类、版本、评论及互动。

实际目标先备份并只读盘点 Article/Publisher/Organization、孤立引用、既有标签、授权来源和消费者版本，保留原 ID/版本/状态/媒体/互动基线。停旧 writer，单实例 migrate/validate 至 V61 后部署匹配二进制；旧非空 Publisher 外键写者不能混跑。新代码不注册旧 Bootstrap，不需要先配置 Publisher；Article guard 接替旧 Publisher 行的分类/发布互斥职责。两新职责模板只同步受控副本与 Provider 支持，不修改实际角色就绪/可授予状态，不创建真实授权。

经批准的历史迁移逐篇记录批准人、时间、原新 ID、映射证据、影响集合和恢复记录；先有可信映射，曾发布文章还须有可核实原签名证据，才能受控设置组织及历史快照。当前 Publisher 标签和 Organization 当前名均不等于原签名。证据不足保留旧只读表示并拒绝所有业务改写；映射表和签名依据不能通过公共编辑接口创建。新组织写入有同事务授权审计，后续签名变化由 Article 正式操作负责。

Flyway 成功历史和 checksum 控制重复执行；SQL 不供手动重复运行。MySQL DDL 隐式提交，中断先核对列/外键/索引/表及 Flyway 历史，不盲 repair/重跑。回退先停新写，保留组织、快照、证据及审计，使用能双读 V61 的兼容版本；存在组织数据后不能直接回退旧 Writer、删除新列/审计或用当前名称重写历史。整库备份恢复需单独批准与演练，当前实现不构成目标环境恢复证据。

ArticleOrganizationMigrationTest 在独占 MySQL 8.4 验证 V60→V61 带旧文章/评论/点赞升级、V1→V61 空库重放、validate/重复 migrate 和空映射。实际目标迁移与历史批准不由合成 fixture 代替，命令见 Article focused validation。

## Article 分类两层扩展 V60

`V60__add_article_category_parent.sql` 在原 article_category 增加可空 parent_id、自引用外键及父键索引。旧行全为 NULL（顶层），原 ID、名称全目录唯一约束、状态、排序、审计和 revision 保持；article.category_id、文章版本及内容不改写。不创建候选栏目、不迁移旧分类、不新增栏目归属表。最大两层和关系不可变由 Article owner 的事务内校验保证，当前 V61 Writer 的所有分类维护与文章发布/在线编辑共用 Article 自有 guard 行锁；不允许绕过 owner 直接改挂。

实际目标迁移前备份并核对 Flyway validate / 当前版本、分类总量与名称唯一性、文章引用是否存在；核实 V55–V59 的既有部署前提，不将空库重放等同真实数据 preflight。停旧 writer，单实例 Flyway migrate/validate 至 V60 后再部署匹配二进制。迁移仅 additive，不需要业务数据映射；首次创建真实子项前须确认 Admin Web/小程序等消费者能忽略新增 parentId，并正确处理扁平父子目录。

重复执行由 Flyway 历史和 checksum 保证：成功后 migrate 无新迁移；SQL 本身不供手动重复运行。MySQL DDL 隐式提交；异常中断先检查 information_schema 列/索引/外键与 Flyway 失败记录，确认实际完成范围后按受控恢复流程处理，不盲重跑或 repair。回退先停止新写，保留新增结构及关系，使用理解 V60 的兼容版本；存在子项后不能直接回退会忽略父状态的旧二进制，也不能清空 parent_id、删除子项或改写文章作为回滚。备份恢复须整套一致并另行批准。

真实隔离库验证入口为 ArticleCategoryMigrationTest：V59→V60 带合成历史分类/文章升级、V1→V60 空库重放、外键、索引、validate 和重复 migrate。该测试拒绝已存在的专用 schema，不能指向业务库。目标环境旧数据 preflight、迁移和恢复演练仍需实际证据，Mock 不替代 MySQL 验证。

## Unified 授权、成员、Points 与 Learning V55–V59

`V55__register_unified_points_reader.sql` 对齐 `rbac-v1-unified-r1`，只新增 `POINTS_READER_V1`（仅 `points:read`、NOT_READY、不可授予）及更新已知旧摘要；不修改旧角色成员、已有可用状态、User、Authority、Assignment。先迁移再部署匹配摘要的二进制，不能混跑旧目录。

`V56__add_local_membership_evidence.sql` 为不可变人员事件增加局部组织及局部前后快照，增加 `person_local_membership` 当前正向/明确缺席证据。不回填完整个人集合或组织花名册；局部写仍使用 V48 的人员版本、事件、永久收据和 authority gate。完整替换清除被替代的局部当前投影，保留其事件历史；局部写只修改精确组织周期，不将 UNKNOWN/DISPUTED 提升为完整 VERIFIED。退出后重入生成新周期。

先在独占 MySQL 重放并验证 V54→V56，再对明确批准的实际目标执行；迁移不构成运行授权。回退先关闭局部写入口并停止 writer，保留列、当前事实、周期和事件/收据。产生局部事实后，旧 Reader 不能理解其来源，不得直接回退旧二进制或删除证据；采用理解 V56 的兼容读版本。V55/V56 仅负责目录和局部事实；V57–V59 分别承接下述来源、受理及任务归属，不自动解除发布门禁。


`V57__add_unified_authorization_sources.sql` 在既有 Assignment 增加 origin_kind，旧记录默认 STANDALONE 且保留原唯一槽；UNIFIED 非 ROOT 记录允许多条独立来源。新增受限 authority、Person 逐职责范围、永久复合收据及短期预览/游标，不创建平行 Role/Permission，不自动转换旧范围或授权真人。

`V58__bind_points_acceptance_cycles.sql` 增加受理组织/原周期、CLOSED 与流水归属，保持旧金额和个人账本；历史缺依据保持 LEGACY_UNRESOLVED，未决单不能批准。新增受理资格不可变证据/当前引用及独立花名册完整性证据，全部默认空，没有生产白名单或任意公共维护入口。`V59__bind_learning_task_management_target.sql` 增加唯一管理归属 JSON；旧值 NULL 不猜测回填，管理访问503，本人已有 Assignment 继续有效。

顺序：目标备份与只读 preflight → 停旧创建/审批 writer、排空并分类在途意图 → 单实例 Flyway migrate/validate 至 V59 → 匹配二进制及已迁移 consumer/Provider/目录原子切换 → 目标 HTTP/数据库验证。读取 [unified preflight](validation/unified_v1_preflight.sql) 的计数不能代替资格批准或来源审计。旧客户端缺新必填字段会400；不自动默认组织或任务归属，不支持新旧创建协议长期混跑。

回退：先停止新创建、审批、复合授权和成员 writer，保留 V55–V59 的列、约束、周期、旧/新账务、来源和永久收据；先查询既有意图结果再恢复。已产生 UNIFIED、BOUND、CLOSED 或管理归属后，旧二进制无法解释安全边界，不可直接回滚旧 Reader、删表/列或逆改历史金额/目录摘要；使用理解 V59 的兼容版本关闭新增写入。灾备恢复必须整套一致备份并单独批准，当前没有业务环境恢复演练证据。

## 人员政策审计 V54

`V54__allow_confirmed_person_policy_audit.sql` 为既有 Points 授权审计增加 CONFIRMED_LOCAL_PARTY_V1 来源，保留隔离测试和关闭政策的旧值，不改身份事实、申请、积分流水或角色。先执行 V54 再部署使用已确认政策的新后端；该来源只表达资格政策来源，不代表真实人员/外部 Provider/生产验收已通过。

## ROOT 人员范围 V53

`V53__allow_root_person_scope_issuer.sql` 仅扩展范围来源约束，允许 ROOT 明确签发 `ROOT_OWNER` OPERATE，authorityGrantId 为空；不修改已有人员、组织、范围、RBAC 角色或 Assignment。原 LOCAL_MAINTENANCE 继续要求父 ADMINISTER。新后端与接受该来源枚举的消费者一起部署，先执行 V53；执行记录与测试不能代替实际目标迁移证据。权限和审计规则见[人员基础](../../../../docs/person-foundation.md)。

## ROOT 平台所有者 V52

`V52__align_root_platform_owner_catalog.sql` 更新 ROOT 名称及匹配旧摘要的目录 metadata；不改角色成员、人员、来源、Assignment 或目录 lifecycle/readiness/grantable。权限派生由新 Reader 使用当前真实 ROOT 与当前就绪/支持清单计算，不插入业务角色。旧摘要数据执行 V52 后才部署匹配新 catalog 二进制，禁止新旧摘要混跑；已有人员无需重做 V45/V46。错误/缺失目录摘要不会被迁移自动修复，Reader 继续拒绝。目标环境实际迁移与重启需另行执行并核验，源码存在不代表已部署。

## Points 集成 V49–V51

`V49__add_points_private_materials.sql` 在 media owner 内新增 POINTS 私有上传、材料、永久登记收据和授权访问审计；独立 owner/purpose、upload/index 唯一、单次绑定、状态 CHECK、外键与 cleanup 索引。不创建公开 Attachment，不给历史申请回填材料。服务端不可变留存对象与临时直传对象分离；绑定与申请同事务，保留终审历史。

`V50__add_points_authorization_audit.sql` 只追加授权判断审计，关联 actor、人员、申请/流水、动作、幂等键、事务上下文、authority epoch、开始与最终检查时间。隔离政策明确标为 ISOLATED_CANDIDATE_POLICY，未确认正式政策标为 PROPOSED_POLICY_CLOSED。不修改 V47 账务表或 V48 事实表。

`V51__add_points_material_source_cleanup.sql` 为私有材料增加临时源对象的清理进度；凭证过期后只删临时源，BOUND 留存对象不进入未绑定回收。复用媒体清理调度，失败保留 durable retry 状态。

新二进制先依赖 V49–V51；先迁移后部署，不修改已发布历史迁移、不回填/重建 V47 账务。停用新写入口可保留全部历史，不能以删表或改回旧数据方式回退。正式私有桶/ACL/内容安全、规则批准和冲正角色仍需独立证据，见 [积分实现边界](../../../../docs/points.md)。

## Person foundation V48

`V48__add_person_foundation.sql` 新增人员身份/组织/成员/动作范围/事件、authority epoch 和永久命令收据。不修改 V47 或旧历史迁移，不回填 User 身份/组织，不写 Points 账务。缺 head 为 UNKNOWN。唯一槽、外键、CHECK、聚合版本及 CAS 共同保护事实；时间为 UTC DATETIME(6)。事件及收据的互指由 receipt 外键和 owner preflight 验证，避免 MySQL 立即外键下无法插入的循环外键。

V48 同步 canonical RBAC 的四个权限和三个角色，新条目保持 NOT_READY/grantable=false；更新全部目录摘要以匹配受控副本，保留既有条目的 lifecycle/readiness/grantable 和 Assignment。先执行 V48 再部署新副本；旧二进制目录摘要不能在 V48 后混跑。回退先关闭新入口并停止新 writer，保留人员事实/事件/收据；不得删表、改回目录摘要或回写 User.role 冒充回滚。

当前四阶段与 Points 阶段准入政策已经受控目录确认；真实人员事实、受理资格、范围来源、私有 Provider 和 B01/B02 目标验收不随迁移自动成立。正式 ADMINISTER 需独立批准、精确目标/epoch/账号来源和线下执行；无自动 ROOT 初始化或真实人员授予。只读 `PersonIntegrityService.inspect` 区分 UNKNOWN 与损坏，不自动修复。锁、初始授权和验证边界见 [人员基础](../../../../docs/person-foundation.md)。生产迁移、真实授权和回退演练均需其目标环境证据。

## Points core V47

`V47__add_points_core.sql` 新增 `point_application`、`point_ledger`、`point_request`、`point_subject_lock`，不转换或修改既有人员、业务、RBAC 数据。当前迁移历史没有可复用的积分表。后者仅协调每用户的并发，不保存余额；汇总以 Ledger 的精确 DECIMAL 中间和计算。时间为 UTC `DATETIME(6)`，User 外键保持现有 unsigned ID 类型。

`grant_application_id` 仅 GRANT 非空且唯一；`original_ledger_id` 仅 REVERSAL 非空且唯一，利用 MySQL 多个 NULL 互不冲突，分别保证每申请唯一授予和每原授予唯一冲正。跨行金额/归属/APPROVED 对应关系由 Service 锁与同一事务保证；Mapper 不提供流水改写/删除入口。当前 Service 使用 REPEATABLE_READ；先取人员 gate，共享同事务 User/RBAC owner 锁，再按用户 ID 升序锁定 subject、申请。受保护的第一份一致读在 owner 锁后建立，等待期间不复用旧授权/旧汇总。不可变申请人/流水归属的预定位在独立只读事务中完成，不携带授权结论。永久幂等键按主体 User、会话类别、operationId、目标 ID 隔离并使用大小写敏感索引；成功回执同事务保存，保留至业务历史清理前，不定义自动清理。

本人列表使用 `(applicant_user_id,id)` / `(applicant_user_id,status,id)`，流水使用 `(user_id,id)` / `(user_id,kind,id)`；队列预留 `(status,id)`。稳定 ID 倒序及一基有界分页；count 与 items 同一 REPEATABLE_READ 快照。组织管理列表和聚合通过 Person owner 签发的事务绑定 plan，在 SQL 中应用相同 owning predicate 后分页、COUNT/GROUP；候选仅为实际 Points 参与者，未知相关成员不得被当作不属于组织。

先在隔离库重放及验证升级，再对明确批准的目标迁移并部署新后端。代码读取新表，不可先部署到 V46 库；迁移失败不得清库或重建既有表解决。V47 原切片验证过隔离空库及 V46→V47；当前 V1–V59 与有历史数据的升级另有隔离验证，内部私有文件、通知受理及人员/逐职责边界已接通。未访问生产，外部 Provider 和真实目标验收仍需证据，迁移成功不代表可开放申请。回滚需停用新增写入口并保留全部积分历史，不自动撤销 DDL 或改写账务。执行证据和剩余项见 [积分实现边界](../../../../docs/points.md)。

## Audited Initial ROOT Correction V46

`V46__support_audited_initial_root_correction.sql` 仅扩展离线 operation CHECK，支持 v3 初始账号纠错及固定初始 LEARNING_MANAGER 授予事实；不直接修改人员或目录。原 v1/v2 审批与 kind 约束保持，新增事实有明确版本。实际人员变化由用户批准的独立纠错清单、同一事务及运维审计承接，见 [离线治理 §8.1](../../../../docs/rbac-offline-governance.md#81-明确误选初始账号的更正)。必须先准备 v3 Reader 再执行人员纠错；旧构建不能读 v3，不能删历史、伪造正常 Change 或直接回退。生产执行仍需另行批准。

## Explicit Initial ROOT V45

`V45__support_explicit_initial_root_migration.sql` 仅向前扩展离线 operation 的 version/approval CHECK，保留 v1 双签，支持 v2 指定初始 ROOT 的真实运维清单批准。DDL 不选择人员、不启用权限、不改 User 或 Authority；人员及两个完整角色的受控状态由独立 `RbacInitialMigration` 在一个事务提交，见 [离线治理 §8](../../../../docs/rbac-offline-governance.md#8-指定初始-root-的运维迁移)。不重写 V41/V43，不将初始 ROOT 写成伪造的普通 GRANT actor。

先准备新 v2 Reader 再执行实际人员迁移；旧 Reader 不能理解新收据，不能在已切源后回退。V45 可先 expand，失败停止、保留证据，不自动 repair/baseline。线上重新批准人员、环境与支持制品；本次仅当前测试环境获准。

## LearningTask Preview Classification V44

`V44__freeze_learning_task_preview_membership.sql` 只向既有预览表追加 nullable 的新分配人数、已有分配人数和原已有人员 ID 快照；CHECK 校验整体缺失或完整数组/人数一致。新 writer 在任务锁内一次冻结，发布/追加后 GET 不重新分类。无新增身份/通知表，不更改任务、Assignment、Submission、审核或完成事实，不启用权限目录。

旧行保持 NULL；摘要优先从原幂等收据恢复原人数，不写回。旧快照若全新或全部已有，可准确恢复对应名单标记；混合名单没有旧分类证据时拒绝成员读取，不按当前分配伪造历史；须重新预览。旧 NULL ETag 仍按 V39 拒绝应用。新代码必须在 V44 后运行；部署时暂停预览 writer，受控 expand 后部署全部新 writer，再考虑开放，不能用旧 writer 混写不完整新快照。回退保留列、收据和历史，既有外键/唯一性不变。

本轮在独占 MySQL 8.4 验证 V43→V44，使用原 synthetic 历史 fixture 对照 Task、Assignment、Submission、预览既有字段完全不变；另库 V1–V44 replay 和 Flyway validate 通过。领域测试验证分类冻结、CHECK 拒绝不一致及旧收据读取。生产 DDL、备份、锁等待与目标历史未执行；见 [LearningTask](../../../../docs/learning-task.md)。

## RBAC Offline Governance V43

`V43__add_rbac_offline_governance_facts.sql`仅新建`rbac_offline_operation`和`rbac_offline_effect`，不修改V41/V42、普通授权成功行或目录状态。操作唯一ID、环境/执行主体、批准证据/双签、规范化请求/指纹、版本化固定回执与逐User前后事实独立保留；真实User/Assignment/operation引用RESTRICT。离线ROOT的grantChangeId引用同事务真实effect，不伪装普通Change；普通撤销仍保留原授予引用并新增Change。

该迁移不创建测试guard、不授予人员、不切来源、不提供生产执行权限。正常Reader/HTTP六表职责不变；实施边界见[离线治理](../../../../docs/rbac-offline-governance.md)。只读[offline preflight](validation/rbac_offline_preflight.sql)检查操作/effect/审计引用基本一致性，空异常集不证明已恢复最新撤权、来源和幂等事实；仍须核对独立故障域证据。不支持删除新表或回退role-only二进制作为回滚。生产DDL/备份/恢复与执行器须另行批准。

当前ASSIGNMENT Reader/历史查询会读取V43两表核验已知离线授予origin；不能让此构建接入缺V43的ASSIGNMENT流量。effect关联按唯一Assignment索引读取，缺effect的origin从operation收据JSON_TABLE追溯，不靠缺行默认为普通授权。两份origin历史同时丢失并不能由此读取还原；仍须preflight和离线最新事实核对。生产历史规模下的收据追溯成本需另行验证，不把小库闭环视为容量验收。

## RBAC Audit Publication V42

`V42__add_rbac_audit_publication_order.sql`在V41后仅给authorization_change添加内部自增publication_order及唯一索引，用作正式审计列表的已提交高水位。旧UUID主键、公共Change ID、已有成功行/收据、FK及六表职责不变；Governance仍按ROOT mutex串行成功writer，未增加新owner。该序号不出现在HTTP中，不以UUID或可能相同的时间戳猜测发布顺序。

不启用Role/Permission、不初始化ROOT或切换Authority；没有清理/归档/保留策略变更。旧writer的显式列INSERT向前兼容该新增列，但授权来源切换后禁止退回旧role-only二进制的既有约束保持。MySQL8.4专用测试验证空库重放及V40/V1 baseline升级至当前迁移；目标环境DDL、元数据锁/备份和生产发布仍须单独批准。正式HTTP与期限精度隔离验收见[RBAC基础](../../../../docs/rbac-foundation.md)，发布条件与恢复见 [runbook](../../../../docs/operations/runbook.md#rbac-发布与恢复)；文档不构成执行批准。

## RBAC Foundation V41

`V41__add_rbac_foundation.sql` 在V40后新增方案A六张 `rbac_*` 核心表，登记canonical的73项Permission及18个候选Role/不可变成员。全部保持NOT_READY、不可授予，五项历史/延后Permission保持DISABLED；**不回填Authority、不创建人员Assignment、不激活ROOT、不改user_profile.role**。应用资源 `rbac/rbac-v1.json` 与迁移seed固定同一canonical SHA-256，核验入口见 [commands](../../../../governance/commands.md#rbac-foundation-focused-validation)。

User FK沿用BIGINT UNSIGNED；code/version采用NO PAD二进制比较，幂等key为VARBINARY(128)，actor+key跨命令唯一。Assignment只允许显式namespace/GLOBAL，ROOT无expiresAt；当前槽唯一、过期释放/撤销保留历史，授权/版本/成功审计收据由同一事务维护。MySQL不支持延迟环形FK，Assignment到Change的审计引用由事务owner及失败回滚验证；Change到Assignment/User采用RESTRICT FK。新增RBAC时间以UTC DATETIME(6)保存，不改变旧User限制时间语义。

迁移是expand结构准备；现有业务仍用旧来源。专用MySQL8.4已验证空库V1–V41、带旧User的V40升级及V1生产式baseline接管重放，不是实际生产快照或生产执行证明。目标环境须核对Flyway history、MySQL CHECK及utf8mb4_0900_bin支持、字符集/时区与主库连接，并确认只登记而无人员变更。无cleanup、自动归档或DROP回退脚本；D06保留方案待批准。

激活前必须完成显式LEGACY Authority初始化/回填、全部旧授权消费者与ROOT账号guard接入、D07差异审批、D03人员/环境批准及独立admin/QR集成。未满足时不得切ASSIGNMENT；一旦将来启用，最低回退版本必须理解互斥source，不能退回旧role-only二进制复活已撤权限。内部服务及当前阻断详见 [RBAC基础](../../../../docs/rbac-foundation.md)。

## Post Organization V40

`V40__add_post_organization.sql` 在 V39 后增加 Post 字典、分类适用集合、有序话题关联，以及 post_type/category_id/revision/audit_revision；历史 Post 自动 NORMAL，其内容、作者、审核、媒体、时间与互动不重写。首批四类按唯一 code 初始化，ID 由数据库分配。外键保护分类/类型和话题引用，关联主键去重、位置唯一键及 CHECK 限制最多三条；有效状态和新关联由同事务字典行锁保护。Comment 复用 V36 的 reply_to_comment_id，只增加 nullable mentor_evidence，不猜测旧 reply_to_uid 对应的具体评论或历史导师资格。

执行前在明确授权目标运行只读 [Post preflight](validation/post_organization_preflight.sql)，确认 V39、同名表/列与独立审核场景 10 未被使用。迁移自身对场景冲突 fail closed；不提供自动 baseline/repair。MySQL DDL 不承诺整体事务回滚，失败后停止并按受控恢复流程处理。先备份并停止受影响 Post/Comment writer、reader 和审核 worker，再迁移 V40、部署新服务；旧服务列表不识别 PostType、评论不具备资格校验，不得在答疑存在时混跑或直接回退旧 JAR。回滚保留新表、关联、证据和内容，需先关闭新入口并安排旧读写兼容，不把 QA 改成 NORMAL 或删除历史。

隔离验证入口为 PostOrganizationDatabaseTest：只接受本次一次性 `jdbc:mysql://127.0.0.1:<port>/post_organization_test`，root/空密码，并要求两个测试 schema 不存在。测试创建 post_organization_test 和 post_organization_replay，分别验证 V1–V39 / synthetic 历史 / V40 升级，以及 V1–V40 空库重放与 validate。不能在共享实例或业务数据库运行；不传 URL 时数据库测试跳过，不报告 PASS。该旧 V40 专项不证明当前正式导师来源或 V82 内容版本裁决；迁移完成不授权开放正式回答。

## LearningTask V39

`V39__bind_learning_task_preview_representation.sql` 只给既有 learning_task_preview 增加 nullable ASCII `task_etag`；不增加角色/身份、文件或通知表，不改写 V38。任务响应含关联 Article 的当前公开可用性，强 ETag 必须随该表示变化；预览因此绑定完整 ETag，不能只绑定 task revision。

旧预览保留 NULL，新 apply 将其视为 preview-stale，不回填当前标签冒充原预览。任务、分配、Submission、审核、完成、首次时间及通知事实均不变化；只需重新预览，不重建分配。新增 writer 总是写完整标签。部署前确认 V38 和目标旧数据，备份并受控迁移 V39，再部署依赖该列的代码；不得让 V38-only 数据库运行新的预览 writer，不混跑仍生成未绑定预览的旧 writer。回滚保留列和历史，旧预览不自动恢复可应用资格，MySQL DDL 不承诺整体事务回滚。

V39 阶段验证含 `learning_task_upgrade` 的 V1–V38、[synthetic 历史 fixture](../../../test/resources/learning-task-v38-fixture.sql)、V38→V39/validate 及升级前后事实对照；另有 `learning_task_replay` 的空库 V1–V39 replay/validate。旧 NULL 预览不可 apply 的行为由真实 DB 领域测试验证；测试不能使用业务库。当前纵向实现、Provider 和受控授权边界见 [LearningTask](../../../../docs/learning-task.md)，本轮迁移证据见上节 V44；迁移成功不授权开放业务，没有生产迁移或部署证据。

## LearningTask V38

`V38__add_learning_task.sql` 仅增加 LearningTask 自有的六张表；任务/用户唯一分配、提交次数唯一键、生成列单待审唯一键、父子复合外键及状态/时间 CHECK 共同保护事实。复用 `user_profile.id` 与 `article.id`；不修改 Post/Activity/Article/旧审核或媒体数据。Assignment.latestSubmission 必须属于同一 Assignment，Submission 必须属于同一任务分配链。管理操作者的存在、认证和数据范围由授权 Provider 负责，不把普通 role 或运营标志转换为新权限。

部署前在明确授权的目标执行只读 [LearningTask preflight](validation/learning_task_preflight.sql)，盘点 migration history、既有 learning/submission 表、旧 kind/CONFIRMATION 数据和时间区。仓库未发现旧 LearningTask 不证明目标环境无历史；发现旧表或确认审核时先评估兼容，不自动 drop、baseline、repair 或把 CONFIRMATION 转成 MATERIAL。V38 不使用 IF NOT EXISTS 掩盖同名旧结构。

日期显式按 Asia/Shanghai 的 DATETIME(6) 存储；领域使用可注入 Clock，输入规范化至微秒，不依赖 JVM 默认时区；HTTP 已在隔离测试中验证 RFC3339 offset 转换及微秒回放，未完成正式授权开放。先核对 V37 基线与备份，再受控执行 V38；当前代码还要求 V39 与 V44。管理入口依赖当前正式授权，本人入口按当前账号与所属 Assignment 校验，不允许仅替换 Provider 或添加开关便开放业务。回滚保留新表与历史，不回写/删除完成或审核事实，不释放文件历史引用；MySQL DDL 不承诺整体事务回滚。实现/依赖边界见 [LearningTask](../../../../docs/learning-task.md)。

隔离验证分别使用 `learning_task_test` 的 V37→V38 升级（含旧 Post synthetic fixture），以及 `learning_task_replay` 的空库 V1→V38 重放/validate；不能据此声称业务库已迁移。数据库测试清理仅限本次 disposable `learning_task_test`，不读取 application 配置。

`V33__align_activity_reminder_v1_storage.sql` 扩容 Timeline label/description/time_description 到 Contract 的 120/2000/500，Plan/Notification title 到 Activity 合法标题的 200；新增 nullable `notify_plan.recipient_upper_user_id`，首次 fan-out 条件冻结扫描上界，重试复用。历史字段内容、状态、source key、readAt 和关系保持，不制造 Delivery、不改写 notify_enable。部署前运行只读 [Activity Reminder V1 preflight](validation/activity_reminder_v1_preflight.sql) 分类历史任务、开关及 DB 时间区。新代码的 Mapper 依赖 V33；停止旧提醒 writer 后迁移，再启动新代码，不能仅恢复旧 JAR作为 V1 语义回滚。业务与兼容边界见 [通知设计 §11.2](../../../../docs/reminder-notification.md#112-activity-reminder-v1-当前实施入口与兼容边界)。

`V32__allow_unknown_activity_participant_mode.sql` 将 `activity.participant_mode` 改为有符号 TINYINT、允许 NULL、默认 NULL，其他列及索引不变。新草稿未知参与形式存 NULL，API 省略可选 participantMode；明确值仍为 1=INDIVIDUAL、2=TEAM。V5/V10 记录的历史 0 按未知回读，3 为历史 either 且不能映射为个人或团队；迁移不回填历史行、不修改发布状态或 contract_version。已发布 NULL/0/3 或非法编码需运维按原始依据审查，不自动推断。

部署 V32 前运行只读 [参与形式 preflight](validation/activity_participant_mode_preflight.sql)，核对实际列定义与状态分布，备份并暂停相关 writer 后迁移，再部署新后端。发布及已发布编辑由业务层拒绝未明确形式，返回 publish-validation-failed 的 /participantMode；数据库允许 NULL 不等于允许不完整发布。旧后端会把 0 误读为个人，不能仅恢复旧 JAR 即视为语义回滚完成；将列恢复 NOT NULL 前必须先处理新 NULL 草稿，禁止自动改为个人或 0。本地 replay/升级测试不代表生产已执行 V32。

`V31__retire_activity_audience_bitmask.sql` 将活动受众统一到已有 `audience_department_ids`：空数组为全院，非空字符串数组为部门，NULL 为未设置草稿。没有结构化受众的旧位掩码按原公开读取结果回填为全院或字符串数字 ID，非空 JSON（含未知 ID）保留；不猜测规范部门映射。回填增加 contract_version 后删除 audience_scope。非法 JSON、重复 ID、无法表达的未知位在数据更新前阻断。部署前运行只读 [活动 preflight](validation/activity_discovery_preflight.sql)，停止所有旧应用和活动 writer、验证备份，再迁移并启动新应用；不能混跑或直接回滚旧 JAR。部署顺序、恢复边界及业务映射待确认项见 [活动发现](../../../../docs/activity-discovery.md)。生产执行留到部署，仓库与隔离验证不代表已迁移。

`V30__bind_registered_attachment_uploads.sql` 增加 `bound_attachment_id`（独立附件生命周期接管者，首次登记 ID）与 `cleanup_started_at`（首次清理接管事实）。`bound_target_id` 仍表示旧业务目标；附件归属与业务归属互斥，重复元数据登记共用对象的首次接管关系。登记元数据与 PENDING → BOUND 在同一事务提交，清理抢占同一上传行；已进入过删除的对象不得按普通新上传重新绑定。媒体替换的删除意图在业务事务内持久化到现有上传记录，提交后由清理任务处理。

V30 不从历史 URL 或目录数字伪造上传归属，也不批量认定 COS 对象存在。切换前先运行只读 [附件 preflight](validation/media_attachment_lifecycle_preflight.sql)，停写并排空旧清理实例，再执行迁移及受控 [附件 repair](validation/media_attachment_lifecycle_repair.sql)；repair 只接受带对象存在／来源证据的显式映射，默认 ROLLBACK。历史直接业务 BOUND、缺上传记录、来源不明及已缺失对象另行核对，不能强行转换。新 worker 对独立附件、已有业务归属及真实事件引用的精确 key／配置 URL 别名做删除保护；非规范编码、旧域名、带 query 的历史 URL 必须在重新开放清理前分类解决。不能混跑旧清理／writer，也不能直接恢复旧 JAR；生产迁移、回填未由源码或隔离测试证明完成。步骤见 [运维手册](../../../../docs/operations/runbook.md#14-附件与媒体生命周期切换)。

`V29__protect_effective_user_profiles.sql` 为资料写入增加单调 `profile_revision` 和头像/背景的服务端快照 key。历史行 revision 从 0 开始，历史资料与媒体引用不自动改写；快照 key 初始 NULL，不伪造已审核事实。所有新旧资料 writer 与 callback 必须切换同一应用版本，不能混跑旧乐观 writer。资料与审核历史分类使用 [Profile preflight](validation/user_profile_contract_preflight.sql)；运行与恢复限制见 [运维手册](../../../../docs/operations/runbook.md#13-用户资料安全检查与恢复)。

旧审核日志未保存候选与旧有效版本，不能只按 pending/rejected/unknown 状态自动恢复资料或认定当前值已审核通过；历史 NULL nickname、媒体引用和未知审核结果由资料 owner 按可追溯证据处理。旧媒体源对象不自动成为已审核快照。不能直接回滚至旧乐观 writer/callback JAR；恢复须停止资料写入、明确数据兼容方案，不自动撤销 DDL 或删除已绑定快照。

[migration/](migration/) 是可重建数据库结构的唯一可执行来源。数据演进的通用保证见 [Backend §3](../../../../standards/backend-engineering.md#3-persistence--data-evolution)，模块所有权和 SQL 写法见 [项目架构](../../../../docs/architecture.md)。
本文件只补充 Flyway 与当前仓库流程，不维护手工 DDL 快照、生产版本表或服务器参数建议。

## 编写与切换

- 使用 `V<递增整数>__<英文描述>.sql`。已进入永久环境的版本不可重写，修正时新增版本；不要通过修改 Entity 假装完成迁移。
- 变更时检查历史数据、约束/索引、Entity、Mapper/XML、字段投影、相关测试与应用切换顺序。
- 新增字段的默认值和历史回填须明确。大批量回填与 DDL 分开设计，说明批次、校验、重试与恢复边界。
- 需要混合版本兼容时先扩展、迁移，再收缩旧字段。恢复旧 JAR 不撤销已执行的数据库变化。
- 不在 SQL 中写真实凭据、生产数据、环境专用库名或 USE 语句。

## 执行与证据

- 应用不负责生产启动时自动迁移。生产迁移通过受控运维流程在核对目标、备份及取得相应授权后显式执行。
- 现有数据库接入 Flyway 前必须比对结构和历史，再确定 baseline；不把任何固定 baseline 版本套用于未知环境，不启用 baselineOnMigrate 绕过核对。
- 空库完整重放与现有库升级是两种验证。CI 的 Flyway 配置见 [workflow](../../../../.github/workflows/backend-ci-cd.yml)，命令边界见 [commands](../../../../governance/commands.md)。
- 当前数据库版本以目标环境的 flyway_schema_history 和实际结构核验为证，不能由仓库存在某个 V 文件推断已部署。
- [validation/](validation/) 是专项盘点/检查 SQL，不能替代版本迁移；执行前检查其内容及目标。

## 本地开发容器管理

本地入口为仓库根目录的 `compose.local.yaml` 和 `tools/local-dev.ps1`。MySQL 继续使用 Windows 本机 `localhost:3306/jualncampus`，不搬库、不创建替代 MySQL；Flyway 容器通过 Docker Desktop 的 `host.docker.internal` 连接该本地库，固定 CLI 镜像与迁移只读挂载，使用 `.env.dev` 的 `MYSQL_USER` / `MYSQL_PASSWORD`。容器只接收这两个必要凭据，不加载其他微信 / COS 等秘密。开发 profile 关闭应用内 Flyway，迁移不随 Java 服务启动。

脚本复用已有 `redis` 容器及其原数据卷，保留原配置；不把它强行纳入 Compose，也不删除或替换。默认 Start 先启动并 PING Redis，再运行 Flyway `migrate info`，执行成功返回 0，失败返回非零并保留控制台输出。Flyway 是一次性任务，Docker Desktop 中 `jualncampus-flyway` 显示 `Exited (0)` 是正常完成；Redis 才是常驻进程。后续每次 Start 会重建这个已结束的 Flyway 容器并读取最新迁移文件，输出保存在 `target/local-dev/flyway-*.log`，不依赖容器旧日志。不要只在 Docker Desktop 中启动上一次 Info/Validate 容器来代替 Start。

```powershell
# 在启动 IDEA 中的后端前执行；也可以从其他目录用脚本绝对路径调用。
.\tools\local-dev.ps1
.\tools\local-dev.ps1 -Action Status
.\tools\local-dev.ps1 -Action Info
.\tools\local-dev.ps1 -Action Migrate
.\tools\local-dev.ps1 -Action Validate
.\tools\local-dev.ps1 -Action Logs
.\tools\local-dev.ps1 -Action Stop
# 可单独控制，不停止或重启 Windows MySQL、IDEA 后端，也不删除数据卷。
.\tools\local-dev.ps1 -Action Start -Service Redis
.\tools\local-dev.ps1 -Action Start -Service Flyway
```

第一次接管旧手动管理的库：先核对真实实例、完整备份、迁移重放结构（列/默认值/索引/外键/CHECK）及必要的数据迁移事实，再用 `-Action Baseline -BaselineVersion <已核实版本>` 显式建立一次基线。基线只声明此前版本已由外部管理，不会补跑它们，也不会创建每份旧 SQL 的执行记录或校验和；之后新增版本才逐条记录。不能从 `info` 的 Pending、当前文件最大版本或表存在推断旧数据迁移已执行。结构漂移应明确分类与保留证据，不借接管批量重写业务数据；未确认的库先停在 Info。

空库不做 baseline，直接 Migrate 从 V1 开始。禁止启用自动 baseline；本入口固定 `cleanDisabled=true`，不提供 clean/repair、批量重试或强制中断正在运行的迁移。失败先看日志和 `flyway_schema_history`，MySQL DDL 可能已经提交，不能通过修历史记录掩盖失败。迁移启动前停止相关业务 writer；脚本不自动关闭 IDEA 或后台应用，也不自动执行各版本需要的业务数据 preflight。备份和本次接管核验证据放在被 Git 忽略的 `target/local-dev/`，自行保留需要的恢复文件，不执行 `mvn clean` 丢弃备份。生产继续使用已有受控运维流程，不运行此本地 Compose。

此入口只管理已有 Redis 的进程生命周期，不改变其当前持久化配置。退出 Docker Desktop、机器断电与完整持久化保证需按 Redis 自身配置判断；Flyway 迁移历史存储在 MySQL 的 `flyway_schema_history`，不依赖 Flyway 容器生命周期。机制参考 [Flyway Docker](https://documentation.red-gate.com/flyway/reference/usage/flyway-docker) 与 [Baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline)。

迁移新增或机制变化时按 [maintenance-map](../../../../governance/maintenance-map.md) 检查依赖，只更新受影响来源，不重建旧数据库设计文档。

## RBAC V41 与 Authority 启动门禁

V41只登记六表和73项Permission/18个候选Role，全部NOT_READY且不可授予，不初始化人员或回填Authority；不得修改已执行V41。G2本身没有新Flyway版本；V48另行增量登记人员基础权限/角色及更新目录摘要，仍不创建人员Assignment。新User与明确LEGACY Authority在同一事务建立；历史User必须在启动强制Reader的新二进制前显式回填。缺失/损坏Authority会使AuthorizationReadinessGate阻止启动，不默认LEGACY。

目标环境须批准、备份并静默旧User writer：V41→[只读preflight](validation/rbac_authority_preflight.sql)→[显式LEGACY回填](validation/rbac_authority_legacy_backfill.sql)→提交后再次核对missing=0/corrupt=0→新二进制启动门禁→开放流量。保留目标、批次、插入/缺失/冲突计数；重复no-op且绝不覆盖ASSIGNMENT及version。本文不批准任何真实环境执行、ROOT引导或来源切换。实际隔离测试/兼容约束及未来旧reader回滚限制见[RBAC基础](../../../../docs/rbac-foundation.md)。

当前异步实现的可执行结构由 `V17__add_async_processing_foundation.sql` 创建：`outbox_event`、`async_job`、局部消费者幂等表 `async_event_consumption` 以及 `notification.source_key` 唯一约束。其状态含义、保留期与恢复责任由 [Async Processing Architecture](../../../../docs/async-processing.md) 定义；目标环境是否已执行 V17 仍以 `flyway_schema_history` 和真实结构为准。

`V18__backfill_pending_notification_jobs.sql` 只把 `notify_plan.status=0` 的 durable schedule 幂等回填为 fan-out Job；已有 `content_audit_log.final_result=0` 不能仅凭状态判断是否应重新提交外部审核，必须先运行 `validation/async_migration_preflight.sql` 分类并人工处理 unknown。

`V19__add_async_dead_message_evidence.sql` 增加 `async_dead_message`，持久保存 Redis Stream terminal/poison delivery 的记录身份、关联 ID、受控错误分类和投递次数，不保存原始 payload。它是定位和受控 replay 的运行证据，不改变 Outbox 已完成发布的事实。

`V20__expand_reminder_notification_delivery.sql` 是 Reminder/Notification/Delivery 的 expand migration：以 nullable 新列兼容历史 `notify_plan`/broadcast，为新 Notification 增加渠道无关冻结快照，并创建带渠道唯一约束和明确终态的 `notification_delivery`。它不从旧 plan 或 Job 状态推断外部送达，也不自动生成历史 Delivery。目标环境升级前先运行只读 `validation/reminder_delivery_preflight.sql` 分类，切换并 drain v1 Job 后才可设计后续 backfill/contract migration。

`V21__add_notification_preferences_and_inbox_generation.sql` 为 canonical 通知接口增加 Category × Channel sparse override，并把 Notification 明确分为 `LEGACY` 与后续 producer 才可写入的 `CANONICAL` 世代。默认值保持尚未切换的旧 writer 自动归入明确旧集合；迁移不制造 `IN_APP` Delivery，也不切换 producer 或旧设置 owner。canonical reader 的共同集合为明确旧集合与具有已生效 `IN_APP` Delivery 的新集合并集。D1 已明确不追溯历史消息；目标环境切换仍受 contracts 协调文档的 D2 preflight 和 D3 逐用户 owner 切换门槛约束。

`V22__add_notification_preference_owner.sql` 增加偏好来源 `USER_OVERRIDE/LEGACY_MIGRATION` 和逐用户 canonical owner 标记。无 owner 的历史用户不能由 canonical GET/PATCH 伪装为默认值；旧 activity/exam 有效布尔值须在锁定 `user_setting` 后原子导入服务号组合并切 owner，已有偏好冲突时停止而非覆盖。新用户创建时直接建立 canonical owner。V22 只提供结构，不自动批量迁移历史用户；历史批量回填由 V24 承接，两者的结构与数据效果不能混为一谈。

`V23__enforce_unique_official_account_identity.sql` 用唯一键保护服务号 `mp_openid` 不能关联多个用户；应用层对重复同一关联保持幂等，对替换和争用一律拒绝，不自动合并用户。升级前运行只读 `validation/wechat_identity_preflight.sql`；重复或空身份必须由身份 owner 明确处理，不能在迁移中静默选择任一用户。实际执行版本按目标库 migration history 核对，部署确认见运维手册。

`V24__backfill_notification_preference_owners.sql` 在旧设置写入已静默的发布边界内迁移历史用户：把 activity/exam 的有效布尔值仅导入对应的服务号组合，来源记为 `LEGACY_MIGRATION`，再在同一事务切换为 canonical owner；缺少 `user_setting` 时采用已核实的旧 DDL 缺省 `false`。已有 canonical owner 保持不变；存在“已有偏好但没有 owner”的冲突或非法旧布尔值时迁移直接失败，不覆盖或猜测。该迁移不改变 IN_APP/小程序偏好，不表示微信授权，也不替代 D2 producer/reader 切换证据。

`V25__freeze_reminder_subject_delivery_fields.sql` 为 ReminderPlan 增加渠道无关的开始时间与地点快照，使 Notification fan-out 能在不回查可变 Activity/Timeline 数据的前提下生成外部投递内容。迁移只对可由 `timeline_id` 明确关联的现有 `notify_type=8` 计划补值；无法关联的历史计划保持 NULL，并继续只生成站内通知，不制造服务号 Delivery。

Timeline 的业务 semantic 继续使用既有 `timeline.node_type VARCHAR(32)`；POINT/RANGE/DATE/TEXT 结构由 precision/time 字段表达，label 仅用于展示。正式启用 ReminderPolicy 前运行只读 `validation/timeline_semantic_preflight.sql`，分类未知/重复/非精确/缺失 START 与 orphan 节点。不得按 label、排序或首个 EXACT 节点自动回填 semantic；当前没有新增 Timeline schema migration。

`V26__expand_notification_read_and_inbox_stream.sql` 增加 canonical `read_at`、nullable `inbox_seq`、逐用户 counter 与未读/类别分页索引。`V27__backfill_notification_read_and_inbox_stream.sql` 在旧 Notification/read/Delivery 写入静默后执行：历史已读采用迁移确认时间；只对明确 LEGACY 或已 DELIVERED IN_APP 的行按 createdAt/id 建立一次性历史 baseline，external-only 保持无 sequence，不制造 Delivery。新写入的顺序由用户 counter 锁到提交保障。

`V28__version_unread_projection_and_complete_pending_anchors.sql` 增加 durable read_version 和历史 occurrence 查找索引；仅对 pending 当前代次、可靠 Timeline 节点关联的 type 9–11 补冻结 anchor。其他缺 anchor 的 canonical 计划需由业务 reconcile/运维分类，不能从 sendAt 或当前主体伪造历史。升级前运行只读 [notification_contract_preflight.sql](validation/notification_contract_preflight.sql)；该文件前半用于 V25，后半用于 V28 验收。

切换必须停止旧应用的通知创建、read、fan-out 与 Delivery 写入，完成 V26–V28 后启动新应用，再开放消费者。不能让旧 `is_read` writer 与新 `read_at` writer 并存。保留 is_read 列仅提供结构兼容，不保证旧 JAR 可安全回滚阅读事实；回滚需停写并制定 shadow/readAt 数据兼容方案，不能直接恢复旧 JAR 即称已恢复。MySQL DDL 非全事务回滚；备份、失败停止与恢复按既有发布流程执行。版本切换与恢复结果记录在对应发布证据中。


`V34__freeze_wechat_delivery_templates.sql` 是 additive migration：为 notification_delivery 增加 nullable `template_snapshot_version SMALLINT UNSIGNED` 与 `template_snapshot JSON`。JSON 冻结渠道、非敏感账号引用/AppID、产品、模板及绑定版本、已渲染 data、已解析跳转和配置摘要；不含 openid、Secret、AccessToken。已有行保留 NULL，不回填当前配置。新外部意图在同一事务写 Notification/Delivery/Job；无效模板保存 FAILED 且不开 Job，IN_APP 不受影响。新 reader 对旧 PENDING 安全 SKIPPED，旧 PROCESSING UNKNOWN；终局不复活。

升级前盘点 `SELECT channel,status,COUNT(*) FROM notification_delivery GROUP BY channel,status;` 并明确历史待发送任务的处置。停止旧 writer/worker后执行 V34 再启动新版；旧 JAR 虽可读取 additive 结构，却会继续创建无快照意图或绕过冻结规则，不支持直接混跑/业务回滚。旧任务结果恢复仍遵循既有 UNKNOWN/DEAD 流程，不提供自动补发。空库 V1–V34 重放、带 PENDING/PROCESSING 旧行的 V33→V34 升级和快照 JSON/事务/DEAD 修复在隔离 MySQL 8.4 验证；具体运行证据见本次交付测试结果。

`V35__add_official_content.sql` 创建官方宣传内容主表，并扩展已有附件引用的 CHECK 约束允许 target_type=7；不更新任何旧表数据。媒体使用已有 event_attachment_link 的 target_type=7 引用已登记资源，目录 official-content 表示上传业务种类。先执行受控迁移并部署提供方，再启用两端入口；旧业务保持兼容。迁移文件存在不表示运行环境已迁移。

## Article V37 视频审核

`V37__add_cos_video_audit.sql` 增量增加 audit owner 的 `media_video_audit`，不修改 V36、媒体内容或既有互动数据。资源 / 策略唯一键防止重复预占；provider JobId、资源快照、状态与序号用于异步恢复。无级联删除附件的外键，审核事实不拥有对象生命周期。不将原始 OCR / ASR、签名 URL 或密钥存入审核记录。

先迁移 V37，再部署包含视频审核 Handler 的后端，最后配置并启用。不要把新 Job 派给不认识 `audit.cos-video.check` 的旧 worker。回滚前关闭新任务生产、停止或隔离该类型的 worker；保留表和审核事实，不删除状态行以强制重发。UNKNOWN 必须先核对平台任务，不能自动 repair 或清除记录。配置与恢复见 [Article](../../../../docs/article.md) 和 [Runbook](../../../../docs/operations/runbook.md#article-cos-video-audit)。

验证在本任务独占一次性 MySQL 8.4 进行 V36→V37 升级、空库 V1→V37 重放及 validate；旧 Post 和 Activity 附件 fixture 保留。数据库测试验证审核与 Job 原子提交 / 回滚、并发去重、租约隔离、身份匹配、策略变化及 UNKNOWN；provider 使用替身，不代表真实收费审核已验收。

## Article V36

`V36__add_article.sql` 新增 article、article_category、article_publisher；扩展既有媒体资源、上传声明和引用约束，增加 comment 的真实回复目标以及 view_log 的自然日唯一键。历史 view_day 为 NULL，原浏览记录不重写或去重；不迁移 Post / OfficialContent，不重复建互动表。

部署前在已批准的 V35 目标执行只读 [article_preflight.sql](validation/article_preflight.sql)。ARTICLE 的持久 TargetType=8，审核场景 AuditScene=9；后者不是 TargetType 编码。旧共享目标 8 或审核场景 9 有数据时必须先查明来源，不自动清理。迁移用 CHECK 前置断言拒绝编码冲突；失败留下的 `article_v36_target_preflight` 表不是业务资源，应按批准的迁移恢复流程处理，不能自动 repair / baseline / 继续发布。MySQL DDL 不提供整体事务回滚。

先备份、核对编码和停止受影响 writer / worker，再迁移 V36，再部署新后端。新增 Entity / Mapper 字段同样被既有媒体链路读取，不能只关闭 Article 页面而让新 JAR 运行在旧媒体结构上。迁移不初始化假管理员或伪造发布主体：官方主体由显式可信部署配置和安全检查初始化，见 [Article](../../../../docs/article.md#授权配置和启用条件)。

旧 JAR 保持旧列结构可读，但不具备 Article、VIDEO、自然日去重和目标 8 的业务语义。回滚前关闭新增入口及通知目标生产，停止相关 writer；新内容和事实保留，不能删除表或把 Article 改写为 Post。生产回滚需单独核对当前旧 consumer / worker 的未知目标降级，结构 additive 不等于业务可无条件混跑。

验证使用专用一次性 MySQL 8.4：空库 V1–V36 replay / validate；另一库先 V1–V35，插入 synthetic Post 和 Activity 附件引用再升级 V36，检查旧内容和链接未变。`ArticleDatabaseTest` 只接受 `article_contract_test` 的 loopback 显式端口 / root 空密码，不读取应用配置；其清理和故障 trigger 只能用于该 disposable schema。测试结束清理本次创建的容器，不对业务库执行 fixture、trigger、drop 或删除。

## 内容安全审核 V71–V72

V71 additive 新增轮次/检查与旧日志关联，保留旧 ID 和全局幂等键；每轮最多一个人工结论。V72 保存失效原因，撤下后重发布不恢复旧未决审核。迁移不回填版本、快照或历史决定，不处置活动风险数据；旧证据不能由当前内容补猜。实现及切换边界见[内容安全审核](../../../../docs/content-safety-review.md)。应用升级不能混跑旧回调 writer；DDL 中断须按既有流程核对实际对象和 Flyway，不自动 repair。隔离空库、V70 历史升级、重复 migrate/validate 与生产目标迁移分别验收。


## Notification lifecycle V75

V75 新增 notification.deleted_at、created_at/id 清理索引、最小来源摘要回执及投递处置审计。不回填/修改历史创建时刻、阅读事实和入箱序号，不自动清理数据。90 天用户可见窗口独立于后台物理维护；未决正文 180 天最小化、去重和恢复边界归 docs/reminder-notification.md §12。部署需先核对实际 schema / writer / 历史状态；只有 V74 baseline 不证明所有历史迁移实际执行。已有迁移不重写，本次只在独占一次性 MySQL 验证空库 V1..V75 与带 synthetic 历史 V74→V75。


## 管理查询增量 V76–V77

V76 为 content_safety_review 增加可空的固定答疑关系 snapshot_qa_relation。新轮次在原事务中捕获 question/answerRoot/replyTo；旧轮次保持 NULL，不按当前关系补造历史证据。该关系用于授权上下文预览，不替换原正文/附件/检查快照。

V77 增加 admin_audit_record 安全投影与 admin_audit_guard 提交序号互斥。recorded_at 明确为 UTC DATETIME(6)，原 owner 的业务时区在接入时显式转换，Points/RBAC/Person 的 UTC 不重解释。审计不设源对象 FK，以保留删除/解绑后的原身份与历史；原 owner 继续负责事实、事务和收据。迁移不搬运旧日志、不清理原历史、不初始化或授予 ROOT。部署新 writer 前须完成这两项迁移；MySQL DDL 的回退不通过删除审计历史实现。

## 账号凭证失效 V78

V78只在user_profile新增非负session_version，默认0，不写账号状态、角色、Authority或Person。封禁由User owner在原限制事务原子递增，JWT中的旧版本在恢复后仍失效；缺claim仅兼容版本0。部署须同时升级全部认证reader，禁止混跑旧二进制；恢复备份须按runbook丢弃不能证明撤销完整性的旧认证会话。本次仅在任务独占MySQL重放迁移，未操作业务库。


## LearningTask 私有成果与责任溯源 V85–V86

V85 新建 learning_task_notice，固定业务 occurrence、责任/可确定投递User/绑定及安全跳过状态；article_safety_unpublish 独立保留显式安全撤下原因。V86 新建 Media owned media_learning_upload、media_learning_file、media_learning_receipt：精确上传分配、不可变私有候选/正式接管、原成功登记结果。未改写历史 Task/Assignment/Submission，不推断 Person、授权或旧 Attachment 的私有归属；无权限目录发布、业务数据回填或云端操作。

候选登记期限与凭证期限分离；正式接管和 Submission 同事务，回收仅允许 UPLOADING/REGISTERED → CLEANING → DELETED。BOUND 历史不参与临时清理。远程核验晚于回收完成时重新排入 CLEANING，防止晚到快照残留；失败保留清理意图，不把超时当删除证明。登记收据和通知责任记录不随90天 inbox 可见窗口删除。

仅在核验支持的管理端、新旧 writer 与旧草稿配置后按目标库既有流程部署匹配的迁移和二进制；本地测试没有操作业务库或真实 COS。私有桶默认未启用，回退不能让旧 writer 绕过 Article 保护或把正式文件重新当候选。验证见 [commands](../../../../governance/commands.md#learningtask-business-v1-validation)。

## Person 名单关联、完整性与文件留存 V87

`V87__align_person_roster_binding_and_retention.sql` 使自助绑定历史的名单行/组织引用可空，保留旧引用；新增批次Provider命名空间、固定密文留存期限，允许原文件清理；事件扩展覆盖组织完整性与重复来源追溯。来源行、正式Person事实、绑定历史、事件和收据不删除。

旧完整花名册记录没有当前契约要求的确认人与审计证据，V87 将其标记 UNCONFIRMED/UNKNOWN，须由有权管理员重新核对整个成员集合并显式确认。未提交批次的文件期限为expiresAt后24小时，已提交批次从原提交事件时间起24小时；过期密文由有界扫描清除。新上传批次写入当前Provider命名空间；历史批次新增列默认沿原默认 CAMPUS_STUDENT_NUMBER，部署使用自定义命名空间时须先核对历史来源与真实标识命名空间，不从组织推导或重新分配唯一标识。

应用V87与匹配JAR前按目标库流程备份并检查DDL锁影响，停止混跑旧人员/名单写入者。无需修改RBAC/Duty目录；不自动在业务库执行迁移，不盲repair或删除历史回退。隔离验证使用全新schema及V86历史样本升级；业务库迁移、部署和真实客户端联调仍需各自证据。

## 成长关联与私有媒体 V89–V90

`V89__add_growth_record.sql` 新增 Post 唯一成长关联、Media 上传上下文与固定登记结果，并为安全审核轮次保留固定发布组织。V90 将登记窗口与登记后 24 小时候选期限分开；重放不延长期限，正式接管取消候选到期。Post owner 负责原子创建与归属不可变；不为现有普通 Post 猜测 Person、组织或类型，不回填旧帖子。应用不物理删除关联或正式媒体归属，普通清理额外排除正式成长历史引用；候选继续由现有上传清理机制管理。

迁移、完整关联识别和所有读取/互动/媒体隔离必须与新二进制一致切换；不得混跑不识别关联的旧 reader、writer、审核或清理程序。新表不会自动发布管理权限、配置 COS 或补成视频检查能力。目标业务库迁移、目录实际发布、私有桶 ACL/STS/CORS、真实 provider 与设备读取需要各自证据。隔离验证入口见 [成长验证命令](../../../../governance/commands.md#成长记录验证)。


## 首页精选 V93–V94

[V93](migration/V93__add_home_banner_configuration.sql) 初始化一个空的平台级配置和版本行。保存锁定版本行后核验 If-Match、新增或重新启用的公开目标以及新增封面。配置、版本和附件有效引用使用同一事务。撤回或删除目标由所属业务处理，首页摘要重新读取其当前可见性。

V93 与 [首页 Contracts](../../../../../contracts/docs/coordination/home-summary.md) 的 RBAC r8 / Duty r9 目录配套。[V94](migration/V94__publish_home_banner_management.sql) 发布仍处于 V93 初始状态的首页权限和角色。部署匹配的 Backend 后，当前有效 ROOT 即可管理首页精选。普通管理员可经现有流程获得显式 `PLATFORM` 的 `HOME_BANNER_MANAGE` 职责。迁移不创建人员任命。

`HomeV1DatabaseTest` 使用一次性专用 MySQL 8.4 实例的空库 `home_v1_fresh` 和 `home_v1_upgrade`，校验服务器 UUID 后分别重放全历史及 V93→V94。通过 `home.test.port` 和 `home.test.serverUuid` 指定该实例。MockMvc、MyBatis 和 MySQL 验证 HTTP 到持久化、候选筛选和竞争写入，并验证正式附件引用保护。身份、源媒体授权和活动时间线输入使用测试替身。
