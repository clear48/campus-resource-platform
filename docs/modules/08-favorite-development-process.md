# 收藏模块开发流程文档

> 本文档遵循 `docs/AGENTS.md` 第 24 节《模块开发流程文档规范》生成。
> 当前状态：**步骤 2（Redis Key 常量）、步骤 3（实体和 Mapper）、步骤 4（VO）、步骤 5（Service）与步骤 6（Controller）已完成，专项测试待开发**。已明确接口设计（4 个接口）、数据库表（`favorite`）、Redis Key（`crp:user:favorites:{userId}`）、错误码（复用现有 + 可选新增）、涉及核心类和分步骤开发提示词。所有设计均基于 `docs/04-api-doc.md` 第 7 节和 `docs/05-redis-design.md` 第 11 节，与已有代码和数据库表结构一致。
> 已执行 `.\mvnw.cmd test`，全量 49 个已有测试通过；收藏模块专项测试仍待步骤 7 补充。

---

## 1. 模块基本信息

| 项 | 内容 |
| --- | --- |
| 模块名称 | 收藏模块 |
| 英文标识 | favorite |
| 文档路径 | `docs/modules/08-favorite-development-process.md` |
| 当前分支 | `dev` |
| 当前状态 | 步骤 2、3、4、5、6 已完成；专项测试待开发 |
| 前置依赖模块 | 用户认证模块、资料模块、审核模块（只有审核通过资料可被收藏） |
| 下游模块 | 排行榜与定时任务模块（消费收藏行为更新热度 ZSet） |
| 接口前缀 | `/api/v1/resources/{resourceId}/favorites`、`/api/v1/resources/{resourceId}/favorite-status`、`/api/v1/users/me/favorites` |

> 编号说明：下载模块已占用 `07-download-development-process.md`，因此收藏模块使用 `08-`。

---

## 2. 模块目标

收藏模块负责让登录用户收藏感兴趣的资料，并在「我的收藏」中统一管理；收藏行为同时作为热度分计算的输入之一，为排行榜模块提供数据积累。

首版目标：

- 实现「收藏资料」接口，校验资料可收藏状态，写入 `favorite` 表，防重复收藏。
- 实现「取消收藏」接口，更新收藏状态为已取消。
- 实现「查询资料收藏状态」接口，快速判断当前用户是否已收藏某资料。
- 实现「获取我的收藏列表」分页查询接口。
- 接入 Redis Set 缓存用户收藏集合，加速收藏状态判断。
- 收藏/取消收藏时更新 `resource.favorite_count` 和 Redis 热度 ZSet（与后续排行榜模块联动预留）。

本模块要体现的核心价值：收藏不是简单的「写一条记录」，而是**状态校验 + 幂等防重复 + MySQL 唯一索引兜底 + Redis Set 缓存加速 + 收藏数更新 + 热度联动**的组合能力。

---

## 3. 需求分析

1. 搜索模块和资料详情页已经让用户发现 `APPROVED` 资料，收藏是用户消费之后的自然留存行为（见 `docs/06-project-progress.md` 第 11 节）。
2. 只有审核通过（`APPROVED`）的资料可以被收藏；待审核、已拒绝、已下架、已删除资料必须拒绝收藏。
3. 同一个用户不能重复收藏同一资料：`favorite` 表的 `uk_favorite_user_resource (user_id, resource_id)` 唯一索引是最终兜底，Redis Set 作为第一层快速判断。
4. 取消收藏不是物理删除记录，而是更新 `status = 0`（已取消），保留记录用于数据分析和再次收藏时复用。
5. 收藏数需要维护在 `resource.favorite_count`，每次收藏 +1、取消收藏 -1。
6. 收藏行为需要联动热度分：收藏 `ZINCRBY +3`，取消收藏 `ZINCRBY -3`（热度 ZSet 联动归排行榜模块，本模块首版决定是否纳入，见第 17 节）。

为什么不是简单 CRUD：收藏模块集中体现**幂等设计（唯一索引 + 业务判断）**、**Redis Set 缓存加速收藏状态查询**、**收藏数和热度分的联动更新**，以及**软状态设计（status 字段区分已收藏和已取消，而非物理删除）**。

---

## 4. 本模块不做什么

- 首版不实现热度 ZSet `ZINCRBY` 联动（`crp:rank:resource:hot:{period}`），该能力归排行榜与定时任务模块；收藏模块只负责维护 `resource.favorite_count`。
- 首版不实现收藏夹/收藏分组功能（如「期末复习」「考研资料」等自定义分组）。
- 首版不实现批量取消收藏。
- 首版不实现收藏资料的备注/标签功能。
- 首版不修改 `favorite`、`resource` 两张表的表结构（表已在 `sql/init.sql` 存在）。
- 首版不改动其他模块已有接口路径与业务逻辑。

---

## 5. 涉及接口

> 以下接口设计来源于 `docs/04-api-doc.md` 第 7 节，当前均未实现。接口路径、请求参数和响应字段以 API 文档设计为准，实现时如有调整需同步更新本文档和 API 文档。

### 5.1 收藏资料

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/v1/resources/{resourceId}/favorites` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员（登录即可） |
| 当前状态 | 待开发 |

路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 资料 ID |

响应数据（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | long | 资料 ID |
| `favorited` | boolean | 当前收藏状态，成功为 `true` |
| `duplicateIgnored` | boolean | 是否因重复收藏而被幂等忽略 |
| `favoriteCount` | long | 资料当前总收藏数 |
| `hotScoreDelta` | int | 热度分变化，首版返回 0（热度联动归排行榜模块） |

说明：

- 校验资料存在且为 `APPROVED`，否则拒绝。
- 通过唯一索引 `uk_favorite_user_resource` 和业务判断实现防重复收藏。
- 重复收藏返回幂等成功（`duplicateIgnored = true`），不重复增加收藏数。
- 取消收藏后再次收藏：复用已有 `favorite` 记录，将 `status` 从 0 更新为 1。
- Redis Set `crp:user:favorites:{userId}` 同步 `SADD`。

### 5.2 取消收藏资料

| 项 | 内容 |
| --- | --- |
| 方法 | `DELETE` |
| 路径 | `/api/v1/resources/{resourceId}/favorites` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员（登录即可，只能取消自己的收藏） |
| 当前状态 | 待开发 |

路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 资料 ID |

响应数据（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | long | 资料 ID |
| `favorited` | boolean | 当前收藏状态，取消后为 `false` |
| `favoriteCount` | long | 资料当前总收藏数 |
| `hotScoreDelta` | int | 热度分变化，首版返回 0 |

说明：

- 校验该用户确实存在 `status = 1` 的收藏记录，不存在返回 `40401` 或幂等成功（待实现时确定）。
- 更新 `favorite.status = 0`，不物理删除记录。
- `resource.favorite_count` 减 1（不能小于 0）。
- Redis Set `crp:user:favorites:{userId}` 同步 `SREM`。

### 5.3 获取我的收藏列表

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/users/me/favorites` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员，只查自己的记录 |
| 当前状态 | 待开发 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `pageNo` | int | 否 | 页码，默认 1 |
| `pageSize` | int | 否 | 每页数量，默认 10，最大 100 |

