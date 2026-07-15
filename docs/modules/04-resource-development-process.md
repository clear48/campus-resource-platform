# 资料模块开发流程文档

> 本文档遵循 `docs/AGENTS.md` 第 24 节《模块开发流程文档规范》生成。
> 当前状态：**首版已完成，步骤 1-10 已完成**。资料模块已实现创建资料、公开详情和我的上传列表三类能力，并完成 MockMvc 测试、数据库集成测试、Postman 示例、接口文档、数据库记录、项目进度文档和 README 同步。

---

## 1. 模块基本信息

| 项 | 内容 |
| --- | --- |
| 模块名称 | 资料模块 |
| 英文标识 | resource |
| 文档路径 | `docs/modules/04-resource-development-process.md` |
| 建议分支 | `feature/resource` |
| 当前状态 | 首版已完成，步骤 1-10 已完成 |
| 前置依赖模块 | 用户认证模块、分类查询模块、文件上传模块 |
| 下游模块 | 审核模块、搜索模块、下载模块、收藏模块、排行榜模块 |
| 接口前缀 | `/api/v1/resources`、`/api/v1/users/me/resources` |

---

## 2. 模块目标

资料模块负责把已经上传成功的物理文件 `file_info` 转换为平台中的业务资料 `resource`，并提供资料创建、公开详情查询、我的上传资料列表查询能力。

首版目标：

- 学生或管理员基于已存在 `fileId` 创建资料。
- 新资料默认进入 `PENDING_REVIEW` 状态，等待管理员审核。
- 游客可以查看已审核通过资料的公开详情。
- 登录用户可以查看自己上传资料的审核状态。
- 为后续审核、搜索、下载、收藏提供统一的业务资料主体。

---

## 3. 需求分析

1. 文件上传模块只保存物理文件元数据，不保存标题、课程、分类、标签等业务资料信息。
2. 平台需要 `resource` 记录来承载审核状态、可见性、下载量、收藏量、热度分等业务字段。
3. 创建资料时必须校验 `file_info`、`category` 和当前登录用户，不能信任前端传入的上传者。
4. 新资料不能直接公开展示，必须先进入待审核状态。
5. 我的上传列表需要展示待审核、已通过、已拒绝、已下架等状态，方便用户跟踪资料生命周期。

为什么不是简单 CRUD：资料创建会触发审核状态流转的起点，影响后续搜索、下载、收藏和排行榜可见性；同时需要处理文件引用、分类有效性、重复提交、权限边界和状态展示。

---

## 4. 本模块不做什么

- 不上传物理文件，文件上传继续由 `FileController` 和 `FileService` 负责。
- 不实现管理员审核通过、审核拒绝、下架资料。
- 不实现搜索列表、关键词统计或 Elasticsearch。
- 不实现下载、下载限流、下载量 Redis 统计。
- 不实现收藏、取消收藏、收藏状态判断。
- 不实现热门资料排行榜。
- 不新增数据库表或字段，首版复用 `sql/init.sql` 中已有表结构。

---

## 5. 涉及接口

> 以下为当前 `ResourceController` 已实现的资料模块接口，已同步到 `docs/api/api-reference.md`。

### 5.1 创建资料

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/v1/resources` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员 |
| 请求体 | JSON |

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fileId` | long | 是 | 已上传成功的文件 ID |
| `title` | string | 是 | 资料标题 |
| `description` | string | 否 | 资料简介 |
| `categoryId` | long | 是 | 启用分类 ID |
| `courseName` | string | 是 | 课程名称 |
| `resourceType` | int | 是 | 资料类型：1课件 2笔记 3真题 4实验报告 5课程设计 99其他 |
| `tags` | array | 否 | 标签列表，首版可用逗号拼接存入 `resource.tags` |

响应数据：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | long | 新创建的资料 ID |
| `fileId` | long | 关联文件 ID |
| `status` | int | 默认 `0` |
| `statusName` | string | 默认 `PENDING_REVIEW` |
| `message` | string | 提示资料已进入待审核状态 |

### 5.2 获取公开资料详情

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/resources/{resourceId}` |
| 是否登录 | 否 |
| 权限要求 | 仅返回 `APPROVED` 资料 |

首版只返回公开字段，不返回下载地址；下载由下载模块实现。

### 5.3 获取我的上传资料

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/users/me/resources` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | int | 否 | 资料状态 |
| `pageNo` | int | 否 | 默认 1 |
| `pageSize` | int | 否 | 默认 10 |

---

## 6. 涉及数据库表

### 6.1 `resource` 资料表

首版主要写入和查询该表。

| 字段 | 使用场景 |
| --- | --- |
| `id` | 资料主键 |
| `title` | 创建资料、详情展示、列表展示 |
| `description` | 创建资料、详情展示 |
| `category_id` | 创建时校验分类，详情展示分类 |
| `course_name` | 创建资料、筛选和展示 |
| `resource_type` | 创建资料、展示资料类型 |
| `tags` | 标签展示，首版可用逗号分隔 |
| `file_id` | 关联 `file_info.id` |
| `uploader_id` | 当前登录用户 ID，不接受前端传参 |
| `status` | 新增默认为 `0 PENDING_REVIEW` |
| `reject_reason` | 我的上传列表展示驳回原因 |
| `offline_reason` | 我的上传列表展示下架原因 |
| `download_count` | 详情展示，后续由下载模块统计 |
| `favorite_count` | 详情展示，后续由收藏模块统计 |
| `hot_score` | 详情展示，后续由排行榜模块回写 |

