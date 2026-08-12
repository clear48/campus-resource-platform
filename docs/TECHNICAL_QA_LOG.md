# 项目技术问答日志

本文件用于持续沉淀用户在 Codex 中提出的项目技术问题及其对应答案，方便后续检索、复盘和面试准备。

## 归档状态

`ENABLED`

- `ENABLED`：每次与本项目有关的技术提问都自动追加记录。
- `DISABLED`：暂停追加记录，已有历史内容保持不变。
- 用户可随时要求 Codex 开启、恢复、关闭或暂停归档，也可以直接修改本节状态值。
- 开关指令只修改状态，不作为技术问答写入日志。

## 记录规则

- 触发条件：用户消息中包含任何与本项目有关的技术问题。
- 记录时机：完成必要的代码、配置或文档核查后，并在发送当轮最终答复前追加。
- 记录粒度：每个独立问题对应一组“问题/答案”；同一消息中的多个问题按出现顺序编号。
- 内容要求：保留用户问题原文；答案与最终答复的技术结论一致，并包含必要依据、边界或不确定项。
- 历史保护：仅在文件末尾追加新记录，不改写或删除已有记录；纠错时新增更正记录。
- 安全要求：密码、Token、密钥、数据库凭据、个人信息等敏感数据统一写成 `<已脱敏>`。
- 非技术内容：寒暄、单纯的操作指令、进度催问，以及与本项目无关的问题不写入本日志。

## 记录模板

```markdown
## YYYY-MM-DD HH:mm:ss | 主题

### 问题 1

用户问题原文。

### 答案 1

与当轮最终答复一致的技术结论；必要时补充代码位置、验证依据、适用边界和未确认项。

### 关联信息

- 相关文件：`path/to/file`
- 验证方式：执行的命令或“仅文档/静态分析，未运行测试”
```

---

以下区域用于按时间顺序追加实际问答记录。

## 2026-08-07 09:36:00 | 下载增量同步幂等明细表与用户文件授权表

### 问题 1

如何理解数据库表中的**下载增量同步幂等明细表和用户文件授权表** **user\_file\_authorization**

### 答案 1

这两张表都不是普通业务主表，而是为关键边界提供数据库兜底：`download_delta_sync_item` 是 Redis 下载增量同步到 MySQL 时的“已处理事件账本”，解决 MySQL 已提交但 Redis 确认失败后重试造成的重复累计；`user_file_authorization` 是用户与全局去重物理文件之间的“可引用关系表”，解决知道或猜到 `fileId` 后跨用户复用他人文件的越权问题。

`download_delta_sync_item` 以 `(batch_id, resource_id)` 唯一。同一 Redis UUID 批次中，某资料的增量首次插入明细成功时，才在同一 MySQL 事务内执行 `resource.download_count += delta`。若数据库提交后 Redis `HDEL` 失败，下一轮仍以相同批次重试，`INSERT IGNORE` 命中唯一键后会校验原增量并跳过再次累加，随后继续确认 Redis。`confirmed_at` 表示 Redis 字段已经成功删除，不表示下载增量是否已落库；明细行本身存在就表示对应增量已经随事务持久化。因此明细不能在对应 Redis 批次仍可能重试时提前删除，批次 ID 也不能在重试时随意更换。

`user_file_authorization` 以 `(user_id, file_id)` 唯一，表示当前用户已经通过可信上传流程验证过该文件内容，可以在创建资料时引用这个全局去重后的 `file_info`。`source_type=1` 表示首次上传，`2` 表示实际上传内容后命中物理去重，`3` 表示历史迁移。MD5 预检和全局缓存只用于定位候选文件；未查到当前用户授权时，接口不能返回 `fileId`。用户真实上传相同内容后系统才幂等授予权限，创建 `resource` 时还会再次校验授权。该表表达的是“文件引用权”，不是文件所有权，也不直接等同于公开资料的下载权限。

两者的共同点是都使用“业务唯一键 + `INSERT IGNORE`”让重复操作收敛；不同点是前者保护 Redis→MySQL 同步的一致性，唯一键回答“这个批次的这条增量是否处理过”，后者保护跨用户文件复用的权限边界，唯一键回答“这个用户是否可引用这个文件”。

### 关联信息

