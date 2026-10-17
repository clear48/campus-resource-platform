# 校园资料共享与检索平台

**面向 Java 后端实习学习与面试展示的全栈项目：从文件上传、管理员审核，到资料检索、下载收藏和热度排行。**

本项目是由仓库作者 [clear48](https://github.com/clear48) **独立设计与开发的个人项目**。作者负责需求分析、数据库设计、Java 后端实现、配套 Vue 前端、测试验证及项目文档，作为个人学习实践与 Java 后端实习求职的作品展示。

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-6%2B-DC382D?logo=redis&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)

[快速启动](#快速启动) · [部署上线](#部署上线) · [部署运行手册](deploy/README.md) · [后端亮点](#值得阅读的后端实现) · [面试复盘](docs/interview/README.md) · [接口文档](docs/api/api-reference.md) · [项目结构](#项目结构) · [已知边界](#当前边界与后续方向)

正式站点：[https://campusshare.online](https://campusshare.online)。`dev`、`codex/deploy-02` 和 `deploy` 的现有提交已汇总到 [`main` 分支](https://github.com/clear48/campus-resource-platform/tree/main)，仓库仅保留 `main` 主干。服务器、DNS 与 HTTPS 的历史验收已完成，公网完整业务和生产备份恢复仍处于 DEPLOY-08 阶段，详见[部署交接](docs/DEPLOYMENT_HANDOFF.md)。

课程课件、复习笔记和实验资料容易散落在群聊与个人网盘中。本项目围绕「上传 → 审核 → 公开检索 → 下载 / 收藏」建立完整业务闭环，用具体业务展示 **Spring Boot、MyBatis、MySQL、Redis、JWT、事务与并发控制** 的使用方式，并提供 Vue 演示界面和按模块整理的开发文档。

适合正在准备 Java 实习、希望练习前后端联调，或需要梳理 Redis 与数据库一致性问题的同学。当前以单体后端和本地文件存储为基础，检索采用 **MySQL 多字段模糊查询、条件筛选与排序**，尚未接入 AI、向量检索或 Elasticsearch。

如果源码或面试复盘对你有帮助，欢迎点一个 **Star** 收藏，也欢迎通过 [Issues](https://github.com/clear48/campus-resource-platform/issues) 反馈问题。

## 功能一览

| 模块 | 已实现能力 |
| --- | --- |
| 用户认证 | 注册、登录、个人信息、BCrypt 密码哈希、JWT 鉴权、退出 Token 黑名单 |
| 文件上传 | 大小与扩展名校验、MD5 + 文件大小去重、授权感知的秒传预检、本地文件存储 |
| 资料管理 | 创建待审核资料、公开详情、我的上传、有效状态下的重复提交约束 |
| 管理员审核 | 待审核列表、待审核文件读取、通过 / 拒绝 / 下架、审核流水 |
| 资料搜索 | 关键词、分类、课程、类型、标签筛选，分页、排序白名单、热门搜索词 |
| 下载与收藏 | 用户 / IP 下载限流、一次性下载票据、下载记录、幂等收藏、我的收藏 |
| 排行榜 | 热门资料与热词、下载 / 收藏联动、下载增量同步、热度快照、管理员总榜重建 |
| 演示前端 | 登录注册、搜索、详情、上传、个人中心、管理员审核和榜单管理 |

## 值得阅读的后端实现

| 业务问题 | 当前实现 | 源码入口与可讲解的知识点 |
| --- | --- | --- |
| 相同文件重复占用存储 | `file_md5 + file_size` 唯一约束；用户文件授权；MD5 正缓存、负缓存和未命中三态 | [FileServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/FileServiceImpl.java)、[FileMd5CacheServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/FileMd5CacheServiceImpl.java)：去重不等于授权，负缓存用 `SET NX` 避免覆盖并发正值 |
| 并发提交产生重复资料 | 生成列配合唯一索引，仅约束同一用户、同一文件的待审核 / 已通过资料 | [ResourceServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceServiceImpl.java)、[SQL 迁移](sql/migrations/20260813_resource_active_duplicate_guard.sql)：前置查重与数据库最终兜底 |
| 审核状态和流水不一致 | 条件更新控制状态流转；事务内写审核记录；提交后触发缓存失效 | [AuditServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/AuditServiceImpl.java)：状态机、事务回滚、并发审核 |
| 热门详情反复访问数据库 | Cache Aside、30～35 分钟随机 TTL、Redisson 互斥回填、审核失效与回填共享锁 | [ResourceDetailCacheServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/ResourceDetailCacheServiceImpl.java)：公共缓存隔离用户态、故障回源、一致性边界 |
| 下载请求过多与文件越权访问 | Redis ZSet + Lua 滑动窗口限流；60 秒一次性票据；归属、资料状态与文件路径检查 | [DownloadRateLimiterImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadRateLimiterImpl.java)、[DownloadServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadServiceImpl.java)：原子限流与两步下载 |
| 下载统计重试导致重复累加 | Redis Hash 增量、UUID 隔离批次、Redisson 看门狗锁；MySQL 批次唯一键与计数累加同事务 | [DownloadDeltaSyncServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaSyncServiceImpl.java)、[DownloadDeltaPersistenceServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/DownloadDeltaPersistenceServiceImpl.java)：幂等落库与失败重试 |
| 重复收藏与计数偏差 | 用户 / 资料唯一索引、状态条件更新、收藏与计数事务、Redis Set 状态缓存 | [FavoriteServiceImpl](campus-resource-platform/src/main/java/com/john/campus/service/impl/FavoriteServiceImpl.java)：幂等接口与缓存降级 |

这些实现有对应的单元或集成测试与设计说明。Redis 和 MySQL 之间仍有故障窗口，具体限制见[项目局限与演进路线](docs/interview/05-limitations-and-improvement-roadmap.md)，不将其表述为无条件的强一致性。

## 业务与架构

```mermaid
flowchart LR
    U[用户上传文件] --> F[MD5 去重与用户文件授权]
    F --> R[创建待审核资料]
    R --> A{管理员审核}
    A -->|通过| P[公开搜索与详情]
    A -->|拒绝| X[保留状态与审核记录]
    P --> D[限流与票据下载]
    P --> C[收藏与取消收藏]
    D --> S[Redis 增量统计与热度排行]
    C --> S
    S --> T[定时任务与幂等落库]
    T --> M[(MySQL)]
```

前端通过 `/api/v1` 调用 Spring MVC 接口；后端采用 `Controller → Service → Mapper → MySQL` 分层。Redis 承担缓存、Token 黑名单、限流、排行和临时计数；文件保存在独立的运行时目录中。更多流程图见[总体架构](docs/modules/00-overall-architecture.md)。

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5.16、Spring MVC、Validation、MyBatis 3.0.5 |
| 数据与缓存 | MySQL 8、Spring Data Redis、Redisson 4.6.1 |
| 认证与密码 | JJWT 0.12.6、BCrypt（Spring Security Crypto） |
| 前端 | Vue 3、TypeScript、Vite、Element Plus、Axios、Vue Router |
| 测试 | JUnit 5、Spring Boot Test、Mockito、MyBatis Test、H2、Vitest、Vue Test Utils |
| 构建 | Maven Wrapper、npm 锁文件 |

依赖版本以 [pom.xml](campus-resource-platform/pom.xml)、[package.json](frontend/package.json) 和 [package-lock.json](frontend/package-lock.json) 为准。

## 项目结构

```text
campus-resource-platform/               # Git 仓库根目录
├── campus-resource-platform/           # Spring Boot 子工程，Maven 命令在此执行
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/
│       ├── main/java/com/john/campus/
│       │   ├── controller/             # HTTP 入口与参数校验
│       │   ├── service/impl/           # 业务接口及实现
│       │   ├── mapper/                 # MyBatis 数据访问接口
│       │   ├── entity/                 # 数据库实体
│       │   ├── dto/                    # 请求对象
│       │   ├── vo/                     # 响应对象
│       │   ├── common/                 # 统一响应、错误码、Redis Key 等
│       │   ├── config/                 # Web、MyBatis、Redis 等配置
│       │   ├── interceptor/            # JWT 鉴权
│       │   ├── exception/              # 业务异常与全局处理
│       │   ├── enums/                  # 业务枚举
│       │   └── task/                   # 定时同步与任务监控
│       ├── main/resources/             # application.yaml 与 mapper XML
│       └── test/                       # 后端单元与集成测试
├── frontend/                           # Vue 演示前端与组件测试
├── deploy/                             # Compose、Nginx、环境变量模板、发布与恢复检查脚本
├── sql/                                # 新库初始化和存量迁移
├── scripts/                            # API 驱动的测试数据准备脚本
├── docs/                               # 需求、API、数据库、Redis、面试复盘
├── .codex/ / .claude/                   # 项目协作配置
└── README.md
```

当前结构检查结论及待完善项见[项目结构检查报告](docs/11-project-structure-review.md)。后端与仓库同名属于目录命名选择，启动时注意进入内层工程即可。

## 快速启动

### 1. 准备环境与代码

需要 **JDK 17+、MySQL 8.x、Redis 6+、Node.js 22.x（≥22.18）或 ≥24.11，以及 npm**。Node.js 版本范围依据当前锁定依赖。后端自带 Maven Wrapper，首次执行需要下载 Maven 和依赖。以下主要使用 Windows PowerShell，数据库导入单独标注为 MySQL 客户端命令。

```powershell
git clone --branch main https://github.com/clear48/campus-resource-platform.git
Set-Location campus-resource-platform
```

`main` 是已通过本地测试与构建的集成版本，也是当前唯一保留的分支。后续新功能可从 `main` 创建临时功能分支，验证后合回并删除。macOS / Linux 使用 `cd campus-resource-platform` 进入克隆后的仓库。

### 2. 初始化新数据库

先启动 MySQL 和 Redis。在**仓库根目录**打开 MySQL 客户端：

```powershell
mysql --default-character-set=utf8mb4 -u root -p
```

在 MySQL 提示符中执行：

```sql
-- 仅用于新库初始化；脚本包含建库与建表，不包含演示账号。
SOURCE sql/init.sql;
SHOW TABLES;
exit
```

已有旧数据库请先阅读[数据库变更记录](docs/database/database-change-log.md)，按版本应用 [sql/migrations](sql/migrations) 中的迁移；`CREATE TABLE IF NOT EXISTS` 不会给旧表自动补齐列和索引。下载同步协议升级还有旧批次处理要求，见[运行手册](docs/07-project-runbook.md)。

### 3. 启动后端

在仓库根目录的 PowerShell 中执行，替换本地数据库密码：

```powershell
Set-Location campus-resource-platform
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '<你的 MySQL 密码>'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''
# 随机生成仅供本地会话使用的 JWT 密钥；重启时换密钥会使旧 Token 失效。
$jwtBytes = New-Object byte[] 48
$jwtGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
$jwtGenerator.GetBytes($jwtBytes)
$jwtGenerator.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
# 上传目录与源码分离，已在 Git 忽略规则中排除。
$env:APP_UPLOAD_STORAGE_PATH = Join-Path (Get-Location) 'data/user-uploads'
.\mvnw.cmd spring-boot:run
```

`JWT_SECRET` 必须提供至少 32 个 UTF-8 字节的非默认值。数据库地址可通过 `MYSQL_URL` 覆盖，其他配置见 [application.yaml](campus-resource-platform/src/main/resources/application.yaml)。真实密码、Token 和上传文件不应提交到仓库。

另开终端确认进程可访问：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

健康接口返回 `code: 0`、`data.status: UP`，只表示应用进程可响应，不替代 MySQL / Redis 的连通性验证。Redis 可用 `redis-cli ping` 检查，预期返回 `PONG`。

<details>
<summary>macOS / Linux 后端启动示例</summary>

完成相同的 MySQL 初始化后，从仓库根目录执行：

```bash
cd campus-resource-platform
export MYSQL_USERNAME='root'
export MYSQL_PASSWORD='<你的 MySQL 密码>'
export REDIS_HOST='localhost'
export REDIS_PORT='6379'
export REDIS_PASSWORD=''
# 需要本机提供 openssl；不把生成的密钥写入 Git。
export JWT_SECRET="$(openssl rand -base64 48)"
export APP_UPLOAD_STORAGE_PATH='./data/user-uploads'
# 使用 sh 调用，避免克隆后的 Wrapper 没有可执行权限。
sh ./mvnw spring-boot:run
```

</details>

### 4. 启动前端

另开终端，从**仓库根目录**执行：

```powershell
Set-Location frontend
npm ci
Copy-Item .env.example .env.development
npm run dev
```

macOS / Linux 将 `Set-Location frontend` 换成 `cd frontend`，将 `Copy-Item` 换成 `cp`。打开 Vite 输出的地址，默认是 [http://127.0.0.1:5173](http://127.0.0.1:5173)。示例配置使用 `/api/v1`，开发代理指向 `http://127.0.0.1:8080`。

### 5. 准备演示数据并走通业务

初始化脚本只创建表结构。先通过页面注册普通用户，再注册一个专门用于本地演示的管理员候选账号。在**本地演示库**中执行以下 SQL，替换账号占位符：

```sql
USE campus_resource_platform;
-- 仅提升刚刚注册的演示账号，避免无条件更新所有用户。
UPDATE `user` SET role = 2 WHERE username = '<刚注册的管理员演示账号>';
-- 没有启用分类时无法提交资料，先准备一个一级分类。
INSERT INTO category (parent_id, category_name, description, sort_order, status)
SELECT 0, 'Java 学习资料', '用于本地演示', 1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM category WHERE parent_id = 0 AND category_name = 'Java 学习资料'
);
```

管理员账号需要**退出后重新登录**，让新 Token 携带管理员角色。建议使用两个浏览器会话演示：

1. 普通用户上传一份自己有权分享的资料，填写分类等信息后提交。
2. 管理员读取待审核文件，审核通过。
3. 普通用户搜索资料、查看详情、收藏并下载，观察记录和排行榜。
4. 管理员下架该资料，再验证公开入口的状态限制与审核流水。

批量测试数据可参考[数据准备脚本说明](docs/10-bulk-test-data-seeding.md)。更完整的启动和排障说明见[运行手册](docs/07-project-runbook.md)。

## 部署上线

以下命令均从**仓库根目录**执行。部署采用 Docker Compose 单机方案：Nginx 提供 Vue 静态页面，将同源 `/api/v1/**` 代理到 Spring Boot，后端访问私网 MySQL 和 Redis。只运行一个后端实例，上传文件保存在固定的 `/data/uploads` 持久卷中。

### 1. 服务器与部署文件

当前方案面向 Ubuntu 24.04 / Linux amd64、2 核 2 GB 内存、40 GB 磁盘的演示服务器，需要 Docker Engine、Compose 插件；HTTPS 还需要宿主机上的 Certbot、OpenSSL 及证书脚本使用的 `flock`。镜像在本地构建并验证，2 GB 服务器只加载镜像和运行容器。

```bash
# 首次部署创建固定发布目录；已部署服务器继续使用原仓库和环境文件。
sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 0755 /srv/campusshare
git clone --branch main https://github.com/clear48/campus-resource-platform.git /srv/campusshare/app
cd /srv/campusshare/app
# 正式发布前检出与发布证据对应的已验证提交，避免直接追随变化中的分支。
git checkout --detach '<已验证的配置提交SHA>'
```

服务器示例统一使用 `/srv/campusshare/app`，它也是证书脚本安装后的默认配置查找目录。自定义发布目录时，需按[续期说明](deploy/README.md#93-自动续期与回滚)在 Certbot wrapper 和手工发布命令显式设置 `CAMPUSSHARE_HTTPS_CONFIG`，仅在其他目录 clone 不足以保证自动续期正常。

| 文件 | 用途 |
| --- | --- |
| [deploy/docker-compose.yml](deploy/docker-compose.yml) | 四服务基础编排、内存预算、健康检查、私网和持久卷；默认只启用 HTTP |
| [deploy/.env.example](deploy/.env.example) | 复制为服务器本地 `deploy/.env`，填写 Secret、镜像标签及资源配置 |
| [deploy/docker-compose.acme.yml](deploy/docker-compose.acme.yml) | 首次签发证书时挂载 HTTP-01 challenge 目录 |
| [deploy/docker-compose.https.yml](deploy/docker-compose.https.yml) | 与基础编排合并，追加 443、TLS 卷和 HTTPS Nginx 配置 |
| [deploy/nginx](deploy/nginx) | HTTP / HTTPS 反代、SPA 回退、转发头和上传请求上限 |
| [后端 Dockerfile](campus-resource-platform/Dockerfile)、[前端 Dockerfile](frontend/Dockerfile) | 固定基础镜像 digest 的多阶段构建 |
| [deploy/scripts](deploy/scripts) | 本地发布门禁、证书发布、公网预检、联合备份和恢复介质预检 |

安全组与宿主机防火墙仅对外放行 80 / 443；SSH 按运维来源限制。后端 8080、MySQL 3306 和 Redis 6379 不发布到宿主机。

### 2. 生产环境变量

```bash
# 仅在首次部署且目标文件不存在时复制，已有环境继续使用原文件。
cp -n deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

编辑 `deploy/.env`，至少核对以下配置。它是 **Compose 插值文件**；后端连接地址和 `prod` Profile 由 Compose 注入，不需要把 `.env` 放进前端，也不要把生产 Secret 写入 `VITE_*`。

| 配置项 | 配置要求 / 默认值 |
| --- | --- |
| `COMPOSE_PROJECT_NAME` | 默认 `campus-resource-platform`；已部署后保持不变，避免切换到另一组命名卷 |
| `BACKEND_IMAGE`、`FRONTEND_IMAGE` | 填本地验证并交付的镜像标签；发布脚本默认使用构建提交的 12 位 SHA，模板中的 `deploy-02` 不是最新发布版本 |
| `MYSQL_ROOT_PASSWORD` | 独立随机管理员密码，仅用于数据库初始化和维护 |
| `MYSQL_APP_USERNAME`、`MYSQL_APP_PASSWORD` | 默认账号 `campus_app`，禁止 `root`；应用密码至少 16 字节且满足强度校验 |
| `REDIS_PASSWORD` | 独立强随机密码，至少 16 字节；Redis 开启 AOF 和 `noeviction` |
| `JWT_SECRET` | 独立随机密钥，至少 32 个 UTF-8 字节；更换后已有 Token 会失效 |
| `JWT_EXPIRATION_SECONDS` | 默认 `7200` 秒 |
| `HTTP_PORT`、`HTTPS_PORT` | 默认 `80` / `443`；仅基础 Compose 不会启用 HTTPS |
| `ACME_WEBROOT_DIR` | 默认 `/srv/campusshare/acme`，宿主机公开 challenge 目录 |
| `TLS_RUNTIME_VOLUME` | 默认 `campus-resource-platform_tls_runtime`，由证书脚本创建的外部卷 |
| `APP_UPLOAD_MIN_FREE_SPACE_BYTES` | 默认 `5368709120`（5 GiB），必须为正数；不足时上传拒绝写入 |
| `RANK_DOWNLOAD_DELTA_SYNC_ENABLED`、`RANK_HOT_RANKING_SYNC_ENABLED` | 默认均为 `true`；同步协议升级须遵守迁移门禁 |

四项 Secret 分别独立生成：MySQL root / 应用密码、Redis 密码可使用 `openssl rand -hex 32`，JWT 可使用 `openssl rand -base64 48`；在服务器本地受控环境生成并保存，不能复制本地开发密钥或相互复用。不要展示完整 `docker compose config`，校验时用 `config --quiet`，避免展开 Secret。

默认内存预算为后端 `768m`、MySQL `512m`、Redis `160m`、Nginx `64m`。`JAVA_TOOL_OPTIONS` 将 JVM 堆限制为 128～512 MiB，Hikari 最大连接数 8，MySQL Buffer Pool 为 256 MiB、最大连接数 30，Redis 数据上限为 96 MiB。完整变量及调整边界见[部署手册](deploy/README.md)。

### 3. 本地验证、镜像交付与 HTTP 启动

在 Windows PowerShell 7 中，对**干净的 `main` 分支候选提交**依次执行；若验证临时功能分支，四个脚本均可用 `-RequiredBranch '<实际分支名>'` 显式指定：

```powershell
# 自动创建隔离 MySQL，执行前后端测试、生产构建及双镜像构建。
pwsh -NoProfile -File .\deploy\scripts\Test-Deploy04Build.ps1
pwsh -NoProfile -File .\deploy\scripts\Test-Deploy04Compose.ps1
pwsh -NoProfile -File .\deploy\scripts\Test-Deploy04Migration.ps1
pwsh -NoProfile -File .\deploy\scripts\Test-Deploy04Rollback.ps1
```

将验证过的镜像通过 `docker image save` 导出，在服务器核对发布包 SHA-256 后 `docker image load`；交付命令和 image ID 核对见[镜像交付说明](deploy/README.md#41-镜像交付到服务器)。GitHub 保存源码、配置模板与脚本；`git push` 不会自动发布 Docker 镜像，也不会部署服务器。

服务器检出配置提交、加载前后端镜像，提前按固定 digest 拉取 Compose 指定的 MySQL / Redis 镜像，并将准确镜像标签写入 `.env` 后，首次启动执行：

```bash
sudo docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
sudo docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --no-build --pull never
sudo docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
curl --fail http://127.0.0.1/healthz
curl --fail http://127.0.0.1/api/v1/health/readiness
```

上述 curl 使用默认 80 端口，修改 `HTTP_PORT` 时要同步调整。**空 MySQL 卷**首次启动自动执行 `sql/init.sql` 和应用账号授权脚本，仅授予业务库 `SELECT/INSERT/UPDATE`；不会生成演示账号或分类。首次业务数据准备参照快速启动第 5 步，并在受控维护窗口核对目标库和账号。

**已有数据库或数据卷**不会重跑初始化。发布前按[数据库变更记录](docs/database/database-change-log.md)核对三份迁移的执行顺序、旧下载同步批次与有效资料重复组，并检查应用账号权限；先备份、在副本演练，再用管理员账号迁移。直接修改 `.env` 的 MySQL 密码不会修改已有数据库用户密码。

### 4. DNS、HTTPS 与证书续期

当前正式域名为 `campusshare.online`：根域名 `A` 指向服务器公网 IPv4，`www` 使用 `CNAME` 指向根域名。HTTPS Nginx 和证书脚本固定校验这两个域名；部署到其他域名时需同步调整配置、证书校验及公网验收脚本，没有可直接替换的 `DOMAIN` 环境变量。

首次启用 HTTPS 必须按[证书签发与续期步骤](deploy/README.md#9-deploy-07-https-与证书续期)依次完成：

1. 创建 `ACME_WEBROOT_DIR`，以基础编排 + ACME override 启动入口，验证两个域名的 challenge 路径。
2. 使用宿主机 Certbot 为根域名与 `www` 签发同一张 SAN 证书。
3. 运行 `deploy07-cert-deploy.sh`，校验证书并写入带项目标签的 TLS 外部卷。
4. 使用基础编排 + HTTPS override 启动；安装 root-owned Certbot deploy hook，验证自动续期和 Nginx 平滑 reload。

证书已签发且 TLS 卷已初始化后，正式 HTTPS 启动命令为：

```bash
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml config --quiet
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml up -d --no-build --pull never
curl --fail https://campusshare.online/healthz
curl --fail https://campusshare.online/api/v1/health/readiness
```

80 保留 HTTP-01 challenge，其余 HTTP 和 `www` HTTPS 均 301 到根域名 HTTPS；443 映射到容器非特权端口 8443，容器健康探针走 loopback 8081。续期 dry-run 启用 deploy hook 后须重新部署生产 lineage，确认运行端恢复为受信任证书。后续 `up/ps/logs/stop/down` 均保留同一 `.env`、项目名和这两个 Compose 文件，避免入口配置回退到 HTTP。

### 5. 验证、持久化与维护

| 检查 | 表示什么 |
| --- | --- |
| `/healthz` | Nginx 存活 |
| `/api/v1/health` | Spring Boot 进程存活 |
| `/api/v1/health/readiness` | MySQL、Redis、上传目录可写与磁盘水位均正常；失败为 HTTP 503 |

readiness 会短暂创建并删除目录探针；容器 `healthy` 不等于公网业务验收完成，`unhealthy` 也不会自动摘除后端或触发容器重启。入口排障使用：

```bash
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml logs --tail=100 backend frontend
```

MySQL、Redis 完整 AOF 和上传文件分别使用 `mysql_data`、`redis_data`、`uploads_data`，必须在同一停写切点联合备份恢复。生产停止或更新不得执行 `docker compose down -v`。TLS 私钥和生产 `.env` 单独受控保管，不进入 Git 或业务备份；备份密文与发布镜像归档也应保存在仓库外。

已有只读公网预检、加密联合备份和独立恢复介质预检脚本，具体参数见[DEPLOY-08 运行手册](docs/DEPLOY-08-RUNBOOK.md)。备份默认仅预检，实际备份需要显式停机参数；恢复预检不执行恢复。完整公网业务、独立恢复、上一版镜像回滚及 RPO/RTO 验证尚未完成，DEPLOY-08 保持 `IN_PROGRESS`。

## 测试与构建

后端在 `campus-resource-platform/` 子目录执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
```

审核数据库集成测试使用真实 MySQL 独立库，默认库名为 `campus_resource_platform_audit_test`，通过 `MYSQL_TEST_USERNAME`、`MYSQL_TEST_PASSWORD`、`MYSQL_TEST_URL` 覆盖连接；未设置测试用户名 / 密码时会回退到 `MYSQL_USERNAME` / `MYSQL_PASSWORD`。**测试脚本会重建测试表，不要将 `MYSQL_TEST_URL` 指向业务库。** 其他部分数据库测试使用 H2 MySQL 模式；Redis 相关测试主要使用 Mock。

前端在 `frontend/` 子目录执行：

```powershell
npm run test:unit
npm run build
```

测试覆盖接口鉴权、资料提交、审核状态流转与回滚、搜索 SQL、缓存行为、下载票据、排行榜及幂等同步等路径。实际通过数量以本次运行输出为准；历史记录见[项目进度](docs/06-project-progress.md)。

## 文档与面试阅读路线

| 你想了解 | 推荐入口 |
| --- | --- |
| 项目解决什么问题 | [需求](docs/01-requirements.md)、[业务流程](docs/02-business-flow.md) |
| 请求如何走完整个系统 | [架构流程图](docs/modules/00-overall-architecture.md)、[业务模块索引](docs/modules/README.md) |
| 如何设计数据与索引 | [数据库设计](docs/database/database-design.md)、[建表 SQL](sql/init.sql) |
| Redis 为什么这样用 | [Redis 设计](docs/05-redis-design.md)及上方源码入口 |
| 如何调试接口 | [API 参考](docs/api/api-reference.md)、[Postman 集合](docs/api/postman/campus-resource-platform.postman_collection.json) |
| 如何部署到公网 | [部署运行手册](deploy/README.md)、[上线交接](docs/DEPLOYMENT_HANDOFF.md)、[生产验收与恢复](docs/DEPLOY-08-RUNBOOK.md) |
| 如何准备项目面试 | [项目背景与价值](docs/interview/01-project-background-and-value.md)、[技术栈复盘](docs/interview/03-technology-stack-review.md)、[高频问答](docs/interview/04-interview-question-bank.md) |
| 当前进度和后续改进 | [当前状态](docs/CURRENT_STATUS.md)、[局限与演进路线](docs/interview/05-limitations-and-improvement-roadmap.md)、[文档总索引](docs/README.md) |

建议先跑通一条上传到下载的链路，再阅读对应的 Controller、Service、Mapper 与测试，最后用自己的语言解释设计取舍。面试中只描述自己理解、验证或改进过的内容，不直接套用未经验证的性能数字。

## 当前边界与后续方向

- 检索尚未实现分词、全文索引、语义检索和搜索建议。
- 文件使用本地存储，尚未接入对象存储、病毒扫描或多实例共享文件。
- 下载统计在创建记录阶段触发，不能等同于文件已完整传输到客户端。
- 周期榜使用固定周期 Key 与 TTL，尚非精确自然日 / 周 / 月切桶。
- Redis 是受保护接口的鉴权依赖；部分缓存可以降级，不代表所有功能都能脱离 Redis 运行。
- 已完成 2 GB 服务器部署及 DNS / HTTPS 历史验收；生产完整业务与备份恢复仍在 DEPLOY-08 阶段，GitHub Actions CI、公开压测和真实 Redis 故障与并发验证仍可补强。

欢迎围绕这些明确的边界提出改进建议。提交问题时请附复现步骤和脱敏日志；提交代码前请运行相关测试。当前仓库尚未添加 `LICENSE`，README 不声明特定开源许可证。
