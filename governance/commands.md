# Backend commands

所有命令从 backend 根目录执行。此目录记录真实仓库入口，不把示例命令当作已验证操作。

## 环境

Java 17 基线由 Java Engineering §0.2 定义，构建配置负责表达与执行。使用仓库已有 Maven Wrapper：Windows 为 `.\mvnw.cmd`，Linux/CI 为 `./mvnw`，不调用全局 Maven。

当前配置：pom 声明 Java 17、Boot 3.5.13，Compiler 配置 source/target 17；CI 配置 JDK 17。仓库没有 Toolchains 选择配置。Wrapper 固定 Maven 分发版本，不自动选择 JDK；不能声称仓库已强制所有本地测试使用 Java 17。使用已配置的 Java 17 开发环境，不每轮重新推断或修改系统环境。

普通任务直接使用 canonical command，不例行运行 java -version、mvn -version 或 Wrapper --version。仅在以下条件诊断环境：canonical command 出现环境故障、配置的 Java 17 toolchain 无法解析、修改 build/toolchain 配置、任务本身是环境诊断，或已有证据显示实际基线不一致。已确认的环境事实可复用，相关配置/环境变化或新故障才重新检查。

诊断命令（不是构建前置步骤）：

```powershell
java -version
.\mvnw.cmd --version
```

Toolchains 可将选定 JDK 与 Maven 启动 JVM 分离，后续若配置需一起核对编译、注解处理、测试 JVM 和 CI；当前不新增插件或机器专属路径。见 [Maven Toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html)。

## 风险与授权

- C0：Git/版本等检查；Maven 解析依赖可能写入本地缓存。
- C1：本地构建/隔离测试，仅产生可丢弃输出。
- C2：启动应用、访问本地数据库、执行本地迁移；先检查目标与副作用。
- C3：共享非生产环境修改；需要任务已授权该目标及操作。
- C4：生产、破坏性或安全敏感修改；需要具体目标与操作的明确授权。

风险分类不替代用户已有授权。命令文档本身不是操作授权；不得从安全变体推导删除卷、清库等变体。

## 常用入口

| ID | 风险 | Windows 命令 | 用途与边界 |
|---|---|---|---|
| GIT-STATUS | C0 | `git status --short` | 查看已有修改 |
| GIT-DIFF | C0 | `git diff` | 查看未暂存变更 |
| GIT-DIFF-STAGED | C0 | `git diff --cached` | 查看暂存变更 |
| JAVA-VERSION | C0 | `java -version` | 仅环境诊断：当前 Java 版本 |
| MAVEN-VERSION | C0 | `.\mvnw.cmd --version` | 仅环境诊断：Wrapper 与实际 JDK |
| MVN-COMPILE | C1 | `.\mvnw.cmd -B -ntp compile` | 编译主代码，不能证明业务通过 |
| MVN-TEST | 条件 C1 | `.\mvnw.cmd -B -ntp test` | 先确认测试依赖和环境隔离 |
| MVN-VERIFY | 条件 C1 | `.\mvnw.cmd -B -ntp verify` | 仅运行实际绑定到生命周期的检查 |
| MVN-PACKAGE-SKIP-TESTS | C1 | `.\mvnw.cmd -B -ntp package '-DskipTests'` | 跳过测试执行，通常仍编译测试；只证明构建/打包路径 |
| MVN-PACKAGE-NO-TEST-COMPILE | C1 | `.\mvnw.cmd -B -ntp package '-Dmaven.test.skip=true'` | 跳过测试编译和执行；只证明主代码/打包路径 |

上表列出仓库支持的命令入口，不代表本次任务已经执行成功。报告时记录实际环境、退出码和结果。
Maven 会下载依赖、写入本地缓存及 target/。不要用 `clean` 代替一般文件清理。

## 验证命令选择

