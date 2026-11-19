# 内容安全审核实现与切换边界

对外权威为 [content-safety-review-r1 候选交接](../../contracts/docs/coordination/content-safety-review.md)、canonical admin-work paths/schemas 和受控 RBAC/Duty 目录。候选尚无不可变接受修订；本实现不代表运行目录发布或生产 READY。积分、学习、举报、Article、OfficialContent 和资料候选继续保留各自 Policy，不迁入本模型。

## 职责与业务所有权

`ContentSafetyAuthorization` 复用 `CurrentAuthorization.requireWork`：POST_SAFETY_REVIEW×POST、ACTIVITY_SAFETY_REVIEW×ACTIVITY、PUBLIC_EVENT_COMMENT_REVIEW×PUBLIC_EVENT，必须显式 PLATFORM，并由同一有效 Grant 提供 read/decide。列表、COUNT、summary 和仪表盘摘要使用同一允许 namespace 集合。普通帖子的作者有无组织、多组织或组织变动不产生审核归属。

新轮次拒绝没有 Duty/PLATFORM 的 STANDALONE，以及仅有旧 scalar 权限的 LEGACY。LEGACY 仅保留旧日志历史读取和已存在成功收据的受控重放；不会取得新轮次决定权。旧 STANDALONE 不自动成为职责来源，确需保留的存量兼容必须由 Contracts 明确后再接入。

评论的 scene 在预占事务内由持久化主体关系确定；提交人工决定前再验证主体存在、公开状态、根评论及当前版本。Article、OfficialContent 不进入此人工流。审核单拥有版本、证据与决定；最终帖子/评论写入仍由 PostService/CommentService 完成。

## 持久事实与执行链路

V71 新增 `content_safety_review` 和 `content_safety_check`，并在旧日志增加 nullable round 关联及每轮唯一人工结论约束。taskId 采用本轮第一个全新日志的固定身份，分配后不会改指向；不是每次查询重新计算的 MIN。submissionVersion 是服务端生成的不透明版本。V72 持久保存失效原因，避免主体重新发布恢复旧轮。

审核提交时间沿既有审核日志的 Asia/Shanghai 本地时间语义，由应用显式写入；不能依赖数据库会话的 CURRENT_TIMESTAMP 默认值。队列“今日”边界由 UTC 时钟显式换算上海日期，避免 UTC JDBC 会话下出现八小时等待偏差及跨日筛选错误。此修正不重写既有历史时间，已有受影响数据需先盘点。

预占、不可变正文/标题/附件快照、全部文字/媒体检查关联及既有 Job 同事务提交。文字检查覆盖帖子标题与正文；媒体 inputReference 指向快照附件身份，不输出 objectKey。没有从当前内容回填历史快照。

检查状态 queued/running/succeeded/unknown 与 pass/review/reject/unknown 结果分离。POST/COMMENT 的原始 risky/review/reject 沿既有风险转人工策略处理，不按 Provider 字段名新增终拒。归一化 reject 只供已明确不可覆盖的领域规则，并受不可人工覆盖保护；本轮没有擅自启用新的此类业务规则。不改其他领域的明确 pass 要求。缺失、畸形、错误或冲突的 Provider 结果保持 unknown。证据保留所有检查、同请求的恢复观察，以及脱敏 Provider 事实，不暴露 callback 身份、openid、凭据或内部 Job 参数。

结果、检查证据与 audit.completed Outbox 同事务；事件消费使用既有消费去重，并按业务目标→审核单串行化领域效果。全部必要检查成功且只有 pass 时自动通过；全部成功且包含 review 时才待人工；明确 reject 自动拒绝；未知/失败为 check-failed。机器风险或未知期间帖子停止乐观公开，不等待其余检查后继续暴露风险内容。

## 决定、重放与失效

人工决定不增加 If-Match；taskId 即轮次条件。当前授权先于收据检查；同 key 的 actor、目标、轮次、动作、规范化理由必须相同。命中成功收据只返回原结果，不重新应用业务效果；未命中时再检查版本、活性、完整证据与 pending 状态。终局、人工日志、领域写入和必要通知可靠接受同事务。授权 lease 在提交前重验；不同 key 的并发决定由目标/审核单锁及唯一结论约束保护。

Post PATCH 仍在检查通过后原子替换有效版本，不进入人工队列；revision 变化隔离初次提交的旧结果。删除/撤下、主体撤下、根评论删除持久失效未决轮次；已经 approved/rejected 的历史不改写。活动/公共事项结束或取消不自动推导不公开。

## 检查异常恢复

