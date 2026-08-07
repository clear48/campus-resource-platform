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
