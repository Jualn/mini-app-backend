# WeChat Integration

本文是微信第三方能力边界的项目级权威来源。通用远程调用失败语义见 [Reliability Baseline](reliability.md)，通知与渠道投递状态见 [Reminder、Notification 与 Delivery 设计](reminder-notification.md)，Worker 执行见 [Async Processing Architecture](async-processing.md)。

小程序订阅消息采用 `PROVIDER_VERIFIED_AT_SEND`，微信响应判定本次发送资格；首版目录与模板配置见 §16.6，服务号本版停用见 §17。模板、偏好、身份与实际发送资格分别判断。

阅读顺序：§0 为职责入口；§1–8 为身份、通知、账号及模板规则；§10–12 为模块结构与基础流程；§13–15 为验证与变更维护；§16 为配置职责；§17 为服务号订阅通知的适用范围与兼容限制。

## 0. 范围与维护方式

本文维护本项目采用的微信能力、账号边界、协议选择和接入条件，不复制整套微信文档，也不把所有腾讯产品合并为一个集成。通知是其中一个用例；登录、身份绑定、OAuth、JS-SDK、内容安全和回调同样属于微信接入范围。尚未采用的支付、客服等能力不提前建设。

维护微信不能只靠文档，按事实性质分工：

| 维护对象 | 唯一落点与责任 |
|---|---|
| 采用哪些能力、账号与协议边界、启用条件 | 本文；微信适配维护者负责，业务含义仍由业务 owner 决定 |
| 请求/响应、字段转换、错误分类、验签与 token 行为 | `third/wx` 的 Client、DTO、纯格式化/结果分类及对应测试；文档不代替执行约束 |
| 登录、绑定、审核、通知用例及持久状态 | 对应模块 Service；沿用架构中的数据所有权，不在 Client 建第二套业务状态 |
| 环境账号、模板映射、开关、回调及跳转参数 | 类型化配置与部署配置；secret/token/密钥由环境秘密管理，仓库只保留配置引用和无敏感示例 |
| 平台已开通权限、模板审核、域名关联及实际发送能力 | 微信后台与指定环境的验证证据；不能由配置存在或代码编译推断 |
| 用户身份、关注/授权、次数与撤销、投递结果 | 相应状态所有者的持久事实；文档、前端 success、日志和 token 缓存均不能代替 |
| 前后端新增或变化的授权/订阅接口 | [共享契约](../../contracts/README.md)；确认语义后才实现，本文不自行发明公开接口 |

当前采用单体内按用例分工的维护方式，不新增通用第三方平台、模板管理后台或动态配置数据库。只有出现真实的多账号、运营在线编辑或独立发布需求时，再决定是否引入这些能力。

### 0.1 项目能力清单与证据边界

下表维护能力入口与责任边界，入口存在不证明账号/渠道专项验收通过。路径以 `src/main/java/cn/jualn/miniapp/` 为根。

| 能力 | 当前入口/编排 | 维护重点及当前边界 |
|---|---|---|
| 小程序登录 | `module/auth/service/impl/AuthServiceImpl` → `third/wx/client/WxClient.getMiniSession` | 小程序账号、code 换会话、身份关联；应用登录态归 auth，不把微信 session 当通用业务会话 |
| 服务号绑定、关注/扫码事件 | `module/wx/service/WxBindService`、`WxSubscribeService`、`module/wx/handler` | 绑定写入归 user Service；scene 成功后消费，重复同一身份幂等，替换/争用拒绝；绑定仍不等于通知授权 |
| 服务号 OAuth 与 JS-SDK | `module/wx/service/WxMpOauthService`、`WxJsSdkService` | OAuth state 有短租约 claim、身份解析态和可重放完成态；签名 URL 限定配置 HTTPS origin；具体官方 code/URL 规则仍需协议核验 |
| 内容安全 | `module/audit/service/impl/AuditServiceImpl` → `WxClient`；`module/wx/handler/ma/WxaMediaCheckHandler` | 同步结果、异步任务关联及审核回调；审核业务结果归 audit |
| 接入验证与消息回调 | `module/wx/controller/WxCallbackController` | 账号识别、验签/解密、事件路由、重复与迟到处理；业务成功后才按 provider 协议 ACK |
| 服务号订阅通知 | `module/wx/service/WxMpNoticeSendService` → `WxClient.sendMpSubscribeMessage` | 首版固定停发；保留 Client 产品边界与历史身份，不发起新请求 |
| 服务号模板消息 | `WxClient.sendMpTemplateMessage` | 当前仅确认 Client 方法；生产 Java 源码搜索未发现调用方，不等于已有业务投递链路 |
| 小程序订阅消息 | `WxMiniNoticeSendService` → `WxClient.sendMiniSubscribeMessage`，有效模板直接参与统一投递 | 不得用服务号发送接口或模板代替 |
| 新通知模型的微信规划 | `module/notify/service/impl/NotificationDeliveryServiceImpl` | IN_APP 独立规划；服务号适用类型见 §17，小程序通过同一有效模板目录与 Adapter 接入统一投递链 |

