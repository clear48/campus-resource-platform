[CmdletBinding()]
param(
    [string]$RequiredBranch = 'deploy',
    [switch]$AllowDirtyWorkingTree,
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'Deploy04.Common.ps1')

function Protect-MigrationSensitiveText {
    param([AllowNull()][string]$Text, [AllowNull()][string[]]$SensitiveValues)

    $protected = if ($null -eq $Text) { '' } else { $Text }
    foreach ($value in @($SensitiveValues)) {
        if (-not [string]::IsNullOrEmpty($value)) {
            $protected = $protected.Replace($value, '<redacted>')
        }
    }
    return $protected
}

function Invoke-MigrationDocker {
    param([string]$Docker, [string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $Docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = (($output | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) {
            $details = if ($null -ne $_.ErrorDetails) { $_.ErrorDetails.Message } else { '' }
            @($_.ToString(), $_.Exception.Message, $details) -join ' '
        }
        else {
            $_.ToString()
        }
    }) -join [Environment]::NewLine).Trim()
    if ($exitCode -ne 0) {
        # 保存原生命令退出码，最终脚本会按该退出码失败，便于 CI 精确诊断。
        $exception = [System.Exception]::new("Docker 命令失败（退出码 $exitCode）：$text")
        $exception.Data['ExitCode'] = [int]$exitCode
        throw $exception
    }
    return $text
}

function Invoke-MigrationMysql {
    param([string]$Docker, [string]$Container, [string]$Database, [string]$Sql)

    # 密码只从容器环境读取，不进入宿主命令行、日志或证据。
    $mysqlArguments = @('--protocol=socket', '-uroot', '--batch', '--skip-column-names')
    if (-not [string]::IsNullOrWhiteSpace($Database)) {
        $mysqlArguments += "--database=$Database"
    }
    $mysqlArguments += @('-e', $Sql)
    Invoke-MigrationDocker $Docker (@(
        'exec', $Container, 'sh', '-c',
        'err=/tmp/deploy04-mysql.err; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql "$@" 2>"$err"; code=$?; if [ "$code" -ne 0 ]; then cat "$err" >&2; fi; rm -f "$err"; exit "$code"',
        'deploy04-mysql'
    ) + $mysqlArguments)
}

function Wait-MigrationHealthy {
    param([string]$Docker, [string]$Container, [int]$TimeoutSeconds = 150)

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $status = Invoke-MigrationDocker $Docker @(
            'inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}', $Container
        )
        if ($status -eq 'healthy') { return }
        if ($status -in @('exited', 'dead')) { throw "容器提前退出：$Container ($status)" }
        Start-Sleep -Seconds 2
    }
    throw "等待容器健康超时：$Container"
}

function Invoke-MigrationSource {
    param([string]$Docker, [string]$Container, [string]$FileName)

    Invoke-MigrationDocker $Docker @(
        'exec', $Container, 'sh', '-c',
        'printf "%s\n" "$1" | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --commands --protocol=socket -uroot campus_resource_platform --skip-column-names',
        'deploy04-source', "\. /deploy04/migrations/$FileName"
    )
}

function Invoke-MigrationAssertions {
    param([string]$Docker, [string]$Container, [string]$Database, [int]$AllowMissing)

    Invoke-MigrationDocker $Docker @(
        'exec', $Container, 'sh', '-c',
        'printf "%s\n%s\n" "$1" "$2" | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --commands --protocol=socket -uroot "$3" --skip-column-names',
        'deploy04-source', "SET @allow_missing=$AllowMissing;", '\. /deploy04/assertions.sql', $Database
    )
}

function Invoke-ExpectedAssertionFailure {
    param([scriptblock]$Action, [string]$Name, [string]$ExpectedText)

    try {
        & $Action | Out-Null
    }
    catch {
        if (-not $_.Exception.Message.Contains($ExpectedText, [System.StringComparison]::Ordinal)) {
            throw "负向自测发生了非预期失败：$Name"
        }
        return [ordered]@{ name = $Name; expectedFailure = $ExpectedText; status = 'PASSED' }
    }
    throw "负向自测未被预期门禁拒绝：$Name"
}

function Get-NormalizedFileSha256 {
    param([string]$Path)

    $text = [IO.File]::ReadAllText($Path).Replace("`r`n", "`n").Replace("`r", "`n")
    return Get-Deploy04Sha256 -Value $text
}

function Test-MigrationPathWithinRoot {
    param([string]$Root, [string]$Candidate)

    $separators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd($separators)
    $normalizedCandidate = [IO.Path]::GetFullPath($Candidate)
    $comparison = if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [Runtime.InteropServices.OSPlatform]::Windows)) {
        [StringComparison]::OrdinalIgnoreCase
    }
    else {
        [StringComparison]::Ordinal
    }
    return $normalizedCandidate.Equals($normalizedRoot, $comparison) -or
        $normalizedCandidate.StartsWith($normalizedRoot + [IO.Path]::DirectorySeparatorChar, $comparison)
}

