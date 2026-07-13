# 前端演示模块任务队列

## 模块信息

| 项目 | 内容 |
| --- | --- |
| 模块名称 | 前端演示模块 |
| 模块定位 | 只服务于当前 Java 后端项目功能演示 |
| 当前分支 | `dev` |
| 当前状态 | 开发中 |
| 任务执行模式 | 自动推进，遇到停止条件暂停 |
| 开发计划 | `docs/frontend/04-frontend-dev-plan.md` |
| 接口映射 | `docs/frontend/03-api-mapping.md` |
| 任务总数 | 47 |
| 当前任务 | `T10` |
| 已完成任务 | 9 |
| `docs/CURRENT_STATUS.md` | 已创建；每个前端子任务完成后必须更新 |
| `docs/BRANCH_HANDOFF.md` | 已创建；每个前端子任务完成后必须更新 |

---

## 使用方式

用户可以使用以下指令启动队列：

```text
请按 docs/frontend/05-frontend-task-queue.md 自动推进前端演示模块任务。
从第一个 TODO 任务开始，每完成一个任务后测试、提交、推送并继续下一个任务；遇到停止条件立即暂停。
```

Codex 接到上述指令后，应持续执行队列，而不是每完成一个正常任务就等待用户再次确认。自动推进只改变执行节奏，不扩大任务权限或允许修改范围。

### 适用范围

| 项目 | 规则 |
| --- | --- |
| 适用任务 | `frontend/` 目录下的前端代码、前端测试和前端运行说明 |
| 必要同步文档 | 允许按队列规则同步前端计划、队列和项目状态文档 |
| 不适用任务 | 后端 Java、Maven、SQL、数据库、Redis、其他业务模块和纯文档任务 |
| 越界处理 | 前端任务需要修改任何后端业务实现时，立即命中停止条件并报告用户 |

根 `AGENTS.md` 中的“前端代码自动任务推进规则”不得被其他模块复用。后端或其他模块即使存在任务列表，也不能据此自动连续执行。

---

## 状态定义

| 状态 | 含义 | 是否继续自动推进 |
| --- | --- | --- |
| `TODO` | 尚未开始 | 是，选择编号最小的 TODO |
| `IN_PROGRESS` | 当前正在执行 | 否，不得并行开始其他任务 |
| `DONE` | 已完成、已测试、已提交并推送 | 是，查找下一个 TODO |
| `BLOCKED` | 命中停止条件，无法在当前范围继续 | 否，必须报告用户 |
| `SKIPPED` | 用户明确要求跳过 | 是，但必须记录原因 |

任意时刻最多只能有一个 `IN_PROGRESS` 任务。任务没有完成测试、文档更新、Git commit 和 push 时，不得标记为 `DONE`。

---

## 停止条件

Codex 在以下情况必须停止，不允许继续自动执行下一个任务：

1. `npm run build`、专项测试或静态检查失败，且无法在当前任务允许范围内修复；
2. 测试失败且原因不明确，或修复需要修改当前任务之外的功能；
3. 需要新增或升级 `04-frontend-dev-plan.md` 未预先批准的 npm 依赖；
4. 需要修改数据库表结构、SQL、Redis Key 或后端 Maven 依赖；
5. 需要修改既有后端接口路径、请求参数或响应结构；
6. 需要修改后端代码、其他业务模块或不在当前任务“涉及文件”中的文件；
7. 发现 `docs/04-api-doc.md`、前端文档和真实后端代码严重不一致；
8. 出现 JWT、管理员权限、文件安全、下载权限、事务一致性或敏感信息疑问；
9. 当前任务超过允许修改范围，或需要提前实现后续任务；
10. 需要用户确认页面业务规则、接口语义、依赖选择或交互边界；
11. 当前分支不是 `dev` 或明确的 `feature/frontend-demo`，或者工作区包含无法隔离的他人修改；
12. 需要执行破坏性 Git 操作、强制推送、修改 `main` 或删除已有功能；
13. `docs/CURRENT_STATUS.md` 或 `docs/BRANCH_HANDOFF.md` 等强制更新文件不存在，且创建文件的内容或责任边界未经用户确认；
14. Git commit 或 push 失败，无法确认子任务已经安全保存到当前远程开发分支；
15. 后端、MySQL 或 Redis 环境不可用，导致本任务明确要求的真实联调无法完成，且没有计划内替代验证方式。

以下情况本身不构成停止条件：

