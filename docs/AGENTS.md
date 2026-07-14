# AGENTS.md

## 1\. 项目简介

本项目是一个面向 Java 后端实习面试的项目，名称为：

**校园资料共享与智能检索平台**

项目目标不是实现普通 CRUD 管理系统，而是通过真实业务场景展示 Java 后端开发能力，包括：

* 用户注册与登录
* JWT 登录认证
* 文件上传
* 文件 MD5 去重
* 资料上传与审核
* 资料搜索
* 资料下载
* Redis 缓存
* Redis 下载限流
* Redis 热门资料排行榜
* Redis 下载量临时统计
* MySQL 表设计、索引设计与事务处理
* 模块化开发与项目文档沉淀

核心业务流程：

```text
用户上传文件
→ 系统计算文件 MD5
→ 判断是否重复文件
→ 创建资料记录
→ 管理员审核资料
→ 审核通过后用户可搜索、查看、下载、收藏
→ Redis 维护热门资料排行、下载限流和下载量统计
```

\---

## 2\. 技术栈

本项目主要使用以下技术：

* Java 17
* Spring Boot 3.x
* Spring MVC
* MyBatis / MyBatis-Plus
* MySQL 8.x
* Redis
* JWT
* Maven
* Lombok
* Validation
* Git

除非用户明确要求，不要随意引入新的技术栈或第三方依赖。

如果确实需要新增依赖，必须先说明：

1. 为什么需要新增依赖；
2. 这个依赖解决什么问题；
3. 是否可以用现有技术栈实现；
4. 新增依赖会修改哪些文件。

\---

## 3\. 项目目录结构约定

后端代码主要位于：

```text
src/main/java/com/john/campus
```

推荐分层结构如下：

* controller：接收 HTTP 请求，处理参数校验，调用 Service
* service：业务接口
* service/impl：业务实现类
* mapper：MyBatis Mapper 接口
* entity：数据库实体类
* dto：接收前端请求参数
* vo：返回给前端的数据对象
* common：统一返回结果、错误码、常量
* config：配置类
* exception：业务异常与全局异常处理
* interceptor：登录拦截器、权限拦截器
* utils：工具类
* task：定时任务
* enums：业务枚举
* constant：常量定义

MyBatis XML 文件位于：

```text
src/main/resources/mapper
```

配置文件位于：

```text
src/main/resources/application.yml
```

项目文档位于：

```text
docs
```

SQL 文件位于：

```text
sql
```

\---

## 4\. 文档体系

每次开发时，需要优先阅读以下文档或目录下相似文档：

1. `docs/AI\_ENTRYPOINT.md`
2. `docs/PROJECT\_CONTEXT.md`
3. `docs/CURRENT\_STATUS.md`
4. `docs/BRANCH\_HANDOFF.md`
5. `docs/api/api-reference.md`
6. `docs/05-redis-design.md`
7. 当前模块对应的 `docs/modules/xx-module.md`

如果这些文档不存在，可以协助创建。

推荐文档结构：

```text
docs/
├── AI\_ENTRYPOINT.md
├── PROJECT\_CONTEXT.md
├── CURRENT\_STATUS.md
├── BRANCH\_HANDOFF.md
├── DECISIONS.md
├── 01-requirements.md
├── 02-business-flow.md
├── api/
│   ├── api-reference.md
│   └── postman/
├── database/
│   ├── database-design.md
│   └── database-change-log.md
├── 05-redis-design.md
├── 06-test-cases.md
├── 07-interview-summary.md
├── frontend/
├── modules/
├── branches/
├── redis/
└── test/
```

\---

## 5\. 开发前必须做的事情

在开始修改代码前，必须先完成以下步骤：

1. 阅读第 4 节《文档体系》中列出的相关文档（至少包括 `AGENTS.md`、`docs/AI\_ENTRYPOINT.md`、`docs/PROJECT\_CONTEXT.md`、`docs/CURRENT\_STATUS.md`、`docs/BRANCH\_HANDOFF.md` 及当前任务相关模块文档）；
2. 检查当前 Git 分支，以及工作区是否有未提交修改；
3. 输出当前任务理解和修改计划，并明确本次任务允许修改哪些文件；
4. 等用户确认后再修改代码。

如果用户明确要求“直接修改”，也仍然要先简要说明修改范围。

\---

## 6\. 新任务处理流程

