# 校园资料共享与智能检索平台 RESTful API 文档

## 1. 文档说明

本文基于 `docs/01-requirements.md`、`docs/02-business-flow.md` 和 `docs/03-database-design.md` 设计 RESTful API。

接口设计重点突出以下非 CRUD 能力：

- 资料上传后进入审核状态流转，不直接发布。
- 文件上传前支持 MD5 去重检查。
- 管理员审核通过、审核拒绝、下架资料都生成审核记录。
- 普通搜索接口只能搜索审核通过的资料。
- 搜索关键词写入 Redis 热门搜索词排行榜。
- 下载接口需要登录、限流、记录下载行为，并通过 Redis 统计下载次数。
- 收藏接口需要防止重复收藏，并更新资料热度分数。
- 热门资料排行和热门搜索词优先从 Redis 获取。

## 2. 通用约定

### 2.1 基础路径

```text
/api/v1
```

### 2.2 请求头

| 请求头 | 是否必填 | 说明 |
| --- | --- | --- |
| `Authorization` | 登录接口之外按需必填 | JWT，格式：`Bearer {token}` |
| `Content-Type` | 是 | JSON 接口使用 `application/json`，上传接口使用 `multipart/form-data` |

### 2.3 统一响应结构

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "traceId": "b1c2d3e4"
}
```

失败响应：

```json
{
  "code": 40001,
  "message": "参数不合法",
  "data": null,
  "traceId": "b1c2d3e4"
}
```

### 2.4 分页响应结构

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [],
    "pageNo": 1,
    "pageSize": 10,
    "total": 100,
    "pages": 10
  },
  "traceId": "b1c2d3e4"
}
```

### 2.5 通用错误码

| 错误码 | 标识 | 说明 |
| --- | --- | --- |
| `0` | `SUCCESS` | 请求成功 |
| `40001` | `PARAM_ERROR` | 请求参数错误 |
| `40002` | `DATA_DUPLICATE` | 数据重复 |
| `40101` | `UNAUTHORIZED` | 未登录或 Token 无效 |
| `40102` | `TOKEN_BLACKLISTED` | Token 已失效 |
| `40301` | `FORBIDDEN` | 无权限访问 |
| `40401` | `RESOURCE_NOT_FOUND` | 资源不存在 |
| `40901` | `RESOURCE_STATUS_INVALID` | 资料状态不允许当前操作 |
| `40902` | `FAVORITE_DUPLICATE` | 重复收藏，接口通常按幂等成功处理 |
| `41301` | `FILE_TOO_LARGE` | 文件过大 |
| `41501` | `FILE_TYPE_NOT_ALLOWED` | 文件类型不允许 |
| `42901` | `RATE_LIMITED` | 请求过于频繁 |
| `50001` | `SERVER_ERROR` | 服务端异常 |

### 2.6 枚举约定

用户角色：

| 值 | 含义 |
| --- | --- |
| `1` | 学生 |
| `2` | 管理员 |

资料状态：

| 值 | 标识 | 含义 |
| --- | --- | --- |
| `0` | `PENDING_REVIEW` | 待审核 |
| `1` | `APPROVED` | 审核通过 |
| `2` | `REJECTED` | 审核拒绝 |
| `3` | `OFFLINE` | 已下架 |
| `4` | `DELETED` | 已删除 |

资料类型：

| 值 | 含义 |
| --- | --- |
| `1` | 课件 |
| `2` | 笔记 |
| `3` | 真题 |
| `4` | 实验报告 |
| `5` | 课程设计 |
| `99` | 其他 |

## 3. 用户认证模块

### 3.1 用户注册

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 用户注册 |
| 请求方法 | `POST` |
| URL | `/api/v1/auth/register` |
| 是否需要登录 | 否 |
| 权限要求 | 无 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 登录账号，建议使用学号 |
| `password` | string | 是 | 密码，后端加密存储 |
| `nickname` | string | 是 | 昵称或姓名 |
| `email` | string | 否 | 邮箱 |
| `phone` | string | 否 | 手机号 |

