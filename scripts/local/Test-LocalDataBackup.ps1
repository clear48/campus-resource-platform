#requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backupScript = Join-Path $scriptRoot 'Backup-LocalData.ps1'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $scriptRoot '../..'))
. (Join-Path $scriptRoot 'LocalDataProtection.Common.ps1')
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("crp-local-backup-test-{0}" -f [guid]::NewGuid().ToString('N'))
$uploadRoot = Join-Path $testRoot 'uploads with space'
$backupRoot = Join-Path $testRoot 'backups'
$fakeRoot = Join-Path $testRoot 'fake-tools'
$oldMySqlPassword = $env:CRP_BACKUP_MYSQL_PASSWORD
$oldRedisPassword = $env:CRP_BACKUP_REDIS_PASSWORD

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

try {
    New-Item -ItemType Directory -Path $uploadRoot, $backupRoot, $fakeRoot -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $uploadRoot '课程资料') | Out-Null
    'alpha' | Set-Content -LiteralPath (Join-Path $uploadRoot '课程资料/讲义.txt') -Encoding utf8NoBOM -NoNewline
    [IO.File]::WriteAllBytes((Join-Path $uploadRoot 'sample.bin'), [byte[]](0, 1, 2, 3, 255))

    $fakeMySqlDump = Join-Path $fakeRoot 'fake-mysqldump.ps1'
    @'
if ($args -contains '--version') { 'mysqldump fake 1.0'; exit 0 }
$resultArgument = $args | Where-Object { $_ -like '--result-file=*' } | Select-Object -First 1
if (-not $resultArgument) { exit 11 }
'CREATE TABLE file_info(id BIGINT);' | Set-Content -LiteralPath $env:CRP_TEST_DUMP_PATH -Encoding utf8NoBOM -NoNewline
'@ | Set-Content -LiteralPath $fakeMySqlDump -Encoding utf8NoBOM

    $fakeMySql = Join-Path $fakeRoot 'fake-mysql.ps1'
    @'
$query = ($args | Where-Object { $_ -like '--execute=*' } | Select-Object -First 1)
if ($query -like '*information_schema.tables*') { "file_info`nresource"; exit 0 }
if ($query -like '*file_info*') { '2'; exit 0 }
if ($query -like '*resource*') { '3'; exit 0 }
exit 12
'@ | Set-Content -LiteralPath $fakeMySql -Encoding utf8NoBOM

    $fakeRedis = Join-Path $fakeRoot 'fake-redis.ps1'
    @'
if ($args -contains '--version') { 'redis-cli fake 1.0'; exit 0 }
if ($args -contains 'HLEN') { '0'; exit 0 }
if ($args -contains '--scan') { exit 0 }
$rdbIndex = [Array]::IndexOf($args, '--rdb')
if ($rdbIndex -ge 0) {
    [IO.File]::WriteAllBytes($env:CRP_TEST_RDB_PATH, [byte[]](82, 69, 68, 73, 83, 48, 48, 49))
    exit 0
}
exit 13
'@ | Set-Content -LiteralPath $fakeRedis -Encoding utf8NoBOM

    $env:CRP_BACKUP_MYSQL_PASSWORD = 'unit-test-mysql-secret'
    $env:CRP_BACKUP_REDIS_PASSWORD = 'unit-test-redis-secret'
    $runId = '20261024T120000Z'
    $env:CRP_TEST_DUMP_PATH = Join-Path (Join-Path $backupRoot "$runId.partial") 'mysql.sql'
    $env:CRP_TEST_RDB_PATH = Join-Path (Join-Path $backupRoot "$runId.partial") 'redis.rdb'
    $commonArguments = @{
        UploadPath                  = $uploadRoot
        BackupRoot                  = $backupRoot
        RepositoryRoot              = $repositoryRoot
        RunId                       = $runId
        AcknowledgeBackendStopped    = $true
        BackendPort                  = 65534
        MySqlExecutable              = 'pwsh'
        MySqlPrefixArguments         = @('-NoProfile', '-File', $fakeMySql)
        MySqlDumpExecutable          = 'pwsh'
        MySqlDumpPrefixArguments     = @('-NoProfile', '-File', $fakeMySqlDump)
        RedisCliExecutable           = 'pwsh'
        RedisCliPrefixArguments      = @('-NoProfile', '-File', $fakeRedis)
    }

    & $backupScript @commonArguments
    $runDirectory = Join-Path $backupRoot $runId
    Assert-True (Test-Path -LiteralPath (Join-Path $runDirectory 'manifest.json') -PathType Leaf) '应发布正式 manifest'
    Assert-True (-not (Test-Path -LiteralPath "$runDirectory.partial")) '成功后不应保留 partial 目录'
    $manifestText = Get-Content -LiteralPath (Join-Path $runDirectory 'manifest.json') -Raw
    Assert-True (-not $manifestText.Contains('unit-test-mysql-secret')) 'manifest 不得包含 MySQL 密码'
    Assert-True (-not $manifestText.Contains('unit-test-redis-secret')) 'manifest 不得包含 Redis 密码'
    $manifest = $manifestText | ConvertFrom-Json
    Assert-True ($manifest.status -eq 'PASSED') 'manifest 状态应为 PASSED'
    Assert-True ([int]$manifest.totals.uploadFiles -eq 2) '应备份两个上传文件'

    Assert-Throws -ExpectedText '备份运行目录已存在' -Action { & $backupScript @commonArguments }
    $nestedBackupRoot = Join-Path $uploadRoot 'nested-backup'
    New-Item -ItemType Directory -Path $nestedBackupRoot | Out-Null
    Assert-Throws -ExpectedText '互为父子目录' -Action {
        & $backupScript @commonArguments -BackupRoot $nestedBackupRoot -RunId '20261024T120001Z'
    }
    'partial' | Set-Content -LiteralPath (Join-Path $uploadRoot 'stale.part') -Encoding utf8NoBOM
    Assert-Throws -ExpectedText '.part 文件' -Action {
        & $backupScript @commonArguments -RunId '20261024T120002Z'
    }
    Remove-Item -LiteralPath (Join-Path $uploadRoot 'stale.part') -Force

    Assert-Throws -ExpectedText '不得等于或包含 Git 仓库目录' -Action {
        & $backupScript @commonArguments -UploadPath $repositoryRoot -RunId '20261024T120003Z'
    }

    Write-Host '本地备份专项测试通过：7/7'
}
finally {
    $env:CRP_BACKUP_MYSQL_PASSWORD = $oldMySqlPassword
    $env:CRP_BACKUP_REDIS_PASSWORD = $oldRedisPassword
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

