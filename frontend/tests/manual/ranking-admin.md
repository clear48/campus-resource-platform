# T46 排行榜与管理员下架真实联调记录

## 联调前提

| 项目 | 结果 |
| --- | --- |
| 后端健康检查 | `GET http://127.0.0.1:8080/api/v1/health` 返回 HTTP 200，业务码为 `0` |
| PDF 演示资料 | 用户提供的 PDF（资料 ID `2`）保持 `APPROVED`，不作为下架对象 |
| 下架测试资料 | 使用另一条已审核测试资料（ID `1`）验证下架状态流转 |
| 测试范围 | 只调用真实接口；测试账号、密码和 Token 均不记录 |

## 已通过场景

| 场景 | 实际接口/操作 | 结果 |
| --- | --- | --- |
| 普通用户角色边界 | 普通用户调用总榜重建、下架资料接口 | 两个接口均返回 HTTP 403 |
| 资料热榜 | `GET /api/v1/rankings/resources/hot?period=all&limit=10` | T45 下载行为后，PDF 资料进入总榜 |
| 热门搜索词 | `GET /api/v1/rankings/search-keywords/hot?period=daily&limit=10` | T45 的 `UML` 搜索词进入日榜 |
| 总榜重建 | 管理员调用 `POST /api/v1/admin/rankings/resources/hot/rebuild` | 成功返回业务码 `0`；重建后榜单仍可查询 |
| 管理员下架 | 管理员调用 `POST /api/v1/admin/resources/1/offline-records` | 状态由 `1`（已审核）变为 `3`（已下架） |
| 下架公开隔离 | 查询下架资料详情、公开搜索和热门资料总榜 | 详情返回业务码 `40901`（HTTP 400）；资料不再出现在搜索和热榜 |
| 下架审计 | 管理员查询 `GET /api/v1/admin/resources/1/audit-records` | 存在 `actionType=3`、`beforeStatus=1`、`afterStatus=3` 的审计记录 |
| PDF 演示资料保护 | 查询 PDF 详情与公开搜索 | 资料 ID `2` 仍为 `APPROVED`，且可由 `UML` 搜索返回 |

## 回归命令

| 命令 | 预期 |
| --- | --- |
| `npm run test:unit` | 全量前端单元测试通过 |
| `npm run build` | 前端生产构建通过 |

本记录不保存真实账号密码、Authorization Token、数据库连接信息或其他敏感信息。
