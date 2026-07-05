# 校园资料共享与智能检索平台 Redis 设计文档

## 1. 设计目标

Redis 在本项目中不是简单缓存，而是承担以下真实业务能力：

- 缓存高频读取的资料详情，降低 MySQL 查询压力。
- 使用 ZSet 实现热门资料排行榜和热门搜索词排行榜。
- 使用 Redis + Lua 实现下载限流，防止恶意刷下载量。
- 使用 Hash 临时统计下载量，由定时任务批量同步到 MySQL。
- 保存登录 Token 黑名单或登录态辅助信息。
- 缓存文件 MD5 去重结果，提升上传前秒传判断速度。

## 2. Key 命名规范

统一使用项目前缀 `crp`，表示 `campus-resource-platform`。

命名格式：

```text
crp:{业务模块}:{数据含义}:{维度}:{ID或周期}
```

示例：

```text
crp:cache:resource:detail:20001
crp:rank:resource:hot:daily
crp:rate:download:user:10001
crp:stats:resource:download:delta
```

设计原则：

- Key 使用小写英文和冒号分隔。
- 固定前缀便于统一扫描、监控和删除。
- ID 类数据放在最后，便于代码中拼接和排查。
- 排行榜类 Key 显式带周期，例如 `daily`、`weekly`、`monthly`、`all`。

## 3. Redis Key 总览

| 场景 | Key | 类型 | TTL |
| --- | --- | --- | --- |
| 资料详情缓存 | `crp:cache:resource:detail:{resourceId}` | String | 30 分钟 + 随机 0-5 分钟 |
| 热门资料排行榜 | `crp:rank:resource:hot:{period}` | ZSet | 按周期设置 |
| 热门搜索词排行榜 | `crp:rank:search:keyword:{period}` | ZSet | 按周期设置 |
| 用户下载限流 | `crp:rate:download:user:{userId}` | ZSet | 限流窗口 + 60 秒 |
| IP 下载限流 | `crp:rate:download:ip:{ip}` | ZSet | 限流窗口 + 60 秒 |
| 同资料重复下载去重 | `crp:dedup:download:{userId}:{resourceId}` | String | 10-30 分钟 |
| 下载量临时统计 | `crp:stats:resource:download:delta` | Hash | 不主动设置 TTL |
| 登录 Token 黑名单 | `crp:auth:token:blacklist:{jti}` | String | Token 剩余有效期 |
| 用户登录态版本 | `crp:auth:user:token-version:{userId}` | String | 可不设置或与登录策略一致 |
| 文件 MD5 去重缓存 | `crp:cache:file:md5:{fileMd5}:{fileSize}` | String | 6-24 小时 |
| 用户收藏集合 | `crp:user:favorites:{userId}` | Set | 30 分钟 |

## 4. 资料详情缓存

### 4.1 Key 设计

```text
crp:cache:resource:detail:{resourceId}
```

示例：

```text
crp:cache:resource:detail:20001
```

### 4.2 数据结构

String，存储 JSON 字符串。

示例值：

```json
{
  "resourceId": 20001,
  "title": "数据结构期末复习提纲",
  "description": "覆盖排序、树、图等重点内容",
  "categoryId": 10,
  "categoryName": "计算机基础",
  "courseName": "数据结构",
  "resourceType": 2,
  "tags": ["数据结构", "复习", "期末"],
  "status": 1,
  "downloadCount": 128,
  "favoriteCount": 35,
  "hotScore": 745.0,
  "createdAt": "2026-07-02 10:00:00"
}
```

### 4.3 使用场景

- 资料详情页高频访问。
- 下载前查询资料基础信息。
- 收藏前校验资料是否存在、是否 `APPROVED`。

### 4.4 TTL 策略

建议 TTL：

```text
30 分钟 + 0-5 分钟随机值
```

加入随机值是为了避免大量资料详情缓存同时过期，造成缓存雪崩。