### 6.2 `file_info` 文件信息表

创建资料时校验文件是否存在且 `status = 1`。首版不在资料模块中修改 `file_info.ref_count`，因为文件上传秒传已处理物理文件引用计数；如后续允许一个文件创建多份资料，可再统一评估引用语义。

### 6.3 `category` 分类表

创建资料时校验分类是否存在且启用。当前已通过 `CategoryMapper.selectEnabledById` 按主键查询 `status = 1` 的启用分类。

---

## 7. 涉及 Redis Key

首版资料模块可以先不接入 Redis，保证 MySQL 主链路闭环。

后续可接入以下已设计 Key：

| Key | 类型 | 用途 |
| --- | --- | --- |
| `crp:cache:resource:detail:{resourceId}` | String | 缓存资料公开详情 |

接入缓存时需同步更新 `RedisKeyConstants`，并在审核通过、审核拒绝、下架资料时删除或刷新缓存。

---

## 8. 涉及核心类

### 8.1 复用已存在类

| 类型 | 类 | 作用 |
| --- | --- | --- |
| common | `ApiResponse` | 统一响应 |
| common | `ErrorCode` | 统一错误码 |
| common | `PageResult`、`PageQuery` | 分页模型 |
| common | `UserContextHolder` | 获取当前登录用户 |
| entity | `BaseEntity`、`FileInfo`、`Category` | 基础实体、文件实体、分类实体 |
| mapper | `FileInfoMapper`、`CategoryMapper` | 文件和分类校验 |
| exception | `BusinessException`、`GlobalExceptionHandler` | 业务异常和统一异常处理 |

### 8.2 本模块新增或修改类

| 类型 | 类 | 职责 |
| --- | --- | --- |
| entity | `Resource` | 映射 `resource` 表，封装资料状态常量 |
| dto | `ResourceCreateDTO` | 创建资料请求体 |
| dto | `PageQuery` | 我的上传列表分页参数，复用已有通用分页模型 |
| vo | `ResourceCreateVO` | 创建资料响应 |
| vo | `ResourceDetailVO` | 公开详情响应 |
| vo | `MyResourceVO` | 我的上传列表项 |
| mapper | `ResourceMapper` + `ResourceMapper.xml` | `insert`、详情查询、我的上传分页、重复提交校验 |
| service | `ResourceService` / `ResourceServiceImpl` | 资料创建、详情查询、我的上传列表业务编排 |
| controller | `ResourceController` | 资料相关 HTTP 入口 |

---

## 9. 模块内部调用关系

```text
ResourceController
  └── ResourceService (ResourceServiceImpl)
        ├── UserContextHolder（获取 uploaderId）
        ├── FileInfoMapper（校验 fileId 是否正常）
        ├── CategoryMapper（校验 categoryId 是否启用）
        ├── ResourceMapper（写入 resource、查询详情、分页）
        └── PageResult（封装分页响应）
```

Controller 只负责接收请求、触发参数校验和返回统一响应；业务规则放在 `ResourceServiceImpl`。

---

## 10. 请求处理流程

### 10.1 创建资料

1. JWT 拦截器校验登录。
2. `ResourceController.create` 接收 `ResourceCreateDTO`。
3. `ResourceServiceImpl.create` 从 `UserContextHolder` 获取当前用户 ID。
4. 校验 `fileId` 对应的 `file_info` 存在且状态正常。
5. 校验 `categoryId` 对应分类存在且启用。
6. 校验标题、课程名、资料类型、标签数量和长度。
7. 查询当前用户是否已基于同一 `fileId` 创建待审核或已通过资料，防止重复提交。
8. 构造 `Resource`，设置 `status = 0`、`uploaderId = 当前用户`。
9. 调用 `ResourceMapper.insert` 写入 `resource` 表。
10. 返回 `ResourceCreateVO`。

### 10.2 获取公开资料详情

1. 请求进入 `GET /api/v1/resources/{resourceId}`。
2. `WebMvcConfig` 使用 `/api/v1/resources/*` 排除该公开详情路径的 JWT 拦截。
3. Service 查询资料详情，只允许返回 `status = 1 APPROVED` 的资料。
4. 资料不存在或状态不可见时返回 `40401` 或 `40901`。
5. 返回资料详情，不返回文件真实存储路径。

### 10.3 获取我的上传资料

1. JWT 拦截器校验登录。
2. Controller 接收 `status`、`pageNo`、`pageSize`。
3. Service 校验分页参数和状态参数。
4. Mapper 按 `uploader_id = 当前用户` 查询，支持按状态筛选。
5. 返回 `PageResult<MyResourceVO>`。

---

## 11. 数据流转流程

```text
FileUploadVO.fileId
  → ResourceCreateDTO.fileId
  → 校验 file_info.status = 1
  → 校验 category.status = 1
  → resource.status = 0(PENDING_REVIEW)
  → ResourceCreateVO
  → 后续审核模块消费 resource.id
```

---

## 12. 权限校验

- `POST /api/v1/resources`：需要登录，上传者取当前登录用户。
- `GET /api/v1/resources/{resourceId}`：公开接口，但只返回审核通过资料。
- `GET /api/v1/users/me/resources`：需要登录，只返回当前用户上传资料。
- 首版不实现管理员角色专属能力，管理员审核放到审核模块。

