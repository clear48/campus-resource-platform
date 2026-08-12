# 面试复盘文档生成与维护计划

## 1. 本轮目标

把项目从“按模块记录开发过程”整理为“按面试叙事组织证据”，覆盖：

- 项目背景与业务价值；
- 已实现模块和端到端业务流程；
- Spring Boot、MyBatis、Redis、MySQL 的真实落地；
- 事务、并发、幂等、缓存一致性、权限和文件安全；
- 高频面试问题与基于本项目的回答提纲；
- 当前局限、诚实表达边界与后续优化方向。

本轮只读取生产代码、配置、SQL、测试报告和现有文档；只新增或更新 `docs/` 下的复盘文档，不修改 Java、前端、SQL、Redis Key 或接口。

## 2. 生成文件

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| `interview/README.md` | 总索引、使用方式、事实边界 | 已生成 |
| `interview/01-project-background-and-value.md` | 背景、价值、不同长度介绍、简历表达 | 已生成 |
| `interview/02-modules-and-business-flows.md` | 模块、接口、主流程、关键调用链 | 已生成 |
| `interview/03-technology-stack-review.md` | 四项核心技术栈、Redis Key、表与事务 | 已生成 |
| `interview/04-interview-question-bank.md` | 网络面经映射、高频追问与回答提纲 | 已生成 |
| `interview/05-limitations-and-improvement-roadmap.md` | 未实现能力、风险边界、分阶段演进 | 已生成 |
| `docs/README.md` | 增加面试复盘入口 | 已更新 |

## 3. 项目证据采集范围

### 代码与配置

- `campus-resource-platform/pom.xml`
- `campus-resource-platform/src/main/resources/application.yaml`
- `campus-resource-platform/src/main/java/com/john/campus/controller/`
- `campus-resource-platform/src/main/java/com/john/campus/service/impl/`
- `campus-resource-platform/src/main/java/com/john/campus/interceptor/`
- `campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java`
- `campus-resource-platform/src/main/resources/mapper/`

### 数据与验证

- `sql/init.sql`
- `sql/migrations/`
- `campus-resource-platform/target/surefire-reports/`
- `docs/CURRENT_STATUS.md`
- `docs/05-redis-design.md`
- `docs/database/database-design.md`
- `docs/api/api-reference.md`
- `docs/modules/`

### 当前可核验快照

| 指标 | 当前值 | 说明 |
| --- | ---: | --- |
| Controller | 12 | 包含健康检查和管理端入口 |
| REST 接口方法 | 28 | 按 `@GetMapping`、`@PostMapping`、`@DeleteMapping` 统计 |
| Service 接口 / 实现 | 22 / 22 | 包含文件 MD5 缓存、公开详情缓存和排行榜后台任务相关服务 |
| MyBatis Mapper | 9 | 与 9 份 Mapper XML 对应 |
| MySQL 表 | 9 | 以 `sql/init.sql` 为准 |
| Java 测试类 | 31 | 单元、Controller、Mapper/Service 集成测试 |
| 本轮后端全量测试 | 167 项，0 失败、9 错误 | 158 项通过；9 项 MySQL 审核集成测试因本机 `localhost:3306` 未启动而无法建连，不表述为当前全量通过 |
| 前端页面 / 测试文件 | 14 / 31 | 已覆盖主要用户与管理流程；本轮因前端依赖命令缺失，未执行 Vitest 和构建 |

## 4. 网络面试信息如何映射到项目

近期 Java 后端面经和高频题整理反复出现以下方向：

| 网络高频方向 | 本项目对应证据 | 文档落点 |
| --- | --- | --- |
| 项目是否有实际价值、如何判断重复提交 | 文件 MD5 + size 去重、资料重复提交校验、收藏唯一索引 | 背景、业务流程、问答 |
| 为什么用 Redisson，不直接用 SETNX | 下载增量同步锁、排行榜维护读写锁、公开详情每资料锁、看门狗续期 | 技术栈、问答 |
| MySQL 表和索引如何按业务设计 | 9 张表、联合索引、唯一索引、条件更新 | 技术栈、问答 |
| 事务、锁、MVCC、幂等 | 审核事务、收藏事务、批次唯一幂等栅栏 | 技术栈、问答 |
| Redis 数据结构、TTL、缓存一致性和降级 | String/Set/Hash/ZSet、不同失败策略、MySQL 兜底 | 技术栈、问答 |
| SQL 优化和动态条件安全 | MyBatis 动态 SQL、排序白名单、分页和联合索引 | 技术栈、问答 |
| 鉴权和越权 | JWT 拦截器、ThreadLocal 清理、用户文件授权、票据归属 | 业务流程、问答 |

网络信息只用于确定“面试官会追问什么”，项目答案仍以当前代码为准，不能照搬通用答案替代真实实现。

## 5. 外部参考

以下页面核对日期均为 2026-07-28：

### 面经与求职表达

- [牛客：字节后端一面](https://www.nowcoder.com/discuss/796497072009584640)：项目价值、重复提交、Redisson、TTL、表和索引、幂等。
- [牛客：Java 面试高频考点](https://www.nowcoder.com/discuss/842505854560501760)：MySQL 索引/事务/锁、Redis 数据结构/一致性/分布式锁。
- [牛客：Java 开发一面](https://www.nowcoder.com/discuss/862328439104155648)：事务机制、SQL 优化闭环。
- [Reddit：Java Backend 项目与简历建议](https://www.reddit.com/r/careerguidance/comments/1tayhdk/why_am_i_not_getting_interview_calls_for_java/)：项目描述应表达问题、实现、结果和测试，避免只罗列技术名词。

### 官方技术资料

- [Spring Framework：`@Transactional`](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [MyBatis：Mapper XML](https://mybatis.org/mybatis-3/sqlmap-xml.html)
- [MyBatis：Dynamic SQL](https://mybatis.org/mybatis-3/dynamic-sql.html)
- [Redis：Sorted Sets](https://redis.io/docs/latest/develop/data-types/sorted-sets/)
- [Redis：Lua 脚本与原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)
- [Redisson：Locks and synchronizers](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/index.html)
- [MySQL 8.4：联合索引](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html)

## 6. 后续维护触发条件

出现以下变化时应同步更新本目录：

- 新增、删除或修改接口；
- 数据库表、索引、事务边界变化；
- Redis Key、数据结构、TTL 或失败策略变化；
- 搜索从 MySQL 升级到 Elasticsearch 或语义检索；
- 文件存储从本地迁移到 MinIO/OSS；
- 完成压测、部署或获得可验证的真实运行指标；
- 修复本复盘中列出的局限。

更新时先重新生成事实快照，再更新讲稿和问答，避免“代码已经变了，面试答案仍停留在旧版本”。
