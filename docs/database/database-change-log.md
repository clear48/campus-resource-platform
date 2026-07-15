# 数据库变更记录

## 2026-07-15 用户文件引用授权隔离

### 变更结论

新增 `user_file_authorization` 表和可重复执行迁移 `sql/migrations/20260715_user_file_authorization.sql`。文件预检仅对已授权用户返回 `fileId`；真实上传命中物理去重时，在同一事务内增加引用次数并授权当前用户；创建资料时再次校验授权关系。

### 迁移与兼容性

- 迁移仅自动回填 `file_info.uploader_id` 可直接证明的首次上传关系。
- 迁移末尾查询会列出 `resource.uploader_id <> file_info.uploader_id` 的历史跨用户引用，需上线前人工核查；不会自动把潜在历史越权转为可信授权。
- 既有资料读取不依赖新授权表，不会被迁移删除或下架；但未授权用户后续再次引用同一 `fileId` 时必须真实上传内容。
- 部署顺序必须先执行迁移，再发布依赖该表的后端版本；回滚应用前可保留新表，不影响旧版本。

## 2026-07-14 下载增量同步幂等化

### 变更结论

新增 `download_delta_sync_item` 表及可重复执行迁移 `sql/migrations/20260714_download_delta_sync_idempotency.sql`；不修改既有 `resource`、`download_record` 或任何接口字段。

### 变更原因

原同步流程在 MySQL 事务已提交、Redis `HDEL` 未执行或响应失败时会重试同一 Hash 字段，可能重复累计 `resource.download_count`。新表以 `batch_id + resource_id` 唯一键记录已成功落库的 UUID 批次资料增量；该记录与 `resource.download_count` 原子累加在同一事务中提交，重试命中唯一键后只确认 Redis，不再重复累加。

### 表、索引与迁移

| 项目 | 内容 |
| --- | --- |
| 新表 | `download_delta_sync_item` |
| 主键 | `id` 自增主键 |
| 业务唯一键 | `uk_download_delta_sync_batch_resource (batch_id, resource_id)` |
| 清理索引 | `idx_download_delta_sync_confirmed_created (confirmed_at, created_at)` |
| 初始化 SQL | `sql/init.sql` |
| 存量库迁移 | `sql/migrations/20260714_download_delta_sync_idempotency.sql`，可重复执行 |

### 兼容性

- HTTP API、JWT 权限、前端请求、Redis 下载增量 Key 与 `resource.download_count` 字段均不变。
- Redis 新增 current 指针 Key，实际 Hash 使用 UUID 批次；升级前遗留的 `syncing:active` Hash 因缺少历史幂等记录而不能自动重试。发布前必须排空该 Key，或人工核对后处理；当前版本会保留并记录错误，避免重复累计不确定历史数据。
- 切换期间必须停止旧版本下载同步调度器，确认旧 `syncing:active` 已处理后再启用新版本调度器；旧、新批次协议不可并行运行。
- 幂等明细本次保留，不在同步成功后立即删除；后续可按 `confirmed_at` 单独设计保留期清理任务。

## 2026-07-12 排行榜与定时任务模块

### 变更结论

本模块没有新增生产数据库表、字段或索引，复用 `sql/init.sql` 中已设计的 `resource` 表及其 `idx_resource_hot` 索引。

### 使用到的已有表与字段

| 表/字段 | 使用方式 |
| --- | --- |
| `resource.download_count` | 接收 Redis 下载增量的 MySQL 原子累加结果，并作为 all 总榜重建的权重输入 |
| `resource.favorite_count` | 作为 all 总榜重建的权重输入 |
| `resource.view_count` | 作为 all 总榜重建的权重输入；当前未实现新的浏览计数写入 |
| `resource.hot_score` | 定时从 Redis all 总榜分批回写，供搜索排序和 Redis 故障降级 |
| `resource.status` | 游标重建查询及热度快照更新均固定过滤 `status = 1`，避免下架或删除资料被写回公开快照 |

### 使用到的已有索引

| 索引 | 使用场景 |
| --- | --- |
| `idx_resource_hot (status, hot_score, download_count)` | Redis 不可用时按 MySQL 热度快照查询热门资料 |
| 主键 `id` | 总榜重建通过 `id > lastResourceId ORDER BY id` 游标分页，避免 OFFSET 大分页扫描 |

### 备注

- 不新增排行榜快照表；Redis `crp:rank:resource:hot:all` 是实时总榜，MySQL `hot_score` 是可降级快照。
- all 榜重建只处理审核通过资料，首版公式为 `download_count * 5 + favorite_count * 3 + view_count * 1`。
- 本次无生产库迁移 SQL；新增的 Mapper SQL 均复用既有 `resource` 字段。

## 2026-07-07 审核模块

### 变更结论

本模块没有新增生产数据库表、字段或索引，复用 `sql/init.sql` 中已设计的 `resource` 和 `audit_record` 表。

### 使用到的已有表

| 表名 | 使用方式 |
| --- | --- |
| `resource` | 查询待审核资料、校验资料存在性、执行审核通过/拒绝/下架状态流转 |
| `audit_record` | 每次审核通过、拒绝、下架后追加审核记录，支持按资料 ID 查询审核历史 |
| `user` | 不直接查询用户表；管理员身份来自 JWT 解析后的 `LoginUser.role = 2` |

### 使用到的已有索引

| 索引 | 使用场景 |
| --- | --- |
| `idx_resource_status_created` | 支撑待审核列表按 `status = 0` 和创建时间倒序分页 |
| `idx_resource_uploader_status` | 支撑按上传者筛选待审核资料 |
| `idx_audit_resource_created` | 支撑按资料 ID 查询审核记录并按时间倒序返回 |
| `idx_audit_auditor_created` | 后续可支撑按管理员维度统计审核记录 |
| `idx_audit_action_created` | 后续可支撑按审核动作类型统计 |