公开详情接口与创建接口共用 `/api/v1/resources` 前缀时，`WebMvcConfig` 只能按路径排除，不能按 HTTP 方法排除。因此建议仅排除 `/api/v1/resources/*`，保护 `POST /api/v1/resources` 不受影响。

---

## 13. 参数校验

| 场景 | 规则 | 失败错误码 |
| --- | --- | --- |
| `fileId` | 必填且大于 0，文件必须存在且正常 | `40001` / `40401` |
| `title` | 必填，长度建议 2 到 150 | `40001` |
| `categoryId` | 必填且大于 0，分类必须启用 | `40001` / `40401` |
| `courseName` | 必填，长度建议 1 到 100 | `40001` |
| `resourceType` | 必须在 1、2、3、4、5、99 中 | `40001` |
| `tags` | 可选，建议最多 10 个，每个标签限长 | `40001` |
| `status` 查询参数 | 可选，必须是 0、1、2、3、4 | `40001` |
| 分页参数 | `pageNo >= 1`，`pageSize` 设置上限 | `40001` |

---

## 14. 异常处理

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 未登录创建资料 | `40101 UNAUTHORIZED` | JWT 拦截器处理 |
| 文件不存在或已删除 | `40401 RESOURCE_NOT_FOUND` | `file_info` 不可用 |
| 分类不存在或禁用 | `40401 RESOURCE_NOT_FOUND` | `category` 不可用 |
| 重复提交同一文件资料 | `40002 DATA_DUPLICATE` | 同一用户、同一文件、待审核或已通过 |
| 公开详情资料未审核通过 | `40901 RESOURCE_STATUS_INVALID` | 不允许公开查看 |
| 分页或状态参数错误 | `40001 PARAM_ERROR` | 参数校验失败 |

---

## 15. 事务处理

创建资料只写 `resource` 单表，首版可以使用 `@Transactional(rollbackFor = Exception.class)` 包裹创建逻辑，便于后续扩展文件引用、审核草稿或事件记录。

详情查询和我的上传列表是只读操作，不需要事务。

本模块不执行文件 IO，不需要处理文件落盘与数据库写入之间的补偿逻辑。

---

## 16. 核心实现步骤

1. 创建 `Resource` 实体，补充资料状态常量和状态判断方法。
2. 创建 `ResourceMapper` 与 `ResourceMapper.xml`。
3. 为 `CategoryMapper` 补充按 ID 查询启用分类的方法。
4. 创建 `ResourceCreateDTO`、`ResourceCreateVO`、`ResourceDetailVO`、`MyResourceVO`。
5. 实现 `ResourceService` 与 `ResourceServiceImpl`。
6. 实现 `ResourceController`。
7. 更新 `WebMvcConfig`，仅开放 `GET /api/v1/resources/{resourceId}` 所需的路径模式。
8. 补充接口测试、数据库集成测试和 Postman 示例。
9. 更新 `docs/api/api-reference.md`、`docs/database/database-change-log.md`、`docs/06-project-progress.md` 和 `README.md`。
10. 校准本模块开发流程文档。

---

## 17. 开发任务拆分

| 序号 | 任务 | 产出 |
| --- | --- | --- |
| T1 | 实体与 Mapper | `Resource`、`ResourceMapper`、`ResourceMapper.xml` |
| T2 | DTO/VO | 创建资料、详情、我的上传列表相关 DTO/VO |
| T3 | Service 创建逻辑 | 文件校验、分类校验、重复提交校验、写入 `resource` |
| T4 | Service 查询逻辑 | 公开详情、我的上传分页 |
| T5 | Controller 与权限路径 | `ResourceController`、`WebMvcConfig` 路径调整 |
| T6 | 测试与文档 | 测试用例、API 文档、模块文档、进度文档 |

---

## 18. 已完成事项

- 已确认下一阶段优先开发资料模块。
- 已明确本模块只消费文件上传模块返回的 `fileId`，不再把文件上传和资料创建混在一个接口里。
- 已生成本模块开发流程文档初稿。
- 步骤 1 已完成：创建 `Resource` 实体，字段对齐 `resource` 表，并补充资料状态、资料类型常量和公开可见性判断方法。
- 步骤 2 已完成：创建 `ResourceMapper` 和 `ResourceMapper.xml`，提供资料插入、公开详情查询、我的上传分页、总数统计和重复提交计数方法。
- 步骤 3 已完成：补充 `CategoryMapper.selectEnabledById` 和 `FileInfoMapper.selectNormalById`，供资料创建时校验分类和文件是否可引用。
- 步骤 4 已完成：创建 `ResourceCreateDTO`、`ResourceCreateVO`、`ResourceDetailVO`、`MyResourceVO`，用于资料创建、公开详情和我的上传列表响应。
- 步骤 5 已完成：创建 `ResourceService` 和 `ResourceServiceImpl`，实现创建资料的文件校验、分类校验、重复提交校验、标签清洗和待审核资料入库。
- 步骤 6 已完成：在 `ResourceService` 和 `ResourceServiceImpl` 中实现公开资料详情查询和我的上传资料分页查询。
- 步骤 7 已完成：创建 `ResourceController`，实现资料创建、公开详情、我的上传列表三个入口，并在 `WebMvcConfig` 中仅放行 `/api/v1/resources/*` 公开详情路径。
- 步骤 8 已完成：新增 `ResourceControllerTest` 覆盖资料接口层和鉴权路径，新增 `ResourceDatabaseIntegrationTest` 覆盖真实 Mapper SQL 与 Service 数据库读写链路，更新 Postman 集合补充资料模块请求示例，并记录验证命令。
- 步骤 9 已完成：同步 `docs/api/api-reference.md`、`docs/database/database-change-log.md`、`docs/06-project-progress.md` 和 `README.md`，资料模块已从规划状态更新为首版完成状态。
- 步骤 10 已完成：根据当前真实代码校准本模块开发流程文档，补全状态、测试、文件清单、后续优化和下一阶段建议。