### 4.5 更新时机

| 触发动作 | 处理方式 |
| --- | --- |
| 查询资料详情，缓存未命中 | 查询 MySQL，结果写入 Redis |
| 审核通过 | 删除该资料详情缓存 |
| 审核拒绝 | 删除该资料详情缓存 |
| 下架资料 | 删除该资料详情缓存 |
| 修改资料标题、简介、分类、标签 | 先更新 MySQL，再删除缓存 |
| 下载量、收藏数变化 | 可不立即更新详情缓存，由 TTL 自然刷新或异步刷新 |

### 4.6 MySQL 一致性处理

采用 Cache Aside 模式：

1. 查询时先读 Redis。
2. Redis 未命中时查 MySQL。
3. 查到数据后写 Redis。
4. 更新资料状态或基础信息时，先更新 MySQL，再删除 Redis 缓存。

对于审核通过、下架这种影响可见性的关键操作，建议使用延迟双删：

```text
更新 MySQL -> 删除 Redis -> 延迟 500ms 再删除一次 Redis
```

这样可以降低并发读写时旧缓存被重新写回 Redis 的概率。

### 4.7 为什么选择 String

- 资料详情是一个整体对象，String 存 JSON 最简单。
- 一次 `GET` 即可取出完整对象。
- Java 中可直接使用 Jackson 序列化和反序列化。
- 相比 Hash，String 更适合整体读写、整体失效的详情缓存。

## 5. 热门资料排行榜

### 5.1 Key 设计

```text
crp:rank:resource:hot:{period}
```

示例：

```text
crp:rank:resource:hot:daily
crp:rank:resource:hot:weekly
crp:rank:resource:hot:monthly
crp:rank:resource:hot:all
```

`period` 取值：

| period | 含义 |
| --- | --- |
| `daily` | 日榜 |
| `weekly` | 周榜 |
| `monthly` | 月榜 |
| `all` | 总榜 |

### 5.2 数据结构

ZSet。

Member：

```text
resourceId
```

Score：

```text
hot_score
```

热度分建议：

```text
hot_score = download_count * 5 + favorite_count * 3 + view_count * 1 - time_decay
```

### 5.3 使用场景

- 首页展示热门资料 Top N。
- 管理员后台查看热门资料。
- 搜索结果按热度排序时作为辅助数据。
- 下载、收藏、浏览后实时更新资料热度。

### 5.4 TTL 策略

| Key | TTL |
| --- | --- |
| `crp:rank:resource:hot:daily` | 2 天 |
| `crp:rank:resource:hot:weekly` | 14 天 |
| `crp:rank:resource:hot:monthly` | 60 天 |
| `crp:rank:resource:hot:all` | 不设置 TTL |

周期榜保留时间略长于统计周期，方便页面展示昨日、本周等数据，也方便排查问题。

### 5.5 更新时机

| 行为 | Redis 操作 | 分数变化建议 |
| --- | --- | --- |
| 资料审核通过 | `ZADD` 初始化资料分数 | `0` |
| 用户浏览资料 | `ZINCRBY` | `+1` |
| 用户收藏资料 | `ZINCRBY` | `+3` |
| 用户取消收藏 | `ZINCRBY` | `-3` |
| 用户下载资料 | `ZINCRBY` | `+5` |
| 资料下架或删除 | `ZREM` | 移出排行榜 |

下载、收藏、浏览行为需要同时更新多个周期榜，例如 `daily`、`weekly`、`monthly`、`all`。

### 5.6 MySQL 一致性处理

Redis 排行榜作为实时数据源，MySQL 保存快照或兜底字段：

- `resource.hot_score` 保存热度分快照。
- `resource.download_count` 保存下载总数。
- `resource.favorite_count` 保存收藏总数。

建议定时任务每 5-10 分钟执行：

