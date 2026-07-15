# 下载模块开发流程文档

> 本文档遵循 `docs/AGENTS.md` 第 24 节《模块开发流程文档规范》生成。
> 当前状态：**首版主功能已完成（步骤 1–8、10–11 已完成，步骤 9 测试待补充）**。已落地 `DownloadRecord` 实体、`DownloadRecordMapper`、`RedisKeyConstants` 下载 Key、`DownloadRateLimiter` 限流器、`DownloadService` + `DownloadServiceImpl`、`DownloadTicketVO` / `MyDownloadRecordVO`、`DownloadController` 三接口、`FileStorageService.loadAsResource` 文件流读取和所有相关文档同步。待补充下载模块针对性测试（按新规范每小功能须先测试后提交）。
> 已完成部分的编译与全量 49 个已有测试均通过。

---

## 1. 模块基本信息

| 项 | 内容 |
| --- | --- |
| 模块名称 | 下载模块 |
| 英文标识 | download |
| 文档路径 | `docs/modules/07-download-development-process.md` |
| 当前分支 | `dev` |
| 当前状态 | 开发中，已完成文档初稿、下载记录实体与 Mapper、下载 Redis Key 常量 |
| 前置依赖模块 | 用户认证模块、资料模块、文件上传模块、审核模块、搜索模块 |
| 下游模块 | 排行榜与定时任务模块（消费下载量增量和热度分） |
| 接口前缀 | `/api/v1/resources/{resourceId}/download-records`、`/api/v1/download-records`、`/api/v1/users/me/download-records` |

> 编号说明：搜索模块已占用 `06-search-development-process.md`，因此下载模块使用 `07-`。这与 `docs/AGENTS.md` 第 24 节示例中的 `05-download-development-process.md` 编号不同，以避免与已存在的搜索模块文档冲突，一切以真实文件为准。

---

## 2. 模块目标

下载模块负责让登录用户在资料通过审核后安全下载文件，并把下载行为沉淀为可统计、可风控的后端能力。

首版目标（规划）：

- 实现「创建下载记录并获取下载地址」接口，校验资料可下载状态并写入 `download_record`。
- 实现「下载文件流」接口，按下载记录返回物理文件二进制流。
- 实现「获取我的下载记录」分页查询接口。
- 接入 Redis 下载限流（按用户、按 IP），防止刷下载量。
- 接入 Redis 下载量临时统计（Hash 增量）和同资料重复下载去重。
- 为后续排行榜模块的热度分和下载量定时同步预留 Redis Key。

本模块要体现的核心价值：下载不是简单的「读文件返回」，而是**权限校验 + 状态校验 + 限流 + 去重计数 + 记录落库 + 文件流返回 + Redis 与 MySQL 最终一致**的组合能力。

---

## 3. 需求分析

1. 搜索模块已经能让用户发现 `status = 1 APPROVED` 资料，下载是搜索之后自然的公开消费入口（见 `docs/06-project-progress.md` 第 11 节）。
2. 只有审核通过（`APPROVED`）的资料可以下载；待审核、已拒绝、已下架、已删除资料必须拒绝下载，避免未审核内容外泄。
3. 下载要防刷：同一用户、同一 IP 高频下载需要限流，短时间重复下载同一资料不应重复计数下载量和热度。
4. 下载量是高频写场景，若每次下载都 `UPDATE resource.download_count` 会给 MySQL 造成压力，应先写 Redis Hash 增量，再由定时任务批量回写 MySQL。
5. 下载行为需要审计：写入 `download_record`，记录用户、资料、文件、IP、UA、成功或失败原因。
6. 物理文件与业务资料解耦：资料只持有 `file_id`，下载时需通过 `file_info` 定位真实存储路径，且不能把内部存储路径暴露给前端。

为什么不是简单 CRUD：下载模块集中体现下载权限与状态校验、Redis 滑动窗口限流、重复下载去重、下载量「先写 Redis 再同步 MySQL」的最终一致性设计、文件流安全返回，以及为排行榜联动铺路，全部是面试可讲的后端能力。

---

## 4. 本模块不做什么

- 首版不实现排行榜查询接口（热门资料榜属于排行榜模块）。
- 首版不实现下载量从 Redis 到 MySQL 的定时同步任务与分布式锁（`crp:lock:sync:download-delta`），该能力归排行榜与定时任务模块；下载模块只负责把增量写入 Redis Hash。
- 首版不实现热度分的最终计算公式与回写（`hot_score`），只负责在下载成功后对热度 ZSet 递增（是否纳入首版见开发任务拆分）。
- 首版不实现对象存储（MinIO/OSS）下载，只支持 `file_info.storage_type = 1` 本地存储。
- 首版不实现断点续传、分片下载、限速下载。
- 首版不修改 `download_record`、`resource`、`file_info` 三张表的表结构（表已在 `sql/init.sql` 存在）。
- 首版不改动其他模块已有接口路径与业务逻辑。

---

## 5. 涉及接口

> 以下接口设计来源于 `docs/api/api-reference.md` 第 8 节；**当前三个接口均已实现**，与真实代码一致。`docs/api/api-reference.md` 已同步更新。

### 5.1 创建下载记录并获取下载地址

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/v1/resources/{resourceId}/download-records` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员（登录即可） |
| 当前状态 | 已完成 |

路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 资料 ID |

响应数据（`data` 字段）：`downloadRecordId`、`downloadTicket`、`resourceId`、`fileId`、`downloadUrl`、`expireSeconds`、`counted`。

说明：

- 进入核心业务前执行 Redis 下载限流。
- 校验资料存在且为 `APPROVED`，否则拒绝。
- 写入 `download_record`（`download_status = 1`）。
- 通过去重 Key 判断本次是否计入下载量与热度，`counted` 反映结果。
- 下载量增量优先写 Redis Hash。

### 5.2 下载文件流

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/download-records/{downloadRecordId}/file` |
| 是否登录 | 是 |
| 权限要求 | 下载记录所属用户，并携带 `X-Download-Ticket` 一次性票据 |
| 当前状态 | 已完成 |

路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `downloadRecordId` | long | 是 | 下载记录 ID |

说明：

- 校验下载记录归属，非本人且非管理员返回 `40301`。
- 通过 `download_record.file_id` 关联 `file_info` 定位物理文件。
- 以文件二进制流返回，设置 `Content-Type` 和 `Content-Disposition`（下载文件名由 `file_info.original_name` 还原）。
- 不返回内部 `storage_path`、`stored_name`。

