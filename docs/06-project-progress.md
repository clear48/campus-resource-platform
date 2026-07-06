# 项目进度文档

## 1. 当前阶段结论

项目当前处于“基础工程 + 用户认证模块 + 分类查询模块 + 文件上传模块（核心代码完成，待接口/自动化测试）”阶段。

已经完成的内容主要包括：项目需求与设计文档、MySQL 初始化脚本、Spring Boot 后端基础骨架、统一响应与异常处理、用户实体、用户 Mapper、密码加密、JWT 工具类、JWT 鉴权拦截器、注册/登录/退出登录/当前用户查询接口、Redis Token 黑名单，以及资料上传前使用的公开分类查询接口。

文件上传模块（含文件 MD5 去重与秒传）已完成核心代码并通过编译，测试待补充，详见 `docs/modules/02-file-upload-development-process.md`。当前上传模块只负责 `file_info` 物理文件，不直接创建 `resource` 资料记录。

下一阶段建议优先开发“资料模块”，把已上传文件转换为业务资料记录，并提供资料详情和我的上传列表能力。审核、搜索、下载、收藏、排行榜等模块依赖 `resource` 数据，仍放在资料模块之后逐步开发。

## 2. 进度状态说明

| 状态 | 含义 |
| --- | --- |
| 已完成 | 已有实际代码或可直接执行的脚本/文档 |
| 部分完成 | 有基础类、设计文档或部分支撑能力，但业务功能未完整落地 |
| 待开发 | 已在需求/接口/数据库/Redis 文档中设计，但当前代码尚未实现 |
| 暂未涉及 | 当前阶段没有代码实现，也不是当前模块范围 |

## 3. 已完成的设计与文档

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `docs/01-requirements.md` | 已完成 | 项目背景、用户角色、功能需求、非功能需求、项目亮点 |
| `docs/02-business-flow.md` | 已完成 | 上传、审核、搜索、下载、收藏等核心业务流程和状态流转 |
| `docs/03-database-design.md` | 已完成 | MySQL 表结构、字段说明、索引、设计理由和知识点 |
| `docs/04-api-doc.md` | 已完成，后续需随代码同步 | RESTful API 总体设计，认证、分类查询、文件上传模块已按实际代码同步 |
| `docs/05-redis-design.md` | 已完成，后续需随代码同步 | Redis Key、数据结构、TTL、一致性策略，Token 黑名单已按实际代码同步 |
| `docs/modules/module.md` | 已完成 | 用户认证模块开发记录 |
| `docs/modules/category-module.md` | 已完成 | 分类查询模块开发记录 |
| `docs/modules/02-file-upload-development-process.md` | 已完成，测试待补充 | 文件上传模块开发流程记录，已按实际代码同步上传、预检、MD5 缓存与补偿逻辑 |
| `docs/modules/03-resource-development-process.md` | 已规划 | 下一阶段资料模块开发流程初稿，用于指导后续小步实现 |
| `docs/database/database-change-log.md` | 已完成 | 认证、分类查询、文件上传模块均未新增数据库结构，复用已有表 |
| `README.md` | 已同步 | 包含项目启动说明、当前已完成模块与下一阶段模块说明 |

## 4. 已完成的数据库与脚本

| 文件 | 状态 | 说明 |
| --- | --- | --- |
| `sql/init.sql` | 已完成 | MySQL 8.x 初始化脚本，包含核心业务表 |

`sql/init.sql` 当前已设计以下表：

| 表名 | 设计状态 | 当前代码使用状态 |
| --- | --- | --- |
| `user` | 已设计 | 已被认证模块使用 |
| `category` | 已设计 | 已被分类查询模块使用 |
| `file_info` | 已设计 | 已被文件上传模块使用 |
| `resource` | 已设计 | 下一阶段资料模块优先使用 |
| `favorite` | 已设计 | 当前代码暂未使用 |
| `download_record` | 已设计 | 当前代码暂未使用 |
| `audit_record` | 已设计 | 当前代码暂未使用 |

## 5. 已完成的后端基础能力