每次接到任务后，按以下流程执行。

### 第一步：理解任务

先判断任务属于哪个模块：

* auth：登录认证模块
* file-upload：文件上传模块
* resource：资料模块
* audit：审核模块
* search：搜索模块
* download：下载模块
* favorite：收藏模块
* rank：排行榜模块
* redis：Redis 相关增强
* database：数据库变更
* docs：文档维护
* test：测试补充

### 第二步：确认边界

明确：

* 本次要实现什么；
* 本次不实现什么；
* 涉及哪些接口；
* 涉及哪些表；
* 涉及哪些 Redis Key；
* 涉及哪些类；
* 是否需要事务；
* 是否需要权限控制；
* 是否需要更新文档。

### 第三步：小步修改

不要一次性实现多个模块。

每次只完成一个明确的小任务，例如：

* 只实现登录接口；
* 只实现文件 MD5 计算；
* 只实现文件类型校验；
* 只实现资料审核通过逻辑；
* 只实现 Redis 下载限流；
* 只实现热门资料排行榜 Top 10 查询。

### 第四步：完成后总结

每次修改完成后，必须按第 20 节《每次任务完成标准》输出完成总结，至少覆盖：修改了哪些文件与类、涉及哪些接口/数据库表/Redis Key、核心逻辑是什么、如何测试、是否更新文档，以及建议的 Git commit message。

\---

## 7\. 编码规范

### Controller 层

Controller 只负责：

* 接收请求；
* 参数校验；
* 获取当前用户；
* 调用 Service；
* 返回统一结果。

Controller 中不要写复杂业务逻辑。

返回值统一使用 `Result`。

示例：

```java
public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO)
```

### Service 层

Service 负责核心业务逻辑。

复杂业务必须写在 `ServiceImpl` 中，例如：

* 登录校验；
* 文件 MD5 去重；
* 资料审核状态流转；
* 下载限流；
* 下载记录保存；
* Redis 统计更新。

### Mapper 层

Mapper 只负责数据库操作。

不要在 Mapper 中写业务判断。

复杂 SQL 优先写在 XML 中，并注意防止 SQL 注入。

### DTO / VO / Entity

* DTO：前端请求参数
* VO：后端返回给前端的数据
* Entity：数据库表映射对象

不要直接把 Entity 暴露给前端。

### 异常处理

业务异常统一使用 `BusinessException`。

错误码统一维护在 `ErrorCode` 中。

不要在业务代码中直接返回杂乱字符串错误。

\---

## 8\. 接口设计规范

接口路径统一使用 RESTful 风格。

推荐前缀：

* `/api/auth`：登录认证
* `/api/files`：文件上传
* `/api/resources`：资料相关
* `/api/admin/resources`：管理员审核
* `/api/downloads`：下载相关
* `/api/favorites`：收藏相关
* `/api/rank`：排行榜
* `/api/search`：搜索

禁止使用低质量接口命名：

* `/add`
* `/delete`
* `/update`
* `/list`
* `/doSomething`

新增接口时必须更新：

```text
docs/api/api-reference.md
```

接口文档必须包含：

* 接口名称
* 请求方法
* 请求路径
* 请求参数
* 响应示例
* 错误码
* 是否需要登录
* 权限要求

\---

## 9\. 数据库设计规范

数据库使用 MySQL。

涉及数据库变更时，必须更新：

* `docs/database/database-design.md`
* `docs/database/database-change-log.md`
* `sql/init.sql` 或对应迁移 SQL

设计原则：

1. 表名使用小写加下划线；
2. 主键统一使用 `id`；
3. 必须包含 `create\_time` 和 `update\_time`；
4. 需要逻辑删除时使用 `is\_deleted`；
5. 状态字段使用 `tinyint`；
6. 高频查询字段需要考虑索引；
7. 防重复业务优先使用唯一索引兜底。

重要表：

* `user`：用户表
* `file\_info`：文件信息表
* `resource`：资料表
* `category`：分类表
* `favorite`：收藏表
* `download\_record`：下载记录表
* `audit\_record`：审核记录表

典型索引设计：

* `favorite(user\_id, resource\_id)`：防止重复收藏
* `file\_info(file\_md5)`：支持文件去重
* `resource(status, category\_id)`：支持审核通过资料查询
* `download\_record(user\_id, resource\_id)`：支持用户下载记录查询

涉及多表一致性时，需要考虑事务。

