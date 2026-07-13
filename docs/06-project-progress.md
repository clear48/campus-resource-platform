# 项目进度文档

## 1. 当前阶段结论

项目当前处于“基础工程 + 用户认证模块 + 分类查询模块 + 文件上传模块 + 资料模块首版 + 审核模块首版 + 搜索模块首版 + 下载模块首版 + 收藏模块首版完成”阶段。

已经完成的核心能力包括：Spring Boot 后端基础骨架、统一响应与异常处理、JWT 鉴权、Redis Token 黑名单、用户注册/登录/退出登录/当前用户查询、公开分类查询、文件上传与 MD5 秒传、基于 `fileId` 创建资料、公开资料详情、我的上传资料分页查询、管理员待审核列表、审核通过、审核拒绝、下架资料、审核记录查询、公开资料搜索与热门搜索词写入、登录后下载，以及收藏/取消收藏/收藏状态查询/我的收藏列表。

资料模块首版已经把 `file_info` 物理文件转换为 `resource` 业务主体，审核模块进一步把待审核资料推进到 `APPROVED`、`REJECTED`、`OFFLINE` 状态，并通过 `audit_record` 保留审计流水。搜索模块首版消费 `status = 1 APPROVED` 的公开资料，提供关键词/分类/课程/类型/标签筛选、分页排序，并把非空关键词写入 Redis 热门搜索词 ZSet。收藏模块首版以 MySQL `favorite` 表和唯一索引保证幂等，维护 `resource.favorite_count`，并以 Redis Set 加速状态查询。排行榜与定时任务模块已完成查询、行为热度联动、下载增量同步、all 总榜重建、热度快照和管理员手动重建；总榜重建与实时热度写入已通过 Redisson 读写锁协调。前端演示模块已完成 T01-T13：基础工程、认证闭环、个人会话和公开排行榜 API 均已完成；请求层测试 3/3、枚举格式化测试 4/4、认证 API 测试 3/3、登录/注册/个人信息组件测试各 1/1、会话测试 2/2、排行榜 API 测试 2/2 与生产构建均通过。本机后端 8080 端口未监听，真实认证联调待后续联调。当前进入 T14 排行榜首页。下一阶段可补充 Mapper 集成测试和任务运行指标。

### 前端自动队列最新进度