响应数据：`PageResult<MyFavoriteVO>`，每条记录包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | long | 资料 ID |
| `title` | string | 资料标题 |
| `courseName` | string | 课程名称 |
| `downloadCount` | long | 下载次数 |
| `favoriteCount` | long | 收藏次数 |
| `createdAt` | string | 资料发布时间 |
| `favoriteAt` | string | 用户收藏时间 |

说明：

- 只按当前登录用户 ID 查询 `status = 1` 的收藏记录，不接受前端传入 `userId`。
- 关联 `resource` 表获取资料标题、课程名、下载数、收藏数等展示信息。
- 按 `favorite.created_at DESC` 排序（最近收藏的在前）。

### 5.4 查询资料收藏状态

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/resources/{resourceId}/favorite-status` |
| 是否登录 | 是 |
| 权限要求 | 学生或管理员 |
| 当前状态 | 待开发 |

路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 资料 ID |

响应数据（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | long | 资料 ID |
| `favorited` | boolean | 当前用户是否已收藏 |

说明：

- 优先查 Redis Set `SISMEMBER crp:user:favorites:{userId} {resourceId}`。
- Redis 未命中或 Set 不存在时降级查 MySQL `favorite` 表。
- 资料不存在或未 `APPROVED` 时，`favorited` 也返回 `false`（或根据设计返回错误码，实现时确定）。

---

## 6. 涉及数据库表

> `favorite` 和 `resource` 两张表均已在 `sql/init.sql` 中存在，本模块不新增、不修改表结构。

### 6.1 `favorite` 收藏表（本模块主表）

真实字段（来自 `sql/init.sql`）：

| 字段 | 类型 | 使用场景 |
| --- | --- | --- |
| `id` | BIGINT 自增 | 收藏记录主键 |
| `user_id` | BIGINT | 收藏用户，来自登录上下文 |
| `resource_id` | BIGINT | 被收藏资料 ID |
| `status` | TINYINT | 收藏状态：1 已收藏，0 已取消 |
| `created_at` | DATETIME | 首次收藏时间 |
| `updated_at` | DATETIME | 更新时间（取消收藏时更新） |

真实索引：

| 索引 | 作用 |
| --- | --- |
| `uk_favorite_user_resource` | 唯一索引，`(user_id, resource_id)`，防止重复收藏，保证幂等 |
| `idx_favorite_user_status_created` | 按用户 + 状态 + 时间查询，支撑「我的收藏列表」 |
| `idx_favorite_resource_status` | 按资料 + 状态查询，支撑收藏数统计和后台分析 |

约束：`chk_favorite_status CHECK (status IN (0, 1))`。

设计要点：

- 采用软状态设计：取消收藏时 `status = 0`，不删除记录。这样再次收藏同一资料时可以复用已有记录（`UPDATE status = 1`），避免频繁 `INSERT`/`DELETE` 造成主键空洞和索引碎片。
- 唯一索引 `uk_favorite_user_resource` 是关键：用户 + 资料维度全局唯一，插入重复记录时 MySQL 抛 `DuplicateKeyException`，业务层捕获后转为幂等返回。

### 6.2 `resource` 资料表（读写）

- 读 `status` 校验是否可收藏（只允许 `STATUS_APPROVED = 1`）。
- 读 `favorite_count` 返回当前收藏总数。
- 写 `favorite_count`：收藏 +1，取消收藏 -1。
- `favorite_count` 更新需注意并发安全：使用 `UPDATE resource SET favorite_count = favorite_count + 1 WHERE id = ?`（原子增减，避免读-改-写竞态条件）。

---

## 7. 涉及 Redis Key

> 以下 Key 设计来源于 `docs/05-redis-design.md` 第 11 节。当前 `RedisKeyConstants` 尚未包含收藏相关常量，本模块需要新增。

| Key | 数据结构 | 用途 | TTL | 更新时机 |
| --- | --- | --- | --- | --- |
| `crp:user:favorites:{userId}` | Set | 用户收藏集合缓存，member = resourceId | 30 分钟 | 收藏成功 `SADD`、取消收藏 `SREM`；查询未命中时从 MySQL 重建 |

一致性策略：

- 收藏关系最终以 MySQL `favorite` 表和唯一索引 `uk_favorite_user_resource` 为准。
- Redis Set 只是缓存加速层：`SISMEMBER` 快速判断收藏状态；未命中时查 MySQL 并重建 Set。
- 首次查询收藏状态时，若 Redis 不存在，从 MySQL 查询该用户所有 `status = 1` 的收藏记录，批量 `SADD` 写入 Redis 并设置 TTL。
- Redis 更新失败不影响主流程，记录日志，等待 TTL 过期后下次查询重建。

---

## 8. 涉及核心类

### 8.1 复用已存在类

| 类型 | 类 | 作用 |
| --- | --- | --- |
| common | `ApiResponse` | 统一响应 |
| common | `ErrorCode` | 统一错误码（`FAVORITE_DUPLICATE(40902)` 已存在） |
| common | `PageResult` | 分页响应 |
| common | `UserContextHolder` | 获取当前登录用户 ID/角色（`getRequiredUserId()`） |
| common | `LoginUser` | 登录用户快照，`isAdmin()` 判断管理员 |
| common | `RedisKeyConstants` | 已新增 `USER_FAVORITES` 常量和 `userFavorites(long userId)` 格式化方法 |
| dto | `PageQuery` | 通用分页请求（pageNo/pageSize/offset） |
| entity | `Resource` | 资料实体，`STATUS_APPROVED`、`isApproved()` |
| mapper | `ResourceMapper` | 已实现 `selectById`、`selectByIds`、`updateFavoriteCount`，供状态校验、列表展示和计数更新复用 |
| exception | `BusinessException`、`GlobalExceptionHandler` | 业务异常与统一异常处理 |
| config | `WebMvcConfig` | 拦截器与放行路径配置（收藏路径不需放行，天然要求登录） |

### 8.2 待新增或修改类

| 类型 | 类 | 职责 |
| --- | --- | --- |
| entity | `Favorite` | 已新增，对应 `favorite` 表，含 `STATUS_FAVORITED=1`/`STATUS_CANCELED=0` 常量 |
| mapper | `FavoriteMapper` + `FavoriteMapper.xml` | 已实现 insert/selectByUserAndResource/条件 updateStatus/selectActiveResourceIdsByUser/selectByUser/countByUser |
| vo | `FavoriteResultVO` | 已实现收藏/取消收藏操作结果（resourceId, favorited, duplicateIgnored, favoriteCount, hotScoreDelta） |
| vo | `FavoriteStatusVO` | 已实现收藏状态查询结果（resourceId, favorited） |
| vo | `MyFavoriteVO` | 已实现我的收藏列表条目（resourceId, title, courseName, downloadCount, favoriteCount, createdAt, favoriteAt） |
| service | `FavoriteService`（接口） | 已实现 favorite / unfavorite / getFavoriteStatus / listMyFavorites |
| service/impl | `FavoriteServiceImpl` | 已实现校验→唯一索引幂等→收藏数更新→Redis Set 同步 |
| controller | `FavoriteController` | 已实现四接口入口，仅做参数绑定和 Service 委托 |
| common | `RedisKeyConstants` | 已新增 `USER_FAVORITES = "crp:user:favorites:%d"` 常量及格式化方法 |

> 记忆约束：本项目所有 Spring Service（含基础设施/工具类服务）必须「接口 + 实现」。收藏 Service 遵守此约束。

---

## 9. 模块内部调用关系（规划）

```text
FavoriteController
  ├── POST /resources/{resourceId}/favorites
  │     └── FavoriteService.favorite(resourceId)
  │           ├── UserContextHolder（取当前用户 ID）
  │           ├── ResourceMapper.selectById（校验存在 + APPROVED）
  │           ├── FavoriteMapper.selectByUserAndResource（判断是否已有记录）
  │           ├── FavoriteMapper.insert（新收藏写入，唯一索引防重复）
  │           │     或 FavoriteMapper.updateStatus（取消后重新收藏，status 0→1）
  │           ├── ResourceMapper.updateFavoriteCount（favorite_count +1，原子 SQL）
  │           └── Redis SADD crp:user:favorites:{userId} {resourceId}
  │
  ├── DELETE /resources/{resourceId}/favorites
  │     └── FavoriteService.unfavorite(resourceId)
  │           ├── UserContextHolder（取当前用户 ID）
  │           ├── FavoriteMapper.selectByUserAndResource（校验存在 status=1 的记录）
  │           ├── FavoriteMapper.updateStatus（status 1→0）
  │           ├── ResourceMapper.updateFavoriteCount（favorite_count -1，防负）
  │           └── Redis SREM crp:user:favorites:{userId} {resourceId}
  │
  ├── GET /resources/{resourceId}/favorite-status
  │     └── FavoriteService.getFavoriteStatus(resourceId)
  │           ├── Redis SISMEMBER crp:user:favorites:{userId} {resourceId}
  │           │     未命中 → FavoriteMapper.selectByUserAndResource → 重建 Redis Set
  │           └── 返回 FavoriteStatusVO
  │
  └── GET /users/me/favorites
        └── FavoriteService.listMyFavorites(pageQuery)
              ├── FavoriteMapper.selectByUser（分页 + 状态=1）
              ├── FavoriteMapper.countByUser
              ├── 批量查 Resource 获取标题/课程名等展示信息
              └── 返回 PageResult<MyFavoriteVO>