参数校验规则：

| 参数 | 规则 |
| --- | --- |
| `username` | 非空，最大 50 个字符 |
| `password` | 非空，长度 8 到 50 个字符 |
| `nickname` | 非空，最大 50 个字符 |
| `email` | 可空，必须符合邮箱格式，最大 100 个字符 |
| `phone` | 可空，必须符合中国大陆手机号格式 |

请求示例 JSON：

```json
{
  "username": "20260001",
  "password": "Passw0rd123",
  "nickname": "张三",
  "email": "zhangsan@example.com",
  "phone": "13800000000"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "userId": 10001,
    "username": "20260001",
    "nickname": "张三",
    "email": "zhangsan@example.com",
    "role": 1,
    "status": 1
  },
  "traceId": "a1000001"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | 用户名、密码或邮箱格式不合法 |
| `40002` | 用户名、邮箱或手机号已存在 |
| `50001` | 注册失败 |

### 3.2 用户登录

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 用户登录 |
| 请求方法 | `POST` |
| URL | `/api/v1/auth/login` |
| 是否需要登录 | 否 |
| 权限要求 | 无 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 登录账号 |
| `password` | string | 是 | 登录密码 |

请求示例 JSON：

```json
{
  "username": "20260001",
  "password": "Passw0rd123"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.xxx",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "userId": 10001,
      "username": "20260001",
      "nickname": "张三",
      "email": "zhangsan@example.com",
      "status": 1,
      "role": 1
    }
  },
  "traceId": "a1000002"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | 账号或密码为空 |
| `40101` | 账号或密码错误 |
| `40301` | 用户已被禁用 |
| `42901` | 登录尝试过于频繁，当前代码暂未实现 |

### 3.3 用户退出登录

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 用户退出登录 |
| 请求方法 | `POST` |
| URL | `/api/v1/auth/logout` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | Token 从请求头读取 |

请求示例 JSON：

```json
{}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": null,
  "traceId": "a1000003"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录或 Token 无效 |
| `40102` | Token 已在黑名单中 |
| `50001` | Token 黑名单写入 Redis 失败 |

### 3.4 获取当前用户信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取当前用户信息 |
| 请求方法 | `GET` |
| URL | `/api/v1/users/me` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| 无 | - | - | 无 |

请求示例 JSON：

```json
{}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "userId": 10001,
    "username": "20260001",
    "nickname": "张三",
    "email": "zhangsan@example.com",
    "role": 1,
    "status": 1
  },
  "traceId": "a1000004"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录或 Token 无效 |
| `40102` | Token 已失效 |
| `40401` | 用户不存在 |
| `40301` | 用户已被禁用 |

## 4. 资料模块

本节已按当前 `ResourceController`、`ResourceServiceImpl`、DTO/VO 和测试结果同步。资料模块首版只实现资料创建、公开详情、我的上传列表，不包含审核、搜索、下载、收藏和排行榜能力。

### 4.1 获取公开资料详情

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取公开资料详情 |
| 请求方法 | `GET` |
| URL | `/api/v1/resources/{resourceId}` |
| 是否需要登录 | 否 |
| 权限要求 | 无，仅返回 `APPROVED` 资料 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |

请求示例：

```http
GET /api/v1/resources/20001
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "title": "数据结构期末复习提纲",
    "description": "覆盖排序、树、图等重点内容",
    "categoryId": 10,
    "categoryName": "计算机基础",
    "courseName": "数据结构",
    "resourceType": 2,
    "tags": ["数据结构", "复习", "期末"],
    "status": 1,
    "downloadCount": 128,
    "favoriteCount": 35,
    "hotScore": 745.0,
    "createdAt": "2026-07-02T10:00:00",
    "favorited": null
  },
  "traceId": "r2000001"
}
```

实现说明：

