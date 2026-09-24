# 当前项目状态

## DEPLOY-05 服务器初始化与部署（2026-09-24）

- 中国香港服务器已完成 Ubuntu 24.04/amd64、2 核 2 GB、40 GB 系统盘与 1.9 GB Swap 基线核对；SSH 已禁用 root、密码和键盘交互登录，AppArmor 与 UFW 正常启用。
- Docker Engine `29.8.1`、Compose `v5.5.1` 已从官方 APT 仓库安装并设为开机启动；普通运维账号未加入 `docker` 组。
- 服务器检出冻结提交 `e554ec5619010e60c29ca3b9798c02cdcfb4c048`，发布包全部 SHA-256 校验通过；前后端镜像 ID 与 DEPLOY-04 证据一致，MySQL、Redis 镜像按固定 digest 拉取。
- 生产 Secret 已在服务器本地独立生成并写入权限 `600` 的未跟踪 `deploy/.env`，未输出到终端、聊天或仓库；Compose 通过 `--no-build --pull never` 启动。
- 四个服务均 healthy；首页、登录页、`/healthz`、liveness、readiness 在服务器本地及公网 IP + Host 头检查中均为 HTTP 200。MySQL 应用账号只有 `SELECT/INSERT/UPDATE`，Redis 未认证访问被拒绝。
- 宿主机仅发布 80，后端 8080、MySQL 3306、Redis 6379 未发布；MySQL、Redis、上传目录使用三个独立命名卷。宿主机重启后 Docker、UFW、四容器、卷、镜像身份和健康检查均恢复正常。
- 重启后磁盘剩余约 30 GB、Swap 未使用、无内核或容器 OOM 事件。MySQL 稳态约占其 512 MiB 上限的 96%，cgroup 未出现 `max/oom/oom_kill`，后续需持续监控峰值并在出现内存事件、持续 Swap 或重启时扩容或重新评估预算。
- 下一部署任务为 `DEPLOY-06` DNS 配置；当前尚未修改 DNS，也未开始 HTTPS、完整公网业务验收或生产联合备份恢复。

## DEPLOY-04 本地发布门禁（2026-09-24）

- 当前分支为 `deploy`；候选构建、Compose 隔离运行、旧库迁移、联合恢复和上一版镜像回滚演练均已完成。
- 构建验证为后端 208/208、前端 76/76；Compose 验证覆盖空卷、readiness、最小权限、内部端口隔离、依赖故障恢复和三个持久卷重启保留。
- 三份迁移首次执行及完整重跑通过，七张 legacy 表全列摘要保持一致；Redis legacy guard、七类错误结构和失败路径证据门禁通过。
- MySQL、Redis 完整 AOF 数据卷和上传卷在同一停写切点联合恢复到新空卷；三联 marker、固定 `64498230` 旧镜像、健康接口及上传卷读写兼容验证通过。
- 实现提交：`1a8a4e36`、`e2aa4fa7`、`79fb3b2f`、`1e475b18`、`42b39a18`，均已推送到 `origin/deploy`。下一部署任务为 `DEPLOY-05` 服务器初始化与部署。

## DEPLOY-03 生产安全收敛（2026-09-22）

- 当前分支为 `deploy`，DEPLOY-03 已完成可信代理、同源 CORS 边界、生产 Secret 校验、MySQL 最小权限、依赖 readiness、优雅停机和上传安全边界。
- Nginx 覆盖并清洗转发头；Tomcat 仅解析私网可信代理，下载限流只使用 `request.remoteAddr`。正式前后端同源，后端不再开放 CORS。
- `prod` 启动会拒绝 root MySQL 账号、弱密码、Secret 复用和无效磁盘保留水位；新 MySQL 空卷仅授予应用账号 `SELECT/INSERT/UPDATE`，旧卷需人工核对授权。
- 新增 `GET /api/v1/health/readiness`，真实检查 MySQL、Redis、上传目录写入和磁盘水位；Spring 优雅停机 30 秒，Compose 停止宽限期 40 秒。
- 上传新增扩展名、MIME、文件签名、严格 UTF-8、ZIP bomb 与 OOXML 内容类型/关系/宏边界校验；容量不足返回 HTTP 507，落盘采用 `.part` 与原子移动。下载固定安全附件响应。
- 功能提交：`3a2c5e96`、`9272af41`、`9a3c2d86`、`5dee7699`、`740a4d97`、`248bc29d`，均已推送到 `origin/deploy`。
- 已验证后端全量 208/208、前端 76/76 和生产构建；真实空卷容器验证覆盖最小权限、Redis 认证、依赖故障 503/恢复和优雅停机。后继任务 `DEPLOY-04` 已完成。

