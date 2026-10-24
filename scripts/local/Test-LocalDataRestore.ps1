#requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptRoot 'LocalDataProtection.Common.ps1')
$backupScript = Join-Path $scriptRoot 'Backup-LocalData.ps1'
$restoreScript = Join-Path $scriptRoot 'Restore-LocalData.ps1'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $scriptRoot '../..'))
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("crp-local-restore-test-{0}" -f [guid]::NewGuid().ToString('N'))
$uploadRoot = Join-Path $testRoot 'active uploads'
$backupRoot = Join-Path $testRoot 'backups'
$fakeRoot = Join-Path $testRoot 'fake-tools'
$workspaceParent = Join-Path $testRoot 'restore-workspaces'
$oldMySqlPassword = $env:CRP_BACKUP_MYSQL_PASSWORD
$oldRedisPassword = $env:CRP_BACKUP_REDIS_PASSWORD
$oldImportPassword = $env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD
$oldImportGrants = $env:CRP_TEST_IMPORT_GRANTS

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw "断言失败：$Message" }
}

function Assert-Throws {
    param([scriptblock] $Action, [string] $ExpectedText)
    try {
        & $Action
        throw "断言失败：预期异常未发生：$ExpectedText"
    }
    catch {
        if ($_.Exception.Message -notlike "*$ExpectedText*") {
            throw "断言失败：异常不包含 '$ExpectedText'，实际为 '$($_.Exception.Message)'"
        }
    }
}

