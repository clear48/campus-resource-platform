# 审核模块开发流程文档

> 本文档遵循 `docs/AGENTS.md` 第 24 节《模块开发流程文档规范》生成。
> 当前状态：**首版已完成并通过测试**。资料模块能够产生 `resource.status = 0 PENDING_REVIEW` 的待审核资料；审核模块已基于这些资料实现管理员审核通过、审核拒绝、下架和审核记录能力。

---

## 1. 模块基本信息

| 项 | 内容 |
| --- | --- |
| 模块名称 | 审核模块 |
| 英文标识 | audit |
| 文档路径 | `docs/modules/05-audit-development-process.md` |
| 当前分支 | `dev` |
| 当前状态 | 首版已完成并通过测试 |
| 前置依赖模块 | 用户认证模块、资料模块 |
| 下游模块 | 搜索模块、下载模块、收藏模块、排行榜模块 |
| 接口前缀 | `/api/v1/admin/resources` |

---

## 2. 模块目标

审核模块负责管理员对用户提交的资料进行内容审核，控制资料是否能进入公开消费链路。

首版目标：

- 管理员分页查询待审核资料。
- 管理员审核通过资料，资料状态从 `PENDING_REVIEW` 变为 `APPROVED`。
- 管理员审核拒绝资料，资料状态从 `PENDING_REVIEW` 变为 `REJECTED` 并记录拒绝原因。
- 管理员下架已通过资料，资料状态从 `APPROVED` 变为 `OFFLINE` 并记录下架原因。
- 每次审核动作写入 `audit_record`，保留审核轨迹。
- 对状态流转做强校验，禁止重复审核或越级流转。

---

## 3. 需求分析

1. 资料模块创建的新资料默认是 `status = 0 PENDING_REVIEW`，不能直接公开展示。
2. 搜索、下载、收藏等后续模块只能消费 `status = 1 APPROVED` 的资料，因此审核是内容公开前的必要闸口。
3. 审核动作需要可追溯，不能只更新 `resource.status`，还要写入 `audit_record`。
4. 管理员接口不能只依赖“已登录”，还必须校验当前用户角色是管理员。
5. 审核通过、拒绝、下架都是状态机流转，必须限制前置状态，避免并发或重复操作导致状态错乱。

为什么不是简单 CRUD：审核模块的核心不是增删改查，而是围绕资料生命周期做状态机控制、权限控制、事务一致性和审计留痕。

---

## 4. 本模块不做什么

- 不创建资料，资料创建继续由资料模块负责。
- 不上传物理文件，文件上传继续由文件上传模块负责。
- 不实现全文搜索或 Elasticsearch。
- 不实现下载、收藏、排行榜。
- 不实现举报、评论、内容安全自动识别。
- 不新增生产数据库表或字段，首版复用 `sql/init.sql` 中已有 `resource` 和 `audit_record` 表。
- 不接入 Redis 缓存；若后续资料详情缓存上线，审核通过、拒绝、下架时再补充缓存失效逻辑。

---

## 5. 涉及接口

> 以下接口已在 `AuditController` 中实现，并已同步到 `docs/04-api-doc.md`。

### 5.1 获取待审核资料列表

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/admin/resources/pending-reviews` |
| 是否登录 | 是 |
| 权限要求 | 管理员 |

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `courseName` | string | 否 | 课程名称筛选 |
| `resourceType` | int | 否 | 资料类型 |
| `uploaderId` | long | 否 | 上传者 ID |
| `pageNo` | int | 否 | 默认 1 |
| `pageSize` | int | 否 | 默认 10，最大 100 |

响应数据：`PageResult<PendingReviewResourceVO>`。

### 5.2 审核通过资料

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/v1/admin/resources/{resourceId}/audit-approvals` |
| 是否登录 | 是 |
| 权限要求 | 管理员 |

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `auditReason` | string | 否 | 审核意见，最大 500 字符 |

响应数据：`AuditResultVO`，包含 `resourceId`、`beforeStatus`、`afterStatus`、`auditRecordId`、`approvedAt`。

