# 分支交接记录

## 2026-09-24 DEPLOY-05 服务器初始化与部署

- 分支：`deploy`；服务器部署身份固定为提交 `e554ec5619010e60c29ca3b9798c02cdcfb4c048`，未在服务器重新构建镜像。
- Ubuntu 24.04/amd64、2 核 2 GB、40 GB 系统盘和 1.9 GB Swap 基线通过；SSH 仅允许公钥普通账号登录，AppArmor、UFW、Docker、Compose 均启用。
- DEPLOY-04 发布包在服务器端完成 SHA-256 复核；前后端 immutable image ID 与本地证据一致，MySQL、Redis 使用 Compose 固定 digest。
- 生产 Secret 仅在服务器本地生成并保存在 `600` 权限的未跟踪 `deploy/.env`；四服务使用 `--no-build --pull never` 启动且全部 healthy。
- 本地和公网 IP + Host 头验证首页、登录页、Nginx、liveness、readiness 均为 HTTP 200；MySQL 最小权限、Redis 认证、内部端口隔离和三个命名卷通过。
- 宿主机重启后 Docker 与四容器自动恢复，卷和镜像身份保持不变，Swap 为 0、无 OOM。MySQL 稳态接近 512 MiB 容器上限，已列为后续重点容量监控项。
- 服务器发布目录保存了不含 IP 和 Secret 的 DEPLOY-05 验收证据。下一任务为 `DEPLOY-06` DNS 配置；尚未修改 DNS 或启用 HTTPS。

## 2026-09-24 DEPLOY-04 本地发布门禁

- 分支：`deploy`；提交 `1a8a4e36`、`e2aa4fa7`、`79fb3b2f`、`1e475b18`、`42b39a18` 已推送到 `origin/deploy`。
- 新增统一 Build、Compose、Migration、Rollback 演练脚本；证据写入系统临时目录，Secret、数据库导出和卷归档不进入仓库。
- 验证：后端 208/208、前端 76/76、双镜像构建、空卷与 SPA、readiness、最小权限、内部端口隔离、依赖故障恢复及三个持久卷重启保留均通过。
- 迁移：三份 SQL 首次执行和完整重跑通过，七张 legacy 表迁移前后全列摘要一致，七类错误结构和 Redis guard 均被正确拒绝。
- 回滚：停止前端/后端后联合备份 MySQL、Redis 完整数据卷和上传卷，恢复到新空卷后切点前 marker 全部存在、切点后 marker 全部不存在；固定 `64498230` 旧镜像通过 revision、健康及上传卷读写验证。
- 本地 Windows MySQL/Redis 服务未启动；所有 Docker project、卷、网络、临时历史镜像和含敏感数据的临时目录均已清理。下一任务为 `DEPLOY-05` 服务器初始化与部署，暂不配置 DNS。

## 2026-09-22 DEPLOY-03 生产安全收敛

- 分支：`deploy`；基线 `e9730f74`，提交 `3a2c5e96`、`9272af41`、`9a3c2d86`、`5dee7699`、`740a4d97`、`248bc29d` 已推送到 `origin/deploy`。
- 同源部署取消后端 CORS；Nginx 覆盖代理头，Tomcat 只信任私网代理，业务层统一使用解析后的 `remoteAddr`。
- `prod` 配置校验应用账号、MySQL/Redis 密码、JWT Secret、Secret 复用和磁盘保留水位。新 MySQL 空卷通过初始化脚本仅授予应用账号 `SELECT/INSERT/UPDATE`；旧卷不会自动执行，必须人工 `SHOW GRANTS` 并整改。
- readiness 分别探测 MySQL、Redis、上传目录写入和磁盘水位，失败返回 503；原 `/api/v1/health` 保持 liveness。Spring 优雅停机 30 秒，Compose 宽限 40 秒。
- 上传检查扩展名、客户端 MIME、服务端签名/文本/压缩包结构；OOXML 校验主内容类型与根关系，拒绝宏和外部关系。落盘使用容量水位、进程锁、同目录临时文件与原子移动，下载使用安全附件响应。
- 当前不含病毒扫描、历史文件重扫、严格用户累计配额、多实例共享存储或对象存储；2 GB 主机不运行重型扫描服务。
- 后端全量测试在隔离 MySQL 下 208/208 通过；专项安全补丁 26/26、配置/健康专项 6/6、前端 76/76 和生产构建通过。
- 真实空卷容器中 readiness 为 200；停止 Redis 后约 2.3 秒、停止 MySQL 后约 3.3 秒返回 503，恢复后自动回到 UP；应用账号仅有 `SELECT/INSERT/UPDATE`，Redis 未认证拒绝，内部服务无端口发布，优雅停机完成。测试容器、卷和镜像已清理。
- 下一任务为 `DEPLOY-04` 的最终镜像、迁移、持久化和回滚演练。

