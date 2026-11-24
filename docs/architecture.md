# 项目架构与实现约定

本文件负责本项目的模块所有权、依赖方向、对象流转和持久化选型。它是通用标准的项目适配，不重复定义 Java、Spring 机制或业务协议。通用规范入口见 [AGENTS](../AGENTS.md)。
规则用于新增和本次修改的链路，不宣称所有历史实现已符合，也不要求顺手重构无关代码。

当前项目尚未正式上线。已实现接口默认接入，不增加仅为预上线、灰度或联调验收设置的功能总开关、测试 profile 或数据库名称门槛。实际认证授权、业务状态、并发与幂等、数据完整性及外部 Provider 必需配置继续校验；新增迁移或兼容层按实际数据和消费者需要决定，不为假设中的生产历史提前建设。

## 1. 项目布局

应用根包为 `cn.jualn.miniapp`，保持单体、按业务模块组织：

| 位置 | 职责 |
|---|---|
| `module/<业务>/` | 业务用例、状态、持久化及对外能力 |
| `common/` | 无独立业务归属的结果、异常、上下文和技术工具 |
| `config/` | 框架配置与 Bean 装配 |
| `web/` | 跨业务 HTTP 上下文与身份入口适配；不放入公共技术工具包 |
| `infrastructure/` | 缓存、队列等技术能力实现 |
| `third/` | 微信、COS、AI 等外部协议与客户端适配 |
| `src/main/resources/mapper/` | 自定义 MyBatis XML |
| `src/main/resources/db/` | 迁移与数据库验证材料 |

业务规则、枚举及专用存储归所属模块；common 只保留共同目标标识 TargetType 等无独立业务归属的语义和技术类型。Java 包名调整不改变既有 wire code、JSON 枚举名称和持久化值。common、infrastructure、third 不依赖业务模块；专用认证 Store 归 admin/auth/store，普通 Redis 客户端仍归 infrastructure/cache。
不为目录完整提前创建空层级。Repository、Facade、Command/Query 分拆等只在现有边界无法清楚表达实际需求时引入。

### 1.1 时间语义与存储边界

