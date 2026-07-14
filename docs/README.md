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
| `06-project-progress.md` | 项目总体协作 | 已实现能力、测试记录和进度 |
| `07-project-runbook.md` | 项目运行维护 | 前后端启动、联调、验收与排查 |
| `CURRENT_STATUS.md`、`BRANCH_HANDOFF.md` | 当前协作状态 | 当前分支、任务边界与交接信息 |
| `AGENTS.md` | 项目协作规范 | 开发、测试、文档与提交要求 |

## 接口资料

- API 参考：[`api/api-reference.md`](api/api-reference.md)
- Postman 集合：[`api/postman/campus-resource-platform.postman_collection.json`](api/postman/campus-resource-platform.postman_collection.json)
- Postman 本地环境示例：[`api/postman/campus-local.postman_environment.json`](api/postman/campus-local.postman_environment.json)

Postman 本地环境文件只保留可公开的示例变量；真实账号、Token、密码和主机配置仍应放在本机环境中，不能提交到仓库。

## 业务模块入口

业务模块与后端 Controller、前端演示页面的对应关系见 [`modules/README.md`](modules/README.md)。新增或修改业务能力时，应先进入对应模块文档，再同步相关 API、数据库或 Redis 文档。
