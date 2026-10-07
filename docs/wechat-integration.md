# WeChat Integration

本文是微信第三方能力边界的项目级权威来源。通用远程调用失败语义见 [Reliability Baseline](reliability.md)，通知与渠道投递状态见 [Reminder、Notification 与 Delivery 设计](reminder-notification.md)，Worker 执行见 [Async Processing Architecture](async-processing.md)。

2026-09-29 恢复既有服务号订阅通知及首条 canonical 活动开始提醒的决定见 §17。§3–5、§16 中“没有明确 permission 语义则不调用”的默认规则仍适用于未接通类型；legacy 和 canonical `ACTIVITY_START_REMINDER` 按 §17 的 provider-at-send 边界执行，不能再以固定 false 将它们等同于从未接入。带日期的早期现状表仅记录当时状态。

阅读顺序：§0 为范围与现状；§1–8 为身份、通知、账号及模板规则；§10–12 为整个微信接入的目标结构与基础流程；§13–15 为差距、实施批次与验收维护；§16 专门说明配置职责、当前发送限制及后续解耦。目标规则不代表现有代码已实现。可复制的实施任务见 [微信整理执行 Prompt](WECHAT_REFACTOR_PROMPT.md)，它只引用本文，不另立规范。

## 0. 范围与维护方式

本文维护本项目采用的微信能力、账号边界、协议选择和接入条件，不复制整套微信文档，也不把所有腾讯产品合并为一个集成。通知是其中一个用例；登录、身份绑定、OAuth、JS-SDK、内容安全和回调同样属于微信接入范围。尚未采用的支付、客服等能力不提前建设。

维护微信不能只靠文档，按事实性质分工：

| 维护对象 | 唯一落点与责任 |
|---|---|
| 采用哪些能力、账号与协议边界、启用条件 | 本文；微信适配维护者负责，业务含义仍由业务 owner 决定 |
| 请求/响应、字段转换、错误分类、验签与 token 行为 | `third/wx` 的 Client、DTO、适配代码及对应测试；文档不代替执行约束 |
| 登录、绑定、审核、通知用例及持久状态 | 对应模块 Service；沿用架构中的数据所有权，不在 Client 建第二套业务状态 |
| 环境账号、模板映射、开关、回调及跳转参数 | 类型化配置与部署配置；secret/token/密钥由环境秘密管理，仓库只保留配置引用和无敏感示例 |
| 平台已开通权限、模板审核、域名关联及实际发送能力 | 微信后台与指定环境的验证证据；不能由配置存在或代码编译推断 |
| 用户身份、关注/授权、次数与撤销、投递结果 | 相应状态所有者的持久事实；文档、前端 success、日志和 token 缓存均不能代替 |
| 前后端新增或变化的授权/订阅接口 | [共享契约](../../contracts/README.md)；确认语义后才实现，本文不自行发明公开接口 |

当前采用单体内按用例分工的维护方式，不新增通用第三方平台、模板管理后台或动态配置数据库。只有出现真实的多账号、运营在线编辑或独立发布需求时，再决定是否引入这些能力。

### 0.1 项目能力清单与证据边界

以下为 2026-09-28 工作区静态核对，表示代码入口存在，不表示目标环境可用。路径以 `src/main/java/cn/jualn/miniapp/` 为根。

| 能力 | 当前入口/编排 | 维护重点及当前边界 |
|---|---|---|
| 小程序登录 | `module/auth/service/impl/AuthServiceImpl` → `third/wx/client/WxClient.getMiniSession` | 小程序账号、code 换会话、身份关联；应用登录态归 auth，不把微信 session 当通用业务会话 |
| 服务号绑定、关注/扫码事件 | `module/wx/service/WxBindService`、`WxSubscribeService`、`module/wx/handler` | 绑定写入归 user Service；scene 成功后消费，重复同一身份幂等，替换/争用拒绝；绑定仍不等于通知授权 |
| 服务号 OAuth 与 JS-SDK | `module/wx/service/WxMpOauthService`、`WxJsSdkService` | OAuth state 有短租约 claim、身份解析态和可重放完成态；签名 URL 限定配置 HTTPS origin；具体官方 code/URL 规则仍需协议核验 |
| 内容安全 | `module/audit/service/impl/AuditServiceImpl` → `WxClient`；`module/wx/handler/ma/WxaMediaCheckHandler` | 同步结果、异步任务关联及审核回调；审核业务结果归 audit |
| 接入验证与消息回调 | 活跃入口为 `module/wx/controller/WxCallbackController`；`third/wx/controller/WxVerifyController` 是注释遗留文件 | 账号识别、验签/解密、事件路由、重复与迟到处理；本次未做安全机制验收 |
| 服务号订阅通知 | `module/wx/service/WxMpNoticeSendService` → `WxClient.sendMpSubscribeMessage` | legacy 及 canonical `ACTIVITY_START_REMINDER` 复用既有发送基础；本地不虚构永久 GRANTED，微信发送响应权威判定该次投递 |
| 服务号模板消息 | `WxClient.sendMpTemplateMessage` | 当前仅确认 Client 方法；生产 Java 源码搜索未发现调用方，不等于已有业务投递链路 |
| 小程序订阅消息 | 登录/token 已有；通知 Adapter 尚缺 | 不得用服务号发送接口或模板代替 |
| 新通知模型的微信规划 | `module/notify/service/impl/NotificationDeliveryServiceImpl.planCanonicalDeliveries` | IN_APP 独立规划；服务号仅开放 `ACTIVITY_START_REMINDER`，小程序及其余新类型保持 unavailable |

### 0.2 微信与腾讯云 COS

微信接入归 `third/wx`；腾讯云对象存储归 `third/cos`，媒体上传、绑定与清理用例归 `module/media`。两者分别维护账号凭据、权限、配置、客户端、失败分类和验证证据。COS 的 bucket/region、临时凭据、objectKey 与清理状态不进入微信账号或模板注册表。

二者共享项目既有 Reliability、Observability、Async 等工程基线，不共享业务授权模型或一套无差别重试。COS 详细设计在实际处理媒体/存储任务时维护；本轮只确定边界，不新增空的第三方总纲或 COS 规范。

## 1. 职责边界

微信是可替换的外部 Integration，不是 Activity、PublicEvent、Reminder 或 Notification 的组成部分：

```text
Business Notification Factory
        │ channel-neutral NotificationContent
        ▼
NotificationDelivery / Delivery Handler
        │ stable DeliveryData
        ▼
WeChat template mapping + identity resolution
        │ provider request DTO
        ▼
WxClient
```

- 业务模块决定通知类型、用户可见快照和业务目标。
- Delivery 层分别决定是否存在 `WECHAT_MINI_PROGRAM`、`WECHAT_OFFICIAL_ACCOUNT` 渠道意图，并持久化每个渠道结果；禁止使用笼统 `WECHAT`。
- WeChat Integration 按渠道解析身份与 permission、选择独立模板、把稳定字段映射成各自 provider DTO、调用对应 API，并分别分类 provider 结果。
- `WxClient` 只负责 token、HTTP、序列化、微信响应码和一次受控 token refresh；不读取业务表，不判断 Activity/PublicEvent 状态，不创建 Notification。
- Integration 不得依赖 ActivityMapper、ExamInfoMapper、NotifyPlan Entity 或业务 Controller DTO。当前静态检查仍发现 `module/wx/notice/builder/ActivityRemindBuilder -> ActivityMapper`；原文所列 `NotifyPlanNoticeDataFactory` 在当前生产源码已不存在，不再作为现状依据。

