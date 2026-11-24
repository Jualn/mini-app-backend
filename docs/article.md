# Article 官方富文本资讯

外部组织署名权威为 [Organization Publisher V2](../../contracts/docs/coordination/article-organization-publisher.md)，正文、分类和互动基线为 [Article Contract](../../contracts/docs/coordination/article.md)、[Article operations](../../contracts/api/paths/article.yaml)、[Article schemas](../../contracts/api/schemas/article.yaml) 与 [canonical interactions](../../contracts/api/paths/interaction.yaml)。本文件只维护 Backend 所有权、实现边界、配置和启用条件，不复制请求字段或改变跨端协议。

## 所有权与复用

| 链路 | 使用方式 |
|---|---|
| Post、OfficialContent | 保持独立表、ID 和发布生命周期，不继承、不迁移历史内容 |
| Article 主体、两层分类、统一组织署名与旧 Publisher 只读适配 | article 模块独立维护；ArticleService / ArticleWriter 拆分候选检查与短写事务 |
| 管理身份 | 复用独立 AdminStpUtil、AdminTokenService 和现有 MVC 拦截器；ArticleAuthorization 表达本业务能力边界 |
| Comment | 复用 comment；canonical 增量入口同步检查纯文本后落库，根线程、真实回复目标及删除由 comment owner 处理；旧 Post 创建仍保留异步审核 |
| Like、Share、View | 复用既有事实表和 InteractService；ARTICLE 持久编码 8，wire 为语义字符串；不落入 Post 分支 |
| 计数 | Interact / Comment owner 提供批量事实投影；Article owner 回写自己的统计列，不改变编辑版本；公开统计从事实读取 |
| 存在性及缓存 | SubjectExistenceService 的 Article 存在性与公开资格分开；Article 和父文章可见性实时读库，不能以缓存存在或点赞状态放行；公共表示 no-store |
| 媒体 | Media 拥有登记、不可变资源快照、上传声明、引用和清理；复用 COS Client / Service、上传记录及 event_attachment_link |
| 审核及异步 | 复用微信文本检查、AuditReservationService、Job、审核日志与回调；ARTICLE 审核场景编码 9，与 TargetType=8 不混用；候选回调不发布文章 |
| 搜索与场景 | 公开及管理 q 在 Article title / plainText 查询；公开 sceneCode 使用顶层 sceneCodes 的当前启用映射，与分类和 q 在 SQL 分页前 AND 过滤；不混入旧 Post 搜索 |
| 通知 | 只对真实评论用户发送 COMMENT_REPLIED / COMMENT_LIKED；冻结标题和摘录，首次正向事实去重，排除自己；文章点赞、顶层评论不通知官方主体或管理员 |
| 日志、指标 | 复用 HTTP trace / Problem Details / Micrometer HTTP 指标及既有 async 指标；不建立每文章 ID 的 metric 或第二套审计系统 |

## 状态、并发与读取

正文保存在版本化 JSON 文档中；ArticleBodyCodec 校验封闭节点、属性、code point 长度、HTTPS 链接、资源种类和集合上限，顺序派生 plainText，不能把 HTML 或编辑器私有 JSON 当正文。媒体 caption 进入阅读文本，alt 和链接只进入安全检查输入。

草稿可未分类和空正文。发布、重新发布、在线编辑先检查候选完整性及全部安全输入，成功后进入 ArticleWriter；写事务按 Person gate → RBAC governance/账号 → Article guard/文章/分类取锁并核对原 If-Match、可用媒体和合法状态。任一步失败保留旧表示和旧引用。审核远程调用、等待和 COS 操作不进入 Article 写事务。

强 ETag 仅覆盖管理表示。缺少、格式不合法、过期分别为 428、400、412；动态统计、分类改名和主体改名不更新 Article revision。首次发布时间保留，重新发布不改变公开排序。公开详情、列表和评论读取使用一致数据库读事务；列表过滤先于 LIMIT，游标绑定分类、includeChildren 的有效布尔值、排序、用户身份及位置，评论游标另绑定文章和根线程。