---

## 19. 待完成事项

- 资料模块首版范围内暂无未完成事项。
- 下一阶段建议进入审核模块：管理员待审核列表、审核通过、审核拒绝、下架资料和审核记录。
- 资料模块后续增强项包括详情缓存、浏览次数统计、标签表拆分、重新提交审核和上传频率限制。

---

## 20. 测试清单

### 20.1 创建资料

| 用例 | 预期 |
| --- | --- |
| 合法 `fileId`、分类和资料参数 | 创建成功，`status = 0` |
| 未登录创建资料 | 返回 `40101` |
| `fileId` 不存在 | 返回 `40401` |
| 文件已删除 | 返回 `40401` 或 `40901` |
| 分类不存在或禁用 | 返回 `40401` |
| 标题为空或过长 | 返回 `40001` |
| 非法 `resourceType` | 返回 `40001` |
| 重复提交同一文件资料 | 返回 `40002` |

### 20.2 公开详情

| 用例 | 预期 |
| --- | --- |
| 查询审核通过资料 | 返回详情 |
| 查询待审核资料 | 返回 `40901` |
| 查询不存在资料 | 返回 `40401` |

### 20.3 我的上传列表

| 用例 | 预期 |
| --- | --- |
| 登录用户查询自己的资料 | 返回分页列表 |
| 按状态筛选 | 只返回对应状态 |
| 未登录查询 | 返回 `40101` |
| 非法分页参数 | 返回 `40001` |

### 20.4 当前验证记录

- 已执行 `.\mvnw.cmd -DskipTests compile`，编译通过。
- 已执行 `.\mvnw.cmd -Dtest=ResourceControllerTest test`，`ResourceControllerTest` 共 11 个用例全部通过。
- 已执行 `.\mvnw.cmd -Dtest=ResourceDatabaseIntegrationTest test`，`ResourceDatabaseIntegrationTest` 共 5 个数据库集成用例全部通过。
- 已执行 `.\mvnw.cmd test`，共 17 个测试全部通过。
- 测试输出仍包含 Lombok/Netty Unsafe 提示和 Mockito 动态 Agent 提示，当前不影响测试结果；如后续升级到更严格的 JDK 运行策略，可按 Mockito 官方建议配置测试 Java Agent。

### 20.5 自动化测试覆盖

| 测试类 | 覆盖内容 |
| --- | --- |
| `ResourceControllerTest` | `GET /api/v1/resources/{resourceId}` 匿名访问成功 |
| `ResourceControllerTest` | `POST /api/v1/resources` 未登录返回 `40101` |
| `ResourceControllerTest` | 创建资料请求体参数非法返回 `40001` 且不进入 Service |
| `ResourceControllerTest` | 创建资料成功返回待审核状态 `status = 0` |
| `ResourceControllerTest` | 文件不存在返回 `40401` |
| `ResourceControllerTest` | 分类不存在返回 `40401` |
| `ResourceControllerTest` | 重复提交同一文件返回 `40002` |
| `ResourceControllerTest` | 公开详情遇到未审核通过资料返回 `40901` |
| `ResourceControllerTest` | 我的上传列表绑定 `status`、`pageNo`、`pageSize` |
| `ResourceControllerTest` | 我的上传列表非法分页返回 `40001` |
| `ResourceControllerTest` | 我的上传列表未登录返回 `40101` |
| `ResourceDatabaseIntegrationTest` | 使用 H2 MySQL 模式执行真实 MyBatis XML，验证创建资料写入 `resource` 并可读回 |
| `ResourceDatabaseIntegrationTest` | 验证 `ResourceMapper.insert` 自增主键回填、默认统计字段和标签清洗入库 |
| `ResourceDatabaseIntegrationTest` | 验证公开详情只读取 `status = 1` 资料，待审核资料不可公开 |
| `ResourceDatabaseIntegrationTest` | 验证“我的上传”分页、状态筛选、总数统计和用户隔离 |
| `ResourceDatabaseIntegrationTest` | 验证重复提交同一文件资料被真实数据库计数拦截 |
| `ResourceDatabaseIntegrationTest` | 验证已删除文件、禁用分类不会被资料创建流程引用 |

### 20.6 Postman 手工测试说明

`docs/api/postman/campus-resource-platform.postman_collection.json` 已新增 `03 Resource Module` 分组。运行前建议准备以下环境变量：

| 变量 | 说明 |
| --- | --- |
| `baseUrl` | 后端服务地址，例如 `http://localhost:8080` |
| `username` / `password` | 可登录用户；可先运行认证模块注册和登录流程 |
| `resourceFileId` | 已上传成功且 `file_info.status = 1` 的文件 ID |
| `resourceCategoryId` | 已启用且 `category.status = 1` 的分类 ID |
| `publicResourceId` | 已审核通过且 `resource.status = 1` 的资料 ID，用于公开详情成功用例 |
| `pendingResourceId` | 待审核资料 ID；创建资料成功后集合会自动写入 |