- 已完成 T01-T14：基础工程、认证闭环、会话、公开排行榜 API 与排行榜首页。
- T14 已通过 `npm run test:unit -- src/views/HomeView.test.ts`（1/1）和 `npm run build`；首页不使用图表，热点数据为空时展示正常空态。
- T15 已通过 `npm run test:unit -- src/api/categories.test.ts src/api/search.test.ts`（2/2）和 `npm run build`；未调用后端尚未实现的搜索建议接口。
- T16 已通过 `npm run test:unit -- src/views/SearchView.test.ts`（1/1）和 `npm run build`；页面支持公开资料筛选、排序、分页和 URL 关键词回填。
- T17 已通过 `npm run test:unit -- src/api/resources.test.ts`（2/2）和 `npm run build`；已覆盖公开详情路径与创建资料 JSON 请求体。
- T18 已通过 `npm run test:unit -- src/views/ResourceDetailView.test.ts`（1/1）和 `npm run build`；游客可访问只读公开详情，未接入收藏和下载操作。
- 本轮已完成三个开发阶段，队列暂停在 T19 收藏 API 前；本机后端 8080 端口未监听，真实接口联调仍待补充。
- T19 已通过 `npm run test:unit -- src/api/favorites.test.ts`（3/3）和 `npm run build`；收藏、取消和状态请求均由会话层携带 Token，未使用 `hotScoreDelta` 计算热度。
- 当前执行 T20：详情页收藏操作；真实接口联调仍待后端 8080 服务可用后执行。
- T20 已通过 `npm run test:unit -- src/views/ResourceDetailView.test.ts`（2/2）和 `npm run build`；游客跳登录，登录用户可收藏或取消收藏。
- 当前执行 T21：下载 API 和文件流工具。
- T21 已通过 `npm run test:unit -- src/api/downloads.test.ts src/utils/file-download.test.ts`（4/4）和 `npm run build`。
- 当前执行 T22：详情页两步下载。
- T22 已通过 `npm run test:unit -- src/views/ResourceDetailView.test.ts`（3/3）和 `npm run build`；页面严格执行两步下载并展示 counted。
- 当前执行 T23：MD5 工具和文件 API。
- T23 暂停：浏览器原生 Web Crypto 不支持 MD5，项目未安装 MD5 实现；按前端队列停止条件等待用户授权新增轻量 MD5 依赖后继续。
- 用户已授权后，T23 新增 `spark-md5@3.0.2` 并通过 MD5/文件 API 测试 3/3、`npm audit` 与生产构建。
- 当前执行 T24：上传页文件选择、预检和上传。
- T24 已通过 `npm run test:unit -- src/views/UploadView.test.ts`（1/1）和 `npm run build`；秒传命中不会重复上传。
- T25 已通过 `npm run test:unit -- src/views/UploadView.test.ts`（2/2）和 `npm run build`；标题、简介、分类、课程、类型、标签可填写，未取得 `fileId` 时不能提交资料。
- T26 已通过 `npm run test:unit -- src/views/UploadView.test.ts`（3/3）和 `npm run build`；已验证资料创建请求、待审核结果和我的上传入口。
- T27 已通过 `npm run test:unit -- src/api/users.test.ts`（3/3）和 `npm run build`；Token、状态筛选和分页参数已由 Mock 验证。
- T28 已通过 `npm run test:unit -- src/views/user/MyUploadsView.test.ts`（1/1）和 `npm run build`；已验证上传资料、状态和拒绝原因展示。
- T29 已通过 `npm run test:unit -- src/api/users.test.ts`（4/4）和 `npm run build`；已验证收藏列表 Token 与分页参数。
- T30 已通过 `npm run test:unit -- src/views/user/MyFavoritesView.test.ts`（1/1）和 `npm run build`；已验证详情入口及取消收藏后刷新列表。
- T31 已通过 `npm run test:unit -- src/api/users.test.ts`（5/5）和 `npm run build`；已验证下载记录 Token 与分页参数。
- 当前执行 T32：我的下载页面。

## 2. 进度状态说明

| 状态 | 含义 |
| --- | --- |
| 已完成 | 已有实际代码，并至少通过编译或测试验证 |
| 部分完成 | 有基础类、设计文档或部分支撑能力，但业务功能未完整落地 |
| 待开发 | 已在需求/接口/数据库/Redis 文档中设计，但当前代码尚未实现 |
| 暂未涉及 | 当前阶段没有代码实现，也不是当前模块范围 |

