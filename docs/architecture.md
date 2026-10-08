# 项目架构与实现约定

本文件负责本项目的模块所有权、依赖方向、对象流转和持久化选型。它是通用标准的项目适配，不重复定义 Java、Spring 机制或业务协议。通用规范入口见 [AGENTS](../AGENTS.md)。
规则用于新增和本次修改的链路，不宣称所有历史实现已符合，也不要求顺手重构无关代码。

## 1. 项目布局

应用根包为 `cn.jualn.miniapp`，保持单体、按业务模块组织：

| 位置 | 职责 |
|---|---|
| `module/<业务>/` | 业务用例、状态、持久化及对外能力 |
| `common/` | 无独立业务归属的结果、异常、上下文和技术工具 |
| `config/` | 框架配置与 Bean 装配 |
| `infrastructure/` | 缓存、队列等技术能力实现 |
| `third/` | 微信、COS、AI 等外部协议与客户端适配 |
| `src/main/resources/mapper/` | 自定义 MyBatis XML |
| `src/main/resources/db/` | 迁移与数据库验证材料 |

新业务规则留在所属模块，不继续向 common 堆业务枚举和模型。保留已有共享类型的兼容性，按实际依赖逐步调整。
不为目录完整提前创建空层级。Repository、Facade、Command/Query 分拆等只在现有边界无法清楚表达实际需求时引入。

## 2. 模块与数据所有权

所有权包含数据写入、合法状态变化及相关派生数据失效的责任。下表是职责分配，不是数据库完整字段快照。

| 模块 | 所属数据或能力 |
|---|---|
| user | user_profile、user_agreement |
| setting | user_setting |
| post | post |
| activity | activity、activity_enrollment、activity_registration |
| exam | public_event、exam_subscription；当前承载公共事项 |
| eventcontent | event_section、event_action；共享正文与参与入口 |
| comment | comment |
| interact | like_record、share_record、view_count_cache、view_log |
| media | media_attachment、media_upload_record、event_attachment_link；附件登记、引用持久化、媒体绑定与清理 |
| timeline | timeline |
| audit | content_audit_log |
| search | search_doc |
| notify | Reminder reconcile/plan、notification、notification_delivery；渠道无关通知编排 |
| report | report |
| admin/auth | 独立管理身份与权限机制 |
| admin/operation | admin_operation_log |
| auth、wx、content | 认证、微信回调、内容能力编排；wx 负责微信身份/模板/provider 适配，不得绕过数据所有者写表或读取 Activity/PublicEvent Mapper |

