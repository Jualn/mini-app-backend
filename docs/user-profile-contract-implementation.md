# User/Profile provider implementation — 2026-10-07

本轮实现依据为 contracts 的 2026-10-02 review 及 2026-10-07 POST 方法调整后的 canonical Profile；没有修改 contracts、提交 Git、部署应用或访问共享/生产数据库。按用户要求移除资料写入总开关，新 POST 和旧 PUT 直接使用同一检查后原子生效链路。只有具体检查拒绝或不可用才返回 422/503；目标环境迁移与真实微信/COS 验证仍未执行。

## 固定协议来源

来源为 [coordination](../../contracts/docs/coordination/user-profile-homepage.md)、[paths](../../contracts/api/paths/profile.yaml)、[schemas](../../contracts/api/schemas/profile.yaml) 以及 [OpenAPI entry](../../contracts/api/openapi.yaml)，同时遵循 contracts README、PROJECT_RULES 和 API_GUIDELINES。当前来源没有可供本轮固定的契约 Git revision，使用 SHA-256 固定内容，不伪造发布版本。

| 文件（相对 contracts） | SHA-256 |
|---|---|
| api/openapi.yaml | E34F64250B145440D1D1EE5FB08606221448CF76CC14A521E0F8CE005B4DA9EB |
| api/paths/profile.yaml | D7C47BBAD5A148655A72359E3F1E8853B5038A17A0E14A6E60F66A804F160708 |
| api/schemas/profile.yaml | E71E057D92537D8CEF57701320F462ABBC617F60F9B72D13821DF2A1FBDAC30A |
| docs/coordination/user-profile-homepage.md | C3AD52B2F4C620FD4186D805B5D2C28E9FE9A082D2D4BE6AD859D9A351D9579F |

## 旧执行链与问题

本轮开始时审计的小程序 `services/user.ts` / `services/api.ts` → GET/PUT `/v1/users/me` 或 GET `/v1/users/public/{userId}` → UserController → UserConverter → UserServiceImpl → 绑定上传、更新 user_profile → 发布 UserProfileUpdatedEvent → BEFORE_COMMIT UserProfileAuditListener → UserProfileAuditSubmitter → AuditReservationService 预留 content_audit_log 和 Durable Job → Worker 调微信 → 审核事实及 Outbox → Stream handler 调字段 callback。

2026-10-07 方法调整前复核消费者现状：小程序 services/api.ts 已切换 canonical 读取和当时的 PATCH 写入，services/user.ts 已验证五字段表示并直接使用 isPlatformOperator；`isProfileEditAvailable()` 仍返回 false，注明 provider safety switch 和 native PATCH 缺真实运行证据。这些是其他工作的现有结果，本轮只读取，没有修改小程序；不能继续把首次审计时的旧路径依赖报告为现在仍有调用。

BEFORE_COMMIT 只保证任务预约与资料事务一致，不能保证安全检查完成。候选提交后公开可读；旧拒绝回调只按 userId 清空头像/简介/背景或重置昵称，不保留修改前的有效资料，也无法区分旧请求和之后的新资料。微信 mediaCheckAsync 只返回 traceId，不是 PASS。原 STS 允许客户端写 `user/{userId}/*`，直接审核并展示源对象还存在审核后覆盖内容的风险。

## canonical 操作映射

| 契约语义 | HTTP / Service / 持久化 | 本地证据 | 剩余边界 |
|---|---|---|---|
| GET /v1/users/me/profile | UserController.getMyProfile → UserService.getEffectiveProfile；身份来自 UserContext，直接读 DB | MVC 五字段/string userId/no-store；真实 Mapper 本人与目标本人一致 | 真实 Sa-Token/HTTP/反向代理端到端未跑 |
| GET /v1/users/{userId}/profile | 同表示、相同登录 actor 检查；目标缺失/逻辑删除 404 | MVC 和 MySQL；不返回 role/openid/account status/领域列表 | 目标环境历史资料 preflight |
| POST /v1/users/me/profile | closed DTO → updateEffectiveProfile → 共享 updateCurrentProfile → 检查 →短事务 → 条件更新 | Service/MySQL、MVC、多字段失败、并发、媒体、拒绝/不可用、保护字段测试 | 直接进入安全检查；目标环境与真实 provider 尚未验证 |

