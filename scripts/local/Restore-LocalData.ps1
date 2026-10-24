#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $BackupDirectory,
    [ValidateSet('ValidateOnly', 'Restore')] [string] $Mode = 'ValidateOnly',
    [string] $BackupRoot,
    [string] $RepositoryRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $RestoreWorkspace,
    [string] $RestoreDatabase,
    [string] $MySqlHost = '127.0.0.1',
    [ValidateRange(1, 65535)] [int] $MySqlPort = 3306,
    [string] $MySqlUsername = 'root',
    [string] $MySqlExecutable = 'mysql',
    [string[]] $MySqlPrefixArguments = @(),
    [string] $MySqlImportUsername,
    [string] $MySqlImportAccountHost = '%',
    [string] $RedisCheckExecutable = 'redis-check-rdb',
    [string[]] $RedisCheckPrefixArguments = @(),
    [switch] $ActivateUploadDirectory,
    [string] $ExpectedUploadPath,
    [switch] $AcknowledgeBackendStopped,
    [switch] $AcknowledgeUploadSwitch
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

function Get-MySqlImportEnvironment {
    if ([string]::IsNullOrWhiteSpace($env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD)) {
        throw '缺少环境变量 CRP_RESTORE_MYSQL_IMPORT_PASSWORD。'
    }
    return @{ MYSQL_PWD = $env:CRP_RESTORE_MYSQL_IMPORT_PASSWORD }
}

function Invoke-RestoreMySqlQuery {
    param(
        [Parameter(Mandatory)] [string] $Query,
        [string] $Database
    )

    $arguments = @("--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUsername", '--batch', '--skip-column-names')
    if (-not [string]::IsNullOrWhiteSpace($Database)) {
        $arguments += "--database=$Database"
    }
    $arguments += "--execute=$Query"
    return Invoke-CrpProcess -FilePath $MySqlExecutable -PrefixArguments $MySqlPrefixArguments `
        -Arguments $arguments -Environment (Get-MySqlEnvironment) -Stage 'MySQL 恢复查询'
}

function Invoke-ImportMySqlQuery {
    param([Parameter(Mandatory)] [string] $Query)

    $arguments = @(
        "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlImportUsername",
        '--batch', '--skip-column-names', "--execute=$Query"
    )
    return Invoke-CrpProcess -FilePath $MySqlExecutable -PrefixArguments $MySqlPrefixArguments `
        -Arguments $arguments -Environment (Get-MySqlImportEnvironment) -Stage 'MySQL 导入账号自检'
}

function Assert-RestoredDatabaseInventory {
    param([Parameter(Mandatory)] [object[]] $Expected)

    $databaseLiteral = $RestoreDatabase.Replace("'", "''")
    $tableResult = Invoke-RestoreMySqlQuery -Query "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='$databaseLiteral' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;"
    $actualTables = @($tableResult.stdout -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object)
    $expectedTables = @($Expected | ForEach-Object { [string]$_.table } | Sort-Object)
    if (($actualTables -join '|') -cne ($expectedTables -join '|')) {
        throw '恢复数据库表集合与备份清单不一致。'
    }
    foreach ($record in $Expected) {
        $table = [string]$record.table
        if ($table -notmatch '^[A-Za-z0-9_]+$') {
            throw "备份数据库清单包含非法表名：$table"
        }
        $countResult = Invoke-RestoreMySqlQuery -Database $RestoreDatabase -Query "SELECT COUNT(*) FROM ``$table``;"
        $countText = $countResult.stdout.Trim()
        if ($countText -notmatch '^\d+$' -or [long]$countText -ne [long]$record.rows) {
            throw "恢复数据库行数不一致：$table"
        }
    }
}