## DEPLOY-02 容器化部署资产（2026-09-22）

- 分支 `codex/deploy-02` 已补齐后端/前端多阶段 Dockerfile、Docker Compose、非特权 Nginx、生产环境变量示例和完整部署说明；实现提交 `6449823` 已推送。
- Compose 仅发布 Nginx 端口，MySQL、Redis 和后端保持容器网络内访问；三个命名卷分别保存数据库、Redis AOF 和上传文件。
- 针对 2 GB 演示服务器设置后端 `768 MB`、MySQL `512 MB`、Redis `160 MB`、Nginx `64 MB` 容器上限，并限制 JVM、Hikari、MySQL Buffer Pool/连接数和 Redis 数据内存。
- Java、Node、Nginx、MySQL、Redis 镜像固定版本与 digest，Maven Wrapper 增加 SHA-256 校验；Redis、后端和 Nginx 均以非 root 用户运行。
- 验证通过：前端全量 76/76、后端全量 169/169、双镜像构建、空卷启动、HTTP/API/SPA 路由、内部端口隔离及 MySQL/Redis/上传卷重建持久化。
- 该阶段的后继任务 `DEPLOY-03`、`DEPLOY-04` 已在 `deploy` 分支完成；当前下一任务为 `DEPLOY-05`。

## 前端门户 UI 与响应式布局升级（2026-09-21）

- 前台升级为校园知识门户视觉：三段式吸顶导航、品牌 Hero、首页关键词搜索、上传/浏览入口与分层热门榜单；登录和注册页升级为品牌说明区 + 表单卡双栏布局。
- 管理端升级为桌面深色侧栏 + 内容工作区，小屏自动退化为横向管理导航；全局统一品牌色、卡片、表格、表单、焦点态、窄屏表格滚动和减弱动效规则。
- 首页搜索支持点击与回车提交，关键词会去除首尾空格；游客从受保护入口登录后可返回原站内路径，非法外部或异常 `redirect` 会安全回退首页。
- 本次未新增依赖，未修改后端接口、数据库、Redis、JWT 或角色规则；前端专项测试 18/18、全量测试 76/76、生产构建通过。
- 已使用真实浏览器检查 1440px 首屏，并通过设备指标模拟核对 375px、320px 页面宽度；保留既有主包约 844.05 kB、超过 500 kB 的构建警告，代码拆分不在本次 UI 范围内。

## 资料有效状态条件唯一约束（2026-08-13）

- `resource` 新增生成列 `active_duplicate_guard`：待审核/已通过生成 `1`，拒绝/下架/删除生成 `NULL`。
- 唯一索引 `uk_resource_active_duplicate (uploader_id, file_id, active_duplicate_guard)` 作为并发重复提交的数据库最终兜底；Service 保留前置查重，并把插入唯一键冲突转换为 `DATA_DUPLICATE`。
- 已新增可重复迁移 `sql/migrations/20260813_resource_active_duplicate_guard.sql`；迁移遇到存量有效重复组会主动中止，不自动修改业务数据。
- 本机 MySQL 8.0.45 的 `campus_resource_platform` 已完成迁移并重复执行验证；迁移前 `resource` 备份保存在系统临时目录。
- H2 MySQL 模式已同步生成列/唯一索引，资料专项回归 27/27、后端全量 169/169 通过。

## 文件 MD5 三态与负缓存（2026-08-07）