### 5.3 获取我的下载记录

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/users/me/download-records` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员，只查自己的记录 |
| 当前状态 | 已完成 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `pageNo` | int | 否 | 页码，默认 1 |
| `pageSize` | int | 否 | 每页数量，默认 10，最大 100 |

响应数据：`PageResult<MyDownloadRecordVO>`（VO 名称最终以代码为准）。

说明：只按当前登录用户 ID 查询，不接受前端传入 `userId`，避免越权。

---

## 6. 涉及数据库表

> 三张表均已在 `sql/init.sql` 中存在，本模块不新增、不修改表结构。

### 6.1 `download_record` 下载记录表（本模块主表，当前代码尚未使用）

真实字段（来自 `sql/init.sql`）：

| 字段 | 类型 | 使用场景 |
| --- | --- | --- |
| `id` | BIGINT 自增 | 下载记录主键，也是 `downloadRecordId` |
| `user_id` | BIGINT | 下载用户，来自登录上下文 |
| `resource_id` | BIGINT | 被下载资料 ID |
| `file_id` | BIGINT | 被下载文件 ID，下载文件流时定位物理文件 |
| `user_ip` | VARCHAR(45) | 用户 IP，兼容 IPv4/IPv6，用于风控和审计 |
| `user_agent` | VARCHAR(255) | 浏览器/客户端信息 |
| `download_status` | TINYINT | 下载状态：1 成功 2 失败 |
| `fail_reason` | VARCHAR(255) | 下载失败原因 |
| `created_at` | DATETIME | 下载时间 |

真实索引：

| 索引 | 作用 |
| --- | --- |
| `idx_download_user_created` | 按用户查下载记录并按时间排序（支撑「我的下载记录」） |
| `idx_download_resource_created` | 按资料查下载记录 |
| `idx_download_user_resource_created` | 按用户 + 资料查询，支撑去重与统计 |

约束：`chk_download_status CHECK (download_status IN (1, 2))`。

### 6.2 `resource` 资料表（读为主）

- 读 `status` 校验是否可下载（只允许 `STATUS_APPROVED = 1`）。
- 读 `file_id` 关联物理文件。
- `download_count` 由下载量 Redis 增量在后续定时任务中同步回写（本模块首版不直接高频 `UPDATE`）。

### 6.3 `file_info` 文件信息表（读为主）

- 读 `storage_path` 定位物理文件（仅 `storage_type = 1` 本地存储）。
- 读 `original_name`、`file_ext`、`mime_type` 还原下载文件名和 `Content-Type`。
- 读 `status` 确认文件正常（`STATUS_NORMAL = 1`），已删除文件不可下载。

---

## 7. 涉及 Redis Key

> 以下 Key 设计来源于 `docs/05-redis-design.md` 第 7、8 节。当前 `RedisKeyConstants` 已补充下载相关常量与格式化方法，后续限流器和下载 Service 必须复用这些常量，禁止在业务代码中硬编码完整 Redis Key。

| Key | 数据结构 | 用途 | TTL | 更新时机 |
| --- | --- | --- | --- | --- |
| `crp:rate:download:user:{userId}` | ZSet | 按用户下载限流（滑动窗口） | 限流窗口 + 60 秒 | 每次下载请求进入核心业务前 |
| `crp:rate:download:ip:{ip}` | ZSet | 按 IP 下载限流（滑动窗口） | 限流窗口 + 60 秒 | 每次下载请求进入核心业务前 |
| `crp:dedup:download:{userId}:{resourceId}` | String | 同用户同资料重复下载去重 | 10–30 分钟 | 首次计数后写入 |
| `crp:stats:resource:download:delta` | Hash | 下载量临时增量（field = resourceId） | 不主动设置 TTL | 下载成功且通过去重判断后 `HINCRBY` |

限流规则建议（`docs/05-redis-design.md` 第 7.3 节）：

| 规则 | 建议值 |
| --- | --- |
| 单用户下载限流 | 每分钟最多 10 次 |
| 单 IP 下载限流 | 每分钟最多 30 次 |
| 同一用户同资料计数去重 | 10–30 分钟内只统计一次下载量 |

限流实现要点：滑动窗口用 ZSet，`ZREMRANGEBYSCORE` 清窗口外记录 → `ZCARD` 统计窗口内数量 → 超阈值拒绝 → 否则 `ZADD` 当前请求 → `EXPIRE` 刷新 TTL，全过程用 Lua 脚本保证原子性。

一致性策略：

- 限流数据是临时风控数据，不同步 MySQL。
- 下载量增量 `delta` Hash **不设 TTL**，由后续定时任务同步 MySQL 后主动 `HDEL`，避免任务异常时统计丢失。
- 下载失败不写下载量增量。

排行榜联动（`docs/05-redis-design.md` 第 8.5 节）：下载成功计数时可对 `crp:rank:resource:hot:{period}` 执行 `ZINCRBY +5`。该 Key 归排行榜模块，是否纳入下载模块首版见第 17 节开发任务拆分。

---

## 8. 涉及核心类

### 8.1 复用已存在类（真实存在）

| 类型 | 类 | 作用 |
| --- | --- | --- |
| common | `ApiResponse` | 统一响应 |
| common | `ErrorCode` | 统一错误码 |
| common | `PageResult` | 分页响应 |
| common | `UserContextHolder` | 获取当前登录用户 ID/角色（`getRequiredUserId()`） |
| common | `LoginUser` | 登录用户快照，`isAdmin()` 判断管理员 |
| common | `RedisKeyConstants` | 已补充下载限流、下载去重、下载量增量统计 Key 常量与生成方法 |
| dto | `PageQuery` | 通用分页请求（pageNo/pageSize/offset） |
| entity | `Resource` | 资料实体，`STATUS_APPROVED`、`isApproved()` |
| entity | `FileInfo` | 文件实体，`STATUS_NORMAL`、`isNormal()`、`storagePath` |
| mapper | `ResourceMapper` | 已有 `selectById`，可复用做状态校验 |
| mapper | `FileInfoMapper` | 已有 `selectNormalById`，可复用定位物理文件 |
| service | `FileStorageService` | 已有落盘/删除能力；下载读流能力当前**未提供**，见下 |
| exception | `BusinessException`、`GlobalExceptionHandler` | 业务异常与统一异常处理 |
| config | `WebMvcConfig` | 拦截器与放行路径配置 |

### 8.2 新增或修改类（按当前真实代码状态）

| 类型 | 类 | 职责 |
| --- | --- | --- |
| entity | `DownloadRecord` | ✅ 已存在，对应 `download_record` 表，含 `STATUS_SUCCESS=1`/`STATUS_FAIL=2` 常量 |
| mapper | `DownloadRecordMapper` + `DownloadRecordMapper.xml` | ✅ 已存在，insert/selectById/selectByUser/countByUser |
| vo | `DownloadTicketVO` | ✅ 已创建，record(downloadRecordId, resourceId, fileId, downloadUrl, expireSeconds, counted) |
| vo | `MyDownloadRecordVO` | ✅ 已创建，record(downloadRecordId, resourceId, title, fileId, downloadStatus, createdAt) |
| service | `DownloadRateLimiter`（接口） | ✅ 已创建，checkDownloadLimit / checkUserLimit / checkIpLimit |
| service/impl | `DownloadRateLimiterImpl` | ✅ 已创建，ZSet 滑动窗口 + Lua 原子限流 |
| service | `DownloadService`（接口） | ✅ 已创建，createDownloadRecord / loadFile / listMyDownloadRecords + DownloadFileInfo record |
| service/impl | `DownloadServiceImpl` | ✅ 已创建，注入 FileStorageService，编排限流→校验→记录写入→去重计数→文件流读取 |
| service | `FileStorageService` | ✅ 已修改，新增 loadAsResource 方法 + FileResource record |
| service/impl | `FileStorageServiceImpl` | ✅ 已修改，实现 loadAsResource，含路径穿越防护和文件存在/可读校验 |
| controller | `DownloadController` | ✅ 已创建，三接口入口，文件流返回 ResponseEntity\<InputStreamResource\> |
| common | `RedisKeyConstants` | ✅ 已补充 DOWNLOAD_RATE_USER/IP/DEDUP/DELTA 常量及格式化方法 |

> 记忆约束：本项目所有 Spring Service（含基础设施/工具类服务）必须「接口 + 实现」。限流器（`DownloadRateLimiter` 接口 + `DownloadRateLimiterImpl`）和文件存储服务（`FileStorageService` 接口 + `FileStorageServiceImpl`）均遵守此约束。

> 关于文件流读取：已在 `FileStorageService` 接口补充 `loadAsResource(String storagePath)` 方法，并在 `FileStorageServiceImpl` 实现。入参为 `file_info.storage_path` 绝对路径，内部做 `toAbsolutePath().normalize()` + `startsWith(storageRoot)` 路径穿越防护，文件不存在或不可读时抛 `BusinessException`。

---

## 9. 模块内部调用关系（已实现）

```text
DownloadController
  ├── POST /resources/{id}/download-records
  │     └── DownloadService.createDownloadRecord(resourceId)
  │           ├── UserContextHolder（取当前用户 ID / 角色）
  │           ├── DownloadRateLimiter（用户 + IP 滑动窗口限流，Lua）
  │           ├── ResourceMapper.selectById（校验存在 + APPROVED）
  │           ├── FileInfoMapper.selectNormalById（校验文件正常）
  │           ├── DownloadRecordMapper.insert（写下载记录）
  │           ├── 去重 Key crp:dedup:download:{userId}:{resourceId}
  │           └── 下载量增量 HINCRBY crp:stats:resource:download:delta
  │
  ├── GET /download-records/{id}/file
  │     └── DownloadService.loadFile(downloadRecordId)
  │           ├── DownloadRecordMapper.selectById（校验归属）
  │           ├── FileInfoMapper.selectNormalById（定位物理文件）
  │           └── FileStorageService.loadAsResource(storagePath)（待补充）
  │
  └── GET /users/me/download-records
        └── DownloadService.listMyDownloadRecords(pageQuery)
              ├── DownloadRecordMapper.selectByUser
              └── DownloadRecordMapper.countByUser
