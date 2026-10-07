# Admin QR Login v2 Backend Internal Design

设计日期：2026-10-07。状态：**READY_FOR_IMPLEMENTATION**，仅表示内部设计可进入编码，不表示实现、微信集成或上线验收完成。

2026-10-07 后续实现已交付，用户确认 AppID/确认页后配置已默认启用，见 [实现与验证记录](admin-qr-login-v2-implementation.md)。用户随后确认旧方案未上线并授权移除，当前单一新流程见 §13。下文的源码现状、待实现清单与本轮结论保留为设计时点证据，不作为当前实现状态或上线完成声明。

## 1. Context 与权威边界

本设计兑现已经 READY 的 [Admin QR Login v2 Contract](../../contracts/docs/coordination/admin-qr-login.md)。后续实施必须同时读取 Contract、本设计和项目规范；本文件不拥有对外 API、状态、错误或三端启动协议。Java 17、Spring Boot 3.5.13、现有 Sa-Token 1.45.0、MyBatis、MySQL 和 Redis 不变。

旧二维码是 `jualn-admin-login:` 加随机 sessionId，**不是最终 Admin token**。升级解决微信扫一扫进入小程序确认页、真实扫码绑定、公开/私有凭证隔离，以及旧 GET 领取丢结果无法恢复的问题；不得将旧版描述为直接泄露管理员 token。

依据：[架构](architecture.md)、[Reliability](reliability.md)、[Observability](observability.md)、[WeChat Integration](wechat-integration.md)、[Async](async-processing.md)、三份 [Backend](../standards/backend-engineering.md) / [Java](../standards/java-engineering.md) / [Spring](../standards/spring-boot-engineering.md) 标准及 [commands](../governance/commands.md)。通用规则不在这里复制。

### 1.1 Contract 修订定位

当前 contracts 无可报告的 Git commit/tag。本次完整读取 canonical operation、schema（含 examples）、security schemes、Problem Details、coordination、Backend Handoff、compatibility/migration 后，以以下 SHA-256 固定本次输入。实施者必须核对同一修订；若变化，重新审查受影响部分，不自行改 Contract。

以下散列为原设计修订快照；协调文档随后按用户的未上线移除决定作说明性更新，八操作 paths/schema 未变。

| 相对 contracts 的文件 | SHA-256 |
|---|---|
| `api/openapi.yaml` | `E34F64250B145440D1D1EE5FB08606221448CF76CC14A521E0F8CE005B4DA9EB` |
| `api/paths/admin-qr-login.yaml` | `7110D9D92F551E4D6B843DC6F84D506284D90C311A15E1F11168985D42EAD4FF` |
| `api/schemas/admin-qr-login.yaml` | `D98F2371551CCA79050BF964E51FB3B1994F3C1632850D6FAB02C40AE8F3C2FB` |
| `docs/coordination/admin-qr-login.md` | `2CE8B20B20F9A4315BCE8723D5C53921591D37076DAADFC74E539B23FA6375B3` |

共享 ResourceId、ProblemDetails、ValidationProblem 和 root responses 通过 canonical entry 引用；实施时仍需读取完整入口，不把上述散列当作所有依赖的独立 bundle。

### 1.2 已确定的外部事实

最终名称是 `sessionId`、`sceneCode`、`pollSecret`，私有 Header 是 `X-Admin-Login-Secret`。scene 为独立 192-bit 随机值的 32 字符 Base64URL；pollSecret 为独立 256-bit 随机值的 43 字符 Base64URL。sessionId 只标识资源。

**first authenticated scan wins**，不是 first confirm wins。必须先 scan 才能 confirm；其他 subject 不能读取绑定后的状态、确认或拒绝。状态为 PENDING、SCANNED、CONFIRMED、CONSUMED、EXPIRED、REJECTED、CANCELLED；后四者终态。CONFIRMED 不是已登录。

有效期固定、最多五分钟；终态至少保留至 expiresAt 后五分钟。consume 在有效期内凭相同私有凭证返回同一个 token/profile；到期不重放、不换 token、不续期。新响应直接业务对象，错误使用 `/problems/...` 的 type，**没有新增 ErrorCode enum**。全部成功/错误响应 `Cache-Control: no-store`。

手机页为 `subpkg_setting/pages/admin-login-confirm/index`；微信 scene 仅是 sceneCode，无前缀、无键值包装。页面/环境发布是启用门槛，不由后端动态接受客户端指定。

## 2. Current Backend：源码证据

以下为当前本地工作树事实，包含用户已有改动；没有推断生产部署或运行配置。