| 模块 | 状态 | 已实现内容 | 主要代码 |
| --- | --- | --- | --- |
| Spring Boot 工程骨架 | 已完成 | Maven Wrapper、启动类、标准分层包结构 | `campus-resource-platform/` |
| 基础依赖 | 已完成 | Web、Validation、MyBatis、MySQL、Redis、JWT、BCrypt、Lombok | `pom.xml` |
| 配置文件 | 已完成 | 端口、MySQL、Redis、MyBatis、JWT、上传目录配置 | `application.yaml` |
| 统一响应 | 已完成 | `ApiResponse<T>` 统一返回 `code/message/data/traceId` | `ApiResponse` |
| 统一错误码 | 已完成 | 业务错误码枚举 | `ErrorCode` |
| 全局异常处理 | 已完成 | 业务异常、参数校验异常、JSON 解析异常、兜底异常 | `GlobalExceptionHandler` |
| 业务异常 | 已完成 | 通过 `BusinessException` 承载业务错误码和提示信息 | `BusinessException` |
| MyBatis 配置 | 已完成 | Mapper 扫描、XML 映射配置 | `MyBatisConfig`、`UserMapper.xml` |
| Web 配置 | 已完成 | CORS、JWT 拦截器路径配置 | `WebMvcConfig` |
| 分页返回模型 | 已完成 | 通用分页结果对象 | `PageResult` |
| 分页查询模型 | 已完成 | 通用分页查询对象 | `PageQuery` |

说明：`HealthService`、`HealthServiceImpl`、`HealthVO` 已配套 `HealthController`，健康检查 HTTP 接口 `GET /api/v1/health` 已完整落地，并在 `WebMvcConfig` 中排除 JWT 拦截，无需登录即可访问。

## 6. 已完成的用户认证模块

### 6.1 模块状态

用户认证模块已完成基础闭环：注册、登录、退出登录、当前用户查询、JWT 鉴权、Redis Token 黑名单。

### 6.2 已实现接口

| 接口 | 方法 | 状态 | 说明 |
| --- | --- | --- | --- |
| `/api/v1/auth/register` | `POST` | 已完成 | 用户注册，密码使用 BCrypt 加密后保存 |
| `/api/v1/auth/login` | `POST` | 已完成 | 用户登录，校验密码和账号状态后签发 JWT |
| `/api/v1/auth/logout` | `POST` | 已完成 | 退出登录，将 Token 的 `jti` 写入 Redis 黑名单 |
| `/api/v1/users/me` | `GET` | 已完成 | 查询当前登录用户信息 |

### 6.3 已实现功能

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 用户实体 | 已完成 | `User` 使用 Lombok 管理 Getter/Setter，包含角色、状态常量和状态判断方法 |
| 基础实体 | 已完成 | `BaseEntity` 提供通用时间字段 |
| 用户注册 DTO | 已完成 | `AuthRegisterDTO` 包含参数校验注解 |
| 用户登录 DTO | 已完成 | `AuthLoginDTO` 包含参数校验注解 |
| 登录响应 VO | 已完成 | `AuthLoginVO` 返回 Token、过期时间和用户信息 |
| 用户响应 VO | 已完成 | `UserVO` 不返回密码字段 |
| 用户 Mapper | 已完成 | 按用户名查询、按 ID 查询、插入用户、更新最近登录时间 |
| Mapper XML | 已完成 | `UserMapper.xml` 映射 `user` 表和 `password_hash` 等字段 |
| 密码加密 | 已完成 | `PasswordServiceImpl` 使用 `BCryptPasswordEncoder` |
| JWT 生成 | 已完成 | `JwtUtils.generateToken(Long, Integer)` |
| JWT 解析 | 已完成 | `JwtUtils.parseToken(String)` 和 `getUserId/getRole/getJti` |
| JWT 剩余有效期 | 已完成 | `JwtUtils.getRemainingSeconds(String)` |
| 登录状态上下文 | 已完成 | `UserContextHolder` 使用 `ThreadLocal` 保存当前用户 |
| JWT 拦截器 | 已完成 | 解析请求头 Token，校验 Redis 黑名单，写入当前用户上下文 |
| 退出登录黑名单 | 已完成 | Redis Key：`crp:auth:token:blacklist:{jti}` |
| 认证模块事务 | 已完成 | 注册和登录方法使用 `@Transactional(rollbackFor = Exception.class)` |

### 6.4 涉及数据库表

| 表名 | 使用方式 |
| --- | --- |
| `user` | 注册插入用户、登录按用户名查询、按 ID 查询当前用户、更新 `last_login_at` |

当前认证模块没有新增数据库表、字段或索引，复用 `sql/init.sql` 中已有 `user` 表。

