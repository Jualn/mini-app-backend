# 项目开发准则

> 版本：v0.2  
> 定位：单人开发阶段的第一层地基，先规范普通 CRUD、查询、简单事务和缓存操作。  
> 优先级：分层、边界和编码规则以本文件为准；仓库目标表结构和字段语义以 `DATABASE_DESIGN.md` 为准；可执行数据库版本以 `src/main/resources/db/migration/` 为准；`BACKEND_DESIGN.md` 作为整体设计背景。

## 1. 使用方式

本准则分为三级：

- **MUST**：新增代码立即遵守，修改旧链路时不得继续扩大违规范围。
- **MIGRATION**：现有技术债，修改相关模块时顺带迁移，不要求一次性重写。
- **FUTURE**：现在不启用，项目规模或协作人数增加后再评估。

当前不要求为了“统一形式”大规模重构。每次只处理本次需求涉及的链路。

### 1.1 当前范围

本版本重点回答以下问题：

1. 一个功能属于哪个模块，各层分别写什么。
2. Request、BO、Entity、VO 在哪里转换。
3. 一个模块如何拆分 Service，主应用和管理端如何复用业务能力。
4. MyBatis-Plus API、Mapper 注解和 XML 分别何时使用。
5. 查询应该读取多少字段，普通写操作、事务和缓存如何安排。
6. 新增代码最低需要做到哪些校验、异常处理和测试。

日志体系、复杂并发、超大事务拆分、统一重试、消息幂等和补偿机制暂不在本版本展开。开发时若遇到这些场景，应留下清晰注释并停止继续堆叠临时方案，再单独设计和升级本准则。

## 2. 普通功能开发路径

```text
Controller
    │ Request / Query：格式校验
    │ Converter：Request / Query → BO
    ▼
本模块 Service
    ├── 业务校验与事务
    ├── 本模块 Mapper → MySQL
    ├── 其他模块 Service
    ├── RedisService
    └── third 下的第三方能力
    │
    ▼
BO → Converter → VO → Controller → Result<T>
```

### 2.1 MUST

1. Controller 只能调用当前业务上下文的应用 Service，禁止调用 Mapper、Wrapper、Redis Client 和第三方 Client。
2. 跨模块只能调用对方 Service 接口，禁止引用对方 Mapper、Entity、Converter、Request、VO。
3. 一张业务表只能由所属模块写入。
4. 数据库是事实源；Redis 只能加速读取，不能替代数据库唯一约束和状态判断。
5. 由数据库状态派生的缓存失效和异步入队，原则上在事务提交后执行。
6. Controller 不捕获业务异常，不自行拼接失败响应，统一交给全局异常处理器。

### 2.2 Controller、校验与转换

- Controller 只负责解析 HTTP 请求、执行 Bean Validation、获取当前身份、调用 Service 和包装响应。
- 字段非空、长度、格式、分页范围等输入合法性放在 Request / Query。
- 权限、数据归属、业务状态、唯一性和“是否允许操作”等业务校验放在 Service。
- Converter 只做字段转换，不访问数据库、不调用 Service、不决定业务流程。
- Request / Query 转 BO、BO 转 VO，优先由对应模块 Converter 完成；字段很少且只使用一次时，可以在 Controller 中显式构造。
- MapStruct 未映射字段必须显式映射或 `ignore`，不长期保留未确认的映射警告。

### 2.3 异常与身份的最低规则

- 可预期的业务拒绝使用 `BusinessException` 和稳定的 `ResultCode`。
- 微信、对象存储、AI 等外部能力失败使用 `ExternalServiceException`，不把第三方原始错误直接返回前端。
- 未预期的内部错误交由 `GlobalExceptionHandler` 统一兜底，Controller 不编写通用 `try-catch`。
- 普通请求中的 `userId`、角色和状态不作为可信身份；用户身份从 Sa-Token / `UserContext` 获取。
- 管理端使用独立的管理员身份上下文；关键管理 BO 显式携带 `operatorId`、`reason` 等业务需要的信息。
- `UserContext` 仅用于同步请求线程，异步消息、定时任务和回调必须通过 Payload 或方法参数显式传递身份。

## 3. 模块与数据所有权