function Assert-MigrationEvidencePathComponents {
    param([string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    $pathRoot = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($pathRoot)) {
        throw 'EvidencePath 缺少有效根目录。'
    }
    $rootAttributes = [IO.File]::GetAttributes($pathRoot)
    if (($rootAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "EvidencePath 根目录不得为 ReparsePoint：$pathRoot"
    }
    $separators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $relativePath = [IO.Path]::GetRelativePath($pathRoot, $fullPath)
    $parts = $relativePath.Split($separators, [StringSplitOptions]::RemoveEmptyEntries)
    $currentPath = $pathRoot
    $missingParentSeen = $false
    for ($index = 0; $index -lt $parts.Length; $index++) {
        $currentPath = Join-Path $currentPath $parts[$index]
        try {
            $attributes = [IO.File]::GetAttributes($currentPath)
        }
        catch [IO.FileNotFoundException] {
            $missingParentSeen = $true
            continue
        }
        catch [IO.DirectoryNotFoundException] {
            $missingParentSeen = $true
            continue
        }

        if ($missingParentSeen) {
            throw 'EvidencePath 的缺失父目录下出现了意外已存在对象。'
        }
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            # Windows symlink、junction 及其他 ReparsePoint 都可能把 TEMP 路径重定向回仓库。
            throw "EvidencePath 任一路径层级不得为 symlink、junction 或 ReparsePoint：$currentPath"
        }
        $isTarget = $index -eq ($parts.Length - 1)
        if (-not $isTarget -and ($attributes -band [IO.FileAttributes]::Directory) -eq 0) {
            throw "EvidencePath 的父路径不是目录：$currentPath"
        }
        if ($isTarget -and ($attributes -band [IO.FileAttributes]::Directory) -ne 0) {
            throw 'EvidencePath 必须指向文件。'
        }
    }
}

function Resolve-MigrationEvidencePath {
    param([string]$Path, [string]$RepositoryRoot, [string[]]$ForbiddenPaths)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (Test-MigrationPathWithinRoot -Root $RepositoryRoot -Candidate $fullPath) {
        throw 'EvidencePath 必须位于仓库根目录之外。'
    }
    Assert-MigrationEvidencePathComponents -Path $fullPath
    $comparison = if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [Runtime.InteropServices.OSPlatform]::Windows)) {
        [StringComparison]::OrdinalIgnoreCase
    }
    else {
        [StringComparison]::Ordinal
    }
    foreach ($forbiddenPath in $ForbiddenPaths) {
        if (-not [string]::IsNullOrWhiteSpace($forbiddenPath) -and
                $fullPath.Equals([IO.Path]::GetFullPath($forbiddenPath), $comparison)) {
            throw 'EvidencePath 不得覆盖输入文件或临时 Secret 文件。'
        }
    }
    return $fullPath
}

function Write-MigrationEvidenceSafely {
    param(
        [string]$Path,
        [string]$RepositoryRoot,
        [string[]]$ForbiddenPaths,
        [object]$Evidence
    )

    $safePath = Resolve-MigrationEvidencePath -Path $Path -RepositoryRoot $RepositoryRoot -ForbiddenPaths $ForbiddenPaths
    $parentDirectory = Split-Path -Parent $safePath
    [IO.Directory]::CreateDirectory($parentDirectory) | Out-Null
    # 父目录可能原先不存在；创建后再逐层复核，避免写入阶段经过新出现的重解析点。
    $safePath = Resolve-MigrationEvidencePath -Path $safePath -RepositoryRoot $RepositoryRoot -ForbiddenPaths $ForbiddenPaths
    $writtenPath = Write-Deploy04Evidence -Path $safePath -Evidence $Evidence
    Assert-MigrationEvidencePathComponents -Path $writtenPath
    return $writtenPath
}

