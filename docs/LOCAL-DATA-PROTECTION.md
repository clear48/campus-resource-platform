# 本地开发数据备份与恢复

## 1. 目标与边界

本方案为 Windows PowerShell 本地开发环境提供以下能力：

- 把用户上传文件放在明确、稳定的固定目录；
- 在后端停写后联合备份 MySQL、Redis RDB 和上传目录；
- 使用 SHA-256 清单检测复制、磁盘或传输造成的意外损坏；
- 默认只校验备份，恢复时只导入全新的隔离数据库；
- 可选地以“暂存 → 校验 → 原目录隔离 → 同盘切换”的方式恢复活动上传目录；
- 原数据保留在 `quarantine`，不自动删除历史备份或故障现场。

本方案不提供在线热备、高可用、跨设备自动复制或恶意篡改证明。SHA-256 可以发现意外变化，但攻击者如果能同时修改文件和清单，仍可能伪造一致结果；需要来源认证时应增加独立密钥签名。

本地脚本与生产 `deploy08-*` 脚本相互独立，不能用本地验证替代生产恢复验收。

## 2. 数据关系与一致性切点

本项目的真实文件内容位于上传目录，MySQL 的 `file_info.storage_path` 保存绝对路径，Redis 还可能保存尚未同步到 MySQL 的下载增量。因此必须把三者作为一个恢复集合：

```text
等待下载增量排空
  → 停止后端和定时任务
  → 连续确认 Redis 无 delta/syncing 批次
  → 导出 MySQL
  → 导出 Redis RDB
  → 复制上传目录
  → 复算 SHA-256
  → 发布 PASSED manifest
```

本地 NTFS、MySQL 和 Redis 之间没有分布式快照。这里的“一致恢复点”依赖维护窗口内完全停写，不表示三个存储具备原子事务。

## 3. 目录规划

新开发环境建议使用仓库外固定目录：

```text
C:\campusshare-dev\uploads       # 活动上传文件
D:\campusshare-backups           # 联合备份，尽量使用另一块物理磁盘
```

首次上传前设置：

```powershell
New-Item -ItemType Directory -Path 'C:\campusshare-dev\uploads' -Force
$env:APP_UPLOAD_STORAGE_PATH = 'C:\campusshare-dev\uploads'
```

已有环境不能直接改变 `APP_UPLOAD_STORAGE_PATH`。历史 `file_info.storage_path` 是绝对路径，只移动文件会导致下载与审核预览失效；在完成单独的文件复制、数据库路径迁移和下载验证前，应继续使用原目录。

脚本拒绝以下路径：

- 卷根、UNC 路径和无法规范化的路径；
- 位于 Git 仓库内或包含 Git 仓库的备份根目录；
- 等于或包含 Git 仓库、用户主目录或 Windows 系统目录的上传目录；
- 上传、备份、恢复工作区互为父子目录；
- 目录或其祖先包含符号链接、Junction 等 ReparsePoint；
- 已存在的正式备份、恢复工作区、暂存或隔离目标。

为了兼容当前项目，脚本允许上传目录是 Git 仓库内的一个明确子目录，但不会自动移动该目录。新环境仍应优先使用仓库外固定目录，避免清理工作区时误删运行数据。

## 4. 工具与凭据

备份需要：

- PowerShell 7；
- `mysql`、`mysqldump`；
- `redis-cli`；
- 后端与定时任务已经停止；
- 当前 Redis 为该项目专用实例。

恢复还需要 `redis-check-rdb`，并要求预先创建一个与管理员账号不同的专用导入账号。该账号必须初始只有 `USAGE`；脚本会用导入凭据读取`CURRENT_USER()`和`SHOW GRANTS`，拒绝Host身份不一致、数据库/全局权限、角色或代理授权，再临时向全新的目标恢复库授权，导入核对后立即撤权：

```sql
CREATE USER 'crp_restore'@'%' IDENTIFIED BY '<仅用于本地恢复的密码>';
```

默认`MySqlImportAccountHost`为`%`。如果实际创建的是`'crp_restore'@'localhost'`等更具体账号，恢复命令必须显式传入相同Host；同名但Host不一致时脚本会停止。