- 当前步骤按计划首次安装 Vue、Vite、TypeScript、Element Plus、Axios、Vue Router 或测试依赖；
- 当前步骤明确批准并完成评估后安装轻量 MD5 依赖；
- 真实后端暂不可用，但本步骤只要求单元测试、Mock 测试和生产构建，且这些验证已经通过。

---

## 自动推进规则

每完成一个子任务后，必须按顺序执行：

1. 将当前任务从 `TODO` 改为 `IN_PROGRESS`，并在“当前执行记录”写入开始时间和当前 commit；
2. 只修改该任务“涉及文件”列允许的文件；
3. 完成实现后记录实际修改文件和核心实现；
4. 运行该任务规定的专项测试；
5. 运行 `npm run build`；文档任务改为运行 Markdown/差异检查；
6. 运行 `git diff --check` 并核对 `git status --short`；
7. 更新 `docs/frontend/04-frontend-dev-plan.md` 中对应任务的实际进度或验证记录；
8. 更新本任务队列的状态和“当前执行记录”；
9. 更新 `docs/CURRENT_STATUS.md`；
10. 更新 `docs/BRANCH_HANDOFF.md`；
11. 如项目继续使用 `docs/06-project-progress.md` 记录整体进度，同时同步前端模块状态；
12. 生成并使用当前任务独立的 Git commit message；
13. 只暂存当前任务文件，执行 `git commit`；
14. 立即 `git push` 到当前开发分支；
15. 记录实际 commit id、测试结果和推送结果；
16. 将当前任务改为 `DONE`，更新“当前任务”为下一个编号最小的 `TODO`；
17. 输出当前任务完成摘要、下一个任务编号和“继续自动执行”；
18. 再次检查停止条件；如果未命中，立即执行下一个 TODO；
19. 如果没有 TODO，更新模块状态为“已完成”，输出最终汇总并停止。

> `docs/CURRENT_STATUS.md` 和 `docs/BRANCH_HANDOFF.md` 已在用户明确授权后创建。后续自动推进必须同步更新这两份文档，不得再以缺失为由跳过。

---

## 单任务 Git 规则

| 阶段 | 要求 |
| --- | --- |
| 开始前 | 检查分支、状态、最近提交和队列状态 |
| 暂存 | 只 `git add` 当前任务涉及文件和必须同步的进度文档 |
| 提交 | 每个任务独立 commit，不合并多个 TODO |
| 推送 | 每个任务测试通过后立即推送当前开发分支 |
| `main` | 不直接提交或推送未稳定前端功能 |
| 失败 | commit/push 失败时保持任务为 `IN_PROGRESS` 或 `BLOCKED`，不得继续 |

---

## 任务列表

> 任务 T01-T47 与 `docs/frontend/04-frontend-dev-plan.md` 的 F01-F47 一一对应。详细依赖说明、测试背景和阶段停靠点以开发计划为准；本文件是自动执行时的状态权威来源。

### 第一阶段：最小工程骨架

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T01 | DONE | 创建 Vue 3 + Vite + TypeScript 空工程 | `frontend/package.json`、`index.html`、Vite/TS 配置、`src/main.ts`、`src/App.vue` | `npm run build` 通过；开发服务返回 HTTP 200 |
| T02 | DONE | 接入 Element Plus 和基础样式 | `frontend/package.json`、`src/main.ts`、`src/App.vue`、`src/styles/index.css` | Element Plus 组件可渲染；生产构建和开发服务验证通过 |
| T03 | DONE | 接入 Vue Router 和最小页面壳 | Router、默认 Layout、首页占位、`App.vue`、`main.ts` | `/` 与未知路径均返回 SPA 入口；客户端配置 404 兜底 |
| T04 | DONE | 配置环境变量和后端代理 | `vite.config.ts`、`.env.example`、`.env.development`、必要时 `.gitignore` | API 地址可配置；Vite 配置构建成功；后端未启动，真实代理请求待后续联调 |
| T05 | DONE | 创建通用 API 类型和 Axios 请求实例 | `src/types/api.ts`、`src/utils/request.ts` | 统一响应、分页、错误消息和 `traceId` 可处理；生产构建通过 |
| T06 | DONE | 建立最小单元测试环境 | `package.json`、`vite.config.ts`、`tests/setup.ts`、请求层测试 | Vitest 和 Axios Mock 测试 3/3 通过；生产构建通过 |
| T07 | DONE | 添加枚举和格式化工具 | `src/types/enums.ts`、`src/utils/format.ts` 及测试 | 枚举与格式化专项测试 4/4 通过；生产构建通过 |