function Get-MigrationProjectResources {
    param([string]$Docker, [string]$Project)

    # 同时从名称前缀和 Compose label 两条路径枚举，错标签资源也不能被 down -v 误删或漏检。
    $definitions = [ordered]@{
        containers = [ordered]@{
            list = @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
            all = @('ps', '--all', '--format', '{{.ID}}|{{.Names}}')
            inspect = @('inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}')
        }
        networks = [ordered]@{
            list = @('network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
            all = @('network', 'ls', '--format', '{{.ID}}|{{.Name}}')
            inspect = @('network', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}')
        }
        volumes = [ordered]@{
            list = @('volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
            all = @('volume', 'ls', '--format', '{{.Name}}|{{.Name}}')
            inspect = @('volume', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}')
        }
    }
    $snapshot = [ordered]@{}
    foreach ($definition in $definitions.GetEnumerator()) {
        $allResources = Invoke-MigrationDocker $Docker $definition.Value.all
        foreach ($line in @($allResources -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            $parts = $line -split '\|', 2
            if ($parts.Count -eq 2 -and
                    ($parts[1].StartsWith("$Project-") -or $parts[1].StartsWith("$Project`_"))) {
                $candidateLabel = Invoke-MigrationDocker $Docker ($definition.Value.inspect + $parts[0])
                if ($candidateLabel -ne $Project) {
                    throw "发现名称属于本次 project 但标签不匹配的 Docker $($definition.Key)，拒绝清理：$($parts[0])"
                }
            }
        }

        $rawIds = Invoke-MigrationDocker $Docker $definition.Value.list
        $items = @($rawIds -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        foreach ($id in $items) {
            $label = Invoke-MigrationDocker $Docker ($definition.Value.inspect + $id)
            if ($label -ne $Project) {
                throw "Docker $($definition.Key) 的 Compose project 标签不匹配，拒绝清理：$id"
            }
        }
        $snapshot[$definition.Key] = $items
    }
    return $snapshot
}

function Assert-MigrationProjectEmpty {
    param([System.Collections.IDictionary]$Resources, [string]$Message)

    if ($Resources.containers.Count -ne 0 -or
            $Resources.networks.Count -ne 0 -or
            $Resources.volumes.Count -ne 0) {
        throw $Message
    }
}

function Invoke-CleanupWrongLabelNegativeTest {
    param([string]$Docker, [string]$Project, [string]$RunId)

    $probeName = "$Project-cleanup-probe"
    $ownerLabel = 'com.campus-resource-platform.deploy04.run'
    $probeId = $null
    try {
        $probeId = Invoke-MigrationDocker $Docker @(
            'network', 'create', '--label', "com.docker.compose.project=wrong-$RunId",
            '--label', "$ownerLabel=$RunId", $probeName
        )
        return Invoke-ExpectedAssertionFailure -Name 'cleanupWrongProjectLabel' `
            -ExpectedText '名称属于本次 project 但标签不匹配' `
            -Action { Get-MigrationProjectResources -Docker $Docker -Project $Project | Out-Null }
    }
    finally {
        if (-not [string]::IsNullOrWhiteSpace($probeId)) {
            $owner = Invoke-MigrationDocker $Docker @(
                'network', 'inspect', '--format', "{{ index .Labels `"$ownerLabel`" }}", $probeId
            )
            $name = Invoke-MigrationDocker $Docker @('network', 'inspect', '--format', '{{.Name}}', $probeId)
            if ($owner -ne $RunId -or $name -ne $probeName) {
                throw '清理错标签负测网络的归属无法确认。'
            }
            Invoke-MigrationDocker $Docker @('network', 'rm', $probeId) | Out-Null
        }
    }
}

function Invoke-MigrationRedis {
    param([string]$Docker, [string]$Container, [string[]]$Arguments)

    Invoke-MigrationDocker $Docker (@(
        'exec', $Container, 'sh', '-c',
        'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" "$@"', 'deploy04-redis'
    ) + $Arguments)
}

function Invoke-MigrationRedisEval {
    param([string]$Docker, [string]$Container, [string]$Script, [string[]]$Keys, [string[]]$Arguments)

    $redisArguments = @('EVAL', $Script, [string]$Keys.Count) + $Keys + $Arguments
    Invoke-MigrationRedis -Docker $Docker -Container $Container -Arguments $redisArguments
}

function Get-LegacyDataDigest {
    param([string]$Docker, [string]$Container)

    # 列清单固定为 legacy fixture 的全部原列；生成列等迁移新增列不参与“旧数据未改写”证明。
    $tableColumns = [ordered]@{
        user = @('id','username','password_hash','nickname','email','phone','role','status','avatar_url','last_login_at','created_at','updated_at')
        category = @('id','parent_id','category_name','description','sort_order','status','created_at','updated_at')
        file_info = @('id','file_md5','original_name','stored_name','file_ext','mime_type','file_size','storage_type','storage_path','uploader_id','ref_count','status','created_at','updated_at')
        resource = @('id','title','description','category_id','course_name','resource_type','tags','file_id','uploader_id','status','reject_reason','offline_reason','view_count','download_count','favorite_count','hot_score','approved_at','offline_at','created_at','updated_at')
        favorite = @('id','user_id','resource_id','status','created_at','updated_at')
        download_record = @('id','user_id','resource_id','file_id','user_ip','user_agent','download_status','fail_reason','created_at')
        audit_record = @('id','resource_id','auditor_id','action_type','before_status','after_status','audit_reason','created_at')
    }
    $result = [ordered]@{ aggregateDigest = $null; tables = [ordered]@{}; rows = [ordered]@{} }
    $aggregateParts = [Collections.Generic.List[string]]::new()
    foreach ($entry in $tableColumns.GetEnumerator()) {
        $encodedColumns = @($entry.Value | ForEach-Object {
            "IF(``$_`` IS NULL,'N',CONCAT('V',OCTET_LENGTH(CAST(``$_`` AS BINARY)),':',HEX(CAST(``$_`` AS BINARY))))"
        })
        # ID 仅为十进制数字、摘要仅为十六进制，冒号不会与两侧内容冲突，也不会被 mysql --batch 转义。
        $sql = "SELECT CONCAT(CAST(``id`` AS CHAR),':',SHA2(CONCAT($($encodedColumns -join ',')),256)) FROM ``$($entry.Key)`` ORDER BY ``id``"
        $raw = Invoke-MigrationMysql $Docker $Container 'campus_resource_platform' $sql
        $rowDigests = [Collections.Generic.List[string]]::new()
        foreach ($line in @($raw -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            $parts = $line -split ':', 2
            if ($parts.Count -ne 2 -or $parts[0] -notmatch '^\d+$' -or $parts[1] -notmatch '^[0-9a-fA-F]{64}$') {
                throw "legacy 数据摘要输出格式非法：$($entry.Key)"
            }
            $rowDigests.Add("$($parts[0]):$($parts[1].ToLowerInvariant())")
        }
        $tableDigest = Get-Deploy04Sha256 -Value ($rowDigests -join "`n")
        $result.tables[$entry.Key] = [ordered]@{ rowCount = $rowDigests.Count; digest = $tableDigest }
        $result.rows[$entry.Key] = $rowDigests.ToArray()
        $aggregateParts.Add("$($entry.Key):$($rowDigests.Count):$tableDigest")
    }
    $result.aggregateDigest = Get-Deploy04Sha256 -Value ($aggregateParts -join "`n")
    return $result
}

function Assert-LegacyDataDigestEqual {
    param([System.Collections.IDictionary]$Expected, [System.Collections.IDictionary]$Actual, [string]$Stage)

    if ($Expected.aggregateDigest -cne $Actual.aggregateDigest) {
        throw "$Stage 改写了 legacy 全列数据摘要。"
    }
    foreach ($tableName in $Expected.tables.Keys) {
        if ($Expected.tables[$tableName].rowCount -ne $Actual.tables[$tableName].rowCount -or
                $Expected.tables[$tableName].digest -cne $Actual.tables[$tableName].digest -or
                (($Expected.rows[$tableName] -join "`n") -cne ($Actual.rows[$tableName] -join "`n"))) {
            throw "$Stage 改写了 legacy 表数据：$tableName"
        }
    }
}

function Convert-LegacyDigestToEvidence {
    param([System.Collections.IDictionary]$Digest)

    return [ordered]@{ aggregateDigest = $Digest.aggregateDigest; tables = $Digest.tables }
}

function Add-MigrationResult {
    param([Collections.Generic.List[object]]$Results, [string]$Docker, [string]$Container, [string]$File)

    try {
        Invoke-MigrationSource $Docker $Container $File | Out-Null
        $Results.Add([ordered]@{ file = $File; exitCode = 0; status = 'PASSED' })
    }
    catch {
        $nativeExitCode = if ($null -ne $_.Exception.Data['ExitCode']) { [int]$_.Exception.Data['ExitCode'] } else { 1 }
        $Results.Add([ordered]@{ file = $File; exitCode = $nativeExitCode; status = 'FAILED' })
        throw
    }
}

$startedAt = [DateTimeOffset]::UtcNow
$runId = ($startedAt.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)).ToLowerInvariant()
$project = "campus-deploy04-migration-$runId"
$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) "campus-resource-platform/deploy-04/migration-$runId"
$envFile = Join-Path $tempDirectory 'compose.env'
$overrideFile = Join-Path $tempDirectory 'compose.override.yml'
$defaultEvidencePath = Join-Path ([IO.Path]::GetTempPath()) "campus-resource-platform/deploy-04/evidence/migration-$runId.json"
$evidencePathToWrite = $defaultEvidencePath
$migrationFiles = @(
    '20260714_download_delta_sync_idempotency.sql',
    '20260715_user_file_authorization.sql',
    '20260813_resource_active_duplicate_guard.sql'
)
$environmentNames = @(
    'MYSQL_ROOT_PASSWORD', 'MYSQL_APP_USERNAME', 'MYSQL_APP_PASSWORD',
    'REDIS_PASSWORD', 'JWT_SECRET', 'COMPOSE_PROJECT_NAME'
)
$savedEnvironment = @{}
$sensitiveValues = [Collections.Generic.List[string]]::new()
$migrations = [Collections.Generic.List[object]]::new()
$rerunMigrations = [Collections.Generic.List[object]]::new()
$negativeTests = [Collections.Generic.List[object]]::new()
$evidence = [ordered]@{
    timestamp = $startedAt.ToString('o'); finishedAt = $null; branch = $null; commit = $null; sourceDigest = $null
    allowDirtyWorkingTree = [bool]$AllowDirtyWorkingTree; project = $project; versions = [ordered]@{}; fixture = [ordered]@{}
    guard = [ordered]@{ initiallyAbsent='NOT_RUN'; preexistingRejected='NOT_RUN'; syntheticGuardRejected='NOT_RUN'; cleaned='NOT_RUN' }
    migrations = $migrations; rerunMigrations = $rerunMigrations; negativeTests = $negativeTests
    schemaData = [ordered]@{
        beforeDigest=$null; afterDigest=$null; rerunDigest=$null; legacyDigests=[ordered]@{}
        authorizationCount=$null; activeDuplicateGroups=$null; crossUserHistorical=$null
    }
    idempotentRerun='NOT_RUN'; sourceRecheck='NOT_RUN'
    cleanup = [ordered]@{ resources='NOT_CREATED'; temporaryFiles='NOT_CREATED'; remainingResources=$null }
    failure=$null; overallStatus='RUNNING'
}

$repositoryRoot = $null
$git = $null
$docker = $null
$branch = $null
$commit = $null
$workingTreeStatus = $null
$sourceDigest = $null
$sourceSnapshotEstablished = $false
$legacyFixture = $null
$forbiddenEvidencePaths = @()
$compose = $null
$composeAttempted = $false
$tempCreated = $false
$environmentCaptured = $false
$failure = $null
$failureExitCode = 1
$stage = 'initialize'

try {
    $repositoryRoot = Get-Deploy04RepositoryRoot -ScriptRoot $PSScriptRoot
    $legacyFixture = Join-Path $repositoryRoot 'deploy/fixtures/deploy-04-legacy-schema.sql'
    $legacySeed = Join-Path $repositoryRoot 'deploy/fixtures/deploy-04-legacy-seed.sql'
    $assertionsFile = Join-Path $repositoryRoot 'deploy/mysql/deploy-04-assertions.sql'
    $composeFile = Join-Path $repositoryRoot 'deploy/docker-compose.yml'
    $migrationDirectory = Join-Path $repositoryRoot 'sql/migrations'
    $forbiddenEvidencePaths = @(
        $legacyFixture, $legacySeed, $assertionsFile, $composeFile, $envFile, $overrideFile,
        (Join-Path $PSScriptRoot 'Test-Deploy04Migration.ps1'),
        (Join-Path $PSScriptRoot 'Deploy04.Common.ps1')
    ) + @($migrationFiles | ForEach-Object { Join-Path $migrationDirectory $_ })

    $stage = 'evidencePath'
    if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) {
        $evidencePathToWrite = Resolve-MigrationEvidencePath -Path $EvidencePath -RepositoryRoot $repositoryRoot -ForbiddenPaths $forbiddenEvidencePaths
    }

    $stage = 'sourcePreflight'
    $git = Assert-Deploy04Command git
    $branch = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'branch', '--show-current')
    $commit = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'rev-parse', 'HEAD')
    $workingTreeStatus = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'status', '--porcelain=v1', '--untracked-files=all')
    $evidence.branch = $branch
    $evidence.commit = $commit
    $sourceDigest = Get-Deploy04SourceSnapshotDigest -GitCommand $git -RepositoryRoot $repositoryRoot
    $evidence.sourceDigest = $sourceDigest
    $sourceSnapshotEstablished = $true
    if ($branch -ne $RequiredBranch) { throw "必须在 $RequiredBranch 分支运行，当前为 $branch" }
    if (-not $AllowDirtyWorkingTree -and -not [string]::IsNullOrWhiteSpace($workingTreeStatus)) {
        throw '正式迁移演练要求干净工作区。'
    }
    $stage = 'fixturePreflight'
    $fixtureSource = 'b528826864e1100c350b0454ff071f178b03ee80:sql/init.sql'
    $fixtureExpectedSha = 'e208cdcc188da9f7850e88ae3f9abb0e74976de03729a0281a07899b3d9768e6'
    foreach ($requiredPath in @($legacyFixture, $legacySeed, $assertionsFile, $composeFile)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw '迁移演练输入文件缺失。' }
    }
    $fixtureActualSha = Get-NormalizedFileSha256 $legacyFixture
    $evidence.fixture = [ordered]@{ source=$fixtureSource; expectedSha256=$fixtureExpectedSha; actualSha256=$fixtureActualSha }
    if ($fixtureActualSha -ne $fixtureExpectedSha) { throw 'legacy schema fixture 已漂移，拒绝演练。' }

    $stage = 'toolVersions'
    $docker = Assert-Deploy04Command docker
    $evidence.versions.docker = Invoke-MigrationDocker $docker @('version', '--format', '{{.Server.Version}}')
    $evidence.versions.compose = Invoke-MigrationDocker $docker @('compose', 'version', '--short')

    $stage = 'projectCollisionPreflight'
    $preexistingResources = Get-MigrationProjectResources -Docker $docker -Project $project
    Assert-MigrationProjectEmpty -Resources $preexistingResources -Message '唯一 Compose project 在启动前已存在 Docker 资源。'
    $negativeTests.Add((Invoke-CleanupWrongLabelNegativeTest -Docker $docker -Project $project -RunId $runId))
    $afterProbeResources = Get-MigrationProjectResources -Docker $docker -Project $project
    Assert-MigrationProjectEmpty -Resources $afterProbeResources -Message '清理错标签负测后仍有 Docker 资源。'

    $stage = 'temporaryInputs'
    [IO.Directory]::CreateDirectory($tempDirectory) | Out-Null
    $tempCreated = $true
    $rootSecret = New-Deploy04Secret 'Root4!'
    $appSecret = New-Deploy04Secret 'App4!'
    $redisSecret = New-Deploy04Secret 'Redis4!'
    $jwtSecret = New-Deploy04Secret 'Jwt4!'
    foreach ($secret in @($rootSecret, $appSecret, $redisSecret, $jwtSecret)) { $sensitiveValues.Add($secret) }
    foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $environmentCaptured = $true
    @(
        "MYSQL_ROOT_PASSWORD=$rootSecret", 'MYSQL_APP_USERNAME=campus_app', "MYSQL_APP_PASSWORD=$appSecret",
        "REDIS_PASSWORD=$redisSecret", "JWT_SECRET=$jwtSecret", 'HTTP_PORT=127.0.0.1:',
        'BACKEND_IMAGE=unused:deploy04-migration', 'FRONTEND_IMAGE=unused:deploy04-migration'
    ) | Set-Content -LiteralPath $envFile -Encoding utf8
    @"
