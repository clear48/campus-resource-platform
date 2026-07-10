# 排行榜与定时任务模块开发流程文档

> 本文档遵循 `docs/AGENTS.md` 第 24 节《模块开发流程文档规范》生成。
> 当前状态：**步骤 1、步骤 2 已完成；排行榜 Redis Key 常量与周期模型已落地，步骤 3 待开发**。
> 步骤 2 未实现 Service、Controller、Mapper、定时任务，也未修改数据库结构或引入依赖。

---

## 1. 模块基本信息

| 项 | 内容 |
| --- | --- |
| 模块名称 | 排行榜与定时任务模块 |
| 英文标识 | rank |
| 文档路径 | `docs/modules/09-rank-development-process.md` |
| 当前分支 | `dev` |
| 当前状态 | 步骤 1、2 已完成；下一步创建排行榜 DTO 与 VO |
| 前置依赖模块 | 资料、审核、搜索、下载、收藏模块 |
| 下游模块 | 首页热门资料展示、搜索框热门词展示、后台运营统计 |
| 接口前缀 | `/api/v1/rankings` |
| 主要技术 | Spring MVC、MyBatis、Redis ZSet/Hash/String、Spring Scheduling |

> 编号说明：`08-favorite-development-process.md` 已用于收藏模块，因此本模块使用 `09-`。

---

## 2. 模块目标

排行榜与定时任务模块负责把搜索、下载、收藏等高频行为产生的 Redis 数据转换为可查询的实时榜单，并把需要长期保存的统计增量和热度快照安全同步到 MySQL。

首版目标：

- 提供热门资料 Top N 公开查询接口，优先读取 Redis ZSet。
- 提供热门搜索词 Top N 公开查询接口，读取搜索模块已经维护的 Redis ZSet。
- 让有效下载、收藏、取消收藏等行为实时调整资料热度分。
- 使用定时任务把 `crp:stats:resource:download:delta` 中的下载增量批量同步到 `resource.download_count`。
- 定时把总榜热度分快照回写到 `resource.hot_score`，为 Redis 故障降级和搜索排序提供数据。
- 使用带所有权标识的 Redis 分布式锁，避免多实例重复执行同步任务。
- 对 Redis 查询异常提供明确降级，不让热门榜故障扩大到搜索、下载、收藏主流程。

本模块要体现的核心价值：排行榜不是对 MySQL 做一次 `ORDER BY`，而是**Redis ZSet 实时排序 + 行为增量更新 + MySQL 快照兜底 + 定时批量同步 + 分布式锁 + 最终一致性补偿**的组合设计。

---

## 3. 需求分析

1. 搜索模块已在搜索成功后写入 `crp:rank:search:keyword:{period}`，但缺少查询热门搜索词的接口。
2. 下载模块已把去重后的下载次数写入 `crp:stats:resource:download:delta`，但尚未同步到 MySQL，也尚未增加资料热度分。
3. 收藏模块已维护 `resource.favorite_count`，但收藏和取消收藏尚未联动热门资料 ZSet。
4. `resource` 表已经存在 `download_count`、`favorite_count`、`view_count`、`hot_score` 字段和 `idx_resource_hot` 索引，无需新增表或字段。
5. 热门资料只允许展示 `status = 1` 的审核通过资料；Redis 中的下架、删除或失效成员必须在查询时过滤，并在状态变更时主动移除。
6. 热门资料支持 `daily`、`weekly`、`monthly`、`all` 四个周期；热门搜索词支持 `daily`、`weekly`、`monthly` 三个周期。
7. 热门资料接口支持可选 `categoryId`。当前 ZSet 是全局榜，不按分类拆 Key，因此首版需要从 ZSet 分段取候选 ID，再由 MySQL 校验状态和分类，并保持 Redis 分数顺序。
8. Redis 周期榜只代表模块上线后的行为增量，不能把 MySQL 历史总量伪装成日榜、周榜或月榜。首版只允许根据 MySQL 统计快照重建 `all` 总榜；其他周期榜从新行为开始累计。
9. 下载增量同步必须避免“任务读取 Hash 后、删除字段前又产生新增量”导致计数丢失。首版应先把待同步数据原子隔离到 syncing Key，再执行 MySQL 事务。
10. Redis 不是 MySQL 事务的一部分。同步失败时不能删除待同步批次，必须保留或合并回增量 Key，等待下一轮重试。

为什么不是简单 CRUD：本模块同时处理实时榜单、周期统计、高频计数削峰、分布式任务互斥、跨 Redis/MySQL 的最终一致性、失败补偿和公开数据可见性过滤。

---

## 4. 本模块不做什么

- 首版不新增 Elasticsearch、RocketMQ、Redisson 等依赖，使用现有 `StringRedisTemplate`、MyBatis 和 Spring Scheduling 实现。
- 首版不新增排行榜快照表或搜索词快照表，只复用 `resource.hot_score`、`download_count` 等现有字段。
- 首版不实现热门课程榜、热门上传者榜、下载趋势报表等扩展榜单。
- 首版不实现浏览量采集；`view_count` 仅作为总榜重建公式中的已有字段使用。
- 首版不实现复杂时间衰减算法；周期榜通过独立 Key 与 TTL 控制时效，总榜使用行为权重累计。
- 首版不把 MySQL 历史总量写入 `daily`、`weekly`、`monthly` 榜单。
- 首版不实现管理员专属排行榜接口；现有两个设计接口均为公开只读接口。
- 首版不修改数据库表结构、不新增第三方依赖、不改动已有接口路径。

---

## 5. 涉及接口

> 以下接口来自 `docs/04-api-doc.md` 第 9 节，当前均为设计接口，代码尚未实现。实际开发时如调整参数或返回结构，必须同步更新 API 文档和本文档。

### 5.1 获取热门资料排行榜

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/rankings/resources/hot` |
| 是否登录 | 否 |
| 权限要求 | 无，只返回审核通过资料 |
| 当前状态 | 待开发 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | int | 否 | 默认 10，范围 1～50 |
| `categoryId` | long | 否 | 分类 ID，必须大于 0 |
| `period` | string | 否 | `daily`、`weekly`、`monthly`、`all`，建议默认 `weekly` |

计划响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rank` | int | 当前返回结果中的名次，从 1 开始 |
| `resourceId` | long | 资料 ID |
| `title` | string | 资料标题 |
| `courseName` | string | 课程名称 |
| `downloadCount` | long | MySQL 下载总数快照 |
| `favoriteCount` | long | MySQL 收藏总数 |
| `hotScore` | decimal | Redis 实时分数；降级时为 MySQL `hot_score` |

