# 校园资料共享与智能检索平台数据库设计

## 1. 设计目标

本文基于 `docs/01-requirements.md` 和 `docs/02-business-flow.md`，设计校园资料共享与智能检索平台的 MySQL 8.x 数据库表结构。

数据库设计重点服务以下业务能力：

- 用户登录、角色鉴权和账号状态控制。
- 文件上传、MD5 去重和文件信息复用。
- 资料审核状态流转，包括待审核、通过、拒绝、下架。
- 资料搜索、分类筛选、热门排序。
- 收藏幂等处理，防止重复收藏。
- 下载记录审计，以及 Redis 下载计数定时同步 MySQL。
- 审核记录留痕，便于后台追踪管理员操作。

完整建表 SQL 见 `sql/init.sql`。

## 2. 表结构总览

| 表名 | 说明 | 核心作用 |
| --- | --- | --- |
| `user` | 用户表 | 保存学生、管理员账号信息 |
| `category` | 分类表 | 保存课程资料分类，支持父子分类 |
| `file_info` | 文件信息表 | 保存物理文件信息、MD5、存储路径 |
| `user_file_authorization` | 用户文件授权表 | 保存用户经真实上传验证后可引用的物理文件关系 |
| `resource` | 资料表 | 保存资料业务信息和审核状态 |
| `favorite` | 收藏表 | 保存用户收藏关系，防止重复收藏 |
| `download_record` | 下载记录表 | 保存用户下载行为，辅助审计和统计 |
| `download_delta_sync_item` | 下载增量同步幂等明细表 | 用唯一批次/资料组合防止 Redis 重试重复累计下载量 |
| `audit_record` | 审核记录表 | 保存管理员审核、拒绝、下架操作记录 |

## 3. 关联关系设计

本项目默认采用“逻辑外键”为主，也就是在表中保留 `user_id`、`resource_id`、`file_id` 等关联字段，但不在 SQL 中强制创建物理外键约束。

这样设计的原因：

- 资料、收藏、下载记录属于高频业务表，物理外键会增加写入和删除约束成本。
- 项目存在软删除、下架、审核状态流转，很多业务不能简单依赖数据库级级联删除。
- 后端 Service 层需要统一做权限校验、状态机校验和审计日志，逻辑关联更贴近业务规则。
- 面试时可以说明：中小型教学项目可以使用物理外键保证完整性，线上高频系统常使用逻辑外键加业务层校验。

逻辑关联如下：

| 来源字段 | 关联目标 | 说明 |
| --- | --- | --- |
| `resource.uploader_id` | `user.id` | 资料上传者 |
| `resource.file_id` | `file_info.id` | 资料关联的物理文件 |
| `user_file_authorization.user_id` | `user.id` | 获得文件引用权限的用户 |
| `user_file_authorization.file_id` | `file_info.id` | 用户已验证上传内容对应的物理文件 |
| `resource.category_id` | `category.id` | 资料所属分类 |
| `favorite.user_id` | `user.id` | 收藏用户 |
| `favorite.resource_id` | `resource.id` | 被收藏资料 |
| `download_record.user_id` | `user.id` | 下载用户 |
| `download_record.resource_id` | `resource.id` | 被下载资料 |
| `download_record.file_id` | `file_info.id` | 被下载文件 |
| `audit_record.resource_id` | `resource.id` | 被审核资料 |
| `audit_record.auditor_id` | `user.id` | 审核管理员 |

## 4. 用户表 `user`

### 4.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 用户 ID |
| `username` | VARCHAR(50) | 非空，唯一 | 登录账号，可使用学号或工号 |
| `password_hash` | VARCHAR(255) | 非空 | 加密后的密码 |
| `nickname` | VARCHAR(50) | 非空 | 昵称或姓名 |
| `email` | VARCHAR(100) | 唯一，可空 | 邮箱 |
| `phone` | VARCHAR(20) | 唯一，可空 | 手机号 |
| `role` | TINYINT | 非空 | 角色：1 学生，2 管理员 |
| `status` | TINYINT | 非空 | 状态：0 禁用，1 正常 |
| `avatar_url` | VARCHAR(500) | 可空 | 头像地址 |
| `last_login_at` | DATETIME | 可空 | 最近登录时间 |
| `created_at` | DATETIME | 非空 | 创建时间 |
| `updated_at` | DATETIME | 非空 | 更新时间 |