## 2026-09-22 DEPLOY-02 容器化部署资产

- 分支：`codex/deploy-02`；从 `dev@9e1e6d29` 创建，功能提交 `6449823` 已推送到同名远程分支。
- 新增后端和前端多阶段镜像、`deploy/docker-compose.yml`、非特权 Nginx 配置、`deploy/.env.example` 与 `deploy/README.md`；真实 `deploy/.env` 保持忽略且未提交。
- Compose 仅发布 Nginx 端口，内部运行后端、MySQL 8.4 与 Redis 7.4；数据库、Redis AOF 和 `/data/uploads` 使用独立命名卷。
- 2 GB 资源预算为后端 768 MB、MySQL 512 MB、Redis 160 MB、Nginx 64 MB；JVM 最大堆 512 MB，Hikari 最大连接 8，MySQL Buffer Pool 256 MB，Redis `maxmemory` 96 MB 且 `noeviction`。
- 供应链与权限：所有基础镜像固定 digest，Maven Wrapper 校验 SHA-256；后端、Redis、Nginx 均以非 root 用户运行；Secret 只由未提交环境文件注入。
- 验证：前端全量 31 文件 76/76；后端全量 169/169；镜像构建和 Compose 配置通过；空卷四服务健康；`/healthz`、API、SPA 路由通过；内部端口无宿主机绑定；容器重建后 MySQL、Redis、上传文件均保留。
- 本地 MySQL 服务没有被修改；后端数据库集成测试使用隔离的临时 MySQL 实例完成。下一任务为 `DEPLOY-03`。

## 2026-09-21 前端门户 UI 与响应式布局升级

- 分支：`dev`；不新增依赖、不改变 API 与权限边界，将前台、管理端、首页和认证页统一为靛蓝/紫色校园知识门户风格。
- 前台使用三段式吸顶导航；管理端使用桌面侧栏和移动端横向菜单；首页新增声明式关键词搜索、上传/浏览 CTA、真实平台能力标签与更清晰的热门榜单层级。
- 首页搜索支持点击和回车，空白关键词不携带 query；登录页消费路由守卫写入的站内 `redirect`，并拒绝 `//`、反斜杠、换行和非字符串目标，防止开放重定向。
- 可访问性补充：`lang=zh-CN`、语义化导航和搜索表单、可见焦点、榜单周期 `aria-pressed`、减弱动效支持；320px 管理员态通过保留品牌标识并隐藏品牌长文案避免溢出。
- 验证：UI 专项测试 5 文件 18/18、前端全量 31 文件 76/76、`npm run build`、`git diff --check` 均通过；真实浏览器桌面首屏和 375/320 设备指标宽度检查通过。
- 已知事项：Vite 构建仍提示主包约 844.05 kB 超过 500 kB；这是既有警告，本次未扩大为懒加载或拆包改造。

## 2026-08-13 资料有效状态条件唯一约束