处理约束：

- Redis 查询只提供候选 ID 和分数，资料标题、课程、统计字段仍从 MySQL 批量查询。
- 必须过滤非 `APPROVED` 资料，防止 Redis 脏成员导致下架资料重新公开。
- 有 `categoryId` 时按分类过滤，但最终顺序仍以 Redis 分数为主。
- Redis 不可用时降级查询 MySQL `resource.hot_score`；周期语义会退化为总榜快照，日志中应明确记录降级。

可能错误码：

| 错误码 | 场景 |
| --- | --- |
| `40001` | `limit`、`categoryId` 或 `period` 不合法 |
| `50001` | Redis 查询失败且 MySQL 兜底也失败 |

### 5.2 获取热门搜索词排行榜

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/rankings/search-keywords/hot` |
| 是否登录 | 否 |
| 权限要求 | 无 |
| 当前状态 | 待开发 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | int | 否 | 默认 10，范围 1～50 |
| `period` | string | 否 | `daily`、`weekly`、`monthly`，建议默认 `daily` |

计划响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rank` | int | 名次，从 1 开始 |
| `keyword` | string | 已归一化的搜索关键词 |
| `searchCount` | long | Redis ZSet 分数转换后的搜索次数 |

处理约束：

- 直接使用 `ZREVRANGE ... WITHSCORES` 获取 Top N。
- 热门搜索词属于可降级运营数据，Redis 不可用时返回空列表，不中断其他业务。
- 当前没有 MySQL 搜索词快照表，因此不得伪造 MySQL 兜底数据。

可能错误码：

| 错误码 | 场景 |
| --- | --- |
| `40001` | `limit` 或 `period` 不合法 |
| `50001` | 仅在项目最终决定不允许空列表降级时使用；实现前需统一 API 文档口径 |

---

## 6. 涉及数据库表

### 6.1 `resource` 资料表（本模块唯一直接使用的表）

本模块不新增、不修改表结构，复用以下真实字段：

| 字段 | 使用方式 |
| --- | --- |
| `id` | Redis ZSet member 与增量 Hash field 对应的资料 ID |
| `title` | 热门资料展示字段 |
| `category_id` | 热门资料分类过滤 |
| `course_name` | 热门资料展示字段 |
| `status` | 固定过滤 `STATUS_APPROVED = 1` |
| `view_count` | 总榜初始化/重建时的可选权重输入 |
| `download_count` | 定时接收 Redis 下载增量；榜单展示快照 |
| `favorite_count` | 热度计算输入；榜单展示快照 |
| `hot_score` | 总榜热度分快照与 Redis 故障兜底 |

已存在索引：

```text
idx_resource_hot (status, hot_score, download_count)
```

该索引支撑 MySQL 兜底查询：先固定 `status = 1`，再按 `hot_score`、`download_count` 排序。

### 6.2 其他表

- `download_record`：下载模块已写入下载记录，本模块首版不读取该表重算周期榜。
- `favorite`：收藏关系由收藏模块维护，本模块首版不扫描该表重算历史周期榜。
- 排行榜快照表、搜索词快照表：本模块暂未涉及。

---

## 7. 涉及 Redis Key

### 7.1 已存在并已使用的 Key

| Key | 结构 | 当前状态 | 本模块用途 |
| --- | --- | --- | --- |
| `crp:rank:search:keyword:{period}` | ZSet | 搜索模块已写入 | 查询热门搜索词 Top N |
| `crp:stats:resource:download:delta` | Hash | 下载模块已写入 | 定时同步下载量到 MySQL |

### 7.2 常量已定义但业务尚未使用的 Key

| Key | 结构 | TTL | 用途 |
| --- | --- | --- | --- |
| `crp:rank:resource:hot:daily` | ZSet | 2 天 | 日榜 |
| `crp:rank:resource:hot:weekly` | ZSet | 14 天 | 周榜 |
| `crp:rank:resource:hot:monthly` | ZSet | 60 天 | 月榜 |
| `crp:rank:resource:hot:all` | ZSet | 不设置 | 总榜 |
| `crp:lock:sync:download-delta` | String | 短 TTL | 下载增量同步任务互斥锁 |
| `crp:stats:resource:download:syncing:{batchId}` | Hash | 处理完成后主动删除 | 与实时 delta 隔离的待同步批次 |

> 步骤 2 已在 `RedisKeyConstants` 中补充热门资料榜、同步锁和 syncing 批次常量及格式化方法。当前仅完成统一定义，具体 Redis 读写仍归后续 Service 与定时任务步骤。

### 7.3 热度权重与更新时机

首版权重沿用 `docs/05-redis-design.md`：

| 行为 | 分数变化 | 周期 |
| --- | --- | --- |
| 审核通过 | `ZADD 0` | daily / weekly / monthly / all |
| 有效下载（通过 10 分钟去重） | `ZINCRBY +5` | daily / weekly / monthly / all |
| 收藏成功（非幂等重复） | `ZINCRBY +3` | daily / weekly / monthly / all |
| 取消收藏（真实状态 1→0） | `ZINCRBY -3` | daily / weekly / monthly / all |
| 下架或删除 | `ZREM` | daily / weekly / monthly / all |

浏览 `+1` 本模块首版暂未涉及，因为当前资料详情模块尚未实现浏览去重和浏览量统计。

### 7.4 一致性与降级

- 热门资料 ZSet 是实时数据源，`resource.hot_score` 是总榜快照和 MySQL 兜底。
- 热门搜索词只属于运营数据；Redis 丢失不影响搜索主流程。
- 行为热度更新失败时记录日志并降级，不回滚已经成功的下载或收藏主业务。
- 下载增量 Hash 不设置 TTL；只有 MySQL 事务成功后才允许删除对应 syncing 批次。
- 分布式锁必须保存随机 owner token，释放时通过 Lua 比较 token 后删除，不能直接 `DEL`。

---

## 8. 涉及核心类

### 8.1 复用已存在类（真实存在）

