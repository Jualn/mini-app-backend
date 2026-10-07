# Timeline Semantic Preflight

更新日期：2026-09-27

本报告是正式实现 ReminderPolicy 前的阻断检查。它只收口 Timeline 的稳定业务语义，不重新设计 ReminderPlan、Notification、Delivery 或 AsyncJob。

## 1. 结论

当前 `timeline.node_type VARCHAR(32)` 已经承担受控业务 semantic，而不是 POINT/RANGE 结构类型：

```text
label
= 可编辑展示文本

node_type
= 稳定业务 semantic code

start_precision/end_precision + start_time/end_time/time_description
= EXACT_POINT / EXACT_RANGE / DATE_POINT / DATE_RANGE / TEXT 结构
```

因此不新增重复的 `semantic_type` 列，也不把 VARCHAR 强制改成数字枚举。第一版在现有 `node_type` code set 中只增加：

- `ACTIVITY_START`
- `PUBLIC_EVENT_START`

报名截止继续复用已经稳定存在的 `REGISTRATION_END`，不另造 `ACTIVITY_REGISTRATION_DEADLINE/PUBLIC_EVENT_DEADLINE` 同义 code。ReminderPolicy 后续只能读取这些稳定 semantic，不读取 label、displayOrder、nodeKey 命名或“第一个 EXACT_POINT”。

## 2. 证据

| 检查项 | 真实证据 | 结论 |
|---|---|---|
| schema | V5 为 timeline 增加 `node_type VARCHAR(32)`，并以 precision/time 字段表达结构；V9 增加稳定 `node_key` | node_type 可以继续承载 semantic，无 schema 扩列必要 |
| migration | V12 明确写入 `REGISTRATION_START/REGISTRATION_END/EXAM/OTHER`，并把旧 CUSTOM 归为 OTHER | 现有值已经是业务阶段，不是 schedule kind |
| DTO / contract | Admin TimelineNode.type 是受控字符串；schedule.kind 独立为五种结构 | semantic 与结构已分维度；调整前缺少 subject-specific START semantic |
| converter | Activity/PublicEvent Converter 将 type 原样写入 `TimelineItemBO.nodeType`，将 title 写入 label | Admin 可直接写稳定 semantic；无需从 label 推导 |
| service / mapper | canonical create/update 只能经 Activity/PublicEvent aggregate 调用 `replaceTimelines`；独立 update/delete 已禁用 | semantic 写入 owner 明确，可在 canonical path 校验 subject compatibility |
| existing rules | 平台报名窗口一直按 `REGISTRATION_START/REGISTRATION_END` 判断，不按 title | `REGISTRATION_END` 可安全复用为报名截止 semantic |
| PublicEvent phases | `EXAM/FINAL/MATERIAL_SUBMISSION/...` 表示具体阶段，但不能统一证明事项开始或截止 | 不自动映射为 PUBLIC_EVENT_START/DEADLINE |
| real DB samples | 本机只读连接被 MySQL 拒绝：`Access denied for user 'ACER'@'localhost' (using password: NO)` | BLOCKED；仓库 migration/fixture 不能冒充当前数据库样本 |

## 3. 写入与兼容决定

- Java `TimelineSemantic` 集中维护 code、读取兼容和 Activity/PublicEvent subject compatibility；Converter 不再各自复制字符串集合。
- Activity Admin 只接受 `ACTIVITY_START`，PublicEvent Admin 只接受 `PUBLIC_EVENT_START`；其余现有共享 semantic 保持兼容。
- 未知历史值在读取表示中归为 `OTHER`，但不把原始数据库值自动改写成已知 semantic。
- canonical 写入拒绝未知 code 和跨主体 START code；legacy compatibility path 不在本任务中强行清洗。
- OpenAPI 继续以字符串 enum 表达 semantic，并为 Activity/PublicEvent 提供各自受限的 TimelineNode schema。
- Admin 共享编辑器按主体提供 semantic 选项：Activity 只展示 `ACTIVITY_START`，PublicEvent 只展示 `PUBLIC_EVENT_START`；请求仍由各自 canonical aggregate 整体替换 Timeline。
- 小程序读取类型已接受两个新增 response enum，避免已正确写入的 START semantic 在消费端成为未知类型。

## 4. 历史数据与 backfill

当前没有足够证据执行自动 semantic backfill：

- `OTHER + label=活动开始` 不是证据；
- `displayOrder=0` 不是证据；
- `EXACT_POINT` 不是证据；
- `node_key=legacy-activity-time/legacy-exam-date` 可用于人工分类，但当前 nodeKey 未被声明为永远保留的 migration provenance，不能单独作为自动 UPDATE 条件；
- `EXAM` 只证明考试阶段，不能普遍证明 PublicEvent start。

所以旧记录维持 `OTHER` 或原 semantic，不产生对应 start Reminder。只有目标数据库 preflight 与可追溯来源共同证明无歧义后，才新增独立、可审查 migration；宁可少发，不错误提醒。

## 5. 只读 preflight

执行 [timeline_semantic_preflight.sql](../src/main/resources/db/validation/timeline_semantic_preflight.sql) 可获得：

- timeline 当前 column/schema；
- target × node_type × precision 分布；
- label 样本（仅供人工分类）；
- OTHER/未知 code、legacy key 候选；
- START semantic 跨主体错误；
- Reminder semantic 非精确时间、重复 semantic；
- published/active 主体缺失精确 start；
- 报名截止重复与 orphan timeline。

SQL 全部为 SELECT，不根据 label/key 自动更新数据。

## 6. ReminderPolicy 解锁条件

只有以下条件完成后，才启用对应规则：

1. 管理端与 contracts 能写入正确 subject-specific START semantic（代码与静态契约已完成，真实 HTTP 联调仍待执行）；
2. 发布校验能保证需要 start Reminder 的主体恰好一个精确 START；
3. 目标数据库运行 preflight，并对旧 published/active 主体完成“不提醒或人工修正”的结论；
4. Policy 测试证明 label、排序和非目标 EXACT 节点不影响规则选择；
5. `REGISTRATION_END` 仍按现有精确报名窗口约束使用。

当前代码已在 semantic foundation 之上实现两套独立 ReminderPolicy：只识别唯一、精确、显式的上述 semantic；旧 `OTHER`/未知记录自然不产出规则。目标数据库尚未执行本 preflight，因此这里只能声明代码与静态验证完成，不能声明历史数据已适合上线。
