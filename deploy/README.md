# 单机生产部署与运维说明

本目录提供校园资料共享与检索平台的单机生产编排。浏览器只访问 Nginx；Spring Boot、MySQL 和 Redis 仅在 Compose 私网中访问。正式站点采用同源 `/api/v1/**`，后端不开放跨域。

部署源码已汇总到唯一保留的 [`main` 分支](https://github.com/clear48/campus-resource-platform/tree/main)。部署顺序为：本地干净候选验证 → 导出镜像并校验交付 → 服务器配置 `.env` → HTTP 启动 → DNS / ACME 签发 → HTTPS / 续期 → 公网业务与生产恢复验收。根 [README](../README.md#部署上线) 提供配置总览，[部署交接](../docs/DEPLOYMENT_HANDOFF.md)记录阶段状态，[DEPLOY-08 运行手册](../docs/DEPLOY-08-RUNBOOK.md)说明尚未完成的生产验收。

宿主机采用 Linux amd64，当前已验证基线为 Ubuntu 24.04、2 核 2 GB、40 GB 系统盘。命令默认在仓库根目录执行，服务器示例仓库绝对路径为 `/srv/campusshare/app`；证书脚本安装到 `/usr/local/sbin` 后默认从该目录读取 `deploy/nginx/https.conf`，自定义目录必须按第 9.3 节设置 `CAMPUSSHARE_HTTPS_CONFIG`。服务器普通运维账号使用 `sudo docker`，不要求加入 `docker` 组。首次创建环境文件和运行证书脚本前，确认已检出与发布证据对应的配置提交。

## 1. 服务、端口与数据

| 组件 | 容器端口 | 宿主机发布 | 持久化 |
| --- | --- | --- | --- |
| Nginx / Vue | `8080` | `HTTP_PORT`，默认 `80` | 镜像内静态资源 |
| Nginx HTTPS override | `8443`；健康检查 `127.0.0.1:8081` | `HTTPS_PORT`，默认 `443` | 外部 `TLS_RUNTIME_VOLUME`，只读挂载 |
| Spring Boot | `8080` | 否 | `uploads_data:/data/uploads` |
| MySQL | `3306` | 否 | `mysql_data:/var/lib/mysql` |
| Redis | `6379` | 否 | `redis_data:/data` |

`file_info.storage_path` 保存容器内绝对路径，上传挂载点固定为 `/data/uploads`。本地文件存储只支持一个后端实例，MySQL、Redis 完整 AOF 和上传卷必须在同一停写切点联合备份、联合恢复。

入口和后端请求上限为 `60 MB`，单文件上限为 `50 MB`。上传还要求磁盘可用空间大于“本次文件大小 + `APP_UPLOAD_MIN_FREE_SPACE_BYTES`”；示例生产值为 5 GiB。

## 2. 准备生产环境变量

```bash
# 只在首次部署且文件不存在时复制，保留已有生产环境文件。
cp -n deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

分别生成随机值后写入 `deploy/.env`，不要把结果输出到提交或日志：

```bash
openssl rand -hex 32
openssl rand -base64 48
```

必须填写 `MYSQL_ROOT_PASSWORD`、`MYSQL_APP_USERNAME`、`MYSQL_APP_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`。应用启动时会拒绝：

- `root` 或空的 MySQL 应用账号；
- 少于 16 字节、明显常见或字符种类过少的 MySQL/Redis 密码；
- 少于 32 字节、旧默认值或缺失的 JWT Secret；
- MySQL、Redis、JWT 三类 Secret 复用；
- 非正数的上传磁盘保留水位。

真实 `deploy/.env` 已被 Git 忽略。生产 Secret 必须彼此独立，也不能复用本地开发值。

### 2.1 配置变量与映射

`deploy/.env` 用于 Compose 插值，不由 Spring Boot 自动加载。正式操作始终显式传入 `--env-file deploy/.env`，避免从错误目录读取其他环境文件；不要在 Certbot hook 中 `source` 它。

| 分类 | `.env` 变量 | 含义与约束 |
| --- | --- | --- |
| 项目身份 | `COMPOSE_PROJECT_NAME` | 默认 `campus-resource-platform`；生产部署后保持稳定，它决定数据卷和服务身份 |
| 发布镜像 | `BACKEND_IMAGE`、`FRONTEND_IMAGE` | 与本地发布证据一致的准确标签；默认模板 `deploy-02` 仅为初始标签，不代表最新候选 |
| 入口 | `HTTP_PORT=80`、`HTTPS_PORT=443` | 基础编排只发布 HTTP，HTTPS 必须加载 override |
| ACME | `ACME_WEBROOT_DIR` | 宿主机绝对目录，默认 `/srv/campusshare/acme` |
| TLS | `TLS_RUNTIME_VOLUME` | 默认 `campus-resource-platform_tls_runtime`，证书脚本创建并校验的外部卷 |
| 数据库管理员 | `MYSQL_ROOT_PASSWORD` | 仅初始化和维护；已有 MySQL 卷不会因修改此变量自动轮换密码 |
| 应用账号 | `MYSQL_APP_USERNAME`、`MYSQL_APP_PASSWORD` | Compose 映射为后端 `MYSQL_USERNAME`、`MYSQL_PASSWORD`；业务库固定为 `campus_resource_platform` |
| Redis / JWT | `REDIS_PASSWORD`、`JWT_SECRET`、`JWT_EXPIRATION_SECONDS=7200` | 生产独立 Secret、Token 有效期秒数；连接地址由 Compose 固定为 `redis:6379` |
| 上传 | `APP_UPLOAD_MIN_FREE_SPACE_BYTES=5368709120` | 5 GiB 保留水位；容器路径固定 `/data/uploads`，请求 / 单文件上限固定 60 MB / 50 MB |
| 定时同步 | `RANK_DOWNLOAD_DELTA_SYNC_ENABLED`、`RANK_HOT_RANKING_SYNC_ENABLED` | 默认 `true`；协议切换前排查旧同步批次 |
| JVM / 容器 | `JAVA_TOOL_OPTIONS`、`BACKEND_MEMORY_LIMIT`、`MYSQL_MEMORY_LIMIT`、`REDIS_MEMORY_LIMIT`、`NGINX_MEMORY_LIMIT` | 默认 JVM 堆 128～512 MiB；容器分别 `768m/512m/160m/64m` |
| 连接池 | `HIKARI_MAXIMUM_POOL_SIZE=8`、`HIKARI_MINIMUM_IDLE=2`、`HIKARI_CONNECTION_TIMEOUT_MS=3000` | 控制后端连接数与等待上限，不能无限放大 |
| 数据服务预算 | `MYSQL_INNODB_BUFFER_POOL_SIZE=256M`、`MYSQL_MAX_CONNECTIONS=30`、`REDIS_MAXMEMORY=96mb` | 须低于各自容器内存上限；Redis 禁止自动淘汰业务数据 |
| Redis 超时 | `REDIS_CONNECT_TIMEOUT=2s`、`REDIS_COMMAND_TIMEOUT=2s` | 分别映射连接与命令超时 |

`SPRING_PROFILES_ACTIVE=prod`、MySQL JDBC 地址、Redis 地址和上传路径由 Compose 注入。前端构建时已固定 `VITE_API_BASE_URL=/api/v1`；浏览器只访问同源 Nginx，不需要填写服务器 IP 或数据库地址，也不能通过 `VITE_*` 传递 Secret。

## 3. MySQL 最小权限

全新空数据卷首次启动时，MySQL 官方入口依次执行 `sql/init.sql` 和 `deploy/mysql/002-application-grants.sh`。授权脚本撤销默认权限，仅向应用账号授予目标库的 `SELECT`、`INSERT`、`UPDATE`。

初始化脚本不会在旧数据卷上重跑。升级旧库时先用管理员账号核对：

```sql
SHOW GRANTS FOR 'campus_app'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'campus_app'@'%';
GRANT SELECT, INSERT, UPDATE ON campus_resource_platform.* TO 'campus_app'@'%';
```

把 `campus_app` 替换为实际应用账号；不要把管理员账号交给后端。结构迁移仍由管理员账号在维护窗口执行。

## 4. DEPLOY-04 本地测试与构建

2 GB 服务器不执行 Maven、npm 或镜像构建。推荐在仓库任意目录调用统一脚本：

```powershell
& "<仓库根目录>\deploy\scripts\Test-Deploy04Build.ps1"
```

脚本会从自身位置解析仓库根目录，不依赖当前工作目录。四个 DEPLOY-04 脚本默认要求当前分支为 `main` 且工作区干净；验证临时功能分支时通过 `-RequiredBranch '<实际分支名>'` 显式指定。开发脚本期间需要验证未提交改动时，可以显式添加 `-AllowDirtyWorkingTree`，正式候选验证不能使用该参数。

脚本执行以下操作：

1. 检查 Git 分支和工作区，并检查 Docker Engine、Docker Compose、Java、Node.js 与 npm；
2. 以固定前缀和唯一名称启动一次性 MySQL `8.4.11` 容器，只随机绑定到 `127.0.0.1`，运行后端全量测试与打包；
3. 运行前端 `npm ci`、全量单元测试与生产构建；
4. 使用当前 Git commit 的 12 位短 SHA 标记后端、前端候选镜像，静默校验 Compose 并构建镜像；
5. 将不含 Secret 的 JSON 证据写入系统临时目录 `campus-resource-platform\deploy-04\evidence`，可用 `-EvidencePath` 指定其他位置。

临时强密码和 Compose env 文件只写入系统临时目录，测试环境变量只在当前脚本进程内设置。脚本成功或失败都会进入清理流程，只删除本次运行创建且标签匹配的一次性 MySQL 容器和临时文件。容器或临时 Secret/env 文件无法确认删除时，JSON 证据中的 `cleanup` 会标记 `FAILED`，整体执行返回非零，并输出任务专属容器 name/id 与临时目录供人工核对。

镜像构建结束后，脚本会重新读取分支、HEAD 和工作区状态。正式运行必须继续保持干净；使用 `-AllowDirtyWorkingTree` 时，最终 tracked/untracked 状态及源码内容摘要也必须与启动快照完全一致。摘要覆盖 `git diff --binary HEAD`，以及按路径排序的每个未跟踪普通文件 SHA-256；未跟踪路径必须位于规范化仓库根内，任一层为符号链接或 junction 时拒绝读取。证据只保存最终摘要，不保存源码内容。任一变化都会让候选验证失败。该阶段不需要启动 Windows MySQL 服务，也不需要本机 Redis 服务。

如果需要手工排查，必须从仓库根目录开始，并在前端构建后返回仓库根目录再运行 Compose：

```powershell
Set-Location campus-resource-platform
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package

Set-Location ..\frontend
npm ci
npm run test:unit
npm run build

Set-Location ..
```

再验证 Compose 并构建镜像：

```powershell
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml build
```

不要输出完整 `docker compose config`，展开结果包含 Secret。手工运行后端全量测试仍需提供隔离的 MySQL 测试实例；统一脚本会自动创建并清理。

完成候选镜像构建后，运行 Compose 隔离演练：

```powershell
& "<仓库根目录>\deploy\scripts\Test-Deploy04Compose.ps1"
```

脚本使用 `campus-deploy04-<run-id>` 唯一项目名、由 Docker 原子分配且仅绑定 `127.0.0.1` 的动态端口，以及系统临时目录中的独立强 Secret，以 `up --no-build --pull never` 启动当前 Git 短 SHA 对应的候选镜像。它逐次核对实际容器的 immutable image ID，验证四个服务健康、Nginx/SPA/liveness/readiness、内部端口隔离、Redis 认证、MySQL 最小权限、MySQL/Redis/上传目录故障时 liveness 仍正常及 readiness 恢复，以及 MySQL/Redis/上传卷在不带 `-v` 的 `down/up` 后仍然保留数据。

最终清理只对本次唯一 Compose project 执行 `down -v`。执行前逐项核对容器、网络和卷的 `com.docker.compose.project` 标签，标签不匹配时拒绝删除；清理失败会使演练失败并在脱敏证据中保留 project 名。JSON 证据默认写入系统临时目录 `campus-resource-platform\deploy-04\evidence`。开发脚本期间可以显式使用 `-AllowDirtyWorkingTree`，但启动与结束的分支、HEAD、Git 状态和源码摘要必须完全一致。

完成空卷运行演练后，可执行旧库迁移与结构验收演练：

> 当前状态（2026-09-24）：迁移脚本已覆盖合成旧库的首次迁移、幂等重跑、结构负测、资源清理、失败路径源码终态复核、EvidencePath 重解析点防护和全部 legacy 表数据摘要；联合恢复与旧镜像回滚演练也已通过。

```powershell
& "<仓库根目录>\deploy\scripts\Test-Deploy04Migration.ps1"
```

脚本从固定历史提交的 `sql/init.sql` 快照创建独立 legacy 数据卷，加载只用于演练的合成数据，并仅启动 MySQL 8.4.11 与 Redis 7.4。它先验证 Redis `legacy-active` 门禁确实拒绝迁移，再按 `20260714`、`20260715`、`20260813` 的固定顺序执行迁移，精确核对表、列、索引、CHECK、生成列和数据回填。`user`、`category`、`file_info`、`resource`、`favorite`、`download_record`、`audit_record` 的全部 legacy 原列按主键排序，以带 NULL/长度边界的逐行 SHA-256 生成逐表及汇总摘要；三个空表也以 `rowCount=0` 参与摘要。首次迁移和完整幂等重跑后都必须与迁移前完全一致。隔离 scratch database 会验证原四类错误结构、同名 VIEW，以及 guard 列缺失但残留同名错误索引共六类错误结构都被拒绝。

每次运行使用 `campus-deploy04-migration-<run-id>` 唯一 Compose project，不发布 MySQL/Redis 宿主机端口，也不需要启动本机 MySQL 或 Redis 服务。强密码、Compose override/env 和脱敏 JSON 证据都位于系统临时目录。自定义 `-EvidencePath` 会先规范化，且必须位于仓库根目录外，也不能覆盖输入文件或临时 Secret 文件；从文件系统根到目标的任一已存在层级为 symlink、junction 或其他 ReparsePoint 时都会拒绝，写入前还会复核新建的父目录，防止 TEMP 路径绕回仓库。非法路径会使演练失败，并尽可能在默认 TEMP 目录生成失败证据。启动前和最终清理前都会从资源名称前缀与 Compose project label 两路核对容器、网络和卷；任一缺失或错误 label 都拒绝执行 `down -v`。临时 Secret 或隔离 Docker 资源无法确认删除时，清理失败会覆盖成功状态并保留 project 名供人工核查。

发现真实 legacy active/current/任意 syncing 批次、未知 Redis key 内容、已有同名但结构错误的表、列或索引、或有效资料重复组时，必须停止并人工核查。synthetic Redis guard 只通过“key 不存在才创建”的 Lua 原子脚本写入；任何已有 key 都只拒绝且不修改。清理也通过 Lua 原子比较 key 类型、Hash 长度、字段和值，仅删除完全匹配的本次 synthetic key，不会自动修复或删除导入的真实数据。

完成迁移演练后，可执行本地联合恢复与旧镜像回滚演练：

```powershell
& "<仓库根目录>\deploy\scripts\Test-Deploy04Rollback.ps1"
```

脚本从固定提交 `6449823045734a24f377c270b4d54d358d99b33c` 构建上一版镜像，在唯一隔离项目中加载 legacy fixture、执行三份迁移并启动当前候选。停写时先停止前端和后端，确认没有下载同步批次或上传 `.part` 文件，再用容器内 `mysqldump`、Redis 完整 `/data` 卷和上传卷创建同一切点恢复点。恢复目标使用新的空卷，并通过 MySQL、Redis、上传文件三联 marker 证明切点前数据存在、切点后数据不存在；最后用上一版镜像验证入口、后端存活、镜像 revision 和上传卷读写兼容。

临时 Secret、数据库导出和卷归档只保存在本次系统临时目录，JSON 证据仅记录状态、摘要、字节数和镜像 ID。成功或失败都会按精确 Compose project/volume label 与 run-id 清理隔离资源和历史临时镜像；这只是合成数据的本地回滚演练，生产备份保留、加密、异地保存和真实数据恢复仍属于 DEPLOY-08。

### 4.1 镜像交付到服务器

GitHub 推送的是源码与配置，镜像需要单独交付。以下本地 PowerShell 示例在四个 DEPLOY-04 门禁通过后执行，候选标签必须与 `Test-Deploy04Build.ps1` 的证据一致；归档保存在系统临时目录，不进入 Git：

```powershell
$releaseRevision = (git rev-parse --short=12 HEAD).Trim()
$releaseDirectory = Join-Path ([IO.Path]::GetTempPath()) ("campusshare-release-" + $releaseRevision)
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
$imageArchive = Join-Path $releaseDirectory 'application-images.tar'
docker image save --output $imageArchive `
  "campus-resource-platform-backend:$releaseRevision" `
  "campus-resource-platform-frontend:$releaseRevision"
if ($LASTEXITCODE -ne 0) { throw '镜像导出失败，停止发布。' }
Get-FileHash -Algorithm SHA256 -LiteralPath $imageArchive
# 分别记录两镜像 immutable ID，服务器导入后必须逐项相同。
docker image inspect --format '{{.Id}}' "campus-resource-platform-backend:$releaseRevision"
docker image inspect --format '{{.Id}}' "campus-resource-platform-frontend:$releaseRevision"
```

通过 SSH 等受控通道传输归档，并单独保留本地 SHA-256 和镜像 ID。服务器检出已验证的配置提交；若只更新运维配置并复用旧应用镜像，明确分别记录配置提交和应用构建提交，不能用配置 HEAD 猜测镜像标签。

在服务器仓库根目录执行，替换发布包绝对路径、可信摘要与已验证镜像标签：

```bash
# 先与本地发布记录中的摘要比较，一致后才导入。
sha256sum /受控发布目录/application-images.tar
sudo docker image load --input /受控发布目录/application-images.tar
sudo docker image inspect --format '{{.Id}}' 'campus-resource-platform-backend:<已验证的12位SHA>'
sudo docker image inspect --format '{{.Id}}' 'campus-resource-platform-frontend:<已验证的12位SHA>'
```

将上述准确标签填入服务器 `deploy/.env` 的 `BACKEND_IMAGE` 和 `FRONTEND_IMAGE`。核对后，显式拉取当前 Compose 固定 digest 的数据服务镜像，静默验证配置：

```bash
sudo docker compose --env-file deploy/.env -f deploy/docker-compose.yml pull mysql redis
sudo docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

`--pull never` 依赖四个镜像已在服务器存在；缺失时应停止排查发布介质，而不是临时在 2 GB 服务器构建。保留上一版镜像、配置提交与三卷联合恢复点，具体生产恢复和回滚要求见 [DEPLOY-08 手册](../docs/DEPLOY-08-RUNBOOK.md)。

## 5. 启动、存活与就绪检查

以下为首次 HTTP 启动；已启用 HTTPS 的站点后续操作还必须带 `-f deploy/docker-compose.https.yml`，见第 9 节。

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --no-build --pull never
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/healthz
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/api/v1/health
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/api/v1/health/readiness
```

- `/healthz`：Nginx 入口存活。
- `/api/v1/health`：Spring 进程存活，不检查依赖。
- `/api/v1/health/readiness`：检查 MySQL 连接、Redis `PING`、上传目录创建/写探针及磁盘保留水位；任一失败返回 HTTP 503，仅公开组件 `UP`/`DOWN`。数据库取连接默认最多等待 3 秒，Redis 连接和命令默认最多等待 2 秒。

Compose 用 readiness 控制前端启动顺序。Docker 健康状态本身不会自动重启仍在运行但不就绪的后端，也不会让单个 Nginx 动态摘除它，运行期故障仍需监控和人工处置。

上述 `${HTTP_PORT:-80}` 读取的是当前 shell 环境，不会自动读取 `deploy/.env`；如果 `.env` 自定义了端口，curl 中显式使用实际端口即可，不要为了端口查询把含 Secret 的文件整体 `source` 到 shell。

## 6. 可信代理与同源边界

Nginx 会覆盖 `X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`，并清空常见伪造代理头。Tomcat 只接受默认私网可信代理产生的转发头，业务代码只读取解析后的 `request.remoteAddr`。

该边界依赖后端端口不发布到宿主机、公网只允许进入 Nginx。若更换网络拓扑或增加外部负载均衡，必须同步收敛 Tomcat `internal-proxies`，再验证伪造头不能绕过下载 IP 限流。

正式站点的前端和 API 同源，后端未配置 CORS。将前端改到其他 Origin 前，需要单独设计明确的 Origin、方法、请求头和凭证策略。

## 7. 上传与下载安全边界

上传按扩展名、客户端 MIME 和服务端内容联合校验：固定格式检查文件签名；TXT/Markdown 必须是严格 UTF-8；ZIP/OOXML 限制条目数、单条目大小、总展开量和压缩比，并拒绝路径穿越。DOCX/XLSX/PPTX 还会解析受限大小的内容类型与关系 XML，确认主文档类型，禁用外部关系并拒绝宏部件。

落盘在进程内串行执行容量检查，先写同目录 `.part` 文件，再原子移动；失败会清理临时文件。普通下载固定使用 `application/octet-stream`、附件模式、`nosniff` 和 `no-store`；审核预览增加 CSP `sandbox`。

当前能力是轻量格式和容器结构校验，不是病毒、恶意文档或宏执行扫描。历史文件不会自动重扫；高风险格式仍依赖管理员审核。引入 ClamAV、内容安全服务、严格用户累计配额、多实例共享存储或对象存储需要后续独立设计，2 GB 主机不内置重型扫描服务。

## 8. 停止、日志与持久化

Spring 使用优雅停机，最多等待 30 秒完成在途请求和调度任务；Compose 预留 40 秒停止宽限期。所有容器使用 `restart: unless-stopped`，JSON 日志单文件最多 10 MB、保留 3 个文件。

HTTP 部署普通停止不得添加 `-v`：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml down
```

HTTPS 部署的停止、重建和日志操作均使用同一 `.env` 和两个编排文件，例如：

```bash
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml stop
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml logs --tail=100 backend frontend
```

`docker compose down -v` 会删除 MySQL、Redis 和上传卷，只能用于明确隔离的测试项目。至少执行一次写入测试数据、`down`、重新 `up -d` 的持久化验证。

## 9. DEPLOY-07 HTTPS 与证书续期

HTTPS 分两阶段启用，基础 `docker-compose.yml` 始终保持 DEPLOY-04 的 HTTP 行为。

前置条件为宿主机已安装 Certbot、OpenSSL、`flock`，DNS 根域名 `A` 和 `www` CNAME 均已指向生产服务器，80 / 443 已在安全组及防火墙开放。当前 `https.conf` 和证书脚本固定使用 `campusshare.online` / `www.campusshare.online`；换域名需同步调整 Nginx、证书域名校验及公网预检脚本，没有通用 `DOMAIN` 环境变量。

首先创建宿主机 ACME webroot，并把以下非敏感变量写入服务器本地 `deploy/.env`：

```dotenv
HTTPS_PORT=443
ACME_WEBROOT_DIR=/srv/campusshare/acme
TLS_RUNTIME_VOLUME=campus-resource-platform_tls_runtime
```

### 9.1 首次签发

先启动 HTTP + ACME override；此阶段不需要证书卷，原站点和 `/healthz` 行为保持不变：

```bash
sudo install -d -o root -g root -m 0755 /srv/campusshare/acme/.well-known/acme-challenge
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.acme.yml \
  config --quiet
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.acme.yml \
  up -d --no-build --pull never frontend
```

确认两个域名的 challenge 均可从公网访问后，用真实通知邮箱替换占位符。邮箱只写命令行或 Certbot 配置，不写仓库文档：

```bash
sudo certbot certonly --webroot \
  --webroot-path /srv/campusshare/acme \
  --cert-name campusshare.online \
  --email '<CERTBOT_EMAIL>' \
  --agree-tos \
  -d campusshare.online \
  -d www.campusshare.online
```

将证书写入带项目标签的外部 Docker 卷。脚本要求 root、使用 `flock` 串行化，校验证书 SAN、有效期和公私钥匹配，并通过运行中 frontend 的 immutable image ID 执行候选 `nginx -t`：

```bash
sudo sh deploy/scripts/deploy07-cert-deploy.sh \
  --lineage /etc/letsencrypt/live/campusshare.online \
  --project-name campus-resource-platform \
  --tls-volume campus-resource-platform_tls_runtime
```

### 9.2 启用 HTTPS

服务器启用 HTTPS 时必须始终使用以下准确的两个文件；HTTPS override 已包含 ACME 挂载，Compose 合并会保留基础 `80:8080`，并追加 `443:8443`：

```bash
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.https.yml \
  config --quiet
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.https.yml \
  up -d --no-build --pull never --force-recreate frontend
```

HTTPS 配置让 HTTP challenge 继续可用，其余 HTTP 请求 301 到根域名；`www` 的 HTTPS 请求也 301 到根域名。TLS 容器端口为非特权 `8443`，容器健康检查单独访问只绑定 loopback 的 `8081`。当前仍不启用 HSTS；应先完成 DEPLOY-08 的完整业务、恢复与回滚验收并稳定观察，再单独评估是否让浏览器形成长期强制策略。

### 9.3 自动续期与回滚

先把主脚本安装到固定的 root 运维路径，再创建只包含非敏感固定参数的 Certbot wrapper。wrapper 不传 `--lineage`，由主脚本读取 Certbot 提供的 `RENEWED_LINEAGE`：

```bash
sudo install -o root -g root -m 0750 \
  deploy/scripts/deploy07-cert-deploy.sh \
  /usr/local/sbin/campusshare-deploy07-cert
sudo install -d -o root -g root -m 0755 /etc/letsencrypt/renewal-hooks/deploy
sudo sh -c 'umask 027
printf "%s\n" \
  "#!/bin/sh" \
  "set -eu" \
  "exec /usr/local/sbin/campusshare-deploy07-cert --project-name campus-resource-platform --tls-volume campus-resource-platform_tls_runtime" \
  > /etc/letsencrypt/renewal-hooks/deploy/50-campusshare-nginx
chown root:root /etc/letsencrypt/renewal-hooks/deploy/50-campusshare-nginx
chmod 0750 /etc/letsencrypt/renewal-hooks/deploy/50-campusshare-nginx'
sudo certbot renew --dry-run --run-deploy-hooks --no-random-sleep-on-renew
sudo /usr/local/sbin/campusshare-deploy07-cert \
  --lineage /etc/letsencrypt/live/campusshare.online \
  --project-name campus-resource-platform \
  --tls-volume campus-resource-platform_tls_runtime
```

`--run-deploy-hooks` 会让 dry-run 的 staging 证书真实经过 hook 和 Nginx reload，用于覆盖完整续期链路；dry-run 完成后必须立即再次部署生产 lineage，确保运行时卷和公网端点恢复为受信任的生产证书。最后应比较生产 lineage、TLS 卷当前证书和公网端点的指纹。

如果生产 `COMPOSE_PROJECT_NAME` 或 `TLS_RUNTIME_VOLUME` 改名，首次部署命令和 wrapper 必须同步使用 `.env` 中的准确非敏感值。禁止在 wrapper 中 `source deploy/.env`：该文件还包含数据库、Redis 和 JWT Secret，不应进入 Certbot hook 环境。

上述安装后命令默认仓库位于 `/srv/campusshare/app`。若使用其他发布目录，在 root-owned wrapper 的 `exec` 前增加 `export CAMPUSSHARE_HTTPS_CONFIG='/实际仓库绝对路径/deploy/nginx/https.conf'`；所有安装后手工发布命令也应改为 `sudo env CAMPUSSHARE_HTTPS_CONFIG='/实际仓库绝对路径/deploy/nginx/https.conf' /usr/local/sbin/campusshare-deploy07-cert ...`。此变量属于宿主机脚本环境，放进 Compose `.env` 不会传给 Certbot hook。配置文件应保持来自可信发布提交，路径在每次续期时都必须存在；安装后先验证 `renew --dry-run --run-deploy-hooks`，随后用相同路径设置重新发布生产 lineage。

续期成功后，hook 会在 TLS 卷内建立指纹 release，通过 `current.next` 原子切换 `current`，再检查并平滑重载 Nginx；失败会恢复旧链接和旧证书。原始 `/etc/letsencrypt` 不会长期挂入应用容器，Docker socket 也不会挂入任何容器。

HTTPS 启动失败时回滚到基础 HTTP + ACME 组合，确保 challenge 仍可用于修复证书：

```bash
sudo docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.acme.yml \
  up -d --no-build --pull never --force-recreate frontend
```

本地隔离验收使用系统临时目录生成自签 SAN 证书、随机 Compose project/端口/网络/外部 TLS 卷，成功或失败都会按专属标签清理：

```powershell
& "<仓库根目录>\deploy\scripts\Test-Deploy07Https.ps1"
```

Linux/root 环境还应直接黑盒验证证书部署脚本的首次发布、幂等、轮换、权限和失败回滚；测试只操作随机命名且经标签核对的隔离资源，不向容器挂载 Docker socket：

```bash
sudo sh deploy/scripts/Test-Deploy07Certificate.sh
```

证书、私钥、生产 `.env` 均不得进入 Git。生产和回滚操作都禁止使用 `docker compose down -v`，否则会同时删除 MySQL、Redis 和上传数据卷；外部 TLS 卷也只能在明确废弃证书且完成备份后人工处理。

## 10. 2 GB 服务器预算与下一步

默认容器上限约为后端 `768 MB`、MySQL `512 MB`、Redis `160 MB`、Nginx `64 MB`。JVM 最大堆 512 MB、Hikari 最大连接 8、MySQL Buffer Pool 256 MB、Redis 数据上限 96 MB 且 `noeviction`。持续 Swap 或 OOM 时应停止接流量并升级内存，不在同机运行 Jenkins、Prometheus、Grafana 或病毒扫描守护进程。

根据 2026-09-25 的历史生产验收记录，DEPLOY-03 生产安全收敛、DEPLOY-04 本地发布门禁、DEPLOY-05 真实服务器部署、DEPLOY-06 DNS 和 DEPLOY-07 HTTPS/自动续期均已完成。当前任务为 DEPLOY-08 公网业务验收与生产联合备份恢复，状态为 `IN_PROGRESS`。当时 2 GB 服务器上的 MySQL 稳态内存接近 512 MiB 容器上限，虽未发生 cgroup 超限、OOM 或持续 Swap，仍应作为首要容量监控项；历史结论不代替当前运行检查。

DEPLOY-08 已提供 `scripts/Test-Deploy08PublicAcceptance.ps1` 只读公网预检、`scripts/deploy08-backup.sh` 生产联合备份脚本和 `scripts/deploy08-restore-preflight.sh` 独立恢复介质预检。备份脚本默认只预检，真实执行必须显式确认停机；它在同一停写切点导出 MySQL、归档 Redis AOF 卷和上传卷，并使用预先导入的 GPG 公钥加密。恢复预检会核对独立固定的签名信任链、六个加密产物、恢复私钥、Secret、隔离 Docker daemon 以及当前/上一版镜像，但不会创建资源。完整命令、安全边界、尚未执行项和恢复要求见 [DEPLOY-08 运行手册](../docs/DEPLOY-08-RUNBOOK.md)。脚本资产完成不代表生产备份或恢复已经通过。