### 4.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识用户 |
| `uk_user_username` | 唯一索引 | `username` | 防止账号重复 |
| `uk_user_email` | 唯一索引 | `email` | 防止邮箱重复 |
| `uk_user_phone` | 唯一索引 | `phone` | 防止手机号重复 |
| `idx_user_role_status` | 普通索引 | `role`, `status` | 管理后台按角色和状态筛选 |

### 4.3 设计说明

- `password_hash` 不保存明文密码，体现安全设计。
- `role` 和 `status` 支持 JWT 鉴权和账号禁用。
- `last_login_at` 可以用于后台观察用户活跃情况。

## 5. 分类表 `category`

### 5.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 分类 ID |
| `parent_id` | BIGINT | 非空，默认 0 | 父分类 ID，0 表示一级分类 |
| `category_name` | VARCHAR(100) | 非空 | 分类名称 |
| `description` | VARCHAR(255) | 可空 | 分类说明 |
| `sort_order` | INT | 非空，默认 0 | 排序值，越小越靠前 |
| `status` | TINYINT | 非空 | 状态：0 禁用，1 启用 |
| `created_at` | DATETIME | 非空 | 创建时间 |
| `updated_at` | DATETIME | 非空 | 更新时间 |

### 5.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识分类 |
| `uk_category_parent_name` | 唯一索引 | `parent_id`, `category_name` | 防止同一父分类下名称重复 |
| `idx_category_parent_status` | 普通索引 | `parent_id`, `status`, `sort_order` | 查询分类树和启用分类 |

### 5.3 设计说明

- `parent_id` 支持课程大类和子类，例如“计算机类 -> 数据结构”。
- 分类表独立出来，避免在资料表中重复保存分类文本。

## 6. 文件信息表 `file_info`

### 6.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 文件 ID |
| `file_md5` | CHAR(32) | 非空 | 文件 MD5，用于文件去重 |
| `original_name` | VARCHAR(255) | 非空 | 用户上传时的原始文件名 |
| `stored_name` | VARCHAR(255) | 非空 | 存储系统中的文件名 |
| `file_ext` | VARCHAR(20) | 非空 | 文件扩展名 |
| `mime_type` | VARCHAR(100) | 可空 | 文件 MIME 类型 |
| `file_size` | BIGINT | 非空 | 文件大小，单位字节 |
| `storage_type` | TINYINT | 非空 | 存储类型：1 本地，2 MinIO，3 OSS |
| `storage_path` | VARCHAR(500) | 非空 | 文件存储路径或对象存储 Key |
| `uploader_id` | BIGINT | 非空 | 首次上传该文件的用户 ID |
| `ref_count` | INT | 非空 | 文件引用次数 |
| `status` | TINYINT | 非空 | 文件状态：1 正常，2 已删除 |
| `created_at` | DATETIME | 非空 | 创建时间 |
| `updated_at` | DATETIME | 非空 | 更新时间 |

### 6.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识文件 |
| `uk_file_md5_size` | 唯一索引 | `file_md5`, `file_size` | 判断重复文件，支持秒传和物理文件复用 |
| `idx_file_uploader_created` | 普通索引 | `uploader_id`, `created_at` | 查询用户上传过的文件 |

### 6.3 设计说明

- `file_md5` 是上传流程的核心字段，用于判断物理文件是否重复。
- 唯一索引使用 `file_md5 + file_size`，比单独 MD5 更稳妥。
- `storage_type` 和 `storage_path` 为后续从本地存储迁移到 MinIO、OSS 等对象存储服务预留扩展。
- `ref_count` 可以统计同一文件被多少条资料复用，是文件去重和存储优化的面试亮点。

### 6.3 用户文件授权表 `user_file_authorization`

该表把“全局物理文件已存在”和“当前用户可引用该文件”拆开。`uk_user_file_authorization (user_id, file_id)` 保证授权幂等；`source_type` 区分首次上传、实际内容去重和历史迁移。MD5 缓存只能定位候选文件，不能代替该表的用户级授权判断。

## 7. 资料表 `resource`