```

Controller 只负责接收请求、取路径参数、绑定分页参数、返回统一响应；状态校验、幂等判断、收藏数更新、Redis 缓存同步等业务全部在 `FavoriteServiceImpl`。

---

## 10. 请求处理流程（规划）

### 10.1 收藏资料

1. JWT 拦截器校验登录（该路径不在 `WebMvcConfig` 放行列表，天然需要登录）。
2. `FavoriteController` 接收 `resourceId`。
3. `FavoriteServiceImpl` 通过 `UserContextHolder.getRequiredUserId()` 取当前用户。
4. `ResourceMapper.selectById` 校验资料存在；不存在抛 `RESOURCE_NOT_FOUND(40401)`。
5. 校验资料 `isApproved()`；非通过抛 `RESOURCE_STATUS_INVALID(40901)`。
6. `FavoriteMapper.selectByUserAndResource` 查询是否已有记录：
   - 已有记录且 `status = 1`：已收藏，幂等返回 `duplicateIgnored = true`，不更新收藏数。
   - 已有记录且 `status = 0`：之前取消过，`UPDATE status = 1`，走收藏数 +1 流程。
   - 无记录：`INSERT` 新记录，依赖唯一索引兜底防并发重复。
7. `ResourceMapper.updateFavoriteCount(+1)` 原子更新 `resource.favorite_count`。
8. Redis `SADD crp:user:favorites:{userId} {resourceId}`（失败不影响主流程）。
9. 组装 `FavoriteResultVO` 返回。

### 10.2 取消收藏

1. JWT 拦截器校验登录。
2. `FavoriteController` 接收 `resourceId`。
3. `FavoriteServiceImpl` 取当前用户。
4. `FavoriteMapper.selectByUserAndResource` 查收藏记录；不存在或 `status = 0` 时可幂等返回成功或抛出异常（实现时确定）。
5. `FavoriteMapper.updateStatus(status 1→0)`。
6. `ResourceMapper.updateFavoriteCount(-1)`（SQL 加 `WHERE favorite_count > 0` 防负）。
7. Redis `SREM crp:user:favorites:{userId} {resourceId}`。
8. 组装 `FavoriteResultVO` 返回。

### 10.3 查询收藏状态

1. JWT 拦截器校验登录。
2. `FavoriteController` 接收 `resourceId`。
3. `FavoriteServiceImpl` 取当前用户。
4. 先查 Redis `SISMEMBER`：
   - 命中 → 直接返回 `favorited = true/false`。
   - 未命中（Set 不存在或 Key 不存在）→ 查 MySQL `FavoriteMapper.selectByUserAndResource`。
5. MySQL 查询后异步或同步重建 Redis Set（批量加载该用户所有 `status=1` 的收藏 ID 并 `SADD` + `EXPIRE`）。
6. 返回 `FavoriteStatusVO`。

### 10.4 获取我的收藏列表

1. JWT 拦截器校验登录。
2. `FavoriteController` 绑定 `PageQuery`。
3. `FavoriteServiceImpl` 用当前用户 ID 调 `countByUser` + `selectByUser`（`status = 1`）。
4. 根据返回的 `resource_id` 列表批量查询 `resource` 表获取标题、课程名等展示字段。
5. 组装 `MyFavoriteVO` 列表，封装 `PageResult` 返回。

---

## 11. 数据流转流程（规划）

```text
用户点击收藏
  → FavoriteController 取 resourceId
  → FavoriteServiceImpl 取当前用户
  → ResourceMapper 校验 APPROVED
  → FavoriteMapper 查询是否已有记录
       ├── 无记录：INSERT（唯一索引防重复）
       ├── status=0 旧记录：UPDATE status=1
       └── status=1 已有：幂等返回 duplicateIgnored=true
  → ResourceMapper.updateFavoriteCount(+1)（原子 SQL）
  → Redis SADD crp:user:favorites:{userId}
  → 返回 FavoriteResultVO