## 3. 文档状态

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `docs/01-requirements.md` | 已完成 | 项目背景、用户角色、功能需求、非功能需求、项目亮点 |
| `docs/02-business-flow.md` | 已完成 | 上传、审核、搜索、下载、收藏等核心业务流程和状态流转 |
| `docs/03-database-design.md` | 已完成 | MySQL 表结构、字段说明、索引、设计理由和知识点 |
| `docs/04-api-doc.md` | 已同步 | 认证、分类、文件上传、资料、审核、搜索、下载、收藏和两个排行榜查询接口均已按当前代码校准 |
| `docs/05-redis-design.md` | 已同步 | Redis Key、热度 ZSet、下载增量批次、Redisson 锁、all 榜重建与快照策略均已按真实实现记录 |
| `docs/modules/01-auth-development-process.md` | 已完成 | 用户认证模块开发记录 |
| `docs/modules/02-category-development-process.md` | 已完成 | 分类查询模块开发记录 |
| `docs/modules/03-file-upload-development-process.md` | 已完成 | 文件上传模块开发流程记录 |
| `docs/modules/04-resource-development-process.md` | 已完成 | 资料模块首版开发流程、测试记录和后续优化记录 |
| `docs/modules/05-audit-development-process.md` | 已完成 | 审核模块开发流程、真实接口、状态机、权限、测试记录和后续优化 |
| `docs/modules/06-search-development-process.md` | 已完成 | 搜索模块开发流程、真实接口、排序白名单、Redis 热词统计、测试记录和后续优化 |
| `docs/modules/08-favorite-development-process.md` | 已完成 | 收藏模块真实接口、MySQL 幂等、Redis Set、一致性策略和跳过专项测试记录 |
| `docs/modules/09-rank-development-process.md` | 已完成 | 排行榜查询、热度联动、下载同步、all 榜重建、快照、测试和文档记录 |
| `docs/database/database-change-log.md` | 已同步 | 记录认证、分类、文件上传、资料、审核、搜索、下载和收藏模块均复用已有生产表结构 |
| `README.md` | 已同步 | 启动说明、当前完成模块、测试命令和下一阶段建议 |
| `docs/frontend/01-frontend-requirements.md` 至 `05-frontend-task-queue.md` | 已完成 | 前端演示需求、页面、API 映射、开发计划和自动任务队列 |

## 4. 数据库与脚本状态

| 文件 | 状态 | 说明 |
| --- | --- | --- |
| `sql/init.sql` | 已完成 | MySQL 8.x 初始化脚本，包含核心业务表 |
| `campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql` | 已完成 | 资料模块 H2 集成测试和审核模块本机 MySQL 集成测试使用的最小表结构，不属于生产库变更 |

`sql/init.sql` 当前表使用状态：

| 表名 | 设计状态 | 当前代码使用状态 |
| --- | --- | --- |
| `user` | 已设计 | 已被认证模块使用 |
| `category` | 已设计 | 已被分类查询模块和资料模块使用 |
| `file_info` | 已设计 | 已被文件上传模块和资料模块使用 |
| `resource` | 已设计 | 已被资料、审核、搜索、下载和收藏模块使用 |
| `favorite` | 已设计 | 已被收藏模块使用 |
| `download_record` | 已设计 | 已被下载模块使用 |
| `audit_record` | 已设计 | 已被审核模块使用 |

## 5. 后端基础能力

| 模块 | 状态 | 已实现内容 |
| --- | --- | --- |
| Spring Boot 工程骨架 | 已完成 | Maven Wrapper、启动类、标准分层包结构 |
| 基础依赖 | 已完成 | Web、Validation、MyBatis、MySQL、Redis、Redisson、JWT、BCrypt、Lombok、测试 H2 |
| 配置文件 | 已完成 | 端口、MySQL、Redis、MyBatis、JWT、上传目录配置 |
| 统一响应 | 已完成 | `ApiResponse<T>` 统一返回 `code/message/data/traceId` |
| 统一错误码 | 已完成 | `ErrorCode` 维护业务错误码 |
| 全局异常处理 | 已完成 | 业务异常、参数校验异常、JSON 解析异常、上传异常、兜底异常 |
| MyBatis 配置 | 已完成 | Mapper 扫描、XML 映射配置 |
| Web 配置 | 已完成 | CORS、JWT 拦截器路径配置、公开接口排除 |
| 分页模型 | 已完成 | `PageQuery` 和 `PageResult` |
| 健康检查 | 已完成 | `GET /api/v1/health`，无需登录 |

## 6. 已完成业务模块

### 6.1 用户认证模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 用户注册 | 已完成 | `POST /api/v1/auth/register`，密码 BCrypt 加密 |
| 用户登录 | 已完成 | `POST /api/v1/auth/login`，签发 JWT |
| 用户退出 | 已完成 | `POST /api/v1/auth/logout`，写入 Redis Token 黑名单 |
| 当前用户 | 已完成 | `GET /api/v1/users/me`，从登录上下文查询 |
| JWT 鉴权 | 已完成 | 拦截 `/api/v1/**` 受保护接口 |
| Redis 黑名单 | 已完成 | `crp:auth:token:blacklist:{jti}` |

