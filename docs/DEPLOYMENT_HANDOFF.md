# 项目部署上线交接文档

> 最后更新：2026-09-21  
> 当前开发分支：`dev`  
> 编写前基线：`ac9ee7cc`  
> 用途：供后续新对话快速恢复部署上下文、确认当前进度并继续执行上线任务。

## 1. 当前目标

将“校园资料共享与检索平台”部署到公网，通过以下正式地址提供服务：

```text
https://campusshare.online
```

首版以个人项目和简历展示为目标，优先保证：

- 域名与 HTTPS 可正常访问；
- 前后端、MySQL、Redis 和上传文件形成完整可用链路；
- 数据在容器和服务器重启后不丢失；
- MySQL、Redis、后端内部端口不暴露到公网；
- 部署流程、备份恢复和回滚方式可复现。

首版不追求微服务、Kubernetes、多机高可用或大规模并发。

## 2. 当前进度

| 事项 | 状态 | 说明 |
| --- | --- | --- |
| 项目业务功能 | `DONE` | 后端认证、上传、审核、搜索、下载、收藏、排行榜已完成首版；Vue 演示前端已完成 |
| 最终根域名 | `DONE` | 已购买阿里云域名 `campusshare.online` |
| 域名实名认证 | `DONE` | 用户已确认实名认证完成 |
| 权威 DNS | `DONE` | 2026-09-21 检测为阿里云 `dns21.hichina.com`、`dns22.hichina.com` |
| 根域名 A 记录 | `TODO` | 当前未配置；必须等服务器取得固定公网 IPv4 后再添加 |
| 中国香港服务器 | `TODO` | 尚未购买，是当前用户侧的下一项主要任务 |
| Docker 部署资产 | `TODO` | 仓库当前没有 Dockerfile、Compose 或 Nginx 生产配置 |
| 生产安全配置 | `TODO` | CORS、可信代理、健康检查、生产 Secret 等仍需处理 |
| HTTPS | `TODO` | DNS 生效且 Nginx 可访问后申请和部署证书 |
| 上线验收 | `TODO` | 尚未执行公网全链路、重启持久化和备份恢复验证 |

此前讨论过的 `campusshare.click` 只是候选域名，已经作废。后续所有部署配置统一使用 `campusshare.online`。

## 3. 已确认的技术栈

### 3.1 应用技术栈

| 层次 | 当前实现 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Element Plus、Axios、Vue Router |
| 后端 | Java 17、Spring Boot 3.5.16、Spring MVC、Validation |
| 数据访问 | MyBatis 3.0.5、MySQL Connector/J |
| 数据库 | MySQL 8.x |
| Redis | Spring Data Redis、Redisson 4.6.1 |
| 认证 | JWT、BCrypt、JWT 黑名单 |
| 构建 | Maven Wrapper、npm |
| 文件存储 | 当前为服务器本地磁盘 |

### 3.2 推荐部署技术栈

| 层次 | 推荐实现 |
| --- | --- |
| 云服务器 | 阿里云中国香港 ECS 或满足配置的同类实例 |
| 操作系统 | 受支持的 Ubuntu LTS |
| 容器 | Docker、Docker Compose |
| Web 入口 | Nginx |
| HTTPS | Let's Encrypt/Certbot 或阿里云数字证书服务 |
| 持久化 | MySQL、Redis、上传文件分别使用持久卷 |
| 备份 | `mysqldump` + Redis 数据 + 上传目录联合备份 |

## 4. 推荐首版架构

```text
浏览器
  │
  │ HTTPS 443
  ▼
Nginx
  ├─ /                  → Vue 构建产物
  ├─ /admin/**          → Vue SPA 管理端路由
  └─ /api/v1/**         → Spring Boot:8080
                              ├─ MySQL:3306
                              ├─ Redis:6379
                              └─ /data/uploads 持久卷
```

推荐使用同源结构：

```text
正式站点：https://campusshare.online
后端接口：https://campusshare.online/api/v1/**
管理页面：https://campusshare.online/admin/**
www 别名：https://www.campusshare.online → 301 跳转到根域名
```

首版不创建 `api.campusshare.online` 和 `files.campusshare.online`，避免增加跨域、证书和鉴权复杂度。

## 5. 关键架构约束

### 5.1 首版只能部署一个后端实例

当前 `FileStorageServiceImpl` 把上传文件写入本地磁盘，并把规范化后的绝对路径保存到 `file_info.storage_path`。因此：

