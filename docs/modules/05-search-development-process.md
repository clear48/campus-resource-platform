# 搜索模块开发流程文档

> 本文档遵循 `docs/AGENTS.md` 第 24 节《模块开发流程文档规范》生成。
> 当前状态：**步骤 6 已完成，搜索接口 `GET /api/v1/search/resources` 已实现并通过全量测试**。搜索模块基于审核模块产生的 `resource.status = 1 APPROVED` 资料集合，提供公开资料检索能力，并通过 Redis ZSet 记录热门搜索词。

---

## 1. 模块基本信息

| 项 | 内容 |
| --- | --- |
| 模块名称 | 搜索模块 |
| 英文标识 | search |
| 文档路径 | `docs/modules/05-search-development-process.md` |
| 当前分支 | `dev` |
| 当前状态 | 步骤 6 已完成，搜索接口 `GET /api/v1/search/resources` 已实现并通过全量测试 |
| 前置依赖模块 | 用户认证模块、分类查询模块、资料模块、审核模块 |
| 下游模块 | 下载模块、收藏模块、排行榜模块 |
| 接口前缀 | `/api/v1/search` |

---

## 2. 模块目标

搜索模块负责为游客和登录用户提供公开资料检索入口，只展示已经审核通过的资料。

首版目标：

- 实现 `GET /api/v1/search/resources` 资料搜索接口。
- 强制只查询 `resource.status = 1 APPROVED` 的资料。
- 支持关键词、分类、课程名、资料类型、标签筛选。
- 支持分页和排序，排序字段使用白名单，避免 SQL 注入。
- 返回 `PageResult<SearchResourceVO>`，不直接暴露 `Resource` Entity。
- 搜索成功后，将非空关键词写入 Redis 热门搜索词排行榜。
- 首版使用 MySQL 模糊查询，不引入 Elasticsearch。

---

## 3. 需求分析

1. 审核模块已经能把待审核资料推进到 `APPROVED`，搜索模块的输入集合已经明确。
2. 普通搜索是下载、收藏、排行榜之前的公开消费入口，必须保证未审核、已拒绝、已下架、已删除资料不会泄露。
3. 首版数据量可控，使用 MySQL `LIKE` 查询足够满足项目演示和面试讲解；后续再通过接口抽象切换 Elasticsearch。
4. 搜索关键词天然适合用 Redis ZSet 做热词统计，可以为后续热门搜索词排行榜和搜索建议接口铺路。
5. 搜索排序字段来自前端输入，必须走后端白名单映射，不能把请求参数直接拼进 SQL。

为什么不是简单 CRUD：搜索模块的核心是公开可见性隔离、动态筛选、排序安全、分页性能、Redis 热词统计以及后续搜索引擎可替换设计。

---

## 4. 本模块不做什么

- 首版不接入 Elasticsearch。
- 首版不实现全文分词、高亮、拼音搜索、同义词搜索。
- 首版不实现搜索建议接口，`GET /api/v1/search/suggestions` 作为后续小步任务。
- 首版不实现热门搜索词排行榜查询接口，该能力归排行榜模块。
- 首版不实现下载、收藏、资料详情缓存。
- 首版不新增生产数据库表，不修改 `sql/init.sql` 表结构。
- 首版不让管理员审核列表复用普通搜索接口，避免后台资料状态泄露。
- 首版不对搜索接口做登录强制校验；公开搜索由状态过滤保证安全。

---

## 5. 涉及接口

> 5.1 搜索资料接口已由 `SearchController` 实现（`GET /api/v1/search/resources`）；5.2 搜索建议接口仍为后续任务，尚未实现。`docs/04-api-doc.md` 待按真实代码同步校准。

### 5.1 搜索资料

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/search/resources` |
| 是否登录 | 否 |
| 权限要求 | 无，服务端强制只返回 `APPROVED` 资料 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 否 | 搜索关键词，匹配标题、简介、课程名、标签 |
| `categoryId` | long | 否 | 分类 ID |
| `courseName` | string | 否 | 课程名称 |
| `resourceType` | int | 否 | 资料类型 |
| `tag` | string | 否 | 标签 |
| `sortBy` | string | 否 | 排序字段：`createdAt`、`downloadCount`、`favoriteCount`、`hotScore` |
| `order` | string | 否 | 排序方向：`asc` 或 `desc`，默认 `desc` |
| `pageNo` | int | 否 | 页码，默认 1 |
| `pageSize` | int | 否 | 每页数量，默认 10，最大 100 |

响应数据：`PageResult<SearchResourceVO>`。

### 5.2 获取搜索建议

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/search/suggestions` |
| 是否登录 | 否 |
| 当前状态 | 后续任务，首版不实现 |

说明：搜索建议可从热门搜索词 Redis ZSet 或 MySQL 课程名生成。为了保持小步开发，首版先完成资料搜索主链路。

---

## 6. 涉及数据库表

### 6.1 `resource` 资料表

搜索模块主要读取以下字段：