涉及表：`user`。

### 6.2 分类查询模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 启用分类列表 | 已完成 | `GET /api/v1/categories?parentId=0`，无需登录 |
| 分类查询 Service | 已完成 | 校验 `parentId`，只返回 `status = 1` 分类 |
| 分类 VO | 已完成 | 不直接返回 `Category` Entity |

涉及表：`category`。

### 6.3 文件上传模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 文件上传 | 已完成 | `POST /api/v1/files`，保存本地文件并写入 `file_info` |
| MD5 预检 | 已完成 | `GET /api/v1/files/check` |
| 秒传 | 已完成 | 基于 `file_md5 + file_size` 复用已有文件记录 |
| Redis MD5 缓存 | 已完成 | `crp:cache:file:md5:{fileMd5}:{fileSize}` |
| 上传补偿 | 已完成 | 数据库写入失败时清理已保存文件 |

涉及表：`file_info`。

### 6.4 资料模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 创建资料 | 已完成 | `POST /api/v1/resources`，需要登录，创建后 `status = 0` |
| 公开资料详情 | 已完成 | `GET /api/v1/resources/{resourceId}`，匿名访问，只返回审核通过资料 |
| 我的上传列表 | 已完成 | `GET /api/v1/users/me/resources`，需要登录，支持状态筛选和分页 |
| 资料实体与 Mapper | 已完成 | `Resource`、`ResourceMapper`、`ResourceMapper.xml` |
| 文件/分类校验 | 已完成 | `FileInfoMapper.selectNormalById`、`CategoryMapper.selectEnabledById` |
| 重复提交拦截 | 已完成 | 同一用户、同一文件、待审核或已通过资料不允许重复提交 |
| 标签清洗 | 已完成 | 去空白、去重、保序后写入 `resource.tags` |
| 鉴权路径 | 已完成 | 仅放行 `/api/v1/resources/*`，不放行创建接口 |

涉及表：`resource`、`file_info`、`category`。

当前资料模块暂未使用 Redis，后续可接入 `crp:cache:resource:detail:{resourceId}`。

### 6.5 审核模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 待审核资料列表 | 已完成 | `GET /api/v1/admin/resources/pending-reviews`，管理员查询 `status = 0` 资料，支持课程名、类型、上传者和分页筛选 |
| 审核通过 | 已完成 | `POST /api/v1/admin/resources/{resourceId}/audit-approvals`，待审核变为已通过，写入 `approved_at` 和审核记录 |
| 审核拒绝 | 已完成 | `POST /api/v1/admin/resources/{resourceId}/audit-rejections`，待审核变为已拒绝，写入拒绝原因和审核记录 |
| 下架资料 | 已完成 | `POST /api/v1/admin/resources/{resourceId}/offline-records`，已通过变为已下架，写入下架原因、下架时间和审核记录 |
| 审核记录查询 | 已完成 | `GET /api/v1/admin/resources/{resourceId}/audit-records`，按资料 ID 查询审核流水 |
| 管理员权限 | 已完成 | JWT 拦截器保证登录，`AuditServiceImpl.requireAdmin()` 校验 `role = 2` |
| 事务一致性 | 已完成 | 审核通过、拒绝、下架使用 `@Transactional(rollbackFor = Exception.class)` |
| 并发兜底 | 已完成 | 状态更新 SQL 带旧状态条件，影响行数为 0 时返回非法状态流转 |

涉及表：`resource`、`audit_record`。

当前审核模块暂未使用 Redis；后续资料详情缓存上线后，需要在审核通过、拒绝、下架时删除 `crp:cache:resource:detail:{resourceId}`。

