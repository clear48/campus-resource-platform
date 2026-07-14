# 项目运行手册

> 本手册以当前仓库的实际配置为准，覆盖 Windows PowerShell 下的 MySQL、Redis、Spring Boot 后端和 Vue 前端启动方式。前端仅作为后端能力演示界面，浏览器请求必须走版本化接口前缀 `/api/v1`。

## 1. 启动顺序与目录

```text
MySQL 8.x ─┐
           ├─> Spring Boot 后端（127.0.0.1:8080，/api/v1）
Redis 6+ ──┘                          │
                                      └─> Vite 前端（127.0.0.1:5173）
                                           将 /api/v1 代理到后端
```

按 **MySQL → Redis → 后端 → 前端** 的顺序启动。

| 路径 | 作用 |
| --- | --- |
| `sql/init.sql` | MySQL 初始化脚本 |
| `sql/migrations/20260714_download_delta_sync_idempotency.sql` | 已有数据库的下载增量同步幂等化迁移 |
| `campus-resource-platform/` | Spring Boot 后端，含 `mvnw.cmd` |
| `frontend/` | Vue 3 + Vite 演示前端 |
| `docs/api/api-reference.md` | 后端接口文档 |
| `docs/05-redis-design.md` | Redis 设计与 Key 说明 |

## 2. 环境要求

| 软件 | 要求 | 自检命令 |
| --- | --- | --- |
| JDK | 17 或更高 | `java -version` |
| MySQL | 8.x | `mysql --version` |
| Redis | 6 或更高 | `redis-cli ping` |
| Node.js | 22.18 或更高 | `node -v` |
| npm | 与 Node.js 配套 | `npm -v` |

后端已自带 Maven Wrapper，Windows 运行后端时使用 `mvnw.cmd`，不要求全局 Maven。

## 3. 初始化 MySQL

### 3.1 导入表结构

`sql/init.sql` 会创建 `campus_resource_platform` 及其表结构。脚本使用 `CREATE ... IF NOT EXISTS`，重复导入不会清空已有数据，也不会恢复已修改的演示数据。

在仓库根目录打开 **cmd.exe**，执行：

```bat
mysql -u root -p < sql\init.sql
```

### 3.1 已有数据库升级下载增量同步

首次初始化仍执行 `sql/init.sql`。若数据库已在运行旧版本，应在部署新后端前执行以下安全步骤：

1. 停止旧版本后端的下载增量定时任务，避免旧、新批次协议并行执行。
2. 使用 Redis 客户端检查 `crp:stats:resource:download:syncing:active` 是否遗留字段；若存在，先人工核对该批次是否已经落库，再决定清理或补偿，**不得直接启用新版本自动重试**。
3. 确认旧批次已处理后，在仓库根目录执行：

```bat
mysql -u root -p campus_resource_platform < sql\migrations\20260714_download_delta_sync_idempotency.sql
```

迁移仅创建 `download_delta_sync_item` 表及索引，可重复执行；完成后再启动新版本后端。

命令会提示输入密码。不要把密码写入文档、Git、`.env.example` 或提交信息。

导入后可在 MySQL 客户端检查：

```sql
USE campus_resource_platform;
SHOW TABLES;
```

### 3.2 自定义数据库地址

后端默认连接本机：

```text
jdbc:mysql://localhost:3306/campus_resource_platform
```

MySQL 不在本机或端口不是 3306 时，在启动后端的 PowerShell 中设置：

```powershell
$env:MYSQL_URL = 'jdbc:mysql://<mysql-host>:3306/campus_resource_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
```

## 4. 启动 Redis

默认 Redis 为 `localhost:6379`、数据库 `0`。完整演示依赖 Redis：Token 黑名单、MD5 缓存、收藏状态、下载限流、排行榜和下载增量同步都会使用它。

### 4.1 Redis 在本机

```powershell
redis-cli -h 127.0.0.1 -p 6379 ping
```

期望输出：`PONG`。

### 4.2 Redis 在虚拟机或远程主机

先测试端口可达：

```powershell
Test-NetConnection -ComputerName <redis-host> -Port 6379
```

然后在启动后端的同一窗口设置：

```powershell
$env:REDIS_HOST = '<redis-host>'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = '<无密码时留空>'
$env:REDIS_DATABASE = '0'
```