### 7.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 资料 ID |
| `title` | VARCHAR(150) | 非空 | 资料标题 |
| `description` | TEXT | 可空 | 资料简介 |
| `category_id` | BIGINT | 非空 | 分类 ID |
| `course_name` | VARCHAR(100) | 非空 | 课程名称 |
| `resource_type` | TINYINT | 非空 | 资料类型：1 课件，2 笔记，3 真题，4 实验报告，5 课程设计，99 其他 |
| `tags` | VARCHAR(255) | 可空 | 标签，初期可用逗号分隔 |
| `file_id` | BIGINT | 非空 | 文件 ID |
| `uploader_id` | BIGINT | 非空 | 上传用户 ID |
| `status` | TINYINT | 非空 | 审核状态：0 待审核，1 已通过，2 已拒绝，3 已下架，4 已删除 |
| `active_duplicate_guard` | TINYINT | 生成列，可空 | 状态为待审核/已通过时生成 1，其余状态生成 NULL，用于条件唯一约束 |
| `reject_reason` | VARCHAR(500) | 可空 | 最近一次拒绝原因 |
| `offline_reason` | VARCHAR(500) | 可空 | 最近一次下架原因 |
| `view_count` | BIGINT | 非空 | 浏览次数 |
| `download_count` | BIGINT | 非空 | 下载次数，Redis 定时同步 |
| `favorite_count` | BIGINT | 非空 | 收藏次数 |
| `hot_score` | DECIMAL(12,2) | 非空 | 热度分快照，Redis 排行榜可定时回写 |
| `approved_at` | DATETIME | 可空 | 审核通过时间 |
| `offline_at` | DATETIME | 可空 | 下架时间 |
| `created_at` | DATETIME | 非空 | 创建时间 |
| `updated_at` | DATETIME | 非空 | 更新时间 |

### 7.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识资料 |
| `uk_resource_active_duplicate` | 唯一索引 | `uploader_id`, `file_id`, `active_duplicate_guard` | 并发下防止同一用户对同一文件产生多条有效资料 |
| `idx_resource_status_created` | 普通索引 | `status`, `created_at` | 审核列表、公开资料列表 |
| `idx_resource_category_status` | 普通索引 | `category_id`, `status`, `created_at` | 按分类筛选已通过资料 |
| `idx_resource_uploader_status` | 普通索引 | `uploader_id`, `status`, `created_at` | 查询用户上传记录 |
| `idx_resource_course_status` | 普通索引 | `course_name`, `status` | 按课程筛选资料 |
| `idx_resource_file` | 普通索引 | `file_id` | 查询某文件关联的资料 |
| `idx_resource_hot` | 普通索引 | `status`, `hot_score`, `download_count` | MySQL 侧热门资料兜底排序 |

### 7.3 设计说明

- `status` 是资料审核状态机的核心字段，避免项目变成普通 CRUD。
- `active_duplicate_guard` 利用唯一索引允许多个 `NULL` 的语义，只约束待审核和已通过资料；拒绝、下架或删除后仍允许重新提交。
- Service 前置查重用于返回友好提示，`uk_resource_active_duplicate` 才是并发重复提交的最终兜底。
- `reject_reason` 和 `offline_reason` 保存最近原因，完整历史保存在 `audit_record`。
- `download_count`、`favorite_count`、`hot_score` 与 Redis 配合：Redis 负责实时统计和排行榜，MySQL 保存最终落库结果或快照。
- `idx_resource_status_created` 服务“待审核资料列表”和“公开资料列表”两个核心查询。

## 8. 收藏表 `favorite`

### 8.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 收藏记录 ID |
| `user_id` | BIGINT | 非空 | 收藏用户 ID |
| `resource_id` | BIGINT | 非空 | 被收藏资料 ID |
| `status` | TINYINT | 非空 | 收藏状态：0 已取消，1 已收藏 |
| `created_at` | DATETIME | 非空 | 首次收藏时间 |
| `updated_at` | DATETIME | 非空 | 更新时间 |

### 8.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识收藏记录 |
| `uk_favorite_user_resource` | 唯一索引 | `user_id`, `resource_id` | 防止重复收藏，保证收藏接口幂等 |
| `idx_favorite_user_status_created` | 普通索引 | `user_id`, `status`, `created_at` | 查询用户收藏列表 |
| `idx_favorite_resource_status` | 普通索引 | `resource_id`, `status` | 统计资料收藏人数 |

### 8.3 设计说明

- `uk_favorite_user_resource` 是必须的唯一索引，防止用户重复收藏同一资料。
- 取消收藏不一定物理删除，可将 `status` 改为 0；再次收藏时更新回 1。
- 这种设计支持接口幂等，也能避免重复点击导致收藏数错误。

## 9. 下载记录表 `download_record`

### 9.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 下载记录 ID |
| `user_id` | BIGINT | 非空 | 下载用户 ID |
| `resource_id` | BIGINT | 非空 | 被下载资料 ID |
| `file_id` | BIGINT | 非空 | 被下载文件 ID |
| `user_ip` | VARCHAR(45) | 可空 | 用户 IP，兼容 IPv4 和 IPv6 |
| `user_agent` | VARCHAR(255) | 可空 | 浏览器或客户端信息 |
| `download_status` | TINYINT | 非空 | 下载状态：1 成功，2 失败 |
| `fail_reason` | VARCHAR(255) | 可空 | 下载失败原因 |
| `created_at` | DATETIME | 非空 | 下载时间 |

