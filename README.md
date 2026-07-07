# 校园资料共享与智能检索平台

这是一个面向 Java 后端实习项目的校园资料共享与智能检索平台后端工程，配套需求、流程、数据库、接口、Redis 和模块开发文档放在 `docs/` 目录下。

## 项目结构

```text
campus-resource-platform/
  docs/                         需求、流程、数据库、接口、Redis 设计文档
  sql/init.sql                  MySQL 8.x 初始化脚本
  campus-resource-platform/     Spring Boot 后端工程
```

后端基础分层：

```text
controller     接口层
service        业务接口
service.impl   业务实现
mapper         MyBatis Mapper
entity         数据库实体
dto            请求参数对象
vo             响应视图对象
common         统一响应、错误码、分页结果
config         Web、MyBatis 等配置
exception      业务异常、全局异常处理
interceptor    JWT 拦截器
```

## 环境要求

- JDK 17+
- MySQL 8.x
- Redis 6+
- Maven Wrapper，项目已自带 `mvnw.cmd`

## 初始化数据库

在仓库根目录执行：

```powershell
mysql -u root -p < sql\init.sql
```

## 启动后端

进入后端工程目录：

```powershell
cd campus-resource-platform
```

设置本地环境变量：

```powershell
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的MySQL密码"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:REDIS_PASSWORD=""
$env:JWT_SECRET="campus-resource-platform-dev-secret-change-me"
```

启动项目：

```powershell
.\mvnw.cmd spring-boot:run
```

默认端口为 `8080`，启动后可访问：

```text
GET http://localhost:8080/api/v1/health
```

## 编译验证

```powershell
cd campus-resource-platform
.\mvnw.cmd -DskipTests package
```

## 测试验证

```powershell
cd campus-resource-platform
.\mvnw.cmd test
```

当前测试覆盖资料模块 Controller 层、资料数据库读写集成链路和 Spring Boot 上下文加载。
审核模块数据库集成测试使用本机 MySQL 独立测试库，运行前需要保证 `MYSQL_PASSWORD` 或 `MYSQL_TEST_PASSWORD` 环境变量可用。

## 当前完成内容

- Spring Boot 基础依赖：Web、Validation、MyBatis、MySQL、Redis、JWT 基础库。
- 标准分层包结构。
- 统一响应 `ApiResponse`。
- 统一错误码 `ErrorCode`。
- 全局异常处理 `GlobalExceptionHandler`。
- 业务异常 `BusinessException`。
- MyBatis Mapper 扫描配置。
- Web CORS 配置和 JWT 鉴权拦截器。
- 健康检查接口 `/api/v1/health`。
- 用户认证模块：注册、登录、退出登录、当前用户查询、BCrypt 密码加密、JWT 签发与解析、Redis Token 黑名单。
- 分类查询模块：公开查询启用分类列表 `/api/v1/categories`。
- 文件上传模块：`POST /api/v1/files` 上传文件、`GET /api/v1/files/check` 做 MD5 预检，支持 `file_md5 + file_size` 去重、秒传、本地存储、`file_info` 入库和 Redis MD5 缓存。
- 资料模块首版：`POST /api/v1/resources` 创建待审核资料、`GET /api/v1/resources/{resourceId}` 查询公开资料详情、`GET /api/v1/users/me/resources` 查询我的上传资料列表，支持文件/分类校验、重复提交拦截、分页和状态筛选。
- 审核模块首版：`GET /api/v1/admin/resources/pending-reviews` 查询待审核资料，`POST /api/v1/admin/resources/{resourceId}/audit-approvals` 审核通过，`POST /api/v1/admin/resources/{resourceId}/audit-rejections` 审核拒绝，`POST /api/v1/admin/resources/{resourceId}/offline-records` 下架资料，`GET /api/v1/admin/resources/{resourceId}/audit-records` 查询审核记录。
- 审核状态机：支持 `PENDING_REVIEW -> APPROVED`、`PENDING_REVIEW -> REJECTED`、`APPROVED -> OFFLINE`，使用事务保证 `resource` 状态更新和 `audit_record` 审核记录一致。
- 审核权限：所有审核接口需要登录，Service 层统一校验管理员角色 `role = 2`，普通用户返回 `40301`。
- 自动化测试：已补充 `ResourceControllerTest`、`ResourceDatabaseIntegrationTest`、`AuditControllerTest`、`AuditServiceDatabaseIntegrationTest`，验证接口层、鉴权路径、真实 MyBatis SQL、数据库状态流转和事务回滚。

当前下一阶段建议开发“搜索模块”：消费审核通过的 `resource.status = 1` 资料，实现公开资料检索、筛选、分页排序和搜索热词统计。审核模块记录见 `docs/modules/04-audit-development-process.md`，资料模块记录见 `docs/modules/03-resource-development-process.md`，总体进度见 `docs/06-project-progress.md`。