function Assert-MySqlDumpScoped {
    param(
        [Parameter(Mandatory)] [string] $DumpPath,
        [Parameter(Mandatory)] [string] $SourceDatabase
    )

    $qualifiedSourcePattern = '(?i)`?' + [regex]::Escape($SourceDatabase) + '`?\s*\.'
    foreach ($line in Get-Content -LiteralPath $DumpPath) {
        if ($line -match '(?i)^\s*(CREATE|DROP)\s+DATABASE\b' -or $line -match '(?i)^\s*USE\s+' `
                -or $line -match $qualifiedSourcePattern) {
            throw 'mysql.sql 包含跨数据库或数据库级语句，拒绝使用管理员边界外的备份。'
        }
    }
}

function Copy-UploadTree {
    param(
        [Parameter(Mandatory)] [string] $Source,
        [Parameter(Mandatory)] [string] $Destination
    )

    New-Item -ItemType Directory -Path $Destination -ErrorAction Stop | Out-Null
    foreach ($child in Get-ChildItem -LiteralPath $Source -Force) {
        Copy-Item -LiteralPath $child.FullName -Destination $Destination -Recurse -ErrorAction Stop
    }
}

$validated = Get-CrpValidatedBackupManifest -BackupDirectory $BackupDirectory
$manifest = $validated.Manifest
Write-Host "备份完整性校验通过：$($validated.Path)"
if ($Mode -eq 'ValidateOnly') {
    Write-Host 'ValidateOnly 未修改数据库、Redis或上传目录。'
    exit 0
}

if (-not $AcknowledgeBackendStopped) {
    throw 'Restore 模式必须提供 -AcknowledgeBackendStopped。'
}
if ($ActivateUploadDirectory -and -not $AcknowledgeUploadSwitch) {
    throw '启用 -ActivateUploadDirectory 时必须同时提供 -AcknowledgeUploadSwitch。'
}
if ([string]::IsNullOrWhiteSpace($MySqlImportUsername) -or $MySqlImportUsername -notmatch '^[A-Za-z0-9_]+$' `
        -or $MySqlImportUsername -eq $MySqlUsername) {
    throw 'Restore 模式必须提供与管理员不同的 -MySqlImportUsername。'
}
if ($MySqlImportAccountHost -notmatch '^[A-Za-z0-9_.%:-]+$') {
    throw 'MySqlImportAccountHost 格式无效。'
}
[void](Get-MySqlImportEnvironment)
if ([string]::IsNullOrWhiteSpace($RestoreWorkspace)) {
    throw 'Restore 模式必须提供全新的 -RestoreWorkspace。'
}
if ([string]::IsNullOrWhiteSpace($BackupRoot)) {
    throw 'Restore 模式必须提供 -BackupRoot，用于取得互斥锁和路径边界校验。'
}

$repositoryRootPath = Resolve-CrpExistingDirectory -Path $RepositoryRoot -Label 'Git 仓库目录'
$backupRootPath = Resolve-CrpExistingDirectory -Path $BackupRoot -Label '备份根目录'
if (-not (Test-CrpPathInsideOrEqual -Candidate $validated.Path -Container $backupRootPath)) {
    throw '备份目录不在声明的 BackupRoot 内。'
}
Assert-CrpPathsSeparated -First $backupRootPath -FirstLabel '备份根目录' -Second $repositoryRootPath -SecondLabel 'Git 仓库目录'

$workspacePath = [IO.Path]::GetFullPath($RestoreWorkspace).TrimEnd(
    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
if (Test-Path -LiteralPath $workspacePath) {
    throw 'RestoreWorkspace 必须是尚不存在的全新目录。'
}
$workspaceParent = Split-Path -Parent $workspacePath
$workspaceParentPath = Resolve-CrpExistingDirectory -Path $workspaceParent -Label '恢复工作区父目录'
Assert-CrpNotVolumeRoot -Path $workspacePath -Label '恢复工作区'
Assert-CrpNoReparsePoints -Path $workspaceParentPath -Label '恢复工作区父目录'
Assert-CrpPathsSeparated -First $workspacePath -FirstLabel '恢复工作区' -Second $validated.Path -SecondLabel '备份目录'
Assert-CrpPathsSeparated -First $workspacePath -FirstLabel '恢复工作区' -Second $repositoryRootPath -SecondLabel 'Git 仓库目录'

$sourceDatabase = [string]$manifest.source.database
if ([string]::IsNullOrWhiteSpace($RestoreDatabase)) {
    $RestoreDatabase = "${sourceDatabase}_restore_$([string]$manifest.runId.Replace('Z', ''))"
}
$expectedDatabasePrefix = "${sourceDatabase}_restore_"
if ($RestoreDatabase -eq $sourceDatabase -or -not $RestoreDatabase.StartsWith($expectedDatabasePrefix, [StringComparison]::Ordinal) `
        -or $RestoreDatabase -notmatch '^[A-Za-z0-9_]+$') {
    throw "恢复数据库必须使用全新的隔离库名称：${expectedDatabasePrefix}<标识>"
}

Assert-MySqlDumpScoped -DumpPath (Join-Path $validated.Path 'mysql.sql') -SourceDatabase $sourceDatabase

$activeUploadRoot = $null
$activeParent = $null
$stagePath = $null
$quarantineRoot = $null
$quarantinePath = $null
$failedActivationPath = $null
if ($ActivateUploadDirectory) {
    if ([string]::IsNullOrWhiteSpace($ExpectedUploadPath)) {
        throw '激活上传目录时必须提供 -ExpectedUploadPath。'
    }
    $activeUploadRoot = [IO.Path]::GetFullPath([string]$manifest.source.uploadRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $expectedUploadRoot = [IO.Path]::GetFullPath($ExpectedUploadPath).TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    if (-not [string]::Equals($activeUploadRoot, $expectedUploadRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'ExpectedUploadPath 与 manifest 中的原上传目录不一致。'
    }
    Assert-CrpNotVolumeRoot -Path $activeUploadRoot -Label '活动上传目录'
    Assert-CrpPathDoesNotContainProtectedRoot -Candidate $activeUploadRoot -CandidateLabel '活动上传目录' `
        -ProtectedRoot $repositoryRootPath -ProtectedLabel 'Git 仓库目录'
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        Assert-CrpPathDoesNotContainProtectedRoot -Candidate $activeUploadRoot -CandidateLabel '活动上传目录' `
            -ProtectedRoot $env:USERPROFILE -ProtectedLabel '用户主目录'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
        Assert-CrpPathDoesNotContainProtectedRoot -Candidate $activeUploadRoot -CandidateLabel '活动上传目录' `
            -ProtectedRoot $env:SystemRoot -ProtectedLabel 'Windows 系统目录'
    }
    $activeParent = Resolve-CrpExistingDirectory -Path (Split-Path -Parent $activeUploadRoot) -Label '活动上传目录父目录'
    Assert-CrpNoReparsePoints -Path $activeParent -Label '活动上传目录父目录'
    Assert-CrpPathsSeparated -First $activeUploadRoot -FirstLabel '活动上传目录' -Second $backupRootPath -SecondLabel '备份根目录'
    Assert-CrpPathsSeparated -First $workspacePath -FirstLabel '恢复工作区' -Second $activeUploadRoot -SecondLabel '活动上传目录'

    $stagePath = Join-Path $activeParent ".crp-restore-$($manifest.runId)-stage"
    $quarantineRoot = Join-Path $activeParent 'quarantine'
    $quarantinePath = Join-Path $quarantineRoot "$($manifest.runId)-original"
    $failedActivationPath = Join-Path $activeParent ".crp-restore-$($manifest.runId)-failed"
    foreach ($candidate in @($stagePath, $quarantineRoot, $quarantinePath, $failedActivationPath)) {
        Assert-CrpPathsSeparated -First $workspacePath -FirstLabel '恢复工作区' -Second $candidate -SecondLabel '上传切换目录'
    }
}

$lock = Enter-CrpExclusiveLock -BackupRoot $backupRootPath
try {
    $databaseLiteral = $RestoreDatabase.Replace("'", "''")
    $existingDatabase = (Invoke-RestoreMySqlQuery `
        -Query "SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='$databaseLiteral';").stdout.Trim()
    if (-not [string]::IsNullOrWhiteSpace($existingDatabase)) {
        throw "恢复数据库已存在，拒绝覆盖：$RestoreDatabase"
    }

    $accountLiteral = "'$MySqlImportUsername'@'$MySqlImportAccountHost'"
    $actualAccount = (Invoke-ImportMySqlQuery -Query 'SELECT CURRENT_USER();').stdout.Trim()
    $expectedAccount = "$MySqlImportUsername@$MySqlImportAccountHost"
    if (-not [string]::Equals($actualAccount, $expectedAccount, [StringComparison]::OrdinalIgnoreCase)) {
        throw "MySQL 实际认证账号与声明不一致：expected=$expectedAccount actual=$actualAccount"
    }
    $grants = (Invoke-ImportMySqlQuery -Query 'SHOW GRANTS;').stdout
    foreach ($grant in $grants -split "`r?`n") {
        if ([string]::IsNullOrWhiteSpace($grant)) { continue }
        if ($grant -notmatch '(?i)^GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+') {
            throw 'MySQL 恢复账号必须仅有 USAGE；检测到数据库权限、全局权限、角色或代理授权。'
        }
    }

    New-Item -ItemType Directory -Path $workspacePath -ErrorAction Stop | Out-Null
    $redisWorkspace = Join-Path $workspacePath 'redis'
    New-Item -ItemType Directory -Path $redisWorkspace -ErrorAction Stop | Out-Null
    $restoredRdb = Join-Path $redisWorkspace 'dump.rdb'
    Copy-Item -LiteralPath (Join-Path $validated.Path 'redis.rdb') -Destination $restoredRdb -ErrorAction Stop
    [void](Invoke-CrpProcess -FilePath $RedisCheckExecutable -PrefixArguments $RedisCheckPrefixArguments `
        -Arguments @($restoredRdb) -Stage 'Redis RDB 校验')

    [void](Invoke-RestoreMySqlQuery -Query "CREATE DATABASE ``$RestoreDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
    $importGrantIssued = $false
    $databaseRestoreError = $null
    try {
        [void](Invoke-RestoreMySqlQuery -Query "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON ``$RestoreDatabase``.* TO $accountLiteral;")
        $importGrantIssued = $true
        $mysqlArguments = @("--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlImportUsername", "--database=$RestoreDatabase")
        [void](Invoke-CrpProcessWithInputFile -FilePath $MySqlExecutable -PrefixArguments $MySqlPrefixArguments `
            -Arguments $mysqlArguments -Environment (Get-MySqlImportEnvironment) `
            -InputFile (Join-Path $validated.Path 'mysql.sql') -Stage 'MySQL 隔离恢复')
        Assert-RestoredDatabaseInventory -Expected @($manifest.database)
    }
    catch {
        $databaseRestoreError = $_
    }
    $revokeError = $null
    if ($importGrantIssued) {
        try {
            [void](Invoke-RestoreMySqlQuery -Query "REVOKE ALL PRIVILEGES ON ``$RestoreDatabase``.* FROM $accountLiteral;")
        }
        catch {
            $revokeError = $_
        }
    }
    if ($null -ne $revokeError) {
        $suffix = if ($null -ne $databaseRestoreError) { '，且数据库恢复同时失败' } else { '' }
        throw "无法撤销MySQL导入账号的临时目标库权限$suffix；请保持停机并人工撤权。"
    }
    if ($null -ne $databaseRestoreError) {
        throw $databaseRestoreError
    }

    if (-not $ActivateUploadDirectory) {
        $isolatedUploads = Join-Path $workspacePath 'uploads'
        Copy-UploadTree -Source $validated.UploadPath -Destination $isolatedUploads
        $isolatedInventory = @(Get-CrpFileInventory -Root $isolatedUploads)
        Assert-CrpInventoryMatches -Expected @($manifest.uploads) -Actual $isolatedInventory -Label '隔离恢复上传文件'
        Write-Host "隔离恢复完成：workspace=$workspacePath database=$RestoreDatabase"
        Write-Host 'Redis RDB 已验证并放入隔离工作区；脚本不会覆盖正在运行或共享的 Redis 实例。'
        exit 0
    }

    foreach ($target in @($stagePath, $quarantinePath)) {
        if (Test-Path -LiteralPath $target) {
            throw "恢复切换目标已存在，拒绝覆盖：$target"
        }
    }
    if (-not (Test-Path -LiteralPath $quarantineRoot)) {
        New-Item -ItemType Directory -Path $quarantineRoot -ErrorAction Stop | Out-Null
    }
    Assert-CrpNoReparsePoints -Path $quarantineRoot -Label '隔离目录' -Recursive
    Copy-UploadTree -Source $validated.UploadPath -Destination $stagePath
    $stageInventory = @(Get-CrpFileInventory -Root $stagePath)
    Assert-CrpInventoryMatches -Expected @($manifest.uploads) -Actual $stageInventory -Label '切换暂存上传文件'

    $movedOriginal = $false
    $activatedStage = $false
    if (Test-Path -LiteralPath $failedActivationPath) {
        throw "失败恢复保留目录已存在，拒绝覆盖：$failedActivationPath"
    }
    try {
        if (Test-Path -LiteralPath $activeUploadRoot) {
            Assert-CrpNoReparsePoints -Path $activeUploadRoot -Label '原活动上传目录' -Recursive
            Move-Item -LiteralPath $activeUploadRoot -Destination $quarantinePath -ErrorAction Stop
            $movedOriginal = $true
        }
        Move-Item -LiteralPath $stagePath -Destination $activeUploadRoot -ErrorAction Stop
        $activatedStage = $true
        $activeInventory = @(Get-CrpFileInventory -Root $activeUploadRoot)
        Assert-CrpInventoryMatches -Expected @($manifest.uploads) -Actual $activeInventory -Label '活动上传目录'
    }
    catch {
        $activationError = $_
        $compensationErrors = [Collections.Generic.List[string]]::new()
        if ($activatedStage -and (Test-Path -LiteralPath $activeUploadRoot)) {
            try {
                Move-Item -LiteralPath $activeUploadRoot -Destination $failedActivationPath -ErrorAction Stop
            }
            catch {
                $compensationErrors.Add('无法保留失败的活动目录。')
            }
        }
        if ($movedOriginal -and -not (Test-Path -LiteralPath $activeUploadRoot) -and (Test-Path -LiteralPath $quarantinePath)) {
            try {
                Move-Item -LiteralPath $quarantinePath -Destination $activeUploadRoot -ErrorAction Stop
            }
            catch {
                $compensationErrors.Add('无法把 quarantine 中的原目录回迁。')
            }
        }
        if ($compensationErrors.Count -gt 0) {
            throw "上传目录切换失败且补偿不完整：$($compensationErrors -join ' ') 原始错误类型=$($activationError.Exception.GetType().Name)"
        }
        throw $activationError
    }
    Write-Host "恢复完成：database=$RestoreDatabase uploadRoot=$activeUploadRoot"
    if ($movedOriginal) {
        Write-Host "原上传目录已保留在：$quarantinePath"
    }
    Write-Host "隔离 Redis RDB：$restoredRdb"
}
finally {
    $lock.Dispose()
}
