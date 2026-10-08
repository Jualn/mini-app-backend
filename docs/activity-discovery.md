# 活动发现列表后端实现与切换

协议权威为 [活动发现协调](../../contracts/docs/coordination/activity-discovery-list.md) 和 [listActivities](../../contracts/api/paths/user.yaml)。本文只记录后端实现、验证边界和切换义务，不另定义协议。

## 实现边界

`GET /v1/activities` 的 Controller → ActivityResourceConverter → ActivityServiceImpl → ActivityMapper/XML 支持可选 `audienceFilter`：

- ALL 不应用本人部门或仅全院兜底。
- CAMPUS 使用既有全院判定：结构化部门数组为空，且旧位掩码为 null、0 或含全院位。
- DEPARTMENT 为上述全院集合或 JSON 部门数组包含指定规范字符串；不把旧数字/位掩码转换成规范部门。
- 省略保持当前身份模型的旧行为：模型没有已确认学科部事实，仅返回全院。旧接口、字段、默认生命周期及旧游标格式保留。

结构化非空部门数组优先于旧位掩码，与现有读取表示一致。受众、标题/简介搜索、分类、生命周期、已发布和未删除条件在同一 SQL 的 LIMIT 前组合；排序仍为 published_at DESC、id DESC，Service 读取 pageSize+1 判断续页。封面、timeline、平台计数继续按页批量补充。无封面时不以 null 查询可能拒绝 null key 的批量结果 Map。

新显式查询使用 v2 游标，指纹包含受众模式、departmentId、服务端 UserContext 身份、关键词、分类、生命周期及既有排序。不同模式、部门或身份不兼容时返回 invalid-cursor。省略模式保持既有 v1 游标；目前无可绑定的已确认部门事实，不把客户端 departmentId 当作身份。以后引入已确认部门身份时，须同步省略模式查询与游标上下文，不能只修改过滤。

查询枚举及组合错误通过 ContractProblemException 返回 validation-error，并携带定位 query 参数的 errors。规范目录仅约束新 departmentId 查询；管理写入和响应中的既有 ResourceId 接受范围不收紧，历史未知值保持原值。

## 发布来源及历史数据

已核对管理端 activity-editor.config / activity.http-mapper：固定目录为 information/science/finance/humanities/foundation，发布 DEPARTMENTS 时直接发送 audienceCodes。后端 AdminActivityConverter 将其保存为 JSON，ActivityServiceImpl 的 Entity 构造保留该 JSON，读取从 audience_department_ids 优先还原；此次没有新增发布字段或更改 writer。

这只证明仓库链路，不证明已部署版本和现有数据库符合目录。部署数据核查尚未执行。旧位掩码展开的数字 ID、未知字符串不能按位置猜测学校部门；ALL 可读取这些活动，规范部门查询不会替它们创造匹配关系。

按本次用户要求，本阶段不执行或新增活动迁移，不转移已有数据，不移除废弃字段，不删除旧接口。小程序推送后再处理收缩清理。**推送前仍须只读核查部署数据**：若发现非规范 ID，记录受影响活动、原始数组/位掩码和经业务确认的映射；必要数据转换尚未可用时，不能宣称历史部门发现已验收。用户将转换延后不等于该验收条件已经满足。

## 验证入口与范围

复用 [commands 的定向脚本](../governance/commands.md#event-focused-test--定向活动测试)，显式选择：

```powershell
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' -JavaHome '<Java17目录>' `
  -TestNames CanonicalResourceMvcTest,ActivityPublicCursorCodecTest,ActivityServiceImplTest,AdminActivityConverterTest,ActivityLegacyRepresentationTest
```

`ActivityDiscoveryDatabaseTest` 可加入上述集合，并提供 `-JdbcUrl 'jdbc:mysql://127.0.0.1:<本次一次性端口>/activity_discovery_test'`。仅接受该专用 loopback schema，root/空密码，不读取应用凭据。测试创建 activity 并写入 synthetic fixture；表已存在则创建失败，不覆盖。结束时只删除本次成功创建的表，不执行 Flyway，不修改原有业务表。使用普通表是因为 MySQL TEMPORARY 表不支持当前续页 SQL 对同表的多次子查询引用。

数据库测试通过真实 MVC、Service、生成的 MapStruct、MyBatis XML 和 MySQL 查询验证：全院/多部/单部/未知 ID、旧省略行为、JSON 优先、过滤前分页、45 条匹配数据的三页稳定次序、AND 搜索/分类/生命周期、未发布/已删除排除、空结果和跨模式/部门/身份游标拒绝。媒体、timeline、报名计数与认证入口使用替身；不证明真实鉴权、发布事务、完整数据库迁移、线上 HTTP 或设备联调。

管理转换测试同时保留规范多部门和旧数字/未知字符串的写入及读取表示。MVC 测试验证非法组合及错误定位；游标测试验证新旧模式不互用。

## 当前交付与剩余项

实现依据快照（2026-10-08，SHA-256；日期或 OpenAPI info.version 不证明运行版本）：

- contracts/api/paths/user.yaml：`1A6D9B7B5D3A0D43BC8392F6AD955E002A39251564F21EAF9A1FE6BE06E8C020`
- contracts/api/schemas/activity.yaml：`401C9202A76704EE8079AE75E056B742ABA45AB91E07977C6667DE173DD6B81D`

2026-10-08 本地验证 PASS：使用仓库 Wrapper、Java 17.0.18 和上述定向脚本，执行六个测试类共 36 项，失败 0、错误 0、跳过 0，退出码 0。ActivityDiscoveryDatabaseTest 运行于本次创建的 MySQL 8.0.40 一次性容器，覆盖上述数据库读取边界；测试容器在任务结束前移除。git diff --check 通过。未运行全仓测试，已有无关 MapStruct 映射警告未在本次扩展修复。

小程序实现由并行聊天负责；后端部署、部署数据只读核查、真实 HTTP/小程序联调及推送未在此任务执行。不得仅凭旧服务返回 200 判断新参数已生效，也不得静默回退仅全院。

小程序推送并确认消费者已切换后，再核实旧入口全部调用方，制定必要的数据映射、可恢复迁移和废弃字段/接口删除方案；本阶段不从本文推导生产操作授权。