脚本不会自动创建账号、安装工具、停止远程服务或覆盖共享 Redis。

密码只通过当前进程环境传递：

```powershell
$env:CRP_BACKUP_MYSQL_PASSWORD = '<本地 MySQL 密码>'
$env:CRP_BACKUP_REDIS_PASSWORD = '<本地 Redis 密码；无密码时不设置>'
$env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD = '<专用恢复账号密码>'
```

密码不会写入命令参数、manifest 或Git。操作完成后可执行：

```powershell
Remove-Item Env:CRP_BACKUP_MYSQL_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:CRP_BACKUP_REDIS_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD -ErrorAction SilentlyContinue
```

## 5. 创建联合备份

先让应用保持运行，等待正常调度把下载增量同步到 MySQL；确认没有待处理批次后停止后端。不要在后端仍可写入时执行备份。

创建仓库外备份根目录：

```powershell
New-Item -ItemType Directory -Path 'D:\campusshare-backups' -Force
```

从仓库根目录执行：

```powershell
pwsh -NoProfile -File .\scripts\local\Backup-LocalData.ps1 `
  -UploadPath 'C:\campusshare-dev\uploads' `
  -BackupRoot 'D:\campusshare-backups' `
  -MySqlUsername 'root' `
  -RedisHost '127.0.0.1' `
  -AcknowledgeBackendStopped
```

脚本会执行以下门禁：

- 8080端口不得仍在监听；
- 连续两次确认下载增量 Hash 为空且不存在 `syncing:*`；
- 上传目录不得存在 `.part` 或 ReparsePoint；
- 备份空间至少覆盖上传文件总量和安全余量；
- MySQL表清单和行数必须可以读取；
- 数据库只能包含基础表；发现视图、触发器、存储例程或事件时停止，避免生成当前协议无法完整还原的备份；
- 任一外部命令、复制或摘要失败时不得发布正式目录。

备份先写入 `<runId>.partial`，全部成功后才重命名为 `<runId>`。失败的 `.partial` 仅供排查，不能用于恢复。正式目录包含：

```text
<runId>\
├── mysql.sql
├── redis.rdb
├── uploads\
├── manifest.json
└── manifest.json.sha256
```

MySQL导出使用`--skip-triggers`，当前协议只面向本项目的基础表及表数据。若未来业务真正引入视图、触发器、存储例程或事件，需要先单独设计对象权限、`DEFINER`处理和真实恢复测试，不能绕过门禁。`redis-cli --rdb`导出的是整个 Redis 实例，不是单个逻辑数据库；Redis 与其他项目共享时必须停止，不能生成或恢复该 RDB。

## 6. 校验备份

校验模式不会修改数据库、Redis或上传目录：

```powershell
pwsh -NoProfile -File .\scripts\local\Restore-LocalData.ps1 `
  -BackupDirectory 'D:\campusshare-backups\<runId>' `
  -Mode ValidateOnly
```

脚本会严格验证：

- `manifest.json.sha256`；
- manifest schema、状态和run-id；
- MySQL与Redis产物的大小和SHA-256；
- 每个上传文件的唯一相对路径、大小和SHA-256；
- 缺失文件、额外文件、路径穿越和协议外目录。

## 7. 隔离恢复与上传目录切换

恢复前停止后端。恢复数据库必须使用新的隔离库，脚本硬拒源库名称或已经存在的目标库：

```powershell
pwsh -NoProfile -File .\scripts\local\Restore-LocalData.ps1 `
  -BackupDirectory 'D:\campusshare-backups\<runId>' `
  -BackupRoot 'D:\campusshare-backups' `
  -Mode Restore `
  -RestoreWorkspace 'C:\campusshare-restore\<runId>' `
  -RestoreDatabase 'campus_resource_platform_restore_<标识>' `
  -MySqlUsername 'root' `
  -MySqlImportUsername 'crp_restore' `
  -AcknowledgeBackendStopped