- 该接口在 `WebMvcConfig` 中通过 `/api/v1/resources/*` 匿名放行。
- Service 层先按 ID 查询资料，再通过状态判断只允许 `status = 1` 的资料公开返回。
- 当前不返回下载地址，也不返回 `file_info.storage_path`、`stored_name` 等内部存储字段。
- `favorited` 字段预留给收藏模块；收藏模块未接入前返回 `null`。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40401` | 资料不存在 |
| `40001` | `resourceId` 不合法 |
| `40901` | 资料未审核通过、已下架或已删除 |

### 4.2 获取我的上传资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取我的上传资料 |
| 请求方法 | `GET` |
| URL | `/api/v1/users/me/resources` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `status` | int | 否 | 资料状态：0 待审核，1 通过，2 拒绝，3 下架，4 已删除 |
| `pageNo` | int | 否 | 页码，默认 1 |
| `pageSize` | int | 否 | 每页数量，默认 10 |

请求示例：

```http
GET /api/v1/users/me/resources?status=0&pageNo=1&pageSize=10
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [
      {
        "resourceId": 20002,
        "title": "操作系统实验报告模板",
        "courseName": "操作系统",
        "status": 0,
        "rejectReason": null,
        "offlineReason": null,
        "createdAt": "2026-07-02T10:30:00"
      }
    ],
    "pageNo": 1,
    "pageSize": 10,
    "total": 1,
    "pages": 1
  },
  "traceId": "r2000002"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40001` | 分页参数或状态参数错误 |

### 4.3 创建资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 创建资料 |
| 请求方法 | `POST` |
| URL | `/api/v1/resources` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

说明：文件上传模块当前只负责生成 `fileId`，本接口负责把已上传文件转换为业务资料记录。新资料默认进入 `PENDING_REVIEW` 状态，等待管理员审核。

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `fileId` | long | 是 | 已上传成功的文件 ID |
| `title` | string | 是 | 资料标题 |
| `description` | string | 否 | 资料简介 |
| `categoryId` | long | 是 | 分类 ID，必须是启用分类 |
| `courseName` | string | 是 | 课程名称 |
| `resourceType` | int | 是 | 资料类型：1课件 2笔记 3真题 4实验报告 5课程设计 99其他 |
| `tags` | array | 否 | 标签列表，首版可转换为逗号分隔字符串存储 |

请求示例 JSON：

```json
{
  "fileId": 30001,
  "title": "数据结构期末复习提纲",
  "description": "覆盖排序、树、图等重点内容",
  "categoryId": 10,
  "courseName": "数据结构",
  "resourceType": 2,
  "tags": ["数据结构", "复习", "期末"]
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "fileId": 30001,
    "status": 0,
    "statusName": "PENDING_REVIEW",
    "message": "资料已创建，等待管理员审核"
  },
  "traceId": "r2000004"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40001` | 资料标题、课程、分类、类型或标签参数错误 |
| `40002` | 同一用户已提交相同待审核或已通过资料 |
| `40401` | 文件不存在、文件已删除、分类不存在或分类已禁用 |

实现说明：

- 上传者来自 JWT 拦截器写入的 `UserContextHolder`，不接受前端传入 `uploaderId`。
- 创建资料前会校验 `file_info.status = 1` 和 `category.status = 1`。
- 标签在 Service 层去空白、去重、保序后以逗号分隔字符串写入 `resource.tags`。
- 当前不修改 `file_info.ref_count`，物理文件复用语义仍由文件上传模块维护。

### 4.4 获取分类列表

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取分类列表 |
| 请求方法 | `GET` |
| URL | `/api/v1/categories` |
| 是否需要登录 | 否 |
| 权限要求 | 无 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `parentId` | long | 否 | 父分类 ID，默认 `0`，查询一级分类；不能小于 `0` |

请求示例：

```http
GET /api/v1/categories?parentId=0
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "categoryId": 10,
      "parentId": 0,
      "categoryName": "计算机基础",
      "description": "计算机公共基础课程资料",
      "sortOrder": 1
    }
  ],
  "traceId": "r2000003"
}
```

实现说明：

- 当前代码仅查询启用分类，不返回禁用分类。
- MyBatis 查询条件固定为 `parent_id = #{parentId}` 和 `status = 1`。
- 排序规则固定为 `sort_order ASC, id ASC`。
- 该接口已在 `WebMvcConfig` 中排除 JWT 拦截，可在上传前未登录场景下使用。
- 响应数据使用 `CategoryVO`，不会直接返回 `Category` Entity。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | `parentId` 参数格式错误或小于 `0` |

