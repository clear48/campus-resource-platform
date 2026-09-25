# DEPLOY-08 公网验收与生产恢复运行手册

## 1. 当前阶段

`DEPLOY-08` 是部署流程的最终生产验收门，目标是证明公网业务、安全边界、持久化、联合恢复和上一镜像回滚真实可用。它不是新业务功能，也不能以“脚本已生成”或“容器 healthy”代替恢复结论。

当前已完成 **D08-1A 公网只读预检资产**、**D08-2 生产联合备份脚本资产**和 **D08-3A 独立恢复介质预检资产**。生产备份与独立恢复尚未真实执行，`DEPLOY-08` 整体状态仍为 `IN_PROGRESS`，不得提前标记为 `DONE`。

## 2. 安全边界

- 当前脚本只允许访问 `https://campusshare.online`，只发送 `GET` 请求，并禁止自动跟随重定向。
- 脚本不会注册、登录、上传、审核、收藏、下载或调用其他写接口。
- 脱敏证据默认写入系统临时目录，不得写入 Git 仓库。
- 证据只保存域名、场景状态、耗时和错误类型，不保存响应正文、IP、密码、Token、生产 Secret、文件内容或证书私钥。
- 证据文件使用 `CreateNew` 创建，拒绝覆盖已有证据；路径中的符号链接或 ReparsePoint 会被拒绝。
- PowerShell 版本、BaseUri 和证据路径属于启动前安全校验；这些校验失败时不会写证据，因为目标路径或目标站点尚未被信任。
- 本阶段不直接操作 SSH、Docker、Compose、生产卷、数据库或 Redis。readiness 接口会按既有实现短暂创建并删除一个 `.readiness-*.tmp` 上传目录写探针，用于证明上传卷可写；它不创建业务文件，但不能描述为绝对无副作用。

## 3. D08-1A 公网只读预检

从仓库根目录使用 PowerShell 7 执行：

```powershell
pwsh -NoProfile -File .\deploy\scripts\Test-Deploy08PublicAcceptance.ps1
```

如需把证据写到指定的仓库外新文件：

```powershell
pwsh -NoProfile -File .\deploy\scripts\Test-Deploy08PublicAcceptance.ps1 `
  -EvidencePath 'C:\temp\deploy08\public-preflight.json'