```

Controller 只负责接收请求、取路径参数、绑定分页参数、返回统一响应；限流、状态校验、去重、计数、记录写入等业务全部在 `DownloadServiceImpl`。

---

## 10. 请求处理流程（已实现）

### 10.1 创建下载记录并获取下载地址

1. JWT 拦截器校验登录（该路径不在 `WebMvcConfig` 放行列表，天然需要登录）。
2. `DownloadController` 接收 `resourceId`，从请求中取客户端 IP。
3. `DownloadServiceImpl` 通过 `UserContextHolder.getRequiredUserId()` 取当前用户。
4. 执行 Redis 下载限流（用户 + IP，滑动窗口 Lua）；超限抛 `RATE_LIMITED(42901)`。
5. `ResourceMapper.selectById` 校验资料存在；不存在抛 `RESOURCE_NOT_FOUND(40401)`。
6. 校验资料 `isApproved()`；非通过抛 `RESOURCE_STATUS_INVALID(40901)`。
7. `FileInfoMapper.selectNormalById` 校验文件存在且正常；否则抛 `RESOURCE_NOT_FOUND(40401)` 或 `SERVER_ERROR(50001)`。
8. 写入 `download_record`（`download_status = 1`），回填自增 `id`。
9. 查询去重 Key：不存在则本次计入下载量与热度，写入 `delta` Hash（`HINCRBY +1`）并设去重 Key + TTL，`counted = true`；已存在则 `counted = false`。
10. 组装 `DownloadTicketVO`（含 `downloadUrl` 指向 5.2 接口）返回。

### 10.2 下载文件流

1. JWT 拦截器校验登录。
2. `DownloadController` 接收 `downloadRecordId`。
3. `DownloadServiceImpl` 查下载记录；不存在抛 `RESOURCE_NOT_FOUND(40401)`。
4. 校验归属：记录 `user_id` 等于当前用户，或当前用户 `isAdmin()`；否则抛 `FORBIDDEN(40301)`。
5. 通过 `file_id` 查 `file_info`，定位本地物理文件。
6. 读取文件流，设置 `Content-Type`、`Content-Disposition`（文件名用 `original_name`，注意编码），返回二进制流。

### 10.3 获取我的下载记录

1. JWT 拦截器校验登录。
2. `DownloadController` 绑定 `PageQuery`。
3. `DownloadServiceImpl` 用当前用户 ID 调 `countByUser` + `selectByUser`。
4. 转 `MyDownloadRecordVO` 列表，封装 `PageResult` 返回。

---

## 11. 数据流转流程（规划）

```text
用户点击下载
  → DownloadController 取 resourceId + 客户端 IP
  → DownloadServiceImpl 取当前用户
  → Redis 限流（用户 + IP 滑动窗口，Lua 原子）
  → ResourceMapper 校验 APPROVED
  → FileInfoMapper 校验文件正常
  → download_record 落库（成功记录）
  → 去重 Key 判断是否计数
       ├── 未计数过：HINCRBY delta +1，写去重 Key，counted=true
       └── 已计数过：counted=false
  → 返回 DownloadTicketVO（含 downloadUrl）
  → 用户请求下载文件流
  → 校验下载记录归属
  → file_info 定位物理文件
  → 返回文件二进制流
  → （后续定时任务）读取 delta Hash 批量回写 resource.download_count 后 HDEL
