# 数据库迁移

`migration/` 是数据库结构演进的唯一可执行来源，使用 Flyway Open Source
管理。应用本身不在生产启动时自动执行迁移。

## 当前版本

| 版本 | 含义 | 生产状态 |
|---|---|---|
| `V1` | 2026-06-17 首次初始化的 20 张业务表 | 2026-08-31 Baseline |
| `V2` | 托管媒体 object key 与 `media_upload_record` | 2026-08-31 Success |
| `V3` | 管理员审核命令幂等键 | 2026-08-31 Success |

现有生产库必须先经过结构核对，再显式执行 `baselineVersion=1`。禁止在生产
启用 `baselineOnMigrate`，也禁止对现有生产库执行 `V1`。

## 编写规则

- 文件名使用 `V<递增整数>__<英文描述>.sql`。
- 已经进入永久环境的版本禁止修改；修正时新增更高版本。
- 结构变更同时更新根目录 `DATABASE_DESIGN.md`、Entity、Mapper/XML 和测试。
- 采用 expand/contract：先添加兼容结构，再部署应用，清理旧结构另建版本。
- 长时间数据回填不与结构迁移混写；先明确批次、校验、恢复和重试边界。
- SQL 不包含环境名、真实密码、生产数据或 `USE` 语句。

## CI Migration Policy

CI 在重放 SQL 前执行 `.github/scripts/check-migration-policy.sh`：

- 所有版本文件必须使用 `V<正整数>__<lower_snake_case>.sql`，且版本不能重复。
- 相对 PR base 或 push 前一提交，历史迁移只允许保留，禁止修改、删除和重命名。
- PR 修改 `entity/` 下的 Java Entity 却没有新增迁移时直接失败。
- 确认只是 Entity 映射或注释变化时，可给 PR 添加 `no-db-migration` 标签显式豁免。
- `DATABASE_DESIGN.md` 变化但没有新增迁移时给出警告，要求确认它只是文档修正。

这项检查只能防止常见遗漏，不能从 Java 自动推导完整表结构，也不能发现所有
人工修改数据库造成的 schema drift。开发库仍应通过同一迁移链更新，并定期从
空 MySQL 重放验证。

CI 会在空 MySQL 8.0.40 上从 `V1` 重放到最新版本并执行 `validate`。生产迁移
由服务器运维脚本在备份和人工确认后调用同一固定版本的 Flyway CLI。