1. 从 Redis ZSet 读取 Top N 或全量分数。
2. 批量更新 MySQL `resource.hot_score`。
3. 如果 Redis 数据丢失，可从 MySQL 的 `download_count`、`favorite_count`、`view_count` 重建总榜。

下架资料时必须执行：

```text
更新 MySQL status = OFFLINE -> 删除资料详情缓存 -> ZREM 排行榜
```

### 5.7 为什么选择 ZSet

- ZSet 天然支持按分数排序。
- `ZREVRANGE` 可以高效获取 Top N。
- `ZINCRBY` 可以原子增加热度分。
- 很适合排行榜、热度榜、积分榜等场景。

## 6. 热门搜索词排行榜

### 6.1 Key 设计

```text
crp:rank:search:keyword:{period}
```

示例：

```text
crp:rank:search:keyword:daily
crp:rank:search:keyword:weekly
crp:rank:search:keyword:monthly
```

### 6.2 数据结构

ZSet。

Member：

```text
归一化后的搜索关键词
```

Score：

```text
搜索次数
```

### 6.3 使用场景

- 生成热门搜索词排行榜。
- 首页搜索框展示热门词。
- 搜索建议接口优先展示高频关键词。
- 管理员观察学生最关心的课程和资料方向。

### 6.4 TTL 策略

| Key | TTL |
| --- | --- |
| `crp:rank:search:keyword:daily` | 2 天 |
| `crp:rank:search:keyword:weekly` | 14 天 |
| `crp:rank:search:keyword:monthly` | 60 天 |

### 6.5 更新时机

搜索接口执行成功后更新：

```text
ZINCRBY crp:rank:search:keyword:daily 1 "数据结构"
ZINCRBY crp:rank:search:keyword:weekly 1 "数据结构"
ZINCRBY crp:rank:search:keyword:monthly 1 "数据结构"
```

更新前需要做关键词归一化：

- 去除首尾空格。
- 统一大小写。
- 过滤空字符串。
- 过滤过长关键词。
- 可选：过滤敏感词和无意义词。

### 6.6 MySQL 一致性处理

热门搜索词可以只保存在 Redis 中，因为它属于运营统计数据，不是核心交易数据。

如果后续需要长期趋势分析，可以增加 MySQL 搜索词快照表，由定时任务同步：

```text
Redis ZSet -> search_keyword_snapshot
```

Redis 宕机或数据丢失时，不影响资料搜索主流程，只影响热门词展示。

### 6.7 为什么选择 ZSet

- 搜索词需要按搜索次数排序。
- 需要快速获取 Top N。
- `ZINCRBY` 可直接累加搜索次数。
- 周期榜只需不同 Key，不需要复杂表结构。

## 7. 用户下载限流

### 7.1 Key 设计

按用户限流：

```text
crp:rate:download:user:{userId}
```

按 IP 限流：

```text
crp:rate:download:ip:{ip}
```

按用户和资料去重：

```text
crp:dedup:download:{userId}:{resourceId}
```

示例：

```text
crp:rate:download:user:10001
crp:rate:download:ip:192.168.1.10
crp:dedup:download:10001:20001
```

### 7.2 数据结构

下载限流使用 ZSet 实现滑动窗口。

ZSet 的设计：

| 项目 | 值 |
| --- | --- |
| Member | 请求唯一标识，例如 `timestamp:random` |
| Score | 请求时间戳，毫秒 |

重复下载去重使用 String。

### 7.3 使用场景

- 限制单用户每分钟下载次数。
- 限制单 IP 每分钟下载次数。
- 限制同一用户短时间重复下载同一资料时重复增加下载量。

示例规则：

| 规则 | 建议值 |
| --- | --- |
| 单用户下载限流 | 每分钟最多 10 次 |
| 单 IP 下载限流 | 每分钟最多 30 次 |
| 同一用户同资料计数去重 | 10-30 分钟内只统计一次下载量 |

### 7.4 TTL 策略