| Postman 请求 | 覆盖场景 |
| --- | --- |
| `Refresh Token For Resource Tests` | 刷新 `accessToken`，避免认证流程退出登录后 Token 失效 |
| `Create Resource` | 合法文件、分类和资料参数创建成功 |
| `Create Resource Without Token Should Fail` | 未登录创建资料返回 `40101` |
| `Create Resource File Not Found Should Fail` | 文件不存在返回 `40401` |
| `Create Resource Category Not Found Should Fail` | 分类不存在返回 `40401` |
| `Create Resource Duplicate Should Fail` | 同一用户重复提交同一文件返回 `40002` |
| `Public Resource Detail` | 匿名查询已审核通过资料详情 |
| `Public Pending Resource Detail Should Fail` | 匿名查询待审核资料返回 `40901` |
| `My Resources` | 登录用户查询自己的上传资料分页 |
| `My Resources Invalid Page Should Fail` | 非法分页参数返回 `40001` |

---

## 21. 修改文件记录

当前已修改或新增：

| 文件 | 说明 |
| --- | --- |
| `campus-resource-platform/src/main/java/com/john/campus/entity/Resource.java` | 新增资料实体，映射 `resource` 表并封装状态/类型常量 |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/ResourceMapper.java` | 新增资料表 Mapper 接口，定义资料插入和查询方法 |
| `campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml` | 新增资料表 MyBatis XML，维护字段映射和基础 SQL |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/CategoryMapper.java` | 新增按 ID 查询启用分类的方法 |
| `campus-resource-platform/src/main/resources/mapper/CategoryMapper.xml` | 新增按 ID 查询启用分类 SQL |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/FileInfoMapper.java` | 新增按 ID 查询正常文件的方法 |
| `campus-resource-platform/src/main/resources/mapper/FileInfoMapper.xml` | 新增按 ID 查询正常文件 SQL |
| `campus-resource-platform/src/main/java/com/john/campus/dto/ResourceCreateDTO.java` | 新增创建资料请求 DTO，并添加基础参数校验 |
| `campus-resource-platform/src/main/java/com/john/campus/vo/ResourceCreateVO.java` | 新增创建资料响应 VO |
| `campus-resource-platform/src/main/java/com/john/campus/vo/ResourceDetailVO.java` | 新增公开资料详情响应 VO |
| `campus-resource-platform/src/main/java/com/john/campus/vo/MyResourceVO.java` | 新增我的上传资料列表项 VO |
| `campus-resource-platform/src/main/java/com/john/campus/service/ResourceService.java` | 新增资料业务接口，定义创建资料、公开详情和我的上传列表方法 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceServiceImpl.java` | 新增资料业务实现，完成创建资料和查询主流程 |
| `campus-resource-platform/src/main/java/com/john/campus/controller/ResourceController.java` | 新增资料接口入口，提供创建资料、公开详情和我的上传列表接口 |
| `campus-resource-platform/src/main/java/com/john/campus/config/WebMvcConfig.java` | 放行公开资料详情路径 `/api/v1/resources/*`，同时保留创建资料接口登录保护 |
| `campus-resource-platform/pom.xml` | 新增 H2 测试依赖，用于本地可重复的数据库集成测试 |
| `campus-resource-platform/src/test/java/com/john/campus/controller/ResourceControllerTest.java` | 新增资料 Controller 层 MockMvc 测试，覆盖核心接口、鉴权路径和异常映射 |
| `campus-resource-platform/src/test/java/com/john/campus/service/ResourceDatabaseIntegrationTest.java` | 新增资料数据库集成测试，覆盖真实 SQL 写入、读取、分页、重复提交和关联校验 |
| `campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql` | 新增资料模块测试用最小表结构，供 H2 MySQL 模式初始化数据库 |
| `docs/api/postman/campus-resource-platform.postman_collection.json` | 新增资料模块 Postman 分组，覆盖创建、公开详情、我的上传列表和异常场景 |
| `docs/modules/04-resource-development-process.md` | 记录资料模块完整开发流程、接口、表、测试、文件清单和后续方向 |
| `docs/06-project-progress.md` | 同步资料模块首版完成状态，并将审核模块列为下一阶段建议 |
| `docs/api/api-reference.md` | 按真实 Controller、DTO、VO 和错误码校准资料模块接口说明 |
| `README.md` | 同步资料模块首版完成状态、测试命令和下一阶段建议 |
| `docs/database/database-change-log.md` | 记录资料模块复用 `resource`、`file_info`、`category` 表，无生产库结构变更 |

资料模块首版修改文件清单已补齐，后续进入审核模块时应新建对应模块开发流程记录。

---

## 22. 与其他模块的关系

- 依赖认证模块：创建资料和我的上传列表需要当前登录用户。
- 依赖分类查询模块：创建资料需要校验 `categoryId`。
- 依赖文件上传模块：创建资料需要已存在且正常的 `fileId`。
- 支撑审核模块：审核模块以 `resource.status = 0` 的资料作为待审核数据。
- 支撑搜索模块：搜索模块只查询 `status = 1` 的资料。
- 支撑下载模块：下载模块根据 `resource.file_id` 找到真实文件。
- 支撑收藏模块：收藏模块以 `resource.id` 建立用户收藏关系。

