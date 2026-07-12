# 校园资料共享与智能检索平台前端接口映射文档

## 1. 文档目标

本文档逐项读取 `docs/04-api-doc.md` 中第 3 至第 10 节的全部 27 个接口，为前端设计 API 文件、方法名、使用页面、Authorization Token 要求、请求参数和响应字段。

本文档只做前端调用映射，不修改后端接口。接口文档未明确、存在歧义或仍处于规划状态的内容统一标记为“文档缺失/待确认”，不通过前端设计补造字段或规则。

## 2. 前端 API 目录建议

| 文件 | 职责 | 包含方法 |
| --- | --- | --- |
| `src/api/auth.ts` | 注册、登录、退出 | `register`、`login`、`logout` |
| `src/api/users.ts` | 当前用户及个人中心列表 | `getCurrentUser`、`getMyResources`、`getMyFavorites`、`getMyDownloadRecords` |
| `src/api/resources.ts` | 资料创建和公开详情 | `getResourceDetail`、`createResource` |
| `src/api/categories.ts` | 分类查询 | `getCategories` |
| `src/api/files.ts` | MD5 预检和文件上传 | `checkFileDuplicate`、`uploadFile` |
| `src/api/admin/resources.ts` | 管理员审核和下架 | `getPendingReviews`、`approveResource`、`rejectResource`、`offlineResource`、`getAuditRecords` |
| `src/api/favorites.ts` | 收藏操作和状态 | `addFavorite`、`removeFavorite`、`getFavoriteStatus` |
| `src/api/downloads.ts` | 创建下载记录和文件流 | `createDownloadRecord`、`downloadFile` |
| `src/api/rankings.ts` | 公开排行榜 | `getHotResources`、`getHotSearchKeywords` |
| `src/api/admin/rankings.ts` | 管理员排行榜运维 | `rebuildHotResourceRanking` |
| `src/api/search.ts` | 资料搜索和预留搜索建议 | `searchResources`、`getSearchSuggestions` |

> `getMyResources`、`getMyFavorites` 和 `getMyDownloadRecords` 按 URL 归入 `users.ts`；收藏写操作归入 `favorites.ts`，下载写操作和文件流归入 `downloads.ts`。该拆分只影响前端组织，不改变后端路径。

## 3. 通用调用约定

### 3.1 基础配置

| 项目 | 约定 |
| --- | --- |
| API 基础路径 | `/api/v1`；如请求实例已配置该前缀，方法内只写剩余路径 |
| JSON 请求 | `Content-Type: application/json` |
| 文件上传 | `Content-Type: multipart/form-data`；建议让浏览器自动生成 boundary |
| Token 请求头 | `Authorization: Bearer {token}` |
| GET 参数 | 使用 query 参数或 path 参数，不把文档中的“请求示例 JSON”作为 GET 请求体发送 |
| 普通成功响应 | `ApiResponse<T>` |
| 分页成功响应 | `ApiResponse<PageResult<T>>` |
| 文件流成功响应 | 二进制 Blob/ArrayBuffer，不使用 `ApiResponse<T>` |

### 3.2 通用响应字段

| 类型 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| `ApiResponse<T>` | `code` | number | `0` 表示成功 |
| `ApiResponse<T>` | `message` | string | 成功或错误说明 |
| `ApiResponse<T>` | `data` | T 或 null | 业务数据 |
| `ApiResponse<T>` | `traceId` | string | 请求追踪标识 |
| `PageResult<T>` | `records` | T[] | 当前页记录 |
| `PageResult<T>` | `pageNo` | number | 当前页码 |
| `PageResult<T>` | `pageSize` | number | 每页数量 |
| `PageResult<T>` | `total` | number | 总记录数 |
| `PageResult<T>` | `pages` | number | 总页数 |

后续各接口的“响应字段”只列 `data` 内部字段；所有 JSON 接口外层默认仍包含 `code/message/data/traceId`。

### 3.3 Token 处理

| Token 标记 | 前端处理 |
| --- | --- |
| 否 | 不要求 Authorization；即使本地有 Token，也不依赖 Token 才能调用 |
| 是 | 请求拦截器必须附加 Authorization Token |
| 管理员 | 必须附加 Token，并由后端继续校验 `role = 2`；前端隐藏按钮不构成权限控制 |

## 4. 接口总映射