- 相关文件：`sql/init.sql`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaPersistenceServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaSyncServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileAuthorizationServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceServiceImpl.java`
- 验证方式：核对建表 SQL、迁移 SQL、Mapper、Service 与模块文档；仅静态分析，未运行测试

## 2026-08-07 09:44:52 | download_delta_sync_item 的 batch_id 含义

### 问题 1

**download\_delta\_sync\_item表batch\_id字段的含义**

### 答案 1

`batch_id` 是一次 Redis 下载增量隔离批次的唯一身份标识，正常值由 `UUID.randomUUID()` 生成。同步任务通过 Lua 把实时 Hash `crp:stats:resource:download:delta` 原子重命名为 `crp:stats:resource:download:syncing:{batchId}`，并把同一个 UUID 写入 `syncing:current`；随后写入 MySQL 幂等明细的 `batch_id` 就来自这个 Redis 批次。

一个批次 Hash 可以包含多个 `resourceId -> delta`，因此同一个 `batch_id` 会出现在多条明细中；`(batch_id, resource_id)` 唯一键才唯一标识“某个隔离批次中的某个资料增量”。它不是用户下载记录 ID，也不严格等于定时任务执行 ID：单批最多处理 500 个资料，若 Hash 尚未处理完，后续定时任务会恢复同一批次，继续使用原 `batch_id`。

该字段的核心价值是保持重试身份稳定。若 MySQL 已经提交而 Redis `HDEL` 失败，下一轮从 `syncing:current` 恢复相同 UUID，插入相同 `(batch_id, resource_id)` 时命中唯一键，从而跳过 `download_count` 的重复累加。若重试时重新生成批次 ID，数据库会把它识别成新事件，幂等保护就会失效。建表注释中的 `legacy-active` 用于描述旧固定 `syncing:active` 批次的兼容背景；当前代码检测到这类没有历史幂等记录的遗留批次时只告警保留，不会自动累加。

### 关联信息

- 相关文件：`sql/init.sql`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaSyncServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaPersistenceServiceImpl.java`、`campus-resource-platform/src/main/resources/mapper/DownloadDeltaSyncItemMapper.xml`、`docs/05-redis-design.md`
- 验证方式：核对建表 SQL、Redis 批次隔离与恢复代码、MySQL 幂等持久化代码；仅静态分析，未运行测试

## 2026-08-07 10:01:00 | 文件内容去重与业务重复提交的区别

### 问题 1

**用户上传资料流程已经有根据 md5 + file\_size 去重，为什么还需要判断是否为业务重复提交**

### 答案 1

因为两次判断保护的对象不同：`md5 + file_size` 是物理文件层去重，回答“这份字节内容是否已经存储”，命中后复用同一个 `file_info.id` 和物理文件，避免重复落盘；业务重复提交是资料层幂等，回答“是否还应创建新的 `resource` 业务记录”。如果只有文件去重，同一用户连续提交同一个 `fileId` 时虽然不会多存一份文件，但仍会产生多条待审核资料，造成重复审核、重复展示以及统计口径混乱。

项目当前业务规则是按“同一用户 + 同一 `file_id` + 状态为 `PENDING_REVIEW` 或 `APPROVED`”判重。`ResourceServiceImpl.create` 调用 `countActiveByUploaderAndFileId`，命中后返回 `DATA_DUPLICATE`；查询不统计 `REJECTED`、`OFFLINE`、`DELETED`，因此这些状态是否允许重新提交可按后续业务规则处理。判重带有用户和状态维度，而文件唯一索引是全局的，因此不同用户上传相同内容可以复用同一物理文件，但各自建立合法的资料记录。

流程文档还描述了可以按课程、标题或资料用途放宽业务规则，让同一物理文件对应多条不同业务资料；这是可配置的业务策略。当前代码采用更保守的实现：同一用户只要对该 `fileId` 已有待审核或已通过资料，即使课程或标题不同也会拒绝。另一个实现边界是当前逻辑采用“先计数、后插入”，`resource` 表没有对应业务唯一约束，极端并发请求仍可能同时通过检查；若要强并发兜底，需要另行设计数据库约束、幂等键或锁。

### 关联信息

- 相关文件：`docs/02-business-flow.md`、`sql/init.sql`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceServiceImpl.java`、`campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml`
- 验证方式：核对上传去重、资料创建、重复计数 SQL、建表索引及业务流程文档；仅静态分析，未运行测试

## 2026-08-07 10:23:58 | 文件审核中的 MIME 属性

### 问题 1

介绍文件审核中的MIME属性

### 答案 1

MIME（Multipurpose Internet Mail Extensions）是文件内容类型的标准化声明，格式通常为 `主类型/子类型`，例如 PDF 为 `application/pdf`、PNG 为 `image/png`、文本为 `text/plain`。在文件审核中，它有两个主要用途：一是作为类型一致性的辅助线索，例如文件名为 `.pdf` 但 MIME 声明为 `application/x-msdownload` 时应提高风险等级；二是生成 HTTP `Content-Type`，决定浏览器尝试内联预览，还是作为普通二进制附件下载。`application/octet-stream` 表示通用未知二进制类型，不能据此认定文件危险或安全。

本项目将 MIME 保存到 `file_info.mime_type VARCHAR(100)`，值来自上传请求中 `MultipartFile.getContentType()`，允许为空并限制为 100 个字符。该值通常由浏览器或客户端根据文件名、操作系统或自身识别规则生成，上传者可以伪造，因此当前代码只把它当作元数据记录；上传拦截实际以文件扩展名白名单为准，尚未实现 MIME 与扩展名交叉校验、文件魔数识别或恶意内容扫描。流程文档中的“MIME 校验”是设计目标，不能描述为当前已经完成的安全能力。

管理员审核预览接口没有直接信任数据库 MIME，而是按服务端扩展名白名单映射安全响应类型：PDF、JPEG、PNG、TXT、MD 可以内联展示，其余类型统一返回 `application/octet-stream` 并强制附件下载；同时设置 `X-Content-Type-Options: nosniff` 和 `Cache-Control: private, no-store`，降低浏览器内容嗅探和审核文件缓存风险。普通下载接口目前仍优先使用已保存的 MIME 作为 `Content-Type`，无值时回退 `application/octet-stream`，这是待改进边界。更可靠的审核应综合扩展名、受控 MIME 映射、文件签名/魔数、格式解析和恶意文件扫描，不能只根据 MIME 作出审核结论。

### 关联信息

- 相关文件：`sql/init.sql`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/controller/AuditController.java`、`campus-resource-platform/src/main/java/com/john/campus/controller/DownloadController.java`、`docs/modules/03-file-upload-development-process.md`、`docs/project-improvement/01-improvement-audit.md`
- 验证方式：核对 MIME 入库、上传校验、管理员审核预览、普通下载响应头和改进审计文档；仅静态分析，未运行测试