成功为直接 application/json，字段为 `userId,nickname,avatarUrl,backgroundUrl,bio,isPlatformOperator`，无头像或背景为 null、无简介为空串。nickname 为非 null 历史事实；历史 NULL 不用虚构默认名字掩盖，preflight 要求修复，否则读取失败而不输出无效表示。POST 只接受 nickname/avatarObjectKey/backgroundObjectKey/bio，省略保留、null 拒绝、空请求 400、空 bio 清空；长度按 code point 验证。未知/保护字段 400，非法媒体引用的 errors 为 `/avatarObjectKey` + `INVALID_REFERENCE`。

统一 GlobalExceptionHandler 继续适配 Problem Details：401 unauthorized、403 forbidden、404 resource-not-found、400 validation-error、422 profile-content-rejected、503 profile-safety-check-unavailable。没有新包装或客户端可写的身份字段。nickname 无唯一约束；普通 role=1 标志 false，OPR=2/ADMIN=3 true，不查询 Activity，也不据昵称判断。已存在的有效封禁禁止资料操作，禁言保留 profile:edit；到期封禁不继续阻止。未增加治理状态机或 RBAC。

## 审核后原子生效

1. 从 DB 读取有效资料和 revision；本地字段、身份及媒体引用校验。
2. 新图片由 media 预先登记清理记录，再通过 COS copy 到 `profile-effective/{userId}/{random}`。原客户端 STS 只有 `user/{userId}/*` 上传权限，不能覆盖此快照。COPY 失败/响应丢失不发布；已登记快照由现有 PENDING cleanup 处理，通常一天后清理。
3. ProfileSafetyCheckService 在无资料事务中同步调用现有微信文本检查。仅明确 pass 通过；risky/reject 为 422，缺结论、review、远程异常为 503。空简介是清除动作，不发送空文本检查。
4. 新头像/背景快照经现有 AuditReservationService + media Job → 微信异步检查 → 已认证 callback → 审核结果事务。HTTP writer 只等待其特定 log 的持久终态，至多 10 秒（`app.user-profile.media-check-wait-millis` 默认 10000，代码上限 10000）；超时/未完成 503，不返回 pending/202，不发布候选。整体 media suggest、provider errCode 与 detail 不完整时保持待定，不将部分 detail 的 pass 当整体 PASS。
5. 所有必要检查通过后才开启 TransactionTemplate 短事务，锁用户行，重查权限/revision，绑定源对象及快照上传记录，按 revision 更新本次指定字段并递增 revision。读回完整最终记录，提交后失效旧派生缓存、清理已解除引用对象；仍被另一资料字段引用的对象不删除。

没有远程微信调用持有资料行锁，也没有持久化可自动生效的 pending profile。媒体 Job 可以在 503 后完成或记录结果，但没有任何 continuation 可提交资料；用户需重新提交。保留当前头像原 objectKey 不要求重新上传/重新复制，继续展示当前快照 URL。

## 并发、迟到和兼容

V29 增加非空 BIGINT profile_revision（历史默认 0）以及 nullable avatar_snapshot_key/background_snapshot_key。revision 单调递增，避免按字段相等或时间戳比较的 ABA 问题。旧请求 A 读取 revision 0 后检查较慢，B 在 revision 0 提交并变成 1；A 的最终短事务不能通过 0 的条件，503 且无资料写入。不暴露版本 token/ETag，不新增契约未定义的 409/412；消费者不依赖并发冲突检测承诺，只能重新读取后人工重提。不同字段并发也保守拒绝旧快照，不无条件重试。

四个字段 callback 对 PASS/REJECT 均不再写 user_profile，也不发送“已清空/已重置”的过时通知。审核事实和 Outbox 机制保留。旧 UserProfileUpdatedEvent listener 不再预约第二套写后审核。遗留 Job、已提交 trace、重试及 replay 因此都不能覆盖之后的新资料。

旧 GET 本人保留 Result/UserProfileVO、旧公开 GET 保留匿名政策和 UserPublicProfileVO；canonical GET 不沿用匿名白名单。旧 PUT 保留请求/响应 wire 和背景/性别字段，但委派同一 updateCurrentProfile；文本、头像、背景共同检查成功才一次提交，legacy writer 不能绕过检查。没有删除旧 endpoint：当前小程序 transport 已迁移，但不能证明外部/已发布旧客户端依赖解除；管理端认证仍内部调用旧 UserService 资料读取，不能改掉旧账户/权限表示。