- 已抽取 `FileMd5CacheService` 统一管理 `crp:cache:file:md5:{fileMd5}:{fileSize}`；正值为十进制 `fileId`，固定负哨兵为 `NOT_FOUND`，读取结果为 `FOUND` / `NOT_FOUND` / `ABSENT` 三态。
- 正缓存 TTL 6 小时且用普通 `SET` 覆盖旧负值；数据库确实未命中后才用 `SET NX` 写 5 分钟负缓存，避免覆盖并发上传刚提交的正值。
- 预检负缓存命中不查 `file_info` 和授权表；正缓存命中仍查当前用户授权。上传主链继续直查 MySQL，事务/授权成功后才写正缓存；Redis 异常不影响数据库及最终上传正确性，但正值写失败时旧负值可能造成最长 5 分钟的预检假阴性。
- 非正、溢出和协议外缓存值会被最佳努力删除并按 ABSENT 回源；已提供显式 `evict`，但真实文件删除/恢复生命周期尚未接入，留给 `BATCH-17`。
- 本次未改接口、数据库结构、Mapper、SQL、Controller、前端或依赖；编译通过，缓存专项测试 21/21、后端全量测试 173/173 通过。

## 公开资料详情缓存（2026-08-07）

- `GET /api/v1/resources/{resourceId}` 已接入 `crp:cache:resource:detail:{resourceId}` Cache Aside，缓存值为 `ResourceDetailVO` 公共 JSON，`favorited` 固定为 `null`，TTL 为 30 分钟 + 随机 0-5 分钟。
- 缓存读取会拒绝 ID 不匹配、非 `APPROVED`、用户态字段非空和坏 JSON；Redis 读写/删除异常只告警并回源 MySQL，不做负缓存。
- miss 回填与审核失效共享 `crp:lock:cache:resource:detail:{id}`；最多等待约 2 秒，持锁后二次检查，竞争/Redisson 异常路径只回源不回填。
- 审核状态提交后 `afterCommit` → `invalidate()` 持锁 DELETE；与 `getOrLoad()` 回填互斥，锁超时（2s）降级直接删除，Redis 不可用仅告警，依赖 TTL（30~35分钟）兜底。
- 最终验证已完成：缓存专项 34/34、资料/审核相关回归 74/74、Spring 上下文 1/1、后端全量 155/155 均通过。

## 多 Agent 项目初始化 Skill（2026-08-05）

- 已创建个人 Skill `$multi-agent-project-bootstrap`，用于在后续代码仓库中幂等安装 `architect`、`implementer`、`tester`、`reviewer` 四类 Agent。
- Skill 提供 dry-run、字段级 TOML 合并、项目专属 Agent 保留、路径边界检查、冲突零写入、预检快照和失败回滚，并通过 11 个脚本回归测试及官方 Skill 校验。
- 已将 Skill 应用到当前仓库：`max_threads = 4` 已迁移为 `max_concurrent_threads_per_session = 4`，并显式设置 `agents.enabled = true`。
- 当前四个项目专属 Agent、根 `AGENTS.md` 和既有协作手册均被识别并保留，没有修改 Java、Vue、数据库或 Redis 业务实现。

## 2026-07-15 改进项实施状态

- 已完成并推送 IMP-001、IMP-003、IMP-004、IMP-005；其余审计项未获本轮实施授权。
- JWT 启动时拒绝缺失、已知默认值和不足 32 字节的密钥。
- 管理员可通过受控接口读取仍处于 `PENDING_REVIEW` 的待审核文件。
- 下载文件流要求 60 秒一次性 Redis 票据，原子消费并在取流前复核资料仍为 `APPROVED`。
- 新增 `user_file_authorization`；存量库发布前执行 `sql/migrations/20260715_user_file_authorization.sql`，并人工核查迁移末尾列出的历史跨用户引用。
- 本轮不包含 IMP-008，下载成功计数时点保持现状。
- 全量回归：后端 `mvnw.cmd test` 124/124 通过；前端 `npm run test:unit` 66/66 通过；前端 `npm run build` 通过（保留既有主包体积警告）。

## Codex 多 Agent 协作配置（2026-07-14）

- 新增项目级 `.codex/config.toml`，限制最多 4 个 Agent 线程和 1 层派生深度。
- 新增 `architect`、`implementer`、`tester`、`reviewer` 四个自定义 Agent，采用并行只读分析、单写者实现、测试与审查并行、主 Agent 集成的流程。
- 根 `AGENTS.md` 与 `docs/AGENTS.md` 已补充触发条件、文件所有权、停止条件和协作输出要求。
- 完整启用、提示词、权限注意事项、Worktree 边界与排查步骤见 `docs/08-multi-agent-collaboration.md`。
- 本次只涉及 Codex 配置和项目文档，不修改 Java、Vue、接口、数据库或 Redis 业务实现。

