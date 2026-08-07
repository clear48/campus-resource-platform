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
