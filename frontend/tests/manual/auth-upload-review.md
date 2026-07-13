# T44 认证、上传与审核真实联调记录

## 联调环境

| 项目 | 结果 |
| --- | --- |
| 后端健康检查 | `GET http://127.0.0.1:8080/api/v1/health` 返回 HTTP 200 |
| MySQL | Spring Boot 启动后成功建立连接 |
| Redis | Spring Boot 已连接本机虚拟机 Redis |
| 前端业务代码 | 本次未修改；仅执行真实 HTTP 联调 |

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

## 阻塞场景

| 场景 | 实际结果 | 结论 |
| --- | --- | --- |
| 创建待审核资料 | `GET /api/v1/categories?parentId=0` 返回空数组；数据库没有启用分类 | 无法取得合法 `categoryId`，不能调用创建资料接口 |
| 管理员审核 | 数据库活动管理员账号数为 0 | 没有可登录管理员，不能验证待审核列表、审核通过和公开详情 |

## 恢复条件

需由用户授权的初始化流程准备至少一个启用分类和一个可登录管理员账号。准备完成后，继续验证：

1. 创建资料后状态为 `PENDING_REVIEW`；
2. 管理员审核通过；
3. 游客可读取审核通过后的公开资料详情；
4. 审核记录可追溯。

本记录不保存联调账号密码、Authorization Token 或其他敏感信息。