| 序号 | 后端接口 | 前端 API 文件 | 方法名 | 使用页面 | Token | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/auth/register` | `src/api/auth.ts` | `register` | `/register` | 否 | 已实现 |
| 2 | `POST /api/v1/auth/login` | `src/api/auth.ts` | `login` | `/login` | 否 | 已实现 |
| 3 | `POST /api/v1/auth/logout` | `src/api/auth.ts` | `logout` | `/me/profile` | 是 | 已实现 |
| 4 | `GET /api/v1/users/me` | `src/api/users.ts` | `getCurrentUser` | 应用初始化、`/login`、`/me/profile` | 是 | 已实现 |
| 5 | `GET /api/v1/resources/{resourceId}` | `src/api/resources.ts` | `getResourceDetail` | `/resources/:id`、`/admin/resources` | 否 | 已实现 |
| 6 | `GET /api/v1/users/me/resources` | `src/api/users.ts` | `getMyResources` | `/me/uploads` | 是 | 已实现 |
| 7 | `POST /api/v1/resources` | `src/api/resources.ts` | `createResource` | `/upload` | 是 | 已实现 |
| 8 | `GET /api/v1/categories` | `src/api/categories.ts` | `getCategories` | `/search`、`/upload`、`/admin/resources` | 否 | 已实现 |
| 9 | `GET /api/v1/files/check` | `src/api/files.ts` | `checkFileDuplicate` | `/upload` | 是 | 已实现 |
| 10 | `POST /api/v1/files` | `src/api/files.ts` | `uploadFile` | `/upload` | 是 | 已实现 |
| 11 | `GET /api/v1/admin/resources/pending-reviews` | `src/api/admin/resources.ts` | `getPendingReviews` | `/admin/reviews` | 管理员 | 已实现 |
| 12 | `POST /api/v1/admin/resources/{resourceId}/audit-approvals` | `src/api/admin/resources.ts` | `approveResource` | `/admin/reviews` | 管理员 | 已实现 |
| 13 | `POST /api/v1/admin/resources/{resourceId}/audit-rejections` | `src/api/admin/resources.ts` | `rejectResource` | `/admin/reviews` | 管理员 | 已实现 |
| 14 | `POST /api/v1/admin/resources/{resourceId}/offline-records` | `src/api/admin/resources.ts` | `offlineResource` | `/admin/resources` | 管理员 | 已实现 |
| 15 | `GET /api/v1/admin/resources/{resourceId}/audit-records` | `src/api/admin/resources.ts` | `getAuditRecords` | `/admin/reviews`、`/admin/resources` | 管理员 | 已实现 |
| 16 | `POST /api/v1/resources/{resourceId}/favorites` | `src/api/favorites.ts` | `addFavorite` | `/resources/:id` | 是 | 已实现 |
| 17 | `DELETE /api/v1/resources/{resourceId}/favorites` | `src/api/favorites.ts` | `removeFavorite` | `/resources/:id`、`/me/favorites` | 是 | 已实现 |
| 18 | `GET /api/v1/users/me/favorites` | `src/api/users.ts` | `getMyFavorites` | `/me/favorites` | 是 | 已实现 |
| 19 | `GET /api/v1/resources/{resourceId}/favorite-status` | `src/api/favorites.ts` | `getFavoriteStatus` | `/resources/:id` | 是 | 已实现 |
| 20 | `POST /api/v1/resources/{resourceId}/download-records` | `src/api/downloads.ts` | `createDownloadRecord` | `/resources/:id` | 是 | 已实现 |
| 21 | `GET /api/v1/download-records/{downloadRecordId}/file` | `src/api/downloads.ts` | `downloadFile` | `/resources/:id`、`/me/downloads` | 是 | 已实现 |
| 22 | `GET /api/v1/users/me/download-records` | `src/api/users.ts` | `getMyDownloadRecords` | `/me/downloads` | 是 | 已实现 |
| 23 | `GET /api/v1/rankings/resources/hot` | `src/api/rankings.ts` | `getHotResources` | `/`、`/admin/rankings` | 否 | 已实现 |
| 24 | `GET /api/v1/rankings/search-keywords/hot` | `src/api/rankings.ts` | `getHotSearchKeywords` | `/`、`/admin/rankings` | 否 | 已实现 |
| 25 | `POST /api/v1/admin/rankings/resources/hot/rebuild` | `src/api/admin/rankings.ts` | `rebuildHotResourceRanking` | `/admin/rankings` | 管理员 | 已实现 |
| 26 | `GET /api/v1/search/resources` | `src/api/search.ts` | `searchResources` | `/search`、`/admin/resources` | 否 | 已实现 |
| 27 | `GET /api/v1/search/suggestions` | `src/api/search.ts` | `getSearchSuggestions` | 首版页面不调用；未来可用于 `/search` | 否 | **未实现，仅预留** |

> `docs/04-api-doc.md` 第 3 至第 10 节共包含 27 个带请求方法和 URL 的接口，其中 26 个已实现，搜索建议接口尚未实现。

## 5. 认证与当前用户接口

### 5.1 用户注册 `register`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/auth.ts` |
| 建议签名 | `register(data: RegisterRequest)` |
| 请求 | `POST /auth/register`，JSON body |
| 使用页面 | `/register` |
| Authorization | 否 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 非空，最大 50 字符 |
| `password` | string | 是 | 8 至 50 字符 |
| `nickname` | string | 是 | 非空，最大 50 字符 |
| `email` | string | 否 | 合法邮箱，最大 100 字符 |
| `phone` | string | 否 | 中国大陆手机号格式 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | number | 新用户 ID |
| `username` | string | 用户名 |
| `nickname` | string | 昵称 |
| `email` | string/null | 邮箱 |
| `role` | number | `1` 学生、`2` 管理员 |
| `status` | number | 用户状态；具体枚举含义在 API 文档中缺失 |

