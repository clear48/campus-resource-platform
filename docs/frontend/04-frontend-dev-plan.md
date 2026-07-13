# 校园资料共享与智能检索平台前端开发计划

## 1. 文档目标

本文档基于以下现有文档制定前端分步开发计划：

- `docs/frontend/01-frontend-requirements.md`
- `docs/frontend/02-page-design.md`
- `docs/frontend/03-api-mapping.md`
- `docs/04-api-doc.md`

前端只用于演示当前后端项目的业务闭环和技术亮点，不作为独立商业产品建设。开发重点是让 JWT、文件 MD5 去重、审核状态流转、搜索、收藏、下载限流、Redis 排行榜和管理员运维能力可以通过简单页面被操作和讲解。

本计划将工程能力、API 模块和页面能力拆成足够小的步骤。**一次只执行一个步骤；每个步骤完成后立即测试、提交并推送，再进入下一步。禁止一次性生成所有页面。**

## 当前执行进度

| 计划步骤 | 队列任务 | 状态 | 验证记录 | 下一步 |
| --- | --- | --- | --- | --- |
| F01 | T01 | 已完成 | `npm install` 成功；`npm run build` 通过；Vite 开发服务返回 HTTP 200 | F02 / T02：接入 Element Plus |
| F02 | T02 | 已完成 | `npm install element-plus` 成功；`npm run build` 通过；Vite 开发服务返回 HTTP 200 | F03 / T03：接入 Vue Router |
| F03 | T03 | 已完成 | `npm install vue-router` 成功；`npm run build` 通过；首页和未知路径均返回 HTTP 200 SPA 入口 | F04 / T04：配置环境变量和后端代理 |
| F04 | T04 | 已完成 | 环境变量与 `/api` 代理配置通过构建和 Vite 启动验证；本机后端 8080 端口未监听，健康检查代理联调待补 | F05 / T05：创建 Axios 请求实例 |
| F05 | T05 | 已完成 | `npm install axios` 成功；请求层和通用响应类型通过 `npm run build` 验证 | F06 / T06：建立最小单元测试环境 |
| F06 | T06 | 已完成 | 请求层 Vitest + Axios Mock 专项测试 3/3 通过；`npm run build` 通过 | F07 / T07：添加枚举和格式化工具 |
| F07 | T07 | 已完成 | 枚举与格式化专项测试 4/4 通过；`npm run build` 通过 | F08 / T08：实现认证 API 方法 |
| F08 | T08 | 已完成 | 认证 API 专项测试 3/3 通过；`npm run build` 通过 | F09 / T09：实现登录页面 |
| F09 | T09 | 已完成 | 登录页组件测试 1/1 通过；`npm run build` 通过；真实登录待后端启动后联调 | F10 / T10：实现注册页面 |
| F10 | T10 | 已完成 | 注册页组件测试 1/1 通过；`npm run build` 通过；真实注册待后端启动后联调 | F11 / T11：当前用户 API 和简单会话状态 |
| F11 | T11 | 已完成 | 当前用户 API 与会话恢复专项测试 2/2 通过；`npm run build` 通过 | F12 / T12：个人信息与后端退出 |
| F12 | T12 | 已完成 | 个人信息页组件测试 1/1 通过；`npm run build` 通过；真实退出待后端启动后联调 | F13 / T13：公开排行榜 API |
| F13 | T13 | 已完成 | 排行榜 API 专项测试 2/2 通过；`npm run build` 通过 | F14 / T14：排行榜首页 |
| F14 | T14 | 已完成 | 首页组件测试 1/1 通过；`npm run build` 通过；Element Plus 产物体积告警不影响构建结果 | F15 / T15：分类和搜索 API |
| F15 | T15 | 已完成 | 分类与搜索 API 专项测试 2/2 通过；`npm run build` 通过 | F16 / T16：资料搜索页面 |
| F16 | T16 | 已完成 | 搜索页组件测试 1/1 通过；`npm run build` 通过；真实筛选待后端启动后联调 | F17 / T17：资料详情和创建资料 API |
| F17 | T17 | 已完成 | 资料 API 专项测试 2/2 通过；`npm run build` 通过 | F18 / T18：只读资料详情页 |
| F18 | T18 | 已完成 | 资料详情页组件测试 1/1 通过；`npm run build` 通过；游客访问链路由 Mock 验证 | F19 / T19：收藏 API（本轮阶段上限，暂停） |

## 2. 技术栈与开发边界

### 2.1 固定技术栈

| 类别 | 选型 | 用途 |
| --- | --- | --- |
| 前端框架 | Vue 3 | 使用 Composition API 和单文件组件 |
| 构建工具 | Vite | 本地开发、代理和生产构建 |
| 开发语言 | TypeScript | 约束请求参数、响应字段和页面状态 |
| UI 组件 | Element Plus | 表单、表格、分页、标签、弹窗和消息提示 |
| HTTP 请求 | Axios | API 请求、Token 注入、统一错误处理和文件流下载 |
| 页面路由 | Vue Router | 固定路由和简单登录/管理员守卫 |