| 字段 | 使用场景 |
| --- | --- |
| `id` | 返回资料 ID |
| `title` | 关键词匹配和结果展示 |
| `description` | 关键词匹配和结果展示 |
| `category_id` | 分类筛选 |
| `course_name` | 课程筛选、关键词匹配和结果展示 |
| `resource_type` | 资料类型筛选和结果展示 |
| `tags` | 标签筛选、关键词匹配和结果展示 |
| `status` | 强制过滤 `APPROVED` |
| `download_count` | 结果展示和排序 |
| `favorite_count` | 结果展示和排序 |
| `hot_score` | 结果展示和排序 |
| `created_at` | 结果展示和排序 |

可利用现有索引：

| 索引 | 作用 |
| --- | --- |
| `idx_resource_status_created` | 按审核状态过滤并按创建时间排序 |
| `idx_resource_category_status` | 按分类筛选审核通过资料 |
| `idx_resource_course_status` | 按课程名和状态筛选资料 |
| `idx_resource_hot` | 按状态过滤后做热度、下载量兜底排序 |

### 6.2 `category` 分类表

首版搜索结果可只返回 `categoryId`，也可通过 `CategoryMapper.selectEnabledById` 或后续联表补充 `categoryName`。

为了控制首版范围，建议先不联表查询分类名称；如果搜索结果需要展示分类名称，应在 Mapper SQL 中明确关联 `category` 且只读取启用分类。

---

## 7. 涉及 Redis Key

搜索模块首版涉及热门搜索词统计：

| Key | 类型 | 用途 | TTL |
| --- | --- | --- | --- |
| `crp:rank:search:keyword:daily` | ZSet | 当日热门搜索词 | 2 天 |
| `crp:rank:search:keyword:weekly` | ZSet | 本周热门搜索词 | 14 天 |
| `crp:rank:search:keyword:monthly` | ZSet | 本月热门搜索词 | 60 天 |

Member：归一化后的搜索关键词。

Score：搜索次数。

更新时机：

- 搜索接口参数校验通过，且关键词非空时更新。
- Redis 统计失败不阻断 MySQL 搜索主流程，可记录日志后继续返回搜索结果。

一致性策略：

- 热门搜索词属于运营统计数据，首版可只保存在 Redis。
- Redis 丢失不影响资料搜索正确性，只影响热门词统计和后续排行榜展示。
- Key 必须集中定义在 `RedisKeyConstants`，业务代码禁止硬编码。

当前真实代码状态：

- `RedisKeyConstants` 已有 Token 黑名单、文件 MD5 缓存以及搜索热词 Key。
- `crp:rank:search:keyword:{period}` 常量 `SEARCH_KEYWORD_RANK` 和格式化方法 `searchKeywordRank(String period)` 已实现。
- `SearchServiceImpl` 已接入 `StringRedisTemplate`，搜索成功后对 `daily`、`weekly`、`monthly` 三个 ZSet 执行 `ZINCRBY +1` 并设置 2/14/60 天 TTL；关键词为空或 Redis 不可用时跳过，异常时记录日志且不阻断搜索主流程。

---

## 8. 涉及核心类

### 8.1 复用已存在类

| 类型 | 类 | 作用 |
| --- | --- | --- |
| common | `ApiResponse` | 统一响应 |
| common | `ErrorCode` | 统一错误码 |
| common | `PageResult` | 分页响应 |
| dto | `PageQuery` | 通用分页请求 |
| entity | `Resource` | 资料实体和状态常量 |
| mapper | `ResourceMapper` | 后续补充搜索 SQL |
| common | `RedisKeyConstants` | 后续补充搜索热词 Key |
| exception | `BusinessException`、`GlobalExceptionHandler` | 业务异常和统一异常处理 |
| config | `WebMvcConfig` | 已放行 `/api/v1/search/**` |

### 8.2 计划新增或修改类

| 类型 | 类 | 职责 |
| --- | --- | --- |
| dto | `SearchResourceQueryDTO` | 承载搜索筛选、排序和分页参数 |
| vo | `SearchResourceVO` | 搜索结果列表项 |
| service | `SearchService` | 搜索业务接口 |
| service/impl | `SearchServiceImpl` | 参数校验、关键词归一化、热词统计、搜索编排 |
| controller | `SearchController` | 搜索接口入口 |
| mapper | `ResourceMapper` + XML | 补充 `searchApprovedResources` 和 `countApprovedResources` |
| common | `RedisKeyConstants` | 补充搜索热词 Key 常量和格式化方法 |

---

## 9. 模块内部调用关系

```text
SearchController
  └── SearchService (SearchServiceImpl)
        ├── ResourceMapper（查询 APPROVED 资料列表和总数）
        ├── RedisKeyConstants（生成搜索热词 Key）
        ├── StringRedisTemplate（ZINCRBY 记录搜索关键词）
        └── PageResult（封装分页响应）
```