远程 Redis 必须允许开发机访问该端口，但不应为了联调关闭保护机制或暴露到公网。

## 5. 启动 Spring Boot 后端

### 5.1 设置当前会话变量

从仓库根目录进入后端目录：

```powershell
Set-Location .\campus-resource-platform
```

以下变量只在当前 PowerShell 会话有效。请替换尖括号内容，真实密码和密钥不得提交：

```powershell
$env:SERVER_PORT = '8080'
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '<你的 MySQL 密码>'
$env:REDIS_HOST = 'localhost'                 # Redis 在虚拟机时改为实际地址
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''                       # 无密码实例保持空字符串
$env:REDIS_DATABASE = '0'
$env:JWT_SECRET = '<仅本地使用的随机长字符串>'
$env:APP_UPLOAD_STORAGE_PATH = (Join-Path (Get-Location) 'data\user-uploads')
```

可选变量：

| 变量 | 默认值 | 使用时机 |
| --- | --- | --- |
| `MYSQL_URL` | 本机 `campus_resource_platform` | 数据库不在本机 |
| `APP_UPLOAD_MAX_FILE_SIZE` | `50MB` | 调整单文件上限 |
| `APP_UPLOAD_MAX_REQUEST_SIZE` | `60MB` | 调整请求总大小 |
| `JWT_EXPIRATION_SECONDS` | `7200` | 调整本地 Token 有效期 |
| `RANK_DOWNLOAD_DELTA_SYNC_ENABLED` | `true` | 排查时临时关闭下载量同步 |
| `RANK_HOT_RANKING_SYNC_ENABLED` | `true` | 排查时临时关闭排行榜维护 |

上传目录是运行时数据。存在 `file_info` 记录时不要随意删除或移动其中的文件，否则下载演示会失效。

### 5.2 编译并运行

首次运行或修改后端后，建议先编译：

```powershell
.\mvnw.cmd -DskipTests compile
```

启动：

```powershell
.\mvnw.cmd spring-boot:run
```

保持该终端运行。默认后端地址：`http://127.0.0.1:8080`。

### 5.3 健康检查

另开 PowerShell，执行：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

关键响应应为：

```json
{ "code": 0, "message": "success" }
```

健康检查失败时，先处理后端控制台中的 MySQL、Redis、端口占用异常；不要只通过前端报错判断原因。

### 5.4 停止后端

在 `spring-boot:run` 的终端按 `Ctrl + C`。如需检查端口：

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```

## 6. 启动 Vue 前端

### 6.1 安装依赖

新开 PowerShell，从仓库根目录执行：

```powershell
Set-Location .\frontend
npm ci
```

`npm ci` 按已提交的锁文件安装，适合首次安装和可复现环境。

### 6.2 配置 Vite 代理

首次运行创建本机配置：

```powershell
Copy-Item .env.example .env.development
```

默认内容必须保持：

```dotenv
VITE_API_BASE_URL=/api/v1
VITE_API_PROXY_TARGET=http://127.0.0.1:8080
```

| 变量 | 作用 |
| --- | --- |
| `VITE_API_BASE_URL` | 浏览器请求路径，必须为 `/api/v1` |
| `VITE_API_PROXY_TARGET` | Vite 代理的后端地址 |

后端在其他主机时仅修改 `VITE_API_PROXY_TARGET`。不要把 API 基地址改为 `/api`，否则浏览器会请求不存在的未版本化接口。

`.env.development` 被 Git 忽略；`.env.example` 仅是公开示例，不能写入账号、密码、Token 或数据库连接信息。

### 6.3 启动与访问

```powershell
npm run dev
```

浏览器访问：`http://127.0.0.1:5173/`。

### 6.4 代理自检

后端和前端都运行后：

```powershell
Invoke-RestMethod http://127.0.0.1:5173/api/v1/health
```

返回 `code: 0` 即表示 Vite 到后端的代理基础链路正常。

### 6.5 停止前端

在 `npm run dev` 的终端按 `Ctrl + C`。

## 7. 完整启动示例

假设 MySQL 与 Redis 已启动，且当前在仓库根目录。

**终端 A：后端**