### 第二阶段：认证闭环

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T08 | DONE | 实现认证 API 方法 | `src/api/auth.ts`、`src/types/auth.ts` 及测试 | 认证 API 专项测试 3/3 通过；生产构建通过 |
| T09 | DONE | 实现登录页面 | `LoginView.vue` 及测试、Router、默认 Layout | 登录页组件测试通过；生产构建通过；真实登录联调待后端启动 |
| T10 | TODO | 实现注册页面 | `RegisterView.vue` 及测试、Router | 字段和校验与 API 文档一致；成功跳登录 |
| T11 | TODO | 实现当前用户 API 和简单会话状态 | `users.ts`、用户类型、`state/session.ts`、请求层及测试 | 可恢复当前用户；401/40102 清理会话；不引入 Pinia |
| T12 | TODO | 实现个人信息和后端退出 | `ProfileView.vue` 及测试、Router、默认 Layout | 展示真实用户字段；退出调用后端；管理员入口可见 |

### 第三阶段：公开浏览、排行榜与搜索

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T13 | TODO | 实现公开排行榜 API | `rankings.ts`、排行榜类型及测试 | 两个榜单 query 正确；资料榜和热词榜周期范围正确 |
| T14 | TODO | 将首页改为排行榜首页 | `HomeView.vue` 及测试 | 两类榜单可切换周期并展示空/错/加载状态；无图表 |
| T15 | TODO | 实现分类和搜索 API | `categories.ts`、`search.ts`、相关类型及测试 | 分类与搜索 query、分页、响应字段正确；不调用搜索建议 |
| T16 | TODO | 实现资料搜索页面 | `SearchView.vue` 及测试、Router、默认 Layout | 筛选、排序、分页和 URL 关键词可用；只展示公开资料 |
| T17 | TODO | 实现资料详情和创建资料 API | `resources.ts`、资料类型及测试 | 详情和创建方法正确；本任务不创建页面 |
| T18 | TODO | 实现只读资料详情页 | `ResourceDetailView.vue` 及测试、Router | 展示详情真实字段；游客可访问；不接收藏和下载 |

### 第四阶段：收藏与下载

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T19 | TODO | 实现收藏 API | `favorites.ts`、收藏类型及测试 | 收藏、取消和状态方法正确；均携带 Token |
| T20 | TODO | 在详情页接入收藏操作 | 详情页及测试；必要时小型收藏按钮组件 | 能查询、收藏、取消；游客跳登录；按钮防重复提交 |
| T21 | TODO | 实现下载 API 和文件流工具 | `downloads.ts`、下载类型、文件下载工具及测试 | 两步下载方法分离；中文文件名和 JSON 错误可处理 |
| T22 | TODO | 在详情页接入两步下载 | 详情页及测试 | 严格“创建记录→文件流”；展示 `counted`；429 提示正确 |

### 第五阶段：文件上传与资料创建

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T23 | TODO | 实现 MD5 工具和文件 API | `package.json`、`file-md5.ts`、`files.ts`、文件类型及测试 | 分片 MD5 稳定；预检 query 和 FormData 上传正确 |
| T24 | TODO | 实现上传页文件选择、预检和上传 | `UploadView.vue` 及测试、Router、默认 Layout | 显示 MD5 进度；命中秒传不上传；获得 `fileId` |
| T25 | TODO | 增加资料元数据表单 | 上传页及测试，复用分类和资料 API | 标题、简介、分类、课程、类型、标签可填写；无 `fileId` 禁止提交 |
| T26 | TODO | 完成资料创建和结果跳转 | 上传页及测试 | 创建后显示资料 ID 和待审核状态；失败保留表单 |