## 工作目录整理（2026-07-14）

- 数据库设计文档已归入 `docs/database/database-design.md`；接口参考与 Postman 手工验收资料已归入 `docs/api/`。
- 新增 `docs/README.md` 说明文档归属，新增 `docs/modules/README.md` 建立业务模块、后端入口与前端演示入口的对应关系。
- 后端工程目录、前端工程目录、`sql/` 与运行时用户上传资料未移动；本次不修改 Java 代码、接口、数据库结构或 Redis 配置。
- 已通过 Markdown 本地链接检查、`npm run test:unit`（31 文件、66 用例）、`npm run build` 与 `mvnw.cmd -DskipTests compile` 验证。

## 排行榜与定时任务补强（2026-07-14）

- 补强 P1 已完成：新增 `RankingMapperIntegrationTest`，真实验证排行榜候选公开过滤、MySQL 兜底排序、游标扫描和热度快照写回；`mvnw.cmd -Dtest=RankingMapperIntegrationTest test` 3 个用例通过。
- 补强 P2 已完成：新增进程内 `RankingTaskExecutionMonitor`，统一记录下载增量同步、all 榜重建、热度快照三个入口的最近开始/结束时间、耗时和未捕获异常类型；`mvnw.cmd "-Dtest=RankingTaskExecutionMonitorTest,RankingSyncTaskTest,HotRankingMaintenanceTaskTest" test` 4 个用例通过。
- 补强 P3 已完成：下载增量同步改为 UUID 隔离批次，通过 `download_delta_sync_item` 的 `(batch_id, resource_id)` 唯一键与同事务下载量累加实现幂等；已提供初始化建表、可重复迁移和 H2 MyBatis 集成测试。P3 专项 13 个、排行榜关联 19 个及当前全量 113 个测试均通过。
- 本次未新增依赖、HTTP 接口或前端改动；新增 Redis current 批次指针和幂等明细表。发布前必须排空或人工核对旧 `syncing:active` Hash，且切换窗口不得让旧、新版本调度器并行执行。

## 基本信息

| 项目 | 当前状态 |
| --- | --- |
| 项目名称 | 校园资料共享与检索平台 |
| 当前分支 | `deploy` |
| 当前后端状态 | 认证、分类、文件、资料、审核、搜索、下载、收藏、排行榜与定时任务均已完成首版 |
| 当前前端状态 | 已完成 T01-T47：轻量前端演示、三条真实后端联调链路、运行说明与最终验证记录均已完成 |
| 当前自动队列 | `docs/frontend/05-frontend-task-queue.md`，47 个任务均为 `DONE` |

## 当前工作

- 目标：使用 Vue 3 + Vite + Element Plus + Axios 构建轻量演示前端。
- 执行方式：仅在用户明确要求时，按前端任务队列自动推进；每个子任务独立测试、提交并推送。
- 本轮上限：最多推进三个开发阶段，具体以用户本轮指令为准。

## 当前前端任务边界

| 项目 | 说明 |
| --- | --- |
| 允许范围 | `frontend/` 前端代码、前端测试、前端运行说明，以及队列要求的前端进度文档 |
| 不允许范围 | 后端 Java、SQL、数据库、Redis、接口路径和其他业务模块 |
| 当前任务 | 无；前端演示队列已完成 |
| 后续任务 | 无；等待新的用户需求 |
| 停止条件 | 见 `docs/frontend/05-frontend-task-queue.md` 的“停止条件” |

## 已知事项

- 前端未创建前，不应假设任何前端目录、依赖、路由或页面已存在。
- 搜索建议接口当前未实现，不能进入首版页面调用链。
- 前端权限控制只负责入口提示；JWT、管理员角色和资源访问权限以后端为最终边界。

## 最近验证记录