| 模块 | 主要数据所有权 |
|---|---|
| `user` | `user_profile`、`user_agreement` |
| `setting` | `user_setting` |
| `post` | `post` |
| `activity` | `activity`、`activity_enrollment` |
| `exam` | `exam_info`、`exam_subscription` |
| `comment` | `comment` |
| `interact` | `like_record`、`share_record`、浏览相关表 |
| `media` | `media_attachment`、`media_upload_record` |
| `timeline` | `timeline` |
| `audit` | `content_audit_log` |
| `search` | `search_doc` |
| `notify` | `notification`、`notify_plan` |
| `report` | `report` |
| `auth` / `admin` | 认证流程；不直接拥有或修改用户资料表 |

“拥有”表示该模块负责表的写入规则、状态迁移、缓存失效和数据正确性。其他模块若要修改该数据，必须调用所有者 Service。

### 3.1 模块公开边界

- 模块内部可以有多个 Service，但只有明确放在 Service 接口中的用例可以作为跨模块能力。
- 查询某模块的数据，由数据提供方返回标量或 BO；调用方不取得该模块 Entity 后自行解释状态。
- 跨模块写入必须调用状态所有者 Service，不因为“只改一个字段”而绕过边界。
- `common` 只放稳定、无业务归属的技术基础，例如统一结果、异常、上下文和通用工具；业务枚举、业务 BO 和业务规则留在所属模块。
- 暂不额外引入 Repository、Facade、Domain Service 等层级，现有边界无法表达真实问题时再增加。

## 4. 各层对象边界

| 对象 | 用途 | 允许范围 |
|---|---|---|
| Request / Query | HTTP 入参、Bean Validation | Controller |
| VO | HTTP 响应模型 | Controller |
| BO | Service 命令、查询条件、业务结果和聚合数据 | Controller 与 Service；必要时跨 Service |
| Entity | 数据表映射 | 所属模块 Service 与 Mapper |
| Payload | 异步消息载荷 | Producer 与 Consumer |
| 第三方 DTO | 微信、COS、AI 等外部协议 | `third` 内部及其调用边界 |

### 4.1 BO 跨模块规则

- BO 必须由数据提供方定义，例如用户模块提供 `UserSimpleBO`。
- 调用方不能拿其他模块 Entity 再自行解释业务状态。
- 一个只在单个方法中使用一次的临时组合，优先使用局部变量，不新建 BO。
- 当前暂不引入 Command、Query、ResultModel 等更多对象类型；BO 含义过多时再按实际需要拆分。

## 5. Service 边界

### 5.1 一个模块可以有多个 Service

Service 不是“模块文件夹的唯一入口”，也不是“一张表固定一个 Service”。一个 Service 应代表一个完整业务能力或一组共同维护的业务不变量。

合理示例：

```text
activity
├── ActivityService             活动主体的创建、编辑、发布、删除
├── ActivityQueryService        活动列表、详情、搜索等读模型（需要时再拆）
├── ActivityEnrollmentService   用户订阅状态及其状态迁移
└── ActivityAiService           文件解析与 AI 提取用例
```

### 5.2 需要拆分 Service 的信号

出现以下任一情况时，应讨论拆分：

1. 两组方法维护不同的状态和不变量，例如活动主体与活动订阅。
2. 两组方法具有不同的权限、事务边界或调用方。
3. 查询编排与写业务分别依赖完全不同的一组组件。
4. 类中大部分依赖只被少数方法使用，构造器持续膨胀。
5. 为了复用一个查询，被迫让其他模块依赖整个大 Service。
6. 已出现循环依赖、跨 Service 操作对方 Mapper 或大量条件分支。

行数和依赖数量只是提醒，不作为机械阈值。不要为了把类变短而创建只有一个转发方法的 Service。

### 5.3 Service 内部写权限

- 每个 Mapper 应有一个明确的主要写入 Service。
- 主要写入 Service 是目标数据的“状态所有者”，负责统一校验合法状态变化；它不等于模块内唯一 Service。
- 同模块 QueryService 可以读取本模块多个 Mapper，但不能拥有状态迁移规则。
- 同模块 Service A 需要 Service B 管理的数据发生变化时，调用 B，不直接写 B 的 Mapper。
- Callback、Listener、Handler 是入口适配器，应尽量只解析消息并调用 Service，不直接堆积 Mapper 和业务规则。

### 5.4 Service 接口

当前项目继续保留 Service 接口与实现类，但接口只暴露真实用例：

- 方法名表达业务语义，例如 `cancelPlanBySource`，而不是把 Mapper 方法原样暴露出去。
- 跨模块只依赖接口。
- 只供实现类内部使用的辅助方法不放进接口。
- 不为每个私有方法创建一个接口，也不创建纯粹转发的接口层。

## 6. 主应用与管理端用例

