# 项目进度文档

## 1. 当前阶段结论

项目当前处于“基础工程 + 用户认证模块 + 分类查询模块 + 文件上传模块 + 资料模块首版完成”阶段。

已经完成的核心能力包括：Spring Boot 后端基础骨架、统一响应与异常处理、JWT 鉴权、Redis Token 黑名单、用户注册/登录/退出登录/当前用户查询、公开分类查询、文件上传与 MD5 秒传、基于 `fileId` 创建资料、公开资料详情、我的上传资料分页查询，以及资料模块的 Controller 测试和数据库集成测试。

资料模块首版已经把 `file_info` 物理文件转换为 `resource` 业务资料主体，后续审核、搜索、下载、收藏、排行榜都可以围绕 `resource` 继续开发。下一阶段建议优先开发“审核模块”，消费 `status = 0 PENDING_REVIEW` 的资料并实现审核通过、审核拒绝、下架和审核记录。

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
| `docs/04-api-doc.md` | 已同步 | 认证、分类、文件上传、资料模块已按当前代码校准；审核等后续模块仍为设计接口 |
| `docs/05-redis-design.md` | 已完成，后续需同步 | Token 黑名单和文件 MD5 缓存已落地，排行榜/限流等仍为后续设计 |
| `docs/modules/module.md` | 已完成 | 用户认证模块开发记录 |
| `docs/modules/category-module.md` | 已完成 | 分类查询模块开发记录 |
| `docs/modules/02-file-upload-development-process.md` | 已完成 | 文件上传模块开发流程记录 |
| `docs/modules/03-resource-development-process.md` | 已完成 | 资料模块首版开发流程、测试记录和后续优化记录 |
| `docs/database/database-change-log.md` | 已同步 | 记录认证、分类、文件上传、资料模块均复用已有生产表结构 |
| `README.md` | 已同步 | 启动说明、当前完成模块、测试命令和下一阶段建议 |

## 4. 数据库与脚本状态

| 文件 | 状态 | 说明 |
| --- | --- | --- |
| `sql/init.sql` | 已完成 | MySQL 8.x 初始化脚本，包含核心业务表 |
| `campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql` | 已完成 | 资料模块 H2 集成测试使用的最小表结构，不属于生产库变更 |

`sql/init.sql` 当前表使用状态：

| 表名 | 设计状态 | 当前代码使用状态 |
| --- | --- | --- |
| `user` | 已设计 | 已被认证模块使用 |
| `category` | 已设计 | 已被分类查询模块和资料模块使用 |
| `file_info` | 已设计 | 已被文件上传模块和资料模块使用 |
| `resource` | 已设计 | 已被资料模块使用 |
| `favorite` | 已设计 | 当前代码暂未使用 |
| `download_record` | 已设计 | 当前代码暂未使用 |
| `audit_record` | 已设计 | 当前代码暂未使用 |

## 5. 后端基础能力

| 模块 | 状态 | 已实现内容 |
| --- | --- | --- |
| Spring Boot 工程骨架 | 已完成 | Maven Wrapper、启动类、标准分层包结构 |
| 基础依赖 | 已完成 | Web、Validation、MyBatis、MySQL、Redis、JWT、BCrypt、Lombok、测试 H2 |
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

## 7. 测试与验证

| 类型 | 状态 | 说明 |
| --- | --- | --- |
| 编译验证 | 已完成 | `.\mvnw.cmd -DskipTests compile` 已通过 |
| 全量测试 | 已完成 | `.\mvnw.cmd test` 已通过，共 17 个测试 |
| Controller 测试 | 已完成 | `ResourceControllerTest` 共 11 个用例，覆盖接口层、鉴权路径、异常映射 |
| 数据库集成测试 | 已完成 | `ResourceDatabaseIntegrationTest` 共 5 个用例，覆盖真实 MyBatis SQL、写入、读取、分页和重复提交 |
| Spring 上下文测试 | 已完成 | `CampusResourcePlatformApplicationTests.contextLoads` |
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

## 8. 待开发模块

### 8.1 审核模块

建议下一阶段优先开发。

待实现功能：