### 第六阶段：普通用户个人列表

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T27 | TODO | 实现“我的上传”API | `users.ts`、资料类型、用户 API 测试 | 状态和分页参数正确；响应只声明文档字段 |
| T28 | TODO | 实现“我的上传”页面 | `MyUploadsView.vue` 及测试、Router、导航 | 可筛选状态、分页、显示拒绝/下架原因；无编辑删除 |
| T29 | TODO | 实现“我的收藏”API | `users.ts`、收藏类型、用户 API 测试 | 收藏列表分页参数和 records 类型正确 |
| T30 | TODO | 实现“我的收藏”页面 | `MyFavoritesView.vue` 及测试、Router、导航 | 可分页、跳详情和取消收藏；不做收藏夹分组 |
| T31 | TODO | 实现“我的下载”API | `users.ts`、下载类型、用户 API 测试 | 下载记录只包含文档字段；分页参数正确 |
| T32 | TODO | 实现“我的下载”页面 | `MyDownloadsView.vue` 及测试、Router、导航 | 可分页、跳详情、再次下载；文件名取响应头 |

### 第七阶段：管理员审核与下架

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T33 | TODO | 实现管理员审核只读 API | `api/admin/resources.ts`、审核类型及测试 | 待审核列表和审核记录路径、query、Token 正确 |
| T34 | TODO | 实现待审核列表页面 | `ReviewManagementView.vue`、`AdminLayout.vue`、Router 及测试 | 只读列表、筛选、分页、审核流水可用；无写按钮 |
| T35 | TODO | 实现审核通过和拒绝 API | 管理员资料 API、审核类型及测试 | 通过/拒绝 path、body、Token 正确；拒绝原因必填 |
| T36 | TODO | 在审核页接入通过和拒绝 | 审核管理页及测试 | 二次确认；成功后移出列表；409 状态冲突可见 |
| T37 | TODO | 创建发布资料只读管理页 | `PublishedResourcesView.vue`、Router、管理员菜单及测试 | 只列 APPROVED 资料；支持筛选、分页和详情；无下架按钮 |
| T38 | TODO | 实现下架 API | 管理员资料 API、审核类型及测试 | 下架 path、`offlineReason` body 和 Token 正确 |
| T39 | TODO | 接入下架和审核流水 | 发布资料页及测试 | 原因必填；下架后移出公开列表；可查看流水 |

### 第八阶段：排行榜运维与路由收口

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T40 | TODO | 实现管理员总榜重建 API | `api/admin/rankings.ts` 及测试 | 无业务参数；携带管理员 Token；正确处理 `data=null` |
| T41 | TODO | 实现排行榜运维页面 | `RankingManagementView.vue`、Router、管理员菜单及测试 | 表格展示两类榜；周期正确；重建有确认，无虚构进度 |
| T42 | TODO | 收口登录和管理员路由守卫 | Router、会话状态、Layout 及路由测试 | 游客、普通用户、管理员跳转正确；后端仍为最终鉴权边界 |
| T43 | TODO | 收口固定导航和页面通用状态 | 两个 Layout、确有缺失的页面状态及测试 | 页面均可达；加载、空、错、重试完整；无复杂视觉重构 |

### 第九阶段：后端演示验收

| 编号 | 状态 | 任务 | 涉及文件 | 验收标准 |
| --- | --- | --- | --- | --- |
| T44 | TODO | 验证认证和上传审核闭环 | 仅缺陷文件、`tests/manual/auth-upload-review.md` | 注册、登录、秒传、创建、审核、退出黑名单均验证 |
| T45 | TODO | 验证搜索收藏下载闭环 | 仅缺陷文件、`tests/manual/resource-consumption.md` | 搜索隔离、收藏、两步下载、去重和限流均验证 |
| T46 | TODO | 验证排行榜和管理员下架闭环 | 仅缺陷文件、`tests/manual/ranking-admin.md` | 热度、重建、下架、角色拒绝均验证 |
| T47 | TODO | 整理启动说明和最终验证记录 | `frontend/README.md`、开发计划和必要进度文档 | 干净环境安装、测试、构建、启动通过；不记录真实密钥 |

---

## 当前执行记录

### T01

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `74554259ba03de64b78cd17bf75ca5aa993600e6` |
| 修改文件 | `frontend/` Vite 工程文件、`docs/frontend/04-frontend-dev-plan.md`、本队列、`docs/CURRENT_STATUS.md`、`docs/BRANCH_HANDOFF.md`、`docs/06-project-progress.md` |
| 核心实现 | 生成 Vue 3 + Vite + TypeScript 工程，并将默认演示替换为轻量项目标题页 |
| 测试命令 | `npm install`；`npm run build`；启动 Vite 后请求 `http://127.0.0.1:5173/` |
| 测试结果 | 依赖安装成功；构建通过；开发服务返回 HTTP 200 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `chore(frontend): scaffold vue vite app` |
| 实际 commit id | `93067f31b52652a44ef92cfcae70d202f66139be` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送 |