```

脚本验证：

- 首页、登录、注册、搜索、上传、管理审核等 Vue 路由可以直接刷新；
- `/healthz` 返回 HTTP 200；
- `/api/v1/health` 返回 HTTP 200 和成功业务码；
- `/api/v1/health/readiness` 返回 HTTP 200、成功业务码和 `UP`；
- HTTP 根域名 301 到 HTTPS 根域名；
- `www` HTTPS 301 到根域名。

输出 `PREFLIGHT_PASSED_WITH_SKIPS` 只表示入口预检通过，不表示 DEPLOY-08 完成。

## 4. 十二项验收矩阵

| ID | 验收内容 | 当前状态 | 后续执行方式 |
| --- | --- | --- | --- |
| 1 | 首页、登录、注册、Vue 路由刷新 | `SKIPPED` | D08-1A 只验证页面入口；D08-1B 使用专用账号验证真实注册和登录 |
| 2 | 普通用户上传并创建待审核资料 | `SKIPPED` | D08-1B 显式授权生产写入后执行 |
| 3 | 管理员读取文件并审核通过 | `SKIPPED` | 用户以安全交互方式提供管理员凭据，不写入命令、日志或证据 |
| 4 | 游客搜索和查看已审核资料 | `SKIPPED` | 对本轮唯一标题和资源 ID 验证 |
| 5 | 收藏、下载票据和文件下载 | `SKIPPED` | 下载后比较文件摘要，不只检查状态码 |
| 6 | 下载限流、排行榜和定时任务 | `SKIPPED` | 等待真实调度窗口并核对公开结果与服务端证据 |
| 7 | 普通用户不能调用管理员接口 | `SKIPPED` | 使用普通用户 Token 断言 HTTP 403 |
| 8 | 接近 50 MB 上传 | `SKIPPED` | 使用合法真实格式文件，经浏览器和公网 HTTPS 验证 |
| 9 | 伪造转发 IP 头不能绕过限流 | `SKIPPED` | 使用 4 个专用用户验证真实 IP 限流 |
| 10 | 容器和宿主机重启后数据不丢失 | `SKIPPED` | 用户在服务器维护窗口执行，严禁 `down -v` |
| 11 | 联合备份可恢复到独立环境 | `SKIPPED` | D08-2/3 完成 MySQL、Redis、上传卷三联恢复 |
| 12 | 上一镜像兼容数据库和文件 | `SKIPPED` | 在独立恢复副本运行真实登录、搜索、详情和下载链路 |

任何 `SKIPPED` 都不计入通过。只有 12 项全部为 `PASSED`，且证据、RPO/RTO 和异地备份均满足要求，才能将 DEPLOY-08 标记为 `DONE`。

## 5. D08-1B 生产业务验收约束

D08-1B 会在生产环境创建测试用户、文件、资料、审核流水、收藏和下载记录。当前项目没有删除这些验收数据的业务接口，因此执行前必须接受它们会留在生产数据库和上传卷中，并统一使用 `deploy08-<runId>` 前缀便于审计。

管理员凭据必须通过安全交互输入，仅在进程内存中使用；禁止：

- 把密码、Token 或生产 Secret 写入参数、脚本、JSON、Markdown 或聊天；
- 保存完整 API 响应或请求头；
- 将生产 `.env`、数据库导出、Redis 归档或上传文件提交到 Git。

### 接近 50 MB 上传

必须使用内容校验器允许的真实文件，建议大小为 47～50 MiB，并同时通过真实浏览器和 API 验证。不能使用全零 TXT 等无效伪造文件。

当前前端 Axios 全局超时为 10 秒。如果浏览器上传在服务端成功前先超时，应立即停止该项验收，另开独立前端小功能，只调整上传请求超时并补充专项、全量测试和构建；不能用命令行上传成功掩盖浏览器失败。

### 伪造转发 IP 头

用户限流是每用户每分钟 10 次，IP 限流是每 IP 每分钟 30 次。正确验收方式是：

1. 使用 4 个本轮专用普通用户；
2. 每个用户在窗口内不超过 10 次；
3. 每次请求携带不同的伪造 `X-Forwarded-For`；
4. 前 30 次允许，第 31 次仍应因同一真实来源 IP 返回 429；
5. 与其他限流场景至少间隔 120 秒，避免窗口相互污染。

## 6. D08-2 生产联合备份

脚本路径：`deploy/scripts/deploy08-backup.sh`。默认只做预检；真实执行必须同时提供 `--execute` 与 `--acknowledge-downtime`。

### 6.1 准备加密公钥和目录

备份使用 GPG 公钥加密，因为 MySQL 导出、Redis AOF 和上传文件本身都属于敏感数据。该命令是服务器运维依赖，不修改 Java、Node 或项目运行依赖；脚本发现 `gpg` 缺失时只会停止，不会擅自安装。

恢复私钥只能保存在独立恢复端。生产服务器只导入对应加密公钥，并另外创建一把用途隔离、root-only 的 Ed25519 备份签名私钥。签名公钥必须在首次备份前通过独立可信渠道固定到恢复端，不能把备份目录中附带的公钥当作信任根。

```bash
sudo install -d -o root -g root -m 0700 /srv/campusshare/backups
sudo gpg --import /受控路径/deploy08-backup-public-key.asc
sudo install -d -o root -g root -m 0700 /root/.config/campusshare
sudo ssh-keygen -t ed25519 -N '' \
  -f /root/.config/campusshare/deploy08-backup-signing-key