| Key | TTL |
| --- | --- |
| `crp:rate:download:user:{userId}` | 限流窗口 + 60 秒 |
| `crp:rate:download:ip:{ip}` | 限流窗口 + 60 秒 |
| `crp:dedup:download:{userId}:{resourceId}` | 10-30 分钟 |

例如限流窗口为 60 秒，则限流 ZSet TTL 可以设置为 120 秒。

### 7.5 更新时机

下载接口进入核心业务前执行限流：

1. 删除窗口外请求记录：`ZREMRANGEBYSCORE key 0 now-window`
2. 查询窗口内请求数：`ZCARD key`
3. 如果请求数超过阈值，拒绝请求。
4. 未超过阈值，则写入当前请求：`ZADD key now requestId`
5. 刷新 TTL：`EXPIRE key window+60`

以上操作必须使用 Lua 脚本保证原子性。

下载成功后处理重复计数：

1. 查询 `crp:dedup:download:{userId}:{resourceId}`。
2. 如果不存在，则本次下载计入下载量和热度。
3. 如果存在，则允许下载，但不重复增加下载量和热度。
4. 首次计数后写入去重 Key，并设置 TTL。

### 7.6 MySQL 一致性处理

限流数据是临时风控数据，不需要同步 MySQL。

下载记录仍然写入 MySQL `download_record`：

- 限流通过后创建下载记录。
- 下载成功记录 `download_status = 1`。
- 下载失败记录 `download_status = 2` 和 `fail_reason`。

下载次数是否增加由去重 Key 决定，最终通过 Redis 下载量临时统计同步到 MySQL。

### 7.7 为什么选择 ZSet

- 滑动窗口需要按时间删除过期请求。
- ZSet 的 score 可以保存毫秒时间戳。
- `ZREMRANGEBYSCORE` 可以删除窗口外数据。
- `ZCARD` 可以统计当前窗口请求数。
- 比固定窗口计数更平滑，限流效果更准确。

## 8. 下载量临时统计

### 8.1 Key 设计

```text
crp:stats:resource:download:delta
```

### 8.2 数据结构

Hash。

Field：

```text
resourceId
```

Value：

```text
待同步到 MySQL 的下载增量
```

示例：

```text
HGETALL crp:stats:resource:download:delta
20001 -> 15
20002 -> 8
```

### 8.3 使用场景

- 下载成功后先写 Redis，避免每次下载都更新 MySQL。
- 定时任务批量同步下载增量。
- 与热门资料排行榜联动，提高热门榜实时性。

### 8.4 TTL 策略

不主动设置 TTL。

原因：

- 下载增量属于需要落库的数据。
- 如果设置 TTL，定时任务异常时可能导致统计丢失。
- 同步成功后由任务主动清理已处理字段。

### 8.5 更新时机

下载成功且通过重复计数判断后执行：

```text
HINCRBY crp:stats:resource:download:delta {resourceId} 1
ZINCRBY crp:rank:resource:hot:daily 5 {resourceId}
ZINCRBY crp:rank:resource:hot:weekly 5 {resourceId}
ZINCRBY crp:rank:resource:hot:monthly 5 {resourceId}
ZINCRBY crp:rank:resource:hot:all 5 {resourceId}
```

### 8.6 MySQL 一致性处理

定时同步流程：

1. 定时任务读取 `crp:stats:resource:download:delta`。
2. 获取所有 `resourceId -> delta`。
3. 批量执行 MySQL 更新：

```sql
UPDATE resource
SET download_count = download_count + ?
WHERE id = ?;
```

4. MySQL 更新成功后删除对应 Hash 字段：

```text
HDEL crp:stats:resource:download:delta {resourceId}
```

5. 如果 MySQL 更新失败，不删除 Redis Hash 字段，等待下一轮重试。

并发同步建议：