### 测试用数据库结构

审核模块复用 `campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql` 初始化测试表结构。当前 `AuditServiceDatabaseIntegrationTest` 使用本机 MySQL 独立测试库 `campus_resource_platform_audit_test` 执行真实 MyBatis XML，不属于生产数据库结构变更。

### 备注

- `ResourceMapper.selectPendingReviews` / `countPendingReviews` 固定过滤 `status = 0`，支持课程名、资料类型和上传者筛选。
- `ResourceMapper.approvePendingReview`、`rejectPendingReview`、`offlineApprovedResource` 均带旧状态条件，通过影响行数识别重复审核或非法状态流转。
- `AuditRecordMapper.insert` 写入审核流水并回填自增 ID。
- 审核通过、拒绝、下架在 Service 层使用 `@Transactional(rollbackFor = Exception.class)` 保证 `resource` 状态更新和 `audit_record` 写入一致。

## 2026-07-06 资料模块

### 变更结论

本模块没有新增生产数据库表、字段或索引，复用 `sql/init.sql` 中已设计的 `resource`、`file_info`、`category` 表。

### 使用到的已有表

| 表名 | 使用方式 |
| --- | --- |
| `resource` | 创建待审核资料、公开详情查询、我的上传分页查询、重复提交计数 |
| `file_info` | 创建资料时按 `fileId` 校验文件是否存在且 `status = 1` |
| `category` | 创建资料时按 `categoryId` 校验分类是否存在且 `status = 1`，详情中补充分类名称 |

### 使用到的已有索引

| 索引 | 使用场景 |
| --- | --- |
| `idx_resource_uploader_status` | 支撑 `uploader_id` + 可选 `status` 的我的上传列表和总数统计 |
| `idx_resource_status_created` | 支撑审核通过资料的公开可见性查询方向 |
| `idx_resource_file` | 支撑按 `file_id` 关联文件和重复提交排查 |
| `idx_category_parent_status` | 分类模块继续用于公开分类列表；资料模块新增按主键查询启用分类 |
| `uk_file_md5_size` | 文件上传模块继续用于物理文件去重；资料模块通过 `file_info.id` 引用文件 |

### 测试用数据库结构

本模块新增 `campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql`，仅用于 H2 MySQL 模式下的自动化集成测试，不属于生产数据库结构变更。

### 备注

- `ResourceMapper.insert` 写入 `resource` 并回填自增 ID。
- `ResourceMapper.selectByUploader` / `countByUploader` 用于我的上传分页。
- `ResourceMapper.countActiveByUploaderAndFileId` 只统计待审核和已通过资料，防止同一用户重复提交同一文件。
- `CategoryMapper.selectEnabledById` 和 `FileInfoMapper.selectNormalById` 是资料创建流程新增的校验查询。
- 当前资料模块不修改 `file_info.ref_count`，物理文件复用仍由文件上传模块负责。

## 2026-07-05 文件上传模块

### 变更结论

本模块没有新增数据库表、字段或索引。

### 使用到的已有表

| 表名 | 使用方式 |
| --- | --- |
| `file_info` | 保存物理文件元数据，支持 MD5 去重、秒传、本地存储路径记录和引用次数自增 |

### 使用到的已有索引

| 索引 | 使用场景 |
| --- | --- |
| `uk_file_md5_size` | 按 `file_md5 + file_size` 判断是否已存在相同物理文件，支撑秒传和并发兜底 |
| `idx_file_uploader_created` | 后续可支撑按上传用户查询文件记录 |

### 备注

- 当前文件上传模块复用了 `sql/init.sql` 中已设计的 `file_info` 表结构。
- 文件上传模块只写入物理文件信息，不创建 `resource` 资料记录。
- `resource` 表已在资料模块中使用，文件上传模块仍只负责生成可引用的 `fileId`，详见 `docs/modules/04-resource-development-process.md`。

## 2026-07-05 分类查询模块

### 变更结论

本模块没有新增数据库表、字段或索引。

### 使用到的已有表

| 表名 | 使用方式 |
| --- | --- |
| `category` | 按 `parent_id` 查询启用分类，用于资料上传前选择分类 |

### 使用到的已有索引

| 索引 | 使用场景 |
| --- | --- |
| `idx_category_parent_status` | 支撑 `parent_id = ? AND status = 1 ORDER BY sort_order ASC, id ASC` 的分类列表查询 |

### 备注

- 当前分类查询模块复用了 `sql/init.sql` 中已设计的 `category` 表结构。
- 本模块只实现分类查询能力，不涉及资料上传、文件上传、审核或分类维护。
- 分类状态仍使用已有字段 `status`：`0` 表示禁用，`1` 表示启用。

## 2026-07-04 用户认证模块

### 变更结论

本模块没有新增数据库表、字段或索引。

### 使用到的已有表

| 表名 | 使用方式 |
| --- | --- |
| `user` | 注册插入用户、登录按用户名查询、按 ID 查询当前用户、更新 `last_login_at` |

### 使用到的已有索引

| 索引 | 使用场景 |
| --- | --- |
| `uk_user_username` | 注册重复校验、登录查询 |
| `uk_user_email` | 数据库层防止邮箱重复 |
| `uk_user_phone` | 数据库层防止手机号重复 |

### 备注

- 当前认证模块复用了 `sql/init.sql` 中已设计的 `user` 表结构。
- `UserMapper.updateLastLoginAt` 使用已有字段 `last_login_at`，不涉及结构变更。
- 如果后续增加登录审计、登录失败次数、账号锁定策略，可考虑新增 `login_record` 或在 `user` 表增加相关字段。