```

可下载状态隔离：

```text
PENDING_REVIEW(0) 不可下载 → 40901
APPROVED(1)       可下载
REJECTED(2)       不可下载 → 40901
OFFLINE(3)        不可下载 → 40901
DELETED(4)        不可下载 → 40901
```

---

## 12. 权限校验（规划）

- 下载相关接口全部需要登录，路径**不加入** `WebMvcConfig.excludePathPatterns`，由 JWT 拦截器保证登录态。
- 创建下载记录：登录即可（学生或管理员）。
- 下载文件流：只能是下载记录所属用户或管理员，`DownloadServiceImpl` 通过 `LoginUser.isAdmin()` 与记录 `user_id` 兜底校验，防止越权下载他人下载记录。
- 我的下载记录：只按 `UserContextHolder.getRequiredUserId()` 查询，不接受前端传 `userId`。
- 需要注意 `/api/v1/resources/{resourceId}/download-records` 是 `/api/v1/resources/*` 之下的两段式路径，`WebMvcConfig` 当前只放行一段式 `/api/v1/resources/*`（用于公开详情），两段式的 download-records 不会被匿名放行——需在实现时确认拦截器路径匹配符合预期，保证下载创建接口仍要求登录。

---

## 13. 参数校验（规划）

| 参数 | 规则 | 失败错误码 |
| --- | --- | --- |
| `resourceId` | 路径参数，必须为正整数 | `40001` / 404 路由 |
| `downloadRecordId` | 路径参数，必须为正整数 | `40001` / 404 路由 |
| `pageNo` | 大于等于 1（复用 `PageQuery`） | `40001` |
| `pageSize` | 1–100（复用 `PageQuery`） | `40001` |

业务级校验（非纯参数）：资料是否存在、是否 `APPROVED`、文件是否正常、下载记录归属，均在 Service 层完成并映射到对应错误码。

---

## 14. 异常处理（规划）

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 未登录 | `40101 UNAUTHORIZED` | JWT 拦截器 / `UserContextHolder.getRequired()` |
| 分页参数非法 | `40001 PARAM_ERROR` | `PageQuery` Bean Validation |
| 资料不存在 | `40401 RESOURCE_NOT_FOUND` | `ResourceMapper.selectById` 返回 null |
| 资料未通过审核或已下架 | `40901 RESOURCE_STATUS_INVALID` | 非 `APPROVED` |
| 下载记录不存在 | `40401 RESOURCE_NOT_FOUND` | 文件流接口 |
| 无权访问该下载记录 | `40301 FORBIDDEN` | 非本人且非管理员 |
| 下载过于频繁 | `42901 RATE_LIMITED` | Redis 限流触发（`ErrorCode.RATE_LIMITED` 已存在） |
| 文件不存在或读取失败 | `50001 SERVER_ERROR` | 物理文件缺失或 IO 异常，由全局异常兜底 |

`ErrorCode` 现状核对：`RESOURCE_NOT_FOUND(40401)`、`RESOURCE_STATUS_INVALID(40901)`、`FORBIDDEN(40301)`、`RATE_LIMITED(42901)`、`SERVER_ERROR(50001)`、`UNAUTHORIZED(40101)`、`PARAM_ERROR(40001)` **均已存在**，下载模块无需新增错误码。

---

## 15. 事务处理（规划）

- 创建下载记录接口涉及一次 MySQL 写（`download_record` 插入），单表写入可不显式开启事务；若后续把「写记录 + 更新统计快照」合并为多表 MySQL 写，再评估 `@Transactional(rollbackFor = Exception.class)`。
- Redis 操作（限流、去重、下载量增量）**不纳入 MySQL 事务**：Redis 与 MySQL 的一致性通过「先写 Redis 增量、定时任务回写 MySQL、成功后 `HDEL`」的最终一致方案保证，不能依赖数据库事务。
- 关键约束（`docs/AGENTS.md` 第 11 节）：不要在事务中执行耗时文件 IO。文件流读取不应放在任何数据库事务内。
- 下载失败（IO 异常等）应记录 `download_status = 2` + `fail_reason`，且失败不写下载量增量。

---

## 16. 核心实现步骤（规划）

1. 创建下载模块开发流程文档初稿（**本步已完成**）。
2. 创建 `DownloadRecord` 实体、`DownloadRecordMapper` 接口与 XML（复用已存在的 `download_record` 表，不改表结构）。
3. 为 `RedisKeyConstants` 补充下载限流、去重、下载量增量 Key 常量与生成方法（**本步已完成**）。
4. 实现 `DownloadRateLimiter` 接口 + 实现（Redis 滑动窗口 + Lua 原子限流）。
5. 实现 `DownloadService` 接口 + `DownloadServiceImpl`：状态校验、限流、去重计数、记录写入、下载量增量。
6. 补充 `FileStorageService` 读文件流能力（或在下载模块内实现），支撑文件流返回。
7. 创建 DTO/VO：`DownloadTicketVO`、`MyDownloadRecordVO`。
8. 实现 `DownloadController` 三个接口。
9. 确认 `WebMvcConfig` 下载路径需要登录（不放行）。
10. 补充测试（Controller 测试、Service/Mapper 数据库集成测试、限流与去重测试）。
11. 同步 `docs/api/api-reference.md`、`docs/05-redis-design.md`、`docs/06-project-progress.md`、`README.md`。
12. 更新本模块开发流程文档。

---

## 17. 开发任务拆分（规划）

| 序号 | 任务 | 产出 | 状态 |
| --- | --- | --- | --- |
| T1 | 下载模块文档初稿 | `docs/modules/07-download-development-process.md` | 已完成 |
| T2 | 实体 + Mapper | `DownloadRecord`、`DownloadRecordMapper(.java/.xml)` | 已完成 |
| T3 | Redis Key 常量 | `RedisKeyConstants` 下载相关常量与方法 | 已完成 |
| T4 | 下载限流器 | `DownloadRateLimiter` + 实现（Lua 滑动窗口） | 待开发 |
| T5 | 下载 Service | `DownloadService` + `DownloadServiceImpl` | 待开发 |
| T6 | 文件流读取能力 | `FileStorageService` 读流方法（或模块内实现） | 待开发 |
| T7 | DTO / VO | `DownloadTicketVO`、`MyDownloadRecordVO` | 待开发 |
| T8 | Controller | `DownloadController` 三接口 | 待开发 |
| T9 | 测试 | Controller 测试 + 数据库集成测试 + 限流/去重测试 | 待开发 |
| T10 | 文档同步 | API、Redis、进度、README、模块流程文档 | 待开发 |

> 是否纳入首版待定：下载成功时对 `crp:rank:resource:hot:{period}` 的热度 `ZINCRBY`。该 Key 归排行榜模块，建议下载模块首版先只写 `download:delta`，热度 ZSet 联动在排行榜模块统一落地，避免跨模块职责混淆。实现前需与需求确认并回填文档。

---

## 18. 已完成事项

- 已阅读 `AGENTS.md` 与 `docs/AGENTS.md`，确认模块开发流程文档规范（第 14、24 节）。
- 已阅读 `docs/06-project-progress.md`，确认下载模块是当前推荐开发模块。
- 已阅读 `docs/api/api-reference.md` 第 8 节下载模块接口设计，确认三大接口路径与响应结构。
- 已阅读 `docs/05-redis-design.md` 第 7、8 节，确认下载限流、去重、下载量增量的 Key、数据结构、TTL 与一致性策略。
- 已核对 `sql/init.sql`，确认 `download_record`、`resource`、`file_info` 三表结构与索引真实存在。
- 已核对 `ErrorCode`，确认下载模块所需错误码全部已存在，无需新增。
- 已核对 `WebMvcConfig`，确认下载路径未被放行，天然需要登录。
- 【步骤 1】已生成本下载模块开发流程文档初稿。
- 【步骤 2】已创建 `DownloadRecord` 实体 + `DownloadRecordMapper` 接口 + XML（insert/selectById/selectByUser/countByUser）。
- 【步骤 3】已在 `RedisKeyConstants` 补充下载限流、去重、下载量增量常量及格式化方法。
- 【步骤 4】已创建 `DownloadRateLimiter` 接口 + `DownloadRateLimiterImpl`（ZSet 滑动窗口 + Lua，用户 10次/分、IP 30次/分）。
- 【步骤 5】已创建 `DownloadService` 接口 + `DownloadServiceImpl`（含 `createDownloadRecord`、`listMyDownloadRecords`、`tryCountDownload` 去重计数）。
- 【步骤 5】已创建 `DownloadTicketVO`(record) 和 `MyDownloadRecordVO`(record)。
- 【步骤 6】已在 `FileStorageService` 接口补充 `loadAsResource` 方法 + `FileResource` record，并在 `FileStorageServiceImpl` 实现（路径穿越防护 + 文件存在/可读校验）。
- 【步骤 8】已创建 `DownloadController` 三接口（POST 创建下载记录、GET 文件二进制流、GET 我的下载记录），并在 `DownloadService` 补充 `loadFile` 方法 + `DownloadFileInfo` record。
- 【步骤 10】已同步 `docs/api/api-reference.md` 第 8 节（三接口真实响应字段、文件流二进制返回说明、错误码）、`docs/05-redis-design.md`（第 7.8 节限流和去重实现状态、第 8.8 节增量统计实现状态）、`docs/06-project-progress.md`（新增第 6.7 节、更新第 8.1/9/10/11 节）、`README.md`（新增下载模块首版条目）。
- 【步骤 11】已更新本模块开发流程文档（当前条目）。
- 明确热度 ZSet 联动不纳入下载模块首版，归排行榜与定时任务模块。
- 明确下载量 Redis→MySQL 定时同步归排行榜与定时任务模块。
- 每步均通过 `.\mvnw.cmd -DskipTests compile` 编译验证，并通过 `.\mvnw.cmd test` 全量 49 个测试无回归。
- 每步均已完成 Git commit 并推送到 `origin/dev`。

---

## 19. 待完成事项

- 补充下载模块针对性测试（步骤 9）：Controller 测试、Service/Mapper 数据库集成测试、限流与去重测试。
- 实现下载量 Redis→MySQL 定时同步任务 + 分布式锁（归排行榜与定时任务模块）。
- 实现热度 ZSet `ZINCRBY` 联动（归排行榜与定时任务模块）。
- 实现下载地址过期机制（临时 token）。
- 实现对象存储（MinIO/OSS）下载。

---

## 20. 测试清单（规划）

### 20.1 创建下载记录接口

| 用例 | 预期 |
| --- | --- |
| 已登录下载 `APPROVED` 资料 | 成功写入 `download_record`，返回 `downloadUrl`，`counted=true` |
| 未登录 | `40101` |
| 资料不存在 | `40401` |
| 资料为待审核/已拒绝/已下架/已删除 | `40901` |
| 文件已删除或缺失 | `40401` 或 `50001` |
| 同用户同资料短时间重复下载 | 允许下载但 `counted=false`，不重复计入下载量 |
| 单用户/单 IP 触发限流阈值 | `42901` |

### 20.2 下载文件流接口

| 用例 | 预期 |
| --- | --- |
| 本人下载自己的记录 | 返回文件二进制流，文件名正确 |
| 管理员下载他人记录 | 允许 |
| 非本人非管理员访问 | `40301` |
| 下载记录不存在 | `40401` |
| 未登录 | `40101` |

### 20.3 我的下载记录接口

| 用例 | 预期 |
| --- | --- |
| 分页查询自己的下载记录 | 只返回本人记录，分页正确 |
| `pageNo=0` / `pageSize=101` | `40001` |
| 未登录 | `40101` |

### 20.4 Redis 限流与统计

| 用例 | 预期 |
| --- | --- |
| 窗口内请求数未超阈值 | 放行 |
| 窗口内请求数超阈值 | 拒绝，返回 `42901` |
| 首次计数 | `HINCRBY delta +1`，写去重 Key |
| 去重 Key 命中期内再次下载 | 不重复 `HINCRBY` |
| 下载失败 | 不写下载量增量，`download_status=2` |

### 20.5 已执行测试记录

| 测试命令 | 结果 | 说明 |
| --- | --- | --- |
| `.\mvnw.cmd -DskipTests compile` | 通过（每次步骤后均执行） | 步骤 2–8、10–11 每步完成后编译均通过 |
| `.\mvnw.cmd -Dtest=CampusResourcePlatformApplicationTests test` | 通过，Tests run: 1 | 步骤 2–8 每次验证 Spring 上下文正常加载 |
| `.\mvnw.cmd test`（全量） | 通过，**Tests run: 49, Failures: 0, Errors: 0, Skipped: 0** | 步骤 4–8、10–11 每步完成后验证无回归 |

> 说明：下载记录的针对性数据库集成测试（写入/分页/计数）与限流、去重测试尚未编写（步骤 9 待完成）。当前通过全量 49 个已有测试确保无回归。

---

## 21. 修改文件记录

当前已修改或新增：

| 文件 | 说明 |
| --- | --- |
| `docs/modules/07-download-development-process.md` | 下载模块开发流程文档（初稿 + 持续更新） |
| `.../entity/DownloadRecord.java` | 【步骤 2】下载记录实体，含状态常量与 `isSuccess()` |
| `.../mapper/DownloadRecordMapper.java` | 【步骤 2】下载记录 Mapper 接口 |
| `.../resources/mapper/DownloadRecordMapper.xml` | 【步骤 2】下载记录 SQL（insert / selectById / selectByUser / countByUser） |
| `.../common/RedisKeyConstants.java` | 【步骤 3】下载限流/去重/增量 Key 常量与生成方法 |
| `.../service/DownloadRateLimiter.java` | 【步骤 4】下载限流接口 |
| `.../service/impl/DownloadRateLimiterImpl.java` | 【步骤 4】Redis Lua 滑动窗口限流实现 |
| `.../vo/DownloadTicketVO.java` | 【步骤 5】下载凭证响应 record |
| `.../vo/MyDownloadRecordVO.java` | 【步骤 5】我的下载记录列表项 record |
| `.../service/DownloadService.java` | 【步骤 5/8】下载业务接口 + `DownloadFileInfo` record |
| `.../service/impl/DownloadServiceImpl.java` | 【步骤 5/8】下载业务实现，编排限流→校验→记录→去重→文件流 |
| `.../service/FileStorageService.java` | 【步骤 6】新增 `loadAsResource` 方法 + `FileResource` record |
| `.../service/impl/FileStorageServiceImpl.java` | 【步骤 6】实现 `loadAsResource`，路径穿越防护 + 文件校验 |
| `.../controller/DownloadController.java` | 【步骤 8】三接口入口，文件流返回二进制 |
| `docs/api/api-reference.md` | 【步骤 10】第 8 节三接口按真实代码同步 |
| `docs/05-redis-design.md` | 【步骤 10】第 7.8/8.8 节下载限流和增量统计实现状态 |
| `docs/06-project-progress.md` | 【步骤 10】新增 6.7 节，更新 8.1/9/10/11 节 |
| `README.md` | 【步骤 10】新增下载模块首版条目，更新下一阶段建议 |

待补充测试文件（步骤 9）：

| 文件 | 说明 |
| --- | --- |
| `.../test/.../controller/DownloadControllerTest.java` | 下载接口测试 |
| `.../test/.../service/DownloadServiceDatabaseIntegrationTest.java` | 下载 Service 数据库集成测试 |

> 所有类名与方法名以当前真实代码为准，已与文档一致。

---

## 22. 与其他模块的关系

- 依赖资料模块：下载前读 `resource` 校验 `APPROVED` 状态并取 `file_id`。
- 依赖审核模块：只有审核通过资料能进入下载链路。
- 依赖文件上传模块：通过 `file_info` 定位物理文件；文件与资料解耦（`resource.file_id → file_info.id`）。
- 依赖搜索模块：用户通常先搜索发现资料，再进入下载。
- 依赖认证模块：下载全部需要登录，权限校验依赖 `UserContextHolder` / `LoginUser`。
- 支撑排行榜与定时任务模块：下载量增量 `crp:stats:resource:download:delta` 与热度 ZSet 由排行榜模块消费；下载量 Redis→MySQL 定时同步归排行榜与定时任务模块。

---

## 23. 面试可讲点

- 下载为什么要先校验 `APPROVED`，如何避免未审核/已下架资料被下载。
- 为什么用 Redis ZSet 滑动窗口做下载限流，比固定窗口计数好在哪，为什么要用 Lua 保证原子性。
- 同用户同资料重复下载去重为什么用 String + TTL，如何区分「允许下载」和「计入下载量」。
- 下载量为什么先写 Redis Hash（`HINCRBY`）再由定时任务同步 MySQL，如何降低高频 `UPDATE` 压力。
- 下载量增量 Hash 为什么不设 TTL，同步成功后为什么才 `HDEL`，如何避免统计丢失。
- Redis 与 MySQL 的最终一致性如何设计，同步任务如何用分布式锁防重复执行（归排行榜模块）。
- 物理文件与业务资料为什么解耦，下载时如何通过 `file_info` 定位文件且不暴露内部存储路径。
- 文件流下载为什么不能放在数据库事务里（耗时 IO）。
- 下载记录表的索引如何支撑「我的下载记录」和风控查询。

---

## 24. 后续优化方向

- 下载量 Redis→MySQL 定时同步任务 + 分布式锁（`crp:lock:sync:download-delta`）。
- 热度分 `hot_score` 计算与回写，联动热门资料排行榜。
- 支持对象存储（MinIO/OSS）下载与预签名地址。
- 下载地址临时化（带过期时间的一次性 token），替代直接暴露下载记录 ID。
- 断点续传、分片下载、下载限速。
- 下载失败重试与补偿。
- 按资料/时间维度的下载趋势统计接口（管理员）。
- 限流阈值与去重 TTL 可配置化。

---

## 25. Git commit message 记录

| 步骤 | Commit | Message |
|------|--------|---------|
| T2 | — | `feat(download): add DownloadRecord entity and mapper` |
| T3 | — | `feat(download): add download redis key constants` |
| T4 | — | `feat(download): implement download rate limiter with sliding window and Lua` |
| T5 | `587b0a5` | `feat(download): implement download service with rate limit, dedup and delta stats` |
| T6 | `c17ad64` | `feat(download): add file stream read capability to FileStorageService` |
| T8 | `6b93673` | `feat(download): implement DownloadController with three endpoints` |
| T10–11 | 本步 | `docs(download): sync module documentation with real code` |

> 步骤 2–4 的 commit 由前序会话完成，步骤 5 起每步均已完成 commit 并推送到 `origin/dev`。

---

## 26. 分步骤开发提示词

> 使用说明：以下提示词按 `docs/AGENTS.md` 第 24 节要求拆分，每一步都是一个最小可执行任务。执行时请一次只复制一条提示词给 Agent，完成并验证后再进入下一步。所有步骤都必须遵守「先文档后代码、小步开发、不编造不存在的类/接口/表/Redis Key、不越权修改范围外文件」。

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 步骤 1 | 创建下载模块文档初稿 | ✅ 已完成 |
| 步骤 2 | 创建下载记录实体与 Mapper | ✅ 已完成 |
| 步骤 3 | 补充下载相关 Redis Key 常量 | ✅ 已完成 |
| 步骤 4 | 实现下载限流器 | ✅ 已完成 |
| 步骤 5 | 实现下载 Service + VO | ✅ 已完成（VO 随 Service 一起创建） |
| 步骤 6 | 补充文件流读取能力 | ✅ 已完成 |
| 步骤 7 | 创建下载 DTO/VO | ✅ 已完成（随步骤 5） |
| 步骤 8 | 实现下载 Controller | ✅ 已完成 |
| 步骤 9 | 补充下载模块测试 | ⬜ 待完成 |
| 步骤 10 | 同步下载模块文档 | ✅ 已完成 |
| 步骤 11 | 更新本模块开发流程文档 | ✅ 已完成 |

### 步骤 2：创建下载记录实体与 Mapper

提示词：

```text
请为下载模块创建下载记录实体和 MyBatis Mapper。

本步目标：
- 创建 `entity/DownloadRecord.java`，对应已存在的 `download_record` 表，字段与 `sql/init.sql` 保持一致。
- 定义下载状态常量：`STATUS_SUCCESS = 1`、`STATUS_FAIL = 2`。
- 创建 `mapper/DownloadRecordMapper.java` 和 `src/main/resources/mapper/DownloadRecordMapper.xml`。
- Mapper 至少提供：insert（回填自增 id）、selectById、selectByUser（分页）、countByUser。

涉及文件或类：
- `entity/DownloadRecord.java`
- `mapper/DownloadRecordMapper.java`
- `src/main/resources/mapper/DownloadRecordMapper.xml`
- `entity/BaseEntity.java`（参考现有实体基类，不修改）
- `sql/init.sql`（只核对表结构，不修改）

完成标准：
- 实体字段与 `download_record` 表字段一一对应，含 user_ip、user_agent、download_status、fail_reason。
- Mapper 只做数据库操作，不写业务判断。
- 不新增、不修改数据库表结构。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现 Service 和 Controller。
- 不接入 Redis。
- 不实现文件流读取。
```

### 步骤 3：补充下载相关 Redis Key 常量（已完成）

提示词：

```text
请为下载模块补充 Redis Key 常量。

本步目标：
- 修改 `common/RedisKeyConstants.java`。
- 新增：
  - `DOWNLOAD_RATE_USER = "crp:rate:download:user:%d"`
  - `DOWNLOAD_RATE_IP = "crp:rate:download:ip:%s"`
  - `DOWNLOAD_DEDUP = "crp:dedup:download:%d:%d"`
  - `DOWNLOAD_DELTA = "crp:stats:resource:download:delta"`
- 为前三个 Key 提供对应格式化方法，下载量增量 Key 为固定字符串常量。
- 保留已有 Token 黑名单、文件 MD5、搜索热词 Key 和方法签名不变。

涉及文件或类：
- `common/RedisKeyConstants.java`
- `docs/05-redis-design.md`（只核对 Key 命名，不修改）

完成标准：
- Key 命名与 `docs/05-redis-design.md` 第 7、8 节完全一致。
- 业务代码后续不硬编码完整 Redis Key。
- 新增常量和方法有简洁中文注释。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现限流或统计逻辑。
- 不修改其他模块。
```

### 步骤 4：实现下载限流器

提示词：

```text
请实现下载限流能力，使用接口 + 实现。

本步目标：
- 创建 `service/DownloadRateLimiter.java` 接口。
- 创建 `service/impl/DownloadRateLimiterImpl.java` 实现。
- 使用 Redis ZSet 滑动窗口 + Lua 脚本保证原子性：清窗口外记录、统计窗口内数量、超阈值拒绝、否则写入当前请求并刷新 TTL。
- 分别支持按用户和按 IP 限流，阈值参考 `docs/05-redis-design.md`（用户每分钟 10 次、IP 每分钟 30 次），阈值可用常量或配置。
- Key 统一走 `RedisKeyConstants`。

涉及文件或类：
- `service/DownloadRateLimiter.java`
- `service/impl/DownloadRateLimiterImpl.java`
- `common/RedisKeyConstants.java`
- `docs/05-redis-design.md`（只核对设计，不修改）

完成标准：
- 遵守项目「Service 必须接口 + 实现」约束，不写成单一具体 @Component。
- 超限时能被上层转换为 `ErrorCode.RATE_LIMITED(42901)`。
- 限流失败关闭策略（防被刷）在实现中说明清楚。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现下载主流程。
- 不写下载量统计与去重计数（放在 Service 步骤）。
- 不实现定时同步任务。
```

### 步骤 5：实现下载 Service

提示词：

```text
请实现下载模块的 Service 与 ServiceImpl。

本步目标：
- 创建 `service/DownloadService.java` 与 `service/impl/DownloadServiceImpl.java`。
- 实现「创建下载记录」：取当前用户 → 限流 → 校验资料 APPROVED → 校验文件正常 → 写 download_record → 去重判断 → 下载量增量 HINCRBY。
- 实现「获取我的下载记录」分页查询，只查当前登录用户。
- 去重 Key `crp:dedup:download:{userId}:{resourceId}` 命中期内不重复计入下载量。

涉及文件或类：
- `service/DownloadService.java`
- `service/impl/DownloadServiceImpl.java`
- `mapper/DownloadRecordMapper.java`
- `mapper/ResourceMapper.java`（复用 selectById）
- `mapper/FileInfoMapper.java`（复用 selectNormalById）
- `service/DownloadRateLimiter.java`
- `common/RedisKeyConstants.java`、`common/UserContextHolder.java`、`common/PageResult.java`
- `exception/BusinessException.java`、`common/ErrorCode.java`

完成标准：
- 非法状态抛 `BusinessException`，错误码映射见本文档第 14 节。
- Redis 操作不纳入 MySQL 事务，下载量走「先写 Redis 增量」方案。
- 不在事务中执行文件 IO。
- Service 不返回 Entity。
- 完成后更新本文档「已完成事项」和「待完成事项」。

本步不做什么：
- 不实现 Controller。
- 不实现文件流读取（下一步）。
- 不实现下载量定时同步任务。
```

### 步骤 6：补充文件流读取能力

提示词：

```text
请为下载文件流补充物理文件读取能力。

本步目标：
- 在 `service/FileStorageService.java` 接口补充读取文件流方法（如 `loadAsResource(String storagePath)`），并在 `service/impl/FileStorageServiceImpl.java` 实现。
- 只支持本地存储（storage_type = 1），路径来自 file_info.storage_path。
- 读取时做路径安全校验，防止路径穿越；文件不存在时抛出可被映射为 40401/50001 的异常。

涉及文件或类：
- `service/FileStorageService.java`
- `service/impl/FileStorageServiceImpl.java`
- `entity/FileInfo.java`（只读常量与字段）

完成标准：
- 不破坏已有 store/delete/calculateMd5/resolveExtension 方法签名。
- 读取方法不泄露内部存储绝对路径给上层调用方之外的响应体。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现对象存储读取。
- 不实现断点续传/分片下载。
- 不实现 Controller。
```

### 步骤 7：创建下载 DTO/VO

提示词：

```text
请为下载模块创建响应 VO（分页参数复用 PageQuery）。

本步目标：
- 创建 `vo/DownloadTicketVO.java`：downloadRecordId、resourceId、fileId、downloadUrl、expireSeconds、counted。
- 创建 `vo/MyDownloadRecordVO.java`：downloadRecordId、resourceId、title、fileId、downloadStatus、createdAt。
- 字段注释说明用途，不直接暴露 DownloadRecord / Resource / FileInfo Entity。

涉及文件或类：
- `vo/DownloadTicketVO.java`
- `vo/MyDownloadRecordVO.java`
- `dto/PageQuery.java`（只参考，不修改）

完成标准：
- VO 字段与 `docs/api/api-reference.md` 第 8 节响应示例一致。
- 不返回内部 storage_path、stored_name。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现 Controller 和 Service（若已实现则只做接线）。
- 不新增数据库结构。
```

### 步骤 8：实现下载 Controller

提示词：

```text
请实现下载模块 Controller。

本步目标：
- 创建 `controller/DownloadController.java`。
- 实现：
  - POST `/api/v1/resources/{resourceId}/download-records`
  - GET  `/api/v1/download-records/{downloadRecordId}/file`（返回文件二进制流）
  - GET  `/api/v1/users/me/download-records`
- 从请求获取客户端 IP 传给 Service 用于限流。
- 文件流接口设置 Content-Type 和 Content-Disposition（文件名用 original_name，注意中文编码）。

涉及文件或类：
- `controller/DownloadController.java`
- `service/DownloadService.java`
- `vo/DownloadTicketVO.java`、`vo/MyDownloadRecordVO.java`
- `common/ApiResponse.java`、`common/PageResult.java`、`dto/PageQuery.java`

完成标准：
- Controller 不直接访问 Mapper，不写复杂业务逻辑。
- 接口路径与 `docs/api/api-reference.md` 第 8 节一致。
- 确认下载路径需要登录（不加入 WebMvcConfig 放行列表）。
- 完成后更新本文档「已完成事项」。

本步不做什么：
- 不实现排行榜接口。
- 不实现下载量定时同步任务。
- 不修改其他模块接口路径。
```

### 步骤 9：补充下载模块测试

提示词：

```text
请为下载模块补充自动化测试。

本步目标：
- 新增 Controller 测试：覆盖未登录 40101、资料不存在 40401、状态不可下载 40901、越权访问文件流 40301、限流 42901、分页参数 40001。
- 新增 Service/Mapper 数据库集成测试：验证 download_record 写入、按用户分页查询、只查本人记录。
- 覆盖去重逻辑：首次计数写 delta，去重 Key 命中期内不重复计数。
- 覆盖限流：窗口内超阈值拒绝。

涉及文件或类：
- `src/test/java/com/john/campus/controller/DownloadControllerTest.java`
- `src/test/java/com/john/campus/service/DownloadServiceDatabaseIntegrationTest.java`
- `src/test/resources/sql/resource-db-test-schema.sql`（如需补 download_record 测试表结构）

完成标准：
- 至少运行 `.\mvnw.cmd test` 并记录结果到本文档「已执行测试记录」。
- 集成测试确认只查本人下载记录，不越权。
- 不把数据库密码写入仓库。
- 完成后更新本文档「测试清单」和「已完成事项」。

本步不做什么：
- 不测试排行榜/收藏模块。
- 不引入新的测试框架依赖。
```

### 步骤 10：同步下载模块文档

提示词：

```text
请根据当前真实代码同步下载模块相关文档。

本步目标：
- 更新 `docs/api/api-reference.md` 第 8 节，确保下载三接口的请求参数、响应字段、错误码与真实代码一致。
- 更新 `docs/05-redis-design.md`，标注下载限流、去重、下载量增量的真实实现状态（已实现 / 仍为设计）。
- 更新 `docs/06-project-progress.md`：把下载模块从「待开发」更新为已完成项，记录接口、涉及表、Redis Key 和测试结果。
- 同步 `README.md` 当前完成模块与测试命令。

涉及文件：
- `docs/api/api-reference.md`
- `docs/05-redis-design.md`
- `docs/06-project-progress.md`
- `README.md`

完成标准：
- 不把定时同步、排行榜、对象存储写成已完成，除非代码真实实现。
- Redis Key 使用 `crp:` 前缀规范命名，与 RedisKeyConstants 一致。
- 测试命令和结果写清楚。

本步不做什么：
- 不修改 Java 业务代码。
- 不新增数据库结构。
```

### 步骤 11：更新本模块开发流程文档

提示词：

```text
请根据当前真实代码更新下载模块开发流程文档。

本步目标：
- 更新 `docs/modules/07-download-development-process.md`。
- 补全「当前状态」「已完成事项」「待完成事项」「测试清单」「修改文件记录」「与其他模块的关系」「面试可讲点」「后续优化方向」。
- 如果实现过程中接口、类名、方法名、字段名与规划不一致，以当前真实代码为准修正文档。
- 保留「分步骤开发提示词」小节，并根据实际开发顺序校准下一轮可复制提示词。

涉及文件：
- `docs/modules/07-download-development-process.md`

完成标准：
- 文档能回答：本模块解决什么问题、有哪些接口、调用链路、涉及哪些表、是否用 Redis、如何权限控制、是否需要事务、如何测试。
- 文档不含「已规划但伪装成已完成」的内容。
- 文档记录本模块真实修改文件清单和测试结果。

本步不做什么：
- 不修改业务代码。
- 不扩展收藏、排行榜模块。
- 不删除已有文档章节。
```
