# 数据库变更记录

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
