# 文档目录与归属

本文档用于定位项目资料，避免把业务文档、接口调试资料和运行记录混放在仓库根目录。

| 目录或文件 | 归属 | 用途 |
| --- | --- | --- |
| `01-requirements.md`、`02-business-flow.md` | 项目总体设计 | 需求边界与核心业务流程 |
| `database/` | 数据库模块 | 表结构设计、数据库变更记录 |
| `api/` | 接口模块 | REST API 参考、Postman 手工验收集合与本地环境示例 |
| `05-redis-design.md` | 跨模块基础设施 | Redis Key、数据结构、TTL 与一致性策略 |
| `frontend/` | 前端演示模块 | 前端需求、页面设计、接口映射、开发计划与任务队列 |
| `modules/` | 后端业务模块 | 认证、分类、文件、资料、审核、搜索、下载、收藏、排行榜的开发过程记录 |
| `interview/` | 面试复盘 | 项目背景、业务流程、技术栈深挖、高频问答与诚实改进边界 |
| `06-project-progress.md` | 项目总体协作 | 已实现能力、测试记录和进度 |
| `07-project-runbook.md` | 项目运行维护 | 前后端启动、联调、验收与排查 |
| `08-multi-agent-collaboration.md` | Codex 协作配置 | 项目级 Subagent 角色、调度流程、权限、验证与排查 |
| `CURRENT_STATUS.md`、`BRANCH_HANDOFF.md` | 当前协作状态 | 当前分支、任务边界与交接信息 |
| `AGENTS.md` | 项目协作规范 | 开发、测试、文档与提交要求 |

## 接口资料

- API 参考：[`api/api-reference.md`](api/api-reference.md)
- Postman 集合：[`api/postman/campus-resource-platform.postman_collection.json`](api/postman/campus-resource-platform.postman_collection.json)
- Postman 本地环境示例：[`api/postman/campus-local.postman_environment.json`](api/postman/campus-local.postman_environment.json)

Postman 本地环境文件只保留可公开的示例变量；真实账号、Token、密码和主机配置仍应放在本机环境中，不能提交到仓库。

## 业务模块入口

业务模块与后端 Controller、前端演示页面的对应关系见 [`modules/README.md`](modules/README.md)。新增或修改业务能力时，应先进入对应模块文档，再同步相关 API、数据库或 Redis 文档。

## 面试复盘入口

- 面试复盘总索引：[`interview/README.md`](interview/README.md)
- 文档生成与维护计划：[`interview/00-document-generation-plan.md`](interview/00-document-generation-plan.md)
- 项目背景与价值表达：[`interview/01-project-background-and-value.md`](interview/01-project-background-and-value.md)
- 模块与业务流程：[`interview/02-modules-and-business-flows.md`](interview/02-modules-and-business-flows.md)
- Spring Boot、MyBatis、Redis、MySQL 技术栈复盘：[`interview/03-technology-stack-review.md`](interview/03-technology-stack-review.md)
- 高频问题与回答提纲：[`interview/04-interview-question-bank.md`](interview/04-interview-question-bank.md)
- 局限与演进路线：[`interview/05-limitations-and-improvement-roadmap.md`](interview/05-limitations-and-improvement-roadmap.md)