Controller 只负责接收请求、触发参数绑定和返回统一响应；可见性过滤、排序白名单、关键词归一化、Redis 统计降级都放在 Service 层。

---

## 10. 请求处理流程

### 10.1 搜索资料

1. `WebMvcConfig` 放行 `/api/v1/search/**`，游客可访问。
2. `SearchController` 接收查询参数并绑定为 `SearchResourceQueryDTO`。
3. `SearchServiceImpl` 校验分页、关键词长度、分类 ID、课程名、资料类型、排序字段、排序方向。
4. Service 对关键词做 trim 等归一化处理，空关键词不写入热词统计。
5. Service 将 `sortBy` 映射为固定 SQL 列名，禁止直接拼接前端原始值。
6. Service 调用 `ResourceMapper.countApprovedResources(...)` 统计总数。
7. Service 调用 `ResourceMapper.searchApprovedResources(...)` 查询当前页资料，SQL 固定追加 `status = 1`。
8. 搜索成功后，Service 使用 Redis ZSet 递增 daily、weekly、monthly 热词分数。
9. Redis 写入失败时不影响搜索结果返回。
10. Service 将 `Resource` 转换为 `SearchResourceVO`。
11. Controller 返回 `ApiResponse.success(PageResult<SearchResourceVO>)`。

---

## 11. 数据流转流程

```text
用户输入搜索条件
  → SearchController 参数绑定
  → SearchServiceImpl 参数校验与关键词归一化
  → ResourceMapper 固定 status = APPROVED 查询
  → Resource Entity 列表
  → SearchResourceVO 列表
  → PageResult 分页响应
  → Redis ZSet 记录非空关键词
```

状态隔离要求：

```text
PENDING_REVIEW(0) 不进入搜索结果
APPROVED(1)       可以进入搜索结果
REJECTED(2)       不进入搜索结果
OFFLINE(3)        不进入搜索结果
DELETED(4)        不进入搜索结果
```

---

## 12. 权限校验

- 搜索资料接口不要求登录。
- 搜索资料接口不能根据用户角色放宽状态条件，所有访问者都只能看到 `APPROVED` 资料。
- 管理员审核列表不能复用普通搜索接口。
- `/api/v1/search/**` 当前已经在 `WebMvcConfig.excludePathPatterns` 中放行。
- 安全核心在 Mapper 或 Service 层固定过滤 `Resource.STATUS_APPROVED`，不能依赖前端传入状态。

---

## 13. 参数校验

| 参数 | 规则 | 失败错误码 |
| --- | --- | --- |
| `keyword` | 可空；非空 trim 后建议最大 100 字符 | `40001` |
| `categoryId` | 可空；非空必须大于 0 | `40001` |
| `courseName` | 可空；非空 trim 后最大 100 字符 | `40001` |
| `resourceType` | 可空；非空必须是 1、2、3、4、5、99 | `40001` |
| `tag` | 可空；非空 trim 后最大 20 字符 | `40001` |
| `sortBy` | 可空；必须在 `createdAt`、`downloadCount`、`favoriteCount`、`hotScore` 中 | `40001` |
| `order` | 可空；必须是 `asc` 或 `desc`，大小写可归一化 | `40001` |
| `pageNo` | 大于等于 1 | `40001` |
| `pageSize` | 1 到 100 | `40001` |

默认值建议：

| 参数 | 默认值 |
| --- | --- |
| `sortBy` | `createdAt` |
| `order` | `desc` |
| `pageNo` | `1` |
| `pageSize` | `10` |

---

## 14. 异常处理

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 分页参数非法 | `40001 PARAM_ERROR` | 使用 `PageQuery` 和 Service 兜底校验 |
| 搜索关键词过长 | `40001 PARAM_ERROR` | 防止无意义长文本拖慢查询 |
| 分类 ID 非法 | `40001 PARAM_ERROR` | 小于等于 0 直接拒绝 |
| 资料类型非法 | `40001 PARAM_ERROR` | 必须命中 `Resource.TYPE_*` |
| 排序字段非法 | `40001 PARAM_ERROR` | 必须命中白名单 |
| 排序方向非法 | `40001 PARAM_ERROR` | 只能是 `asc` 或 `desc` |
| Redis 热词写入失败 | 不影响主响应 | 记录日志，搜索结果照常返回 |
| 数据库查询异常 | `50001 SERVER_ERROR` | 由全局异常兜底处理 |

首版暂不实现搜索限流；若后续补充限流，超限返回 `42901 RATE_LIMITED`。

---

## 15. 事务处理

搜索模块首版是读多写少场景，不需要 MySQL 事务。

原因：

- MySQL 只执行 `SELECT` 查询，不更新核心业务表。
- Redis 热词统计是运营统计，不要求与 MySQL 查询结果强事务一致。
- Redis 统计失败不影响搜索主流程。

如果后续实现搜索日志落库、搜索词快照表或用户行为记录，再按写入场景评估事务边界。

---

## 16. 核心实现步骤