### 2.2 允许按需增加的最小依赖

| 依赖 | 引入时机 | 原因 |
| --- | --- | --- |
| Vitest | 建立最小测试环境时 | 独立验证请求层、API 方法和工具函数 |
| Vue Test Utils | 开始页面组件测试时 | 验证表单提交、按钮状态和列表渲染 |
| Axios Mock Adapter | 测试 API 方法时 | 不依赖真实后端即可校验方法、路径、参数和 Token 行为 |
| 轻量 MD5 库 | 开发文件 MD5 步骤时再决定 | 浏览器 Web Crypto 不保证支持 MD5，需要分片计算文件摘要 |

新增 MD5 依赖前必须核对维护状态、体积和许可证，并在对应步骤说明原因。不得在脚手架阶段提前安装暂时不用的依赖。

### 2.3 明确不做

| 不做内容 | 说明 |
| --- | --- |
| Pinia 或其他复杂状态管理 | JWT 和当前用户使用简单响应式模块即可 |
| 动态权限菜单和后端菜单树 | 路由和导航固定，管理员权限以后端校验为准 |
| 微前端、SSR、PWA | 与后端演示目标无关 |
| 复杂主题、动画和响应式布局 | 使用 Element Plus 默认能力和少量基础样式 |
| ECharts 等复杂图表 | 排行榜用表格展示即可 |
| 搜索建议 | 后端接口尚未实现，首版不调用 |
| 管理员用户管理、复杂统计 | 当前没有对应后端接口 |
| 一次性批量生成页面 | 每次只完成一个页面或一个独立页面能力 |

## 3. 目录规划

> 当前仓库尚不存在 `frontend/`。以下目录是开发计划目标，不代表文件已经创建。

```text
frontend/
├─ package.json
├─ vite.config.ts
├─ tsconfig.json
├─ .env.example
├─ src/
│  ├─ api/
│  │  ├─ auth.ts
│  │  ├─ users.ts
│  │  ├─ resources.ts
│  │  ├─ categories.ts
│  │  ├─ files.ts
│  │  ├─ favorites.ts
│  │  ├─ downloads.ts
│  │  ├─ rankings.ts
│  │  ├─ search.ts
│  │  └─ admin/
│  │     ├─ resources.ts
│  │     └─ rankings.ts
│  ├─ components/
│  ├─ layouts/
│  ├─ router/
│  ├─ state/
│  ├─ styles/
│  ├─ types/
│  ├─ utils/
│  ├─ views/
│  │  ├─ admin/
│  │  └─ user/
│  ├─ App.vue
│  └─ main.ts
└─ tests/
```

不建立组件库、领域层、Repository 层或复杂分包。只有出现两个以上页面真实复用时，才提取公共组件。

## 4. 每一步统一执行规则

### 4.1 开发前

| 检查项 | 命令/要求 |
| --- | --- |
| 当前分支 | `git branch --show-current`，默认应为 `dev` 或 `feature/frontend-demo` |
| 工作区 | `git status --short --branch`，不得混入其他任务修改 |
| 最近提交 | `git log -5 --oneline --decorate` |
| 任务范围 | 只修改当前步骤“涉及文件”中列出的文件；需要扩展时先说明原因 |

### 4.2 开发完成后

| 顺序 | 动作 | 要求 |
| --- | --- | --- |
| 1 | 运行步骤专项测试 | 使用该步骤表格中的测试命令 |
| 2 | 运行构建检查 | 默认执行 `npm run build` |
| 3 | 检查修改 | `git diff --check` 和 `git status --short` |
| 4 | 独立提交 | 只暂存当前步骤文件并使用建议 commit message |
| 5 | 立即推送 | 推送到当前开发分支，不推送未稳定功能到 `main` |
| 6 | 记录结果 | 记录测试命令、测试结果、commit id 和远程分支 |

如果某一步因后端、Redis、文件环境等原因无法完成真实联调，必须先完成可执行的单元测试或构建验证，并在提交总结中明确未验证范围。

## 5. 第一阶段：最小工程骨架