### 5.3 审核拒绝资料

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/v1/admin/resources/{resourceId}/audit-rejections` |
| 是否登录 | 是 |
| 权限要求 | 管理员 |

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `rejectReason` | string | 是 | 拒绝原因，最大 500 字符 |

响应数据：`AuditResultVO`，包含 `resourceId`、`beforeStatus`、`afterStatus`、`auditRecordId`、`rejectReason`。

### 5.4 下架资料

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| 路径 | `/api/v1/admin/resources/{resourceId}/offline-records` |
| 是否登录 | 是 |
| 权限要求 | 管理员 |

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `offlineReason` | string | 是 | 下架原因，最大 500 字符 |

响应数据：`AuditResultVO`，包含 `resourceId`、`beforeStatus`、`afterStatus`、`auditRecordId`、`offlineAt`。

### 5.5 获取资料审核记录

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| 路径 | `/api/v1/admin/resources/{resourceId}/audit-records` |
| 是否登录 | 是 |
| 权限要求 | 管理员 |

响应数据：`List<AuditRecordVO>`。

---

## 6. 涉及数据库表

### 6.1 `resource` 资料表

审核模块会更新以下字段：

| 字段 | 使用场景 |
| --- | --- |
| `id` | 定位被审核资料 |
| `status` | 状态流转：待审核、已通过、已拒绝、已下架 |
| `reject_reason` | 审核拒绝时记录拒绝原因 |
| `offline_reason` | 下架时记录下架原因 |
| `approved_at` | 审核通过时间 |
| `offline_at` | 下架时间 |
| `updated_at` | 状态更新时自动或显式更新 |

首版只允许以下状态流转：

```text
PENDING_REVIEW(0) -> APPROVED(1)
PENDING_REVIEW(0) -> REJECTED(2)
APPROVED(1)       -> OFFLINE(3)
```

### 6.2 `audit_record` 审核记录表

每次审核动作写入一条记录。

| 字段 | 使用场景 |
| --- | --- |
| `id` | 审核记录 ID |
| `resource_id` | 被审核资料 ID |
| `auditor_id` | 当前管理员用户 ID |
| `action_type` | 操作类型：1通过 2拒绝 3下架 |
| `before_status` | 操作前资料状态 |
| `after_status` | 操作后资料状态 |
| `audit_reason` | 审核意见、拒绝原因或下架原因 |
| `created_at` | 操作时间 |

### 6.3 `user` 用户表

首版不需要查询完整用户信息即可完成审核动作，管理员身份来自 JWT 拦截器写入的 `LoginUser`，由 `AuditServiceImpl.requireAdmin()` 调用 `LoginUser.isAdmin()` 校验 `role = 2`。若待审核列表需要展示上传者昵称，可后续按 `resource.uploader_id` 关联 `user`。

---

## 7. 涉及 Redis Key

首版审核模块暂不接入 Redis。

后续资料详情缓存上线后，审核模块需要在状态变化时删除或刷新：

| Key | 类型 | 用途 |
| --- | --- | --- |
| `crp:cache:resource:detail:{resourceId}` | String | 资料公开详情缓存，审核状态变化后应失效 |

---

## 8. 涉及核心类

### 8.1 复用已存在类

| 类型 | 类 | 作用 |
| --- | --- | --- |
| common | `ApiResponse` | 统一响应 |
| common | `ErrorCode` | 统一错误码 |
| common | `PageQuery`、`PageResult` | 分页请求和响应 |
| common | `UserContextHolder` | 获取当前管理员 ID 和角色 |
| entity | `Resource` | 资料实体和状态常量 |
| mapper | `ResourceMapper` | 查询和更新资料状态 |
| exception | `BusinessException`、`GlobalExceptionHandler` | 业务异常和统一异常处理 |

### 8.2 已新增或修改类

| 类型 | 类 | 职责 |
| --- | --- | --- |
| entity | `AuditRecord` | 映射 `audit_record` 表，封装审核动作常量 |
| dto | `AuditApproveDTO` | 审核通过请求体 |
| dto | `AuditRejectDTO` | 审核拒绝请求体 |
| dto | `ResourceOfflineDTO` | 下架资料请求体 |
| vo | `PendingReviewResourceVO` | 待审核资料列表项 |
| vo | `AuditResultVO` | 审核动作结果 |
| vo | `AuditRecordVO` | 审核记录响应 |
| mapper | `AuditRecordMapper` + XML | 写入和查询审核记录 |
| mapper | `ResourceMapper` + XML | 补充待审核分页、状态更新 SQL |
| service | `AuditService` / `AuditServiceImpl` | 审核状态流转和审计记录编排 |
| controller | `AuditController` | 管理员审核接口入口 |

---

## 9. 模块内部调用关系

```text
AuditController
  └── AuditService (AuditServiceImpl)
        ├── UserContextHolder（校验管理员身份，获取 auditorId）
        ├── ResourceMapper（查询资料、分页待审核、更新状态）
        ├── AuditRecordMapper（写入/查询审核记录）
        └── PageResult（封装分页响应）