缺失说明：请求允许提交 `phone`，但响应不返回 `phone`；这是当前文档定义，前端不要假设响应包含手机号。

### 5.2 用户登录 `login`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/auth.ts` |
| 建议签名 | `login(data: LoginRequest)` |
| 请求 | `POST /auth/login`，JSON body |
| 使用页面 | `/login` |
| Authorization | 否 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 登录账号 |
| `password` | string | 是 | 登录密码 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `accessToken` | string | JWT |
| `tokenType` | string | 示例为 `Bearer` |
| `expiresIn` | number | Token 有效秒数 |
| `user.userId` | number | 用户 ID |
| `user.username` | string | 用户名 |
| `user.nickname` | string | 昵称 |
| `user.email` | string/null | 邮箱 |
| `user.status` | number | 用户状态，枚举含义未完整说明 |
| `user.role` | number | `1` 学生、`2` 管理员 |

缺失说明：文档列出 `42901`，同时明确登录限流当前未实现；前端不能展示为已具备的能力。

### 5.3 用户退出 `logout`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/auth.ts` |
| 建议签名 | `logout()` |
| 请求 | `POST /auth/logout`，空 JSON body 或无业务字段 |
| 使用页面 | `/me/profile` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | Token 从 Authorization 请求头读取 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `data` | null | 无业务数据 |

缺失说明：文档未明确空请求应发送 `{}` 还是完全无 body；示例为 `{}`。前端可按示例发送空对象，不增加业务字段。

### 5.4 获取当前用户 `getCurrentUser`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/users.ts` |
| 建议签名 | `getCurrentUser()` |
| 请求 | `GET /users/me` |
| 使用页面 | 应用初始化、`/login` 登录后确认、`/me/profile` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 无 query、path 或 body 参数 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | number | 当前用户 ID |
| `username` | string | 用户名 |
| `nickname` | string | 昵称 |
| `email` | string/null | 邮箱 |
| `role` | number | `1` 学生、`2` 管理员 |
| `status` | number | 用户状态，枚举含义未完整说明 |

## 6. 资料、分类与文件接口

### 6.1 获取公开资料详情 `getResourceDetail`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/resources.ts` |
| 建议签名 | `getResourceDetail(resourceId: number)` |
| 请求 | `GET /resources/{resourceId}` |
| 使用页面 | `/resources/:id`、`/admin/resources` |
| Authorization | 否 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `title` | string | 标题 |
| `description` | string/null | 简介 |
| `categoryId` | number | 分类 ID |
| `categoryName` | string | 分类名称 |
| `courseName` | string | 课程名称 |
| `resourceType` | number | 资料类型枚举 |
| `tags` | string[] | 标签列表 |
| `status` | number | 公开接口正常只返回 `1` |
| `downloadCount` | number | 下载量 |
| `favoriteCount` | number | 收藏量 |
| `hotScore` | number | 热度分 |
| `createdAt` | string | 创建时间 |
| `favorited` | boolean/null | 文档称为预留字段，示例为 null |

缺失说明：文档仍写“收藏模块未接入前返回 null”，但同一文档第 7 节收藏模块已实现。详情接口是否会根据 Token 实时填充 `favorited` 没有明确更新；前端应以 `GET /resources/{resourceId}/favorite-status` 为登录用户的收藏状态来源。

### 6.2 获取我的上传 `getMyResources`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/users.ts` |
| 建议签名 | `getMyResources(params: MyResourceQuery)` |
| 请求 | `GET /users/me/resources`，query 参数 |
| 使用页面 | `/me/uploads` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | number | 否 | `0-4` 资料状态 |
| `pageNo` | number | 否 | 默认 1 |
| `pageSize` | number | 否 | 默认 10；最大值未说明 |