第一阶段只建立可运行、可构建、可测试的最小工程，不开发业务页面。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F01 | 创建 Vue 3 + Vite + TypeScript 空工程 | 新增 `frontend/package.json`、`frontend/index.html`、`frontend/vite.config.ts`、`frontend/tsconfig*.json`、`frontend/src/main.ts`、`frontend/src/App.vue`、`frontend/src/vite-env.d.ts`；按 Vite 模板生成必要文件 | 在 `frontend/` 执行 `npm install`、`npm run build`、`npm run dev` 后访问首页 | 工程能启动；生产构建通过；页面只显示一个简单项目标题；不安装业务无关依赖 | `chore(frontend): scaffold vue vite app` |
| F02 | 接入 Element Plus 和基础样式 | 修改 `frontend/package.json`、`frontend/src/main.ts`、`frontend/src/App.vue`；新增 `frontend/src/styles/index.css` | `npm install`、`npm run build`；浏览器确认一个 Element Plus 按钮和提示组件可渲染 | Element Plus 全局可用；只有基础字体、间距和页面背景样式；不做主题系统 | `chore(frontend): integrate element plus` |
| F03 | 接入 Vue Router 和最小页面壳 | 修改 `frontend/package.json`、`frontend/src/main.ts`、`frontend/src/App.vue`；新增 `frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue`、`frontend/src/views/HomeView.vue` | `npm run build`；手动访问 `/` 和一个不存在路径 | 首页通过 Router 渲染；未知路由有简单兜底；只创建首页占位，不创建其他业务页面 | `feat(frontend): add router and base layout` |
| F04 | 配置开发环境变量和后端代理 | 修改 `frontend/vite.config.ts`；新增 `frontend/.env.example`、`frontend/.env.development`；如仓库忽略规则需要，最小修改根 `.gitignore` | `npm run build`；`npm run dev` 后验证 `/api/v1/health` 代理请求能到后端；后端未启动时确认代理配置被 Vite 加载 | API 基础地址来自环境变量；开发代理不硬编码密钥；不把数据库或 JWT 密钥写入前端 | `chore(frontend): configure api proxy` |
| F05 | 创建通用 API 类型和 Axios 请求实例 | 新增 `frontend/src/types/api.ts`、`frontend/src/utils/request.ts`；修改 `frontend/src/main.ts` 仅在确有需要时注入全局配置 | `npm run build`；用临时只读调用或浏览器网络面板验证基础 URL、JSON 解包和错误消息 | 定义 `ApiResponse<T>`、`PageResult<T>`；Axios 实例可统一处理 `code/message/traceId`；暂不实现登录跳转和管理员逻辑 | `feat(frontend): add axios request client` |
| F06 | 建立最小单元测试环境 | 修改 `frontend/package.json`、`frontend/vite.config.ts`；新增 `frontend/tests/setup.ts`、`frontend/src/utils/request.test.ts` | `npm run test:unit -- src/utils/request.test.ts`、`npm run build` | Vitest 可运行；Axios Mock 可验证成功解包和业务错误；不引入端到端测试平台 | `test(frontend): add minimal unit test setup` |
| F07 | 添加通用枚举和格式化工具 | 新增 `frontend/src/types/enums.ts`、`frontend/src/utils/format.ts`、`frontend/src/utils/format.test.ts` | `npm run test:unit -- src/utils/format.test.ts`、`npm run build` | 资料状态、资料类型、用户角色能转换为中文；未知值显示“未知”；时间和数量格式化稳定 | `feat(frontend): add display enums and formatters` |

### 第一阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| 工程 | Vue 3 + Vite + TypeScript 可启动和构建 |
| UI | Element Plus 已接入，但未批量创建页面 |
| 请求 | Axios 通用请求实例可单独测试 |
| 路由 | 只有首页占位和最小兜底路由 |
| 测试 | Vitest 最小环境可运行 |
| Git | F01-F07 每步均有独立 commit 并已推送 |

## 6. 第二阶段：认证闭环

第二阶段按“API → 单个页面 → 会话恢复 → 退出”逐步完成，不同时生成登录、注册和个人信息页面。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F08 | 实现认证 API 方法 | 新增 `frontend/src/api/auth.ts`、`frontend/src/types/auth.ts`、`frontend/src/api/auth.test.ts` | `npm run test:unit -- src/api/auth.test.ts`、`npm run build` | `register/login/logout` 的方法、路径、body 和 Token 要求与 `03-api-mapping.md` 一致；不创建页面 | `feat(frontend): add auth api client` |
| F09 | 实现登录页面 | 新增 `frontend/src/views/LoginView.vue`、`frontend/src/views/LoginView.test.ts`；修改 `frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue` | `npm run test:unit -- src/views/LoginView.test.ts`、`npm run build`；后端可用时手动登录一次 | 只有用户名、密码、登录和注册链接；提交中禁用按钮；成功保存 Token 并跳转；失败展示后端消息 | `feat(frontend): add login page` |
| F10 | 实现注册页面 | 新增 `frontend/src/views/RegisterView.vue`、`frontend/src/views/RegisterView.test.ts`；修改 `frontend/src/router/index.ts` | `npm run test:unit -- src/views/RegisterView.test.ts`、`npm run build`；后端可用时注册测试账号 | 字段与注册接口一致；基础校验生效；成功跳转登录；不实现验证码或复杂密码强度组件 | `feat(frontend): add register page` |
| F11 | 实现当前用户 API 和简单会话状态 | 新增 `frontend/src/api/users.ts`、`frontend/src/types/user.ts`、`frontend/src/state/session.ts`、`frontend/src/api/users.test.ts`；修改 `frontend/src/utils/request.ts` | `npm run test:unit -- src/api/users.test.ts src/utils/request.test.ts`、`npm run build` | `getCurrentUser` 可调用；Token 和当前用户由简单响应式模块维护；401/40102 清理会话；不引入 Pinia | `feat(frontend): add user session recovery` |
| F12 | 实现个人信息与后端退出 | 新增 `frontend/src/views/user/ProfileView.vue`、`frontend/src/views/user/ProfileView.test.ts`；修改 `frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue` | `npm run test:unit -- src/views/user/ProfileView.test.ts`、`npm run build`；后端可用时验证旧 Token 退出后失效 | 展示 API 已返回的用户字段；退出必须调用后端再清理本地 Token；管理员显示固定管理端入口 | `feat(frontend): add profile and logout flow` |