| 类型 | 类 | 作用 |
| --- | --- | --- |
| common | `ApiResponse` | 统一接口响应 |
| common | `ErrorCode` | 使用 `PARAM_ERROR(40001)`、`SERVER_ERROR(50001)` |
| common | `RedisKeyConstants` | 已有热门搜索词、下载增量、热门资料榜、同步锁和 syncing 批次 Key |
| entity | `Resource` | 已含状态、下载数、收藏数、浏览数、热度分字段 |
| mapper | `ResourceMapper` + XML | 复用 `selectByIds`，后续新增榜单和同步 SQL |
| service/impl | `SearchServiceImpl` | 已写入三周期热门搜索词 ZSet |
| service/impl | `DownloadServiceImpl` | 已完成下载去重和下载增量 `HINCRBY` |
| service/impl | `FavoriteServiceImpl` | 已维护收藏状态和 `favorite_count` |
| service/impl | `AuditServiceImpl` | 已实现审核通过、下架状态流转，后续联动榜单初始化/移除 |
| config | `WebMvcConfig` | 已放行 `/api/v1/rankings/**`，接口可匿名访问 |
| application | `CampusResourcePlatformApplication` | 当前未启用 `@EnableScheduling` |

### 8.2 本模块新增或计划新增类

以下表格通过“状态”区分真实实现与开发计划；实现时如有调整，必须以真实代码回写本文档。

| 类型 | 类/文件 | 职责 | 状态 |
| --- | --- | --- | --- |
| enums | `RankingPeriod` | 集中定义周期白名单、Key 后缀、TTL 和热词周期边界 | 已实现 |
| dto | `HotResourceRankingQueryDTO` | 接收 `limit`、`categoryId`、`period` | 待开发 |
| dto | `HotSearchKeywordRankingQueryDTO` | 接收 `limit`、`period` | 待开发 |
| vo | `HotResourceRankingVO` | 热门资料榜单项 | 待开发 |
| vo | `HotSearchKeywordRankingVO` | 热门搜索词榜单项 | 待开发 |
| service | `RankingService` | 排行榜查询和资料热度行为入口 | 待开发 |
| service/impl | `RankingServiceImpl` | ZSet 查询、MySQL 补齐、状态过滤、降级和热度增减 | 待开发 |
| controller | `RankingController` | 两个公开只读接口入口 | 待开发 |
| mapper | `ResourceMapper` + XML | 新增热门榜批量补齐、MySQL 兜底、下载增量和热度快照更新 SQL | 待开发 |
| service | `DownloadDeltaSyncService` | 定义一次下载增量同步业务 | 待开发 |
| service/impl | `DownloadDeltaSyncServiceImpl` | 批次隔离、MySQL 事务更新、成功清理和失败补偿 | 待开发 |
| task | `RankingSyncTask` | 按计划触发下载增量同步与热度快照任务，不承载事务细节 | 待开发 |
| config/application | 调度配置或启动类 | 启用 Spring Scheduling；不新增第三方依赖 | 待开发 |
| test | `RankingPeriodTest` | 校验周期 TTL、热词边界与排行榜 Key 格式 | 已实现 |
| test | `RankingControllerTest` 等 | 覆盖接口、Service、Mapper、Redis 降级与定时任务 | 待开发 |

> 项目现有约定要求 Spring Service 使用“接口 + 实现”。定时任务只负责触发，带事务的同步逻辑必须放入独立 Service，由 Spring 代理调用，避免同类自调用导致事务失效。

---

## 9. 模块内部调用关系（规划）

```text
RankingController
  ├── GET /rankings/resources/hot
  │     └── RankingService.listHotResources(query)
  │           ├── Redis ZREVRANGE crp:rank:resource:hot:{period} WITHSCORES
  │           ├── ResourceMapper 批量查询 APPROVED 候选资料
  │           ├── 按 categoryId 过滤并恢复 Redis 排名顺序
  │           └── Redis 异常时 ResourceMapper 查询 hot_score 兜底
  │
  └── GET /rankings/search-keywords/hot
        └── RankingService.listHotSearchKeywords(query)
              └── Redis ZREVRANGE crp:rank:search:keyword:{period} WITHSCORES

DownloadServiceImpl（有效下载）
  └── RankingService.increaseForDownload(resourceId) → 四周期 ZINCRBY +5

FavoriteServiceImpl（事务提交成功后）
  ├── RankingService.increaseForFavorite(resourceId) → 四周期 ZINCRBY +3
  └── RankingService.decreaseForFavorite(resourceId) → 四周期 ZINCRBY -3

AuditServiceImpl（MySQL 状态事务提交后）
  ├── 审核通过 → RankingService.initializeApprovedResource(resourceId)
  └── 下架 → RankingService.removeResource(resourceId)

RankingSyncTask
  └── DownloadDeltaSyncService.syncOnce()
        ├── 获取 crp:lock:sync:download-delta 分布式锁
        ├── 原子隔离 delta → syncing:{batchId}
        ├── ResourceMapper 原子累加 download_count（MySQL 事务）
        ├── 同步 all 榜热度分快照到 resource.hot_score
        └── 成功删除 syncing；失败保留/合并并重试
```

---

## 10. 请求处理流程（规划）

### 10.1 热门资料排行榜

1. Controller 绑定并校验 `limit`、`categoryId`、`period`。
2. Service 根据周期生成 `crp:rank:resource:hot:{period}`。
3. 从 ZSet 按分数倒序分段获取候选 `resourceId + score`。
4. 批量查询 MySQL，固定过滤 `status = 1`，可选过滤 `category_id`。
5. 移除不存在、下架、删除或分类不匹配的资料。
6. 按 Redis 候选顺序组装 `HotResourceRankingVO`，直到达到 `limit` 或候选耗尽。
7. Redis 访问失败时调用 MySQL `hot_score` 兜底查询。
8. 返回 `ApiResponse<List<HotResourceRankingVO>>`。

### 10.2 热门搜索词排行榜

1. Controller 校验 `limit`、`period`。
2. Service 读取 `crp:rank:search:keyword:{period}` Top N 和 score。
3. 过滤空白 member，按返回顺序生成名次。
4. Redis 不可用时记录日志并返回空列表。
5. 返回统一响应。

---

## 11. 数据流转流程（规划）

### 11.1 下载行为到排行榜