| 响应 records 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `title` | string | 标题 |
| `courseName` | string | 课程名称 |
| `status` | number | 资料状态 |
| `rejectReason` | string/null | 拒绝原因 |
| `offlineReason` | string/null | 下架原因 |
| `createdAt` | string | 创建时间 |

响应同时包含通用分页字段。缺失说明：`pageSize` 的最大值未在该接口小节说明，前端不要自行宣称为 100。

### 6.3 创建资料 `createResource`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/resources.ts` |
| 建议签名 | `createResource(data: CreateResourceRequest)` |
| 请求 | `POST /resources`，JSON body |
| 使用页面 | `/upload` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fileId` | number | 是 | 已上传文件 ID |
| `title` | string | 是 | 资料标题 |
| `description` | string | 否 | 资料简介 |
| `categoryId` | number | 是 | 启用分类 ID |
| `courseName` | string | 是 | 课程名称 |
| `resourceType` | number | 是 | `1/2/3/4/5/99` |
| `tags` | string[] | 否 | 标签列表 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 新资料 ID |
| `fileId` | number | 关联文件 ID |
| `status` | number | 初始状态 `0` |
| `statusName` | string | 示例为 `PENDING_REVIEW` |
| `message` | string | 创建结果说明 |

缺失说明：接口小节没有列出标题、简介、课程名称和标签的精确长度上限，仅以“参数错误”概括；前端校验应以后端实际约束为准，不能从文档推测数值。

### 6.4 获取分类 `getCategories`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/categories.ts` |
| 建议签名 | `getCategories(params?: { parentId?: number })` |
| 请求 | `GET /categories`，query 参数 |
| 使用页面 | `/search`、`/upload`、`/admin/resources` |
| Authorization | 否 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `parentId` | number | 否 | 默认 0，不能小于 0 |

| 响应 data 数组字段 | 类型 | 说明 |
| --- | --- | --- |
| `categoryId` | number | 分类 ID |
| `parentId` | number | 父分类 ID |
| `categoryName` | string | 分类名称 |
| `description` | string/null | 分类说明 |
| `sortOrder` | number | 排序值 |

缺失说明：文档没有说明分类树最大层级，也没有一次返回整棵树的接口；前端只能按 `parentId` 分次查询。

### 6.5 文件 MD5 预检 `checkFileDuplicate`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/files.ts` |
| 建议签名 | `checkFileDuplicate(params: FileCheckQuery)` |
| 请求 | `GET /files/check`，query 参数 |
| 使用页面 | `/upload` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fileMd5` | string | 是 | 32 位十六进制 MD5 |
| `fileSize` | number | 是 | 字节数，不能小于 0 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `secondUpload` | boolean | 是否命中秒传 |
| `fileId` | number/null | 命中时可复用的文件 ID；未命中示例未提供 |

缺失说明：未命中时 `fileId` 究竟返回 `null` 还是字段缺省，文档没有给出示例；前端应同时兼容 null/undefined，不自行填充 ID。

### 6.6 上传文件 `uploadFile`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/files.ts` |
| 建议签名 | `uploadFile(file: File, onUploadProgress?: ProgressCallback)` |
| 请求 | `POST /files`，`multipart/form-data` |
| 使用页面 | `/upload` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | File | 是 | multipart 表单字段 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileId` | number | 文件 ID |
| `fileMd5` | string | 服务端计算/确认的 MD5 |
| `originalName` | string | 原始文件名 |
| `fileSize` | number | 字节数 |
| `fileExt` | string | 文件扩展名 |
| `secondUpload` | boolean | 本次是否复用已有文件 |

缺失说明：文档没有列出允许扩展名/MIME 白名单和最大文件大小的具体配置值，只定义对应错误码；前端不得硬编码一套未经确认的限制。

## 7. 管理员审核接口

### 7.1 待审核列表 `getPendingReviews`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/admin/resources.ts` |
| 建议签名 | `getPendingReviews(params: PendingReviewQuery)` |
| 请求 | `GET /admin/resources/pending-reviews`，query 参数 |
| 使用页面 | `/admin/reviews` |
| Authorization | 是，管理员 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `courseName` | string | 否 | 课程名称筛选 |
| `resourceType` | number | 否 | 资料类型 |
| `uploaderId` | number | 否 | 上传用户 ID |
| `pageNo` | number | 否 | 默认 1 |
| `pageSize` | number | 否 | 默认 10，最大 100 |