范围和升级策略由 [Backend §9.12](../standards/backend-engineering.md#912-verification-scope-and-escalation) 拥有。下表仅映射可执行入口，不要求从 V0 顺序跑到 V5：

| 范围 | 命令入口或选择 |
|---|---|
| V0 | MVN-COMPILE；文档修改通常不需要 |
| V1 | 下方单类/单方法 Surefire 模板 |
| V2 | `-Dtest=FirstTest,SecondTest` 指定相关集合；必要时使用既有专项脚本 |
| V3 | 选择真正覆盖所需 MVC/Spring/数据库机制的测试，按 V1/V2 方式执行，并准备该测试要求的隔离设施 |
| V4 | MVN-TEST；默认测试集合不等于所有可能的集成测试 |
| V5 | MVN-VERIFY 或当前 CI 对应检查；仅证明实际绑定和执行的阶段 |

定向测试模板（替换为真实目标，PowerShell 保留参数引号）：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=TestClass' test
.\mvnw.cmd -B -ntp '-Dtest=TestClass#testMethod' test
```

Linux/CI 等价入口：

```bash
./mvnw -Dtest=SomeTest test
./mvnw '-Dtest=SomeTest#someMethod' test
```

`-Dtest` 限定 Surefire 执行集合，生命周期仍可能先编译其他测试源文件。若 testCompile 失败，目标测试是 BLOCKED，不是 FAIL 或 PASS；按 [Backend §9.13](../standards/backend-engineering.md#913-failure-classification-and-scope-control) 区分原因。不要自动修复无关测试或只运行可能陈旧的测试产物来宣称通过。

`-DskipTests` 跳过测试执行，测试源通常仍编译；`-Dmaven.test.skip=true` 跳过测试编译和执行。两种 packaging 入口均不能提供 Tests PASS，具体语义见 [Maven Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/examples/skipping-tests.html)。也不能靠跳过测试解除必需的行为验证。
全上下文测试可能读取应用配置或连接基础设施，不能未经检查就归类为无状态 C1；禁止使用生产凭据或生产数据。

## CI 与数据库

[CI/CD](../.github/workflows/backend-ci-cd.yml) 当前构建命令是：

```bash
./mvnw -B -ntp clean package -Dmaven.test.skip=true
```

它跳过测试编译和执行，不能把任务名 Build & Test 当成测试通过证据。
CI 另有临时 MySQL 上的 Flyway migrate/validate；具体参数以 workflow 为执行表示，规则见 [数据库说明](../src/main/resources/db/README.md)。

## EVENT-FOCUSED-TEST — 定向活动测试

[专项脚本](../tools/validate-event-information.ps1) 可只编译和执行指定测试，输出在 target/event-validation/，临时 pom 由脚本 finally 清理，不修改主 pom。
下列为参数模板，不是已执行结果。JavaHome 替换为本机真实 Java 17 目录；通过 Maven 参数使用项目 Wrapper：

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>' -TestNames CanonicalSubscriptionRouteTest,ActivityEnrollmentServiceImplTest
```

不要使用脚本的默认全集合：其中包含当前已删除的历史测试名。本目录使用显式 TestNames；选择与改动有关且仍存在的测试。不传 JdbcUrl 时数据库专项测试按其条件跳过，只能报告实际执行集合。
传 JdbcUrl 属 C2：脚本目前只接受 127.0.0.1 加显式端口/库名，数据库必须是已按测试要求应用迁移的一次性本地库；脚本使用 root/空密码，部分测试提交合成数据。业务库或生产库不适用。执行前重新核对脚本和测试前提，测试库生命周期由本次测试任务负责。

活动/公共事项公开接口分层与响应回归，可按影响选取下列相关集合（不是每个活动改动的默认必跑集，不连接数据库）：

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>' -TestNames CanonicalResourceMvcTest,SubscriptionTransactionBoundaryTest,ActivityParticipationPolicyTest,EventContactCodecTest,AttachmentReadServiceTest,ActivityLegacyRepresentationTest,ActivityServiceImplTest,ExamServiceImplTest,ActivityEnrollmentServiceImplTest,ExamSubscriptionServiceImplTest,ActivityRegistrationContractTest,CanonicalSubscriptionRouteTest
```

此集合覆盖 MVC 请求响应、业务读取判断、旧表示兼容及 Spring 事务代理；部分 Service 以及 Mapper / JDBC 使用测试替身，事务测试验证事务激活与 commit / rollback 调用，不证明真实鉴权、数据库锁、数据回滚、迁移或完整契约联调。新增或改变这些保证时另按相应机制验证。

## 尚未登记的运维操作

### Notification Contract focused validation

复用上述选择测试脚本，指定 `-TestNames`；不要直接运行全仓测试替代受影响边界。真实数据库测试只允许专用 disposable localhost schema，禁止指向业务库。

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:33987/notification_contract_replay_final' `
  -TestNames @('NotificationCenterDatabaseTest','NotificationCenterMvcTest','NotificationStreamCursorCodecTest','ReminderOccurrenceTest','NotifyServiceImplTest','NotificationDeliveryServiceImplTest','CanonicalNotificationCapabilityTest','LikeNotificationProducerTest','ActivityServiceImplTest','ExamServiceImplTest','WxMpNoticeSendServiceTest')
```

该 URL 仅为隔离环境示例，数据库须已完整执行测试所需迁移。`NotificationUpgradeDatabaseTest` 单独使用 `notification_contract_upgrade` 专用 schema：先迁移到 V25，加载 `src/test/resources/notification-v25-upgrade-fixture.sql`，再迁移到 V28。测试会验证明确编号的 synthetic fixture；不能在真实业务库运行。升级语义与恢复限制见 [数据库说明](../src/main/resources/db/README.md)。Redis 与微信在这些测试中使用替身；数据库测试使用真实 MySQL、Mapper 与 Spring 事务代理。

本仓库没有 Compose 文件，不登记 COMPOSE-* 命令。后续采用 Compose 时再按真实服务名和卷定义补充。
直接启动应用、数据库迁移/备份/恢复、Redis 清理和生产发布，先检查环境、脚本、恢复范围及已有授权；不提供可盲跑的通用命令。
生产发布当前由 CI/CD 调用服务器脚本；服务器当前状态不能从仓库文档推断。

变更常用命令时同步检查 AGENTS、CI、工具和引用文档，报告命令、退出码、目标环境与未验证范围。

## 专项验证入口

### Admin QR Login v2 focused validation

复用 `tools/validate-event-information.ps1`，显式选择 `AdminQrLoginRedisTest,AdminQrLoginMvcTest,WxMiniProgramCodeTest,GlobalExceptionHandlerTest`。Redis 测试仅从 `admin.qr.test.port` 接收一次性 loopback Redis；未提供时跳过，不能报告 Redis PASS。`admin.qr.test.container=jualn-admin-qr-v2-test` 显式允许测试重启本次创建的同名 AOF 容器；不能替换为业务容器。测试不读取应用 Redis/MySQL 配置，微信使用 loopback 模拟 HTTP，独立 JVM 崩溃使用 synthetic 数据。运行与恢复限制见 [运维手册](../docs/operations/runbook.md#12-admin-扫码登录运行与恢复)；本节登记验证方法，不记录历史执行结果。

### User/Profile focused validation

复用 `tools/validate-event-information.ps1` 并显式选择 `EffectiveProfileDatabaseTest,EffectiveProfileMvcTest,UserProfileUpdateTest,UserServiceImplRoleChangeTest,UserServiceImplAdminCursorTest,MediaServiceImplTest,ProfileSafetyCheckTest,ProfileCallbackIsolationTest,LikeOwnershipTest,MediaProfileSnapshotTest,ProfileMediaCallbackTest,AuditResultPersistenceServiceTest,AuditCompletedEventHandlerTest`。
数据库测试只接受 `jdbc:mysql://127.0.0.1:<port>/user_profile_contract_*` 的专用一次性数据库（已执行测试所需迁移），使用 root/空密码并提交 synthetic fixture；没有 JdbcUrl 时会跳过，不得报告数据库 PASS。测试替换微信、COS、Redis，使用真实 MySQL/Mapper/Spring 事务验证资料原子生效与并发；不证明真实 provider、鉴权或设备展示。

协调工作区的消费者 HTTP 验证可选 `EffectiveProfileMvcTest,ProfileConsumerHttpTest,ProfileSafetyCheckTest,ProfileCallbackIsolationTest`。ProfileConsumerHttpTest 需 Node 和相邻 `../mini-program/scripts/test-profile-http-flow.mjs` 及其已安装依赖；缺消费者脚本会跳过。测试自动启动并停止 loopback HTTP 服务，经真实客户端 Page/Action/Store/Service/request 与后端 MVC 验证 POST；UserService、平台、鉴权和媒体为替身，不证明数据库或微信/COS 联调。

若 Windows PowerShell Wrapper 在 `(Get-Item $MAVEN_M2_PATH).Target[0]` 处启动失败，可在确认同一故障后使用 `.mvn/wrapper/maven-wrapper.properties` 固定版本的已缓存 Maven 分发，并显式选择 Java 17；不要把其他机器的历史故障当作默认跳过 Wrapper 的理由。

按 Backend §9.11 的状态语义报告实际边界；省略不相关项，不机械填写所有层级。示例只展示格式：

```text
Focused: PASS — SomeTest#someMethod；已验证的性质
Related: NOT RUN — 当前变更未影响相关协作者
Repository-wide: NOT RUN — 局部变更无需扩大范围
Environment: 使用仓库 Wrapper / 已配置 Java 17 环境；未额外探测
```

环境行必须按真实证据填写；若只检查了配置，写“配置声明 Java 17，未执行构建”，不能声称 Java 17 路径已经运行。
执行失败时记录命令、阶段、对象、FAIL/BLOCKED、归因证据及未完成的必需证明；更广范围失败与 focused 结果分开。未执行、被跳过及被阻塞的测试均不写成 PASS。