| 边界 | 实际入口与当前行为 | v2 复用/差距 |
|---|---|---|
| Admin HTTP | [AdminAuthController](../src/main/java/cn/jualn/miniapp/module/admin/auth/controller/AdminAuthController.java)：POST `/v1/admin/auth/qr-sessions`、GET/DELETE `qr-sessions/{sessionId}`、POST `qr-confirmations/{sessionId}`、GET me、POST logout | 旧 Result envelope 与 routes 保持；v2 新 Controller |
| 旧编排 | `AdminAuthServiceImpl` (设计时点历史源码，已移除)：16-byte sessionId、32-byte pollSecret、create/confirm 限流、权限检查、GET claim 后 issue | 没有 scan/reject/独立 consume；不能搬用 GET 副作用 |
| 旧存储 | [AdminAuthStore](../src/main/java/cn/jualn/miniapp/infrastructure/cache/AdminAuthStore.java)：StringRedisTemplate + Lua，`jualn:admin:qr-login:`、`jualn:admin:auth:rate:`、`jualn:admin:auth:revoked:` | secret hash 比较可借鉴；claim/cancel 删除；poll 到期删除，保留机制不满足 v2 |
| 旧状态/期限 | `旧 enum` (设计时点历史源码，已移除)、[配置](../src/main/java/cn/jualn/miniapp/module/admin/auth/config/AdminAuthProperties.java)：PENDING/SCANNED/CONFIRMED/DENIED/EXPIRED；默认 2m、保留 1m、poll 1500ms、Admin token 8h | SCANNED 无实际 scan writer；DENIED 不是 v2 REJECTED；新配置不能改变旧 wire |
| 管理身份 | [AdminStpUtil](../src/main/java/cn/jualn/miniapp/common/security/AdminStpUtil.java) `StpLogicJwtForSimple("admin")`；[AdminTokenService](../src/main/java/cn/jualn/miniapp/module/admin/auth/service/AdminTokenService.java) login、revocation blacklist、当前用户重新准入、logout | 复用同一内部 userId、同一 admin loginType 与登录/注销路径；旧 issue 返回带前缀 Authorization 值 |
| 授权 | [AdminPermissionPolicy](../src/main/java/cn/jualn/miniapp/module/admin/auth/support/AdminPermissionPolicy.java)：NORMAL 且 OPR/ADMIN；[SaPermissionProvider](../src/main/java/cn/jualn/miniapp/config/SaPermissionProvider.java) 动态读取 user profile | 没有独立 AdminUser 表；不得使用公开 isPlatformOperator 或旧 session role 快照作准入 |
| Mini 身份 | [AuthController](../src/main/java/cn/jualn/miniapp/module/auth/controller/AuthController.java) → [AuthServiceImpl](../src/main/java/cn/jualn/miniapp/module/auth/service/impl/AuthServiceImpl.java) → WxClient.getMiniSession(code).openid → [UserServiceImpl.getUserInfo](../src/main/java/cn/jualn/miniapp/module/user/service/impl/UserServiceImpl.java) → user_profile.openid 查询/事务创建 → assertLoginAllowed → 普通 StpUtil.login | 普通 token 映射内部 userId；手机 Controller 直接从普通 StpUtil 取 subject，不能接受客户端身份 |
| unionid / 绑定 | [MiniSessionResponse](../src/main/java/cn/jualn/miniapp/third/wx/dto/MiniSessionResponse.java) 有 unionid；当前登录仅取 openid，首次注册不写 unionid；[WxBindService](../src/main/java/cn/jualn/miniapp/module/wx/service/WxBindService.java) 用临时 scene 关联服务号身份 | unionid 不是 QR 登录必需映射。未找到独立 bind-phone 登录链；不新增手机号/服务号登录依赖 |
| 当前 profile | UserService.getUserProfile 直接 Mapper.selectById → BO，无该读取路径的 Redis profile cache | 授权重新读取可直接复用；只将 USER_NOT_FOUND 视为准入失败，不吞掉其他业务/基础设施错误 |
| Web 安全 | [WebMvcConfig](../src/main/java/cn/jualn/miniapp/config/WebMvcConfig.java) 仅特判旧手机确认与匿名旧 Web routes；[ContextInterceptor](../src/main/java/cn/jualn/miniapp/common/interceptor/ContextInterceptor.java) 先普通后 admin | 新 mobile 路由必须精确区分；私有 Web routes 不要求已有 Admin token。CORS 已 allowedHeaders(*)，但未 expose Retry-After |
| Redis / DB | [RedisService](../src/main/java/cn/jualn/miniapp/infrastructure/cache/RedisService.java)、[RedisConfig](../src/main/java/cn/jualn/miniapp/config/RedisConfig.java)、[RedisKeyConstant](../src/main/java/cn/jualn/miniapp/common/constant/RedisKeyConstant.java)；MyBatis Plus + XML + Flyway | 普通缓存会吞 Redis 故障/返回 miss，不能用于认证 truth。专用 store 采用既有严格 AdminAuthStore 模式；键常量集中登记 |
| 微信 | [WxClient](../src/main/java/cn/jualn/miniapp/third/wx/client/WxClient.java)：小程序登录、独立 ma/mp access token cache、单 JVM monitor、token 失效刷新一次；有服务号 `cgi-bin/qrcode/create` | **没有**小程序 `wxa/getwxacodeunlimit` 能力；不能用服务号 ticket QR 替代 |
| HTTP client | [WebClientConfig](../src/main/java/cn/jualn/miniapp/config/WebClientConfig.java)：connect 5s、response/read/write 10s、body 2MiB；URL 日志去 query | 复用 Client/连接池；新增二进制/JSON 错误辨识与整体 deadline，无 SDK/Feign 新依赖 |
| File/COS | [CosService](../src/main/java/cn/jualn/miniapp/third/cos/service/CosService.java) / [MediaUploadRecordService](../src/main/java/cn/jualn/miniapp/module/media/service/MediaUploadRecordService.java)：STS、objectKey、PENDING/BOUND/CLEANING、持久清理 | 临时认证 PNG 不进入 Attachment/媒体审核/上传清理链 |
| 可观测性 | [RequestLogFilter](../src/main/java/cn/jualn/miniapp/common/filter/RequestLogFilter.java)、[ObservabilityContext](../src/main/java/cn/jualn/miniapp/common/observability/ObservabilityContext.java)、[GlobalExceptionHandler](../src/main/java/cn/jualn/miniapp/common/exception/GlobalExceptionHandler.java)、Actuator/Micrometer | 复用 trace、Problem Details、registry；不新增日志框架 |
| 审计/测试 | [AdminOperationLogServiceImpl](../src/main/java/cn/jualn/miniapp/module/admin/operation/service/impl/AdminOperationLogServiceImpl.java) 目前只写角色变更；`AdminAuthServiceImplTest` (设计时点历史源码，已移除) 使用 mock store | 没有已实现的登录安全审计；旧 mock 测试不证明 Lua/并发/恢复 |

### 2.1 Sa-Token 的真实副作用（决定 consume 的依据）

不能采用 SaTokenConfig 注释中的“Simple 无状态、不存 token”描述。对本地 Maven cache 锁定的 **1.45.0** jar 用 Java 17 javap 检查：

- `StpLogicJwtForSimple.createTokenValue` 只替换 token 风格，支持 extra；它没有覆写基类的 login/session/mapping 生命周期。
- `StpLogic.createLoginSession` 写账号 SaSession/terminal、token→loginId mapping、可选 token session，并触发 login listener；`getLoginIdNotHandle` 通过 SaTokenDao.get(mappingKey) 查询。
- `SaLoginParameter` 支持 `setToken` 指定 token；SaSession.addTerminal 按 token 替换已有 terminal，重复会更新历史计数，不等同无副作用。
- Simple 的四参数 createTokenValue 没有自动将 timeout 写入 JWT 的 eff；有效期限由既有 Sa-Token Redis mapping 管理。v2 额外保存固定绝对 tokenExpiresAt，不能通过重复 login 延长它。