- 分支：`dev`；`resource` 新增生成列 `active_duplicate_guard` 和唯一索引 `uk_resource_active_duplicate (uploader_id, file_id, active_duplicate_guard)`。
- 待审核/已通过资料的生成值固定为 `1`，从数据库层阻止同一用户、同一文件出现第二条有效资料；拒绝/下架/删除生成 `NULL`，允许后续重新提交。
- `ResourceServiceImpl.create` 保留前置查重，并把并发插入产生的 `DuplicateKeyException` 转换为 `DATA_DUPLICATE`。
- 存量库迁移为 `sql/migrations/20260813_resource_active_duplicate_guard.sql`，执行前检查有效重复组，支持重复执行；回滚顺序为先删唯一索引、再删生成列。
- 本机 MySQL 8.0.45 的 `campus_resource_platform` 已完成迁移，执行前 3 条资料、有效重复组 0；迁移前表备份位于系统临时目录，不纳入 Git。
- 验证：资料数据库/Service/Controller 专项测试 27/27、后端全量 169/169 通过；本机迁移连续执行两次成功，事务探针验证第二条有效资料被拒绝、无效资料可共存且回滚后无残留。

## 2026-08-07 文件 MD5 三态与负缓存

- 分支：`dev`；新增 `FileMd5CacheService` / `FileMd5CacheServiceImpl`，继续使用 `RedisKeyConstants.fileMd5Cache(md5, size)`，缓存正值为十进制 `fileId`，负值为固定 `NOT_FOUND`。
- 预检按 `FOUND` / `NOT_FOUND` / `ABSENT` 分流：FOUND 仍查用户授权，NOT_FOUND 不查文件表和授权表，ABSENT 回源 MySQL 并按结果写正缓存或 `SET NX` 负缓存。
- 正值 TTL 6 小时，负值 TTL 5 分钟；上传主链仍直查 MySQL，在新文件事务、既有文件授权及并发唯一键回退授权成功后用普通 `SET` 覆盖旧负值。Redis 读写异常不影响数据库主流程。
- 非正、溢出或协议外缓存值最佳努力删除；显式 `evict(md5, size)` 已提供，但没有真实文件删除/恢复入口调用，生命周期失效闭环留给 `BATCH-17`。
- 验证：`.\\mvnw.cmd -DskipTests compile` 通过；缓存专项测试 21/21、后端全量测试 173/173 通过。正缓存写失败不影响数据库及最终上传正确性，但旧负值可能造成最长 5 分钟的预检假阴性；真实 Redis/数据库并发集成与生命周期接线仍待后续完成。

## 2026-08-07 公开资料详情缓存

- 分支：`dev`；已实现 `crp:cache:resource:detail:{resourceId}`，缓存 `ResourceDetailVO` 公共 JSON，TTL 30 分钟 + 随机 0~5 分钟且 `favorited` 固定为 `null`。
- 公开详情采用 Cache Aside；ID/状态/用户态字段不合法或坏 JSON 时删除并回源，Redis 故障保持 MySQL 原错误语义，不做不存在/不可见负缓存。
- miss 回填和审核失效共享每资料锁并最多等待约 2 秒；竞争/Redisson 异常线程只回源不回填。审核提交后 `afterCommit` → `invalidate()` 持锁 DELETE（与回填互斥）；锁超时（2s）降级直接删除。
- Redis/Redisson 同时故障时 `deleteQuietly` 仅告警，缓存残留由 TTL（30~35分钟）兜底。
- 最终验证：缓存专项 34/34、资料/审核相关回归 74/74、Spring 上下文 1/1、后端全量 155/155 均通过；本功能将按项目规则提交并推送到 `dev`。

## 2026-08-05 多 Agent 项目初始化 Skill