---

## 23. 面试可讲点

- 为什么文件上传和资料创建拆成两个模块：物理文件与业务资料解耦，便于秒传、复用、审核和后续对象存储扩展。
- 为什么新资料默认待审核：平台内容需要状态机控制，避免未审核资料直接进入搜索和下载。
- 如何防止越权创建资料：上传者取 `UserContextHolder`，不接受前端传入 `uploaderId`。
- 如何校验资源可见性：公开详情只返回 `APPROVED` 状态，其他状态只在我的上传列表中展示给上传者。
- 如何避免重复提交：同一用户、同一文件、待审核或已通过资料需要业务校验。
- 为什么首版不急着接 Redis：资料创建是低频核心写链路，先保证 MySQL 状态正确，再做详情缓存增强。

---

## 24. 后续优化方向

- 接入 `crp:cache:resource:detail:{resourceId}` 资料详情缓存。
- 审核通过后初始化热门资料排行榜分数。
- 资料详情增加浏览次数统计。
- 资料标签从逗号字符串演进为独立标签表。
- 增加资料重新提交或修改后再次审核流程。
- 对上传资料数量或频率增加 Redis 限流。
- 增加资源可见性统一策略类，供详情、搜索、下载、收藏复用。

---

## 25. Git commit message 建议

```text
feat(resource): complete resource module MVP

- add resource entity, mapper, service and controller
- support creating pending resources from uploaded files
- support public approved detail and my resource pagination
- add controller and database integration tests
- sync API, progress, database and module docs
```

---

## 26. 分步骤开发提示词

> 使用说明：以下提示词是本轮资料模块首版开发已使用并校准过的分步提示词，可作为复盘记录，也可在重建同类模块时复用。若再次执行，请一次只复制一条提示词给 Agent，完成并验证后再进入下一步。

### 步骤 1：创建 Resource 实体

提示词：

```text
请为资料模块创建 Resource 实体。

本步目标：
- 创建 `campus-resource-platform/src/main/java/com/john/campus/entity/Resource.java`，映射 MySQL `resource` 表。
- 继承 `BaseEntity`，字段与 `sql/init.sql` 中的 `resource` 表保持一致。
- 增加资料状态常量：`STATUS_PENDING_REVIEW = 0`、`STATUS_APPROVED = 1`、`STATUS_REJECTED = 2`、`STATUS_OFFLINE = 3`、`STATUS_DELETED = 4`。
- 增加资料类型常量：1课件、2笔记、3真题、4实验报告、5课程设计、99其他。
- 添加必要的状态判断方法，例如 `isApproved()`、`isVisibleToPublic()`。

涉及文件或类：
- `entity/Resource.java`
- `entity/BaseEntity.java`（只复用，不修改）
- `sql/init.sql`（只核对字段，不修改）

完成标准：
- `Resource` 字段与 `resource` 表字段能一一对应。
- 状态和类型常量没有魔法值散落风险。
- 关键字段、状态常量和非显而易见的判断方法有简洁中文注释。

本步不做什么：
- 不创建 Mapper、Service、Controller。
- 不修改数据库结构。
- 不实现审核、搜索、下载、收藏。
- 不更新接口文档，除非发现当前规划与真实表结构不一致。
```

### 步骤 2：创建 ResourceMapper

提示词：

```text
请为资料模块创建 ResourceMapper 接口和 ResourceMapper.xml。

本步目标：
- 创建 `mapper/ResourceMapper.java` 和 `src/main/resources/mapper/ResourceMapper.xml`。
- 实现 `insert(Resource resource)`，插入资料并回填自增 ID。
- 实现 `selectPublicDetailById(Long id)`，只查询 `status = 1` 的公开资料详情所需字段。
- 实现 `selectByUploader(...)` 和 `countByUploader(...)`，支持“我的上传资料”分页和可选状态筛选。
- 实现 `countActiveByUploaderAndFileId(Long uploaderId, Long fileId)`，用于校验同一用户重复提交同一文件的待审核或已通过资料。

涉及文件或类：
- `mapper/ResourceMapper.java`
- `src/main/resources/mapper/ResourceMapper.xml`
- `entity/Resource.java`
- `common/PageQuery.java`、`common/PageResult.java`（只参考分页风格）

完成标准：
- XML 中提供清晰的 `resultMap` 和公共列清单。
- SQL 查询使用已有索引方向：`idx_resource_uploader_status`、`idx_resource_status_created`、`idx_resource_category_status`。
- 重复提交校验仅统计当前用户、同一 `file_id`、状态为待审核或已通过的资料。
- 编译不报 Mapper 方法或 XML 绑定错误。

本步不做什么：
- 不实现 Service 和 Controller。
- 不新增表、字段、索引。
- 不把 Entity 直接作为接口响应返回。
```

### 步骤 3：补充分类与文件校验查询

提示词：