[官方 JWT 集成文档](https://sa-token.com/plugin/jwt-extend.html) 也区分 Simple 与 Stateless；在线文档目前示例为 1.46.0，不作为升级授权，本设计依赖本地 1.45.0 证据。源码未发现项目自定义 SaTokenListener。将来新增 listener 时必须兼容固定 token 恢复，不能借其发送非幂等通知/审计。

## 3. Target Architecture 与对象边界

下面带“新增”的名称是目标职责，不声称它们已经存在。不新增第二套 AdminAuth、通用 QR 框架或 Repository 接口套壳。

```text
新增 AdminQrLoginController（HTTP/DTO/VO，Web 与手机精确路由）
  → 新增 AdminQrLoginService / impl（唯一 v2 状态与用例 owner）
    → 新增 AdminQrLoginStore（infrastructure/cache，严格 Redis/Lua）
    → UserService.getUserProfile → AdminPermissionPolicy（已有）
    → WxClient.generateMiniProgramCode（新增 provider 方法，纯 page/scene→PNG）
    → AdminTokenService（已有，新增固定候选/恢复/验证能力）
      → AdminStpUtil.STP_LOGIC / Sa-TokenDao（已有 admin success path）
```

Control Flow：

1. create：Controller → Service → credentials → WxClient ma token/微信码 → store 原子发布 session+scene index+PNG → Converter → 201。
2. scan：Controller 普通 StpUtil authenticated subject → Service → store scene resolution/subject CAS → BO → VO。scan 不查管理准入、不产生 token。
3. confirm：Controller 普通 subject → Service → store 验证绑定/状态 → UserService → PermissionPolicy → store CAS SCANNED→CONFIRMED 或 REJECTED → 手机 VO。
4. query/code/cancel：Controller 私有 Header → Service → store ownership+状态检查 → BO/bytes → HTTP。GET 不领取、不签发；期限投影允许 lazy expiry。
5. consume：Service ownership+CONFIRMED → 当前准入 → AdminTokenService 准备候选 → store 固定候选 → AdminTokenService 恢复**同 token**的既有登录 → store 原子 activation+CONSUMED → 当前准入/token 有效性检查 → 冻结 BO → 裸 token/profile VO。

Data Flow：普通 token→服务端 userId；sceneHash→sessionId；pollSecretHash 只校验 Web 所有权；session.boundUserId→UserService 最新 BO→权限策略；consume 固定 token/profile→私有 Web。phone、openid、unionid 不存入 LoginSession；scene/pollSecret/token 不进入 MDC、日志或 metrics。

### 3.1 Package/Class 清单

| 位置（根包 cn.jualn.miniapp） | 目标职责 |
|---|---|
| `module/admin/auth/controller/AdminQrLoginController` | 新八个 operation 的 HTTP 适配，可分 Web/mobile 方法但无需两个空 Controller |
| `module/admin/auth/dto/qrlogin/`、`vo/qrlogin/`、`bo/qrlogin/` | 严格 scene request，具名 Created/Session/Scan/LoginResult；Service 不返回 VO |
| `module/admin/auth/converter/AdminQrLoginConverter` | BO→canonical 投影；终态省略规则、固定 ADMIN_WEB、裸 token 表示 |
| `module/admin/auth/service/AdminQrLoginService`、`impl/AdminQrLoginServiceImpl` | 状态、权限、恢复、限流编排；保留旧 AdminAuthService |
| `module/admin/auth/enums/AdminQrLoginV2Status` | 独立七状态，不修改 legacy enum |
| `module/admin/auth/config/AdminQrLoginProperties` | `admin-auth.qr-login-v2`；避免复用旧 1m retention |
| `module/admin/auth/support/AdminAuthCrypto`（扩展已有） | 复用 SecureRandom/Base64URL/SHA-256；固定长度安全比较，无 Utils/Manager 新类 |
| `infrastructure/cache/AdminQrLoginStore` | 一个具体 store，无预建 Repository interface；内部 typed record 可以嵌套，业务 BO 由 auth owner 定义 |
| `module/admin/auth/service/AdminTokenService`（扩展已有） | candidate、固定 token materialization、显式 token validation、激活检查及 logout 配合；旧 issue 外观不变 |
| `third/wx/client/WxClient`、`third/wx/dto/`（扩展已有） | ma 小程序码协议 DTO/二进制响应与脱敏错误；不依赖 auth model |
| `common/constant/RedisKeyConstant`、`config/WebMvcConfig` | 新 key factories、精确安全/CORS/no-store；不改普通登录 |

不新增 scheduled cleanup：临时 session/index/PNG/gate 都由 Redis TTL 清理。PNG 放 store 的独立 binary value；不能用带 Java 类型信息的 JSON serializer 序列化原始图像。

## 4. Storage Decision：Redis 为短期 Truth

| 方案 | 评价 |
|---|---|
| Redis | 短期状态/高频 poll/TTL/单主 Lua 合适；需明确保留、严格失败和候选恢复；当前 Admin 登录本身依赖 Redis |
| MySQL | 条件 UPDATE 可行，但临时数据/PNG清理、poll成本增加；不能用 DB transaction 原子覆盖 Sa-Token Redis 登录；仍须恢复协议 |
| Redis + MySQL | 没有需跨 Redis 丢失保留的 QR 业务历史/安全审计规格，双 truth 带来复制与恢复问题，无实际收益 |

**选择 Redis，不建表、不写 Flyway migration。** 普通用户准入事实仍为 MySQL user_profile，Admin 登录的 mapping/session/revocation 仍归既有 Sa-Token/AdminTokenService。QR truth 是短生命周期能力，故障时允许用户重新扫码；它不是普通 read-through cache，禁止从 DB/进程内重建丢失会话或降级授权。

| 项 | Owner |
|---|---|
| Source of Truth | v2 session + scene index + 固定候选/结果 + activation，同一 Redis primary |
| Runtime Replica | 无；进程内只短暂持有请求工作数据，不能恢复授权 |
| TTL owner | AdminQrLoginStore 创建时设置绝对期限；Sa-Token owner 管理正常登录 TTL |
| Cleanup owner | Redis TTL；未激活的 Sa-Token 残留 mapping/session 按既有 TTL，Service 尽力清理单候选 token |

当前应用 YAML 是 host/port Redis 配置，未见 cluster 配置。**本方案依赖同一个非 Cluster Redis primary**：finalize 脚本需要检查既有 Sa-Token mapping/revocation key，不能与当前不同 hash slot 的 Sa-Token keys 在 Redis Cluster 执行。实施时校验实际 connection factory；发现 Cluster 时 BLOCKED，不改写框架 key 或假称支持 Cluster。目标环境 Redis/SaTokenDao 必须指向同实例同 database；运行验证之前不声称已满足。

### 4.1 Redis key/value schema

新 factories 集中到 RedisKeyConstant；沿用 auth 的 `jualn:admin:` 家族并显式 v2 隔离，不能改 legacy prefixes。

| Key | Value / 物理删除时刻 |
|---|---|
| `jualn:admin:qr-login:v2:session:{sessionId}` | Hash，下节模型；expiresAt + 5m |
| `jualn:admin:qr-login:v2:scene:{sha256(sceneCode)}` | sessionId；expiresAt + 5m；create 原子 NX 检查碰撞 |
| `jualn:admin:qr-login:v2:image:{sessionId}` | 原始 PNG bytes，专用 byte[] Redis serializer；expiresAt |
| `jualn:admin:qr-login:v2:activation:{sessionId}` | tokenHash、userId、tokenExpiresAt；仅 finalize 创建；tokenExpiresAt 时删除 |
| `jualn:admin:auth:rate:v2:{operation}:{discriminatorHash}` | 原子计数+固定 window TTL；返回剩余 TTL 供 Retry-After |

不要把 hash、PNG 和通用 RedisService 混用；Lua 处理 typed return code，严格区分 absence 与 transport failure。访问 scene key 是 hash lookup，不存 scene 原文。随机碰撞最多重试三次，本地重新生成三个独立值及图像；连续碰撞为内部故障。

每个 script 在写前校验所有 key type、required fields、数值范围和参数；Redis Lua 执行中错误**不自动回滚已执行命令**，不能把任意半写当事务。create/finalize 只使用预检查通过的固定写序列，并测试 WRONGTYPE/corrupt value，不在 script 内做动态未知操作。

### 4.2 LoginSession 最小模型

| 字段 | 必要性/写 owner |
|---|---|
| schemaVersion=1 | 识别部署期间 value 格式；未知版 fail closed，不当 PENDING |
| status、createdAt、expiresAt、pollIntervalMs | Lua TIME 统一时钟；固定期限/响应快照 |
| sceneHash、pollSecretHash | 两个独立 SHA-256；不存 raw；scene index 指向此 record 并验证一致性 |
| boundUserId、scannedAt | 第一次 scan 原子写，永不转让；userId 是内部服务端 subject |
| confirmedAt | 成功确认写；只在 CONFIRMED/CONSUMED 投影 |
| candidateToken、candidateTokenHash、candidateProfileJson、tokenExpiresAt | 首次 consume reservation 固定，后续只恢复这一组；profile 为 auth BO 的 canonical 快照 |
| consumedAt | finalize 写，与 activation 同一原子承诺 |

sessionId 在 key 中，不再重复持久化；confirmedBy/scannedBy 可由不可变 boundUserId 表达，无需三份身份。无独立 grantId（sessionId 即 consume 范围）、version、lease、owner、outbox/job ID 或拒绝详情。单向状态+不可变 candidateHash 足够 CAS。

candidateToken **不能只存 hash**，因为同次响应恢复必须返回原值。按既有 Sa-Token credential 存储等级保护 Redis ACL、网络、备份及受控运维读取；仅 session 保留期内存 token/profile，activation 长期只保存 hash/userId。没有现成认证结果加密 abstraction，首版不增加独立加密密钥管理；如部署安全政策要求应用层 encryption，须在启用前补齐该边界，不能借日志存 token。

## 5. Credentials

复用 AdminAuthCrypto：分别 SecureRandom 生成 sessionId 16 bytes→22 chars、sceneCode 24 bytes→32 chars、pollSecret 32 bytes→43 chars；三次独立取样，不相互推导，不含 user/时间/主键。sessionId 输出符合 ResourceId，它不是所有权 secret。

scene 和 pollSecret 按 UTF-8 SHA-256 存固定 32-byte digest（可编码为固定 64-char hex）。无需 password KDF：输入已具有足够随机性。Java 用 MessageDigest.isEqual 比较固定长度 bytes，Lua 按旧 store 的完整长度逐 byte 聚合差异，不能提前遇不同字节退出；不声称全 HTTP 响应绝对 constant-time。

私有凭证缺失/格式错误/不匹配/记录已回收统一 401 invalid-web-credential，**不能被 DTO/Header required 校验抢先变成 400**；资源 ID 的结构错误仍按 Contract 400。先验证所有权，后取状态/图像/consume；普通/Admin token/scene 不能替代此 Header。

raw scene 仅在 create 内存中生成微信图像；手机 body 的 scene 只在本次请求中 hash。raw pollSecret 仅 201 返回一次。三个值、Admin token、微信 appSecret/accessToken 均禁止 body/header/URL/query日志、DTO toString、异常消息与 telemetry。摘要也不作为日志/metric标签。

## 6. State Machine 与并发协议

各 Store script 使用 Redis TIME，在同一原子执行中完成：认证材料/绑定检查→lazy expiry→状态条件→迁移/typed result。鉴权数据库查询在脚本外，**迁移不是先查后普通 SET**。多个实例用同 Redis，不用 JVM synchronized 代替状态原子性。

| 操作 | 原子条件/成功 | 重复、非法状态、竞争 |
|---|---|---|
| create | 三 keys 均不存在；生成有效 PNG 后写 PENDING/index/image；expiresAt=Redis now+TTL | 独立 create 不幂等；丢 201 后 Web 可明确重建，孤儿自然过期 |
| scan | scene 解析一致；绑定存在且不同 subject 时首先 conflict；PENDING 且未到期写 boundUserId+SCANNED | 同 subject 返回现状，无续期；尚未绑定 EXPIRED/CANCELLED 仅返回终态，不绑定；绑定终态仍只允许本人 |
| confirm allow | scene+本人绑定、未过期 SCANNED、fresh canLogin；写 CONFIRMED/confirmedAt | PENDING invalid-state；本人 CONFIRMED 在期内幂等，CONSUMED 200不含token；其他终态对应409；不同subject优先conflict |
| confirm deny | 本人 SCANNED、未过期，已知无准入才 CAS→REJECTED | 手机403；Web只状态；如果其他迁移已赢，返回真实终态/冲突，不覆盖。MySQL故障不写REJECTED |
| reject | 本人未过期 SCANNED→REJECTED | 本人已 REJECTED 返回200；CONFIRMED/CONSUMED/PENDING invalid-state；EXPIRED/CANCELLED对应409 |
| cancel | Web ownership；有效 PENDING/SCANNED/CONFIRMED→CANCELLED；到期先落EXPIRED | 任意终态返回当前状态200；不删除记录，不撤销已消费token |
| expire | 非终态且 now>=expiresAt→EXPIRED | 终态不变；不能回滚CONSUMED；保留期内query正常200 |
| reserve consume | Web ownership、未过期 CONFIRMED、当前准入；candidate为空才写完整固定候选 | 并发候选只有一个保存，所有 loser 丢自己的内存候选并使用 Store 返回的赢家；外部状态仍 CONFIRMED |
| finalize consume | 同 candidateHash、未过期 CONFIRMED、SaToken mapping有效且属于boundUser、未撤销；原子写activation+CONSUMED/consumedAt | 并发 loser读取同结果；cancel/expire赢则不激活；不删除session |
| replay consume | ownership、CONSUMED、now<expiresAt、fresh准入、原token有效 | 不调用login；原token失效或到期409 already-consumed；撤权403，保持CONSUMED |

显式 precedence：mobile 先有效 scene/认证，再不同 subject conflict，随后 lazy expiry/动作规则；Web 先私有凭证，再期限/状态，最后相应授权。CONSUMED 的时间检查走 already-consumed，不 lazy 改 EXPIRED。confirm 的 CONSUMED 幂等展示不签发；reject 已 REJECTED 展示在保留期内仍200。内部无准入与基础设施不可读分开。

### 6.1 A/B 扫码例子

A/B 同时 scan 的 Lua 承诺只有一个将 PENDING→SCANNED。若 A 赢，B 后续 scan/confirm/reject 一律 subject-conflict，不泄露 A 身份或状态。A 可以重复 scan、confirm；若 A 不是管理员，其 confirm 把自己的 SCANNED→REJECTED，Web 创建新码；B 不能“接管”旧码。100 个不同 subject confirm 不是本 Contract 的“100 个赢家竞争”：需先测100个 scan 只一个绑定，再测绑定者与99个其他subject confirm 的保护。

### 6.2 Confirm 权限链

WebMvcConfig 的普通小程序认证→Controller `StpUtil.getLoginIdAsLong()`→Service scene/subject前置检查→UserService.getUserProfile→AdminPermissionPolicy.canLogin→Lua CAS。OPR/ADMIN/NORMAL 判断只在已有 policy，QR Service 不另写角色规则。unionid、客户端身份、UserContext 中可能来自 Admin 的 fallback 不参与 mobile subject。

fresh profile 读取失败为500且状态不改；只有明确 USER_NOT_FOUND/不可用/不具准入才拒绝。确认后、消费前以及每次重放都重新查。跨 MySQL 与 Redis 没有共同事务：准入依据是提交前最新成功读取，随后真实管理请求也动态检查当前准入；不承诺角色更新与 Redis consume 零时间差原子化。

## 7. Consume：固定结果 + 既有登录恢复 + 激活承诺

### 7.1 方案比较与选择

| 方案 | 决策 |
|---|---|
| Confirm 时 stable grant | CONFIRMED 本身已有稳定绑定；无需提前 mint Admin token；保持confirm无登录副作用 |
| Idempotent consume | 必选：sessionId+pollSecret识别同次逻辑操作，保存固定token/profile，安全重放 |
| 一个Lua直接执行完整Sa-Token登录 | 不选：Java SaSession序列化、listener、mapping多步不能假设可塞进Lua；不能手写第二套框架session |
| 固定候选、恢复既有login、Lua提交激活与消费 | **选择**。最小增加v2 activation检查，使中间login不能提前成为有效Admin登录 |
| MySQL事务/分布式锁包裹issue | 不解决跨存储Unknown Outcome或先签发的有效性；不选 |

### 7.2 实际顺序

1. 私有凭证验证并读取 CONFIRMED。若 CONSUMED，直接走下节 replay。fresh准入检查；已知失效只原子拒绝仍为CONFIRMED的会话。
2. AdminTokenService 新增 `prepareQrCandidate(userId, sessionId, profile)`：使用**现有** AdminStpUtil.STP_LOGIC.createTokenValue 准备 token，包含服务端签名 extra `qrLoginV2SessionId=sessionId`；不调用 login、不写 HTTP Cookie/Header。构建具名登录 BO/profile快照与绝对 tokenExpiresAt（已有admin tokenTtl，默认8h）。
3. reserve script 原子写完整候选。只有保存的 token 允许 materialize；准备但未保存的token没有Sa-Token mapping/activation，不能访问管理端。未知reserve结果先read，**不能先对本地候选login**。
4. AdminTokenService 新增 `materializeQrCandidate`：调用同一 AdminStpUtil.STP_LOGIC.login(userId, SaLoginParameter.setToken(savedToken))，继续既有8h策略、无lasting cookie，剩余TTL从冻结tokenExpiresAt计算，不延长截止；禁止重新调用issue或重新生成token。使用同 token 更新既有mapping/账号session，而不是新增独立身份。
5. 无论login返回成功还是丢结果，按保存token显式查询既有Sa-Token mapping/session就绪状况。部分写可以通过同token恢复，失败返回500；对外仍CONFIRMED。恢复允许重复框架写/terminal历史计数，不承诺只调用一次Java login；承诺只有一个可用token和逻辑登录效果。
6. 再查当前准入，finalize script一次校验deadline、status、candidateHash、该token的SaToken mapping/剩余TTL、revocation，写activation+CONSUMED+consumedAt。Sa-Token keys由框架公开key factory提供，不硬编码内部key格式；专用store用与SaTokenDao兼容的string值/serializer读取，不拿JSON template猜格式。脚本不能写SaSession序列化对象。
7. 返回冻结结果前，AdminTokenService 显式验证savedToken的签名extra、mapping、activation、revocation、绝对tokenExpiresAt，以及当前准入。不得用请求上下文getTokenValue误拿小程序/Web Header中的token。v2只输出裸token；旧issue仍输出旧Authorization值。

### 7.3 Activation 是有效性条件，不是第二种 token

扩展已有 `AdminTokenService.requireValidLogin`：原Sa-Token checkLogin→可信已验签QR extra（仅v2 token有）→activation存在，userId/tokenHash匹配且未到tokenExpiresAt→既有revocation与当前准入。无marker的legacy token保留原路径。标记非法/activation丢失为未授权；Redis不可用为内部故障，不能当未激活miss。

所有管理HTTP入口继续经过WebMvcConfig这个服务校验；admin permission仍是现有SaPermissionProvider。不能让直接Stp checkLogin/ContextInterceptor/新后台入口绕过activation。candidate token 即使框架mapping暂时存在，在项目管理认证边界也无效。候选失败不写Cookie/Authorization响应，不通过listener交付凭证。

activation只能在CONFIRMED→CONSUMED脚本写一次；**不续期、不从session/mapping重建**。logout先取消v2 activation（缺失亦成功），再沿既有blacklist/logout；删activation成功而后续清理失败仍不可访问，客户端结果重放不能恢复。即使过期的在途materialize恢复了mapping，activation已缺失/不匹配仍拒绝。Gate保留到正常Admin token截止，不能跟QR的2m/7m一起删除；因此二维码到期不会正常撤销已经消费的管理员token。

### 7.4 Unknown Outcome、并发与崩溃窗口

| 故障点 | Truth / 恢复 |
|---|---|
| candidate保存前crash | 无登录副作用；后续允许准备新candidate，只有真正reserve者可login |
| reserve响应丢失/crash | CONFIRMED+完整candidate；read得到同token/profile，继续恢复 |
| login部分完成/crash | CONFIRMED+candidate，activation absent；已有mapping也不能管理登录；同token补完，不mint替代 |
| login成功但finalize前crash | 同上；在有效期内重试继续同token；过期/cancel可赢，候选不能交付 |
| finalize响应丢失/crash | 同一Lua中的CONSUMED+activation+结果已经commit或均未commit；read确认，不重签 |
| consume成功HTTP丢失 | query CONSUMED并不能证明浏览器有token；期限内POST返回同token/profile |
| cancel/expire先赢 | finalize失败；activation未建立；尽力logout单候选token，残留mapping自然TTL，不清账号全部token |
| consume先赢 | 后续cancel返回CONSUMED；期限只结束replay，不改变Admin正常登录TTL |
| 迟到worker在logout后写mapping | 可能产生不可用框架残留，但不会创建activation；不可再交付/激活；必须故障注入测试 |

同时consume可以准备多个**未保存未生效**候选，reserve唯一赢家后全部使用同token。并发materialize不会mint不同token；不引入跨进程lease平台。finalize原子仲裁唯一有效结果，重放不materialize。SaSession terminal更新不是跨请求事务，测试必须同时覆盖同user其他既有登录不被logout/清理删除；安全正确性依赖mapping+gate而非terminal计数。

Redis连接丢失不能判定已回滚；恢复前先读同session/candidate。Redis完全丢失或authority无法恢复时fail closed，重新扫码，不能从候选进程内token恢复会话。Redis重启使用一致持久化恢复；不允许回滚单独session key或activation key、手工导入旧认证快照。缓存淘汰/持久化/异步failover丢写不由Lua解决：部署需确认认证key内存预算及持久化策略、无不受控旧主继续授权。发生明确丢失/部分restore时停用v2并整体清除该故障范围的v2授权、要求重新扫码，而非继续mint；这类共享环境操作需单独授权。

### 7.5 Replay

先ownership，后now<expiresAt，再fresh准入和原token的显式有效性检查。返回首次保存的profile，不根据新nickname/权限重写结果；真实管理授权仍读最新事实。撤权403，CONSUMED保持；原token已logout/mapping失效/gate丢失时409 already-consumed，不创建替代。Redis/MySQL读取失败500，不能误判已撤权/已消费失效。

## 8. WeChat Mini Program Code

扩展WxClient最小方法 `generateMiniProgramCode(page, sceneCode, envVersion, checkPath)`，返回受限PNG bytes；不引入完整SDK或重写微信模块。访问 ma AppID/secret/getMiniAccessToken(false)，**不是mp**。目标provider调用为POST `https://api.weixin.qq.com/wxa/getwxacodeunlimit`，token仅provider query，body为具名page/scene等参数。

page固定为Contract路径，scene原32字符，无URL编码包装；用户澄清确认页目前仅本地完成：`check_path` 由 Backend 配置持有，正式环境默认 true、dev 默认 false，可用 ADMIN_QR_LOGIN_CHECK_PATH 覆盖以生成未发布页面的开发/体验码。此决定取代原先固定 true 的内部实现限制；页面路径仍固定，客户端不能提供 page/check_path。环境由部署配置 `release/trial/develop` 允许集控制，不凭客户端传入。width初始430，opaque标准码。既有WebClient默认Accept JSON需在此请求适配为二进制/JSON错误均可接收。

复用connect5s、response/read/write10s与2MiB body上限；本次create整个token获取+生成链总deadline25s，token刷新一次也必须在同budget内。先按有限content-type/文件signature区分JSON错误与图像；允许可解码PNG/JPEG，必要时用Java17 ImageIO规范化为PNG（不改变page/scene内容）。先读取尺寸元数据并限制每边<=2048、总像素<=4Mi，再完整解码，输出PNG仍<=2MiB，防止小压缩体占用无界heap。不能把HTTP200 JSON错误/JPEG原始bytes直接标成image/png；其他格式/损坏内容拒绝。JSON错误只解析errcode等受控字段，不透传errmsg/body/token URL。

显式token失效错误（复用WxApiResult.isTokenExpired所识别代码）最多刷新一次，用既有miniTokenMonitor避免同实例刷新风暴；二次失效停止。timeout、HTTP4xx/5xx、rate limit、其他业务错误**不自动重试POST**；返回create 503 code-unavailable（Redis发布自身失败为500）。生成是可替换临时图片，微信可能在timeout后已经生成，但没有会话授权或交付凭证，无需远程补偿/查询；用户可明确重新create，受create限流。不要retryWhen包整个create。

### 8.1 创建与生成失败

先生成凭证和PNG，只在生成成功之后用Lua TIME确定createdAt/expiresAt并原子发布session/index/image；未发布前scene不可解析。PNG生成失败不建PENDING，不新增FAILED状态；进程crash只丢本地临时bytes。publish未知结果可能留下完整但Web未拿私有secret的孤儿，TTL清理，不能claim或以scene获取Websecret。201只在发布成功、仍未到期且PNG可读取时返回，包含Location。

### 8.2 资源方案

Contract已选择**201 JSON + 专属Backend PNG URL**，内部选择PNG缓存Redis至expiresAt，每会话只生成一次，不每GET调用微信。

| 方案 | 结论 |
|---|---|
| create直接binary / Base64 | 不符合已确定Created表示；不改Contract |
| Backend URL + Redis bytes | 选择；多实例共享、短TTL、私有Header读取、无文件清理job |
| 本地文件/内存缓存 | 多实例/重启会丢PNG，无权威会话可重建raw scene；不选 |
| COS/CDN | 无需已有media registration/长期object清理；signed URL/cache/privacy与Contract Header资源不一致；不选 |
| 每次重新微信生成 | 增加provider成本且需留raw scene；不选 |

image GET仅PENDING/SCANNED、now<expiresAt；读取state和binary通过同原子边界检查（专用script可返回binary），防cancel/expiry与读图状态分离。其他状态按Contract409；已知有效session缺图是内部500，不调用微信再生成。图像资源虽然只编码公开scene，也必须按Contract验证私有Header。所有origin/Nginx缓存禁用、无ETag/304/CDN缓存，Webfetch创建临时Blob URL。仓库未发现可用Nginx/Compose配置，需部署owner核实不能由文档推断代理已经no-store。

### 8.3 官方能力证据门槛

本轮尝试读取[微信官方小程序码文档](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/qrcode-link/qr-code/getUnlimitedQRCode.html)及新版官方路径，工具返回不可读取；没有用第三方文章替代官方规格。本设计采用Contract确定的page/scene和目标API，不声称当前微信限制/QPS/环境可达已验证。**实现中的provider集成验收与启用v2 create前必须**读取届时官方文档、核对errcode/PNG/环境参数并真实调用验证目标AppID/分包页面；若发现与READY Contract冲突，BLOCKED并走contract协调，不自行改scene/页面。

## 9. Polling、Expiry、Cancel

默认TTL2m、terminal retention5m、pollInterval1500ms（校验>=1000）。v2 properties启动时校验0<TTL<=5m、retention>=5m、tokenTtl有效且足够覆盖QR恢复。创建时保存pollInterval，不通过配置热变改变已有表示。

expiresAt是业务期限，物理删除到expiresAt+5m；脚本在任何触达时对非终态lazy EXPIRED，query正常200。首次过期查询不能删key；五分钟保留后Web统一401、scene统一404，不区分曾存在与从未存在。无需keyspace事件/定时扫描产生EXPIRED；未读取的hash可仍存PENDING，权威读取必须按expiresAt投影，不能暴露过期可确认状态。

poll一次Redis script，O(1)，无MySQL权限查询/登录签发；code二进制亦Redis。Web单请求轮询，终态停止；consume超时可按Contract查询并在期内重试。多tab每次create独立私有secret/session；复制secret仅表示共享所有权，不保证硬件浏览器绑定。刷新丢内存secret重新create；后端不发恢复secret接口。离开/刷新cancel是尽力优化，网络断开/unload不能成为正确性前提，TTL最终兜底。

## 10. Security、Rate Limit 与HTTP实现

路由范围由Controller mapping与WebMvcConfig共同维护，不能宽泛放行`/v1/admin/auth/**`：

- 匿名仅POST `/qr-login-sessions`。
- Web五个相关路径（含create）精确识别；query/code/consume/cancel以Header ownership校验，不要求已有admin登录。请求中的Authorization不能让私有校验失效。
- mobile仅POST `/qr-login-scans`、`:confirm`、`:reject`，强制普通loginType；Admin token不能替代。Controller subject不用ContextInterceptor的admin fallback。
- 其余admin入口继续已有AdminTokenService；OPTIONS按已有预检处理，ERROR/ASYNC不能误当新业务授权。

request严格拒绝未知scene body属性；create/consume/cancel不接受任何body（包括`{}`）。不能全局开启Jackson unknown属性失败影响旧接口；用本DTO范围的具名验证/既有局部读入模式处理额外字段。所有no-store要覆盖401/400/429/500、auth拦截器提前拒绝和PNG错误；通过针对新route的Filter/response边界设置，不只给Controller成功加header。复用ContractProblemException/GlobalExceptionHandler，公开错误不直接映射旧ResultCode或微信response。

CORS允许配置中的实际Web origin、X-Admin-Login-Secret、Authorization，新增expose Retry-After，保留Location/X-Trace-Id。现有token不是Cookie专属；v2不设置Cookie，也不以自动附带Cookie证明Web ownership。私有随机Header+非Cookie身份以及受控origin是本流程CSRF边界，现有allowCredentials(true)不是新增Cookie认证授权；测试无Header只有Cookie必拒绝。不新增全系统CSRF框架。

### 10.1 分操作限流（首版可配置默认值）

| 操作 | 默认固定window | 信任与成本 |
|---|---|---|
| create | 10/IP/min + v2全局60/min；实例入口另设同时微信生成上限4，容量不足503 | remoteAddr经可信代理配置得到；不直接信任任意XFF；全局计数同Redis原子window，provider实际额度仍需部署核实 |
| scan | 30/subject/min + 60/IP/min | 高熵scene不能枚举；subject从普通token |
| confirm/reject | 10/subject/min，各operation独立 | 防权限查询放大，不用scene原文当key |
| poll | 60/session/min + 120/IP/min | 私有有效owner bucket；错误secret仅IP bucket，不能借已知sessionId耗尽合法owner配额 |
| code | 10/session/min + 30/IP/min | 合法owner再计session额度 |
| consume | 20/session/min + 60/IP/min | 允许弱网恢复，同会话原子逻辑结果；429不改变truth |
| cancel | 10/session/min + 30/IP/min | 尽力清理不是登录正确性依赖 |

rate script原子INCR+首次固定PEXPIRE，不滑动续期；Redis故障fail closed500。429含剩余window秒数的Retry-After（上取整>=1）。数字是内部初始运维默认、可基于真实流量调参，不是三端新Contract。invalid credential响应先经过IP预算防暴力，再owner验证；429预算不暴露资源存在性。

资源预算上界必须与部署Redis可用内存核对：默认60create/min×2min图像TTL×2MiB最大PNG，图像理论上界约240MiB（另加协议/存储开销），不是实际平均图像大小；TTL改5min时上界相应增加。enable前用真实430码测size并按可用预算降低全局额度/响应上限，不能在没有容量证据时开启认证key任意淘汰，也不把实例内生成并发上限当作全局provider QPS控制。

Replay保护：scene只允许手机绑定流程不能换token；其他subject无法覆写；同subjectconfirm/reject不续期；pollSecret只能访问一个session；consume同token重放仅到固定期限且重新验证准入/原token有效；CONSUMED终态不恢复、不mint replacement。

## 11. Failure Matrix 与Recovery Owner

对外500使用既有internal-error；不能把基础设施故障静默映射401/404/403。下表retry均受固定expiresAt、限流与同次操作约束。

| 故障 | 用户可观察 | Backend truth | retry / owner | reconciliation |
|---|---|---|---|---|
| Redis unavailable | create/其他操作500，无授权降级 | 可能已提交，暂不可判断 | Web/mobile恢复连接后同次read；create可明确新建 | consume read候选/结果；不可据异常mint新token |
| Redis全部丢失 | 恢复后Web401、scene404；已发v2token gate/mapping缺失不可用 | 短期authority丢失 | 用户重新扫码；运维处理Redis | 不从DB/内存重建QR；不宣称仍满足可用性保留 |
| MySQL unavailable | confirm/consume/replay500；poll仍可返回Redis状态 | 状态不因未知权限写REJECTED | mobile/Web查询后同操作、期内重试 | 无后台盲拒绝/异步授权 |
| WeChat API unavailable/4xx/5xx | create503 code-unavailable | 未publish | 无server自动POSTretry；Web明确新create | 无授权需补偿；provider码未交付 |
| ma access_token unavailable | create503 | 未publish | 明确新create；token缓存owner负责下一次fetch | 不降级mp token |
| code generation timeout | create503 | provider结果unknown，但本地未publish | 不重试该POST；用户新create | 无QR登录effect，无remote撤销要求 |
| Admin authorization lookup failure | 500而非403 | SCANNED/CONFIRMED/CONSUMED保持 | 当前调用方query/同次retry | 明确用户缺失才deny |
| confirm race | 同subject幂等或真实状态409，其他subjectconflict | 一个状态迁移赢家 | 不自动重试非法状态 | read当前可观察状态 |
| consume race | 200同token/profile或真实终态409 | reserve一个候选，finalize一个激活 | 原Web同POST，期内 | 不另issue；候选恢复 |
| process crash | 网络断开/500 | 取决于reserve/materialize/finalize窗口 | Web同session恢复 | §7.4，activation控制未提交效果 |
| network timeout after confirm | 网络错误，不能说确认失败 | 可为CONFIRMED或其他竞态终态 | 手机同scene confirm/scan；Webpoll | 保持绑定，不重建授权 |
| network timeout after consume | 网络错误，本地可能没token | 可为CONFIRMED+候选或CONSUMED | Webquery再同POST，期内 | 返回原结果，过期重新扫码 |
| expired during scan | 本人/未绑定200EXPIRED；不同绑定subject优先conflict | lazy EXPIRED | Web明确新create | 不新绑定、不续期 |
| expired during confirm | 409session-expired（已CONSUMED的展示按confirm规则） | 非终态EXPIRED | 不retry原确认 | 新会话 |
| expired during consume | CONFIRMED409expired；CONSUMED409already-consumed | 非终态EXPIRED或CONSUMED保持 | 不重放，不恢复候选登录 | 未激活残留失效；新扫码 |
| cancel during materialize | cancel200CANCELLED，consume409cancelled | candidate可有mapping，无activation | 不继续finalize | 尽力清候选mapping，TTL兜底 |
| revoke/disable before replay | 403denied或409already-consumed | CONSUMED不改，不替换token | 不retry兑换，需恢复资格后新扫码 | logout gate+既有revocation |
| PNG缓存缺失/corrupt | 有效owner500，create未成功发布时不201 | session不能生成新scene/授权 | owner可cancel并new create | 查Redis容量/写协议，不透传providerbody |

无需Outbox/Job/Message/Stream：create/微信码同步交付，expiry/资源由TTL；没有要可靠跨事务投递的通知副作用。恢复由客户端同session请求驱动，期限之后不接受工作，不能用Job继续签发超期登录。

## 12. Observability 与Audit

复用HTTP `http.server.requests`（MVC模板URI、status、latency），不再建每operationHTTP计时。下面为**待实现/登记**的meter，不声称已存在；实施时在observability.md登记精确枚举和成本。

| meter | 类型/有限tags | owner、成本、消费与移除 |
|---|---|---|
| `jualn.admin.qr.login.transition` | Counter；from/to为七状态允许边集合 | store提交成功后Service计数，幂等读不计；运维registry，v2退休时移除 |
| `jualn.admin.qr.login.consume` | Counter；result=`committed,replayed,recovered,denied,conflict` | 每次consume观察结果；重复请求不是新登录数；O(1) |
| `jualn.admin.qr.login.wechat.code.duration` | Timer；result=`success,failure`，error.category来自既有taxonomy有限集合 | WxClient一次生成链（含允许token refresh）；秒；复用registry，适配器退休时移除 |

Counter进程内、提交后可能因crash少计，不作为审计truth。expiry只统计被请求lazy发现的迁移，不声称所有自然过期数量。首版无active-session Gauge、无Redis全量SCAN、无time-to-scan/confirm多套Timer；若运营需要再增加受控采集。

HTTP RequestLogFilter安装traceId；consume逻辑operationId可取稳定sessionId，仅在consume作用域安装、finally恢复，遵循已有ObservabilityContext，不为poll制造operationId。事件日志用普通键值`sessionId`（即Contract资源），不加新的MDC ID类型。低频create/scan/confirm/reject/consume commit可INFO，重复poll/scan/replay不逐次INFO；预期denied/conflict不打堆栈。失败分类复用authorization/conflict/redis/database/remote/timeout/internal，最终异常由GlobalExceptionHandler一次记录；Wx adapter不得把含token的原WebClient异常URI/JSONbody嵌入cause后交给ERROR泄密，须在该边界转换为受控安全诊断。

日志事件覆盖session发布、绑定、确认/拒绝、准入拒绝、consume提交/恢复、非法迁移和微信最终失败；metric不带sessionId/userId/traceId/operationId/openid/credential。生产Actuator仅health、没有时序dashboard/alert已部署证据，不为此功能额外暴露metrics公网。

### 12.1 Audit Decision

现有admin_operation_log及其Service只提供角色变更写入，不是通用登录审计能力。当前Contract/业务文档没有要求durable登录审计，因此本版保留脱敏登录事件与现有观测，**不创建QR专用表、不将普通log说成持久审计**。若后来确定合规/安全审计需求，应扩展admin/operation的action与service，记录内部user、WECHAT_QR、时间/结果/可靠client来源，并明确可靠投递/去重；该需求不能悄悄作为登录主链MySQL双存储依据。本轮不引入async audit。

## 13. 单一新流程与启用门槛

2026-10-07 用户确认旧流程从未上线并授权删除。此决定取代本设计原先的并存/观测/弃用计划：仅保留 v2，删除旧 Controller 的 QR 操作、旧编排/状态/VO/存储脚本/配置、无 marker Token 兼容和小程序旧设置页扫码。me/logout 与 Token 撤销记录继续保留。

Web 新 adapter 与小程序新确认页已存在；用户已确认小程序 AppID 与确认页就绪，create 配置默认启用并复用 wx.ma；正式环境默认 release、dev 默认 develop，环境变量可覆盖。真实调用和端到端证据仍须单独报告。所有管理认证实例必须部署 activation gate，不能回退至绕过 gate 的版本。QR 有效期结束不撤销已消费 Token 的正常寿命。

## 14. Testing Strategy 与验收证据

使用[commands](../governance/commands.md)已有定向Java17/Wrapper流程，按Backend§9/Spring§12选验证，不从全仓测试开始。以下是后续必需测试计划，**本轮未实现/执行**。

| 边界 | 必须证明的性质 |
|---|---|
| Unit | 三独立随机值长度/格式/熵来源；hash-at-rest；projection省略；全部状态规则/错误precedence；权限policy复用；绝对deadline/重复不续期；具体错误与unknown分开 |
| 真实Redis store | Lua并发与key types；collision原子发布；hash mismatch统一拒绝；TIMEnow>=expiry；expiresAt+5m保留；二进制缓存；故障不能当miss；原子activation/CONSUMED；Redisrestart保留状态 |
| 真实Sa-Token/Redis | 同candidate.setToken产生同mapping；无activation候选拒绝所有admin入口；sameuser既有登录保留；candidateTTL不延长；logout/revoke后replay不恢复；gate生命周期8h独立QRTTL；Simple JWT签名marker校验 |
| WeChat Adapter mock HTTP | PNG成功/JPEG规范化/尺寸/2MiB；HTTP200 JSON错误、HTTP4xx/5xx、timeout、body超限/损坏/解码像素超限；ma token一次刷新；二次失效终止；总deadline；无通用POST重试；token URL/body异常脱敏 |
| MVC/security | 八operation真实路由、普通/Admin/private三认证边界、CORS预检与Retry-After、401/403/404/409/429/500/503、ProblemDetails、no-store含错误/PNG、严格未知属性/禁止body、裸token且无泄露 |
| crash/recovery | reserve前后、login部分写、login完毕、finalize前后、丢HTTP响应，换Service实例后同token/profile；与cancel/expire/revoke竞态；迟到materialize不能恢复gate |
| 真实微信/三端 | 已发布分包页、冷/热启动/认证等待/切账号、同AppID/env、真实微信扫一扫、BlobHeader获取、弱网消费恢复、旧流程路由已不存在 |

并发使用latch/barrier而非任意sleep：100不同subject同时scan→恰一个boundUser；再100不同subjectconfirm→只有绑定者可确认；100同subjectconfirm→一个CONFIRMED迁移；100私有同sessionconsume→**同一个token/profile、一个activation、一个CONSUMED承诺**，不要求所有attempt都恰HTTP200（限流单独测试，机制测试适当配置预算）。同token materialization多次不能增加独立有效凭证。

竞争矩阵至少confirm/reject、confirm/cancel、confirm/expire、consume/cancel、consume/expire、consume/revoke；loser不能覆盖终态。故障注入用独立process/代理在明确边界断连接/停进程，恢复读不能用mock猜。保存token以合成隔离数据验证，不打印值。Redis/MySQL测试必须独立本地disposable环境，不用业务/生产DB。Repository不存在完整v2测试，旧mock测试不替代此集合。

### 14.1 Contract→实现入口→证据映射

| operationId | Controller→Service/持久化 | 后续验收重点 |
|---|---|---|
| createAdminQrLoginSession | create→createSession→WxClient+store.publish | 201PNG可取/固定TTL/503无可用session/独立credentials |
| getAdminQrLoginSession | query→querySession→store.read | 无token/无login副作用、expiry保留、统一401 |
| getAdminQrLoginCode | code→readCode→store.state+binary | Headerowner/仅PENDING与SCANNED、PNG/no-store、无再生成 |
| scanAdminQrLoginSession | scan→scanSession→store.bind | 普通身份、firstscanwin、本人幂等/他人conflict |
| confirmAdminQrLoginSession | confirm→confirmSession→UserService/policy/store.CAS | 先绑定、fresh准入、403+REJECTED、无token |
| rejectAdminQrLoginSession | reject→rejectSession→store.CAS | 本人SCANNED限定、幂等REJECTED、不覆盖CONFIRMED |
| consumeAdminQrLoginSession | consume→consumeSession→AdminTokenService+store.reserve/finalize | 固定token/profile、一次有效登录、丢结果恢复/withdrawal/replay期限 |
| cancelAdminQrLoginSession | cancel→cancelSession→store.CAS | 幂等当前状态、终态不删、不依赖unload |

## 15. Implementation Sequence

| 顺序 | 修改模块/新增职责 | 前置依赖 | 完成证据 |
|---|---|---|---|
| 1 | auth BO/DTO/VO/v2enum/converter、typed config、key factories | 固定Contract修订/本设计 | Unit projection/unknown属性/配置边界，与Contract examples对应 |
| 2 | AdminQrLoginStore：publish/read/scenebind/confirm/reject/cancel/expiry；扩展AdminAuthCrypto | 1 | 真实Redis并发scan/状态/ownership/TTL/WRONGTYPE；不写全Lua框架 |
| 3 | AdminTokenService准备固定token、materialize与explicitvalidation；store reserve/finalize/activation；logout gate | 1–2、本地1.45.0语义 | 真实Sa-Token+Redis固定token恢复、crash/cancel/expire、现有登录兼容；这是上线硬门槛，不能放到最后补救 |
| 4 | WxClient/具名provider DTO新增ma PNG能力；配置env/page启用校验 | 1、官方provider规格核实 | mockHTTP二进制/JSON/timeout/tokenrefresh/no泄密；无SDK升级 |
| 5 | AdminQrLoginService create/scan/confirm/reject/query/code/cancel/consume编排、限流 | 2–4 | Service语义+真实store组合，准入错误区分/候选不替换 |
| 6 | 新Controller+局部请求验证、WebMvc精确鉴权、no-store/CORS/Problem适配 | 5、gate-aware AdminTokenService | 八path MVC/security matrix；API读取保持落库语义，裸token无旧包装 |
| 7 | auth Counter/provider Timer/安全事件，observability.md登记；README/WeChat/可靠性事实必要联动 | 5–6 | Micrometer tag白名单、MDC作用域、日志脱敏；不建metrics API或审计表 |
| 8 | 相关package测试、真实Redis/SaToken崩溃与并发、旧能力移除回归 | 2–7 | §14关键invariant、固定结果、终态保留、旧 routes 已移除；报告分边界PASS/FAIL/BLOCKED |
| 9 | 部署全部gate-aware后端，Mini确认页先发布，再真实微信/三端联调enablev2 | 8、三owner交付 | 真实AppID/env/page/微信扫一扫/弱网恢复；Redis拓扑/容量/持久化/代理缓存校验 |
| 10 | 未上线旧能力移除（用户已授权） | 新两端源码已就绪 | 删除旧接口及调用方，验证 activation 和 me/logout |

没有DBDDL/DML或migration步骤，若后续确立durable audit新需求，先更新其业务authority再扩展该计划。

## 16. Review Report 与本轮Completion Evidence

| 项 | 结论 |
|---|---|
| A Contract Compatibility | 七状态、firstauthenticatedscanwins、独立privateHeader、PNG、裸token、错误/保留/安全恢复均按已READY Contract设计；无外部协议修改 |
| B Existing Capability Reuse | UserService、AdminPermissionPolicy、AdminStpUtil/AdminTokenService、严格Redis/Lua模式、AdminAuthCrypto、WxClient ma凭据/WebClient、Micrometer/trace/Problem复用；媒体/COS不接入 |
| C Storage Decision | Redis临时truth；用户权限MySQL；无replica/DB表；业务expiry与物理TTL分离 |
| D Concurrency Decision | scan绑定CAS；confirm/reject状态CAS；reserve固定candidate+existinglogin恢复+Lua activation/CONSUMED原子承诺 |
| E Credential Decision | 三独立随机值；scene/pollSecret hash-at-rest；固定token短期可恢复存储；日志/metric禁凭证 |
| F WeChat Code Decision | 同步ma API，先PNG成功再publish；201对象+Header保护BackendURL；RedisPNG到expiresAt，不用COS |
| G Failure/Recovery | timeout不判失败；同token恢复；cancel/expire未激活不能有效登录；replay期限/撤权/token失效禁止替代 |
| H Observability | 现有HTTP+trace，三个低基数待登记meter和脱敏事件；不新增全套日志、Gauge或audit表 |
| I Legacy Migration | 用户确认未上线，旧能力已直接移除；真实微信、页面发布与环境启用另验收 |
| J Implementation Sequence | §15；consume机制必须先证实再做可启用HTTP流，不能只补controller200 |

本轮交付仅内部设计和README入口，未改Contract/Java/配置/DDL/三端代码，未add/commit/push或操作生产/共享环境。

| 本轮边界 | 状态与证据 |
|---|---|
| 来源/设计检查 | PASS：本地源码扫描、Sa-Token1.45.0字节码检查、Contract READY与四个输入SHA-256定位；此PASS不是provider实现验收 |
| 文档检查 | PASS：本设计全部本地Markdown链接目标存在、八个canonical operationId覆盖、七状态覆盖、README入口；新文档及受影响README的diff/空白检查 |
| Contract工具 | NOT RUN：未修改contracts；此前31/31通过仅为coordination记录，本轮没有重跑 |
| Java/Redis/Sa-Token行为测试 | NOT RUN：本轮仅设计，尚无v2实现；§14是后续必需验证，不宣称并发/恢复已PASS |
| 微信/部署/三端联调 | NOT RUN：真实调用、页面发布、生产拓扑与端到端未执行；官方微信页面在线读取失败，保留§8.3门槛 |

**READY_FOR_IMPLEMENTATION**。没有已知Contract冲突需要重定义协议。真实微信provider文档/页面验证、Redis同primary拓扑以及全部服务gateaware rollout是实现验收/启用门槛；若实际条件不满足，按明确边界BLOCKED，不绕开本设计的correctness承诺。
