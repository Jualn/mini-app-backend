# Admin API 契约基线

> 版本：v0.1  
> 状态：已冻结跨模块通用约定；各模块按“已确认 / 部分实现 / 待实现”标记。  
> 适用范围：`/v1/admin/**`。本文件描述后端业务契约，不描述页面布局、中文标签、颜色或 Mock 数据。

## 1. 契约依据与优先级

本契约按以下事实确定：

1. `DEVELOPMENT_GUIDE.md` 的模块所有权、对象边界和管理端用例规则。
2. `DATABASE_DESIGN.md` 中当前表、字段和索引。
3. 当前 Controller、Service、Mapper、VO 的真实实现。
4. 管理端现有 HTTP Adapter 的实际请求方式。

发生冲突时：数据库事实以 `DATABASE_DESIGN.md` 为准；业务边界以
`DEVELOPMENT_GUIDE.md` 为准；前后端通过本文件统一 HTTP 契约。前端 Mock 类型、页面展示字段和候选
HTTP Adapter 不能单独作为后端契约。

## 2. 资源与数据所有权

| 管理能力 | HTTP 入口所属模块 | 主要事实源 | 写入规则 |
|---|---|---|---|
| 管理员认证 | `admin/auth` | Redis 二维码会话、`user_profile` 管理身份 | `admin/auth` 只管理认证状态，不修改用户资料 |
| 人工审核 | `audit` | `content_audit_log` | 审核日志由 `audit` 写；目标状态由目标模块 Service 修改 |
| 内容管理 | `content` 只读聚合入口 | `post`、`comment`、`report`、`user_profile` | 帖子和评论写操作分别委托 `PostService`、`CommentService` |
| 活动管理 | `activity` | `activity`、`activity_enrollment` | 活动状态由 `ActivityService` 统一维护 |
| 用户管理 | `user` | `user_profile`、`user_agreement` | 用户状态由 `UserService` 统一维护 |

`AdminContentMapper` 和审核列表 SQL 是受控的只读管理投影，可以跨表读取，但禁止写入其他模块的表。

## 3. 通用 HTTP 约定

### 3.1 身份与权限

- 浏览器管理端使用独立的 admin Token，通过 `Authorization` 请求头传递。
- `/v1/admin/auth/qr-sessions` 的创建、轮询和取消是扫码登录公开阶段，不要求 admin Token。
- `/v1/admin/auth/qr-confirmations/{sessionId}` 必须使用小程序用户登录态。
- 其余 `/v1/admin/**` 先验证有效 admin 登录态，再按接口检查具体权限点。
- 前端权限仅用于隐藏入口，不能代替后端授权。

### 3.2 统一响应

成功响应继续使用项目现有 `Result<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1788138000000,
  "traceId": "request-trace-id"
}
```

约束：

- 只有成功响应使用 HTTP 2xx 和 `code = 200`。
- 失败响应的 HTTP 状态必须与失败语义一致，前端不能把“HTTP 200 + 业务失败”当作统一错误协议。
- 当前项目使用 `traceId`，不同时引入含义重复的 `requestId`。
- `data` 在无返回值的成功命令中为 `null`。

### 3.3 ID、时间、枚举和空值

- 数据库 `BIGINT` ID 在 JSON 中统一返回十进制字符串；路径参数也使用该字符串形式。
- BO、Entity、Mapper 内部继续使用 Java `Long`，只在 HTTP Converter 转成字符串。
- 枚举返回稳定英文 code，不返回数据库数字，也不要求后端返回中文 label。
- `avatarText`、`statusLabel`、`categoryLabel`、`roleLabel`、`tone`、相对时间和页面路由由前端 mapper 生成。
- `null` 表示业务上不存在或不限；不得在同一字段混用 `null`、空字符串和 `0`。
- v1 时间目标格式为带偏移的 ISO 8601，例如 `2026-08-31T09:30:00+08:00`。当前使用
  `LocalDateTime` 的 VO 属于待迁移项，迁移完成前前端按 `Asia/Shanghai` 解释。

## 4. 列表与游标分页

所有 admin 长列表统一使用以下查询字段：

```text
cursor: string | null
pageSize: integer
```

规则：

- `cursor` 是后端生成的不透明字符串，前端不得解析或自行构造。
- 游标必须绑定当前排序方式；筛选、排序或 `pageSize` 变化后，前端清空游标。
- 默认 `pageSize = 20`。审核、活动、用户最大 100；内容聚合列表当前最大 50。
- 无游标表示第一页；非法、过期或与排序不匹配的游标返回 HTTP 400。
- 排序字段相同时必须使用唯一 ID 作为稳定次序。
- v1 后端只承诺向后翻页；前端通过本地游标栈实现“上一页”，后端暂不返回 `previousCursor`。

统一列表响应：