## 2026-08-07 11:44:09 | Redis 在各模块中的职责

### 问题 1

详细说明redis在各个模块中承担的职责

### 答案 1

本项目中的 Redis 不是单一缓存，而是分别承担认证失效、安全票据、风控限流、文件预检加速、收藏状态缓存、实时排行榜、运营热词统计、下载增量缓冲以及多实例定时任务协调等职责；MySQL 仍是用户、文件、资料、收藏、下载记录和最终统计快照的事实来源。

- 认证模块：`crp:auth:token:blacklist:{jti}` 使用 String 保存已退出 JWT，TTL 等于 Token 剩余有效期。退出时写入黑名单，受保护请求在解析 JWT 后检查 Key 是否存在，实现无状态 JWT 的主动失效。当前代码把 Redis 作为鉴权链路的直接依赖；用户级 Token 版本 `crp:auth:user:token-version:{userId}` 仅为设计预留，尚未实现。
- 文件上传模块：`crp:cache:file:md5:{fileMd5}:{fileSize}` 使用 String 保存 `file_info.id`，TTL 为 6 小时，只加速上传前 MD5 预检；缓存未命中或异常时回查 MySQL。真实上传流程仍直接查 MySQL，并依靠 `(file_md5, file_size)` 唯一索引兜底并发去重；全局缓存命中后还必须校验当前用户的文件引用授权，不能把缓存当作授权依据。
- 资料模块：设计文档规划了 `crp:cache:resource:detail:{resourceId}` 详情缓存，但当前 `ResourceServiceImpl.getPublicDetail` 仍直接查询 MySQL，常量类中也没有该 Key，因此目前不能把资料详情缓存描述为已实现能力。
- 收藏模块：`crp:user:favorites:{userId}` 使用 Set 保存有效收藏的 `resourceId`，TTL 为 30 分钟，通过 `SISMEMBER` 加速收藏状态判断。收藏/取消收藏先在 MySQL 事务中更新收藏关系和计数，提交后再 `SADD`/`SREM`；Redis 异常时降级 MySQL，缓存缺失时按数据库重建。MySQL 唯一索引才是重复收藏的正确性兜底。
- 搜索模块：搜索成功后将归一化关键词写入 daily、weekly、monthly 三个 ZSet，member 为关键词、score 为搜索次数，TTL 分别为 2、14、60 天；排行榜接口倒序读取 Top N。该数据只用于运营展示，Redis 缺失或异常时搜索主流程继续，热词接口返回空列表。当前固定 Key 只通过事件续期，没有自然日/周/月轮换，因此并非严格日历窗口榜。
- 排行榜模块：资料热度使用 daily、weekly、monthly、all 四个 ZSet，member 为 `resourceId`、score 为实时热度。有效下载 `+5`、收藏 `+3`、取消收藏 `-3`，审核通过以 `ZINCRBY 0` 初始化，下架从四榜移除；查询时 Redis 只给候选 ID 和分数，资料公开状态和展示字段仍由 MySQL 校验，Redis 故障时按 `resource.hot_score` 快照降级。周期榜 TTL 为 2、14、60 天，总榜不设 TTL。
- 审核模块：审核本身以 MySQL 状态机和审核记录为准；事务提交后才间接调用排行榜服务。审核通过初始化榜单成员，下架移除榜单成员，Redis 失败不能回滚已经提交的审核状态。
- 下载模块：用户和 IP 限流分别使用 ZSet 滑动窗口，Lua 原子完成过期成员清理、计数、写入和续期；用户每分钟最多 10 次、IP 每分钟最多 30 次，Key TTL 为 120 秒，Redis 异常采用失败关闭。一次性下载票据使用 String，TTL 60 秒，只存随机票据的 SHA-256 摘要并绑定用户与下载记录，Lua 原子删除保证最多消费一次，异常同样失败关闭。`crp:dedup:download:{userId}:{resourceId}` 使用 String + 10 分钟 TTL 避免重复下载放大统计；首次计数才对下载增量 Hash 执行 `HINCRBY` 并更新热度，统计异常则跳过计数但不阻断已完成的下载主流程。
- 下载增量同步：`crp:stats:resource:download:delta` 是不设 TTL 的 Hash，field 为资料 ID、value 为待落库增量。定时任务每 60 秒获取 Redisson 看门狗锁，以 Lua 原子执行 `RENAME delta -> syncing:{uuid}` 并设置 current 指针，让新下载写入新的 delta；同步 MySQL 成功后才 `HDEL`。MySQL 通过 `(batch_id, resource_id)` 唯一幂等明细和同事务原子累加避免“数据库已提交、Redis 确认失败”后的重复计数。
- 总榜维护任务：Redis all 榜缺失时，从 MySQL 分批构建临时 ZSet，再用 `RENAME` 原子替换正式榜；每 5 分钟也会把 all 榜分数分批快照到 MySQL。重建用 Redisson 写锁，实时 `ZINCRBY`、下架移除和快照读取用同一读写锁体系，避免重建覆盖并发热度变更。