| 响应 records 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `title` | string | 标题 |
| `description` | string/null | 简介 |
| `categoryId` | number | 分类 ID |
| `courseName` | string | 课程名称 |
| `resourceType` | number | 资料类型 |
| `tags` | string[] | 标签 |
| `fileId` | number | 文件 ID |
| `uploaderId` | number | 上传者 ID |
| `status` | number | 待审核状态，示例为 0 |
| `createdAt` | string | 创建时间 |

响应同时包含通用分页字段。缺失说明：后端未提供管理员待审核详情接口；列表也不返回分类名称、上传者名称或文件元数据，前端不得编造。

### 7.2 审核通过 `approveResource`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/admin/resources.ts` |
| 建议签名 | `approveResource(resourceId: number, data: { auditReason?: string })` |
| 请求 | `POST /admin/resources/{resourceId}/audit-approvals`，JSON body |
| 使用页面 | `/admin/reviews` |
| Authorization | 是，管理员 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |
| `auditReason` | body | string | 否 | 最大 500 字符 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `actionType` | number | 示例为 1 |
| `beforeStatus` | number | 原状态，示例为 0 |
| `afterStatus` | number | 新状态，示例为 1 |
| `auditRecordId` | number | 审核记录 ID |
| `auditReason` | string/null | 审核意见 |
| `approvedAt` | string/null | 通过时间 |
| `offlineAt` | string/null | 下架时间，此操作示例为 null |

### 7.3 审核拒绝 `rejectResource`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/admin/resources.ts` |
| 建议签名 | `rejectResource(resourceId: number, data: { rejectReason: string })` |
| 请求 | `POST /admin/resources/{resourceId}/audit-rejections`，JSON body |
| 使用页面 | `/admin/reviews` |
| Authorization | 是，管理员 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |
| `rejectReason` | body | string | 是 | 最大 500 字符 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `actionType` | number | 示例为 2 |
| `beforeStatus` | number | 原状态，示例为 0 |
| `afterStatus` | number | 新状态，示例为 2 |
| `auditRecordId` | number | 审核记录 ID |
| `auditReason` | string | 响应统一命名为 `auditReason`，值来自拒绝原因 |
| `approvedAt` | null | 示例为 null |
| `offlineAt` | null | 示例为 null |

### 7.4 下架资料 `offlineResource`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/admin/resources.ts` |
| 建议签名 | `offlineResource(resourceId: number, data: { offlineReason: string })` |
| 请求 | `POST /admin/resources/{resourceId}/offline-records`，JSON body |
| 使用页面 | `/admin/resources` |
| Authorization | 是，管理员 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |
| `offlineReason` | body | string | 是 | 最大 500 字符 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `actionType` | number | 示例为 3 |
| `beforeStatus` | number | 原状态，示例为 1 |
| `afterStatus` | number | 新状态，示例为 3 |
| `auditRecordId` | number | 审核记录 ID |
| `auditReason` | string | 响应统一命名为 `auditReason`，值来自下架原因 |
| `approvedAt` | null | 示例为 null；是否保留历史通过时间未说明 |
| `offlineAt` | string | 下架时间 |

### 7.5 审核记录 `getAuditRecords`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/admin/resources.ts` |
| 建议签名 | `getAuditRecords(resourceId: number)` |
| 请求 | `GET /admin/resources/{resourceId}/audit-records` |
| 使用页面 | `/admin/reviews`、`/admin/resources` |
| Authorization | 是，管理员 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |

| 响应 data 数组字段 | 类型 | 说明 |
| --- | --- | --- |
| `auditRecordId` | number | 审核记录 ID |
| `resourceId` | number | 资料 ID |
| `auditorId` | number | 审核人 ID |
| `actionType` | number | 操作类型 |
| `beforeStatus` | number | 原状态 |
| `afterStatus` | number | 新状态 |
| `auditReason` | string/null | 操作原因 |
| `createdAt` | string | 操作时间 |

审核模块缺失说明：API 文档只通过示例体现 `actionType` 的 `1/2/3`，没有在枚举约定中正式定义完整操作类型；前端可暂按已知示例显示通过、拒绝、下架，但应保留未知值兜底，不扩展更多枚举。

## 8. 收藏接口

### 8.1 收藏资料 `addFavorite`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/favorites.ts` |
| 建议签名 | `addFavorite(resourceId: number)` |
| 请求 | `POST /resources/{resourceId}/favorites`，无 body |
| 使用页面 | `/resources/:id` |
| Authorization | 是 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `favorited` | boolean | 收藏后为 true |
| `duplicateIgnored` | boolean | 重复收藏是否被幂等忽略 |
| `favoriteCount` | number | 最新收藏数 |
| `hotScoreDelta` | number | 当前兼容字段，固定为 0 |

