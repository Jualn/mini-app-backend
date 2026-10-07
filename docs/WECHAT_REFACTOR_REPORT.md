# 微信集成基础整理实施报告

本报告记录 2026-09-29 工作区的 W0–W3 实施结果。设计和规则仍以 [WeChat Integration](wechat-integration.md) 为准；本文不是第二份规范，也不表示目标环境或真实微信已接通。

更新说明：§1–4 为第一批整理的历史记录，其中“legacy 固定关闭/官方页面无法取得”的状态已被本报告 §5 的用户确认、官方证据及恢复实现取代；其他未验证环境边界不变。

## 1. W0 当前清单

| 能力/入口 | 当前调用与 owner | 本轮判断 |
|---|---|---|
| 小程序登录 | `AuthServiceImpl -> WxClient.getMiniSession -> UserService` | 既有路径保留；真实 code/账号未验证 |
| 服务号扫码/OAuth 绑定 | `/v1/wx/bind/**`、`/third/wx/mp-oauth/**` → `module/wx/service` → `UserService` | 已整理；身份唯一迁移和 Redis 原子机制仍需真实基础设施验证 |
| JS-SDK | `/third/wx/js-sdk-config` → `WxJsSdkService` → `WxClient` | 已加 origin 边界；官方 URL 规范和真实域名签名未验证 |
| 微信回调 | `/v1/wx/{mp|ma}/callback` → `WxCallbackService` → `WxEventService` → handler | 已按账号路由并收紧失败 ACK；官方 ACK/重投规则未取得正文 |
| 内容安全回调 | `ma:event:wxa_media_check` → 具名 `WxaMediaCheckMessage` → `AuditService` | 已取消 raw XML 二次解析并校验 appId；真实回调/迟到重投未验证 |
| 服务号订阅入口 | `/third/wx/mp-notice-subscribe/**` | 结果仅是入口操作线索，不写成 permission |
| 微信通知发送 | legacy Delivery/Job → `WxMpNoticeSendService` → `WxClient` | permission capability 缺失，已在 provider 前关闭；canonical 两渠道继续 unavailable |
| 模板配置 | `wx.mp.notice-templates` → `WxMpNoticeTemplateRegistry` | 已加单一 type 和启用配置校验；真实模板/账号资格未知 |
| 凭据 | `WxClient` + Redis | 已按 `账号类型 + appId + 用途` 分键并采用 `expires_in`；只实现单实例刷新合并 |
| 旧 payload | `NotifyPayload.wxData`、具名 `NoticeData` | 保留兼容 reader；微信层不再查询 Activity Mapper 补字段 |

现有公开 URL、请求/响应形状和既有配置键未改变。本轮新增的部署事实只有 Flyway `V23`，没有执行迁移、部署、真实发送或共享环境写入。

## 2. 实施与证据

