# 校园资料共享与智能检索平台

这是一个面向 Java 后端实习项目的 Spring Boot 后端骨架，配套文档已经放在 `docs/` 目录下。

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
interceptor    JWT 拦截器骨架
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

## 当前完成内容

- Spring Boot 基础依赖：Web、Validation、MyBatis、MySQL、Redis、JWT 基础库。
- 标准分层包结构。
- 统一响应 `ApiResponse`。
- 统一错误码 `ErrorCode`。
- 全局异常处理 `GlobalExceptionHandler`。
- 业务异常 `BusinessException`。
- MyBatis Mapper 扫描配置。
- Web CORS 配置和 JWT 拦截器骨架。
- 健康检查接口 `/api/v1/health`。

当前阶段只搭建项目骨架和公共能力，业务接口会在后续按 `docs/04-api-doc.md` 逐步实现。