职责边界可概括为：认证黑名单、限流和一次性下载票据属于安全关键依赖，失败时拒绝请求；MD5、收藏状态、搜索热词和排行榜属于可降级缓存或派生数据；下载增量属于需要可靠落库的缓冲数据，不能设置 TTL；分布式锁、Lua、UUID 批次和 MySQL 幂等表共同承担跨实例原子性与最终一致性。当前还需注意，去重 Key 的 `SETNX` 与下载增量 `HINCRBY` 不是同一原子操作，若前者成功而后者失败会漏记一次增量；收藏空集合不创建占位 Key，会让无收藏用户持续回查 MySQL。

### 关联信息

- 相关文件：`docs/05-redis-design.md`、`campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java`、`campus-resource-platform/src/main/java/com/john/campus/interceptor/JwtAuthenticationInterceptor.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/AuthServiceImpl.java`、`FileServiceImpl.java`、`ResourceServiceImpl.java`、`FavoriteServiceImpl.java`、`SearchServiceImpl.java`、`RankingServiceImpl.java`、`AuditServiceImpl.java`、`DownloadRateLimiterImpl.java`、`DownloadServiceImpl.java`、`DownloadDeltaSyncServiceImpl.java`、`DownloadDeltaPersistenceServiceImpl.java`、`HotRankingMaintenanceServiceImpl.java`
- 验证方式：核对 Redis Key 常量、各 Service/Interceptor/Task 的真实读写路径、TTL、Lua、Redisson 锁和 MySQL 幂等落库代码；仅静态分析，未运行测试

## 2026-08-07 12:09:13 | 下载模块的滑动窗口限流

### 问题 1

解释下载模块中使用的滑动窗口

### 答案 1

下载模块的滑动窗口以每次请求到达时间 `now` 为右边界，只统计区间 `(now - 60秒, now]` 内的请求，而不是按 12:00、12:01 这样的自然分钟固定清零。用户维度限制每 60 秒最多 10 次，IP 维度限制每 60 秒最多 30 次；`DownloadServiceImpl.createDownloadRecord` 在访问资料、文件和下载记录数据库之前先调用限流器，减少无效请求穿透。

每个用户或 IP 对应一个 Redis ZSet。member 使用 `毫秒时间戳:UUID`，保证同一毫秒并发请求仍是不同成员；score 使用毫秒时间戳，便于按时间范围删除。一次请求执行 Lua 脚本：先用 `ZREMRANGEBYSCORE key 0 now-60000` 删除窗口外成员，再以 `ZCARD` 得到当前窗口请求数；若数量已经达到阈值，刷新 120 秒 TTL 后返回 0；否则用 `ZADD` 写入当前请求、刷新 TTL 并返回 1。窗口边界上的 `now-60000` 也会被删除，因此实际保留的是左开右闭区间。

Lua 把“清理、计数、判断、写入、续期”放在 Redis 单次原子执行中。若两个请求同时到达且窗口内已有 9 次，Redis 会串行执行两个脚本：第一个看到 9 次并写成第 10 次，第二个随后看到 10 次并被拒绝，不会出现两边都读到 9 而共同放行的竞态。120 秒 TTL 不是统计窗口，窗口仍是 60 秒；额外 60 秒只用于无流量后自动清理临时风控 Key。

相较固定窗口，滑动窗口避免边界突刺。例如用户在 12:00:59 连续请求 10 次，12:01:01 的新请求仍能看到过去 60 秒内的 10 次，因此会被拒绝；固定自然分钟计数器则可能在跨分钟后立即清零，短短两秒内放行 20 次。

