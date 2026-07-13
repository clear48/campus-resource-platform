# T44 认证、上传与审核真实联调记录

## 联调环境

| 项目 | 结果 |
| --- | --- |
| 后端健康检查 | `GET http://127.0.0.1:8080/api/v1/health` 返回 HTTP 200 |
| MySQL | Spring Boot 启动后成功建立连接 |
| Redis | Spring Boot 已连接本机虚拟机 Redis |
| 上传目录 | 默认改为 `campus-resource-platform/data/user-uploads/`，运行时文件不纳入 Git |
| 演示数据 | 经用户授权初始化一条启用根分类和一个管理员账号；密码和 Token 未记录 |

## 已通过场景

| 场景 | 实际接口/操作 | 结果 |
| --- | --- | --- |
| 注册 | `POST /api/v1/auth/register`，使用本次唯一联调用户 | 通过 |
| 登录与会话 | `POST /api/v1/auth/login`、`GET /api/v1/users/me` | 通过，当前用户与注册用户一致 |
| MD5 预检 | `GET /api/v1/files/check` | 首次返回未命中 |
| 首次上传 | `POST /api/v1/files` 上传仓库内允许类型的 Markdown 文件 | 通过，`secondUpload=false` |
| 上传后预检 | 再次调用 MD5 预检 | 命中同一 `fileId` |
| 同文件秒传 | 再次调用 `POST /api/v1/files` | 通过，`secondUpload=true` |
| 普通用户权限边界 | 普通用户访问管理员待审核列表 | HTTP 403，符合后端最终鉴权边界 |
| 退出黑名单 | `POST /api/v1/auth/logout` 后使用旧 Token 调用当前用户接口 | HTTP 401，旧 Token 已失效 |
| 待审核资料创建 | 使用已上传文件调用 `POST /api/v1/resources` | 返回 `PENDING_REVIEW`（状态 0），我的上传列表可查询 |
| 管理员审核 | 管理员查询待审核列表、调用审核通过并查询流水 | 状态从 0 变为 1，审核记录可追溯 |
| 公开详情 | 游客在审核前和审核后查询资料详情 | 审核前不可公开，审核通过后返回 `APPROVED`（状态 1） |
| PDF 上传 | 上传用户提供的 `UML七类图画法_结合手写笔记.pdf` | 首次上传成功，`file_info.storage_path` 位于 `data/user-uploads` |
| PDF 演示资料 | 创建 PDF 资料并由管理员审核通过 | 公开详情状态为 1；UTF-8 字节与 Node JSON 解析验证中文标题正确 |
| 既有联调文件 | 切换默认上传目录前迁移既有 Markdown 测试文件及其 `file_info.storage_path` | 已审核资料的存储路径未失效 |

## 自动化回归

| 命令 | 结果 |
| --- | --- | --- |
| `npm run test:unit` | 31 个文件、65 个用例通过 |
| `npm run build` | 通过；仅保留既有 bundle 体积提示 |
| `mvnw.cmd -DskipTests compile` | 通过 |

本记录不保存联调账号密码、Authorization Token 或其他敏感信息。
