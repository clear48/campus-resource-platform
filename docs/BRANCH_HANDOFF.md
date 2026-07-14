# 分支交接记录

## 2026-07-14 工作目录整理

- 文档入口改为 `docs/README.md`：数据库资料在 `docs/database/`，接口参考和 Postman 集合在 `docs/api/`，前后端业务模块映射在 `docs/modules/README.md`。
- 后续引用 API 文档时使用 `docs/api/api-reference.md`；导入 Postman 时使用 `docs/api/postman/` 下的集合和本地环境示例。
- 未移动 `campus-resource-platform/data/` 下的运行时上传资料，也未删除本地工具、构建或依赖缓存目录。
- 已通过 Markdown 本地链接检查、前端全量单测与构建、后端跳过测试编译；本次仅调整文档与接口调试资料路径。

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
