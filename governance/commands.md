# Backend commands

所有命令从 backend 根目录执行。此目录记录真实仓库入口，不把示例命令当作已验证操作。

## 环境

项目目标是 Java 17。Windows 使用 Maven Wrapper `mvnw.cmd`；Linux/CI 使用 `./mvnw`，不依赖全局 Maven。
先确认当前进程 JAVA_HOME/PATH 指向 JDK 17，再执行构建或测试。不要为了修正文档修改系统环境变量。

```powershell
java -version
.\mvnw.cmd --version
```

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
| JAVA-VERSION | C0 | `java -version` | 当前 Java 版本 |
| MAVEN-VERSION | C0 | `.\mvnw.cmd --version` | Wrapper 与实际 JDK |
| MVN-COMPILE | C1 | `.\mvnw.cmd -B -ntp compile` | 编译主代码，不能证明业务通过 |
| MVN-TEST | 条件 C1 | `.\mvnw.cmd -B -ntp test` | 先确认测试依赖和环境隔离 |
| MVN-VERIFY | 条件 C1 | `.\mvnw.cmd -B -ntp verify` | 仅运行实际绑定到生命周期的检查 |
| MVN-PACKAGE-SKIP-TESTS | C1 | `.\mvnw.cmd -B -ntp package '-DskipTests'` | 跳过测试执行，仍可能编译测试 |

上表列出仓库支持的命令入口，不代表本次任务已经执行成功。报告时记录实际环境、退出码和结果。
Maven 会下载依赖、写入本地缓存及 target/。不要用 `clean` 代替一般文件清理。

定向测试模板（将类名和方法名替换为真实目标，PowerShell 保留参数引号）：

```powershell
.\mvnw.cmd -B -ntp '-Dtest=TestClass' test
.\mvnw.cmd -B -ntp '-Dtest=TestClass#testMethod' test
```

`-Dtest` 限定执行集合，不保证只编译该测试文件；无关旧测试编译失败时也会阻断。必须报告实际执行范围。
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
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>'
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>' -TestNames ActivitySubscriptionControllerTest,ActivityEnrollmentServiceImplTest
```

不传 JdbcUrl 时数据库专项测试按其条件跳过，只能报告实际执行集合。
传 JdbcUrl 属 C2：脚本目前只接受 127.0.0.1 加显式端口/库名，数据库必须是已按测试要求应用迁移的一次性本地库；脚本使用 root/空密码，部分测试提交合成数据。业务库或生产库不适用。执行前重新核对脚本和测试前提，测试库生命周期由本次测试任务负责。

## 尚未登记的运维操作

本仓库没有 Compose 文件，不登记 COMPOSE-* 命令。后续采用 Compose 时再按真实服务名和卷定义补充。
直接启动应用、数据库迁移/备份/恢复、Redis 清理和生产发布，先检查环境、脚本、恢复范围及已有授权；不提供可盲跑的通用命令。
生产发布当前由 CI/CD 调用服务器脚本；服务器当前状态不能从仓库文档推断。

变更常用命令时同步检查 AGENTS、CI、工具和引用文档，报告命令、退出码、目标环境与未验证范围。
