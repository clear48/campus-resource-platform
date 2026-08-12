# 面试复盘文档索引

## 1. 使用目标

这套文档用于把“校园资料共享与智能检索平台”的开发记录转换为一条能在 Java 后端面试中讲清楚、经得住追问的项目叙事。

复盘原则：

- 先讲业务问题，再讲技术选择；
- 只讲当前代码真实实现，不把规划写成成果；
- 能说明正常流程，也能说明并发、异常、权限和一致性边界；
- 不虚构用户量、QPS、响应时间或性能提升；
- 被问到未实现能力时，明确回答现状、限制和可落地的下一步。

本轮事实核对基线为 `dev` 分支提交 `86a0ac39`，核对日期为 2026-08-12。仓库中可直接核验到 12 个 Controller、28 个 REST 接口方法、22 个 Service 接口及 22 个实现、9 张 MySQL 表、9 个 MyBatis Mapper 和 31 个 Java 测试类。本轮 Maven 全量执行 167 项，其中 158 项通过、9 项因本机 MySQL 未启动而连接错误，因此不把历史的 `124/124` 或本轮结果表述为当前全量通过。

## 2. 推荐阅读顺序

| 顺序 | 文档 | 解决的问题 |
| --- | --- | --- |
| 1 | [01-project-background-and-value.md](01-project-background-and-value.md) | 30 秒、1 分钟、3 分钟怎么介绍项目 |
| 2 | [02-modules-and-business-flows.md](02-modules-and-business-flows.md) | 项目有哪些模块，请求如何流转 |
| 3 | [03-technology-stack-review.md](03-technology-stack-review.md) | Spring Boot、MyBatis、Redis、MySQL 为什么这样用 |
| 4 | [04-interview-question-bank.md](04-interview-question-bank.md) | 面试官可能怎么追问，回答抓手是什么 |
| 5 | [05-limitations-and-improvement-roadmap.md](05-limitations-and-improvement-roadmap.md) | 哪些没做、为什么没做、下一步如何演进 |
| 6 | [00-document-generation-plan.md](00-document-generation-plan.md) | 文档证据、维护边界和后续生成计划 |

## 3. 三种使用方式

### 面试前 30 分钟

依次复习项目 1 分钟介绍、端到端主流程、Redis Key 表、MySQL 索引表和十个核心追问。

### 写简历

从项目价值和技术决策中提炼 3 到 4 条项目描述。只能使用仓库指标和设计结果，不写没有压测或生产数据支持的百分比。

### 模拟面试

先用 [04-interview-question-bank.md](04-interview-question-bank.md) 只看问题口述，再对照回答提纲检查是否覆盖“场景—方案—边界—取舍”。

## 4. 事实边界

本项目当前的“智能检索”落地是基于 MySQL 动态 SQL 的多条件检索、排序和热门搜索词统计，尚未接入 Elasticsearch、向量检索、大模型或语义搜索。面试中应主动说明这一点，不能把项目名称中的“智能”扩张为不存在的 AI 能力。

项目当前是可运行、可测试的教学与作品集工程，不是已有真实校园生产流量的线上系统。可以讲设计如何应对并发和故障，但不能声称已经承载某个 QPS、用户规模或数据规模。

Vue 3 前端已经覆盖 14 个主要页面和用户、管理员核心流程，不只是页面骨架；但健康检查与管理员审核文件预览尚未接入页面。前端现有 31 个测试文件，本轮因依赖命令缺失未能执行 Vitest 和构建，不能把测试文件数量等同于本轮通过数量。

## 5. 证据优先级

发生表述冲突时按以下顺序核对：

1. 当前 Java、Mapper XML 和配置代码；
2. `sql/init.sql` 与迁移 SQL；
3. `docs/CURRENT_STATUS.md`；
4. 模块开发记录与历史进度文档。

原因是历史文档可能保留早期版本口径，例如测试数量和待办事项可能已经被后续提交更新。