services:
  mysql:
    volumes:
      - ${legacyFixture}:/docker-entrypoint-initdb.d/001-init.sql:ro
      - ${legacySeed}:/docker-entrypoint-initdb.d/003-deploy04-seed.sql:ro
      - ${migrationDirectory}:/deploy04/migrations:ro
      - ${assertionsFile}:/deploy04/assertions.sql:ro
"@.Replace('\', '/') | Set-Content -LiteralPath $overrideFile -Encoding utf8

    [Environment]::SetEnvironmentVariable('MYSQL_ROOT_PASSWORD', $rootSecret, 'Process')
    [Environment]::SetEnvironmentVariable('MYSQL_APP_USERNAME', 'campus_app', 'Process')
    [Environment]::SetEnvironmentVariable('MYSQL_APP_PASSWORD', $appSecret, 'Process')
    [Environment]::SetEnvironmentVariable('REDIS_PASSWORD', $redisSecret, 'Process')
    [Environment]::SetEnvironmentVariable('JWT_SECRET', $jwtSecret, 'Process')
    [Environment]::SetEnvironmentVariable('COMPOSE_PROJECT_NAME', $project, 'Process')
    $compose = @('compose', '--project-name', $project, '--env-file', $envFile, '-f', $composeFile, '-f', $overrideFile)
    Invoke-MigrationDocker $docker ($compose + @('config', '--quiet')) | Out-Null

    $stage = 'dependencyStartup'
    $composeAttempted = $true
    Invoke-MigrationDocker $docker ($compose + @('up', '-d', '--no-build', '--pull', 'never', 'mysql', 'redis')) | Out-Null
    $mysqlId = Invoke-MigrationDocker $docker ($compose + @('ps', '-q', 'mysql'))
    $redisId = Invoke-MigrationDocker $docker ($compose + @('ps', '-q', 'redis'))
    Wait-MigrationHealthy $docker $mysqlId
    Wait-MigrationHealthy $docker $redisId
    $evidence.versions.mysql = Invoke-MigrationMysql $docker $mysqlId '' 'SELECT VERSION()'
    $evidence.versions.redis = Invoke-MigrationDocker $docker @('exec', $redisId, 'redis-server', '--version')

    $stage = 'preMigrationAssertions'
    Write-Host '[DEPLOY-04] 校验迁移前 legacy 结构与全列数据摘要'
    Invoke-MigrationAssertions $docker $mysqlId 'campus_resource_platform' 1 | Out-Null
    $digestBefore = Get-LegacyDataDigest $docker $mysqlId
    $evidence.schemaData.beforeDigest = $digestBefore.aggregateDigest
    $evidence.schemaData.legacyDigests.before = Convert-LegacyDigestToEvidence $digestBefore
    $duplicateBefore = Invoke-MigrationMysql $docker $mysqlId 'campus_resource_platform' 'SELECT COUNT(*) FROM (SELECT uploader_id,file_id FROM resource WHERE status IN(0,1) GROUP BY uploader_id,file_id HAVING COUNT(*)>1) d'
    if ($duplicateBefore -ne '0') { throw 'synthetic seed 存在有效重复组。' }

    $stage = 'redisLegacyGuard'
    $legacyKey = 'crp:stats:resource:download:syncing:active'
    if ((Invoke-MigrationRedis $docker $redisId @('EXISTS', $legacyKey)) -ne '0') {
        throw '隔离 Redis 在 synthetic guard 创建前已存在 legacy active key。'
    }
    $evidence.guard.initiallyAbsent = 'PASSED'
    $createOnlyIfAbsent = "if redis.call('exists',KEYS[1]) == 1 then return 0 end redis.call('hset',KEYS[1],ARGV[1],ARGV[2]); return 1"
    $created = Invoke-MigrationRedisEval $docker $redisId $createOnlyIfAbsent @($legacyKey) @('900001', '7')
    if ($created -ne '1') { throw '无法原子创建本次 synthetic legacy active key。' }
    $beforeType = Invoke-MigrationRedis $docker $redisId @('TYPE', $legacyKey)
    $beforeLength = Invoke-MigrationRedis $docker $redisId @('HLEN', $legacyKey)
    $beforeValue = Invoke-MigrationRedis $docker $redisId @('HGET', $legacyKey, '900001')
    $secondCreate = Invoke-MigrationRedisEval $docker $redisId $createOnlyIfAbsent @($legacyKey) @('900001', '8')
    $afterType = Invoke-MigrationRedis $docker $redisId @('TYPE', $legacyKey)
    $afterLength = Invoke-MigrationRedis $docker $redisId @('HLEN', $legacyKey)
    $afterValue = Invoke-MigrationRedis $docker $redisId @('HGET', $legacyKey, '900001')
    if ($secondCreate -ne '0' -or $beforeType -ne 'hash' -or $beforeLength -ne '1' -or $beforeValue -ne '7' -or
            $afterType -ne $beforeType -or $afterLength -ne $beforeLength -or $afterValue -ne $beforeValue) {
        throw 'Redis preexisting guard 未做到只拒绝且不修改已有 key。'
    }
    $evidence.guard.preexistingRejected = 'PASSED'
    try {
        if ((Invoke-MigrationRedis $docker $redisId @('EXISTS', $legacyKey)) -ne '0') {
            throw '检测到 legacy active 下载同步批次，拒绝迁移。'
        }
        throw 'Redis legacy guard 未拒绝 synthetic active key。'
    }
    catch {
        if (-not $_.Exception.Message.Contains('检测到 legacy active', [StringComparison]::Ordinal)) { throw }
        $evidence.guard.syntheticGuardRejected = 'PASSED'
    }
    $deleteExactSynthetic = "if redis.call('type',KEYS[1]).ok ~= 'hash' then return -1 end if redis.call('hlen',KEYS[1]) ~= 1 then return -2 end if redis.call('hget',KEYS[1],ARGV[1]) ~= ARGV[2] then return -3 end return redis.call('del',KEYS[1])"
    $deleteResult = Invoke-MigrationRedisEval $docker $redisId $deleteExactSynthetic @($legacyKey) @('900001', '7')
    if ($deleteResult -ne '1' -or (Invoke-MigrationRedis $docker $redisId @('EXISTS', $legacyKey)) -ne '0') {
        throw '本次 synthetic legacy key 未按精确内容原子清理。'
    }
    $remainingKeys = Invoke-MigrationRedis $docker $redisId @('--scan', '--pattern', 'crp:stats:resource:download:syncing:*')
    $currentExists = Invoke-MigrationRedis $docker $redisId @('EXISTS', 'crp:stats:resource:download:syncing:current')
    if (-not [string]::IsNullOrWhiteSpace($remainingKeys) -or $currentExists -ne '0') {
        throw 'Redis 下载同步 key 未清空，拒绝迁移。'
    }
    $evidence.guard.cleaned = 'PASSED'

    $stage = 'firstMigrationRun'
    foreach ($file in $migrationFiles) { Add-MigrationResult -Results $migrations -Docker $docker -Container $mysqlId -File $file }
    $stage = 'postMigrationAssertions'
    Invoke-MigrationAssertions $docker $mysqlId 'campus_resource_platform' 0 | Out-Null
    $digestAfter = Get-LegacyDataDigest $docker $mysqlId
    Assert-LegacyDataDigestEqual -Expected $digestBefore -Actual $digestAfter -Stage '首次迁移'
    $evidence.schemaData.afterDigest = $digestAfter.aggregateDigest
    $evidence.schemaData.legacyDigests.afterFirstRun = Convert-LegacyDigestToEvidence $digestAfter
    $cross = Invoke-MigrationMysql $docker $mysqlId 'campus_resource_platform' 'SELECT CONCAT(COUNT(*),'':'',SUM(a.id IS NULL)) FROM resource r JOIN file_info f ON f.id=r.file_id LEFT JOIN user_file_authorization a ON a.user_id=r.uploader_id AND a.file_id=r.file_id WHERE r.uploader_id<>f.uploader_id'
    $authorizationCount = Invoke-MigrationMysql $docker $mysqlId 'campus_resource_platform' 'SELECT COUNT(*) FROM user_file_authorization'
    $activeDuplicateCount = Invoke-MigrationMysql $docker $mysqlId 'campus_resource_platform' 'SELECT COUNT(*) FROM (SELECT uploader_id,file_id FROM resource WHERE status IN(0,1) GROUP BY uploader_id,file_id HAVING COUNT(*)>1) d'
    $evidence.schemaData.crossUserHistorical = $cross
    $evidence.schemaData.authorizationCount = [int]$authorizationCount
    $evidence.schemaData.activeDuplicateGroups = [int]$activeDuplicateCount

    $stage = 'idempotentMigrationRun'
    foreach ($file in $migrationFiles) { Add-MigrationResult -Results $rerunMigrations -Docker $docker -Container $mysqlId -File $file }
    Invoke-MigrationAssertions $docker $mysqlId 'campus_resource_platform' 0 | Out-Null
    $digestAfterRerun = Get-LegacyDataDigest $docker $mysqlId
    Assert-LegacyDataDigestEqual -Expected $digestBefore -Actual $digestAfterRerun -Stage '幂等重跑'
    $evidence.schemaData.rerunDigest = $digestAfterRerun.aggregateDigest
    $evidence.schemaData.legacyDigests.afterRerun = Convert-LegacyDigestToEvidence $digestAfterRerun
    $procedureCount = Invoke-MigrationMysql $docker $mysqlId '' "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='campus_resource_platform' AND routine_name='migrate_resource_active_duplicate_guard'"
    if ($procedureCount -ne '0') { throw '20260813 迁移存储过程残留。' }
    $evidence.idempotentRerun = 'PASSED'

    $stage = 'negativeStructureTests'
    $scratchPrefix = ('deploy04_scratch_' + $runId.Replace('-', '_').Replace('t', '').Replace('z', ''))
    $negativeDefinitions = @(
        @{ Name='wrongDownloadBatchType'; Expected='wrong column definition: download_delta_sync_item'; Sql='CREATE TABLE resource(id BIGINT); CREATE TABLE download_delta_sync_item(id BIGINT NOT NULL AUTO_INCREMENT,batch_id VARCHAR(10) NOT NULL,resource_id BIGINT NOT NULL,delta BIGINT NOT NULL,confirmed_at DATETIME NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,PRIMARY KEY(id),UNIQUE KEY uk_download_delta_sync_batch_resource(batch_id,resource_id),KEY idx_download_delta_sync_confirmed_created(confirmed_at,created_at),CONSTRAINT chk_download_delta_sync_item_delta CHECK(delta>0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci' },
        @{ Name='authorizationMissingUnique'; Expected='wrong index definition: user_file_authorization'; Sql='CREATE TABLE resource(id BIGINT); CREATE TABLE user_file_authorization(id BIGINT NOT NULL AUTO_INCREMENT,user_id BIGINT NOT NULL,file_id BIGINT NOT NULL,source_type TINYINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(id),KEY idx_file_authorized_user(file_id,user_id),CONSTRAINT chk_file_authorization_source CHECK(source_type IN(1,2,3))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci' },
        @{ Name='ordinaryGuardColumn'; Expected='wrong active_duplicate_guard definition'; Sql='CREATE TABLE resource(id BIGINT,uploader_id BIGINT,file_id BIGINT,status TINYINT,active_duplicate_guard TINYINT NULL,UNIQUE KEY uk_resource_active_duplicate(uploader_id,file_id,active_duplicate_guard))' },
        @{ Name='wrongGuardIndexOrder'; Expected='wrong active duplicate unique index'; Sql='CREATE TABLE resource(id BIGINT,uploader_id BIGINT,file_id BIGINT,status TINYINT,active_duplicate_guard TINYINT GENERATED ALWAYS AS(CASE WHEN status IN(0,1) THEN 1 ELSE NULL END) STORED,UNIQUE KEY uk_resource_active_duplicate(file_id,uploader_id,active_duplicate_guard))' },
        @{ Name='sameNameView'; Expected='required object is not base table: download_delta_sync_item'; Sql='CREATE TABLE resource(id BIGINT); CREATE VIEW download_delta_sync_item AS SELECT CAST(1 AS SIGNED) AS id' },
        @{ Name='guardMissingWrongNamedIndex'; Expected='active duplicate index exists without guard column'; Sql='CREATE TABLE resource(id BIGINT,uploader_id BIGINT,file_id BIGINT,status TINYINT,KEY uk_resource_active_duplicate(uploader_id,file_id,status))' }
    )
    for ($index = 0; $index -lt $negativeDefinitions.Count; $index++) {
        $database = "${scratchPrefix}_$index"
        try {
            Invoke-MigrationMysql $docker $mysqlId '' "CREATE DATABASE $database DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; USE $database; $($negativeDefinitions[$index].Sql)" | Out-Null
            $negativeTests.Add((Invoke-ExpectedAssertionFailure -Name $negativeDefinitions[$index].Name -ExpectedText $negativeDefinitions[$index].Expected -Action { Invoke-MigrationAssertions $docker $mysqlId $database 1 }))
        }
        finally {
            Invoke-MigrationMysql $docker $mysqlId '' "DROP DATABASE IF EXISTS $database" | Out-Null
        }
    }
    $scratchRemaining = Invoke-MigrationMysql $docker $mysqlId '' "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE '${scratchPrefix}%'"
    if ($scratchRemaining -ne '0') { throw 'scratch database 清理不完整。' }

}
catch {
    $rawFailure = "stage=$stage; $($_.Exception.Message)"
    $failure = Protect-MigrationSensitiveText -Text $rawFailure -SensitiveValues $sensitiveValues.ToArray()
    if ($null -ne $_.Exception.Data['ExitCode']) {
        $candidateExitCode = [int]$_.Exception.Data['ExitCode']
        if ($candidateExitCode -ge 1 -and $candidateExitCode -le 255) { $failureExitCode = $candidateExitCode }
    }
}
finally {
    $finalizationFailures = [Collections.Generic.List[string]]::new()
    if ($composeAttempted) {
        try {
            Get-MigrationProjectResources -Docker $docker -Project $project | Out-Null
            Invoke-MigrationDocker $docker ($compose + @('down', '--volumes', '--remove-orphans')) | Out-Null
            $remainingResources = Get-MigrationProjectResources -Docker $docker -Project $project
            Assert-MigrationProjectEmpty -Resources $remainingResources -Message '本次 Compose 资源仍有残留。'
            $evidence.cleanup.resources = 'PASSED'
            $evidence.cleanup.remainingResources = 0
        }
        catch {
            $evidence.cleanup.resources = 'FAILED'
            $finalizationFailures.Add('cleanup=' + (Protect-MigrationSensitiveText -Text $_.Exception.Message -SensitiveValues $sensitiveValues.ToArray()))
        }
    }

    if ($environmentCaptured) {
        foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
    if ($tempCreated) {
        $tempCleanup = Remove-Deploy04TemporaryArtifacts -Files @($envFile, $overrideFile) -Directory $tempDirectory
        $evidence.cleanup.temporaryFiles = $tempCleanup.status
        if ($tempCleanup.status -ne 'PASSED') { $finalizationFailures.Add('cleanup=' + $tempCleanup.message) }
    }

    if ($sourceSnapshotEstablished) {
        try {
            # 终态复核放在 finally，业务成功和任意失败路径都必须证明源码仍与启动快照一致。
            $finalBranch = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'branch', '--show-current')
            $finalCommit = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'rev-parse', 'HEAD')
            $finalStatus = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'status', '--porcelain=v1', '--untracked-files=all')
            $finalDigest = Get-Deploy04SourceSnapshotDigest -GitCommand $git -RepositoryRoot $repositoryRoot
            Assert-Deploy04SourceUnchanged $branch $finalBranch $commit $finalCommit `
                $workingTreeStatus $finalStatus $sourceDigest $finalDigest
            $evidence.sourceRecheck = 'PASSED'
        }
        catch {
            $evidence.sourceRecheck = 'FAILED'
            $finalizationFailures.Add('sourceRecheck=' + (Protect-MigrationSensitiveText `
                -Text $_.Exception.Message -SensitiveValues $sensitiveValues.ToArray()))
        }
    }

    if ($finalizationFailures.Count -gt 0) {
        $finalizationMessage = $finalizationFailures -join '; '
        $failure = if ([string]::IsNullOrWhiteSpace($failure)) { $finalizationMessage } else { "$failure; $finalizationMessage" }
        $failureExitCode = 1
    }
    $evidence.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $evidence.failure = $failure
    $evidence.overallStatus = if ([string]::IsNullOrWhiteSpace($failure)) { 'PASSED' } else { 'FAILED' }

    try {
        $writtenEvidence = Write-MigrationEvidenceSafely -Path $evidencePathToWrite `
            -RepositoryRoot $repositoryRoot -ForbiddenPaths $forbiddenEvidencePaths -Evidence $evidence
    }
    catch {
        # 自定义输出失败时也生成默认 TEMP 失败证据，且证据写入失败不能被误报为演练成功。
        $writeFailure = Protect-MigrationSensitiveText -Text $_.Exception.Message -SensitiveValues $sensitiveValues.ToArray()
        $failure = if ([string]::IsNullOrWhiteSpace($failure)) { "evidenceWrite=$writeFailure" } else { "$failure; evidenceWrite=$writeFailure" }
        $failureExitCode = 1
        $evidence.failure = $failure
        $evidence.overallStatus = 'FAILED'
        $writtenEvidence = Write-MigrationEvidenceSafely -Path $defaultEvidencePath `
            -RepositoryRoot $repositoryRoot -ForbiddenPaths $forbiddenEvidencePaths -Evidence $evidence
    }
    Write-Host "[DEPLOY-04] 迁移演练证据：$writtenEvidence"
}

if (-not [string]::IsNullOrWhiteSpace($failure)) {
    [Console]::Error.WriteLine("DEPLOY-04 迁移演练失败：$failure")
    exit $failureExitCode
}
Write-Host '[DEPLOY-04] 迁移演练通过。'