### T02

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `93067f31b52652a44ef92cfcae70d202f66139be` |
| 修改文件 | `frontend/package.json`、`frontend/package-lock.json`、`frontend/src/main.ts`、`frontend/src/App.vue`、`frontend/src/styles/index.css`、本队列和前端进度文档 |
| 核心实现 | 注册 Element Plus、导入组件样式，并使用 `el-card` 与 `el-tag` 验证组件可渲染 |
| 测试命令 | `npm install element-plus`；`npm run build`；启动 Vite 后请求 `http://127.0.0.1:5173/` |
| 测试结果 | 依赖安装成功；构建通过；开发服务返回 HTTP 200 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `chore(frontend): integrate element plus` |
| 实际 commit id | `513208be5767a0b18f7fc54085f1e000653f8ab7` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送 |

### T03

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `513208be5767a0b18f7fc54085f1e000653f8ab7` |
| 修改文件 | `frontend/package.json`、`frontend/package-lock.json`、`frontend/src/main.ts`、`frontend/src/App.vue`、`frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue`、`frontend/src/views/HomeView.vue`、`frontend/src/views/NotFoundView.vue`、`frontend/src/styles/index.css`、本队列和前端进度文档 |
| 核心实现 | 接入固定 Vue Router、默认布局、首页占位和未知路径 404 兜底；使用相对导入避免 TypeScript 6 路径别名弃用配置 |
| 测试命令 | `npm install vue-router`；`npm run build`；启动 Vite 后请求首页与未知路径 |
| 测试结果 | 依赖安装成功；构建通过；首页和未知路径均返回 HTTP 200 SPA 入口 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `feat(frontend): add router and base layout` |
| 实际 commit id | `7e79072e9bf7e81722259c83b40afa02e5d5bd6e` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送 |

### T04

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `7e79072e9bf7e81722259c83b40afa02e5d5bd6e` |
| 修改文件 | `frontend/vite.config.ts`、`frontend/.env.example`、`frontend/.env.development`、本队列和前端进度文档 |
| 核心实现 | 使用 `VITE_API_BASE_URL` 与 `VITE_API_PROXY_TARGET` 配置相对 API 地址和 `/api` 开发代理；不存放敏感信息 |
| 测试命令 | `npm run build`；启动 Vite 后请求首页；`Test-NetConnection 127.0.0.1:8080` |
| 测试结果 | 构建通过；开发服务返回 HTTP 200；本机 8080 端口未监听，未执行真实健康检查代理联调 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `chore(frontend): configure api proxy` |
| 实际 commit id | `0b5d5cb4d394916c160668f79c37e4926f3cf80d` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送；真实代理联调待后端可用时补充 |

### T05

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `0b5d5cb4d394916c160668f79c37e4926f3cf80d` |
| 修改文件 | `frontend/package.json`、`frontend/package-lock.json`、`frontend/src/types/api.ts`、`frontend/src/utils/request.ts`、本队列和前端进度文档 |
| 核心实现 | 定义 `ApiResponse`/`PageResult`，封装 Axios JSON 请求、业务错误、网络错误和 traceId；未提前接入 Token |
| 测试命令 | `npm install axios`；`npm run build` |
| 测试结果 | Axios 安装成功；构建通过；构建期间修复 TypeScript 6 不支持构造参数属性的问题 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `feat(frontend): add axios request client` |
| 实际 commit id | `6d2978ff0161a10561020d54266222941c76f403` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送；请求层专项 Mock 测试在 T06 建立 Vitest 后补充 |

### T06

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `6d2978ff0161a10561020d54266222941c76f403` |
| 修改文件 | `frontend/package.json`、`frontend/package-lock.json`、`frontend/vite.config.ts`、`frontend/tests/setup.ts`、`frontend/src/utils/request.test.ts`、本队列和前端进度文档 |
| 核心实现 | 接入 Vitest、Happy DOM、Vue Test Utils、Axios Mock Adapter；为请求层覆盖成功、业务错误和非 2xx JSON 错误 |
| 测试命令 | `npm install -D vitest @vue/test-utils happy-dom axios-mock-adapter`；`npm run test:unit -- src/utils/request.test.ts`；`npm run build` |
| 测试结果 | 请求层专项测试 3/3 通过；生产构建通过；修复断言泛型与 TypeScript 6 的兼容问题 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `test(frontend): add minimal unit test setup` |
| 实际 commit id | `791b23034674132b973f6362a942c2950f2f5c78` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送；页面组件测试环境已准备 |