```text
请为资料创建流程补充必要的分类和文件校验查询能力。

本步目标：
- 为 `CategoryMapper` / `CategoryMapper.xml` 增加按 ID 查询启用分类的方法，例如 `selectEnabledById(Long id)`，查询条件必须包含 `status = 1`。
- 为 `FileInfoMapper` / `FileInfoMapper.xml` 增加按 ID 查询正常文件的方法，例如 `selectNormalById(Long id)`，查询条件必须包含 `status = 1`。
- 这些方法供资料创建 Service 校验 `categoryId` 和 `fileId` 使用。

涉及文件或类：
- `mapper/CategoryMapper.java`
- `src/main/resources/mapper/CategoryMapper.xml`
- `mapper/FileInfoMapper.java`
- `src/main/resources/mapper/FileInfoMapper.xml`
- `entity/Category.java`
- `entity/FileInfo.java`

完成标准：
- 分类查询只返回启用分类。
- 文件查询只返回正常文件。
- 不破坏分类模块和文件上传模块已有方法。
- XML 命名和 Java Mapper 方法一一对应。

本步不做什么：
- 不实现分类管理接口。
- 不修改文件上传流程。
- 不新增 Redis 缓存。
```

### 步骤 4：创建 DTO 与 VO

提示词：

```text
请为资料模块创建请求 DTO 和响应 VO。

本步目标：
- 创建 `dto/ResourceCreateDTO.java`，用于 `POST /api/v1/resources`。
- 创建 `vo/ResourceCreateVO.java`，用于创建资料响应。
- 创建 `vo/ResourceDetailVO.java`，用于公开资料详情响应。
- 创建 `vo/MyResourceVO.java`，用于我的上传资料列表项。
- `ResourceCreateDTO` 使用 `jakarta.validation` 添加参数校验。
- `tags` 可以在 DTO 中使用 `List<String>`，后续由 Service 转换为逗号分隔字符串存入 `resource.tags`。

涉及文件或类：
- `dto/ResourceCreateDTO.java`
- `vo/ResourceCreateVO.java`
- `vo/ResourceDetailVO.java`
- `vo/MyResourceVO.java`
- `common/PageResult.java`（只复用，不修改）

完成标准：
- DTO 覆盖 `fileId`、`title`、`description`、`categoryId`、`courseName`、`resourceType`、`tags`。
- VO 不直接暴露 `Resource` Entity。
- 公开详情 VO 不返回 `file_info.storage_path`、`stored_name` 等敏感或内部存储字段。
- 参数校验错误可由现有 `GlobalExceptionHandler` 统一转为 `40001 PARAM_ERROR`。

本步不做什么：
- 不实现 Service。
- 不新增接口。
- 不处理审核、搜索、下载、收藏字段的写逻辑。
```

### 步骤 5：实现创建资料 Service

提示词：

```text
请实现资料模块的创建资料业务逻辑。

本步目标：
- 创建 `service/ResourceService.java` 和 `service/impl/ResourceServiceImpl.java`。
- 在 `ResourceService` 中定义创建资料方法，例如 `ResourceCreateVO create(ResourceCreateDTO dto)`。
- 在 `ResourceServiceImpl` 中完成文件校验、分类校验、重复提交校验和 `resource` 入库。
- 上传者必须来自 `UserContextHolder.getRequiredUserId()`，不能来自前端请求参数。
- 新资料默认设置为 `status = 0 PENDING_REVIEW`。
- 使用 `@Transactional(rollbackFor = Exception.class)` 包裹创建资料写入流程。

涉及文件或类：
- `service/ResourceService.java`
- `service/impl/ResourceServiceImpl.java`
- `mapper/ResourceMapper.java`
- `mapper/FileInfoMapper.java`
- `mapper/CategoryMapper.java`
- `dto/ResourceCreateDTO.java`
- `vo/ResourceCreateVO.java`
- `common/UserContextHolder.java`
- `exception/BusinessException.java`
- `common/ErrorCode.java`

完成标准：
- `fileId` 不存在、文件非正常状态或当前用户没有 `user_file_authorization` 授权时返回同一类不可用文件异常，避免探测他人文件。
- `categoryId` 不存在或分类未启用时返回合适的业务异常。
- 同一用户对同一文件已有待审核或已通过资料时，返回 `DATA_DUPLICATE`。
- 创建成功后返回 `resourceId`、`fileId`、`status`、`statusName` 和提示信息。
- 复杂逻辑留在 Service，不写到 Controller。

本步不做什么：
- 不实现 Controller。
- 不实现公开详情和我的上传列表查询。
- 不修改 `file_info.ref_count` 语义。
- 不触发审核、搜索索引、排行榜或消息队列。
```

### 步骤 6：实现查询 Service

提示词：

```text
请实现资料公开详情和我的上传列表查询业务逻辑。

本步目标：
- 在 `ResourceService` 中增加公开详情查询方法，例如 `ResourceDetailVO getPublicDetail(Long resourceId)`。
- 在 `ResourceService` 中增加我的上传列表查询方法，例如 `PageResult<MyResourceVO> listMyResources(Integer status, PageQuery pageQuery)`。
- 公开详情只允许返回 `status = 1 APPROVED` 的资料。
- 我的上传列表只能查询当前登录用户上传的资料，支持按状态筛选和分页。
- 查询结果需要转换为 VO，不直接返回 Entity。

涉及文件或类：
- `service/ResourceService.java`
- `service/impl/ResourceServiceImpl.java`
- `mapper/ResourceMapper.java`
- `vo/ResourceDetailVO.java`
- `vo/MyResourceVO.java`
- `common/PageQuery.java`
- `common/PageResult.java`
- `common/UserContextHolder.java`

完成标准：
- 查询不存在的资料返回 `RESOURCE_NOT_FOUND`。
- 公开详情遇到未审核通过资料时返回 `RESOURCE_STATUS_INVALID` 或按项目既有约定处理为不可见。
- 我的上传列表分页参数有边界校验。
- 状态筛选只允许 `0、1、2、3、4`。

本步不做什么：
- 不实现搜索接口。
- 不实现下载地址或文件流返回。
- 不实现收藏状态、是否已收藏字段。
- 不接入 Redis 资料详情缓存。
```