```json
{
  "items": [],
  "summary": {},
  "hasMore": false,
  "nextCursor": null,
  "pageSize": 20
}
```

`summary` 的统计口径由各模块说明，不随当前页条数推算。

## 5. 错误协议

| HTTP 状态 | 场景 | 示例稳定 code |
|---|---|---|
| 400 | 参数格式、非法枚举、非法游标、业务命令缺字段 | `BAD_REQUEST` 或模块参数错误码 |
| 401 | admin Token 缺失、过期或已注销 | `UNAUTHORIZED` |
| 403 | 已登录但没有动作权限，或禁止操作该目标 | `FORBIDDEN` |
| 404 | 管理资源、审核任务或目标不存在 | `NOT_FOUND` 或对应模块 not-found code |
| 409 | 状态已变化、任务已处理、幂等键冲突 | `DATA_CONFLICT` |
| 429 | 二维码创建、确认或其他受限操作超过频率 | `TOO_MANY_REQUESTS` |
| 500 | 未预期内部失败 | `SERVER_ERROR` |
| 502 | 微信、COS 等外部依赖失败 | `EXTERNAL_SERVICE_ERROR` |

错误响应仍使用 `Result<Void>` 的 `code/message/timestamp/traceId`。`message` 可以安全展示，但前端分支判断必须使用
HTTP 状态和稳定 code，不能匹配中文消息。

## 6. 当前接口契约

### 6.1 管理员认证：已确认

```text
POST   /v1/admin/auth/qr-sessions
GET    /v1/admin/auth/qr-sessions/{sessionId}
DELETE /v1/admin/auth/qr-sessions/{sessionId}
POST   /v1/admin/auth/qr-confirmations/{sessionId}
GET    /v1/admin/auth/me
POST   /v1/admin/auth/logout
```

- 轮询和取消使用 `X-Admin-Login-Secret`。
- 二维码只包含 session 标识，不包含轮询密钥或可登录 Token。
- 浏览器只能一次领取独立 admin Token。
- `/me` 返回稳定用户 ID、显示名、角色 code 和权限集合。

### 6.2 人工审核：已确认，作为首条联调基线

```text
GET  /v1/admin/reviews
GET  /v1/admin/reviews/{taskId}
POST /v1/admin/reviews/{taskId}/decisions
```

列表查询：`keyword/targetType/riskLevel/tab/sort/cursor/pageSize`。

审核决定使用 `Idempotency-Key`，请求体为：

```json
{
  "action": "approve",
  "reasonCode": null,
  "remark": "可选备注"
}
```

- `reject` 必须提供稳定 `reasonCode`。
- 同一操作人、任务和命令重放同一幂等键返回成功。
- 不同命令复用幂等键、机器审核未完成或任务状态已变化返回 409。
- `content_audit_log.idempotency_key` 唯一索引是最终防重约束。
- 目标状态与人工审核日志在同一事务中提交。

### 6.3 内容管理：后端部分实现，前端仍为 Mock

```text
GET  /v1/admin/contents
GET  /v1/admin/contents/{type}/{id}
POST /v1/admin/contents/{type}/{id}/{action}
```

- v1 `type` 仅支持 `post/comment`。
- `action` 支持 `pin/unpin/feature/unfeature/take-down/restore`，但实际允许动作由类型和当前状态决定。
- `take-down/restore` 必须提供原因。
- 帖子当前不能安全恢复，因为数据库尚不能区分用户删除与管理员下架；请求返回 409。
- 当前详情 VO 中的 `avatarText/statusLabel/context/history/tone` 是展示模型遗留项，不纳入稳定契约；HTTP 接入前改为结构化事实字段。
- 当前数据库没有通用管理员操作日志，内容详情不得伪造操作历史。

### 6.4 活动管理：分页与 HTTP DTO 已对齐，命令能力仍部分实现

规范入口：

```text
GET    /v1/admin/activities
GET    /v1/admin/activities/{id}
GET    /v1/admin/activities/{id}/draft
POST   /v1/admin/activities/drafts
PUT    /v1/admin/activities/{id}/draft
POST   /v1/admin/activities/{id}/submit-review
POST   /v1/admin/activities/{id}/pin
DELETE /v1/admin/activities/{id}/pin
POST   /v1/admin/activities/{id}/cancel
POST   /v1/admin/activities/{id}/end-early
DELETE /v1/admin/activities/{id}
POST   /v1/admin/media/activity-upload-credentials
```

列表查询：`keyword/status/category/audience/sort/cursor/pageSize`。

稳定响应事实包括：

- 字符串 `id`、标题、摘要；
- `category/status/auditStatus/audienceCodes` 稳定 code；
- 主办方、地点、置顶状态、有效订阅人数、容量；
- 开始、结束、报名截止、浏览量和更新时间；
- 汇总中的报名中、进行中、审核中、即将开始数量及时间窗口。