1. 创建搜索模块开发流程文档初稿。
2. 创建 `SearchResourceQueryDTO` 和 `SearchResourceVO`。
3. 为 `RedisKeyConstants` 补充搜索热词 Key。
4. 为 `ResourceMapper` 补充只查询 `APPROVED` 资料的搜索 SQL 和计数 SQL。
5. 实现 `SearchService` 与 `SearchServiceImpl`。
6. 实现 `SearchController` 的 `GET /api/v1/search/resources`。
7. 补充 Controller 测试和数据库集成测试。
8. 同步 `docs/04-api-doc.md`、`docs/05-redis-design.md`、`docs/06-project-progress.md` 和 README。
9. 更新本模块开发流程文档。

---

## 17. 开发任务拆分

| 序号 | 任务 | 产出 |
| --- | --- | --- |
| T1 | 搜索文档初稿 | `docs/modules/05-search-development-process.md` |
| T2 | DTO/VO | `SearchResourceQueryDTO`、`SearchResourceVO` |
| T3 | Redis Key 常量 | `RedisKeyConstants.searchKeywordRank(period)` |
| T4 | Mapper 搜索 SQL | `searchApprovedResources`、`countApprovedResources` |
| T5 | Service 搜索编排 | 参数校验、排序白名单、关键词归一化、热词统计 |
| T6 | Controller | `GET /api/v1/search/resources` |
| T7 | 测试 | Controller 测试、数据库集成测试、Redis 降级测试 |
| T8 | 文档同步 | API、Redis、进度、README、模块流程文档 |

---

## 18. 已完成事项

- 已阅读 `AGENTS.md` 和 `docs/AGENTS.md`，确认模块开发流程文档规范。
- 已阅读 `docs/06-project-progress.md`，确认搜索模块是下一阶段推荐开发模块。
- 已阅读 `docs/04-api-doc.md` 搜索模块设计，确认首版主接口为 `GET /api/v1/search/resources`。
- 已阅读 `docs/05-redis-design.md` 热门搜索词设计，确认 Redis Key 为 `crp:rank:search:keyword:{period}`。
- 已阅读 `docs/02-business-flow.md` 搜索流程，确认普通搜索必须固定过滤 `APPROVED`。
- 已确认 `WebMvcConfig` 已放行 `/api/v1/search/**`。
- 已确认当前真实代码中尚无 `SearchController`、`SearchService` 和搜索 Mapper 方法。
- 已生成本搜索模块开发流程文档初稿。
- 已创建 `SearchResourceQueryDTO`，承载关键词、分类、课程名、资料类型、标签、排序和分页参数。
- 已创建 `SearchResourceVO`，作为搜索结果列表项，避免直接返回 `Resource` Entity。
- 已为 `RedisKeyConstants` 补充 `SEARCH_KEYWORD_RANK` 常量和 `searchKeywordRank(String period)` 方法。
- 已为 `ResourceMapper` 补充 `searchApprovedResources(...)` 和 `countApprovedResources(...)`。
- 已在 `ResourceMapper.xml` 中补充公开搜索 SQL，固定过滤 `status = 1`，并使用排序白名单避免直接拼接前端字段。
- 已补充 `ResourceDatabaseIntegrationTest` 搜索 Mapper 用例，覆盖状态隔离、筛选、分页和排序回退。
- 已创建 `SearchService` 和 `SearchServiceImpl`，完成参数兜底校验、排序白名单归一化、关键词 trim、Mapper 调用和 VO 转换。
- 已补充 `SearchServiceDatabaseIntegrationTest`，覆盖 Service 搜索链路、默认查询和非法参数。
- 已在 `SearchServiceImpl` 接入 Redis 热门搜索词统计：搜索成功后对 `daily`、`weekly`、`monthly` 三个 ZSet 递增关键词分数，并按 2/14/60 天设置 TTL，Key 统一走 `RedisKeyConstants.searchKeywordRank(period)`。
- 已通过 `ObjectProvider<StringRedisTemplate>` 将热词统计声明为可选依赖：Redis 未装配（如 `@MybatisTest` 切片）时跳过统计，避免破坏现有集成测试上下文。
- 已实现热词统计降级：空/空白关键词不写入，Redis 异常仅记录日志、不阻断搜索主流程。
- 已创建 `SearchController`，实现 `GET /api/v1/search/resources`，通过 `@Valid SearchResourceQueryDTO` 绑定查询参数，返回 `ApiResponse<PageResult<SearchResourceVO>>`。
- 已确认 `WebMvcConfig` 放行 `/api/v1/search/**`，接口匿名可访问，可见性由 Service 层固定 `APPROVED` 过滤保证。

---

## 19. 待完成事项

- 补充搜索模块 Controller 测试（`SearchControllerTest`）：覆盖公开访问、参数绑定、非法参数（`40001`）和异常映射，并确认匿名访问不触发 JWT 解析。
- 补充热词统计的 Service 层单元测试：验证非空关键词写入三周期 ZSet、空关键词不写入、Redis 异常降级不影响搜索结果。
- 同步 API 文档、Redis 文档、项目进度文档和 README。
- 搜索模块完成后更新本文档的测试记录、修改文件记录和已完成事项。

