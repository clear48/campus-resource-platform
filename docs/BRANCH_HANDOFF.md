# 分支交接记录

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