### 6.5 涉及 Redis Key

| Key | 类型 | 状态 | 使用场景 |
| --- | --- | --- | --- |
| `crp:auth:token:blacklist:{jti}` | String | 已实现 | 用户退出登录后让未过期 JWT 立即失效 |

## 7. 已完成的分类查询模块

### 7.1 模块状态

分类查询模块已完成基础只读能力：按 `parentId` 查询启用分类，用于资料上传前选择分类。

### 7.2 已实现接口

| 接口 | 方法 | 状态 | 说明 |
| --- | --- | --- | --- |
| `/api/v1/categories?parentId=0` | `GET` | 已完成 | 查询指定父分类下的启用分类，不需要登录 |

### 7.3 已实现功能

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 分类实体 | 已完成 | `Category` 使用 Lombok，映射 `category` 表 |
| 分类响应 VO | 已完成 | `CategoryVO` 返回分类展示字段，不直接返回 Entity |
| 分类 Mapper | 已完成 | `CategoryMapper.selectEnabledByParentId` |
| Mapper XML | 已完成 | 固定过滤 `parent_id = #{parentId}`、`status = 1`，按 `sort_order ASC, id ASC` 排序 |
| 分类 Service | 已完成 | 校验 `parentId`，查询启用分类并转换为 VO |
| 分类 Controller | 已完成 | `GET /api/v1/categories` |
| JWT 排除 | 已完成 | `WebMvcConfig` 已排除 `/api/v1/categories` |

### 7.4 涉及数据库表

| 表名 | 使用方式 |
| --- | --- |
| `category` | 按父分类查询启用分类列表 |

当前分类查询模块没有新增数据库表、字段或索引，复用 `sql/init.sql` 中已有 `category` 表和 `idx_category_parent_status` 索引。

### 7.5 涉及 Redis Key

当前分类查询模块暂未使用 Redis。

## 8. 已完成的测试与验证

| 类型 | 状态 | 说明 |
| --- | --- | --- |
| 编译验证 | 已完成 | `.\mvnw.cmd -DskipTests compile` 已通过，文件上传核心代码已纳入编译 |
| Postman 集合 | 已准备 | `postman/campus-resource-platform.postman_collection.json` |
| Postman 环境 | 已准备 | `postman/campus-local.postman_environment.json` |
| 文件上传接口测试 | 待补充 | 需补充上传成功、秒传、预检命中/未命中、文件过大、非法类型、未登录等用例 |
| 自动化单元测试 | 待开发 | 当前暂未针对认证、分类、文件上传模块补充单元测试 |
| 集成测试 | 待开发 | 当前暂未使用 Testcontainers 或 MockMvc 做完整链路测试 |

## 9. 部分完成的模块

| 模块 | 当前状态 | 已有基础 | 尚缺内容 |
| --- | --- | --- | --- |
| 用户模块 | 部分完成 | 注册、登录、退出登录、当前用户查询 | 用户资料修改、头像、管理员禁用用户、用户列表、角色管理 |
| Redis 能力 | 部分完成 | Token 黑名单、文件 MD5 去重缓存已实现 | 排行榜、下载限流、下载量统计、资料详情缓存暂未实现 |
| 数据库模块 | 部分完成 | 完整建表 SQL 已有，`user`、`category`、`file_info` 表已被使用 | `resource`、`favorite`、`download_record`、`audit_record` 还没有 Mapper、Entity、Service、Controller |
| 接口文档 | 部分完成 | 整体接口已设计，认证、分类、文件上传模块已按代码同步 | 资料、审核、搜索、下载、收藏等模块待开发后继续校准 |

## 10. 待开发的核心业务模块

### 10.1 文件上传模块（核心已完成，非下一阶段主线）

已实现功能：

- 文件类型白名单校验。
- 文件大小校验。
- 计算文件 MD5。
- 根据 `file_md5` 和 `file_size` 判断是否重复文件。
- 保存文件到本地存储。
- 写入 `file_info` 表。
- 文件复用或秒传。
- 上传失败后的文件清理。
- Redis 文件 MD5 去重缓存。

待补充功能：

- 上传频率限制。
- 文件上传接口手工测试与自动化测试。
- 严格 MIME/文件头校验。

涉及表：

- `file_info`
- `resource`（下一阶段资料模块使用）

涉及 Redis Key：