分类复用 article_category，可空 parent_id 只指向顶层项；关系仅在创建时确定，最多两层。创建不要求父项启用；替换省略 parentId 保留关系，显式值仅允许原父项，null、改挂、自引用、不存在或三级关系返回 400，错误指向 #/parentId。顶层响应省略 parentId，子项在目录及文章 category 投影返回该字段。整个目录仍合计最多 100 项，名称仍全目录 trim 后精确唯一，包含停用项。

公开分类导航以同一次目录读取检查本项及父项启用；管理目录保留全部项。停用父项不修改子项 enabled、不下架或迁移历史文章、不改变 Article 编辑 ETag。草稿及下架文章可保留停用引用；发布、重新发布与在线编辑在事务外候选检查和最终写事务内均检查本项及父项状态。最终写事务与分类维护复用 Article 自有 guard 行锁，分类维护采用 READ_COMMITTED，避免等待锁后按旧快照误算目录上限；授权和强 If-Match 均保留。

Article 仅保存原 categoryId。公开及管理文章列表默认精确匹配；includeChildren=true 通过 SQL 子查询包含本项及直属子项，再排序和分页，管理计数使用同一谓词。子项汇总等同精确匹配；停用 ID 仍可查询历史；未知分类为 404。includeChildren 只接受 true/false，未传 categoryId 时连显式 false 也返回 400。公开游标绑定该有效值，省略等同 false，切换条件返回 invalid-cursor；旧游标不兼容时从首页重读。分类无删除入口；文章使用独立逻辑删除和恢复。V1 无定时发布、组织目录或完整修订历史。

互动写事务锁定当前 Article 和相关根评论。关闭评论只拒绝新增；历史有效评论仍可读、点赞和本人删除。下架隐藏新的公开读和互动，本人删除已有评论例外。根删除隐藏整个线程，统计排除其回复。每日浏览以 view_log 的 target_type / target_id / user_id / Asia/Shanghai view_day 唯一约束去重；GET 和预览不写浏览事实。

## 媒体生命周期