## 5. 文件上传模块

### 5.1 文件 MD5 去重检查

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 文件 MD5 去重检查 |
| 请求方法 | `GET` |
| URL | `/api/v1/files/check` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

说明：该接口已按当前代码实现同步。前端可在上传前计算文件 MD5，并用 `fileMd5 + fileSize` 判断是否可秒传。

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `fileMd5` | string | 是 | 文件 MD5，32 位十六进制 |
| `fileSize` | long | 是 | 文件大小，单位字节，不能小于 0 |

请求示例：

```http
GET /api/v1/files/check?fileMd5=5d41402abc4b2a76b9719d911017c592&fileSize=1048576
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "secondUpload": true,
    "fileId": 30001
  },
  "traceId": "f3000001"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40001` | MD5 或文件大小不合法 |

### 5.2 上传文件

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 上传文件 |
| 请求方法 | `POST` |
| URL | `/api/v1/files` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

说明：该接口只保存物理文件并返回 `fileId`，不创建 `resource` 资料记录。资料标题、课程、分类、标签等业务信息由资料模块的 `POST /api/v1/resources` 处理。

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 上传文件，`multipart/form-data` |

请求示例：

```http
POST /api/v1/files
Content-Type: multipart/form-data

file=@数据结构复习.pdf
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileId": 30001,
    "fileMd5": "5d41402abc4b2a76b9719d911017c592",
    "originalName": "数据结构复习.pdf",
    "fileSize": 1048576,
    "fileExt": "pdf",
    "secondUpload": false
  },
  "traceId": "f3000002"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40001` | 文件为空或参数错误 |
| `41301` | 文件过大 |
| `41501` | 文件类型不允许 |
| `50001` | 文件保存失败或数据库保存失败 |

## 6. 审核模块

本节已按当前 `AuditController`、`AuditServiceImpl`、DTO/VO 和测试结果同步。审核模块首版实现管理员待审核列表、审核通过、审核拒绝、下架资料和审核记录查询；状态流转和审核记录写入由 Service 层事务保证。

### 6.1 获取待审核资料列表

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取待审核资料列表 |
| 请求方法 | `GET` |
| URL | `/api/v1/admin/resources/pending-reviews` |
| 是否需要登录 | 是 |
| 权限要求 | 管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `courseName` | string | 否 | 课程名称筛选 |
| `resourceType` | int | 否 | 资料类型 |
| `uploaderId` | long | 否 | 上传用户 ID |
| `pageNo` | int | 否 | 页码，默认 1 |
| `pageSize` | int | 否 | 每页数量，默认 10，最大 100 |

请求示例：

```http
GET /api/v1/admin/resources/pending-reviews?courseName=数据结构&resourceType=2&pageNo=1&pageSize=10
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [
      {
        "resourceId": 20001,
        "title": "数据结构期末复习提纲",
        "description": "覆盖排序、树、图等重点内容",
        "categoryId": 10,
        "courseName": "数据结构",
        "resourceType": 2,
        "tags": ["数据结构", "复习"],
        "fileId": 30001,
        "uploaderId": 10001,
        "status": 0,
        "createdAt": "2026-07-02T10:00:00"
      }
    ],
    "pageNo": 1,
    "pageSize": 10,
    "total": 1,
    "pages": 1
  },
  "traceId": "a4000001"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40301` | 非管理员 |
| `40001` | 分页、资料类型或上传者参数错误 |

### 6.2 审核通过资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 审核通过资料 |
| 请求方法 | `POST` |
| URL | `/api/v1/admin/resources/{resourceId}/audit-approvals` |
| 是否需要登录 | 是 |
| 权限要求 | 管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |
| `auditReason` | string | 否 | 审核意见，最大 500 字符 |