---

## 20. 测试清单

### 20.1 搜索资料接口

| 用例 | 预期 |
| --- | --- |
| 无条件搜索 | 只返回 `APPROVED` 资料，按默认创建时间倒序 |
| 关键词匹配标题 | 返回标题匹配且已通过资料 |
| 关键词匹配简介 | 返回简介匹配且已通过资料 |
| 关键词匹配课程名 | 返回课程名匹配且已通过资料 |
| 关键词匹配标签 | 返回标签匹配且已通过资料 |
| 分类筛选 | 只返回指定分类的已通过资料 |
| 课程名筛选 | 只返回指定课程的已通过资料 |
| 资料类型筛选 | 只返回指定类型的已通过资料 |
| 标签筛选 | 只返回包含指定标签的已通过资料 |
| 待审核资料存在 | 不出现在搜索结果 |
| 已拒绝资料存在 | 不出现在搜索结果 |
| 已下架资料存在 | 不出现在搜索结果 |
| 已删除资料存在 | 不出现在搜索结果 |

### 20.2 分页和排序

| 用例 | 预期 |
| --- | --- |
| `pageNo=1&pageSize=10` | 返回第一页和正确总数 |
| `pageNo=0` | 返回 `40001` |
| `pageSize=101` | 返回 `40001` |
| `sortBy=createdAt&order=desc` | 按创建时间倒序 |
| `sortBy=downloadCount` | 按下载次数排序 |
| `sortBy=favoriteCount` | 按收藏次数排序 |
| `sortBy=hotScore` | 按热度分排序 |
| 非法 `sortBy` | 返回 `40001` |
| 非法 `order` | 返回 `40001` |

### 20.3 Redis 热词统计

| 用例 | 预期 |
| --- | --- |
| 非空关键词搜索成功 | daily、weekly、monthly 三个 ZSet 分数递增 |
| 空关键词搜索 | 不写入 Redis |
| 空白关键词搜索 | trim 后为空，不写入 Redis |
| Redis 写入异常 | 搜索结果正常返回，不因热词统计失败中断 |

### 20.4 已执行测试记录

当前已完成搜索 DTO/VO、搜索热词 Redis Key 常量、搜索 Mapper SQL、搜索 Service 和流程文档更新；搜索接口入口尚未实现。

| 测试命令 | 结果 | 说明 |
| --- | --- | --- |
| `.\mvnw.cmd -Dtest=SearchServiceDatabaseIntegrationTest test` | 通过，`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` | 验证 SearchService 参数校验、默认查询、VO 转换和 Mapper 调用 |
| `.\mvnw.cmd -Dtest=ResourceDatabaseIntegrationTest test` | 通过，`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` | 验证新增搜索 Mapper SQL、状态隔离、筛选、分页和排序白名单回退 |
| `.\mvnw.cmd -DskipTests compile` | 通过 | 步骤 5 接入 Redis 后主代码编译通过 |
| `.\mvnw.cmd -Dtest=SearchServiceDatabaseIntegrationTest,ResourceDatabaseIntegrationTest test` | 通过，`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` | 步骤 5 改动后搜索相关集成测试全部通过，验证 ObjectProvider 可选注入不破坏切片上下文 |
| `.\mvnw.cmd test` | 通过，`Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` | 步骤 5 接入 Redis 热词统计后全量测试通过，无回归 |
| `.\mvnw.cmd -DskipTests compile` | 通过 | 步骤 6 新增 SearchController 后主代码编译通过 |
| `.\mvnw.cmd test` | 通过，`Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` | 步骤 6 新增 SearchController 后全量测试通过，Spring 上下文正常装配，无回归 |

> 说明：搜索接口 `SearchControllerTest` 与热词统计的 Service 层单元测试尚未编写，已列入待完成事项，将在步骤 7 补充。

---

## 21. 修改文件记录

当前已修改或新增：

| 文件 | 说明 |
| --- | --- |
| `docs/modules/05-search-development-process.md` | 新增搜索模块开发流程文档初稿 |
| `campus-resource-platform/src/main/java/com/john/campus/dto/SearchResourceQueryDTO.java` | 新增搜索请求参数 DTO，复用 `PageQuery` 分页默认值和校验 |
| `campus-resource-platform/src/main/java/com/john/campus/vo/SearchResourceVO.java` | 新增搜索结果 VO，返回公开展示字段和统计快照 |
| `campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java` | 新增搜索热词 ZSet Key 常量和生成方法 |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/ResourceMapper.java` | 新增公开搜索列表和计数 Mapper 方法 |
| `campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml` | 新增公开搜索过滤 SQL、计数 SQL 和排序白名单 |
| `campus-resource-platform/src/test/java/com/john/campus/service/ResourceDatabaseIntegrationTest.java` | 补充搜索 Mapper 数据库集成测试 |
| `campus-resource-platform/src/main/java/com/john/campus/service/SearchService.java` | 新增搜索业务接口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/SearchServiceImpl.java` | 新增搜索业务实现，完成校验、查询和 VO 转换；步骤 5 接入 Redis 热门搜索词统计（ObjectProvider 可选注入 + 三周期 ZSet 递增 + TTL + 异常降级） |
| `campus-resource-platform/src/test/java/com/john/campus/service/SearchServiceDatabaseIntegrationTest.java` | 新增搜索 Service 数据库集成测试 |
| `campus-resource-platform/src/main/java/com/john/campus/controller/SearchController.java` | 新增搜索接口入口，实现 `GET /api/v1/search/resources` |

