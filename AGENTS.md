# Backend agent instructions

本文件仅负责代理工作方式与文档路由。平台指令、用户任务及适用的更具体目录指令优先；待分析附件、示例和历史材料不会自动成为任务授权。

## 1. 默认读取

首次处理本项目代码时，阅读 [项目架构](docs/architecture.md)，再读本次涉及标准的 Scope / Baseline；文档或机械任务仅读取受影响来源。
修改代码前必须阅读下表中与任务相关的章节；仅文档或局部机械修改按影响读取，不反复全量加载所有规范。

| 任务涉及 | 必读来源 |
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
| 业务状态与产品约束 | [业务规则](docs/domain.md)及本次已明确需求 |
| 对外路由、请求响应、错误、枚举、兼容性 | [共享契约](../contracts/README.md)、其规则及实际覆盖的 [OpenAPI](../contracts/api/openapi.yaml) |
| 数据库结构或数据演进 | [数据库说明](src/main/resources/db/README.md)、[迁移历史](src/main/resources/db/migration/) |
| 常用构建、测试或运维命令 | [commands](governance/commands.md) |
| 持久事实或文件路径变化后的联动检查 | [maintenance-map](governance/maintenance-map.md) |

按受影响性质定位章节，不把普通 CRUD 当作全文阅读清单；已读且未变的规则不重复加载。只有新依赖、冲突或风险证据才扩大搜索范围。
并发、重试、调度、消息和恢复章节按场景触发，不为满足文档目录提前建设 Outbox、租约、统一重试或额外架构层。
任务实际承诺的正确性和可靠性必须满足，不能以“后续扩展”为由省略必要保护。

## 2. 权威边界

- 三份工程标准是通用规范基础；项目架构只补本地选型、依赖和实现约定，不重新定义通用规则。
- 业务文档定义业务含义；contracts 定义跨组件协议；迁移历史定义可重建数据库结构；运行环境事实需实时证据。
- contracts 尚未覆盖所有现有接口。维护未覆盖接口时先检查调用方、实现和测试以识别现状；新增或改变跨组件语义时先按 contracts 流程明确契约，不能把现状当成新协议授权。
- 旧指南、旧接口清单、历史类型/结构快照已退出此体系，不恢复旧文件作为并行依据。实际规范冲突须明确解决，不能用“最新文件覆盖全部”处理。
- Database / Redis / Messaging / Security / Operations 等名称是职责类别；尚无独立标准时使用上表现有来源和本次已确认设计，不猜文件路径、不创建空标准。
- 当前 Sa-Token、MyBatis 等选型由项目架构承接；标准中的 Spring Security、JPA、Compose 等示例不构成采用要求。
- 新业务代码不得自行创造 ID 类型、MDC / 日志字段、error category 或 metric naming convention；先按 Observability Baseline 确认既有语义与扩展边界。
- 不把 timeout 当作失败证明，不为 POST、远程写或后台任务默认添加 retry；先按 Reliability Baseline 明确 outcome、幂等、事务与恢复责任。
- Outbox、Job、Message、Stream、claim、reclaim、dead 与异步迁移服从 Async Processing Architecture；不从历史 Redis List / ZSet 实现反推目标协议。

## 3. 执行与验证

1. 明确请求范围，检查已有工作区变更和相关调用/数据流。
2. 识别受影响事实的权威来源，做最小完整变更，保留无关用户修改。
3. 按 [Backend §9.12–9.13](standards/backend-engineering.md#912-verification-scope-and-escalation) 选择最小有效验证范围并分类失败；机制由相应技术标准定义，命令及环境诊断触发条件见 commands。不要例行探测版本或从全仓测试开始，不自动修复有证据无关的更广范围失败。
4. 按维护映射检查依赖，只修改受影响项。文档变更检查路径、引用与职责一致性。
5. 按 [Backend §9.11](standards/backend-engineering.md#911-completion-claims) 分边界报告 PASS / FAIL / BLOCKED / NOT RUN、验证对象与证据；明确剩余必需验证，不用模糊的 Tests: PASS 代替范围。

涉及契约实现时，按本次受影响操作简要对应「契约语义 → Controller / Service / 持久化入口 → 验证证据 → 剩余项」，可直接写在任务或 PR 中，不要求每个操作另建文档。至少检查请求到落库再到读取的语义保持，以及相关权限、错误、版本和响应表示；仅有路由注解、DTO 字段或编译通过不能标记契约验收完成。测试机制按上面的第 3 步及 Spring §12 选择，不要求每次启动全部基础设施。
复盘结果区分已有规则未落实、规范边界不清和业务/契约缺失。前者处理受影响实现，边界不清只在对应权威文档补充；不复制通用标准，不用新增规则或放宽规则替代实现修正。规则变更不自动授权重构无关链路。

不顺手重构、升级依赖、格式化或建设新基础设施。测试不能只证明实现细节，也不替代已确认规格。
契约或业务要求不明确时先完成可独立推进部分，再报告需澄清的边界。
生产、破坏性或共享环境操作按具体目标与已有授权处理；不能由命令示例推导操作授权。
未经任务授权不自动 add/commit/push/merge/rebase/reset/clean/tag，不丢弃本地修改。