```

默认结果：

- `mysql.sql`导入全新的隔离数据库；
- 管理员账号只负责创建隔离库和临时授权，专用账号经实际认证身份与纯`USAGE`检查后负责导入，完成后撤销目标库权限；
- 导入前拒绝`USE`、数据库创建/删除和源库全限定引用，恢复后核对完整表集合及逐表行数；
- 上传文件复制到恢复工作区并再次计算SHA-256；
- `redis.rdb`复制为`redis\dump.rdb`并通过`redis-check-rdb`；
- 不覆盖活动上传目录或正在运行的Redis。

如果明确需要把上传文件切回manifest记录的原绝对路径，额外提供：

```powershell
-ActivateUploadDirectory `
  -ExpectedUploadPath 'C:\campusshare-dev\uploads' `
  -AcknowledgeUploadSwitch
```

`ExpectedUploadPath`必须与manifest记录的原绝对路径完全一致，用于防止选错活动目录。

切换顺序为：

1. 在活动目录同一父目录创建唯一stage；
2. 复制备份并完成第二次SHA-256校验；
3. 将原活动目录移动到`quarantine\<runId>-original`；
4. 将stage同盘移动为原活动目录；
5. 任一步失败时独立尝试保留失败目录并把quarantine原目录移回；
6. 切换成功后再次校验活动目录，且不自动删除quarantine。

补偿属于尽力回滚，不是跨文件系统事务保证。若文件锁、权限或磁盘故障导致补偿不完整，脚本会输出“补偿不完整”的严重错误；此时保持后端停止，人工检查活动目录、`quarantine`和`.crp-restore-*-failed`后再决定回迁，不能直接重跑或删除现场。

恢复出的RDB只放入隔离工作区。要验证Redis业务状态，应使用独立端口、独立数据目录或独立容器启动，不能把RDB直接复制到共享或运行中的Redis数据目录；RDB也不包含`requirepass`等服务配置。

恢复完成后，把`MYSQL_URL`指向隔离数据库。默认隔离恢复时，把`APP_UPLOAD_STORAGE_PATH`指向`<RestoreWorkspace>\uploads`；只有已执行活动目录切换时，才继续使用manifest中的原路径。随后验证登录、搜索、详情、审核文件读取和下载，全部通过后才能决定后续数据切换。

## 8. 自动测试与当前验证边界

运行专项测试：

```powershell
pwsh -NoProfile -File .\scripts\local\Test-LocalDataBackup.ps1
pwsh -NoProfile -File .\scripts\local\Test-LocalDataRestore.ps1
```

测试使用系统临时目录和伪MySQL、Redis客户端，覆盖：

- Unicode和空格路径；
- 联合备份与正式manifest发布；
- 密码不进入manifest；
- 重复run、目录重叠和残留`.part`被拒绝；
- ValidateOnly无外部修改；
- 新隔离数据库与Redis RDB校验流程；
- 活动上传目录恢复及quarantine保留；
- 宽目录、目录不匹配及恢复工作区重叠被拒绝；
- 跨库 SQL、空数据库清单、路径穿越和备份产物篡改被拒绝。
- 同名不同Host账号、已有权限/角色账号和非基础表数据库被拒绝。

模拟测试不等于真实恢复。本机具备专用MySQL、Redis和相应客户端后，还必须完成一次真实“上传 → 停写 → 联合备份 → 人工损坏 → 隔离恢复 → 下载摘要验证”。

## 9. 停止条件

遇到以下情况立即停止：

- 后端未停止或上传目录存在`.part`；
- 下载增量或同步批次未排空；
- Redis为共享实例或无法控制其隔离恢复环境；
- 缺少`redis-cli`、`redis-check-rdb`等工具；
- 需要移动存量上传目录但尚未迁移数据库绝对路径；
- 恢复数据库已经存在；
- MySQL导入账号不是实际认证账号、并非纯`USAGE`，或临时目标库权限无法撤销；
- 源数据库包含视图、触发器、存储例程或事件；
- 路径重叠、指向卷根、包含ReparsePoint或空间不足；
- 任一摘要、文件数量、数据库行数或恢复对账不一致。

脚本不会自动删除旧备份、quarantine或失败的恢复工作区，需在确认不再用于审计和回退后人工处理。
