# 在线单文件保护与恢复

## 1. 目标与边界

本模块解决本地开发环境中“某一个不可变上传文件被误删”后的自动发现和在线恢复问题。后端保持运行，只有受影响文件暂时返回 HTTP 503；其他文件、接口和定时任务继续服务。

本模块不替代 `LOCAL-DATA-PROTECTION.md` 中 MySQL、Redis、上传目录的停机联合备份。在线副本用于单文件快速恢复，联合备份用于磁盘损坏、数据库丢失、批量误删等灾难恢复。

## 2. 数据与存储布局

- 活动文件目录：`app.upload.storage-path`。
- 在线副本目录：`app.file-protection.backup-path`，不得与活动目录互为父子目录，实际部署应优先放在另一块物理磁盘。
- 活动目录必须包含 `.crp-storage-id`，副本目录必须包含 `.crp-backup-id`；内容分别与配置的 `storage-id`、`backup-id` 完全一致。
- `file_backup_record` 保存每个正常本地文件的副本路径、大小、MD5、SHA-256 和状态。
- `file_integrity_event` 保存缺失发现、确认、恢复、失败和熔断审计。

副本按 SHA-256 内容寻址：`<backup-root>/<前两位>/<sha256>`。创建过程使用临时 `.part` 文件，复制时同时计算大小、MD5、SHA-256，全部匹配后才发布为只读副本。

## 3. 自动流程

```text
FileBackupTask（默认每 30 秒）
  → 扫描没有 READY 副本的正常本地文件
  → 获取 crp:lock:file:protection:{fileId}
  → 创建并校验内容寻址副本
  → 写 file_backup_record=READY

FileIntegrityTask（默认每 10 秒）
  → 游标扫描正常本地文件
  → 先收集本轮全部缺失项
  → 缺失数 >= 2 时整轮熔断，不写活动目录
  → 单文件第一次缺失：记录 DETECTED
  → 下一轮仍缺失：获取单文件锁并二次读库确认
  → 校验 READY 副本大小、MD5、SHA-256
  → 复制到活动目录同级临时文件并 fsync
  → 无覆盖原子移动到原路径
  → 再次校验，事件记为 RECOVERED，清理 MD5 缓存
```

两轮确认避免把短暂可见性故障当成误删；批量熔断避免盘符掉线、挂载错误时把大量副本错误写入错误磁盘。恢复只允许目标不存在；若目标在竞争期间重新出现，健康文件保留，异常文件不覆盖。

## 4. 业务可用性门禁

保护功能开启时，以下入口先调用 `FileAvailabilityService.requireAvailable`：

- 上传命中既有物理文件后的秒传授权；
- 创建下载记录和一次性下载票据；
- 真正读取下载文件；
- 管理员读取待审核文件。

文件缺失、大小异常、路径不安全或根目录身份不匹配时返回业务码 `50301`、HTTP 503。下载取流在确认文件健康后才消费一次性票据，因此恢复窗口不会白白作废票据。保护功能默认关闭，关闭时保持原有业务行为。

## 5. 并发、事务与失败策略

- Redis Key：`crp:lock:file:protection:{fileId}`，Redisson `RLock`，不设置 TTL，由看门狗续期；Redis 故障时失败关闭，不并发恢复。
- 文件复制和摘要计算不放进数据库长事务。
- 数据库记录是任务重放依据；任务重启后继续扫描 `PENDING`、`FAILED` 或未完成事件。
- 单文件最多按 `max-recovery-attempts` 重试，超过上限保留失败事件等待人工处理。
- 发布后校验失败时尽力把目标移动到 `.restore-failed-*` 隔离文件，不把未验证内容继续暴露给业务。
- 批量缺失、根目录身份异常、副本不存在或摘要不一致只告警和记录，不停止 Spring Boot 进程。

## 6. 本地启用步骤

先执行数据库迁移：

```powershell
mysql -u root -p campus_resource_platform < .\sql\migrations\20261102_file_online_protection.sql
```

在仓库外创建两个独立目录和身份文件，示例：

```powershell
New-Item -ItemType Directory -Force 'C:\campusshare-dev\uploads','D:\campusshare-file-replicas'
Set-Content -NoNewline 'C:\campusshare-dev\uploads\.crp-storage-id' 'crp-dev-storage-v1'
Set-Content -NoNewline 'D:\campusshare-file-replicas\.crp-backup-id' 'crp-dev-backup-v1'

$env:APP_UPLOAD_STORAGE_PATH = 'C:\campusshare-dev\uploads'
$env:FILE_PROTECTION_BACKUP_PATH = 'D:\campusshare-file-replicas'
$env:FILE_PROTECTION_STORAGE_ID = 'crp-dev-storage-v1'
$env:FILE_PROTECTION_BACKUP_ID = 'crp-dev-backup-v1'
$env:FILE_PROTECTION_ENABLED = 'true'
```

存量 `file_info.storage_path` 是绝对路径；如果当前活动目录不同，必须先单独迁移文件和数据库路径，不能只改环境变量。

## 7. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `FILE_PROTECTION_ENABLED` | `false` | 总开关，迁移和演练完成前保持关闭 |
| `FILE_PROTECTION_BACKUP_PATH` | `./data/file-backups` | 在线副本根目录 |
| `FILE_PROTECTION_STORAGE_ID` | 空 | 活动目录身份 |
| `FILE_PROTECTION_BACKUP_ID` | 空 | 副本目录身份 |
| `FILE_PROTECTION_BATCH_SIZE` | `200` | 游标扫描批量 |
| `FILE_PROTECTION_BACKUP_DELAY_MS` | `30000` | 副本任务固定延迟 |
| `FILE_PROTECTION_INTEGRITY_DELAY_MS` | `10000` | 完整性任务固定延迟 |
| `FILE_PROTECTION_MISSING_COUNT_THRESHOLD` | `2` | 同轮缺失达到该值即熔断 |
| `FILE_PROTECTION_MISSING_RATIO_THRESHOLD` | `0.01` | 多文件场景的比例保护阈值 |
| `FILE_PROTECTION_MAX_RECOVERY_ATTEMPTS` | `3` | 单文件最大恢复尝试次数 |

## 8. 验证与演练

自动测试覆盖副本创建、摘要不一致、两轮缺失确认、单文件在线恢复、目标不覆盖、批量缺失熔断、事件闭环、缓存失效和 HTTP 503 映射。

启用前还应执行一次真实演练：上传测试文件，等待 `file_backup_record=READY`，手工删除该文件，确认第一次扫描仅记录事件、第二次扫描恢复文件，下载重新成功，同时后端进程和其他文件下载始终正常。

## 9. 已知边界

- SHA-256 用于识别和校验副本内容，不替代恶意篡改的签名认证。
- 当前按文件大小做常规巡检；恢复前后会计算完整 MD5/SHA-256。若要周期性重算所有大文件摘要，应另做低峰限速任务。
- 在线副本与活动目录在同一物理盘时只能防逻辑误删，不能防整盘损坏。
- 当前没有管理端事件查询接口和通知渠道，事件通过数据库及日志查看。