- `crp:cache:file:md5:{fileMd5}:{fileSize}`

### 10.2 资料模块（下一阶段优先开发）

下一阶段建议实现功能：

- 上传文件后创建资料记录。
- 资料进入待审核状态。
- 查询资料详情。
- 查询我的上传资料。
- 资料状态展示。
- 校验分类、文件状态和上传者权限。
- 资料浏览次数统计可作为本模块后续增强，首版可先保留默认值。

涉及表：

- `resource`
- `category`
- `file_info`

涉及 Redis Key：

- `crp:cache:resource:detail:{resourceId}`

### 10.3 审核模块

待实现功能：

- 管理员查询待审核资料。
- 审核通过。
- 审核拒绝。
- 下架资料。
- 写入审核记录。
- 禁止非法状态转换。
- 审核后删除或刷新资料缓存。
- 审核通过后更新搜索可见性。

涉及表：

- `resource`
- `audit_record`
- `user`

### 10.4 搜索模块

待实现功能：

- 只搜索审核通过的资料。
- 按关键词搜索标题、课程、标签、描述。
- 分类筛选。
- 排序和分页。
- 记录搜索关键词。
- 更新热门搜索词排行榜。
- 可选接入 Elasticsearch。

涉及表：

- `resource`
- `category`

涉及 Redis Key：

- `crp:rank:search:keyword:{dateScope}`

### 10.5 下载模块

待实现功能：

- 登录校验。
- 资料状态校验，只允许下载审核通过资料。
- 下载限流。
- 写入下载记录。
- Redis 临时统计下载量。
- 定时同步下载量到 MySQL。
- 同一用户短时间重复下载去重统计。
- 文件流返回。

涉及表：

- `download_record`
- `resource`
- `file_info`

涉及 Redis Key：

- `crp:rate:download:user:{userId}`
- `crp:rate:download:ip:{ip}`
- `crp:dedup:download:{userId}:{resourceId}`
- `crp:stats:resource:download:delta`

### 10.6 收藏模块

待实现功能：

- 收藏资料。
- 取消收藏。
- 查询我的收藏列表。
- 防止重复收藏。
- 更新资料收藏数。
- 更新资料热度分数。
- 使用 Redis Set 加速收藏状态判断。

涉及表：

- `favorite`
- `resource`

涉及 Redis Key：

- `crp:user:favorites:{userId}`
- `crp:rank:resource:hot:{dateScope}`

### 10.7 排行榜模块

待实现功能：

- 热门资料排行榜。
- 热门搜索词排行榜。
- 按日/周/月维度统计。
- Redis ZSet 分数更新。
- 排行榜降级查询。
- 排行榜数据定期回写或快照。

涉及 Redis Key：

- `crp:rank:resource:hot:{dateScope}`
- `crp:rank:search:keyword:{dateScope}`

### 10.8 定时任务与数据同步模块

待实现功能：

- 定时同步 Redis 下载量增量到 MySQL。
- 同步成功后清理 Redis 增量。
- 分布式锁防止多实例重复同步。
- 同步失败重试或补偿日志。
- 热度分数计算与回写。

涉及表：

- `resource`

涉及 Redis Key：

- `crp:stats:resource:download:delta`
- 后续可新增同步锁 Key。

## 11. 当前未实现的重点功能清单

| 功能 | 当前状态 | 备注 |
| --- | --- | --- |
| 文件上传 | 核心已完成（待测试） | `POST /api/v1/files` 上传 + `GET /api/v1/files/check` 预检 |
| 文件 MD5 去重 | 已完成 | `file_md5 + file_size` 唯一索引去重与秒传，Redis 缓存加速 |
| 资料创建 | 未实现（下一阶段优先） | `resource` 表已设计，文件上传模块已返回可引用的 `fileId` |
| 分类查询 | 已实现 | `GET /api/v1/categories?parentId=0`，只返回启用分类 |
| 审核状态流转 | 未实现 | 需求和流程已设计 |
| 审核记录 | 未实现 | `audit_record` 表已设计 |
| 资料搜索 | 未实现 | 接口文档和 Redis 热词设计已完成 |
| 热门搜索词 | 未实现 | Redis ZSet 设计已完成 |
| 下载接口 | 未实现 | 接口文档、数据库表和 Redis 统计设计已完成 |
| 下载限流 | 未实现 | Redis Key 设计已完成 |
| 下载量定时同步 | 未实现 | Redis Hash 设计已完成 |
| 收藏资料 | 未实现 | `favorite` 表和唯一索引已设计 |
| 防重复收藏 | 未实现 | 数据库唯一索引已设计，业务代码未实现 |
| 热门资料排行榜 | 未实现 | Redis ZSet 设计已完成 |
| 管理员接口 | 未实现 | 当前只有用户角色字段，没有管理员业务接口 |
| 注解式权限控制 | 未实现 | 当前只通过 JWT 拦截器完成登录校验 |
| 登录限流 | 未实现 | `ErrorCode.RATE_LIMITED` 已存在，但认证模块未使用 |
| Refresh Token | 未实现 | 当前只有 Access Token |
| 用户禁用后 Token 批量失效 | 未实现 | `crp:auth:user:token-version:{userId}` 仅为设计预留 |
| 自动化测试 | 未实现 | 当前只有默认测试类，认证链路测试待补充 |
| Elasticsearch | 未实现 | 属于可选扩展技术栈 |
| RocketMQ | 未实现 | 属于可选扩展技术栈 |

