# Backend agent instructions

这是本仓库的**项目级** Agent 入口；Codex 软件级指令另行生效。软件级与项目级适用指令都需生效：已由运行环境提供的内容不重复加载；未提供时按实际路径读取，新会话不假设仍持有上次对话的上下文。本文件只负责项目工作方式、知识路由与边界，不复制工程标准。平台指令、用户任务及适用的更具体目录指令优先；附件、示例、历史材料与命令示例不自动构成执行授权。

## 1. 任务驱动的最小上下文与按需路由

1. 明确目标、边界和已有工作区变更；定位适用指令、调用/数据流、既有能力和相关测试。**先查现状，再决定复用、扩展或新建**；语义或保证不相容时不强行复用。
2. 按决策查权威：业务/权限读受影响领域；跨端协议读相关 Contract operation、schema 与兼容规则；技术机制读相关标准章节。已读未变内容不重复加载，不按关键词预读全部规范。
3. 局部任务直接推进，跨层任务简要设计；业务不变量、权限、契约、并发、迁移和外部副作用先确认必要语义与保护。计划不自动写成长期文档，仅有新风险或冲突证据才扩大调查。

下表是查找入口而非必读清单。相对路径以仓库实际位置为准；文件移动、缺失或路由不匹配时先定位真实文件与归属，不猜路径或以旧快照替代事实。