### 步骤 7：实现 Controller 与鉴权路径

提示词：

```text
请实现资料模块 Controller，并配置公开详情接口的鉴权路径。

本步目标：
- 创建 `controller/ResourceController.java`。
- 实现 `POST /api/v1/resources`，创建资料，需要登录。
- 实现 `GET /api/v1/resources/{resourceId}`，公开资料详情，不需要登录，但只返回已审核通过资料。
- 实现 `GET /api/v1/users/me/resources`，我的上传资料列表，需要登录。
- 更新 `WebMvcConfig`，仅排除公开详情路径，例如 `/api/v1/resources/*`，不要排除 `/api/v1/resources`。

涉及文件或类：
- `controller/ResourceController.java`
- `config/WebMvcConfig.java`
- `service/ResourceService.java`
- `common/ApiResponse.java`
- `dto/ResourceCreateDTO.java`
- `common/PageQuery.java`

完成标准：
- Controller 方法返回 `ApiResponse` 包装结果。
- `POST /api/v1/resources` 和 `GET /api/v1/users/me/resources` 仍受 JWT 拦截器保护。
- `GET /api/v1/resources/{resourceId}` 可匿名访问。
- Controller 不直接访问 Mapper，不写复杂业务逻辑。

本步不做什么：
- 不新增管理员审核接口。
- 不开放搜索、下载、收藏接口。
- 不改变认证、分类、文件上传已有接口路径。
```

### 步骤 8：补充接口测试与验证

提示词：

```text
请为资料模块补充接口测试与验证记录。

本步目标：
- 优先使用现有测试方式验证项目能编译通过。
- 补充或更新 Postman 请求示例，覆盖资料创建、公开详情、我的上传列表。
- 若适合自动化，补充 MockMvc 或 Spring Boot 测试；若暂不写自动化测试，需要在文档中说明原因和手工测试步骤。
- 测试至少覆盖成功、未登录、文件不存在、分类不存在、重复提交、公开详情状态不可见、我的上传分页、非法分页参数。

涉及文件或类：
- `docs/api/postman/campus-resource-platform.postman_collection.json`
- `docs/modules/04-resource-development-process.md`
- `campus-resource-platform/src/test/java/...`（如新增自动化测试）

完成标准：
- 至少执行 `.\mvnw.cmd -DskipTests compile` 或说明未执行原因。
- 手工测试用例有请求路径、请求参数和预期响应。
- 自动化测试或手工测试覆盖核心业务分支。

本步不做什么：
- 不为了测试引入新的重量级依赖。
- 不修改业务实现扩大模块范围。
- 不测试审核、搜索、下载、收藏模块。
```

### 步骤 9：同步接口文档与数据库记录

提示词：

```text
请同步资料模块相关文档，但不要修改业务代码。

本步目标：
- 根据当前真实代码更新 `docs/api/api-reference.md` 中资料模块接口说明。
- 根据当前真实代码更新 `docs/database/database-change-log.md`，说明资料模块是否新增数据库结构；若没有新增结构，写明复用 `resource`、`file_info`、`category` 表。
- 根据当前真实代码更新 `docs/06-project-progress.md` 中资料模块状态、已实现接口、涉及表和待办项。
- 如 README 的当前完成内容已落后，同步更新 README。

涉及文件：
- `docs/api/api-reference.md`
- `docs/database/database-change-log.md`
- `docs/06-project-progress.md`
- `README.md`

完成标准：
- 文档中的接口路径、HTTP 方法、请求/响应字段与真实 Controller 保持一致。
- 文档不把未实现功能写成已完成。
- 数据库记录明确“新增结构”或“复用已有结构”。

本步不做什么：
- 不修改 Java 代码。
- 不新增接口规划以外的模块内容。
- 不编造 Redis Key 或数据库字段。
```

### 步骤 10：更新本模块开发流程文档

提示词：

```text
请根据当前真实代码更新资料模块开发流程文档。

本步目标：
- 更新 `docs/modules/04-resource-development-process.md`。
- 补全“当前状态”“已完成事项”“待完成事项”“测试清单”“修改文件记录”“与其他模块的关系”“面试可讲点”“后续优化方向”。
- 如果实现过程中接口、类名、方法名、字段名与规划不一致，以当前真实代码为准修正文档。
- 保留“分步骤开发提示词”小节，并根据实际开发顺序校准下一轮可复制提示词。

涉及文件：
- `docs/modules/04-resource-development-process.md`

完成标准：
- 文档能回答：本模块解决什么问题、有哪些接口、调用链路是什么、涉及哪些表、是否使用 Redis、如何权限控制、如何测试。
- 文档不含“已规划但伪装成已完成”的内容。
- 文档记录本模块真实修改文件清单和测试结果。

本步不做什么：
- 不修改业务代码。
- 不扩展审核、搜索、下载、收藏、排行榜模块。
- 不删除已有文档章节。
```