```

Controller 只负责接收请求、触发参数校验和返回统一响应；状态机判断、事务和审计记录写入放在 Service 层。

---

## 10. 请求处理流程

### 10.1 获取待审核资料列表

1. JWT 拦截器校验登录。
2. `AuditController` 接收筛选参数和分页参数。
3. `AuditServiceImpl` 校验当前用户角色必须为管理员。
4. 校验分页、资料类型、上传者 ID 等查询参数。
5. `ResourceMapper` 查询 `status = 0` 的资料列表和总数。
6. 转换为 `PendingReviewResourceVO`。
7. 返回 `PageResult<PendingReviewResourceVO>`。

### 10.2 审核通过

1. JWT 拦截器校验登录。
2. `AuditServiceImpl` 校验管理员角色。
3. 查询资料是否存在。
4. 校验当前状态必须是 `PENDING_REVIEW`。
5. 在事务内更新 `resource.status = APPROVED` 和 `approved_at`。
6. 写入 `audit_record`，记录 `before_status = 0`、`after_status = 1`。
7. 返回 `AuditResultVO`。

### 10.3 审核拒绝

1. JWT 拦截器校验登录。
2. 校验管理员角色和拒绝原因。
3. 查询资料是否存在。
4. 校验当前状态必须是 `PENDING_REVIEW`。
5. 在事务内更新 `resource.status = REJECTED` 和 `reject_reason`。
6. 写入 `audit_record`。
7. 返回 `AuditResultVO`。

### 10.4 下架资料

1. JWT 拦截器校验登录。
2. 校验管理员角色和下架原因。
3. 查询资料是否存在。
4. 校验当前状态必须是 `APPROVED`。
5. 在事务内更新 `resource.status = OFFLINE`、`offline_reason`、`offline_at`。
6. 写入 `audit_record`。
7. 返回 `AuditResultVO`。

### 10.5 获取资料审核记录

1. JWT 拦截器校验登录。
2. 校验管理员角色。
3. 校验资料是否存在。
4. `AuditRecordMapper` 按 `resource_id` 查询审核记录，按时间倒序返回。
5. 转换为 `AuditRecordVO`。

---

## 11. 数据流转流程

```text
resource.status = 0(PENDING_REVIEW)
  → 管理员审核通过
  → resource.status = 1(APPROVED)
  → audit_record(action_type=1)
  → 后续搜索/下载/收藏可消费

resource.status = 0(PENDING_REVIEW)
  → 管理员审核拒绝
  → resource.status = 2(REJECTED)
  → audit_record(action_type=2)
  → 上传者在我的上传列表查看拒绝原因

resource.status = 1(APPROVED)
  → 管理员下架
  → resource.status = 3(OFFLINE)
  → audit_record(action_type=3)
  → 公开详情/搜索/下载不再消费
