# 后端开发设计文档

> **项目**：企业级微信小程序后端服务
> **文档版本**：v1.0
> **更新日期**：2026-04-24
> **技术栈**：Spring Boot 3.x · MyBatis-Plus · Sa-Token · Redis · MapStruct · Lombok · Jackson · 腾讯云COS · 微信小程序API

---

## 目录

1. [项目结构总览](#项目结构总览)
2. [分层架构与职责边界](#分层架构与职责边界)
3. [命名规范](#命名规范)
4. [DTO / VO / BO 使用规范](#dto--vo--bo-使用规范)
5. [Controller 规范](#controller-规范)
6. [Service 规范](#service-规范)
7. [Mapper 规范](#mapper-规范)
8. [Converter 规范（MapStruct）](#converter-规范mapstruct)
9. [日志规范](#日志规范)
10. [注释与 Javadoc 规范](#注释与-javadoc-规范)
11. [统一响应与异常处理](#统一响应与异常处理)
12. [Sa-Token 鉴权规范](#sa-token-鉴权规范)
13. [Redis 使用规范](#redis-使用规范)
14. [第三方集成规范（COS / 微信）](#第三方集成规范cos--微信)
15. [依赖说明](#依赖说明)

---

## 项目结构总览

```
src/main/java/com/xxx/app/
│
├── module/                          # 业务模块（按领域拆分）
│   ├── user/                        # 用户模块
│   │   ├── controller/
│   │   ├── service/
│   │   │   └── impl/
│   │   ├── mapper/
│   │   ├── entity/
│   │   ├── dto/
│   │   ├── vo/
│   │   ├── bo/
│   │   ├── handler/
│   │   ├── payload/				 # 原 message，改名 payload 和 QueueMessage 区分
│   │   └── converter/
│   ├── post/                        # 帖子广场模块
│   │   ├── controller/
│   │   ├── service/
│   │   │   └── impl/
│   │   ├── mapper/
│   │   ├── entity/
│   │   ├── dto/
│   │   ├── vo/
│   │   └── converter/
│   ├── activity/                    # 活动模块
│   ├── exam/                        # 考试信息模块
│   ├── comment/                     # 通用评论模块
│   ├── interaction/                 # 互动行为模块（点赞/分享/浏览）
│   ├── notification/                # 通知与订阅模块
│   ├── notification/                # 通知与订阅模块
│   ├── wx/                			# 新增模块，处理微信事件业务逻辑
│   │   ├── controller/
│   │   ├── dto/
│   │   │   ├── SubscribeHandler.java   		 # 处理关注事件
│   │   │   └── WxEventMessage.java				# 解析后的事件对象
│   │   └── handler/
│   │       └── WxEventHandler.java      		# 接口，各事件 Handler 实现
│   └── audit/                      # 内容审核模块
│
├── third/                           # 第三方集成
│   ├── cos/                         # 腾讯云 COS
│   │   ├── config/
│   │   ├── client/
│   │   ├── service/
│   │   └── dto/
│   └── wx/                          # 微信相关
│       ├── controller/				# 接入验证 + 事件回调入口
│       ├── config/				    # appId/appSecret/token 配置
│       ├── client/					# 所有 HTTP 请求
│       ├── service/
│       │   ├── WxBindService.java
│       │   ├── WxEventService.java				# 事件解析分发
│       │   └── WxSubscribeService.java 		# 订阅消息
│       └── dto/					# 事件/消息统一解析对象,审核回调体
│
├── infrastructure/                  # 基础设施
│   ├── queue/                       # Redis 队列（通知推送队列）
│   │	├── annotation/
│	│	│   ├── QueueTopic.java          # 原 QueueTopic，类注解，标记 topic
│	│	│   └── QueueListener.java       # 原 Handler，方法注解，标记处理方法
│	│	│
│	│	├── contract/                    # 框架契约，接口和抽象定义
│	│   │	├── MessagePayload.java      # 原 QueueMessageBody，空接口，约束 payload 类型
│	│   │	├── QueueMessage.java        # 消息包装体，不改
│	│   │	├── QueueHandler.java        # Handler 接口，不改
│	│   │	└── QueueProducer.java       # Producer 接口，不改
│	│	│
│	│	├── dispatch/                    # 消息分发，原 core 里的注册和路由逻辑
│	│	│   ├── QueueRegistry.java       # 原 QueueConfig，扫描注册 Handler 和 Message
│	│	│   └── MessageDispatcher.java   # 原 QueueConsumer，根据 topic 路由到 Handler
│	│	│
│	│	└── redis/                       # Redis 具体实现
│   │		├── RedisQueueProducer.java  # 不改
│  	│		└── RedisQueueConsumer.java  # 不改
│   ├── cache/                       # Redis 缓存封装
│   │   └── RedisService.java
│   └── schedule/                    # 定时任务
│       └── NotifyScheduler.java
│
├── config/                          # 全局配置
│   ├── SaTokenConfig.java
│   ├── MybatisPlusConfig.java
│   ├── JacksonConfig.java
│   └── WebMvcConfig.java
│
└── common/                          # 公共组件
    ├── result/
    │   ├── R.java                   # 统一响应体
    │   └── PageResult.java          # 分页响应体
    ├── enums/
    │   ├── TargetTypeEnum.java      # 多态目标类型枚举
    │   ├── AuditStatusEnum.java
    │   └── NotifyTypeEnum.java
    ├── exception/
    │   ├── BizException.java        # 业务异常
    │   ├── GlobalExceptionHandler.java
    │   └── ErrorCode.java           # 错误码枚举
    ├── constant/
    │   └── RedisKeyConstant.java    # Redis Key 常量
    └── util/
        ├── AesUtil.java             # AES 加解密（手机号）
        └── PageUtils.java           # 分页工具
```

---

## 分层架构与职责边界

```
小程序请求
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│  Controller 层（接收请求、参数校验、权限声明、返回 VO）      │
└────────────────────────────┬────────────────────────────┘
                             │ 传入 DTO（携带校验注解）
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Service 层（业务逻辑、编排、事务控制）                     │
│  内部可使用 BO 承载中间计算结果                             │
└───────────────┬───────────────────────────┬─────────────┘
                │                           │
                ▼                           ▼
┌──────────────────────┐       ┌────────────────────────┐
│  Mapper 层            │       │  Third / Infrastructure │
│  （SQL操作，MyBatis+） │       │  （COS、微信、Redis）   │
└──────────┬───────────┘       └────────────────────────┘
           │ Entity（数据库映射对象）
           ▼
         MySQL
```

### 各层职责说明

| 层次 | 职责 | 禁止事项 |
|------|------|---------|
| **Controller** | 接收请求、`@Valid` 校验、调用 Service、将返回值包装成 `R<VO>` | 禁止写业务逻辑；禁止直接操作 Mapper；禁止返回 Entity |
| **Service** | 核心业务编排、事务控制、调用 Mapper 及第三方服务 | 禁止直接操作 HttpServletRequest；禁止返回 Entity 给 Controller |
| **Mapper** | 数据库 CRUD，MyBatisPlus 自带方法 + 自定义 XML | 禁止写业务判断；禁止写 System.out |
| **Entity** | 数据库表字段映射，1 Entity = 1 Table | 禁止添加业务方法；禁止混入 VO/DTO 注解 |
| **DTO** | 接收前端请求参数（携带 `@Valid` 注解） | 禁止包含 Entity 引用 |
| **VO** | 返回给前端的视图对象 | 禁止包含敏感字段（如 openid、phone 明文） |
| **BO** | Service 内部中间计算对象，不跨层传递 | 禁止在 Controller 层出现 |
| **Converter** | DTO ↔ Entity ↔ VO 转换（MapStruct） | 禁止写业务逻辑 |

---

## 命名规范

### 包命名

```
com.xxx.app.module.{模块名}.{层次名}
```

| 层次 | 包名 | 示例 |
|------|------|------|
| 控制层 | `controller` | `com.xxx.app.module.post.controller` |
| 服务层 | `service` / `service.impl` | `com.xxx.app.module.post.service.impl` |
| 数据层 | `mapper` | `com.xxx.app.module.post.mapper` |
| 实体 | `entity` | `com.xxx.app.module.post.entity` |
| 数据传输 | `dto` | `com.xxx.app.module.post.dto` |
| 视图对象 | `vo` | `com.xxx.app.module.post.vo` |
| 对象转换 | `converter` | `com.xxx.app.module.post.converter` |

### 类命名

| 类型 | 命名规则 | 示例 |
|------|---------|------|
| Controller | `{模块}Controller` | `PostController` |
| Service 接口 | `{模块}Service` | `PostService` |
| Service 实现 | `{模块}ServiceImpl` | `PostServiceImpl` |
| Mapper | `{模块}Mapper` | `PostMapper` |
| Entity | 与表名对应，大驼峰 | `Post`（对应 `post` 表） |
| 请求 DTO | `{模块}{操作}Request` | `PostCreateRequest`、`PostUpdateRequest` |
| 分页查询 DTO | `{模块}PageQuery` | `PostPageQuery` |
| 响应 VO | `{模块}VO` / `{模块}DetailVO` | `PostVO`、`PostDetailVO` |
| 内部 BO | `{模块}{描述}BO` | `NotifyBuildBO` |
| Converter | `{模块}Converter` | `PostConverter` |
| 枚举 | `{描述}Enum` | `AuditStatusEnum` |
| 常量 | `{描述}Constant` | `RedisKeyConstant` |

### 方法命名

| 场景 | 命名前缀 | 示例 |
|------|---------|------|
| 单个查询 | `get` | `getPostById` |
| 列表查询 | `list` / `page` | `listPostByUser`、`pagePost` |
| 创建 | `create` | `createPost` |
| 更新 | `update` | `updatePost` |
| 删除（软删除） | `remove` | `removePost` |
| 检查/判断 | `check` / `is` | `checkPostOwner`、`isLiked` |
| 构建 | `build` | `buildNotifyMessage` |

###  变量命名

- 普通变量：小驼峰，`postId`、`userId`
- 常量：全大写下划线，`MAX_COMMENT_DEPTH`
- 布尔：`is` 前缀，`isLiked`、`isPinned`
- 集合：复数形式，`postList`、`userIds`

### 数据库字段与 Java 字段映射

统一通过 MyBatisPlus 的驼峰映射处理，无需手动写 `@Column`：

```yaml
# application.yml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
```

---

## DTO / VO / BO 使用规范

###  DTO（Data Transfer Object）

**DTO/request**，仅用于**接收前端请求参数**，必须携带 Bean Validation 注解。

**命名**：`{模块}{操作}Request`，放在 `module/{模块}/dto/request` 下。

```java
/**
 * 创建帖子请求参数
 */
@Data
public class PostCreateRequest {

    @NotBlank(message = "帖子内容不能为空")
    @Size(max = 5000, message = "内容不能超过5000字")
    private String content;

    @Size(max = 128, message = "标题不能超过128字")
    private String title;

    /** 附件 URL 列表（已由前端直传 COS，后端只接收 URL） */
    @Size(max = 9, message = "最多上传9张图片")
    private List<String> imageUrls;
}
```

**分页查询 DTO** 命名：`{模块}PageQuery`

```java
@Data
public class PostPageQuery {

    /** 游标分页：上一页最后一条 ID，首次传 null */
    private Long lastId;

    @Max(value = 50, message = "每页最多50条")
    @Min(value = 1)
    private Integer pageSize = 20;

    /** 状态筛选，管理员用 */
    private Integer status;
}
```

**DTO/inner**

 模块内部对外暴露（Service 间用）

```java
// user/dto/inner/UserSimpleDTO.java（不叫 VO，叫 DTO，用于service直接流通）
@Data
public class UserSimpleDTO {
    private Long id;
    private String nickname;
    private String avatarUrl;
}
```





### VO（View Object）—— 响应出参

VO 仅用于**向前端返回数据**，不包含敏感字段，字段名以前端需要为准。

**命名**：`{模块}VO`（列表）/ `{模块}DetailVO`（详情），放在 `module/{模块}/vo/` 下。

```java
/**
 * 帖子列表项视图对象
 */
@Data
public class PostVO {

    private Long id;
    private String title;
    private String content;         // 列表页可截断
    private Integer likeCount;
    private Integer commentCount;
    private Integer viewCount;
    private Boolean liked;          // 当前用户是否已点赞
    private String publishedAt;     // 格式化后的时间字符串
    private UserSimpleVO author;    // 嵌套作者信息（精简）
    private List<String> imageUrls; // 附件图片列表
}
```

**原则**：
- VO 不返回 `openid`、`phone`（即使加密过）、`deletedAt` 等内部字段
- 时间字段在 VO 层格式化为字符串（`yyyy-MM-dd HH:mm`），不返回时间戳
- 涉及计数类字段（likeCount 等），从 Entity 冗余字段读取，不在 VO 层再查库

### BO（Business Object）—— Service 内部流转

BO 仅在 **Service 层内部**使用，用于承载跨方法传递的中间状态，不上升到 Controller 层，不下降到 Mapper 层。

**命名**：`{描述}BO`，放在对应 `module/{模块}/` 下（或直接作为 Service 内部静态类）。

**使用场景举例**：构建通知消息时，需要聚合帖子信息 + 用户信息 + 点赞信息，可用 BO 承载：

```java
// Service 内部使用，不对外暴露
@Data
@Builder
private static class NotifyBuildBO {
    private Long recipientId;
    private Long triggerId;
    private String contentSummary;
    private Integer notifyType;
}
```

**实际判断标准**：若一个对象只在某个 Service 方法内部用一次，直接用局部变量即可，不必专门建 BO 类。BO 适合在同一 Service 类多个私有方法之间传递中间结果时使用。

---

## Controller 规范

###  基本结构

```java
/**
 * 帖子广场接口
 *
 * @author xxx
 * @since 2026-04-24
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /**
     * 发布帖子
     */
    @PostMapping
    @SaCheckLogin
    public R<Long> createPost(@RequestBody @Valid PostCreateRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long postId = postService.createPost(userId, request);
        return R.ok(postId);
    }

    /**
     * 帖子广场分页列表（游标分页）
     */
    @GetMapping
    public R<PageResult<PostVO>> pagePost(@Valid PostPageQuery query) {
        return R.ok(postService.pagePost(query));
    }

    /**
     * 帖子详情
     */
    @GetMapping("/{id}")
    public R<PostDetailVO> getPost(@PathVariable Long id) {
        return R.ok(postService.getPostDetail(id));
    }

    /**
     * 删除帖子（仅本人或管理员）
     */
    @DeleteMapping("/{id}")
    @SaCheckLogin
    public R<Void> removePost(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        postService.removePost(userId, id);
        return R.ok();
    }
}
```

###  路由命名规范

| 操作 | HTTP 方法 | URL 示例 |
|------|---------|----------|
| 列表/分页 | `GET` | `/api/v1/posts` |
| 详情 | `GET` | `/api/v1/posts/{id}` |
| 创建 | `POST` | `/api/v1/posts` |
| 全量更新 | `PUT` | `/api/v1/posts/{id}` |
| 部分更新 | `PATCH` | `/api/v1/posts/{id}` |
| 删除 | `DELETE` | `/api/v1/posts/{id}` |
| 嵌套资源 | `POST` | `/api/v1/posts/{id}/comments` |
| 动作类操作 | `POST` | `/api/v1/posts/{id}/like` |

**统一前缀**：所有接口以 `/api/v1/` 开头，版本号便于后续兼容升级。

### Controller 层严禁事项

- ❌ 禁止在 Controller 写 `if/else` 业务判断
- ❌ 禁止直接注入 Mapper，Controller 只能依赖 Service
- ❌ 禁止返回 `Entity` 或 `Map`，必须返回 VO
- ❌ 禁止在 Controller 层写日志（除非特殊审计需求），日志在 Service 写
- ❌ 禁止在 Controller 层 `try-catch`，统一由全局异常处理器处理

---

## Service 规范

### 接口与实现分离

必须定义接口，实现类放在 `impl/` 子包下：

```java
// PostService.java
public interface PostService {
    Long createPost(Long userId, PostCreateRequest request);
    PageResult<PostVO> pagePost(PostPageQuery query);
    PostDetailVO getPostDetail(Long id);
    void removePost(Long userId, Long postId);
}
```

```java
// PostServiceImpl.java
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostMapper postMapper;
    private final MediaAttachmentService mediaAttachmentService;
    private final PostConverter postConverter;
    // ...

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPost(Long userId, PostCreateRequest request) {
        // 1. 构建实体
        Post post = postConverter.toEntity(request);
        post.setUserId(userId);
        post.setStatus(PostStatusEnum.AUDITING.getCode());

        // 2. 保存帖子
        postMapper.insert(post);

        // 3. 保存附件（异步审核触发放在这之后）
        if (CollectionUtils.isNotEmpty(request.getImageUrls())) {
            mediaAttachmentService.saveImages(TargetTypeEnum.POST, post.getId(), request.getImageUrls());
        }

        log.info("[帖子] 用户 {} 创建帖子 {}", userId, post.getId());
        return post.getId();
    }
}
```

###  Service 层规范要点

**事务控制**：
- 涉及多表写操作必须加 `@Transactional(rollbackFor = Exception.class)`
- 只读查询无需加 `@Transactional`
- 事务方法不可被同类内其他方法直接调用（Spring AOP 代理失效问题），如有需要通过接口自注入调用

**参数校验**：
- DTO 的格式校验由 `@Valid` 在 Controller 完成
- 业务规则校验（如"用户是否有权限删除该帖"）在 Service 完成，违规时抛 `BizException`

**分页查询**：统一使用游标分页，禁止大偏移量分页：

```java
// 游标分页示例（MyBatisPlus + XML）
public PageResult<PostVO> pagePost(PostPageQuery query) {
    LambdaQueryWrapper<Post> wrapper = Wrappers.lambdaQuery(Post.class)
        .eq(Post::getStatus, PostStatusEnum.PUBLISHED.getCode())
        .lt(query.getLastId() != null, Post::getId, query.getLastId())
        .isNull(Post::getDeletedAt)
        .orderByDesc(Post::getPublishedAt);

    List<Post> posts = postMapper.selectList(
        wrapper.last("LIMIT " + query.getPageSize())
    );

    List<PostVO> voList = posts.stream()
        .map(postConverter::toVO)
        .collect(Collectors.toList());

    return PageResult.of(voList, posts.size() == query.getPageSize());
}
```

---

### Service层跨模块调用规范

为防止项目后期代码耦合混乱，跨模块调用必须遵循“接口暴露、语义传递、边界清晰”的原则。

**核心原则**

1. **✅ Service 依赖 Service**：A 模块需要 B 模块数据时，必须调用 `BService`，严禁直接注入 `BMapper`，除非是不包含业务逻辑的，简单的count与exist，其他包含业务的查询就新增BQueryService
   - *理由*：直接操作 Mapper 会导致 A 模块感知 B 模块的表结构细节。一旦 B 表结构变化，A 也会被迫修改。
2. **✅ 传基础类型或 Param**：Service 方法入参优先使用 `Long id`、`String` 等基础类型。若参数过多（>5个），定义一个属于**被调用方模块**的 `Param` 对象。
3. **❌ Reques/VO 不跨境**：Request（前端入参）和 VO（前端出参）只活在 Controller 和本模块 Service 内。跨模块传递时，Request和 VO 严禁出现在方法签名中。

**使用示例**

**场景一：查询外部模块数据（Service A → Service B）**

A 模块获取帖子详情时需要展示作者（用户模块）信息。

Java

```java
// PostServiceImpl.java ✅ 正确做法
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {
    private final PostMapper postMapper;
    private final UserService userService;          // ✅ 注入外部 Service 接口

    @Override
    public PostDetailVO getPostDetail(Long postId) {
        Post post = postMapper.selectById(postId);
        
        // ✅ 传基础类型（Long），接收 Entity 或模块定义的简单 Info 对象
        UserProfileDTO author = userService.getById(post.getUserId()); 
        
        PostDetailVO vo = postConverter.toDetailVO(post);
        vo.setAuthorName(author.getNickname());
        return vo;
    }
}
```

**场景二：跨模块业务操作（入参规范）**

严禁 Service 方法接受另一个模块的 Request，因为 Request是 HTTP 层的产物，含有校验注解和前端语义。

Java

```java
// ❌ 错误：Service 方法接受外部模块 DTO
public void createComment(CommentCreateRequest request) { ... }

// ✅ 正确：如果参数多，定义一个属于“被调用方（comment模块）”的内部参数对象
public Long createComment(CommentCreateDTO dto); 

// CommentCreateDTO 放在 comment 模块下，无校验注解，仅供 Service 间调用
@Data
@Builder
public class CommentCreateDTO {
    private Long userId;
    private Long targetId;
    private String content;
}
```

**场景三：返回值规范**

Service 之间传递数据应返回 **Entity** 或在 `common` 中定义的简单模型，**绝对不返回 VO**。

Java

```java
// UserService.java 供其他模块调用的出口
public interface UserService {
    // ✅ 返回 Entity
    UserProfile getById(Long userId);
    
    // ✅ 返回简单聚合对象（非 VO）
    UserSimpleInfo getSimpleInfo(Long userId); 
    
    // ❌ 错误：严禁返回 VO 给其他 Service，VO 仅用于 Controller 返回前端
    // UserVO getById(Long userId);
}
```

**跨模块规则总结表**

| **传递方向**                 | **可以传什么**                     | **禁止传什么**        |
| ---------------------------- | ---------------------------------- | --------------------- |
| **Controller A → Service A** | A 自己的 Request                   | 其他模块的 DTO        |
| **Service A → Service B**    | 基础类型、B 模块的 Param，DTO 对象 | 任何 Request、任何 VO |
| **Service B → Service A**    | Entity、简单 Info 对象             | **VO** (VO 只给前端)  |
| **Service A → Mapper**       | Entity、基础类型                   | DTO、VO               |
| **Service A → Controller A** | 经 Converter 转换后的 VO           | Entity 直接暴露       |

------

### 异步结果回调模式（跨模块解耦）

当一个模块（如 audit）处理完异步任务后需要通知多个业务模块（post/activity/exam）更新状态时，禁止在 audit 模块内直接调用各业务模块的 Mapper 或堆砌多个 Service 依赖。应使用**回调接口**解耦，各业务模块自行实现回调逻辑。

**接口定义（属于 audit 模块）**：

```java
// module/audit/callback/AuditResultCallback.java
public interface AuditResultCallback {
    /** 审核通过 */
    void onPass(Long targetId);
    /** 审核拒绝 */
    void onReject(Long targetId, String reason);
}
```

**各业务模块实现回调**：

```java
// module/post/callback/PostAuditCallback.java
@Component
public class PostAuditCallback implements AuditResultCallback {
    private final PostMapper postMapper;
    private final NotificationService notificationService;

    @Override
    public void onPass(Long targetId) {
        // ✅ post 模块只操作自己的 Mapper
        postMapper.updateStatus(targetId, PostStatusEnum.PUBLISHED.getValue());
        notificationService.sendAuditPass(targetId, TargetTypeEnum.POST.getValue());
    }

    @Override
    public void onReject(Long targetId, String reason) {
        postMapper.updateStatus(targetId, PostStatusEnum.REJECTED.getValue());
        notificationService.sendAuditReject(targetId, TargetTypeEnum.POST.getValue(), reason);
    }
}
```

**audit 模块通过注册表路由回调，不感知具体业务**：

```java
// module/audit/callback/AuditCallbackRegistry.java
@Component
@RequiredArgsConstructor
public class AuditCallbackRegistry {

    // Spring 自动注入所有 AuditResultCallback 实现，key 为 Bean 名
    private final Map<String, AuditResultCallback> callbacks;

    public AuditResultCallback get(Integer targetType) {
        return switch (targetType) {
            case 1 -> callbacks.get("postAuditCallback");
            case 2 -> callbacks.get("activityAuditCallback");
            case 3 -> callbacks.get("examAuditCallback");
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知内容类型");
        };
    }
}
```

**audit 处理流程（Handler 只调一个方法）**：

```java
// Queue Handler：薄如纸，只转发
@Component
@QueueTopic("audit.image")
public class AuditImageHandler implements QueueHandler<AuditImagePayload> {
    private final AuditService auditService;

    @Override
    public void handle(QueueMessage<AuditImagePayload> message) {
        auditService.processMediaAudit(message.getPayload());
    }
}

// AuditService：完整审核流程 + 通过回调通知业务层
@Transactional
public void processMediaAudit(AuditImagePayload payload) {
    // 1. 调微信审核（audit 自己的事）
    boolean pass = wxMsgSecService.checkMedia(payload.getMediaUrl());

    // 2. 写审核日志（audit 自己的事）
    auditLogMapper.insert(buildLog(payload, pass));

    // 3. 通过回调通知业务层，audit 不直接操作业务表
    AuditResultCallback callback = callbackRegistry.get(payload.getTargetType());
    if (pass) {
        callback.onPass(payload.getTargetId());
    } else {
        callback.onReject(payload.getTargetId(), "内容违规");
    }
}
```

**模块依赖方向**：

```
post/activity/exam  →  实现 AuditResultCallback（依赖 audit 定义的接口）
audit               →  持有 AuditCallbackRegistry，不直接依赖任何业务模块
```

audit 不感知 post/activity/exam，新增业务类型只需新增一个 Callback 实现类，不改 audit 模块。

------

## Mapper 规范

### 基本结构

所有 Mapper 继承 `BaseMapper<Entity>`，获得 MyBatisPlus 的基础 CRUD 能力：

```java
/**
 * 帖子数据访问层
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 查询用户帖子列表（游标分页）
     *
     * @param userId   用户ID
     * @param lastId   上一页最后一条ID，首次为 null
     * @param pageSize 每页条数
     */
    List<Post> selectByUserId(@Param("userId") Long userId,
                               @Param("lastId") Long lastId,
                               @Param("pageSize") int pageSize);
}
```

### Entity 规范

Entity 需继承 MyBatisPlus 的 Model 基类，使用 Lombok 简化代码：

```java
/**
 * 帖子实体
 * 对应表：post
 */
@Data
@TableName("post")
public class Post {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String content;

    /** 状态：0-草稿 1-审核中 2-已发布 3-审核拒绝 4-已删除 */
    private Integer status;

    private Integer auditStatus;
    private String rejectReason;
    private Integer isPinned;
    private Integer isFeatured;
    private Integer commentCount;
    private Integer likeCount;
    private Integer shareCount;
    private Integer viewCount;

    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 软删除字段，配合 @TableLogic */
    @TableLogic
    private LocalDateTime deletedAt;
}
```

**软删除配置**：在 `MybatisPlusConfig` 中配置 `@TableLogic` 使所有查询自动附加 `AND deleted_at IS NULL`，无需手动写。

###  自定义 XML SQL

复杂 SQL 放在 `resources/mapper/{模块}/{Mapper名}.xml`：

```xml
<!-- PostMapper.xml -->
<mapper namespace="com.xxx.app.module.post.mapper.PostMapper">

    <select id="selectByUserId" resultType="com.xxx.app.module.post.entity.Post">
        SELECT * FROM post
        WHERE user_id = #{userId}
          AND deleted_at IS NULL
          <if test="lastId != null">
            AND id &lt; #{lastId}
          </if>
        ORDER BY id DESC
        LIMIT #{pageSize}
    </select>

</mapper>
```

---

### 跨模块 Mapper 使用规范

**核心原则：跨模块不直接调用对方的 Mapper 做写操作，读操作视情况而定。**

| 场景                      | 做法                                       |
| ------------------------- | ------------------------------------------ |
| A 需要 B 的简单字段       | 调 B 的 Service，不调 B 的 Mapper          |
| A 需要修改 B 的数据       | 必须调 B 的 Service，严禁跨 Mapper 写      |
| A 需要 A+B 联查，性能敏感 | A 的 Mapper 写联查 SQL，结果映射到 A 的 BO |

**联查场景示例**：帖子列表需要同时展示用户昵称，单独调 UserService 会产生 N+1 查询，此时 A 的 Mapper 直接写联查 SQL，结果映射到 A 自己的 BO：

```java
// PostMapper.java
List<PostListBO> selectWithAuthor(@Param("lastId") Long lastId,
                                  @Param("pageSize") int pageSize);
```

```xml
<!-- PostMapper.xml：联查 user 表，结果映射到 PostListBO（属于 post 模块） -->
<select id="selectWithAuthor" resultType="com.xxx.app.module.post.bo.PostListBO">
    SELECT p.id, p.title, p.like_count, p.published_at,
           u.nickname AS authorNickname, u.avatar_url AS authorAvatar
    FROM post p
    LEFT JOIN user_profile u ON p.user_id = u.id
    WHERE p.deleted_at IS NULL
      <if test="lastId != null">AND p.id &lt; #{lastId}</if>
    ORDER BY p.id DESC
    LIMIT #{pageSize}
</select>
```

`PostListBO` 属于 post 模块，user 的 `nickname` 只是其中一个字段，不需要构建 `UserBO`，也不需要引入 `UserMapper`。

**严禁的跨模块 Mapper 操作**：

```java
// ❌ post 模块直接跨模块写操作
userMapper.updateStatus(userId, status);

// ❌ post 模块用 B 的 Mapper 构建 B 的业务对象
UserProfile user = userMapper.selectById(userId);
UserBO userBO = new UserBO(user);   // 这是 user 模块的事
```

---

## Converter 规范（MapStruct）

### 使用场景

Converter 负责**对象之间的属性拷贝转换**，避免手动 `setXxx`。所有 DTO→Entity、Entity→VO 的转换必须通过 Converter，禁止在 Service 中直接 `new` 一个对象再逐个 set。

### 基本结构

```java
/**
 * 帖子对象转换器
 */
@Mapper(componentModel = "spring")
public interface PostConverter {

    /**
     * 创建请求 → Entity
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)       // 状态由业务逻辑赋值
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    Post toEntity(PostCreateRequest request);

    /**
     * Entity → 列表 VO
     * 注意：like/author 等需二次查询的字段，在 Service 中手动 set，Converter 不做
     */
    @Mapping(target = "publishedAt", dateFormat = "yyyy-MM-dd HH:mm")
    @Mapping(target = "liked", ignore = true)        // Service 层填充
    @Mapping(target = "author", ignore = true)       // Service 层填充
    @Mapping(target = "imageUrls", ignore = true)    // Service 层填充
    PostVO toVO(Post post);

    /**
     * 批量转换
     */
    List<PostVO> toVOList(List<Post> posts);
}
```

### 使用规范

- Converter 只做**纯字段映射**，不查数据库，不注入其他 Service
- 需要二次查询填充的字段（如作者信息、是否点赞）标注 `ignore = true`，在 Service 层手动 set
- 复杂转换逻辑写成 `default` 方法在 Converter 接口内，保持 Service 干净
- 与 Lombok 配合时，Entity 上用 `@Data`，MapStruct 可正常识别 getter/setter

---

### Converter 归属规范

**Converter 属于数据提供方（被调用方），不属于调用方。**

原则：谁的对象谁来转，调用方只接收 BO 或 Entity，不持有被调用方的 Converter。

```
PostConverter  → 属于 post 模块，负责 Post 相关所有转换
UserConverter  → 属于 user 模块，负责 UserProfile 相关所有转换
```

**调用链路中的转换职责**：

```
Controller（调用方）
  ├── 持有本模块 Converter，将 Request → BO，将 BO/Entity → VO
  └── 不持有其他模块的 Converter

Service（被调用方）
  ├── 内部使用本模块 Converter 完成转换后返回 BO 或 Entity
  └── 调用方拿到结果直接用，无需再转
```

```java
// ✅ 正确：PostService 返回 BO，Controller 用 PostConverter 转 VO
public class PostController {
    private final PostService postService;
    private final PostConverter postConverter;   // 本模块 Converter

    public R<PostDetailVO> getPost(Long id) {
        PostDetailBO bo = postService.getPostDetail(id);
        return R.ok(postConverter.toDetailVO(bo));
    }
}

// ✅ 正确：PostService 内部用自己的 Converter，返回 BO 给上层
public class PostServiceImpl implements PostService {
    private final PostConverter postConverter;   // 本模块 Converter

    public PostDetailBO getPostDetail(Long id) {
        Post post = postMapper.selectById(id);
        return postConverter.toDetailBO(post);   // 转换在本模块内完成
    }
}

// ❌ 错误：Controller 持有被调用方的 Converter 自己转
public class PostController {
    private final UserConverter userConverter;   // 不该出现在 post 模块
}
```

**跨模块时调用方只接收结果，不参与转换**：

```java
// PostServiceImpl 需要用户信息
public PostDetailBO getPostDetail(Long id) {
    Post post = postMapper.selectById(id);
    // ✅ UserService 内部已经转好，返回的就是可直接用的对象
    UserSimpleDTO author = userService.getSimpleInfo(post.getUserId());

    PostDetailBO bo = postConverter.toDetailBO(post);
    bo.setAuthorName(author.getNickname());   // 直接用，不需要再转
    return bo;
}
```

---

## 日志规范

### 基本使用

统一使用 Slf4j，通过 Lombok `@Slf4j` 注解引入，禁止使用 `System.out.println`：

```java
@Slf4j
@Service
public class PostServiceImpl implements PostService {

    public void createPost(...) {
        log.info("[帖子] 用户 {} 创建帖子，标题：{}", userId, request.getTitle());
        // ...
        log.info("[帖子] 帖子 {} 创建成功", post.getId());
    }
}
```

### 日志级别规范

| 级别 | 使用场景 | 示例 |
|------|---------|------|
| `ERROR` | 系统异常、第三方调用失败、需立即处理的错误 | 微信 API 调用失败、Redis 连接异常 |
| `WARN` | 业务异常（用户侧错误）、数据不一致但可恢复 | 用户未授权订阅消息、帖子已删除 |
| `INFO` | 关键业务操作记录 | 用户登录、帖子创建/删除、通知推送 |
| `DEBUG` | 开发调试，生产禁止 | SQL 参数、中间变量值 |

### 日志格式规范

**格式**：`[模块标签] 操作描述，关键 ID 信息`

```java
// ✅ 好的日志
log.info("[审核] 帖子 {} 微信安全检测通过，自动发布", postId);
log.warn("[通知] 用户 {} 未授权订阅消息，跳过微信推送", userId);
log.error("[COS] 获取临时密钥失败，错误：{}", e.getMessage(), e);

// ❌ 不好的日志
log.info("success");                     // 无上下文
log.info("post: " + post.toString());    // 字符串拼接，性能差，应用占位符
log.error(e.getMessage());               // 丢失堆栈信息
```

### 9.4 生产配置

```yaml
logging:
  level:
    root: INFO
    com.xxx.app: INFO
    com.xxx.app.module.mapper: WARN  # MyBatisPlus SQL 日志仅开发环境开启
  file:
    name: /var/log/app/app.log
  logback:
    rollingpolicy:
      max-file-size: 50MB
      max-history: 30
```

---

## 注释与 Javadoc 规范

### 必须写注释的位置

| 位置 | 要求 |
|------|------|
| 所有 Controller 方法 | 写简短 Javadoc，说明接口用途 |
| Service 接口方法 | 写完整 Javadoc（`@param`、`@return`、`@throws`） |
| Entity 字段 | 复杂枚举字段必须注释枚举含义 |
| Enum 枚举值 | 每个枚举值写中文说明 |
| 复杂业务逻辑 | 在关键分支前写行注释，说明"为什么" |
| 常量类 | 每个常量说明用途 |

### Javadoc 示例

```java
/**
 * 删除帖子（软删除）
 * <p>
 * 普通用户只能删除自己的帖子；管理员可删除任意帖子。
 * 删除后关联的附件记录不删除，仅标记帖子为软删除状态。
 * </p>
 *
 * @param operatorId 操作者用户ID（从 Sa-Token 获取）
 * @param postId     帖子ID
 * @throws BizException 帖子不存在或无权限时抛出 {@link ErrorCode#POST_NOT_FOUND}
 */
void removePost(Long operatorId, Long postId);
```

### 行注释规范

```java
// ✅ 说明"为什么"而不是"是什么"
// 游标分页：使用 id < lastId 避免大 OFFSET 性能问题
.lt(query.getLastId() != null, Post::getId, query.getLastId())

// ❌ 废话注释（代码本身已经说明了）
// 查询帖子
Post post = postMapper.selectById(postId);
```

### TODO / FIXME

```java
// TODO: 后续接入微信图片审核 mediaCheckAsync，当前仅做文字审核
// FIXME: audience_scope 位运算当 scope=0 时会查出全部，待确认产品逻辑
```

---

## 统一响应与异常处理

### 统一响应体 `Result<T>`

```java
/**
 * 统一响应体
 */
@Data
public class Result<T> {
    private Integer code;
    private String  message;
    private T       data;
    private Long    timestamp;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code      = 200;
        r.message   = "success";
        r.data      = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        Result<T> r = new Result<>();
        r.code      = resultCode.getCode();
        r.message   = resultCode.getMessage();
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        Result<T> r = new Result<>();
        r.code      = resultCode.getCode();
        r.message   = message;           // 用自定义 message 覆盖
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> r = new Result<>();
        r.code      = code;
        r.message   = message;
        r.timestamp = System.currentTimeMillis();
        return r;
    }
}
```

### 分页响应体 `PageResult<T>`

```java
@Data
@AllArgsConstructor(staticName = "of")
public class PageResult<T> {

    private List<T> list;
    /** 是否还有下一页（游标分页判断：返回数量 == pageSize） */
    private Boolean hasMore;
    /** 游标：下次请求传入此值作为 lastId（取列表最后一条 id） */
    private Long nextCursor;

    public static <T> PageResult<T> of(List<T> list, boolean hasMore) {
        // nextCursor 由调用方从 list 最后一条取 id
        return new PageResult<>(list, hasMore, null);
    }
}
```

### 错误码规范

```java
/**
 * 业务错误码枚举
 * 规则：通用 | 1xx-系统 | 2xx-用户 | 3xx-帖子 | 4xx-活动 | 5xx-考试 | 6xx-通知/订阅
 */
@Getter
@AllArgsConstructor
public enum ResultCode {
    // 通用
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或token已过期"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    SERVER_ERROR(500, "服务器内部错误"),

    // 系统相关 1-
    WX_API_ERROR(1000, "微信接口调用失败"),
    WX_BIND_SCENE_EXPIRED(1001, "微信绑定场景已失效"),

    // 用户相关 2-
    USER_BANNED(2000, "账号已被封禁"),
    USER_MUTED(2001, "账号已被禁言"),
    AGREEMENT_NOT_SIGNED(2002, "请先同意用户协议"),
    CONTENT_AUDIT_REJECT(2003, "内容未通过审核"),
    ROLE_NOT_ENOUGH(2004, "权限不足"),


    // 帖子相关 3-


    // 活动相关 4-
    ACTIVITY_FULL(4000, "活动人数已满"),

    // 考试相关 5-


    // 通知或订阅 6-
    SUBSCRIBE_ALREADY(6000, "已订阅，请勿重复操作");


    private final int code;
    private final String message;
}

```

### 业务异常 `BusinessException`

```java
/**
 * 业务异常（可预期的业务规则违反，不需要打印完整堆栈）
 */
@Getter
public class BusinessException extends RuntimeException{
    private final Integer code;
    private final String message;

    // 用枚举，message 用枚举默认的
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code    = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    // 用枚举，message 自定义覆盖
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code    = resultCode.getCode();
        this.message = message;
    }

}

```

###  全局异常处理器

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 业务异常，正常业务流程中主动抛出
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    // 参数校验失败（@Valid 触发）
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)   // 过滤null
                .findFirst()
                .orElse("参数错误");
        log.warn("参数校验失败: {}", msg);
        return Result.fail(ResultCode.BAD_REQUEST, msg);
    }

    // 兜底，未预期异常
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("未知异常", e);
        return Result.fail(ResultCode.SERVER_ERROR);
    }
}

```

---

## Sa-Token 鉴权规范

### 登录与 Token

```java
// 登录成功后签发 Token（在 UserService 中调用）
StpUtil.login(userId);
String token = StpUtil.getTokenValue();
// 将 token 返回给小程序前端，后续请求携带在 Header: satoken: xxx
```

### 接口权限注解

| 场景 | 注解 | 说明 |
|------|------|------|
| 需要登录 | `@SaCheckLogin` | 未登录抛 `NotLoginException` |
| 需要管理员角色 | `@SaCheckRole("admin")` | |
| 可选登录（游客也能访问，但登录有额外信息） | 不加注解，Service 中判断 | |

```java
// 获取当前登录用户 ID（确认已登录时使用）
Long userId = StpUtil.getLoginIdAsLong();

// 判断是否已登录（游客接口中使用）
boolean isLogin = StpUtil.isLogin();
Long userId = isLogin ? StpUtil.getLoginIdAsLong() : null;
```

---

## Redis 使用规范

###  Key 命名规范

所有 Key 统一维护在 `RedisKeyConstant` 中，格式：`{项目前缀}:{模块}:{类型}:{id}`

```java
/**
 * Redis Key 统一管理。
 * <p>所有业务代码应通过本类生成 key，避免硬编码和前缀漂移。</p>
 */
public final class RedisKeyConstant {
    private RedisKeyConstant() {
    }

    public static final String PREFIX = "miniapp:";

    // 用户相关
    public static final String USER_TOKEN_PREFIX = PREFIX + "user:token:";
    public static final String USER_ROLE_PREFIX = PREFIX + "user:userRole:";
    public static final String USER_PUBLIC_PROFILE_PREFIX = PREFIX + "user:profile:public:";
    public static final String USER_AGREEMENT_PREFIX = PREFIX + "user:agreement:";

    // 互动相关
    public static final String LIKE_COUNT_PREFIX = PREFIX + "like:count:";
    public static final String LIKE_SET_PREFIX = PREFIX + "like:set:";
    public static final String VIEW_COUNT_PREFIX = PREFIX + "view:count:";

    // 微信相关
    public static final String WX_MP_ACCESS_TOKEN = PREFIX + "wx:mp:access_token";
    public static final String WX_BIND_SCENE_PREFIX = PREFIX + "wx:bind:scene:";

    // 队列相关
    public static final String NOTIFY_QUEUE = PREFIX + "notify:queue";
    public static final String QUEUE_MAIN = PREFIX + "queue:main";

    public static String userToken(Long userId) {
        return USER_TOKEN_PREFIX + userId;
    }

    public static String userRole(Long userId) {
        return USER_ROLE_PREFIX + userId;
    }

    public static String userPublicProfile(Long userId) {
        return USER_PUBLIC_PROFILE_PREFIX + userId;
    }

    public static String userAgreement(Long userId) {
        return USER_AGREEMENT_PREFIX + userId;
    }

    public static String likeCount(String targetType, Long targetId) {
        return LIKE_COUNT_PREFIX + targetType + ":" + targetId;
    }

    public static String likeSet(String targetType, Long targetId) {
        return LIKE_SET_PREFIX + targetType + ":" + targetId;
    }

    public static String viewCount(String targetType, Long targetId) {
        return VIEW_COUNT_PREFIX + targetType + ":" + targetId;
    }

    public static String wxBindScene(String scene) {
        return WX_BIND_SCENE_PREFIX + scene;
    }
}
```

### RedisService` 封装

封装 `RedisTemplate` 操作，统一处理异常和日志，业务层不直接操作 `RedisTemplate`：

```java
/**
 * Redis 基础设施封装。
 * <p>业务层仅依赖本类访问 Redis，统一异常日志、键值操作与队列语义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 写入缓存（永久）。
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("[Redis] set 失败，key={}", key, e);
        }
    }

    /**
     * 写入缓存（指定时间与单位）。
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            log.error("[Redis] set 失败，key={}", key, e);
        }
    }

    /**
     * 写入缓存（Duration）。
     */
    public void set(String key, Object value, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            set(key, value);
            return;
        }
        set(key, value, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[Redis] get 失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 按目标类型读取缓存，类型不匹配时返回 null。
     */
    public <T> T get(String key, Class<T> clazz) {
        Object value = get(key);
        if (clazz == null || !clazz.isInstance(value)) {
            return null;
        }
        return clazz.cast(value);
    }

    /**
     * 读取字符串缓存。
     */
    public String getString(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        return String.valueOf(value);
    }

    public Long increment(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("[Redis] increment 失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 删除缓存。
     */
    public boolean delete(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        try {
            return redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("[Redis] delete 失败，key={}", key, e);
            return false;
        }
    }

    /**
     * 查询剩余过期时间（秒）。
     */
    public Long getExpireSeconds(String key) {
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Redis] getExpire 失败，key={}", key, e);
            return null;
        }
    }

    /** 向队列尾部压入消息 */
    public Long rightPush(String key, Object value) {
        try {
            return redisTemplate.opsForList().rightPush(key, value);
        } catch (Exception e) {
            log.error("[Redis] rightPush 失败，key={}", key, e);
            return null;
        }
    }

    /** 向队列头部压入消息 */
    public Long leftPush(String key, Object value) {
        try {
            return redisTemplate.opsForList().leftPush(key, value);
        } catch (Exception e) {
            log.error("[Redis] leftPush 失败，key={}", key, e);
            return null;
        }
    }

    /** 从队列头部弹出（阻塞式） */
    public Object leftPop(String key, long timeout, TimeUnit unit) {
        try {
            return redisTemplate.opsForList().leftPop(key, timeout, unit);
        } catch (RedisConnectionFailureException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Redis] leftPop 失败，key={}", key, e);
            return null;
        }
    }

    /** 从队列尾部弹出（阻塞式） */
    public Object rightPop(String key, long timeout, TimeUnit unit) {
        try {
            return redisTemplate.opsForList().rightPop(key, timeout, unit);
        } catch (RedisConnectionFailureException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Redis] rightPop 失败，key={}", key, e);
            return null;
        }
    }
}

```

### 通知推送队列（infrastructure/queue）

`notify_queue` 表由定时任务扫描，到达推送时间后将任务压入 Redis List，由消费者异步处理实际推送：

```java
// 生产者（定时任务调用）
public interface QueueProducer {
    <T extends MessagePayload> void send(T payload);
}

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisQueueProducer implements QueueProducer {
    private final ObjectMapper objectMapper;
    private final RedisService redisService;

    @Override
    public <T extends MessagePayload> void send(T payload) {
        Class<?> clazz = payload.getClass();
        String topic  = resolveTopic(clazz);

        String traceId = MDC.get("traceId");
        QueueMessage<T> msg = QueueMessage.<T>builder()
                .topic(topic)
                .traceId(traceId)
                .userId(UserContext.getUserId())
                .retryCount(0)
                .payload(payload)
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 message 失败", e);
        }

        doSend(json);
    }
}

// 消费者（独立线程轮询）
@Slf4j
@Component
public class RedisQueueConsumer {

    private static final long REDIS_ERROR_LOG_INTERVAL_MS = 30_000L;
    private static final long REDIS_RETRY_INITIAL_BACKOFF_MS = 1_000L;
    private static final long REDIS_RETRY_MAX_BACKOFF_MS = 30_000L;

    private final Executor handlerExecutor;
    private final MessageDispatcher messageDispatcher;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    volatile boolean running = true;
    private boolean redisUnavailable = false;
    private long nextRedisErrorLogAt = 0L;
    private int suppressedRedisErrorCount = 0;
    private long retryBackoffMs = REDIS_RETRY_INITIAL_BACKOFF_MS;

    public RedisQueueConsumer(@Qualifier("consumerExecutor") Executor handlerExecutor, MessageDispatcher messageDispatcher, RedisService redisService, ObjectMapper objectMapper) {
        this.handlerExecutor = handlerExecutor;
        this.messageDispatcher = messageDispatcher;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        running = false;
    }

    // Spring 启动后会执行这个方法
    @PostConstruct
    public void startConsumer() {
        // 然后启动一个永远运行的线程，然后队列消费者就会一直监听 redis
        Thread thread = new Thread(this::consumeLoop, "queue-redis-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 通过 Redis 获取队列消息
     * 反序列化基础消息，获取 topic
     * 提交线程池，绑定了 MDC 和 context，提交给 consumer
     */
    private void consumeLoop() {
        while (running) {
            // ……
        }
    }
}
```

---

## 第三方集成规范（COS / 微信）

### 14.1 目录结构

```
third/
├── cos/
│   ├── config/CosConfig.java          # COS 客户端 Bean 配置
│   ├── client/CosClient.java          # 对 COSClient 的封装
│   ├── service/CosService.java        # COS 业务方法（生成临时密钥等）
│   └── dto/CosCredentialDTO.java      # 返回给前端的临时凭证 DTO
└── wx/
    ├── config/WxConfig.java           # 微信配置（appid/secret）
    ├── client/WxApiClient.java        # WebClient 封装，调用微信 API
    ├── service/WxAuthService.java     # 登录、code2session
    ├── service/WxMsgSecService.java   # 文字/图片安全检测
    ├── service/WxNotifyService.java   # 订阅消息推送
    └── dto/                           # 微信接口请求/响应 DTO
```

### COS 直传凭证接口

前端直传 COS，后端仅提供临时密钥，不做文件中转：

```java

```

### 微信 API 调用（WebClient）

```java
/**
 * 微信第三方网关客户端。
 * <p>
 * 负责封装小程序与服务号的常用 API 调用，包括：
 * 小程序登录、服务号 access_token 获取、二维码创建、用户信息查询、模板消息发送。
 * </p>
 * <p>
 * 本类仅处理第三方调用与通用错误转换，不承载业务编排逻辑。
 * </p>
 *
 * @implNote 服务号 access_token 会缓存到 Redis；当识别到 token 失效时会刷新并重试一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxClient {

	private static final String MP_ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
	private static final String MP_CREATE_QR_URL = "https://api.weixin.qq.com/cgi-bin/qrcode/create";
	private static final String MP_TEMPLATE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/template/send";
	private static final String MP_USER_INFO_URL = "https://api.weixin.qq.com/cgi-bin/user/info";
	private static final String MINI_CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

	private final WebClient webClient;
	private final WxMiniProperties wxMiniProperties;
	private final WxMpProperties wxMpProperties;
	private final RedisService redisService;
}
```

### 内容安全审核流程

```java

```

---

## 依赖说明

| 依赖 | 用途 | 使用注意 |
|------|------|---------|
| `spring-boot-web` | Web 服务基础、Controller、RestTemplate | — |
| `spring-boot-validation` | DTO 参数校验（`@Valid`、`@NotBlank` 等） | 配合全局异常处理器使用 |
| `mybatis-plus` | ORM、BaseMapper、软删除（`@TableLogic`）、自动填充 | 启用 `map-underscore-to-camel-case` |
| `sa-token` | 登录鉴权、角色控制 | Token 存 Redis，配置 `SaTokenConfig` |
| `spring-data-redis` + `lettuce` | Redis 缓存、消息队列（List）、计数 | 通过 `RedisService` 统一访问 |
| `jackson` | JSON 序列化/反序列化 | 配置：`LocalDateTime` 序列化格式、`null` 字段不返回 |
| `jackson-dataformat-xml` | XML 格式支持（如微信 API 回调使用 XML） | 微信消息推送/服务号回调时使用 |
| `mapstruct` | DTO ↔ Entity ↔ VO 对象转换 | 与 Lombok 配合时注意 `annotationProcessor` 顺序 |
| `lombok` | `@Data`、`@Slf4j`、`@Builder` 等代码简化 | 放在 `mapstruct-processor` 之前 |
| `WebClient`（spring-webflux） | 调用微信 API、外部 HTTP（异步非阻塞） | 仅做 HTTP 客户端用，无需完整 WebFlux 依赖 |
| 腾讯云 COS SDK | COS 文件操作、STS 临时密钥 | 配置在 `third/cos` 包中 |
| `guava` | 工具类备用（Collections、Cache等） | 当前未启用，按需引入具体工具方法 |

### Lombok + MapStruct annotationProcessor 顺序（pom.xml）

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <!-- Lombok 必须在 MapStruct 之前 -->
          <path>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
          </path>
          <path>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct-processor</artifactId>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Jackson 全局配置

```java
/**
 * Jackson 全局序列化配置
 * <p>
 * 解决以下问题：
 * 1. Long 类型超出 JS 精度范围（前端 id 丢失精度）→ Long 转 String
 * 2. LocalDateTime 序列化为时间戳而非字符串 → 统一格式 yyyy-MM-dd HH:mm:ss
 * 3. null 字段照常返回（不过滤），保持接口字段稳定
 * 4. 前端传来未知字段不报错（小程序版本兼容）
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_FORMAT       = "yyyy-MM-dd";

    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        JavaTimeModule javaTimeModule = new JavaTimeModule();

        // LocalDateTime 序列化/反序列化格式
        javaTimeModule.addSerializer(LocalDateTime.class,
            new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));
        javaTimeModule.addDeserializer(LocalDateTime.class,
            new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));

        // LocalDate 序列化/反序列化格式
        javaTimeModule.addSerializer(LocalDate.class,
            new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));
        javaTimeModule.addDeserializer(LocalDate.class,
            new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));

        return builder
            .modules(javaTimeModule)
            // Long/long → String，解决前端 JS 精度丢失问题
            .serializerByType(Long.class, ToStringSerializer.instance)
            .serializerByType(long.class, ToStringSerializer.instance)
            // 不把时间序列化为时间戳
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 前端传来多余字段不报错（兼容小程序多版本并行）
            .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // null 字段正常返回，不过滤
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();
    }
}

```

---

## 附录：开发检查清单

在提交代码前，逐项确认：

**结构规范**
- [ ] 新增模块按 `module/{模块}/entity,controller,service,dto,vo,converter,mapper` 结构创建
- [ ] 新增第三方集成按 `third/{名称}/config,client,service,dto` 结构创建

**命名规范**
- [ ] 类名符合 `{模块}{层次}` 命名规则
- [ ] 请求 DTO 以 `Request` 结尾，响应 VO 以 `VO` 结尾
- [ ] 方法名使用 `get/list/page/create/update/remove` 等前缀

**代码规范**
- [ ] Controller 不写业务逻辑，不直接调 Mapper
- [ ] Controller 不返回 Entity 或 Map
- [ ] Service 接口方法有完整 Javadoc
- [ ] 多表写操作加 `@Transactional(rollbackFor = Exception.class)`
- [ ] 对象转换使用 Converter，不手动 set
- [ ] 敏感字段（phone、openid）不出现在 VO 中
- [ ] 列表分页使用游标分页，不用 OFFSET

**日志规范**
- [ ] 关键业务操作有 `log.info` 记录
- [ ] 异常使用 `log.error(msg, e)` 格式，保留堆栈
- [ ] 无 `System.out.println`

**Redis 规范**
- [ ] Redis Key 通过 `RedisKeyConstant` 常量引用，无魔法字符串

---

*文档版本 v1.0 | 随项目迭代同步维护*