用户取消收藏
  → FavoriteController 取 resourceId
  → FavoriteServiceImpl 取当前用户
  → FavoriteMapper 校验存在 status=1 记录
  → FavoriteMapper.updateStatus(1→0)
  → ResourceMapper.updateFavoriteCount(-1)（防负）
  → Redis SREM crp:user:favorites:{userId}
  → 返回 FavoriteResultVO

查询收藏状态
  → Redis SISMEMBER 快速判断
       ├── 命中 → 直接返回
       └── 未命中 → MySQL 查询 → 重建 Redis Set → 返回
```

---

## 12. 权限校验（规划）

- 收藏相关接口全部需要登录，路径**不加入** `WebMvcConfig.excludePathPatterns`，由 JWT 拦截器保证登录态。
- 收藏/取消收藏：登录即可（学生或管理员）。
- 查询收藏状态：登录即可，只能查自己的收藏状态。
- 我的收藏列表：只按 `UserContextHolder.getRequiredUserId()` 查询，不接受前端传 `userId`。
- 取消收藏：只能取消自己的收藏（通过 `user_id` 校验），管理员也不取消他人收藏（首版设计如此，后续可扩展管理员批量管理能力）。

---

## 13. 参数校验（规划）

| 参数 | 规则 | 失败错误码 |
| --- | --- | --- |
| `resourceId` | 路径参数，必须为正整数 | `40001` / 404 路由 |
| `pageNo` | 大于等于 1（复用 `PageQuery`） | `40001` |
| `pageSize` | 1–100（复用 `PageQuery`） | `40001` |

业务级校验（非纯参数）：资料是否存在、是否 `APPROVED`、收藏记录归属，均在 Service 层完成并映射到对应错误码。

---

## 14. 异常处理（规划）

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 未登录 | `40101 UNAUTHORIZED` | JWT 拦截器 / `UserContextHolder.getRequired()` |
| 分页参数非法 | `40001 PARAM_ERROR` | `PageQuery` Bean Validation |
| 资料不存在 | `40401 RESOURCE_NOT_FOUND` | `ResourceMapper.selectById` 返回 null |
| 资料未审核通过或已下架 | `40901 RESOURCE_STATUS_INVALID` | 非 `APPROVED` |
| 重复收藏 | `40902 FAVORITE_DUPLICATE` | 首版按幂等成功处理（`duplicateIgnored = true`），不抛异常 |
| 取消不存在的收藏 | `40401 RESOURCE_NOT_FOUND` 或幂等成功 | 实现时确定策略 |
| 取消他人收藏 | `40301 FORBIDDEN` | 仅当实现管理员取消能力时需要 |

`ErrorCode` 现状核对：`PARAM_ERROR(40001)`、`UNAUTHORIZED(40101)`、`FORBIDDEN(40301)`、`RESOURCE_NOT_FOUND(40401)`、`RESOURCE_STATUS_INVALID(40901)`、`FAVORITE_DUPLICATE(40902)` **均已存在**，收藏模块无需新增错误码。

---

## 15. 事务处理（规划）

- 收藏操作涉及两张表（`favorite` 写入 + `resource.favorite_count` 更新），当前通过 `TransactionTemplate` 把两项 MySQL 写操作包裹在同一事务中。
- 重复收藏幂等返回：`DuplicateKeyException` 在 `TransactionTemplate` 事务结束后由外层捕获，再查询赢家记录并返回幂等成功，避免当前事务被标记为 rollback-only。
- Redis 操作（Set SADD/SREM）**不纳入 MySQL 事务**：`TransactionTemplate.execute` 正常返回后再更新 Redis；Redis 失败不影响主流程，等待 TTL 过期后从 MySQL 重建。
- 关键约束（`docs/AGENTS.md` 第 11 节）：不要在事务中执行耗时操作。收藏模块无文件 IO，主要关注 Redis 操作不应阻塞事务提交。
- `favorite_count` 更新使用原子 SQL（`UPDATE resource SET favorite_count = favorite_count + 1 WHERE id = ?`），避免 SELECT → UPDATE 的读-改-写竞态条件。

---

## 16. 核心实现步骤（规划）

1. 创建收藏模块开发流程文档初稿（**本步已完成**）。
2. 为 `RedisKeyConstants` 补充收藏相关 Key 常量（`USER_FAVORITES`）。
3. 创建 `Favorite` 实体 + `FavoriteMapper` 接口 + XML。
4. 创建 VO：`FavoriteResultVO`、`FavoriteStatusVO`、`MyFavoriteVO`。
5. 实现 `FavoriteService` 接口 + `FavoriteServiceImpl`：校验、幂等、收藏数更新、Redis Set 同步。
6. 补充 `ResourceMapper` 收藏数原子更新 SQL（`updateFavoriteCount`）。
7. 实现 `FavoriteController` 四个接口。
8. 确认 `WebMvcConfig` 收藏路径需要登录（不放行）。
9. 补充测试（Controller 测试、Service/Mapper 数据库集成测试、幂等与并发测试）。
10. 同步 `docs/04-api-doc.md`、`docs/05-redis-design.md`、`docs/06-project-progress.md`、`README.md`。
11. 更新本模块开发流程文档。

---

## 17. 开发任务拆分（规划）

| 序号 | 任务 | 产出 | 状态 |
| --- | --- | --- | --- |
| T1 | 收藏模块文档初稿 | `docs/modules/08-favorite-development-process.md` | 已完成 |
| T2 | Redis Key 常量 | `RedisKeyConstants` 补充 `USER_FAVORITES` 常量与方法 | 已完成 |
| T3 | 实体 + Mapper | `Favorite`、`FavoriteMapper(.java/.xml)`、`ResourceMapper` 补充 `updateFavoriteCount` | 已完成 |
| T4 | DTO / VO | `FavoriteResultVO`、`FavoriteStatusVO`、`MyFavoriteVO` | 已完成 |
| T5 | Service | `FavoriteService` + `FavoriteServiceImpl`（含收藏、取消、状态查询、列表） | 已完成 |
| T6 | Controller | `FavoriteController` 四接口 | 已完成 |
| T7 | 测试 | Controller 测试 + 数据库集成测试 + 幂等/并发测试 | 待开发 |
| T8 | 文档同步 | API、Redis、进度、README、模块流程文档 | 待开发 |

> 是否纳入首版待定：收藏成功时对 `crp:rank:resource:hot:{period}` 的热度 `ZINCRBY +3` 联动。该 Key 归排行榜模块，建议收藏模块首版先只维护 `resource.favorite_count`，热度 ZSet 联动在排行榜模块统一落地，避免跨模块职责混淆。

---

## 18. 已完成事项

- 已阅读 `AGENTS.md` 与 `docs/AGENTS.md`，确认模块开发流程文档规范（第 14、24 节）。
- 已阅读 `docs/06-project-progress.md`，确认收藏模块是当前推荐开发模块。
- 已阅读 `docs/04-api-doc.md` 第 7 节收藏模块接口设计，确认四大接口路径与响应结构。
- 已阅读 `docs/05-redis-design.md` 第 11 节，确认用户收藏集合的 Key、数据结构、TTL 与一致性策略。
- 已核对 `sql/init.sql`，确认 `favorite` 表结构与索引（含唯一索引 `uk_favorite_user_resource`）真实存在。
- 已核对 `ErrorCode`，确认收藏模块所需错误码全部已存在（`FAVORITE_DUPLICATE(40902)`），无需新增。
- 已核对 `WebMvcConfig`，确认收藏路径未被放行（`/api/v1/resources/{resourceId}/favorites` 是两段式路径，`/api/v1/resources/*` 只放行一段式），天然需要登录。
- 【步骤 1】已生成本收藏模块开发流程文档初稿。
- 【步骤 2】已在 `RedisKeyConstants` 新增 `USER_FAVORITES` 常量和 `userFavorites(long userId)` 格式化方法。
- 【步骤 3】已新增 `Favorite` 实体、`FavoriteMapper` 接口与 XML，并为 `ResourceMapper` 补充 `updateFavoriteCount` 原子更新方法。
- 【步骤 4】已新增 `FavoriteResultVO`、`FavoriteStatusVO`、`MyFavoriteVO` 三个响应 record，未暴露收藏内部状态和用户 ID。
- 【步骤 5】已实现 `FavoriteService` 与 `FavoriteServiceImpl`，覆盖收藏、取消、状态查询、分页列表、MySQL 幂等与 Redis Set 缓存同步。
- 【步骤 6】已新增 `FavoriteController` 四接口；收藏路径未加入公开放行列表，继续由 JWT 拦截器保护。

---

## 19. 待完成事项

- 补充收藏模块针对性测试（Controller 测试、Service/Mapper 数据库集成测试、幂等与并发测试）。
- 同步所有相关文档（API、Redis、项目进度、README、本流程文档）。
- 实现热度 ZSet `ZINCRBY` 联动（归排行榜与定时任务模块）。
- 实现收藏夹/分组功能（后续版本）。

---

## 20. 测试清单（规划）

### 20.1 收藏资料接口

| 用例 | 预期 |
| --- | --- |
| 已登录收藏 `APPROVED` 资料 | 成功写入 `favorite`，`favorite_count +1`，返回 `favorited=true` |
| 未登录 | `40101` |
| 资料不存在 | `40401` |
| 资料为待审核/已拒绝/已下架/已删除 | `40901` |
| 重复收藏同一资料 | 幂等成功，`duplicateIgnored=true`，不重复 +1 |
| 取消后重新收藏 | `status` 0→1，`favorite_count +1` |
| 并发重复收藏 | 唯一索引兜底，至少一条成功，其他幂等返回 |

### 20.2 取消收藏接口

| 用例 | 预期 |
| --- | --- |
| 取消自己的收藏 | `status` 1→0，`favorite_count -1` |
| 未登录 | `40101` |
| 取消不存在的收藏 | `40401` 或幂等成功（实现时确定） |
| 重复取消 | 幂等处理，不重复 -1 |

### 20.3 收藏状态查询接口

| 用例 | 预期 |
| --- | --- |
| 已收藏资料 | `favorited=true` |
| 未收藏资料 | `favorited=false` |
| 未登录 | `40101` |
| Redis 命中 | 直接返回，不查 MySQL |
| Redis 未命中 | 降级查 MySQL，重建 Redis Set |

### 20.4 我的收藏列表接口

| 用例 | 预期 |
| --- | --- |
| 分页查询自己的收藏 | 只返回本人 `status=1` 记录，分页正确 |
| `pageNo=0` / `pageSize=101` | `40001` |
| 未登录 | `40101` |
| 按收藏时间倒序 | 最近收藏在前 |

### 20.5 已执行测试记录

| 测试命令 | 结果 | 说明 |
| --- | --- | --- |
| `.\mvnw.cmd test` | 通过 | 全量 49 个已有测试通过；收藏模块专项测试待步骤 T7 补充 |

---

## 21. 修改文件记录

当前已修改或新增：

| 文件 | 说明 |
| --- | --- |
| `docs/modules/08-favorite-development-process.md` | 收藏模块开发流程文档，已同步步骤 2 至 5 完成状态和测试结果 |
| `.../common/RedisKeyConstants.java` | 已新增 `USER_FAVORITES` 常量和格式化方法 |
| `.../entity/Favorite.java` | 已新增收藏实体，含 `STATUS_FAVORITED`/`STATUS_CANCELED` 常量 |
| `.../mapper/FavoriteMapper.java` | 已新增收藏 Mapper，并补充缓存重建所需的有效收藏 ID 查询 |
| `.../resources/mapper/FavoriteMapper.xml` | 已新增收藏 SQL，状态更新带旧状态条件避免并发重复计数 |
| `.../mapper/ResourceMapper.java` | 已新增 `selectByIds` 与 `updateFavoriteCount` 方法 |
| `.../resources/mapper/ResourceMapper.xml` | 已新增资料批量查询和收藏数原子更新 SQL |
| `.../vo/FavoriteResultVO.java` | 已新增收藏/取消收藏操作结果 record |
| `.../vo/FavoriteStatusVO.java` | 已新增收藏状态查询结果 record |
| `.../vo/MyFavoriteVO.java` | 已新增我的收藏列表项 record |
| `.../service/FavoriteService.java` | 已新增收藏业务接口 |
| `.../service/impl/FavoriteServiceImpl.java` | 已新增收藏业务实现，负责事务、幂等、缓存和分页编排 |
| `.../controller/FavoriteController.java` | 已新增收藏四接口入口，统一委托 FavoriteService |

待新增或修改：

| 文件 | 说明 |
| --- | --- |
| `.../test/.../controller/FavoriteControllerTest.java` | 收藏接口测试 |
| `.../test/.../service/FavoriteServiceDatabaseIntegrationTest.java` | 收藏 Service 数据库集成测试 |
| `docs/04-api-doc.md` | 第 7 节按真实代码同步 |
| `docs/05-redis-design.md` | 第 11 节标注收藏 Redis 实现状态 |
| `docs/06-project-progress.md` | 收藏模块从待开发更新为已完成 |
| `README.md` | 新增收藏模块首版条目 |

---

## 22. 与其他模块的关系

- 依赖资料模块：收藏前读 `resource` 校验 `APPROVED` 状态；收藏数更新需要写 `resource.favorite_count`。
- 依赖审核模块：只有审核通过资料能被收藏；下架资料已有收藏关系保持不变但不再接受新收藏。
- 依赖认证模块：收藏全部需要登录，权限校验依赖 `UserContextHolder` / `LoginUser`。
- 支撑排行榜与定时任务模块：收藏行为为热度分提供 `+3` 权重输入（由排行榜模块消费）；收藏数已维护在 `resource.favorite_count`。
- 与搜索模块的关系：搜索结果中的 `favoriteCount` 排序依赖本模块维护的收藏数。
- 与下载模块的关系：属于消费链上的相邻模块（搜索 → 详情 → 收藏/下载）。

---

## 23. 面试可讲点

- 收藏为什么不是简单 CRUD：涉及状态校验、幂等防重复、软状态设计、Redis Set 缓存加速、收藏数原子更新和热度联动。
- 防重复收藏的三层设计：业务层先判断 → MySQL 唯一索引 `uk_favorite_user_resource` 兜底 → 捕获 `DuplicateKeyException` 转幂等返回。
- 为什么用软状态（`status = 0/1`）而不是物理删除：保留历史数据用于分析，取消后重新收藏可复用记录避免主键空洞。
- 收藏数为什么用原子 SQL（`SET favorite_count = favorite_count + 1`）而不是 SELECT 再 UPDATE：避免并发读-改-写导致计数不准确。
- Redis Set 如何加速收藏状态判断：`SISMEMBER O(1)` 判断，未命中时从 MySQL 批量重建，比每次都查 MySQL 快得多。
- Redis Set 和 MySQL 的一致性如何保证：MySQL 为准，Redis 只是缓存加速层；更新失败不影响主流程，TTL 过期后自动重建。
- 取消收藏后再收藏的设计：复用已有记录更新 `status`，体现对唯一索引和软状态设计的理解。
- 事务范围如何界定：MySQL 两张表的写入在一个事务中；Redis 更新在事务外，失败不阻塞主流程。

---

## 24. 后续优化方向

- 收藏成功时对 `crp:rank:resource:hot:{period}` 的 `ZINCRBY +3` 联动（归排行榜模块）。
- 收藏夹/分组功能（如「期末复习」「考研资料」等自定义分组）。
- 批量取消收藏。
- 收藏资料的备注/标签功能。
- 收藏资料更新提醒（被收藏资料有新版本或状态变更时通知用户）。
- 收藏趋势统计接口（管理员查看收藏变化趋势）。

---

## 25. Git commit message 建议

| 步骤 | 建议 Message |
|------|-------------|
| T2 | `feat(favorite): add favorite redis key constants` |
| T3 | `feat(favorite): add Favorite entity and mapper` |
| T4 | `feat(favorite): add favorite VO classes` |
| T5 | `feat(favorite): implement favorite service with dedup and count update` |
| T6 | `feat(favorite): implement FavoriteController with four endpoints` |
| T7 | `test(favorite): add controller and integration tests` |
| T8 | `docs(favorite): sync module documentation` |

---

## 26. 分步骤开发提示词

> 使用说明：以下提示词按 `docs/AGENTS.md` 第 24 节要求拆分，每一步都是一个最小可执行任务。执行时请一次只复制一条提示词给 Agent，完成并验证后再进入下一步。所有步骤都必须遵守「先文档后代码、小步开发、不编造不存在的类/接口/表/Redis Key、不越权修改范围外文件」。

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 步骤 1 | 创建收藏模块文档初稿 | ✅ 已完成 |
| 步骤 2 | 补充收藏相关 Redis Key 常量 | ✅ 已完成 |
| 步骤 3 | 创建收藏实体与 Mapper | ✅ 已完成 |
| 步骤 4 | 创建收藏 VO | ✅ 已完成 |
| 步骤 5 | 实现收藏 Service | ✅ 已完成 |
| 步骤 6 | 实现收藏 Controller | ✅ 已完成 |
| 步骤 7 | 补充收藏模块测试 | ⬜ 待开发 |
| 步骤 8 | 同步收藏模块文档 | ⬜ 待开发 |
| 步骤 9 | 更新本模块开发流程文档 | ⬜ 待开发 |

### 步骤 2：补充收藏相关 Redis Key 常量

提示词：

```text
请为收藏模块补充 Redis Key 常量。

本步目标：
- 修改 `common/RedisKeyConstants.java`。
- 新增：
  - `USER_FAVORITES = "crp:user:favorites:%d"`
- 提供对应格式化方法 `userFavorites(long userId)`。
- 保留已有 Token 黑名单、文件 MD5、搜索热词、下载限流/去重/增量 Key 和方法签名不变。

涉及文件或类：
- `common/RedisKeyConstants.java`
- `docs/05-redis-design.md`（只核对 Key 命名，不修改）

完成标准：
- Key 命名与 `docs/05-redis-design.md` 第 11 节完全一致（`crp:user:favorites:{userId}`）。
- 业务代码后续不硬编码完整 Redis Key。
- 新增常量和方法有简洁中文注释。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现收藏业务逻辑。
- 不修改其他模块。
```

### 步骤 3：创建收藏实体与 Mapper

提示词：

```text
请为收藏模块创建实体类和 MyBatis Mapper，并补充 ResourceMapper 收藏数更新方法。

本步目标：
- 创建 `entity/Favorite.java`，对应已存在的 `favorite` 表，字段与 `sql/init.sql` 保持一致。
- 定义收藏状态常量：`STATUS_FAVORITED = 1`、`STATUS_CANCELED = 0`。
- 创建 `mapper/FavoriteMapper.java` 和 `src/main/resources/mapper/FavoriteMapper.xml`。
- Mapper 至少提供：insert（回填自增 id）、selectByUserAndResource（按 userId + resourceId 查）、updateStatus（按 id 更新 status）、selectByUser（分页，status=1）、countByUser。
- 修改 `mapper/ResourceMapper.java` 和对应的 XML，新增 `updateFavoriteCount` 方法：使用原子 SQL `UPDATE resource SET favorite_count = favorite_count + #{delta} WHERE id = #{resourceId}`，delta 为 +1 或 -1。取消收藏时需加 `AND favorite_count > 0` 防负。

涉及文件或类：
- `entity/Favorite.java`
- `entity/BaseEntity.java`（参考现有实体基类，不修改）
- `mapper/FavoriteMapper.java`
- `src/main/resources/mapper/FavoriteMapper.xml`
- `mapper/ResourceMapper.java`（新增 updateFavoriteCount 方法）
- `src/main/resources/mapper/ResourceMapper.xml`（新增 updateFavoriteCount SQL）
- `sql/init.sql`（只核对表结构，不修改）

完成标准：
- 实体字段与 `favorite` 表字段一一对应（id, userId, resourceId, status, createdAt, updatedAt）。
- Mapper 只做数据库操作，不写业务判断。
- `updateFavoriteCount` 使用原子 SQL，不 SELECT 再 UPDATE。
- 不新增、不修改数据库表结构。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现 Service 和 Controller。
- 不接入 Redis。
```

### 步骤 4：创建收藏 VO

提示词：

```text
请为收藏模块创建响应 VO（分页参数复用 PageQuery）。

本步目标：
- 创建 `vo/FavoriteResultVO.java`：resourceId、favorited、duplicateIgnored、favoriteCount、hotScoreDelta。
- 创建 `vo/FavoriteStatusVO.java`：resourceId、favorited。
- 创建 `vo/MyFavoriteVO.java`：resourceId、title、courseName、downloadCount、favoriteCount、createdAt、favoriteAt。
- 字段注释说明用途，不直接暴露 Favorite / Resource Entity。

涉及文件或类：
- `vo/FavoriteResultVO.java`
- `vo/FavoriteStatusVO.java`
- `vo/MyFavoriteVO.java`
- `dto/PageQuery.java`（只参考，不修改）

完成标准：
- VO 字段与 `docs/04-api-doc.md` 第 7 节响应示例一致。
- 使用 Java 17 record 类型（与项目现有 VO 风格一致）。
- 不返回内部 user_id、status 等非必要字段给前端。
- 完成后更新本文档「修改文件记录」和「已完成事项」。

本步不做什么：
- 不实现 Controller 和 Service。
- 不新增数据库结构。
```

### 步骤 5：实现收藏 Service

提示词：

```text
请实现收藏模块的 Service 与 ServiceImpl。

本步目标：
- 创建 `service/FavoriteService.java` 接口与 `service/impl/FavoriteServiceImpl.java` 实现类。
- 实现「收藏资料」：取当前用户 → 校验资料 APPROVED → 查是否已有记录 → INSERT（唯一索引防重复）或 UPDATE status 0→1 → 原子更新 favorite_count +1 → Redis SADD。
- 实现「取消收藏」：取当前用户 → 校验存在 status=1 记录 → UPDATE status 1→0 → 原子更新 favorite_count -1（防负）→ Redis SREM。
- 实现「查询收藏状态」：优先 Redis SISMEMBER → 未命中查 MySQL 并重建 Redis Set（批量 SADD + EXPIRE 30分钟）。
- 实现「我的收藏列表」：分页查 FavoriteMapper → 批量查 Resource 获取展示信息 → 组装 VO。
- 重复收藏：捕获 DuplicateKeyException 后在事务外或新事务中处理幂等返回。

涉及文件或类：
- `service/FavoriteService.java`
- `service/impl/FavoriteServiceImpl.java`
- `mapper/FavoriteMapper.java`
- `mapper/ResourceMapper.java`（复用 selectById、updateFavoriteCount）
- `common/RedisKeyConstants.java`、`common/UserContextHolder.java`、`common/PageResult.java`
- `exception/BusinessException.java`、`common/ErrorCode.java`
- `vo/FavoriteResultVO.java`、`vo/FavoriteStatusVO.java`、`vo/MyFavoriteVO.java`

完成标准：
- 遵守项目「Service 必须接口 + 实现」约束。
- 收藏数更新使用原子 SQL，不 SELECT 再 UPDATE。
- Service 不返回 Entity。
- 非法状态抛 BusinessException，错误码映射见本文档第 14 节。
- Redis Set 同步失败不影响主流程（try-catch + log）。
- 事务只覆盖 MySQL 写操作（favorite 写入 + favorite_count 更新），Redis 操作在事务外。
- 完成后更新本文档「已完成事项」和「待完成事项」。

本步不做什么：
- 不实现 Controller。
- 不实现热度 ZSet 联动（归排行榜模块）。
- 不实现收藏夹分组功能。
```

### 步骤 6：实现收藏 Controller

提示词：

```text
请实现收藏模块 Controller。

本步目标：
- 创建 `controller/FavoriteController.java`。
- 实现：
  - POST   `/api/v1/resources/{resourceId}/favorites`（收藏资料）
  - DELETE `/api/v1/resources/{resourceId}/favorites`（取消收藏）
  - GET    `/api/v1/resources/{resourceId}/favorite-status`（查询收藏状态）
  - GET    `/api/v1/users/me/favorites`（我的收藏列表）
- 所有接口返回统一 ApiResponse（列表接口使用 PageResult）。

涉及文件或类：
- `controller/FavoriteController.java`
- `service/FavoriteService.java`
- `vo/FavoriteResultVO.java`、`vo/FavoriteStatusVO.java`、`vo/MyFavoriteVO.java`
- `common/ApiResponse.java`、`common/PageResult.java`、`dto/PageQuery.java`

完成标准：
- Controller 不直接访问 Mapper，不写复杂业务逻辑。
- 接口路径与 `docs/04-api-doc.md` 第 7 节一致。
- 确认收藏路径需要登录（不加入 WebMvcConfig 放行列表）。
- 完成后更新本文档「已完成事项」。

本步不做什么：
- 不实现排行榜接口。
- 不修改其他模块接口路径。
```

### 步骤 7：补充收藏模块测试

提示词：

```text
请为收藏模块补充自动化测试。

本步目标：
- 新增 Controller 测试：覆盖未登录 40101、资料不存在 40401、资料不可收藏 40901、收藏成功、重复收藏幂等、取消收藏、分页参数 40001、收藏状态查询（已收藏/未收藏）。
- 新增 Service/Mapper 数据库集成测试：验证 favorite 写入、唯一索引防重复、status 0→1 重新收藏、favorite_count 原子增减、我的收藏分页只查本人。
- 覆盖 Redis Set 缓存：SISMEMBER 命中/未命中、降级查 MySQL 并重建。

涉及文件或类：
- `src/test/java/com/john/campus/controller/FavoriteControllerTest.java`
- `src/test/java/com/john/campus/service/FavoriteServiceDatabaseIntegrationTest.java`
- `src/test/resources/sql/resource-db-test-schema.sql`（如需补 favorite 测试表结构）

完成标准：
- 至少运行 `.\mvnw.cmd test` 并记录结果到本文档「已执行测试记录」。
- 集成测试确认只查本人收藏，不越权。
- 不把数据库密码写入仓库。
- 完成后更新本文档「测试清单」和「已完成事项」。

本步不做什么：
- 不测试排行榜/下载模块。
- 不引入新的测试框架依赖。
```

### 步骤 8：同步收藏模块文档

提示词：

```text
请根据当前真实代码同步收藏模块相关文档。

本步目标：
- 更新 `docs/04-api-doc.md` 第 7 节，确保收藏四接口的请求参数、响应字段、错误码与真实代码一致。
- 更新 `docs/05-redis-design.md`，标注用户收藏集合的真实实现状态（已实现 / 仍为设计）。
- 更新 `docs/06-project-progress.md`：把收藏模块从「待开发」更新为已完成项，记录接口、涉及表、Redis Key 和测试结果。
- 同步 `README.md` 当前完成模块与测试命令。

涉及文件：
- `docs/04-api-doc.md`
- `docs/05-redis-design.md`
- `docs/06-project-progress.md`
- `README.md`

完成标准：
- 不把热度 ZSet 联动、收藏夹分组写成已完成，除非代码真实实现。
- Redis Key 使用 `crp:` 前缀规范命名，与 RedisKeyConstants 一致。
- 测试命令和结果写清楚。

本步不做什么：
- 不修改 Java 业务代码。
- 不新增数据库结构。
```

### 步骤 9：更新本模块开发流程文档

提示词：

```text
请根据当前真实代码更新收藏模块开发流程文档。

本步目标：
- 更新 `docs/modules/08-favorite-development-process.md`。
- 补全「当前状态」「已完成事项」「待完成事项」「测试清单」「修改文件记录」「与其他模块的关系」「面试可讲点」「后续优化方向」。
- 如果实现过程中接口、类名、方法名、字段名与规划不一致，以当前真实代码为准修正文档。
- 保留「分步骤开发提示词」小节，并根据实际开发顺序校准下一轮可复制提示词。

涉及文件：
- `docs/modules/08-favorite-development-process.md`

完成标准：
- 文档能回答：本模块解决什么问题、有哪些接口、调用链路、涉及哪些表、是否用 Redis、如何权限控制、是否需要事务、如何测试。
- 文档不含「已规划但伪装成已完成」的内容。
- 文档记录本模块真实修改文件清单和测试结果。

本步不做什么：
- 不修改业务代码。
- 不扩展排行榜模块。
- 不删除已有文档章节。
```