请求示例 JSON：

```json
{
  "auditReason": "资料内容完整，允许发布"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "actionType": 1,
    "beforeStatus": 0,
    "afterStatus": 1,
    "auditRecordId": 50001,
    "auditReason": "资料内容完整，允许发布",
    "approvedAt": "2026-07-02T11:00:00",
    "offlineAt": null
  },
  "traceId": "a4000002"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40301` | 非管理员 |
| `40001` | `resourceId` 不合法或审核意见超过 500 字符 |
| `40401` | 资料不存在 |
| `40901` | 当前状态不是待审核，禁止审核通过 |

### 6.3 审核拒绝资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 审核拒绝资料 |
| 请求方法 | `POST` |
| URL | `/api/v1/admin/resources/{resourceId}/audit-rejections` |
| 是否需要登录 | 是 |
| 权限要求 | 管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |
| `rejectReason` | string | 是 | 拒绝原因，最大 500 字符 |

请求示例 JSON：

```json
{
  "rejectReason": "文件内容与课程无关，请重新上传"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "actionType": 2,
    "beforeStatus": 0,
    "afterStatus": 2,
    "auditRecordId": 50002,
    "auditReason": "文件内容与课程无关，请重新上传",
    "approvedAt": null,
    "offlineAt": null
  },
  "traceId": "a4000003"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40301` | 非管理员 |
| `40001` | `resourceId` 不合法、拒绝原因为空或超过 500 字符 |
| `40401` | 资料不存在 |
| `40901` | 当前状态不是待审核，禁止审核拒绝 |

### 6.4 下架资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 下架资料 |
| 请求方法 | `POST` |
| URL | `/api/v1/admin/resources/{resourceId}/offline-records` |
| 是否需要登录 | 是 |
| 权限要求 | 管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |
| `offlineReason` | string | 是 | 下架原因，最大 500 字符 |

请求示例 JSON：

```json
{
  "offlineReason": "收到举报，经核实存在版权风险"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "actionType": 3,
    "beforeStatus": 1,
    "afterStatus": 3,
    "auditRecordId": 50003,
    "auditReason": "收到举报，经核实存在版权风险",
    "approvedAt": null,
    "offlineAt": "2026-07-02T12:00:00"
  },
  "traceId": "a4000004"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40301` | 非管理员 |
| `40001` | `resourceId` 不合法、下架原因为空或超过 500 字符 |
| `40401` | 资料不存在 |
| `40901` | 当前状态不是审核通过，禁止下架 |

### 6.5 获取资料审核记录

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取资料审核记录 |
| 请求方法 | `GET` |
| URL | `/api/v1/admin/resources/{resourceId}/audit-records` |
| 是否需要登录 | 是 |
| 权限要求 | 管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |

请求示例：

```http
GET /api/v1/admin/resources/20001/audit-records
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "auditRecordId": 50001,
      "resourceId": 20001,
      "auditorId": 90001,
      "actionType": 1,
      "beforeStatus": 0,
      "afterStatus": 1,
      "auditReason": "资料内容完整，允许发布",
      "createdAt": "2026-07-02T11:00:00"
    }
  ],
  "traceId": "a4000005"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40301` | 非管理员 |
| `40001` | `resourceId` 不合法 |
| `40401` | 资料不存在 |

## 7. 收藏模块

### 7.1 收藏资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 收藏资料 |
| 请求方法 | `POST` |
| URL | `/api/v1/resources/{resourceId}/favorites` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |

请求示例 JSON：

```json
{
  "resourceId": 20001
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "favorited": true,
    "duplicateIgnored": true,
    "favoriteCount": 36,
    "hotScoreDelta": 0
  },
  "traceId": "fav00001"
}
```