| 范围 | 命令/方式 | 结果 |
| --- | --- | --- |
| 后端现有代码 | 见 `docs/06-project-progress.md` 与各模块文档 | 已有后端验证记录 |
| 前端 T01 | `npm run build`；Vite 开发服务 HTTP 请求 | 构建通过；`http://127.0.0.1:5173/` 返回 HTTP 200 |
| 前端 T02 | `npm run build`；Vite 开发服务 HTTP 请求 | Element Plus 构建通过；`http://127.0.0.1:5173/` 返回 HTTP 200 |
| 前端 T03 | `npm run build`；Vite 首页和未知路径请求 | 构建通过；两条路径均返回 HTTP 200 SPA 入口 |
| 前端 T04 | `npm run build`；Vite 首页请求；8080 端口探测 | 构建与 Vite 启动通过；后端端口未监听，真实代理联调待补 |
| 前端 T05 | `npm run build` | Axios 请求层构建通过；专项 Mock 测试待 T06 建立 |
| 前端 T06 | `npm run test:unit -- src/utils/request.test.ts`；`npm run build` | 请求层测试 3/3 通过；构建通过 |
| 前端 T07 | `npm run test:unit -- src/utils/format.test.ts`；`npm run build` | 枚举和格式化测试 4/4 通过；构建通过 |
| 前端 T08 | `npm run test:unit -- src/api/auth.test.ts`；`npm run build` | 认证 API 测试 3/3 通过；构建通过 |
| 前端 T09 | `npm run test:unit -- src/views/LoginView.test.ts`；`npm run build` | 登录页组件测试 1/1 通过；后端未启动，真实登录待联调 |
| 前端 T10 | `npm run test:unit -- src/views/RegisterView.test.ts`；`npm run build` | 注册页组件测试 1/1 通过；后端未启动，真实注册待联调 |
| 前端 T11 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 会话与当前用户 API 测试 2/2 通过；构建通过 |
| 前端 T12 | `npm run test:unit -- src/views/user/ProfileView.test.ts`；`npm run build` | 个人信息页组件测试 1/1 通过；真实退出待后端启动后联调 |
| 前端 T13 | `npm run test:unit -- src/api/rankings.test.ts`；`npm run build` | 排行榜 API 测试 2/2 通过；构建通过 |
| 前端 T25 | `npm run test:unit -- src/views/UploadView.test.ts`；`npm run build` | 上传页组件测试 2/2 通过；可填写资料字段，未取得 `fileId` 时不能提交 |
| 前端 T26 | `npm run test:unit -- src/views/UploadView.test.ts`；`npm run build` | 上传页组件测试 3/3 通过；Mock 验证创建请求、待审核结果和我的上传入口 |
| 前端 T27 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 用户 API 测试 3/3 通过；Token、状态和分页 query 正确 |
| 前端 T28 | `npm run test:unit -- src/views/user/MyUploadsView.test.ts`；`npm run build` | 我的上传页面组件测试 1/1 通过；资料状态和拒绝原因正确展示 |
| 前端 T29 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 用户 API 测试 4/4 通过；收藏列表 Token 和分页参数正确 |
| 前端 T30 | `npm run test:unit -- src/views/user/MyFavoritesView.test.ts`；`npm run build` | 我的收藏页面组件测试 1/1 通过；取消收藏后重新加载列表 |
| 前端 T31 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 用户 API 测试 5/5 通过；下载记录 Token 和分页参数正确 |
| 前端 T32 | `npm run test:unit -- src/views/user/MyDownloadsView.test.ts`；`npm run test:unit`；`npm run build` | 页面测试 1/1、全量测试 45/45 和构建通过；再次下载两步链路正确 |
| 前端 T33 | `npm run test:unit -- src/api/admin/resources.test.ts`；`npm run build` | 管理员资料 API 测试 2/2 通过；待审核列表和审核流水请求正确 |
| 前端 T34 | `npm run test:unit -- src/views/admin/ReviewManagementView.test.ts`；`npm run build` | 待审核页面测试 1/1 通过；只读列表和审核流水正确 |
| 前端 T35 | `npm run test:unit -- src/api/admin/resources.test.ts`；`npm run build` | 管理员资料 API 测试 4/4 通过；审核通过和拒绝请求正确 |
| 前端 T36 | `npm run test:unit -- src/views/admin/ReviewManagementView.test.ts`；`npm run build` | 待审核页面测试 3/3 通过；确认、拒绝校验和 409 错误展示正确 |
| 前端 T37 | `npm run test:unit -- src/views/admin/PublishedResourcesView.test.ts`；`npm run build` | 发布资料页面测试 1/1 通过；仅渲染公开搜索返回的已通过资料 |
| 前端 T38 | `npm run test:unit -- src/api/admin/resources.test.ts`；`npm run build` | 管理员资料 API 测试 5/5 通过；下架请求正确 |
| 前端 T39 | `npm run test:unit -- src/views/admin/PublishedResourcesView.test.ts`；`npm run build` | 发布资料页面测试 2/2 通过；下架和审核流水正确 |
| 前端 T40 | `npm run test:unit -- src/api/admin/rankings.test.ts`；`npm run build` | 管理员排行榜 API 测试 1/1 通过；无参数和 null 响应正确 |
| 前端 T41 | `npm run test:unit -- src/views/admin/RankingManagementView.test.ts`；`npm run build` | 排行榜运维页面测试 1/1 通过；榜单周期和重建确认正确 |
| 前端 T42 | `npm run test:unit -- src/router/index.test.ts`；`npm run build` | 路由守卫测试 3/3 通过；游客、普通用户和管理员跳转正确 |
| 前端 T43 | `npm run test:unit -- src/layouts/DefaultLayout.test.ts src/layouts/AdminLayout.test.ts src/views/HomeView.test.ts`；`npm run test:unit`；`npm run build` | 专项测试 6/6、全量测试 65/65 与构建通过；固定导航和排行榜失败重试正确 |
| 前端 T44 | 真实 HTTP 联调；`npm run test:unit`；`npm run build`；`mvnw.cmd -DskipTests compile` | 注册、登录、MD5 预检、首次上传、秒传、待审核创建、管理员审核、公开详情、403 边界和退出 Token 401 均通过；用户 PDF 已审核通过并保存在 `data/user-uploads` |
| 前端 T45 | 真实 HTTP 联调；公开搜索、收藏、两步下载、重复下载与限流 | 已审核 PDF 可搜索；待审核资料不泄露；收藏状态和列表一致；下载文件 MD5 一致；首次 `counted=true`、重复 `counted=false`、限流 HTTP 429 |
| 前端 T46 | 真实 HTTP 联调；排行榜、总榜重建、下架和角色边界 | PDF 已进入热榜，`UML` 进入日热词榜；普通用户管理员接口 403；管理员重建成功；测试资料下架后退出详情、搜索、热榜并有审计记录；PDF 保持公开 |
| 前端 T47 | `npm ci`、`npm run test:unit`、`npm run build`、临时 Vite 启动 | 锁定依赖安装成功且审计 0 漏洞；31 个测试文件、65 个用例通过；构建通过；首页 HTTP 200，临时进程已关闭 |