### 0.2 微信与腾讯云 COS

微信接入归 `third/wx`；腾讯云对象存储归 `third/cos`，媒体上传、绑定与清理用例归 `module/media`。两者分别维护账号凭据、权限、配置、客户端、失败分类和验证证据。COS 的 bucket/region、临时凭据、objectKey 与清理状态不进入微信账号或模板注册表。

二者共享项目既有 Reliability、Observability、Async 等工程基线；业务授权、配置、凭据和操作恢复分别由各自 owner 维护，不建设尚未需要的通用第三方平台。

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
- Integration 不得依赖 ActivityMapper、PublicEventMapper、NotifyPlan Entity 或业务 Controller DTO；模板映射只消费业务 owner 冻结的快照，不回查主体补字段。

## 2. 微信身份不是一个 openid

至少区分：

| 身份 | 使用边界 |
|---|---|
| Mini Program openid / session | 小程序登录、会话和小程序订阅消息 |
| Official Account openid | 服务号关注、OAuth、模板/订阅通知 |
| unionid（可得时） | 同一开放平台下的身份关联证据，不替代具体渠道 openid |

不能把某一渠道的 openid 作为所有微信能力的通用用户 ID。当前表中 `openid` 是小程序身份，`mp_openid` 是服务号身份。Delivery channel 必须指明需要哪类身份；无法解析时在 planning 阶段不创建，或对已存在 Delivery 标记 `SKIPPED`，不能把空身份交给 provider。

用户 Notification Preference 与 provider permission 是两种事实：前者表示用户愿意接收某 Category × Channel，后者表示微信当前允许具体模板/消息向该渠道身份发送。前置 eligibility 检查所有本地可证明的条件；两个渠道的实际发送资格分别由 §5.1 的 provider-at-send 模式判定，不据模板或偏好伪造 GRANTED。其他渠道没有已确认授权模式时不得调用。

## 3. 渠道 Capability

| Capability | WECHAT_MINI_PROGRAM | WECHAT_OFFICIAL_ACCOUNT |
|---|---|---|
| identity | 小程序 `openid` | 服务号 `mp_openid`，必要时以 unionid 作为关联证据而非发送身份 |
| authorization / permission | 用户通过对应小程序向微信订阅；发送响应判定本次资格，不维护本地授权次数 | 服务号关注/绑定及具体订阅通知权限；当前 H5 回传只证明入口操作完成，不是持久 permission 事实 |
| template registry | `wx.notification` 中独立的 ma-primary / MINI_PROGRAM_SUBSCRIBE Definition 与 Binding | 首版固定停发，无服务号模板启用入口 |
| field mapping | 小程序订阅消息字段规范，独立 mapping | 服务号订阅通知字段 mapping |
| jump target | 小程序页面路径，由该渠道配置 | 服务号通知中的 miniprogram/pagePath，由该渠道配置 |
| request API | `WxClient.sendMiniSubscribeMessage` → `/cgi-bin/message/subscribe/send`；有效配置直接可用 | 服务号本版停发；Client 产品边界保留 |
| error classification | 按小程序 API code 单独注册 | 按服务号订阅通知 API code 单独注册 |