## Like 与作者

Like 越权确认存在：PostService.pageUserLikedPosts 传入客户端 userId，InteractService.pageUserLikes 未比较当前主体。本轮在后者要求登录并强制本人，其他 userId 403，省略 userId 使用本人；查询依旧复用原帖子公开状态/审核条件。补回原 UserLikeBO 丢失的 like id，使既有 like 游标不会变 null；没有重写 Post/Like 或修改 Profile。本人权限单测 PASS，完整 Post/Like canonical 与分页/可见性端到端属于独立 follow-up。

帖子/评论使用 UserService.batchGetSimple 批量作者查询。UserSimpleBO 和原有 single/batch SQL 同次取得 `(role IN (2,3)) AS platform_operator`，未新增 N+1。没有把该字段偷偷增加到尚未正式化的 Post/Comment VO；客户端静态蓝勾/verified=true 以及作者 wire contract 是 follow-up。NotificationActor 仍保留历史快照含义。

## 修改文件（仅本轮触达，不等于全部工作区 diff）

- user：controller/UserController.java；dto/request/EffectiveProfileUpdateRequest.java；bo/EffectiveProfileBO.java、UserSimpleBO.java；vo/EffectiveProfileVO.java；service/UserService.java、impl/UserServiceImpl.java；entity/UserProfile.java；mapper/UserProfileMapper.java；converter/UserConverter.java；listener/UserProfileAuditListener.java；audit/AbstractUserProfileAuditCallback.java 与 Avatar/Nickname/Bio/Background 四个 callback。
- audit：service/ProfileSafetyCheckService.java、service/impl/AuditServiceImpl.java（仅 Profile media 完整结论 guard）。
- media：bo/ProfileMediaSnapshotBO.java；service/MediaService.java、MediaUploadRecordService.java、impl/MediaServiceImpl.java（快照准备与引用 preflight）。third/cos/client/CosClient.java、service/CosService.java（COPY adapter）。
- interact/service/impl/InteractServiceImpl.java（ownership 及 like cursor id）。上述路径以 `src/main/java/cn/jualn/miniapp/module/` 为根，third 路径以 `src/main/java/cn/jualn/miniapp/` 为根。
- resources：mapper/UserProfileMapper.xml；db/migration/V29__protect_effective_user_profiles.sql；db/validation/user_profile_contract_preflight.sql；db/README.md。
- 测试：EffectiveProfileDatabaseTest、EffectiveProfileMvcTest、ProfileCallbackIsolationTest、ProfileSafetyCheckTest、ProfileMediaCallbackTest、MediaProfileSnapshotTest、LikeOwnershipTest；调整 UserProfileUpdateTest（兼容断言；原缓存/返回值不变量转入真实 DB 测试）、UserServiceImplRoleChangeTest、UserServiceImplAdminCursorTest 的新增依赖。
- 文档：docs/domain.md（移除与新契约冲突的写后审核业务描述）、governance/commands.md、本文。保留已有异步/通知/其他模块未提交修改。

## 验证证据

使用 Java `D:\Jualn\Java\jdk-17.0.18+8`、项目 Wrapper 固定且已缓存的 Maven 3.9.14、MySQL 8.4.7。Wrapper 批处理本机启动缺陷与最初沙箱网络限制已分别识别；没有改 pom/Wrapper 规避业务断言。