- 后端容器内的上传根路径必须固定，例如 `/data/uploads`；
- `/data/uploads` 必须挂载到服务器持久目录或 Docker Volume；
- 重新部署时不能随意修改容器内路径；
- 数据库和上传目录必须成对备份、成对恢复；
- 在改造为 MinIO、OSS 或 S3 前，不应部署多个后端副本。

### 5.2 Redis 不只是普通缓存

Redis 还承担 JWT 黑名单、下载限流、一次性下载票据、排行榜和下载增量统计。Redis 故障可能导致受保护接口不可用，未同步增量丢失还可能影响计数。因此首版也要：

- 配置密码；
- 只在 Compose 私有网络中访问；
- 启用合适的持久化；
- 挂载持久卷；
- 纳入备份和监控范围。

### 5.3 当前健康接口不等于生产就绪

`GET /api/v1/health` 当前只返回应用进程状态，不检查 MySQL 和 Redis。不能仅凭该接口判断服务具备接流量条件，部署阶段需要增加或补充依赖探测。

## 6. 服务器购买建议

当前建议购买阿里云中国香港服务器，以便尽快完成简历项目上线并减少 ICP 备案等待。建议起步配置：

```text
地域：中国香港
CPU：2 核
内存：4 GB
系统盘：至少 40 GB
系统：受支持的 Ubuntu LTS
公网：固定公网 IPv4
带宽：3～5 Mbps 起步
```

购买时重点确认：

- 实例有固定公网 IPv4；
- 云盘可扩容并支持快照；
- 中国内地到该地域的访问质量可接受；
- 续费价格和带宽计费方式可以长期承担；
- 不购买只适合临时测试、重建后 IP 会变化的资源。

如果以后改用中国内地 ECS，需要先通过阿里云备案工具核查 `.online` 后缀、实名主体与服务器是否满足备案要求，再完成 ICP 备案和后续合规步骤。

## 7. 安全组与端口

| 端口 | 用途 | 公网策略 |
| --- | --- | --- |
| `80` | HTTP 与证书校验 | 对公网开放，最终跳转 HTTPS |
| `443` | HTTPS | 对公网开放 |
| `22` | SSH | 只允许用户自己的固定公网 IP |
| `8080` | Spring Boot | 不开放公网，只允许 Nginx/容器网络访问 |
| `3306` | MySQL | 不开放公网 |
| `6379` | Redis | 不开放公网 |

不要安装后就把所有端口设置为 `0.0.0.0/0`。如果用户网络没有固定公网 IP，应使用阿里云远程连接、VPN、堡垒机或临时调整 SSH 白名单，而不是长期暴露 22 端口。

## 8. 主要任务清单

### DEPLOY-01：购买中国香港服务器

责任人：用户。

完成标准：

- 获得 ECS 公网 IPv4；
- 确认系统、CPU、内存、云盘和带宽；
- 仅按第 7 节开放安全组端口；
- 保存实例地域、系统版本和公网 IP，但不要把登录密码写入仓库。

### DEPLOY-02：补齐容器化部署资产

责任人：后续实现 Agent。

建议新增：

```text
campus-resource-platform/Dockerfile
frontend/Dockerfile
deploy/docker-compose.yml
deploy/nginx/default.conf
deploy/.env.example
deploy/README.md
```

要求：

- 后端镜像使用 Java 17 运行环境；
- 前端使用锁文件完成构建，由 Nginx 提供静态资源；
- Nginx 配置 Vue History fallback；
- `/api/v1` 反向代理到后端；
- `client_max_body_size` 不低于应用的 60 MB 请求上限；
- MySQL、Redis、上传文件使用独立持久卷；
- Compose 中不把 8080、3306、6379 映射到公网；
- 所有 Secret 只通过服务器环境变量或未提交的环境文件注入。

### DEPLOY-03：收敛生产安全配置

责任人：后续实现 Agent。

当前必须处理：

1. 将允许任意来源的 CORS 收敛为 `https://campusshare.online`，或在完全同源后取消不必要的跨域配置；
2. Nginx 必须覆盖并清洗转发 IP 请求头，后端不能信任客户端直接伪造的 `X-Forwarded-For`；
3. 为生产环境单独生成强随机 `JWT_SECRET`，不能复用本机开发密钥；
4. 创建最小权限 MySQL 应用账号，不使用 `root` 运行应用；
5. Redis 设置强密码并保持私网访问；
6. 增加 MySQL、Redis 和上传目录可用性的 readiness 或等效部署探针；
7. 配置日志轮转、容器重启策略和优雅停机；
8. 对上传文件类型、安全扫描能力和磁盘配额设置明确边界。

