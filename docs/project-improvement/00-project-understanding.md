# 项目理解与审计基线

> 审计日期：2026-07-14  
> 当前分支：`dev`  
> 基线提交：`dcf1383 feat(rank): make download delta sync idempotent`  
> 阶段：`ANALYSIS_ONLY`  
> 事实标记：除明确写有“待确认”的内容外，均已由代码、SQL、文档或实际命令交叉验证。

## 1. 项目目标与用户角色

本项目是校园资料共享平台。业务主线为“上传文件 → 创建资料 → 管理员审核 → 公开检索 → 收藏/下载 → 排行榜与统计”，同时用于展示 Spring Boot、MySQL、Redis、文件存储、事务与最终一致性等工程能力。

主要角色：

- 游客：健康检查、分类浏览、已审核资料详情、公开搜索、公开排行榜。
- 学生/普通用户（角色值 `1`）：注册登录、上传文件、创建资料、查看自己的上传、收藏、下载。
- 管理员（角色值 `2`）：审核通过、拒绝、下架、查询审核流水、手工重建总榜。

## 2. 技术栈

| 层次 | 已验证技术 |
| --- | --- |
| 后端 | Java 17 目标版本、Spring Boot 3.5.16、Spring MVC、Bean Validation、MyBatis 3.0.5、Maven Wrapper |
| 认证 | JWT（JJWT 0.12.6）、BCrypt、Redis Token 黑名单、请求拦截器与 `UserContext` |
| 数据 | MySQL 8、手写 Mapper XML、手工初始化/迁移 SQL |
| Redis | Spring Data Redis、Redisson 4.6.1、String/Set/Hash/ZSet、Lua、分布式读写锁 |
| 文件 | 本地磁盘、UUID 存储名、MD5+大小去重，数据库保存绝对路径 |
| 前端 | Vue 3、TypeScript、Vite 8.1.4、Element Plus、Axios、Vitest |
| 当前未使用 | 消息队列、`@Async`、对象存储、Elasticsearch、业务线程池 |

## 3. 目录结构与模块

```text
campus-resource-platform/
├─ campus-resource-platform/        后端 Maven 工程
│  ├─ src/main/java/...             Controller、Service、Mapper、Entity、任务与配置
│  ├─ src/main/resources/           application.yaml、Mapper XML
│  └─ src/test/                     单元测试、H2/MySQL 集成测试、测试 DDL
├─ frontend/                        Vue 前端
├─ sql/                             init.sql 与人工迁移脚本
├─ docs/                            需求、流程、数据库、Redis、API、运行和模块文档
├─ .github/                         当前没有 workflows
└─ .codex/                          项目级 Agent 配置
```

核心业务模块：认证与用户、分类、文件、资料、审核、搜索、下载、收藏、排行榜、健康检查。

## 4. 核心调用链

| 场景 | 真实调用链与基础设施 |
| --- | --- |
| 认证 | `AuthController → AuthServiceImpl → UserMapper → user`；登录签 JWT，退出写 Redis 黑名单；受保护请求经 `JwtAuthenticationInterceptor` |
| 文件 | `FileController → FileServiceImpl → FileStorageServiceImpl/FileInfoMapper → 本地磁盘/file_info`；MD5 结果缓存到 Redis |
| 资料 | `ResourceController → ResourceServiceImpl → FileInfoMapper/CategoryMapper/ResourceMapper → MySQL` |
| 审核 | `AuditController → AuditServiceImpl → ResourceMapper/AuditRecordMapper`；状态条件更新和流水同事务，提交后更新 Redis 榜单 |
| 搜索 | `SearchController → SearchServiceImpl → ResourceMapper`；查询后写热门搜索词 ZSet |
| 下载 | `DownloadController → DownloadServiceImpl → DownloadRateLimiter/ResourceMapper/FileInfoMapper/DownloadRecordMapper`；Redis 限流、去重、增量，磁盘取流 |
| 收藏 | `FavoriteController → FavoriteServiceImpl → FavoriteMapper/ResourceMapper`；`TransactionTemplate` 更新关系与计数，提交后更新 Redis Set/榜单 |
| 排行榜 | `RankingController → RankingServiceImpl → Redis ZSet`，异常时通过 `ResourceMapper` 回退 MySQL 快照；管理员可重建总榜 |