说明：如果用户已经收藏过该资料，接口按幂等成功处理，不重复增加收藏数和热度分。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40401` | 资料不存在 |
| `40901` | 资料未审核通过，不能收藏 |

### 7.2 取消收藏资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 取消收藏资料 |
| 请求方法 | `DELETE` |
| URL | `/api/v1/resources/{resourceId}/favorites` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |

请求示例 JSON：

```json
{
  "resourceId": 20001
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "favorited": false,
    "favoriteCount": 35,
    "hotScoreDelta": -3
  },
  "traceId": "fav00002"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40401` | 资料不存在 |

### 7.3 获取我的收藏列表

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取我的收藏列表 |
| 请求方法 | `GET` |
| URL | `/api/v1/users/me/favorites` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `pageNo` | int | 否 | 页码 |
| `pageSize` | int | 否 | 每页数量 |

请求示例 JSON：

```json
{
  "pageNo": 1,
  "pageSize": 10
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [
      {
        "resourceId": 20001,
        "title": "数据结构期末复习提纲",
        "courseName": "数据结构",
        "downloadCount": 128,
        "favoriteCount": 35,
        "createdAt": "2026-07-02 10:00:00",
        "favoriteAt": "2026-07-02 13:00:00"
      }
    ],
    "pageNo": 1,
    "pageSize": 10,
    "total": 1,
    "pages": 1
  },
  "traceId": "fav00003"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40001` | 分页参数错误 |

### 7.4 查询资料收藏状态

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 查询资料收藏状态 |
| 请求方法 | `GET` |
| URL | `/api/v1/resources/{resourceId}/favorite-status` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |

请求示例 JSON：

```json
{
  "resourceId": 20001
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "resourceId": 20001,
    "favorited": true
  },
  "traceId": "fav00004"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40401` | 资料不存在 |

## 8. 下载模块

### 8.1 创建下载记录并获取下载地址

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 创建下载记录并获取下载地址 |
| 请求方法 | `POST` |
| URL | `/api/v1/resources/{resourceId}/download-records` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `resourceId` | long | 是 | 路径参数，资料 ID |

请求示例 JSON：

```json
{
  "resourceId": 20001
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "downloadRecordId": 60001,
    "resourceId": 20001,
    "fileId": 30001,
    "downloadUrl": "/api/v1/download-records/60001/file",
    "expireSeconds": 300,
    "counted": true,
    "redisDeltaKey": "stats:resource:download:delta",
    "hotScoreDelta": 5
  },
  "traceId": "down0001"
}
```

说明：

- 该接口会执行 Redis 下载限流。
- 下载成功后写入 `download_record`。
- 下载次数优先写入 Redis Hash，再由定时任务同步到 MySQL。
- 短时间重复下载同一资料时，`counted` 可返回 `false`，表示允许下载但不重复增加热度和下载量。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40401` | 资料不存在 |
| `40901` | 资料未审核通过或已下架，不能下载 |
| `42901` | 下载过于频繁 |
| `50001` | 文件不存在或下载记录创建失败 |

### 8.2 下载文件流

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 下载文件流 |
| 请求方法 | `GET` |
| URL | `/api/v1/download-records/{downloadRecordId}/file` |
| 是否需要登录 | 是 |
| 权限要求 | 下载记录所属用户或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `downloadRecordId` | long | 是 | 路径参数，下载记录 ID |

请求示例 JSON：

```json
{
  "downloadRecordId": 60001
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "contentType": "application/octet-stream",
    "fileName": "数据结构复习.pdf",
    "stream": "实际接口返回文件二进制流"
  },
  "traceId": "down0002"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40301` | 无权访问该下载记录 |
| `40401` | 下载记录或文件不存在 |
| `40901` | 下载地址已过期 |

### 8.3 获取我的下载记录

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取我的下载记录 |
| 请求方法 | `GET` |
| URL | `/api/v1/users/me/download-records` |
| 是否需要登录 | 是 |
| 权限要求 | 学生或管理员 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `pageNo` | int | 否 | 页码 |
| `pageSize` | int | 否 | 每页数量 |

请求示例 JSON：

