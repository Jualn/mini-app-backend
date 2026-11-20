# Backend commands

本文件记录**当前可执行入口、环境前提和授权边界**，不是每个任务的必跑清单。先按 [项目 AGENTS](../AGENTS.md) 定位实际变更，再按 [Backend §9.12–9.13](../standards/backend-engineering.md#912-verification-scope-and-escalation) 选需证明的性质。普通任务从「环境 / 常用入口 / 验证命令选择 / 测试资源选择」开始；下方按业务标题定位专项前提，不整份预读。已标明历史或合成前提的专项不能自动作为当前验收入口。

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

## 模块边界验证

Java 17 下执行 `.\mvnw.cmd -B -ntp '-Dtest=ArchitectureBoundaryTest,BoundaryGuardRulesTest' test`。检查当前生产源码对应的编译类型及源码 import / 全限定引用，不初始化业务类、不启动应用或访问数据库；不使用 SQL 字符串或注释推断 Java 类型依赖。覆盖实际公开操作签名、全模块职责依赖及跨模块直接/间接类型环；精确适配例外与未整改债务在 `src/test/resources/architecture/boundary-exceptions.tsv`，新增违规与过期例外均失败。输出 `target/boundary-guards/inventory.json`；这些检查不替代跨表 SQL、权限、事务和动态装配验证。

`InteractionProjectionDatabaseTest` 复用下文 ActivityReminderDatabaseTest 的独占 MySQL 8.4 / V84 专用 schema；另传 `-Dboundary.test.serverUuid=<已核对的独占实例 UUID>`。测试实际装配主体能力与 Mapper，核对计数/存在性投影及调用方事务回滚。不能指向业务库；它不替代提醒、内容安全审核及完整应用 HTTP 专项。

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

## 测试范围、环境选择与资源生命周期

先分别确定三个维度，避免把“真实 MySQL / Docker”和“运行整个测试套件”混为一谈：

| 维度 | 必须确认 | 默认选择 |
|---|---|---|
| 证明范围 | 变更行为、关联依赖和必要失效场景 | 最小充分的现有测试类/方法；依风险升级 |
| 真实机制 | 纯 Java、Spring 代理、HTTP、MySQL 事务/迁移、Redis、进程崩溃等 | 只使用能证明该性质的真实组件；不 mock 被验证机制 |
| 资源生命周期 | 目标身份、隔离/重置能力、创建者、可保留或清理的范围 | 复用合格专用服务；测试数据按测试契约隔离 |

- **现有本地测试数据库并不天然授权自动化使用。** 先核对它是任务获准使用的专用测试实例、实际 loopback 端口/身份、schema 所有权和与其他测试的冲突。不得选业务库/生产库；不得以 `test` 命名或 root/空密码作为安全证明。
- 普通 Mapper/事务/迁移验证若用例允许，**复用专用 MySQL 服务，创建本次独占的全新 schema**。新的 schema 不等于新 MySQL 服务器或新 Docker 容器。要求固定 schema 不存在、完整迁移、实例 UUID、独占实例或一次性数据库的专项，按其当前真实前提执行；未经修改并验证测试夹具，不放宽为复用脏库。
- Redis 用专用测试实例；只有现有测试明确支持隔离的 DB/key namespace、可靠重置和并发安全时才复用测试状态。涉及 flush、AOF 重启、pause、配置变更、实例级故障注入时须有独占、可销毁、明确归属的实例；不对共享/业务 Redis 做实例级操作。
- **按任务/资源所有权，而不是按聊天创建环境**。常驻测试 MySQL/Redis 服务可保留给后续任务；本次临时 schema/容器应记录创建者、用途、目标和清理边界，成功后先保留需要的证据，再在**已授权且测试契约允许**时清理本次拥有的资源。不要自动 DROP 现存库，也不要停止或删除非本次创建的实例。失败或 Unknown Outcome 先保留现场与报告，不为求干净不断新建容器；排查后再决定受控清理。
- 默认使用已有 Maven Wrapper、Surefire 报告和已登记的 validator；不要为每项需求新建 `validate-xxx` 或环境脚本。只有已证明反复高成本、现有工具无法覆盖且具稳定复用价值时，才考虑统一的轻量环境准备/清理机制。首次失败只读取相关报告、真实运行目标和前提错误，定点修复后优先复测；无关失败与环境阻断按 Backend §9.13 区分。
- 输出证据要写明测试选择器、**实际运行的类/方法**、使用真实/替身的机制、MySQL/Redis 测试目标标识（不含凭据）、PASS/FAIL/BLOCKED/NOT RUN、SKIPPED 和剩余必需验证。切勿用旧的 HTTP/JSON 证据、编译或跳过状态代替当前 PASS。

## CI 与数据库

本地依赖入口为 `.\tools\local-dev.ps1`（默认 Start）；`-Action Status/Info/Validate/Migrate/Logs/Stop` 分别查看进程、数据库版本、校验、迁移、日志和停止。`-Service Redis/Flyway` 支持单独控制；详细命令与首次旧库 baseline 前提见 [数据库说明](../src/main/resources/db/README.md#本地开发容器管理)。Start / Migrate / Baseline 是本地数据库操作（C2），不是只读构建；Info 是只读版本查看。该入口固定本机开发库，不用于生产，不自动基线或 repair，迁移历史留在 MySQL。复用原 Redis 容器和数据卷，Stop 不删除卷，也不停止 Windows MySQL / IDEA 后端。

[CI/CD](../.github/workflows/backend-ci-cd.yml) 当前构建命令是：

```bash
./mvnw -B -ntp clean package -Dmaven.test.skip=true
```

它跳过测试编译和执行，不能把任务名 Build & Test 当成测试通过证据。
CI 另有临时 MySQL 上的 Flyway migrate/validate；具体参数以 workflow 为执行表示，规则见 [数据库说明](../src/main/resources/db/README.md)。

## 业务专项使用说明

专项是可选的真实测试入口和严格环境契约，并非每次修改都要执行。先查实际测试类、当前 Contract/实现和测试资源前提；找不到对应当前用例时不得盲跑旧命令或由历史合成夹具推出生产结论。以下现有专项说明保留其原有独占、全新 schema、禁止 DROP/重启共享实例等限制；通用资源复用原则不覆盖具体用例的强制前提。

## 积分纠错 r6 验证

`ManagedPersonDatabaseHttpTest#pointsR6*` 复用独立 Person 的实例 UUID、环境变量凭据及全新独占 `independent_person_test_<suffix>` schema 前提。执行 V1→V88，另建本次独占 `<schema>_upgrade` 验证 V87→V88 保留历史12分审核/GRANT，validate及重复migrate。真实 MySQL/MyBatis、Spring事务、Points Service/MVC验证连续改分、最终撤销、同key恢复、失败回滚、并发改分/撤销及审核/本人结束、四状态统计和CURRENT分页；真实Unified grant及owning policy验证独立纠错、材料/完整账本隔离、人员归档/组织停用及撤权。会话、Points admission/material/notification adapter为显式替身，不能作为完整Provider验收。

`PointsNotificationDatabaseTest` 使用同一本次拥有的专用实例及全新 `points_notice_test_<suffix>` schema；真实Job/worker/通知持久化验证单条纠错通知、换绑/解绑跳过证据、账号复核、去重、重试和只读站内降级；Person binding reader是明确替身。它使用root/空密码，只可用于已核对的loopback独占可销毁实例；不得指向共享或业务服务。`PointsTransactionBoundaryTest` 另验证真实Spring beforeCommit撤权复核，其JDBC/owner读取为替身。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=ManagedPersonDatabaseHttpTest#pointsR6*,PointsTransactionBoundaryTest,PointsNotificationDatabaseTest,PointsBoundaryTest,PointsResultNotificationsTest,PointsEntryRoutingTest,PointsDependencyDiagnosticsTest,RootManagementPolicyTest,UnifiedR3PolicyTest,PointsIntegrationCatalogTest,ArchitectureBoundaryTest,BoundaryGuardRulesTest' '-Dmanaged.person.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/independent_person_test_<fresh-suffix>' '-Dmanaged.person.test.serverUuid=<verified-uuid>' '-Dpoints.notice.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/points_notice_test_<fresh-suffix>' test
```

测试不自动DROP schema；失败保留现场与报告。输出 `target/points-r6/http.json`、`results.json`、`current-statistics-explain.json` 和Surefire。EXPLAIN只覆盖小型夹具，不作为生产容量证明。用Contracts固定版本Redocly从当前canonical解引用到 `target/points-r6/openapi.json` 后，执行 `node tools/validate-points-http.mjs target/points-r6/http.json --component --bundle=target/points-r6/openapi.json --output=target/points-r6/wire-validation.json`；静态路由覆盖与实际已捕获响应分别计数，不能将28条静态路由当作28项真实接口验收。真实COS私有视频上传/登记/下载、外部供应商、客户端及目标环境部署仍需独立验收。

## 积分受理组织专项验证

复用下方独立 Person 测试的 loopback 实例身份、凭据环境变量及全新独占 `independent_person_test_<suffix>` schema 前提。`PointAcceptanceSettingsDatabaseTest` 执行 V1–V68 迁移/validate，并用真实全局 RbacCatalog 校验器覆盖 V67 混合摘要与 V68 修复，使用真实 MySQL/MyBatis、Spring 事务、Person/RBAC 锁和 MockMvc；会话与 capability/职责见证为明确的合成 adapter，不是实际 Provider 或微信会话联调。测试关闭上下文，不自动 DROP schema；失败先读报告与具体前提再定点修复。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=PointAcceptanceSettingsDatabaseTest,PointsDependencyDiagnosticsTest,PointsBoundaryTest,UnifiedR3PolicyTest' '-Dmanaged.person.test.jdbcUrl=jdbc:mysql://127.0.0.1:<approved-port>/independent_person_test_<new-suffix>' '-Dmanaged.person.test.serverUuid=<verified-instance-uuid>' test
```

## 业务时间与 V73 定点升级

`BusinessTimeDatabaseTest` 使用下述相同 loopback / UUID / 环境变量凭据约束，并自行创建全新独占 `independent_person_test_<suffix>` schema；库已存在则拒绝运行，不自动 DROP。测试从 V72 插入已知旧 UTC 图片审核与其他来源对照，再迁移 / validate 到 V73；覆盖默认和生产 JDBC 参数一致、真实 Hikari `+08:00` 会话、SQL 时钟、DATETIME / TIMESTAMP 微秒往返、北京零点过滤、真实 Mapper 过期清理和 User 限制边界，在 UTC / 洛杉矶 / 上海 JVM 默认时区下验证。`BusinessTimeSchedulingTest` 验证实际 Spring cron 触发计算及 UTC Clock 保持；`WechatPointMaterialSafetyDatabaseTest` 可顺序复用同一本次拥有的 schema，使用真实审核日志持久化 / 事务，WeChat / COS 是替身。联合运行使用 `-Dsurefire.runOrder=alphabetical` 确保 fresh-schema 测试先创建库，不能把该组合指向现有业务库或另一个任务的库。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=BusinessTimeDatabaseTest,BusinessTimeSchedulingTest,WechatPointMaterialSafetyDatabaseTest' '-Dsurefire.runOrder=alphabetical' '-Dmanaged.person.test.jdbcUrl=jdbc:mysql://127.0.0.1:<approved-port>/independent_person_test_<new-suffix>' '-Dmanaged.person.test.serverUuid=<verified-instance-uuid>' test
```

报告新连接和隔离数据的实际结果；本测试不修复业务库，不证明旧普通业务字段在历史非上海主机 / 数据库会话中的数据语义，也不证明生产会话、真实微信回调、Admin / Mini 客户端展示已验收。业务 V73 执行前提和精确恢复见 [runbook §14.3](../docs/operations/runbook.md#143-积分图片微信检查恢复)。

## 独立Person聚合专项验证

使用已批准本地开发 MySQL 的全新 `independent_person_test_<suffix>` schema；必须显式核对 `managed.person.test.serverUuid`，拒绝复用已有库，不自动 DROP。凭据只通过 `MANAGED_PERSON_TEST_DB_USER` / `MANAGED_PERSON_TEST_DB_PASSWORD` 环境变量传入，不放命令行或证据。Java 17，串行执行 Maven，防止共享 target 竞争。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=ManagedPersonDatabaseHttpTest,ManagedPersonBoundaryTest,PersonAuthenticationBoundaryTest,PersonEntryRoutingTest,PointsBoundaryTest' '-Dmanaged.person.test.jdbcUrl=jdbc:mysql://127.0.0.1:<approved-port>/independent_person_test_<new-suffix>' '-Dmanaged.person.test.serverUuid=<verified-instance-uuid>' test
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/independent-person/openapi.json
node tools/validate-managed-person.mjs
```

专项运行 V1–V66 Flyway、实际 schema/行/唯一性、事务回滚、双方绑定、期限/撤权竞争、未知结果恢复、名单/导师/Points/Learning 责任与权限边界。升级用例另建同后缀 `independent_person_upgrade_*`，先证明旧 Points 在破坏性 DDL 前阻断，再核对保留的独立主体、职责及 ACCOUNT 任务。合成 storage sentinel 不代表旧授权获准转移。Admin身份/capability、密钥/技术命名空间与部分业务副作用为测试 adapter；真实主库 Mapper、Service 和事务不替换。

完整应用链路必须另用独占 Redis（显式 `POINTS_INTEGRATION_REDIS_PORT`）和全新 `points_integration_test_<suffix>`，核对相同实例 UUID。`unified.r3.fixture` 仅是已有测试夹具的确认值，明确标记合成目录就绪状态与批准来源，应用不以该值或库名选择政策，不扩张 Role/Permission/31项 Duty 定义。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=PointsIntegrationHttpTest#v66*' '-Dpoints.integration.jdbcUrl=jdbc:mysql://127.0.0.1:<approved-port>/points_integration_test_<new-suffix>' '-Dpoints.integration.serverUuid=<verified-instance-uuid>' '-Dunified.r3.fixture=EXPLICIT_SYNTHETIC_R3_APPROVAL' test
```

完整应用运行后再执行 `node tools/validate-managed-person.mjs --full`，对捕获的业务响应另做当前契约校验；已脱敏凭证不计入。该组合实际执行完整 Spring 应用、Sa/扫码会话、Redis、RBAC、Person、Points、私有材料 owner、Learning 和通知持久化。外部微信、COS/STS、安全供应商仍是显式替身；不是设备或真实 Provider 端到端验收。既有目录未授调整/批准能力时冲正保持403；同人换号自审的服务专项与真实目录拒绝分别留证。Learning 通知用首版结构化响应核对 assignmentId 和原实际投递账号。

证据：`target/independent-person/schema.json`、`http.json`、`wire-validation.json`（核心20个管理操作），`target/points-integration/http.json`（完整应用响应）、`target/independent-person/v66/`（手动 SOURCE 重建/升级与行证据）及 Surefire。validator 同时复核固定三份 handoff 与同一135文件 SHA 清单。源码/编译/Mock/SQL/数据库/完整应用分别标层级；以上不代表主开发库、服务器、真实名单、外部 Provider 或 Admin/Mini 发布。V66 手动执行和恢复见[数据库说明](../src/main/resources/db/README.md#独立-person-旧结构清理-v66)。

## 历史专项命令

以下较早 Person/名单/Points r3 夹具和静态矩阵保留历史证据路由，不作为独立 Person r5 当前验收入口。运行旧专项前须先核对其 User 主体前提，不以旧通过结果接受新契约。

## 党建名单与导师资格验证

Java 17，使用本任务独占 MySQL 8.4、root/空密码、全新 `person_foundation_test_<suffix>` 及对应 upgrade schema；禁止指向业务库或复用旧测试 schema。测试启动真实 Flyway、Spring 代理、主库 Mapper、事务与 loopback HTTP；admin 会话/capability 是显式合成 adapter，Mini MVC 使用 Sa 及限流替身，不构成真实微信/Redis/客户端联调。合成数据与测试密钥仅存在于隔离测试，不能用于实际环境。`#qualification*` 必须保留，避免运行继承的 Person 用例。

```powershell
$env:JAVA_HOME = '<本机 Java17 目录>'
.\mvnw.cmd -B -ntp '-Dtest=PeopleQualificationDatabaseTest#qualification*,PeopleQualificationPolicyTest,PersonAuthenticationBoundaryTest,PostOrganizationPolicyTest,CommentServiceImplTest' '-Dperson.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/person_foundation_test_<new-suffix>' test
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/people-qualification/openapi.json
node tools/validate-people-qualification.mjs
```

Provider wire 与覆盖结果写入 `target/people-qualification/http.json` / `wire-validation.json`，包含当前24个 operation 的表示及强版本核对页；Mini MVC 与真实 HTTP 的边界须分开说明。另在新的 schema 运行下文 Person 全量专项，以及 RBAC / role namespace 回归。Points 阶段资格和 Learning 所用 Person 阶段 Reader 可消费真实标明来源的名单身份，不意味着受理组织/完整花名册或实际积分、任务链路已获授权。

实际联调需要 V64 目标迁移/validate 证据、稳定独立的加密与匹配密钥、独占可用 Redis、普通/admin 会话、既有目录的逐能力 readiness/合法 Assignment、精确组织/整人目标范围，以及管理员核对过的来源。真实数据库、目录发布、微信会话、Admin/Mini 消费、完整 Comment 审核/通知与业务验收分别记录；不可用上述 fixture 宣称部署完成。

## Admin query focused validation

Java 17 下先执行定向回归，再使用本任务独占、空 Redis 和全新 MySQL schema 运行五个新读取及父附件投影的实际 HTTP 验证。数据库 URL 只接受 `jdbc:mysql://127.0.0.1:<owned-port>/points_integration_test_<new-suffix>`，root/空密码；测试继承 Points 集成夹具，显式发布合成职责目录，微信/COS/安全适配器为替身，不得使用业务库或共享 Redis。

```powershell
$env:JAVA_HOME = '<本机 Java17 目录>'
.\mvnw.cmd -B -ntp '-Dtest=AdminQueryBoundaryTest,CanonicalResourceMvcTest,AttachmentReadServiceTest,AdminActivityConverterTest,UnifiedR3PolicyTest,AdminReviewAuthorizationTest' test
$env:POINTS_INTEGRATION_REDIS_PORT = '<owned empty Redis port>'
.\mvnw.cmd -B -ntp '-Dtest=AdminQueryIntegrationHttpTest#query*' '-Dpoints.integration.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/points_integration_test_<new-suffix>' test
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/query-optimization/openapi.json
node tools/validate-admin-query.mjs
```

必须保留 `#query*` 选择器，以免执行继承的其他 Points 用例。测试将真实 wire 写入 `target/points-integration/http.json`；schema/投影精确对应校验写入 `target/query-optimization/wire-validation.json`。附件比较在同一合成数据下实际请求旧单项路由和新 batch，记录请求数、响应字节、Mapper 查询数和单次耗时至 `attachment-comparison.json`；不代表生产延迟或全页面性能。仅测试拦截器在父事务提交后的展开查询注入故障，以验证已保存主体仍返回成功和 ETag。运行后保留测试结果与隔离环境身份，部署、真实授权目录及外部 Provider 另验。

## Unified local membership focused validation

先执行下节 Person 命令（包含三个局部成员 HTTP 和全量/局部事务竞争用例），再生成同一 canonical bundle：

```powershell
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/person-foundation/openapi.json
node tools/validate-unified-local-membership.mjs
node tools/validate-rbac-foundation.mjs
```

局部validator检查局部成员接口的实际wire/no-store；不代替完整Person政策副本、受限委派、Points r3、Learning新范围或发布验收。`validate-person-foundation.mjs`已对齐Person r3 / 20操作，包含listOrganizationMembers的实际OrganizationMemberPage表示及UNKNOWN不影响已登记关系的数据库回归；`validate-unified-authorization.mjs`同时核对七个授权接口和实际Points/Learning wire，逐项列出未验证成功表示。RBAC回归选`RbacDatabaseTest,RbacRoleNamespaceTest`，数据库前提与原专项一致；禁止并行Maven写共享target。


完整应用 r3 与新 Learning 范围的实际集成用例使用 `PointsIntegrationHttpTest`，`points.integration.jdbcUrl` 必须为全新独占 `jdbc:mysql://127.0.0.1:<owned-port>/points_integration_test_<suffix>`，并显式设置 `POINTS_INTEGRATION_REDIS_PORT` 为本任务独占 Redis；不可使用共享 Redis 或业务库。该测试通过实际扫码/Sa、主库 RBAC/Person/Points/Learning 和通知持久化，外部微信、COS 与候选文本安全明确使用 Adapter 替身。测试 profile 明确包含本次文本任务权限，不发布生产目录。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=PointsIntegrationHttpTest' '-Dpoints.integration.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/points_integration_test_<new-suffix>' test
node tools/validate-unified-authorization.mjs
```

## Person foundation focused validation

使用本任务独占 MySQL 8.4、root/空密码、显式 loopback 端口。`person_foundation_test_<suffix>` 和对应 `person_foundation_upgrade_<suffix>` 必须全新，不复用业务/共享库，测试不 DROP 已有库。Java17：

```powershell
$env:JAVA_HOME = '<本机 Java17 目录>'
.\mvnw.cmd -B -ntp '-Dtest=PersonFoundationDatabaseHttpTest,PersonAuthenticationBoundaryTest' '-Dperson.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/person_foundation_test_<new-suffix>' test
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/person-foundation/openapi.json
node tools/validate-person-foundation.mjs
```

真实 MySQL、Flyway 空库/升级、Service 代理、Reader、范围控制执行器和 loopback HTTP；会话/capability adapter 是合成替身，TestCatalog可人为撤回确认以验证失败关闭，不改变现已CONFIRMED的正式受控目录。HTTP原始响应与校验输出在target/person-foundation。正式Sa/Redis/微信扫码及真实人员授权另验。

线下 `cn.jualn.miniapp.module.person.service.bootstrap.PersonInitialAuthority` 不是应用 Bean。先通过受控运维调用其 `inspect` 保存原计划，需明确已有 ROOT、独立接收者、domain/actions/目标、批准文件 SHA/引用、执行引用、原因/期限和 JDBC tunnel；不读取应用默认库，不迁移、不建立 ROOT。运行 classpath 用 main classes 加 Maven runtime dependencies。CLI `<jdbc-url> inspect <preparation.json>` 只读生成计划；`<jdbc-url> <plan.json>` 默认只验证，执行需附 `--execute <approved-plan-sha256>`。数据库身份只从 `PERSON_AUTHORITY_DB_USER/PASSWORD` 环境读取，不能放入 URL/参数/计划。撤销控制需新的批准计划及 revokeScopeGrantId，不能在线改 ADMINISTER。

本任务仅授权隔离验证，没有任何真实名单/批准文件/执行授权；不得把测试 fixture 或示例命令当作生产批准。恢复先关闭新入口，保留事件/收据并用原 key 查询/恢复结果，不删事实。完整约束见 [人员基础](../docs/person-foundation.md)。

## RBAC offline governance validation

指定初始 ROOT 的实际运维迁移见 [离线治理 §8](../docs/rbac-offline-governance.md#8-指定初始-root-的运维迁移)，不使用下文仅合成库的旧双签 CLI。新入口为 `tools/invoke-rbac-initial-migration.ps1`：显式 `-JdbcUrl`、`-RuntimeClasspath`；`-Mode Inspect` 另需环境 ID、ROOT User ID、执行/批准引用和批准文件；默认 `-Mode DryRun -PlanPath` 只验清单，执行还需 `-Mode Execute -ApprovedPlanSha256`。可显式 `-EnvironmentFile .env` 仅临时读取 MYSQL_USER/PASSWORD，或预设 RBAC_INITIAL_DB_USER/PASSWORD；不打印凭据。输出 Inspect 的 JSON 需原样保存为 plan 再计算文件 SHA-256，不重建 operationId。

运行 classpath 用已编译 main classes + `mvnw.cmd dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/rbac-initial-runtime-classpath.txt'` 的依赖。工具不执行 DDL；V45 按已批准目标单独执行。隔离验证 `RbacInitialMigrationTest` 只接受全新独占 `jdbc:mysql://127.0.0.1:<port>/rbac_initial_test`，会完整重放/验证 V1–V45 及 V44→V45、事务/审计失败回滚、原计划重放、过期/账号/目录变化拒绝和 default/prod profile 失败拒绝；不能指向业务库。

服务器的 ROOT 初始化、计划交接、应急恢复及旧任命盘点使用 [ROOT 运维流程](../docs/operations/runbook.md#root-运维与旧任命退出)。通过生产发布包内的 shell 脚本及同包应用 JAR 运行。

先运行`node tools/validate-rbac-offline-plan.mjs`，只核对ROOT/NOTICE_MONITOR完整成员及namespace交集、V41与Catalog哈希、生产support仍空和V43仅建两表，输出`target/rbac-offline-support-plan.json`；不执行SQL、不启用目录。

`RbacOfflineDatabaseTest`只接受本次独占可销毁MySQL8.4的`jdbc:mysql://127.0.0.1:<显式端口>/rbac_offline_test`、root/空密码；`rbac_offline_test`及`rbac_offline_upgrade`必须不存在。测试创建合成User、测试专有guard、临时Ed25519批准密钥，真实重放/升级V43、并发/失败trigger/commit确认丢失/CLI子进程。不能用业务实例或复用共享MySQL。使用既有选择测试入口：

```powershell
.\tools\validate-event-information.ps1 -Maven .\mvnw.cmd -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' -JdbcUrl 'jdbc:mysql://127.0.0.1:<disposable port>/rbac_offline_test' -TestNames RbacOfflineDatabaseTest,CurrentAuthorizationTest
```

普通RBAC机制回归另用`RbacDatabaseTest`及其原专有三个schema，不能因同一次传入offline URL导致其跳过就称通过。

独立类`cn.jualn.miniapp.module.authorization.service.RbacOfflineTool`通过Java17和显式运行classpath调用（正常编译输出及Maven runtime依赖）；不启动SpringBoot、不读application配置、不迁移数据库、不创建guard。CLI参数为`<synthetic-jdbc-url> <signed-request.json>`，默认dry-run；仅末尾显式`--execute`执行。`<url> inspect <userId[,userId]>`读取版本/Subject指纹及当前事实。DB身份仅从显式`RBAC_OFFLINE_DB_USER`和`RBAC_OFFLINE_DB_PASSWORD`环境读取；签名请求不含密码/Token/私钥。当前仅能在测试fixture已建立guard和完整目录的专用库运行；没有生产模式/启用开关。请求/签名格式与恢复边界见[离线治理](../docs/rbac-offline-governance.md)。

## RBAC offline to online integration validation

`RbacOfflineOnlineHttpTest`只接受独占MySQL8.4的`jdbc:mysql://127.0.0.1:<port>/rbac_offline_test`、root/空密码及环境变量`RBAC_ONLINE_REDIS_PORT`指定的空独占Redis；schema必须不存在，不与P0测试同批创建。测试先迁移V1–V43、合成guard/双签，调用真实独立CLI，再以完整MiniAppApplication随机loopback端口运行真实QR/Token/Redis/九HTTP与当前主库事务。仅WxClient外部身份/码图和受控完整Catalog支持视图是测试fixture；不替换Reader、权限缓存、治理或业务Service。

```powershell
$env:RBAC_ONLINE_REDIS_PORT = '<owned empty Redis port>'
.\tools\validate-event-information.ps1 -Maven .\mvnw.cmd -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' -JdbcUrl 'jdbc:mysql://127.0.0.1:<owned MySQL port>/rbac_offline_test' -TestNames RbacOfflineOnlineHttpTest
```

Source Switch的测试子进程包装JDBC真实commit后抛确认丢失，正式CLI/guard/签名/事务代码不带failpoint。负例只修改本次合成库，finally恢复原事实；不暂停共享数据库、Redis或应用。输出`target/rbac-offline-online/http.json`（普通登录/QR/token响应脱敏、幂等key仅hash）、steps/facts与unknown日志，不输出私钥。全链是一个连续集成测试，不把各HTTP请求算成独立JUnit数量。

从本次canonical entry重新生成bundle，再验证九操作的实际响应/写请求，全部只写Backend/target：

```powershell
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/rbac-offline-online/openapi.json
node tools/validate-rbac-offline-online.mjs
```

完整HTTP与P0数据库回归须分别留存XML/日志；P0会创建相同schema，不能直接在已有闭环库中运行。数据/日志先保留，再仅清理本次拥有的测试库或容器。未提供专用条件而跳过不算PASS；真微信、设备、生产执行/发布与全库恢复另列。

## RBAC foundation focused validation

授权证明性能比较使用`RbacProofPerformanceTest`。它只通过测试fixture创建合成数据，走loopback HTTP测试桥接到真实Reader/QueryService代理和MySQL，**不是完整Controller/身份管线耗时**；完整九HTTP另跑P1和Offline→Online。使用独占MySQL8.4、root/空密码，固定`rbac_offline_test`/`rbac_offline_upgrade`库及`codex.task=rbac-proof-p11`标签；容器名须`codex-p1-mysql-<8 hex>`，通过`RBAC_PERF_MYSQL_CONTAINER`显式传入。测试会校验所有权并重启该容器，重新读取随机host端口；不得指向共享实例。建容器时关闭`innodb_buffer_pool_load_at_startup`及`innodb_buffer_pool_dump_at_shutdown`，固定buffer pool和资源；首请求仅代表MySQL重启后的InnoDB冷状态，不声称OS缓存清空。

使用Java17与Wrapper执行`mvnw.cmd -B -ntp -Dtest=RbacProofPerformanceTest -Drbac.perf.phase=before -Devent.test.jdbcUrl=jdbc:mysql://127.0.0.1:<port>/rbac_offline_test test`；首次两库须不存在，保留数据后以待比较源码重新编译，改`phase=after`读取相同数据。phase只是输出/建库标签，不会自动切换旧实现，须保存两份真实源码hash，不能同一实现换标签声称优化。`rbac.perf.reuse=true`仅供明确拥有的既有合成基线库恢复测量，不重建数据。每场景重启、首请求、3次预热、12个暖样本，输出`target/rbac-proof-p11/performance-<phase>.json`中的Mapper查询数、原始耗时、参数化SQL执行计划、固定数据身份/版本和数据库配置/状态。Mapper计数不含BEGIN/COMMIT，不能推导生产p95；操作前核对测试源码与目标，测量期间不并发运行其他回归。全仓默认跳过此类，必须单列实际性能运行。

先运行 `node tools/validate-rbac-foundation.mjs` 核对相邻canonical目录与应用受控副本、阶段输入hash、9操作及29/34候选追溯，输出 `target/rbac-g0-evidence.json`、`target/rbac-d07-candidates.csv`。历史输入位置来自canonical `rbac-v1-inputs.json`，文件不可用或变化时停止对应静态核验，不生成批准结论。

`RbacDatabaseTest` 只接受独占一次性MySQL的固定schema名称；四个库 `rbac_foundation_test`、`rbac_foundation_upgrade`、`rbac_foundation_baseline`、`rbac_owner_catalog_upgrade` 必须尚不存在。测试使用root/空密码，创建并迁移合成数据库，包含审计失败trigger、表暂时改名故障与并发事务；不可使用业务实例。沿用既有选定测试脚本：

```powershell
.\tools\validate-event-information.ps1 -Maven .\mvnw.cmd `
  -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:<disposable port>/rbac_foundation_test' `
  -TestNames RbacDatabaseTest
```

上述RbacDatabaseTest覆盖六表及内部主库Reader/事务，不是九个RBAC HTTP或人员迁移验收。ROOT 平台所有者增量同时覆盖 V51→V52 目录升级保留人员/授权/可用状态、派生能力精确域和真实来源、停用/受限/撤权收敛。非数据库定向回归使用已有 Surefire 模板选择 `CurrentAuthorizationTest,RbacHttpAdapterTest,AdminContentOwnerScopeTest,ArticleAuthorizationTest,AdminReviewAuthorizationTest,OfficialContentAuthorizationTest,PostDictionaryAuthorizationTest,LearningTaskAuthorizationPolicyTest,PersonAuthenticationBoundaryTest,PointsBoundaryTest`；这些测试不能代替真实登录、目标环境运行目录发布和 Provider 验收。G2另按下述方法验证Sa/QR及旧owner。专用Redis不得复用业务6379实例。已实现能力默认提供运行支持，没有Bootstrap或source切换入口，见[RBAC基础](../docs/rbac-foundation.md)。

G2静态检查使用 `node tools/validate-rbac-integration.mjs`，只读相邻Contracts/历史产物，输出target下211入口/132动作/29+34候选对照及消费者行/hash，不修改其他工作区。显式回填和启动门禁见[RBAC基础](../docs/rbac-foundation.md#g-authority-建立部署顺序与验证)及[DB说明](../src/main/resources/db/README.md#rbac-v41-与-authority-启动门禁)。validation下回填SQL是需明确目标批准的手动写，不能按示例推导生产授权。

定向接入测试选择 `P1SecurityDatabaseHttpTest,CurrentAuthorizationTest,AdminQrLoginRedisTest,AdminQrLoginMvcTest,ReportServiceImplTest,ActivityServiceImplTest,PublicEventServiceImplTest,MediaServiceImplTest,CommentServiceImplTest,PostServiceImplTest,UserServiceImplRoleChangeTest,AdminQrLoginConfigurationTest,PostDictionaryAuthorizationTest,ArticleAuthorizationTest,PostOrganizationPolicyTest,LearningTaskHttpGateTest,AttachmentReadServiceTest,ActivityRegistrationContractTest,OfficialContentAuthorizationTest`。P1需独占MySQL/Redis及登记的容器pause授权，不能和其他数据库测试并行暂停同一实例。QR额外提供JVM system property `admin.qr.test.port`（例如临时JAVA_TOOL_OPTIONS，须恢复原值），未提供会跳过。指定固定专用AOF容器的重启测试未满足条件时单列SKIPPED。Mock、真实Redis状态机、真实HTTP/DB及生产验收分开报告。

旧入口收口另选 `AdminReviewAuthorizationTest,MediaAuthorizationTest,InteractServiceImplTest,ActivityFormVersionServiceTest,AdminSystemServiceImplTest,UserServiceImplAdminCursorTest,ProfileSafetyCheckTest,ProfileCallbackIsolationTest,UserProfileUpdateTest`。P1含混合审核队列列表/计数/summary/跨域决定、当前撤权和未绑定附件精确上传登记关系；每轮P1使用新schema和空的独占Redis，换schema但复用上一轮计数会污染结果。QR AOF重启与P1故障pause须使用不同专用Redis，不能对业务实例清空或重启。全仓测试编译已恢复，可直接用标准Maven生命周期；未提供各专用环境而跳过的测试不算通过。

Phase 3-C九接口实际验证沿用`P1SecurityDatabaseHttpTest`及`RbacHttpAdapterTest`，使用上述P1专用schema/容器属性。P1每个case会清空该独占Redis DB，隔离合成会话、计数及真实QR限流预算，不得给业务Redis端口；测试专用filter在commit后制造503，仅在测试源注册，不提供生产failpoint。新HTTP场景只启用合成ROOT及完整NOTICE_MONITOR；原P1旧入口回归保留各自明确的合成角色fixture。

`MiniAppApplicationTests`只在显式`-Dcontext.test.jdbcUrl=jdbc:mysql://127.0.0.1:<port>/application_context_test`和`-Dcontext.test.redisPort=<专用端口>`时运行，schema必须不存在。完整应用使用真实MySQL/Redis、合成COS初始化参数和正常Provider门禁，无测试替身替换业务Bean；DirtiesContext关闭上下文并沿既有executor等待策略清理。无该属性时全仓测试跳过上下文，必须单独报告实际上下文验证，不能运行默认业务配置冒充隔离测试。

Contract静态与wire验证只写Backend/target，不改相邻源码：

```powershell
node ../contracts/node_modules/@redocly/cli/bin/cli.js lint ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/rbac-3c-openapi.json
node tools/validate-rbac-http.mjs
```

最后命令读取实际P1的`target/p1-security-http-responses.json`，要求九操作有成功记录，逐条校验canonical响应/状态、媒体类型、no-store及Location，输出`target/rbac-3c-wire-validation.json`。先运行本次源码的HTTP测试及重新bundle，不能用历史响应或旧bundle替代。当前覆盖有期限授予0–6位、超精度400、等价offset/尾零重放、1微秒冲突、真实DB到期及撤销后收据恢复；canonical官方样例的请求正反例与响应同时校验。依赖相邻Contracts已安装的锁定校验工具，缺依赖按BLOCKED记录，不自动改版本。

## Account and interaction security focused validation

`P1SecurityDatabaseHttpTest` 使用真实 loopback HTTP、MySQL 8.4、Redis/Sa 登录存储及 Spring 事务。必须提供两个独占一次性容器：MySQL root/空密码、空 Redis；不得复用业务实例或其他测试仍在使用的 Redis。JDBC 只接受 `jdbc:mysql://127.0.0.1:<port>/p1_security_test` 或带小写字母/数字后缀的同名测试库，库必须不存在，测试创建库并重放现有迁移。

```powershell
$env:P1_REDIS_PORT = '<disposable Redis port>'
$env:P1_REDIS_CONTAINER = 'codex-p1-redis-<8 hex characters>'
$env:P1_MYSQL_CONTAINER = 'codex-p1-mysql-<8 hex characters>'
.\tools\validate-event-information.ps1 -Maven .\mvnw.cmd `
  -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:<port>/p1_security_test' `
  -TestNames P1SecurityDatabaseHttpTest,CommentServiceImplTest,LikeOwnershipTest,LikeNotificationProducerTest,UserServiceImplRoleChangeTest,PostServiceImplTest,ActivityServiceImplTest,PublicEventServiceImplTest
```

故障用例会暂停并在 finally 恢复显式指定、名称匹配的专用容器；不得传入共享实例。输出 `target/p1-security-http-responses.json`（不保存请求 Token，成功登录凭证脱敏）。微信、安全检查、媒体、通知使用替身；订阅/报名使用真实 Controller、资格拦截器及替身业务 Service，只证明认证入口资格，不证明其完整业务持久化。该测试不是生产/设备验收。相邻 Post/Article 定向回归分别使用下述已有测试入口；新 canonical 互动目前只支持 Article，不借安全修复扩展其 targetType。

## Post organization focused validation

在本次独占一次性 MySQL 8.4 实例执行，两个 schema 必须尚不存在，测试自行创建；示例端口需替换为本次测试端口：

```powershell
.\tools\validate-event-information.ps1 -Maven .\mvnw.cmd -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' -JdbcUrl 'jdbc:mysql://127.0.0.1:<port>/post_organization_test' -TestNames PostOrganizationDatabaseTest,PostOrganizationPolicyTest,PostServiceImplTest,CommentServiceImplTest,PostAuditCallbackTest,MediaServiceImplTest,AuditCompletedEventHandlerTest,PostDictionaryAuthorizationTest
```

测试使用真实迁移、Mapper、Spring 事务和 MVC；用户身份、导师资格、安全检查、媒体与通知 provider 使用测试替身。不得将其他业务 schema 传入。测试输出 `target/post-contract-responses.json`；在相邻 Contracts 执行 `npm.cmd run validate` 更新 canonical bundle 后，从 Backend 执行 `node tools/validate-post-contract.mjs` 比较实际响应。没有正式资格源、真实 COS/微信和客户端验收时，INTEGRATION / RELEASE READY 仍阻断。部署边界见数据库 README 的 Post Organization V40。

所有命令从 backend 根目录执行。此目录记录真实仓库入口，不把示例命令当作已验证操作。

## Points V1 focused validation

Java17、Maven Wrapper、独占一次性 MySQL8.4 和 Redis。所有数据库测试拒绝已存在 schema；失败保留证据，不 DROP/重建。不得把示例端口或 schema 改为共享/生产。相同工作树的 Maven 编译/测试必须串行；并行 javac 会删除别的测试正在读取的 class 文件。

```powershell
$env:POINTS_INTEGRATION_REDIS_PORT='<owned-redis-port>'
.\mvnw.cmd -B -ntp '-Dtest=PointsIntegrationHttpTest,PointsIntegrationCatalogTest,PointsOwnershipQueryDatabaseTest,PointsNotificationDatabaseTest,PointMaterialDatabaseTest,PointMaterialInspectionTest,PointsDatabaseHttpTest' `
  '-Dpoints.integration.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/points_integration_test_<new-suffix>' `
  '-Dperson.points.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/person_foundation_test_pointsb<new-suffix>' `
  '-Dpoints.notice.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/points_notice_test_<new-suffix>' `
  '-Dpoints.media.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/points_media_test_<new-suffix>' `
  '-Dpoints.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/points_v1_test_<new-suffix>' test
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/points-v1/openapi.json
node tools/validate-points-http.mjs
node tools/validate-points-http.mjs target/points-integration/http.json
```

`PointsIntegrationHttpTest` 是完整应用、真实MySQL/Redis/QR/Sa/RBAC source与普通治理、人员owner/Reader、Points、私有材料owner的HTTP闭环；外部微信及COS/内容安全Adapter明确为替身，不能证明设备或真实存储就绪。精确points-integration profile只允许明确指定的loopback测试库及测试确认值；身份目录使用现已CONFIRMED的四阶段政策，审计明确区分正式政策与旧隔离政策。运行前查独占容器label/端口；测试fixture可准备合成账号/批准及隔离catalog状态，不能复制为真实人员授权脚本。

`PointMaterialDatabaseTest` 验 V48→最新升级保留 V47 摘要与空库；`PointsDatabaseHttpTest` 保留账务唯一性/冲正/重放和18操作 wire，身份、范围、文件及通知是旧测试替身。`PointsOwnershipQueryDatabaseTest` 使用真实 owner/SQL，session/capability 是基础测试替身；其他作用域见各测试类注释。通知单独验证真实 Job Worker/DB/收件箱和 UTC/+08 session 下租约；不代表已发布客户端兼容。缺数据库属性时相应测试 SKIPPED，不能写 PASS。

只读 canonical，派生文件写 Backend target。当前启动、账号/存储/政策限制和执行证据见 [积分实现边界](../docs/points.md)。

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
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>' -TestNames CanonicalResourceMvcTest,SubscriptionTransactionBoundaryTest,ActivityParticipationPolicyTest,EventContactCodecTest,AttachmentReadServiceTest,ActivitySearchRetirementMvcTest,ActivityServiceImplTest,PublicEventServiceImplTest,ActivityEnrollmentServiceImplTest,PublicEventSubscriptionServiceImplTest,ActivityRegistrationContractTest,CanonicalSubscriptionRouteTest
```

此集合覆盖 MVC 请求响应、业务读取判断、旧表示兼容及 Spring 事务代理；部分 Service 以及 Mapper / JDBC 使用测试替身，事务测试验证事务激活与 commit / rollback 调用，不证明真实鉴权、数据库锁、数据回滚、迁移或完整契约联调。新增或改变这些保证时另按相应机制验证。

## 尚未登记的运维操作

### Activity discovery focused validation

Activity 草稿参与形式回归可显式选取 `AdminActivityConverterTest,ActivityServiceImplTest,ActivityParticipantModeDatabaseTest,CanonicalResourceMvcTest,ActivityParticipationPolicyTest,ActivityRegistrationContractTest,ActivityFormPolicyTest`。真实数据库用 `jdbc:mysql://127.0.0.1:<一次性端口>/activity_participant_test`，MySQL 8.4、root/空密码；先完整迁移至 V31，加载 `src/test/resources/activity-participant-v31-fixture.sql`，再迁移至 V32。另建同实例 `activity_participant_replay` 空库并完整迁移至 V32，用于结构和索引对照。测试通过真实 Mapper/事务代理写入合成数据，外部通知/缓存使用替身；两库只能为本次 disposable 实例，不传 JDBC 属性会跳过数据库测试。迁移、历史编码及切换边界见数据库 README，生产不能运行这些 fixture。

活动发现列表使用上方定向脚本，显式选择 `CanonicalResourceMvcTest,ActivityPublicCursorCodecTest,ActivityServiceImplTest,AdminActivityConverterTest,ActivitySearchRetirementMvcTest`。MySQL 集合/分页验证另加 `ActivityDiscoveryDatabaseTest` 并传入 `jdbc:mysql://127.0.0.1:<本次一次性端口>/activity_discovery_test`；仅接受该专用 loopback schema、root/空密码，创建 synthetic activity 表，表已存在则失败，结束时只删除本次创建的表，执行 V31 验证回填及旧列删除。不传 JdbcUrl 时数据库测试跳过，不报告数据库 PASS。实现与推送后清理边界见 [活动发现](../docs/activity-discovery.md)。

### Notification Contract focused validation

复用上述选择测试脚本，指定 `-TestNames`；不要直接运行全仓测试替代受影响边界。真实数据库测试只允许专用 disposable localhost schema，禁止指向业务库。

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:33987/notification_contract_replay_final' `
  -TestNames @('NotificationCenterDatabaseTest','NotificationCenterMvcTest','NotificationStreamCursorCodecTest','ReminderOccurrenceTest','NotifyServiceImplTest','NotificationDeliveryServiceImplTest','NotificationFirstReleaseTest','LikeNotificationProducerTest','ActivityServiceImplTest','PublicEventServiceImplTest','WxMpNoticeSendServiceTest')
```

该 URL 仅为隔离环境示例，数据库须已完整执行测试所需迁移。`NotificationUpgradeDatabaseTest` 单独使用 `notification_contract_upgrade` 专用 schema：先迁移到 V25，加载 `src/test/resources/notification-v25-upgrade-fixture.sql`，再迁移到 V28。测试会验证明确编号的 synthetic fixture；不能在真实业务库运行。升级语义与恢复限制见 [数据库说明](../src/main/resources/db/README.md)。Redis 与微信在这些测试中使用替身；数据库测试使用真实 MySQL、Mapper 与 Spring 事务代理。

本仓库没有 Compose 文件，不登记 COMPOSE-* 命令。后续采用 Compose 时再按真实服务名和卷定义补充。
直接启动应用、数据库迁移/备份/恢复、Redis 清理和生产发布，先检查环境、脚本、恢复范围及已有授权；不提供可盲跑的通用命令。
生产发布当前由 CI/CD 调用服务器脚本；服务器当前状态不能从仓库文档推断。

变更常用命令时同步检查 AGENTS、CI、工具和引用文档，报告命令、退出码、目标环境与未验证范围。

## 专项验证入口

### LearningTask focused validation

仅验证微信 keyword 空明细兼容与预览响应 ETag 时，使用既有专项脚本显式选择 `WxTextSafetyStructureTest,WxTextSafetyDiagnosticsTest,LearningTaskTextSafetyTest,LearningTaskPreviewHttpTest`，不传 JdbcUrl；这些是结构/Provider 替身与 MockMvc 验证，不证明真实微信、数据库或客户端成功。空 keyword 兼容仅在 `dev & learning-task-integration & !prod` 生效，第三方实例依据和本测试真实链路证据见 [LearningTask](../docs/learning-task.md)。

文字提交/审核增量可显式选择 `LearningTaskDatabaseTest,LearningTaskDraftHttpTest,LearningTaskHttpGateTest,LearningTaskAuthorizationPolicyTest,NotifyServiceImplTest`；JdbcUrl 必须为独占 disposable MySQL 的 `jdbc:mysql://127.0.0.1:<port>/learning_task_test`，不得指向 3306 业务库。本轮使用端口 33988，V1–V46 已重放。覆盖无审核完成、三次提交/两次驳回/固定补交期/批准、通知失败原子回滚及重放、本人隔离、双权限、父版本冲突与安全等待期间状态/期限/资格变化。之后运行既有 bundle/schema 命令检查新增 Submission/Review 表示；通过不自动发布审核目录或授权真人。

真实集成准备先按 [LearningTask 依赖与启用边界](../docs/learning-task.md#外部依赖与启用边界) 核对已授权目标、迁移和合法账号；不能将下列隔离测试指向当前业务测试库。只读文件快照使用 `powershell -File tools/prepare-learning-task-integration.ps1`，默认输出 `target/learning-task-integration-snapshot.json`；读取现存 `target/learning-task-vertical-*` 证据与 `target/learning-task-integration-environment.txt`，不重新执行环境探测、测试、迁移或授权。缺少输入会失败，不自动补造证据。

正式 owning policy 的权限映射由 `LearningTaskAuthorizationPolicyTest` 核对当前 20 个管理 operation、namespace/GLOBAL、AND、目录未发布和撤权/故障。原草稿隔离用例使用 test-source 授权决策和安全 fixture；纵向用例另外执行正式主库 Reader、User owner 实时 Mapper、NotifyService/InboxService 与 JobWorker。受控 catalog/grant、Sa 会话、UserService façade、WxClient 和缓存仍为隔离边界，不证明真实 QR/Redis、微信往返或客户端集成。已实现能力默认提供运行支持，测试不自动发布数据库目录。

`LearningTaskDatabaseTest` 在独占 loopback `learning_task_test` 创建/验证当前迁移；空库先至 V37，准备 synthetic 旧 Post（title=`legacy-learning-fixture`、content=`preserve-before-v38`），再升级当前版本。不得传已有业务库；用例会清除该固定测试库的任务事实。另建 `learning_task_replay` 进行空库 V1–V44 migrate/validate；`learning_task_upgrade` 先迁移至 V43，加载 `src/test/resources/learning-task-v38-fixture.sql`，再升级 V44/validate 并对照原 Task/Assignment/Submission 和预览既有字段。旧 V38→V39 验收是历史范围，不等于本轮重验。见 [数据库说明](../src/main/resources/db/README.md#learningtask-preview-classification-v44)；不得复用业务数据库或运行生产凭据。

```powershell
.\tools\validate-event-information.ps1 -Maven .\mvnw.cmd -JavaHome '<configured Java 17>' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:<dedicated-port>/learning_task_test' `
  -TestNames LearningTaskAuthorizationPolicyTest,LearningTaskTextSafetyTest,LearningTaskDatabaseTest,LearningTaskDraftHttpTest,LearningTaskHttpGateTest,CurrentAuthorizationTest,ArticleBodyCodecTest,ArticleAuthorizationTest,AttachmentReadServiceTest,NotificationFirstReleaseTest,NotifyServiceImplTest,NotificationCenterServiceTest,JobHandlerRegistryTest,JobExecutionContextTest
```

`LearningTaskDatabaseTest` 只接受该精确 loopback schema、显式端口、root/空密码。测试通过真实 MyBatis/Spring 事务和 MySQL 唯一键/外键/行锁验证状态、竞争、幂等与回滚；测试 Provider 仅在测试源中写入已有 outbox 表验证原子提交，不包含真实通知消费/提醒调度/私有 COS/人员权限。测试会清除上述 schema 的 LearningTask 行及 topic=`learning-task.test` 的 synthetic Outbox 行，不能传业务目标。无 JdbcUrl 会跳过 DB 测试，不得报告 DB PASS。

`LearningTaskHttpGateTest` 读取相邻 Contracts 的 canonical operations，核对 owning authority 不可用时每个 canonical 路由的 503 Problem Details/no-store；须有该 sibling checkout。MVC 测试的认证替身不代表真实登录验收。

纵向 HTTP 测试连接显式使用 Asia/Shanghai 并设置 forceConnectionTimeZoneToSession=true，以对齐既有通知 SQL NOW 与 DATETIME；断言通知已读时间不早于创建时间。目标环境连接会话时区仍需独立核对，不从测试配置推导生产已满足。

`LearningTaskDraftHttpTest` 使用相同 disposable schema，启动只绑定 127.0.0.1 随机端口的 Tomcat，真实 HTTP 客户端、Spring MVC、JacksonConfig、MyBatis/Spring 事务与 MySQL。Sa-Token 通过真实会话 API 在测试进程内内存 Dao 建立 synthetic 会话，未走完整 QR/AdminTokenService；纵向用例执行生产 Provider 的创建、显式预览/发布、本人确认、通知读取与管理查询。正式 Reader 的授权撤回、授权源缺失，以及通知插入故障回滚/同 key 恢复均在该库验证。未执行真实账号/微信验收，生产目录未开放；完整应用的管理认证可能先于业务门禁拒绝。不存在测试登录或生产许可开关。未传 JdbcUrl 会跳过 DB/HTTP 测试类，不能报告它们 PASS。

实际 HTTP 响应保存在 Backend `target/learning-task-b2-http-responses.json`。先从 Backend 执行下列只读 Contracts 来源、只写 Backend target 的命令，避免复用陈旧 bundle；不修改 sibling 源码或其 .validation：

```powershell
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/learning-task-b2-contract.json
node tools/validate-learning-task-http.mjs
```

需要 sibling Contracts 已安装的固定工具依赖；本轮不安装/更新其依赖。校验实际已产生的 HTTP 表示，不能替代正式授权、真实 Provider 或客户端业务联调。测试报告位于 `target/event-validation/surefire-reports`，最后只清理本次创建的专用容器；不停止用户原有 Redis/MySQL。

### Media attachment lifecycle focused validation

复用 tools/validate-event-information.ps1，显式选择 `MediaAttachmentLifecycleDatabaseTest,CosAttachmentUrlTest,MediaServiceImplTest,MediaUploadRecordServiceTest,MediaProfileSnapshotTest,AttachmentReadServiceTest,UserProfileUpdateTest,ProfileMediaCallbackTest,PostServiceImplTest,CommentServiceImplTest`。数据库测试只接受 `jdbc:mysql://127.0.0.1:<port>/media_attachment_lifecycle_test`，必须是已执行 V1..V74 的本次一次性独占 schema（该套件保留 V30 历史正式夹具与回填机制，新登记闭环使用 FileFoundationDatabaseTest），root/空密码；测试会清除该 schema 的媒体表并提交 synthetic 数据，排序规则回归还会临时修改并还原两张媒体表的 object_key 列排序规则，绝不能使用业务库。未提供 JdbcUrl 时数据库测试跳过，不报告数据库 PASS。真实 MySQL/Mapper/Spring 事务验证登记、复用、竞争、回滚、历史保护和删除意图恢复；COS 使用替身，不证明真实对象上传／删除或生产回填。

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


### Activity V1 Backend 验证

使用 Java 17，先核对独占 MySQL 8.4 实例 UUID；不能指向业务库。`ActivityV1DatabaseTest` 只接受 loopback / `activity_v1_test<数字>`、root/空密码，并在建库前验证 UUID。schema 必须不存在，测试自行执行 V83 带旧数据→V84、空库完整重放与 validate。重跑用同一专用实例的新数字后缀，不能复用已写入的 schema 冒充全新升级。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=ActivityV1DatabaseTest,ActivityV1ReminderDatabaseTest' `
  '-Dsurefire.runOrder=alphabetical' `
  '-Dactivity.v1.jdbcUrl=jdbc:mysql://127.0.0.1:<专用端口>/activity_v1_test<数字>' `
  '-Dactivity.v1.serverUuid=<已核对的独占实例UUID>' test
```

两类按字母顺序共用本次 schema；后者复用真实提醒/投递 harness，验证临近补发、已完成扇出后的低 userId、同锚点去重、关系/改期复核及终态抑制。授权、审计、Redis、微信为明确替身，不代表真实账号、完整应用 HTTP、微信或部署。没有属性会 SKIPPED，不可称 DB PASS。结束只清理本次创建的专用容器/卷，保留日志在 target；不停止或清除其他服务。

### Activity Reminder V1 focused validation

使用 `tools/validate-event-information.ps1` 指定 `ActivityReminderPolicyTest,ActivityReminderSubjectStatusTest,ReminderReconcileServiceTest,ReminderOccurrenceTest,ActionableTimelineChangeDetectorTest,NotificationFirstReleaseTest,NotifyServiceImplTest,NotificationDeliveryServiceImplTest,ActivityDeliveryEligibilityTest,ActivityReminderDatabaseTest`，并选择 Java 17。相关回归可加入 Activity/Registration、PublicEventPolicy、TimelineIdentity、Notification MVC 和 Async Job 测试，不从无关全仓 testCompile 失败扩大修复。

`ActivityReminderDatabaseTest` 仅接受 `jdbc:mysql://127.0.0.1:<port>/activity_reminder_test<可选数字>` 的一次性 MySQL 8.4、root/空密码，要求完整 V1–V84。它不读取 application.yaml，使用 synthetic fixture、真实 Mapper/Spring 事务和微信/Redis替身；无 JdbcUrl 时跳过，不可报告 DB PASS。空库完整 Flyway replay、带旧数据 V32→V33 升级和旧字段不变分别验证。测试不得改为业务库；结束后清理本次创建的专用容器。


### 微信模板 V1 基础验证

当前首版模板、真实任务落库和 HTTP 验证统一使用下方[通知首版验证](#通知首版验证)，不再以旧服务号配置或旧通知表示作为验收。模板结构与离线 Fixture 边界见[微信集成 §16.6](../docs/wechat-integration.md#166-wechat-notification-template-v1-实施边界)。离线模板测试不调用真实微信；历史 V34 专项只证明当时迁移，不替代 V98 验证。

### Official content M01 focused validation

复用 `tools/validate-event-information.ps1`，显式选择 `OfficialContentDatabaseTest,AttachmentReadServiceTest,MediaAttachmentIdentityTest,MediaServiceImplTest`。数据库测试只接受 `jdbc:mysql://127.0.0.1:<port>/official_content_test`，root/空密码；须先在一次性本地 schema 重放 V1–V34，执行 [synthetic fixture](../src/test/resources/official-content-v34-fixture.sql) 插入迁移验收旧 Post 和 Activity 附件引用，再执行 V35。测试清理只作用于该专用 schema；未提供地址时数据库测试跳过，不得报告 PASS。真实 MySQL/Mapper/Spring 事务/MVC 验证发布、引用、回滚和并发；管理身份与 COS 为替身，不证明真实账号/COS/微信运行。

### Article focused validation

统一内容固定包 `article-content-v1-r1` 先核对 `.manifest.json`、归档 SHA-256 和 sourceRevisionSha256。使用本轮独占 MySQL 8.4、root/空密码，分别运行 `ArticleContentMigrationTest`（全新 article_contract_test；额外随机命名升级 schema，验证 V82→V83、空库、validate/重复 migrate）与 `ArticleDatabaseTest,ArticleContentDatabaseTest,ArticleOrganizationDatabaseTest,ArticleAuthorizationTest,ArticleBodyCodecTest,ArticleContentFieldsTest,ArticleSafetyCheckTest,ManagedArticleMediaInspectorTest,ArticleDocumentInspectorTest,ArticleMediaReferenceTest,MediaAuthorizationTest,AttachmentReadServiceTest,CanonicalResourceMvcTest,LearningTaskArticleProviderTest`。沿下述 `tools/validate-event-information.ps1` 选择测试，固定 URL 为该独占实例的 article_contract_test。迁移用例拒绝已有主测试 schema，不删库重跑；失败需按具体断言修复并保留失败证据。

ArticleContentDatabaseTest 包含原 Article 回归，补充真实 Mapper/事务/MVC 的场景/搜索、文件来源保存与旧字段省略、收藏和删除/任务引用保护；身份、COS、文字/图片检查是显式替身。ArticleDocumentInspectorTest 使用真实 PDFBox/POI 验证可解析内容和不合格文件，不访问 COS。ArticleOrganizationDatabaseTest 使用真实职责 Reader/写事务，包含跨组织材料选项、媒体隔离和删除恢复。专项响应先从已校验固定包以既有 Redocly bundle 生成 `target/article-content-v1-r1/openapi.json`，再运行 `node tools/validate-article-contract.mjs --organization --article-content`；它读取原 Article/组织响应及 `target/article-content-http-responses.json`，不改 Contracts。结果记录在 target；真实 COS/微信、安全供应商、DOC 设备打开和目标环境部署仍须分别记录。


组织署名 V2 使用 Java17 和本轮独占 MySQL8.4/root空密码，串行执行既有 `tools/validate-event-information.ps1`。先以 `article_contract_test` 的 loopback URL 单独选择 `ArticleOrganizationMigrationTest`：拒绝已存在的 `article_org_upgrade_test` / `article_contract_test`，验证 V60→V61 合成旧文/评论/点赞升级及空库重放、validate/重复 migrate。随后在该库选择 `ArticleOrganizationDatabaseTest,ArticleDatabaseTest,ArticleRbacDatabaseTest,ArticleAuthorizationTest,ArticleBodyCodecTest,ArticleSafetyCheckTest,ArticlePublisherBootstrapTest,CurrentAuthorizationTest,RbacRoleNamespaceTest,AttachmentReadServiceTest`。新组织数据库类依赖原 ArticleDatabaseTest.Config，须同批编译该类；全部仅用于本轮 disposable 实例。

ArticleOrganizationDatabaseTest 使用真实 Unified options/preview/commit、委派/来源/当前 Reader、组织 owner、Article Writer、审计、MySQL 事务和 MVC。账号会话、完整能力/角色就绪清单是显式 synthetic fixture；安全、COS、Redis、通知仍用替身。原 ArticleDatabaseTest 的组织范围是测试替身，仅承担原正文/分类/ETag/媒体/互动回归；ArticleRbacDatabaseTest 测当前 capability 与完整角色，真实精确组织拒绝由组织数据库类验证。Bootstrap 测试只覆盖退役类直接调用，不能表明启动仍执行初始化。

实际新旧响应另存 `target/article-org-http-responses.json`；`node tools/validate-article-contract.mjs --organization` 比较该文件及原 `target/article-contract-responses.json` 与当前 canonical bundle。只有实际执行的边界可报告 PASS；真实账号/组织、成员周期集成、消费者双读切换、provider 与目标迁移/恢复仍需各自证据，不把 synthetic 就绪事实发布到业务库。

两层分类增量先在本轮独占 MySQL 8.4 空实例、root/空密码上，以原定向脚本和 `article_contract_test` URL 单独选择 `ArticleCategoryMigrationTest`。它拒绝已存在的 `article_category_upgrade_test`、`article_category_replay_test`、`article_contract_test`，执行真实 V59→V60 与 V1→V60、validate/重复 migrate，并建立 Article 回归所需的合成旧 Post。随后串行选择 `ArticleDatabaseTest,ArticleAuthorizationTest,ArticleBodyCodecTest,ArticleSafetyCheckTest,ArticlePublisherBootstrapTest`，再按需单独运行 `ArticleRbacDatabaseTest`。迁移测试不可与回归类同批运行，不能对共享实例运行或删库重跑。ArticleDatabaseTest 使用真实 MySQL/Mapper/事务/MVC，包含两层、启停历史、分页游标、并发上限/ETag及写事务最终状态复核；授权、审核、COS/Redis 等边界仍有替身。

Article RBAC 增量选择 `ArticleRbacDatabaseTest,ArticleDatabaseTest,ArticleAuthorizationTest,ArticleBodyCodecTest,CurrentAuthorizationTest,AttachmentReadServiceTest`。`ArticleRbacDatabaseTest` 复用下述独占 `article_contract_test`，须完整执行当前迁移；会清理该库 Article/分类/主体及 RBAC 授权数据，绝不能指向业务库或共享测试库。使用真实主库 Reader、完整角色目录、MySQL、Article Service/Writer 与 MVC，管理会话为 synthetic，安全审核/COS 为替身。缺 JdbcUrl 时跳过，不报告 DB/接口 PASS；不证明真实登录、provider、运行目录发布或人员授权。

使用既有选择测试脚本与 Java 17。数据库仅为专用一次性 `jdbc:mysql://127.0.0.1:<port>/article_contract_test`，root / 空密码，先完整迁移到 V35，加载 synthetic `official-content-v34-fixture.sql`（只包含旧 Post 与 Activity 附件），再执行 V36。另一专用 schema 进行 V1–V36 空库重放和 validate；迁移和恢复约束见 [数据库说明](../src/main/resources/db/README.md#article-v36)。fixture 传入 MySQL 时显式使用 utf8mb4，不以控制台编码判断内容是否一致。

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:<port>/article_contract_test' `
  -TestNames ArticleDatabaseTest,ArticleBodyCodecTest,ArticleAuthorizationTest,ArticleSafetyCheckTest,ManagedArticleMediaInspectorTest,PostServiceImplTest,PostAuditCallbackTest,CommentServiceImplTest,LikeOwnershipTest,LikeNotificationProducerTest,MediaServiceImplTest,MediaAttachmentIdentityTest,MediaProfileSnapshotTest,AttachmentReadServiceTest,CanonicalResourceMvcTest,ProfileMediaCallbackTest,ArticleMediaCallbackTest,NotifyServiceImplTest,AuditResultPersistenceServiceTest,AuditCompletedEventHandlerTest
```

实际 MySQL / Mapper / Spring 事务 / MVC 验证生命周期、条件更新、互斥编辑、分类限制、媒体保留 / 回滚、每日浏览和线程可见性；真实管理员凭证、COS、微信、通知基础设施与 Redis 不由这些替身测试证明。ImageIO 检查使用真实本地 JPEG / PNG / WebP 字节。未传 JdbcUrl 时数据库类跳过，不能报告 DB PASS。

`ArticleDatabaseTest` 产生 `target/article-contract-responses.json`。先在相邻 Contracts 执行其已登记的 `npm.cmd run validate` 生成 dereferenced bundle，再从 Backend 执行 `node tools/validate-article-contract.mjs`，将实际 MVC / MySQL 响应与 canonical Schema 比较。脚本只读 Contracts 来源及工具，不编辑契约。该校验覆盖已产生的表示，不能代替设备、provider、真实权限或完整三端验收。

COS 视频审核增量选择 `CosVideoAuditClientTest,CosVideoSafetyCheckTest,CosVideoAuditJobTest,CosVideoAuditDatabaseTest,ArticleDatabaseTest,ArticleSafetyCheckTest`；数据库使用同一个独占 `article_contract_test` schema，但须已执行至 V37。迁移另验 V36→V37 升级与 V1→V37 空库重放 / validate，synthetic 旧 Post / Activity 附件须保持。`CosVideoAuditDatabaseTest` 会删除该专用 schema 中本次视频审核记录及对应类型 Job，不能使用业务库。

Client 测试只运行合成 XML、签名请求的 loopback HTTP 和重定向拒绝，不访问腾讯云；数据库测试用真实 MySQL / Mapper / Job / 事务及 provider 替身验证原子预占、并发去重、策略变化、结果身份、UNKNOWN 和过期租约隔离。真实 CI 的收费提交、权限、画面 / 音频效果和 ffprobe 运行仍须按 [Runbook](../docs/operations/runbook.md#article-cos-video-audit) 在指定环境另验，不因测试通过就开启配置。

## 本地管理测试目录发布

适用已批准的本机开发数据库，属于 C2；不是生产发布或人员授权命令。后端已实现能力默认提供运行支持，无需管理开关或额外集成 profile；数据库目录和真实授权仍须满足。前端若需资讯与 Post 字典，在 `.env.development.local` 设置 `VITE_ARTICLE_ENABLED=true`、`VITE_POST_ORGANIZATION_ENABLED=true`。读取 `../.env.dev` 的启动器须保留这些变量。

在 Backend 根目录执行只读检查；端口、库名及 server UUID 均须取自已核验目标，不可照搬其他环境：

```powershell
powershell -File tools/publish-local-admin-test-catalog.ps1 -DatabaseName '<本地库名>' -Port <端口> -ExpectedServerUuid '<已核验的server UUID>'
```

确认目标后用相同参数加 `-Apply` 执行。脚本固定连接 127.0.0.1，校验目录摘要、Authority 完整性、清单完整角色和禁用项，保存旧元数据及 `restore-catalog.sql`，持 ROOT 互斥锁发布；只更新清单中的 Permission/Role，不增删 Authority/Assignment，并比对前后指纹。重启后端并在页面刷新当前权限，验收 ROOT capabilities 与真实业务请求。

已实现能力无运行支持总开关；需要止损时先按受影响入口停流量或停止应用。恢复目录须先停服务并核验同一目标、无后续目录变更，再以该目标的 mysql 客户端执行本次证据目录内 `restore-catalog.sql`；脚本未自动回滚业务数据或后续授权，不要覆盖后续发布。代码支持与运行目录边界见 [RBAC 基础](../docs/rbac-foundation.md#实现支持与运行可用性)。


## 统一业务职责验证

先核本任务独占 MySQL/Redis 容器标签与 loopback 端口。禁止复用业务 schema 或业务 Redis，禁止以清库让固定测试名复用。Java17 下完整构建使用 `mvnw.cmd package`；默认未配置专用属性的数据库/真实会话类应 SKIPPED，不能把这个构建写成全部集成 PASS。

专用测试命令（新的 schema 后缀，每批独立）如下，端口必须替换为已核验值；空密码只属于独占测试 MySQL：

```powershell
$env:POINTS_INTEGRATION_REDIS_PORT='<owned-redis-port>'
.\mvnw.cmd '-Dtest=UnifiedR3IntegrationHttpTest,UnifiedR3MigrationTest,PointsR3SingleSourceDatabaseTest,RbacDatabaseTest' `
 '-Dpoints.integration.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/points_integration_test_<new-suffix>' `
 '-Dunified.r3.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/unified_r3_migration_test' `
 '-Devent.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-mysql-port>/rbac_foundation_test_<new-suffix>' test
```

UnifiedR3IntegrationHttpTest 仅在专用数据库发布 synthetic Role/Permission，使用正式 UnifiedDutyCatalog，不替换其 ready() 判定；它不改主资源的 NOT_READY、不调用本地目录发布脚本、不批准真人授权。完整 MySQL/Redis/QR activation/HTTP 与来源、委派、零写检查是真实本地执行，微信身份/二维码生成、COS、内容安全是显式替身。迁移类使用新随机 schema 验 V1→V62 和 V61 升级；不调用 clean/drop。PointsR3SingleSourceDatabaseTest 的有效来源输入是 double，Person 存储、事务 handle 与 R3 Mapper SQL 是真实的；不能把这个边界写成完整授权链集成。全应用另有真实来源与撤权用例。保留 `target/unified-authorization-implementation-2026-10-23/` 下日志、HTTP、摘要与逐场景状态；真实生产/Provider/浏览器/设备验收另行标明。


## 微信模板离线预览

前提为本机 Java 17 和显式测试 Fixture；命令只编译主代码、生成 classpath 并运行离线预览，不启动应用、不读取真实账号或调用微信。Fixture 字段约束不是真实模板定义。

```powershell
# 使用工程指定 Java 17；只编译 Backend 主代码并生成 classpath，不启动应用。
$env:JAVA_HOME = '<本机 Java17 目录>'
.\mvnw.cmd -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/wechat-preview-classpath.txt'
$previewCp = 'target/classes;' + (Get-Content target/wechat-preview-classpath.txt -Raw).Trim()
& "$env:JAVA_HOME/bin/java.exe" -cp $previewCp cn.jualn.miniapp.module.wx.notice.WxTemplatePreview `
  src/test/resources/wechat-template-preview.yaml src/test/resources/wechat-template-preview.json fixture-activity-start
```


## 内容安全审核专项验证

`ContentSafetyReviewDatabaseTest` 仅接受 loopback `content_safety_test` 及同实例 `content_safety_upgrade_test`，须显式匹配 `safety.test.serverUuid`。首次要求全新 schema：完整迁移/validate；upgrade 先 V70、写入合成旧日志/成功收据，再升级并验证重复 migrate 无新执行。只能使用任务创建或已核实专用的 MySQL 8.4 实例，root/空密码只是此独占夹具的凭据，不是安全证明。失败保留现场；修正后仅在核实同一实例 UUID 与本任务资源所有权后传 `safety.test.reuseOwnedSchema=true`，复用夹具并继续 migrate/validate，不清理未知库或 repair。每方法重置仅作用于该测试专属内容/审核/Job/Outbox 表；关闭 context 后保留 schema，实例由创建者在保留证据后停止。

```powershell
mvn.cmd -B -ntp '-Dtest=ContentSafetyAuthorizationTest,ContentSafetyReviewDatabaseTest,AuditCompletedEventHandlerTest,AdminReviewAuthorizationTest,AuditResultPersistenceServiceTest,ProfileSafetyCheckTest,ArticleMediaCallbackTest,PointMaterialCallbackTest,ProfileMediaCallbackTest' '-Dsafety.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/content_safety_test' '-Dsafety.test.serverUuid=<verified-instance-uuid>' test
```

使用 Java 17。测试真实 MySQL/MyBatis/Spring 事务、轮次/唯一键/回滚、重复和并发决定、旧版本/撤下/重放及队列范围。身份、目录发布、授权锁数据来源、Redis、外部通知和安全供应商是显式替身；不证明真实账号、撤权 writer 的完整锁竞争、微信、生产历史数据、Admin/Mini 或部署。Surefire 与 `target/content-safety-*` 分别记录执行与制品证据。

## 文件基础 V74 验证

消费制品 `file-foundation-v1-r2.zip` SHA-256 为 `d24c9cb3f5f85f17719041b61b63c7132b19a31f076920db218d14432267d57f`；本轮核对 canonical api 文件与包内字节一致。Backend 实现证据与制品 Contracts 验证分开。

`FileFoundationDatabaseTest` 只接受本次独占一次性 MySQL 的 `jdbc:mysql://127.0.0.1:<port>/file_foundation_test[_小写字母数字后缀]`，root/空密码；schema 必须不存在，测试自行创建，不自动 DROP。V1→V73、插入历史附件、V73→V74/validate 后，执行真实 Mapper/Spring 事务、HTTP 表示、登记同键/跨键并发、原键恢复、固定期限、保存回滚、清理/保存及删除/新增引用竞争、本人分页与历史删除保护、普通快照核验与绑定。当前身份、COS 和历史保留读取使用明确替身；该测试不依赖 Redis。

```powershell
$env:JAVA_HOME='D:\Jualn\Java\jdk-17.0.18+8'
.\mvnw.cmd -B -ntp '-Dtest=FileFoundationDatabaseTest,CosUploadPolicyTest,CosClientTest,CosAttachmentUrlTest,MediaServiceImplTest,MediaUploadRecordServiceTest,MediaProfileSnapshotTest,AttachmentReadServiceTest,MediaAuthorizationTest,CanonicalResourceMvcTest,ManagedArticleMediaInspectorTest,ManagedAttachmentInspectorTest,MediaAttachmentIdentityTest,OfficialContentCandidateTest,UserProfileUpdateTest,ProfileMediaCallbackTest,PostServiceImplTest,CommentServiceImplTest' '-Devent.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/file_foundation_test_<fresh-suffix>' test
```

`CosUploadPolicyTest` 检查精确资源、创建操作的禁止覆盖条件、允许操作集合及版本状态不明/启用时拒绝签发；不替代真实 COS 授权拒绝、云端版本设置或客户端分片验证。实际环境切换按 runbook §14，不能由本命令推导业务库迁移或云端变更授权。用后关闭本次测试上下文并停止本次专用容器，不动既有共享服务。

资料原子生效的补充回归使用 `EffectiveProfileDatabaseTest`，仅指向本任务拥有且已迁移的 `jdbc:mysql://127.0.0.1:<owned-port>/user_profile_contract_<suffix>`，root/空密码；COS/Media、检查与缓存为替身，实际 Profile Mapper/事务生效。该套件清除并重建其明确的 synthetic user ID 范围，不得指向业务库。验证快照失败保持有效资料、Profile 专用错误与只绑定已采信快照（原上传对象仍临时）等行为：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=EffectiveProfileDatabaseTest' '-Devent.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/user_profile_contract_<owned-suffix>' test
```


## 通知首版验证

使用 Java 17 和既有选择测试脚本。`NotificationFoundationDatabaseTest` 仅接受本次独占 MySQL 8.4 的 `jdbc:mysql://127.0.0.1:<owned-port>/notification_v1_test_<suffix>`，同时显式匹配 server UUID。完整重放并 validate 当前迁移（包括 V98）；测试清理该专用 schema 的合成通知、偏好及 Job，禁止业务或共享实例。真实机制为 MyBatis、Spring事务、Job持久化和MVC；Redis、身份/业务资格、微信Provider及监测授权是显式替身，不代表真实登录、微信或设备验收。缺属性为 SKIPPED。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnotification.v1.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/notification_v1_test_<suffix> -Dnotification.v1.serverUuid=<verified-uuid>'
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17Home>' -TestNames WxNotificationDeploymentTemplatesTest,NotificationFirstReleaseTest,NotificationFoundationDatabaseTest,NotificationDeliveryServiceImplTest,WxNotificationTemplateTest,WxNotificationTemplateRegistryTest,WxNotificationTemplateRendererTest,WxNotificationChannelTest,WxMpNoticeSendServiceTest,WxNoticeBudgetTest,WxNoticeSendResultTest,CanonicalNotificationControllerTest,NotificationCenterMvcTest,CommentNotificationSnapshotTest,NotificationContentCompatibilityTest
Remove-Item Env:JAVA_TOOL_OPTIONS
```

覆盖27项偏好和99项能力、并发组合/重置、触发器故障回滚、33类型站内落库、一级评论模板冻结与真实任务链、原生拒绝/UNKNOWN、服务号安全跳过和去个人化聚合。响应写入 `target/notification-first-release/responses.json` 与 `monitoring.json`，需对照当前 Contracts schemas 校验；目录未配置其他真实模板时不可声称这些模板已实际收件。权限、当前Person绑定与安全字段的专项替身证据由 `NotificationFirstReleaseTest` 给出。保留证据后仅停止本次拥有的测试容器。

## 运行日志基础验证

专项采用真实 Logback/Boot 编码器、内嵌 Tomcat HTTP、Spring executor/scheduler 和独立 Docker 容器；不需要业务 MySQL/Redis、生产凭据或外部 Provider。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=LoggingFoundationTest,HttpLoggingIntegrationTest,ExecutionLoggingTest,ObservabilityConfigurationTest,RequestLogFilterTest,ContextCopyDecoratorTest,GlobalExceptionHandlerTest,JobLoggingContextTest,EventStreamConsumerTest,OutboxPublisherTest' test
.\tools\verify-logging-docker.ps1
```

Java 17 环境沿用本文件入口；测试编译仍遵循全仓 testCompile 的失败分类，不能用旧 class 或跳过测试宣称 PASS。日志测试修改当前测试 JVM 的 Logback 配置后恢复，不与其他修改全局 logger/System.out 的测试并发执行。文件轮转使用 JUnit 临时目录和缩小的阈值，验证实际分片/压缩、过期历史以及下一次真实启动时的归档 cap 收敛；不模拟业务数据。HTTP 使用随机本机端口、48 个并发合成请求，真实验证同步/Callable、5xx、响应 trace、完成摘要和池复用。压力测试阻塞实际输出 stream，再提交 20,000 个合成异常，证明 queue 满时不阻塞；不是生产吞吐、CPU/heap 或 P95 benchmark。

Docker 脚本需要 Windows PowerShell/PowerShell、Docker Engine、Compose 2.24.4+ 和本地已有 `redis:7-alpine`（可显式指定已有、含 sh/awk 的镜像）；不自动拉取。复用镜像仅作日志生产器，network_mode=none，不发布端口、不挂载宿主目录、不启动 Redis 服务。随机独立 Compose project 先核对原样生产 driver/options 和 logs，然后为排除 non-blocking 丢弃对轮转证明的干扰，仅在轮转 fixture 中改用 blocking，写入 85,000 条合成 JSON，检查旧记录淘汰和连续尾部。应用生产片段仍为 non-blocking。退出时只清理该随机 project 的临时容器；保留 `target/logging-docker/result.json` 和两个 fixture 文件作为本机证据，不操作既有容器/卷/daemon 配置。脚本验证的是 Docker 日志机制，不等于实际生产应用制品、Compose 或服务器容量验收。

部署预算、查看与回退见 [Runbook：运行日志部署与容量](../docs/operations/runbook.md#运行日志部署与容量)，字段与异步/异常责任见 [Observability](../docs/observability.md)。


## 管理业务查询与审计定向验证

`AdminBusinessContractDatabaseTest` 需要任务专属 MySQL 实例及 `admin_business_test_<suffix>` 新 schema，显式核对 server UUID；该夹具仅使用本机 loopback、独占实例内的 root/空密码测试身份，禁止指向开发业务库或共享服务。执行完整 Flyway migrate/validate，并使用真实 MyBatis/Spring 事务、ROOT 合成适配与 MockMvc 验证审计、分页/撤权、答疑节点和固定提交姓名来源。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=AdminBusinessContractDatabaseTest,AdminContentOwnerScopeTest,ContentSafetyAuthorizationTest,AdminReviewAuthorizationTest,LearningTaskAuthorizationPolicyTest,LearningTaskSubmitterSummaryTest' '-Dadmin.business.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/admin_business_test_<suffix>' '-Dadmin.business.test.serverUuid=<verified-uuid>' test
```

默认要求 schema 不存在；仅排查本次测试已创建并保留的 schema 时可显式使用 `-Dadmin.business.test.reuseSchema=true`，不得借此复用他人的数据。夹具只重置自己约定的合成记录，不 DROP schema；保留报告后按实例所有权停止测试服务。真实登录、其他 owner 的全部命令、前端联调和生产迁移不由该测试证明。扩大到既有 RBAC/业务专项时仍遵守其各自实例与 schema 前提。

## Security foundation focused validation

复用P1的独占一次性MySQL8.4/Redis与全新p1_security_test_<suffix>前提，选择SecurityFoundationDatabaseHttpTest#foundation*；显式指定p1.redis.port、p1.redis.container、p1.mysql.container。测试包含本次拥有容器的pause/unpause，名称须匹配codex-p1-(mysql|redis)-<8hex>，绝不使用业务Redis。新测试覆盖当前普通注销/重放、双loginType封禁版本、恢复/到期/回滚、旧QR候选、真实ROOT/LEGACY保护、管理兼容拒绝、限流和存储故障。失败保留schema；不放宽全新schema前提。

ArticleOrganizationDatabaseTest#foundation*使用原article_contract_test专用库和真实迁移，创建/清理仅本用例的延迟审计trigger，以真实MySQL等待跨expiresAt证明beforeCommit回滚。其余组织授权回归运行同类原测试。Person信任和模块边界选择ManagedPersonBoundaryTest、PersonAuthenticationBoundaryTest、ArchitectureBoundaryTest；源码/Mock边界不能代替上述真实机制。Admin/Mini均提供test:security-foundation以及原类型检查，Contracts运行npm.cmd run validate。各端真机/浏览器、目标部署配置和外部Provider单列验收。


## 普通社区验证

`PostCommunityDatabaseTest` 仅允许任务独占 MySQL 8.4 的 `127.0.0.1:13381/post_community_test`，显式匹配 `community.test.serverUuid`，root/空密码；该实例不允许指向业务库。首次由拥有者创建空 schema，测试完整 Flyway migrate/validate；后续定点复测只重置该实例的合成账号 99101/99102 及其本用例记录，保留报告。Redis 使用独占 loopback:16381，故障测试仅 pause/unpause 显式拥有的 `codex-post-community-redis-1031`。本机未使用其他服务的 Redis。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=PostCommunityDatabaseTest,ArchitectureBoundaryTest,PostCommentRepresentationTest,PostServiceImplTest,CommentServiceImplTest,PostAuditCallbackTest,AdminContentOwnerScopeTest,AttachmentReadServiceTest,CurrentAuthorizationTest' '-Dcommunity.test.jdbcUrl=jdbc:mysql://127.0.0.1:13381/post_community_test' '-Dcommunity.test.serverUuid=<verified-owned-server-uuid>' '-Dcommunity.test.redisContainer=codex-post-community-redis-1031' test
node tools/validate-post-contract.mjs target/post-community/responses.json
```

真实 MySQL / MyBatis / Spring 事务及 MVC、Redis 是机制；当前账号授权、微信/COS、安全预约和通知受理适配器为显式替身。该验证不证明真实微信、COS、完整登录或客户端部署。响应与目标摘要留在 `target/post-community/`；保留证据后按资源所有权停止任务容器，不操作共享实例。

### Mentor QA focused validation

方案 A 的聚焦回归为 `MentorAnswersTest` 及 `ManagedPersonDatabaseHttpTest#mentorPlanA*+mentorQaProof*+v66Mentor*`；沿下述独占实例及全新 schema 前提执行。前者校验新旧原始归属、冲突拒绝和换绑候选，后者验证同组合自助绑定由管理员原子确认、普通 Person 业务保留、三状态响应、姓名/职务/单位与绑定交集筛选、原作者过滤以及真实来源锁竞争。`PostCommunityDatabaseTest#qa*` 覆盖评论受理、首次公开、改题、历史恢复及回答状态筛选；HTTP 原始响应再按当前 canonical schema 校验。历史证据的只读盘点使用 `src/main/resources/db/validation/mentor_answer_preflight.sql`，仅适用 V82+；线上执行须另获目标授权：按仅旧、仅新、双字段一致/冲突和缺失来源分组，禁止从当前绑定补齐历史快照。


复用 `PostCommunityDatabaseTest` 的已识别专用 MySQL/Redis fixture 与 `tools/validate-post-contract.mjs target/post-community/responses.json`，验证 QA 请求落库、版本/首次公开竞争、固定归属、两级讨论、恢复、筛选分页和真实 MVC 表示。该组件 fixture 的资格与通知 provider 是替身；`ManagedPersonDatabaseHttpTest#mentorQaProofUsesRealPersonBindingLedgerAndSerializesRevocation` 单独验证实际 Person/绑定/导师来源 Mapper、管理事务与资格 gate，要求新的 `independent_person_test_<suffix>` schema、`managed.person.test.serverUuid` 及 `MANAGED_PERSON_TEST_DB_USER` / `MANAGED_PERSON_TEST_DB_PASSWORD`，不得复用已存在 schema。其管理身份/授权范围仍为专用测试 adapter，不能证明正式管理会话、真实 COS/微信或客户端验收。迁移另验证 V81→V82 升级和空库 V1→V82；证据放 target，不能回写 Contract 正文作为部署状态。

### Controlled media focused validation

Java17 Maven聚焦选择 `ArchitectureBoundaryTest,ControlledMediaAuthenticationTest,CosPrivateStorageTest,MediaContentTransportTest,AuditPrivateMediaJobTest,PostCommunityDatabaseTest,FileFoundationDatabaseTest`。PostCommunity专用MySQL/Redis参数沿上节；FileFoundation另传 `event.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/file_foundation_test_<new-suffix>`，要求新schema，验证V73→最新、上传核验快照→私有声明→真实绑定/读取。缺参数的数据库测试是SKIPPED，不能当PASS。该fixture COS/Wx/身份provider为替身；鉴权入口使用真实MVC拦截器并隔离模拟Sa会话，不证明真实云端和客户端已切换。Canonical契约执行相邻Contracts的 `npm.cmd run validate`，变更定位见media paths与post-community协调文档。只读历史核查SQL与实际云端切换分开执行，不能用测试命令访问业务库。


## LearningTask business V1 validation

使用 Java17/Maven Wrapper。`LearningMaterialDatabaseTest` 只允许任务独占 MySQL 8.4 的 `learning_material_test_<小写字母数字后缀>` 全新 schema，显式匹配 `learning.material.test.serverUuid`；同时新建同后缀 `_upgrade`，验证 V84→V86、空库→V86、validate/重复 migrate 及旧任务保留。每次执行此类使用新后缀，不删除/重用已有 schema。实例须由本任务创建或核实独占，root/空密码仅属于该合成夹具。

`LearningTaskDatabaseTest` 仍只接受同一独占实例内的固定 learning_task_test，会重置合成任务/文件记录；已知本任务保留的该 schema 可定点复测，不得传业务库。测试真实 MySQL/MyBatis/Spring 事务、固定补交/统计、过期预览/动态 ETag、原提交账号自审无副作用，以及 MockMvc 纯视频提交→正式接管→驳回重交→历史读取和跨账号/任务拒绝。Media 专项验证真实 Person 绑定下换号自审、失效接收人绑定追溯、通知受理失败回滚、组织并集/阶段交集 SQL、凭证并发恢复和登记/回收竞争。COS/微信、安全/播放、管理身份及部分授权目录是明确替身，不证明真实 Provider 或客户端已就绪。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=LearningTaskDatabaseTest,LearningMaterialDatabaseTest,LearningTaskRecipientOptionsTest,LearningTaskHttpGateTest,LearningTaskPreviewHttpTest,LearningTaskArticleProviderTest,LearningTaskAuthorizationPolicyTest,LearningTaskSubmitterSummaryTest,LearningTaskTextSafetyTest,ManagedArticleMediaInspectorTest,CosUploadPolicyTest,CosPrivateStorageTest,PointMaterialInspectionTest,PointMaterialConfigurationTest,CosVideoSafetyCheckTest,AuditPrivateMediaJobTest,NotificationContentCompatibilityTest' '-Devent.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/learning_task_test' '-Dlearning.material.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/learning_material_test_<fresh-suffix>' '-Dlearning.material.test.serverUuid=<verified-owned-uuid>' test
```

Article 跨领域验证复用同实例的 article_contract_test，首次须由拥有者建空库。显式 `learning.business.test.serverUuid` 开启该专用夹具 migrate/validate 和旧 Post sentinel；不带此属性仍沿原已迁移专项前提。`ArticleContentDatabaseTest` 覆盖普通撤下保护、安全原因/审计失败回滚、任务发布与撤下竞争及原 Article 内容回归。权限、COS和安全 Provider 是替身。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=ArticleContentDatabaseTest,ArticleAuthorizationTest,ArticleContentFieldsTest,ArticleBodyCodecTest' '-Devent.test.jdbcUrl=jdbc:mysql://127.0.0.1:<owned-port>/article_contract_test' '-Dlearning.business.test.serverUuid=<verified-owned-uuid>' test
.\mvnw.cmd -B -ntp '-Dtest=ArchitectureBoundaryTest,BoundaryGuardRulesTest' test
node ../contracts/node_modules/@redocly/cli/bin/cli.js bundle ../contracts/api/openapi.yaml --config ../contracts/redocly.yaml --dereferenced --output target/learning-task-v1-contract.json
node tools/validate-learning-task-http.mjs target/learning-task-v1-business-http-responses.json target/learning-task-v1-contract.json
node tools/validate-learning-task-http.mjs target/learning-task-v1-recipient-wire.json target/learning-task-v1-contract.json
node tools/validate-learning-task-http.mjs target/article-content-http-responses.json target/learning-task-v1-contract.json
```

新增 wire 文件来源为 MockMvc/真实 MySQL 和明确身份/存储替身，校验工具只证明记录响应符合当前 canonical schema。原 b2 默认记录仍保留其历史 loopback 范围，不用旧记录证明当前实现。证据保留在 target；结束后只停止本任务独占实例，不动业务数据库、共享 Redis 或云端配置。默认私有桶为空，真实 COS 拒绝覆盖、Range206、图片安全回调、完整编码正例、客户端兼容及部署仍需各自验收。

## Person 名单与 A7 聚焦回归

沿用独立Person聚合专项的loopback实例UUID、`MANAGED_PERSON_TEST_DB_USER` / `MANAGED_PERSON_TEST_DB_PASSWORD` 和全新独占 `independent_person_test_<suffix>` schema 前提。Java17下运行 `-Dtest=ManagedPersonBoundaryTest,PeopleQualificationPolicyTest,ManagedPersonDatabaseHttpTest`。只检查本次增量可选 `ManagedPersonDatabaseHttpTest#rosterRevision*`；包含真实绑定竞争、重复导入/冲突、受控筛选、A7确认竞争/到期/不可复活、文件清理后历史读取及V86→V87样本升级。完整类还覆盖既有绑定周期、Points、PERSON/ACCOUNT任务和局部共享字段权限。

测试会创建主schema及当前URL后缀 `v87` 的升级schema，旧V66升级方法还使用其原有独占schema；不自动DROP。HTTP身份/capability与外部服务使用合成adapter，MySQL/MyBatis、事务、Flyway和loopback HTTP是真实机制。该测试不证明真实微信/COS/Redis限流联调、业务库迁移或部署。

## 成长记录验证

Java 17 下的聚焦检查：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=PersonGrowthQualificationTest,PostCommentRepresentationTest,PostMediaContentPolicyTest,MediaContentTransportTest,ContentSafetyAuthorizationTest,CurrentAuthorizationTest,NotificationContentCompatibilityTest,ArchitectureBoundaryTest,BoundaryGuardRulesTest' test
```

`GrowthRecordDatabaseTest` 仅接受 `jdbc:mysql://127.0.0.1:13389/growth_record_test`，必须是本专项独占 MySQL 8.4 实例及空 schema，显式传入已核对的实例 UUID。首次运行迁移全历史至 V90，随后重跑只清理专用测试身份的数据。不能指向业务库或共享 schema。测试使用真实 MySQL、Mapper、事务、幂等竞争和图片解码；Person/账号及云端存储使用明确测试替身，不代表 Person gate 并发、真实 COS 或视频可用性验收。

```powershell
.\mvnw.cmd -B -ntp '-Dtest=GrowthRecordDatabaseTest' `
  '-Dgrowth.test.jdbcUrl=jdbc:mysql://127.0.0.1:13389/growth_record_test' `
  '-Dgrowth.test.serverUuid=<已核对的本专项独占实例 UUID>' test
```