主应用用户和管理员可以操作同一个领域对象、最终写入同一张表，但不代表它们是同一个业务用例。只要权限、状态迁移、输入字段、返回字段、日志或通知语义不同，就应使用独立入口和独立方法。

### 6.1 代码归属

- `module/admin/auth` 只负责管理员认证、Token、身份和权限能力。
- 帖子管理属于 `post`，活动管理属于 `activity`，考试管理属于 `exam`；禁止把所有管理业务和各模块 Mapper 集中到 `module/admin`。
- 用户 Controller 与管理员 Controller 分开，推荐路径分别为 `/v1/{resources}` 和 `/v1/admin/{resources}`。

推荐结构按需求逐步增加，不提前创建空类：

```text
module/post
├── controller/PostController
├── controller/admin/AdminPostController
├── service/PostService
├── service/PostQueryService              # 查询复杂后再拆
├── service/PostAdminService              # 管理用例增加后再拆
├── dto/request/PostUpdateRequest
├── dto/admin/AdminPostReviewRequest
├── vo/PostDetailVO
└── vo/admin/AdminPostDetailVO
```

### 6.2 对象和接口

- 主应用 Request/VO 与管理端 Request/VO 必须分开。
- 普通用户 Request 不得包含 `status`、`pinned`、`auditStatus`、`rejectReason` 等管理字段。
- 管理端 VO 可以包含审核、举报、操作记录等管理信息，但不能复用给普通用户接口。
- 不使用 `isAdmin`、`adminMode` 等布尔参数在一个方法中切换两套流程。
- 方法名必须表达操作意图，例如 `deleteOwnPost`、`takeDownPost`、`approvePost`、`restorePost`，避免用一个模糊的 `removePost` 覆盖所有语义。

### 6.3 复用边界

只有业务语义完全相同时才复用同一个 Service 方法。判断时比较：

1. 权限规则是否相同。
2. 合法状态迁移是否相同。
3. 是否需要记录管理员、原因和时间。
4. 是否需要通知内容作者。
5. 输入和返回字段是否相同。
6. 失败时是否应返回相同业务结果。

例如“查询公开帖子摘要”可以复用；“用户删除自己的帖子”和“管理员下架违规帖子”必须是不同用例。

主应用和管理端入口不同，不代表底层状态变化必须复制两份。推荐关系如下：

```text
用户用例 Service ──┐
                  ├── 状态所有者 Service ── Mapper
管理用例 Service ──┘
```

入口 Service 分别负责身份、可操作范围、管理原因、批量参数和返回模型；状态所有者 Service 负责共同的数据库状态迁移。用例很少时可以先放在同一个 Service 接口中，但方法名必须保持业务语义，不能使用 `isAdmin` 切换流程。

### 6.4 Mapper 写入所有权

- 用户用例和管理用例可以读取同一模块的数据。
- 写入仍需集中在明确的状态所有者 Service，不能因为增加 `XxxAdminService` 就形成两套互不相干的状态规则。
- 当前阶段可由主 Service 同时提供语义明确的用户和管理方法；管理用例增多后，再抽 `XxxAdminService`，由它调用统一状态所有者。
- 管理查询筛选和返回字段明显不同后，可以独立为 `XxxAdminQueryService`；QueryService 只读，不拥有状态迁移。

### 6.5 权限与操作人

- `/v1/admin/**` 首先通过独立 admin loginType 验证管理身份。
- 具体的 `post:review`、`report:handle` 等业务权限仍由对应管理用例检查。
- 管理操作 BO 应显式携带 `operatorId`、`reason` 等审计信息，不依赖深层代码自行猜测当前身份。
- 即使调用者已经是管理员，Service 仍需验证合法状态迁移，并检查条件更新的影响行数。

### 6.6 查询差异

- 主应用查询通常只读取公开或当前用户可见的数据，可使用缓存和精简列表字段。
- 管理查询可以跨状态、查看软删除和审核信息，通常要求更高实时性，不默认复用主应用缓存。
- SQL 选择仍取决于查询复杂度，不是“管理端一律 XML”；复杂多条件管理列表通常属于 Q3，简单详情仍可使用 MyBatis-Plus API。

## 7. MyBatis-Plus 与 SQL 写法

持久化写法按以下顺序选择：

```text
能用 MyBatis-Plus API 清楚表达
    → 使用 BaseMapper / LambdaWrapper
否则，如果是极短、固定、单表原子 SQL
    → Mapper 接口注解
否则
    → Mapper 接口声明方法 + XML 实现
```