### 9.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识下载记录 |
| `idx_download_user_created` | 普通索引 | `user_id`, `created_at` | 查询个人下载记录 |
| `idx_download_resource_created` | 普通索引 | `resource_id`, `created_at` | 查询资料下载趋势 |
| `idx_download_user_resource_created` | 普通索引 | `user_id`, `resource_id`, `created_at` | 判断短时间重复下载，辅助防刷 |

### 9.3 设计说明

- 下载记录表不直接承担实时下载量统计，实时计数由 Redis 处理。
- MySQL 中的下载记录用于用户历史、审计、防刷分析和后台报表。
- `user_ip` 使用 `VARCHAR(45)`，因为 IPv6 最长可到 45 个字符。

### 9.4 下载增量同步幂等明细表 `download_delta_sync_item`

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 幂等明细 ID |
| `batch_id` | CHAR(36) | 非空 | Redis 隔离批次 UUID；存量 `syncing:active` 使用 `legacy-active` |
| `resource_id` | BIGINT | 非空 | 被同步下载量的资料 ID |
| `delta` | BIGINT | 非空，`> 0` | 该批次资料增量 |
| `confirmed_at` | DATETIME | 可空 | Redis `HDEL` 成功确认时间 |
| `created_at` / `updated_at` | DATETIME | 非空 | 创建与最近更新时间 |

索引与约束：

- `uk_download_delta_sync_batch_resource (batch_id, resource_id)`：同一 Redis 批次的同一资料只能首次累加一次，是 MySQL 已提交而 Redis 确认失败时的幂等栅栏。
- `idx_download_delta_sync_confirmed_created (confirmed_at, created_at)`：为后续按确认状态和保留期清理历史幂等记录预留。
- 不建立物理外键：同步重试链路不能因资料已被逻辑删除或状态变化而增加跨表约束开销；资料不存在时由 Service 事务回滚并保留 Redis 批次处理。

## 10. 审核记录表 `audit_record`

### 10.1 字段说明

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 主键，自增 | 审核记录 ID |
| `resource_id` | BIGINT | 非空 | 被审核资料 ID |
| `auditor_id` | BIGINT | 非空 | 审核管理员 ID |
| `action_type` | TINYINT | 非空 | 操作类型：1 通过，2 拒绝，3 下架 |
| `before_status` | TINYINT | 非空 | 操作前资料状态 |
| `after_status` | TINYINT | 非空 | 操作后资料状态 |
| `audit_reason` | VARCHAR(500) | 可空 | 审核意见、拒绝原因或下架原因 |
| `created_at` | DATETIME | 非空 | 操作时间 |

### 10.2 索引设计

| 索引 | 类型 | 字段 | 作用 |
| --- | --- | --- | --- |
| `PRIMARY` | 主键 | `id` | 唯一标识审核记录 |
| `idx_audit_resource_created` | 普通索引 | `resource_id`, `created_at` | 查询某资料审核历史 |
| `idx_audit_auditor_created` | 普通索引 | `auditor_id`, `created_at` | 查询管理员审核记录 |
| `idx_audit_action_created` | 普通索引 | `action_type`, `created_at` | 按审核动作统计 |

### 10.3 设计说明

- 审核记录表用于保留状态流转历史，体现“审核流程”而不是普通更新字段。
- `before_status` 和 `after_status` 可以还原每次状态变化。
- `audit_reason` 对拒绝和下架必须填写，便于用户和管理员追踪原因。

## 11. 面试亮点字段说明

| 字段或索引 | 所在表 | 面试亮点 |
| --- | --- | --- |
| `file_md5` | `file_info` | 支持文件去重、秒传、避免重复存储 |
| `uk_file_md5_size` | `file_info` | 数据库层保证同一物理文件不重复入库 |
| `status` | `resource` | 支持待审核、通过、拒绝、下架的状态机流转 |
| `audit_record.before_status/after_status` | `audit_record` | 保留审核状态变化历史，便于审计 |
| `download_count` | `resource` | Redis 下载计数定时同步 MySQL 的落库字段 |
| `hot_score` | `resource` | Redis 热门排行榜快照字段，可做兜底排序 |
| `uk_favorite_user_resource` | `favorite` | 防止重复收藏，体现接口幂等设计 |
| `uk_download_delta_sync_batch_resource` | `download_delta_sync_item` | MySQL 下载同步幂等栅栏，防止确认失败重试重复累计 |
| `idx_download_user_resource_created` | `download_record` | 支持下载防刷、短时间重复下载判断 |
| `storage_type/storage_path` | `file_info` | 支持从本地文件迁移到对象存储 |