当前实现先检查用户 Key，再检查 IP Key，两次检查分别原子，但二者不是一个联合原子操作：如果用户检查已写入记录、随后 IP 检查拒绝，本次请求仍会消耗一次用户额度。Redis 异常采用 fail-closed，统一返回限流错误，防止 Redis 故障被利用来绕过防刷；IP 维度的可靠性还取决于上游是否清洗并可信传递 `X-Forwarded-For` 等代理头。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadRateLimiterImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/controller/DownloadController.java`、`campus-resource-platform/src/test/java/com/john/campus/service/DownloadRateLimiterTest.java`、`docs/05-redis-design.md`
- 验证方式：核对限流调用入口、ZSet Lua 脚本参数、用户/IP 阈值、TTL、异常策略和现有 Mockito 测试；仅静态分析，未运行测试

## 2026-08-07 15:38:00 | 全局异常处理器配置

### 问题 1

介绍项目的全局异常处理器是如何配置的（开启技术问答日志）

### 答案 1

项目的全局异常处理器是 `GlobalExceptionHandler`。它没有在 `application.yml` 中手工注册，而是通过类上的 `@RestControllerAdvice` 同时获得全局 Controller 增强、异常捕获和 JSON 响应能力。启动类 `CampusResourcePlatformApplication` 位于根包 `com.john.campus` 并使用 `@SpringBootApplication`，因此 Spring Boot 默认组件扫描能够发现子包 `com.john.campus.exception` 下的处理器并注册到 Spring MVC 异常解析链。生产环境不需要额外配置；`@WebMvcTest` 属于切片测试，所以相关 Controller 测试通过 `@Import(GlobalExceptionHandler.class)` 显式导入它。

业务层、拦截器或工具类遇到可预期业务失败时抛出 `BusinessException`。该异常继承 `RuntimeException`，保存稳定的整数业务错误码，并支持使用 `ErrorCode` 默认提示或覆盖为更具体的消息。`handleBusinessException` 捕获它后，调用 `resolveHttpStatus` 计算 HTTP 状态，再通过 `ApiResponse.fail(code, message)` 生成统一响应。当前映射规则是：未认证或 Token 失效映射 401、无权限映射 403、资源不存在映射 404、请求过频映射 429、文件过大映射 413、所有 `5xxxx` 错误映射 500，其余业务错误默认映射 400。因此当前 `40901/40902` 和 `41501` 虽然业务码表达 409/415 语义，实际 HTTP 状态仍是 400，并不是按业务码前三位自动映射。

请求进入 Controller 前后的框架异常也有专门处理：`MethodArgumentNotValidException` 处理 `@RequestBody` 校验失败，`BindException` 处理表单或查询对象绑定失败，二者优先返回第一个字段错误；`ConstraintViolationException` 处理路径变量或单个查询参数约束；`MethodArgumentTypeMismatchException` 处理类型转换失败；`HttpMessageNotReadableException` 处理非法或不可读 JSON；`MaxUploadSizeExceededException` 返回文件过大和 HTTP 413；`MissingServletRequestPartException`、`MissingServletRequestParameterException` 分别处理缺失 multipart 文件部分和必填查询参数。这些参数类异常统一使用 `PARAM_ERROR(40001)` 和 HTTP 400。最后的 `@ExceptionHandler(Exception.class)` 是兜底分支，固定返回 HTTP 500 与 `SERVER_ERROR(50001)`，不把未知异常详情或堆栈暴露给客户端。

所有失败响应均采用 `ApiResponse` 的 `code/message/data/traceId` 结构，其中失败时 `data` 为 `null`，每次响应生成一个去掉连字符的 UUID 作为 `traceId`。这保证前端同时拥有 HTTP 状态和稳定业务码两层判断依据。不过当前兜底处理器没有接收或记录异常对象，`traceId` 也尚未写入日志上下文，所以它目前只是响应标识，还没有形成可按 `traceId` 查询服务端异常堆栈的完整链路。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/exception/GlobalExceptionHandler.java`、`campus-resource-platform/src/main/java/com/john/campus/exception/BusinessException.java`、`campus-resource-platform/src/main/java/com/john/campus/common/ErrorCode.java`、`campus-resource-platform/src/main/java/com/john/campus/common/ApiResponse.java`、`campus-resource-platform/src/main/java/com/john/campus/CampusResourcePlatformApplication.java`
- 验证方式：核对组件扫描、异常类型到处理方法的映射、HTTP 状态解析和统一响应生成逻辑；运行 `.\\mvnw.cmd '-Dtest=ResourceControllerTest,AuditControllerTest,RankingControllerTest,AdminRankingControllerTest' test`，共 28 个测试通过，0 失败、0 错误

## 2026-08-07 16:04:04 | 文件上传模块 Redis 的触发时机与作用

### 问题 1

文件上传模块的redis配置什么时候触发，有什么作用

### 答案 1

文件上传模块的 Redis 分为“启动时装配连接”和“业务请求时真正读写”两层。应用启动时，Spring Boot 读取 `spring.data.redis` 下的 host、port、password、database，并自动装配 `StringRedisTemplate` 注入 `FileServiceImpl`；这一步只是准备 Redis 客户端。真正访问 Redis 只发生在文件 MD5 缓存的读取或写入方法被调用时。

当前 Key 为 `crp:cache:file:md5:{fileMd5}:{fileSize}`，类型是 String，实际值是 `file_info.id` 字符串，TTL 固定为 6 小时。它的核心作用是把“MD5 + 文件大小”快速映射为候选物理文件 ID，加速上传前秒传预检，减少重复查询 `file_info`，但不保存文件内容，也不承担权限判断和最终去重正确性。

Redis 读取只在 `GET /api/v1/files/check` 的 `checkByMd5AndSize` 中触发：先按 MD5 和大小 `GET`；命中后仍查询 MySQL `user_file_authorization`，只有当前用户已获文件引用授权才返回 `secondUpload=true` 和 `fileId`，否则返回不可秒传且不泄露 ID。缓存未命中或 Redis 异常时查询 MySQL `file_info`；查到后回填 Redis，再做相同授权检查；数据库也未命中时直接返回不存在，当前没有负缓存。

Redis 写入在三类场景触发：新文件完成落盘、`file_info` 与首次授权事务成功后写入；真实上传计算服务端 MD5 后直接查 MySQL，若已存在则先增加引用计数并授予当前用户引用权，再写入或续期；并发上传命中 `(file_md5, file_size)` 唯一索引时，删除本次多余物理文件、读取赢家记录、授权当前用户后写入或续期。真实 `POST /api/v1/files` 上传流程本身不会先读 Redis，因为服务端仍需完整 `FileInfo`、状态与数据库唯一约束来保证正确性。

