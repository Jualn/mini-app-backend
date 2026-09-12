# 项目架构与实现约定

本文件负责本项目的模块所有权、依赖方向、对象流转和持久化选型。它是通用标准的项目适配，不重复定义 Java、Spring 机制或业务协议。通用规范入口见 [AGENTS](../AGENTS.md)。
规则用于新增和本次修改的链路，不宣称所有历史实现已符合，也不要求顺手重构无关代码。

## 1. 项目布局

应用根包为 `cn.jualn.miniapp`，保持单体、按业务模块组织：

| 位置 | 职责 |
|---|---|
| `module/<业务>/` | 业务用例、状态、持久化及对外能力 |
| `common/` | 无独立业务归属的结果、异常、上下文和技术工具 |
| `config/` | 框架配置与 Bean 装配 |
| `infrastructure/` | 缓存、队列等技术能力实现 |
| `third/` | 微信、COS、AI 等外部协议与客户端适配 |
| `src/main/resources/mapper/` | 自定义 MyBatis XML |
| `src/main/resources/db/` | 迁移与数据库验证材料 |

新业务规则留在所属模块，不继续向 common 堆业务枚举和模型。保留已有共享类型的兼容性，按实际依赖逐步调整。
不为目录完整提前创建空层级。Repository、Facade、Command/Query 分拆等只在现有边界无法清楚表达实际需求时引入。

## 2. 模块与数据所有权

所有权包含数据写入、合法状态变化及相关派生数据失效的责任。下表是职责分配，不是数据库完整字段快照。

| 模块 | 所属数据或能力 |
|---|---|
| user | user_profile、user_agreement |
| setting | user_setting |
| post | post |
| activity | activity、activity_enrollment、activity_registration |
| exam | exam_info、exam_subscription；当前承载公共事项 |
| eventcontent | event_section、event_action；共享正文与参与入口 |
| comment | comment |
| interact | like_record、share_record、view_count_cache、view_log |
| media | media_attachment、media_upload_record；媒体绑定与清理 |
| timeline | timeline |
| audit | content_audit_log |
| search | search_doc |
| notify | notification、notify_plan |
| report | report |
| admin/auth | 独立管理身份与权限机制 |
| admin/operation | admin_operation_log |
| auth、wx、content | 认证、微信回调、内容能力编排；不得绕过数据所有者写表 |

跨模块只依赖对方公开 Service 接口和其提供的 BO/标量，不引用对方 Mapper、Entity、Controller 模型或 Converter。
共享子资源由其所属 Service 处理；活动/公共事项主体负责业务资格及组合用例，不能因为一张表被共享使用就形成多个写入所有者。

## 3. 层与对象

普通调用路径：Controller → Converter → Service → 本模块 Mapper / 其他模块 Service → Converter → 响应。

| 位置或对象 | 项目约定 |
|---|---|
| Controller | HTTP 解析、结构校验、认证主体提取、Service 调用及契约响应适配；不访问 Mapper、Redis 或第三方 Client |
| Request / Query | HTTP 入参；放在模块 dto 下，管理入参使用独立 dto/admin |
| BO | Service 的业务命令、条件和结果；提供方定义跨模块 BO |
| Entity | 所属模块持久化对象，不向 HTTP 或其他模块传递 |
| VO | 模块 vo 下的响应模型，管理响应独立；Service 不返回 VO |
| Converter | Request/Query 与 BO、BO 与 VO 的显式转换；不查询数据库、不决定业务流程 |
| Payload | 异步边界中的 ID、类型和必要快照；不携带 Entity、请求对象或事务资源 |

Service 实现身份相关权限、归属、状态和唯一性等业务判断。Bean Validation 通过不代表业务可执行。
字段很少且仅用一次时可以在 Controller 显式构造，避免仅为转发新建类。
MapStruct 映射遗漏应逐项确认并映射或明确 ignore；不能忽略未确认的新字段。
保留 BO 命名体系，不因 Java 标准允许 record 或强类型就批量替换现有模型。

## 4. Service 与管理用例