### DEPLOY-04：本地构建、测试和迁移演练

后端验证：

```powershell
Set-Location campus-resource-platform
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
```

前端验证：

```powershell
Set-Location frontend
npm ci
npm run test:unit
npm run build
```

容器验证至少覆盖：

- Compose 能从空数据卷启动；
- Nginx 能加载首页并刷新任意 Vue 路由；
- `/api/v1/health` 能通过 Nginx 访问；
- MySQL 和 Redis 不通过宿主机公网端口访问；
- 上传文件写入持久卷；
- Compose 重启后数据库、Redis 和文件仍然存在。

数据库处理：

- 新库执行 `sql/init.sql`；
- 旧库不能只重复执行初始化脚本，需要按顺序核对 `sql/migrations/`；
- 执行资料有效状态迁移前检查存量重复资料；
- 切换下载增量协议前检查旧 Redis `crp:stats:resource:download:syncing:active`；
- 所有迁移先在数据库副本演练并准备回滚。

### DEPLOY-05：初始化服务器并部署

服务器侧主要步骤：

1. 更新系统并创建非 root 运维用户；
2. 配置 SSH 密钥登录和最小权限防火墙；
3. 安装 Docker 与 Compose；
4. 创建稳定的数据目录和最小权限；
5. 上传部署资产或从 Git 拉取已验证提交；
6. 在服务器本地创建生产环境变量文件；
7. 启动 Compose；
8. 先通过公网 IP 和 Host 头验证 Nginx，再配置 DNS。

推荐的数据目录示例：

```text
/srv/campusshare/mysql
/srv/campusshare/redis
/srv/campusshare/uploads
/srv/campusshare/backups
```

### DEPLOY-06：配置 DNS

取得固定公网 IPv4 后，在阿里云云解析 DNS 中添加：

| 类型 | 主机记录 | 记录值 |
| --- | --- | --- |
| `A` | `@` | ECS 公网 IPv4 |
| `CNAME` | `www` | `campusshare.online` |

不要把主机记录填写为完整域名，否则可能形成重复后缀。DNS 生效后验证：

```powershell
Resolve-DnsName campusshare.online -Type A
Resolve-DnsName www.campusshare.online -Type CNAME
```

### DEPLOY-07：配置 HTTPS

要求：

- 证书覆盖 `campusshare.online`；
- 如果保留 `www`，证书也覆盖 `www.campusshare.online`；
- Nginx 监听 443；
- 80 端口统一 301 跳转到 HTTPS；
- 配置证书自动续期并验证续期流程；
- 不把证书私钥提交到 Git。

### DEPLOY-08：公网验收与备份恢复

必须验收：

1. 首页、登录、注册和 Vue 路由刷新；
2. 普通用户上传文件并创建待审核资料；
3. 管理员读取文件并审核通过；
4. 游客搜索和查看已审核资料；
5. 用户收藏、申请下载票据并下载文件；
6. 下载限流、排行榜和定时任务；
7. 普通用户不能调用管理员接口；
8. 接近 50 MB 的上传能通过 Nginx 与后端限制；
9. 伪造转发 IP 头不能绕过限流；
10. 重启服务器和容器后 MySQL、Redis、上传文件不丢失；
11. 数据库与上传目录联合备份可以恢复到独立测试环境；
12. 回滚到上一镜像时数据库和文件仍兼容。

## 9. 生产环境变量清单

以下只记录变量名，不记录真实值：

```dotenv
SERVER_PORT=8080
MYSQL_URL=<容器内 MySQL JDBC 地址>
MYSQL_USERNAME=<最小权限应用账号>
MYSQL_PASSWORD=<强密码>
REDIS_HOST=<Compose 服务名>
REDIS_PORT=6379
REDIS_PASSWORD=<强密码>
REDIS_DATABASE=0
JWT_SECRET=<生产独立随机密钥，至少 32 字节>
JWT_EXPIRATION_SECONDS=7200
APP_UPLOAD_STORAGE_PATH=/data/uploads
APP_UPLOAD_MAX_FILE_SIZE=50MB
APP_UPLOAD_MAX_REQUEST_SIZE=60MB
RANK_DOWNLOAD_DELTA_SYNC_ENABLED=true
RANK_HOT_RANKING_SYNC_ENABLED=true
```