两渠道可以共享 access-token/HTTP/provider client foundation、脱敏日志、Unknown Outcome 模型，但不能共享 provider identity、templateId、permission 判定或假设相同 error code 语义。两个渠道已有独立发送适配与 Delivery 分派，分别保留独立模板、身份和 provider 协议；服务号本版停发；不创建永久 GRANTED 或本地订阅次数台账。

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

规划和执行都必须在 provider 调用前检查：首版产品边界、adapter capability、templateId/field mapping、正确渠道身份、已明确的授权模式和当前业务相关性。

小程序有效绑定使用 `PROVIDER_VERIFIED_AT_SEND`：应用先检查本地模板、身份、设置/偏好和冻结字段，微信权威响应判定本次资格及结果。本地不持久化虚构 GRANTED/次数，也不把“无本地次数台账”等同于 capability 不存在。没有有效模板的类型保持 unavailable；用户在小程序完成微信订阅仍是真实收件的必要前提，后端配置不替用户订阅。

- planning 时已知产品停用、templateId 缺失、Adapter 或 permission capability 不存在：不创建该渠道 Delivery，并记录稳定 planning reason。
- Delivery 已创建后 capability 被撤销、身份解绑或 permission 失效：`SKIPPED`，不调用 provider。
- capability 已配置，但冻结 payload 违反已确认模板 contract：`FAILED`；这是实现/数据错误，不应依靠微信拒绝发现。
- templateId 目前未提供的矩阵单元统一视为“配置缺失 / unavailable”，文档、测试和默认配置不得编造 ID。

### 5.2 启用与执行证据

启用某个 NotificationType × Channel 前，必须有该渠道真实 provider contract 与验证证据：身份来源、模板字段及跳转、授权来源与作用域、有效期/撤销语义，以及是否存在次数限制。当前两个渠道均由 provider 判定实际资格和消费，不维护本地次数或预占用模型；UNKNOWN 不返还虚构额度，也不自动重发。未来确需本地额度管理时再按真实产品规则明确并发和恢复责任。