### 第二阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| 注册登录 | 能完成注册、登录、保存 JWT 和读取当前用户 |
| 退出 | 调用后端退出接口，能演示 Redis Token 黑名单 |
| 页面数量 | 本阶段只新增登录、注册、个人信息三个页面，且分三次提交 |
| 状态管理 | 没有引入复杂全局状态框架 |

## 7. 第三阶段：公开浏览、排行榜与搜索

第三阶段先完成 API，再逐页完成首页、搜索页和只读详情页。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F13 | 实现公开排行榜 API | 新增 `frontend/src/api/rankings.ts`、`frontend/src/types/ranking.ts`、`frontend/src/api/rankings.test.ts` | `npm run test:unit -- src/api/rankings.test.ts`、`npm run build` | 两个 GET 方法均使用 query 参数；资料榜支持四周期，热词榜不接受 `all`；不创建榜单页面 | `feat(frontend): add ranking api client` |
| F14 | 将首页占位改为排行榜首页 | 修改 `frontend/src/views/HomeView.vue`；新增 `frontend/src/views/HomeView.test.ts` | `npm run test:unit -- src/views/HomeView.test.ts`、`npm run build`；后端可用时切换日/周/月/总榜 | 显示热门资料和热门搜索词表格；支持周期切换；Redis 热词降级为空数组时显示空状态；不使用图表 | `feat(frontend): show rankings on home page` |
| F15 | 实现分类和搜索 API | 新增 `frontend/src/api/categories.ts`、`frontend/src/api/search.ts`、`frontend/src/types/category.ts`、`frontend/src/types/search.ts`、对应 API 测试 | `npm run test:unit -- src/api/categories.test.ts src/api/search.test.ts`、`npm run build` | `getCategories` 和 `searchResources` 的 query、分页和响应类型正确；不实现/不调用搜索建议方法 | `feat(frontend): add category and search api clients` |
| F16 | 实现资料搜索页面 | 新增 `frontend/src/views/SearchView.vue`、`frontend/src/views/SearchView.test.ts`；修改 `frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue` | `npm run test:unit -- src/views/SearchView.test.ts`、`npm run build`；后端可用时验证筛选、排序和分页 | 页面包含文档定义的筛选字段、结果和分页；URL 关键词可回填；只展示 APPROVED 结果；没有搜索建议 UI | `feat(frontend): add resource search page` |
| F17 | 实现资料详情和创建资料 API 类型 | 新增 `frontend/src/api/resources.ts`、`frontend/src/types/resource.ts`、`frontend/src/api/resources.test.ts` | `npm run test:unit -- src/api/resources.test.ts`、`npm run build` | `getResourceDetail` 和 `createResource` 路径、body、响应字段正确；此步不创建详情或上传页面 | `feat(frontend): add resource api client` |
| F18 | 实现只读资料详情页 | 新增 `frontend/src/views/ResourceDetailView.vue`、`frontend/src/views/ResourceDetailView.test.ts`；修改 `frontend/src/router/index.ts` | `npm run test:unit -- src/views/ResourceDetailView.test.ts`、`npm run build`；后端可用时打开一条 APPROVED 资料 | 展示详情接口真实字段；游客可访问；收藏和下载按钮先显示为受控占位或暂不显示，不在本步接入操作 | `feat(frontend): add read-only resource detail` |

### 第三阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| 首页 | 能演示 Redis 热门资料和热门搜索词 |
| 搜索 | 能演示筛选、排序、分页和公开状态隔离 |
| 详情 | 只读公开详情已完成，尚未混入收藏和下载功能 |
| 页面提交 | 首页、搜索、详情分别独立提交 |