### 6.6 搜索模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 搜索资料 | 已完成 | `GET /api/v1/search/resources`，匿名可访问，强制只返回 `status = 1 APPROVED` 资料 |
| 筛选与排序 | 已完成 | 支持关键词、分类、课程名、资料类型、标签筛选；排序字段走白名单（`createdAt`/`downloadCount`/`favoriteCount`/`hotScore`），方向 `asc`/`desc` |
| 参数校验 | 已完成 | DTO Bean Validation + `SearchServiceImpl` 兜底校验，非法参数返回 `40001` |
| 结果 VO | 已完成 | 返回 `PageResult<SearchResourceVO>`，不直接暴露 `Resource` Entity |
| 热门搜索词统计 | 已完成 | 搜索成功后对 `daily`/`weekly`/`monthly` 三个 ZSet `ZINCRBY +1` 并设 2/14/60 天 TTL |
| Redis 降级 | 已完成 | 空关键词不写入；`ObjectProvider` 可选注入，Redis 缺失或异常时跳过统计、不阻断搜索 |
| 搜索接口测试 | 待补充 | `SearchControllerTest` 与热词 Service 单测尚未编写，见模块流程文档待完成事项 |

涉及表：`resource`。

涉及 Redis Key：`crp:rank:search:keyword:{period}`（`period` 取 `daily`/`weekly`/`monthly`）。

首版基于 MySQL 模糊查询，未接入 Elasticsearch；未实现搜索建议接口和搜索限流。详见 `docs/modules/06-search-development-process.md`。

### 6.7 下载模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 创建下载记录 | 已完成 | `POST /api/v1/resources/{resourceId}/download-records`，需登录，限流 → 状态校验 → 写记录 → 去重计数 |
| 下载文件流 | 已完成 | `GET /api/v1/download-records/{downloadRecordId}/file`，需登录，归属校验后返回文件二进制流 |
| 我的下载记录 | 已完成 | `GET /api/v1/users/me/download-records`，需登录，只查当前用户，分页返回 |
| Redis 下载限流 | 已完成 | ZSet 滑动窗口 + Lua 原子脚本，按用户（10次/分）和 IP（30次/分）限流 |
| 下载去重 | 已完成 | Redis `SETNX` + TTL 10分钟，同用户同资料去重期内不重复计入下载量 |
| 下载量增量统计 | 已完成 | `HINCRBY crp:stats:resource:download:delta`，不设 TTL，由排行榜定时任务安全同步到 MySQL |
| 文件流读取 | 已完成 | `FileStorageService.loadAsResource`，含路径穿越防护、文件存在和可读校验 |
| 模块测试 | 待补充 | 下载模块针对性测试尚未编写，当前通过全量 49 个已有测试无回归 |

涉及表：`download_record`、`resource`、`file_info`。

涉及 Redis Key：`crp:rate:download:user:{userId}`、`crp:rate:download:ip:{ip}`、`crp:dedup:download:{userId}:{resourceId}`、`crp:stats:resource:download:delta`。

首版未实现：下载地址过期机制。下载热度 ZSet 联动与 Redis→MySQL 定时同步已由排行榜与定时任务模块完成。详见 `docs/modules/07-download-development-process.md`。

### 6.8 收藏模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 收藏资料 | 已完成 | `POST /api/v1/resources/{resourceId}/favorites`，仅允许收藏 `APPROVED` 资料，重复请求按幂等成功返回 |
| 取消收藏 | 已完成 | `DELETE /api/v1/resources/{resourceId}/favorites`，软更新 `favorite.status` 并原子减少收藏数 |
| 我的收藏列表 | 已完成 | `GET /api/v1/users/me/favorites`，只查询当前用户有效收藏，按收藏时间倒序分页返回 |
| 收藏状态查询 | 已完成 | `GET /api/v1/resources/{resourceId}/favorite-status`，优先 Redis Set，缓存缺失时从 MySQL 重建 |
| 并发与事务 | 已完成 | MySQL 唯一索引兜底重复收藏；`TransactionTemplate` 保证收藏关系和 `favorite_count` 同一事务提交 |
| Redis 缓存 | 已完成 | `crp:user:favorites:{userId}`，Set + 30 分钟 TTL；Redis 故障仅降级，不阻断主流程 |
| 模块专项测试 | 已跳过 | 用户明确要求跳过步骤 7；当前全量 49 个既有测试通过，但未覆盖收藏模块专项场景 |