注意：本机已经设置的 Windows 用户级 `JWT_SECRET` 只用于本地开发，不能当作生产密钥复制到服务器。

## 10. 停止条件

遇到以下任一情况应停止上线并先处理问题：

- 没有固定公网 IPv4；
- 没有上传文件持久卷；
- MySQL、Redis 或 8080 暴露公网；
- 真实密码、JWT 密钥或证书私钥准备提交到 Git；
- 后端测试、前端测试或生产构建失败；
- 旧库迁移发现有效资料重复或未知 Redis 同步批次；
- CORS 或可信代理边界仍允许任意客户端伪造来源；
- 数据库和上传目录无法联合恢复；
- 域名解析、HTTPS 或证书续期验证失败。

## 11. 当前建议的下一步

当前只执行一项用户侧任务：

> 购买符合第 6 节配置的阿里云中国香港服务器，取得固定公网 IPv4，并记录实例规格和系统版本。

服务器购买完成后，新的对话应从 `DEPLOY-02` 开始：先在仓库中实现 Docker、Compose 和 Nginx 部署资产，在本地完成测试和持久化验证，再连接服务器。不要在部署资产尚未验证时直接修改域名 A 记录。

## 12. 新对话建议提示词

可以在新对话中直接发送：

```text
请先阅读根 AGENTS.md、docs/AGENTS.md、docs/DEPLOYMENT_HANDOFF.md、
docs/CURRENT_STATUS.md 和 docs/07-project-runbook.md。

继续校园资料共享与检索平台的部署上线任务。
当前已经购买并实名认证 campusshare.online，已经购买中国香港服务器，
服务器公网 IP 和密码等敏感信息不会写入仓库。

请先检查当前分支、git status、最近提交和部署交接文档，
然后只执行 DEPLOY-02：补齐 Docker、Docker Compose 和 Nginx 部署资产，
本地完成测试、构建、持久卷和端口暴露验证后再提交推送。
```

如果服务器仍未购买，则把“已经购买中国香港服务器”改为“尚未购买中国香港服务器”，先继续做不依赖公网 IP 的部署设计或本地容器化准备。

## 13. 相关文件与官方资料

项目内文档：

- [项目运行手册](07-project-runbook.md)
- [当前项目状态](CURRENT_STATUS.md)
- [分支交接记录](BRANCH_HANDOFF.md)
- [数据库变更记录](database/database-change-log.md)
- [Redis 设计](05-redis-design.md)
- [项目结构检查](11-project-structure-review.md)

关键代码和配置：

- [后端依赖](../campus-resource-platform/pom.xml)
- [应用配置](../campus-resource-platform/src/main/resources/application.yaml)
- [本地文件存储实现](../campus-resource-platform/src/main/java/com/john/campus/service/impl/FileStorageServiceImpl.java)
- [Web 与 CORS 配置](../campus-resource-platform/src/main/java/com/john/campus/config/WebMvcConfig.java)
- [下载入口和客户端 IP 解析](../campus-resource-platform/src/main/java/com/john/campus/controller/DownloadController.java)
- [前端环境变量示例](../frontend/.env.example)
- [前端 Vite 配置](../frontend/vite.config.ts)

阿里云官方资料：

- [域名实名认证](https://help.aliyun.com/zh/dws/user-guide/how-to-complete-domain-name-authentication)
- [为网站配置 A 记录](https://help.aliyun.com/zh/dns/pubz-add-website-parsing)
- [ECS 安全组](https://help.aliyun.com/zh/ecs/user-guide/start-using-security-groups)
- [个人网站 ICP 备案](https://help.aliyun.com/zh/icp-filing/basic-icp-service/getting-started/quick-start-for-icp-filing-for-personal-websites)
- [服务器部署 SSL 证书](https://help.aliyun.com/zh/ssl-certificate/server-deployment-deploy-the-ssl-certificate-to-cloud-or-on-premises-servers)

## 14. 文档维护规则

每完成一个部署任务，应更新本文件对应任务的状态、验证命令、结果、提交 ID 和下一步。不得把密码、Token、JWT 密钥、证书私钥、服务器登录凭据或个人信息写入本文档。
