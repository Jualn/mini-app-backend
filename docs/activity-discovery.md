# 活动发现与受众查询

协议权威为 [活动发现协调](../../contracts/docs/coordination/activity-discovery-list.md) 和 [listActivities](../../contracts/api/paths/user.yaml)。本文记录后端实现与部署边界，不另定义协议。迁移文件不表示目标库已经执行，实际发布另按目标环境核对。

## 数据来源与接口

活动受众统一由 `activity.audience_department_ids` 表达：非空字符串数组表示 DEPARTMENTS，`[]` 表示 CAMPUS，NULL 表示草稿尚未设置。管理保存的 CAMPUS 写入 `[]`，省略受众写入 NULL；发布仍要求受众已设置。公开响应保留 `audienceScope` 对象及 `audienceSummary`，不改变请求和响应结构。

`GET /v1/activities` 的 Controller → ActivityResourceConverter → ActivityServiceImpl → ActivityMapper/XML 保持：ALL 无受众限制；CAMPUS 返回全院；DEPARTMENT 返回全院或数组精确包含指定规范部门的活动。省略模式沿用当前身份模型的全院兜底，因为尚无已确认部门身份事实。发布、删除、生命周期、分类和标题/简介条件在 LIMIT 前组合，按 published_at DESC、id DESC 排序，读取 pageSize+1 判断续页。封面、timeline、报名计数按页批量补充。

显式查询继续使用 v2 游标，绑定模式、departmentId、服务端身份、关键词、分类、生命周期和排序；省略模式继续使用 v1。查询组合错误返回 validation-error，游标上下文变化返回 invalid-cursor。将来引入已确认部门身份时，省略模式的查询和游标必须一起调整。

已移除 `/v1/search/activities`、对应 Service 入口和孤立的旧列表 Query/VO/转换；活动 Entity、BO、Mapper 与写入链路不再依赖位掩码。`/v1/search/posts`、通用搜索及搜索文档同步不属于此次删除范围，继续保留。小程序运行调用已转向 canonical 活动列表。

## 历史数据与 V31

管理端固定目录为 information/science/finance/humanities/foundation，发布时发送字符串 IDs，后端直接保存 JSON；结构化非空 JSON 本来就优先于位掩码，因此不增加表或字段。

[V31](../src/main/resources/db/migration/V31__retire_activity_audience_bitmask.sql) 对没有结构化受众的行按原公开读取结果回填：0 或全院位转 `[]`；旧部门位转字符串 `"1"` 至 `"5"`。非空 JSON 原样保留，包括数字和未知 ResourceId。迁移不猜测这些 IDs 对应哪个规范部门；ALL 可以读取，规范部门查询不会自动匹配这些值。若业务需要历史部门精确发现，必须先确认映射，再以独立可追溯数据修复处理，不能宣称此次已完成业务映射。

迁移检查所有行，包括草稿、已删除行。非数组、非字符串/空白/超过128字符、重复 ID，或没有结构化受众且无法表达的未知位会在更新前阻断。每条回填行增加 contract_version，避免迁移前管理草稿版本继续覆盖新表示。回填后删除 `audience_scope`。兼容历史 nullable mask 时，已发布 NULL 转全院，未设置草稿保持 NULL；正常历史 DDL 的 mask 为 NOT NULL。

## 发布与验证入口

V31 切换顺序与恢复见 [runbook](operations/runbook.md#活动发现切换)；迁移/预检归 [数据库说明](../src/main/resources/db/README.md)。查询、转换、分页/游标、非法旧数据阻断和旧搜索路由退役使用 [定向活动测试](../governance/commands.md#event-focused-test--定向活动测试)。真实 MySQL、HTTP、身份/媒体替身及客户端分别报告，不以旧测试次数声明当前库已升级或小程序已验收。
