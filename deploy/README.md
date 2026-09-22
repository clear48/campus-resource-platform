# DEPLOY-02 容器化部署说明

本目录提供校园资料共享与检索平台的首版单机容器编排。Compose 运行 Nginx、单实例 Spring Boot、MySQL 和 Redis；只发布 Nginx 的 HTTP 端口，后端与数据服务仅在容器网络中访问。

## 1. 数据与端口边界

| 组件 | 容器端口 | 是否发布到宿主机 | 持久化 |
| --- | --- | --- | --- |
| Nginx / Vue | `8080` | `HTTP_PORT`，默认 `80` | 镜像内静态资源 |
| Spring Boot | `8080` | 否 | `uploads_data:/data/uploads` |
| MySQL | `3306` | 否 | `mysql_data:/var/lib/mysql` |
| Redis | `6379` | 否 | `redis_data:/data` |

`file_info.storage_path` 保存容器内绝对路径，因此上传挂载点固定为 `/data/uploads`。在改造为对象存储前只能运行一个后端实例，数据库和上传卷必须成对备份及恢复。

当前入口和后端请求上限固定为 `60 MB`，单文件上限固定为 `50 MB`。调整上传上限时必须同时修改 Nginx 与 Spring Boot 配置，不能只改其中一层。

## 2. 准备环境变量

在仓库根目录执行：

```bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

分别生成随机值后写入 `deploy/.env`，不要把命令输出粘贴到提交、日志或聊天记录：

```bash
openssl rand -hex 32      # MySQL root、应用账号和 Redis 可分别生成
openssl rand -base64 48   # JWT_SECRET，至少 32 字节
```

必须填写：

- `MYSQL_ROOT_PASSWORD`
- `MYSQL_APP_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_SECRET`

`MYSQL_APP_USERNAME` 是应用最小权限账号，不能改成 `root`。`deploy/.env` 已被 Git 忽略，`.env.example` 只能保存变量名和安全的非敏感默认值。

## 3. 本地测试与构建

服务器只有 2 GB 内存，不在服务器执行 Maven、npm 或镜像构建。先在开发机或 CI 完成测试：

```powershell
# 后端
Set-Location campus-resource-platform
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package

# 前端
Set-Location ..\frontend
npm ci
npm run test:unit
npm run build
```

然后从仓库根目录验证 Compose 并构建镜像：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml build
```

不要在终端或 CI 日志中输出完整的 `docker compose config` 结果，因为展开后的配置包含环境变量值。

## 4. 启动与检查

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/healthz
curl --fail http://127.0.0.1:${HTTP_PORT:-80}/api/v1/health
```

确认只有 Nginx 发布端口：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps --format json
```

输出中 `backend`、`mysql`、`redis` 不应出现宿主机端口映射。MySQL 和 Redis 的健康检查用于等待容器内服务可连接；后端 `/api/v1/health` 目前只代表应用进程可响应，不等同于完整生产 readiness。

## 5. 空卷初始化与旧库升级

`../sql/init.sql` 只会在 `mysql_data` 首次创建且为空时由 MySQL 官方入口执行。它固定创建 `campus_resource_platform`，因此 Compose 中的库名不能随意修改。

已有数据库不会因为重新挂载脚本而自动补齐迁移。旧库升级、历史数据核对和迁移演练属于 DEPLOY-04，执行前应阅读 `docs/database/database-change-log.md` 与 `docs/07-project-runbook.md`。

## 6. 重启持久化验证

至少完成一次以下验证：

1. 注册测试用户并上传一个非敏感测试文件；
2. 确认 MySQL 有对应记录，Redis 可认证访问，上传文件位于 `/data/uploads`；
3. 执行 `docker compose ... down`，不得添加 `-v`；
4. 再次执行 `docker compose ... up -d`；
5. 确认数据库记录、Redis 数据和上传文件仍然存在。

普通停止不会删除命名卷：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml down
```

`docker compose down -v` 会删除 MySQL、Redis 和上传文件卷，属于破坏性清理命令，不能作为常规停止或升级步骤。

## 7. 服务器运行

在本地或 CI 构建并验证镜像后，可以推送到私有镜像仓库，或者用 `docker save` 导出并在服务器执行 `docker load`。服务器拉取完整仓库和经过验证的提交，准备 `deploy/.env`，然后只使用现成镜像启动：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --no-build
```

镜像标签应使用不可变版本或提交 ID；部署新版本前保留上一版标签，以便应用层回滚。数据库结构和本地上传文件是否兼容必须另外验证，不能只回滚容器镜像。

## 8. 2 GB 服务器限制

默认内存上限约为：后端 `768 MB`、MySQL `512 MB`、Redis `160 MB`、Nginx `64 MB`，总计约 `1.5 GB`。MySQL Buffer Pool 从 `256 MB` 起步，Redis 数据上限为 `96 MB` 且使用 `noeviction`，Hikari 最大连接数为 `8`。

基础镜像已同时固定版本标签与已验证的多架构 digest，Maven Wrapper 也校验分发包 SHA-256。升级 Java、Node、Nginx、MySQL、Redis 或 Maven 时，需要重新构建并完成本页验证，不能只替换标签。

这些值只适合低流量演示。上线后必须观察容器峰值、宿主机可用内存、Swap 和 OOM 记录；出现持续 Swap 或 OOM 时应先停止接流量并升级到至少 4 GB。不要在同机运行 Jenkins、Prometheus 或 Grafana。

## 9. 后续任务边界

DEPLOY-02 提供容器运行底座。以下事项仍需在后续任务完成：

- DEPLOY-03：生产 CORS、可信代理边界复核、生产 Secret、完整依赖 readiness、优雅停机和上传安全边界；
- DEPLOY-04：全量测试、数据库迁移以及持久化和回滚演练；
- DEPLOY-05～08：服务器初始化、DNS、HTTPS、公网验收和联合备份恢复。

在本地部署资产和迁移演练完成前，不修改 `campusshare.online` 的 A 记录。
