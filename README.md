# Jualn Campus Backend

后端规范由通用工程标准、项目适配和工作流程组成。根目录只保留本索引及代理入口，按下面的职责查阅。

## 文档职责

| 文档 | 唯一维护内容 |
|---|---|
| [Backend Engineering](standards/backend-engineering.md) | 与技术无关的系统保证、日志事件责任、验证范围/升级/失败分类与证据 |
| [Java Engineering](standards/java-engineering.md) | Java 基线、语言/类型/资源规则、源码注释与日志调用纪律 |
| [Spring Boot Engineering](standards/spring-boot-engineering.md) | Spring 容器、代理、事务、Web、配置、日志上下文及测试机制 |
| [项目架构](docs/architecture.md) | 目录、模块所有权、层与对象、Service、MyBatis 及现有基础设施选型 |
| [Observability Baseline](docs/observability.md) | ID 语义、日志/异常责任、错误分类、指标基数、安全与 health 契约 |
| [Reliability Baseline](docs/reliability.md) | 结果确定性、重试、幂等、外部副作用、补偿与 reconciliation |
| [Async Processing Architecture](docs/async-processing.md) | Outbox、Durable Job、Stream、Worker、retry/dead/recovery 与异步迁移边界 |
| [业务规则](docs/domain.md) | 当前业务决定，不包含接口清单或阶段进度 |
| [Reminder / Notification / Delivery](docs/reminder-notification.md) | 提醒策略与计划、用户通知事实、渠道投递状态及三者边界 |
| [WeChat Integration](docs/wechat-integration.md) | 微信目标结构、账号/凭据/身份/许可、回调与消息产品、维护流程和验收边界 |
| [Admin QR Login 内部设计](docs/admin-qr-login.md) | 扫码登录内部存储、状态转换与固定登录结果恢复；对外协议仍归 contracts |
| [运维手册](docs/operations/runbook.md) | 运行排障、受控恢复、部署配置与兼容限制 |
| [数据库说明](src/main/resources/db/README.md) | 迁移目录使用与本项目数据库演进约束 |
| [AGENTS](AGENTS.md) | 代理默认读取路由及任务工作方式 |
| [commands](governance/commands.md) | 常用命令、前提、副作用与验证边界 |
| [maintenance-map](governance/maintenance-map.md) | 事实变化后需要检查哪些依赖 |

代理规则统一由 AGENTS 维护，不建立第二份工具专属规则副本。

## 开发顺序

先读项目架构，再按任务阅读三份工程标准的相关章节。涉及产品行为读业务规则；涉及独立组件协议读 [contracts](../contracts/README.md)；涉及表结构读 [迁移历史](src/main/resources/db/migration/)。执行命令前查 commands；环境诊断不是每次任务的前置步骤，验证范围按 Backend §9.12 选择。
通用标准中的高级机制按实际场景使用，不要求提前建设。规范适用于新增与本次修改的链路，现有代码是否符合仍需检查。

## 维护方式

一个持久规则只有一个归属。更新对应主题的当前正文，删除被替代结论，不追加“后文覆盖前文”的增量规范。
协议只在 contracts 维护；目前未覆盖的接口须在实际变更时确认，不能假定所有接口已经迁移或验收。
数据库结构只由迁移历史重建；若日后需要结构视图，应从实际迁移或验证库生成并标明来源，不再手工维护第二份 DDL。
测试次数、阶段进度和部署结果放在任务/发布证据中，不写进长期标准。运行环境和生产状态不从旧文档推断。
已完成任务的执行 Prompt、交接清单、preflight 分析与实施报告不留在开发文档树中，也不另建旧文档归档；先把仍有效的设计、命令和恢复限制收口到对应长期来源，再删除一次性材料并更新引用。SQL migration 与仍被验证流程使用的 validation SQL 按数据库职责保留。
代码与文档同步、同主题原文维护及新增文档条件统一按 [maintenance-map §46.1](governance/maintenance-map.md#461-本项目代码与文档同步约束) 执行，避免多套 GUIDE、接口快照和并行开发规范。

Reminder/Notification/Delivery 的当前设计见上表专题来源，对外协议见 [共享 contracts](../contracts/README.md)，运行排障见运维手册。Admin QR Login 的协议见 [共享 Contract](../contracts/docs/coordination/admin-qr-login.md)，内部机制见上表内部设计。

部署确认、配置和恢复限制见 [运维手册](docs/operations/runbook.md)。文档命名使用主题明确的 lowercase-kebab-case；阶段号、Prompt/Report 和实施日期不作为长期主题文件名。