涉及表：`favorite`、`resource`。

涉及 Redis Key：`crp:user:favorites:{userId}`。

首版未实现：收藏夹/分组、批量取消收藏。真实收藏/取消收藏已在 MySQL 事务提交后联动排行榜 ZSet `+3/-3`；接口响应中的 `hotScoreDelta` 仍保留为兼容字段 `0`。详见 `docs/modules/08-favorite-development-process.md`。

## 7. 测试与验证

| 类型 | 状态 | 说明 |
| --- | --- | --- |
| 编译验证 | 已完成 | `.\mvnw.cmd -DskipTests compile` 已通过 |
| 全量测试 | 已完成 | `.\mvnw.cmd test` 已通过，共 49 个测试 |
| Controller 测试 | 已完成 | `ResourceControllerTest` 共 11 个用例，`AuditControllerTest` 共 9 个用例，覆盖接口层、鉴权路径、参数校验和异常映射 |
| 数据库集成测试 | 已完成 | `ResourceDatabaseIntegrationTest` 共 7 个用例，`AuditServiceDatabaseIntegrationTest` 共 9 个用例；审核数据库测试使用本机 MySQL 独立测试库 |
| Spring 上下文测试 | 已完成 | `CampusResourcePlatformApplicationTests.contextLoads` |
| 收藏模块专项测试 | 已跳过 | 用户明确要求跳过步骤 7；尚未新增 `FavoriteControllerTest` 与收藏数据库集成测试 |
| Postman 集合 | 已更新 | `postman/campus-resource-platform.postman_collection.json` 已新增资料模块分组 |

已验证的资料模块场景：

| 场景 | 覆盖方式 |
| --- | --- |
| 创建资料成功并写入 `resource` | 数据库集成测试 |
| `ResourceMapper.insert` 自增 ID 回填 | 数据库集成测试 |
| 公开详情只返回 `APPROVED` 资料 | Controller 测试 + 数据库集成测试 |
| 我的上传分页和状态筛选 | Controller 测试 + 数据库集成测试 |
| 未登录创建资料/查询我的上传被拦截 | Controller 测试 |
| 文件不存在、分类不存在、重复提交 | Controller 测试 + 数据库集成测试 |
| 已删除文件、禁用分类不可引用 | 数据库集成测试 |

已验证的审核模块场景：

| 场景 | 覆盖方式 |
| --- | --- |
| 管理员查询待审核资料并按条件筛选 | Controller 测试 + 数据库集成测试 |
| 未登录访问审核接口返回 `40101` | Controller 测试 |
| 普通用户访问审核接口返回 `40301` | Controller 测试 + 数据库集成测试 |
| 审核通过待审核资料并写入 `audit_record` | 数据库集成测试 |
| 审核拒绝待审核资料并写入拒绝原因 | 数据库集成测试 |
| 下架已通过资料并写入下架原因和时间 | 数据库集成测试 |
| 资料不存在返回 `40401` | Controller 测试 + 数据库集成测试 |
| 非法状态流转返回 `40901` | Controller 测试 + 数据库集成测试 |
| 审核记录查询按资料返回历史流水 | Controller 测试 + 数据库集成测试 |
| 审核记录写入失败时回滚资料状态更新 | 本机 MySQL 数据库集成测试 |

## 8. 待开发模块

> 搜索模块首版已完成，见第 6.6 节；剩余待补充为搜索接口测试、搜索建议接口、搜索限流和 Elasticsearch 扩展。