跨模块只依赖对方公开 Service 接口和其提供的 BO/标量，不引用对方 Mapper、Entity、Controller 模型或 Converter。
微信接入的目标分工见 [WeChat §10](wechat-integration.md#10-整个微信接入的目标结构)：`third/wx` 承担 provider 协议、凭据与纯映射，`module/wx` 承担入口和应用编排，现有用户微信标识的持久化仍归 user；third 层不反向依赖业务模块编排或持久模型。发现实现偏离时在受影响链路修正，不从旧实施批次推导当前状态。
管理端 content/audit 的跨表聚合 Mapper 仅承担受控只读投影；任何目标写入仍委托数据所有者 Service，不因聚合查询例外扩大跨模块写权限。
共享子资源由其所属 Service 处理；活动/公共事项主体负责业务资格及组合用例，不能因为一张表被共享使用就形成多个写入所有者。

Activity 与 PublicEvent 分别拥有自己的 Reminder Policy、Rule Catalog 和业务 Notification Factory；notify 只拥有通用 reconcile、计划、Notification、Category × Channel Preference、Recipient/Delivery 编排。Timeline 提供业务时间事实，Async Job 提供执行状态，WeChat Integration 只消费渠道无关快照。正式渠道为 `IN_APP/WECHAT_MINI_PROGRAM/WECHAT_OFFICIAL_ACCOUNT`，两个微信渠道拥有独立 Capability。完整依赖和状态边界见 [Reminder、Notification 与 Delivery 设计](reminder-notification.md) 与 [WeChat Integration](wechat-integration.md)。

### 2.1 媒体生命周期与 COS 集成

`module/media` 拥有本站媒体的持久化生命周期；`third/cos` 负责 COS 协议、配置地址解析、STS 与对象操作，不决定附件是否仍有效或是否应删除。业务模块决定内容保存、媒体替换与保留资格，通过 media 的公开 Service 在本地业务事务中绑定媒体或登记删除意图，不直接操作上传记录或调用 COS 删除。

当前入口分别是 `MediaService`（凭证、登记、引用及业务媒体操作）、`MediaUploadRecordService`（上传状态与清理接管）和 `AttachmentReadService`（组合主体 owner 的可见性）。`MediaUploadCleanupTask` 调用状态 owner 执行有界清理；`CosService` / `CosClient` 承接外部存储调用。文件由客户端持 STS 直传 COS，数据库事务不包含远程上传或删除。

普通业务媒体在主体保存事务中绑定；管理端可复用附件在独立登记事务中接管，Activity/PublicEvent 保存只维护引用。管理凭证入口显式传入已认证 operatorId，目录数字表示上传者而非活动／事项 ID。媒体替换在业务事务内持久化删除意图，由现有清理任务在提交后执行；兼容方法 `deleteObjectsAfterCommit` 当前表达该语义，不是内存回调删除。状态与迁移说明见 [数据库入口](../src/main/resources/db/README.md)，保留规则见 [业务规则](domain.md#媒体与用户资料)，切换与历史核对见 [运维手册](operations/runbook.md#14-附件与媒体生命周期切换)，验证入口见 [commands](../governance/commands.md#media-attachment-lifecycle-focused-validation)。

这套基础不等于全桶 inventory 或通用存储治理框架；COS ACL、CORS、bucket lifecycle 及实际部署配置属于环境事实，不能从媒体表或源码推断。新增媒体调用方先遵循上述 owner 与事务边界，不自行建设第二套绑定或清理机制。

## 3. 层与对象

普通调用路径：Controller → Converter → Service → 本模块 Mapper / 其他模块 Service → Converter → 响应。

| 位置或对象 | 项目约定 |
|---|---|
| Controller | HTTP 解析、结构校验、认证主体提取、Service 调用及契约响应适配；不访问 Mapper、Redis 或第三方 Client |
| Request / Query | HTTP 入参；放在模块 dto 下，管理入参使用独立 dto/admin |
| BO | Service 的业务命令、条件和结果；提供方定义跨模块 BO |
| Entity | 所属模块持久化对象，不向 HTTP 或其他模块传递 |
| VO | 模块 vo 下的响应模型，管理响应独立；Service 不返回 VO |
| Converter | Request/Query 与 BO、BO 与 VO 的显式转换；不查询数据库、不决定业务流程 |
| Payload | 异步边界中的 ID、类型和必要快照；不携带 Entity、请求对象或事务资源 |
| `infrastructure.async` | Outbox、Durable Job、Stream transport、claim/reclaim、retry/dead、metrics 与 cleanup；业务模块只提供 typed payload/handler 并在本地事务创建 intent |

Service 实现身份相关权限、归属、状态和唯一性等业务判断。Bean Validation 通过不代表业务可执行。
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
- 模块公开用例使用 Service 接口；私有辅助逻辑不放接口，不为所有类创建接口。
- 同模块有多个 Service 时，写入仍由明确的状态所有者统一管理；查询 Service 可以读本模块数据，不拥有写状态规则。
- 不同权限、状态迁移或副作用的用户/管理用例采用不同入口和语义明确的方法，不使用 adminMode/isAdmin 切换两套流程。
- 管理活动归 activity，管理公共事项归 exam；不将所有管理业务集中到 admin 模块。
- Callback、Listener、Handler 负责入口适配，调用状态所有者 Service，不自行堆 Mapper 和业务流程。
- 拆分依据是业务职责、权限、事务或依赖分化，不按行数机械拆分；不添加只有转发价值的层。
- 默认沿用 Controller / Converter / Service / Mapper；不为每个操作建立一套 Facade、Manager、CommandHandler 或 Repository。新增类应承担可说明的转换、业务规则、状态所有权或查询职责，不能只是搬走 Controller 的方法后保留混杂责任。
- 循环依赖先检查数据所有权与编排方向，必要时提取查询能力或编排；不将跨模块 Mapper、静态 Bean 获取或 @Lazy 作为长期补丁。

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
