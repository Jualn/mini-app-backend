# 数据库演进入口

[migration/](migration/) 是可重建数据库结构的唯一可执行来源。数据演进的通用保证见 [Backend §3](../../../../standards/backend-engineering.md#3-persistence--data-evolution)，模块所有权和 SQL 写法见 [项目架构](../../../../docs/architecture.md)。
本文件只补充 Flyway 与当前仓库流程，不维护手工 DDL 快照、生产版本表或服务器参数建议。

## 编写与切换

- 使用 `V<递增整数>__<英文描述>.sql`。已进入永久环境的版本不可重写，修正时新增版本；不要通过修改 Entity 假装完成迁移。
- 变更时检查历史数据、约束/索引、Entity、Mapper/XML、字段投影、相关测试与应用切换顺序。
- 新增字段的默认值和历史回填须明确。大批量回填与 DDL 分开设计，说明批次、校验、重试与恢复边界。
- 需要混合版本兼容时先扩展、迁移，再收缩旧字段。恢复旧 JAR 不撤销已执行的数据库变化。
- 不在 SQL 中写真实凭据、生产数据、环境专用库名或 USE 语句。

## 执行与证据

- 应用不负责生产启动时自动迁移。生产迁移通过受控运维流程在核对目标、备份及取得相应授权后显式执行。
- 现有数据库接入 Flyway 前必须比对结构和历史，再确定 baseline；不把任何固定 baseline 版本套用于未知环境，不启用 baselineOnMigrate 绕过核对。
- 空库完整重放与现有库升级是两种验证。CI 的 Flyway 配置见 [workflow](../../../../.github/workflows/backend-ci-cd.yml)，命令边界见 [commands](../../../../governance/commands.md)。
- 当前数据库版本以目标环境的 flyway_schema_history 和实际结构核验为证，不能由仓库存在某个 V 文件推断已部署。
- [validation/](validation/) 是专项盘点/检查 SQL，不能替代版本迁移；执行前检查其内容及目标。

迁移新增或机制变化时按 [maintenance-map](../../../../governance/maintenance-map.md) 检查依赖，只更新受影响来源，不重建旧数据库设计文档。