Redis 只是可降级加速层：读失败按缓存未命中处理并回源 MySQL，写失败只记录日志，不影响已经完成的上传、授权或秒传；并发去重最终由 MySQL 唯一索引 `uk_file_md5_size` 保证，用户是否可引用文件由 `user_file_authorization` 保证。Key 同时包含 MD5 和大小，既与数据库唯一键一致，也降低单独 MD5 碰撞或错误预检的风险。

当前边界包括：设计文档仍写成缓存完整 JSON，真实代码只保存 `fileId`；缓存命中时没有再次查询 `file_info` 的当前状态，文件删除或异常后需要主动删除对应 Key，否则在 6 小时 TTL 内可能产生陈旧预检结果，而当前代码中尚未发现文件状态变更时的缓存删除调用。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/resources/application.yaml`、`campus-resource-platform/src/main/java/com/john/campus/controller/FileController.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileAuthorizationServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java`、`campus-resource-platform/src/test/java/com/john/campus/service/FileServiceImplTest.java`、`sql/init.sql`、`docs/05-redis-design.md`
- 验证方式：核对 Redis 自动配置、预检与真实上传调用链、缓存读写、授权事务、数据库唯一索引及现有单元测试；仅静态分析，未运行测试

## 2026-08-07 16:36:07 | 文件上传模块的并发处理

### 问题 1

文件上传模块是如何解决并发问题的

### 答案 1

文件上传模块没有使用 JVM 锁或 Redis 分布式锁，而是采用“前置查询降低冲突概率 + MySQL 唯一索引最终裁决 + 竞争失败转秒传 + 短事务 + 文件删除补偿”的方案。真实上传由服务端计算 MD5 和文件大小，先按 `(file_md5, file_size)` 查询 `file_info`；该组合同时受唯一索引 `uk_file_md5_size` 约束，因此前置查询即使出现先查后写竞态，也不会生成两条有效文件记录。

两个请求首次并发上传相同内容时，二者可能都查询未命中，并各自使用 UUID 文件名落盘，所以不会互相覆盖。随后二者在独立 Spring 事务 Bean `FileAuthorizationServiceImpl.createAuthorizedFile` 中尝试写入文件元数据和首次用户授权；一个请求成为赢家，另一个请求被数据库唯一索引阻塞并最终抛出 `DuplicateKeyException`。失败事务由 Spring 代理先回滚，外层 `FileServiceImpl` 再捕获异常，删除本请求刚写入的 UUID 文件，重新查询赢家记录，并转入秒传流程。最终两个响应指向同一个 `fileId`，只保留赢家的物理文件；删除是尽力补偿，失败时只记录日志。

秒传命中既有文件时，引用计数使用 `UPDATE file_info SET ref_count = ref_count + 1 WHERE id = ?`，由数据库对单行做原子自增，避免 Java 层“先读再写”造成丢失更新。用户授权使用 `(user_id, file_id)` 唯一索引与 `INSERT IGNORE`，并发重复授权最终只保留一条；引用计数更新与授权写入位于同一个短事务中，任一未捕获异常都会整体回滚。文件 MD5 计算和落盘位于数据库事务外，避免大文件 IO 长时间占用数据库事务。

Redis 只缓存 `MD5 + 大小 -> fileId` 来加速上传前预检，不参与并发裁决，也不是权限依据。Redis 未命中或故障时回查 MySQL；真实上传始终直接查数据库。预检即使命中全局缓存，也必须查询 `user_file_authorization`，只有当前用户已通过真实上传获得授权才返回 `fileId`。

当前实现仍有未完全解决的边界：同一用户重复或并发上传同一文件时，`ref_count` 每个请求都会自增，但用户授权因唯一索引只新增一次，导致引用计数可能虚高；如果 `ref_count` 表示有效授权或业务资料引用，应改为授权插入成功后才自增，或把计数移动到资料创建/删除事务。`increaseRefCount` 也没有校验更新行数和文件正常状态，与并发删除组合时可能写入悬空授权。补偿删除失败或进程在落盘后崩溃仍可能留下孤儿文件，目前没有定时对账清理任务。现有 `FileServiceImplTest` 的 3 个 Mockito 单元测试全部通过，但只覆盖预检授权隔离和秒传授权调用，没有验证真实 MySQL 唯一索引等待、事务代理回滚、并发最终状态和磁盘文件数量；模块文档第 15 节仍称相关写入是单条 SQL，也已落后于当前两表事务代码。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileAuthorizationServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileStorageServiceImpl.java`、`campus-resource-platform/src/main/resources/mapper/FileInfoMapper.xml`、`campus-resource-platform/src/main/resources/mapper/UserFileAuthorizationMapper.xml`、`campus-resource-platform/src/test/java/com/john/campus/service/FileServiceImplTest.java`、`sql/init.sql`、`docs/modules/03-file-upload-development-process.md`
- 验证方式：核对上传调用链、Spring 事务代理边界、唯一索引、原子 SQL、用户授权幂等、Redis 降级和文件补偿逻辑；运行 `.\mvnw.cmd -Dtest=FileServiceImplTest test`，共 3 个测试通过，0 失败、0 错误

## 2026-08-07 17:05:01 | 文件 MD5 三态与负缓存新增改动