```

---

## 12. 权限校验

- 所有审核接口都需要登录。
- 所有审核接口都要求当前用户角色为管理员，即 `role = 2`。
- 普通学生访问审核接口应返回 `40301 FORBIDDEN`。
- 管理员身份不接受前端传参，必须来自 JWT 解析后的 `UserContextHolder`。

当前在 `AuditServiceImpl` 内集中实现 `requireAdmin()`，后续若管理员接口增多，可再抽取拦截器或注解式权限校验。

---

## 13. 参数校验

| 场景 | 规则 | 失败错误码 |
| --- | --- | --- |
| `resourceId` | 必填且大于 0 | `40001` |
| `auditReason` | 可空，非空最大 500 字符 | `40001` |
| `rejectReason` | 必填，最大 500 字符 | `40001` |
| `offlineReason` | 必填，最大 500 字符 | `40001` |
| `resourceType` | 可选，必须是 1、2、3、4、5、99 | `40001` |
| `uploaderId` | 可选，必须大于 0 | `40001` |
| `pageNo` | 大于等于 1 | `40001` |
| `pageSize` | 1 到 100 | `40001` |

---

## 14. 异常处理

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 未登录访问审核接口 | `40101 UNAUTHORIZED` | JWT 拦截器处理 |
| 非管理员访问审核接口 | `40301 FORBIDDEN` | Service 权限校验 |
| 资料不存在 | `40401 RESOURCE_NOT_FOUND` | `resource` 查不到 |
| 待审核列表参数错误 | `40001 PARAM_ERROR` | 分页、类型、上传者参数不合法 |
| 拒绝原因或下架原因为空 | `40001 PARAM_ERROR` | 请求体校验 |
| 审核通过非待审核资料 | `40901 RESOURCE_STATUS_INVALID` | 状态机禁止 |
| 审核拒绝非待审核资料 | `40901 RESOURCE_STATUS_INVALID` | 状态机禁止 |
| 下架非已通过资料 | `40901 RESOURCE_STATUS_INVALID` | 状态机禁止 |

---

## 15. 事务处理

审核通过、审核拒绝、下架资料都必须使用事务，因为每次操作至少包含：

1. 更新 `resource` 状态字段。
2. 写入 `audit_record` 审核记录。

建议使用：

```java
@Transactional(rollbackFor = Exception.class)
```

并发注意点：

- 当前使用带前置状态条件的更新 SQL，例如 `WHERE id = ? AND status = ?`，更新行数为 0 时按状态不允许处理。
- 如需更严格并发控制，可在查询资料时使用 `SELECT ... FOR UPDATE`，但要评估本机 MySQL 测试覆盖、锁粒度和接口响应时延。

---

## 16. 核心实现步骤

1. 创建 `AuditRecord` 实体，补充审核动作常量。
2. 创建 `AuditRecordMapper` 与 XML，实现插入审核记录、按资料查询审核记录。
3. 为 `ResourceMapper` 补充待审核分页、总数统计和状态更新方法。
4. 创建审核模块 DTO 与 VO。
5. 实现 `AuditService` 与 `AuditServiceImpl`。
6. 实现 `AuditController`。
7. 补充管理员权限校验和 Web 鉴权路径确认。
8. 补充 MockMvc 测试和数据库集成测试。
9. 同步接口文档、数据库记录、项目进度文档和 README。
10. 更新本模块开发流程文档。

---

## 17. 开发任务拆分

| 序号 | 任务 | 产出 |
| --- | --- | --- |
| T1 | 审核记录实体与 Mapper | `AuditRecord`、`AuditRecordMapper`、`AuditRecordMapper.xml` |
| T2 | 资料 Mapper 增强 | 待审核分页、状态更新 SQL |
| T3 | DTO/VO | 审核通过、拒绝、下架请求体和响应对象 |
| T4 | Service 状态机 | 管理员校验、状态流转、事务、审计记录 |
| T5 | Controller | 管理员审核接口 |
| T6 | 测试 | Controller 测试、数据库集成测试、Postman 示例 |
| T7 | 文档 | API、进度、数据库记录、模块流程文档 |

---

## 18. 已完成事项

- 已创建 `AuditRecord` 实体，映射 `audit_record` 表并封装审核动作常量。
- 已创建 `AuditRecordMapper` 和 XML，实现审核记录写入与按资料查询。
- 已增强 `ResourceMapper` 和 XML，实现待审核分页、计数、审核通过、审核拒绝和下架状态更新 SQL。
- 已创建审核请求 DTO：`AuditApproveDTO`、`AuditRejectDTO`、`ResourceOfflineDTO`。
- 已创建审核响应 VO：`PendingReviewResourceVO`、`AuditResultVO`、`AuditRecordVO`。
- 已实现 `AuditService` 和 `AuditServiceImpl`，完成管理员校验、状态机、事务和审核记录编排。
- 已实现 `AuditController`，提供 5 个管理员审核接口。
- 已确认 `/api/v1/admin/resources/**` 不在公开排除列表中，未登录返回 `40101`，普通用户返回 `40301`。
- 已补充 `AuditControllerTest` 和 `AuditServiceDatabaseIntegrationTest`。
- 已同步 `docs/04-api-doc.md`、`docs/database/database-change-log.md`、`docs/06-project-progress.md`、`README.md` 和本模块开发流程文档。

---

## 19. 待完成事项

- 本模块首版功能已完成。
- 后续可补充审核列表关联上传者昵称、文件大小、分类名称等辅助信息。
- 后续资料详情缓存上线后，审核通过、拒绝、下架需要删除或刷新 `crp:cache:resource:detail:{resourceId}`。
- 后续搜索模块上线后，审核通过可触发搜索索引刷新；下架时需要移除公开搜索结果。

---

## 20. 测试清单

### 20.1 待审核列表

| 用例 | 预期 |
| --- | --- |
| 管理员查询待审核资料 | 返回 `status = 0` 的分页列表 |
| 按课程名筛选 | 只返回匹配课程资料 |
| 按资料类型筛选 | 只返回匹配类型资料 |
| 按上传者筛选 | 只返回对应上传者资料 |
| 普通用户查询 | 返回 `40301` |
| 未登录查询 | 返回 `40101` |
| 非法分页参数 | 返回 `40001` |

### 20.2 审核通过

| 用例 | 预期 |
| --- | --- |
| 管理员审核通过待审核资料 | `resource.status = 1`，写入 `audit_record` |
| 审核通过不存在资料 | 返回 `40401` |
| 审核通过已通过资料 | 返回 `40901` |
| 普通用户审核通过 | 返回 `40301` |

### 20.3 审核拒绝

| 用例 | 预期 |
| --- | --- |
| 管理员拒绝待审核资料 | `resource.status = 2`，写入拒绝原因和审核记录 |
| 拒绝原因为空 | 返回 `40001` |
| 拒绝已通过资料 | 返回 `40901` |
| 普通用户审核拒绝 | 返回 `40301` |

### 20.4 下架资料

| 用例 | 预期 |
| --- | --- |
| 管理员下架已通过资料 | `resource.status = 3`，写入下架原因、下架时间和审核记录 |
| 下架原因为空 | 返回 `40001` |
| 下架待审核资料 | 返回 `40901` |
| 普通用户下架 | 返回 `40301` |

### 20.5 审核记录

| 用例 | 预期 |
| --- | --- |
| 管理员查询某资料审核记录 | 返回审核记录列表 |
| 查询不存在资料记录 | 返回 `40401` |
| 普通用户查询记录 | 返回 `40301` |

### 20.6 已执行测试记录

| 测试命令 | 结果 | 说明 |
| --- | --- | --- |
| `.\mvnw.cmd test` | 通过，`Tests run: 37, Failures: 0, Errors: 0, Skipped: 0` | 审核数据库集成测试使用本机 MySQL 独立测试库 `campus_resource_platform_audit_test` |

已覆盖的审核模块自动化测试：

| 测试类 | 覆盖重点 |
| --- | --- |
| `AuditControllerTest` | 管理员接口路由、JWT 保护、未登录、非管理员、参数校验、资料不存在、非法状态异常映射 |
| `AuditServiceDatabaseIntegrationTest` | 待审核筛选、审核通过、审核拒绝、下架、审核记录、非管理员拒绝、资料不存在、非法状态、事务回滚 |

---

## 21. 修改文件记录

当前已修改或新增：

| 文件 | 说明 |
| --- | --- |
| `campus-resource-platform/src/main/java/com/john/campus/entity/AuditRecord.java` | 新增审核记录实体和动作常量 |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/AuditRecordMapper.java` | 新增审核记录 Mapper |
| `campus-resource-platform/src/main/resources/mapper/AuditRecordMapper.xml` | 新增审核记录插入和查询 SQL |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/ResourceMapper.java` | 增加审核列表、计数和状态更新方法 |
| `campus-resource-platform/src/main/resources/mapper/ResourceMapper.xml` | 增加待审核查询和状态流转 SQL |
| `campus-resource-platform/src/main/java/com/john/campus/dto/AuditApproveDTO.java` | 新增审核通过请求 DTO |
| `campus-resource-platform/src/main/java/com/john/campus/dto/AuditRejectDTO.java` | 新增审核拒绝请求 DTO |
| `campus-resource-platform/src/main/java/com/john/campus/dto/ResourceOfflineDTO.java` | 新增资料下架请求 DTO |
| `campus-resource-platform/src/main/java/com/john/campus/vo/PendingReviewResourceVO.java` | 新增待审核列表响应 VO |
| `campus-resource-platform/src/main/java/com/john/campus/vo/AuditResultVO.java` | 新增审核动作结果 VO |
| `campus-resource-platform/src/main/java/com/john/campus/vo/AuditRecordVO.java` | 新增审核记录响应 VO |
| `campus-resource-platform/src/main/java/com/john/campus/service/AuditService.java` | 新增审核业务接口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/AuditServiceImpl.java` | 新增审核状态机、权限校验、事务和审计记录编排 |
| `campus-resource-platform/src/main/java/com/john/campus/controller/AuditController.java` | 新增管理员审核接口 |
| `campus-resource-platform/src/test/java/com/john/campus/controller/AuditControllerTest.java` | 新增/补充审核 Controller 测试 |
| `campus-resource-platform/src/test/java/com/john/campus/service/AuditServiceDatabaseIntegrationTest.java` | 新增/补充审核数据库集成测试 |
| `campus-resource-platform/src/test/resources/sql/resource-db-test-schema.sql` | 补充审核记录测试表结构 |
| `docs/04-api-doc.md` | 同步审核模块接口文档 |
| `docs/database/database-change-log.md` | 记录审核模块复用已有生产表结构 |
| `docs/06-project-progress.md` | 更新审核模块完成状态和测试结果 |
| `README.md` | 更新当前完成内容和测试说明 |
| `docs/modules/05-audit-development-process.md` | 更新审核模块开发流程文档 |

---

## 22. 与其他模块的关系

- 依赖认证模块：审核接口必须登录，并依赖当前用户角色判断管理员权限。
- 依赖资料模块：审核模块消费 `resource.status = 0` 的待审核资料。
- 支撑搜索模块：只有审核通过资料才能进入搜索结果。
- 支撑下载模块：只有审核通过资料才能下载。
- 支撑收藏模块：只有审核通过资料才能收藏。
- 支撑排行榜模块：只有审核通过资料适合进入热门资料统计。

---

## 23. 面试可讲点

- 为什么审核模块是状态机，而不是普通更新字段。
- 如何保证审核状态更新和审核记录写入的一致性。
- 如何防止普通用户访问管理员审核接口。
- 如何处理重复审核、并发审核和非法状态流转。
- 为什么审核通过后才允许搜索、下载和收藏。
- `audit_record` 为什么只追加不修改，如何支撑审计追踪。
- 后续接入资料详情缓存时，审核状态变化如何做缓存失效。

---

## 24. 后续优化方向

- 抽取统一管理员权限注解或拦截器。
- 审核通过后主动删除或刷新资料详情缓存。
- 审核通过后发送领域事件，驱动搜索索引、排行榜初始化。
- 审核拒绝后支持用户修改资料并重新提交审核。
- 审核列表关联上传者昵称、文件大小、分类名称等更多审核辅助信息。
- 支持批量审核，但需要更严格的事务和部分失败处理策略。
- 支持审核操作日志导出和管理员维度统计。

---

## 25. Git commit message 建议

```text
docs(audit): sync audit module documentation

- align API docs with AuditController and VO fields
- record audit table usage and MySQL test coverage
- mark audit module as completed in progress docs
- update audit module development process
```

---

## 26. 分步骤开发提示词

> 使用说明：以下提示词按 `docs/AGENTS.md` 第 24 节要求拆分，每一步都是一个最小可执行任务。执行时请一次只复制一条提示词给 Agent，完成并验证后再进入下一步。

### 步骤 1：创建 AuditRecord 实体

提示词：

```text
请为审核模块创建 AuditRecord 实体。

本步目标：
- 创建 `campus-resource-platform/src/main/java/com/john/campus/entity/AuditRecord.java`，映射 MySQL `audit_record` 表。
- 继承 `BaseEntity`，字段与 `sql/init.sql` 中的 `audit_record` 表保持一致。
- 增加审核动作常量：`ACTION_APPROVE = 1`、`ACTION_REJECT = 2`、`ACTION_OFFLINE = 3`。
- 添加必要的动作判断方法，例如 `isApprove()`、`isReject()`、`isOffline()`。

涉及文件或类：
- `entity/AuditRecord.java`
- `entity/BaseEntity.java`（只复用，不修改）
- `sql/init.sql`（只核对字段，不修改）

完成标准：
- `AuditRecord` 字段与 `audit_record` 表字段能一一对应。
- 审核动作常量没有魔法值散落风险。
- 关键字段、动作常量和判断方法有简洁中文注释。

本步不做什么：
- 不创建 Mapper、Service、Controller。
- 不修改数据库结构。
- 不实现审核通过、拒绝或下架业务逻辑。
```

### 步骤 2：创建 AuditRecordMapper

提示词：

```text
请为审核模块创建 AuditRecordMapper 接口和 AuditRecordMapper.xml。

本步目标：
- 创建 `mapper/AuditRecordMapper.java` 和 `src/main/resources/mapper/AuditRecordMapper.xml`。
- 实现 `insert(AuditRecord auditRecord)`，写入审核记录并回填自增 ID。
- 实现 `selectByResourceId(Long resourceId)`，按资料 ID 查询审核记录，按 `created_at DESC, id DESC` 排序。

涉及文件或类：
- `mapper/AuditRecordMapper.java`
- `src/main/resources/mapper/AuditRecordMapper.xml`
- `entity/AuditRecord.java`

完成标准：
- XML 中提供清晰的 `resultMap` 和公共列清单。
- SQL 使用 `idx_audit_resource_created` 查询方向。
- 编译不报 Mapper 方法或 XML 绑定错误。

本步不做什么：
- 不实现 Service 和 Controller。
- 不新增表、字段、索引。
- 不把 Entity 直接作为接口响应返回。
```

### 步骤 3：增强 ResourceMapper 审核查询与状态更新

提示词：

```text
请为审核模块增强 ResourceMapper 和 ResourceMapper.xml。

本步目标：
- 增加待审核资料分页查询，例如 `selectPendingReviews(...)`。
- 增加待审核资料总数统计，例如 `countPendingReviews(...)`。
- 增加审核通过状态更新 SQL，要求前置状态必须是 `PENDING_REVIEW`。
- 增加审核拒绝状态更新 SQL，要求前置状态必须是 `PENDING_REVIEW`。
- 增加下架资料状态更新 SQL，要求前置状态必须是 `APPROVED`。

涉及文件或类：
- `mapper/ResourceMapper.java`
- `src/main/resources/mapper/ResourceMapper.xml`
- `entity/Resource.java`
- `common/PageQuery.java`、`common/PageResult.java`（只参考分页风格）

完成标准：
- 待审核列表固定过滤 `status = 0`。
- 支持可选筛选：`courseName`、`resourceType`、`uploaderId`。
- 状态更新 SQL 必须带旧状态条件，防止重复审核或非法状态流转。
- 更新方法返回影响行数，供 Service 判断是否更新成功。

本步不做什么：
- 不实现 Service。
- 不写审核记录。
- 不实现搜索、下载、收藏联动。
```

### 步骤 4：创建 DTO 与 VO

提示词：

```text
请为审核模块创建请求 DTO 和响应 VO。

本步目标：
- 创建 `dto/AuditApproveDTO.java`，用于审核通过请求。
- 创建 `dto/AuditRejectDTO.java`，用于审核拒绝请求。
- 创建 `dto/ResourceOfflineDTO.java`，用于下架资料请求。
- 创建 `vo/PendingReviewResourceVO.java`，用于待审核资料列表项。
- 创建 `vo/AuditResultVO.java`，用于审核通过、拒绝、下架响应。
- 创建 `vo/AuditRecordVO.java`，用于审核记录响应。
- DTO 使用 `jakarta.validation` 添加参数校验。

涉及文件或类：
- `dto/AuditApproveDTO.java`
- `dto/AuditRejectDTO.java`
- `dto/ResourceOfflineDTO.java`
- `vo/PendingReviewResourceVO.java`
- `vo/AuditResultVO.java`
- `vo/AuditRecordVO.java`

完成标准：
- 拒绝原因和下架原因必填且最大 500 字符。
- 审核意见可选但最大 500 字符。
- VO 不直接暴露 Entity。
- 列表 VO 包含管理员审核所需的核心展示字段。

本步不做什么：
- 不实现 Service。
- 不新增接口。
- 不扩展搜索、下载、收藏字段。
```

### 步骤 5：实现审核 Service 状态机

提示词：

```text
请实现审核模块的 Service 和 ServiceImpl。

本步目标：
- 创建 `service/AuditService.java` 和 `service/impl/AuditServiceImpl.java`。
- 实现待审核资料分页查询。
- 实现审核通过、审核拒绝、下架资料。
- 实现查询资料审核记录。
- 所有管理员接口都必须校验当前用户角色为管理员。
- 审核通过、拒绝、下架必须使用 `@Transactional(rollbackFor = Exception.class)`。
- 每次状态更新成功后必须写入 `audit_record`。

涉及文件或类：
- `service/AuditService.java`
- `service/impl/AuditServiceImpl.java`
- `mapper/ResourceMapper.java`
- `mapper/AuditRecordMapper.java`
- `common/UserContextHolder.java`
- `exception/BusinessException.java`
- `common/ErrorCode.java`
- 审核 DTO/VO

完成标准：
- 普通用户调用审核能力返回 `FORBIDDEN`。
- 资料不存在返回 `RESOURCE_NOT_FOUND`。
- 非法状态流转返回 `RESOURCE_STATUS_INVALID`。
- 状态更新和审核记录写入在同一事务中完成。
- Service 不直接返回 Entity。

本步不做什么：
- 不实现 Controller。
- 不接入 Redis。
- 不触发搜索索引、下载、收藏、排行榜联动。
```

### 步骤 6：实现 AuditController

提示词：

```text
请实现审核模块 Controller。

本步目标：
- 创建 `controller/AuditController.java`。
- 实现 `GET /api/v1/admin/resources/pending-reviews`。
- 实现 `POST /api/v1/admin/resources/{resourceId}/audit-approvals`。
- 实现 `POST /api/v1/admin/resources/{resourceId}/audit-rejections`。
- 实现 `POST /api/v1/admin/resources/{resourceId}/offline-records`。
- 实现 `GET /api/v1/admin/resources/{resourceId}/audit-records`。

涉及文件或类：
- `controller/AuditController.java`
- `service/AuditService.java`
- `common/ApiResponse.java`
- 审核 DTO/VO

完成标准：
- Controller 方法返回 `ApiResponse` 包装结果。
- 请求体使用 `@Valid @RequestBody`。
- 路径参数不在 Controller 写复杂业务判断，交给 Service 校验。
- 所有接口都保留在 JWT 拦截范围内，不加入公开排除列表。

本步不做什么：
- 不在 Controller 直接访问 Mapper。
- 不实现普通用户接口。
- 不改变资料模块已有接口路径。
```

### 步骤 7：补充权限边界与鉴权路径确认

提示词：

```text
请检查并补充审核模块权限边界。

本步目标：
- 确认 `/api/v1/admin/resources/**` 不在 `WebMvcConfig.excludePathPatterns` 中。
- 确认审核 Service 统一校验 `UserContextHolder.getRequiredRole()` 必须为管理员角色 `2`。
- 如有必要，补充一个私有方法 `requireAdmin()`，集中处理管理员权限异常。

涉及文件或类：
- `config/WebMvcConfig.java`
- `service/impl/AuditServiceImpl.java`
- `common/UserContextHolder.java`
- `common/ErrorCode.java`

完成标准：
- 未登录访问审核接口返回 `40101`。
- 普通学生访问审核接口返回 `40301`。
- 管理员可以正常进入审核业务流程。

本步不做什么：
- 不实现注解式权限系统。
- 不新增 Spring Security。
- 不调整公开接口排除规则。
```

### 步骤 8：补充审核模块测试

提示词：

```text
请为审核模块补充测试。

本步目标：
- 补充 MockMvc 测试，覆盖审核 Controller 路径、权限、参数校验和异常映射。
- 补充数据库集成测试，使用本机 MySQL 独立测试库执行真实 MyBatis XML。
- 测试审核通过、审核拒绝、下架资料时 `resource` 更新和 `audit_record` 写入是否在同一事务中完成。
- 补充普通用户访问管理员接口返回 `40301` 的测试。

涉及文件或类：
- `src/test/java/.../AuditControllerTest.java`
- `src/test/java/.../AuditServiceDatabaseIntegrationTest.java`
- `src/test/resources/sql/...`（如需要测试专用 schema）

完成标准：
- 至少覆盖成功、未登录、非管理员、资料不存在、非法状态、参数错误。
- 至少执行 `.\mvnw.cmd test` 并记录结果。
- 数据库测试通过 `MYSQL_TEST_URL` / `MYSQL_TEST_USERNAME` / `MYSQL_TEST_PASSWORD` 或 `MYSQL_USERNAME` / `MYSQL_PASSWORD` 读取本机 MySQL 连接信息，不把密码写入仓库。

本步不做什么：
- 不为了测试引入重量级依赖。
- 不测试搜索、下载、收藏模块。
```

### 步骤 9：同步接口文档与数据库记录

提示词：

```text
请同步审核模块相关文档，但不要修改业务代码。

本步目标：
- 根据当前真实代码更新 `docs/04-api-doc.md` 中审核模块接口说明。
- 根据当前真实代码更新 `docs/database/database-change-log.md`，说明审核模块是否新增数据库结构；若没有新增结构，写明复用 `resource` 和 `audit_record` 表。
- 根据当前真实代码更新 `docs/06-project-progress.md` 中审核模块状态、已实现接口、涉及表和待办项。
- 如 README 的当前完成内容已落后，同步更新 README。

涉及文件：
- `docs/04-api-doc.md`
- `docs/database/database-change-log.md`
- `docs/06-project-progress.md`
- `README.md`

完成标准：
- 文档中的接口路径、HTTP 方法、请求/响应字段与真实 Controller 保持一致。
- 文档不把未实现功能写成已完成。
- 数据库记录明确“新增结构”或“复用已有结构”。

本步不做什么：
- 不修改 Java 代码。
- 不新增接口规划以外的模块内容。
- 不编造 Redis Key 或数据库字段。
```

### 步骤 10：更新本模块开发流程文档

提示词：

```text
请根据当前真实代码更新审核模块开发流程文档。

本步目标：
- 更新 `docs/modules/05-audit-development-process.md`。
- 补全“当前状态”“已完成事项”“待完成事项”“测试清单”“修改文件记录”“与其他模块的关系”“面试可讲点”“后续优化方向”。
- 如果实现过程中接口、类名、方法名、字段名与规划不一致，以当前真实代码为准修正文档。
- 保留“分步骤开发提示词”小节，并根据实际开发顺序校准下一轮可复制提示词。

涉及文件：
- `docs/modules/05-audit-development-process.md`

完成标准：
- 文档能回答：本模块解决什么问题、有哪些接口、调用链路是什么、涉及哪些表、是否使用 Redis、如何权限控制、如何测试。
- 文档不含“已规划但伪装成已完成”的内容。
- 文档记录本模块真实修改文件清单和测试结果。

本步不做什么：
- 不修改业务代码。
- 不扩展搜索、下载、收藏、排行榜模块。
- 不删除已有文档章节。
```