业务时区按 [Contracts Date and Time](../../contracts/docs/governance/API_GUIDELINES.md#date-and-time) 使用 `Asia/Shanghai`，不依赖 JVM、数据库主机或用户设备默认时区。普通业务 `DATETIME`、SQL `NOW` / `CURRENT_TIMESTAMP`、业务日界与调度使用北京时间；默认与生产 JDBC 配置显式使用 `+08:00`，Hikari 新连接执行 `SET time_zone = '+08:00'`。覆盖数据源的部署也必须保留这些会话语义，配置文本不代表运行会话已经核验。

已有 UTC 所有权保持：RBAC / Person 的期限、事实和收据，Points 申请、账务及私有材料生命周期使用原 UTC 读写与精度，数据库当前时间从显式 `UTC_TIMESTAMP` 取得。`applicationClock` 和 `learningTaskClock` 保持 UTC `Clock`，普通业务消费 `clock.instant()` 时明确转换到 `Asia/Shanghai`；不能全局切换 Clock 或序列化器来重解释这些值。User 限制期限是北京时间，与 RBAC / Person 的 UTC 时间比较时显式转换。

对外保留各 operation 已确认的时间格式：已有 UTC 输出与无时区北京时间字符串不借此次实现对齐改格式；新设计默认带 `+08:00`，请求的其他显式 offset 按同一时刻归一化。读取 `DATETIME` 不会自动获知其时区，必须按所属字段的时间域解释。

`content_audit_log.created_at` 是普通业务北京时间；V73 前积分图片自动审核（`target_type=12 AND audit_source=1`）是已确认的 UTC 写入例外，V73 只转换这条旧写入路径。其他历史来源未核实前不得批量平移，迁移 / 停写 / 恢复见 [runbook §14.3](operations/runbook.md#143-积分图片微信检查恢复)。现存普通字段若曾在非上海 JVM / 数据库会话写入，必须按真实来源另行核查，不能由新规则宣称历史数据已正确。

## 2. 模块与数据所有权

所有权包含数据写入、合法状态变化及相关派生数据失效的责任。下表是职责分配，不是数据库完整字段快照。

| 模块 | 所属数据或能力 |
|---|---|
| user | user_profile、user_agreement |
| person | 独立档案/姓名、专业班级登记、标识、账号绑定、正式身份、组织成员及范围、名单来源、导师资料/资格、事件和永久收据；V65/V66 分开 Person 与 User，事实和业务主体使用 personId，账号承担绑定/操作/独立职责。共同 authority gate / RBAC owner 锁及提交前检查保护跨模块最终裁决；完整集合与花名册独立核实，见 [人员基础](person-foundation.md) |
| setting | user_setting |
| post | post、post_category、post_category_type、topic、post_topic、post_creation_receipt、post_management_history、post_favorite、post_view_history；Post 类型、字典引用及编辑版本由 Post owner 维护 |
| officialcontent | official_content；官方宣传内容的分类、正文及发布状态，附件引用由 media 持久化 |
| article | article、article_category、Article guard/组织授权审计、安全撤下原因及历史 Publisher/映射证据；统一 Organization 署名由 Person owner 读取，逐职责来源由 UnifiedDutyReader 验证；独立官方富文本资讯，状态与资格归 Article，互动事实和附件引用由各 owner 提供；见 [Article](article.md) |
| learningtask | 任务领域表、通知责任溯源、不可变提交/预览分类、局部幂等及 V59 唯一管理归属；当前管理/成果审核及完整同源受众由 LearningTaskScopePolicy 校验；受众最小选项、纯确认与文字/私有附件成果、事务外候选安全、IN_APP 与既有 Job 提醒；审核使用独立 read/review 权限，正式目录/真实客户端开放边界见 [LearningTask](learning-task.md) |
| points | point_application、追加式 point_ledger、永久局部幂等 point_request、不存余额的 point_subject_lock 及 point_authorization_audit；Service 先取得 person gate / User-RBAC 锁，再执行申请/核分/完整冲正事务。受控参与者 SQL 复用 Person owner 的规范谓词；media 拥有独立私有材料生命周期，通知用既有 Job 同事务受理。正式政策、真实 Provider 或 capability 未就绪时关闭对应写入；当前边界与证据见 [积分实现边界](points.md) |
| activity | activity、activity_enrollment、activity_registration |
| publicevent | public_event、exam_subscription；公共事项及其订阅，Java 类型使用 PublicEvent；既有 TargetType.EXAM、数据库表名和兼容 wire 值保持 |
| eventcontent | event_section、event_action；共享正文与参与入口 |
| comment | comment、comment_management_history；线程节点公开资格和评论私有读取由 Comment owner 维护 |
| interact | like_record、share_record、view_count_cache、view_log、post_view_day、post_share_event；NORMAL 每日浏览事实与 Post owner 计数/最近历史在同事务提交 |
| media | media_attachment、media_upload_record、event_attachment_link；附件登记、引用持久化、媒体绑定与清理；独立 POINTS 私有上传/留存/绑定/访问审计由 PointMaterialService 维护；media_learning_upload/file/receipt 及 LearningMaterialService 拥有学习任务私有候选与正式历史，两者均不创建公开 Attachment |
| timeline | timeline |
| audit | content_audit_log、content_safety_review/check、media_video_audit；内容安全单拥有不可变提交快照/检查/轮次，业务效果归 Post/Comment owner；后者保留 Article 不可变视频资源的 COS 检查事实。见[内容安全审核](content-safety-review.md) |
| search | search_doc |
| notify | Reminder reconcile/plan、notification、notification_delivery；渠道无关通知编排 |
| report | report、report_resolution；结案保存固定覆盖和实际效果，ReportResolutionFacts 同事务接受本人结果通知 |
| subject | 无独立业务写表；SubjectExistenceService 及专用只读投影提供兼容存在性/缓存，不提供公开可见性或操作授权；Article 和 Article Comment 的实时资格仍由各自 owner 提供 |
| admin/auth | 管理身份、扫码登录与令牌会话；专用 Redis/Lua Store 属于本模块，不承载业务职责授权治理 |
| authorization | 六张 RBAC 核心表、独立离线运维事实、受控目录、互斥 Reader/dispatcher、治理事务和正式 HTTP；Query Service拥有当前事实/分页读取，普通写由Governance原子提交，隔离离线执行器不注册应用Bean；期限输入已完成隔离验收、已实现能力默认纳入运行支持清单，见[RBAC基础](rbac-foundation.md)与[离线治理](rbac-offline-governance.md) |
| admin/operation | admin_operation_log；admin_audit_record 的 ROOT 安全投影、提交高水位与追加式到期/依赖失效事实；原 owner 日志与收据不迁移、不清理 |
| admin/query | 管理仪表盘只读编排；通过各 owner 的公开 summary Service 读取四个独立授权分区，不访问其他模块 Mapper，不加载列表明细 |
| auth、wx、content | 认证、微信回调、内容能力编排；wx 负责微信身份/模板/provider 适配，不得绕过数据所有者写表或读取 Activity/PublicEvent Mapper |

模块边界按业务语义、状态所有权、事务与权限保证划分，不把类名后缀或 Java public 可见性直接当作公开协议。

| 协作性质 | 项目边界 |
|---|---|
| 业务写入 | 调用状态 owner 的公开业务操作；不访问对方 Mapper 或传递可变 Entity |
| 事实与资格读取 | 使用 owner 提供的 Reader、Policy 或业务查询结果；同事务能力明确传播、读取时点及锁要求 |
| 审批、列表与统计投影 | 专用 Mapper 可以读取明确的跨表投影，返回本模块的 Row；锁定投影保留既有锁顺序，不取得被读模块的业务写权限 |
| 扩展与回调 | 提供方定义稳定上下文，业务实现返回业务事实；通知扩展不暴露 NotifyPlan/Notification Entity |
| HTTP 表示 | 默认在 HTTP 边界转换；明确共享的附件/时间线表示及纯转换可复用，不借 Converter 查询数据库、判断权限或编排业务 |

公开能力可以是 Service、Reader、Policy 或扩展接口，不为命名一致增加转发层。外部调用只使用已明确公开的操作，不将内部 Controller、持久化模型或整个实现包视作可复用 API。

`ArchitectureBoundaryTest` 对当前源码对应的全部业务模块建立编译类型模型，补充以下静态守卫：

- 从跨模块调用及 HTTP 入口对 Service / Reader / Policy 的调用发现实际公开操作；接口检查全部公共方法，具体类检查被这些边界调用的操作，并保留已收口能力的整类签名检查。检查包含继承方法、嵌套泛型、通配符、数组、类型变量上界及异常类型；构造器注入不作为业务公开操作。既有 `dto.inner` 业务传输值不按 HTTP DTO 判断。
- 全模块约束跨模块实现、内部协作、启动、Controller/Web 及持久化类型的依赖；业务 Service（含实现）不新增对 HTTP 请求/VO 的依赖。身份与微信入口适配、共享纯转换及存量债务仅按[精确例外](../src/test/resources/architecture/boundary-exceptions.tsv)区分 `APPROVED` / `DEBT`，不按整个模块或包放行。新违规和已失效例外都会使检查失败；债务不是已完成整改的证明。
- 按当前生产类型的继承、字段、签名、注解和方法体引用建立图，合并同一顶层类的内部类型后检测跨模块强连通分量，拒绝直接或间接类型环。不同模块通过独立能力、扩展和回调协作可以使按模块名聚合的图双向连接；该检查不声称整个模块聚合图是 DAG，也不以新增接口隐藏实际编排的循环。

编译类型检查不将注释或 SQL/字符串字面量当成 Java 依赖；原有源码解析仍守卫公共技术层反向依赖业务、跨业务 Mapper/Entity 引用及已明确的模块规则。反射、Spring 动态装配、跨表 SQL、权限裁决、事务组织与接口职责的合理性仍需代码审查和相应运行验证；静态守卫不替代这些证据。覆盖清单与精确例外输出在 `target/boundary-guards/inventory.json`。

Service 包按聚合、独立用例或确有不同变化原因的职责分组，不为每个类强制建立接口 / `impl` 层级。同一职责的紧密协作可同包并使用包内可见性；跨聚合通过收口能力协作，目录拆分不改变状态所有权、事务与权限边界。

普通社区 `PostCommunityService` / `CommunityCommentService` 承担本人资源、原键恢复及新增普通社区读取；创建收据保存账号/操作/键及语义摘要，不保存正文快照。`PostPublicAccess` 在数据库当前状态与固定截止上裁决公开资格；`revision` 覆盖可编辑表示，`audit_revision` 绑定内容安全身份，分类维护不使内容不变的安全轮次过期。新增普通评论的 `community_thread` 只标记新受理范围，不反向批准历史隐藏回复。

Post/Comment 按实际用例分离实现：`PostService` / `CommentService` 保留作者创建、删除；`PostQueries` / `CommentQueries` 承担查询，接收业务参数并返回 BO，由 Controller 完成 HTTP 请求和响应转换；`PostGovernanceService` / `CommentGovernanceService` 承担管理处分与内容安全效果。审核与内容治理调用治理能力，搜索调用 `PostQueries`；评论的帖子资格校验使用 `PostPublicAccess`，计数由 Post owner 的 `PostInteractionSubject` 更新，不依赖整个作者 Service。`CommentCounters` 是评论内部的状态转换协作组件，要求已有事务，使父评论与主体计数跟随调用者一起提交或回滚。 审核失效写入由独立的 `ContentSafetyInvalidation` 持有，仅加入调用方既有事务；Post/Comment 删除、治理及活动/公共事项下架调用它，审核编排仍调用业务 owner 应用审核效果，失效写入不再依赖审核编排或业务治理服务。

Authorization 的源码按 web / service / model / mapper / support / bootstrap 分责；关键快照、目录和治理协作保留包内可见性，显式离线执行器及其 Mapper 不进入应用扫描。具体职责与扫描约束见 [RBAC 源码边界](rbac-foundation.md#源码职责与内部边界)。

Person / Authorization 的联合事务由 `TransactionalAuthorization.begin` 统一按 Person gate → ROOT 治理 mutex → 排序账号锁建立；Person 拥有 gate/epoch、事实和绑定，Authorization 拥有授权来源锁与当前权限目录。RR 事实上下文和 RC 业务短写的提交前复核保持各自事务语义。Person 服务按 profile、binding、facts、roster、qualification、access、support、bootstrap 整理，具体职责、内部协作与锁协议见[人员基础](person-foundation.md#绑定与治理)。

Notify 定义 `NotificationChannelAdapter`、`NotificationChannels` 和 `NotificationSendResult`，Delivery 与能力查询使用渠道接口；微信在 `WxNotificationChannelAdapter` 中处理既有 V1 模板快照和 provider 结果转换。业务通知内容记录及字段键归 `notify/payload/data`。Notify 不依赖微信服务、DTO 或枚举；持久化兼容保留的旧类型名仅作为受控解码别名。`ArchitectureBoundaryTest` 检查该依赖方向。

Notify 的个人入口按职责归组：`service/inbox` 包含 `PersonalNotificationInbox`、结构化 `NotificationCenterService` 及其实现、收件箱受理和游标/快照协作；`service/preference` 包含个人偏好用例和已有 owner/旧设置桥接接口及实现；`service/capability` 的 `NotificationCapabilityQueries` 组合渠道配置与当前身份事实。接口及单一实现同职责放置，不再由一个个人入口 Service 同时承担这些能力。Controller 通过 `NotificationPersonalConverter` / `NotificationCenterConverter` 转换请求和响应；公开用例接收或返回业务值，兼容类型别名、字符串 ID 与时间格式留在 HTTP 边界。偏好保留显式 null 重置与 owner 门禁；兼容游标继续绑定原身份/过滤条件，已读、删除、版本与提交后缓存失效保持原事务责任。

微信接入的目标分工见 [WeChat §10](wechat-integration.md#10-整个微信接入的目标结构)：`third/wx` 承担 provider 协议、凭据、纯值格式化与结果分类，`module/wx` 承担入口、应用编排和通知映射；`module/wx/notice` 持有不可变有效模板目录、业务字段白名单、应用跳转与渠道 Adapter 边界，现有用户微信标识的持久化仍归 user；third 层不反向依赖业务模块编排或持久模型。发现实现偏离时在受影响链路修正，不从旧实施批次推导当前状态。
content/audit/subject 的跨表 Mapper 仅承担各自受控读取或锁定投影；任何目标写入仍委托数据所有者 Service，不因聚合查询例外扩大跨模块写权限。
authorization 的 RbacMapper仅投影当前User ID、账号状态、旧角色与限制期限以判断资格，不写user_profile；限制时间使用User owner的北京时间语义，RBAC事实独立UTC。User限制/恢复/旧role修改在显式READ_COMMITTED事务、业务User锁之前调用统一ROOT guard；新User与LEGACY Authority同事务建立。历史Authority显式回填后才可启动强制Reader的新二进制，启动门禁拒绝缺失/损坏来源。当前没有通用账号删除或公共source切换入口；首次迁移owner见下文，其余未来写路径须遵守同一锁顺序。Sa粗权限候选不等于资源授权；Service检查真实namespace/动作及既有业务规则。

ROOT 的首次初始化、计划交接及应急恢复由非应用 Bean 的 `RbacRootOperations` 执行，使用固定运维计划及审计回执。历史 LEGACY 首次来源迁移保留 `RbacInitialMigration`。当前普通业务授权统一由 Duty 提交生成关联任命。详见[运维手册](operations/runbook.md#root-运维与旧任命退出)。
共享子资源由其所属 Service 处理；活动/公共事项主体负责业务资格及组合用例，不能因为一张表被共享使用就形成多个写入所有者。

Activity 与 PublicEvent 分别拥有自己的 Reminder Policy、Rule Catalog 和业务 Notification Factory；notify 只拥有通用 reconcile、计划、Notification、Category × Channel Preference、Recipient/Delivery 编排。Timeline 提供业务时间事实，Async Job 提供执行状态，WeChat Integration 只消费渠道无关快照。正式渠道为 `IN_APP/WECHAT_MINI_PROGRAM/WECHAT_OFFICIAL_ACCOUNT`，两个微信渠道拥有独立 Capability。完整依赖和状态边界见 [Reminder、Notification 与 Delivery 设计](reminder-notification.md) 与 [WeChat Integration](wechat-integration.md)。

### 2.1 媒体生命周期与 COS 集成

管理端附件批量读取由 `MediaAccess.readAdminAttachments` 复用单项可复用附件资格，元数据和绑定/上传用途分别批量读取；Article 媒体附加资格每批最多检查一次，不访问 COS。Activity/PublicEvent 的管理详情、创建和替换响应在主体 Service 返回后展开本次 draft 引用，保留原 ETag；展开故障按项返回 ERROR，不改变已提交主体结果。公开 URL、附件读权与 LearningTask/Points 私有材料仍各自独立。表示定义见 canonical [Admin query schemas](../../contracts/api/schemas/admin-query.yaml)。

`module/media` 拥有本站媒体的持久化生命周期；`third/cos` 负责 COS 协议、配置地址解析、STS 与对象操作，不决定附件是否仍有效或是否应删除。业务模块决定内容保存、媒体替换与保留资格，通过 media 的公开 Service 在本地业务事务中绑定媒体或登记删除意图，不直接操作上传记录或调用 COS 删除。

生命周期入口为 `MediaService`（凭证、预检、业务事务内的绑定/引用与持久化删除意图）、`AttachmentRegistrationService`（独立附件幂等登记）及 `MediaUploadRecordService`（上传状态与清理接管）。`MediaQueries` 提供已授权调用方所需的元数据事实，`MediaAccess` 决定管理复用资格与批量读取状态，`AttachmentReadService` 组合附件事实及主体 owner 的公开可见性。三个能力分别由 MediaServiceImpl、MediaQueriesService、MediaAccessService 实现；元数据读取本身不授予下载或业务访问权限。`MediaUploadCleanupTask` 调用状态 owner 执行有界清理；`CosService` / `CosClient` 承接外部存储调用。文件由客户端持 STS 直传 COS，数据库事务不包含远程上传或删除。

普通业务媒体在主体保存前完成服务端快照和技术核验，主体事务只绑定已验证且未过期的快照。独立 Attachment 按 file-foundation-v1-r2 在登记时成为 CANDIDATE，Activity/PublicEvent/官方宣传/Article 保存全部实际引用时，在同一事务转为 FORMAL；首次草稿保存即接管。AttachmentRegistrationService 持久保存 operator/key/请求指纹和结果，远程复制/核验在事务外，最终登记在短事务内完成；原对象上传记录承担跨键去重。管理凭证入口显式传入已认证 operatorId，目录数字表示上传者而非活动／事项 ID。媒体替换在业务事务内持久化删除意图，由现有清理任务在提交后执行；兼容方法 `deleteObjectsAfterCommit` 当前表达该语义，不是内存回调删除。状态与迁移说明见 [数据库入口](../src/main/resources/db/README.md)，保留规则见 [业务规则](domain.md#媒体与用户资料)，切换与历史核对见 [运维手册](operations/runbook.md#14-附件与媒体生命周期切换)，验证入口见 [commands](../governance/commands.md#media-attachment-lifecycle-focused-validation)。

LearningTask 私有成果由 Media 的 LearningMaterialService 拥有专用 allocation/file/receipt 生命周期，复用精确私有 COS transport、格式核验与现有 MediaUploadCleanupTask；LearningTaskFiles 只协调责任/成果授权，正式接管加入 Submission 事务。专用 LEARNING_TASK_PRIVATE_BUCKET 默认空，不能从公开桶或 Points 配置推断已就绪；VIDEO 必须完整编码核验，见 [LearningTask](learning-task.md#私有成果与安全接管)。

这套基础不等于全桶 inventory 或通用存储治理框架；COS ACL、CORS、bucket lifecycle 及实际部署配置属于环境事实，不能从媒体表或源码推断。新增媒体调用方先遵循上述 owner 与事务边界，不自行建设第二套绑定或清理机制。

### 2.2 COS 环境与访问类别隔离

存储目标采用 **生产/测试 × 公开媒体/私有材料** 四个桶，不按每个业务模块新建桶。当前公开媒体目标采用公有读私有写和自定义源站域名直连 COS，暂不启用 CDN；私有材料仍保持私有读写。域名、ACL 与是否启用 CDN 是独立配置，不能以绑定域名代替读取授权。环境隔离以物理桶、CAM 应用身份和域名边界实现，不以文件夹名或 Spring profile 标签代替。云端建桶、授权与历史数据切换按[运维手册](operations/runbook.md#141-cos-环境隔离与混用桶迁移)执行；配置和目标设计不证明已经完成隔离。

| 环境与用途 | 建议桶名（APPID 为真实账号后缀） | 访问方式 |
|---|---|---|
| 生产公开媒体 | `jualn-campus-prod-public-APPID` | 生产媒体源站域名；公有读私有写，暂不启用 CDN |
| 生产私有材料 | `jualn-campus-prod-private-APPID` | 业务授权后短期 COS HTTPS 签名，不接公开 CDN |
| 测试公开媒体 | `jualn-campus-test-public-APPID` | 测试专用媒体源站域名；公有读私有写，与生产域名隔离 |
| 测试私有材料 | `jualn-campus-test-private-APPID` | 测试业务授权和短期签名；测试身份无生产桶权限 |

上表为命名模板；owner 已提供四桶的实际名称，配置片段与运维手册记录实际资源。创建结果、目标 ACL 和应用切换分别验收，不能由模板推断已生效。当前只有 dev/prod 两份运行配置；本地开发使用非生产身份。多人开发需要进一步隔离时，再划定开发者专用前缀与其 STS 范围，不声称当前上传协议已有开发者 namespace。若后续存在独立长期 staging 环境，再增建其一对桶。

按**访问语义**归属文件：允许永久持链接分发的头像、文章等媒体及公开附件进入对应公开媒体桶；帖子与评论图片以及积分证明进入私有桶，各自复用其业务读取授权。不能按 IMAGE/PDF/WORD 扩展名判断公开性，也不能将未发布或待审核文件自动认定为可公开。其他业务的私有附件须由 owning policy 明确授权、留存和 Provider 后接入；本次不改写 Article、LearningTask 等协议或默认暴露其候选材料。

配置保持最小改动：`COS_BUCKET` 继续供既有 `cos.bucket` 使用，含义限定为当前环境的公开媒体桶；`COS_PRIVATE_BUCKET` 显式绑定 `app.points.private-bucket`，不回落到公开桶。两桶当前共用 `COS_REGION` 和该环境的一组 `COS_SECRET_ID` / `COS_SECRET_KEY`，因此部署时使用同一地域、仅限这两个桶的应用身份。生产与测试必须使用不同应用身份；云端巡检/建桶身份与应用运行身份分开。COS 配置直接放在后端 `.env.dev` / `.env.prod` 中，与其他服务配置一起维护，旧桶配置注释保留，不另设 COS 片段。启动器负责加载对应文件中的环境变量，Spring 不因这些文件存在而自动加载。

对象键统一使用 `<业务>/upload/...` 作为客户端原始上传位置、`<业务>/retained/...` 作为服务端核验快照位置。`user`、`activity`、`exam`、`official-content`、`article` 进入当前环境的公开媒体桶；`post`、`comment`、`growth-record` 进入共享私有桶；Points 与 LearningTask 沿用各自私有 Provider 的桶配置和启用边界，均不能降级到公开桶。上传、核验快照和清理必须按同一 key 路由。独立附件登记同样按上传用途生成对应业务的 `retained/` 快照，不使用跨业务资源前缀。`cos.private-bucket` 与既有 Points 的私有桶配置均绑定 COS_PRIVATE_BUCKET，配置归 storage，媒体不依赖 Points service。

`MediaContentService` 在短读取事务中复核文件生命周期和实际引用，通过 `MediaContentPolicy` SPI 由 Post / Comment owner 决定公开、作者预览和管理读取；不持有业务实体到传输层，不在 COS 客户端查询帖子或权限表。`MediaDeliveryUrls` 返回稳定的不透明文件 URL，配置 `app.media.public-base-url` / MEDIA_PUBLIC_BASE_URL 必须是实际 Backend HTTPS 入口。公开 URL 无凭证只读当前公开图片，作者私有预览必须携带当前普通会话；管理 URL 使用独立 admin 会话及真实主体读取职责。

`MediaContentController` 同步有界转发最多 10MiB 的已核验图片，支持 GET / HEAD / 单段 Range，no-store，不发 COS 重定向、不返回304；业务事务结束后才打开存储流，断连中止并关闭上游。当前实现不引入 Nginx 内部传输协议，后续若委托网关应保持每请求鉴权和缓存语义。后台安全任务仅在实际 provider 调用前由 MediaSafetySource 解析持久文件引用并签发15分钟取图URL，签名不进入业务表示、Job payload 或审核快照。

旧公有直链仍有独立副本/缓存边界；新 Provider 完成不代表历史对象已迁移。核对与切换见 runbook §14.4，不能只改共享公开桶 ACL、覆盖历史身份或声明旧链接已经失效。

公开媒体桶目标为公有读私有写，仅存放允许持链接公开访问的媒体；匿名读取不授予上传、覆盖或删除权限。私有材料桶保持私有读写，拒绝匿名访问和公开 CDN 回源，仅允许真实业务鉴权后的最短必要签名。迁移验证阶段已复制对象仍显式 private，启用公开直连前须逐项核对其公开归属并由运维身份调整对象 ACL；仅改桶 ACL 不证明既有 private 对象已可公开读。应用身份不因此获得 ACL 管理权限。STS 仍按本次服务器分配对象授权，不能因换桶扩大到全桶；Points 保持仅 PutObject、private ACL、禁止覆盖与额外 ACL grant 的限制。CORS 只解决浏览器跨域，不是下载授权；正式客户端域名和小程序上传/下载域名须分别配置。

访问地址与前端职责保持现有协议：普通头像和附件读取使用后端返回的完整 URL，不在 Page/组件做旧域名替换或从 URL 推导业务权限。公开上传由共享 transport 按服务端凭证的 bucket/region/objectKeys/customDomain 构建登记地址，各业务仍按自己的契约完成登记/绑定；backend public-url-prefix 与 credential customDomain 必须指向同一环境、同一公开对象路径。私有材料只保存 fileId，通过所属业务 access 接口重新授权取得短期 URL；签名不得写入持久状态、正文、业务 URL 字段或改写为公开链接。API Bearer 不能转发给 COS。管理端登录不等于取得 COS 读取权。

旧地址迁移由核准清单、文件核验和精确数据库 URL 映射负责，不引入前端全局字符串转换。客户端静态分享图不在业务数据库中，需单独盘点并随环境配置切换；Mini 通过 config/index.ts 的 defaultShareImageUrl 统一配置。该配置在域名切换前仍指向现用地址，不代表测试隔离已经完成。具体字段/接口仍以所属 Contracts 为准，本节不新增协议或一套前端存储路由。

对象键在业务命名空间内区分原始上传与正式快照，例如 `points/upload/...` 与 `points/retained/...`；目录前缀不是环境隔离或访问授权。私有正式材料、软删除保留资源以及仍被业务引用的对象不能套用全桶按龄删除规则。公开直传桶必须保持版本控制 Off：腾讯 COS 在开启版本控制时忽略 forbid-overwrite，凭证签发会实时检查并在证据缺失/不符时返回 503。其他桶的版本恢复策略独立决定；历史版本保留期和永久清理须另行明确，不能采用控制台默认历史版本删除期限。

不立即建设通用存储路由表或双桶回退框架。当前元数据多为 objectKey/URL，存量切换应优先按核准清单、受控写入维护窗口、保持 key 的复制及精确 URL/引用映射完成。若实际发布要求新旧桶长期并行，才单独定义可持久追溯的 bucket/region/key 定位模型及其数据库、Contract 影响，不能靠配置默默猜旧对象属于哪个桶。

## 3. 层与对象

普通调用路径：Controller → Converter → Service → 本模块 Mapper / 其他模块 Service → Converter → 响应。

| 位置或对象 | 项目约定 |
|---|---|
| Controller | HTTP 解析、结构校验、认证主体提取、Service 调用及契约响应适配；不访问 Mapper、Redis 或第三方 Client |
| Request / Query | HTTP 入参；放在模块 dto 下，管理入参使用独立 dto/admin |
| BO | Service 的业务命令、条件和结果；提供方定义跨模块 BO |
| Entity | 所属模块持久化对象，不向 HTTP 或其他模块传递 |
| VO | 模块 vo 下的 HTTP 响应模型，管理响应独立；公开跨业务操作不依赖 VO。历史 HTTP 专用查询适配在所属模块内收口，不作为其他模块的业务接口 |
| Converter | Request/Query 与 BO、BO 与 VO 的显式转换；不查询数据库、不决定业务流程 |
| Payload | 异步边界中的 ID、类型和必要快照；不携带 Entity、请求对象或事务资源 |
| `infrastructure.async` | Outbox、Durable Job、Stream transport、claim/reclaim、retry/dead、metrics 与 cleanup；业务模块只提供 typed payload/handler 并在本地事务创建 intent |

Service 实现身份相关权限、归属、状态和唯一性等业务判断。Bean Validation 通过不代表业务可执行。

普通互动的公开资格分别由 PostPublicAccess、ActivityPublicAccess、PublicEventPublicAccess 读取本模块当前数据库事实；它们只依赖各自 Mapper，不反向依赖互动编排。CommentTargetAccess 仅分派已支持主体及核对评论/根线程，Article 继续使用既有 ArticleService 的资格。写用例在其事务中锁定主体后检查，读取/历史点赞列表及计数也先检查当前可见性；SubjectExistenceService 的存在性缓存不是访问授权。普通认证入口在 Sa 校验后调用 UserAccountService.assertLoginAllowed，以当前数据库事实检查有效封禁；getUserAuthInfo 不读取正向账号资格缓存。

旧 like-count 路由保留匿名兼容，但 Article/Article Comment 的条件登录在 InteractionAccess 内同样复核当前账号资格；不能因路由白名单跳过封禁。ContextInterceptor 只在管理路由采用 admin 登录主体，不将 admin Token 填为普通路由的用户身份；匿名公开数据与需要普通登录的计数继续分开。
固定结构的契约请求和响应使用具名 DTO / VO；只有契约本身允许动态键或动态值的局部内容才使用 Map / JsonNode，不把整个详情、表单定义或分页响应退化为字符串键拼装。
字段很少且仅用一次时可以在 Controller 显式构造具名对象，避免仅为转发增加 Converter。嵌套详情、重复枚举映射和跨操作复用的转换放在所属模块 Converter；不能借此例外在 Controller 内解析持久化 JSON、计算参与资格或补做资源权限判断。
Service 先准备业务结果，包括可操作状态、所需计数和判断时刻；Converter 只转换表示，不查询其他 Service，也不以缺失字段推导权限、版本或业务成功。纯响应映射不需要额外 Service，确有业务编排时也不把它藏进 Converter。
MapStruct 映射遗漏应逐项确认并映射或明确 ignore；不能忽略未确认的新字段。
保留 BO 命名体系，不因 Java 标准允许 record 或强类型就批量替换现有模型。

### 3.1 既有模型与新契约适配

复用旧 Service 或模型前，核对其能否表达本次契约的集合、稳定局部键、枚举、时间精度与时区、缺省语义、版本和引用关系。字段同名或能够拼出 JSON 不证明语义一致；创建、替换、读取及受影响的列表应保持这些事实，不能在返回时重新编号、截断集合或用默认值掩盖未实现的数据来源。
需要兼容旧数据时，由所属模块明确转换规则及适用条件；不存在明确等价关系时，按契约和数据演进流程处理，不静默把异常变为空集合、把未知状态变为正常状态。跨组件语义变化仍由 contracts 定义，本文不指定业务枚举或迁移结果。
旧入口与新入口可以共存，但共享不变量由状态所有者 Service 统一保护；不同响应形状在 HTTP 边界分别适配，不让新入口继续传递旧 VO / Entity。只调整本次受影响调用链，不借适配重构整个历史模块。

## 4. Service 与管理用例

- Service 围绕完整业务能力或共同不变量组织，不要求一模块一个 Service 或一表一个 Service。
- 按实际协作公开业务能力；多实现扩展或替换边界使用接口，单实现 Reader/Policy 可直接作为公开能力，私有辅助逻辑不机械增加接口。
- 同模块有多个 Service 时，写入仍由明确的状态所有者统一管理；查询 Service 可以读本模块数据，不拥有写状态规则。
- 不同权限、状态迁移或副作用的用户/管理用例采用不同入口和语义明确的方法，不使用 adminMode/isAdmin 切换两套流程。
- 管理活动归 activity，管理公共事项归 publicevent；不将所有管理业务集中到 admin 模块。
- Callback、Listener、Handler 负责入口适配，调用状态所有者 Service，不自行堆 Mapper 和业务流程。
- 拆分依据是业务职责、权限、事务或依赖分化，不按行数机械拆分；不添加只有转发价值的层。
- 默认沿用 Controller / Converter / Service / Mapper；不为每个操作建立一套 Facade、Manager、CommandHandler 或 Repository。新增类应承担可说明的转换、业务规则、状态所有权或查询职责，不能只是搬走 Controller 的方法后保留混杂责任。
- 循环依赖先检查数据所有权与编排方向，必要时提取查询能力或编排；不将跨模块 Mapper、静态 Bean 获取或 @Lazy 作为长期补丁。

InteractionSubjects 按主体类型分派到 PostInteractionSubject、ActivityInteractionSubject、PublicEventInteractionSubject。主体持有通知所需的最小摘要与自身派生计数字段写入；Interact 拥有点赞/浏览事实、缓存同步和批处理，不借主体 Mapper 绕过所有权。Article 保留既有统计重建协议，Comment 的统计由 CommentStatisticsService 提供。

PersonBusinessScopes 提供事务内的 gate、数据库时间、绑定和生命周期事实；Points、LearningTask 不依赖 Person Mapper。调用方仍拥有业务事务与既有锁顺序，不在重构时改为独立事务或异步授权。审核日志的旧自动检查汇总、私有材料预占与结果归 AuditLogService；材料和审核预占仍参与同一事务，远程检查仍在事务外。

UserService 保留资料查询/修改与管理资料查询；UserAccountService 拥有当前账号资格、限制及历史角色写入口，UserWechatIdentityService 拥有微信身份查询、首次注册和服务号绑定，UserAgreementService 拥有协议同意记录。调用方直接依赖所需能力；协议查询返回 UserAgreementBO，由 HTTP Converter 转为原有响应。UserCurrentLoginAccess 仍用于调用方事务内的锁定资格复核，不替代普通登录入口的限制到期归一化。

ActivityQueries / PublicEventQueries 负责公开详情和列表，ActivityAdminQueries / PublicEventAdminQueries 负责管理查询，ActivityDraftService / PublicEventDraftService 拥有管理草稿及替换保存，ActivityLifecycleService / PublicEventLifecycleService 拥有发布、下架和生命周期迁移。已发布编辑使用同模块生命周期规则和提醒逻辑，仍在草稿用例持有主体行锁的原事务中执行；详情组装的模块内协作不作为跨模块 Entity 接口。ActivityReviewService 保留停用审核操作的拒绝语义，历史审核回调继续忽略，不恢复审核发布链。ActivityService / PublicEventService 仅保留已停用的普通用户写入口；公开访问与评论派生计数分别使用既有 PublicAccess 与 InteractionSubject，不依赖这些兼容接口。

InteractionLikes、InteractionShares、InteractionViews 分别拥有点赞、分享和浏览写用例，InteractionLikeQueries 提供当前用户点赞状态及可见历史，InteractionCounts 提供计数查询；调用方只依赖实际能力，不保留 InteractService 转发总入口。InteractionAccess 复用主体 owner 的当前可见性/写入锁定检查，并保留 Article 及其评论的条件登录与当前账号资格复核。LikeNotifications 冻结实际新增点赞的通知快照和既有 sourceKey，仍在点赞事务中创建通知意图；LikeCache 仅负责提交后的点赞状态与计数缓存同步。普通浏览仍写 Redis 增量，Article 仍写按用户/上海业务日期去重的浏览记录并重建统计；既有 InteractCountSyncTask 继续负责普通计数回写，本次不改变回写或恢复协议。

PublicEvent 的订阅接口只返回业务状态或接收者 ID，不输出持久化 Entity。通知 fan-out 通过订阅 owner 的有上界查询同时应用 after / upper / limit；不能先取无上界页再在内存截掉超界账号。浏览计数的 Mapper 投影归 PublicEvent，不引用 Interact 的持久化 DTO。媒体与 Timeline 的读写仍通过所属能力；共享 HTTP 表示/纯 Converter 复用不等于跨模块状态所有权。上述源码边界不替代真实数据库事务、通知恢复或客户端联调验收。

## 5. MyBatis 选型与字段读取

按表达清晰度选择，新增 SQL 遵循下表：

| 场景 | 写法 |
|---|---|
| 清楚的单表 CRUD、少量条件 | BaseMapper / LambdaWrapper |
| 极短、固定、单表原子 SQL 或标量查询 | Mapper 注解 |
| 动态筛选、联查、聚合、列表投影、复杂分页、批量、数据库特有函数 | Mapper 方法 + 同名 XML |
| 条件状态迁移、CAS、FOR UPDATE 并发协议 | XML，便于审查完整条件与影响行数 |

Service 可构建简单 LambdaWrapper，但不拼 SQL 字符串；Mapper 不承担业务编排。
注解不承载 script/foreach 或长动态 SQL。重复或复杂 Wrapper 提升为语义清楚的 Mapper 方法，不为一次简单查询机械增加方法。
绑定参数与排序允许值必须受控，禁止拼接客户端字段或 SQL 片段。

| 查询目的 | 返回和字段 |
|---|---|
| Q0：存在/数量 | boolean、count 或必要 ID |
| Q1：权限/状态/摘要 | 标量或只含判断字段的 BO/投影 |
| Q2：完整详情 | 明确需要时读取完整 Entity 或详情 BO |
| Q3：列表/搜索/统计 | 明确列字段的列表 BO/投影 |

Q0/Q1/Q3 不使用 SELECT *。XML 优先显式列字段；完整 selectById 只用于确需整条记录的场景。
部分字段 Entity 仅供局部只读，不传入 updateById。组合数据优先批量补充，避免逐条数据库或跨 Service 查询。
分页形式由接口契约决定；内部排序必须稳定，同值时增加 ID 等唯一排序依据。不能用旧“默认游标”规则改变已确认协议。
状态更新核对受影响行数；唯一性与并发正确性遵循 Backend 标准，不能以先查后写替代数据库保证。
物理或软删除依所属数据设计决定，不对所有表统一物理删除。

## 6. 现有基础设施的使用边界

- 认证继续使用 Sa-Token。普通请求从 UserContext 等服务端上下文取得身份；管理请求使用独立 AdminStpUtil。需要的 operatorId/reason 显式传给用例。
- Sa-Token 会话使用独立的 Jackson 转换器；`SaTokenConfig` 仅将历史类型名 `cn.jualn.miniapp.common.enums.UserRole` 兼容读取为所属模块的 `UserRole`，后续写入使用当前类型名。其他未知类型仍报错，不将反序列化失败当作会话不存在。
- 对外错误由 GlobalExceptionHandler 统一适配为 RFC 9457 Problem Details，并使用真实 HTTP 4xx/5xx 状态；成功响应仍由各接口契约决定，不强制统一包装。可预期业务拒绝使用 BusinessException，外部集成失败按 ExternalServiceException 分类；ResultCode 仅是内部分类，必须在 Web 边界显式映射为稳定 problem type，不能直接成为公共协议。
- Converter 和 HTTP 层按对应协议输出；不将现有 Result<T> 包装强加给所有新契约。
- 普通派生缓存通过 infrastructure/cache 下的 RedisService 使用，键定义集中在 RedisKeyConstant；数据模块负责相关缓存的写入与失效。认证 session、ownership 等 correctness 状态使用所属机制的严格 Store，Redis 错误不得按普通 cache miss 降级；扫码登录设计见 [Admin QR Login](admin-qr-login.md)。
- 普通数据库派生缓存采用提交后失效、读未命中回填。失败收敛与可接受陈旧范围遵循 Backend §2；协调锁和幂等状态不属于可随意降级的普通缓存。
- 提交后回调只解决执行顺序，关键消息/外部效果的持久性仍按 Backend §5 设计；不把“提交后入队”写成可靠送达保证。
- ReminderPlan、Notification、NotificationDelivery 是业务/应用持久事实，不能用 AsyncJob 或 Redis 消息代替；计划/Job 和 Notification/Delivery/Job 能在同一 MySQL 事务创建时不增加 Outbox。IN_APP Delivery 本地完成，微信远程调用在事务外执行并分别回写 Mini Program / Official Account Delivery 状态。
- Spring 事务、异步、配置和资源生命周期直接遵循 Spring 标准，不在这里维护第二份注解规则。
- HTTP trace、MDC 字段、日志/异常责任、metrics 与 health 语义遵循 [Observability Baseline](observability.md)；失败结果、重试、幂等、外部副作用与恢复决策遵循 [Reliability Baseline](reliability.md)；`common/observability` 只承载无业务归属的进程内上下文机制。

## 7. 维护边界

模块职责或依赖方向变化时更新本文；业务决定更新 [业务规则](domain.md)；提醒/通知/投递语义更新 [Reminder、Notification 与 Delivery 设计](reminder-notification.md)；微信适配边界更新 [WeChat Integration](wechat-integration.md)；跨组件协议在 [contracts](../../contracts/README.md) 中维护；数据库演进见 [数据库说明](../src/main/resources/db/README.md)。
规范变化不自动证明实现合规。发现旧实现与规范不一致时，判断是否影响本次需求；相关链路必须明确处理，无关技术债不扩散本次修改范围。

### 账号会话安全边界

UserAccountService拥有账号session_version的读取与封禁时原子推进；Authorization只保护目标和真实ROOT，不写User字段。auth签发普通JWT、admin/auth签发独立管理JWT时冻结该版本，MVC及AdminTokenService按当前主库值拒绝旧版本；账号限制、凭证失效和业务grant是不同事实。缺少claim的升级前凭证统一拒绝并要求重新登录，避免历史kickout失败的会话在旧限制到期后复活；不把资料revision或Authority.version复用成凭证版本。普通/admin当前Token注销由各认证owner调用Sa/QR存储，不依赖业务grant或当前NORMAL资格，存储异常不返回成功。Header是唯一凭证载体，客户端禁止自动重登录/重放注销。V78仅新增User列，不新增会话表或权限系统。

PersonBindingTrust校验当前绑定来源，ADMIN_MANUAL 与 ROSTER_EXACT_MATCH 均可用于普通 Person 本人业务；调用方继续通过 PersonBusinessScopes 公开投影逐次核对档案、账号和业务资格。Mentor 正式回答及当前认证是 A3 方案 A 的明确例外：只接受具有原子事件、永久收据、有权操作者和来源凭据的当前 ADMIN_MANUAL 绑定。已有自助绑定由有权管理员核实后沿原子重关联建立新 bindingId，可保持同一 User–Person 组合；不原地改历史、不延长资格、不继承旧候选或历史作者权限。

### 导师问答的内容版本与原始归属

Post owner 的 MentorQuestions 维护 questionVersion、永久内容保护和公开正式回答摘要；Comment owner 的 MentorAnswers 固定原始资格归属，并在首次公开回调中复核。Person 的 MentorQualification 接口提供可信来源与提交前有效期检查，Person gate → ROOT 治理互斥 → 有序账号 → Post → 根/评论的锁顺序与已有资格 writer 共用；共用评论受理采用 READ_COMMITTED，避免等待来源锁前的旧快照。读取历史归属保留固定 profile，当前认证用独立当前读取事务查询原账号与 Person，不能从当前绑定重写归属。 新旧 mentor_evidence / mentor_attribution 读取共用证据规则：完整旧快照可独立使用，旧最小来源可佐证新快照；双字段冲突、原作者不符或必要快照缺失返回来源不可确认。管理回答筛选使用原 Comment.user_id，COUNT、公开统计与分页不从当前绑定推导；未知历史不计成假空页。导师管理列表在 SQL 中按姓名/职务/单位、资格及绑定交集筛选后计数分页，并独立投影档案、绑定及账号状态。对外使用固定 BO/VO，数据库扩展见 V82；业务约束归 [领域规则](domain.md#post-内容组织与导师答疑)，具体协议归 Contracts。

ROOT全局管理（含固定组织成长内容）复用Decision.managementOwner和既有ownerCapabilities，以真实当前ROOT Assignment、账号及就绪精确能力作为来源；不创建给ROOT自己的Duty/Scope，也不将普通GLOBAL视为业务范围。Article、LearningTask、Points各owner解析自己的真实资源并应用该来源；范围证明不替代具体动作权限、业务事实、状态及提交前重验。

## 成长记录的 Post 关联

成长内容由 Post owner 的 `GrowthRecordService` / `GrowthRecordAccess` 管理，`post_growth_record.post_id` 是唯一且永久的归属关联；创建 Post、关联、媒体接管和既有创建收据在同一本地事务提交。`PersonGrowthQualification` 通过 Person owner 读取可信绑定、正式身份和精确党支部成员事实，写入沿既有 Person gate → 账号 → Post 锁顺序，并在提交前重验有效期。普通 Post、QA 与成长的共享编辑入口根据服务端关联分派，不接受客户端声明转换。

Media owner 的 `GrowthMediaService` 保存专属上传上下文和按 allocation/index 固定的登记结果，复用私有 COS transport、真实图片解码、`media_upload_record` 和既有清理机制。技术快照在远程核验前即关联上下文；已正式绑定的历史媒体由永久关联阻止清理，成员字节读取仍只允许当前引用，获准管理证据可读取保留引用。`GrowthMediaAuthorization` 由 Post 实现，Media 不查询 Person 或决定组织资格。当前未接通成长视频的完整技术与内容检查，VIDEO 请求明确返回 `growth-media-unavailable`，不登记成图片、普通 Post 附件或人工通过。

管理聚合的 `GrowthManagementTargets` 仅反查可信根关联，处置继续由 Post / Comment owner 执行。三个既有职责的 ORGANIZATION 目标按当前同一来源及精确组织检查，沿 `BusinessAuthorizationLease` 复核撤权与到期；PLATFORM 和普通 GLOBAL 权限不扩大到成长。审核轮次保留发布组织，通知通过 owner SPI 在读取和投递时裁决当前资格。完整业务与协议见 [Contracts 成长协调](../../contracts/docs/coordination/growth-record.md)。

管理内容及审核响应的 `managementTarget` 由 content owner 的有界批量查询解析真实 Post/Activity/评论父主体及永久成长组织关联，转换为各 HTTP VO；审计模块不消费其他模块 Mapper 类型。成长列表、摘要和动作使用相同组织上限，普通 PLATFORM 职责不获得成长范围。

报名独立作废由 Activity owner 在 READ_COMMITTED 下先取得当前授权 lease，再锁同一 Activity 行、核对报名归属和强版本。状态变 CANCELLED、contract_version、首次处置事实同事务提交；有效名额/统计从 status=1 读取，不另建可重复释放的计数。当前 CANCELLED 版本幂等返回原事实，旧版本拒绝，所有恢复 SQL 排除管理员作废；本人历史只显示结果和原因，不暴露操作者。