### 问题 1

详细讲解新增改动

### 答案 1

本次改动把原先散落在 `FileServiceImpl` 中的“MD5 + 文件大小 -> fileId”正缓存逻辑抽取为独立 `FileMd5CacheService`，并把二态的“命中/未命中”升级为 `FOUND(fileId)`、`NOT_FOUND`、`ABSENT` 三态。`FOUND` 表示 Redis 有合法正数 fileId；`NOT_FOUND` 表示最近一次 MySQL 回源确认不存在；`ABSENT` 表示 Key 不存在、值损坏或 Redis 读取异常，业务仍需查询 MySQL。这样可以严格区分“Redis 没有结论”和“数据库已经确认不存在”。

Key 仍为 `crp:cache:file:md5:{fileMd5}:{fileSize}`，兼容历史纯数字正值。正缓存使用普通 `SET` 保存 fileId，TTL 6 小时；负缓存使用固定哨兵 `NOT_FOUND`，通过 `SET NX` 写入，TTL 5 分钟。普通 SET 允许上传成功后的正值覆盖旧负值；SET NX 则保证一个较早的数据库未命中结果不能覆盖并发上传已经写入的正值。若负值先写，上传正值随后覆盖；若正值先写，负值的 NX 写入失败，因此只要正缓存写成功，最终结果都收敛为正值。

缓存读取会把 null 解释为 ABSENT，把 `NOT_FOUND` 解释为负命中，把可解析的正 long 解释为 FOUND；非正数、long 溢出和其他协议外字符串会最佳努力删除并按 ABSENT 回源。日志只记录值长度，不记录原始坏值，避免超长内容或换行污染日志。GET、SET、SET NX、DEL 的 RuntimeException 都只告警，不让 Redis 反向破坏数据库主流程。

上传前预检 `checkByMd5AndSize` 的行为是：FOUND 时跳过 `file_info` 查询，但仍查询当前用户的 `user_file_authorization`；NOT_FOUND 时直接返回不可秒传，不查询文件表和授权表；ABSENT 时回源 `file_info`，数据库命中则写正缓存并检查授权，数据库未命中则写 5 分钟负缓存。全局文件存在不等于当前用户有权引用，未授权用户始终得到 `secondUpload=false` 和 null fileId。

真实上传仍不读取 Redis，而是使用服务端计算的 MD5 与文件大小直接查询 MySQL。命中既有文件时，`authorizeExistingFile` 在事务中原子自增引用计数并幂等写用户授权，事务代理成功返回后才写正缓存；首次上传在文件落盘后调用 `createAuthorizedFile`，文件记录和首次授权事务提交后才写正缓存；并发插入命中唯一键时删除本请求的多余文件，查询赢家记录、完成授权后再写赢家 fileId。授权或创建事务失败时不会写正缓存，相关失败路径已有回归测试。

新增 `evict(md5, size)` 只提供未来显式失效能力，目前仓库仍没有真实文件删除、恢复或状态变更入口调用它，因此不能声称生命周期失效闭环已经完成。后续 `BATCH-17` 应先确定逻辑删除后的重传语义，再在数据库事务提交后调用 evict；当前 `selectByMd5AndSize` 仍未限制 `status=1`，`increaseRefCount` 也未附带正常状态条件，这些属于尚未扩大的生命周期边界。

负缓存解决的是同一个不存在 MD5 被反复查询时持续穿透 MySQL的问题，不解决首次同 Key 并发查询，也不防御攻击者不断生成不同 MD5 的高基数穿透。另一个诚实边界是：如果上传事务已经提交，但正缓存 SET 因 Redis 故障或进程中断失败，旧负值可能让预检最多 5 分钟短暂假阴性；真实上传仍直接查 MySQL并正确转秒传，所以数据库文件与授权数据不受影响。

测试新增 `FileMd5CacheServiceImplTest` 12 项与扩充后的 `FileServiceImplTest` 9 项，共 21 项，覆盖三态协议、6 小时/5 分钟 TTL、普通 SET/SET NX、坏值删除、Redis 异常降级、授权隔离、数据库回源、事务失败不写缓存、落盘补偿和唯一键冲突回退。实现交付时后端全量 173 项测试全部通过。

### 关联信息

