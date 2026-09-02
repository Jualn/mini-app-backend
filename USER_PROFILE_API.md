# 当前用户资料更新响应

`GET /v1/users/me` 与 `PUT /v1/users/me` 统一返回 `Result<UserProfileVO>`。PUT 原有请求字段和权限语义保持不变；成功响应由空 data 改为完整资料。

- 增加 `id`，使用现有 Jackson Long 字符串序列化，便于客户端识别资料所属用户。
- 包含昵称、头像、背景、简介、性别、角色/状态及其说明、能力列表、创建时间；未修改字段也返回。
- 头像和背景 URL 仍由后端校验归属后的 objectKey 确定，不回显客户端 URL。
- Service 返回 `UserProfileBO`，Controller 经 `UserConverter` 转成 VO；不返回 Entity，不让 Service 依赖 VO。
- Service 在更新事务内读取完整记录，不经 Redis；事务代理提交成功后 Controller 才发送成功结果。
- 此命令的资料缓存失效安排在提交之后，失败记录日志，等待现有 TTL 收敛；未新增可靠重试队列，也不声称解决全部 Redis 读回填竞态。
- 现有提交后审核仍可能后续修改资料；此响应代表本次保存结果，不代表审核永久通过。

小程序直接用成功响应更新共享资料，不再额外 GET 或重拉帖子/评论。发布顺序为后端先、小程序后；无 DDL/DML 迁移。

`UserProfileUpdateTest` 覆盖完整返回、服务端规范化 URL、未改字段保留、失败不注册失效、提交后缓存故障和 Controller 返回契约。测试使用 Mock，不替代真实数据库事务、Redis、COS 或 HTTP 联调。

正常运行：Java 17 下执行 `mvn -Dtest=UserProfileUpdateTest test`。如果其他旧测试编译失败，可先编译主代码，通过 `dependency:build-classpath` 构造测试类路径，单独用 javac 编译此测试，再执行 `mvn -Dtest=UserProfileUpdateTest surefire:test`；这种定向结果不能标为全量测试通过。
