# 项目结构检查报告

检查日期：2026-09-19。检查基线：`dev` 分支、提交 `27804c29`，开始检查时工作区干净。本报告检查目录组织、分层与展示文档，不等同于全面安全审计或生产就绪认证。

## 结论

当前结构整体符合 Java 实习项目的工程组织需要：Spring Boot 子工程采用标准 Maven 目录，前后端分离，SQL 初始化与迁移独立管理，业务文档、测试和运行数据边界明确。无需为了根目录观感迁移后端或拆分微服务。

主要不足是部分协作规范和旧 README 与实现不同步，以及公开展示配套尚不完整。本次更新根 README，补充作者独立开发说明、真实功能、源码导航、架构图、启动步骤、测试前提和能力边界；其他改进单独列出，未变更业务代码。

## 结构核查

| 检查项 | 结果与依据 |
| --- | --- |
| Maven 工程 | `campus-resource-platform/pom.xml`、`src/main`、`src/test` 和 Maven Wrapper 完整；构建应进入内层工程 |
| 后端分层 | `controller`、`service/impl`、`mapper`、`entity`、`dto`、`vo`、`common`、`config`、`exception`、`interceptor`、`task`、`enums` 均存在 |
| 职责边界抽查 | 资料入口走 Controller → Service → Mapper XML；Controller 扫描未发现直接操作 Mapper / JDBC / Redis；定时任务调用独立持久化服务处理事务 |
| 前端组织 | `frontend/src/` 包含 API、类型、页面、路由、状态、工具和组件；有依赖锁文件、TypeScript 和 Vitest 配置 |
| SQL | `sql/init.sql` 含 9 张表；`sql/migrations/` 含 3 份迁移。新库初始化与存量升级分开 |
| 文档 | `docs/README.md` 与 `docs/modules/README.md` 提供真实入口；接口、数据库、前端、业务模块、面试复盘分别归档 |
| 测试组织 | 31 个后端 Java 测试文件、31 个前端 `.test.ts` 文件；包含 Controller、Service、Mapper、任务与前端组件测试。文件数不代表通过用例数 |
| 运行数据 | `target`、`node_modules`、`dist`、本地环境文件、用户上传目录均被忽略；前端 `.env.example` 已跟踪 |
| GitHub 展示 | 已有根 README；仓库公开，默认分支为 `dev`。本次推送 `dev` 即可更新首页说明 |

## 规范与实际的偏差

下列内容存在于 [docs/AGENTS.md](AGENTS.md)，后续应通过独立文档维护任务统一。本次 README 按实际代码编写。

| 规范或旧描述 | 实际情况 | 建议 |
| --- | --- | --- |
| 源码位于根 `src/main/java/...` | 实际位于 `campus-resource-platform/src/main/java/...` | 区分仓库根目录与后端工程根目录 |
| 配置名 `application.yml` | 实际为 `application.yaml` | 统一路径写法 |
| 统一响应使用 `Result` | 实际使用 `ApiResponse`，文件流使用二进制响应 | 按现有实现修正文档示例 |
| 除 Token 黑名单外 Redis 能力仍为预留 | 已有详情 / MD5 缓存、下载限流与统计、收藏缓存、排行榜 | 更新过期状态说明 |
| 必读 `AI_ENTRYPOINT.md`、`PROJECT_CONTEXT.md` | 两个文件不存在，已有文档索引、模块索引和当前状态 | 优先复用有效入口，减少重复维护 |

旧 README 的“94 个用例通过”“排行榜 Mapper 集成测试待补”“管理员重建入口待补”等描述已过期；已有对应测试和入口。本次移除旧基线，提供实际测试命令与环境前提。

## 可选改进项

1. **统一协作规范**：修正上表的路径、类名与功能状态，避免新贡献者按旧文档操作。
2. **自动化验证**：后续增加后端与前端 CI，明确真实 MySQL 测试库配置；当前没有已跟踪的 GitHub Actions 工作流。
3. **真实演示材料**：补充脱敏页面截图或演示视频；本次使用业务流程图。
4. **贡献与许可说明**：当前没有 `CONTRIBUTING.md` 和 `LICENSE`；许可由作者另行选择，本次未代为赋予许可。
5. **模板残留清理**：`frontend/src/components/HelloWorld.vue`、`frontend/src/assets/vite.svg`、`frontend/src/assets/vue.svg` 仍属于模板遗留，可在独立前端清理任务中处理。

后端目录与仓库同名属于可选命名调整，涉及路径联动，不是结构不合格的证据。当前 `mvnw` 在 Git 中没有可执行位，因此 README 的 Unix 示例使用 `sh ./mvnw`。

## 验证范围

- 使用 Git 状态、索引、忽略规则及源码路径检查目录组织。
- 对照 Maven / npm 清单、应用配置、SQL、核心服务、测试配置验证 README 陈述。
- `campus-resource-platform/` 下执行 `.\mvnw.cmd test`：169 个用例，160 个通过，9 个 `AuditServiceDatabaseIntegrationTest` 因本机 MySQL 连接被拒绝而出错；0 个断言失败。全量测试未通过，不能把本轮记录当作完整验收。
- `frontend/` 下执行 `npm run test:unit`：31 个文件、66 个用例全部通过。
- `frontend/` 下执行 `npm run build`：通过；保留大于 500 kB 的既有产物体积提示。
- README 与本报告共 46 个本地链接 / 锚点检查通过，代码围栏成对；7 段 PowerShell 示例通过语法解析；`git diff --check` 通过。
- 检查新增文档与 README 的本地链接、代码块和 Git 空白错误。
- 本次不修改 Java、Vue、SQL、依赖、接口或数据库 / Redis 行为；未执行生产部署或性能测试。