\---

## 10\. Redis 使用规范

Redis 必须服务于真实业务场景，不允许为了“用 Redis 而用 Redis”。

涉及 Redis 时，必须更新：

* `docs/05-redis-design.md`（Redis Key、数据结构、TTL、一致性策略的权威设计文档）

新增或修改 Redis Key 时，Key 必须集中定义在 `RedisKeyConstants` 常量类中，禁止在业务代码里硬编码（见第 18 节）。

Redis Key 统一使用 `crp:` 应用前缀加业务域分段，命名以 `docs/05-redis-design.md` 和 `RedisKeyConstants` 为准。当前规划的 Key 如下（其中 Token 黑名单已在代码中实现，其余为设计预留）：

* `crp:auth:token:blacklist:{jti}`：退出登录 Token 黑名单（已实现）
* `crp:cache:resource:detail:{resourceId}`：资料详情缓存
* `crp:cache:file:md5:{fileMd5}:{fileSize}`：文件 MD5 去重缓存
* `crp:rank:resource:hot:{period}`：热门资料排行榜
* `crp:rank:search:keyword:{period}`：热门搜索词排行榜
* `crp:rate:download:user:{userId}`：按用户下载限流
* `crp:rate:download:ip:{ip}`：按 IP 下载限流
* `crp:dedup:download:{userId}:{resourceId}`：下载去重统计
* `crp:stats:resource:download:delta`：下载量临时增量统计
* `crp:user:favorites:{userId}`：用户收藏集合
* `crp:auth:user:token-version:{userId}`：用户登录态版本（设计预留）

Redis 使用场景：

1. 资料详情缓存：String 或 Hash
2. 热门资料排行榜：ZSet
3. 热门搜索词排行榜：ZSet
4. 下载限流：String + TTL
5. 下载量临时统计：Hash
6. 登录状态或 Token 黑名单：String

新增 Redis Key 时，必须说明：

* Key 名称；
* 数据结构；
* 使用场景；
* TTL；
* 更新时机；
* 与 MySQL 的一致性处理。

\---

## 11\. 事务规范

涉及以下业务时，需要优先考虑事务：

* 用户注册；
* 资料上传；
* 资料审核；
* 收藏资料；
* 取消收藏；
* 下载记录写入；
* 下载量同步；
* 文件信息入库。

使用 `@Transactional` 时要注意：

1. 事务方法必须通过 Spring 代理调用；
2. 同类内部方法自调用可能导致事务失效；
3. 默认只对 `RuntimeException` 回滚；
4. 不要在事务中执行耗时文件 IO；
5. Redis 和 MySQL 的一致性需要单独设计，不能只依赖 MySQL 事务。

\---

## 12\. 权限与登录规范

登录认证使用 JWT + 拦截器。

请求头统一使用：

```text
Authorization: Bearer {token}
```

需要登录的接口必须通过拦截器校验。

管理员接口必须校验用户角色。

普通用户不能访问：

* 资料审核接口；
* 资料下架接口；
* 管理端统计接口；
* 用户封禁接口。

当前用户信息可以通过 `UserContextHolder` 或类似 ThreadLocal 工具类保存。

请求完成后必须清理 ThreadLocal，避免线程复用导致用户信息污染。

\---

## 13\. 文件上传规范

文件上传模块需要关注：

1. `MultipartFile` 接收文件；
2. 文件大小限制；
3. 文件类型白名单；
4. 文件后缀校验；
5. 文件 MIME 类型校验；
6. 文件名安全处理；
7. 文件 MD5 计算；
8. 根据 MD5 判断是否重复；
9. 文件物理存储路径；
10. `file\_info` 表入库。

不允许直接使用用户上传的原始文件名作为最终存储文件名。

推荐使用：

```text
UUID + 文件后缀
```

文件信息和资料信息必须分离：

* `file\_info`：保存物理文件信息；
* `resource`：保存资料业务信息。

\---

## 14\. 模块文档规范

每完成一个模块，必须生成或更新：

```text
docs/modules/xx-module.md
```

模块文档必须包括：

1. 模块目标；
2. 本次实现功能；
3. 涉及接口；
4. 涉及数据库表；
5. 涉及 Redis Key；
6. 核心类与方法；
7. 请求处理流程；
8. 模块内部类之间的关系；
9. 异常处理；
10. 权限控制；
11. 事务处理；
12. 测试用例；
13. 面试可讲点；
14. 后续优化方向。