- 个人 Skill 名称：`$multi-agent-project-bootstrap`，默认安装目录为 `$CODEX_HOME/skills/multi-agent-project-bootstrap`。
- 新项目中直接要求使用该 Skill，即可先 dry-run 再幂等创建或合并 `.codex/`、Agent 角色、根协作规则和操作文档。
- 当前项目已完成旧并发字段迁移：使用 `agents.max_concurrent_threads_per_session = 4`，该数量不包含主 Agent；`agents.enabled = true` 已显式启用。
- Skill 应用时保留了当前项目四个专属 Agent、根 `AGENTS.md` 和 `docs/08-multi-agent-collaboration.md`，没有覆盖项目业务规则。
- 后续升级 Skill 时，应先运行其 11 个回归测试和 `skill-creator/scripts/quick_validate.py`，再在临时仓库做前向测试。

## 2026-07-15 IMP-001、IMP-003、IMP-004、IMP-005

- 分支：`dev`；四个独立实现提交均已推送到 `origin/dev`。
- 提交：`8b4ae92`（JWT 密钥）、`024aff7`（审核文件读取）、`4ab0da0`（一次性下载票据）、`f07e704`（用户文件授权）。
- 发布前必须先执行 `sql/migrations/20260715_user_file_authorization.sql`，再部署 `f07e704` 及其后版本。
- 下载客户端必须把创建记录响应中的 `downloadTicket` 放入 `X-Download-Ticket` 请求头；旧客户端直接 GET 文件流将失败。
- IMP-004 仅处理重放、下架复核和 Redis 故障关闭，不含 IMP-008 的计数时点修正。
- 验证：后端全量 124/124、前端全量 66/66、前端生产构建均通过。

## 2026-07-14 Codex 多 Agent 协作配置

- 项目级 Agent 配置位于外层仓库 `.codex/`，不要放到内层 Spring Boot 工程。
- 已配置 `architect`（只读分析）、`implementer`（单写者实现）、`tester`（测试）和 `reviewer`（只读审查）。
- 默认最多 4 个 Agent 线程，只允许主 Agent 创建一级子 Agent；角色模型继承父会话，不锁定具体模型名称。
- 新任务应重新加载项目配置，并按 `docs/08-multi-agent-collaboration.md` 中的只读提示词验证角色是否可用。
- Subagent 共享同一工作区；提交时必须显式暂存本任务文件，保留工作区中的其他未提交改动。

## 2026-07-14 工作目录整理

- 文档入口改为 `docs/README.md`：数据库资料在 `docs/database/`，接口参考和 Postman 集合在 `docs/api/`，前后端业务模块映射在 `docs/modules/README.md`。
- 后续引用 API 文档时使用 `docs/api/api-reference.md`；导入 Postman 时使用 `docs/api/postman/` 下的集合和本地环境示例。
- 未移动 `campus-resource-platform/data/` 下的运行时上传资料，也未删除本地工具、构建或依赖缓存目录。
- 已通过 Markdown 本地链接检查、前端全量单测与构建、后端跳过测试编译；本次仅调整文档与接口调试资料路径。

## 2026-07-14 排行榜与定时任务补强

- 已完成 P1：`RankingMapperIntegrationTest` 在 H2 MySQL 模式下验证 `ResourceMapper.xml` 的公开资料过滤、热度兜底排序、主键游标扫描和 APPROVED 热度快照更新。
- 已完成 P2：`RankingTaskExecutionMonitor` 以当前应用实例内最近快照记录下载增量同步、all 榜重建和热度快照任务的开始/结束时间、耗时及未捕获异常类型；调度入口已接入统一日志，任务触发与监控单测共 4 个用例通过。
- 已完成 P3：下载增量以 UUID 批次隔离，`download_delta_sync_item` 的 `(batch_id, resource_id)` 唯一键与 `resource.download_count` 累加同事务提交；Redis 确认失败后重试同批次不会重复计数。建表已写入 `sql/init.sql`，存量库执行 `sql/migrations/20260714_download_delta_sync_idempotency.sql`；P3 专项 13 个、排行榜关联 19 个及当前全量 113 个测试均通过。
- 发布 P3 前必须先排空或人工核对旧 `crp:stats:resource:download:syncing:active` Hash；切换期间停止旧版本调度器，避免旧、新批次协议并行。后续如需继续，应单独设计跨实例指标聚合、历史持久化、管理员查询接口、失败告警和幂等明细保留期清理；不应直接复用进程内快照作为跨实例运维数据。