### 7.1 每一层可以写什么

| 层级 | 可以 | 禁止 |
|---|---|---|
| Controller | 调用 Service | Mapper、Wrapper、SQL、分页插件对象 |
| Service | 业务判断；调用本模块 Mapper；构建简单 LambdaWrapper | 拼接 SQL 字符串；访问其他模块 Mapper |
| Mapper 接口 | 继承 `BaseMapper`；声明自定义数据操作；极简单注解 SQL | 业务权限、业务状态编排、调用 Service |
| Mapper XML | 动态条件、联查、聚合、投影、批处理、全文检索、复杂分页 | 业务流程判断、跨模块写入 |

### 7.2 使用 MyBatis-Plus API

适用场景：

- `selectById`、`selectBatchIds`、`insert`、`updateById` 等基础 CRUD。
- 单表、条件较少且语义直观的 `selectOne`、`selectList`、`exists`、`selectCount`。
- 简单条件更新或软删除。
- 查询只在当前 Service 使用一次，没有复用价值。

示例：

```java
boolean exists = postMapper.exists(
        Wrappers.<Post>lambdaQuery()
                .eq(Post::getId, postId)
                .isNull(Post::getDeletedAt)
);
```

规则：

- 优先使用 LambdaWrapper，避免字符串字段名。
- Wrapper 只表达数据条件，业务权限判断仍在 Service。
- `.last("LIMIT " + value)` 中的值必须由服务端归一化，禁止直接拼接用户输入。
- 同一段 Wrapper 逻辑重复出现或开始出现大量动态条件时，迁移为命名清晰的 Mapper 方法和 XML。

### 7.3 使用 Mapper 注解 SQL

注解 SQL 只允许同时满足以下条件：

1. 单表。
2. SQL 固定，没有 `<if>`、`<foreach>` 等动态片段。
3. 没有 JOIN、子查询、复杂聚合、全文检索或结果映射。
4. SQL 足够短，阅读接口时能一次看懂。
5. 主要用于数据库原子操作或单字段查询。

适合示例：

```java
@Update("""
        UPDATE post
        SET comment_count = comment_count + 1
        WHERE id = #{postId} AND deleted_at IS NULL
        """)
int increaseCommentCount(Long postId);
```

不适合注解的情况：

- 在注解中使用 `<script>`、`<foreach>`。
- 多行动态查询。
- 返回复杂 BO。
- 为了避免 XML 而把长 SQL 拆成多个字符串。

这些情况直接写 XML。

### 7.4 使用 XML

以下场景默认使用 XML：

- 动态筛选条件较多。
- 游标分页排序由多个字段组成。
- JOIN、UNION、子查询、聚合、批量操作。
- 返回列表投影或跨表 BO。
- FULLTEXT、JSON、数据库函数等数据库特有能力。
- 条件状态迁移、CAS 更新、`FOR UPDATE` 等并发协议。
- SQL 需要通过执行计划单独优化。

Mapper 接口只声明方法；SQL、动态标签和结果映射放在同名 XML 中。

## 8. 查询程度与字段选择

在写查询前，先确定查询级别：

| 级别 | 目的 | 返回形式 | 字段规则 |
|---|---|---|---|
| Q0 | 是否存在、数量 | `boolean` / `long` | `EXISTS`、`COUNT` 或只查 `id` |
| Q1 | 权限、状态、摘要 | 标量或小型 BO | 只查判断所需字段 |
| Q2 | 完整详情或完整聚合 | Entity 或详情 BO | 可以查询完整业务字段 |
| Q3 | 列表、搜索、统计、联查 | 列表 BO / 投影 | 必须显式列字段 |

### 8.1 `SELECT *` 规则

新增代码遵守：

- Q0、Q1、Q3 禁止 `SELECT *`。
- XML 和注解中的列表查询禁止 `SELECT *`。
- 只有明确需要完整记录的 Q2 查询，才允许使用 `selectById` 或完整字段查询。
- 即使查询字段很多，XML 仍优先显式列出字段，便于审查字段变化和避免意外返回大字段。

### 8.2 部分字段查询

- 单字段直接返回 `Long`、`Integer`、`String` 等标量。
- 多字段摘要优先返回专用 BO/投影，不要让“只填了三个字段”的 Entity 在多层之间流转。
- 如果使用 `.select(...)` 得到部分字段 Entity，该对象只能用于只读，禁止随后传入 `updateById`。
- 权限判断只查询 `id/userId/status/role` 等必要字段，不加载正文、附件地址、审核原始响应等大字段。