```text
下载请求成功
→ Redis SETNX 去重成功
→ HINCRBY download:delta resourceId 1
→ 四周期热门资料 ZSet 分数各 +5
→ 定时任务隔离一个 syncing 批次
→ MySQL resource.download_count 原子累加
→ MySQL 事务提交
→ 删除 syncing 批次
```

### 11.2 收藏行为到排行榜

```text
收藏/取消收藏 MySQL 事务提交
→ 确认是否发生真实状态变化
→ 收藏四周期 +3 / 取消收藏四周期 -3
→ Redis 失败只记录日志，不回滚收藏主事务
```

### 11.3 热度快照

```text
Redis all 总榜
→ 定时读取资源 ID 与 score
→ 批量更新 resource.hot_score
→ 搜索 hotScore 排序和 Redis 故障兜底读取快照
```

### 11.4 总榜初始化/重建

```text
MySQL 查询 APPROVED 资料
→ hotScore = downloadCount * 5 + favoriteCount * 3 + viewCount * 1
→ 写入 crp:rank:resource:hot:all
→ 不写 daily / weekly / monthly，避免伪造周期历史
```

---

## 12. 权限校验

- 两个排行榜接口均为公开只读接口，不要求登录。
- `WebMvcConfig` 当前已放行 `/api/v1/rankings/**`，无需再次修改放行规则，除非真实接口路径发生变化。
- 即使接口公开，Service/Mapper 仍必须固定过滤 `resource.status = 1`，不能依赖前端传状态。
- 定时任务没有 HTTP 入口，不从用户上下文取身份。
- 首版不提供手动触发同步的管理接口；若后续新增，必须要求管理员权限。

---

## 13. 参数校验

| 参数 | 规则 |
| --- | --- |
| `limit` | 空值使用默认值；必须在 1～50 之间 |
| `categoryId` | 可空；非空时必须大于 0 |
| 热门资料 `period` | `daily`、`weekly`、`monthly`、`all` |
| 热门搜索词 `period` | `daily`、`weekly`、`monthly`，不允许 `all` |

校验原则：

- DTO 使用 Bean Validation 完成基础校验。
- Service 对默认值和周期白名单进行兜底校验，保证直接调用 Service 时仍安全。
- 周期不能直接拼接任意 Redis Key，必须先映射为 `RankingPeriod` 白名单。
- ZSet member 转换为 `Long resourceId` 失败时视为脏数据，跳过并记录告警，不能导致整个榜单失败。

---

## 14. 异常处理

| 场景 | 处理策略 |
| --- | --- |
| 查询参数非法 | 抛 `BusinessException(ErrorCode.PARAM_ERROR)` |
| 热门资料 Redis 查询失败 | 记录日志，降级 MySQL `hot_score` 查询 |
| 热门资料 Redis 与 MySQL 都失败 | 抛 `BusinessException(ErrorCode.SERVER_ERROR)` 或交全局异常处理 |
| 热门搜索词 Redis 查询失败 | 记录日志，返回空列表 |
| ZSet 存在非法资料 ID | 跳过该 member 并记录告警 |
| ZSet 存在下架/删除资料 | 查询时过滤；异步清理脏 member |
| 行为热度写 Redis 失败 | 记录日志，不中断下载、收藏、审核主流程 |
| 未获取同步锁 | 本轮任务直接跳过，不视为业务异常 |
| 下载增量 MySQL 同步失败 | 事务回滚，保留或恢复 syncing 批次，等待重试 |
| 锁过期或释放失败 | 使用 owner token + Lua 安全释放，并记录告警 |

---

## 15. 事务处理

### 15.1 排行榜查询

只读查询不需要业务事务。

### 15.2 行为热度联动

- 下载、收藏、审核的 MySQL 主事务不能与 Redis 强行组成单体事务。
- 收藏/取消收藏必须在 MySQL 事务真实提交后再更新热度，避免回滚操作污染榜单。
- Redis 更新失败采用最终一致性和后续重建，不回滚用户主操作。

### 15.3 下载增量同步

- `RankingSyncTask` 只触发 `DownloadDeltaSyncService`，事务方法由 Spring 代理调用。
- 单个 syncing 批次内的 MySQL 下载量累加应使用 `@Transactional(rollbackFor = Exception.class)`。
- MySQL 更新使用 `download_count = download_count + delta`，禁止先查后改。
- MySQL 事务提交成功后才能删除 syncing Key。
- MySQL 事务失败时不得删除 syncing Key；下一轮任务先恢复遗留批次，再处理新 delta。
- Redis 与 MySQL 不具备原子提交能力，因此必须通过批次隔离、幂等边界和失败补偿实现最终一致性。

---

## 16. 核心实现步骤

1. 生成排行榜模块开发流程文档初稿（本步已完成）。
2. 补充排行榜 Redis Key 常量、周期枚举和 TTL 统一定义。
3. 创建排行榜查询 DTO 与 VO。
4. 为 `ResourceMapper` 补充候选资料批量查询、MySQL 兜底、下载量累加和热度快照 SQL。
5. 实现 `RankingService` 与 `RankingServiceImpl`，完成两个榜单查询及 Redis 降级。
6. 实现 `RankingController` 两个公开接口。
7. 接入下载、收藏、审核状态变化的热门资料 ZSet 联动。
8. 实现下载增量 syncing 批次、分布式锁与定时同步。
9. 实现总榜初始化/重建和 `resource.hot_score` 定时快照。
10. 补充 Controller、Service、Mapper、Redis 降级和定时任务测试。
11. 同步 API、Redis、进度、README、数据库变更日志等文档。
12. 根据真实代码更新本开发流程文档。

---

## 17. 开发任务拆分

| 序号 | 任务 | 主要产出 | 状态 |
| --- | --- | --- | --- |
| T1 | 模块文档初稿 | `09-rank-development-process.md` | 已完成 |
| T2 | Redis 常量与周期模型 | `RedisKeyConstants`、`RankingPeriod`、`RankingPeriodTest` | 已完成（`090c58f`） |
| T3 | DTO / VO | 两个查询 DTO、两个榜单 VO | 待开发 |
| T4 | Mapper 能力 | 榜单补齐/兜底、下载增量、热度快照 SQL | 待开发 |
| T5 | 排行榜 Service | 两榜查询、状态过滤、降级 | 待开发 |
| T6 | 排行榜 Controller | 两个公开 GET 接口 | 待开发 |
| T7 | 行为热度联动 | 下载 +5、收藏 ±3、审核初始化/下架移除 | 待开发 |
| T8 | 下载增量定时同步 | syncing 批次、锁、事务、补偿 | 待开发 |
| T9 | 总榜初始化与热度快照 | all 榜重建、`hot_score` 回写 | 待开发 |
| T10 | 专项测试 | Controller/Service/Mapper/Task 测试 | 待开发 |
| T11 | 文档同步 | API、Redis、进度、README 等 | 待开发 |
| T12 | 流程文档回写 | 根据真实实现更新本文档 | 待开发 |