## 当前分支

| 项目 | 内容 |
| --- | --- |
| 分支 | `dev` |
| 分支用途 | 项目日常迭代与前端演示模块开发 |
| 稳定分支 | `main`，不直接提交未稳定的前端功能 |
| 当前前端队列 | `docs/frontend/05-frontend-task-queue.md` |
| 当前前端任务 | 无；前端演示队列已完成 |

## 已完成前端文档

| 文档 | 内容 |
| --- | --- |
| `docs/frontend/01-frontend-requirements.md` | 前端演示范围、普通用户与管理员页面、后端亮点和非目标 |
| `docs/frontend/02-page-design.md` | 页面路由、字段、操作与接口设计 |
| `docs/frontend/03-api-mapping.md` | 27 个后端接口的前端 API 映射与文档缺失说明 |
| `docs/frontend/04-frontend-dev-plan.md` | F01-F47 增量开发计划 |
| `docs/frontend/05-frontend-task-queue.md` | T01-T47 自动推进任务队列 |

## 前端开发规则

- 前端只服务于后端项目演示，使用 Vue 3 + Vite + Element Plus + Axios。
- 每个子任务完成后必须运行专项测试和 `npm run build`，再独立提交并推送。
- 自动推进规则仅适用于 `frontend/` 下前端代码任务；涉及后端、SQL、Redis 或接口变更必须停止。
- 每轮自动推进最多执行用户指定的阶段数；本轮最多三个阶段。
- `frontend/` 已完成 T01-T47：真实验证认证、文件秒传、待审核创建、管理员审核、公开详情、权限边界、退出 Token 黑名单，以及搜索、收藏、下载、排行榜和管理员下架闭环。用户提供的 PDF 保持为 `APPROVED` 演示资料；另一个测试资料已按 T46 验证下架、公开隔离和审计流水。运行手册、锁定依赖安装、全量测试、构建和 Vite 启动验证也已完成。

## 工作区注意事项

- 开始前先运行 `git branch --show-current`、`git status --short --branch` 和 `git log -5 --oneline --decorate`。
- 工作区中如出现与当前前端任务无关的修改，必须保留，不得暂存或覆盖。
- `AGENTS.md` 可能因本地行尾格式显示为修改；如无内容差异，不得将其混入前端子任务提交。

## 下一位执行者

1. 前端自动任务队列已完成，不再自动创建新的前端任务。
2. 后续开发先读取根 `AGENTS.md`、本文件、`docs/CURRENT_STATUS.md` 与相关功能文档。
3. 继续保持前端仅作为后端演示界面，不记录真实密码、Token、数据库连接信息、绝对上传路径或其他敏感内容。

## 2026-07-13 前端修复与浏览器验收

- 修复 `VITE_API_BASE_URL` 默认值和开发示例配置，将 `/api` 对齐为后端实际版本化路径 `/api/v1`；Vite 仍通过相对路径转发到本机后端。
- 已在真实 Edge 中完成游客、普通用户、管理员流程验收。测试账号仅在本机临时环境变量中使用，未写入仓库。
- 前端全量单测基线更新为 31 个文件、66 个用例；提交前应再次执行 `npm run test:unit` 和 `npm run build`。

## 2026-07-13 项目运行手册

- 新增 `docs/07-project-runbook.md`，作为 MySQL、Redis、后端、前端、联调与排查的统一启动入口；根 `README.md` 已链接该手册。
- 手册明确 `VITE_API_BASE_URL=/api/v1`、后端健康检查和运行时上传目录规则；未写入任何真实敏感信息。