## 12. MySQL 知识点巩固

### 12.1 DDL 与建表语句

`CREATE TABLE` 属于 DDL，也就是数据定义语言，用于创建表结构、字段、索引和约束。

示例：

```sql
CREATE TABLE `category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `category_name` VARCHAR(100) NOT NULL COMMENT '分类名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类表';
```

### 12.2 主键

主键用于唯一标识一行数据。项目中所有业务表都使用 `id BIGINT AUTO_INCREMENT` 作为主键。

主键特点：

- 不能为空。
- 不能重复。
- InnoDB 会根据主键组织数据，主键也是聚簇索引。

### 12.3 唯一索引

唯一索引用于保证字段组合不能重复。

本项目的重要唯一索引包括：

- `user.username` 唯一，防止账号重复。
- `favorite.user_id + favorite.resource_id` 唯一，防止重复收藏。
- `resource.uploader_id + resource.file_id + active_duplicate_guard` 唯一，只限制同一用户、同一文件的待审核或已通过资料。

收藏唯一索引非常重要，因为即使前端重复点击、接口重试或并发请求，数据库也能兜底保证同一用户不会产生两条相同收藏关系。

### 12.4 普通索引

普通索引用于加速查询，不保证唯一性。

例如：

```sql
KEY `idx_resource_status_created` (`status`, `created_at`)
```

这个索引可以加速以下查询：

```sql
SELECT *
FROM `resource`
WHERE `status` = 0
ORDER BY `created_at` DESC;
```

它适合管理员查询待审核资料列表。

### 12.5 联合索引与最左前缀原则

联合索引由多个字段组成，例如：

```sql
KEY `idx_resource_category_status` (`category_id`, `status`, `created_at`)
```

它适合：

```sql
WHERE category_id = ? AND status = ?
```

也适合：

```sql
WHERE category_id = ?
```

但不适合只按 `status` 查询，因为 `status` 不是这个索引的最左字段。这就是最左前缀原则。

### 12.6 外键与逻辑外键

物理外键由数据库强制约束，例如 `resource.uploader_id` 必须存在于 `user.id`。

逻辑外键不创建数据库约束，只在业务代码中保证关联关系。本项目 SQL 采用逻辑外键，原因是高频写入表和状态流转较多，业务层更适合统一做权限、状态和审计控制。

面试表达可以这样说：

```text
我知道物理外键能保证强一致性，但这个项目中下载记录、收藏记录和资料审核都有较多业务规则。
所以我选择逻辑外键，通过 Service 层校验用户、资料和状态，并用索引保证查询性能。
```

### 12.7 TINYINT 状态字段

项目中大量使用 `TINYINT` 保存状态，例如：

- 用户状态：0 禁用，1 正常。
- 资料状态：0 待审核，1 通过，2 拒绝，3 下架，4 删除。
- 收藏状态：0 取消，1 收藏。

这样比直接存中文更节省空间，也更适合程序枚举映射。

### 12.8 DATETIME 自动时间

项目统一使用：

```sql
`created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
`updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
```

作用：

- `created_at` 自动记录插入时间。
- `updated_at` 在数据更新时自动刷新。

### 12.9 InnoDB 与 utf8mb4

本项目使用 InnoDB 引擎：

- 支持事务。
- 支持行级锁。
- 支持崩溃恢复。
- 适合大多数业务系统。

字符集使用 `utf8mb4`：

- 支持中文。
- 支持 emoji 等 4 字节字符。
- 比旧的 `utf8` 更完整。

### 12.10 Redis 统计与 MySQL 落库关系

下载次数属于高频写数据，不建议每次下载都直接更新 MySQL：

```sql
UPDATE resource SET download_count = download_count + 1 WHERE id = ?;
```

更合理的方式：

1. 下载成功后写 Redis Hash 增量。
2. Redis ZSet 更新热门排行榜。
3. 定时任务批量同步到 MySQL 的 `resource.download_count`。

这样既能体现 Redis 的真实使用场景，也能减少 MySQL 高频写压力。

## 13. 建表 SQL

完整建表脚本见：

```text
sql/init.sql
```

执行方式示例：

```bash
mysql -u root -p < sql/init.sql
```