```powershell
Set-Location .\campus-resource-platform
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '<你的 MySQL 密码>'
$env:REDIS_HOST = '<你的 Redis 主机>'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''
$env:JWT_SECRET = '<仅本地使用的随机长字符串>'
$env:APP_UPLOAD_STORAGE_PATH = (Join-Path (Get-Location) 'data\user-uploads')
.\mvnw.cmd spring-boot:run
```

**终端 B：前端**

```powershell
Set-Location .\frontend
if (-not (Test-Path .env.development)) {
  Copy-Item .env.example .env.development
}
npm ci
npm run dev
```

**终端 C：检查**

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
Invoke-RestMethod http://127.0.0.1:5173/api/v1/health
```

## 8. 功能演示与测试

### 8.1 浏览器验收顺序

1. 打开首页，确认热门资料和热门搜索词区域能加载；
2. 在搜索页搜索已审核资料；
3. 注册普通用户并登录；
4. 在已审核公开资料详情中验证收藏和下载；
5. 使用受控测试数据中的管理员账号，验证审核、下架和总榜重建。

`sql/init.sql` 只创建结构，不预置启用分类、普通用户或管理员账号。管理员用户管理接口当前未实现；管理员演示应使用已有的受控测试数据，不能在文档中记录密码、密码哈希或直接修改生产数据。

### 8.2 后端技术点对应检查

| 页面或操作 | 后端能力 | 预期现象 |
| --- | --- | --- |
| 首页热榜和热词 | Redis ZSet、降级查询 | 返回榜单或明确空状态 |
| 公开搜索 | MySQL 筛选与排序白名单、热词统计 | 只返回审核通过资料 |
| 上传 | MD5 预检、秒传、文件元数据 | 新资料默认待审核，不立即公开 |
| 收藏 | MySQL 幂等与 Redis Set | 收藏状态与列表一致 |
| 下载 | 两步下载、限流、去重计数 | 创建下载记录后取得文件流 |
| 管理员审核 | JWT、角色校验、事务、审计记录 | 普通用户不能访问管理员操作 |
| 总榜重建 | Redisson 锁、Redis 重建、MySQL 快照 | 管理员确认后得到成功提示 |

### 8.3 测试与构建命令

```powershell
# 后端
Set-Location .\campus-resource-platform
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package

# 前端
Set-Location ..\frontend
npm run test:unit
npm run build
```

后端数据库集成测试需要可用的 MySQL 测试连接；运行前确认当前 PowerShell 已设置项目要求的密码环境变量。前端构建产物在 `frontend/dist/`，不提交 Git。

## 9. 常见问题

| 现象 | 排查与处理 |
| --- | --- |
| 后端无法连接 MySQL | 检查 MySQL 服务、`MYSQL_URL`、用户名和密码；先用 MySQL 客户端验证连接 |
| 后端无法连接 Redis | 用 `Test-NetConnection` 检查主机与 6379 端口，再检查 `REDIS_HOST`、端口和密码 |
| 8080 被占用 | `Get-NetTCPConnection -LocalPort 8080` 查找旧进程；关闭旧进程或设置 `SERVER_PORT` |
| 前端显示服务端异常 | 先请求 `http://127.0.0.1:8080/api/v1/health`；确认 `VITE_API_BASE_URL=/api/v1` |
| 5173 可打开但接口失败 | 请求 `http://127.0.0.1:5173/api/v1/health`；重启 Vite 使 `.env.development` 生效 |
| 上传后下载失败 | 检查 `APP_UPLOAD_STORAGE_PATH`；不要删除被数据库文件记录引用的运行时文件 |
| 管理员页面被拒绝 | 前端不是权限边界，必须使用后端认可的管理员账号 |
| 热榜为空 | 检查 Redis、排行榜定时任务开关和是否存在可统计的公开资料 |

## 10. 安全与清理

关闭项目只需分别在前端、后端终端按 `Ctrl + C`。不要把删除数据库、清空 Redis 或删除 `data/user-uploads` 当作常规停止步骤，它们会影响演示数据。

结束本次会话前可清除敏感环境变量：

```powershell
Remove-Item Env:MYSQL_PASSWORD, Env:REDIS_PASSWORD, Env:JWT_SECRET -ErrorAction SilentlyContinue
```

相关文档：[接口文档](api/api-reference.md)、[Redis 设计](05-redis-design.md)、[前端运行说明](../frontend/README.md)、[前端接口映射](frontend/03-api-mapping.md)。
