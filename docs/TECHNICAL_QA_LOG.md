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

## 2026-08-12 10:35:00 | 项目全部事务使用场景与原因

### 问题 1

根据文件内容解释项目中所有使用到事务的场景（为什么使用事务）

### 答案 1

当前生产代码共有 11 个 `@Transactional` 方法（6 个实现类），另有收藏模块 3 个 `TransactionTemplate.execute` 调用点。声明式事务都配置 `rollbackFor = Exception.class`，未显式配置传播级别、隔离级别、`readOnly`、超时或独立事务，因此采用 Spring 默认 `REQUIRED` 和数据源默认隔离级别。

真正依赖多步原子性的场景包括：审核通过、拒绝和下架将 `resource` 状态流转与 `audit_record` 留痕放在同一事务，避免状态已变但无审计记录；收藏、取消收藏和唯一键冲突后的恢复事务把 `favorite` 关系状态与 `resource.favorite_count` 增减放在同一事务，避免关系与汇总计数不一致；首次文件上传授权把 `file_info` 与 `user_file_authorization` 同时提交，内容去重授权把 `ref_count` 自增与用户授权同时提交；下载增量落库把 `(batch_id, resource_id)` 幂等明细与 `download_count` 原子累加放在同一事务，并让一个批次任一资料失败时全部回滚；热度快照把同一批次多个 `hot_score` 更新作为一个提交单元。

事务边界外的副作用经过专门隔离：审核的详情缓存失效和排行榜更新注册为 `afterCommit`；收藏的 Redis Set 与排行榜热度在 `TransactionTemplate` 返回后更新；文件 MD5、落盘与删除不进入数据库事务，入库失败用删除文件补偿；下载增量的 Redis 批次隔离、HDEL 确认位于 MySQL 事务外，通过稳定批次 ID、唯一键与保留失败批次实现幂等重试；热度快照只事务化 MySQL 批次，不回滚 Redis 实时榜。这些都不是 MySQL 与 Redis/文件系统的分布式事务。

其余事务收益相对有限：注册目前只有一条用户 INSERT，主要统一异常回滚边界，账号唯一性实际靠数据库唯一索引；登录目前只有一条 `last_login_at` UPDATE，但若后续 JWT 签发失败，事务可撤销登录时间；资料创建当前只 INSERT 一条 `resource`，事务包住前置校验与写入，但无法解决“先查重复、后插入”的并发竞争，仍需数据库唯一约束才可彻底保证业务去重；下载增量确认目前是一条批量 UPDATE，单条 SQL 本身已原子，事务更多提供统一服务边界。Redis Lua、`SET NX`、数据库单条 `UPDATE count = count + delta` 和唯一索引属于各自存储内的原子性/并发保护，不能等同于 Spring 事务。

测试代码另有 `AuditServiceDatabaseIntegrationTest` 使用 `@Transactional(propagation = NOT_SUPPORTED)`，目的不是业务事务，而是关闭测试框架外围回滚事务，让被测 Service 的真实提交与回滚边界可被观察。当前审核集成测试明确验证审核记录插入失败时资料状态更新会回滚；下载增量也有数据库集成测试。收藏、文件授权、热度快照等仍主要依靠单元测试或 Mapper 测试，真实事务代理/并发覆盖程度不完全相同。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/service/impl/AuditServiceImpl.java`、`AuthServiceImpl.java`、`FavoriteServiceImpl.java`、`FileAuthorizationServiceImpl.java`、`FileServiceImpl.java`、`DownloadDeltaPersistenceServiceImpl.java`、`DownloadDeltaSyncServiceImpl.java`、`HotScoreSnapshotPersistenceServiceImpl.java`、`HotRankingMaintenanceServiceImpl.java`、`ResourceServiceImpl.java`，以及对应 Mapper XML、`sql/init.sql`
- 验证方式：扫描全部生产 Java/XML/SQL 中的声明式与程序式事务 API，逐项核对 Service 调用链、Mapper SQL、唯一约束、Redis/文件边界和事务测试；仅静态分析，未修改业务代码、未运行测试

## 2026-08-13 10:37:39 | 防止用户重复提交同一资料

### 问题 1

**如何防止用户重复提交同一资料**

### 答案 1

应把“重复”拆成物理文件重复和资料业务记录重复两层处理。当前项目的物理文件层已经在服务端计算文件 MD5，并以 `file_info(file_md5, file_size)` 唯一索引作为最终并发兜底；普通命中直接秒传，并发插入冲突时删除本请求的多余落盘文件、查询赢家记录后转为秒传。Redis 的 `crp:cache:file:md5:{fileMd5}:{fileSize}` 只加速预检，不承担最终正确性。若面向恶意碰撞风险较高的场景，应考虑改用 SHA-256；只看文件名、标题或客户端上报哈希都不可靠。

资料业务层当前把“同一用户、同一 `fileId`，且已有状态为待审核 `0` 或已通过 `1` 的资料”定义为重复。`ResourceServiceImpl.create` 在插入前调用 `countActiveByUploaderAndFileId` 查询，命中后抛出 `DATA_DUPLICATE`；已有数据库集成测试覆盖普通顺序重复提交。这能拦截常规重复请求，但 `resource` 表目前只有普通索引，没有对应唯一约束，两个并发请求仍可能同时查到 0 后各自插入；`@Transactional` 不能消除这种“先查后写”竞争。

彻底方案应保留 Service 前置查重以返回友好错误，同时在 MySQL 增加最终唯一兜底。由于业务允许资料被拒绝或下架后再次提交，不能直接对 `(uploader_id, file_id)` 建普通唯一索引，也不能对 `(uploader_id, file_id, status)` 建唯一索引（它仍允许同一文件同时存在一条待审核和一条已通过记录）。适合 MySQL 8 的方案是增加生成列，例如状态为 `0/1` 时生成固定值 `1`，其他状态生成 `NULL`，再建立 `(uploader_id, file_id, active_duplicate_guard)` 唯一索引；MySQL 唯一索引允许多条 `NULL`，从而只限制一条有效资料。插入时还应捕获该唯一键产生的 `DuplicateKeyException` 并统一转换为 `DATA_DUPLICATE`。上线前需先清理存量有效重复数据，再执行迁移，并补充真实数据库并发测试，验证多请求竞争时只有一条成功。

前端在请求期间禁用提交按钮、增加防抖，以及使用一次性请求号或幂等键，可以改善重复点击体验；Redis `SET NX` 或分布式锁也可削峰，但锁可能过期或不可用，不能代替数据库唯一约束。若产品允许同一个文件以不同标题或课程发布成多份资料，则应先调整“同一资料”的业务唯一维度，不能直接沿用当前 `(uploader_id, file_id)` 规则。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceServiceImpl.java`、`FileServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/mapper/ResourceMapper.java`、`campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml`、`sql/init.sql`、`campus-resource-platform/src/test/java/com/john/campus/service/ResourceDatabaseIntegrationTest.java`
- 验证方式：核对当前 `dev` 分支的资料创建、文件去重、Mapper SQL、数据库索引和重复提交集成测试；本轮仅静态分析并追加问答归档，未修改业务代码、未运行测试

## 2026-08-13 11:00:28 | 本机 MySQL 增加资料条件唯一约束

### 问题 1

访问本机MySQL增加条件唯一约束

### 答案 1

已访问本机 MySQL 8.0.45 的 `campus_resource_platform`，在变更前确认 `resource` 共 3 行，待审核/已通过的有效重复组为 0。随后先使用 `mysqldump --single-transaction` 备份 `resource` 表到系统临时目录，再执行可重复迁移 `sql/migrations/20260813_resource_active_duplicate_guard.sql`；迁移连续执行两次均成功，未修改现有 3 条资料的业务字段。

`resource` 新增 STORED 生成列 `active_duplicate_guard`：`status IN (0, 1)` 时生成 `1`，其他状态生成 `NULL`；新增唯一索引 `uk_resource_active_duplicate (uploader_id, file_id, active_duplicate_guard)`。该约束保证同一用户、同一物理文件最多存在一条待审核或已通过资料，同时利用唯一索引允许多个 `NULL` 的语义，使拒绝、下架、删除后的历史记录可以共存并允许重新提交。迁移会先检查存量有效重复组，存在冲突时用 `SIGNAL` 主动中止，不自动删除或修改业务数据。

应用层继续保留 `countActiveByUploaderAndFileId` 前置查重以返回友好提示，并在 `ResourceServiceImpl.create` 捕获并发插入产生的 `DuplicateKeyException`，统一转换为 `DATA_DUPLICATE`。初始化 SQL、H2 MySQL 模式测试结构、数据库设计/变更记录、资料模块状态和分支交接文档已同步更新。

本机事务探针验证：两条拒绝资料可使用相同用户/文件组合；插入第二条有效资料被唯一索引拒绝；回滚后探针残留为 0。资料数据库、Service 和 Controller 专项测试 27/27 通过，后端全量测试 169/169 通过，其中包括真实 MySQL 的审核事务集成测试。回滚数据库结构时应先执行 `ALTER TABLE resource DROP INDEX uk_resource_active_duplicate`，再删除 `active_duplicate_guard` 生成列；回滚会重新暴露并发重复提交风险。

### 关联信息

