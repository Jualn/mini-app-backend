# Jualn Campus Backend

本页是文档总索引。按任务查找对应主题；读取顺序和工作方式由 [AGENTS](AGENTS.md) 维护。工程标准、项目机制、业务专题、协议、运维与证据分别维护，正文不记录逐轮实施进度。

## 文档职责

| 文档 | 唯一维护内容 |
|---|---|
| [Backend Engineering](standards/backend-engineering.md) | 与技术无关的系统保证、日志事件责任、验证范围/升级/失败分类与证据 |
| [Java Engineering](standards/java-engineering.md) | Java 基线、语言/类型/资源规则、源码注释与日志调用纪律 |
| [Spring Boot Engineering](standards/spring-boot-engineering.md) | Spring 容器、代理、事务、Web、配置、日志上下文及测试机制 |
| [Database Engineering](standards/database-engineering.md) | 数据建模、SQL、约束、事务、迁移与数据库验证规则 |
| [项目架构](docs/architecture.md) | 目录、模块所有权、层与对象、Service、MyBatis 及现有基础设施选型 |
| [Observability Baseline](docs/observability.md) | ID 语义、日志/异常责任、错误分类、指标基数、安全与 health 契约 |
| [Reliability Baseline](docs/reliability.md) | 结果确定性、重试、幂等、外部副作用、补偿与 reconciliation |
| [Async Processing Architecture](docs/async-processing.md) | Outbox、Durable Job、Stream、Worker、retry/dead/recovery 与异步迁移边界 |
| [业务规则](docs/domain.md) | 业务入口、跨领域不变量和当前产品决定 |
| [人员与组织](docs/person-foundation.md) | 独立 Person、档案/标识/绑定、组织事实、名单与导师资格 |
| [职责与授权](docs/rbac-foundation.md) | RBAC、统一职责的 Backend 裁决、来源与权限消费边界 |
| [离线授权治理](docs/rbac-offline-governance.md) | 运维 operation/effect、首次 ROOT、切源与更正的内部机制 |
| [积分](docs/points.md) | Person 账务、受理周期、审核/冲正、私有材料与依赖边界 |
| [内容安全审核](docs/content-safety-review.md) | 职责、轮次、机器证据、人工决定、未知结果恢复与旧任务切换 |
| [学习任务](docs/learning-task.md) | PERSON/ACCOUNT 责任、受众预览、发布/提交/审核与依赖边界 |
| [Article](docs/article.md) | 组织署名、分类、发布、不可变媒体与安全检查 |
| [活动发现](docs/activity-discovery.md) | 活动受众查询、游标与历史表示边界 |
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

本地 Redis 与 Flyway 使用统一入口：在启动 IDEA 后端前执行 `.\tools\local-dev.ps1`。它复用现有 Redis，使用独立 Flyway 容器管理 Windows 本机开发库并输出版本 / 执行结果；详情、单独控制命令及旧手动 SQL 库的首次接管见 [数据库说明](src/main/resources/db/README.md#本地开发容器管理)。

先定位相关实现、现有能力和适用指令，再按实际决策读取上表主题的必要章节，不默认通读架构或全部标准。涉及跨组件协议读 [contracts](../contracts/README.md)，涉及表结构读 [迁移历史](src/main/resources/db/migration/)。命令查 commands；验证范围按 Backend §9.12–9.13 选择。
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


## 历史证据的使用

`docs/operations/learning-task-integration/` 保存旧学习任务切片的脱敏请求、迁移/更正结果及输入快照；`docs/operations/rbac-v1-release-review/` 保存旧 RBAC 评审输入、逐效果 CSV 与检查结果。它们是原采集时点的证据，不定义当前协议、账号授权或生产状态，不因正文整理改写历史输入及其哈希。CSV 仍由 `tools/validate-rbac-offline-plan.mjs` 使用。

当前验证命令统一见 commands，生成结果保留于各命令声明的 `target/` 与 Surefire 目录。旧真人切片、隔离环境、测试替身、真实外部 Provider 和生产分别判断；V66 以前的 User 主体结果不能作为独立 Person 切换验收。新增执行结果不追加到长期正文；发布和恢复所需条件维护于 runbook，已被替代的分析/交接/进度文件不另建归档。

COS 环境与数据分区采用[架构 §2.2](docs/architecture.md#22-cos-环境与访问类别隔离)，云端盘点、四桶准备和混用存量迁移按[运维手册 §14.1](docs/operations/runbook.md#141-cos-环境隔离与混用桶迁移)执行。后端仅维护 `.env.dev` / `.env.prod` 两份完整环境配置，COS 与其他服务配置放在一起，旧桶配置注释保留。启动器须加载对应文件中的环境变量；配置文件修改不代表运行进程或云资源已完成切换。