### 8.2 取消收藏 `removeFavorite`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/favorites.ts` |
| 建议签名 | `removeFavorite(resourceId: number)` |
| 请求 | `DELETE /resources/{resourceId}/favorites`，无 body |
| 使用页面 | `/resources/:id`、`/me/favorites` |
| Authorization | 是 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `favorited` | boolean | 取消后为 false |
| `duplicateIgnored` | boolean | 示例为 false |
| `favoriteCount` | number | 最新收藏数 |
| `hotScoreDelta` | number | 当前兼容字段，固定为 0 |

### 8.3 我的收藏 `getMyFavorites`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/users.ts` |
| 建议签名 | `getMyFavorites(params: PageQuery)` |
| 请求 | `GET /users/me/favorites`，query 参数 |
| 使用页面 | `/me/favorites` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `pageNo` | number | 否 | 默认 1，最小 1 |
| `pageSize` | number | 否 | 默认 10，范围 1-100 |

| 响应 records 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `title` | string | 标题 |
| `courseName` | string | 课程名称 |
| `downloadCount` | number | 下载量 |
| `favoriteCount` | number | 收藏量 |
| `createdAt` | string | 资料创建时间 |
| `favoriteAt` | string | 收藏时间 |

响应同时包含通用分页字段。

### 8.4 收藏状态 `getFavoriteStatus`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/favorites.ts` |
| 建议签名 | `getFavoriteStatus(resourceId: number)` |
| 请求 | `GET /resources/{resourceId}/favorite-status` |
| 使用页面 | `/resources/:id` |
| Authorization | 是 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `favorited` | boolean | 当前用户是否有有效收藏关系 |

收藏模块缺失说明：`hotScoreDelta` 固定为 0，但真实热度在事务提交后异步更新；前端不能用该字段自行计算榜单热度。

## 9. 下载接口

### 9.1 创建下载记录 `createDownloadRecord`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/downloads.ts` |
| 建议签名 | `createDownloadRecord(resourceId: number)` |
| 请求 | `POST /resources/{resourceId}/download-records`，无 body |
| 使用页面 | `/resources/:id` |
| Authorization | 是 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `resourceId` | path | number | 是 | 资料 ID |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `downloadRecordId` | number | 下载记录 ID |
| `resourceId` | number | 资料 ID |
| `fileId` | number | 文件 ID |
| `downloadUrl` | string | 文件流相对地址 |
| `expireSeconds` | null | 首版未实现过期机制，始终为 null |
| `counted` | boolean | 本次是否计入下载量 |

### 9.2 下载文件流 `downloadFile`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/downloads.ts` |
| 建议签名 | `downloadFile(downloadRecordId: number)` |
| 请求 | `GET /download-records/{downloadRecordId}/file`，响应类型设为 Blob/ArrayBuffer |
| 使用页面 | `/resources/:id`、`/me/downloads` |
| Authorization | 是 |

| 请求字段 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `downloadRecordId` | path | number | 是 | 下载记录 ID |

| 成功响应字段/响应头 | 类型 | 说明 |
| --- | --- | --- |
| 响应体 | binary | 文件二进制流 |
| `Content-Type` | string | 文件 MIME；缺失时为 `application/octet-stream` |
| `Content-Disposition` | string | 附件文件名，支持 RFC 5987 中文编码 |
| `Content-Length` | number | 文件字节数 |

失败时返回通用 JSON 错误结构，而不是二进制文件。前端请求层需要根据 HTTP 状态和 Content-Type 区分文件流与 JSON 错误。

缺失说明：文档没有说明所有错误对应的 HTTP 状态码，也没有给出二进制请求库的错误体解析约定；前端只能按实际 `Content-Type` 兼容处理。

### 9.3 我的下载记录 `getMyDownloadRecords`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/users.ts` |
| 建议签名 | `getMyDownloadRecords(params: PageQuery)` |
| 请求 | `GET /users/me/download-records`，query 参数 |
| 使用页面 | `/me/downloads` |
| Authorization | 是 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `pageNo` | number | 否 | 默认 1 |
| `pageSize` | number | 否 | 默认 10，最大 100 |

| 响应 records 字段 | 类型 | 说明 |
| --- | --- | --- |
| `downloadRecordId` | number | 下载记录 ID |
| `resourceId` | number | 资料 ID |
| `title` | string | 资料标题 |
| `fileId` | number | 文件 ID |
| `downloadStatus` | number | `1` 成功、`2` 失败 |
| `createdAt` | string | 下载记录时间 |

响应同时包含通用分页字段。接口不返回原文件名、文件大小、IP 或 User-Agent，前端不得增加这些响应字段。