## 8. 第四阶段：收藏与下载消费链路

收藏与下载分别拆成“API 方法”和“页面交互”两个提交，避免一次改完详情页所有按钮。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F19 | 实现收藏 API | 新增 `frontend/src/api/favorites.ts`、`frontend/src/types/favorite.ts`、`frontend/src/api/favorites.test.ts` | `npm run test:unit -- src/api/favorites.test.ts`、`npm run build` | 收藏、取消收藏、收藏状态三个方法正确；Token 必需；不修改详情页 | `feat(frontend): add favorite api client` |
| F20 | 在详情页接入收藏操作 | 修改 `frontend/src/views/ResourceDetailView.vue`、对应测试；必要时新增小型 `frontend/src/components/FavoriteButton.vue`，但只有确实复用时才提取 | `npm run test:unit -- src/views/ResourceDetailView.test.ts`、`npm run build`；后端可用时验证首次收藏、重复收藏、取消收藏 | 登录用户能查询状态、收藏和取消；游客跳登录；按钮提交中防重复；不根据 `hotScoreDelta` 自行计算热度 | `feat(frontend): add resource favorite actions` |
| F21 | 实现下载 API 和文件流工具 | 新增 `frontend/src/api/downloads.ts`、`frontend/src/types/download.ts`、`frontend/src/utils/file-download.ts`、对应测试 | `npm run test:unit -- src/api/downloads.test.ts src/utils/file-download.test.ts`、`npm run build` | 创建下载记录和文件流方法分离；Authorization 正确；能从 Content-Disposition 提取中文文件名；JSON 错误可识别 | `feat(frontend): add download api client` |
| F22 | 在详情页接入两步下载 | 修改 `frontend/src/views/ResourceDetailView.vue`、对应测试 | `npm run test:unit -- src/views/ResourceDetailView.test.ts`、`npm run build`；后端可用时执行真实下载并观察 `counted` | 严格执行“创建记录 → 获取文件流”；展示是否计数；429 展示后端限流消息；不直接拼存储路径 | `feat(frontend): add resource download flow` |

### 第四阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| 收藏 | 能演示幂等收藏、取消收藏和 Redis 收藏状态缓存 |
| 下载 | 能演示双维度限流、重复下载去重、下载记录和文件流 |
| 详情页演进 | 只读、收藏、下载分别提交，任一步都可单独回归 |

## 9. 第五阶段：文件上传与资料创建

上传页拆为 MD5/文件阶段和资料元数据阶段。只有文件阶段通过后，才允许开发资料创建表单。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F23 | 实现 MD5 工具和文件 API | 修改 `frontend/package.json`（仅此时按评估结果增加 MD5 依赖）；新增 `frontend/src/utils/file-md5.ts`、`frontend/src/utils/file-md5.test.ts`、`frontend/src/api/files.ts`、`frontend/src/types/file.ts`、对应 API 测试 | `npm run test:unit -- src/utils/file-md5.test.ts src/api/files.test.ts`、`npm run build` | 能分片计算文件 MD5；预检使用 `fileMd5 + fileSize` query；上传使用 FormData；大文件不一次性转字符串 | `feat(frontend): add file md5 and upload api` |
| F24 | 实现上传页的文件选择、预检和上传步骤 | 新增 `frontend/src/views/UploadView.vue`、`frontend/src/views/UploadView.test.ts`；修改 `frontend/src/router/index.ts`、`frontend/src/layouts/DefaultLayout.vue` | `npm run test:unit -- src/views/UploadView.test.ts`、`npm run build`；后端可用时验证未命中上传和相同文件秒传 | 只完成文件阶段；显示 MD5 进度、秒传命中和 `fileId`；未命中才上传；本步不提交资料元数据 | `feat(frontend): add file upload step` |
| F25 | 在上传页增加资料元数据表单 | 修改 `frontend/src/views/UploadView.vue`、对应测试；复用 `categories.ts`、`resources.ts` 和已有类型 | `npm run test:unit -- src/views/UploadView.test.ts`、`npm run build` | 能填写标题、简介、分类、课程、类型、标签；没有 `fileId` 时禁止提交资料；本步可先 mock `createResource` | `feat(frontend): add resource metadata form` |
| F26 | 完成资料创建提交和结果跳转 | 修改 `frontend/src/views/UploadView.vue`、对应测试 | `npm run test:unit -- src/views/UploadView.test.ts`、`npm run build`；后端可用时完成一次“上传/秒传 → 创建资料” | 创建成功显示 `resourceId` 和待审核状态；可跳转我的上传；失败保留表单并展示后端消息；不增加编辑或撤回能力 | `feat(frontend): complete resource submission flow` |

