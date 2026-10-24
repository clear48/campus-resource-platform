#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $UploadPath,
    [Parameter(Mandatory)] [string] $BackupRoot,
    [string] $RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $DatabaseName = 'campus_resource_platform',
    [string] $MySqlHost = '127.0.0.1',
    [ValidateRange(1, 65535)] [int] $MySqlPort = 3306,
    [string] $MySqlUsername = 'root',
    [string] $MySqlExecutable = 'mysql',
    [string[]] $MySqlPrefixArguments = @(),
    [string] $MySqlDumpExecutable = 'mysqldump',
    [string[]] $MySqlDumpPrefixArguments = @(),
    [string] $RedisHost = '127.0.0.1',
    [ValidateRange(1, 65535)] [int] $RedisPort = 6379,
    [ValidateRange(0, 15)] [int] $RedisDatabase = 0,
    [string] $RedisCliExecutable = 'redis-cli',
    [string[]] $RedisCliPrefixArguments = @(),
    [string] $BackendHost = '127.0.0.1',
    [ValidateRange(1, 65535)] [int] $BackendPort = 8080,
    [string] $RunId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'),
    [switch] $AcknowledgeBackendStopped
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalDataProtection.Common.ps1')

function Get-MySqlEnvironment {
    $environment = @{}
    if (-not [string]::IsNullOrEmpty($env:CRP_BACKUP_MYSQL_PASSWORD)) {
        $environment.MYSQL_PWD = $env:CRP_BACKUP_MYSQL_PASSWORD
    }
    return $environment
}

function Get-RedisEnvironment {
    $environment = @{}
    if (-not [string]::IsNullOrEmpty($env:CRP_BACKUP_REDIS_PASSWORD)) {
        $environment.REDISCLI_AUTH = $env:CRP_BACKUP_REDIS_PASSWORD
    }
    return $environment
}