- 相关提交：`67d2a0c6 feat(file): add md5 negative cache`、`ddead6f6 docs(file): clarify cache test record`
- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/service/FileMd5CacheService.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileMd5CacheServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileAuthorizationServiceImpl.java`、`campus-resource-platform/src/test/java/com/john/campus/service/FileMd5CacheServiceImplTest.java`、`campus-resource-platform/src/test/java/com/john/campus/service/FileServiceImplTest.java`、`docs/05-redis-design.md`
- 验证方式：核对当前 `dev` 分支上述提交后的缓存协议、预检/上传调用链、事务代理边界、Mapper 状态条件和测试用例；本轮为代码解释，未重复运行测试，引用实现交付时 21 项专项及 173 项全量测试结果
## 2026-08-12 10:00:00 | 文件上传模块中 Redis 的实际作用

### 问题 1

根据当前项目的实际代码总结文件上传模块中redis的作用

### 答案 1

当前文件上传模块只使用一个 Redis String Key：`crp:cache:file:md5:{fileMd5}:{fileSize}`。它缓存“MD5 + 文件大小”对应的全局物理文件候选结果：正值是十进制 `file_info.id`，TTL 为 6 小时；负值是哨兵 `NOT_FOUND`，TTL 为 5 分钟。缓存读取分为 `FOUND`、`NOT_FOUND`、`ABSENT` 三态：正值命中可跳过 `file_info` 查询，但仍必须查询 MySQL `user_file_authorization`，只有当前用户已经获得文件引用授权才返回可秒传和 `fileId`；负值命中直接返回不可秒传，以减少重复无效预检穿透；缓存未命中、坏值或 Redis 读取异常则回源 MySQL。坏值会最佳努力删除。

Redis 的主要作用是加速 `GET /api/v1/files/check` MD5 预检、防止同一不存在文件反复穿透数据库，并在数据库查询命中、新文件及授权事务提交成功、真实上传命中既有文件后回填正缓存。负缓存使用 `SET NX`，避免数据库旧未命中结果覆盖并发上传已写入的正值；正缓存使用普通 `SET`，可覆盖旧负缓存。Redis 写入或删除异常只记录告警，不回滚已提交的数据库主流程。

Redis 不承担最终文件去重、文件内容或路径存储、MD5 计算、用户文件授权、引用计数事务或分布式锁。真实 `POST /api/v1/files` 上传不会读取该缓存，而是服务端计算 MD5 后直接查询 MySQL；全局物理去重最终由 `file_info` 的唯一索引 `uk_file_md5_size(file_md5, file_size)` 保证，并发唯一键冲突时会删除本请求多余落盘文件，再转为秒传。用户是否可引用文件由 MySQL `user_file_authorization` 判定，所以全局缓存命中不等于当前用户有权限。

边界方面，`evict(md5, size)` 已实现，但当前没有真实文件删除/恢复入口调用，主动失效尚未闭环，只能依赖 TTL；若上传数据库事务已成功但正缓存写入失败，同时旧负缓存仍存在，预检最多可能出现 5 分钟假阴性，但真实上传仍查 MySQL，不影响最终去重。MD5 缓存服务本身在 Redis 异常时会回源 MySQL，但请求前置 JWT 拦截器也使用 Redis 检查 Token 黑名单且未在此处降级，因此不能笼统表述为“Redis 整体不可用时上传仍正常”。当前预检允许任意非负 `fileSize`，随机 MD5/大小组合仍可能放大 MySQL 查询和 Redis 负缓存数量，短负缓存只能防同 Key 重复穿透。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileMd5CacheServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileAuthorizationServiceImpl.java`、`campus-resource-platform/src/main/resources/mapper/FileInfoMapper.xml`、`campus-resource-platform/src/main/resources/mapper/UserFileAuthorizationMapper.xml`、`sql/init.sql`
- 验证方式：`campus-resource-platform\\mvnw.cmd '-Dtest=FileServiceImplTest,FileMd5CacheServiceImplTest' test`，共 21 项测试通过；另结合生产代码、SQL 和两个只读 Subagent 交叉核查。未连接真实 Redis，未验证真实 TTL/NX 与 Redis/MySQL 并发。

## 2026-08-12 10:17:11 | 面试文档与当前代码一致性更新

### 问题 1

更新 `docs/interview` 目录下与当前项目代码冲突的文档描述。

### 答案 1

已依据当前 `dev` 分支生产代码校准全部 7 份面试文档。主要更正包括：文件 MD5 预检已采用 `FOUND`、`NOT_FOUND`、`ABSENT` 三态缓存，正缓存 TTL 为 6 小时、负缓存 TTL 为 5 分钟且使用 `SET NX`；真实上传不读取 Redis，最终去重仍由 MySQL 唯一键保证。公开资料详情缓存也已落地，使用 Redis String 保存公共 JSON 快照，TTL 为 30～35 分钟，并以每资料 Redisson 锁、持锁后二次检查和审核事务提交后失效来控制击穿与一致性；审核通过、拒绝、下架都会失效详情缓存，只有通过和下架联动排行榜。

同时更正了 Redis 认证边界：登录签发本身不查询 Redis，受保护接口的 Token 黑名单校验与退出登录才依赖 Redis；补全当前 Redis Key、Service 和测试规模，并把前端描述更新为已覆盖主要用户与管理流程，但健康检查和审核文件预览尚未接入页面。测试口径不再沿用旧的 124/124：本轮后端缓存相关专项 49 项全部通过；后端全量共执行 167 项，其中 158 项通过、9 项因本机 MySQL `localhost:3306` 未启动而发生连接错误；前端因依赖未安装，`vitest` 与 `vue-tsc` 命令不可用，未宣称本轮通过。

### 关联信息

- 相关文件：`docs/interview/00-document-generation-plan.md`、`docs/interview/01-project-background-and-value.md`、`docs/interview/02-modules-and-business-flows.md`、`docs/interview/03-technology-stack-review.md`、`docs/interview/04-interview-question-bank.md`、`docs/interview/05-limitations-and-improvement-roadmap.md`、`docs/interview/README.md`
- 验证方式：核对当前生产代码、SQL、前端路由/API 与测试源文件；运行 49 项后端缓存相关专项测试、后端全量测试、Markdown 相对链接和代码围栏检查，并完成只读交叉复核。