## 10. 排行榜接口

### 10.1 热门资料 `getHotResources`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/rankings.ts` |
| 建议签名 | `getHotResources(params?: HotResourceQuery)` |
| 请求 | `GET /rankings/resources/hot`，query 参数 |
| 使用页面 | `/`、`/admin/rankings` |
| Authorization | 否 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | number | 否 | 默认 10，最大 50 |
| `categoryId` | number | 否 | 分类 ID |
| `period` | string | 否 | `daily/weekly/monthly/all` |

| 响应 data 数组字段 | 类型 | 说明 |
| --- | --- | --- |
| `rank` | number | 名次 |
| `resourceId` | number | 资料 ID |
| `title` | string | 标题 |
| `courseName` | string | 课程名称 |
| `downloadCount` | number | 下载量 |
| `favoriteCount` | number | 收藏量 |
| `hotScore` | number | 热度分 |

缺失说明：文档的 GET 请求示例使用 JSON 对象，没有给出 query URL；前端应将这些字段放入 query。错误码说明只点名 `limit` 和 `period`，没有明确 `categoryId` 的校验规则。

### 10.2 热门搜索词 `getHotSearchKeywords`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/rankings.ts` |
| 建议签名 | `getHotSearchKeywords(params?: HotKeywordQuery)` |
| 请求 | `GET /rankings/search-keywords/hot`，query 参数 |
| 使用页面 | `/`、`/admin/rankings` |
| Authorization | 否 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | number | 否 | 默认 10，最大 50 |
| `period` | string | 否 | `daily/weekly/monthly` |

| 响应 data 数组字段 | 类型 | 说明 |
| --- | --- | --- |
| `rank` | number | 名次 |
| `keyword` | string | 搜索词 |
| `searchCount` | number | 搜索次数 |

缺失说明：文档的 GET 请求示例使用 JSON 对象；前端应使用 query。Redis 缺失或异常时返回空数组，这不是请求失败。

### 10.3 重建热门资料总榜 `rebuildHotResourceRanking`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/admin/rankings.ts` |
| 建议签名 | `rebuildHotResourceRanking()` |
| 请求 | `POST /admin/rankings/resources/hot/rebuild`，无业务参数 |
| 使用页面 | `/admin/rankings` |
| Authorization | 是，管理员 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 无 path、query 或业务 body 参数 |

| 响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| `data` | null | 重建成功，无额外业务数据 |

缺失说明：响应不返回重建条数、耗时、任务 ID或进度。该接口是同步等待语义，前端只能显示请求中和成功/失败，不能展示虚构进度。

## 11. 搜索接口

### 11.1 搜索资料 `searchResources`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/search.ts` |
| 建议签名 | `searchResources(params: SearchResourceQuery)` |
| 请求 | `GET /search/resources`，query 参数 |
| 使用页面 | `/search`、`/admin/resources` |
| Authorization | 否 |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 否 | 搜索关键词 |
| `categoryId` | number | 否 | 分类 ID |
| `courseName` | string | 否 | 课程名称 |
| `resourceType` | number | 否 | 资料类型 |
| `tag` | string | 否 | 标签 |
| `sortBy` | string | 否 | `createdAt/downloadCount/favoriteCount/hotScore` |
| `order` | string | 否 | `asc/desc`，默认 desc |
| `pageNo` | number | 否 | 页码；默认值未在该小节明确 |
| `pageSize` | number | 否 | 每页数量；默认值和最大值未在该小节明确 |