function Invoke-MySqlQuery {
    param([Parameter(Mandatory)] [string] $Query)

    $arguments = @(
        "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUsername",
        '--batch', '--skip-column-names', "--database=$DatabaseName", "--execute=$Query"
    )
    return Invoke-CrpProcess -FilePath $MySqlExecutable -PrefixArguments $MySqlPrefixArguments `
        -Arguments $arguments -Environment (Get-MySqlEnvironment) -Stage 'MySQL 查询'
}

function Get-DatabaseInventory {
    $databaseLiteral = $DatabaseName.Replace("'", "''")
    $tableResult = Invoke-MySqlQuery -Query "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='$databaseLiteral' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;"
    $tables = @($tableResult.stdout -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($tables.Count -eq 0) {
        throw 'MySQL 表清单为空，拒绝生成不可对账的备份。'
    }
    $inventory = [Collections.Generic.List[object]]::new()
    foreach ($table in $tables) {
        if ($table -notmatch '^[A-Za-z0-9_]+$') {
            throw "MySQL 返回了不安全的表名：$table"
        }
        $countResult = Invoke-MySqlQuery -Query "SELECT COUNT(*) FROM ``$table``;"
        $countText = $countResult.stdout.Trim()
        if ($countText -notmatch '^\d+$') {
            throw "MySQL 表行数格式无效：$table"
        }
        $inventory.Add([pscustomobject][ordered]@{ table = $table; rows = [long]$countText })
    }
    return @($inventory)
}

function Assert-RedisDownloadDeltaDrained {
    $commonArguments = @('-h', $RedisHost, '-p', [string]$RedisPort, '-n', [string]$RedisDatabase, '--raw')
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        $delta = Invoke-CrpProcess -FilePath $RedisCliExecutable -PrefixArguments $RedisCliPrefixArguments `
            -Arguments ($commonArguments + @('HLEN', 'crp:stats:resource:download:delta')) `
            -Environment (Get-RedisEnvironment) -Stage 'Redis 下载增量检查'
        if ($delta.stdout.Trim() -ne '0') {
            throw 'Redis 下载增量尚未排空，拒绝备份。'
        }
        $syncing = Invoke-CrpProcess -FilePath $RedisCliExecutable -PrefixArguments $RedisCliPrefixArguments `
            -Arguments ($commonArguments + @('--scan', '--pattern', 'crp:stats:resource:download:syncing:*')) `
            -Environment (Get-RedisEnvironment) -Stage 'Redis 同步批次检查'
        if (-not [string]::IsNullOrWhiteSpace($syncing.stdout)) {
            throw 'Redis 仍存在下载同步批次，拒绝备份。'
        }
        if ($attempt -eq 0) {
            Start-Sleep -Milliseconds 500
        }
    }
}

if (-not $AcknowledgeBackendStopped) {
    throw '必须显式提供 -AcknowledgeBackendStopped，确认后端和定时任务已经停止。'
}
if ($RunId -notmatch '^\d{8}T\d{6}Z$') {
    throw 'RunId 必须使用 UTC 时间格式 yyyyMMddTHHmmssZ。'
}
if ($DatabaseName -notmatch '^[A-Za-z0-9_]+$') {
    throw 'DatabaseName 只允许字母、数字和下划线。'
}

$uploadRoot = Resolve-CrpExistingDirectory -Path $UploadPath -Label '上传目录'
$backupRootPath = Resolve-CrpExistingDirectory -Path $BackupRoot -Label '备份根目录'
$repositoryRootPath = Resolve-CrpExistingDirectory -Path $RepositoryRoot -Label 'Git 仓库目录'
Assert-CrpNotVolumeRoot -Path $uploadRoot -Label '上传目录'
Assert-CrpNotVolumeRoot -Path $backupRootPath -Label '备份根目录'
Assert-CrpPathsSeparated -First $uploadRoot -FirstLabel '上传目录' -Second $backupRootPath -SecondLabel '备份根目录'
Assert-CrpPathsSeparated -First $backupRootPath -FirstLabel '备份根目录' -Second $repositoryRootPath -SecondLabel 'Git 仓库目录'
Assert-CrpNoReparsePoints -Path $uploadRoot -Label '上传目录' -Recursive
Assert-CrpNoReparsePoints -Path $backupRootPath -Label '备份根目录'

if (Test-CrpTcpPortOpen -HostName $BackendHost -Port $BackendPort) {
    throw "后端端口仍在监听：$BackendHost`:$BackendPort，请先停止后端。"
}
$partialFiles = @(Get-ChildItem -LiteralPath $uploadRoot -File -Recurse -Force -Filter '*.part')
if ($partialFiles.Count -gt 0) {
    throw '上传目录仍有 .part 文件，拒绝生成不一致备份。'
}

$finalDirectory = Join-Path $backupRootPath $RunId
$partialDirectory = "$finalDirectory.partial"
if ((Test-Path -LiteralPath $finalDirectory) -or (Test-Path -LiteralPath $partialDirectory)) {
    throw "备份运行目录已存在，拒绝覆盖：$RunId"
}