| 批次/能力 | 规范章节 | 实现入口与状态 owner | 兼容处理 | 实际验证与证据 | 状态 | 剩余项/所缺输入 |
|---|---|---|---|---|---|---|
| W1 模块/身份边界 | §10、§11.2 | HTTP/绑定/OAuth/发送编排移入 `module/wx`；身份读写归 `UserService` | URL、JSON、配置键不变 | 静态依赖检查：`third/wx` 无 `module/*` import；定向测试 | PASS（本地） | 真实 DB 迁移见下项 |
| W1 旧通知快照 | §4、§10.2、§14 | `ActivityRemindBuilder` 仅消费冻结 payload | 保留 `NoticeData` 多态 reader，不删除历史 payload | 主代码编译、模板/Delivery 测试 | PASS（本地） | 目标环境 backlog 未盘点，旧 reader 不移除 |
| W2 服务号身份唯一性 | §2、§11.2、§12.1 | `UserService.bindOfficialAccountIdentity` + V23 unique key | 同一身份幂等；替换/跨用户争用拒绝，不自动合并 | 3 个 owner 单测；preflight SQL 静态检查 | PARTIAL | BLOCKED：目标 MySQL duplicate/empty preflight、V23 migrate/validate 未运行 |
| W2 OAuth/scene 生命周期 | §12.1 | Redis claim owner、解析身份中间态、完成 redirect 重放；scene 成功后删除 | 保留原 URL/state 参数；provider 换码后本地失败可从解析态继续 | OAuth/扫码单测 | PARTIAL | BLOCKED：真实 Redis 的 SET NX/Lua、并发、过期/进程崩溃未验证；真实 code 单次语义未联调 |
| W2 凭据缓存 | §11.1 | `WxClient` 动态 key、provider TTL、安全余量、单实例 monitor、旧 token 条件刷新 | 旧静态 key 自然过期，不清空 Redis | Java 17 主代码编译；源码路径检查 | PARTIAL | BLOCKED：真实 Redis TTL/并发；部署实例数及是否需要跨实例协调未知 |
| W2 JS-SDK URL | §12.1 | `WxJsSdkService` 同 HTTPS origin/端口，拒绝 userinfo/fragment | 原 endpoint/VO 保留 | 2 个 URL 边界测试 | PARTIAL | BLOCKED：官方页面正文和目标域名真实签名/前端 `location.href` 证据缺失 |
| W2 回调接入 | §12.2 | body 256 KiB 边界；安全 XML；账号 handler key；失败返回 `FAIL`；小程序 appId 校验 | 原 callback URL 保留；未知但有效事件仍明确忽略并 success | 路由、畸形 XML、XXE、签名、过大 body、handler 失败测试 | PARTIAL | BLOCKED：账号后台明文/兼容/安全模式、官方 ACK/重投窗口、真实加密 fixture |
| W2 内容安全 | §12.2–12.3 | 接入层一次解析为 `WxaMediaCheckMessage`，audit 继续拥有条件完成/Outbox | audit 状态机和 trace reader 未改 | typed callback 单测；既有 audit 源码核对 | PARTIAL | BLOCKED：真实回调、重复/迟到/版本行为的 DB 机制测试未运行 |
| W2 模板配置 | §8、§11.1 | `WxMpNoticeTemplateRegistry` 启动索引和一致性校验 | 原 `wx.mp.notice-templates` schema 保留 | duplicate/disabled/invalid enabled 测试 | PASS（本地） | 真实模板字段、长度、跳转和账号产品资格未知 |
| W3 permission/发送门禁 | §3、§5、§8 | `WxMpNoticeSendService` capability=false；notify legacy/canonical 均不在缺 permission 时调用 provider | 不制造历史 Delivery，不重置 UNKNOWN，不补发；已有 Delivery 转 SKIPPED | 8 个 Delivery 测试包含不规划/已有项跳过 | PASS（本地门禁） | BLOCKED：许可来源、模板作用域、有效期/撤销/次数/Unknown Outcome 规则 |

## 3. 验证结果

- **PASS — V0 主代码编译**：使用 `D:\Jualn\Java\jdk-17.0.18+8` 和仓库 Maven Wrapper 执行 `compile`；703 个 main source 编译成功。MapStruct 仍有本任务外的既有 unmapped warnings。
- **PASS — V1/V2 定向行为**：通过隔离 testIncludes 执行 10 个相关测试类、31 个测试，0 failure / 0 error / 0 skipped。覆盖身份冲突、OAuth replay、账号路由、失败 ACK、恶意 XML、URL origin、模板歧义及缺 permission 不发送。
- **BLOCKED — 常规 Maven focused test**：Surefire 在运行目标前会编译全部 test source；当前被未修改的 `InteractServiceImplTest` 旧构造参数和已移除 `RedisKeyConstant.viewCount` 引用阻塞。本轮未扩大修复。
- **NOT RUN — V3 Redis/MySQL**：没有连接或修改本地/共享数据库、Redis；V23、Lua owner delete、TTL、并发和崩溃恢复仅有代码/单测证据。
- **NOT RUN — repository-wide/CI/deploy**：未执行全仓测试、Flyway CI、打包发布或部署。
- **NOT RUN — 真实微信**：没有使用真实 appId/secret/token/templateId/code/openid，没有发送消息或接收目标环境回调。

第一次编译使用机器默认 JDK 24，被旧 Lombok/Javac 兼容问题阻塞；按项目 Java 17 基线切换到已安装 JDK 17 后主代码编译通过。这不是源代码失败。

## 4. W4 与协议阻塞项