### 第五阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| MD5 | 浏览器可计算文件 MD5，工具有专项测试 |
| 秒传 | 命中预检时不重复上传文件 |
| 解耦 | 文件上传先获得 `fileId`，再单独创建 `resource` |
| 状态 | 创建结果明确显示待审核，不直接公开 |
| 提交粒度 | MD5/API、文件页、元数据、最终提交为四个独立 commit |

## 10. 第六阶段：普通用户个人列表

三个个人列表逐个开发。每个列表先增加 API 方法和测试，再增加页面，不能一次创建三个页面。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F27 | 实现“我的上传”API 方法 | 修改 `frontend/src/api/users.ts`、`frontend/src/types/resource.ts`、`frontend/src/api/users.test.ts` | `npm run test:unit -- src/api/users.test.ts`、`npm run build` | `getMyResources` 正确发送状态和分页参数；响应类型只含文档字段 | `feat(frontend): add my resources api` |
| F28 | 实现“我的上传”页面 | 新增 `frontend/src/views/user/MyUploadsView.vue`、对应测试；修改 Router 和导航 | `npm run test:unit -- src/views/user/MyUploadsView.test.ts`、`npm run build`；后端可用时验证状态筛选 | 能按状态筛选和分页；显示拒绝/下架原因；不显示编辑、删除、撤回按钮 | `feat(frontend): add my uploads page` |
| F29 | 实现“我的收藏”API 方法 | 修改 `frontend/src/api/users.ts`、`frontend/src/types/favorite.ts`、`frontend/src/api/users.test.ts` | `npm run test:unit -- src/api/users.test.ts`、`npm run build` | `getMyFavorites` 的分页参数和 records 字段正确 | `feat(frontend): add my favorites api` |
| F30 | 实现“我的收藏”页面 | 新增 `frontend/src/views/user/MyFavoritesView.vue`、对应测试；修改 Router 和导航 | `npm run test:unit -- src/views/user/MyFavoritesView.test.ts`、`npm run build`；后端可用时取消一条收藏 | 能分页、跳详情和取消收藏；取消后列表与总数刷新；不做收藏夹分组 | `feat(frontend): add my favorites page` |
| F31 | 实现“我的下载”API 方法 | 修改 `frontend/src/api/users.ts`、`frontend/src/types/download.ts`、`frontend/src/api/users.test.ts` | `npm run test:unit -- src/api/users.test.ts`、`npm run build` | `getMyDownloadRecords` 参数和 records 字段正确；不增加原文件名、大小、IP 等不存在字段 | `feat(frontend): add my downloads api` |
| F32 | 实现“我的下载”页面 | 新增 `frontend/src/views/user/MyDownloadsView.vue`、对应测试；修改 Router 和导航 | `npm run test:unit -- src/views/user/MyDownloadsView.test.ts`、`npm run build`；后端可用时使用已有记录再次下载 | 能分页、跳详情和再次下载；文件名从响应头读取；只展示 API 已返回字段 | `feat(frontend): add my downloads page` |

### 第六阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| 我的上传 | 状态、原因和分页正确 |
| 我的收藏 | 收藏时间、取消操作和分页正确 |
| 我的下载 | 下载记录字段和再次下载正确 |
| 开发节奏 | 每个列表至少拆为 API 提交和页面提交 |

## 11. 第七阶段：管理员审核与下架

管理员能力先实现只读查询，再实现状态变更。不得在同一步生成所有管理员页面。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F33 | 实现管理员审核只读 API | 新增 `frontend/src/api/admin/resources.ts`、`frontend/src/types/audit.ts`、`frontend/src/api/admin/resources.test.ts` | `npm run test:unit -- src/api/admin/resources.test.ts`、`npm run build` | `getPendingReviews/getAuditRecords` 的路径、query 和 Token 正确；不实现写操作 | `feat(frontend): add admin review query api` |
| F34 | 实现待审核列表页面 | 新增 `frontend/src/views/admin/ReviewManagementView.vue`、对应测试；修改 Router；新增或修改 `frontend/src/layouts/AdminLayout.vue` 仅提供简单固定菜单 | `npm run test:unit -- src/views/admin/ReviewManagementView.test.ts`、`npm run build`；管理员账号验证筛选和分页 | 只展示待审核列表和已有字段；可查看审核流水；普通用户不能通过前端入口访问；本步无通过/拒绝按钮 | `feat(frontend): add pending review page` |
| F35 | 实现管理员审核写 API | 修改 `frontend/src/api/admin/resources.ts`、`frontend/src/types/audit.ts`、对应 API 测试 | `npm run test:unit -- src/api/admin/resources.test.ts`、`npm run build` | `approveResource/rejectResource` 的 path、body 和 Token 正确；拒绝原因必填规则可表达 | `feat(frontend): add review action api` |
| F36 | 在审核页接入通过和拒绝 | 修改 `frontend/src/views/admin/ReviewManagementView.vue`、对应测试 | `npm run test:unit -- src/views/admin/ReviewManagementView.test.ts`、`npm run build`；后端可用时各完成一次通过和拒绝 | 通过意见可选、拒绝原因必填；二次确认；提交后移出待审核列表；409 显示状态冲突 | `feat(frontend): add review actions` |
| F37 | 创建发布资料管理只读列表 | 新增 `frontend/src/views/admin/PublishedResourcesView.vue`、对应测试；修改 Router 和管理员菜单；复用搜索、分类、详情 API | `npm run test:unit -- src/views/admin/PublishedResourcesView.test.ts`、`npm run build` | 只列公开 APPROVED 资料；支持简单筛选、分页和详情；本步没有下架按钮 | `feat(frontend): add published resources page` |
| F38 | 实现下架 API 方法 | 修改 `frontend/src/api/admin/resources.ts`、`frontend/src/types/audit.ts`、对应 API 测试 | `npm run test:unit -- src/api/admin/resources.test.ts`、`npm run build` | `offlineResource` 使用正确 path 和 `offlineReason` body；Token 和错误响应可处理 | `feat(frontend): add resource offline api` |
| F39 | 在发布资料页接入下架和审核流水 | 修改 `frontend/src/views/admin/PublishedResourcesView.vue`、对应测试 | `npm run test:unit -- src/views/admin/PublishedResourcesView.test.ts`、`npm run build`；管理员账号完成一次下架 | 下架原因必填并二次确认；成功后资料移出公开列表；可查看审核/下架流水；不创建全状态后台列表 | `feat(frontend): add resource offline action` |