| 边界 | 状态 | 实际证据及限制 |
|---|---|---|
| 主代码 | PASS | compile 与定向测试生命周期主编译，javac release 17 |
| 定向/相关验证 | PASS | 13 类、58 tests、0 failures/errors/skipped；最终 Surefire 输出 2026-10-07 16:34:30；包含 POST 字段非字符串类型拒绝 |
| Profile MySQL | PASS | EffectiveProfileDatabaseTest 20 tests；真实 Mapper/XML、Spring proxy、TransactionTemplate/commit/rollback、两线程竞争、读取和回调隔离；微信/COS/Redis 为替身 |
| HTTP | PASS（限定） | EffectiveProfileMvcTest 4 tests；真实 MockMvc/Jackson/Advice、路由/五字段/保护字段/Problem Details；没有加载生产鉴权拦截器，不能声称真实 token/HTTP 端到端 |
| 媒体/审核 | PASS（限定） | 文本分类、异步 log 终态、超时/中断、完整 provider 结论、隔离 callback、快照权限前缀及清理登记顺序；provider 和存储 SDK 被替换 |
| schema SQL 空库重放 | PASS | 专用 localhost:33989，user_profile_contract_replay，mysql CLI 执行 V1–V29；并非 Flyway history/validate 证据 |
| V28 历史资料升级 | PASS（SQL） | user_profile_contract_upgrade 先执行 V1–V28，插入 synthetic 历史 nickname/bio/avatar/source key，再执行 V29；revision=0，两 snapshot=NULL，原 nickname/bio/avatar 全保持；preflight 检出 1 个旧头像、0 个缺 revision/NULL nickname |
| 整仓测试/CI | NOT RUN | 当前变更选择定向边界；不把选择编译/执行集合当全仓健康证明 |
| 真实微信、COS、Redis、客户端 POST、生产迁移 | NOT RUN | 没有目标环境授权或凭据/客户端测试；不能由替身推导 provider 接受、回调时延或终端展示 |

命令见 [commands](../governance/commands.md)。本轮 JdbcUrl 为 `jdbc:mysql://127.0.0.1:33989/user_profile_contract_replay`，目标完全独立于已有 MySQL data directory；数据目录 `target/user-profile-mysql-20261007`。完成后停止本轮 MySQL 实例，保留可丢弃输出；没有清理其他服务或业务数据库。

## 启用、blocker 与 deferred

1. 核对目标数据库/备份、固定上述契约内容；停止所有旧应用实例的资料 HTTP writer、Job worker 和审核 callback，禁止旧新版本混跑。运行 preflight 并逐项处理历史 pending/rejected/unknown 和 NULL nickname。旧日志未持久化候选/旧有效版本，不能仅凭它自动恢复旧资料或自动认定当前值已通过；没有可证据化结论的资料必须由 owner 明确处置后开放消费者。
2. 在已核对的目标执行 V29，启动新代码；资料写入直接进入安全检查，不再依赖总开关。读取应只在历史资料验收后作为 canonical effective profile 启用。媒体历史源对象不自动转成“已审核快照”，需核对旧 STS 已过期和历史有效引用，不伪造 backfill。
3. 用明确授权的非敏感测试数据验证微信文本权限、图片 Job 调度、signature callback、稳定且可访问的 COS 快照 URL、服务端 COPY 权限和客户端无法写 `profile-effective/*`；确认微信整体结论和回调时延能在选定 HTTP/代理预算内完成。10 秒只是当前 bounded 等待预算，不是微信完成时延承诺；无法完成就 503，不能加自动生效或改为 202。具体请求缺少通过结论时返回 503，原资料不变。
4. 按用户确认直接替换旧逻辑，新旧写入口共享检查后原子生效保证；没有独立启用配置。审核 Job 后续只保留事实，不需要删除真实队列；未知远程提交不盲重发。处置 dead/unknown 遵循既有 Async 的明确 owner 与运维授权。
5. 当前小程序 transport/表示已迁移到 POST，现有工作已移除消费者本地编辑门禁；实际提交由 provider 对该次请求执行检查后决定，真实 HTTP、会话隔离和 provider 联调须在目标启用前验收。原权限仍从其所属账户边界读取。作者摘要 Contract、Like/Post 完整 canonical、旧路径弃用和 Activity publisher 语义继续 deferred。

不能直接回滚到旧乐观 writer/callback JAR：它会绕过版本、快照和审核保护。回滚须停写并设计受控兼容方案；恢复应用不撤销 MySQL DDL，不自动删除已绑定快照。

**小程序可开始依赖吗？** 可以以固定契约开展接入开发和本地受控验证；目前不能宣称已可依赖目标环境的 canonical 写能力。阻塞项是历史有效资料确认、目标环境 V29/实例切换、真实微信/COS 边界和客户端 POST 验证，没有默认关闭的写 gate；具体失败按 422/503 处理。没有发现须改 Contract 的实现矛盾。

## 2026-10-07 Profile 方法同步

当前 canonical 唯一写操作为 POST /v1/users/me/profile，operationId 与 DTO/Service/安全检查/原子生效不变。工作区已改为 POST 的 Controller、消费者 API 和回归脚本予以保留；本次增加旧 PATCH 返回 405 且不进入 Service 的 MVC 验证。没有已部署 PATCH 消费者的证据；若发布前发现旧方法依赖，须按协调文档增加有界兼容后迁移。旧 PUT 保留原兼容政策。