| 响应 records 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceId` | number | 资料 ID |
| `title` | string | 标题 |
| `description` | string/null | 简介 |
| `courseName` | string | 课程名称 |
| `resourceType` | number | 资料类型 |
| `tags` | string[] | 标签 |
| `downloadCount` | number | 下载量 |
| `favoriteCount` | number | 收藏量 |
| `hotScore` | number | 热度分 |
| `createdAt` | string | 创建时间 |

响应同时包含通用分页字段。

缺失说明：文档的 GET 请求示例使用 JSON 对象，应转换为 query；关键词、课程名、标签和分页的具体数值上限未列出。`42901` 是设计预留，搜索限流尚未实现。

### 11.2 搜索建议 `getSearchSuggestions`

| 项目 | 内容 |
| --- | --- |
| API 文件 | `src/api/search.ts` |
| 建议签名 | `getSearchSuggestions(params: SearchSuggestionQuery)` |
| 请求 | 规划为 `GET /search/suggestions`，query 参数 |
| 使用页面 | 首版不调用；未来可能用于 `/search` |
| Authorization | 否 |
| 实现状态 | **后端尚未实现，前端只保留设计，不导出到实际页面调用链** |

| 请求字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `prefix` | string | 是 | 搜索前缀 |
| `limit` | number | 否 | 默认 10；最大值未说明 |

| 规划响应 data 字段 | 类型 | 说明 |
| --- | --- | --- |
| 数组元素 | string | 搜索建议文本 |

缺失说明：该接口尚未实现；建议来源只写“可从 Redis 或 MySQL，后续可切换 Elasticsearch”，没有确定真实来源、排序规则、去重规则、最大 `limit`、限流规则或上线时间。响应示例仅为设计示例，不能作为真实联调结果。

## 12. API 文档缺失与歧义汇总

| 序号 | 接口/范围 | 缺失或歧义 | 前端处理 |
| --- | --- | --- | --- |
| 1 | 用户相关响应 | `status` 数值含义没有枚举定义 | 只展示已知值并为未知值提供兜底文本 |
| 2 | 注册 | 请求有 `phone`，响应无 `phone` | 不从注册响应读取手机号 |
| 3 | 登录 | `42901` 已列出但登录限流未实现 | 不展示“已启用登录限流” |
| 4 | 退出 | 空对象与无 body 未统一说明 | 可按示例发送 `{}`，不增加字段 |
| 5 | 资料详情 | `favorited` 仍描述为预留，但收藏模块已实现 | 登录用户调用独立收藏状态接口 |
| 6 | 我的上传 | `pageSize` 最大值未说明 | 仅使用保守分页值，不声称具体上限 |
| 7 | 创建资料 | 多个字符串和标签精确长度未列出 | 前端只做基础校验，错误以后端为准 |
| 8 | 分类 | 分类层级和整树查询能力未说明 | 按 `parentId` 分次查询 |
| 9 | MD5 预检 | 未命中时 `fileId` 的 null/缺省形式未说明 | 同时兼容 null 和 undefined |
| 10 | 文件上传 | 文件大小上限和类型白名单具体值未列出 | 不硬编码未经确认的白名单和上限 |
| 11 | 待审核列表 | 不返回分类名、上传者名、文件详情，且无待审核详情接口 | 只展示列表已有字段 |
| 12 | 审核接口 | `actionType` 没有正式枚举表 | 仅映射示例 1/2/3，未知值兜底 |
| 13 | 下架响应 | `approvedAt` 为 null 是否代表不返回历史值未说明 | 不依赖该字段展示历史通过时间 |
| 14 | 收藏响应 | `hotScoreDelta` 固定 0，与真实异步热度变化分离 | 不用该字段计算热度 |
| 15 | 下载地址 | `expireSeconds` 始终为 null，过期机制未实现 | 不显示过期倒计时 |
| 16 | 文件流 | 错误 HTTP 状态与前端二进制错误解析未完整说明 | 根据 HTTP 状态和 Content-Type 双重判断 |
| 17 | 排行榜 GET | 请求示例误写为 JSON，没有 query URL | 全部按 query 参数发送 |
| 18 | 热门资料 | `categoryId` 校验规则未说明 | 只传有效正整数，不编造错误规则 |
| 19 | 搜索 | GET 示例写为 JSON；分页默认值/上限和字符串长度上限不完整 | 按 query 发送，基础校验后以后端错误为准 |
| 20 | 搜索限流 | `42901` 是设计预留，当前未实现 | 不展示搜索限流能力 |
| 21 | 搜索建议 | 整个接口尚未实现，多项规则未确定 | 首版不调用，仅保留方法命名设计 |
| 22 | 健康检查 | `GET /api/v1/health` 未收录在 `docs/04-api-doc.md` 的接口章节 | 本文不为其设计正式 API 映射；如需使用应先补齐 API 文档 |

## 13. 前端实现边界

| 边界 | 要求 |
| --- | --- |
| 方法命名 | 使用本文方法名，避免页面组件直接拼 URL |
| Token | 由统一请求拦截器附加；公开接口不依赖 Token |
| 参数位置 | path、query、JSON body、multipart 严格按本文映射 |
| 响应解包 | JSON 接口统一解包 `ApiResponse<T>`，文件流单独处理 |
| 类型声明 | 只能声明文档已列出的字段；可空性不清楚时采用可选或 nullable 类型 |
| 未实现接口 | `getSearchSuggestions` 不进入首版页面调用链 |
| 错误处理 | 展示后端 `message`，开发演示时可附带 `traceId` |
| 权限 | 前端只控制入口显示，后端仍是最终鉴权边界 |
| 文档缺失 | 不通过猜测补齐；联调发现真实结构后先更新 `docs/04-api-doc.md` 和本文 |