每完成一个任务，必须先运行对应测试；通过后立即 `git add`、`git commit`、`git push` 到当前开发分支，并在本文档记录真实 commit id。

---

## 18. 已完成事项

- 已检查当前分支为 `dev`，任务开始时工作区干净并与 `origin/dev` 同步。
- 已阅读根目录 `AGENTS.md` 和 `docs/AGENTS.md`。
- 已读取 `docs/06-project-progress.md`，确认排行榜与定时任务是当前推荐开发模块。
- 已核对 `docs/01-requirements.md`、`docs/02-business-flow.md` 中的排行榜业务目标。
- 已核对 `docs/03-database-design.md` 与 `sql/init.sql`，确认 `resource` 已具备统计和热度字段及兜底索引。
- 已核对 `docs/04-api-doc.md` 第 9 节，确认两个排行榜设计接口。
- 已核对 `docs/05-redis-design.md`，确认热门资料、热门搜索词、下载增量、TTL 与一致性策略。
- 已核对真实代码：搜索热词写入和下载增量写入已实现；热门资料榜、查询接口、定时同步和分布式锁尚未实现。
- 已核对 `WebMvcConfig` 已放行 `/api/v1/rankings/**`，启动类尚未启用调度。
- 【步骤 1】已生成本开发流程文档初稿；未修改任何代码。
- 【步骤 2】已在 `RedisKeyConstants` 新增 `RESOURCE_HOT_RANK`、`DOWNLOAD_DELTA_SYNCING`、`DOWNLOAD_DELTA_SYNC_LOCK` 及对应格式化方法。
- 【步骤 2】已新增 `RankingPeriod`，统一维护 `daily`、`weekly`、`monthly`、`all` 编码、2/14/60 天 TTL 和热门搜索词不支持 `all` 的边界。
- 【步骤 2】已新增 `RankingPeriodTest`，4 个针对性用例全部通过；全量 53 个测试通过。
- 【步骤 2】功能提交为 `090c58f feat(rank): add ranking redis keys and period model`，已推送到 `origin/dev`。
- 按用户要求，工作区原有的 `FavoriteServiceImpl` 事务注释已原样单独提交为 `9d93fbb chore(favorite): clarify duplicate key rollback flow` 并推送。

---

## 19. 待完成事项

- T3～T12 的 DTO/VO、Mapper、Service、Controller、定时任务、专项测试和同步文档任务。
- 在实现前统一 `docs/04-api-doc.md` 中 Redis Key 示例的旧前缀写法，最终以 `crp:` 规范和 `RedisKeyConstants` 为准。
- 确定定时任务执行频率、锁 TTL、单批最大资料数等运行参数，并通过配置项集中管理。
- 确定热门搜索词 Redis 故障时“返回空列表”与 API 文档 `50001` 描述的最终口径。
- 实现后补充真实测试记录、修改文件、commit id 和推送结果。

---

## 20. 测试清单

### 20.1 热门资料接口

| 场景 | 预期结果 |
| --- | --- |
| 默认参数查询 | 返回默认周期 Top 10 |
| `daily/weekly/monthly/all` | 读取正确 Key |
| `limit=1/50` | 边界值成功 |
| `limit=0/51` | 返回 `40001` |
| `categoryId` 合法 | 只返回指定分类资料 |
| `categoryId<=0` | 返回 `40001` |
| ZSet 含下架/不存在资料 | 过滤脏成员并补足后续候选 |
| 相同 score | 返回顺序稳定且名次连续 |
| Redis 不可用 | 降级 MySQL `hot_score` 查询 |
| Redis 与 MySQL 都失败 | 返回统一服务端错误 |

### 20.2 热门搜索词接口

| 场景 | 预期结果 |
| --- | --- |
| daily/weekly/monthly | 读取正确热词 Key |
| period=all | 返回 `40001` |
| ZSet 为空 | 返回空列表 |
| 含空白 member | 跳过非法项 |
| Redis 不可用 | 按最终约定返回空列表并记录日志 |

### 20.3 行为热度联动

| 场景 | 预期结果 |
| --- | --- |
| 首次有效下载 | 四周期各 +5 |
| 10 分钟去重期内重复下载 | 不重复增加热度 |
| 新收藏 | 四周期各 +3 |
| 幂等重复收藏 | 不重复增加热度 |
| 取消有效收藏 | 四周期各 -3，不能重复扣减 |
| 审核通过 | 四周期初始化 member |
| 下架资料 | 四周期移除 member |
| Redis 异常 | 主业务成功，记录降级日志 |

### 20.4 下载增量定时同步

| 场景 | 预期结果 |
| --- | --- |
| delta 为空 | 安全结束，不更新 MySQL |
| 正常批次 | `download_count += delta`，成功后删除 syncing |
| 同步期间产生新下载 | 新增量留在 delta，不被旧批次删除 |
| MySQL 中途失败 | 整批事务回滚，syncing 保留/恢复 |
| 未获取锁 | 本实例跳过，不重复同步 |
| 锁过期后被其他实例获取 | 当前实例不能删除新 owner 的锁 |
| 遗留 syncing 批次 | 下一轮优先恢复或继续处理 |

### 20.5 总榜初始化与热度快照

| 场景 | 预期结果 |
| --- | --- |
| all 榜缺失 | 从 APPROVED 资料统计字段重建 |
| 存在下架资料 | 不进入重建结果 |
| 周期榜缺失 | 不用历史总量伪造周期榜 |
| 快照同步成功 | `resource.hot_score` 与 all 榜一致 |
| 快照同步失败 | MySQL 事务回滚，Redis 榜不受影响 |

### 20.6 计划测试命令

```powershell
cd campus-resource-platform
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd -Dtest=RankingControllerTest test
.\mvnw.cmd -Dtest=RankingServiceTest test
.\mvnw.cmd -Dtest=RankingServiceDatabaseIntegrationTest test
.\mvnw.cmd -Dtest=DownloadDeltaSyncServiceTest test
.\mvnw.cmd test
```

