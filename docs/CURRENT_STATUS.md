# 当前项目状态

## 基本信息

| 项目 | 当前状态 |
| --- | --- |
| 项目名称 | 校园资料共享与智能检索平台 |
| 当前分支 | `dev` |
| 当前后端状态 | 认证、分类、文件、资料、审核、搜索、下载、收藏、排行榜与定时任务均已完成首版 |
| 当前前端状态 | 已完成 T01-T37：基础、普通用户演示、管理员审核与发布资料只读页面；正在执行 T38：下架 API |
| 当前自动队列 | `docs/frontend/05-frontend-task-queue.md`，当前执行任务为 `T38` |

## 当前工作

- 目标：使用 Vue 3 + Vite + Element Plus + Axios 构建轻量演示前端。
- 执行方式：仅在用户明确要求时，按前端任务队列自动推进；每个子任务独立测试、提交并推送。
- 本轮上限：最多推进三个开发阶段，具体以用户本轮指令为准。

## 当前前端任务边界

| 项目 | 说明 |
| --- | --- |
| 允许范围 | `frontend/` 前端代码、前端测试、前端运行说明，以及队列要求的前端进度文档 |
| 不允许范围 | 后端 Java、SQL、数据库、Redis、接口路径和其他业务模块 |
| 当前任务 | `T38`：实现下架 API |
| 后续任务 | `T39`：接入下架和审核流水 |
| 停止条件 | 见 `docs/frontend/05-frontend-task-queue.md` 的“停止条件” |

## 已知事项

- 前端未创建前，不应假设任何前端目录、依赖、路由或页面已存在。
- 搜索建议接口当前未实现，不能进入首版页面调用链。
- 前端权限控制只负责入口提示；JWT、管理员角色和资源访问权限以后端为最终边界。

## 最近验证记录

| 范围 | 命令/方式 | 结果 |
| --- | --- | --- |
| 后端现有代码 | 见 `docs/06-project-progress.md` 与各模块文档 | 已有后端验证记录 |
| 前端 T01 | `npm run build`；Vite 开发服务 HTTP 请求 | 构建通过；`http://127.0.0.1:5173/` 返回 HTTP 200 |
| 前端 T02 | `npm run build`；Vite 开发服务 HTTP 请求 | Element Plus 构建通过；`http://127.0.0.1:5173/` 返回 HTTP 200 |
| 前端 T03 | `npm run build`；Vite 首页和未知路径请求 | 构建通过；两条路径均返回 HTTP 200 SPA 入口 |
| 前端 T04 | `npm run build`；Vite 首页请求；8080 端口探测 | 构建与 Vite 启动通过；后端端口未监听，真实代理联调待补 |
| 前端 T05 | `npm run build` | Axios 请求层构建通过；专项 Mock 测试待 T06 建立 |
| 前端 T06 | `npm run test:unit -- src/utils/request.test.ts`；`npm run build` | 请求层测试 3/3 通过；构建通过 |
| 前端 T07 | `npm run test:unit -- src/utils/format.test.ts`；`npm run build` | 枚举和格式化测试 4/4 通过；构建通过 |
| 前端 T08 | `npm run test:unit -- src/api/auth.test.ts`；`npm run build` | 认证 API 测试 3/3 通过；构建通过 |
| 前端 T09 | `npm run test:unit -- src/views/LoginView.test.ts`；`npm run build` | 登录页组件测试 1/1 通过；后端未启动，真实登录待联调 |
| 前端 T10 | `npm run test:unit -- src/views/RegisterView.test.ts`；`npm run build` | 注册页组件测试 1/1 通过；后端未启动，真实注册待联调 |
| 前端 T11 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 会话与当前用户 API 测试 2/2 通过；构建通过 |
| 前端 T12 | `npm run test:unit -- src/views/user/ProfileView.test.ts`；`npm run build` | 个人信息页组件测试 1/1 通过；真实退出待后端启动后联调 |
| 前端 T13 | `npm run test:unit -- src/api/rankings.test.ts`；`npm run build` | 排行榜 API 测试 2/2 通过；构建通过 |
| 前端 T25 | `npm run test:unit -- src/views/UploadView.test.ts`；`npm run build` | 上传页组件测试 2/2 通过；可填写资料字段，未取得 `fileId` 时不能提交 |
| 前端 T26 | `npm run test:unit -- src/views/UploadView.test.ts`；`npm run build` | 上传页组件测试 3/3 通过；Mock 验证创建请求、待审核结果和我的上传入口 |
| 前端 T27 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 用户 API 测试 3/3 通过；Token、状态和分页 query 正确 |
| 前端 T28 | `npm run test:unit -- src/views/user/MyUploadsView.test.ts`；`npm run build` | 我的上传页面组件测试 1/1 通过；资料状态和拒绝原因正确展示 |
| 前端 T29 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 用户 API 测试 4/4 通过；收藏列表 Token 和分页参数正确 |
| 前端 T30 | `npm run test:unit -- src/views/user/MyFavoritesView.test.ts`；`npm run build` | 我的收藏页面组件测试 1/1 通过；取消收藏后重新加载列表 |
| 前端 T31 | `npm run test:unit -- src/api/users.test.ts`；`npm run build` | 用户 API 测试 5/5 通过；下载记录 Token 和分页参数正确 |
| 前端 T32 | `npm run test:unit -- src/views/user/MyDownloadsView.test.ts`；`npm run test:unit`；`npm run build` | 页面测试 1/1、全量测试 45/45 和构建通过；再次下载两步链路正确 |
| 前端 T33 | `npm run test:unit -- src/api/admin/resources.test.ts`；`npm run build` | 管理员资料 API 测试 2/2 通过；待审核列表和审核流水请求正确 |
| 前端 T34 | `npm run test:unit -- src/views/admin/ReviewManagementView.test.ts`；`npm run build` | 待审核页面测试 1/1 通过；只读列表和审核流水正确 |
| 前端 T35 | `npm run test:unit -- src/api/admin/resources.test.ts`；`npm run build` | 管理员资料 API 测试 4/4 通过；审核通过和拒绝请求正确 |
| 前端 T36 | `npm run test:unit -- src/views/admin/ReviewManagementView.test.ts`；`npm run build` | 待审核页面测试 3/3 通过；确认、拒绝校验和 409 错误展示正确 |
| 前端 T37 | `npm run test:unit -- src/views/admin/PublishedResourcesView.test.ts`；`npm run build` | 发布资料页面测试 1/1 通过；仅渲染公开搜索返回的已通过资料 |

## 下一步

按前端任务队列继续执行 `T38`，仅实现下架 API。