如果某项不涉及，写：

```text
本模块暂未涉及。
```

不要强行编造。

> 说明：本节的 `xx-module.md` 是模块“结果概览”（偏最终交付）；模块的完整开发过程、分步骤开发提示词等更细的记录，遵循第 24 节《模块开发流程文档规范》。两者互补、各有侧重，不要在两份文档里重复堆叠相同内容。

\---

## 15\. 分支开发规范

推荐分支：

* `main`：稳定版本
* `dev`：开发主分支
* `feature/auth`：登录认证模块
* `feature/file-upload`：文件上传模块
* `feature/resource`：资料模块
* `feature/audit`：审核模块
* `feature/download-limit`：下载限流模块
* `feature/rank`：排行榜模块
* `feature/search`：搜索模块

每个功能分支只做一个模块或一个明确功能点。

不要在一个分支里同时做多个无关模块。

每次切换分支后，需要先检查：

* 当前分支名；
* `git status`；
* 最近 commit；
* `docs/BRANCH\_HANDOFF.md`；
* `docs/CURRENT\_STATUS.md`。

\---

## 16\. Git 提交规范

推荐使用 Conventional Commits 风格：

* feat：新增功能
* fix：修复 bug
* docs：文档更新
* test：测试相关
* refactor：重构
* chore：配置或杂项
* style：格式调整

示例：

```text
feat: implement jwt login authentication
docs: add auth module development record
feat: implement file md5 duplicate detection
fix: handle duplicate favorite exception
test: add download limit test cases
```

模块内小功能开发节奏：

* 开发模块时，需要将模块拆分为可以独立验证的小功能。
* 每完成一个模块内小功能，必须先完成针对性测试或说明无法测试的原因。
* 测试通过后，必须立即执行一次 Git commit，并推送到当前开发分支。
* 当前开发分支通常为 `dev` 或 `feature/模块名`；不要把未稳定的小功能直接推送到 `main`。
* `main` 只接收已经验证可长期运行的版本，需在 `dev` 或功能分支稳定后再合并。

每次任务完成后，需要给出实际使用的 commit message；如果因为用户明确要求不提交，必须在总结中说明原因。

\---

## 17\. 测试规范

每个模块完成后，需要至少给出测试方式。

优先覆盖：

* 正常请求；
* 参数为空；
* 参数非法；
* 未登录；
* 权限不足；
* 数据不存在；
* 重复操作；
* 状态不允许；
* Redis 不命中；
* 数据库异常。

涉及接口时，给出：

* 请求路径；
* 请求方法；
* 请求参数；
* 预期结果；
* 异常场景。

可以使用：

* 单元测试；
* Postman / Apifox；
* curl；
* Spring Boot Test；
* 简单并发测试。

\---

## 18\. 禁止事项

除非用户明确要求，否则不要做以下事情：

1. 不要一次性重构整个项目；
2. 不要一次性生成所有模块；
3. 不要随意修改包名；
4. 不要随意修改接口路径；
5. 不要随意修改数据库表结构；
6. 不要随意新增依赖；
7. 不要删除已有文档；
8. 不要删除已有测试；
9. 不要把 Entity 直接返回给前端；
10. 不要在 Controller 写复杂业务逻辑；
11. 不要在 Service 中直接拼接不安全 SQL；
12. 不要硬编码 Redis Key；
13. 不要硬编码密钥、密码、Token；
14. 不要把数据库密码提交到公共仓库；
15. 不要编造不存在的类、接口、表或 Redis Key。

如果确实需要修改范围外文件，必须先说明原因。

\---

## 19\. 新智能体接手流程

当一个新的智能体接手项目时，必须先执行第 5 节《开发前必须做的事情》中的完整流程，并在“输出当前任务理解”这一步额外提交一份项目理解报告。

项目理解报告必须包括：

* 项目整体定位；
* 当前技术栈；
* 核心业务流程；
* 已完成模块；
* 当前正在开发模块；
* 当前分支目标；
* 当前模块涉及的 Controller、Service、Mapper、Entity、DTO、VO；
* 当前模块涉及的数据库表；
* 当前模块涉及的 Redis Key；
* 当前遗留问题；
* 下一步最小可执行任务；
* 不确定的问题。

\---

## 20\. 每次任务完成标准