### 8.1 下载模块

**已完成**，见第 6.7 节。剩余待补充为下载模块针对性测试、下载量 Redis→MySQL 定时同步、下载地址过期机制和热度 ZSet 联动（后两者归排行榜与定时任务模块）。

### 8.2 收藏模块

**已完成首版**，见第 6.8 节。收藏模块专项测试按用户要求跳过；热度 ZSet 联动、收藏夹/分组和批量取消收藏仍为后续优化。

### 8.3 排行榜与定时任务

已完成：热门资料排行榜、热门搜索词排行榜、下载量增量同步、Redisson 看门狗读写锁、all 总榜缺失重建、管理员手动重建，以及 `resource.hot_score` 定时快照回写。

待实现功能：排行榜 Mapper 集成测试与任务运行指标。

涉及表：`resource`。

涉及 Redis Key：`crp:rank:resource:hot:{period}`、`crp:rank:search:keyword:{period}`、`crp:stats:resource:download:delta`、`crp:lock:sync:hot-rank-maintenance`。

## 9. 当前未实现的重点功能清单

| 功能 | 当前状态 | 备注 |
| --- | --- | --- |
| 资料搜索 | 已完成 | `GET /api/v1/search/resources` 首版基于 MySQL，见第 6.6 节 |
| 热门搜索词写入 | 已完成 | 搜索成功后写入 `crp:rank:search:keyword:{period}` 三周期 ZSet |
| 热门搜索词排行榜查询 | 未实现 | 归排行榜模块，读取搜索热词 ZSet |
| 搜索接口测试 | 未实现 | `SearchControllerTest` 与热词 Service 单测待补充 |
| 搜索建议接口 | 未实现 | `GET /api/v1/search/suggestions` 后续任务 |
| 搜索限流 | 未实现 | `42901` 为设计预留错误码 |
| 下载接口 | 已完成 | `POST` 创建下载记录、`GET` 文件流、`GET` 我的下载记录，见第 6.7 节 |
| 下载限流 | 已完成 | Redis ZSet 滑动窗口 + Lua，用户 10次/分、IP 30次/分 |
| 下载量定时同步 | 已完成 | `RankingSyncTask` 定时触发，Redisson 看门狗锁、`RENAME` 批次隔离、MySQL 事务累加和成功后 `HDEL` 确认 |
| 收藏资料 | 已完成 | 收藏、取消、状态查询和我的收藏列表已实现；专项测试按用户要求跳过 |
| 热门资料排行榜 | 已完成 | 支持 Redis ZSet 查询、MySQL 降级和下载/收藏/审核热度联动 |
| 总榜重建与热度快照 | 已完成 | all 榜缺失时游标分页重建，临时 ZSet 原子替换；重建写锁与实时 all 榜读锁协调；每 5 分钟分批回写 `resource.hot_score` |
| 管理员手动重建总榜 | 已完成 | `POST /api/v1/admin/rankings/resources/hot/rebuild`，Service 校验管理员角色后触发重建 |
| 管理员用户管理 | 未实现 | 当前只有用户角色字段，没有管理员业务接口 |
| 注解式权限控制 | 未实现 | 当前只通过 JWT 拦截器完成登录校验 |
| 登录限流 | 未实现 | `ErrorCode.RATE_LIMITED` 已存在，但认证模块未使用 |
| Refresh Token | 未实现 | 当前只有 Access Token |
| Elasticsearch | 未实现 | 属于可选扩展技术栈 |
| RocketMQ | 未实现 | 属于可选扩展技术栈 |

## 10. 当前项目亮点沉淀

