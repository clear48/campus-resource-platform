# T45 搜索、收藏与下载真实联调记录

## 联调前提

| 项目 | 结果 |
| --- | --- |
| 后端健康检查 | `GET http://127.0.0.1:8080/api/v1/health` 返回 HTTP 200，业务码为 `0` |
| 演示资料 | 用户提供的 `UML七类图画法_结合手写笔记.pdf` 已审核通过，资料 ID 为 `2` |
| 测试范围 | 只调用真实后端接口，不使用 Mock；测试账号、密码和 Token 均不记录 |

## 已通过场景

| 场景 | 实际接口/操作 | 结果 |
| --- | --- | --- |
| 公开搜索已审核资料 | `GET /api/v1/search/resources?keyword=UML` | 返回已审核通过的 PDF 资料（ID `2`） |
| 待审核资料隔离 | 创建状态为 `PENDING_REVIEW` 的测试资料（ID `3`），再以完整标题调用公开搜索 | 搜索结果为空，未审核资料没有泄露 |
| 收藏资料 | `POST /api/v1/resources/2/favorites`、收藏状态与我的收藏列表查询 | 收藏状态为 `true`，列表包含该 PDF |
| 取消收藏 | `DELETE /api/v1/resources/2/favorites`、再次查询状态和列表 | 收藏状态为 `false`，列表不再包含该 PDF |
| 创建下载记录 | `POST /api/v1/resources/2/download-records` | 首次返回 `counted=true` 和文件流地址 |
| 下载文件流 | 按创建记录接口返回的地址请求 `GET /api/v1/download-records/{id}/file` | HTTP 200；下载文件 MD5 与桌面源 PDF 一致 |
| 重复下载去重 | 同一用户再次创建下载记录 | 返回 `counted=false`，但下载记录仍可查询 |
| 下载限流 | 同一用户连续请求创建下载记录 | 在用户维度阈值后返回 HTTP 429 |

## 回归命令

| 命令 | 预期 |
| --- | --- |
| `npm run test:unit` | 全量前端单元测试通过 |
| `npm run build` | 前端生产构建通过 |

本记录不保存真实账号密码、Authorization Token、文件绝对路径或其他敏感信息。