每次任务完成后，必须满足：

1. 代码修改范围清晰；
2. 核心逻辑说明清楚；
3. 异常情况已考虑；
4. 参数校验已考虑；
5. 权限校验已考虑；
6. 事务一致性已考虑；
7. Redis Key 已说明；
8. 数据库变更已记录；
9. 接口文档已更新；
10. 模块文档已更新；
11. 给出测试方式；
12. 完成 Git commit 并推送到当前开发分支；
13. 给出实际使用的 Git commit message。

如果某项不涉及，需要明确说明：

```text
本次任务暂未涉及。
```

\---

## 21\. 常用命令

构建项目：

```bash
mvn clean package
```

运行测试：

```bash
mvn test
```

启动项目：

```bash
mvn spring-boot:run
```

查看当前分支：

```bash
git branch --show-current
```

查看工作区状态：

```bash
git status
```

查看最近提交：

```bash
git log --oneline -5
```

创建功能分支：

```bash
git checkout -b feature/模块名
```

提交代码：

```bash
git add .
git commit -m "feat: implement xxx module"
```

\---

## 22\. 面试导向要求

本项目最终要服务于 Java 后端实习面试。

开发和文档中要重点沉淀以下内容：

1. 为什么这个项目不是普通 CRUD；
2. Redis 在项目中的真实使用场景；
3. MySQL 表设计和索引设计；
4. 文件 MD5 去重的实现；
5. 审核状态流转设计；
6. JWT 登录认证流程；
7. 拦截器如何实现权限控制；
8. Redis 下载限流如何实现；
9. Redis ZSet 排行榜如何实现；
10. 下载量为什么先写 Redis 再同步 MySQL；
11. 事务如何保证数据一致性；
12. 缓存和数据库不一致如何处理；
13. 项目中遇到的问题和优化方案。

每完成一个核心模块，都需要补充对应的面试可讲点。

\---

## 23\. 当前项目的核心原则

请始终遵守以下原则：

1. 小步开发；
2. 先理解再修改；
3. 先文档后代码；
4. 每次只做一个模块；
5. 不随意扩大修改范围；
6. 不为了炫技引入复杂技术；
7. 所有技术点都要服务于业务场景；
8. 所有实现都要能在面试中讲清楚；
9. 文档和代码必须保持一致；
10. 不确定时先说明，不要猜测。

\---

## 24\. 模块开发流程文档规范

每当开始开发一个新的业务模块时，必须为该模块生成一份模块开发流程文档。

文档位置：

```text
docs/modules/xx-module-name-development-process.md
```

例如：

* docs/modules/01-auth-development-process.md
* docs/modules/02-category-development-process.md
* docs/modules/03-file-upload-development-process.md
* docs/modules/04-resource-development-process.md
* docs/modules/05-audit-development-process.md
* docs/modules/06-search-development-process.md
* docs/modules/07-download-development-process.md
* docs/modules/08-favorite-development-process.md

该文档用于记录当前模块的完整开发过程，帮助后续智能体快速接手项目，也帮助开发者复习模块设计、代码结构和面试可讲点。

该文档不应该只记录“做了什么”，还必须提供一组用户可以直接复制给 Agent 的提示词，用来指导 Agent 分步骤开发当前模块。

### 1. 生成时机

在开始开发一个新模块前，必须先生成模块开发流程文档初稿。

在模块开发过程中，每完成一个关键功能，需要更新该文档。

在模块开发完成后，必须补全文档中的测试记录、修改文件、接口文档、面试可讲点和后续优化方向。

### 2. 文档必须包含的内容

每个模块开发流程文档必须包含以下部分：

1. 模块基本信息
2. 模块目标
3. 需求分析
4. 本模块不做什么
5. 涉及接口
6. 涉及数据库表
7. 涉及 Redis Key
8. 涉及核心类
9. 模块内部调用关系
10. 请求处理流程
11. 数据流转流程
12. 权限校验
13. 参数校验
14. 异常处理
15. 事务处理
16. 核心实现步骤
17. 开发任务拆分
18. 已完成事项
19. 待完成事项
20. 测试清单
21. 修改文件记录
22. 与其他模块的关系
23. 面试可讲点
24. 后续优化方向
25. Git commit message 建议
26. 分步骤开发提示词（可直接复制给 Agent 使用）

如果某一部分当前模块暂未涉及，必须明确写：