### 第七阶段完成标志

| 验收项 | 标准 |
| --- | --- |
| 审核 | 列表、流水、通过、拒绝均可演示 |
| 状态机 | 409 冲突可见，不在前端强行改写状态 |
| 下架 | 下架后从公开搜索和详情链路移除 |
| 权限 | 管理员页面有简单入口控制，后端仍执行最终角色校验 |

## 12. 第八阶段：排行榜运维与路由收口

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F40 | 实现管理员排行榜重建 API | 新增 `frontend/src/api/admin/rankings.ts`、对应测试；复用已有排行榜类型 | `npm run test:unit -- src/api/admin/rankings.test.ts`、`npm run build` | `rebuildHotResourceRanking` 无业务参数、携带管理员 Token；成功只处理 `data=null` | `feat(frontend): add ranking rebuild api` |
| F41 | 实现排行榜运维页面 | 新增 `frontend/src/views/admin/RankingManagementView.vue`、对应测试；修改 Router 和管理员菜单 | `npm run test:unit -- src/views/admin/RankingManagementView.test.ts`、`npm run build`；管理员账号验证总榜重建 | 使用表格展示资料榜和热词榜；周期范围正确；重建有二次确认和请求中状态；不展示虚构进度或图表 | `feat(frontend): add ranking management page` |
| F42 | 收口登录和管理员路由守卫 | 修改 `frontend/src/router/index.ts`、`frontend/src/state/session.ts`、Router 测试；必要时修改两个 Layout | `npm run test:unit -- src/router/index.test.ts`、`npm run build`；手动验证游客、普通用户、管理员三类跳转 | 游客访问受保护页跳登录；普通用户访问管理页提示 403/返回首页；刷新可恢复用户；守卫不代替后端鉴权 | `feat(frontend): finalize route guards` |
| F43 | 收口固定导航和空/错/加载状态 | 修改 `DefaultLayout.vue`、`AdminLayout.vue` 和各页面中确有缺失的状态；不新增业务页面 | 运行 `npm run test:unit`、`npm run build`；逐页检查加载、空数据、错误和按钮提交中 | 所有页面导航可达；菜单不动态配置；关键页面具备加载、空、错误和重试；无复杂视觉重构 | `fix(frontend): complete page states and navigation` |

## 13. 第九阶段：后端演示验收

本阶段不增加新业务功能，只验证前端是否能完整演示后端。