## 12. 当前项目亮点沉淀

虽然核心资料业务尚未开发，但当前项目已经具备几个可以继续延展的亮点基础：

- 不是单纯 CRUD：需求和流程已经围绕上传、审核、搜索、下载统计、排行榜、限流、缓存一致性设计。
- 认证安全基础已落地：密码使用 BCrypt，不存明文密码。
- JWT 不是只签发不管理：通过 `jti` 和 Redis 黑名单实现退出登录后 Token 立即失效。
- Redis 已有真实业务使用：当前已用于 Token 黑名单，后续可自然扩展到限流、排行榜、统计缓冲。
- 数据库设计有面试点：`file_info.file_md5`、`resource.status`、`favorite` 唯一索引、下载记录和审核记录都服务于核心业务。
- 分层结构清晰：Controller、Service、Mapper、DTO、VO、Entity、Common、Config、Exception、Interceptor 已建立。
- 分类查询保持只读边界：复用已有 `category` 表，公开查询启用分类，但不提前实现上传和审核模块。
- 文档体系较完整：需求、流程、数据库、接口、Redis、模块记录都有对应文档。

## 13. 推荐下一阶段开发模块

建议下一阶段优先开发“资料模块”，而不是继续扩展审核、搜索或下载。原因如下：

1. 文件上传模块已经能产出稳定的 `fileId`，但平台还缺少 `resource` 业务资料记录，搜索、审核、下载、收藏都没有可消费的业务主体。
2. `resource.status` 是后续审核状态机、搜索可见性、下载权限和收藏校验的共同基础。
3. 资料模块可以复用已完成的认证、分类查询、文件上传、分页模型和统一异常能力，开发边界清晰。
4. 资料模块首版不需要新增数据库结构，直接复用 `resource`、`file_info`、`category` 表，风险较低。

下一阶段资料模块建议边界：

- 实现 `POST /api/v1/resources`：基于已上传 `fileId` 创建资料，默认 `status = 0 PENDING_REVIEW`。
- 实现 `GET /api/v1/resources/{resourceId}`：查询已审核通过的公开资料详情。
- 实现 `GET /api/v1/users/me/resources`：查询当前用户上传资料列表和审核状态。
- 校验 `file_info` 是否存在且正常、`category` 是否启用、资料标题/课程/类型/标签是否合法。
- 暂不实现管理员审核、全文搜索、下载、收藏、排行榜和定时同步。

推荐小步开发顺序：

1. 根据 `docs/modules/03-resource-development-process.md` 确认模块边界。
2. 创建 `Resource` 实体、`ResourceMapper` 和 `ResourceMapper.xml`。
3. 创建资料 DTO/VO，统一处理 tags 存储和展示转换。
4. 实现创建资料 Service：校验文件、分类、重复提交，并写入 `resource`。
5. 实现公开详情和我的上传列表查询。
6. 补充 `ResourceController`，并更新 `WebMvcConfig` 中公开详情接口的 JWT 排除规则。
7. 补充接口测试、模块文档、数据库变更记录和进度文档。

## 14. 当前可提交总结

```text
docs(progress): plan resource module as next milestone

- sync file upload module status with current implementation
- clarify resource module as the next development target
- record resource module scope, dependencies and development order
- add module planning document for the next implementation phase
```