- 使用分布式锁 `crp:lock:sync:download-delta` 防止多个定时任务同时同步。
- 或者使用 Lua 脚本把待同步数据移动到临时 Key，再由任务处理。

更稳妥的同步方式：

```text
crp:stats:resource:download:delta -> crp:stats:resource:download:syncing:{batchId}
```

MySQL 成功后删除 `syncing` Key；失败则合并回 delta Key 或保留重试。

### 8.7 为什么选择 Hash

- 一个 Hash 可以保存多个资料的下载增量。
- `HINCRBY` 可以原子增加某个资料的下载量。
- 定时任务可以一次性读取全部待同步数据。
- 比每个资料一个 String Key 更容易批量处理。

## 9. 登录 Token

### 9.0 当前实现状态

当前用户认证模块已经实现 Token 黑名单 Key：

```text
crp:auth:token:blacklist:{jti}
```

对应代码：

```java
RedisKeyConstants.TOKEN_BLACKLIST
RedisKeyConstants.tokenBlacklist(String jti)
```

用户 Token 版本 Key 仍属于后续优化方向，本阶段代码暂未实现。

### 9.1 Key 设计

JWT 黑名单：

```text
crp:auth:token:blacklist:{jti}
```

用户 Token 版本：

```text
crp:auth:user:token-version:{userId}
```

说明：`crp:auth:user:token-version:{userId}` 为设计预留 Key，当前代码暂未实现。

示例：

```text
crp:auth:token:blacklist:7b2f3d8a9c
crp:auth:user:token-version:10001
```

### 9.2 数据结构

String。

JWT 黑名单值：

```text
logout
```

Token 版本值：

```text
3
```

### 9.3 使用场景

- 用户退出登录后，让未过期 JWT 立即失效。
- 管理员禁用用户后，可以通过递增 Token 版本让旧 Token 失效。
- 修改密码后，可以让用户历史 Token 全部失效。

### 9.4 TTL 策略

| Key | TTL |
| --- | --- |
| `crp:auth:token:blacklist:{jti}` | JWT 剩余有效期 |
| `crp:auth:user:token-version:{userId}` | 可不设置 TTL，或设置为用户最长登录有效期 |

JWT 黑名单 TTL 不应超过 Token 原始过期时间，否则会浪费 Redis 空间。

### 9.5 更新时机

| 动作 | Redis 操作 |
| --- | --- |
| 用户登录 | 签发 JWT，JWT 中写入 `userId`、`role` 和 `jti` |
| 用户退出 | `SET crp:auth:token:blacklist:{jti} logout EX 剩余秒数` |
| 请求鉴权 | 校验 JWT 签名、过期时间和 Redis 黑名单 |
| 修改密码 | 当前代码暂未实现 |
| 管理员禁用用户 | 当前代码暂未实现 |

### 9.6 MySQL 一致性处理

用户基础状态以 MySQL `user.status` 为准。

鉴权时建议：

1. 校验 JWT 签名和过期时间。
2. 查询 Redis 黑名单。
3. 当前用户查询接口会通过 MySQL `user.status` 校验用户状态。
4. 后续如果实现 Token 版本机制，可在拦截器中增加 Redis Token 版本校验。

如果 Redis 不可用：

- 对普通业务接口可以降级为只校验 JWT 和 MySQL 用户状态。
- 对管理员接口建议失败关闭，避免权限风险。

### 9.7 为什么选择 String

- 黑名单只需要判断 Key 是否存在。
- Token 版本只需要保存一个数字。
- String 的 `SET EX`、`EXISTS`、`INCR` 都很直接。
- 不需要复杂集合结构。

## 10. 文件 MD5 去重缓存

### 10.1 Key 设计

```text
crp:cache:file:md5:{fileMd5}:{fileSize}
```

示例：

```text
crp:cache:file:md5:5d41402abc4b2a76b9719d911017c592:1048576
```

### 10.2 数据结构

String，存储 JSON。

示例值：