| 步骤 | 最小任务 | 涉及文件 | 测试方式 | 验收标准 | 建议 commit message |
| --- | --- | --- | --- | --- | --- |
| F44 | 验证认证和上传审核闭环 | 仅在发现缺陷时修改对应页面/测试；新增 `frontend/tests/manual/auth-upload-review.md` 记录步骤和结果 | `npm run test:unit`、`npm run build`；真实后端执行注册、登录、MD5 预检、上传、创建、审核 | 同文件二次上传可演示秒传；资料先待审核再公开；退出后旧 Token 失效；不顺带修改其他模块 | `test(frontend): verify auth upload review flow` |
| F45 | 验证搜索收藏下载闭环 | 仅修改缺陷相关文件；新增 `frontend/tests/manual/resource-consumption.md` | `npm run test:unit`、`npm run build`；真实后端执行搜索、详情、收藏、取消、下载、重复下载和限流 | 搜索只返回已通过资料；收藏状态一致；两步下载成功；重复下载 `counted=false`；429 可见 | `test(frontend): verify resource consumption flow` |
| F46 | 验证排行榜和管理员下架闭环 | 仅修改缺陷相关文件；新增 `frontend/tests/manual/ranking-admin.md` | `npm run test:unit`、`npm run build`；真实后端执行热度行为、榜单查询、手动重建、资料下架 | 榜单周期切换正确；重建成功；下架资料退出搜索、详情和排行榜；普通用户调用管理接口被拒绝 | `test(frontend): verify ranking admin flow` |
| F47 | 整理启动说明和最终验证记录 | 新增 `frontend/README.md`；按实际情况更新 `docs/frontend/04-frontend-dev-plan.md` 的完成状态，不修改后端业务文档除非发现真实不一致 | 从干净环境执行 `npm install`、`npm run test:unit`、`npm run build`、`npm run dev` | README 只包含安装、环境变量、启动、构建、测试和演示账号准备方式；不写真实密码/Token；记录最终测试数量和 commit | `docs(frontend): add frontend runbook` |

## 14. 推荐实施顺序与停靠点

### 14.1 顺序

| 阶段 | 步骤 | 完成后可独立演示 |
| --- | --- | --- |
| 工程骨架 | F01-F07 | 工程启动、Element Plus、路由、Axios 和测试环境 |
| 认证闭环 | F08-F12 | 注册、登录、当前用户、退出黑名单 |
| 公开浏览 | F13-F18 | 首页排行榜、资料搜索、只读详情 |
| 资料消费 | F19-F22 | 收藏、取消收藏、下载和限流提示 |
| 资料生产 | F23-F26 | MD5 秒传、文件上传、创建待审核资料 |
| 个人中心 | F27-F32 | 我的上传、收藏、下载记录 |
| 管理员 | F33-F39 | 审核、拒绝、下架和审计流水 |
| 榜单运维 | F40-F43 | 总榜重建、路由守卫和页面状态收口 |
| 演示验收 | F44-F47 | 三条完整后端演示链路和运行说明 |

### 14.2 可停靠点

| 停靠点 | 已具备能力 | 暂不继续也不会破坏的范围 |
| --- | --- | --- |
| F07 | 可运行工程骨架 | 尚无业务页面 |
| F12 | 完整认证演示 | 尚无资料业务页面 |
| F18 | 游客可浏览和搜索 | 尚无收藏、下载、上传 |
| F22 | 普通用户可消费资料 | 尚无上传和管理端 |
| F26 | 普通用户可上传资料 | 审核需暂用 Postman/Apifox |
| F32 | 普通用户端基本完成 | 管理员仍可用接口工具操作 |
| F39 | 审核和下架闭环完成 | 排行榜重建仍可用接口工具 |
| F43 | 所有首版页面完成 | 只剩真实环境验收和说明 |

每个停靠点都必须保持 `npm run build` 通过，不允许以“后面再补”为由提交无法运行的中间状态。

## 15. 单步骤任务模板

实际执行任一步骤时，先把该步骤复制为单独任务，并按以下模板约束范围：

```text
当前只执行步骤 Fxx：<步骤名称>。

目标：
- <本步骤唯一目标>

允许修改：
- <本步骤涉及文件>

本步骤不做：
- 不创建其他页面；
- 不实现下一步骤 API；
- 不重构无关代码；
- 不引入未说明依赖。

测试：
- <专项测试命令>
- npm run build
- git diff --check

验收：
- <本步骤验收标准>

通过后：
- git add 本步骤文件
- git commit -m "<本步骤 commit message>"
- git push 当前开发分支
- 记录测试结果、commit id 和推送结果
```

## 16. 计划验收标准

| 验收项 | 标准 |
| --- | --- |
| 技术栈 | 明确使用 Vue 3 + Vite + TypeScript + Element Plus + Axios |
| 项目定位 | 所有页面和技术选择只服务后端功能演示 |
| 步骤粒度 | 每步只实现一个工程能力、一个 API 小组、一个页面或一个页面动作 |
| 文件范围 | F01-F47 每步均列出新增或修改文件 |
| 测试 | 每步均有专项测试或手动验证，并默认要求 `npm run build` |
| 验收标准 | 每步都有可观察、可判断的完成条件 |
| Git | 每步测试通过后独立提交并立即推送当前开发分支 |
| 页面节奏 | 不一次性生成所有页面；页面按登录、注册、首页、搜索、详情等顺序逐个实现 |
| 接口真实性 | 只调用 `03-api-mapping.md` 中已实现接口，搜索建议不进入首版 |
| 简洁性 | 不引入 Pinia、动态菜单、复杂图表、微前端或重型工程化 |