### T07

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `791b23034674132b973f6362a942c2950f2f5c78` |
| 修改文件 | `frontend/src/types/enums.ts`、`frontend/src/utils/format.ts`、`frontend/src/utils/format.test.ts`、本队列和前端进度文档 |
| 核心实现 | 集中转换用户角色、资料状态和资料类型，并为未知值、空值、非法时间和非法数值提供页面级兜底 |
| 测试命令 | `npm run test:unit -- src/utils/format.test.ts`；`npm run build` |
| 测试结果 | 枚举与格式化专项测试 4/4 通过；生产构建通过 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `feat(frontend): add display enums and formatters` |
| 实际 commit id | `20af4571faeee4f82025ee70a5522cdb53e6f0de` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送 |

### T08

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `20af4571faeee4f82025ee70a5522cdb53e6f0de` |
| 修改文件 | `frontend/src/types/auth.ts`、`frontend/src/api/auth.ts`、`frontend/src/api/auth.test.ts`、本队列和前端进度文档 |
| 核心实现 | 按 API 映射实现注册、登录和退出请求；退出 Token 注入留待会话任务统一实现 |
| 测试命令 | `npm run test:unit -- src/api/auth.test.ts`；`npm run build` |
| 测试结果 | 认证 API 专项测试 3/3 通过；生产构建通过 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `feat(frontend): add auth api client` |
| 实际 commit id | `cb7063de3c318646efa951dcebfeb0955f8d5716` |
| 推送分支 | `origin/dev` |
| 备注 | 已完成并推送；登录、注册和个人信息页面均由后续任务单独实现 |

### T09

| 项目 | 记录 |
| --- | --- |
| 状态 | DONE |
| 开始时间 | 2026-07-13 |
| 完成时间 | 2026-07-13 |
| 开始前 commit | `cb7063de3c318646efa951dcebfeb0955f8d5716` |
| 修改文件 | `frontend/src/views/LoginView.vue`、`frontend/src/views/LoginView.test.ts`、`frontend/src/router/index.ts`、`frontend/src/styles/index.css`、本队列和前端进度文档 |
| 核心实现 | 登录表单进行必填校验、提交中防重复、错误消息展示；成功后临时保存 Token/用户并跳首页，T11 统一收口会话 |
| 测试命令 | `npm run test:unit -- src/views/LoginView.test.ts`；`npm run build` |
| 测试结果 | 登录页组件测试 1/1 通过；生产构建通过；后端 8080 未启动，未执行真实登录 |
| 文档更新 | 已同步开发计划、任务队列、当前状态、分支交接和项目进度 |
| Git commit message | `feat(frontend): add login page` |
| 实际 commit id |  |
| 推送分支 |  |
| 备注 | 当前只有 `/login`；`/register` 链接将在 T10 落地 |

---

## 子任务完成输出模板

每个子任务提交并推送后，Codex 必须输出：

```text
当前完成任务：Txx
任务状态：DONE
修改文件：
- ...

核心逻辑：
- ...

测试结果：
- 命令：...
- 结果：...

文档更新情况：
- docs/frontend/04-frontend-dev-plan.md：...
- docs/CURRENT_STATUS.md：...
- docs/BRANCH_HANDOFF.md：...
- docs/06-project-progress.md：...
- docs/frontend/05-frontend-task-queue.md：...

Git：
- 分支：...
- commit：...
- push：...

下一个任务：Txx
是否继续自动执行：是/否
停止条件：未命中/已命中（说明原因）
```

---

## 队列完成标准

| 验收项 | 标准 |
| --- | --- |
| 状态 | 所有任务为 `DONE` 或经用户明确批准的 `SKIPPED` |
| 工程 | `npm run test:unit` 和 `npm run build` 通过 |
| 页面 | 公共、普通用户和管理员页面按计划逐个完成 |
| 接口 | 只调用当前已实现接口，搜索建议未被首版调用 |
| 演示 | 三条真实后端演示闭环均有记录 |
| Git | 每个子任务有独立 commit，均已推送当前开发分支 |
| 文档 | 开发计划、任务队列、状态和交接文档与真实代码一致 |
| 范围 | 未引入复杂 UI、Pinia、动态权限菜单、复杂图表或重型工程化 |