$lock = Enter-CrpExclusiveLock -BackupRoot $backupRootPath
try {
    # 后端停写后连续确认下载增量为空，才把后续顺序导出视为同一逻辑恢复点。
    Assert-RedisDownloadDeltaDrained
    $cutAt = (Get-Date).ToUniversalTime().ToString('o')
    $sourceInventory = @(Get-CrpFileInventory -Root $uploadRoot)
    $sourceBytes = [long](($sourceInventory | Measure-Object -Property bytes -Sum).Sum ?? 0)
    $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($backupRootPath))
    if ($drive.AvailableFreeSpace -lt ($sourceBytes + 64MB)) {
        throw '备份盘可用空间不足，至少需要上传文件总量加 64 MiB 安全余量。'
    }

    New-Item -ItemType Directory -Path $partialDirectory -ErrorAction Stop | Out-Null
    $uploadBackupPath = Join-Path $partialDirectory 'uploads'
    New-Item -ItemType Directory -Path $uploadBackupPath -ErrorAction Stop | Out-Null

    $mysqlDumpPath = Join-Path $partialDirectory 'mysql.sql'
    $dumpArguments = @(
        "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUsername",
        '--single-transaction', '--quick', '--routines', '--triggers', '--events', '--hex-blob',
        '--default-character-set=utf8mb4', "--result-file=$mysqlDumpPath", $DatabaseName
    )
    [void](Invoke-CrpProcess -FilePath $MySqlDumpExecutable -PrefixArguments $MySqlDumpPrefixArguments `
        -Arguments $dumpArguments -Environment (Get-MySqlEnvironment) -Stage 'MySQL 导出')
    $databaseInventory = @(Get-DatabaseInventory)

    $redisDumpPath = Join-Path $partialDirectory 'redis.rdb'
    $redisArguments = @('-h', $RedisHost, '-p', [string]$RedisPort, '-n', [string]$RedisDatabase, '--rdb', $redisDumpPath)
    [void](Invoke-CrpProcess -FilePath $RedisCliExecutable -PrefixArguments $RedisCliPrefixArguments `
        -Arguments $redisArguments -Environment (Get-RedisEnvironment) -Stage 'Redis RDB 导出')

    foreach ($child in Get-ChildItem -LiteralPath $uploadRoot -Force) {
        Copy-Item -LiteralPath $child.FullName -Destination $uploadBackupPath -Recurse -ErrorAction Stop
    }
    $backupInventory = @(Get-CrpFileInventory -Root $uploadBackupPath)
    Assert-CrpInventoryMatches -Expected $sourceInventory -Actual $backupInventory -Label '上传文件备份'

    $mysqlVersion = (Invoke-CrpProcess -FilePath $MySqlDumpExecutable -PrefixArguments $MySqlDumpPrefixArguments `
        -Arguments @('--version') -Environment (Get-MySqlEnvironment) -Stage 'mysqldump 版本读取').stdout.Trim()
    $redisVersion = (Invoke-CrpProcess -FilePath $RedisCliExecutable -PrefixArguments $RedisCliPrefixArguments `
        -Arguments @('--version') -Environment (Get-RedisEnvironment) -Stage 'redis-cli 版本读取').stdout.Trim()
    $gitCommit = (Invoke-CrpProcess -FilePath 'git' -Arguments @('-C', $repositoryRootPath, 'rev-parse', 'HEAD') `
        -Stage 'Git revision 读取').stdout.Trim()

    $artifacts = @(
        Get-CrpArtifactDescriptor -Path $mysqlDumpPath -Name 'mysql.sql'
        Get-CrpArtifactDescriptor -Path $redisDumpPath -Name 'redis.rdb'
    )
    $manifest = [ordered]@{
        schemaVersion = 1
        status        = 'PASSED'
        runId         = $RunId
        cutAt         = $cutAt
        finishedAt    = (Get-Date).ToUniversalTime().ToString('o')
        gitCommit     = $gitCommit
        source        = [ordered]@{
            uploadRoot = $uploadRoot
            database   = $DatabaseName
            redisHost  = $RedisHost
            redisPort  = $RedisPort
            redisDb    = $RedisDatabase
        }
        tools         = [ordered]@{ mysqldump = $mysqlVersion; redisCli = $redisVersion }
        totals        = [ordered]@{ uploadFiles = $sourceInventory.Count; uploadBytes = $sourceBytes }
        database      = @($databaseInventory)
        artifacts     = @($artifacts)
        uploads       = @($sourceInventory)
        integrityNote = 'SHA-256 用于检测意外损坏，不提供独立来源认证。'
    }
    $manifestPath = Join-Path $partialDirectory 'manifest.json'
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM -NoNewline
    $manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$manifestHash  manifest.json`n" | Set-Content -LiteralPath (Join-Path $partialDirectory 'manifest.json.sha256') -Encoding ascii -NoNewline

    Move-Item -LiteralPath $partialDirectory -Destination $finalDirectory -ErrorAction Stop
    Write-Host "本地联合备份完成：$finalDirectory"
    Write-Host '下一步必须执行恢复校验；本脚本不会自动清理历史备份。'
}
finally {
    $lock.Dispose()
}

