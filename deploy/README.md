# DEPLOY-03 生产部署说明

本目录提供校园资料共享与检索平台的单机生产编排。浏览器只访问 Nginx；Spring Boot、MySQL 和 Redis 仅在 Compose 私网中访问。正式站点采用同源 `/api/v1/**`，后端不开放跨域。

## 1. 服务、端口与数据

| 组件 | 容器端口 | 宿主机发布 | 持久化 |
| --- | --- | --- | --- |
| Nginx / Vue | `8080` | `HTTP_PORT`，默认 `80` | 镜像内静态资源 |
| Spring Boot | `8080` | 否 | `uploads_data:/data/uploads` |
| MySQL | `3306` | 否 | `mysql_data:/var/lib/mysql` |
| Redis | `6379` | 否 | `redis_data:/data` |

`file_info.storage_path` 保存容器内绝对路径，上传挂载点固定为 `/data/uploads`。本地文件存储只支持一个后端实例，数据库和上传卷必须成对备份、成对恢复。

入口和后端请求上限为 `60 MB`，单文件上限为 `50 MB`。上传还要求磁盘可用空间大于“本次文件大小 + `APP_UPLOAD_MIN_FREE_SPACE_BYTES`”；示例生产值为 5 GiB。

## 2. 准备生产环境变量

```bash
cp deploy/.env.example deploy/.env
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

脚本会从自身位置解析仓库根目录，不依赖当前工作目录。默认要求当前分支为 `deploy` 且工作区干净；开发脚本期间需要验证未提交改动时，可以显式添加 `-AllowDirtyWorkingTree`，正式候选验证不能使用该参数。

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

> 当前状态（2026-09-23）：迁移脚本已完成合成旧库的首次迁移、幂等重跑、结构负测和资源清理演练，但仍处于 `WIP`。提交后还需补齐失败路径的源码终态复核、`EvidencePath` 对 symlink/junction 的防绕过校验，并把 `favorite`、`download_record`、`audit_record` 纳入全表数据摘要。上述问题关闭并重新生成干净工作区证据前，不得把本节结果视为 DEPLOY-04 正式放行。

```powershell
& "<仓库根目录>\deploy\scripts\Test-Deploy04Migration.ps1"
```

脚本从固定历史提交的 `sql/init.sql` 快照创建独立 legacy 数据卷，加载只用于演练的合成数据，并仅启动 MySQL 8.4.11 与 Redis 7.4。它先验证 Redis `legacy-active` 门禁确实拒绝迁移，再按 `20260714`、`20260715`、`20260813` 的固定顺序执行迁移，精确核对表、列、索引、CHECK、生成列和数据回填。`user`、`category`、`file_info`、`resource` 的全部 legacy 原列按主键排序，以带 NULL/长度边界的逐行 SHA-256 生成逐表及汇总摘要；首次迁移和完整幂等重跑后都必须与迁移前完全一致。隔离 scratch database 会验证原四类错误结构、同名 VIEW，以及 guard 列缺失但残留同名错误索引共六类错误结构都被拒绝。

每次运行使用 `campus-deploy04-migration-<run-id>` 唯一 Compose project，不发布 MySQL/Redis 宿主机端口，也不需要启动本机 MySQL 或 Redis 服务。强密码、Compose override/env 和脱敏 JSON 证据都位于系统临时目录。自定义 `-EvidencePath` 会先规范化，且必须位于仓库根目录外，也不能覆盖输入文件或临时 Secret 文件；非法路径会使演练失败，并尽可能在默认 TEMP 目录生成失败证据。启动前和最终清理前都会从资源名称前缀与 Compose project label 两路核对容器、网络和卷；任一缺失或错误 label 都拒绝执行 `down -v`。临时 Secret 或隔离 Docker 资源无法确认删除时，清理失败会覆盖成功状态并保留 project 名供人工核查。

发现真实 legacy active/current/任意 syncing 批次、未知 Redis key 内容、已有同名但结构错误的表、列或索引、或有效资料重复组时，必须停止并人工核查。synthetic Redis guard 只通过“key 不存在才创建”的 Lua 原子脚本写入；任何已有 key 都只拒绝且不修改。清理也通过 Lua 原子比较 key 类型、Hash 长度、字段和值，仅删除完全匹配的本次 synthetic key，不会自动修复或删除导入的真实数据。

## 5. 启动、存活与就绪检查

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/healthz
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/api/v1/health
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/api/v1/health/readiness
```

- `/healthz`：Nginx 入口存活。
- `/api/v1/health`：Spring 进程存活，不检查依赖。
- `/api/v1/health/readiness`：检查 MySQL 连接、Redis `PING`、上传目录创建/写探针及磁盘保留水位；任一失败返回 HTTP 503，仅公开组件 `UP`/`DOWN`。数据库取连接默认最多等待 3 秒，Redis 连接和命令默认最多等待 2 秒。

Compose 用 readiness 控制前端启动顺序。Docker 健康状态本身不会自动重启仍在运行但不就绪的后端，也不会让单个 Nginx 动态摘除它，运行期故障仍需监控和人工处置。

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

普通停止不得添加 `-v`：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml down
```

`docker compose down -v` 会删除 MySQL、Redis 和上传卷，只能用于明确隔离的测试项目。至少执行一次写入测试数据、`down`、重新 `up -d` 的持久化验证。

## 9. 2 GB 服务器预算与下一步

默认容器上限约为后端 `768 MB`、MySQL `512 MB`、Redis `160 MB`、Nginx `64 MB`。JVM 最大堆 512 MB、Hikari 最大连接 8、MySQL Buffer Pool 256 MB、Redis 数据上限 96 MB 且 `noeviction`。持续 Swap 或 OOM 时应停止接流量并升级内存，不在同机运行 Jenkins、Prometheus、Grafana 或病毒扫描守护进程。

DEPLOY-03 已完成生产安全收敛。DEPLOY-04 的候选镜像构建与 Compose 隔离运行演练已完成；旧库迁移脚本已形成 WIP 检查点，仍需关闭本节标注的三个复审问题并完成旧镜像回滚演练。DNS、HTTPS、公网验收和生产联合备份恢复继续按 DEPLOY-05～08 执行。