```json
{
  "pageNo": 1,
  "pageSize": 10
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [
      {
        "downloadRecordId": 60001,
        "resourceId": 20001,
        "title": "数据结构期末复习提纲",
        "fileId": 30001,
        "downloadStatus": 1,
        "createdAt": "2026-07-02 14:00:00"
      }
    ],
    "pageNo": 1,
    "pageSize": 10,
    "total": 1,
    "pages": 1
  },
  "traceId": "down0003"
}
```

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40101` | 未登录 |
| `40001` | 分页参数错误 |

## 9. 排行榜模块

### 9.1 获取热门资料排行榜

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取热门资料排行榜 |
| 请求方法 | `GET` |
| URL | `/api/v1/rankings/resources/hot` |
| 是否需要登录 | 否 |
| 权限要求 | 无 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | int | 否 | 返回数量，默认 10，最大 50 |
| `categoryId` | long | 否 | 分类 ID |
| `period` | string | 否 | 时间范围：`daily`、`weekly`、`monthly`、`all` |

请求示例 JSON：

```json
{
  "limit": 10,
  "categoryId": 10,
  "period": "weekly"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "rank": 1,
      "resourceId": 20001,
      "title": "数据结构期末复习提纲",
      "courseName": "数据结构",
      "downloadCount": 128,
      "favoriteCount": 35,
      "hotScore": 745.0
    }
  ],
  "traceId": "rank0001"
}
```

说明：该接口优先读取 Redis ZSet，例如 `ranking:resource:hot`，MySQL 的 `resource.hot_score` 仅作为兜底或快照。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | `limit` 或 `period` 参数错误 |
| `50001` | Redis 不可用且 MySQL 兜底查询失败 |

### 9.2 获取热门搜索词排行榜

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取热门搜索词排行榜 |
| 请求方法 | `GET` |
| URL | `/api/v1/rankings/search-keywords/hot` |
| 是否需要登录 | 否 |
| 权限要求 | 无 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | int | 否 | 返回数量，默认 10，最大 50 |
| `period` | string | 否 | 时间范围：`daily`、`weekly`、`monthly` |

请求示例 JSON：

```json
{
  "limit": 10,
  "period": "daily"
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "rank": 1,
      "keyword": "数据结构",
      "searchCount": 256
    },
    {
      "rank": 2,
      "keyword": "操作系统",
      "searchCount": 198
    }
  ],
  "traceId": "rank0002"
}
```

说明：该接口读取 Redis ZSet，例如 `ranking:search:keyword:daily`。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | 参数错误 |
| `50001` | Redis 查询失败 |

## 10. 搜索模块

> 10.1 搜索资料接口已按当前 `SearchController`、`SearchServiceImpl`、`SearchResourceQueryDTO` 和 `SearchResourceVO` 同步，接口已实现并通过全量测试。首版基于 MySQL 模糊查询，非空关键词写入 Redis 热门搜索词 ZSet；首版未实现搜索限流，`42901` 为设计预留错误码。10.2 搜索建议接口仍为后续任务，尚未实现。

### 10.1 搜索资料

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 搜索资料 |
| 请求方法 | `GET` |
| URL | `/api/v1/search/resources` |
| 是否需要登录 | 否 |
| 权限要求 | 无，强制只返回 `APPROVED` 资料 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 否 | 搜索关键词 |
| `categoryId` | long | 否 | 分类 ID |
| `courseName` | string | 否 | 课程名称 |
| `resourceType` | int | 否 | 资料类型 |
| `tag` | string | 否 | 标签 |
| `sortBy` | string | 否 | 排序：`createdAt`、`downloadCount`、`favoriteCount`、`hotScore` |
| `order` | string | 否 | `asc` 或 `desc`，默认 `desc` |
| `pageNo` | int | 否 | 页码 |
| `pageSize` | int | 否 | 每页数量 |

请求示例 JSON：

```json
{
  "keyword": "数据结构",
  "categoryId": 10,
  "courseName": "数据结构",
  "resourceType": 2,
  "tag": "复习",
  "sortBy": "hotScore",
  "order": "desc",
  "pageNo": 1,
  "pageSize": 10
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [
      {
        "resourceId": 20001,
        "title": "数据结构期末复习提纲",
        "description": "覆盖排序、树、图等重点内容",
        "courseName": "数据结构",
        "resourceType": 2,
        "tags": ["数据结构", "复习", "期末"],
        "downloadCount": 128,
        "favoriteCount": 35,
        "hotScore": 745.0,
        "createdAt": "2026-07-02 10:00:00"
      }
    ],
    "pageNo": 1,
    "pageSize": 10,
    "total": 1,
    "pages": 1
  },
  "traceId": "search001"
}
```

说明：

- 服务端必须固定追加 `status = APPROVED` 查询条件。
- 非空关键词会写入 Redis 热门搜索词排行榜。
- 后续接入 Elasticsearch 时，该接口路径和响应结构保持不变，只替换搜索实现。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | 搜索参数或分页参数错误（关键词/课程名/标签过长、分类 ID 非正、资料类型或排序字段/方向非法、分页越界） |
| `42901` | 搜索过于频繁（设计预留，首版未实现搜索限流） |
| `50001` | 搜索服务异常（数据库查询异常等由全局异常兜底） |

### 10.2 获取搜索建议

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取搜索建议 |
| 请求方法 | `GET` |
| URL | `/api/v1/search/suggestions` |
| 是否需要登录 | 否 |
| 权限要求 | 无 |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `prefix` | string | 是 | 搜索前缀 |
| `limit` | int | 否 | 返回数量，默认 10 |

请求示例 JSON：

```json
{
  "prefix": "数据",
  "limit": 10
}
```

响应示例 JSON：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    "数据结构",
    "数据库原理",
    "数据结构期末"
  ],
  "traceId": "search002"
}
```