PASS：EffectiveProfileMvcTest 5 项，2026-10-07 17:22:58；小程序 test-profile-contract.mjs、test-current-user.mjs 和 pnpm typecheck。此前 58 项及真实 MySQL 证据对应方法调整前；本次未重跑数据库测试，没有变更 Service 或数据库。该次验证时真实微信/COS、目标部署、DevTools/真机仍 NOT RUN，后端总开关尚未移除（后续变更见下节）。小程序现有工作已移除本地编辑门禁，该次验证时实际写能力仍由目标 provider 安全开关决定（后续已移除），不能把本地可发送 POST 当作目标环境已可用。未修改 QR Login。

补充消费者 HTTP 证据：ProfileConsumerHttpTest 启动随机端口的临时 127.0.0.1 HTTP 服务，将请求送入真实 UserController/Jackson/Advice，再运行相邻小程序 test-profile-http-flow.mjs。实际 Page → Action/Store → Service/API → request 使用 POST；验证只提交改动字段、头像引用、重复点击单次写入、422/503 保留全部旧有效资料及草稿、成功用最终表示同步而不追加 GET。UserService、登录/平台及媒体上传均为替身，不含生产鉴权拦截器，不证明真实数据库提交或微信/COS 接受。与 EffectiveProfileMvcTest、ProfileSafetyCheckTest、ProfileCallbackIsolationTest 共 13 tests、0 failures/errors/skipped，2026-10-07 17:26:35 PASS。DevTools 自动化启动被 IDE service port disabled 阻塞；目标写 gate 保持关闭。

## 2026-10-07 移除资料写入总开关

用户明确要求不使用开关，直接替换旧逻辑。本次删除 UserServiceImpl 的 profileWritesEnabled 配置字段和入口拒绝；新 POST 与旧 PUT 无需额外配置即可进入同一安全检查链。保留文本/图片检查、媒体所有权、短事务、revision 并发保护及旧 callback 隔离；检查拒绝 422、检查不可用 503 仍保证原资料不变。无需配置 app.user-profile.writes-enabled，旧配置即使保留也不再生效。V29 仍是代码依赖的数据库结构。没有修改 contracts 或 QR Login。

PASS：本次定向 6 类、36 项测试，0 failures/errors/skipped（2026-10-07 23:17:14）；含真实 MySQL 20 项，验证无需开关的新旧写入、422/503 零资料修改、提交/回滚、revision 竞争和 callback 隔离。数据库为新建 localhost:33989/user_profile_contract_write_gate，先以 mysql CLI 重放 V1–V29；微信/COS/Redis 使用替身。没有执行目标部署或真实云联调。

## 2026-10-07 背景图 Contract 补漏同步

canonical closed request 新增可选 backgroundObjectKey；本人/他人 GET 和 POST 最终响应均增加必返回、可为 null 的 backgroundUrl。使用现有背景持久化、USER_BACKGROUND 检查、服务端快照与短事务，不新增迁移或开关，不清空历史数据。保留当前引用无须重新上传或检查。新增/null/type/direct-URL 等校验沿用现有边界。

本次文件：EffectiveProfileUpdateRequest、EffectiveProfileBO/VO、UserController、UserServiceImpl 与 Profile MVC/数据库测试；小程序 profile-contract/types、services/user、actions/user、编辑页 TS/WXML、user-profile WXML 与两组回归/资料说明。编辑背景使用现有 Media 上传，失败保留本地预览，成功通过最终响应更新唯一 currentProfile；两个主页共用背景展示，不增加另一资料 owner。

PASS：定向 6 类 44 tests，0 failures/errors/skipped，2026-10-07 23:27:51；其中真实 MySQL 26 tests，MockMvc 7 tests。新增背景单改、四字段组合成功、背景拒绝/不可用全不落库、引用错误、保留当前引用、他人可读及迟到背景回调隔离。小程序两组回归、typecheck、定向 ESLint 和文档完整性通过。目标微信/COS、真实 HTTP/DevTools/设备展示仍 NOT RUN；本地图片选择和网络由替身模拟。其他 Notification/QR Contract 变动未纳入本轮。
