# Admin QR Login v2 后端实现与验证

2026-10-07。本记录是实现证据，不替代 [共享契约](../../contracts/docs/coordination/admin-qr-login.md)、[内部设计](admin-qr-login-v2-backend-design.md) 或工程标准。范围是 Backend；未修改 Admin Web / Mini Program，未部署、启用、提交或推送。

## 实现边界

- 新 `AdminQrLoginController → AdminQrLoginServiceImpl → AdminQrLoginStore` 实现八个 canonical operation。请求仅 sceneCode，身份从普通 StpUtil 提取；新成功响应直接对象、consume 输出裸 token；旧流程从未上线，已按用户决定移除；AdminAuthController 只保留 me/logout，AdminAuthStore 只保留 Token 撤销记录。
- 三个独立随机值为 16/24/32 bytes，对应 22/32/43 个 Base64URL 字符。session/index 保存 scene/pollSecret 的 SHA-256，原始 secret 仅创建响应返回；状态响应无身份/凭证。摘要比较使用固定长度比较。
- Redis Lua TIME 决定固定期限、lazy expiry、first authenticated scan wins、状态 CAS、固定候选 reserve 与 activation/CONSUMED 原子 finalize。session/scene 默认保留到 expiresAt+5m，PNG 至 expiresAt；无新增数据库表或迁移。
- consume 重新检查 MySQL 当前准入，保存唯一 token/profile/绝对 tokenExpiresAt 后才允许 Sa-Token 登录持久化。多个尝试始终恢复保存的 token；GET 与手机确认不会签发。CONSUMED 重放重新检查准入、原 token/mapping/revocation/activation，过期或失效不签发替代。
- 候选登录调用现有 `createLoginSession(setToken(...))`，这是 `login` 的持久化路径，避免未提交候选写入响应或 Cookie。Simple JWT 使用 `getPayloadsNotCheck`：已核对 1.45.0 字节码，它仍校验签名/loginType，只跳过不存在的 eff；真实期限由 Sa-Token mapping 与 activation 的绝对期限管理。
- 管理认证和 ContextInterceptor 均遵守 activation；v2 私有入口不由请求中其他 Authorization/Cookie 替代所有权，手机只接受普通 loginType。无 marker 的 Token 拒绝管理认证。logout 先去 activation，迟到 materialize 不能恢复授权；候选清理仅针对该 token。
- WxClient 使用 ma 凭据、固定分包 page、原始 32 字符 scene、check_path=true 和配置环境。二进制/JSON 识别、2MiB/2048px/4Mi 像素预算、JPEG→PNG、一次显式 token 失效刷新、25s 总 deadline，无通用 POST retry。provider 返回受控 ExternalServiceException，QR Service 映射 503；不携带原始 URL/body/cause。
- 限流使用固定 60s Redis window，错误 secret 只花 IP 配额，合法 owner 才花 session 配额。429 有 Retry-After；CORS expose Retry-After；v2 Filter 在鉴权前设置 no-store，含 JSON/PNG/错误分发。
- 精确 meter 登记见 [Observability](observability.md#61-当前自定义-meters)，不新增审计表、Job、Outbox、cleanup scheduler 或 metrics 公网入口。

## Contract → 实现 → 证据

| operationId | Controller / Service / storage | 已执行证明 |
|---|---|---|
| createAdminQrLoginSession | create / createSession / WxClient+publish | 固定 TTL、独立凭证、hash-at-rest、私有 PNG、201/Location、默认关闭、失败不发布 |
| getAdminQrLoginSession | query / querySession / read | 纯状态无 token、期限保留、错误 secret 401、不耗 owner 配额 |
| getAdminQrLoginCode | code / readCode / 同脚本状态+binary | PENDING/SCANNED、PNG与私有 Header、缓存损坏500、无再生成 |
| scanAdminQrLoginSession | scan / scanSession / bind | 100 subject 仅一绑定；他人 conflict 优先于 expiry；普通/Admin 身份隔离 |
| confirmAdminQrLoginSession | confirm / confirmSession / policy+CAS | 仅绑定者、同 subject 幂等、单迁移、权限失败与 DB 故障区分 |
| rejectAdminQrLoginSession | reject / rejectSession / CAS | confirm/reject/cancel/expiry 竞争、终态不覆盖 |
| consumeAdminQrLoginSession | consume / consumeSession / reserve+既有 login+finalize | 100消费者固定候选、一激活提交、冻结结果重放、进程崩溃/部分写恢复、撤销/过期/取消保护 |
| cancelAdminQrLoginSession | cancel / cancelSession / CAS | 终态保留、未提交 token 无 gate、已消费不被取消 |

## 首轮实施历史验证与边界（旧流程移除前）

使用明确 Java 17 `D:/Jualn/Java/jdk-17.0.18+8` 与项目 Maven Wrapper，Maven 3.9.14；主代码编译成功。首次默认环境编译因新 JDK/Lombok 不兼容失败，改用上述 Java 17，没有升级依赖或修改全局环境。

普通 `-Dtest=AdminQrLoginRedisTest test` 被既有 InteractServiceImplTest 的旧构造器及已移除 viewCount 方法阻塞在 testCompile；这些路径不依赖 QR/Token 的修改。随后使用仓库已有 `validate-event-information.ps1` 的显式 TestNames 隔离编译，未修改或跳过本功能断言。

一次性本地 `redis:latest` 容器 `jualn-admin-qr-v2-test`，仅绑定 `127.0.0.1:33991`，开启 AOF；没有访问应用配置中的 Redis/MySQL。所有数据为 synthetic。测试会重启显式命名的该容器，不适用于业务 Redis。

```powershell
$env:JAVA_HOME='D:/Jualn/Java/jdk-17.0.18+8'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Dadmin.qr.test.port=33991 -Dadmin.qr.test.container=jualn-admin-qr-v2-test'
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome $env:JAVA_HOME `
  -TestNames AdminQrLoginRedisTest,AdminQrLoginMvcTest,GlobalExceptionHandlerTest,WxMiniProgramCodeTest
```

| 边界 | 状态 / 证据 |
|---|---|
| 定向完整集合 | PASS：39 tests，0 failure/error/skipped，退出码0；日志 `target/admin-qr-v2-validation.log`，Surefire `target/event-validation/surefire-reports/` |
| Redis / Sa-Token | PASS：真实 Redis、真实 1.45.0 mapping/session/activation、100并发、固定token/profile、同user已有登录保留、终态保留、WRONGTYPE/损坏值、注销/撤销、候选拒绝、Redis AOF restart |
| 进程故障恢复 | PASS：独立 JVM 在 reserve、Sa-Token 部分写、materialize、finalize 后 Runtime.halt，父 JVM/新 Service 从 Redis 恢复同 token；未打印凭证 |
| 故障分类 | PASS：MySQL collaborator 故障注入不变 REJECTED；Redis transport 故障注入不变 credential-miss。这两项是故障注入，不是实际 MySQL outage 或生产 failover |
| HTTP / MVC | PASS：真实 MVC+WebMvc 安全拦截、八路由、身份边界、闭合字段/类型、禁止body、数字 pollInterval、JSON/PNG、ProblemDetails/no-store、CORS/Retry-After；Service 为替身，非整个应用启动或三端 E2E |
| 微信 provider | PASS：真实 loopback HTTP 模拟 provider，PNG/JPEG/JSON错误/4xx/5xx/超限/损坏/二次失效/25s deadline；未调用真实微信 |
| legacy / shared advice | PASS：AdminAuthServiceImplTest 8项 + GlobalExceptionHandlerTest 5项；真实旧无 marker token 仍可管理认证，候选清理不删同user既有token |
| 全仓测试 | BLOCKED：普通 testCompile 的既有 Interact 测试不兼容；不宣称全仓 PASS |
| 微信官方协议 / 真机 / 三端 / 部署 | NOT RUN：官方文档工具仍不可读取，实际账号/页面、客户端切换、生产拓扑/容量/持久化及代理缓存未验证 |

最终摘要比较强化后，Redis/MVC/legacy 集合于 2026-10-07 18:59:34（Asia/Shanghai）定向重跑 **29/29 PASS**，0 failure/error/skipped，退出码0，日志 `target/admin-qr-v2-final-validation.log`。这次包含真实旧 token 校验及候选清理后的既有登录保留；先前 provider/shared advice 的实现未变化，保留其已执行证据。一次性 Redis 容器在完成后停止并由 --rm 清理。

## 启用门槛与后续 owner

用户确认 AppID/确认页就绪后，当前 `admin-auth.qr-login-v2.enabled=true`，env-version 默认 release，dev profile 默认 develop；`ADMIN_QR_LOGIN_ENV_VERSION` 可覆盖为 release/trial/develop；session-ttl<=5m、retention>=5m、poll-interval>=1000，Admin token TTL 必须覆盖恢复。配置见 [application.yaml](../src/main/resources/application.yaml)。

先让所有承接 Admin token 的实例部署 gate-aware 版本。QR Store 拒绝 Redis Cluster，create/consume 检查 SaTokenDaoForRedisTemplate 与 QR 使用同一 connection factory/database；不得让旧实例承接中间候选。部署 owner 仍需验证 primary/failover/ACL/持久化、可用内存及禁不受控认证key淘汰、实际 Web origin、Nginx no-store。

默认全局60 create/min×2min×最大2MiB 图片，理论图像预算约240MiB，需真实430px码平均大小和部署内存证据调低限额；代码不证明生产容量充足。

Mini Program 已实现确认页、Web 已实现完整新 adapter。部署 owner 仍需发布 `subpkg_setting/pages/admin-login-confirm/index`，验证同 AppID/env/page、冷/热启动/普通认证/切账号和真实微信扫一扫。Admin Web owner整体切换到新adapter：带私有 Header fetch PNG →纯poll→POSTconsume→裸token一次拼Bearer；真实弱网 E2E 尚未验证。

实时核对 [微信官方接口](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/qrcode-link/qr-code/getUnlimitedQRCode.html) 并完成实际生成/真机测试后才启用 create。旧流程未上线，用户已授权直接移除，无需流量观察或在线迁移窗口。此次没有云配置变更或共享环境操作。

## 2026-10-07 未上线旧流程移除

用户确认旧方案未上线并授权直接使用新方案。已删除四个旧 QR 路由、旧 Service/实现/状态/VO/测试、旧 Lua 会话与限流逻辑及配置；保留独立 Token 撤销记录和 me/logout。移除旧白名单及无 marker Token 兼容；不能通过旧 Token 绕过 activation。

Web 源码已使用新八操作中的 Web 操作，无旧 endpoint。小程序删除设置页旧内部扫码、对应 Action/Service/API/类型，使用已注册的新确认页；不把旧二维码映射成 scene。Contracts 迁移说明与设计启用章节同步：canonical 八个操作未改变，未发布旧能力的移除仍归类 breaking。

本次验证结果见下方执行记录；首轮 39/29 tests 是当时的历史证据，已移除旧测试，不代表本次测试集合。

| 本次边界 | 结果与证据 |
|---|---|
| Redis / Sa-Token | PASS：17 tests，0 failure/error/skipped；`target/admin-qr-removal-validation.log`。含无 marker 拒绝、同 user 两次新登录互不误删、100 并发、崩溃恢复、AOF 重启及撤销；首次集合 MVC 有失败，不能称该次整体 PASS |
| HTTP / shared advice | 首次旧路由缺失被 NoHandlerFoundException 误映射 500，FAIL；纳入现有 resource-not-found 404 后重跑 PASS：6 MVC + 5 advice，11/11，退出码0，19:45:07；`target/admin-qr-removal-http-validation.log`。四旧 QR 路由 404，新八操作及 me/logout 保持 |
| 小程序 | PASS：`pnpm typecheck`，`node scripts/test-admin-qr-login.mjs`，改动文件定向 ESLint；Node/VM 平台与网络为替身，非真机 |
| Contracts | PASS：`npm run validate`，OpenAPI lint/bundle + 32 examples tests；本次未改变八操作的 canonical wire，移除说明属于未发布旧接口的 breaking 决策 |
| 文档与改动 | PASS：小程序 docs integrity 30 documents；改动路径 diff-check，无旧 QR 源码调用残留。已保留无关现有工作区变更 |
| 全仓 / 真实微信 / 部署 | NOT RUN：本次未跑全仓，首轮普通 testCompile 有已知 Interact 测试阻塞；未调用真实微信、发布页面或启用部署。微信生成默认关闭，部署时须显式选择 env-version 并验证目标 AppID/page |

两次定向集合合计 28 项相关测试最终通过。WxClient 本次未变，沿用首轮 provider 模拟证据。测试用一次性本地 Redis 完成后停止清理，未连接业务数据存储、未执行 Git 提交或发布。

## 用户确认后的配置启用

用户确认 AppID 复用现有小程序 AppID、确认页已完成，授权直接使用新方案。`application.yaml` 默认 enabled=true、env-version=release；`application-dev.yaml` 默认 develop。两者允许 ADMIN_QR_LOGIN_ENV_VERSION 覆盖，仍拒绝非法环境。AppID 与 AppSecret 沿用 WxClient → wx.ma；没有新增 Admin AppID 或读取前端文件。

这里只启用仓库配置；没有调用真实微信、修改共享环境或部署。前面的默认关闭与 NOT RUN 行属于对应轮次历史证据。

配置验证 PASS：AdminQrLoginConfigurationTest 4/4，0 failure/error/skipped；实际加载 base/dev/prod YAML 并验证启用、环境默认值、环境变量覆盖和非法环境拒绝。2026-10-07 19:55:22，退出码0；target/admin-qr-enable-validation.log。主代码和定向测试编译通过，未跑全仓或真实微信。

## 真实请求失败后的诊断日志修正

用户提供的微信生成码请求返回 HTTP 200，但 WxClient 判定 remote failure；原 WARN 未输出已经解析的 providerCode，现补 code 字段。仅输出数字 errcode 或固定内部诊断码，不输出 errmsg/body/scene/access_token。该日志尚不能确定真实失败原因，需要更新后同请求的 code；未放宽 check_path 或添加业务错误重试。

PASS：WxMiniProgramCodeTest 6/6，0 failure/error/skipped，19:59:47，退出码0；target/admin-qr-provider-log-validation.log。新增回归证明 HTTP 200 业务失败的错误码可见且敏感值不入日志；其余 provider 模拟、图片预算和 deadline 断言保持。未复现用户的真实微信响应，未部署。

## 本地确认页生成配置（用户后续澄清）

用户澄清确认页仅本地完成。真实微信错误 41030 为 page 校验失败；此前“确认页就绪”只代表本地源码，不表示已发布。采用微信支持的 check_path 参数：新增 admin-auth.qr-login-v2.check-path，application.yaml 默认 true，dev profile 默认 false；ADMIN_QR_LOGIN_CHECK_PATH 可显式覆盖。正式环境的默认校验保持，体验码可组合 ENV_VERSION=trial / CHECK_PATH=false。

Service 将配置传给 WxClient，首次生成及唯一 token 刷新均保存该参数；固定 page/scene、身份绑定及 activation 不变。用户不能从 Web 请求选择 page 或校验策略。生成开发码后仍需开发者工具验证或上传相应开发/体验版本才能验证真机打开。本次修改不自动上传页面，不调用真实微信、不部署。

依据：[腾讯 CloudBase 官方说明](https://docs.cloudbase.net/recipes/add-share-with-params-miniprogram)，未发布页面联调可设 checkPath=false；微信原站页面此次工具无法读取。

PASS：AdminQrLoginConfigurationTest 4 + WxMiniProgramCodeTest 7，11/11，0 failure/error/skipped；20:05:59，退出码0，target/admin-qr-local-page-validation.log。实际 YAML 绑定验证 dev=false/prod=true，loopback provider 验证关闭校验的开发码请求与刷新参数保持；未验证真实微信/设备，主代码与定向测试编译通过。Redis 用例已同步新方法参数，本次未重跑 Redis 集合。