模块依赖主线：

```text
认证 → 文件上传 → 资料创建 → 管理员审核 → 公开详情/搜索
                                      ↓
                              收藏/下载 → Redis 榜单 → 定时落库
```

## 5. 核心实体与状态流转

| 实体/表 | 关键含义与状态 |
| --- | --- |
| `user` | 角色：学生 `1`、管理员 `2`；状态：禁用 `0`、正常 `1` |
| `category` | 资料分类，支持启用/禁用和排序 |
| `file_info` | `NORMAL(1)`、`DELETED(2)`；保存 MD5、大小、MIME、存储路径、引用计数 |
| `resource` | `PENDING_REVIEW(0) → APPROVED(1)`；`PENDING_REVIEW(0) → REJECTED(2)`；`APPROVED(1) → OFFLINE(3)`；另有 `DELETED(4)` |
| `favorite` | `CANCELED(0)`、`FAVORITED(1)`；用户与资料唯一关系复用 |
| `download_record` | `SUCCESS(1)`、`FAIL(2)`；当前实际流程只创建成功记录 |
| `audit_record` | 管理员审核动作和理由流水 |
| `download_delta_sync_item` | Redis 下载增量落库的批次/资料幂等明细 |

已验证的状态控制：审核使用带旧状态条件的更新并检查影响行数；审核流水与状态变更处于同一事务。待改进的跨事务并发、下载生命周期和文件生命周期见审计清单。

## 6. MySQL、Redis、文件存储和任务

### MySQL

MySQL 持久化 8 张核心表。新库执行 `sql/init.sql`，旧库按运行手册人工执行 `sql/migrations/`。当前没有 Flyway、Liquibase 或等价 schema history。Mapper 使用 `#{}` 参数和固定排序白名单，未发现 `${}` 拼接或直接 SQL 注入证据。

### Redis

Redis Key 集中定义，主要用途：

- Token 黑名单；
- 文件 MD5 去重缓存；
- 用户收藏 Set；
- 用户/IP 下载滑动窗口限流；
- 下载十分钟去重与待落库增量 Hash；
- 热门资料、热门搜索词 ZSet；
- 增量同步与排行榜重建锁、临时 Key、当前批次标记。

已验证的正向实现包括：单 Key 限流 Lua 原子执行；下载增量隔离使用 Lua `RENAME`；解锁前检查当前线程持锁；热门资料读取支持 MySQL 回退。周期 Key、BigKey、跨 Key 原子性、缓存重建和故障降级仍有改进项。

### 文件存储

文件当前存储在本地磁盘，存储名为 UUID，读取时规范化路径并校验仍在根目录内，流使用 try-with-resources 或交给 Spring 响应生命周期关闭。未发现明确路径穿越或流泄漏；但上传类型校验、文件归属、生命周期和大文件双遍读取存在改进项。

### 定时与异步

- `RankingSyncTask`：默认每 60 秒同步下载增量到 MySQL。
- `HotRankingMaintenanceTask`：默认每 5 分钟检查/重建缺失总榜并回写热度快照。
- `RankingTaskExecutionMonitor`：仅保存进程内最后一次任务快照。
- 未发现 `@Async` 或消息队列；任务默认共享 Spring 调度器，审核后的 Redis 动作仍在请求线程的 `afterCommit` 回调中执行。

## 7. 权限模型

- 路由层：认证、分类、搜索、公开资料和排行榜部分匿名开放，其余 `/api/v1/**` 由 JWT 拦截器保护。
- Service 层：审核、下架、审核历史、排行榜重建再次检查管理员角色；用户资料、收藏和下载列表按当前 userId 查询。
- 资料可见性：公开详情/搜索只返回 `APPROVED`；用户上传列表按上传者过滤。
- 文件读取：当前以下载记录归属为授权依据；资料创建只校验 fileId 正常，不校验该文件是否由当前用户上传或获得引用授权。
- 已验证未发现明显动态 SQL 注入、明文密码存储或已提交密钥；BCrypt 用于密码散列。

## 8. 构建、测试、启动与基线结果

### 工具环境