Article 上传凭证限定本次 objectKeys，允许必要的分片上传，旧上传凭证语义不变；对象级范围参照 [COS STS SDK](https://github.com/tencentyun/qcloud-cos-sts-sdk/blob/master/java/src/main/java/com/tencent/cloud/CosStsClient.java)。声明保存在既有上传记录中。客户端不能获得 `article/retained/` 的写凭证。

登记流程在远程复制前持久化快照的清理责任，再从管理员此次 `article/upload/` 上传复制到服务端 `article/retained/`。检查实际大小和类型；JPEG / PNG / WebP 通过 ImageIO 实际解码（WebP 插件为 [TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys)），先限制解码像素规模；MP4 至少检查文件头；配置 ffprobe 后再检查容器、H.264 / 可选 AAC、尺寸和时长。临时本地文件在调用完成后清理。未配置 ffprobe 时允许视频登记，播放表示仅返回 contentType，不虚构宽高或时长；基础 verified 只表示已完成当前技术检查，不表示编码或可解码性已验证。配置工具后执行失败仍拒绝登记，不静默降级。

最后的登记事务核对管理员、上传声明和待绑定状态，原子写入不可变 Attachment 并绑定快照上传记录。失败保留 PENDING 清理责任，不能留下错误 BOUND。原临时上传继续过期回收；已登记快照由独立资源保留。

Article 保存维护封面、正文、海报和独立 PDF/WORD 文件的全部实际 ID 集合；内容及新增 / 移除引用同事务提交。草稿、下架和逻辑删除同样保留引用；解除本 Article 引用不会直接删除共享附件或 COS 对象。历史未验证、外部 URL 或客户端仍可覆盖的资源不得冒充已验证 Article 媒体，应重新上传 / 登记。旧 OfficialContent 的 LINK 视频投影保留，其他主体不接收 VIDEO。

公共附件 API 必须至少有一个当前公开主体引用；只被草稿 / 下架 Article 引用时 404。管理读取 Article 媒体检查 ARTICLE/media:read；有关联 Article 时至少一篇须在当前精确组织读取范围，上传者、其他 namespace 或 GLOBAL 不能绕过。单项、批量、本人正式列表使用相同 SQL 范围谓词。下架不能撤回已经获得的 COS 公共 URL，本实现不承诺机密草稿存储或私有签名播放。

## 授权、配置和启用条件

ArticleAuthorization 在独立管理员登录后读取当前主库 Article capability；OrganizationPolicy 再以同一事务的 UnifiedDutyReader 校验逐职责精确组织来源。账号、目录 readiness、委派期限、撤权与成员周期由既有 Reader 验证；普通GLOBAL Assignment、组织成员/负责人不替代Article组织范围；当前真实ROOT的已开放Article owner capability覆盖全部真实组织的管理范围。所有读写仍来自可信管理会话，createdBy/updatedBy 与组织署名分别保存。

| 内部能力 | canonical Permission，namespace 均为 ARTICLE |
|---|---|
| READ | article:read |
| CREATE / EDIT | article:edit；编辑已发布文章另需 article:publish，安全检查前和锁定文章后的写事务内均复核 |
| PUBLISH / UNPUBLISH | article:publish / article:unpublish |
| CATEGORY_READ / CATEGORY_MANAGE | article-category:read / article-category:manage |
| PUBLISHER_READ / PUBLISHER_MANAGE | article-publisher:read / article-publisher:manage |
| MEDIA_READ / MEDIA | media:read / media:write；读取与上传、登记分开 |

保存带媒体引用的正文还需 media:write，写事务复核；发布带媒体内容还需 media:read，不要求发布者拥有上传权。CONTENT_EDITOR 和 CONTENT_PUBLISHER 的完整既有组合仍由受控目录与当前运行支持共同决定，不裁剪跨域成员来启用角色。分类管理由 ARTICLE_CATEGORY_MAINTAIN / ARTICLE_CATEGORY_STEWARD_V1 承接 PLATFORM 目标，按当前 options 与完整角色就绪判断；旧 Publisher 改名仍缺固定角色归属，保持不可授予。Article 读取/写入映射不会开放通用内容列表、评论处分或审核的 ARTICLE 域门禁。

ARTICLE_EDIT / ARTICLE_PUBLISH 复用 CONTENT_EDITOR / CONTENT_PUBLISHER × ARTICLE 的完整同源交集和既有统一 options→preview→commit→effective-duties，不增加 Permission、Role 或授权 CRUD。受控模板副本与 Contracts 同步，默认 NOT_READY/grantable=false 不改；Provider 的实现支持清单包含两模板，运行角色/所有权限仍须实际就绪才可授予。本实现不授予真人、不初始化 ROOT、不扩大组织层级。写事务最终重读当前来源和时间，Article 变更、媒体引用与 article_organization_audit 同事务提交；审计失败则回滚。审计保留原新组织、署名、操作者及完整来源，不能以外层预检查代替最终权限判断。

新建直接提交 publisherOrgId，无 Publisher 或 Bootstrap 前置。创建/换入和发布要求 ACTIVE；同组织草稿/下架编辑可保留停用组织。在线编辑需原、新组织两侧 EDIT 与 PUBLISH 来源；停用组织有仍有效 PUBLISH 职责时可下架。首次和重新发布冻结当前可信组织名，首次 publishedAt 不变；同组织普通在线编辑、下架、组织改名保留快照，在线换组织更新快照。新署名先经过原文字安全检查，最终提交复核组织名；检查期间改名返回 503，不发布未经检查的新名。

授权组织候选仅投影实际同源 ARTICLE_EDIT 覆盖的 ACTIVE Organization（ID/name/status），不授组织目录权。授权后按 ID 排序分页，游标绑定操作者、当前账号/目录版本、person epoch、来源及组织投影；撤权、到期、改名/启停后旧游标不能续读。管理列表以同一组织谓词过滤、分页和 COUNT，publisherOrgId 精确筛选未知/无范围404，与旧 publisherId 同传400。

旧 Publisher 表及 GET/PUT 端点保留；不再注册 ArticlePublisherBootstrap，旧初始化配置不执行。旧 publisherId 请求仅查询显式批准的 article_publisher_organization_mapping，再校验当前职责，不数值强转。V61 不自动写任何映射或历史快照。历史已发布/下架记录还需每篇 article_publisher_migration_evidence 中可核实的原署名；映射和原署名证据不足不提升为完整组织表示。旧文公开和管理只读保留旧标签，管理只读沿用变更前当前 ASSIGNMENT + ARTICLE/article:read 的受控策略，不转成组织写范围；旧文所有写入返回 migration-required。原 Publisher 仍被文章引用时，改名拒绝，避免改变保留标签。经批准的旧草稿绑定只需可靠映射，首次发布再生成快照。历史迁移不通过编辑/换组织接口临时认领。

整体切换为 breaking：响应双读适配保留旧 publisher/publisherId 分支，新组织表示省略这些字段。消费者须按组织快照或旧标签分支读取，不能把旧 ID 当 Organization。Provider 本地实现/隔离验证不等于真实读端切换验收；真实消费者、账号与目标数据库核验之前不能宣称 Release Ready。人工审核仍无 Article 正式协议，不增加审核流程。

| 配置 | 用途与默认行为 |
|---|---|
| 旧 `app.article.initial-publisher-name`、`app.article.bootstrap-admin-id` | 已退役，Bootstrap 不注册为启动组件；不再创建或依赖旧 Publisher 配置 |
| `app.article.wechat-image-audit-enabled` | 默认关闭，`prod` 启用。启用时文章图片送微信异步审核并等待回调；关闭时仍校验图片资源，跳过微信图片送审。 |
| `app.article.ffprobe-path` | 可选深度检查程序的绝对文件路径；默认空，仅检查 MP4 文件头并允许视频登记。容器 / 主机安装与安全管理归部署 owner |
| Article 来源通知目标 | 默认生成 ARTICLE_DETAIL 和 ARTICLE 投影，无独立启用开关；客户端按契约解析目标，未知目标保留文本与已读能力 |
| `cos.video-audit.enabled` / `COS_VIDEO_AUDIT_ENABLED` | 默认 false；开启后为 Article 不可变视频资源预占 CI 审核任务，使用现有 COS 服务端密钥，不向客户端发放审核权限 |
| `cos.video-audit.biz-type` / `COS_VIDEO_AUDIT_BIZ_TYPE` | 开启时必填：桶所属数据万象的审核策略 ID；部署 owner 核对策略包含所需的画面、OCR、音频风险场景 |
| `cos.video-audit.policy-revision` / `COS_VIDEO_AUDIT_POLICY_REVISION` | 默认 `1`；控制台修改同一策略时必须同步递增，防止沿用旧策略结果 |
| `cos.video-audit.snapshot-interval-seconds`、`poll-interval-seconds`、`deadline-minutes` | 默认每秒截帧、30 秒查询、120 分钟截止；配置范围由类型化配置校验。截帧最多 10000 张，超出当前采样覆盖时长的视频不放行 |

文本审核依赖操作者真实绑定的微信小程序身份及 provider 准入，不能用 publisherId 代替。审核输入覆盖标题、摘要、正文文本、链接、alt / caption、封面、图片、海报和视频。启用微信图片审核时，图片候选批量预占既有任务，在有界等待内只接受完整确定的结果；超时或未知结果返回 503，不延迟自动生效。

文章文本审核兼容微信的关键词未命中明细。只有微信客户端从原始响应验证综合结果和内容模型均明确通过，且缺少建议的关键词项恰好只有 `strategy=keyword` 和数字 `errcode=0`，才接受该明细。

视频审核使用 [COS 数据万象异步审核](https://cloud.tencent.com/document/product/436/119485)，与微信图片 / 音频接口分开。media 在视频登记事务内调用 audit 的 VideoSafetyChecks 预占；audit 的 `media_video_audit` 保存资源 ID、不可变 objectKey、大小、桶 / 地域、策略指纹和 provider JobId，并同事务创建既有 `async_job`。开启前登记的视频在下一次发布校验时补预占。只接受 `article/retained/` 的服务端不可变资源；更换附件或策略版本重新审核，不能把旧视频 PASS 赋给新视频。

`audit.cos-video.check` Handler 在事务外提交 / 查询，固定 HTTPS CI 域名，复用现有 COS SDK 签名；提交显式指定审核策略、`DetectContent=1`、每秒截图及最多 10000 帧，不配置对象冻结或公网回调。主动查询不要求开放回调端点；纯查询最多 4 次执行尝试，正常审核中的结果则同事务创建下一轮有界延迟 Job，直至截止时间。每次落库重查 Job ownership 与序号，旧 worker / 重复响应不能覆盖结果。复用现有 Job 指标、DEAD 与恢复入口，不建设第二套队列。

只有后续经过签名的查询返回 `Success`、顶层明确正常、截图完整且无矛盾的分段结果，并且 JobId / DataId / objectKey 匹配，才能记 PASS；提交受理不等于通过。疑似结果记 REVIEW，不自动人工批准；违规记 REJECT；失败和未知均拒绝发布。HTTP 不等待视频任务完成，未就绪返回 Contract 既有 503 `content-safety-check-unavailable`，违规返回 409 `content-rejected`；完成后由管理员重新发起发布，后台不自动发布文章，现有文章和 If-Match 不被审核任务改写。图片和文字仍执行原审核链路。

提交超时、响应丢失或提交后的本地故障不能证明任务未创建；预先持久化 SUBMITTING，恢复时转 UNKNOWN 并停止自动重发，避免重复收费。平台只回传 DataId，不提供已验证的创建幂等保证；不能把 DataId 当幂等键。排障、核对 provider 任务和授权恢复见 [Runbook](operations/runbook.md#article-cos-video-audit)。

其他必需启用证据为：精确组织职责及当前账号验证、历史映射/署名证据和消费者双读、真实 COS 上传 / 快照 / 对象范围、微信审核权限及回调、域名 / CORS / 编码、Mini Program 设备视频与富文本渲染、Web Admin 编辑器双向适配和冲突处理、所有通知消费者兼容。源码、隔离数据库测试和测试替身不是这些运行事实。

组织发布迁移为 V61，历史恢复与回退见数据库入口；两层分类结构扩展为 V60；既有分类和文章无数据改写，不自动创建或迁移候选栏目。迁移顺序与回滚兼容见 [数据库入口](../src/main/resources/db/README.md#article-v36) 和 V37，可执行验证见 [commands](../governance/commands.md#article-focused-validation)。外部 Contract 不在 Backend 修改。开启 COS 服务不等于已经验收：还需服务端配置、桶 / CAM 权限、可选 ffprobe 深度检查、真实视频播放及按实际启用策略验证；Article 管理能力仍须完整运行支持清单、目录可用性及当前 Assignment 共同成立。


当前真实ROOT凭已开放Article owner capability覆盖全部真实组织的文章管理；无需额外组织职责。article_organization_audit的authorization_sources仍为非空数组，ROOT操作记录sourceType=ROOT_OWNER及真实rootAssignmentId，普通操作保留原职责来源。组织状态、署名快照、历史映射、内容安全、强版本、审计及提交前复核不变；ROOT撤销后立即拒绝。

## 统一内容、文件与保留

Article 内容基础消费自 `article-content-v1-r1`；当前协议权威为 canonical OpenAPI / Article / LearningTask operations 与 schemas，LearningTask 交叉增量沿其协调规则。本节仅记录 Backend 机制，固定历史包不覆盖后续 canonical 变更。

V83 增量存储场景映射、独立文件 ID 顺序、来源、删除标记及 User 收藏。V92 初始化六个固定入口的主栏目，保留已有合法的一对一绑定及启停状态，补齐缺失项。重复绑定、多重绑定或目录容量不足时迁移失败。数据库约束限制每个主栏目至多一个入口，并保证入口唯一。管理写入可省略场景或回传原绑定。入口主栏目停用时查询返回空集。场景游标绑定当前根/子分类集合，q、categoryId、includeChildren 和身份也参与绑定。

独立资料沿 Article 专用凭证和不可变 `article/retained/` 快照登记，复用 Attachment 候选和业务事务接管。ArticleDocumentInspector 使用现有 PDFBox / POI 被动解析：PDF 严格解析，拒绝加密及主动动作/嵌入内容；DOC 拒绝宏、嵌入和加密结构；DOCX 先检查 ZIP 条目、展开量、宏/嵌入及外部关系，再提取正文与表格。解析边界为单文件 10MiB、PDF 1000 页、展开/解码总量 40MiB、提取文字每文件 20 万字符、每文章 100 万字符。超限拒绝，不截断后冒充完整检查。发布在写事务外重新读取不可变文件，提取为空时当前策略拒绝发布；显示名、来源和提取文字进入既有文字安全检查，正文媒体继续原图片/视频策略。没有新增杀毒或人工审批状态。

旧替换省略 attachmentIds/source 时在同一强版本下保留；显式空附件数组或 null 来源清除。最终写事务重读原字段并维护所有实际引用，错误不部分接管。文件仅进入详情 attachments，正文 media 仍仅 IMAGE/VIDEO。技术核验、文字检查及真实终端打开分别验收。

ArticleFavorites 仅保存登录 User 的关系。重复 PUT 保留原时间，与文章行锁共同裁决当前公开资格；失效收藏列表只返回关系和 UNAVAILABLE，不读取隐藏摘要。取消不要求目标存在。收藏读取不增加浏览、学习完成、通知或积分。

ArticleWriter 的删除/恢复沿原组织职责、强 ETag、可靠组织审计与提交前授权复核。删除不改三个发布状态，已发布先撤下；恢复保持 DRAFT/UNPUBLISHED。LearningTaskArticleUsage 查询全部保留引用，不按管理员任务可见范围过滤；未知返回 503。删除事务使用 READ_COMMITTED，在取得 Article guard 后读取最新引用事实。LearningTask 的新增/改变非空引用和首次发布通过 ArticleReferenceReadService 在同一事务持有该 guard 并锁定公开未删除文章；撤下/删除与引用准入共同裁决。未改变引用的普通任务更正、增补分配及完成/提交/审核/生命周期动作不重验材料公开资格。

任务选项由 LearningTaskArticleOptions 按独立管理身份、read + edit 和 LEARNING_MANAGE 原目标授权，Article owner 只提供公开最小材料投影；不需要 Article 管理职责，可跨发布组织。选项游标绑定管理者、目标/任务、当前来源及授权版本，写入仍独立重验。

E·安全旧 OfficialContent 入口和数据保持。只有可信目标环境盘点才能证明旧集合为空；隔离库、未知盘点和新 Article 可读都不能替代该证据。数据迁移需另外授权。本地机制测试不等于业务库迁移、COS/微信或消费者发布验收。

### 本次已确认的 ROOT 差异

用户明确要求保留工作区新 ROOT 全域管理语义。这与上述固定包要求 ROOT 也具备精确 Article 组织职责的条文不同：当前实现沿既有有效 ROOT owner 来源提供全域 Article 管理及任务目标管理，不创建给 ROOT 自己的职责/组织范围；操作审计保留 ROOT assignment 来源。普通管理员仍按精确组织职责裁决；组织状态、旧 Publisher 证据、当前账号/目录就绪、期限及撤权、强版本和文章/任务状态保护继续有效。此为本次已确认实现差异，不表示固定包正文已同步，也不自动更改受控定义 readiness 或授予真人。


普通撤下与 LearningTask 首次发布/引用准入共用 Article guard，检查当前 PUBLISHED 引用，返回 article-in-use；当前读使用有界锁定查询，不依赖事务旧快照。截止经过或全部完成不解除保护。usage 另提供 hasActiveReferences/canUnpublish，不暴露任务标识；提示不是后续写入许可。显式 SAFETY 仍使用原 article:unpublish 与组织范围、强 ETag、提交前授权复核；V85 article_safety_unpublish 留存原因，审计受理失败回滚停展。安全模式跳过活动任务检查，不等待任务管理，不自动取消任务或释放历史引用。部署前须核对管理端新提示/显式安全模式兼容，停止旧 writer，不能让普通失败自动转安全操作。