```json
{
  "fileId": 30001,
  "fileMd5": "5d41402abc4b2a76b9719d911017c592",
  "fileSize": 1048576,
  "storageType": 1,
  "storagePath": "/data/upload/2026/07/02/abc.pdf",
  "status": 1
}
```

### 10.3 使用场景

- 上传前 MD5 检查接口。
- 上传文件时判断是否可以复用已有文件。
- 减少对 MySQL `file_info` 表的重复查询。

### 10.4 TTL 策略

建议 TTL：

```text
6-24 小时
```

文件 MD5 去重最终以 MySQL 唯一索引 `uk_file_md5_size` 为准，Redis 只做加速，不需要永久保存。

### 10.5 更新时机

| 触发动作 | 处理方式 |
| --- | --- |
| MD5 检查缓存未命中 | 查询 MySQL，查到后写 Redis |
| 新文件保存成功 | 写入 Redis MD5 缓存 |
| 文件被删除或状态异常 | 删除 Redis MD5 缓存 |
| 文件复用成功 | 可刷新 TTL |

### 10.6 MySQL 一致性处理

Redis 只作为查询加速，MySQL 是最终准数据源。

上传时必须仍然依赖 MySQL 唯一索引兜底：

```text
先查 Redis -> Redis 未命中查 MySQL -> 尝试插入 file_info -> 唯一索引防重复
```

并发上传同一文件时，可能两个请求都没有查到 Redis。此时由 MySQL 的 `uk_file_md5_size` 唯一索引保证不会重复插入文件记录。

### 10.7 为什么选择 String

- MD5 去重结果是一个小对象。
- 一次读取即可获得 `fileId`、大小、路径、状态等信息。
- 结构简单，适合缓存 MySQL 查询结果。

## 11. 用户收藏集合

### 11.1 Key 设计

```text
crp:user:favorites:{userId}
```

示例：

```text
crp:user:favorites:10001
```

### 11.2 数据结构

Set。

Member：

```text
resourceId
```

### 11.3 使用场景

- 资料详情页快速判断当前用户是否已收藏。
- 收藏接口防止重复收藏的第一层判断。
- 取消收藏时快速移除资料 ID。

### 11.4 TTL 策略

建议 TTL：

```text
30 分钟
```

用户收藏关系最终以 MySQL `favorite` 表和唯一索引 `uk_favorite_user_resource` 为准。

### 11.5 更新时机

| 动作 | Redis 操作 |
| --- | --- |
| 查询收藏状态，缓存不存在 | 从 MySQL 查询收藏列表，写入 Set |
| 收藏资料成功 | `SADD crp:user:favorites:{userId} {resourceId}` |
| 取消收藏成功 | `SREM crp:user:favorites:{userId} {resourceId}` |
| 用户收藏列表变更异常 | 删除该用户收藏 Set，等待下次重建 |

### 11.6 MySQL 一致性处理

收藏接口流程：

1. 先校验资料状态必须是 `APPROVED`。
2. 插入或更新 MySQL `favorite`。
3. MySQL 成功后更新 Redis Set。
4. 如果 Redis 更新失败，不影响主流程，记录日志并依赖 TTL 或下次查询重建。

重复收藏必须由 MySQL 唯一索引兜底，而不是只依赖 Redis Set。

### 11.7 为什么选择 Set

- Set 天然适合保存“用户收藏了哪些资料 ID”。
- `SISMEMBER` 判断是否收藏非常快。
- `SADD` 天然幂等，重复加入不会产生多份数据。
- 与 MySQL 唯一索引配合，可以同时保证性能和正确性。

## 12. Redis 与 MySQL 一致性总策略

### 12.1 缓存类数据

适用场景：

- 资料详情缓存。
- 文件 MD5 去重缓存。
- 用户收藏集合。

策略：

```text
读 Redis -> 未命中读 MySQL -> 写 Redis
更新 MySQL -> 删除或更新 Redis
```

