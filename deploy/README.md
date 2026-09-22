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

## 4. 本地测试与构建

2 GB 服务器不执行 Maven、npm 或镜像构建。先在开发机或 CI 完成：

```powershell
Set-Location campus-resource-platform
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package

Set-Location ..\frontend
npm ci
npm run test:unit
npm run build
```

再验证 Compose 并构建镜像：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml build
```

不要输出完整 `docker compose config`，展开结果包含 Secret。

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

DEPLOY-03 已完成生产安全收敛。下一任务是 DEPLOY-04：用当前最终提交完成镜像构建、空卷初始化、真实 MySQL/Redis/readiness、最小权限、持久化、迁移与回滚演练。DNS、HTTPS、公网验收和联合备份恢复继续按 DEPLOY-05～08 执行。