```

将 `deploy08-backup-signing-key.pub` 的内容通过独立可信渠道带到恢复端，写成 OpenSSH `allowed_signers` 格式：

```text
campusshare-deploy08-backup ssh-ed25519 <已固定的签名公钥正文>
```

不要把签名私钥、解密私钥、公钥文件路径、UID 中的个人信息或真实指纹写入仓库。签名私钥无口令是为了维护窗口内非交互恢复生产服务，必须严格保持 root 所有、`600` 权限和用途隔离。

### 6.2 预检

```bash
cd /srv/campusshare/app
sudo bash deploy/scripts/deploy08-backup.sh \
  --gpg-recipient '<在服务器上唯一匹配的公钥指纹>' \
  --signing-key /root/.config/campusshare/deploy08-backup-signing-key
```

预检核对：干净的 `deploy` 分支、四服务健康、三个卷标签、immutable image ID、GPG 公钥、输出目录权限和磁盘水位。它不会停止服务或创建备份。

### 6.3 真实执行

只有在已经公告维护窗口、确认管理员业务验收停止写入后执行：

```bash
cd /srv/campusshare/app
sudo bash deploy/scripts/deploy08-backup.sh \
  --gpg-recipient '<在服务器上唯一匹配的公钥指纹>' \
  --signing-key /root/.config/campusshare/deploy08-backup-signing-key \
  --execute \
  --acknowledge-downtime
```

脚本输出目录只包含 GPG 加密业务产物，以及不含 Secret 的 `manifest.json`、相对路径摘要和 detached signature。若任何步骤失败，退出 trap 会先尝试以原镜像和原卷恢复四个生产服务，再清理本轮 `.partial`；服务恢复失败属于立即人工介入的严重错误。

复制到恢复端后，先验证独立固定的签名和相对摘要：

```bash
cd /受控恢复目录/<run-id>
ssh-keygen -Y verify \
  -f /受控路径/deploy08-allowed-signers \
  -I campusshare-deploy08-backup \
  -n campusshare-deploy08 \
  -s manifest.json.sig < manifest.json
sha256sum -c manifest.json.sha256
```

同目录 SHA 只能发现传输损坏；detached signature 和恢复端预先固定的公钥才承担来源认证。

后续生产备份必须采用同一停写切点：

1. 停止公网入口；
2. 优雅停止后端并确认退出；
3. 拒绝存在任何下载增量 `syncing` 批次或上传 `.part` 文件；
4. 确认 Redis `WAITAOF` 和 AOF 状态正常；
5. 导出 MySQL；
6. 正常停止 MySQL 和 Redis；
7. 归档 Redis 完整数据卷和上传卷；
8. 使用离线持有私钥所对应的公钥加密；
9. 生成不含敏感数据的 manifest、摘要、字节数、镜像身份和切点时间；
10. 无论成功失败都恢复原有服务并验证 readiness。

生产脚本不得使用 `down --volumes`，不得自动安装依赖，不得猜测异地目标，也不得把 TLS 私钥或生产 `.env` 放入业务数据备份。

D08-2 资产完成不等于生产备份完成。必须把完整 run-id 目录复制到独立恢复机，核对 `manifest.json.sha256` 和每个加密产物摘要，并成功完成 D08-3，才算第 11 项通过。

## 7. D08-3 独立恢复和上一镜像

生产服务器只有 2 GB 内存，MySQL 已接近其 512 MiB 限制。恢复栈不得与生产栈在同一服务器并行运行。加密备份应复制到本地 Docker 或临时恢复服务器，恢复到新建、空白、带本轮标签的三个卷。

### 7.1 D08-3A 恢复介质只读预检

在独立恢复机准备以下内容：完整 run-id 目录；通过独立可信渠道固定的 `allowed_signers`；只存在于恢复端的 GPG 私钥目录；权限为 root-only 的恢复专用环境文件；已离线导入的当前四服务镜像和上一版前后端镜像。恢复环境文件至少包含 `MYSQL_ROOT_PASSWORD`、`MYSQL_APP_USERNAME`、`MYSQL_APP_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`，不得直接复用生产环境文件路径。

```bash
sudo bash deploy/scripts/deploy08-restore-preflight.sh \
  --backup-dir /srv/campusshare-restore/<run-id> \
  --allowed-signers /root/.config/campusshare/deploy08-allowed-signers \
  --env-file /root/.config/campusshare/deploy08-restore.env \
  --gpg-homedir /root/.gnupg-deploy08-restore \
  --previous-backend-image 'sha256:<上一版后端镜像ID>' \
  --previous-frontend-image 'sha256:<上一版前端镜像ID>' \
  --previous-commit '<上一版40位源码revision>'