以下输入缺失，不影响上述本地整理，但阻止宣称“微信可用”：

1. 小程序/服务号账号别名、目标环境、后台已开通能力、回调模式、域名/网络配置及受控证据位置。
2. 选定的消息产品、真实模板字段/版本/跳转、授权来源与可信回传、有效期/撤销、次数消费和 Unknown Outcome 规则。
3. 微信官方页面可访问的协议正文或受控快照，用于确认 callback ACK/重投、OAuth code/state、JS-SDK URL、token 失效码及发送错误分类。本轮官方开发者页面仍无法取得正文，未用博客替代。
4. 目标 MySQL 的 `wechat_identity_preflight.sql` 结果、备份/发布窗口与 V23 migrate/validate 授权。
5. 目标 Redis 拓扑和应用实例数，用于决定单实例刷新合并是否足够，以及 OAuth claim 的真实并发/故障验证环境。

## 5. 配置专项与既有服务号恢复（2026-09-29）

用户确认曾收到服务号订阅通知，并允许选择先沿用微信校验授权。已取得官方服务号订阅介绍、发送 API 与事件推送正文；具体恢复边界与来源见微信设计 §17。此处不将历史收件当作当前线上已验证。

| 改动 | 实现与保证 | 验证/剩余边界 |
|---|---|---|
| legacy 服务号发送 | 移除固定 false 的 permission 方法，以 `isTemplateAvailable(type)` 表示真实模板配置就绪；微信校验实际订阅资格，不造本地许可次数 | 本地规划与发送测试；canonical 两渠道及 code 8–18 没有因此开启 |
| 模板配置 | 允许不跳转/站内相对页面，拒绝外部 URL、必填/default 冲突和时间截断；保留原配置键、模板 ID、开关及用户设置 | Spring Binder 读取仓库四项配置并验证字段/跳转渲染；真实 profile/部署覆盖未验证 |
| 冻结内容 | 支持 JSON 数组/ISO 日期时间，非法时间显式失败，文本按 Unicode 码点截断，路径参数编码 | Renderer 5 个定向测试；不宣称完整微信字段语义验证 |
| provider 结果 | errcode 缺失/空响应不再视为成功，provider 明确拒绝终局；发送后本地异常不重置 PENDING | Delivery/send Service 的失败测试；真实网络/MySQL 仍未验证 |
| v1 兼容 | 保留旧 payload reader，缺 Delivery 尝试历史的任务进入现有 UnknownOutcome 处置，不随发送恢复自动重放 | 单独 legacy reader/classifier 测试；实际 backlog/preflight 仍待环境核验 |

本次没有变更公开 HTTP 请求/响应、canonical capability/permission 协议，没有新增 schema 或修改既有 V23。基础 application.yaml 只移除两个被 required=true 遮蔽的默认值，不更换真实模板 ID。先前其他任务的工作区变更保留。

已执行命令使用仓库 Wrapper、`D:\Jualn\Java\jdk-17.0.18+8` 和现有隔离专项脚本：

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' -TestNames WxMpNoticeTemplateRegistryTest,WxMpNoticeFieldRendererTest,WxMpNoticeSendServiceTest,NotificationDeliveryServiceImplTest
```

PASS：703 个 main source 编译；上述 4 类 26 个测试，0 failure/error/skipped。Mock provider/Mapper 测试只证明本地发送及结果处理逻辑，未联系微信、DB 或 Redis。既有 MapStruct warning 未扩大修复。

旧 v1 reader 的后续验证命令：

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' -TestNames LegacyNotificationDeliveryJobHandlerTest
```

PASS：旧 v1 payload 读取与 UnknownOutcome 分类 1 个测试，0 failure/error/skipped；本次两次定向运行共 27 个测试通过。

真实微信发送、终端展示、迁移、部署、生产配置和全仓/CI 均 NOT RUN。剩余必需的上线证明：核验目标账号/模板仍有效、当前配置与部署版本、v1 backlog，以及指定测试接收者的受控发送结果。不能在上述证明缺失时把“本地恢复”写成“生产恢复”。

因此本轮结论是：**本地基础整理部分完成，真实账号/协议/环境能力 BLOCKED，微信接通与环境启用均 NOT RUN。**
