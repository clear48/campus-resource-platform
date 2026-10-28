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
| `LOCAL-DATA-PROTECTION.md` | 本地数据保护 | Windows本地MySQL、Redis、上传文件联合备份、SHA-256校验与隔离恢复 |
| `12-local-load-test-plan.md` | 本地性能测试 | 单实例后端、MySQL、Redis、本地文件的压测流程、负载阶梯、限流边界与统计对账 |
| `test/2026-10-28-local-loadtest-report.md` | 本地压测执行记录 | 真实环境、稳态数据、并发失败证据、最终一致性与完成范围 |
| `../deploy/README.md` | 生产部署配置 | Compose 变量、镜像交付、HTTP / HTTPS、证书续期与运行维护 |
| `DEPLOYMENT-QA-QUICK-REVIEW.md` | 部署学习复盘 | 本次部署对话的 14 个问题、精简解答与关键流程速查 |
| `DEPLOYMENT_HANDOFF.md` | 部署阶段交接 | 服务器方案、部署阶段状态、验收边界与后续步骤 |
| `DEPLOY-08-RUNBOOK.md` | 生产验收与恢复 | 公网验收矩阵、加密联合备份、签名与独立恢复介质预检 |
| `08-multi-agent-collaboration.md` | Codex 协作配置 | 项目级 Subagent 角色、调度流程、权限、验证与排查 |
| `CURRENT_STATUS.md`、`BRANCH_HANDOFF.md` | 当前协作状态 | 当前分支、任务边界与交接信息 |
| `AGENTS.md` | 项目协作规范 | 开发、测试、文档与提交要求 |

## 接口资料

- API 参考：[`api/api-reference.md`](api/api-reference.md)
- Postman 集合：[`api/postman/campus-resource-platform.postman_collection.json`](api/postman/campus-resource-platform.postman_collection.json)
- Postman 本地环境示例：[`api/postman/campus-local.postman_environment.json`](api/postman/campus-local.postman_environment.json)

Postman 本地环境文件只保留可公开的示例变量；真实账号、Token、密码和主机配置仍应放在本机环境中，不能提交到仓库。

## 部署上线入口

- 配置总览与操作顺序：[根 README 的部署上线说明](../README.md#部署上线)。
- 详细命令与变量映射：[生产部署手册](../deploy/README.md)。
- 快速回忆概念与流程：[部署关键流程问答速查](DEPLOYMENT-QA-QUICK-REVIEW.md)。
- 历史上线证据与当前阶段：[部署交接](DEPLOYMENT_HANDOFF.md)。
- 尚未完成的生产验收与恢复：[DEPLOY-08 运行手册](DEPLOY-08-RUNBOOK.md)。

部署资产已汇总到唯一保留的 `main` 分支；文档中的旧分支名仅作为历史记录。GitHub 保存配置模板、Dockerfile 和脚本；真实 `.env`、证书私钥、业务备份与镜像归档保存在仓库外。DEPLOY-08 当前为 `IN_PROGRESS`，不能将脚本预检通过写成生产恢复完成。

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
