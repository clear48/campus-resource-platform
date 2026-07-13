# 分支交接记录

## 当前分支

| 项目 | 内容 |
| --- | --- |
| 分支 | `dev` |
| 分支用途 | 项目日常迭代与前端演示模块开发 |
| 稳定分支 | `main`，不直接提交未稳定的前端功能 |
| 当前前端队列 | `docs/frontend/05-frontend-task-queue.md` |
| 当前前端任务 | `T45`：验证搜索收藏下载闭环 |

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
- `frontend/` 已完成 T01-T44：真实验证认证、文件秒传、待审核创建、管理员审核、公开详情、权限边界和退出 Token 黑名单。用户授权初始化了最小分类和管理员账号；用户提供的 PDF 已作为 `APPROVED` 演示资料保存到 `data/user-uploads`。前端全量测试 65/65、构建和后端编译均通过；当前可继续 T45。

## 工作区注意事项

- 开始前先运行 `git branch --show-current`、`git status --short --branch` 和 `git log -5 --oneline --decorate`。
- 工作区中如出现与当前前端任务无关的修改，必须保留，不得暂存或覆盖。
- `AGENTS.md` 可能因本地行尾格式显示为修改；如无内容差异，不得将其混入前端子任务提交。

## 下一位执行者

1. 读取根 `AGENTS.md`、本文件、`docs/CURRENT_STATUS.md`、前端任务队列和开发计划。
2. 读取队列中 T45，使用已审核通过的 PDF 演示资料验证公开搜索、收藏和两步下载。
3. 若联调发现需修改后端、接口、数据库结构或 Redis Key，立即停止；否则完成 T45 后继续 T46。