后续预计修改或新增：

| 文件 | 说明 |
| --- | --- |
| `campus-resource-platform/src/test/java/com/john/campus/controller/SearchControllerTest.java` | 搜索 Controller 测试 |
| `docs/04-api-doc.md` | 搜索接口按真实代码同步 |
| `docs/05-redis-design.md` | 搜索热词实现状态同步 |
| `docs/06-project-progress.md` | 搜索模块进度同步 |
| `README.md` | 当前完成模块和测试命令同步 |

---

## 22. 与其他模块的关系

- 依赖资料模块：搜索模块读取 `resource` 表中的资料业务信息。
- 依赖审核模块：只有审核通过的 `APPROVED` 资料能进入搜索结果。
- 依赖分类模块：搜索可按 `categoryId` 筛选，分类数据由分类模块维护。
- 依赖 Redis 基础能力：搜索热词使用 Redis ZSet 统计。
- 支撑下载模块：用户通常先搜索资料，再进入下载流程。
- 支撑收藏模块：用户通常从搜索结果进入详情或收藏流程。
- 支撑排行榜模块：搜索热词 ZSet 后续可被排行榜模块读取。

---

## 23. 面试可讲点

- 普通搜索为什么必须强制过滤 `APPROVED`，如何避免未审核资料泄露。
- 动态排序为什么不能直接拼接前端字段，如何用白名单映射防 SQL 注入。
- 首版为什么选择 MySQL 模糊查询，而不是一开始就引入 Elasticsearch。
- 如何设计 Service 接口，让后续搜索实现可以从 MySQL 切换到 Elasticsearch。
- Redis ZSet 为什么适合做热门搜索词排行榜。
- Redis 热词统计失败为什么不能阻断搜索主流程。
- 搜索模块如何连接审核、下载、收藏、排行榜，体现项目不是普通 CRUD。

---

## 24. 后续优化方向

- 接入 Elasticsearch，实现分词、相关度排序、高亮和多字段权重。
- 实现 `GET /api/v1/search/suggestions` 搜索建议接口。
- 实现搜索限流，防止高频爬取和恶意刷热词。
- 增加敏感词过滤和无意义关键词过滤。
- 增加搜索日志或搜索词快照表，支持长期趋势分析。
- 审核通过后发送事件刷新搜索索引，下架后从搜索索引移除。
- 搜索结果增加分类名称、文件大小、上传者昵称等展示字段。
- 热词统计从固定 daily、weekly、monthly 扩展为可配置周期。

---

## 25. Git commit message 建议

```text
docs(search): add search module development process

- document search module scope and API plan
- record approved-resource visibility boundary
- outline Redis keyword ranking design
- add step-by-step prompts for future implementation
```

---

## 26. 分步骤开发提示词

> 使用说明：以下提示词按 `docs/AGENTS.md` 第 24 节要求拆分，每一步都是一个最小可执行任务。执行时请一次只复制一条提示词给 Agent，完成并验证后再进入下一步。

### 步骤 1：创建搜索 DTO 与 VO

提示词：

```text
请为搜索模块创建请求 DTO 和响应 VO。

本步目标：
- 创建 `campus-resource-platform/src/main/java/com/john/campus/dto/SearchResourceQueryDTO.java`。
- 创建 `campus-resource-platform/src/main/java/com/john/campus/vo/SearchResourceVO.java`。
- `SearchResourceQueryDTO` 承载 `keyword`、`categoryId`、`courseName`、`resourceType`、`tag`、`sortBy`、`order`、`pageNo`、`pageSize`。
- `SearchResourceVO` 返回 `resourceId`、`title`、`description`、`courseName`、`resourceType`、`tags`、`downloadCount`、`favoriteCount`、`hotScore`、`createdAt`。
- 字段注释说明参数用途、边界和安全意图。

涉及文件或类：
- `dto/SearchResourceQueryDTO.java`
- `vo/SearchResourceVO.java`
- `dto/PageQuery.java`（只参考分页限制，不修改）

完成标准：
- DTO 参数能覆盖 `docs/04-api-doc.md` 中 `GET /api/v1/search/resources` 的查询参数。
- VO 不直接暴露 `Resource` Entity。
- 分页字段默认值与现有 `PageQuery` 保持一致：`pageNo=1`、`pageSize=10`、最大 100。

本步不做什么：
- 不实现 Mapper、Service、Controller。
- 不修改数据库结构。
- 不接入 Redis。
```