### 8.3 列表和详情

- 列表字段由对应列表 BO/VO 的真实需要决定。
- 详情查询可以加载主体完整字段，但附件、互动状态、作者摘要等由所属模块批量补充。
- 禁止在循环中逐条调用 Mapper 或其他 Service，优先批量查询，避免 N+1。
- 分页默认使用游标；排序字段相同时必须增加 `id` 作为稳定次序。

## 9. 普通写操作

- 简单单表新增可以使用 `insert`，但唯一性必须由数据库唯一键兜底。
- “先查询再插入”不是并发控制；需要捕获重复键并转换为稳定业务结果。
- 更新时只接收和写入当前用例允许修改的字段，禁止把 Request 无差别复制到 Entity 后直接 `updateById`。
- 普通更新和删除必须检查影响行数，并区分“目标不存在”“状态不允许”和“已经处理”的业务结果。
- 业务数据默认按表设计执行软删除；只有明确允许物理删除的临时数据、关联数据才能直接 `delete`。
- 状态记录取消后再次启用，应执行合法状态迁移，不要重复 INSERT。
- 计数增减使用数据库原子 SQL，不采用“查询旧值 → Java 加减 → updateById”。
- 状态更新优先带旧状态条件，例如 `WHERE id = ? AND status = PENDING`，并检查影响行数。
- 涉及多表一致性时由 Service 开启事务；Mapper 不管理事务。

本版本只规定以上数据库基础保护，不统一设计锁、版本号、限流、复杂幂等或补偿流程。遇到库存、名额、余额、重复消费等正确性敏感场景时，不允许只凭普通 CRUD 推测“应该没问题”，应先标注风险并单独设计并发协议。

## 10. 跨 Service 与循环依赖

遇到循环依赖时，禁止用 `@Lazy`、ApplicationContext 手动取 Bean 或跨 Mapper 作为长期解决方案。按顺序判断：

1. **确定数据所有者**：谁拥有目标表和状态迁移，谁提供操作方法。
2. **缩小查询接口**：调用方只需要摘要时，由提供方增加小型查询方法，不依赖整个实现类。
3. **区分同步和异步**：当前事务必须成功的操作同步调用；提交后的搜索、通知、缓存等副作用使用事件或队列。
4. **抽出编排 Service**：一个用例确实跨多个模块时，可由独立编排 Service 调用各模块，但它不直接持有业务 Mapper。
5. **多态目标使用策略注册表**：帖子、活动、考试等同类操作由各模块实现统一接口，避免一个 Service 注入所有目标 Mapper 并写大型 `switch`。

Callback、Handler 中出现多个业务 Mapper，通常说明业务逻辑放错层：Callback 应调用目标 Service 应用审核结果，目标 Service 再修改自己的表。

## 11. 简单事务与缓存

- `@Transactional` 放在需要原子提交的公开 Service 命令方法上，不放在 Controller、Mapper、Converter 或私有辅助方法上。
- 同一类内部直接调用事务方法不能依赖 Spring 代理生效；事务入口应由外部 Bean 调用。
- 事务中完成必须共同成功或共同失败的数据库状态变化，不捕获异常后静默提交。
- 纯查询默认不开启事务；确实需要一致性快照时再单独说明。
- 外部 HTTP、文件上传和长耗时计算原则上不放在数据库事务内。
- 由数据库状态派生的缓存删除、搜索同步和普通异步消息，原则上在事务提交后执行。
- Cache Key 统一维护在 `RedisKeyConstant`。
- 一个缓存只能有一个明确的写入和失效责任方。
- 普通缓存采用 Cache Aside：查询未命中时读数据库并回填；写数据库成功后删除相关缓存。
- 缓存未命中、缓存删除失败不能改变数据库已成功写入的业务结果；当前阶段至少记录错误并设置合理 TTL，后续再设计统一重试。
- Redis 锁、幂等键等协调操作不属于普通缓存，不能套用“全部提交后执行”的规则。

队列、回调和定时任务的 Payload 不携带 Entity，只携带 ID、类型和必要快照。统一重试、消息幂等、死信和补偿机制留到后续专题规范；在此之前，新增关键消费者必须用注释写清重复执行风险和当前处理方式。

## 12. 可读性与风险注释

注释用于解释“为什么”和“当前边界”，不重复翻译代码。以下情况必须留下简短注释：

