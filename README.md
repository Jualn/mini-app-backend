# Jualn Campus Backend

后端规范由通用工程标准、项目适配和工作流程组成。根目录只保留本索引及代理入口，按下面的职责查阅。

## 文档职责

| 文档 | 唯一维护内容 |
|---|---|
| [Backend Engineering](standards/backend-engineering.md) | 与技术无关的正确性、状态、失败、可靠性和验证原则 |
| [Java Engineering](standards/java-engineering.md) | Java 语言、类型、资源和线程规则 |
| [Spring Boot Engineering](standards/spring-boot-engineering.md) | Spring 容器、代理、事务、Web、配置和生命周期机制 |
| [项目架构](docs/architecture.md) | 目录、模块所有权、层与对象、Service、MyBatis 及现有基础设施选型 |
| [业务规则](docs/domain.md) | 当前业务决定，不包含接口清单或阶段进度 |
| [数据库说明](src/main/resources/db/README.md) | 迁移目录使用与本项目数据库演进约束 |
| [AGENTS](AGENTS.md) | 代理默认读取路由及任务工作方式 |
| [commands](governance/commands.md) | 常用命令、前提、副作用与验证边界 |
| [maintenance-map](governance/maintenance-map.md) | 事实变化后需要检查哪些依赖 |

[CLAUDE.md](CLAUDE.md) 只转向 AGENTS，不另维护规则。

## 开发顺序

先读项目架构，再按任务阅读三份工程标准的相关章节。涉及产品行为读业务规则；涉及独立组件协议读 [contracts](../contracts/README.md)；涉及表结构读 [迁移历史](src/main/resources/db/migration/)。执行命令前查 commands。
通用标准中的高级机制按实际场景使用，不要求提前建设。规范适用于新增与本次修改的链路，现有代码是否符合仍需检查。

## 维护方式

一个持久规则只有一个归属。更新对应主题的当前正文，删除被替代结论，不追加“后文覆盖前文”的增量规范。
协议只在 contracts 维护；目前未覆盖的接口须在实际变更时确认，不能假定所有接口已经迁移或验收。
数据库结构只由迁移历史重建；若日后需要结构视图，应从实际迁移或验证库生成并标明来源，不再手工维护第二份 DDL。
测试次数、阶段进度和部署结果放在任务/发布证据中，不写进长期标准。运行环境和生产状态不从旧文档推断。
新增文件前先判断现有职责是否已覆盖，避免再出现多套 GUIDE、接口快照和并行开发规范。