- 相关文件：`sql/init.sql`、`sql/migrations/20260813_resource_active_duplicate_guard.sql`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceServiceImpl.java`、`campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql`、`campus-resource-platform/src/test/java/com/john/campus/service/ResourceDatabaseIntegrationTest.java`、`campus-resource-platform/src/test/java/com/john/campus/service/ResourceServiceImplCacheTest.java`、`docs/database/database-design.md`、`docs/database/database-change-log.md`
- 验证方式：本机 MySQL 结构/数据预检、迁移连续执行两次、information_schema 结构核验、事务回滚探针、`.\\mvnw.cmd '-Dtest=ResourceDatabaseIntegrationTest,ResourceServiceImplCacheTest,ResourceControllerTest' test`（27/27）和 `.\\mvnw.cmd test`（169/169）

## 2026-08-13 11:13:25 | 项目关键索引的作用

### 问题 1

详细讲解项目中关键索引的作用。

### 答案 1

项目索引主要承担三类职责：主键索引负责按 ID 定位、批量回表和游标分页；普通联合索引负责按业务维度过滤并尽量同时满足排序；业务唯一索引不仅加速等值查询，更是并发条件下的数据正确性约束。InnoDB 的二级索引叶子节点包含主键，因此多个列表 SQL 的末尾 `ORDER BY id` 通常可由已有联合索引的隐含主键尾列维持稳定顺序，无需机械地把 `id` 再写入每个索引。

最关键的业务唯一索引包括：`user` 的用户名、邮箱和手机号唯一键封住并发注册；`uk_file_md5_size(file_md5, file_size)` 保证同一物理内容只入库一次，并让并发失败请求删除多余落盘文件后转为秒传；`uk_user_file_authorization(user_id, file_id)` 配合 `INSERT IGNORE` 保证用户文件授权幂等；`uk_resource_active_duplicate(uploader_id, file_id, active_duplicate_guard)` 利用生成列在待审核/已通过时取 `1`、其他状态取 `NULL`，只限制有效资料并允许拒绝、下架、删除后重新提交；`uk_favorite_user_resource(user_id, resource_id)` 保证重复收藏不会生成两条关系；`uk_download_delta_sync_batch_resource(batch_id, resource_id)` 是 Redis 下载增量重试的 MySQL 幂等栅栏，避免 MySQL 已提交而 Redis 确认失败后重复累计下载量。Service 的前置查询只负责友好提示，最终并发裁决仍由唯一索引完成。

明确服务当前查询的普通联合索引包括：`idx_category_parent_status(parent_id, status, sort_order)` 支撑启用分类列表；`idx_resource_status_created(status, created_at)` 支撑待审核列表和公开资料默认时间排序；`idx_resource_category_status(category_id, status, created_at)` 支撑分类下已通过资料；`idx_resource_uploader_status(uploader_id, status, created_at)` 支撑带状态的“我的上传”；`idx_resource_hot(status, hot_score, download_count)` 支撑 Redis 不可用时的 MySQL 总热榜；`idx_favorite_user_status_created(user_id, status, created_at)` 支撑收藏列表与缓存重建；`idx_download_user_created(user_id, created_at)` 支撑个人下载历史；`idx_audit_resource_created(resource_id, created_at)` 支撑资料审核轨迹。联合索引遵循最左前缀：例如分类索引不能替代仅按 `status` 的查询；“我的上传”不传 `status` 时虽然可用 `uploader_id` 前缀过滤，却无法越过未约束的中间列继续用 `created_at` 完成全状态排序，数据量大时可能出现 `filesort`。

当前还存在需用真实数据验证的性能边界：公开搜索的标题、简介、课程和标签使用前导 `%LIKE%` 或列函数，普通 B+Tree 基本不能完成有效定位，`idx_resource_course_status` 对现有课程模糊搜索帮助有限；下载量、收藏量排序没有完全匹配的联合索引；带分类的 MySQL 热榜也缺少同时覆盖分类过滤和热度排序的索引。部分下载、审核、反向授权和清理索引在当前生产 Mapper 中没有直接查询消费，属于未来报表或清理预留，会增加写放大，但不能只凭静态分析删除，应结合 `EXPLAIN ANALYZE`、慢日志和索引使用统计决策。项目使用逻辑外键，没有物理外键及其自动索引，索引也不负责验证关联数据存在。

### 关联信息

- 相关文件：`sql/init.sql`、`sql/migrations/20260813_resource_active_duplicate_guard.sql`、`campus-resource-platform/src/main/resources/mapper/*.xml`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/AuthServiceImpl.java`、`FileServiceImpl.java`、`ResourceServiceImpl.java`、`FavoriteServiceImpl.java`、`DownloadDeltaPersistenceServiceImpl.java`、`docs/database/database-design.md`
- 验证方式：静态核对初始化 SQL、3 个迁移脚本、9 个生产 Mapper XML、关键 Service 调用链和数据库设计文档；未连接数据库执行真实 `EXPLAIN ANALYZE`，未修改业务代码、未运行测试

## 2026-08-21 11:31:48 | 项目技术栈清单

### 问题 1

详细列出项目中所使用的技术栈

### 答案 1

项目采用前后端分离架构。后端运行基线为 Java 17，直接使用 Record、文本块等语言特性；应用框架为 Spring Boot 3.5.16，Web 层由 Spring MVC 提供 REST Controller、参数绑定、Multipart 文件上传、CORS、拦截器和统一 JSON 响应，默认使用 Starter 传递引入的 Jackson 与嵌入式 Tomcat。参数校验使用 Jakarta Validation，事务同时使用 `@Transactional` 和 `TransactionTemplate`，定时任务使用 Spring Scheduling。密码只引入 `spring-security-crypto` 的 BCrypt 能力，没有配置完整的 Spring Security Filter Chain；JWT 使用 JJWT 0.12.6，以 HS256 签发、验签和解析 Claims；Lombok仅在编译期辅助 DTO/Entity，日志采用 SLF4J 加 Spring Boot 默认日志实现。

数据访问使用原生 MyBatis，而不是 MyBatis-Plus；`mybatis-spring-boot-starter` 版本为 3.0.5，采用 Mapper 接口、XML SQL、动态 SQL、结果映射和下划线转驼峰配置。权威数据存储是 MySQL 8.x/InnoDB/utf8mb4，初始化结构使用唯一索引、联合索引、CHECK 约束、原子更新和 STORED 生成列，并通过 Spring 事务处理跨表一致性。项目未创建物理外键，关联完整性主要由 Service 和逻辑约束维护。

缓存与实时数据层使用 Redis 6+。Java 客户端主入口是 Spring Data Redis 的 `StringRedisTemplate`，实际使用 String、Set、Hash、ZSet、TTL 和 Lua：覆盖 JWT 黑名单、文件 MD5 三态缓存、公开资料详情 Cache Aside、收藏缓存、下载滑动窗口限流、一次性下载票据、下载去重、热门资料/热词排行榜以及下载量增量。Redisson 4.6.1 提供 `RLock`、`RReadWriteLock` 和看门狗续期，用于下载增量同步、排行榜维护及详情缓存回填/失效互斥。MySQL 是多数业务事实的最终权威源；Redis 根据数据风险分别采用失败关闭、回源 MySQL、跳过统计、重建或幂等重试。

文件能力使用 Spring `MultipartFile`、Java 文件与摘要 API，以及服务器本地文件系统；服务端计算 MD5 并以 MySQL `(file_md5, file_size)` 唯一索引完成最终物理去重。当前不是 MinIO、OSS 或其他对象存储，也没有消息队列。文件 IO 位于数据库事务外，失败通过删除多余文件等补偿处理。

前端是 Vue 3 单页应用：依赖清单声明 Vue `^3.5.39`，锁文件实际锁定 3.5.39；使用 Composition API、`<script setup lang="ts">` 和单文件组件。TypeScript 声明 `~6.0.2`、锁定 6.0.3；构建工具 Vite 声明 `^8.1.1`、锁定 8.1.4，配合 `@vitejs/plugin-vue` 6.0.7 和 `vue-tsc` 3.3.7。路由使用 Vue Router 5.1.0 的 History 模式、路由元数据守卫和管理员页面动态导入；UI 使用 Element Plus 2.14.3 与项目原生 CSS；HTTP 使用 Axios 1.18.1，统一配置 `/api/v1`、10 秒超时、Bearer Token 请求拦截和业务错误转换；浏览器会话以 Vue `ref/computed/readonly` 加 `localStorage` 自行管理，没有引入 Pinia/Vuex。前端文件预检使用 SparkMD5 3.0.2 按 2 MiB 分片计算 MD5。Vite 开发服务器通过环境变量配置 `/api` 代理到后端。

后端测试由 `spring-boot-starter-test`、JUnit Jupiter、Mockito、Spring MockMvc、MyBatis Test 3.0.5 和测试域 H2 组成，部分数据库集成测试也可连接本机 MySQL；项目没有引入 Testcontainers。前端测试使用 Vitest 4.1.10、Vue Test Utils 2.4.11、happy-dom 20.10.6 和 axios-mock-adapter 2.1.0。后端由 Maven Wrapper 构建，Wrapper 3.3.4 固定下载 Maven 3.9.16；前端由 npm 与 lockfile v3 管理，脚本包括 Vite 开发/预览、`vue-tsc` 加 Vite 构建和 Vitest 单元测试。当前依赖清单没有 ESLint、Prettier、Sass/Tailwind、Docker/Compose、CI 工作流、Elasticsearch、消息队列、MyBatis-Plus、完整 Spring Security、MinIO/OSS 等组件，不能将它们描述为已使用技术栈。

运行形态为 Spring Boot 单体后端、Vue SPA、MySQL、Redis 和本地上传目录，配置通过 `application.yaml` 与环境变量注入；仓库未提供容器化部署清单。Git 用于版本控制，默认在 `dev` 分支持续开发。此清单以当前 `pom.xml`、`package.json`、`package-lock.json`、应用配置、生产代码、测试代码和 SQL 为准；文档中旧测试数量或笼统写作“MyBatis/MyBatis-Plus”的描述不作为当前依赖事实。

### 关联信息

- 相关文件：`campus-resource-platform/pom.xml`、`campus-resource-platform/.mvn/wrapper/maven-wrapper.properties`、`campus-resource-platform/src/main/resources/application.yaml`、`campus-resource-platform/src/main/java/com/john/campus/**`、`campus-resource-platform/src/test/java/com/john/campus/**`、`frontend/package.json`、`frontend/package-lock.json`、`frontend/vite.config.ts`、`frontend/tsconfig*.json`、`frontend/src/**`、`sql/init.sql`
- 验证方式：静态核对构建清单、锁文件精确版本、配置键、127 个后端生产 Java 文件、31 个后端测试 Java 文件、80 个前端 TypeScript/Vue 文件与初始化 SQL；未修改业务代码，未运行单元测试或启动外部 MySQL/Redis

## 2026-08-21 11:45:31 | Java 实习简历项目描述

### 问题 1

我现在要在Java实习简历上写上该项目，从项目简介，技术栈，主要工作（不要面面俱到，突出重难点）三个角度给出能写在简历上的描述

### 答案 1

简历项目可命名为“校园资料共享与智能检索平台”。项目简介应准确写成：面向高校用户的前后端分离资料共享平台，围绕文件上传、资料审核、多条件检索、收藏、下载与热门排行构建完整业务闭环，重点解决重复文件存储、热点数据访问、下载防刷以及 Redis 与 MySQL 统计一致性问题。当前“智能检索”指 MySQL 多条件检索、排序和热词/热门资料推荐，不应表述为 Elasticsearch、向量检索或大模型语义检索。

简历技术栈可写为：Java 17、Spring Boot 3.5、Spring MVC、原生 MyBatis、MySQL 8、Redis、Spring Data Redis、Redisson、JWT、BCrypt、Validation、Maven、JUnit 5/Mockito；前端如版面允许可补充 Vue 3、TypeScript、Vite、Element Plus。项目没有使用 MyBatis-Plus、完整 Spring Security、Elasticsearch、消息队列、MinIO/OSS、Docker 或微服务架构，不能写成已使用。

主要工作建议只保留四项重难点：第一，设计 `MD5 + fileSize` 文件去重与用户级文件引用授权，利用 Redis 正值/负值/未命中三态缓存加速预检，并以 MySQL 唯一索引处理并发上传；唯一键冲突时删除多余落盘文件并转为秒传，其他入库异常执行文件删除补偿。第二，采用 Cache Aside 实现公开资料详情缓存，使用 30～35 分钟随机 TTL、Redisson 每资料锁和双重检查收口热点回源；审核事务提交后使用同一把锁失效缓存，减少缓存击穿和旧快照回填。第三，使用 Redis ZSet 与 Lua 实现用户/IP 双维度滑动窗口限流，并通过 60 秒一次性下载票据、取流前资料状态与归属复核降低高频下载、票据重放和已下架资料继续下载的风险。第四，将下载量先聚合到 Redis Hash，再由定时任务通过 Redisson 看门狗锁、Lua 原子批次隔离、UUID 批次和 MySQL 唯一幂等明细批量落库；数据库事务提交后才确认 Redis 字段，使 Redis 确认失败时可以重试且不重复累计。

简历不应宣称“强一致分布式事务”“高并发/百万级数据”“性能提升百分比”或“分布式系统”，因为项目当前是 Spring Boot 单体应用，未完成生产规模压测；更准确的表述是针对并发、幂等、缓存一致性和故障降级完成了工程化设计。

### 关联信息

- 相关文件：`docs/interview/01-project-background-and-value.md`、`docs/interview/03-technology-stack-review.md`、`docs/interview/05-limitations-and-improvement-roadmap.md`、`campus-resource-platform/pom.xml`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`FileMd5CacheServiceImpl.java`、`ResourceDetailCacheServiceImpl.java`、`DownloadRateLimiterImpl.java`、`DownloadServiceImpl.java`、`DownloadDeltaSyncServiceImpl.java`、`DownloadDeltaPersistenceServiceImpl.java`、`sql/init.sql`
- 验证方式：静态核对当前 `dev` 分支的依赖、关键 Service 实现、Redis Lua/Redisson 使用、MySQL 唯一约束和项目面试边界文档；本轮未修改业务代码、未运行测试

## 2026-09-06 15:13:07 | 下载增量同步流程与 Redis Key

### 问题 1

举例说明下载模块中的下载增量同步的流程（重点说明涉及的redis key）

### 答案 1

下载增量同步由“下载模块生产增量”和“排行榜定时任务消费增量”两部分组成。假设用户 `10001` 首次下载资料 `20001`，`DownloadServiceImpl.tryCountDownload` 先用 `SET NX EX 600` 写入 String Key `crp:dedup:download:10001:20001=1`；只有首次写入成功才执行 `HINCRBY crp:stats:resource:download:delta 20001 1`。其中去重 Key 的 TTL 为 10 分钟，控制同一用户对同一资料的重复计数；`delta` 是不设 TTL 的 Hash，field 为 `resourceId`，value 为尚未同步到 MySQL 的正整数增量。计数发生在下载记录创建且 60 秒一次性下载票据签发成功后，并非文件流完整传输之后。

`RankingSyncTask` 默认在上轮结束 60 秒后触发。同步服务先竞争 Redisson 锁 `crp:lock:sync:download-delta`，不传固定 leaseTime，由看门狗续期，配置超时为 30 秒；多实例中只有一个实例继续。若 `crp:stats:resource:download:syncing:current` 没有指向未完成批次，服务生成 UUID，例如 `batch-uuid`，再用同一段 Lua 原子执行 `RENAME crp:stats:resource:download:delta crp:stats:resource:download:syncing:batch-uuid` 和 `SET crp:stats:resource:download:syncing:current batch-uuid`。`syncing:{batchId}` 是不设 TTL 的 Hash，内容仍为 `resourceId -> delta`；`syncing:current` 是不设 TTL 的 String 指针。批次隔离完成后，新下载会重新创建并写入新的 `delta` Hash，因此确认旧批次时不会误删并发产生的新增量。

同步服务每轮从 `syncing:{batchId}` 选取最多 500 个合法 field，在一个 MySQL 事务中逐项先 `INSERT IGNORE download_delta_sync_item(batch_id, resource_id, delta)`，仅首次插入成功时才执行 `UPDATE resource SET download_count = download_count + delta WHERE id = resourceId`。唯一键 `(batch_id, resource_id)` 是幂等栅栏。事务提交后才 `HDEL syncing:{batchId} resourceId`，随后更新幂等明细的 `confirmed_at`；批次 Hash 清空后，Redis 会自动删除空 Hash，代码再用“current 的值仍等于该 batchId”这一 Lua CAS 条件删除 `syncing:current`，最后释放锁。

若 MySQL 失败，事务整体回滚，`syncing:{batchId}` 与 `syncing:current` 保留，下一轮优先恢复相同 UUID。若 MySQL 已提交但 Redis `HDEL` 失败，下一轮相同 `(batchId, resourceId)` 命中唯一键并校验 delta 后跳过再次累加，只重试 Redis 确认，从而避免重复计数。旧版本 Key `crp:stats:resource:download:syncing:active` 没有对应幂等记录，当前代码只告警并停止自动处理，必须人工核对或排空。下载限流 Key、一次性票据 Key和 `crp:rank:resource:hot:{period}` 热榜 ZSet 虽参与完整下载链路，但不属于下载量 Redis→MySQL 的同步协议；热榜只是增量计数成功后的独立旁路更新。

当前边界是去重 `SETNX` 与增量 `HINCRBY` 并非同一 Lua 原子操作：若去重写入成功而增量写入失败，下载仍可继续，但该用户在 10 分钟去重期内会少计一次。另有模块文档不一致：`docs/modules/07-download-development-process.md` 的部分旧章节仍称定时同步未实现，实际应以生产代码、`docs/05-redis-design.md` 和 `docs/modules/09-rank-development-process.md` 为准。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/task/RankingSyncTask.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaSyncServiceImpl.java`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaPersistenceServiceImpl.java`、`campus-resource-platform/src/main/resources/mapper/DownloadDeltaSyncItemMapper.xml`、`campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml`、`campus-resource-platform/src/main/resources/application.yaml`、`docs/05-redis-design.md`
- 验证方式：静态核对 Redis Key、下载计数、Lua 批次隔离、定时任务、MySQL 事务和 Mapper SQL；`.\\mvnw.cmd "-Dtest=DownloadDeltaSyncServiceImplTest,DownloadDeltaPersistenceServiceImplTest,DownloadDeltaPersistenceDatabaseIntegrationTest,RankingSyncTaskTest" test`，14/14 通过

## 2026-09-06 15:25:51 | 简历模块取舍建议

### 问题 1

我想排除一些简历中的模块，有什么推荐

### 答案 1

针对 Java 实习简历，建议只从简历表述中排除低区分度模块，不删除项目代码。注册登录、个人信息、分类管理、健康检查、统一响应、分页列表等属于基础能力，可不写入“主要工作”；JWT 鉴权只需放在技术栈或项目简介中，不必单独占一条。收藏、我的上传、我的下载记录等功能偏常规 CRUD，也建议删除；Vue 前端只用于演示后端闭环，投递 Java 后端岗位时可仅在技术栈末尾一笔带过。当前搜索基于 MySQL 多条件模糊查询，不包含 Elasticsearch 或语义检索，因此只在项目简介中写“多条件检索”，不建议把搜索单列为技术亮点。

最推荐保留三条主线：一是文件 `MD5 + size` 去重、秒传、用户文件授权、数据库唯一索引和落盘失败补偿；二是审核状态机、带旧状态条件的并发更新、审核流水事务，以及事务提交后的详情缓存失效；三是下载安全与统计，包括 Redis ZSet + Lua 双维度限流、一次性票据、Redis Hash 下载增量、Redisson 锁、UUID 批次和 MySQL 幂等落库。如果简历允许第四条，可补充 Cache Aside 详情缓存；排行榜可作为面试展开或用于替换缓存条目，但不是必须保留。

压缩后的最佳结构是：项目简介保留“上传—审核—检索—下载”的业务闭环；技术栈保留 Java 17、Spring Boot、MyBatis、MySQL、Redis、Redisson 和 JWT；主要工作只写文件去重、审核与缓存一致性、下载安全与增量同步三项。这样既覆盖业务完整性，又把篇幅集中在并发、事务、幂等和 Redis/MySQL 一致性等 Java 后端重难点上。

### 关联信息

- 相关文件：`docs/interview/01-project-background-and-value.md`、`docs/CURRENT_STATUS.md`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`AuditServiceImpl.java`、`ResourceDetailCacheServiceImpl.java`、`DownloadRateLimiterImpl.java`、`DownloadServiceImpl.java`、`DownloadDeltaSyncServiceImpl.java`
- 验证方式：核对当前 `dev` 分支项目状态与既有面试总结；本轮仅进行简历内容取舍分析并追加问答归档，未修改业务代码、未运行测试

## 2026-09-06 15:26:45 | 下载增量同步面试口述总结

### 问题 1

精简总结流程用于面试时回答

### 答案 1

上下文：精简前文已核验的下载增量同步流程，重点保留 Redis Key 与幂等机制。

为了减少 MySQL 高频更新下载量的压力，我先用 `crp:dedup:download:{userId}:{resourceId}` 做 10 分钟计数去重，再通过 `HINCRBY` 把有效下载聚合到 `crp:stats:resource:download:delta`。定时任务获取 `crp:lock:sync:download-delta` 分布式锁，用 Lua 把 delta 原子重命名为 `crp:stats:resource:download:syncing:{UUID}`，同时设置 `crp:stats:resource:download:syncing:current` 指针；新下载继续写新的 delta，避免清理旧数据时误删新增量。随后在 MySQL 同一事务中写入批次幂等明细并累加下载量，提交后才删除 Redis 批次对应字段，批次清空后清理 current。失败时恢复同一批次重试，依靠 `(batch_id, resource_id)` 唯一键避免重复累加。

### 关联信息

- 验证方式：复用本轮对话前文的代码核验及下载增量专项测试 14/14 通过结果；本次仅精简表述并追加归档，未重新运行测试、未修改业务代码、未创建提交或推送。

## 2026-09-06 15:28:08 | 热度总榜的简历取舍

### 问题 1

热度总榜模块过于复杂，是否建议删除

### 答案 1

如果“删除”指从简历中移除，建议删除热度总榜的独立描述，但保留项目代码。总榜涉及 Redis ZSet、热度分数、临时 Key 重建、`RENAME` 原子替换、Redisson 读写锁、MySQL 热度快照和 Redis 故障降级，面试追问链较长；当前周期榜也不是按自然日、周、月精确切桶。对于 Java 实习简历，它与下载限流、Redis 下载增量同步展示的技术能力存在重叠，投入较多篇幅却不一定增加明显区分度。

建议从“主要工作”中删除“多周期热门排行、总榜原子重建、热度快照回写、读写锁协调”等内容。项目简介中的“热门排行”可以一并删除，或只保留一句“基于 Redis ZSet 实现资料热度排序”，不主动展开复杂维护流程。只有能够清楚回答权威数据源、实时分数与 MySQL 快照关系、Redis 丢失后的重建、重建与实时写并发、周期 Key 边界等问题时，才值得作为第四条亮点保留。

删除后，简历主要工作集中为三项：文件去重与秒传；审核状态机与详情缓存一致性；下载限流、一次性票据及 Redis 下载增量幂等落库。这三条已经充分覆盖唯一索引、事务、并发控制、Lua 原子性、分布式锁、幂等和 Redis/MySQL 一致性，更适合有限篇幅的 Java 实习简历。

### 关联信息

- 相关文件：`docs/interview/01-project-background-and-value.md`、`docs/interview/03-technology-stack-review.md`、`campus-resource-platform/src/main/java/com/john/campus/service/impl/RankingServiceImpl.java`、`HotRankingMaintenanceServiceImpl.java`、`DownloadDeltaSyncServiceImpl.java`
- 验证方式：结合已核验的排行榜维护边界与简历模块取舍进行分析；本轮未修改业务代码、未运行测试

## 2026-09-07 09:26:10 | 下载增量同步设计原因

### 问题 1

为什么要这样设计

### 答案 1

上下文：解释前文下载去重、Redis Hash 聚合、UUID 批次隔离、current 指针、Redisson 锁及 MySQL 幂等事务的设计原因。

核心目标是减少数据库高频写入，同时处理同步期间的新下载和故障重试。下载量允许短暂延迟，因此采用最终一致的异步同步。去重 Key 防止同一用户短时间反复下载放大统计；Hash 用 resourceId 作 field，通过 HINCRBY 原子聚合增量，例如同一资料某轮内 100 次有效下载可聚合成一次下载量 UPDATE，减少数据库写入与热点行竞争。

批次隔离解决同步读数与新增量写入之间的竞争：若直接从 delta 读取 15，落库期间又来一次下载变成 16，随后 HDEL 会连新来的 1 一起删除。把旧 Hash 原子 RENAME 为 syncing:{UUID} 后，新下载继续写新 delta，确认旧批次不会误删新数据。current 指针让任务重启后能定位未完成 UUID 批次并保持批次身份；RENAME 与 SET current 使用同一 Lua，避免两步之间宕机导致批次失去指针。

Redisson 锁减少多实例重复处理，看门狗为长任务续期；但锁不能判断 MySQL 是否已经提交，所以还要用 (batch_id, resource_id) 唯一键兜底。MySQL 在同一事务里插入幂等明细和原子累加下载量，使二者一起提交或回滚；否则先写标记后累加失败可能少计，先累加后写标记失败可能重复计数。只有数据库提交成功后才 HDEL，防止先删 Redis 后数据库失败失去可重试数据；若数据库提交后、Redis 确认前宕机，下次用同一 UUID 命中幂等记录并跳过重复累加。delta、syncing 与 current 不设置 TTL，避免任务延迟时待同步数据自行过期，但这不等于能抵御 Redis 数据本身丢失。

面试可表述为：下载量允许短暂延迟，所以先用 Redis 聚合减少 MySQL 写入；批次隔离保护并发新增量，current 支持故障恢复，分布式锁协调多实例，数据库事务与唯一键保证同一批次重试不重复累加，提交后再清理 Redis。这是带幂等重试的最终一致方案，不是 Redis 与 MySQL 的强一致分布式事务。

### 关联信息

- 验证方式：复用前文已核验的 DownloadServiceImpl、DownloadDeltaSyncServiceImpl、DownloadDeltaPersistenceServiceImpl 与 Mapper SQL；此前下载增量专项测试 14/14 通过，本轮仅解释设计与追加归档，未重新运行测试、未修改业务代码、未提交或推送。

## 2026-09-07 | 文件传输与文件流走向

### 问题 1

项目中的文件传输是如何实现的（重点是文件流的走向）

### 答案 1

当前采用浏览器经 Spring Boot 后端读写本地磁盘的方式，尚未接入 MinIO/OSS。文件正文存于后端可访问的磁盘；MySQL 保存 file_info 的文件路径、MD5、大小等元数据及 resource 的 fileId 引用，Redis 用于去重缓存、票据、限流和统计，不保存或中转文件正文。默认落盘目录为 app.upload.storage-path 配置的 ./data/user-uploads，相对于后端进程工作目录解析，文件名使用 UUID + 扩展名。开发环境使用默认 API 地址时还会经过 Vite /api 代理。

上传先由 UploadView 调用 calculateFileMd5，浏览器按 2 MiB 分片读取本地 File 并计算 MD5；分片只用于本地计算，并非分片上传。随后 GET /api/v1/files/check 只发送 MD5 和文件大小。只有命中已有文件并且当前用户已获授权，才返回可复用 fileId，浏览器跳过文件正文上传。全局存在但用户没有授权时仍需完整上传，由后端根据实际内容建立授权。

普通上传路径为：用户本地文件 → 浏览器 File/FormData → POST /api/v1/files 的 multipart/form-data 请求体 → Spring MVC 解析为 MultipartFile → FileServiceImpl → FileStorageServiceImpl → 后端磁盘。MultipartFile 是服务端对已接收文件的访问封装，底层暂存由 multipart 容器处理，不能理解成必然把整份文件装进一个 Java byte[]。后端首先通过 file.getInputStream() 和 DigestUtils.md5DigestAsHex 读取内容计算 MD5，再按 MD5 + size 查 MySQL；未命中时重新获取独立 InputStream，通过 Files.copy(inputStream, target, REPLACE_EXISTING) 写入最终磁盘文件。两个输入流均使用 try-with-resources 关闭。这是对后端已接收内容的两次读取，不是两次网络上传。若上传后发现重复，可跳过最终落盘，但网络上传已发生；并发落盘后入库冲突会删除多余文件。落盘成功后保存文件元数据及用户授权，返回包含 fileId 的 JSON；后续创建资料只提交 fileId、标题等 JSON，不再传文件内容。文件 IO 位于数据库写事务之外，入库异常有删除补偿。

下载分两次请求：前端先 POST /api/v1/download-records 创建记录，后端执行权限、状态和限流等业务校验并返回 downloadRecordId 与 60 秒一次性 downloadTicket，此请求不传文件正文。随后 GET /api/v1/download-records/{downloadRecordId}/file，携带 JWT 和 X-Download-Ticket。DownloadServiceImpl 消费票据、校验记录归属、重新确认资料已审核通过和文件有效，按 file_info.storage_path 调用 FileStorageServiceImpl.loadAsResource。该方法用 Files.newInputStream(path) 打开本地文件输入流；DownloadController 将同一输入流包装成 InputStreamResource，通过 ResponseEntity 返回，并设置 Content-Type、Content-Length 和 Content-Disposition。Spring MVC 将输入流内容写入 HTTP 响应体，正常响应写出路径由框架消费和关闭流。

下载字节走向为：后端磁盘文件 → Java InputStream → Spring MVC 的 HTTP 响应输出 → 网络 → 浏览器 Blob → 本地保存。前端 Axios 使用 responseType: 'blob'，等待响应接收完成后，通过 URL.createObjectURL(blob) 创建浏览器内部地址，再用 a.download 和 click 触发保存，最后释放对象 URL；对象 URL 不会再次请求后端。后端按流读取输出，但前端并未实现收到一个分块就直接写入用户目标文件的应用逻辑。InputStream/OutputStream 的方向以 Java 程序为参照，因此下载也是先用 InputStream 从磁盘读，再由 HTTP 响应输出给客户端。

下载成功正文是二进制，不走统一 JSON 包装，也没有返回对象存储直链。创建下载记录和计数发生在第一步，不能据此认定第二步文件已经完整到达浏览器。另有管理员审核文件接口，复用相同的本地磁盘读取链路，但权限入口不同，当前前端未接入该 review-file 接口。

### 关联信息

- 相关文件：frontend/src/views/UploadView.vue、frontend/src/utils/file-md5.ts、frontend/src/api/files.ts、frontend/src/api/downloads.ts、frontend/src/utils/file-download.ts、frontend/src/views/ResourceDetailView.vue、campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java、FileStorageServiceImpl.java、DownloadServiceImpl.java、campus-resource-platform/src/main/java/com/john/campus/controller/DownloadController.java、AuditController.java、campus-resource-platform/src/main/resources/application.yaml。
- 验证方式：主 Agent 与 architect 分工静态核对上传和下载实际调用链；未修改业务代码，未运行应用测试。归档仅追加并保留原有未提交问答；本轮未创建 commit 或推送，当前分支 dev。

## 2026-09-08 | 本机前端全功能测试流程与后端逻辑映射

### 问题 1

我想在本机电脑启动当前项目，在前端界面实际测试项目的功能，给出测试流程确保覆盖所有功能并说明用户当前行为对应后端模块的哪些逻辑。

### 答案 1

本机已按 MySQL、Redis、Spring Boot、Vite 顺序启动，后端地址为 `http://127.0.0.1:8080`，前端地址为 `http://127.0.0.1:5173`。现有数据库缺少当前版本下载增量同步所需的 `download_delta_sync_item`，已执行仓库内可重复迁移 `sql/migrations/20260714_download_delta_sync_idempotency.sql`；未修改生产代码。已创建脱敏的本地普通用户和管理员测试数据，并用三个独立文件真实跑通通过、拒绝、通过后下架三条状态链。

建议浏览器按“游客首页与搜索 -> 注册和登录 -> 首次上传及再次选择同文件验证秒传 -> 创建待审核资料 -> 我的上传 -> 管理员通过/拒绝 -> 公开详情、收藏、两步下载 -> 我的收藏与下载 -> 管理员下架、审核流水、总榜重建 -> 普通用户越权与退出 Token 失效”执行。用户注册、登录和退出分别进入 `AuthController -> AuthServiceImpl`，涉及 BCrypt、JWT、`user` 表及 Token 黑名单；选择文件进入前端 MD5 计算，再调用 `FileController -> FileServiceImpl` 完成授权感知的预检、MD5/大小去重、本地文件存储、`file_info` 与 `user_file_authorization`；提交资料进入 `ResourceController -> ResourceServiceImpl`，校验文件授权、启用分类和有效资料唯一性并写入 `resource` 待审核记录。

管理员审核进入 `AuditController -> AuditServiceImpl`，Service 校验管理员角色，并在事务中同时更新 `resource` 状态和写入 `audit_record`，支持 `PENDING_REVIEW -> APPROVED/REJECTED` 与 `APPROVED -> OFFLINE`；状态提交后同步调整排行榜并失效公开详情缓存。公开搜索进入 `SearchController -> SearchServiceImpl`，MySQL 只查询 `APPROVED` 资料并对白名单字段排序，非空关键词写入 Redis 热词榜。收藏进入 `FavoriteController -> FavoriteServiceImpl`，MySQL 唯一键与事务维护收藏关系及计数，Redis Set 加速状态查询并联动热度。下载进入 `DownloadController -> DownloadServiceImpl`，第一步用 Redis ZSet Lua 做用户/IP 滑动窗口限流、创建下载记录并执行 10 分钟计数去重，返回 60 秒一次性票据；第二步消费票据、复核归属和公开状态，再以文件流返回正文。下载增量先聚合到 Redis Hash，再由定时任务按 UUID 批次、分布式锁和 MySQL 幂等明细同步。排行榜查询进入 `RankingController -> RankingServiceImpl`，优先读 Redis ZSet，必要时使用 MySQL 热度快照兜底；管理员重建进入 `AdminRankingController -> AdminRankingServiceImpl -> HotRankingMaintenanceServiceImpl`。

本轮真实 HTTP 验证结果：普通用户访问管理接口为 403；三条新资料初始状态均为 0；当前用户再次预检同一文件命中秒传；待审核资料不能公开详情且搜索结果为 0；审核通过后详情状态为 1且搜索命中；拒绝资料有一条审核记录；通过后下架资料有两条审核记录且退出公开详情；收藏状态和收藏列表一致；首次下载 `counted=true`、下载文件 MD5 与源文件一致、票据重放失败；退出后旧 Token 请求当前用户为 401；总榜重建成功。后端 `mvnw.cmd test` 为 169/169 通过，前端 `npm run test:unit -- --reporter=verbose --maxWorkers=1 --fileParallelism=false` 为 66/66 通过，前端 `npm run build` 通过并保留现有主包体积警告。

当前界面有三个需要在手工流程中绕开的限制：首页和搜索结果标题没有详情链接，需从上传结果、管理员列表或 Network 响应取得资料 ID 后手动打开 `/resources/{resourceId}`；登录页忽略 `redirect` 参数并总是回首页，登录后需重新打开原页面；管理员前端未接入 `GET /api/v1/admin/resources/{resourceId}/review-file`，拒绝或下架后也没有全状态资料列表，因此审核文件和行移出后的流水要用接口补测。搜索建议、用户管理、评论、在线预览等后端本来未实现的能力不属于当前功能覆盖范围。

### 关联信息

- 运行手册：`docs/07-project-runbook.md`；页面与接口：`docs/frontend/02-page-design.md`、`docs/api/api-reference.md`。
- 测试数据和下载文件位于被 Git 忽略的 `campus-resource-platform/data/manual-test`；账号密码未写入日志或仓库。
- 当前分支为 `dev`；仅保留原有问答日志修改并追加本记录，没有提交或推送。

## 2026-09-08 | HomeWork 批量测试数据脚本设计

### 问题 1

我想编写一个脚本为项目写入大量测试数据，可选文件来源：C:\Users\Lenovo\Desktop\HomeWork目录下所有文件，先提供思路，不要做任何代码改动。

### 答案 1

建议后续使用 PowerShell 7 编写一个“预演、写入、校验”三模式的 API 驱动脚本，默认通过注册、登录、文件预检/上传、资料创建、管理员审核、收藏、搜索和少量下载等现有 HTTP 接口写入，确保文件授权、审核状态机、事务、Redis 缓存/排行和下载限流都走真实业务逻辑。不要用纯 SQL 直接插入 `file_info`、`resource`、`audit_record`、`favorite` 和 `download_record`，否则容易制造不存在的物理文件、缺失 `user_file_authorization`、计数不一致、无审核流水及 Redis 状态缺失。

只读扫描 `C:\Users\Lenovo\Desktop\HomeWork` 得到约 90,716 个文件、5.24 GB，其中大量是项目依赖和构建内容。按后端白名单 `pdf/doc/docx/ppt/pptx/xls/xlsx/zip/rar/7z/txt/md/jpg/jpeg/png`、非空且不超过 50 MB 筛选后有 6,007 个、约 2.48 GB；再排除 `node_modules`、`.git`、虚拟环境、`site-packages`、`dist/build/target/coverage`、IDE 目录等常见非业务目录后有 2,129 个、约 2.46 GB。默认应采用后者，并把依赖目录、压缩包和全量候选设为显式开关。脚本必须先输出扩展名、数量、容量、重复内容、跳过原因和预计耗时的 dry-run 清单，确认磁盘空间与目标环境后才进入写入模式。

建议把大量数据导入到独立的本地测试库、独立 Redis database、独立上传目录和单独后端端口，便于整体重建与清理。脚本用固定 `runId`、测试用户名和标题前缀标识批次，同时在 Git 忽略目录保存不含密码/Token 的 manifest，记录相对路径、MD5、大小、用户、fileId、resourceId、目标状态和错误，使中断后可以断点续跑。账号密码和管理员凭据只从环境变量读取，Token 只驻留内存。

处理流程为：预检环境和容量；扫描并安全规范化相对路径；按 MD5+大小建立本地重复组；准备固定数量普通用户并登录；为每个文件按当前用户先调用 `/files/check`，有授权则复用 fileId，否则实际上传以建立授权；按文件名、目录和扩展名生成受长度约束的标题、课程、类型和标签；创建待审核资料；按可配置比例保留待审核、审核通过、拒绝以及先通过再下架；随后为少量已通过资料生成收藏、搜索关键词和下载行为；最后用分页接口、数据库只读统计和 Redis 关键结果校验数量。默认状态比例可设为通过 70%、待审核 15%、拒绝 10%、下架 5%。

并发建议从 2 到 4 个上传请求开始，遇到网络错误或 5xx 才指数退避，业务 4xx 直接记录并跳过；401 重新登录。下载数据不能高速并发，因为真实代码限制同一用户每 60 秒 10 次、同一 IP 每 60 秒 30 次，批量脚本应少量抽样并主动节流，不能为了造数绕过限流。当前 `resource` 条件唯一索引还规定同一用户、同一文件最多存在一条待审核或已通过资料，因此重复运行必须依赖 manifest/resume 或使用新的 runId 与用户池，不能盲目重插。

### 关联信息

- 已核对 `FileServiceImpl`、`ResourceServiceImpl`、`AuditServiceImpl`、`FavoriteServiceImpl`、`DownloadServiceImpl`、`DownloadRateLimiterImpl`、`sql/init.sql` 与对应模块文档。
- 当前仅进行了目录统计与代码、文档只读分析；没有修改生产代码、数据库或 Redis。按项目归档规则只追加本问答记录，未提交、未推送。

## 2026-09-08 | 测试数据库与真实数据库切换

### 问题 1

如何在测试数据库和真实数据库间切换。

### 答案 1

当前项目没有手工运行专用的 `application-test.yaml`/`application-prod.yaml`，Spring Boot 通过启动进程环境变量切换：MySQL 使用 `MYSQL_URL/MYSQL_USERNAME/MYSQL_PASSWORD`，Redis 使用 `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_DATABASE`，上传目录使用 `APP_UPLOAD_STORAGE_PATH`，端口使用 `SERVER_PORT`。环境变量只在进程启动时绑定，切换后必须停止并重新启动后端；如果测试后端使用不同端口，还要把 Vite 的 `VITE_API_PROXY_TARGET` 指向该端口并重启 Vite。

切换必须按环境成套进行，不能只替换 MySQL schema。建议真实环境使用 `campus_resource_platform`、Redis DB 0、正式上传目录和端口 8080；批量造数环境使用 `campus_resource_platform_seed`、Redis DB 1、独立 `data/seed-uploads`、独立 JWT 密钥和端口 8081。否则资料 ID 相关的详情缓存、MD5 缓存、收藏、排行榜、下载限流/增量和物理文件会跨库串数据。测试库应使用只拥有测试 schema 权限的数据库账号，并在启动前打印脱敏后的 host、schema、Redis DB 和上传目录。

前端 API 前缀继续保持 `/api/v1`，只切换 `VITE_API_PROXY_TARGET`。若真实和测试后端分别监听 8080/8081，可以让两个 Vite 实例分别监听不同前端端口；若共用同一后端端口，则先停旧后端、替换整套环境变量再启动，前端代理地址不变时无需重启。

自动化测试需要单独区分：多数数据库测试显式使用 H2 MySQL Mode；`AuditServiceDatabaseIntegrationTest` 会读取 `MYSQL_TEST_URL/MYSQL_TEST_USERNAME/MYSQL_TEST_PASSWORD` 并操作真实 MySQL。其测试 schema 脚本含无条件 `DROP TABLE IF EXISTS`，因此 `MYSQL_TEST_URL` 绝不能指向 `campus_resource_platform`。为防止其他上下文测试继承真实连接，执行 Maven 测试前也应清除真实库变量或把 `MYSQL_URL` 一并设为测试库。

连接验证不能只请求 `/api/v1/health`，因为该接口只检查应用进程。应使用与启动参数相同的账号执行只读 `SELECT DATABASE(), @@hostname, @@port`，确认 Redis 连接和选定 database，再调用分类查询等只读业务接口。`sql/init.sql` 顶部硬编码创建并使用 `campus_resource_platform`，初始化 `campus_resource_platform_seed` 时需要在临时副本或数据库工具中明确替换这两个库名，不能直接执行原脚本后误以为写入了测试库。

### 关联信息

- 配置依据：`campus-resource-platform/src/main/resources/application.yaml`、`frontend/vite.config.ts`、`campus-resource-platform/src/test/resources/application.properties`、`AuditServiceDatabaseIntegrationTest`、`resource-db-test-schema.sql`。
- 本轮只读核对配置和测试边界，未切换数据库、未修改生产代码、未运行写请求；按项目规则仅追加问答归档，未提交、未推送。

## 2026-09-08 | 关闭已启动的项目

### 问题 1

如何关闭已经启动的项目

### 答案 1

前端和后端需要分别停止：在运行 npm run dev 的前端终端按 Ctrl + C，在运行 spring-boot:run 或 java -jar 的后端终端按 Ctrl + C；若终端提示是否终止批处理，输入 Y 后回车。若后端通过 IntelliJ IDEA 启动，在对应 Run/Debug 窗口点击红色停止按钮。仅关闭浏览器页面不会停止项目服务。通常无需停止 MySQL、Redis，也不要删除数据库、缓存或上传文件。

本轮检查时默认后端端口 8080 和前端端口 5173 都有监听，未执行停止操作。手动停止后可在 PowerShell 执行 Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 5173,8080 } 检查；无输出表示这两个端口已无监听。

### 关联信息

- 依据：docs/07-project-runbook.md 第 5.4、6.5 和 10 节。
- 验证：只读检查监听端口；git diff --check 检查文档格式；本轮为操作问答，未运行业务测试。
- 分支：dev；保留原有问答日志修改，仅追加本记录；没有新增 commit，未推送。

## 2026-09-08 | 找不到终端时关闭前后端的命令

### 问题 1

无法找到当前前后端终端，直接给出关闭命令

### 答案 1

本轮核查默认后端 8080 端口对应 PID 189792，前端 5173 端口对应 PID 192408。可在 PowerShell 执行 Stop-Process -Id 189792,192408 -Force，强制停止当前这两个进程。PID 仅适用于本次核查的进程，重启后需重新查询。停止后执行 Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 5173,8080 }，无输出表示这两个端口已无监听。本轮只提供命令，未代为停止服务。

### 关联信息

- 验证：Get-NetTCPConnection 与 Get-Process 只读检查；未运行业务测试。
- 分支 dev；仅追加问答记录，保留已有修改，无新增提交、未推送。

## 2026-09-08 | 每次通用的项目关闭命令

### 问题 1

有没有固定的命令能在每次启动后都不依赖终端关闭项目

### 答案 1

可以在任意新开的 PowerShell 窗口执行以下命令，动态查找前端 5173 和后端 8080 的监听进程并强制停止，不需要原启动终端，也不需要记住 PID：

```powershell
# 按项目端口查找进程，去重后强制关闭前后端
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in 5173,8080 } |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force }
```

该命令按端口识别进程，适用于本项目仍使用这两个端口的情况；若实际启动端口改变，应替换端口号，并确保它们没有被其他项目占用，避免关闭其他项目。本轮只提供命令，未执行停止操作。

### 关联信息

- 依据：frontend/vite.config.ts、frontend/README.md、campus-resource-platform/src/main/resources/application.yaml、docs/07-project-runbook.md。
- 验证：静态核对配置与运行手册；git diff --check；未实际执行关闭，未运行业务测试。
- 当前分支 dev；仅追加问答记录，保留已有修改；无新增 commit、未推送。

## 2026-09-08 | 上传物理文件的存储位置

### 问题 1

当前项目上传的物理文件存储在哪里

### 答案 1

当前使用后端服务器本地磁盘存储。按运行手册从后端子目录启动时，上传目录为仓库根目录下的 campus-resource-platform/data/user-uploads/。本机已确认该目录存在且含 3 个文件；旧 data/uploads/ 目录为空。

配置依据是 campus-resource-platform/src/main/resources/application.yaml 第 41 行：app.upload.storage-path 的默认值为 ./data/user-uploads，可由 APP_UPLOAD_STORAGE_PATH 环境变量覆盖。相对路径以 Java 进程工作目录为基准；运行手册显式设置为后端子目录下的 data/user-uploads。若启动时覆盖配置，实际存储位置以运行配置为准。

FileStorageServiceImpl 将路径规范化为绝对路径，以去横线的 UUID 加扩展名生成文件名并写入磁盘；file_info.storage_path 保存文件的绝对路径，数据库保存元数据，文件内容在磁盘目录内。

### 关联信息

- 核查：application.yaml、FileStorageServiceImpl、FileServiceImpl、运行手册及本地目录。
- 当前分支 dev；仅追加本条问答，保留已有修改；未运行业务测试（配置与路径问答），无新增 commit，未推送。

## 2026-09-08 16:33:47 | 使用当前数据库批量写入测试数据

### 问题 1

直接将当前配置的数据库作为测试数据库，编写脚本并写入数据（文件数量：30-40）

### 答案 1

已新增 PowerShell 7 脚本 `scripts/seed-homework-test-data.ps1` 和使用文档 `docs/10-bulk-test-data-seeding.md`。脚本默认从 `C:\Users\Lenovo\Desktop\HomeWork` 筛选 36 个后端允许、非空、不超过 50 MiB 且 `MD5 + size` 唯一的文件，排除依赖、构建、缓存、压缩包、reparse point 和疑似凭据文件；所有数据均通过现有 HTTP API 写入，不使用 SQL 绕过认证、文件授权、事务、审核状态机或 Redis 联动。

批次 `seed-20260908-a` 已实际写入当前配置数据库。最终生成 36 条资料和 36 个不同 `fileId`，状态为通过 24、待审核 5、拒绝 4、下架 3；另有 34 条审核流水、18 条有效收藏、6 条下载记录和约 12 次搜索行为，并已执行管理员总榜重建。API 验证确认 24 条通过资料可公开查看，另外 12 条不可公开，普通用户不能访问管理员审核接口；6 次下载均实际取得正文并通过源文件 MD5 校验。

脚本支持 `Preview`、`Seed`、`Resume`、`Verify` 四种模式。写入和核验模式必须显式传入 `-AcknowledgeCurrentDatabaseAsTest`；账号凭据只从 `CRP_SEED_ADMIN_USERNAME`、`CRP_SEED_ADMIN_PASSWORD`、`CRP_SEED_USER_PASSWORD` 环境变量读取。运行清单保存在 Git 忽略目录 `campus-resource-platform/data/seed-runs/<RunId>/manifest.json`，只记录相对路径、MD5、业务 ID 和阶段状态，不保存密码、Token 或源目录绝对路径。Resume 会先执行授权感知的 MD5 预检，上传结果不确定时不会直接重发文件，避免重复授权导致引用计数虚增。

### 关联信息

- 分支：dev。
- 验证：PowerShell Parser、Preview 36、Seed、Resume 幂等恢复、Verify、MySQL 只读汇总、API 权限与可见性检查、git diff --check 均通过。
- 提交：`0441aace7f71a1a18c81e237362e1304fb244d39`，已推送到 `origin/dev`。
- 问答日志原本已有未提交修改，本条仅追加且未纳入上述功能提交，避免混入既有工作区内容。

## 2026-09-10 | 审核删除资料详情缓存为什么需要锁

### 问题 1

举例说明为什么审核模块删除资料详情缓存时需要锁

### 答案 1

加锁是为了协调“查询数据库并回填缓存”和“审核提交后删除缓存”的执行顺序，防止删除后旧数据又被慢查询写回。Redis 单条 DEL 的原子性不能保证这整个跨 MySQL、Redis 流程的原子性。

例如资料 100 原来已通过，详情缓存刚好过期：请求 A 缓存未命中，查询 MySQL 得到已通过的旧资料，但尚未回填；管理员请求 B 将资料下架并提交事务，然后删除详情缓存；A 随后把之前读取的已通过快照写回 Redis。结果是数据库已下架，后续详情请求却命中旧缓存，仍能看到公开详情。即使 A 自己持锁，只要 B 删除时不参与同一把锁，仍然可能发生这种交错。

当前 getOrLoad() 和 invalidate() 共用按 resourceId 区分的 Redisson 锁 crp:lock:cache:resource:detail:{resourceId}。正常持锁时，A 先拿锁则 B 等待 A 查询和回填完成，再删除旧缓存；B 先拿锁并删除则后续 A 在锁内回源，查到已下架状态，抛出业务异常且不回填。锁必须覆盖读侧数据库查询及回填整个过程，不能仅保护写 Redis；删除还必须放在 MySQL 事务提交后，否则读侧仍可能读到尚未更新的已提交状态。

保证有边界：代码锁等待为 2 秒，失效侧锁超时或故障会降级直接删除，仍可能出现旧快照回填；缓存 TTL 为 30～35 分钟，可用于最终过期兜底。缓存命中路径不加锁，因此这套机制不保证下架提交瞬间所有在途请求都看不到旧详情。

### 关联信息

- 代码依据：ResourceDetailCacheServiceImpl.getOrLoad()/invalidate()、AuditServiceImpl.invalidateDetailCacheAfterCommit()/runAfterCommit()、ResourceServiceImpl.loadPublicDetailFromDatabase()、RedisKeyConstants.resourceDetailLock()。
- 当前分支：dev；本轮仅解释代码并追加问答，保留原有未提交修改。未运行业务测试；无新增 commit，未推送。

## 2026-09-10 | 搜索动态排序的白名单与 SQL 注入防护

### 问题 1

搜索模块动态排序为什么不能直接拼接前端字段，如何用白名单映射防 SQL 注入。

### 答案 1

前端参数是不可信输入，下拉框限制可以被绕过。ORDER BY 的列名和 ASC/DESC 属于 SQL 语法；直接拼接请求参数或使用 ${sortBy} ${order}，会把输入当作 SQL 解析，允许输入夹带额外排序表达式、函数或子查询。即使禁止多语句执行，也不能阻止单条语句内的注入。MyBatis 的 #{} 用于绑定数据值，不能替换列名或排序关键字；ORDER BY #{sortBy} 不会根据字符串值选择对应列，不能用来修复动态列排序。

白名单的核心是让用户只选择后端预设选项，实际 SQL 片段始终来自后端常量。当前项目映射为：createdAt -> created_at，downloadCount -> download_count，favoriteCount -> favorite_count，hotScore -> hot_score。排序方向另行限制为 asc/desc，输出固定 ASC/DESC；普通查询值继续使用 #{}。仅过滤分号或引号、仅校验前端、仅把驼峰转下划线，都不能代替白名单。

实际调用链中，SearchResourceQueryDTO 用 @Pattern 限制字段和方向，SearchController 的 @Valid 触发入口校验；SearchServiceImpl.resolveSortBy()/resolveOrder() 再校验和归一化，非法非空选项抛出 PARAM_ERROR，缺省使用 createdAt/desc。真正从业务选项到数据库列名的映射发生在 ResourceMapper.xml 的 ApprovedSearchOrderBy，通过 <choose>/<when> 输出固定列名和方向，不插入原始请求文本。即使绕过 Service 直接调用 Mapper，未知字段和方向也只会分别回退到 created_at 和 DESC。DTO 注释中“Service 会映射为真实数据库列名”的描述不够准确，应以此真实实现为准。

例如 sortBy=downloadCount&order=desc 生成 ORDER BY download_count DESC, id DESC；id 是同向的第二排序键，使主排序值相同时顺序确定，但不保证并发数据变化下跨页快照一致。输入若为 download_count DESC, id，Service 会因不匹配任何白名单选项而拒绝，不会把它作为 SQL 执行。

依据：campus-resource-platform/src/main/java/com/john/campus/service/impl/SearchServiceImpl.java:241、campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml:81、SearchResourceQueryDTO.java、SearchController.java。MyBatis 官方参数与字符串替换说明：https://mybatis.org/mybatis-3/sqlmap-xml.html#String_Substitution 。

### 关联信息

- 当前分支：dev；本轮为代码解释，只追加问答日志，保留日志及运行手册原有未提交修改。
- 验证：核查生产代码和既有测试源码；未运行业务测试。无新增 commit，未推送。

## 2026-09-10 | 动态排序白名单的具体例子

### 问题 1

举例说明

上下文：解释搜索模块为什么不能直接拼接前端排序字段，以及白名单映射如何防 SQL 注入。

### 答案 1

正常请求 sortBy=downloadCount&order=desc 表示按下载量从高到低排序。后端把业务选项 downloadCount 映射为固定列名 download_count，把 desc 映射为固定关键字 DESC，项目最终生成 ORDER BY download_count DESC, id DESC。

假设存在不安全实现 ORDER BY ${sortBy} ${order}，有人绕过页面直接构造请求，令 sortBy=CASE WHEN id = 100 THEN 0 ELSE 1 END，order=asc（这里只展示解码后的参数值）。直接替换将得到 ORDER BY CASE WHEN id = 100 THEN 0 ELSE 1 END ASC。这个表达式会让结果中 id=100 的记录优先显示，说明客户端输入已经从“排序选项”变成了可执行 SQL 表达式。此例只是无破坏性的排序篡改演示，不表示当前项目存在此漏洞，也不表示它能绕过 WHERE 条件；风险不依赖分号或多语句执行。

当前项目的白名单只接受 createdAt、downloadCount、favoriteCount、hotScore，因此上述 CASE 字符串在正常 HTTP 路径被 DTO 校验拒绝，绕过 Controller 直接调用 Service 时也会被 Service 拒绝；即使直接调用 Mapper，XML 的固定分支也只会回退到 created_at，不会把 CASE 文本拼进 SQL。方向同样单独限制为 asc/desc，不能只校验列名。可以理解为前端只交“菜单选项”，后端决定具体 SQL。

### 关联信息

- 依据：上一轮已核查的 SearchResourceQueryDTO、SearchServiceImpl.resolveSortBy()/resolveOrder()、ResourceMapper.xml 的 ApprovedSearchOrderBy。
- 分支：dev；本轮只追加问答日志，保留既有未提交修改；未执行数据库请求或业务测试，无新增 commit、未推送。

## 2026-09-10 | 当前管理员权限范围

### 问题 1

项目中管理员权限有哪些

### 答案 1

当前代码以 role=1 表示普通用户、role=2 表示管理员。管理员专属能力共 7 项：

1. 查看待审核资料列表，支持课程、资料类型、上传者筛选和分页。
2. 读取待审核资料的实际文件用于审核；仅限待审核状态，支持部分格式内联预览，其余作为附件下载。
3. 审核通过：待审核转为已通过，审核意见可选。
4. 审核拒绝：待审核转为已拒绝，必须填写拒绝原因。
5. 下架资料：已通过转为已下架，必须填写下架原因。
6. 查询资料审核历史，查看通过、拒绝、下架等操作流水。
7. 手动重建热门资料 all 总榜，以 MySQL 当前统计值重建 Redis 总榜；不是重建日榜、周榜、月榜或热搜榜。

审核相关入口在 /api/v1/admin/resources 下，排行榜重建入口为 POST /api/v1/admin/rankings/resources/hot/rebuild。前端已有审核管理、已发布资料管理和排行榜运维三个管理入口；已发布资料列表复用公开搜索接口，查看榜单本身也不是管理员专属权限。审核文件读取已有后端接口，当前前端审核页面尚未接入。

管理员还可使用普通登录用户功能，但不具有所有操作的无限豁免：公开详情、普通下载仍要求资料已审核通过；上传仍进入待审核流程。当前未实现管理员用户封禁/解封、角色分配、分类增删改、任意编辑/删除他人资料、下架资料重新上架、完整管理端统计等接口，不能把规范文档中的规划当作已实现功能。

权限校验链路为 JWT 拦截器验证 Token 并写入 LoginUser，AuditServiceImpl.requireAdmin() 和 AdminRankingServiceImpl 校验管理员角色，普通用户调用返回权限不足。前端 role=2 的路由判断负责入口控制，最终权限由后端执行；注册默认普通用户。

### 关联信息

- 依据：AuditController、AuditServiceImpl、AdminRankingController、AdminRankingServiceImpl、ResourceServiceImpl、DownloadServiceImpl、AuthServiceImpl、JwtAuthenticationInterceptor、frontend/src/router/index.ts 及三个管理页面。
- 分支：dev；本轮只读核查代码并追加问答日志，保留既有日志和运行手册修改。
- 验证：rg 检索并静态核对 Controller、Service、前端路由与页面；未运行业务测试。本轮无新增 commit，未推送；当前 HEAD 为 0441aace。

## 2026-09-10 16:52:55 | 为什么单独设计下载票据

### 问题 1

为什么需要专门设计下载票据

### 答案 1

当前下载采用两步接口：先经过用户/IP 限流、资料与文件校验，创建下载记录并签发票据；再携带 JWT 和 X-Download-Ticket 请求文件流。专门设计票据的主要原因是让第二步取流必须持有最近一次通过下载入口校验的、可消费的授权。若只凭长期存在的 downloadRecordId 和登录态取流，即使校验记录归属，同一用户也可能反复请求自己的旧记录文件 URL，绕过第一步的下载限流。

JWT 表示登录身份，下载记录用于审计追溯，下载票据则表示当前用户对指定记录的一次短期取流授权。票据使用 SecureRandom 生成 32 字节随机数并进行 Base64URL 编码，Redis Key 为 crp:download:ticket:{userId}:{downloadRecordId}:{ticketDigest}，ticketDigest 是票据 SHA-256 摘要，值为 1，TTL 为 60 秒。绑定当前登录用户和记录可阻止跨用户或跨记录使用；保存摘要降低 Redis 数据泄露后直接重放原始票据的风险。取流时用 Lua 原子检查并删除，保证同一票据并发消费最多一个请求成功；失效后必须重新申请并经过下载入口限流。

票据不能替代取流时的权限复核。当前代码消费票据后还检查记录归属、资料仍为 APPROVED、关联文件未变化及文件状态，阻止签发后已下架的资料继续取流。消费发生在文件读取之前，因此如果后续读取失败或传输中断，需要重新申请票据；60 秒限制的是票据消费窗口，并非文件传输的最长时长。该设计适合本项目的两步下载接口，也可以用每次取流都重新限流和校验的方式设计其他系统，并非所有下载接口必须使用票据。

面试回答：我把下载拆成申请和取流两步，限流在申请入口；如果仅凭长期下载记录 ID 取流，就可能重放旧地址绕过限流。因此增加绑定用户和记录、60 秒有效的一次性随机票据，用 Redis TTL 控制有效期、Lua 原子消费防重放，取流前再复核资料状态，让每次取流都对应一次近期通过入口校验的授权。

### 关联信息

- 依据：DownloadServiceImpl.createDownloadRecord、createDownloadTicket、consumeDownloadTicket、loadFile 与 DownloadController.downloadFile；Key 格式沿用前文已核验的 RedisKeyConstants。
- 验证：rg 静态核对当前生产代码；分支 dev，本轮仅追加问答日志，保留已有修改；未运行测试、未新增 commit、未推送。

## 2026-09-10 | 项目流程的面试版完善（不含热度排行榜模块）

### 问题 1

这是我对项目流程的总结：

登录：客户端请求受保护接口，拦截器拦截，判断Authorization 是否为 Bearer Token，JwtUtils解析token，判断token是否有效，提取登录信息

文件上传：
上传去重按uk\_file\_md5\_size (file\_md5, file\_size) 唯一索引查是否已存在：命中，开启短事务：引用计数自增，幂等写入当前用户文件授权，回填缓存；未命中：生成 UUID 存储名并落盘，开启短事务：插入 file\_info同时写入当前用户文件授权，数据库写入成功则回填缓存，唯一索引冲突则删除多余落盘文件并查询已存在事务转入秒传，开启短事务：引用计数自增，幂等写入当前用户文件授权，回填缓存；未命中：生成 UUID 存储名并落盘

先预检，再执行上传主流程，预检成功不用走上传流程

资料：创建资料的前提是文件可引用，并且需要判断是否存在待审核或已通过资料
公开详情缓存：未命中需要申请Redisson锁并在锁内二次检查redis，仍未命中才查MySQL，资料必须存在且审核通过，最后回填缓存

审核：必须是管理员，管理员可查看待审核列表，审核记录，查看文件，执行资料状态变更
```scss
状态变更：状态变更必须满足状态要求，事务内执行状态更新和审核记录写入并判断受否只影响一行（防止并发重复操作）

事务结束后必须删除缓存：与 getOrLoad() 使用同一把 Redisson 每资料锁（crp:lock:resource:detail:{id}）
```

搜索：游客可访问，只能搜索已审核资料，归一化关键词、分页参数，并映射排序白名单（过滤危险指令），若存在关键词，则写入redis三个榜单

下载：JWT检验->滑动窗口限流->插入下载记录（只能下载已审核资料）->签发下载票据（如果只凭长期下载记录 ID 获取文件，用户可能重放旧地址绕过限流。）->去重 Key 判断是否计数->用户携 X-Download-Ticket 请求文件流->Lua 原子校验并删除一次性票据（根据前端字段拼接出同一个redis key）->查询下载记录并校验仅属于当前用户->返回二进制流
```css
增量同步：定时任务，为了减少 MySQL 高频更新下载量的压力，我先用 dedup:download:{userId}:{resourceId} 做10分钟计数去重，再通过 HINCRBY 将有效下载聚合到 download:delta Hash。定时任务获取 Redisson 分布式锁，通过 Lua 将 delta 原子隔离成 syncing:{UUID} 批次，并设置 syncing:current 指针。新下载继续写新的 delta，避免清理旧批次时误删新增量。接着在 MySQL 同一事务中写入批次幂等明细（download_delta_sync_item 的唯一键：(batch_id, resource_id)）并累加下载量，提交成功后才删除 Redis 对应字段。失败时恢复同一批次重试，依靠 (batch_id, resource_id) 唯一键，避免数据库已提交、Redis 确认失败造成重复计数。
```

收藏：收藏操作涉及两张表（favorite 写入（修改或插入） + resource.favorite\_count 更新），当前通过 TransactionTemplate 把两项 MySQL 写操作包裹在同一事务中。MYSQL事务结束后刷新收藏set key的有效期或者删除key
查询收藏状态先查redis，再查数据库并回写

从面试角度补充细节并完善该总结

用户补充范围原文：该流程总结已经排除了热度排行榜模块，不需要额外附加

### 答案 1

按当前代码整理为以下面试表述，范围只含用户列出的模块，搜索部分保留原有搜索词统计。

1. 登录与请求认证分开描述。登录接口查询用户，用 BCrypt 校验密码、检查账号正常状态，更新登录时间并签发包含 userId、role、jti 和过期时间的 JWT。受保护请求由拦截器读取 Bearer Token，JwtUtils 验签并检查有效期，再查询 Redis jti 黑名单，写入 ThreadLocal 和 request attribute；请求结束清理 ThreadLocal，防止线程复用串号。退出将 jti 加入黑名单，TTL 为 Token 剩余有效期。JWT 表示身份，管理员角色和文件归属还要由业务层校验；当前请求链不逐次查用户表，不能宣称禁用账号或改变角色会自动让旧 Token 实时失效。

2. 上传预检先查 MD5+size 三态缓存：FOUND 仍查当前用户授权，NOT_FOUND 直接判不可秒传，ABSENT 回源 MySQL。只有存在候选文件且当前用户已有授权才返回可秒传及 fileId；预检不新建授权、不增加 ref_count。正值缓存 6 小时、负值 5 分钟，负值 SET NX 防止覆盖并发正值，成功上传后普通 SET 覆盖旧负值。真实上传校验文件并由服务端计算 MD5，直接查 MySQL。命中时在短事务中 ref_count 原子加一与授权 INSERT IGNORE，提交后回填缓存；未命中则 UUID 名落盘，再短事务插入 file_info 和用户授权。唯一键竞争失败后原事务结束，补偿删除本次冗余文件，查询获胜记录并另开短事务复用；其他数据库失败也补偿删除。末尾重复的“未命中落盘”不需要再写。唯一索引兜底并发，文件 IO 放事务外缩短数据库连接/锁占用，但文件系统不受 MySQL 回滚保护，补偿删除失败或进程崩溃仍可能有孤儿文件。真正跳过网络上传的是授权预检命中；真实上传链内去重主要节省存储。MD5 是去重指纹，不是文件授权凭据。ref_count 当前每次真实去重上传都增加，不能等同于资料引用数或授权人数，亦不能直接用于精确文件回收。

3. 创建资料时重新检查文件正常、当前用户有文件引用授权、分类已启用；同一用户同一文件不能同时有第二条待审核或已通过资料。新资料由服务端设置上传者、待审核状态和初始统计值。前置查重用于友好提示，最终用 active_duplicate_guard 生成列和 uk_resource_active_duplicate(uploader_id,file_id,active_duplicate_guard) 防并发：待审核/已通过生成 1，拒绝/下架/删除生成 NULL，保留历史并允许重新提交。单有事务和前置查询不能替代唯一约束。

4. 公开详情采用 Cache Aside；命中检查 ID、公开状态及用户态字段合法性。未命中获取每资料 Redisson 锁，锁内二次读取后才查 MySQL，资料必须存在且为 APPROVED。公共 JSON 的 favorited 固定为 null，TTL 30 分钟加 0～5 分钟随机值。回填和审核失效的实际共用锁名是 crp:lock:cache:resource:detail:{id}，原总结少了 cache 段。等待锁约 2 秒，读侧锁失败只回源、不回填；这里未实现不存在结果的负缓存。锁解决热点 miss 的并发回填，并协调审核失效；缓存命中并不每次再查 MySQL。

5. 审核后端从登录上下文校验管理员角色，支持待审核列表、审核历史、待审核文件读取以及通过/拒绝/下架。状态机为待审核到通过或拒绝、已通过到下架。UPDATE 带主键与旧状态条件，主键限定最多一行，代码检查更新为 0 时拒绝；不能说状态更新和日志插入都显式检查了影响一行，审计插入目前没有单独检查行数。状态变更与 audit_record 写入同一事务，日志失败回滚状态。afterCommit 在提交成功后触发详情缓存失效，回滚不触发；正常持同锁路径避免慢查询在删除后回填旧值。锁超时降级直接 DELETE，Redis 删除失败仅告警并由 TTL 兜底，因此应讲最终一致及失败窗口，不能讲强一致或保证缓存必删成功。

6. 搜索匿名可访问，SQL 固定 status=APPROVED。关键词去首尾空格、空串转 null，检查长度和分页范围；普通查询值通过 MyBatis #{} 参数绑定，排序字段和方向使用服务端白名单映射固定 SQL，准确表述为参数化查询加白名单，而非过滤危险指令。第二排序字段 id 让相同主排序值的顺序稳定。当前使用 MySQL LIKE；不能宣称为全文检索或模糊匹配必走普通 B+Tree 索引。非空关键词在 COUNT 查询成功后对三个搜索词 ZSet 执行 ZINCRBY，零结果也计入；Redis 失败只跳过统计，不影响搜索主流程。

7. 下载分申请与取流。申请时 JWT 认证后执行用户/IP 两维度滑窗限流：60 秒用户最多 10 次、IP 最多 30 次。ZSet score 为毫秒时间，member 带 UUID；单 Lua 原子清旧请求、计数、判断、加入及续期，两个维度是先后执行的两个脚本，并非共同原子。通过后查资料已审核和文件正常，创建记录、签发 60 秒一次性票据，再做去重统计。JWT 证明身份，下载记录用于追溯，票据约束近期通过入口校验的一次取流授权，避免重放旧记录地址绕开限流。票据服务端生成 32 字节安全随机数；Redis Key 是 crp:download:ticket:{userId}:{downloadRecordId}:{ticketDigest}，userId 取后端登录上下文，digest 为收到票据的 SHA-256，不能描述成信任前端提供用户身份。取流仍需 JWT 和 X-Download-Ticket，Lua 仅原子检查/删除票据，然后 Java/MySQL 校验记录归属、资料仍为 APPROVED、文件关联未变和文件状态，再返回文件流。票据已消费后失败需重新申请；60 秒是消费有效期而非传输时长。限流与票据 Redis 异常拒绝申请/取流，统计 Redis 异常可降级。当前计数在申请票据阶段，不能表述为完整文件传输成功数。

8. 下载增量统计：SET NX EX 对同用户同资料做 10 分钟计数去重，不限制这段时间只允许下载一次；HINCRBY 写 crp:stats:resource:download:delta。定时任务持 Redisson 锁，优先处理 syncing:current 指向的未完成批次；否则 Lua 原子将 delta RENAME 为 syncing:{UUID} 并设置 current，新下载写新 delta。独立 Spring Bean 的事务把 download_delta_sync_item 幂等明细插入和 resource.download_count 原子累加放在一起；仅首次插入才累加，重复明细核对增量一致后跳过重复累加。事务提交后才 HDEL 已处理字段，完成再清理批次/指针。失败保留同一 UUID 批次重试，不换批次、不合并回新 delta，避免已提交但 Redis 确认失败导致重算。面试可说至少一次重试配合幂等落库，实现最终一致，不能保证端到端严格不丢不重：入口 SET NX 与 HINCRBY 是两个命令，前者成功后中断或后者失败可能漏计；持久化幂等不能补回从未写进增量的数据。

9. 收藏关系和 resource.favorite_count 的变化由 TransactionTemplate 包在同一 MySQL 事务。唯一(user_id,resource_id) 防重复关系，恢复用 0→1、取消用 1→0 条件更新，仅实际改变状态才调整计数；SQL 原子加减，递减限制当前值大于 0。事务返回提交成功后，新收藏实际执行 SADD 和 30 分钟续期，取消执行 SREM，不是统一删除整个 Key。状态查询 Key 存在即 SISMEMBER，否则查询数据库并尝试加载全部有效收藏重建 Set。当前存在具体限制：用户原已收藏 A，缓存过期后新增 B，只写入 {B}，再查 A 会因为 Key 存在误报未收藏。因此不能把它描述成完整集合一致性已保证；改进可用完整加载标记，未完整加载的负判断回源，并协调重建与更新并发。此处为现状与改进说明，本轮未修代码。

### 关联信息

- 主 Agent 核对 AuthServiceImpl、JwtUtils、JwtAuthenticationInterceptor、WebMvcConfig、SearchServiceImpl、ResourceMapper.xml、RedisKeyConstants 等；两个只读 Agent 分别核对上传/资料/审核和下载/同步/收藏，结论已统一按真实代码校正。
- 关键依据：FileServiceImpl:79/100/129、ResourceServiceImpl:109、ResourceDetailCacheServiceImpl:111/186、AuditServiceImpl:146/245、DownloadServiceImpl:109/199/244/298、DownloadRateLimiterImpl:41、DownloadDeltaSyncServiceImpl:84、DownloadDeltaPersistenceServiceImpl:38、FavoriteServiceImpl:152/322/340，以及 sql/init.sql 的唯一约束。
- 验证：rg 检索并静态核对 Java、Mapper SQL 与配置；本轮为流程解释，未运行业务测试，不声称完成运行或并发实测。
- 分支 dev；仅在本日志末尾追加，保留原有日志及运行手册修改；当前 HEAD 0441aace，本轮无新增 commit，未推送。

## 2026-09-14 | 各模块复习联想关键词

### 问题 1

总结出每个模块的关键词供用户复习时联想记忆

### 答案 1

沿用上一轮已核对的模块范围，不附加热度排行榜模块。复习时按“入口 → 核心处理 → 并发/事务 → 缓存/异常”联想。

| 模块 | 联想关键词链 |
| --- | --- |
| 登录认证 | BCrypt → JWT 签发 → Bearer → 验签/过期 → jti 黑名单 → ThreadLocal → 请求结束清理 |
| 文件上传 | MD5+大小 → 三态预检 → 用户授权 → 服务端重算 → 唯一索引 → UUID 落盘 → 短事务 → 冲突复用/补偿删除 → 提交后缓存 |
| 创建资料 | 文件正常 → 引用授权 → 分类启用 → 同用户同文件查重 → 生成列+条件唯一约束 → 待审核 |
| 公开详情 | Cache Aside → 公共字段 → Redisson 每资料锁 → 锁内二次检查 → 已审核 → 随机 TTL → 锁失败回源不回填 |
| 管理员审核 | 管理员 → 状态机 → 旧状态条件更新 → 影响行数 → 状态+日志同事务 → afterCommit → 同锁删缓存 |
| 搜索 | 游客可查 → 仅已审核 → 参数归一化 → 参数绑定 → 排序白名单 → id 稳定排序 → 搜索词 ZSet → 统计失败降级 |
| 下载取流 | JWT → 用户/IP 滑窗 → ZSet+Lua → 下载记录 → 60秒一次性票据 → SHA-256 摘要 → 原子消费 → 归属/状态复核 → 文件流 |
| 下载量同步 | 10分钟 SET NX 去重 → HINCRBY → 分布式锁 → Lua 隔离 UUID 批次 → current 指针 → 幂等明细+累加同事务 → 提交后 HDEL → 同批次重试 |
| 收藏 | 用户+资料唯一键 → 状态条件更新 → TransactionTemplate → 关系+计数原子提交 → SADD/SREM → SISMEMBER → 缺失回源重建 |

重点防混淆：认证不等于授权；预检秒传要求已有授权；授权幂等不等于 ref_count 是精确引用数；事务成功后删缓存不等于强一致；下载计数去重不等于禁止重复下载；申请阶段计数不等于完整传输成功；批次幂等不等于入口不漏计；Set 存在不等于缓存集合完整（当前收藏实现有此误判风险）。

### 关联信息

- 依据：上一轮已经核对的 Java、Mapper SQL、Redis 常量及当前 HEAD 未变；本轮只提炼记忆关键词。
- 分支 dev，HEAD 0441aace；仅追加问答日志，保留已有修改。未运行业务测试，无新增 commit，未推送。

## 2026-09-19 | 项目结构规范与 Java 实习展示 README

### 问题 1

检查当前项目结构是否符合规范，为项目编写readme.md文件并推送到github，让更多人能看到并收藏我的项目（主要面向Java实习项目）

### 答案 1

已检查目录、依赖清单、应用配置、核心业务链路、SQL 与测试组织。结构整体符合 Java 实习项目的工程组织需要：内层 Spring Boot 工程采用标准 Maven 目录及 Controller / Service / Mapper 分层，前端独立，SQL 初始化与迁移分开，文档、测试、运行时上传数据边界清楚，无需为了展示迁移后端目录。

发现的主要不足是文档与实现不同步：docs/AGENTS.md 的源码路径、配置扩展名、统一响应类和 Redis 实现状态有过期描述，两个必读入口文件不存在；仓库尚无 CI 工作流、LICENSE 和 CONTRIBUTING，前端仍有模板遗留。具体证据和后续建议已写入 docs/11-project-structure-review.md；本轮未扩展修改业务代码或协作规范。

根 README.md 已重写为面向 Java 实习展示的项目首页，包含真实功能、后端技术亮点及源码链接、业务流程图、实际目录、Windows / Unix 启动说明、演示账号与分类准备、测试前提、面试阅读路线和 Star 引导。根据用户补充要求，开头明确说明该项目由仓库作者独立设计与开发。检索能力如实写为 MySQL 查询，不宣称 AI 检索、生产高可用或未经验证的性能。

本轮验证：Markdown 本地链接 / 锚点 46 个及代码围栏检查通过，7 段 PowerShell 示例语法解析通过，git diff --check 通过；前端 npm run test:unit 为 31 个文件、66 个用例通过，npm run build 通过并保留既有产物体积警告。后端 mvnw.cmd test 执行 169 个用例，其中 160 个通过，9 个审核集成测试因本机 MySQL Connection refused 出错，不能声称全量测试通过。本次修改仅限文档，未改变接口、数据库或 Redis 行为。

GitHub 元数据核查：仓库已公开，默认分支为 dev；本轮在 dev 提交推送即可更新首页 README，无需修改 main 或仓库可见性。具体提交与推送结果以最终答复为准。

## 2026-09-19 | 提高项目在 GitHub 的可检索性

### 问题 1

如何让我的项目更容易被检索到

### 答案 1

核查 GitHub 公开仓库元数据：仓库已公开，简介仍为“校园资料共享与智能检索平台 Java 后端项目”，Topics 为空，Homepage 为空。当前优先补充 About 简介与相关 Topics。GitHub 官方说明普通仓库搜索默认匹配仓库名称、简介与 Topics；README 内容需使用 in:readme 搜索限定符，因此仅扩充 README 不能覆盖所有默认搜索场景。

建议简介：“独立开发的 Java 后端实习项目｜校园资料共享平台｜Spring Boot 3 + MyBatis + MySQL + Redis + Vue 3｜文件去重、审核事务、Lua 限流、幂等统计与面试复盘”。在仓库首页 About 齿轮中修改 Description 与 Topics。推荐真实相关 Topics：java、spring-boot、mybatis、mysql、redis、redisson、vue3、jwt、file-upload、rate-limiting、backend、learning-project。不添加项目尚未实现的 AI、RAG、Elasticsearch 或微服务标签，不建议仅为关键词更改现有仓库名。

README 可自然补充英文一句话介绍，覆盖 Java backend internship project、campus resource sharing platform 等表达；保持中英文介绍与当前能力一致。补充真实页面截图和短演示，可帮助访问者理解功能并判断是否收藏，但不能保证提升搜索排名。面向目标读者发布有具体实现内容的技术文章，例如 Redis + Lua 下载限流、MySQL 条件唯一约束与重复提交、Redis 下载统计幂等落库，并链接仓库，作为站外发现入口；不承诺收录时间或 Star 数量。

修改后可使用 user:clear48 topic:spring-boot、user:clear48 实习 in:description、user:clear48 限流 in:readme 验证对应字段能否检索到仓库；出现搜索结果不等于热门关键词排名靠前。优先顺序为 About / Topics → 真实截图或演示 → 一篇有内容的技术文章。本轮只提供建议并按规则归档，未修改远程仓库设置或发布站外内容。

依据：GitHub 官方 https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories 与 https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/classifying-your-repository-with-topics 。

## 2026-09-19 | 项目名称调整与 GitHub 展示设置落地

### 问题 1

完成About 和 Topics  ，并把项目标题的智能搜索改为普通的搜索（删去智能两个字）

### 答案 1

已通过 GitHub API 更新仓库 About 简介，并重新读取验证：独立开发的 Java 后端实习项目｜校园资料共享与检索平台｜Spring Boot 3 + MyBatis + MySQL + Redis + Vue 3｜文件去重、审核事务、Lua 限流、幂等统计与面试复盘。

已设置并回读确认 12 个 Topics：java、spring-boot、mybatis、mysql、redis、redisson、vue3、jwt、file-upload、rate-limiting、backend、learning-project。

项目名称按“删去智能”统一为“校园资料共享与检索平台”。同步根 README、前端首页与说明、当前项目文档、Postman 集合描述及 Agent 配置中的项目名，并把面试复盘中依赖旧名称的现状说明调整为 MySQL 多条件检索、排序和热度统计。保留独立开发声明。历史问答不改写，未来智能检索规划与错误表述示例仍保留；此前日志中的旧项目名称只反映当时状态，当前以新名称为准。

验证：npm run test:unit -- src/views/HomeView.test.ts 为 2/2 通过；npm run build 通过，保留既有大于 500 kB 产物提示；旧完整项目名仅留在历史问答中；Agent TOML、Postman JSON 解析和 git diff --check 均通过。本轮仅修改展示文本与 GitHub 元数据，未改后端业务、API、SQL、Redis 行为或仓库英文地址，因此未重复运行后端测试。

## 2026-09-20 18:44:14 | 简历主要工作突出功能与问题解决

### 问题 1

这是简历上关于该项目的描述，将主要工作部分按以下要求整改后输出文本：重点说明项目实现了什么功能，解决了什么问题。

### 答案 1

- **文件上传与去重：** 实现文件上传、MD5 + 文件大小预检及重复文件秒传，使用 Redis 三态缓存减少无效查库，并由 MySQL 联合唯一索引兜底并发写入，避免相同文件重复存储和并发重复入库。
- **资料审核与状态一致性：** 实现待审核、通过、拒绝、下架的完整状态流转和审核留痕，通过旧状态条件更新、事务提交状态与审核流水，提交后失效详情缓存并同步榜单，解决并发审核覆盖、跨表不一致及脏缓存问题。
- **多条件检索与热门榜单：** 实现关键词、分类、课程、类型、标签等组合检索及日/周/月/总榜；对排序字段进行白名单校验，利用 Redis ZSet 维护热点候选并回查 MySQL 过滤、补齐和恢复顺序，保证只展示有效资料且榜单结果稳定。
- **下载安全与计数：** 实现用户/IP 滑动窗口限流、60 秒一次性下载票据和重复下载去重；下载量先在 Redis 聚合，再通过 Redisson 锁、Lua 原子批次隔离及幂等记录同步 MySQL，降低高频写库压力，避免恶意刷量和失败重试导致重复计数。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java`、`AuditServiceImpl.java`、`SearchServiceImpl.java`、`RankingServiceImpl.java`、`DownloadRateLimiterImpl.java`、`DownloadServiceImpl.java`、`DownloadDeltaSyncServiceImpl.java`
- 验证方式：对照当前 `dev` 分支源码与 `docs/CURRENT_STATUS.md`、`docs/05-redis-design.md`、模块开发文档进行静态核查；本轮未修改业务代码，未运行测试。

## 2026-09-20 18:54:03 | 精简简历主要工作描述

### 问题 1

适当缩减文本量，只保留必要信息。

### 答案 1

- **文件上传与去重：** 实现 MD5 + 文件大小预检和重复文件秒传，通过 Redis 缓存与 MySQL 唯一索引避免重复存储及并发重复入库。
- **审核与数据一致性：** 实现资料审核、拒绝、下架和操作留痕，通过事务及提交后缓存失效解决并发审核和脏缓存问题。
- **检索与榜单：** 实现多条件检索和多周期热门榜单，通过排序白名单、Redis ZSet 与 MySQL 有效性校验保证结果安全准确。
- **下载与计数：** 实现用户/IP 限流、一次性下载票据和去重计数，通过 Redis 聚合、幂等同步降低写库压力并防止重复计数。

### 关联信息

- 相关文件：`docs/TECHNICAL_QA_LOG.md`
- 验证方式：沿用上一轮已完成的源码与项目文档静态核查结论；本轮仅压缩简历表述，未修改业务代码，未运行测试。

## 2026-09-21 | 项目部署上线思路与技术栈

### 问题 1

我想将该项目部署上线，给出思路和相应的技术栈

### 答案 1

当前项目最适合先采用单机容器化部署：域名和 HTTPS 请求进入 Nginx；Nginx 托管 Vue 构建后的静态文件，对 `/api/v1` 反向代理到单实例 Spring Boot；MySQL 8、Redis 及上传文件分别使用持久卷。建议选用一台受支持 Linux LTS 云服务器，起步资源为 2 核 4 GB，并使用 Docker、Docker Compose、Nginx、Let's Encrypt、Java 17、MySQL 8 和 Redis 6/7。该方案成本和运维复杂度较低，符合当前个人展示项目的规模，也保留完整的前后端、缓存、数据库、文件上传和定时任务能力；现阶段没有必要引入 Kubernetes、微服务、消息队列或 Elasticsearch。

生产请求链路建议为：浏览器 → HTTPS/Nginx → Vue SPA 或 `/api/v1` → Spring Boot → MySQL/Redis/本地持久卷。前端生产环境不运行 Vite 开发服务器，而是执行 `npm ci`、单元测试和 `npm run build`，由 Nginx 提供 `frontend/dist`；由于使用 Vue History 路由，Nginx 必须配置 `try_files ... /index.html`。上传请求上限需与应用保持一致，Nginx `client_max_body_size` 至少为 60 MB。后端通过环境变量注入数据库、Redis、JWT 和上传目录配置，8080、3306、6379 均不直接暴露公网。

当前文件存储是上线架构的主要约束：文件写入本地磁盘，数据库保存规范化绝对路径。因此首版后端只部署一个实例，容器内上传路径固定为稳定位置并挂载持久卷；数据库与上传目录必须联合备份和恢复。Windows 旧数据迁往 Linux 时不能直接沿用数据库中的 Windows 路径。后续需要水平扩容时，应先实现 MinIO、OSS 或 S3 的 `FileStorageService`，再使用多后端实例、负载均衡、托管 MySQL 和高可用 Redis。

上线前必须完成：新增后端和前端 Dockerfile、Compose、Nginx 配置及生产环境配置；限制当前允许任意来源的 CORS；让 Nginx 覆盖并清洗转发 IP，避免客户端伪造 `X-Forwarded-For` 绕过下载限流；配置强随机 JWT 密钥、最小权限 MySQL 用户、Redis 密码与持久化；新库执行 `sql/init.sql`，旧库备份后依次演练三份迁移并核查旧下载同步批次；补充能探测 MySQL、Redis 的 readiness，现有 `/api/v1/health` 只代表应用进程存活；制定数据库、Redis、上传目录、日志、备份恢复和回滚方案。

推荐发布顺序为：准备域名和 Linux 主机 → 创建生产配置与持久卷 → 在数据库副本演练迁移 → 运行后端全量测试、打包及前端测试构建 → 构建镜像并启动 Compose → 配置 Nginx 与 HTTPS → 走通注册、登录、上传、审核、搜索、收藏、下载和排行榜 → 验证重启后数据不丢 → 演练联合备份恢复。正式方案可再增加 GitHub Actions、镜像扫描、Spring Boot Actuator、Prometheus/Grafana 和集中日志，但这些不是首版上线的前置条件。

### 关联信息

- 依据：`campus-resource-platform/pom.xml`、`application.yaml`、`FileStorageServiceImpl.java`、`WebMvcConfig.java`、`DownloadController.java`、`frontend/package.json`、`vite.config.ts`、`sql/init.sql`、`sql/migrations/` 与 `docs/07-project-runbook.md`。
- 当前仓库尚无 Dockerfile、Compose、Nginx 生产配置或 CI 工作流；本轮仅做只读架构分析并追加问答日志，未修改业务代码，未运行测试。
- 分支 `dev`，分析前 HEAD 为 `d2b99e89`，工作区初始干净并与 `origin/dev` 一致。

## 2026-09-21 | 推荐域名结构

### 问题 1

推荐域名结构

### 答案 1

首版推荐使用单一业务域名和同源 API 路径。例如个人已有主域名 `example.com` 时，项目使用 `campus.example.com`，前端地址为 `https://campus.example.com/`，接口继续使用 `https://campus.example.com/api/v1/**`，管理端继续使用 `https://campus.example.com/admin/**`。如果为项目单独购买域名，则使用 `https://example-project.com/` 作为唯一正式入口，并将 `www.example-project.com` 通过 301 重定向到主域名。

同源结构与当前前端 `VITE_API_BASE_URL=/api/v1`、Spring Boot `/api/v1` 接口前缀和 Nginx 反向代理方式完全匹配，不需要额外处理跨域、预检请求或多域名证书，也方便未来改用 HttpOnly Cookie。管理员页面只是同一 SPA 的路由，权限最终由后端控制，因此不需要单独建立 `admin.` 子域名。受保护文件也不应通过公开 `files.` 域名绕过现有 JWT 和一次性下载票据；接入私有对象存储后，才考虑 `files.example.com` 或 `download.example.com` 配合短期签名 URL。

环境建议明确隔离：生产环境使用 `campus.example.com`，预发布环境使用 `campus-staging.example.com`；两套环境各自使用 `/api/v1`，并使用独立数据库、Redis、JWT 密钥和上传目录。开发环境继续使用 `localhost`，不要把 `dev.` 指向生产数据。监控、数据库、Redis 和管理工具不建立公网业务域名，优先仅通过内网、VPN或 SSH 隧道访问。

DNS 最小配置为给业务域名创建指向云服务器公网 IP 的 A 记录；使用独立项目域名时，再给 `www` 创建 CNAME 并重定向到主域名。TLS 证书覆盖实际对外入口和预发布域名。域名命名应简短、易拼写、能体现 campus/resource/share 等业务含义，避免把 `java`、`springboot`、端口号或具体部署技术写进域名。

只有在未来需要第三方开放 API、移动端独立网关或前后端由不同团队和基础设施独立发布时，才建议拆分为 `app.example.com` 与 `api.example.com`；届时需要重新配置生产 CORS、可信来源、TLS、限流和 API 网关策略。

### 关联信息

- 依据：`frontend/.env.example`、`frontend/src/utils/request.ts`、`frontend/src/router/index.ts` 及后端各 Controller 的 `/api/v1` 路径。
- 本轮仅追加问答日志，不修改前端、后端、接口、数据库、Redis 或部署配置；工作区原有前端未提交修改保持不变。

## 2026-09-21 | 部署所需域名数量

### 问题 1

总共需要购买几个域名

### 答案 1

当前部署方案总共只需要购买 1 个根域名。如果已经拥有可用的个人根域名，则不需要额外购买，可以免费创建 `campus.example.com` 子域名；如果没有域名，就购买一个项目根域名，例如 `example-project.com`。

主站使用根域名或 `campus` 子域名，API 复用同一域名下的 `/api/v1`，管理端复用 `/admin` 路径，预发布环境使用免费的 `campus-staging` 子域名。`www`、`campus`、`campus-staging` 等子域名都属于同一个根域名，不是需要再次付费购买的新域名。TLS 证书通常也可通过 Let's Encrypt 免费签发。

因此：已有个人域名时需要新购 0 个；没有域名时需要新购 1 个。当前没有必要分别购买前端域名、API 域名、管理端域名和文件域名。

### 关联信息

- 本轮结论沿用上一条已核对的同源部署结构，不修改代码或配置。
- 工作区原有前端未提交修改保持不变。

## 2026-09-21 | 前端 UI 与布局优化

### 问题 1

当前前端展示页面过于简陋，参考网络上已有模板，优化前端页面的UI，布局

### 答案 1

已在不新增依赖、不改变后端接口和业务链路的前提下，将现有 Vue 3 + Element Plus 前端升级为统一的校园知识门户视觉：前台使用三段式吸顶导航，管理端使用桌面侧栏与移动端横向菜单，首页增加品牌 Hero、可回车提交的关键词搜索、上传/浏览入口和分层热门榜单，登录与注册页改为品牌说明区 + 表单卡双栏布局。全局建立了品牌色、圆角、阴影、表格/表单、焦点态、窄屏滚动和减弱动效规则。

同时补齐了 UI 改造暴露的交互与可访问性问题：首页搜索会清理关键词并通过 SPA 跳转；游客从受保护入口登录后可返回原站内路径，且 `redirect` 只接受安全的单斜杠站内地址；页面语言改为 `zh-CN`；榜单周期向辅助技术暴露选中状态；320px 管理员态导航不再横向溢出。路由、API、JWT、角色判断、数据库和 Redis 均未改变。

验证结果为：5 个 UI 专项测试文件 18/18、前端全量 31 个测试文件 76/76、`npm run build` 和 `git diff --check` 均通过；真实浏览器桌面首屏与 375/320 设备指标宽度检查通过。构建仍有既存的主包约 844.05 kB 超过 500 kB 警告，本次未扩展为拆包任务。

### 关联信息

- 相关文件：`frontend/index.html`、`frontend/src/styles/index.css`、`frontend/src/layouts/DefaultLayout.vue`、`frontend/src/layouts/AdminLayout.vue`、`frontend/src/views/HomeView.vue`、`frontend/src/views/LoginView.vue`、`frontend/src/views/RegisterView.vue` 及对应测试。
- 文档更新：`docs/frontend/02-page-design.md`、`docs/CURRENT_STATUS.md`、`docs/BRANCH_HANDOFF.md`。
- 验证方式：UI 专项测试、前端全量测试、Vite 生产构建、差异空白检查与真实浏览器响应式检查。

## 2026-09-21 10:22:20 | Vite 开发代理连接后端被拒绝

### 问题 1

解释以下本地启动输出的原因：PowerShell 配置加载耗时，`npm ci` 出现 `glob@10.5.0` 弃用警告和 5 个漏洞，Vite 启动后多次请求排行榜接口时报 `connect ECONNREFUSED 127.0.0.1:8080`。

### 答案 1

前端安装和 Vite 启动均已成功，页面可由 `http://localhost:5173/` 访问。真正的运行错误发生在 API 代理链路：`frontend/.env.development` 将 `/api/v1` 请求交给 Vite，再由 `frontend/vite.config.ts` 转发至 `http://127.0.0.1:8080`；核查时 8080 端口没有监听进程，因此 TCP 建连阶段立即返回 `ECONNREFUSED`。这不是接口路径、Controller、Redis 或浏览器跨域错误，当前应先启动 Spring Boot 后端，或在后端使用其他端口时修改 `VITE_API_PROXY_TARGET` 并重启 Vite。后端默认端口由 `application.yaml` 的 `${SERVER_PORT:8080}` 决定，启动后可用 `Invoke-RestMethod http://127.0.0.1:8080/api/v1/health` 验证。

首页 `HomeView.vue` 在 `onMounted` 时各请求一次热门资料和热门搜索词；切换榜单周期及点击错误提示中的“重新加载”也会再次发起对应请求。Axios 请求封装没有自动重试，因此日志中的多次报错代表页面重新挂载或用户触发了再次加载，不是 Vite 自动重试；仅凭日志不能唯一确定每一次对应的界面操作。

`npm ci` 依据 `package-lock.json` 完成了可复现安装，弃用告警不会让安装失败。`glob@10.5.0` 来自开发测试依赖链 `@vue/test-utils -> js-beautify -> glob`，不是项目直接依赖。`npm audit` 报告的 5 项为当前锁文件中的依赖或传递依赖问题：Vitest/@vitest/mocker、brace-expansion、PostCSS、nanoid；它们与 8080 连接失败无关，但仍应另开依赖维护任务，在升级后运行单元测试和生产构建验证，不宜在不了解锁文件变化时直接盲目执行修复。PowerShell 的 4440 毫秒表示个人/系统配置脚本启动耗时，`(base)` 表明 Conda 基础环境被自动激活，这不是本次 Vite 报错；Vite 的首次依赖优化 `bundling dependencies` 也是正常行为。

### 关联信息

- 相关文件：`frontend/.env.development`、`frontend/vite.config.ts`、`frontend/src/utils/request.ts`、`frontend/src/views/HomeView.vue`、`frontend/package-lock.json`、`campus-resource-platform/src/main/resources/application.yaml`、`docs/07-project-runbook.md`
- 验证方式：检查 Git 分支与工作区；执行 `Get-NetTCPConnection -LocalPort 8080 -State Listen`，结果无监听；执行 `npm ls glob --all`、`npm audit --json` 和相关依赖树检查；静态核对代理、请求层、首页加载逻辑和后端端口配置；未启动服务，未运行测试

## 2026-09-21 10:27:14 | 后端因 JWT 密钥缺失启动失败

### 问题 1

解释执行 `.\mvnw.cmd spring-boot:run` 后，Spring Boot 创建 `jwtUtils` 失败、抛出 `JWT secret must be configured`，但 Maven 末尾仍显示 `BUILD SUCCESS` 的原因及处理方式。

### 答案 1

本次后端启动失败的直接原因是当前 PowerShell 会话没有为应用提供 `JWT_SECRET`。`application.yaml` 将 `app.jwt.secret` 绑定为 `${JWT_SECRET:}`，缺失时得到空字符串；`JwtUtils` 构造方法会在密钥为空、使用已公开的历史默认值或 UTF-8 长度少于 32 字节时主动抛出异常，防止应用使用不安全密钥运行。因此 Tomcat 虽已初始化到 8080，但 Spring ApplicationContext 随后取消刷新并停止 Tomcat，核查时 8080 仍无监听，前端代理会继续收到 `ECONNREFUSED`。

应在启动后端的同一个 PowerShell 窗口生成仅供本地会话使用的随机密钥并写入 `$env:JWT_SECRET`，同时按运行手册配置 MySQL、Redis 和上传目录变量，然后重新运行 `.\mvnw.cmd spring-boot:run`。推荐使用 48 个随机字节再转 Base64，满足项目至少 32 个 UTF-8 字节的要求；该环境变量只在当前 PowerShell 进程及其子进程中有效，不应写入仓库。更换密钥会使此前签发的 Token 失效。启动后应分别访问后端 `http://127.0.0.1:8080/api/v1/health` 和前端代理 `http://127.0.0.1:5173/api/v1/health` 验证链路。修复密钥后若 MySQL 或 Redis 不可用，应用或具体业务请求可能继续暴露下一层连接错误。

日志末尾的 `BUILD SUCCESS` 只说明 Maven 生命周期及 `spring-boot:run` 外层任务正常结束，并不能证明 Spring ApplicationContext 启动成功或服务仍在监听。当前启用了 Spring Boot DevTools，实际应用运行在 `restartedMain`；其启动异常已经由日志明确记录，判断成功应以出现 `Started CampusResourcePlatformApplication`、终端保持运行、8080 存在监听并且健康检查成功为准。`No active profile set`、`Found 0 Redis repository interfaces`、`Nothing to compile` 都不是本次失败原因。

附件中应用由 Java 24.0.2 启动，而项目 `pom.xml` 声明的基线是 Java 17；这不是本次 JWT 异常的直接原因，但为保持开发与部署一致，建议使用项目约定的 JDK 17。核查终端当前 `java -version` 为 Java 21，说明不同 PowerShell 会话可能加载了不同 `JAVA_HOME` 或 PATH，应在实际启动窗口中再次检查。

### 关联信息

- 相关文件：`campus-resource-platform/src/main/java/com/john/campus/common/JwtUtils.java`、`campus-resource-platform/src/main/resources/application.yaml`、`campus-resource-platform/pom.xml`、`README.md`、`docs/07-project-runbook.md`
- 验证方式：静态核对 JWT 构造校验、配置绑定、Java 版本声明与启动手册；检查 8080 端口，结果无监听；未修改业务代码，未实际注入用户环境变量或启动服务

## 2026-09-21 10:29:46 | 写入用户级 JWT 密钥

### 问题 1

将至少 32 字节的随机 `JWT_SECRET` 写入用户环境变量。

### 答案 1

已使用系统加密安全随机数生成器生成 48 个随机字节，并转为 Base64 字符串写入 Windows 当前用户作用域的 `JWT_SECRET`。写入后重新从用户环境读取并验证：变量存在，UTF-8 长度为 64 字节，满足项目至少 32 字节的要求。整个过程未输出或写入日志真实密钥，日志仅记录脱敏后的作用域和长度。

用户级环境变量会被之后启动的新进程读取；已打开的 PowerShell 不会自动刷新环境块，需要重新打开终端，或在当前窗口执行 `$env:JWT_SECRET = [Environment]::GetEnvironmentVariable('JWT_SECRET', 'User')` 后再启动后端。替换 JWT 密钥会使旧 Token 无法继续通过验签。

### 关联信息

- 影响范围：Windows 当前用户环境变量 `JWT_SECRET`；未修改前端、后端源代码或仓库配置
- 验证方式：通过 .NET `Environment.GetEnvironmentVariable` 从 `User` 作用域回读，仅验证存在性、UTF-8 字节长度和最小长度条件，不输出密钥内容

## 2026-09-21 | 阿里云域名购买后的上线步骤

### 问题 1

我已经购买了阿里云的域名 campusshare.online，接下来该怎么做

### 答案 1

先在阿里云域名控制台确认 `campusshare.online` 已完成域名持有者实名认证并且状态正常。当前 DNS 实测显示域名已经使用阿里云 `dns21.hichina.com`、`dns22.hichina.com` 权威 DNS，但根域名尚无 A 记录；这是尚未绑定服务器时的正常状态，不应随意填写临时 IP。

下一步必须先选择服务器地域。若目标是尽快上线用于简历展示，可选择阿里云中国香港或其他非中国内地节点，通常无需先完成 ICP 备案；若主要面向中国内地用户并准备长期运行，应购买满足备案条件的中国内地 ECS，先在阿里云完成 ICP 备案，网站对外开通后再按要求办理公安联网备案。正式购买前还应在阿里云备案工具中检测 `.online` 后缀、域名实名主体和服务器是否符合备案要求，不能仅凭域名已经注册成功推断一定可备案。

服务器建议从受支持的 Linux LTS、2 核 4 GB、固定公网 IPv4 和至少 40 GB 云盘起步。安全组只向公网开放 80/443；22 仅允许自己的固定 IP；8080、3306、6379 不开放公网。当前项目推荐在一台服务器中用 Docker Compose 运行 Nginx、单实例 Spring Boot、MySQL 和 Redis，并为 MySQL、Redis 与上传文件配置独立持久卷。

取得服务器公网 IP 后，在阿里云云解析 DNS 添加：根域名 A 记录，主机记录 `@` 指向公网 IPv4；`www` 可添加 CNAME 指向 `campusshare.online`，并在 Nginx 中 301 跳转到根域名。API 继续使用 `https://campusshare.online/api/v1/**`，管理端使用 `/admin/**`，不需要 `api` 子域名。解析生效后再签发并安装覆盖 `campusshare.online` 与实际使用别名的 HTTPS 证书，配置 HTTP 跳转 HTTPS。

域名解析前还需为仓库补齐后端和前端 Dockerfile、`docker-compose.yml`、Nginx 配置、生产环境变量、持久卷、CORS 与可信代理配置、MySQL/Redis readiness、备份与回滚方案。完成全量测试和数据库迁移演练后再启动容器、配置 DNS 和 HTTPS，最后走通注册、登录、上传、审核、搜索、收藏、下载和排行榜，并验证重启及联合备份恢复。

### 关联信息

- 阿里云官方依据：域名实名认证、公网 DNS A 记录、个人网站 ICP 备案、ECS 安全组及 Nginx SSL 证书部署文档。
- 当前仓库仍没有 Dockerfile、Compose、Nginx 生产配置或 CI 工作流；本轮仅提供方案并追加问答日志，不修改业务代码。

## 2026-09-21 | 生成部署上线交接文档

### 问题 1

我现在的进度：已经确认实名认证还没有购买中国香港服务器。将目前为止对话中关于项目部署上线的工作整理为一份 md 文档，方便后续新对话快速了解当前进度、主要任务。

### 答案 1

已新增 `docs/DEPLOYMENT_HANDOFF.md` 作为部署上线的固定交接入口。文档明确记录最终域名为 `campusshare.online`、阿里云域名实名认证已完成、权威 DNS 已接入但根域名尚无 A 记录、中国香港服务器尚未购买，以及 `campusshare.click` 已作废。

文档基于当前真实代码整理首版单机架构、技术栈、本地绝对路径文件存储约束、Redis 安全依赖、健康检查局限、服务器规格、安全组端口、生产环境变量和停止条件，并将后续工作拆分为 DEPLOY-01 至 DEPLOY-08：购买服务器、补齐容器化资产、收敛生产安全配置、本地测试与迁移演练、初始化服务器、配置 DNS、配置 HTTPS、公网验收和备份恢复。

文档还提供了新对话可直接复制的提示词、项目内关键文件入口与阿里云官方资料链接。当前建议只先完成用户侧 DEPLOY-01；取得固定公网 IPv4 后，新对话从 DEPLOY-02 的 Docker、Compose 和 Nginx 部署资产开始，不在部署资产验证前修改域名 A 记录。

### 关联信息

- 修改文件：`docs/DEPLOYMENT_HANDOFF.md`、`docs/TECHNICAL_QA_LOG.md`。
- 验证：13 个本地 Markdown 链接全部存在；22 个代码围栏配对；敏感信息模式未命中；`git diff --check` 通过。
- 本轮未修改接口、数据库、Redis、前端或后端业务代码，因此未运行 Maven 或 npm 测试。

## 2026-09-21 | 2 GB 服务器内存可行性评估

### 问题 1

当前项目服务器 2G 内存是否满足

### 答案 1

如果 Nginx、Spring Boot、MySQL、Redis 和 Docker 全部部署在同一台服务器，2 GB 只能在访问量很低、严格限制各组件内存并且不在服务器上编译的条件下勉强运行，不能视为稳定满足。当前项目还包含 Redisson、定时任务、文件上传与下载、缓存和数据库连接，`application.yaml` 也没有显式配置 JVM、Hikari 连接池或数据库内存上限，直接使用默认配置存在内存抖动、Swap、容器重启或 Linux OOM Kill 风险。

粗略预算为：系统和 Docker/Nginx 约 350～600 MB；Spring Boot 进程即使把 Java 堆限制在 512 MB，连同 Metaspace、线程栈、直接内存和本地库，实际 RSS 仍可能达到约 650～900 MB；MySQL 在收紧缓冲池和连接数后约 300～500 MB；Redis 及其数据约 80～200 MB。总量已经接近或超过 2 GB，缺少突发流量、文件处理、备份和系统页缓存所需余量。

当前推荐保持 2 核 4 GB。若预算只能购买 2 GB，应把它限定为低流量演示环境，并同时满足：镜像和前端产物在本地或 CI 构建；JVM 从 `-Xms128m -Xmx512m` 起步并监控实际 RSS；限制 Hikari 最大连接数；将 MySQL `innodb_buffer_pool_size` 从约 256 MB 起步并限制连接数；限制 Redis `maxmemory`，但由于 Redis 承载 JWT 黑名单、下载票据和计数等安全/业务数据，淘汰策略优先使用 `noeviction` 并监控写入失败，不能简单采用 `allkeys-lru`；不在同机运行 Prometheus、Grafana、Jenkins 等组件；配置 1～2 GB Swap 作为故障缓冲但不把它当作可用内存；上线后持续观察容器峰值、Swap、OOM 和磁盘 IO。

如果 MySQL 和 Redis 改用独立托管服务，2 GB 只运行 Nginx 与受限 JVM 后端则基本可用。若服务器还要执行 Maven/npm 构建、数据库备份压缩或监控服务，建议至少 4 GB，条件允许时选择 8 GB。对当前一体化 Docker Compose 方案，2 核 4 GB 是更合理的最低起步配置。

### 关联信息

- 依据：`campus-resource-platform/pom.xml`、`application.yaml` 和 `docs/DEPLOYMENT_HANDOFF.md`；当前应用使用 Java 17、Spring Boot、MySQL、Redis、Redisson及定时任务，且尚无生产内存限额配置。
- 本轮为静态资源预算评估，未在目标云服务器上进行真实压测或峰值测量；最终容量应以上线后的监控数据为准。
- 本轮未修改业务代码或部署配置，未运行 Maven/npm 测试。

## 2026-09-22 | 腾讯云中国香港服务器购买进度更新

### 问题 1

用户提供已购买的腾讯云服务器信息，并要求结合当前部署任务继续推进。截图包含实例名称、运行状态、CPU、内存、系统盘、公网 IPv4 和到期时间。

### 答案 1

截图已核对：腾讯云中国香港服务器当前处于运行中，规格为 2 核 CPU、2 GB 内存和 40 GB 系统盘，已分配公网 IPv4，到期时间为 2026-10-21 16:21:21。出于基础设施安全考虑，仓库与问答日志不记录具体公网 IP、登录密码、SSH 私钥或控制台凭据。

已更新 `docs/DEPLOYMENT_HANDOFF.md`：将 DEPLOY-01 标记为完成，把云厂商从原计划的阿里云实例调整为实际购买的腾讯云实例，并将当前主任务推进到 DEPLOY-02。域名继续由阿里云管理不会影响跨云部署；待本地部署资产验证通过后，只需在阿里云 DNS 中把 `campusshare.online` 的 A 记录指向腾讯云公网 IPv4。

2 GB 内存低于原建议的 4 GB，因此交接文档增加了资源受限要求：服务器不执行 Maven/npm/镜像构建，JVM 从 512 MB 最大堆起步，限制 Hikari 连接数、MySQL Buffer Pool 和 Redis 内存，不部署额外监控或 CI 服务，配置 Swap 仅作为异常缓冲，并在上线后根据峰值内存、Swap 和 OOM 数据决定是否升级。

### 关联信息

- 修改文件：`docs/DEPLOYMENT_HANDOFF.md`、`docs/TECHNICAL_QA_LOG.md`。
- 下一任务：DEPLOY-02，补齐 Docker、Docker Compose、Nginx 和生产环境变量示例，并针对 2 GB 内存配置资源限制。
- 本轮未修改前后端业务代码、接口、数据库结构或 Redis 业务设计，因此未运行 Maven/npm 测试。