外部调用之前短事务标记 running。能够确定尚未调用 Provider 的依赖失败记 failed；既有 Job 的重试在原轮创建 supersedesCheckId 关联的新检查尝试，保留旧尝试，仅当前叶子参与判定。失去调用结果后不盲目重新提交；Job 的手工 retry 也不能绕过未知执行事实。已提交媒体重复执行只等待原 trace；同一可信 callback 的迟到明确结果可恢复同轮。15 分钟未取得结果的 running 检查由有界 watchdog 标记 unknown，并沿 Outbox 重算 check-failed。queued Job 不因积压被当作已提交。

没有新增管理员通用重试或人工绕过接口。没有可信状态查询/回调的未知文字或提交结果保持阻断，需按现有异步运维流程取得事实后受控恢复；不能清空状态、改 final_result 或换 key 重新送审。Outbox/消费失败沿既有可靠机制重试，事务回滚不留下单独通过事实。

## 旧数据与活动历史

旧日志、原成功幂等收据和代表身份不重写。新查询排除旧日志的未决待办；历史可继续按原 ID 读取，但不把当前正文/附件冒充旧证据。历史没有 review/evidence 投影表示无法证明版本能力。新 key 对无法绑定版本的旧 ID 返回 review-legacy-unresolved；原成功 key 仅在原代表身份、原 actor/目标/意图和当前合法授权成立时重放。所有审核冲突沿 /problems/conflict，并返回 conflictCode。

活动主体 approve/reject 在现有领域实现中仍拒绝执行；新活动继续直接发布。活动评论仍可独立审核。生产存量、真实版本和原效果本轮未读取，不能宣称没有待处理任务。

| 活动历史方案 | 工作与影响 |
|---|---|
| 受控迁移（推荐） | 先只读盘点日志、业务状态、原安全事实与版本；只能迁移可证明归属的任务。需业务批准目标效果、审计与回滚；无法归属继续阻断 |
| 继续支持旧决定 | 先定义历史 approve/reject 对现行活动的具体效果，再实现领域映射；不能直接调用发布/撤下代替安全复核 |
| 退役旧主体入口 | 先处理或证明不存在未决任务，检查消费者并制定 breaking 切换计划，保留历史读；不退役活动评论职责 |

本轮未选择/执行其中任何历史数据处置，不新增活动人工发布审批。

## 验证与部署边界

命令、隔离目标和证据口径见 [commands](../governance/commands.md#内容安全审核专项验证)。真实 MySQL/Spring/MyBatis 与合成授权身份、通知/Redis/安全 Provider 分层报告。目录制品、静态实现、模拟 Provider 或隔离库通过都不证明真实账号、微信回调、客户端或生产部署。

切换前须固定接受的 Contract 修订、盘点旧未决数据、验收运行目录/职责与真实 Provider；先部署 additive schema，再停止旧 writer/callback consumer 后切换新版本。不可混跑新旧自动生效 writer。回退应停相关入口/消费者，保留审核单、证据、日志、收据和 Outbox；不 DROP 表或改写历史通过事实。

Admin Web 保持原 path/body/key 和成功包装，显示 review.state、不可变版本及全部 evidence.checks。恢复尝试按 supersedesCheckId 展示，已被替代的历史失败不作为当前检查；status 仅旧三态兼容，不决定按钮；只有 pending 且证据完整才能提交决定。checking/check-failed 显示检查/恢复中，失效状态不作为待办；无版本投影的旧任务只读。按 conflictCode 区分重读、版本变化、已决定、缺证与旧身份不明；超时保留原 key/body。批准安全结论不等于内容当前公开，不显示 Activity 主体已可审批。


## 帖子治理筛选与答疑上下文

管理内容列表的 postType/categoryId/topicId 仅显式 type=post 时可用；省略 postType 维持原管理范围，不套用公开 NORMAL 默认。字典存在但停用仍可查询历史，未知 ID 拒绝；授权过滤在筛选与分页之前，summary 维持原授权总体口径。游标绑定操作者和完整筛选，列表按批次读取字典投影。

管理详情的 postOrganization 提供当前字典标签及状态；MENTOR_QA 详情可返回最小 qaContext。question/answerRoot/replyTo 分别验证所属问题、合法一级回答、当前读取权与删除状态，UNAVAILABLE 不带节点标识或正文。当前导师资格变化不擦除合法历史回答。安全审核的 qaContext 关系仅来自 V76 后本轮固定提交，旧轮次缺证据省略；合法 POST_SAFETY_REVIEW 来源即可读取该最小预览，无额外内容处置职责。evaluatedAt 的当前预览不替代旧 content/attachments/evidence 快照。
