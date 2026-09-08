# 批量测试数据脚本

## 用途与范围

`scripts/seed-homework-test-data.ps1` 从 `C:\Users\Lenovo\Desktop\HomeWork` 筛选 30～40 个真实文件，通过现有 HTTP API 创建用户、上传文件、创建资料并生成审核与公开行为数据。脚本不直接写 MySQL 或 Redis，因此会经过项目现有的身份校验、文件授权、事务、审核状态机、下载票据和缓存更新逻辑。

默认目标为 36 份资料，最终状态是：通过 24、待审核 5、拒绝 4、下架 3。通过资料还会生成 18 次收藏、6 次完整文件下载、约 12 次搜索，并触发一次管理员总榜重建。搜索请求在网络响应不确定时可能安全重试，因此热词增量允许略高于 12。

## 前置条件

- 使用 PowerShell 7（`pwsh`）。
- 后端已经启动，默认地址为 `http://127.0.0.1:8080/api/v1`。
- 当前后端连接的 MySQL schema、Redis DB 和上传目录均允许留下测试数据。
- 当前数据库已有管理员账号和至少一个启用分类。
- 将账号密码写入当前终端的环境变量。脚本和运行清单不会保存密码或 Token。

```powershell
$env:CRP_SEED_ADMIN_USERNAME = '<测试库管理员账号>'
$env:CRP_SEED_ADMIN_PASSWORD = '<测试库管理员密码>'
$env:CRP_SEED_USER_PASSWORD = '<本批次普通用户统一密码>'
```

## 执行流程

先预演文件选择。预演不会调用写接口，也不会修改数据库、Redis 或上传目录。

```powershell
pwsh -File .\scripts\seed-homework-test-data.ps1 `
  -Mode Preview `
  -TargetCount 36 `
  -RunId seed-20260908-a
```

确认当前 API 确实连接测试数据库后执行写入。确认开关是必填项，避免误向其他环境造数。

```powershell
pwsh -File .\scripts\seed-homework-test-data.ps1 `
  -Mode Seed `
  -TargetCount 36 `
  -RunId seed-20260908-a `
  -AcknowledgeCurrentDatabaseAsTest
```

如果执行中断，使用完全相同的 `RunId`、API 地址和数量恢复。脚本会读取 manifest、先做授权感知的 MD5 预检，并按确定性标题恢复已有资料，不会直接重发结果不确定的上传请求。

```powershell
pwsh -File .\scripts\seed-homework-test-data.ps1 `
  -Mode Resume `
  -TargetCount 36 `
  -RunId seed-20260908-a `
  -AcknowledgeCurrentDatabaseAsTest
```

仅核验该批次时使用 `Verify`。它会登录批次用户、核对 36 个 `fileId`、`resourceId` 和最终状态，并确认公开搜索结果只包含通过资料。

```powershell
pwsh -File .\scripts\seed-homework-test-data.ps1 `
  -Mode Verify `
  -TargetCount 36 `
  -RunId seed-20260908-a `
  -AcknowledgeCurrentDatabaseAsTest
```

运行清单位于 `campus-resource-platform/data/seed-runs/<RunId>/manifest.json`，该目录已被 Git 忽略。清单只记录相对路径、MD5、文件大小、账号序号、文件 ID、资料 ID、目标状态和执行阶段；不会记录源目录绝对路径、密码或 Token。

## 文件筛选规则

脚本只选择后端白名单中的非空文件，单文件不超过 50 MiB，并按 `MD5 + 文件大小` 保证内容唯一。它会拒绝 reparse point，确认最终路径仍在来源根目录内，并排除 `.git`、`node_modules`、虚拟环境、依赖包、构建产物、编辑器目录及文件名疑似包含密钥或凭据的内容。

压缩包默认不参与筛选。确实需要覆盖 `zip`、`rar`、`7z` 上传时可显式传入 `-IncludeArchives`，使用前应先确认压缩包不含凭据、个人信息或无关依赖。

## 数据影响与验收

一次默认运行会持久化以下测试数据：

- 4 个普通测试用户；
- 36 条资料及对应文件授权，物理文件可能因全局 MD5 去重而复用；
- 34 条审核流水：24 次通过、4 次拒绝、3 组“通过后下架”；
- 18 条有效收藏、6 条下载记录，以及搜索热词和热度排行数据；
- 上传目录中的实际文件和运行目录中的 manifest、下载校验副本。

成功输出应显示 `ResourceCount=36`、`Approved=24`、`PendingReview=5`、`Rejected=4`、`Offline=3`、`PublicSearch=24` 和 `BehaviorsDone=True`。再次用相同 `RunId` 执行 `Resume` 时，不应新增资料或再次执行公开行为。

项目当前没有能够安全联动删除资料、文件授权、引用计数、物理文件和 Redis 数据的完整清理 API，因此脚本不提供自动清理。需要清理某批数据时，应先依据 manifest 制定单独的数据库、文件和 Redis 一致性方案。