- 管理员查询待审核资料。
- 审核通过资料，设置 `resource.status = 1` 和 `approved_at`。
- 审核拒绝资料，设置 `resource.status = 2` 和 `reject_reason`。
- 下架已通过资料，设置 `resource.status = 3`、`offline_reason`、`offline_at`。
- 写入 `audit_record`。
- 校验状态流转，禁止非法审核。

涉及表：`resource`、`audit_record`、`user`。

### 8.2 搜索模块

待实现功能：只搜索审核通过资料、关键词/分类/课程/类型筛选、分页排序、搜索热词记录、可选接入 Elasticsearch。

涉及表：`resource`、`category`。

涉及 Redis Key：`crp:rank:search:keyword:{dateScope}`。

### 8.3 下载模块

待实现功能：下载权限校验、下载限流、写入下载记录、文件流返回、下载量 Redis 增量统计和定时同步。

涉及表：`download_record`、`resource`、`file_info`。

涉及 Redis Key：`crp:rate:download:user:{userId}`、`crp:rate:download:ip:{ip}`、`crp:dedup:download:{userId}:{resourceId}`、`crp:stats:resource:download:delta`。

### 8.4 收藏模块

待实现功能：收藏资料、取消收藏、我的收藏列表、防重复收藏、收藏状态查询、更新收藏数和热度分。

涉及表：`favorite`、`resource`。

涉及 Redis Key：`crp:user:favorites:{userId}`、`crp:rank:resource:hot:{dateScope}`。

### 8.5 排行榜与定时任务

待实现功能：热门资料排行榜、热门搜索词排行榜、下载量增量同步、热度分数计算与回写、分布式锁防重复同步。

涉及表：`resource`。

涉及 Redis Key：`crp:rank:resource:hot:{dateScope}`、`crp:rank:search:keyword:{dateScope}`、`crp:stats:resource:download:delta`。

## 9. 当前未实现的重点功能清单

| 功能 | 当前状态 | 备注 |
| --- | --- | --- |
| 管理员审核 | 未实现 | 下一阶段建议优先开发 |
| 审核记录 | 未实现 | `audit_record` 表已设计 |
| 资料搜索 | 未实现 | 接口文档和 Redis 热词设计已完成 |
| 热门搜索词 | 未实现 | Redis ZSet 设计已完成 |
| 下载接口 | 未实现 | 接口文档、数据库表和 Redis 统计设计已完成 |
| 下载限流 | 未实现 | Redis Key 设计已完成 |
| 下载量定时同步 | 未实现 | Redis Hash 设计已完成 |
| 收藏资料 | 未实现 | `favorite` 表和唯一索引已设计 |
| 热门资料排行榜 | 未实现 | Redis ZSet 设计已完成 |
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
- 分层结构清晰：Controller、Service、Mapper、DTO、VO、Entity、Common、Config、Exception、Interceptor 各自承担边界。

## 11. 推荐下一阶段开发模块

建议下一阶段优先开发“审核模块”。

原因：

1. 资料模块已经能稳定创建 `status = 0` 的待审核资料，审核模块有明确输入。
2. 审核通过后，搜索、下载、收藏才有公开可消费的资料集合。
3. 审核模块可以继续复用认证、资料、分页、统一异常和数据库状态常量。
4. 首版审核模块仍可复用已有 `resource` 和 `audit_record` 表，不需要新增生产库结构。

推荐小步开发顺序：

1. 创建 `AuditRecord` 实体和 `AuditRecordMapper`。
2. 为 `ResourceMapper` 补充待审核分页和状态更新 SQL。
3. 创建审核 DTO/VO。
4. 实现审核 Service：通过、拒绝、下架和状态流转校验。
5. 实现管理员审核 Controller。
6. 更新 `WebMvcConfig` 或权限校验策略，确保审核接口仅管理员可访问。
7. 补充 MockMvc 和数据库集成测试。
8. 同步 API 文档、数据库记录和模块开发流程文档。

## 12. 当前可提交总结

```text
feat(resource): complete resource module MVP

- add resource entity, mapper, service and controller
- support creating pending resources from uploaded files
- support public approved detail and my resource pagination
- add controller and database integration tests
- sync API, progress, database and module docs
```