- 因兼容旧接口而暂时违反本准则，并写明后续迁移方向。
- 某段代码依赖事务提交顺序、数据库唯一键或条件更新结果。
- 暂未处理的并发、重复执行、重试或补偿风险。
- 使用数据库特有 SQL、第三方特殊约束或不直观的业务状态值。

推荐格式：

```java
// MIGRATION: 旧接口仍返回 Entity；调用方迁移完成后改为 PostDetailBO。
// RISK: 消息可能重复投递；当前仅通过状态条件更新避免重复变更。
```

注释不能代替正确实现。涉及资金、名额、权限越权或数据不可恢复风险时，不能只写 `TODO` 后继续上线，应先完成专项设计。

## 13. 数据库变更与最低验证

### 13.1 数据库变更

修改表、字段、索引或数据库枚举时，本次需求必须同步检查：

- `DATABASE_DESIGN.md` 中的数据定义。
- `src/main/resources/db/migration/` 中新增一个不可变、可执行的 Flyway 版本。
- Entity 字段和枚举映射。
- Mapper 接口、XML、投影 BO 和受影响的 `SELECT` 字段。
- 唯一键、默认值、非空约束和历史数据是否允许本次写法。

Flyway 规则：

- 已进入永久环境的版本禁止修改，修正时新增更高版本。
- 生产应用启动时不自动迁移；迁移由运维脚本在备份、人工确认和维护窗口内显式执行。
- CI 必须能在空 MySQL 8.0.40 上从 `V1` 重放到最新版本并通过 `validate`。
- 现有生产库只允许在核对结构后显式 baseline，禁止启用 `baselineOnMigrate`。
- 长时间历史数据回填与结构变更分开设计，不把不可控批处理塞进应用启动或普通 DDL。

### 13.2 最低验证

当前不设覆盖率门槛，但修改代码后至少完成与变更相匹配的编译或测试。以下场景应补测试：

- 业务状态转换和权限边界。
- 自定义 Mapper SQL、动态条件和字段投影。
- 唯一约束、重复请求以及条件更新的不同结果。
- 涉及名额、库存、计数或重复消费的写操作。
- 修改构造器、接口方法或对象字段后，同步更新受影响测试。

CI 自动化后续再统一建设；“CI 尚未完善”不代表本地可以不验证。

## 14. 新功能开发检查清单

开发前：

- [ ] 功能属于哪个模块？
- [ ] 涉及的表分别归哪个模块所有？
- [ ] 是本模块 Mapper 操作，还是应该调用其他模块 Service？
- [ ] Request、BO、Entity、VO 的转换分别在哪里？
- [ ] 查询属于 Q0、Q1、Q2 还是 Q3？
- [ ] MyBatis-Plus API、注解、XML 中哪一种最符合本准则？
- [ ] 是否存在并发写入、重复请求或重复消费？

提交前：

- [ ] Controller 未出现 Mapper、Wrapper、Entity。
- [ ] 未引用其他模块 Mapper、Entity、Converter、Request、VO。
- [ ] 格式校验在 Request，业务校验在 Service，Converter 没有业务逻辑。
- [ ] 列表、摘要、权限查询没有 `SELECT *`。
- [ ] Service 没有绕过其他 Service 修改其负责的数据。
- [ ] 数据库写入与普通缓存操作的先后顺序正确。
- [ ] 数据库结构变更已同步文档、DDL、Entity 和 SQL。
- [ ] 新增 Flyway 版本能从生产基线顺序执行，历史版本没有被修改。
- [ ] 修改涉及的关键业务规则有对应测试。

## 15. 当前迁移项

以下是已知 MIGRATION，不要求本次统一整改：

- Controller 返回 BO、Service 返回 VO 的旧接口。
- 注解和 XML 中现有的 `SELECT *`。
- `comment`、`interact`、`wx` 等位置的跨模块 Mapper。
- 审核 Callback 中直接处理目标模块业务状态。
- `notify`、`interact` 等大 Service 的职责拆分。
- 重复的事务提交后回调代码。
- `/v1` 路由单复数和 `/timeline` 前缀不一致。

## 16. 后续升级项

以下暂列 FUTURE：

- CI 全量测试和质量闸门。
- ArchUnit 自动检查模块依赖。
- 统一日志字段、敏感信息脱敏和链路追踪规范。
- 复杂并发控制、分布式锁和热点数据协议。
- 超大事务识别、拆分与跨步骤一致性策略。
- 消息幂等、统一重试、死信和补偿框架。
- BO 进一步拆成 Command、Query、ResultModel。
- 模块或服务拆分为独立进程。