- `java -version`：OpenJDK 21.0.10；Maven Wrapper 实际使用 `JAVA_HOME` 的 Java 24.0.2；编译目标为 Java 17。
- Maven Wrapper：Apache Maven 3.9.16。
- Node.js：v22.23.1；npm：10.9.8。
- MySQL 客户端/服务端查询版本：8.0.45。
- `redis-cli` 不可用；审计时配置的 Redis 主机 TCP 不可达。

### 实际命令

| 命令 | 结果 |
| --- | --- |
| `cd campus-resource-platform; .\mvnw.cmd -DskipTests compile` | `BUILD SUCCESS`，约 2.5 秒 |
| `cd campus-resource-platform; .\mvnw.cmd test` | `BUILD SUCCESS`；113 tests，0 failure，0 error，0 skipped，约 26.9 秒 |
| `cd frontend; npm run test:unit` | 31 个测试文件、66 个测试全部通过，约 75.2 秒 |
| `cd frontend; npm run build` | 成功；主 JS 约 838.38 kB，Vite 报 `>500 kB` chunk 警告 |
| `cd frontend; npm audit --audit-level=low` | 0 vulnerabilities |
| MySQL 只读 `SELECT VERSION()` | 成功，8.0.45 |
| Redis TCP 连通性检查 | 失败；本轮未执行依赖真实 Redis 的运行验证 |

`RankingTaskExecutionMonitorTest` 日志中有一条预期的 ERROR 堆栈，用于验证失败监控，Surefire 最终仍为全通过。

### 启动验证

为避免定时任务触碰 Redis，临时通过进程环境变量关闭两个排行榜任务后启动后端，`GET http://127.0.0.1:8080/api/v1/health` 返回 `code=0`、`status=UP`；随后已停止进程并确认 8080 无监听。该健康接口当前不探测 MySQL/Redis，因此只能证明 Spring 进程可启动，不能证明完整业务在 Redis 故障下可用。

### 外部依赖

- 必需：MySQL；本地磁盘可写目录。
- 完整业务需要：Redis；JWT 黑名单、下载限流/计数、收藏缓存、排行榜均使用 Redis，其中认证拦截器当前把 Redis 作为受保护接口的同步依赖。
- 不需要：消息队列、Elasticsearch、对象存储（仓库当前均未实现）。

## 9. 现有测试结构

后端共有 24 个测试类、113 个测试，重点覆盖资料、审核、排行榜、Redis 降级、下载增量幂等/回滚/锁竞争。前端有 31 个测试文件、66 个测试，覆盖页面与请求/会话基础行为。

主要缺口是认证、用户、文件、完整下载取流、收藏真实 Redis 状态机、权限越权、文件安全、自然周期切换、故障注入和并发边界。另有一个真实 MySQL 集成测试会执行破坏性测试 DDL，隔离保护不足，见 `IMP-012`。

## 10. 已确认的正向结论

- 审核状态条件更新会检查影响行数，事务回滚测试存在。
- 未发现 `@Transactional` 自调用导致失效的明确证据。
- Redis 分布式锁未发现直接释放他人锁的实现。
- MyBatis 动态排序使用白名单，未发现 `${}` 原样拼接。
- 批量 ID 查询后的业务顺序有显式恢复，不依赖数据库 `IN` 返回顺序。
- 文件存储路径使用规范化校验，未发现明确路径穿越。
- `.gitignore` 已覆盖环境文件、上传目录和常见构建产物。
- Controller 整体使用 DTO/VO，未发现系统性直接暴露持久化实体。

## 11. 尚未确认的信息

- “下载成功”应定义为开始输出流还是客户端完整接收，产品口径待确认。
- 同一用户是否允许在不同课程/标题下复用同一 fileId，现有文档与实现口径不完全一致。
- 重复取消收藏应幂等成功还是返回资源不存在，业务流与 API/测试冲突，待确认。
- 文件逻辑删除目前没有公开 HTTP 入口；其实际触发方式、恢复策略和清理策略待确认。
- 正式生产部署方式、Redis 高可用拓扑、日志采集/告警平台、容量目标、SLO 与数据规模未在仓库中提供。
- Redis 故障时普通接口和管理员接口分别应 fail-open、fail-closed 还是降级，安全策略待确认。
- 本轮未执行真实 Redis 的故障注入、容量压测和多实例并发测试。
- 仓库未提供容器、Kubernetes、Nginx 或正式部署清单；这不等于项目必须容器化。