### 步骤 2：补充搜索热词 Redis Key 常量

提示词：

```text
请为搜索模块补充 Redis 热门搜索词 Key 常量。

本步目标：
- 修改 `campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java`。
- 新增 `SEARCH_KEYWORD_RANK = "crp:rank:search:keyword:%s"`。
- 新增 `searchKeywordRank(String period)` 方法，统一生成完整 Key。
- 保留已有 Token 黑名单和文件 MD5 缓存 Key，不改变原有方法签名。

涉及文件或类：
- `common/RedisKeyConstants.java`
- `docs/05-redis-design.md`（只核对 Key 命名，不修改）

完成标准：
- 业务代码后续可以通过 `RedisKeyConstants.searchKeywordRank("daily")` 生成 `crp:rank:search:keyword:daily`。
- 不在 Service 中硬编码 Redis Key。
- 新增常量和方法有简洁中文注释，说明用途和数据结构。

本步不做什么：
- 不实现 Redis 写入逻辑。
- 不新增 Redis 配置。
- 不修改其他业务模块。
```

### 步骤 3：为 ResourceMapper 补充搜索 SQL

提示词：

```text
请为搜索模块增强 ResourceMapper 和 ResourceMapper.xml。

本步目标：
- 在 `ResourceMapper.java` 中新增 `searchApprovedResources(...)` 和 `countApprovedResources(...)`。
- 在 `ResourceMapper.xml` 中实现只查询 `status = 1 APPROVED` 的搜索 SQL。
- 支持关键词、分类、课程名、资料类型、标签筛选。
- 支持分页。
- 支持排序字段白名单映射后的安全列名和排序方向。

涉及文件或类：
- `mapper/ResourceMapper.java`
- `src/main/resources/mapper/ResourceMapper.xml`
- `entity/Resource.java`

完成标准：
- SQL 必须固定包含 `status = 1`，不能由前端传入状态。
- 关键词匹配范围至少包括 `title`、`description`、`course_name`、`tags`。
- `countApprovedResources` 与 `searchApprovedResources` 使用同一过滤条件。
- 排序字段不能直接使用前端原始参数拼接。

本步不做什么：
- 不实现 Service。
- 不实现 Controller。
- 不新增数据库表或索引。
```

### 步骤 4：实现 SearchService

提示词：

```text
请实现搜索模块的 Service 和 ServiceImpl。

本步目标：
- 创建 `service/SearchService.java`。
- 创建 `service/impl/SearchServiceImpl.java`。
- 实现 `PageResult<SearchResourceVO> searchResources(SearchResourceQueryDTO query)`。
- 校验关键词、分类 ID、课程名、资料类型、标签、分页、排序字段和排序方向。
- 将 `sortBy` 映射为安全 SQL 列名。
- 对关键词做 trim 归一化。
- 调用 ResourceMapper 查询 `APPROVED` 资料。
- 将 `Resource` 转换为 `SearchResourceVO`。

涉及文件或类：
- `service/SearchService.java`
- `service/impl/SearchServiceImpl.java`
- `dto/SearchResourceQueryDTO.java`
- `vo/SearchResourceVO.java`
- `mapper/ResourceMapper.java`
- `common/PageResult.java`
- `exception/BusinessException.java`
- `common/ErrorCode.java`

完成标准：
- 非法参数抛出 `BusinessException(ErrorCode.PARAM_ERROR, "...")`。
- 排序字段必须走白名单，默认 `createdAt desc`。
- Service 不返回 Entity。
- 首版不需要 MySQL 事务。

本步不做什么：
- 不实现 Controller。
- 不写 Redis 热词统计。
- 不接入 Elasticsearch。
```

### 步骤 5：接入 Redis 热词统计

提示词：

```text
请在 SearchServiceImpl 中接入 Redis 热门搜索词统计。

本步目标：
- 注入 `StringRedisTemplate`。
- 搜索成功且归一化关键词非空时，对 daily、weekly、monthly 三个 ZSet 执行分数递增。
- 使用 `RedisKeyConstants.searchKeywordRank(period)` 生成 Key。
- Redis 统计失败时记录日志或吞掉异常，不影响搜索结果返回。
- 为 daily、weekly、monthly Key 设置符合 `docs/05-redis-design.md` 的 TTL：2 天、14 天、60 天。

涉及文件或类：
- `service/impl/SearchServiceImpl.java`
- `common/RedisKeyConstants.java`
- `docs/05-redis-design.md`（只核对设计，不修改）

完成标准：
- 非空关键词搜索会更新 Redis ZSet。
- 空关键词或空白关键词不写 Redis。
- Redis 异常不会导致搜索接口失败。
- 业务代码不硬编码完整 Redis Key。

本步不做什么：
- 不实现热门搜索词排行榜查询接口。
- 不实现搜索建议接口。
- 不新增 MySQL 搜索词表。
```