## 2. 微信身份不是一个 openid

至少区分：

| 身份 | 使用边界 |
|---|---|
| Mini Program openid / session | 小程序登录、会话和小程序订阅消息 |
| Official Account openid | 服务号关注、OAuth、模板/订阅通知 |
| unionid（可得时） | 同一开放平台下的身份关联证据，不替代具体渠道 openid |

不能把某一渠道的 openid 作为所有微信能力的通用用户 ID。当前表中 `openid` 是小程序身份，`mp_openid` 是服务号身份。Delivery channel 必须指明需要哪类身份；无法解析时在 planning 阶段不创建，或对已存在 Delivery 标记 `SKIPPED`，不能把空身份交给 provider。

用户 Notification Preference 与 provider permission 是两种事实：前者表示用户愿意接收某 Category × Channel，后者表示微信当前允许具体模板/消息向该渠道身份发送。任一缺失都不能靠最终 provider reject 代替前置 eligibility 判断。

## 3. 渠道 Capability

| Capability | WECHAT_MINI_PROGRAM | WECHAT_OFFICIAL_ACCOUNT |
|---|---|---|
| identity | 小程序 `openid` | 服务号 `mp_openid`，必要时以 unionid 作为关联证据而非发送身份 |
| authorization / permission | 对应小程序订阅消息的用户授权事实；当前没有可靠持久模型 | 服务号关注/绑定及具体订阅通知权限；当前 H5 回传只证明入口操作完成，不是持久 permission 事实 |
| template registry | 必须独立建立；当前缺失 | 已有 `wx.mp.notice-templates` 基础，但新 Activity/PublicEvent NotificationType 默认未配置/未验证 |
| field mapping | 小程序订阅消息字段规范，独立 mapping | 服务号订阅通知字段 mapping |
| jump target | 小程序页面路径，由该渠道配置 | 服务号通知中的 miniprogram/pagePath，由该渠道配置 |
| request API | 小程序订阅消息 API；当前通知 Adapter 缺失 | 当前 `WxMpNoticeSendService -> sendMpSubscribeMessage` |
| error classification | 按小程序 API code 单独注册 | 按服务号订阅通知 API code 单独注册 |

两渠道可以共享 access-token/HTTP/provider client foundation、脱敏日志、Unknown Outcome 模型，但不能共享 provider identity、templateId、permission 判定或假设相同 error code 语义。当前只有服务号通知发送基础可用；小程序登录/token/内容安全能力不能证明 `WECHAT_MINI_PROGRAM` Notification Delivery 已可用。

## 4. 稳定内部 Contract

微信模板映射消费稳定、渠道无关的 `NotificationContent/DeliveryData`，典型字段为：

```text
notificationId
notificationType
receiverId
subjectType / subjectId
subjectTitle
eventTime
location
```

不是所有类型都需要所有字段；具体字段由版本化内部 DTO 明确，不能使用无约束 Map 作为长期 Contract。页面路径由 `notificationType + channel + subjectType` 在 Integration 配置中映射；templateId、keyword 名、颜色、touser 和 provider request body 只存在于对应 Channel Adapter。

Notification 创建时已经冻结用户可见快照。Delivery Handler 不再读取 Activity/PublicEvent 以补模板字段；如果现有 Notification 快照不足，应先由业务 Factory/Notification schema 明确补齐，而不是让微信层跨模块查询。

## 5. Provider 调用与错误分类

`WxClient` 保持 provider-level：

- access token 按渠道独立缓存，并只在微信明确 token 失效码时刷新一次后重试；不能对任意超时或 5xx 自动重复远程写。
- Client 返回或抛出的结果必须保留足够的 provider code、HTTP 阶段和是否收到权威响应信息，供上层分类；日志和持久错误必须脱敏、截断。
- 网络 timeout、connection reset 或写出后无响应是 Unknown Outcome，除非微信接口提供已验证的幂等键或状态查询。

Delivery 分类：

| 结果 | 示例含义 | Delivery / Job 处理 |
|---|---|---|
| Known success | 微信权威响应已接受并成功 | `DELIVERED`；Job 成功 |
| Known retryable failure | 明确未接受的临时限流/服务不可用，且重发安全 | `PENDING` + backoff；Job retry |
| Known permanent failure | 无效模板、无效接收者、参数错误、授权永久拒绝 | `FAILED`；Job 成功终止 |
| Unknown outcome | 请求可能已被接受但响应丢失 | `UNKNOWN`；Job 停止普通 retry，进入 reconciliation/manual |
| Ineligible before call | 身份解绑、用户关闭、对应类型业务资格失效等 | `SKIPPED`；不调用 provider；主体取消仅使相应 Reminder 失效，不能过滤取消通知 |

目前微信通知接口没有被证明具备可用于自动解析 Unknown Outcome 的幂等键或发送结果查询，因此默认不盲重发。未来若 provider 能力变化，必须先补权威证据、内部 Contract、恢复 owner 和测试，再改变该规则。

### 5.1 调用前 Eligibility

规划和执行都必须在 provider 调用前检查：channel enabled、adapter capability、templateId/field mapping、正确渠道身份、provider permission 和当前业务相关性。

- planning 时已知 `enabled=false`、templateId 缺失、Adapter 或 permission capability 不存在：不创建该渠道 Delivery，并记录稳定 planning reason。
- Delivery 已创建后 capability 被撤销、身份解绑或 permission 失效：`SKIPPED`，不调用 provider。
- capability 已配置，但冻结 payload 违反已确认模板 contract：`FAILED`；这是实现/数据错误，不应依靠微信拒绝发现。
- templateId 目前未提供的矩阵单元统一视为“配置缺失 / unavailable”，文档、测试和默认配置不得编造 ID。

### 5.2 启用与执行证据

启用某个 NotificationType × Channel 前，必须有该渠道真实 provider contract 与验证证据：身份来源、模板字段及跳转、授权来源与作用域、有效期/撤销语义，以及是否存在次数限制。若权限有消费次数，需说明并发 Delivery 如何协调使用，以及 Unknown Outcome 后如何保守处理额度；不能只存一个永久 GRANTED 布尔值。没有此类能力证据时保持 unavailable，不在本轮猜测微信协议或新增公开授权接口。