本模块暂未涉及。

不要为了补全文档而编造不存在的接口、类、表或 Redis Key。

### 3. 文档更新规则

每次模块开发完成一个小任务后，需要更新以下内容：

* 已完成事项
* 待完成事项
* 修改文件记录
* 当前模块状态
* 新增或修改的接口
* 新增或修改的数据库表
* 新增或修改的 Redis Key
* 测试记录
* 下一步任务

### 4. 文档与代码一致性要求

模块开发流程文档必须基于当前真实代码生成。

生成文档前需要检查：

* Controller 是否真实存在
* Service 是否真实存在
* Mapper 是否真实存在
* DTO / VO / Entity 是否真实存在
* 接口路径是否真实存在
* 数据库表和字段是否真实存在
* Redis Key 是否真实使用
* 模块状态是否与代码一致

如果文档和代码不一致，必须指出不一致之处，并优先以代码为准。

### 5. 文档完成标准

一个模块开发完成后，对应的模块开发流程文档必须能够回答以下问题：

1. 这个模块解决了什么业务问题？
2. 这个模块为什么不是简单 CRUD？
3. 这个模块有哪些接口？
4. 请求从 Controller 到 Mapper 的调用链路是什么？
5. 这个模块涉及哪些数据库表？
6. 这个模块是否使用 Redis？
7. 这个模块是否需要事务？
8. 这个模块如何做权限控制？
9. 这个模块有哪些异常场景？
10. 这个模块如何测试？
11. 这个模块和其他模块有什么关系？
12. 这个模块有哪些面试可讲点？
13. 后续可以如何优化？

### 6. 禁止事项

生成模块开发流程文档时禁止：

1. 编造不存在的类名；
2. 编造不存在的接口；
3. 编造不存在的数据库表；
4. 编造不存在的 Redis Key；
5. 把未完成的功能写成已完成；
6. 忽略当前模块与其他模块的依赖关系；
7. 只写功能列表，不写调用流程；
8. 只写代码文件，不写业务价值；
9. 不更新已完成事项和待完成事项；
10. 不写测试方式。

### 7. 分步骤开发提示词（可复制给 Agent 的 Prompt 清单）

模块开发流程文档不能只记录“做了什么”，还必须提供一组用户可以直接复制给 Agent 的提示词，用来指导 Agent 分步骤开发当前模块。

要求：

1. 提示词必须集中放在文档固定小节，标题统一为“分步骤开发提示词”。
2. 提示词必须分步骤，每一步对应一个最小可执行任务，严禁用一条提示词要求一次性完成整个模块。
3. 步骤顺序必须与模块真实开发顺序一致，推荐顺序：数据库表与实体 → Mapper → DTO / VO → Service → Controller → 权限与校验 → 测试 → 文档更新。
4. 每条提示词必须可以直接复制使用，无需用户再改写，语言面向 Agent，做到明确、具体、可执行。
5. 每条提示词应说明：本步目标、涉及文件或类、完成标准、本步不做什么。
6. 提示词必须遵守本文件其他规范，包括先文档后代码、小步开发、不编造不存在的类/接口/表/Redis Key、不越权修改范围外文件。
7. 最后一条提示词必须是“更新本模块开发流程文档”，用于保证文档与代码持续同步。
8. 若某一步当前模块暂未涉及，对应提示词写“本模块暂未涉及”，不要编造。

推荐格式：

```text
### 分步骤开发提示词

步骤 1：创建实体与 Mapper
提示词：
请为 xxx 模块创建实体类和 MyBatis Mapper。
- 只创建 Entity、Mapper 接口及对应 XML，不实现 Service 和 Controller。
- 涉及表：xxx。
- 完成后更新本模块开发流程文档的“修改文件记录”和“已完成事项”。

步骤 2：实现 Service
提示词：
请实现 xxx 模块的 Service 与 ServiceImpl。
- 只实现业务逻辑，不新增接口。
- 复杂逻辑写在 ServiceImpl，异常统一使用 BusinessException。
- 完成后更新文档的“已完成事项”和“待完成事项”。

……（按真实开发顺序继续拆分）

步骤 N：更新模块开发流程文档
提示词：
请根据当前真实代码更新 xxx 模块开发流程文档，
补全已完成事项、待完成事项、修改文件记录、测试记录和面试可讲点，
文档与代码不一致时以代码为准。
```