### 步骤 6：实现 SearchController

提示词：

```text
请实现搜索模块 Controller。

本步目标：
- 创建 `controller/SearchController.java`。
- 实现 `GET /api/v1/search/resources`。
- 使用查询参数绑定 `SearchResourceQueryDTO`。
- 调用 `SearchService.searchResources(...)`。
- 返回 `ApiResponse<PageResult<SearchResourceVO>>`。

涉及文件或类：
- `controller/SearchController.java`
- `service/SearchService.java`
- `dto/SearchResourceQueryDTO.java`
- `vo/SearchResourceVO.java`
- `common/ApiResponse.java`
- `common/PageResult.java`

完成标准：
- Controller 不直接访问 Mapper。
- Controller 不写复杂业务逻辑。
- 接口路径与 `docs/04-api-doc.md` 保持一致。
- `/api/v1/search/**` 已在 `WebMvcConfig` 放行，无需新增登录要求。

本步不做什么：
- 不实现搜索建议接口。
- 不新增管理员搜索接口。
- 不修改资料模块已有接口路径。
```

### 步骤 7：补充搜索模块测试

提示词：

```text
请为搜索模块补充自动化测试。

本步目标：
- 新增或补充 `SearchControllerTest`，覆盖公开访问、参数绑定、非法参数和异常映射。
- 新增或补充 `SearchServiceDatabaseIntegrationTest`，使用真实 MyBatis XML 验证搜索 SQL。
- 覆盖只返回 `APPROVED` 资料，排除 `PENDING_REVIEW`、`REJECTED`、`OFFLINE`、`DELETED`。
- 覆盖关键词、分类、课程名、资料类型、标签筛选。
- 覆盖 `createdAt`、`downloadCount`、`favoriteCount`、`hotScore` 排序。
- 覆盖 Redis 写入成功、空关键词不写入、Redis 异常不影响主流程。

涉及文件或类：
- `src/test/java/com/john/campus/controller/SearchControllerTest.java`
- `src/test/java/com/john/campus/service/SearchServiceDatabaseIntegrationTest.java`
- `src/test/resources/sql/resource-db-test-schema.sql`（如需要补测试字段或数据）

完成标准：
- 至少运行 `.\mvnw.cmd test` 并记录结果。
- Controller 测试确认匿名访问搜索接口不触发 JWT 解析。
- 数据库集成测试确认搜索 SQL 不泄露非公开资料。

本步不做什么：
- 不测试下载、收藏、排行榜模块。
- 不引入 Elasticsearch 测试依赖。
- 不把数据库密码写入仓库。
```

### 步骤 8：同步搜索模块文档

提示词：

```text
请根据当前真实代码同步搜索模块相关文档。

本步目标：
- 更新 `docs/04-api-doc.md` 中搜索模块接口说明，确保请求参数、响应字段、错误码与真实代码一致。
- 更新 `docs/05-redis-design.md` 中热门搜索词实现状态，确认 Key、数据结构、TTL、降级策略。
- 更新 `docs/06-project-progress.md`，记录搜索模块已完成能力、测试结果和后续任务。
- 如 README 当前完成模块已落后，同步更新 README。

涉及文件：
- `docs/04-api-doc.md`
- `docs/05-redis-design.md`
- `docs/06-project-progress.md`
- `README.md`

完成标准：
- 文档不把搜索建议、排行榜、Elasticsearch 写成已完成，除非代码真实实现。
- Redis Key 使用 `crp:rank:search:keyword:{period}`，不得使用旧示例 `ranking:search:keyword:*`。
- 测试命令和结果要写清楚。

本步不做什么：
- 不修改 Java 业务代码。
- 不新增数据库结构。
- 不扩展下载、收藏、排行榜模块。
```

### 步骤 9：更新本模块开发流程文档

提示词：

```text
请根据当前真实代码更新搜索模块开发流程文档。

本步目标：
- 更新 `docs/modules/05-search-development-process.md`。
- 补全“当前状态”“已完成事项”“待完成事项”“测试清单”“修改文件记录”“与其他模块的关系”“面试可讲点”“后续优化方向”。
- 如果实现过程中接口、类名、方法名、字段名与规划不一致，以当前真实代码为准修正文档。
- 保留“分步骤开发提示词”小节，并根据实际开发顺序校准下一轮可复制提示词。

涉及文件：
- `docs/modules/05-search-development-process.md`

完成标准：
- 文档能回答：本模块解决什么问题、有哪些接口、调用链路是什么、涉及哪些表、是否使用 Redis、如何权限控制、如何测试。
- 文档不含“已规划但伪装成已完成”的内容。
- 文档记录本模块真实修改文件清单和测试结果。

本步不做什么：
- 不修改业务代码。
- 不扩展下载、收藏、排行榜模块。
- 不删除已有文档章节。
```