- 物理文件与业务资料解耦：文件上传只产生 `fileId`，资料模块再创建 `resource`。
- 资料默认待审核：避免未审核资料进入公开搜索、下载和收藏链路。
- JWT 不是只签发不管理：通过 `jti` 和 Redis 黑名单实现退出登录后 Token 立即失效。
- 文件上传支持 MD5 秒传：数据库唯一索引和 Redis 缓存共同支撑去重。
- 资料创建有真实业务校验：文件状态、分类状态、上传者身份、重复提交、标签长度都在 Service 层兜底。
- 数据库集成测试已落地：资料模块真实执行 MyBatis XML，验证写入和读取。
- 审核模块不是简单改状态：通过状态机、旧状态条件 SQL、事务和 `audit_record` 审计流水保证可追溯。
- 管理员权限做了双层边界：JWT 拦截器保证登录，Service 层基于 `LoginUser.role = 2` 兜底校验。
- 搜索公开可见性隔离：搜索 SQL 固定追加 `status = 1`，不依赖前端传入状态，防止未审核资料泄露。
- 搜索排序防注入：排序字段走后端白名单映射为固定列名，不把前端原始参数拼进 SQL。
- 热门搜索词统计是可降级旁路：`ObjectProvider` 可选注入 + 异常吞掉 + 空词跳过，Redis 故障不影响搜索主流程。
- 下载限流不是固定窗口：ZSet 滑动窗口 + Lua 原子脚本，比固定窗口计数更平滑，且同时按用户和 IP 双维度拦截。
- 下载量统计不是直接 UPDATE：先写 Redis Hash 增量，解耦高频写和 MySQL 压力，后续定时任务批量回写，Redis 异常时 fail-open 不阻断下载。
- 文件流下载不走 JSON 包装：直接返回 `ResponseEntity<InputStreamResource>`，含路径穿越防护和 RFC 5987 中文文件名编码。
- 下载去重区分”允许下载”和”计入统计”：`SETNX` 去重 Key（TTL 10分钟），重复下载允许但不重复计入下载量和热度。
- 收藏不是简单插入：资料状态校验、软状态复用、唯一索引幂等、收藏数原子更新和 Redis Set 缓存共同保证正确性与性能。
- 收藏事务与缓存分层：`TransactionTemplate` 只包裹 MySQL 的 `favorite` 与 `favorite_count` 写入；提交成功后再同步 Redis，缓存异常不阻断主流程。
- 分层结构清晰：Controller、Service、Mapper、DTO、VO、Entity、Common、Config、Exception、Interceptor 各自承担边界。

## 11. 推荐下一阶段开发模块

建议下一阶段优先开发“排行榜与定时任务模块”。

原因：

1. 下载和收藏模块已累计下载量、收藏数等热度输入，具备建设实时排行榜的数据基础。
2. `crp:stats:resource:download:delta` 已有增量数据，适合通过定时任务批量回写 MySQL，降低高频 `UPDATE` 压力。
3. 热门资料 ZSet、热门搜索词 ZSet 和 `resource.hot_score` 的设计已具备，下一步可以统一治理周期、TTL 与回写策略。
4. 收藏模块专项测试被明确跳过，后续可在排行榜开发前或并行补齐，降低联合功能回归风险。

推荐小步开发顺序：

1. 实现热门资料排行榜读取与资料热度 ZSet 初始化。
2. 接入下载、收藏行为的热度增量，并定义 daily/weekly/monthly/all 周期策略。
3. 实现下载量 Redis Hash 到 MySQL 的定时批量同步与防重复执行。
4. 补充排行榜、定时任务以及收藏模块的专项测试。

搜索模块剩余小步任务（可穿插补齐）：补充 `SearchControllerTest` 和热词 Service 单测、实现搜索建议接口、增加搜索限流。下载模块剩余任务：补充针对性测试；收藏模块专项测试已按用户要求跳过。

## 12. 当前可提交总结

```text
docs(favorite): sync favorite module documentation

- align favorite API and Redis documents with real controller, service and Mapper behavior
- record favorite module implementation status and skipped targeted tests
- update project progress and README with the completed favorite module
- recommend ranking and scheduled-task module as the next stage
```