```

脚本要求上述目录和文件均为 `root:root` 且不向 group/other 开放；备份、密钥和 Secret 不得位于仓库内。它依次验证独立固定的 Ed25519 签名、相对 manifest 摘要、严格的 manifest v2 schema、六个加密产物大小和 SHA-256、GPG 私钥指纹及完整解密、Secret 最低边界、当前/上一版 immutable image ID，以及当前签名 `applicationRevision` 与上一版源码 revision 标签。部署资产的 `manifest.commit` 与冻结应用镜像 revision 是两个不同概念，不得混用。

只要当前 Docker daemon 存在 `campus-resource-platform` Compose 项目标记的任一容器、卷或网络，预检就会失败。这是“不得在生产 Docker daemon 恢复”的硬门禁。脚本中不存在 `docker run/create/volume create`，通过只代表介质和前置条件齐备，不代表数据已恢复。

```bash
sudo bash deploy/scripts/Test-Deploy08RestorePreflight.sh
```

D08-3B 仍需在该独立 Docker daemon 上新建带本轮标签的空卷，恢复并比对 MySQL 表行数/校验值、永久 Redis Key 内容摘要和上传文件全量摘要；随后依次使用 manifest 当前镜像和独立提供的上一版镜像完成 readiness、登录、检索、详情、下载等业务验证。D08-3A 不会代替这些步骤。

恢复后必须：

- 对账核心表、Redis 关键持久数据以及上传卷全部普通文件清单和摘要；
- 使用当前镜像验证 readiness、登录、搜索、详情、下载和文件摘要；
- 再顺序切换到固定上一镜像，核对 immutable image ID 和 revision；
- 通过上一镜像再次运行登录、搜索、详情和下载，不能只检查 liveness；
- 清理时只删除本轮隔离 project 和带本轮 run-id 标签的资源。

任何恢复工具都必须拒绝生产 project 名、生产卷名、非空目标卷、绝对路径、`..`、符号链接、硬链接和其他非普通归档项。

## 8. RPO、RTO 与保留策略

当前建议目标为：

- RPO：不超过 24 小时；
- RTO：不超过 120 分钟；
- 每次发布或数据库迁移前额外创建联合备份；
- 至少每月、以及每次存储或 Schema 变更后执行恢复演练。

以上是待用户确认的建议值，必须以一次真实恢复耗时和异地副本校验结果作为最终依据。在用户确认并实测前，不得写成已经达标。

## 9. 停止条件

遇到以下任一情况立即停止，不扩大操作：

- HTTPS、liveness 或 readiness 失败；
- 镜像 ID、revision、Compose project 或卷标签不符；
- 准备输出、提交或传输未加密的敏感数据；
- 发现下载同步批次、上传 `.part`、Redis AOF 异常或磁盘空间不足；
- 服务停止后无法按原状态恢复；
- 没有独立恢复环境或没有固定上一镜像；
- 接近 50 MB 文件在真实浏览器因 10 秒超时失败；
- 任何命令准备对生产 project 执行 `down -v` 或恢复到生产卷。

## 10. 后续小任务

- `D08-1B`：生产业务全链路、安全边界、49 MB 和伪造 IP 验收。
- `D08-2`：Ubuntu 生产联合备份、加密和脱敏 manifest。
- `D08-3`：独立空环境恢复、全量对账和上一镜像业务兼容。
- `D08-4`：真实宿主机重启、异地副本校验、RPO/RTO 实测和文档收口。