function Write-TestManifest {
    param(
        [Parameter(Mandatory)] [object] $Manifest,
        [Parameter(Mandatory)] [string] $ManifestPath
    )

    $Manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ManifestPath -Encoding utf8NoBOM -NoNewline
    $manifestHash = (Get-FileHash -LiteralPath $ManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$manifestHash  manifest.json`n" | Set-Content -LiteralPath "$ManifestPath.sha256" -Encoding ascii -NoNewline
}

try {
    New-Item -ItemType Directory -Path $uploadRoot, $backupRoot, $fakeRoot, $workspaceParent -Force | Out-Null
    'original-content' | Set-Content -LiteralPath (Join-Path $uploadRoot '资料.txt') -Encoding utf8NoBOM -NoNewline

    $fakeMySqlDump = Join-Path $fakeRoot 'fake-mysqldump.ps1'
    @'
if ($args -contains '--version') { 'mysqldump fake 1.0'; exit 0 }
if (-not ($args | Where-Object { $_ -like '--result-file=*' })) { exit 11 }
'CREATE TABLE file_info(id BIGINT);' | Set-Content -LiteralPath $env:CRP_TEST_DUMP_PATH -Encoding utf8NoBOM -NoNewline
'@ | Set-Content -LiteralPath $fakeMySqlDump -Encoding utf8NoBOM

    $fakeMySql = Join-Path $fakeRoot 'fake-mysql.ps1'
    @'
$query = ($args | Where-Object { $_ -like '--execute=*' } | Select-Object -First 1)
if ($query -like '*information_schema.triggers*') { '0'; exit 0 }
if ($query -like '*information_schema.schemata*') { exit 0 }
if ($query -like '*CURRENT_USER*') { 'crp_restore@%'; exit 0 }
if ($query -like '*SHOW GRANTS*') { $env:CRP_TEST_IMPORT_GRANTS ?? 'GRANT USAGE ON *.* TO `crp_restore`@`%`'; exit 0 }
if ($query -like '*information_schema.tables*') { "file_info`nresource"; exit 0 }
if ($query -like '*COUNT(*)*file_info*') { '2'; exit 0 }
if ($query -like '*COUNT(*)*resource*') { '3'; exit 0 }
if ($query -like '*CREATE DATABASE*' -or $query -like '*GRANT SELECT*' -or $query -like '*REVOKE ALL*') { exit 0 }
if (-not $query) { [void][Console]::In.ReadToEnd(); exit 0 }
exit 12
'@ | Set-Content -LiteralPath $fakeMySql -Encoding utf8NoBOM

    $fakeRedis = Join-Path $fakeRoot 'fake-redis.ps1'
    @'
if ($args -contains '--version') { 'redis-cli fake 1.0'; exit 0 }
if ($args -contains 'HLEN') { '0'; exit 0 }
if ($args -contains '--scan') { exit 0 }
if ($args -contains '--rdb') { [IO.File]::WriteAllBytes($env:CRP_TEST_RDB_PATH, [byte[]](82,69,68,73,83,48,48,49)); exit 0 }
exit 13
'@ | Set-Content -LiteralPath $fakeRedis -Encoding utf8NoBOM

    $fakeRedisCheck = Join-Path $fakeRoot 'fake-redis-check.ps1'
    @'
$path = $args | Select-Object -Last 1
$bytes = [IO.File]::ReadAllBytes($path)
if ($bytes.Length -lt 5 -or [Text.Encoding]::ASCII.GetString($bytes, 0, 5) -ne 'REDIS') { exit 21 }
'RDB looks OK'
'@ | Set-Content -LiteralPath $fakeRedisCheck -Encoding utf8NoBOM

    $env:CRP_BACKUP_MYSQL_PASSWORD = 'restore-test-mysql-secret'
    $env:CRP_BACKUP_REDIS_PASSWORD = 'restore-test-redis-secret'
    $env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD = 'restore-test-import-secret'
    $runId = '20261024T130000Z'
    $env:CRP_TEST_DUMP_PATH = Join-Path (Join-Path $backupRoot "$runId.partial") 'mysql.sql'
    $env:CRP_TEST_RDB_PATH = Join-Path (Join-Path $backupRoot "$runId.partial") 'redis.rdb'
    & $backupScript -UploadPath $uploadRoot -BackupRoot $backupRoot -RepositoryRoot $repositoryRoot `
        -RunId $runId -AcknowledgeBackendStopped -BackendPort 65534 `
        -MySqlExecutable 'pwsh' -MySqlPrefixArguments @('-NoProfile', '-File', $fakeMySql) `
        -MySqlDumpExecutable 'pwsh' -MySqlDumpPrefixArguments @('-NoProfile', '-File', $fakeMySqlDump) `
        -RedisCliExecutable 'pwsh' -RedisCliPrefixArguments @('-NoProfile', '-File', $fakeRedis)

    $backupDirectory = Join-Path $backupRoot $runId
    & $restoreScript -BackupDirectory $backupDirectory -Mode ValidateOnly

    $preflightWorkspace = Join-Path $workspaceParent 'preflight-must-not-exist'
    Assert-Throws -ExpectedText 'AcknowledgeUploadSwitch' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace $preflightWorkspace `
            -MySqlImportUsername 'crp_restore' -ActivateUploadDirectory -AcknowledgeBackendStopped
    }
    Assert-True (-not (Test-Path -LiteralPath $preflightWorkspace)) '缺少切换确认时不得创建恢复工作区'

    $wrongPathWorkspace = Join-Path $workspaceParent 'wrong-path-must-not-exist'
    Assert-Throws -ExpectedText 'ExpectedUploadPath 与 manifest' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace $wrongPathWorkspace `
            -MySqlImportUsername 'crp_restore' -ActivateUploadDirectory `
            -ExpectedUploadPath (Join-Path $testRoot 'wrong uploads') `
            -AcknowledgeBackendStopped -AcknowledgeUploadSwitch
    }
    Assert-True (-not (Test-Path -LiteralPath $wrongPathWorkspace)) '上传目录不匹配时不得创建恢复工作区'

    $overlapWorkspace = Join-Path $uploadRoot 'restore-workspace'
    Assert-Throws -ExpectedText '互为父子目录' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace $overlapWorkspace `
            -MySqlImportUsername 'crp_restore' -ActivateUploadDirectory -ExpectedUploadPath $uploadRoot `
            -AcknowledgeBackendStopped -AcknowledgeUploadSwitch
    }
    Assert-True (-not (Test-Path -LiteralPath $overlapWorkspace)) '工作区与活动目录重叠时不得创建目录'

    Assert-Throws -ExpectedText '实际认证账号与声明不一致' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace (Join-Path $workspaceParent 'wrong-account') `
            -RestoreDatabase 'campus_resource_platform_restore_wrong_account' `
            -MySqlImportUsername 'crp_restore' -MySqlImportAccountHost 'localhost' `
            -MySqlExecutable 'pwsh' -MySqlPrefixArguments @('-NoProfile', '-File', $fakeMySql) `
            -AcknowledgeBackendStopped
    }

    $env:CRP_TEST_IMPORT_GRANTS = 'GRANT SELECT ON `campus_resource_platform`.* TO `crp_restore`@`%`'
    Assert-Throws -ExpectedText '必须仅有 USAGE' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace (Join-Path $workspaceParent 'privileged-account') `
            -RestoreDatabase 'campus_resource_platform_restore_privileged_account' `
            -MySqlImportUsername 'crp_restore' `
            -MySqlExecutable 'pwsh' -MySqlPrefixArguments @('-NoProfile', '-File', $fakeMySql) `
            -AcknowledgeBackendStopped
    }
    Remove-Item Env:CRP_TEST_IMPORT_GRANTS -ErrorAction SilentlyContinue

    'damaged-content' | Set-Content -LiteralPath (Join-Path $uploadRoot '资料.txt') -Encoding utf8NoBOM -NoNewline
    $workspace = Join-Path $workspaceParent 'run-1'
    & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
        -RepositoryRoot $repositoryRoot -RestoreWorkspace $workspace `
        -RestoreDatabase 'campus_resource_platform_restore_20261024T130000' `
        -MySqlImportUsername 'crp_restore' `
        -MySqlExecutable 'pwsh' -MySqlPrefixArguments @('-NoProfile', '-File', $fakeMySql) `
        -RedisCheckExecutable 'pwsh' -RedisCheckPrefixArguments @('-NoProfile', '-File', $fakeRedisCheck) `
        -ActivateUploadDirectory -ExpectedUploadPath $uploadRoot -AcknowledgeBackendStopped -AcknowledgeUploadSwitch

    Assert-True ((Get-Content -LiteralPath (Join-Path $uploadRoot '资料.txt') -Raw) -eq 'original-content') '活动上传文件应恢复为备份内容'
    $quarantinePath = Join-Path (Join-Path $testRoot 'quarantine') "$runId-original"
    Assert-True ((Get-Content -LiteralPath (Join-Path $quarantinePath '资料.txt') -Raw) -eq 'damaged-content') '损坏目录应保留在 quarantine'
    Assert-True (Test-Path -LiteralPath (Join-Path $workspace 'redis/dump.rdb') -PathType Leaf) '隔离 Redis RDB 应存在'

    Assert-Throws -ExpectedText 'RestoreWorkspace 必须是尚不存在' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace $workspace `
            -MySqlImportUsername 'crp_restore' -AcknowledgeBackendStopped
    }

    Add-Content -LiteralPath (Join-Path $backupDirectory 'mysql.sql') -Value '--tampered'
    Assert-Throws -ExpectedText '备份产物校验失败' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode ValidateOnly
    }

    'CREATE TABLE file_info(id BIGINT);' | Set-Content -LiteralPath (Join-Path $backupDirectory 'mysql.sql') -Encoding utf8NoBOM -NoNewline
    $manifestPath = Join-Path $backupDirectory 'manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

    'USE campus_resource_platform;' | Set-Content -LiteralPath (Join-Path $backupDirectory 'mysql.sql') -Encoding utf8NoBOM -NoNewline
    $mysqlArtifact = $manifest.artifacts | Where-Object { $_.name -eq 'mysql.sql' }
    $mysqlArtifact.bytes = (Get-Item -LiteralPath (Join-Path $backupDirectory 'mysql.sql')).Length
    $mysqlArtifact.sha256 = (Get-FileHash -LiteralPath (Join-Path $backupDirectory 'mysql.sql') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-TestManifest -Manifest $manifest -ManifestPath $manifestPath
    Assert-Throws -ExpectedText '跨数据库或数据库级语句' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode Restore -BackupRoot $backupRoot `
            -RepositoryRoot $repositoryRoot -RestoreWorkspace (Join-Path $workspaceParent 'unsafe-sql') `
            -RestoreDatabase 'campus_resource_platform_restore_unsafe_sql' `
            -MySqlImportUsername 'crp_restore' -AcknowledgeBackendStopped
    }

    'CREATE TABLE file_info(id BIGINT);' | Set-Content -LiteralPath (Join-Path $backupDirectory 'mysql.sql') -Encoding utf8NoBOM -NoNewline
    $mysqlArtifact.bytes = (Get-Item -LiteralPath (Join-Path $backupDirectory 'mysql.sql')).Length
    $mysqlArtifact.sha256 = (Get-FileHash -LiteralPath (Join-Path $backupDirectory 'mysql.sql') -Algorithm SHA256).Hash.ToLowerInvariant()
    $databaseInventory = @($manifest.database)
    $manifest.database = @()
    Write-TestManifest -Manifest $manifest -ManifestPath $manifestPath
    Assert-Throws -ExpectedText '数据库清单为空' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode ValidateOnly
    }

    $manifest.database = $databaseInventory
    $manifest.uploads[0].path = '../escape.txt'
    Write-TestManifest -Manifest $manifest -ManifestPath $manifestPath
    Assert-Throws -ExpectedText '非法相对路径' -Action {
        & $restoreScript -BackupDirectory $backupDirectory -Mode ValidateOnly
    }

    Write-Host '本地恢复专项测试通过：14/14'
}
finally {
    $env:CRP_BACKUP_MYSQL_PASSWORD = $oldMySqlPassword
    $env:CRP_BACKUP_REDIS_PASSWORD = $oldRedisPassword
    $env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD = $oldImportPassword
    $env:CRP_TEST_IMPORT_GRANTS = $oldImportGrants
    Remove-Item Env:CRP_TEST_DUMP_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:CRP_TEST_RDB_PATH -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        $resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not (Test-CrpPathInsideOrEqual -Candidate $resolvedTestRoot -Container $resolvedTempRoot)) {
            throw '拒绝清理系统临时目录外的测试目录。'
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