详情在列表事实之外返回正文、脱敏联系方式、参与方式、二维码、原始指标、有效订阅数、开启提醒人数、作者摘要、附件和时间线。

当前边界：

- 后端 HTTP 入口已接收并返回不透明 `cursor`；内部仍可使用解码后的记录 ID 完成稳定 SQL 翻页。
- 前端 HTTP mapper 已将事实 code、作者、附件、时间线和原始指标适配为页面模型，不再直接强制转换后端 VO。
- 二进制附件保存使用后端签发的 `objectKey`；前端通过临时凭证直传 COS，保存时不提交可信 URL，后端依据
  `objectKey` 重建 URL，并在活动事务内将当前管理员拥有的 `PENDING` 上传记录绑定为 `BOUND`。
- 外部链接只接受 HTTPS URL，不创建媒体上传记录；二进制附件不能用客户端 URL 替代 `objectKey`。
- 报名二维码仍只有 `qrcode_url`，没有对应 `objectKey` 字段；HTTP 模式暂不开放二维码文件上传，避免绕过媒体绑定闭环。
- 创建、提交、取消等请求虽然前端发送幂等键，但后端尚未实现活动命令幂等；不能宣称已完成。

### 6.5 用户管理：分页与已确认事实已对齐，聚合能力仍待实现

已确认入口：

```text
GET  /v1/admin/users
GET  /v1/admin/users/{id}
POST /v1/admin/users/{id}/mute
POST /v1/admin/users/{id}/ban
POST /v1/admin/users/{id}/restore
PUT  /v1/admin/users/{id}/role
```

列表查询：`keyword/status/role/sort/cursor/pageSize`。

稳定响应事实包括字符串 `id`、昵称、头像 URL、角色 code、状态 code、限制原因、限制到期时间、协议版本、最后登录和注册时间。详情增加简介、性别 code、脱敏 OpenID、UnionID 绑定事实和协议同意时间。

当前边界：

- 后端 HTTP 入口已接收并返回不透明 `cursor`；游标绑定排序方式，非法或错配游标返回 400。
- `avatarText/roleLabel/statusLabel` 已由前端 HTTP mapper 生成。
- 内容数量、举报数量、通知设置等前端详情字段尚无已确认的后端聚合实现。
- 前端 HTTP 模式将未提供的聚合事实显式显示为“未提供”，并隐藏 `most-content` 排序。
- `PUT /v1/admin/users/{id}/role` 接收 `role/reason`，要求 `user:role` 权限；后端仅允许调整正常账号，禁止修改自己的角色、重复修改和移除最后一个管理员，并记录持久化管理员操作日志。
- 用户处置尚未实现请求幂等和通用管理员审计，前端发送 header 不代表后端已支持。

## 7. 数据库能力与缺口

已具备：

- `user_profile` 的角色、状态、限制原因、限制到期、登录时间和注销时间；
- `activity` 的管理状态、审核状态、置顶、时间、容量和统计字段；
- `post/comment` 的管理状态、审核状态和软删除事实；
- `report` 的处理人、处理结果、备注和处理时间；
- `content_audit_log` 的人工审核人、动作、原因和幂等键唯一索引。

尚不具备：

- 通用 `admin_operation_log`，因此不能承诺活动、用户、内容管理的完整持久化操作历史；
- 活动、用户、内容写命令的通用幂等记录；
- 能区分帖子“用户删除”和“管理员下架”的独立状态事实；
- 用户角色变更历史和“最后一个管理员”保护所需的完整管理模型；
- 系统健康、通知投递批次和失败明细等后续管理模型。

缺失数据库事实的能力保持“待设计”，不以结构化日志、Mock 历史或前端静态数据冒充。

## 8. 本轮冻结结论与下一步

本轮已冻结：

1. 后端返回业务事实，展示 label 由前端 mapper 生成。
2. 所有 admin 长列表请求统一使用不透明 `cursor`，不再对外暴露 `lastId`。
3. 列表统一返回 `items/summary/hasMore/nextCursor/pageSize`，v1 不承诺后端上一页游标。
4. 失败使用非 2xx HTTP 状态和稳定业务 code，成功才使用 HTTP 2xx + `code=200`。
5. BIGINT ID 在 HTTP JSON 中使用字符串。
6. 只有审核决定当前具备数据库唯一键支撑的真实幂等闭环。

本轮实现切片已完成活动和用户的 `cursor` 请求/响应闭环、前端 HTTP mapper，以及活动二进制附件的
`PENDING -> BOUND` 可信媒体闭环，没有拆分 Service 或扩展新的业务所有权。真实 COS/CORS/数据库联调、二维码
`objectKey` 数据模型和用户聚合统计继续保持待验证或待设计；角色调整已完成代码闭环，真实数据库迁移与 HTTP 联调仍待环境验证。