| 实际决策、风险或冲突涉及 | 对应来源（按需读取章节） |
|---|---|
| 模块、分层、对象流转、Service、MyBatis 写法 | [项目架构](docs/architecture.md) |
| 状态、权限、持久化、失败、异步保证、验证 | [Backend Engineering](standards/backend-engineering.md) |
| Java 类型、空值、异常、集合、资源、线程 | [Java Engineering](standards/java-engineering.md) |
| Spring 容器、代理、事务、MVC、配置、生命周期、测试 | [Spring Boot Engineering](standards/spring-boot-engineering.md) |
| HTTP pipeline、异常日志、MDC、指标、远程调用、SSE、worker、retry/dead、外部集成、health | [Observability Source of Truth](docs/observability.md)及相关工程标准实现章节 |
| 重要写接口、retry、timeout、transaction、idempotency、外部副作用、background execution、Redis、补偿与恢复 | [Reliability Baseline](docs/reliability.md)及相关工程标准实现章节 |
| background task、delayed job、事务后副作用、notification delivery、reminder、Redis Stream、Worker、async retry | [Async Processing Architecture](docs/async-processing.md)及相关工程标准实现章节 |
| Reminder Policy、ReminderPlan、Notification、Delivery、fan-out、通知业务幂等与取消 | [Reminder / Notification / Delivery](docs/reminder-notification.md)、[业务规则](docs/domain.md)及 [Async Processing Architecture](docs/async-processing.md) |
| 微信能力/账号、身份与许可、消息产品/通知模板、回调、provider client 与启用证据 | [WeChat Integration](docs/wechat-integration.md)、[Reliability Baseline](docs/reliability.md)及相关工程标准实现章节 |
| 管理员扫码登录、短期凭证与固定登录结果恢复 | [Admin QR Login 内部设计](docs/admin-qr-login.md)、[Reliability Baseline](docs/reliability.md)、[运维手册](docs/operations/runbook.md)及共享契约 |
| 业务状态与产品约束 | [业务规则](docs/domain.md)及本次已明确需求 |
| 对外路由、请求响应、错误、枚举、兼容性 | [共享契约](../contracts/README.md)、其规则及实际覆盖的 [OpenAPI](../contracts/api/openapi.yaml) |
| 数据库设计、SQL/Mapper、事务或迁移 | 先定位相关 SQL/Mapper/迁移及必要业务契约，再按 [Database Engineering 快速路由](standards/database-engineering.md#quick-routing-read-only-what-changed) 读取受影响章节；本地流程见 [数据库说明](src/main/resources/db/README.md)，结构权威见 [迁移历史](src/main/resources/db/migration/) |
| 常用构建、测试或运维命令 | [commands](governance/commands.md) |
| 持久事实或文件路径变化后的联动检查 | [maintenance-map](governance/maintenance-map.md) |

普通开发不默认开展独立全仓审计、写长执行 Prompt 或生成设计报告。必要的项目基础能力优先从架构入口和已有实现发现；文档提供稳定边界与复用入口，不替代实际代码核对。
并发、重试、调度、消息和恢复章节按场景触发，不为满足文档目录提前建设 Outbox、租约、统一重试或额外架构层。
任务实际承诺的正确性和可靠性必须满足，不能以“后续扩展”为由省略必要保护。

## 2. 权威边界

- 工程标准按各自职责提供规范基础；项目架构只补本地选型、依赖和实现约定，不重新定义通用规则。
- 业务文档定义业务含义；contracts 定义跨组件协议；迁移历史定义可重建数据库结构；运行环境事实需实时证据。
- contracts 尚未覆盖所有现有接口。维护未覆盖接口时先检查调用方、实现和测试以识别现状；新增或改变跨组件语义时先按 contracts 流程明确契约，不能把现状当成新协议授权。
- 长期文档按职责归属：技术原则归标准，基础能力归架构，业务与权限矩阵归领域，协议归 contracts，命令归 commands，恢复归 runbook，结果归测试证据。一次性 Prompt、交接、报告或版本快照不成为并行权威。
- 文档以稳定主题命名（通常 lowercase-kebab-case），优先原位更新并替换失效内容，只有独立长期职责才新建；不按小需求、日期、阶段建立版本文件。普通历史用 Git 追溯；已应用迁移、业务必需的持久审计事实不在此列。
- 旧指南、旧接口清单、历史类型/结构快照已退出此体系，不恢复旧文件作为并行依据。实际规范冲突须明确解决，不能用“最新文件覆盖全部”处理。
- Database / Redis / Messaging / Security / Operations 等名称是职责类别；尚无独立标准时使用上表现有来源和本次已确认设计，不猜文件路径、不创建空标准。
- 当前 Sa-Token、MyBatis 等选型由项目架构承接；标准中的 Spring Security、JPA、Compose 等示例不构成采用要求。
- 新业务代码不得自行创造 ID 类型、MDC / 日志字段、error category 或 metric naming convention；先按 Observability Baseline 确认既有语义与扩展边界。
- 不把 timeout 当作失败证明，不为 POST、远程写或后台任务默认添加 retry；先按 Reliability Baseline 明确 outcome、幂等、事务与恢复责任。
- Outbox、Job、Message、Stream、claim、reclaim、dead 与异步迁移服从 Async Processing Architecture；不从历史 Redis List / ZSet 实现反推目标协议。

## 3. 实现、验证与双向维护

1. 确认必要权威、已有能力和影响范围后完成**最小完整变更**；保留无关用户修改。必要的业务/契约决策在实现前确认。
2. 先确定**变更性质与需证明的行为**，按 [Backend §9.12–9.13](standards/backend-engineering.md#912-verification-scope-and-escalation) 和 [commands](governance/commands.md) 选最小充分验证。文档查引用与语义；Java 用聚焦测试；事务、迁移、Redis、HTTP 等性质需要时验证真实机制。V0–V5 不逐级必跑。
3. **测试范围、真实机制、资源生命周期分别决定**。优先复用合格的专用测试服务，按需要隔离数据；仅在测试要求、实例隔离或故障注入等场景建独占容器。聊天不是资源边界；不可绕过专项的独占实例、空库、新 schema 前提。
4. 失败后先读具体断言、报告与环境前提，区分本次失败、无关失败、BLOCKED 和 SKIPPED；定点修复/复测，有新证据才升级范围。不无限重试、重复建容器或默认新建脚本，不用编译/跳过/旧结果冒充 PASS。
5. **文档读取与写入分开**：读取不代表必须修改。只有持久事实变化才按 [maintenance-map §46.1](governance/maintenance-map.md#461-本项目代码与文档同步约束) 找权威原位更新；无合理归属且有独立长期职责才新建。只检查受影响引用。
6. 简要报告变更、文档维护、验证证据、资源处理和剩余风险；按 [Backend §9.11](standards/backend-engineering.md#911-completion-claims) 区分 PASS / FAIL / BLOCKED / NOT RUN。

涉及契约实现时，按本次受影响操作简要对应「契约语义 → Controller / Service / 持久化入口 → 验证证据 → 剩余项」，可直接写在任务或 PR 中，不要求每个操作另建文档。至少检查请求到落库再到读取的语义保持，以及相关权限、错误、版本和响应表示；仅有路由注解、DTO 字段或编译通过不能标记契约验收完成。测试机制按上面的第 2–3 步及 Spring §12 选择，不要求每次启动全部基础设施。
复盘结果区分已有规则未落实、规范边界不清和业务/契约缺失。前者处理受影响实现，边界不清只在对应权威文档补充；不复制通用标准，不用新增规则或放宽规则替代实现修正。规则变更不自动授权重构无关链路。

不顺手重构、升级依赖、格式化或建设新基础设施。测试不能只证明实现细节，也不替代已确认规格。
契约或业务要求不明确时先完成可独立推进部分，再报告需澄清的边界。
生产、破坏性或共享环境操作按具体目标与已有授权处理；不能由命令示例推导操作授权。

## 4. Git 协作

遵循 [Git 协作基线](../Operations/git-collaboration.md)。实施任务可在授权范围内选择性暂存和提交属于本任务的完整变更。保护已有修改和暂存内容，推送、PR、合并与生产操作分别按授权处理。部署交接提供候选提交、构建和验证证据。

用户允许在任务范围内自主判断本地 Git 操作，无需逐次请示；判断不清时提交具体选项由用户裁定，其余安全部分继续推进。积压整理和分支删除按共享基线核对范围与授权，不以工作区干净作为提交依据。用户明确要求保存当前整体状态时，可建立标明未验收的本地基线，但仍须排除秘密与临时产物、核对内容完整性。