- Service 围绕完整业务能力或共同不变量组织，不要求一模块一个 Service 或一表一个 Service。
- 模块公开用例使用 Service 接口；私有辅助逻辑不放接口，不为所有类创建接口。
- 同模块有多个 Service 时，写入仍由明确的状态所有者统一管理；查询 Service 可以读本模块数据，不拥有写状态规则。
- 不同权限、状态迁移或副作用的用户/管理用例采用不同入口和语义明确的方法，不使用 adminMode/isAdmin 切换两套流程。
- 管理活动归 activity，管理公共事项归 exam；不将所有管理业务集中到 admin 模块。
- Callback、Listener、Handler 负责入口适配，调用状态所有者 Service，不自行堆 Mapper 和业务流程。
- 拆分依据是业务职责、权限、事务或依赖分化，不按行数机械拆分；不添加只有转发价值的层。
- 循环依赖先检查数据所有权与编排方向，必要时提取查询能力或编排；不将跨模块 Mapper、静态 Bean 获取或 @Lazy 作为长期补丁。

## 5. MyBatis 选型与字段读取

按表达清晰度选择，新增 SQL 遵循下表：

| 场景 | 写法 |
|---|---|
| 清楚的单表 CRUD、少量条件 | BaseMapper / LambdaWrapper |
| 极短、固定、单表原子 SQL 或标量查询 | Mapper 注解 |
| 动态筛选、联查、聚合、列表投影、复杂分页、批量、数据库特有函数 | Mapper 方法 + 同名 XML |
| 条件状态迁移、CAS、FOR UPDATE 并发协议 | XML，便于审查完整条件与影响行数 |

Service 可构建简单 LambdaWrapper，但不拼 SQL 字符串；Mapper 不承担业务编排。
注解不承载 script/foreach 或长动态 SQL。重复或复杂 Wrapper 提升为语义清楚的 Mapper 方法，不为一次简单查询机械增加方法。
绑定参数与排序允许值必须受控，禁止拼接客户端字段或 SQL 片段。

| 查询目的 | 返回和字段 |
|---|---|
| Q0：存在/数量 | boolean、count 或必要 ID |
| Q1：权限/状态/摘要 | 标量或只含判断字段的 BO/投影 |
| Q2：完整详情 | 明确需要时读取完整 Entity 或详情 BO |
| Q3：列表/搜索/统计 | 明确列字段的列表 BO/投影 |

Q0/Q1/Q3 不使用 SELECT *。XML 优先显式列字段；完整 selectById 只用于确需整条记录的场景。
部分字段 Entity 仅供局部只读，不传入 updateById。组合数据优先批量补充，避免逐条数据库或跨 Service 查询。
分页形式由接口契约决定；内部排序必须稳定，同值时增加 ID 等唯一排序依据。不能用旧“默认游标”规则改变已确认协议。
状态更新核对受影响行数；唯一性与并发正确性遵循 Backend 标准，不能以先查后写替代数据库保证。
物理或软删除依所属数据设计决定，不对所有表统一物理删除。

## 6. 现有基础设施的使用边界

- 认证继续使用 Sa-Token。普通请求从 UserContext 等服务端上下文取得身份；管理请求使用独立 AdminStpUtil。需要的 operatorId/reason 显式传给用例。
- 对外错误由 GlobalExceptionHandler 统一适配为 RFC 9457 Problem Details，并使用真实 HTTP 4xx/5xx 状态；成功响应仍由各接口契约决定，不强制统一包装。可预期业务拒绝使用 BusinessException，外部集成失败按 ExternalServiceException 分类；ResultCode 仅是内部分类，必须在 Web 边界显式映射为稳定 problem type，不能直接成为公共协议。
- Converter 和 HTTP 层按对应协议输出；不将现有 Result<T> 包装强加给所有新契约。
- Redis 通过 infrastructure/cache 下的 RedisService 使用，键定义集中在 RedisKeyConstant；数据模块负责相关缓存的写入与失效。
- 普通数据库派生缓存采用提交后失效、读未命中回填。失败收敛与可接受陈旧范围遵循 Backend §2；协调锁和幂等状态不属于可随意降级的普通缓存。
- 提交后回调只解决执行顺序，关键消息/外部效果的持久性仍按 Backend §5 设计；不把“提交后入队”写成可靠送达保证。
- Spring 事务、异步、配置和资源生命周期直接遵循 Spring 标准，不在这里维护第二份注解规则。

## 7. 维护边界

模块职责或依赖方向变化时更新本文；业务决定更新 [业务规则](domain.md)；跨组件协议在 [contracts](../../contracts/README.md) 中维护；数据库演进见 [数据库说明](../src/main/resources/db/README.md)。
规范变化不自动证明实现合规。发现旧实现与规范不一致时，判断是否影响本次需求；相关链路必须明确处理，无关技术债不扩散本次修改范围。