## 下一步

执行 `DEPLOY-06`：将 `campusshare.online` 的 A 记录指向已完成 DEPLOY-05 验收的服务器，并配置 `www` CNAME；DNS 生效前不开始 DEPLOY-07 HTTPS。

## 前端浏览器验收修复记录（2026-07-13）

- 已修正前端请求基地址：开发环境和缺省值统一为 `/api/v1`，避免浏览器把请求发送到后端不存在的 `/api/*` 路径。
- 已补充请求层回归断言，并将 `.env.example` 作为可提交的示例配置纳入前端工程；真实 `.env.development` 继续忽略，不记录敏感信息。
- 真实 Edge 验收通过：游客首页热榜与公开搜索；普通用户登录、收藏切换、下载提示与管理员路由拦截；管理员总榜重建、已发布资料列表和待审核资料页面。
- 后端健康检查 `GET /api/v1/health` 通过；用户提供的 UML PDF 保持 `APPROVED` 公开演示状态。

## 项目运行手册（2026-07-13）

- 新增 `docs/07-project-runbook.md`，集中说明 MySQL 初始化、Redis 本机或虚拟机连接、后端环境变量与 Maven Wrapper 启动、前端 Vite 代理、健康检查、浏览器验收、测试构建和常见排查。
- 根 `README.md` 已加入运行手册入口。手册只提供变量名和占位符，不记录真实密码、Token、密钥或测试账号。