优先使用“删除缓存”而不是“更新缓存”，因为删除更简单，不容易产生字段遗漏。

### 12.2 统计类数据

适用场景：

- 下载量临时统计。
- 热门资料排行榜。
- 热门搜索词排行榜。

策略：

```text
业务请求写 Redis -> 定时任务同步 MySQL 或快照 -> 失败保留 Redis 数据重试
```

注意：

- 下载量增量不能设置 TTL。
- 同步 MySQL 成功后再清理 Redis 增量。
- 定时任务需要分布式锁，避免多实例重复同步。

### 12.3 状态敏感类数据

适用场景：

- 资料审核通过。
- 审核拒绝。
- 下架资料。
- 用户禁用。

策略：

```text
先更新 MySQL 状态 -> 删除相关 Redis 缓存 -> 移除排行榜或更新 Token 版本
```

这些场景影响权限和可见性，不能只改 Redis。

### 12.4 Redis 异常降级

| 场景 | Redis 异常时处理 |
| --- | --- |
| 资料详情缓存 | 降级查 MySQL |
| 热门资料排行榜 | 降级查 MySQL `hot_score` |
| 热门搜索词 | 返回空列表或默认词 |
| 下载限流 | 建议失败关闭，防止被刷 |
| 下载量统计 | 下载主流程可继续，但记录补偿日志 |
| Token 黑名单 | 管理端接口建议失败关闭，普通接口可结合 MySQL 用户状态降级 |
| MD5 去重缓存 | 降级查 MySQL |

## 13. 推荐 Redis Key 常量

当前代码已实现：

```java
public final class RedisKeyConstants {
    public static final String TOKEN_BLACKLIST = "crp:auth:token:blacklist:%s";

    public static String tokenBlacklist(String jti) {
        return String.format(TOKEN_BLACKLIST, jti);
    }
}
```

后端可以建立 `RedisKeyConstants`：

```java
public final class RedisKeyConstants {
    public static final String RESOURCE_DETAIL = "crp:cache:resource:detail:%d";
    public static final String RESOURCE_HOT_RANK = "crp:rank:resource:hot:%s";
    public static final String SEARCH_KEYWORD_RANK = "crp:rank:search:keyword:%s";
    public static final String DOWNLOAD_RATE_USER = "crp:rate:download:user:%d";
    public static final String DOWNLOAD_RATE_IP = "crp:rate:download:ip:%s";
    public static final String DOWNLOAD_DEDUP = "crp:dedup:download:%d:%d";
    public static final String DOWNLOAD_DELTA = "crp:stats:resource:download:delta";
    public static final String TOKEN_BLACKLIST = "crp:auth:token:blacklist:%s";
    public static final String USER_TOKEN_VERSION = "crp:auth:user:token-version:%d";
    public static final String FILE_MD5_CACHE = "crp:cache:file:md5:%s:%d";
    public static final String USER_FAVORITES = "crp:user:favorites:%d";

    private RedisKeyConstants() {}
}
```

## 14. 面试表达要点

- 我没有把 Redis 只当普通缓存，而是用于排行榜、限流、统计缓冲、Token 黑名单和文件去重加速。
- 资料详情适合 String 缓存，因为它是整体读取、整体失效的对象。
- 热门资料和热门搜索词适合 ZSet，因为 ZSet 天然支持分数排序和 Top N 查询。
- 下载限流使用 ZSet 滑动窗口，比固定窗口计数更平滑，且可以用 Lua 保证原子性。
- 下载量使用 Hash 临时统计，可以减少 MySQL 高频 `UPDATE` 压力。
- 收藏状态可以用 Set 加速判断，但最终仍由 MySQL 唯一索引保证不重复收藏。
- 缓存一致性采用 Cache Aside；统计一致性采用 Redis 增量 + 定时落库；状态敏感操作以 MySQL 为准并删除缓存。