说明：初期可从热门搜索词 Redis ZSet 或 MySQL 课程名中生成建议，后续可切换 Elasticsearch completion suggester。

可能的错误码：

| 错误码 | 说明 |
| --- | --- |
| `40001` | 前缀为空或过长 |
| `42901` | 请求过于频繁 |

## 11. 接口设计亮点总结

| 能力 | 对应接口 | 体现点 |
| --- | --- | --- |
| 文件 MD5 去重 | `GET /api/v1/files/check` | 上传前判断文件是否已存在，支持复用 `file_info` |
| 上传后待审核 | `POST /api/v1/resources` | 创建资料后状态为 `PENDING_REVIEW` |
| 审核状态流转 | 审核模块接口 | 通过、拒绝、下架都校验状态机并写 `audit_record` |
| 非公开资料隔离 | `GET /api/v1/search/resources` | 强制只返回 `APPROVED` 资料 |
| 搜索热词 | 搜索接口、热门搜索词接口 | 搜索时写 Redis ZSet，排行榜直接读取 Redis |
| 下载限流 | `POST /api/v1/resources/{resourceId}/download-records` | 使用 Redis 限流，防止刷下载量 |
| 下载统计 | 下载模块接口 | 下载量先写 Redis Hash，再定时同步 MySQL |
| 收藏防重复 | 收藏模块接口 | `user_id + resource_id` 唯一索引，接口幂等返回 |
| 热门资料排行 | `GET /api/v1/rankings/resources/hot` | Redis ZSet 实时排行，MySQL 快照兜底 |

## 12. 后续可扩展接口

- 举报资料：`POST /api/v1/resources/{resourceId}/reports`
- 评论资料：`POST /api/v1/resources/{resourceId}/comments`
- 管理员查看下载趋势：`GET /api/v1/admin/resources/{resourceId}/download-statistics`
- 管理员查看用户上传统计：`GET /api/v1/admin/users/{userId}/upload-statistics`
- Elasticsearch 高亮搜索：保持 `GET /api/v1/search/resources` 不变，在响应中增加 `highlights`
- RocketMQ 事件查询：`GET /api/v1/admin/events/resource-events`