步骤 2 已执行测试记录：

| 命令 | 结果 |
| --- | --- |
| `.\mvnw.cmd -DskipTests compile` | 通过，94 个主源码文件编译成功 |
| `.\mvnw.cmd -Dtest=RankingPeriodTest test` | 通过，4 个测试，0 失败、0 错误、0 跳过 |
| `.\mvnw.cmd test` | 通过，53 个测试，0 失败、0 错误、0 跳过 |

---

## 21. 修改文件记录

### 21.1 本次真实修改

| 文件 | 操作 | 说明 |
| --- | --- | --- |
| `docs/modules/09-rank-development-process.md` | 新增 | 排行榜与定时任务模块开发流程文档初稿 |
| `campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java` | 修改 | 新增热门资料榜、同步锁和 syncing 批次 Key |
| `campus-resource-platform/src/main/java/com/john/campus/enums/RankingPeriod.java` | 新增 | 统一排行榜周期、TTL 和热词支持范围 |
| `campus-resource-platform/src/test/java/com/john/campus/enums/RankingPeriodTest.java` | 新增 | 验证周期规则与 Key 格式 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/FavoriteServiceImpl.java` | 用户注释提交 | 仅增加 DuplicateKeyException 事务回滚说明；不属于排行榜逻辑 |

### 21.2 步骤 2 明确未修改

- `campus-resource-platform/src/main/resources/**`
- `sql/**`
- Service、Controller、Mapper、调度和数据库配置

### 21.3 后续计划修改（尚未发生）

- 排行榜 DTO/VO/Service/Controller、`ResourceMapper` 及 XML。
- 下载、收藏、审核 Service 的热度联动点。
- 调度配置、同步 Service、`task` 包和对应测试。
- `docs/04-api-doc.md`、`docs/05-redis-design.md`、`docs/06-project-progress.md`、`README.md` 等同步文档。

---

## 22. 与其他模块的关系

| 模块 | 关系 |
| --- | --- |
| 资料模块 | 提供资料状态和展示字段；只有 APPROVED 资料可进入榜单 |
| 审核模块 | 审核通过初始化榜单成员，下架时移除榜单成员 |
| 搜索模块 | 生产热门搜索词 ZSet；`hot_score` 快照也用于搜索排序 |
| 下载模块 | 生产下载增量 Hash和去重结果；有效下载贡献 +5 热度 |
| 收藏模块 | 维护收藏数；真实收藏/取消贡献 +3/-3 热度 |
| Redis 设计 | 规定 Key、数据结构、TTL 和降级策略 |
| MySQL `resource` | 保存下载总数、收藏总数和热度分快照 |
| 认证模块 | 排行榜查询公开，不依赖登录；后续手动任务接口才需要管理员权限 |

---

## 23. 面试可讲点

1. 为什么排行榜选择 ZSet：`ZINCRBY` 原子累分，`ZREVRANGE` 高效获取 Top N。
2. 为什么不直接 `ORDER BY download_count`：高频行为实时写 MySQL 会放大锁和 IO 压力，榜单实时性也较差。
3. 为什么下载量使用 Hash：一个 Key 聚合多个资源增量，便于原子累加和定时批处理。
4. 如何避免同步时丢增量：先把 delta 原子隔离为 syncing 批次，新请求继续写新 delta；MySQL 成功后才清理旧批次。
5. 为什么仅有分布式锁还不够：锁只能防重复执行，不能解决“读取后又新增、随后 HDEL”造成的并发丢数。
6. 如何安全释放分布式锁：锁值保存 owner token，Lua 比较 token 后删除，避免误删过期后被其他实例获取的新锁。
7. Redis 与 MySQL 如何保证一致：不追求强事务，通过批次隔离、MySQL 事务、成功后确认、失败保留重试实现最终一致。
8. 如何防止下架资料出现在榜单：状态变更时主动 `ZREM`，查询时再由 MySQL 固定过滤 `status = 1` 双重兜底。
9. 为什么周期榜不能直接从总量重建：总量没有事件时间信息，把历史累计值写入日榜会破坏周期语义。
10. Redis 故障如何降级：热门资料降级 MySQL `hot_score`，热门搜索词返回空列表，用户核心搜索/下载/收藏流程继续可用。
11. 分类榜如何在不扩增 Redis Key 的情况下实现：从全局 ZSet 分段取候选，MySQL 批量校验分类并保持 Redis 顺序；流量上升后再评估分类维度 Key。
12. 定时任务事务为什么放 Service：`@Scheduled` 只负责触发，事务方法通过 Spring 代理调用，避免同类自调用事务失效。

---

## 24. 后续优化方向

- 使用 Lua 将热度四周期更新与 TTL 设置合并为原子操作。
- 使用 Redis Pipeline 批量查询/更新，减少网络往返。
- 对大规模下载增量分批处理，限制单次事务时长。
- 引入可靠事件或消息队列补偿 Redis 热度更新失败，但首版不新增 RocketMQ。
- 增加排行榜快照表，支持历史趋势、昨日榜和运营报表。
- 根据访问量增加分类榜、课程榜等独立 Key，避免全局榜分段过滤。
- 引入可配置权重和时间衰减，避免老资料长期占据总榜。
- 增加任务执行指标、批次大小、失败次数、遗留 syncing Key 监控和告警。
- 使用 Redis Cluster 时，为需要原子操作的相关 Key 设计一致的 hash tag。
- 补充管理员手动重建接口，并使用管理员权限和审计日志保护。

---

## 25. Git commit message 建议

| 步骤 | 建议 Message |
| --- | --- |
| T1 | `docs(rank): add ranking module development process` |
| T2 | `feat(rank): add ranking redis keys and period model`（已使用，commit `090c58f`） |
| T3 | `feat(rank): add ranking query DTOs and VOs` |
| T4 | `feat(rank): add ranking and statistics mapper queries` |
| T5 | `feat(rank): implement ranking service` |
| T6 | `feat(rank): add ranking query endpoints` |
| T7 | `feat(rank): connect resource behavior heat updates` |
| T8 | `feat(rank): sync download deltas with distributed lock` |
| T9 | `feat(rank): rebuild hot ranking and persist score snapshots` |
| T10 | `test(rank): add ranking and scheduled task tests` |
| T11 | `docs(rank): sync ranking module documentation` |

---

## 26. 分步骤开发提示词

> 使用说明：以下每条提示词都是一个最小可执行任务。一次只执行一步，先核对真实代码，再修改；完成对应测试后立即提交并推送当前开发分支。每一步都必须更新本文档的“已完成事项、待完成事项、修改文件记录、测试记录和真实 commit id”。

| 步骤 | 内容 | 当前状态 |
| --- | --- | --- |
| 步骤 1 | 创建排行榜模块开发流程文档初稿 | ✅ 已完成 |
| 步骤 2 | 补充 Redis Key 常量与周期模型 | ✅ 已完成（`090c58f`） |
| 步骤 3 | 创建排行榜 DTO 与 VO | 待执行 |
| 步骤 4 | 补充 ResourceMapper 排行榜与同步 SQL | 待执行 |
| 步骤 5 | 实现排行榜 Service | 待执行 |
| 步骤 6 | 实现排行榜 Controller | 待执行 |
| 步骤 7 | 接入下载/收藏/审核热度联动 | 待执行 |
| 步骤 8 | 实现下载增量定时同步 | 待执行 |
| 步骤 9 | 实现总榜重建与热度快照 | 待执行 |
| 步骤 10 | 补充排行榜模块测试 | 待执行 |
| 步骤 11 | 同步排行榜相关文档 | 待执行 |
| 步骤 12 | 更新本模块开发流程文档 | 待执行 |

### 步骤 2：补充 Redis Key 常量与周期模型

```text
请为排行榜与定时任务模块补充 Redis Key 常量和周期模型。

本步目标：
- 先读取 `docs/modules/09-rank-development-process.md`、`docs/05-redis-design.md` 和当前 `RedisKeyConstants`。
- 在 `RedisKeyConstants` 中补充热门资料榜、下载同步锁、syncing 批次 Key 模板及格式化方法。
- 创建 `RankingPeriod` 枚举，集中维护 daily/weekly/monthly/all 白名单和 TTL；热门搜索词不允许 all。
- 保留所有已有常量和方法签名不变。

完成标准：
- 业务代码无需硬编码完整 Redis Key。
- 周期和 TTL 与 `docs/05-redis-design.md` 一致。
- 关键常量、方法和周期限制有简洁中文注释。
- 运行针对性编译/测试，通过后提交并推送当前开发分支。
- 更新本流程文档的真实修改、测试、commit id 和状态。

本步不做什么：
- 不实现 Service、Controller、Mapper 或定时任务。
- 不新增第三方依赖。
- 不修改数据库结构。
```

### 步骤 3：创建排行榜 DTO 与 VO

```text
请创建排行榜模块的查询 DTO 与响应 VO。

本步目标：
- 创建热门资料查询 DTO：limit、categoryId、period。
- 创建热门搜索词查询 DTO：limit、period。
- 创建热门资料 VO：rank、resourceId、title、courseName、downloadCount、favoriteCount、hotScore。
- 创建热门搜索词 VO：rank、keyword、searchCount。
- 使用 Bean Validation 覆盖 limit 1～50、categoryId > 0 等基础规则。

完成标准：
- DTO 只接收请求参数，VO 不暴露 Entity。
- 字段与 `docs/04-api-doc.md` 第 9 节一致。
- 默认值和 period 业务白名单留给 Service 兜底处理。
- 编译/测试通过后提交并推送，并更新流程文档。

本步不做什么：
- 不实现 Controller、Service 或 Redis 查询。
- 不修改接口文档口径。
```

### 步骤 4：补充 ResourceMapper 排行榜与同步 SQL

```text
请为排行榜与定时任务模块补充 ResourceMapper 接口和 XML。

本步目标：
- 新增按候选 ID 批量查询审核通过资料的方法，可选 categoryId 过滤。
- 新增 MySQL 热门资料兜底查询：固定 status=APPROVED，按 hot_score DESC、download_count DESC、id DESC，支持 categoryId 和 limit。
- 新增 download_count 原子累加方法，使用 `download_count = download_count + delta`。
- 新增 hot_score 快照更新方法。
- 如需要批量 SQL，确保空集合由 Service 短路，所有参数通过 MyBatis 绑定。

完成标准：
- 查询不泄露非 APPROVED 资料。
- 不把前端参数直接拼进 SQL。
- 更新统计字段使用原子 SQL，不采用 SELECT 后 UPDATE。
- 补充 Mapper 数据库集成测试；通过后提交并推送。
- 更新流程文档。

本步不做什么：
- 不实现 Redis、Service、Controller 或定时调度。
- 不修改 `resource` 表结构和 `sql/init.sql`。
```

### 步骤 5：实现排行榜 Service

```text
请实现 RankingService 与 RankingServiceImpl，当前只完成排行榜查询能力。

本步目标：
- 实现热门资料查询：校验参数、读取 ZSet、批量补齐 MySQL、过滤状态/分类、保持 Redis 顺序。
- 候选中存在脏成员时继续分段读取，尽量补足 limit，设置合理扫描上限。
- Redis 热门资料查询失败时降级 MySQL hot_score。
- 实现热门搜索词查询：读取 ZSet WITHSCORES，Redis 异常返回空列表并记录日志。
- 使用 ObjectProvider 或等价方式保证测试切片未装配 Redis 时可降级。

完成标准：
- Service 接口与实现分离。
- period 只能通过 RankingPeriod 白名单生成 Key。
- 不直接返回 Resource Entity。
- 补充 Service 单测，覆盖参数、排序、过滤和降级；通过后提交并推送。
- 更新流程文档。

本步不做什么：
- 不实现行为热度更新、定时任务或 Controller。
- 不修改下载、收藏、审核模块。
```

### 步骤 6：实现排行榜 Controller

```text
请实现 RankingController 的两个公开只读接口。

本步目标：
- GET `/api/v1/rankings/resources/hot`。
- GET `/api/v1/rankings/search-keywords/hot`。
- Controller 只做参数绑定、Bean Validation、调用 RankingService 和返回 ApiResponse。
- 核对 WebMvcConfig 已放行 `/api/v1/rankings/**`；如路径不变，不重复修改配置。

完成标准：
- 接口路径、参数、响应与 API 文档一致。
- 未登录可访问，非法参数返回 `40001`。
- 新增 RankingControllerTest；通过后提交并推送。
- 更新流程文档。

本步不做什么：
- 不在 Controller 写 Redis 或 Mapper 逻辑。
- 不实现管理员接口或定时任务。
```

### 步骤 7：接入下载、收藏、审核热度联动

```text
请把现有下载、收藏、审核业务与热门资料 ZSet 联动，小步修改且保持主业务可用。

本步目标：
- 为 RankingService 增加资料热度行为方法。
- 下载只有在现有 SETNX 去重成功且下载增量计数成功时，四周期各 +5。
- 收藏仅在真实状态变化为已收藏后四周期各 +3；幂等重复收藏不加分。
- 取消收藏仅在真实状态 1→0 后四周期各 -3；重复取消不扣分。
- 审核通过后初始化四周期 member，下架后从四周期移除。
- Redis 异常只记录日志，不回滚下载、收藏、审核的 MySQL 主业务。

完成标准：
- 不改变已有接口响应和权限边界。
- 热度更新发生在正确的事务提交边界之后。
- 覆盖重复下载、重复收藏、重复取消、下架清理和 Redis 异常测试。
- 全量测试通过后提交并推送，并更新流程文档。

本步不做什么：
- 不实现浏览量热度。
- 不改变现有下载去重 TTL、收藏唯一索引或审核状态机。
```

### 步骤 8：实现下载增量定时同步

```text
请实现下载增量 Redis→MySQL 的安全定时同步。

本步目标：
- 创建 DownloadDeltaSyncService 接口与实现，事务方法由 Spring 代理调用。
- 创建 RankingSyncTask，只负责按计划触发 Service。
- 使用 `crp:lock:sync:download-delta` 获取分布式锁，value 为随机 owner token，设置合理 TTL。
- 使用 Lua 或等价原子操作把 delta 隔离为 syncing 批次，避免并发 HINCRBY 被 HDEL 丢失。
- 在 MySQL 事务中原子累加 download_count。
- 提交成功后删除 syncing；失败时保留或安全合并回 delta。
- 使用比较 owner token 的 Lua 释放锁。
- 启用 Spring Scheduling，不新增第三方依赖。

完成标准：
- 覆盖空批次、正常同步、同步期间新增量、事务失败、锁竞争、锁过期和遗留批次。
- 明确任务频率、锁 TTL、批次上限和配置来源。
- 针对性测试及全量测试通过后提交并推送。
- 更新流程文档。

本步不做什么：
- 不新增 Redisson、消息队列或数据库表。
- 不把事务逻辑写在 @Scheduled 方法内。
```

### 步骤 9：实现总榜重建与热度快照

```text
请实现热门资料 all 总榜初始化/重建和 hot_score 定时快照。

本步目标：
- all 榜缺失或管理员内部任务触发时，从 APPROVED 资料统计字段计算初始热度。
- 公式首版使用 downloadCount*5 + favoriteCount*3 + viewCount*1，不引入未实现的时间衰减。
- 只重建 all 总榜，不把历史总量写入 daily/weekly/monthly。
- 定时把 all 榜分数写入 resource.hot_score，供搜索排序和 MySQL 降级。
- 下架/删除资料不得写入榜单或快照更新候选。

完成标准：
- 重建过程可重复执行，结果稳定。
- 批量处理避免一次加载无限数据。
- 补充重建、过滤、快照事务失败测试。
- 测试通过后提交并推送，并更新流程文档。

本步不做什么：
- 不新增排行榜快照表。
- 不实现复杂时间衰减或历史周期回放。
```

### 步骤 10：补充排行榜模块测试

```text
请根据当前真实实现补充排行榜与定时任务模块专项测试。

本步目标：
- 补齐 RankingControllerTest、RankingServiceTest、Mapper 数据库集成测试和 DownloadDeltaSyncService/Task 测试。
- 覆盖本文档第 20 节的正常、边界、降级、并发和失败补偿场景。
- 如测试需要真实 Redis，明确区分单元测试与本地集成测试，不把环境依赖伪装成已通过。
- 运行模块针对性测试和 `mvnw.cmd test` 全量回归。

完成标准：
- 测试失败时先修复，不跳过关键一致性场景。
- 记录真实命令、用例数、结果和环境限制。
- 测试通过后提交并推送，并更新流程文档。

本步不做什么：
- 不借测试任务重构无关模块。
- 不删除或禁用已有测试。
```

### 步骤 11：同步排行榜相关文档

```text
请根据当前真实代码同步排行榜与定时任务相关文档。

本步目标：
- 更新 `docs/04-api-doc.md`，校准两个排行榜接口和错误降级口径。
- 更新 `docs/05-redis-design.md`，记录真实 Key、TTL、锁、syncing 批次和实现状态。
- 更新 `docs/06-project-progress.md`，记录已完成能力、测试结果和剩余事项。
- 更新 `README.md`、`docs/database/database-change-log.md`；若没有表结构变化，要明确“复用现有 resource 表，无数据库结构变更”。
- 如接口集合维护在 Postman，同步对应公开请求。

完成标准：
- 文档、代码、RedisKeyConstants、Mapper SQL 完全一致。
- 未实现能力不能写成已完成。
- 写清测试命令、结果、commit id 和推送状态。
- 文档校验后提交并推送。

本步不做什么：
- 不修改 Java 业务代码。
- 不新增数据库结构或依赖。
```

### 步骤 12：更新本模块开发流程文档

```text
请根据排行榜与定时任务模块当前真实代码更新开发流程文档。

本步目标：
- 更新 `docs/modules/09-rank-development-process.md`。
- 补全当前状态、已完成事项、待完成事项、测试清单、修改文件记录、调用关系、事务与补偿、面试可讲点、真实 commit id 和推送结果。
- 如类名、方法名、Key、任务频率、锁策略或接口字段与初稿不同，以真实实现为准修正文档。
- 保留并校准“分步骤开发提示词”。

完成标准：
- 文档能够回答业务问题、接口、调用链、表、Redis、权限、事务、一致性、测试、模块依赖和优化方向。
- 不存在“规划内容伪装为已完成”的描述。
- 文档只记录真实执行过的测试和真实提交。
- 校验后提交并推送当前开发分支。

本步不做什么：
- 不修改业务代码。
- 不扩展热门课程、趋势报表等新模块。
- 不删除已有文档章节。
```
