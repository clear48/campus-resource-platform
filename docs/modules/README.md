# 业务模块索引

后端源码按 Controller、Service、Mapper 等技术分层组织；本目录按业务模块保存开发过程和验收记录。下表提供“业务模块 → 后端入口 → 前端演示入口”的统一定位，避免跨层查找时丢失归属。

| 业务模块 | 后端入口 | 前端演示入口 | 模块文档 |
| --- | --- | --- | --- |
| 认证与会话 | `AuthController`、`UserController` | `auth.ts`、登录、注册、个人信息页 | [`01-auth-development-process.md`](01-auth-development-process.md) |
| 分类查询 | `CategoryController` | `categories.ts`、搜索与上传页分类选择 | [`02-category-development-process.md`](02-category-development-process.md) |
| 文件上传 | `FileController` | `files.ts`、上传页 MD5 预检与上传 | [`03-file-upload-development-process.md`](03-file-upload-development-process.md) |
| 资料管理 | `ResourceController` | `resources.ts`、资料详情、上传记录页 | [`04-resource-development-process.md`](04-resource-development-process.md) |
| 审核与下架 | `AuditController` | `api/admin/resources.ts`、管理员审核与已发布资料页 | [`05-audit-development-process.md`](05-audit-development-process.md) |
| 公开搜索 | `SearchController` | `search.ts`、搜索页 | [`06-search-development-process.md`](06-search-development-process.md) |
| 下载与限流 | `DownloadController` | `downloads.ts`、资料详情与下载记录页 | [`07-download-development-process.md`](07-download-development-process.md) |
| 收藏 | `FavoriteController` | `favorites.ts`、资料详情与我的收藏页 | [`08-favorite-development-process.md`](08-favorite-development-process.md) |
| 排行榜与定时维护 | `RankingController`、`AdminRankingController`、`task/` | `rankings.ts`、首页与管理员排行榜页 | [`09-rank-development-process.md`](09-rank-development-process.md) |

新增功能时，先归属到现有模块；只有确实独立的业务边界才新增新的模块开发过程文档。跨模块的 MySQL、Redis、接口契约必须分别同步到 [`../database/`](../database/)、[`../05-redis-design.md`](../05-redis-design.md) 和 [`../api/api-reference.md`](../api/api-reference.md)。