类型相关性由业务 owner 返回稳定判断，Adapter 不跨模块查 Mapper。调用前的 ownership、PROCESSING、预算及迟到结果保护遵循 [Async §7.2.1](async-processing.md#721-业务执行所有权与通知收尾)。`DELIVERED` 对外部渠道仅表示获得 provider 权威成功响应，不证明用户设备展示或用户已读。UNKNOWN 停止普通重试；启用真实发送前必须具备通知文档规定的结果收尾和受控处置能力，不以盲重发代替运维恢复。

## 6. 现有能力与演进边界

Admin QR Login v2 使用 `WxClient.generateMiniProgramCode`：使用 ma 凭据调用 `wxa/getwxacodeunlimit`，固定确认页、独立 scene、部署 env-version 与可配置 check_path，归一化有界 PNG。临时码由 admin/auth 持有短期 Redis truth，不进入 COS/Attachment 或通知链。仅明确 token 失效允许刷新一次，整个链 25 秒，无通用 POST retry；内部机制见 [内部设计](admin-qr-login.md)，环境配置及页面核对见 [运维手册](operations/runbook.md#12-admin-扫码登录运行与恢复)。AppID 直接复用 wx.ma 配置，不增设 Admin 凭据；生成码不证明页面已上传或可在真机打开。

当前 `WxClient` 已承载小程序登录/token、内容安全、服务号 token/二维码/用户信息/模板或订阅通知、OAuth 与 JS ticket 等 provider 能力；这些能力可以继续共用同一 provider client，但其应用编排仍按认证、绑定、审核、通知等不同用例分开。

本文覆盖整个微信接入的维护边界，第 1–5 节重点定义通知投递。已有登录、绑定、审核、回调与通知依所属模块维护；新增渠道须独立建立 Adapter/Capability，不能把服务号实现泛化成无差别的“微信”能力。

## 7. 配置、观测与安全

- appId、secret、templateId、回调 token 等继续使用现有配置/Secret 边界；每个 NotificationType × Channel 显式配置 template/mapping/jump target，文档和日志不记录真实值。
- 指标使用稳定低基数 `channel/result/error.category`；notificationId、openid、provider 原文不作 tag。
- 日志关联使用既有 traceId、operationId、jobId 和 deliveryId；正常逐条成功不打 INFO。
- health 只反映可观测依赖状态，不通过主动发送真实消息探活。
- 任何模板或身份映射变化都要检查 NotificationContent、Delivery 状态分类、配置、测试和 rollout；对外 HTTP 契约仅在真实接口变化时更新 contracts。

## 8. 账号、消息产品与模板维护

### 8.1 先区分渠道，再选择消息产品

`WECHAT_MINI_PROGRAM` 与 `WECHAT_OFFICIAL_ACCOUNT` 是项目投递渠道；“订阅消息”“订阅通知”“模板消息”是具体 provider 产品，不能仅凭名称中的“模板”视为同一协议。

| 渠道 / 产品 | 项目中的区分方式 | 禁止的推断 |
|---|---|---|
| 小程序 / 订阅消息 | 独立 Adapter、身份、模板及请求 DTO；有效配置满足后，以 provider 响应判定本次资格 | 小程序登录成功不等于允许发消息 |
| 服务号 / 订阅通知 | Client 产品为 `/cgi-bin/message/subscribe/bizsend`；首版发送适配固定停用 | H5 入口 success、关注和绑定不证明具体模板可发 |
| 服务号 / 模板消息 | 当前 `sendMpTemplateMessage` 调用 `/cgi-bin/message/template/send`；独立 DTO | Client 方法存在不证明账号有权限，也不是订阅通知失败后的备用发送方式 |

同一通知类型在同一渠道第一阶段明确选择一种消息产品，不在失败时自动切换产品或另一账号。此选择不新增公共 channel 枚举，也不允许绕过用户偏好或许可。当前服务号新通知设计沿用订阅通知方向；若改用模板消息，应先确认账号资格、业务场景及适用规则，再修改适配设计和验证。

### 8.2 环境账号记录

沿用 `WxProperties` 的 `wx.ma`（小程序）和 `wx.mp`（服务号），不凭文档新增配置键。每个目标环境维护一条账号记录：环境、账号别名/类型、配置引用、运营责任人、已验证能力、后台权限证据位置及核验日期。真实 secret、回调 token、EncodingAESKey、access token、session key 不进入文档或测试数据。

记录还应覆盖相关域名/回调地址、账号关联、小程序跳转环境及平台要求的网络配置；具体要求以对应能力的官方协议和账号后台为准。尚未核验项明确为 UNKNOWN。access token、OAuth token、session key、JS ticket 分别按用途管理，不能相互替代；缓存至少隔离账号和凭据用途，账号切换时检查旧缓存和在途任务。

本次没有读取账号后台或生产配置，因此账号资格、平台配额、真实模板、域名关联和环境可用性均未确认。

### 8.3 模板注册应维护什么

注册的逻辑定位为“环境/账号 + channel + 消息产品 + NotificationType”。这不是新增数据库键或现有配置 schema 声明；Registry 复用类型化配置校验与字段渲染基础；首版仅小程序 Definition/Binding 生效；不能复用服务号账号、产品、模板 ID、provider DTO 或授权事实。

每个启用项应可追溯到以下事实；可执行项留在配置/代码，审核和验证证据附在对应变更记录，不再复制一份易过期的模板 ID 清单：

- 对应业务类型、provider 产品、模板配置引用、后台模板版本/字段定义和核验日期。
- 逐字段的业务快照来源、provider keyword、类型、必填、格式、长度及缺省规则。不能用默认文案掩盖缺失的必要业务事实。
- 跳转目标、参数来源、页面所属账号与环境；用测试证明目标存在且参数含义一致。
- 许可来源、适用身份/模板范围、有效期、撤销及次数语义；由第 5.2 节要求的证据决定，不统一假设为永久布尔值。
- 真实模板标题、订阅种类、适配版本与验证记录；目录展示不构成发送许可。

小程序有效 Definition/Binding 直接参与统一目录与发送，不设置默认关闭的功能开关；个人接收意愿和微信原生订阅独立成立。服务号固定停发。

### 8.4 模板和权限变更流程

1. 确认 NotificationType、渠道、消息产品和账号实际能力，取得模板字段及许可规则证据；业务接收者不由模板维护者扩大。
2. 修改受影响配置、映射及必要契约；验证必填、长度、时间、跳转、拒绝、撤销、缺身份和未知结果等实际边界。
3. 模板替换或账号切换前盘点待发送 Delivery。明确旧配置保留、兼容迁移或受控终止方案；不能让排队消息静默采用不兼容字段或把旧模板许可转成新模板许可。需要版本持久化时按数据演进流程确定 schema，不由配置流程自行增加字段。
4. 在明确目标账号与环境中完成受控真实验证，分别记录 provider 接受、终端展示和跳转结果；错误结果分类与恢复也需有证据。通过后才启用对应单元。
5. 停用时关闭相应能力/入口并处理在途状态；停止新调用不保证撤回已发送请求。回退不重置 UNKNOWN，也不自动补发历史未规划渠道。

## 9. 能力启用与验收边界

新增通知能力启用前，先核验本项目小程序和服务号的账号能力、拟用模板、授权来源及真实通知场景，再优先接通一个 NotificationType × Channel。基础整理按 §14 独立推进，不以缺少账号后台资料为由停止所有工作。当前没有足够账号证据替用户选择“服务号模板消息替代订阅通知”。

实施分为：账号/产品核验 → 模板及权限语义确认 → 必要的客户端契约与持久模型 → Adapter/Eligibility → 受控联调 → 启用。授权次数的持久化与并发协调按真实产品语义设计，不能先造通用授权表。已有登录、绑定、审核和回调按受影响链路检查，不借本次设计全量重构。

每次能力变更记录：能力与账号别名、环境、配置/代码版本、官方来源与核验日期、后台证据位置、测试对象与结果、未完成项及责任角色。分别标明“代码存在”“本地验证”“账号能力确认”“目标环境联调”“已启用”，不使用笼统“微信已接入”。

账号后台权限、模板字段、所选消息产品的授权规则与指定环境验证分别提供证据；部署完成不等于所有渠道或模板均可用。验收结果记录在任务/发布证据中。

### 9.1 官方协议入口与更新边界

- [小程序发送订阅消息](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/mp-message-management/subscribe-message/sendMessage.html)
- [公众号订阅通知](https://developers.weixin.qq.com/doc/offiaccount/Subscription_Messages/intro.html)
- [公众号模板消息接口](https://developers.weixin.qq.com/doc/offiaccount/Message_Management/Template_Message_Interface.html)

官方来源用于核对协议；账号后台及指定环境证明实际资格。博客、旧示例或历史收件不能替代这些证据。服务号订阅通知官方入口见 §17.2。

## 10. 整个微信接入的目标结构

### 10.1 分工与依赖方向

沿用现有单体、Controller / Service / BO / Mapper 体系，确定下列职责，不按“每个微信 API 一个 Service”机械拆类：

| 所属位置 | 目标职责 | 不应承担 |
|---|---|---|
| `third/wx/config` | 账号及 provider 配置绑定、结构校验 | 查询用户、决定通知接收者 |
| `third/wx/client` | 按账号执行 provider 请求，解析协议响应；凭据获取与缓存可在确有复用时分离 | 用户表读写、通知/审核状态迁移 |
| `third/wx/crypto`、provider DTO | 验签、加解密、安全解析及微信线上的请求/响应模型 | 应用登录态、绑定和业务权限 |
| `third/wx/notice` | 无业务依赖的值格式化与 provider 结果分类 | 业务类型目录、页面路径、用户查询、Delivery 状态迁移 |
| `module/wx/notice` | 有效模板目录、字段白名单、应用跳转、冻结快照与渠道应用边界 | 业务触发、跨模块 Mapper、微信 HTTP/token 细节 |
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

通知发送沿用 §1–5 和通知文档；项目重整必须保留 provider 调用前的执行所有权/attempt 边界，不能在搬迁 `beforeProviderCall` 等接口时把真实调用放到所有权检查之前。远程 HTTP 在业务事务外，结果由 Delivery owner 条件收尾；中间层不自行创建 retry Job。provider 成功、设备展示和已读保持分离。

## 13. 验证重点

| 边界 | 验证对象 |
|---|---|
| 模块与身份 | third 层无业务持久依赖；身份读取/绑定经 user owner；冲突由数据库约束裁决 |
| OAuth / scene | claim owner、身份解析态、完成态重放、成功消费、并发与过期保护 |
| 凭据缓存 | 账号类型 + appId + 用途隔离、provider TTL、安全余量、刷新合并与条件失效 |
| JS-SDK | HTTPS origin/端口、URL 签名规范与真实客户端 URL |
| 接入与回调 | 账号路由、验签/解密、安全解析、大小限制、真实 ACK 与重投语义 |
| 内容安全 | 具名 callback、appId 校验、audit 条件收尾、重复/迟到隔离 |
| 模板与发送 | 注册唯一性、字段/跳转、冻结快照、已定义授权模式、ownership 与结果分类 |

按实际变更选择验证，源码存在、部署运行和专项 PASS 分别报告。测试命令见 commands，排障与恢复见 runbook。

## 14. 变更与兼容约束

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

实施结果和验证证据记录在对应任务或发布记录中，不要求在开发文档树新增或续写一次性报告。仍有效的设计更新本文，执行命令更新 commands，排障与恢复限制更新 runbook。真实账号记录只存别名和受控证据位置；环境状态变更及时更新该证据，不把历史报告当实时事实。

完成声明按受影响能力报告实现与验证范围；必要项 BLOCKED 时只称部分完成。“微信可用”须有对应账号、产品、类型和渠道的实际证据，不使用已结束的整理批次作为判据。

## 16. 首版通知配置与只读目录

### 16.1 唯一运行配置

`wx.notification` 经类型化绑定后由 `WxNotificationTemplateRegistry` 复制为不可变目录；能力、本人订阅目录、管理只读状态、预览和发送共用该目录。配置变更重启生效，不新增模板表、配置编辑 API 或热更新。`environment` 与账号 AppID 参与不透明 configurationRevision；凭据不进入任何目录或诊断响应。

小程序没有默认关闭的渠道、Definition、Binding 总开关或试运行名单。有效配置直接参与目录与后续合法投递，原生订阅与个人接收偏好独立成立。现有“新的评论提醒”由用户确认已运行，继续作为正式配置，不加逐类型重新验收或静态放行门槛。服务号本版固定停发，不能用环境变量、旧模板或回调启用。

Definition 配置 channel/accountRef/product/templateId/title/subscriptionKind/version/fields/miniProgramState；当前账号为 ma-primary，产品为 MINI_PROGRAM_SUBSCRIBE，凭据复用 wx.ma。title 是真实微信模板标题；用户确认当前模板均一次性，subscriptionKind 默认 ONE_TIME，明确长期模板使用 LONG_TERM，未知种类不得出现在订阅目录。miniProgramState 为 developer/trial/formal，默认 formal。Binding 配置 notificationType/channel/accountRef/definitionRef/version/fields/jumpTarget；一个类型与渠道只能有一个有效绑定。

字段来源由 `WxNoticeFieldCatalog` 白名单维护，不支持任意属性路径、表达式或脚本。33 种既有合法类型均支持冻结的 noticeTitle、noticeSummary、eventTime；互动和提醒还提供其受控摘要/时间，学习任务提供安全任务标题和业务截止。积分、审核及处理结果只允许通用安全摘要，不允许材料、审查备注、举报细节、签名 URL 或 Person 绑定证据作为模板字段。业务 owner 冻结事实，发送不回查业务主体补字段。

无效模板、映射、目标或种类只使对应类型 INVALID；其他类型与站内渠道照常工作。缺模板为 NOT_CONFIGURED，服务号为 DISABLED，管理端只返回受控 issues。真实模板ID冲突的标题/种类不能自行猜测合并，相关项判为无效。同一真实 ID 的合法复用按 ID 去重并合并覆盖类型/类别。

### 16.2 本人订阅目录与能力

`GET /v1/users/me/notification-subscription-options` 返回当前账号的有效小程序模板，支持按九类偏好 category 筛选，不按个人偏好关闭或尚无许可过滤。返回真实 title、subscriptionKind、categories、notificationTypes，以及不透明配置修订。当前集成确认的单次调用大小为 1；这不是发送次数/配额。客户端使用本机与目录上限的较小值，不混合不同种类或同名模板。目录仅供原生订阅，后端不接收任意 openid、模板 ID、GRANTED 或剩余次数。

`NotificationCapabilityQueries` 完整返回33类型×3渠道的99组合；站内 NOT_REQUIRED，小程序有效配置为 PROVIDER_VERIFIED_AT_SEND，缺身份单独报告。该状态不表示永久授权，微信实际响应判定该次资格。明确拒绝终结本次投递，UNKNOWN 不普通重试、不切换服务号、不自动写已读。

### 16.3 冻结快照与恢复

Notification、初始 Delivery 与既有 v2 Job 在同一事务接受。外部快照冻结 templateId、字段、账号/环境、Definition/Binding 引用与版本和 originating notificationId；重试不重新渲染或换模板。当前绑定无效、账号变化、个人偏好关闭或业务资格失效时停止未开始的投递。积分与学习任务在发送前由各自 owner 复核当前 Person 绑定及账号，冻结证据不能授予新绑定用户原通知。

INBOX/SEMANTIC_TARGET 使用同一 notificationId 的消息恢复入口；只有已知公开目标才直接映射页面，不发明积分详情路由。已删除/到期的站内资格项不能借外部入口绕过读取规则；微信接受不写 readAt。

### 16.6 WeChat Notification Template V1 实施边界

离线 fixture 为 [配置](../src/test/resources/wechat-template-preview.yaml) 与 [模拟 Notification](../src/test/resources/wechat-template-preview.json)，字段长度是合成测试规则，不作为平台证明。CLI 使用同一 Registry/Renderer，不读账号凭据或发送消息。命令见 [commands](../governance/commands.md#微信模板离线预览)。

后续增加已有类型的模板，只需补真实模板信息、字段/跳转映射；已存在的33种业务生产链不再增加外部渠道放行代码。新业务事件、接收人或新的安全字段仍需各 owner 明确，不能仅由模板配置创造。

### 16.7 帖子一级评论模板

`application.yaml` 的 mini-post-comment 绑定 POST_COMMENTED。真实模板标题为“新的评论提醒”，一次性；ID 以配置为准。一级评论首次公开后通知帖子作者，排除自己评论自己，二级回复不借此模板发送。thing15=原帖正文摘要 originalContent，thing5=replyUserName，thing2=replyContent，time3=评论 createdAt 对应 replyTime；文本采用20个 Unicode 字符摘要，时间按 Asia/Shanghai 渲染，缺文本时使用配置中的明确查看提示。跳转 POST_DETAIL，保留 postId 和同一 notificationId。

个人接收选择使用 COMMENT × Channel 统一偏好，不读取或导入旧 notifyComment。测试账户先保存 COMMENT × WECHAT_MINI_PROGRAM=true 并在同 AppID 小程序原生订阅；用该账户发帖、另一账户发表一级评论。无需通知总开关；本地开发版跳转可设置 WX_NOTIFICATION_MINI_PROGRAM_STATE=developer，正式默认 formal。其他模板无配置时仍不发送，不复活旧 SKIPPED/FAILED/UNKNOWN 或补发历史。

## 17. 服务号首版停用

服务号通知在首版固定停发；所有33项能力明确 CHANNEL_DISABLED，订阅目录不包含服务号。未开始的投递执行前安全 SKIPPED；已开始的调用如实收尾，UNKNOWN 不盲重试。已有身份、历史偏好与通知保留，不用旧 H5 结果或环境变量恢复发送。服务号未来恢复须另行明确产品范围，不提供本版配置编辑、发送或重试入口。