类型相关性由业务 owner 返回稳定判断，Adapter 不跨模块查 Mapper。调用前的 ownership、PROCESSING、预算及迟到结果保护遵循 [Async §7.2.1](async-processing.md#721-业务执行所有权与通知收尾)。`DELIVERED` 对外部渠道仅表示获得 provider 权威成功响应，不证明用户设备展示或用户已读。UNKNOWN 停止普通重试；启用真实发送前必须具备通知文档规定的结果收尾和受控处置能力，不以盲重发代替运维恢复。

## 6. 现有能力与演进边界

Admin QR Login v2 新增 `WxClient.generateMiniProgramCode`：使用 ma 凭据调用 `wxa/getwxacodeunlimit`，固定确认页、独立 scene、部署 env-version 与可配置 check_path，归一化有界 PNG。临时码由 admin/auth 持有短期 Redis truth，不进入 COS/Attachment 或通知链。仅明确 token 失效允许刷新一次，整个链 25 秒，无通用 POST retry；详情及证据见 [v2 实现记录](admin-qr-login-v2-implementation.md)。用户确认现有小程序 AppID 与本地确认页就绪，配置默认启用；正式环境默认 release、dev 默认 develop，可显式覆盖 env-version。AppID 直接复用 wx.ma 配置，不增设 Admin 凭据。用户澄清确认页尚未发布，dev 默认 check-path=false，正式环境默认 true；ADMIN_QR_LOGIN_CHECK_PATH 可显式覆盖，生成码不证明页面已上传或可在真机打开。本地模拟 HTTP 不构成真实微信调用或真机证据。

当前 `WxClient` 已承载小程序登录/token、内容安全、服务号 token/二维码/用户信息/模板或订阅通知、OAuth 与 JS ticket 等 provider 能力；这些能力可以继续共用同一 provider client，但其应用编排仍按认证、绑定、审核、通知等不同用例分开。

本文覆盖整个微信接入的维护边界，第 1–5 节重点定义通知投递。本轮为文档设计维护，不重命名现有包、不统一所有微信 DTO、不改变认证/审核/绑定/OAuth/JS-SDK 流程。后续实施应增加两套明确 Channel Adapter/Capability，而不是把现有服务号实现泛化命名成“微信”。小程序通知 Adapter 和两渠道 permission 事实必须有真实 provider contract 后再启用。

## 7. 配置、观测与安全

- appId、secret、templateId、回调 token 等继续使用现有配置/Secret 边界；每个 NotificationType × Channel 显式配置 enabled/template/mapping/jump target，文档和日志不记录真实值。
- 指标使用稳定低基数 `channel/result/error.category`；notificationId、openid、provider 原文不作 tag。
- 日志关联使用既有 traceId、operationId、jobId 和 deliveryId；正常逐条成功不打 INFO。
- health 只反映可观测依赖状态，不通过主动发送真实消息探活。
- 任何模板或身份映射变化都要检查 NotificationContent、Delivery 状态分类、配置、测试和 rollout；对外 HTTP 契约仅在真实接口变化时更新 contracts。

## 8. 账号、消息产品与模板维护

### 8.1 先区分渠道，再选择消息产品

`WECHAT_MINI_PROGRAM` 与 `WECHAT_OFFICIAL_ACCOUNT` 是项目投递渠道；“订阅消息”“订阅通知”“模板消息”是具体 provider 产品，不能仅凭名称中的“模板”视为同一协议。

| 渠道 / 产品 | 项目中的区分方式 | 禁止的推断 |
|---|---|---|
| 小程序 / 订阅消息 | 独立 Adapter、身份、模板及授权来源；当前尚未接通通知发送 | 小程序登录成功不等于允许发消息 |
| 服务号 / 订阅通知 | 当前 `sendMpSubscribeMessage` 调用 `/cgi-bin/message/subscribe/bizsend`；使用 `wx.mp.notice-templates` | H5 入口 success、关注和绑定不证明具体模板可发 |
| 服务号 / 模板消息 | 当前 `sendMpTemplateMessage` 调用 `/cgi-bin/message/template/send`；独立 DTO | Client 方法存在不证明账号有权限，也不是订阅通知失败后的备用发送方式 |

同一通知类型在同一渠道第一阶段明确选择一种消息产品，不在失败时自动切换产品或另一账号。此选择不新增公共 channel 枚举，也不允许绕过用户偏好或许可。当前服务号新通知设计沿用订阅通知方向；若改用模板消息，应先确认账号资格、业务场景及适用规则，再修改适配设计和验证。

### 8.2 环境账号记录

沿用 `WxProperties` 的 `wx.ma`（小程序）和 `wx.mp`（服务号），不凭文档新增配置键。每个目标环境维护一条账号记录：环境、账号别名/类型、配置引用、运营责任人、已验证能力、后台权限证据位置及核验日期。真实 secret、回调 token、EncodingAESKey、access token、session key 不进入文档或测试数据。

记录还应覆盖相关域名/回调地址、账号关联、小程序跳转环境及平台要求的网络配置；具体要求以对应能力的官方协议和账号后台为准。尚未核验项明确为 UNKNOWN。access token、OAuth token、session key、JS ticket 分别按用途管理，不能相互替代；缓存至少隔离账号和凭据用途，账号切换时检查旧缓存和在途任务。

本次没有读取账号后台或生产配置，因此账号资格、平台配额、真实模板、域名关联和环境可用性均未确认。

### 8.3 模板注册应维护什么

注册的逻辑定位为“环境/账号 + channel + 消息产品 + NotificationType”。这不是新增数据库键或现有配置 schema 声明；当前仅服务号订阅通知已有 `WxMpNoticeTemplateProperties/Registry`，其他产品不能复用其 ID 与规则。

每个启用项应可追溯到以下事实；可执行项留在配置/代码，审核和验证证据附在对应变更记录，不再复制一份易过期的模板 ID 清单：

- 对应业务类型、provider 产品、模板配置引用、后台模板版本/字段定义和核验日期。
- 逐字段的业务快照来源、provider keyword、类型、必填、格式、长度及缺省规则。不能用默认文案掩盖缺失的必要业务事实。
- 跳转目标、参数来源、页面所属账号与环境；用测试证明目标存在且参数含义一致。
- 许可来源、适用身份/模板范围、有效期、撤销及次数语义；由第 5.2 节要求的证据决定，不统一假设为永久布尔值。
- enabled、订阅入口是否展示、适配版本与验证记录。`subscribeVisible` 仅控制入口展示，不构成发送许可。

现有配置类的 `enabled` 默认值为 true；这不等于新通知渠道默认可用。后续接通时需用显式配置及 capability/permission 门禁证明不会误发，不能靠本文改变运行行为。

### 8.4 模板和权限变更流程

1. 确认 NotificationType、渠道、消息产品和账号实际能力，取得模板字段及许可规则证据；业务接收者不由模板维护者扩大。
2. 修改受影响配置、映射及必要契约；验证必填、长度、时间、跳转、拒绝、撤销、缺身份和未知结果等实际边界。
3. 模板替换或账号切换前盘点待发送 Delivery。明确旧配置保留、兼容迁移或受控终止方案；不能让排队消息静默采用不兼容字段或把旧模板许可转成新模板许可。需要版本持久化时再按数据演进流程实现，本轮未新增字段。
4. 在明确目标账号与环境中完成受控真实验证，分别记录 provider 接受、终端展示和跳转结果；错误结果分类与恢复也需有证据。通过后才启用对应单元。
5. 停用时关闭相应能力/入口并处理在途状态；停止新调用不保证撤回已发送请求。回退不重置 UNKNOWN，也不自动补发历史未规划渠道。

## 9. 后续实施顺序与验收记录

新增通知能力启用前，先核验本项目小程序和服务号的账号能力、拟用模板、授权来源及真实通知场景，再优先接通一个 NotificationType × Channel。基础整理按 §14 独立推进，不以缺少账号后台资料为由停止所有工作。当前没有足够账号证据替用户选择“服务号模板消息替代订阅通知”。

实施分为：账号/产品核验 → 模板及权限语义确认 → 必要的客户端契约与持久模型 → Adapter/Eligibility → 受控联调 → 启用。授权次数的持久化与并发协调按真实产品语义设计，不能先造通用授权表。已有登录、绑定、审核和回调按受影响链路检查，不借本次设计全量重构。

每次能力变更记录：能力与账号别名、环境、配置/代码版本、官方来源与核验日期、后台证据位置、测试对象与结果、未完成项及责任角色。分别标明“代码存在”“本地验证”“账号能力确认”“目标环境联调”“已启用”，不使用笼统“微信已接入”。

本轮记录：文档和代码边界已静态核对；没有新增 Adapter、授权持久模型或配置开关，没有发送真实消息。后续必需输入为账号后台权限和模板字段、所选消息产品的许可规则与测试环境；这些缺失不阻止维护本文，但阻止宣称真实渠道可用。

### 9.1 官方协议入口与更新边界

- [小程序发送订阅消息](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/mp-message-management/subscribe-message/sendMessage.html)
- [公众号订阅通知](https://developers.weixin.qq.com/doc/offiaccount/Subscription_Messages/intro.html)
- [公众号模板消息接口](https://developers.weixin.qq.com/doc/offiaccount/Message_Management/Template_Message_Interface.html)

2026-09-28 经 web 浏览工具未能取得上述正文；2026-09-29 直接 HTTPS 读取已取得服务号订阅介绍、发送及事件推送的官方正文，见 §17.2。其他页面和本账号实际资格仍需独立核验，博客和旧示例不能代替官方协议或账号权限证明。

## 10. 整个微信接入的目标结构

### 10.1 分工与依赖方向

沿用现有单体、Controller / Service / BO / Mapper 体系，确定下列职责，不按“每个微信 API 一个 Service”机械拆类：

| 所属位置 | 目标职责 | 不应承担 |
|---|---|---|
| `third/wx/config` | 账号及 provider 配置绑定、结构校验 | 查询用户、决定通知接收者 |
| `third/wx/client` | 按账号执行 provider 请求，解析协议响应；凭据获取与缓存可在确有复用时分离 | 用户表读写、通知/审核状态迁移 |
| `third/wx/crypto`、provider DTO | 验签、加解密、安全解析及微信线上的请求/响应模型 | 应用登录态、绑定和业务权限 |
| `third/wx/notice` | 具体消息产品的模板定义、注册校验、纯字段渲染 | 从 user/activity/exam 查数据、创建 Delivery |
| `module/wx/controller` | 项目微信入口及 provider 回调的 HTTP 适配 | 直接访问 Client/Mapper、承载完整编排 |
| `module/wx/service`、handler/adapter | 绑定/OAuth/订阅入口编排、可信回调路由、身份与许可解析、渠道适配 | 绕过其他模块 Service 写表、自行决定业务通知内容 |
| `module/auth`、`module/user` | auth 负责应用登录；user 负责现有用户微信标识的读写和关联不变量 | 把模板配置或 provider 错误当用户模型 |
| `module/audit`、`module/notify` | audit 负责审核事实；notify 负责通知规划、投递结果和执行所有权 | 在业务状态里复制 provider token/模板协议 |

目标依赖方向：应用入口 → 用例 Service → 数据 owner 的公开 Service / 微信适配 → provider Client；可信回调 → wx 编排 → user/audit 等公开 Service。`third/wx` 不依赖 `module/*` 的 Mapper、Entity、Controller 或应用编排；provider DTO 不依赖业务模型。对公共 BO 的使用服从项目架构，不能通过回调 Service 循环依赖解决反向引用。

通知调用为 notify → wx 渠道适配 → user 的身份查询 / wx 的许可能力 → third/wx；适配返回结果，notify 负责持久化 Delivery。微信适配不反向调用 notify 修改状态。业务快照由业务/notify 所有，映射成微信 keyword 的规则由 wx 所有。

`WxClient` 可以继续作为集中入口；只有凭据生命周期、协议产品或测试边界确实独立时才拆协作者，不强制一次拆成多个 Client。不为每个 DTO 增加接口或工厂，不创建通用 ProviderManager、插件系统、动态脚本模板。

### 10.2 对象与边界

- 前端 Request/VO、应用 BO、provider DTO 分开命名和归属。移动 Java 类不等于改变 HTTP URL、JSON、认证或回调协议；现有 `/third/wx/...` 路由可保留。
- wx 对内公开“身份解析、绑定用例、渠道能力/投递结果”等必要 Service；仅取身份时返回最小 BO/标量，不传 `UserProfile` Entity。
- 固定 Notification 内容使用具名快照。provider 的动态 `data` 字段允许局部 Map；历史 `NotifyPayload.wxData` 的兼容转换集中在边界，不继续让新业务依赖微信字段常量。
- 不强制所有调用统一为一种结果包装。同步登录/查询沿用项目异常规范；发送适配的结果至少可区分拒绝、成功、未知及调用前不可用，并保留脱敏 provider code/响应证据。客户端 wire code、应用结果和 observability category 各有责任。
- 来源不明、版本不支持或关键字段缺失不转换为“成功/空值/随便一个模板”。旧 reader 的保留和移除按 §14 执行。

## 11. 账号、凭据与身份的基础约束

### 11.1 配置与缓存生命周期

1. 当前模型仍是一套环境中的小程序账号与服务号账号，不预建租户/账号数据库。能力按账号和用途独立检查，服务号通知未配置不应意外禁用小程序登录。
2. 未启用的可选能力允许未配置，并报告 unavailable；显式启用但配置内部矛盾（重复类型、非法字段映射、缺必需凭据等）必须在配置校验阶段明确失败，不能运行后任意取第一条。secret 不出现在错误详情。
3. token/ticket 缓存键在实际共享 Redis 范围内隔离环境、账号与用途；若依赖实例/前缀隔离，记录真实保证。不能仅用 `MA/MP` 假定永远只有一个 appId。过期时间依据经验证的 provider 有效期并保留安全余量；错误结果、空 token 或非法有效期不进入缓存。
4. 同一账号的刷新应避免并发风暴和旧失效结果覆盖新凭据。按真实部署实例数选择最小协调手段；不为单实例默认搭建分布式锁平台。明确失效响应后的最多一次刷新/replay 服从具体 API 语义；网络超时不触发通用写重试。
5. secret/appId 轮换检查缓存命名空间、在途请求、JS ticket 依赖、回调密钥及旧配置退出时机。只失效本账号用途的缓存，不清空整个 Redis。秘密获取失败不退回代码内默认凭据。

### 11.2 身份、关注、偏好、许可分别维护

| 事实 | 所有权与规则 |
|---|---|
| 应用用户与微信标识关联 | 当前 user 持久模型及公开 Service；wx 负责取得可信身份并发起关联，不直接写用户 Mapper |
| 登录会话 | auth 管理；微信返回身份只作为认证输入 |
| 关注状态 | 微信事件/查询的事实；不自动等价于绑定、解绑或模板许可 |
| 通知偏好 | notify/既有 setting 按业务范围拥有；不由 OAuth 或前端订阅 success 自动扩大 |
| 模板许可 | wx 的渠道许可能力拥有协议解释与证据生命周期；现有可靠持久模型缺失，满足 §5.2 后才设计 schema |
| 投递与次数占用 | Delivery 归 notify；如果产品确有次数限制，wx 许可 owner 提供受控占用/确认/未知处理，双方事务边界须在实现前明确 |

身份匹配必须带账号作用域。unionid 仅在来源、账号关联及适用范围已证明时使用；不按昵称、头像或客户端提交的 openid 自动合并用户。重复绑定同一身份应稳定；不同用户争用同一身份、替换绑定、解绑与历史多重绑定的处理须先查现有业务/契约及数据约束。缺失的产品规则列为待决定，不能用 `LIMIT 1`、最后写入覆盖或静默迁移掩盖冲突；有歧义时不得建立新的身份关联。

许可状态表达“有证据允许 / 已知拒绝或失效 / 未知”的区别，不在本文固定数据库枚举。前端回传只是线索，须说明可信来源和防重放方式；新模板不继承旧模板次数。对已发出但结果未知的次数占用不能直接释放并重发。许可存储不可用时不能按允许发送处理；它与可丢弃的普通缓存不同。

## 12. 基础流程与失败责任

### 12.1 登录、绑定与 OAuth

登录从客户端 code 进入 auth，经 provider 换取身份后，由 user/auth 完成本地状态；不向客户端返回 session key 或 app secret。code 使用和失败处理遵循对应官方协议，不把 code 当可无限重放的凭据。

绑定 scene / OAuth state 必须关联账号、用途、发起主体及有效期，随机不可预测；处理入口从服务端关联恢复目标用户，不相信回传 userId。用途不能互换，过期/缺失/冲突不得落绑定。一次性操作需具备原子消费或状态迁移，覆盖并发回调；不能先无条件删除 state 再执行不可恢复的外部换码。执行者需明确“消费前、换码后、DB 提交后、返回前”各失败点的结果：安全重放、返回已知结果或要求重新发起。实现可采用最小状态保护，不要求通用工作流。

重定向目标由受控配置与固定页面构造，不接受任意外部 URL；JS-SDK 签名限定项目允许的来源/路径，签名 URL 的规范化按官方协议验证，不擅自解码/重排 query。应用接口认证、OAuth state 校验和微信回调验签分别覆盖自己的入口，不能用一个放行所有 `/wx` 请求的规则替代。

### 12.2 回调接收与处理

```text
明确的账号入口
  → 请求大小/格式边界
  → 该账号验签及按已配置模式解密
  → 核验目标账号，安全解析 provider 事件
  → 按账号类型 + 消息类型 + 事件选择处理器
  → 调用状态 owner（本地提交或持久接收）
  → 按 provider 协议响应
```

- 配置为加密的入口不得由请求缺少某字段自动降为明文；支持的模式及 GET 接入验证按账号协议核验。解析器禁用外部实体/外部资源，限制输入；验签失败、畸形或错账号数据不得进入业务 Handler。
- 解密/解析与业务处理分开。raw XML 如有解析需要仅在接入边界短暂使用，不作为领域 BO、日志或永久 payload 传播；转换为可信的具名事件交给用例。
- 处理器选择包含账号作用域；未知但有效的事件可以按协议忽略，记录低基数结果。非法输入、明确忽略、已提交、暂时失败不能内部都变为同一种成功。
- provider 响应格式由接入适配负责，不一律改为项目 JSON Problem Details。成功 ACK 仅对应已处理，或已在本地持久化接受的工作。需要重试的暂时故障必须按已验证的协议响应；不能吞异常 ACK 后只留日志。
- ACK 语义和重投次数/时间窗口必须查具体官方协议；未核验时标明阻塞项，不猜一个 HTTP 状态即可获得可靠重投。若协议无法保障恢复，重要工作需复用已有 durable intent/Job 能力；不对所有回调建立通用 Inbox 表。
- 重复事件由状态 owner 保护业务不变量；有稳定 provider 事件 ID 时验证其唯一作用域，无 ID 时依据已确认语义选择去重/条件更新，不伪造唯一 ID。Redis 短期去重不能作为重要业务唯一保证。
- 迟到审核结果必须匹配审核请求/版本和合法状态；关注/取关等不能用到达顺序伪装业务时间顺序。重投、迟到及处理失败均需专门测试。

### 12.3 内容安全与通知发送

内容安全请求的提交、provider request 标识、回调关联及业务发布规则由 audit 负责。第三方层只翻译输入和结果；业务 Handler 不因解析失败而伪造“审核通过”。远程提交成功但本地保存失败、回调先到或迟到的恢复按 audit 与 Async 的现有保证检查，不另建一套审核状态机。

通知发送沿用 §1–5 和通知文档；本轮重整必须保留 provider 调用前的执行所有权/attempt 边界，不能在搬迁 `beforeProviderCall` 等接口时把真实调用放到所有权检查之前。远程 HTTP 在业务事务外，结果由 Delivery owner 条件收尾；中间层不自行创建 retry Job。provider 成功、设备展示和已读保持分离。

## 13. 当前差距与整理决策

2026-09-29 整理设计时静态检查发现下列问题，是实施起点而非全量安全审计结论；执行前重新核实工作区。基础缺口不能仅靠更新此表标记完成。

| 证据位置 | 现状/差距 | 整理目标 |
|---|---|---|
| `module/wx/service` 与 user Service | 原先三个编排 Service 直接访问 `UserProfileMapper` | 已收口：入口/编排移到 module/wx，身份读取/绑定走 `UserService`；V23 唯一键及 preflight 尚未在真实 MySQL 验证 |
| `WxMpOauthService`、`WxBindService` | 原 state/scene 可重放且失败点不清 | 已加入 claim owner、解析身份中间态、完成态重放和成功后 scene 消费；Redis Lua/并发机制仍待真实 Redis 验证 |
| `WxJsSdkService` | 原校验仅为 URL 非空 | 已限定配置域名的 HTTPS origin、端口并拒绝 fragment；官方签名 URL 细节因文档不可访问仍 BLOCKED |
| `WxCallbackServiceImpl`、`WxEventServiceImpl` | 原异常/解析失败多处返回 success，事件 key 无账号 | 已按 `mp/ma + msg/event` 路由，非法签名/超大 body/解析与业务失败不再返回 success；provider ACK/重投窗口仍待官方协议确认 |
| `WxaMediaCheckHandler` | 原来传播 raw XML 并二次解析 | 已由接入边界一次解析为具名 DTO，校验小程序 appId 后调用 audit；audit 的 DB 版本/条件收尾保持不变 |
| `WxClient` | 原静态 token/ticket key、固定 TTL | 已按账号类型+appId+用途隔离，使用 provider `expires_in` 留安全余量，并在单实例内合并刷新；多实例协调需部署事实后决定 |
| `WxMpNoticeTemplateRegistry` | 原按 type 任取第一项 | 已拒绝重复/未知 type、启用但缺模板/字段及矛盾字段配置；跳转可选，支持站内相对路径，拒绝外部 URL |
| `ActivityRemindBuilder`、旧 NoticeData/NotifyPayload | 原 Builder 回查 Activity Mapper | 已移除跨模块查询；V25 由 ReminderPlan 冻结开始时间/地点，缺失字段不创建 canonical 服务号 Delivery，也不回查业务表 |
| 新渠道 Adapter 与许可 | canonical 两微信渠道没有可靠本地永久 GRANTED 事实 | 服务号活动开始提醒采用 `PROVIDER_VERIFIED_AT_SEND`；其余 canonical 微信类型仍 unavailable，不伪造本地授权台账 |
| `WxVerifyController`、相关测试 | 注释遗留与过期测试 | 注释遗留已删除；31 个定向测试覆盖本轮局部行为，但不证明真实 Redis/MySQL/微信 |

## 14. 整理批次与兼容迁移

| 批次 | 可交付结果 | 必需完成条件 |
|---|---|---|
| W0 现状与协议表 | 活跃入口、调用方、owner、配置项、回调/旧 payload 版本及当前测试清单 | 每项有源码/契约位置；未知协议、产品决定与平台事实单列 |
| W1 结构与内部边界 | 用例回到模块、user 写入收口、provider 去业务依赖、类型化输入输出 | 相关调用方同时更新；HTTP/配置兼容，原有保证不减弱 |
| W2 基础保护 | 配置校验、凭据隔离、state 生命周期、账号回调路由、安全解析与错误责任 | 完成能独立验证的本地保护；协议依赖项明确阻塞，不能宣称整条链路可靠 |
| W3 通知适配收敛 | 模板注册与映射、统一的渠道调用边界、旧新路径明确 | 两条微信路径分别检查，未就绪不可用；不绕过 Delivery owner，不自动补发 |
| W4 实际能力启用 | 选定场景的真实账号/模板/授权及端到端证据 | 目标环境与真实发送已授权，§5.2 满足，恢复与停用可操作 |

本轮设计交接授权后续执行者完成 W0–W3 中信息充分的代码整理与必要验证；遇到确实缺失的协议/业务决定，继续其他独立项。W4 是单独环境验收，不能以重构授权推导生产操作。W1–W3 是有行为保护的实现工作，不是只改包名或增加抽象。

迁移约束：

- 整理前检查 Git 已有修改。旧接口未进入 contracts 时，追踪 mini-program、H5、管理端或微信后台调用，并记录兼容基线；跨组件语义变化先走 contracts，不顺手重写所有公开接口。
- 保留现有配置键和部署兼容。确需新增/更名时记录缺省、旧键过渡、启停影响和配置校验，不能仅改 Java 字段。
- JSON 类名/字段、异步 payload 版本和 DB 字段可能与 Java 包独立持久存在；重命名不得破坏已排队数据。删除旧 reader 前需目标环境 backlog/preflight 证据；无证据保留局部兼容层，不新增双写。
- DB 变化按迁移历史演进；先检查绑定冲突/缺失身份等历史数据，不猜测回填。绑定替换策略与许可消费规则未确认时，不创建虚构的目标 schema。
- 清理死代码须确认路由、Bean、反射/配置、测试和历史 payload 引用；不要因“搜不到普通调用”删除仍承担兼容的入口。
- 移动完成后删除同职责重复实现，不保留新旧两个维护中心。必要旧适配器写明入口、退出条件与所有者。

## 15. 验收与日常维护

### 15.1 按行为验收

以下按实际改动选取；代码存在或编译通过均不能代替行为证明。

| 验证边界 | 至少要证明的性质 |
|---|---|
| 结构/兼容 | third/wx 不反查业务模块；状态写入归 owner；原路由/响应/配置及必要旧 payload 仍兼容 |
| 配置/模板 | 禁用可选能力不影响无关功能；启用无效配置明确失败；重复 type、缺字段、长度/时间/跳转和错误产品匹配被覆盖 |
| token/ticket | 账号/用途隔离、过期、缓存错误、并发刷新及一次受控 refresh；远程写超时无隐式重试 |
| 身份/入口 | 错账号、过期/重放 state、并发绑定、关联冲突、受控跳转/签名 URL；只更新合法用户 |
| 回调 | 有效/无效签名、加密模式、错账号、恶意 XML、未知事件、重复、暂时失败 ACK、业务失败和迟到结果 |
| 通知 | preference/capability/permission 分离；缺条件不发；调用前所有权、未知结果、永久拒绝和成功分别正确收尾 |
| 实际机制 | 涉及事务/唯一性/并发原子性时用相应 Spring/隔离数据库或 Redis 验证；Mock 只证明其覆盖的逻辑 |
| 真实微信 | 指定环境、账号和模板下 provider 接受/拒绝、终端展示、跳转及撤销；单独报告，不由本地测试替代 |

测试命令按 [commands](../governance/commands.md) 选择。协议 fixture 标明来源和核验日期，使用合成标识且不含真实凭据。为新建/修正的边界增加有意义测试，不添加只证明类存在或转发调用次数的测试。

### 15.2 以后变更时维护哪里

| 变更 | 必须检查的关联项 |
|---|---|
| 新微信能力/消息产品 | 本文能力与职责、官方协议记录、账号 capability、Client/DTO、失败语义、测试 |
| 新通知类型/模板 | 业务/通知权威定义、冻结内容、映射与跳转、许可范围、配置校验、旧 Delivery 兼容 |
| 账号/凭据/域名变更 | 环境记录、缓存与轮换、回调/签名、前端/H5 跳转、联调证据 |
| 新事件/回调 | 账号与模式、可信解析、owner/幂等、ACK/恢复、重放测试 |
| 身份/授权规则 | user/wx owner、contracts、历史数据与迁移、撤销/冲突/消费、并发验证 |

每次实施复用一份变更报告，不建立多个重复台账。建议将本次报告保存为 `docs/WECHAT_REFACTOR_REPORT.md`（实施时创建），包含：批次/版本、能力或入口、规范章节、代码与数据 owner、兼容处理、验证证据、状态、剩余项及所缺输入。真实账号记录只存别名和受控证据位置；环境状态变更及时更新该证据，不把历史报告当实时事实。

“基础整理完成”表示 W0–W3 的必需项均实现并验证，或明确保留经设计允许的局部兼容且满足退出条件记录；必要项 BLOCKED 时只能称部分完成。“微信可用”必须逐能力附 W4 证据。文档设计完成、本地代码完成、真实渠道启用分别报告。

## 16. 通知配置的职责、耦合与发送限制

模板配置属于微信渠道的应用适配配置，连接业务通知与 provider 产品，既不是业务通知规则本身，也不只是 HTTP 参数。业务决定“发生什么、通知谁、冻结什么内容”，渠道配置决定“用哪个产品/模板、怎样映射和跳转”，用户许可决定“这个人现在能否接收”。三者不能合并为一个 enabled。

### 16.1 当前配置链路与现状刷新

以下表格记录 2026-09-29 配置专项开始前的诊断：发送/订阅 Service 已移到 `module/wx/service`，Registry 已增加构造时校验，legacy 规划及执行曾加入 permission capability 门禁。随后用户确认历史真实收件，恢复决定及实现变更见 §17 和实施报告；本表不能作为恢复后的实时状态。未读取部署配置或微信后台，不代表运行环境已切换。

```text
application.yaml 的 wx.mp.notice-templates + 实际环境覆盖
  → WxMpNoticeTemplateProperties
  → WxMpNoticeTemplateRegistry（启动校验和按 type 索引）
      ├→ H5 /templates（enabled + subscribeVisible + templateId）
      └→ WxMpNoticeSendService（许可能力 → 模板开关 → 身份 → 字段/跳转）
          → WxClient.sendMpSubscribeMessage

业务 NotifyType → Delivery 规划/执行 → WxMpNoticeType 映射 → 发送 Service
```

源码检查到的具体限制：

| 条件/配置 | 当前影响 | 证据入口 |
|---|---|---|
| 服务号 permission capability | 服务号不宣称可在发送前可靠查询逐用户剩余许可；capability 返回 `PROVIDER_VERIFIED_AT_SEND`，provider 响应权威判定该次结果 | `CanonicalNotificationServiceImpl`、`WxMpNoticeSendService`、`NotificationDeliveryServiceImpl` |
| canonical 微信渠道 | `ACTIVITY_START_REMINDER` 在模板、服务号身份、用户偏好和冻结 payload 均满足时规划服务号 Delivery；IN_APP 与服务号互不阻塞 | `NotificationDeliveryServiceImpl.planCanonicalDeliveries` |
| 通知类型映射 | 新 code 8 映射既有 `activity_start`；code 9–18 仍未映射、保持 unavailable | `NotifyType`、`WxMpNoticeType`、Delivery Service |
| 仓库模板条目 | 基础 application.yaml 只有 audit_result、comment_reply、activity_start、reply 四项，均显式 enabled/subscribe-visible=true；不证明 ID 与实际账号匹配 | `src/main/resources/application.yaml`，实际环境覆盖仍未知 |
| 配置 type | 受 `WxMpNoticeType` 枚举限制，未知 type 或重复 type 启动校验拒绝；当前枚举有 8 项，不等于 8 项均配置且有业务发送入口 | `WxMpNoticeTemplateRegistry.validateAndIndex` |
| enabled | 控制模板是否可发送，也参与 H5 列表过滤；false 时仍要求 type 合法且不重复 | Registry、SendService |
| subscribe-visible | 只控制 H5 列表，不被 send 用作许可检查；隐藏不是撤销授权或停止发送 | `listSubscribeVisibleTemplates` |
| 必填/跳转参数 | 缺失在调用微信前抛错；当前 required=true 时先报错，不使用 default-value | `WxMpNoticeFieldRenderer` |
| 格式/长度 | 当前 datetime/date 对非 LocalDateTime 值直接转字符串，max-length 用 Java substring 截断 | FieldRenderer；不能视为已符合真实微信字段协议 |

因此服务号是否创建新 Delivery 由类型映射、模板、身份、有效偏好和冻结字段共同决定，不能只靠增加 YAML 条目。模板校验通过仍不证明账号权限、字段协议、跳转或该次 provider 接受。

当前 H5 列表未检查上述 permission capability，可能展示暂时无法发送的订阅入口。业务枚举、provider 类型枚举、配置 type、payload source 与 pagePath 占位符是多处隐式关联：缺一处就可能不规划、启动失败或渲染失败。特别是配置中的 `required=true + default-value` 容易让维护者误以为缺值会回退；应按下一节明确语义。

### 16.2 配置应分清的四种事实

以下是逻辑分工，不要求四个 YAML 文件或新增四张表。先保留 `wx.mp.notice-templates` 兼容，用具名内部模型和单一校验入口表达；确需改变 schema 时才走配置迁移。

| 事实 | 维护内容 | 所有者与边界 |
|---|---|---|
| 业务通知定义 | NotificationType、接收者、触发条件、冻结内容及语义 | 业务/notify；不能由新增模板条目自动新增业务事件 |
| 渠道绑定与映射 | NotificationType × channel 选择的消息产品、模板引用、稳定字段来源、跳转目标 | wx 应用适配；集中显式映射并检查覆盖，不由 notify 到处 switch provider 类型 |
| provider 模板及环境绑定 | 账号/环境、真实模板引用、keyword/类型/长度、格式规则、部署开关与订阅入口展示 | 微信协议与模板配置；与业务定义分离，字段规则需真实证据 |
| 运行时资格 | 该用户身份、偏好、许可、次数/撤销及当前投递相关性 | 各状态 owner；不存进 YAML、不用全局开关模拟逐用户授权 |

一个业务类型在一个渠道只选择一个明确消息产品。多个业务类型若经验证可共用同一 provider 模板，可以有多个绑定引用它；唯一约束应保护绑定的确定性，不能机械禁止 templateId 重复。反过来，枚举有值、模板 ID 有值、订阅列表有条目，都不自动证明业务已接入。

先维护可检查的显式类型映射，不建设配置表达式引擎。通知类型或字段语义变化仍需要代码/测试；更换同一已支持模板的环境 ID 或合理展示参数才属于常规部署配置维护。禁止 YAML 任意指定业务查询、接收者或 SQL。

### 16.3 配置校验与开关语义

- enabled 是部署允许，subscribe-visible 是入口展示；用户偏好、适配完成度、账号产品能力与逐用户许可分别判断。不要再增加一个把这些都覆盖的“强制可发”开关。
- 静态就绪检查覆盖绑定唯一性、已支持类型/产品、字段 source 白名单及类型、required/default 语义、formatter、长度策略和路径占位符；实际 payload 检查发生在发送前。只验证字符串非空不足以证明字段可用。
- 必须存在的业务事实不可用默认值编造；可选展示字段允许经确认的 default。required 与 default 的冲突组合明确拒绝或迁移为清晰的缺省规则，不能悄悄改变老通知文案。
- 内容截断仅用于允许缩略的展示文本；ID、时间和状态不能随意截断。字符计量与 provider 字段规则核验后实现，Unicode 边界、日期序列化及冻结 payload 经 JSON 读取后的类型都要测试。
- 跳转是否必填、路径格式与字段限制由所选产品和本项目场景共同确定。当前 Registry 的“必须以 / 开头”是本地检查，不是本文认证的微信通用规则；核验真实页面及客户端参数，避免自造校验误挡合法发送。
- 显式启用却矛盾的静态配置按 §11.1 提前失败；运行时某用户无许可只影响该次资格，不能触发应用启动失败。未启用条目仍不得含歧义的绑定键/type。
- 记录实际生效配置的来源（基础文件、profile、部署覆盖）及无敏感摘要。仓库 application.yaml 中的 enabled 不等于运行环境的有效值；本轮不输出真实模板 ID 或秘密。

### 16.4 订阅入口、规划与发送的一致性

共用同一套静态绑定/就绪判断，三处各自增加必要条件：

1. 订阅入口：产品/账号/模板可用、授权收集与后续消费链路已实现、部署允许且允许展示。不能要求用户已经拥有许可才展示获取许可的入口，否则形成循环。可以为尚未授权的用户显示入口，但后端整体未接通时不能伪装成可用入口；如保留引导页，明确不可用状态。
2. Delivery 规划：通知类型有绑定、该渠道已就绪，再检查用户偏好、身份、许可及业务资格。不满足时记录可解释原因，不创建一个注定无法发送的任务。
3. provider 调用前：重查可撤销的运行时条件，保留执行所有权；静态/身份/许可变化按既有 SKIPPED/FAILED/UNKNOWN 语义处理，不在配置层发明第二套投递状态。

需要调整 H5/API 的就绪表示时按共享契约流程处理；不把原始 provider code/内部配置路径直接变成用户文案。诊断应区分“未实现渠道、配置缺失、部署关闭、用户关闭、身份缺失、许可未知/不足、payload 无效、provider 拒绝”，复用既有内部原因体系并按其扩展规则登记，不新增 observability category。

就绪不等于 provider 保证接受。服务号当前采用 `PROVIDER_VERIFIED_AT_SEND`，不得显示为“已授权”；若以后 provider 提供可靠逐用户查询或次数台账，再通过契约演进替换该状态。小程序渠道仍不得复用这一语义。

### 16.5 本轮配置专项交付与验证要求

后续整理在 W3 增加：业务类型到产品/模板绑定覆盖检查、基础配置与实际 override 说明、H5 与发送就绪一致性、source/跳转/JSON 快照兼容验证。至少覆盖未映射类型、枚举已存在但未配置、禁用/隐藏的区别、展示入口无需事先授权、整体渠道未接通不诱导订阅、必填与默认值、重复绑定及合法模板复用、旧待发送快照。

配置专项完成不等于真实账号验收。canonical 活动开始提醒已接入本地规划/执行代码；目标环境迁移、部署、真实 provider 响应及设备展示仍需单独验证。

## 17. 恢复既有服务号订阅通知的决定（2026-09-29）

用户确认曾实际收到服务号订阅通知，并授权在“沿用微信校验授权”与“先建本地台账”之间选择。项目选择恢复既有 legacy 服务号路径，由微信发送接口权威校验实际订阅资格和次数；不把本地缺少授权台账推导成所有用户都不能发送。此决定修正之前把新渠道启用要求无差别施加给已有链路的范围，不表示前端 success 或偏好就是授权。

### 17.1 适用范围与保证

- 恢复原有 NotifyType 1–7 中确有启用模板的服务号订阅通知，并在 V25 冻结字段、canonical 偏好 owner 和 Delivery 规划均就绪后开放 code 8 `ACTIVITY_START_REMINDER`。code 9–18 及小程序渠道仍未接通，不借兼容路径补发或绕过各自资格。
- 本地在规划及执行时检查模板配置/开关，执行时检查用户设置、服务号身份及冻结字段；订阅入口继续展示可订阅的启用模板，不要求事先已有许可。微信决定是否接受本次发送及消费资格，应用不持久化虚构 GRANTED/次数，不宣称本地能预知每次是否可发。
- 模板存在不等于许可存在；明确的 provider 拒绝按永久失败收尾，不反复尝试到成功。超时、空/不完整响应及调用后结果未可靠记录保持 UNKNOWN；不得通过重放通知、重置终局或刷新开关补发。
- 复用当前 `bizsend` 产品和模板，不在失败后改用模板消息。保留明确 token 失效码的一次受控刷新；无明确安全依据的重试不可新增。
- 已因旧门禁成为 SKIPPED 的历史项不自动复活；此代码恢复只影响以后依法产生的任务及仍未终结的任务。生产是否已部署该门禁未知。
- 恢复对象为旧业务通知使用的 Delivery v2 Job；最早 v1 Job 没有逐 Delivery 尝试记录，保持 payload 可读，但 Handler 返回 UnknownOutcome，由现有 Job 机制停止普通 retry。运行环境需盘点 v1 backlog 并确认原发送结果后受控处置，不自动发送，也不制造历史 Delivery。
- 真实发送及账号状态仍需目标环境验收。本地恢复测试证明调用路径，不证明当前生产账号仍具备权限。

### 17.2 官方依据与尚存边界

本次通过直接 HTTPS 读取取得官方正文（此前 web 浏览工具读取失败并不代表官方文档不存在）：

- [订阅通知介绍](https://developers.weixin.qq.com/doc/service/guide/product/subscription_messages/intro.html)：订阅资格来自用户主动订阅，产品区分一次性与长期，长期能力有适用范围；不能从一次收件推断所有模板均长期有效。
- [发送订阅通知](https://developers.weixin.qq.com/doc/service/api/notify/notify/api_sendnewsubscribemsg)：使用 `/cgi-bin/message/subscribe/bizsend`，由服务端调用，以 errcode/errmsg 返回结果。该页参数表与示例对跳转字段的呈现存在差异；保留历史已用的 miniprogram 结构，不凭示例扩展幂等保证。
- [订阅通知事件推送](https://developers.weixin.qq.com/doc/service/guide/product/subscription_messages/push.html)：提供订阅操作、拒收管理和发送结果事件；当前不将不具备可靠关联的回调用于自动恢复 UNKNOWN，也不由事件名字推断精确次数台账。

后续如需 canonical 前置授权展示、次数占用或通知资格解释，再设计可信回调与持久模型。此目标不能通过一个返回 true 的“permission capability”方法伪装完成；既有路径的方法应只表达实际能证明的模板/配置就绪。

### 17.3 Notification Contract 外部入口

NotificationDelivery 复用现有 v2 AsyncJob 与 UNKNOWN 策略。冻结 provider 业务字段在发送时加入 originating `notificationId`，覆盖配置中同名参数；page-path 保留目标参数并携带该 ID。Notification Delivery 的模板就绪同时要求站内 page-path 和小程序 appId；缺入口不规划发送，既有待发送任务重查后 SKIPPED。仅有模板存在不能表示可恢复通知的渠道已就绪。旧直接发送方法的无 Notification payload 兼容仍保留，不扩张通知渠道。

外部点击由消费者 GET 恢复同一 Notification，再显式提交同一 readAt，最后按 semantic target 跳转；Delivery success 不写已读。type 19/20 仅复用现有 comment/reply 模板，新增 like 不默认开放外部发送。服务号真实 page-path 接受、订阅权限、设备打开与消费者恢复链仍需目标环境验收；小程序订阅消息渠道仍未接通。
