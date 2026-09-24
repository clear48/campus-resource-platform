[CmdletBinding()]
param(
    [string]$RequiredBranch = 'deploy',
    [switch]$AllowDirtyWorkingTree,
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Deploy04.Common.ps1')

$previousCommit = '6449823045734a24f377c270b4d54d358d99b33c'
$databaseName = 'campus_resource_platform'
$mysqlImage = 'mysql:8.4.11@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d'
$redisImage = 'redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
$runLabel = 'com.campus-resource-platform.deploy04.rollback-run-id'

function Invoke-RollbackDocker {
    param([string]$Docker, [string[]]$Arguments)
    return Invoke-Deploy04Capture -FilePath $Docker -ArgumentList $Arguments
}

function Protect-RollbackSensitiveText {
    param([AllowNull()][string]$Text, [object[]]$SensitiveValues = @())
    $sanitized = $Text
    foreach ($value in $SensitiveValues) {
        if ($null -ne $value -and -not [string]::IsNullOrEmpty([string]$value)) {
            $sanitized = $sanitized.Replace([string]$value, '<redacted>')
        }
    }
    return $sanitized
}

function Invoke-RollbackCompose {
    param(
        [string]$Docker,
        [string]$ComposeFile,
        [string]$OverrideFile,
        [string]$EnvFile,
        [string]$Project,
        [string[]]$Arguments
    )
    $base = @('compose', '--env-file', $EnvFile, '-f', $ComposeFile, '-f', $OverrideFile, '-p', $Project)
    return Invoke-RollbackDocker -Docker $Docker -Arguments ($base + $Arguments)
}

function Wait-RollbackHealthy {
    param([string]$Docker, [string]$ContainerId, [int]$TimeoutSeconds = 180)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $status = Invoke-RollbackDocker $Docker @(
            'inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}', $ContainerId
        )
        if ($status -eq 'healthy') { return }
        if ($status -in @('exited', 'dead')) { throw "容器提前退出：$ContainerId ($status)" }
        Start-Sleep -Seconds 2
    }
    throw "等待容器健康超时：$ContainerId"
}

function Wait-RollbackHttp {
    param([string]$Uri, [int]$ExpectedStatus = 200, [int]$TimeoutSeconds = 120)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastStatus = 0
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -SkipHttpErrorCheck -TimeoutSec 10
            $lastStatus = [int]$response.StatusCode
            if ($lastStatus -eq $ExpectedStatus) { return }
        }
        catch { $lastStatus = 0 }
        Start-Sleep -Seconds 2
    }
    throw "HTTP 状态等待超时：expected=$ExpectedStatus actual=$lastStatus"
}

function Get-RollbackServiceId {
    param([string]$Docker, [string]$ComposeFile, [string]$OverrideFile, [string]$EnvFile, [string]$Project, [string]$Service)
    $id = Invoke-RollbackCompose $Docker $ComposeFile $OverrideFile $EnvFile $Project @('ps', '--quiet', $Service)
    if ([string]::IsNullOrWhiteSpace($id)) { throw "Compose 服务没有容器：project=$Project service=$Service" }
    return $id
}

function Invoke-RollbackMysql {
    param([string]$Docker, [string]$ContainerId, [string]$Sql, [string]$Database = '')
    $arguments = @('--protocol=socket', '-uroot', '--batch', '--skip-column-names')
    if (-not [string]::IsNullOrWhiteSpace($Database)) { $arguments += "--database=$Database" }
    $arguments += @('-e', $Sql)
    return Invoke-RollbackDocker $Docker (@(
        'exec', $ContainerId, 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql "$@"', 'deploy04-mysql'
    ) + $arguments)
}

function Invoke-RollbackMysqlFile {
    param([string]$Docker, [string]$ContainerId, [string]$LocalPath, [string]$RemoteName, [string]$Database = '')
    $remotePath = "/tmp/$RemoteName"
    Invoke-RollbackDocker $Docker @('cp', $LocalPath, "${ContainerId}:$remotePath") | Out-Null
    try {
        $databaseArgument = if ([string]::IsNullOrWhiteSpace($Database)) { '' } else { "--database=$Database" }
        Invoke-RollbackDocker $Docker @(
            'exec', $ContainerId, 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --commands --protocol=socket -uroot $1 < $2',
            'deploy04-source', $databaseArgument, $remotePath
        ) | Out-Null
    }
    finally {
        Invoke-RollbackDocker $Docker @('exec', $ContainerId, 'rm', '-f', $remotePath) | Out-Null
    }
}

function Invoke-RollbackRedis {
    param([string]$Docker, [string]$ContainerId, [string[]]$Arguments)
    return Invoke-RollbackDocker $Docker (@(
        'exec', $ContainerId, 'sh', '-c',
        'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" "$@"', 'deploy04-redis'
    ) + $Arguments)
}

function Assert-RollbackNoSyncingKeys {
    param([string]$Docker, [string]$ContainerId)
    $keys = Invoke-RollbackRedis $Docker $ContainerId @('--scan', '--pattern', 'crp:stats:resource:download:syncing:*')
    if (-not [string]::IsNullOrWhiteSpace($keys)) {
        throw '停止写入点仍存在下载同步协议 key，拒绝创建联合恢复点。'
    }
}

function Get-RollbackVolume {
    param([string]$Docker, [string]$Project, [string]$LogicalName)
    $raw = Invoke-RollbackDocker $Docker @(
        'volume', 'ls', '--quiet',
        '--filter', "label=com.docker.compose.project=$Project",
        '--filter', "label=com.docker.compose.volume=$LogicalName"
    )
    $volumes = @($raw -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($volumes.Count -ne 1) { throw "无法唯一定位 Compose volume：project=$Project logical=$LogicalName" }
    $volume = $volumes[0]
    $projectLabel = Invoke-RollbackDocker $Docker @('volume', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}', $volume)
    $logicalLabel = Invoke-RollbackDocker $Docker @('volume', 'inspect', '--format', '{{ index .Labels "com.docker.compose.volume" }}', $volume)
    if ($projectLabel -ne $Project -or $logicalLabel -ne $LogicalName) { throw "Compose volume 标签不匹配：$volume" }
    return $volume
}

function Invoke-RollbackVolumeHelper {
    param(
        [string]$Docker,
        [string]$Image,
        [string]$RunId,
        [string]$Volume,
        [string]$HostDirectory,
        [string]$Script,
        [switch]$ReadOnlyVolume
    )
    $volumeSpec = "type=volume,source=$Volume,target=/volume"
    if ($ReadOnlyVolume) { $volumeSpec += ',readonly' }
    return Invoke-RollbackDocker $Docker @(
        'run', '--rm', '--user', '0', '--label', "$runLabel=$RunId",
        '--entrypoint', 'sh',
        '--mount', $volumeSpec,
        '--mount', "type=bind,source=$HostDirectory,target=/backup",
        $Image, '-c', $Script
    )
}

function Backup-RollbackVolume {
    param([string]$Docker, [string]$Image, [string]$RunId, [string]$Volume, [string]$HostDirectory, [string]$ArchiveName)
    Invoke-RollbackVolumeHelper $Docker $Image $RunId $Volume $HostDirectory `
        "tar -C /volume -czf /backup/$ArchiveName ." -ReadOnlyVolume | Out-Null
    $archivePath = Join-Path $HostDirectory $ArchiveName
    if (-not (Test-Path -LiteralPath $archivePath)) { throw "卷归档没有生成：$ArchiveName" }
    return [ordered]@{
        path = $archivePath
        sha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $archivePath).Length
    }
}

function Restore-RollbackVolume {
    param([string]$Docker, [string]$Image, [string]$RunId, [string]$Volume, [string]$HostDirectory, [string]$ArchiveName)
    $existing = Invoke-RollbackVolumeHelper $Docker $Image $RunId $Volume $HostDirectory `
        'find /volume -mindepth 1 -print -quit' -ReadOnlyVolume
    if (-not [string]::IsNullOrWhiteSpace($existing)) { throw "恢复目标卷不是空卷：$Volume" }
    Invoke-RollbackVolumeHelper $Docker $Image $RunId $Volume $HostDirectory `
        "tar -C /volume -xzf /backup/$ArchiveName" | Out-Null
}

function Assert-RollbackImage {
    param([string]$Docker, [string]$ContainerId, [string]$ExpectedTag, [string]$ExpectedId, [string]$ExpectedRevision)
    $runningId = Invoke-RollbackDocker $Docker @('inspect', '--format', '{{.Image}}', $ContainerId)
    $configuredTag = Invoke-RollbackDocker $Docker @('inspect', '--format', '{{.Config.Image}}', $ContainerId)
    $revision = Invoke-RollbackDocker $Docker @('image', 'inspect', '--format', '{{ index .Config.Labels "org.opencontainers.image.revision" }}', $runningId)
    if ($runningId -ne $ExpectedId -or $configuredTag -ne $ExpectedTag -or $revision -ne $ExpectedRevision) {
        throw '运行容器的镜像 tag、immutable ID 或 revision 与预期不一致。'
    }
}

function Get-RollbackPublishedPort {
    param([string]$Docker, [string]$ContainerId)
    $published = Invoke-RollbackDocker $Docker @('port', $ContainerId, '8080/tcp')
    if ($published -notmatch '^127\.0\.0\.1:(\d+)$') { throw "前端端口未仅绑定到 127.0.0.1：$published" }
    return [int]$Matches[1]
}

function Remove-RollbackProject {
    param([string]$Docker, [string]$ComposeFile, [string]$OverrideFile, [string]$EnvFile, [string]$Project)
    $containers = Invoke-RollbackDocker $Docker @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
    foreach ($id in @($containers -split "`r?`n" | Where-Object { $_ })) {
        $label = Invoke-RollbackDocker $Docker @('inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', $id)
        if ($label -ne $Project) { throw "容器标签不匹配，拒绝清理：$id" }
    }
    Invoke-RollbackCompose $Docker $ComposeFile $OverrideFile $EnvFile $Project @('down', '--volumes', '--remove-orphans', '--timeout', '45') | Out-Null
    $remaining = @(
        Invoke-RollbackDocker $Docker @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
        Invoke-RollbackDocker $Docker @('network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
        Invoke-RollbackDocker $Docker @('volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$Project")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    if (@($remaining).Count -ne 0) { throw "Compose project 清理后仍有资源：$Project" }
}

function Remove-RollbackPreviousImage {
    param([string]$Docker, [string]$Tag, [string]$RunId)
    $id = Invoke-RollbackDocker $Docker @('image', 'inspect', '--format', '{{.Id}}', $Tag)
    $owner = Invoke-RollbackDocker $Docker @('image', 'inspect', '--format', "{{ index .Config.Labels `"$runLabel`" }}", $Tag)
    if ($owner -ne $RunId) { throw "旧镜像归属标签不匹配，拒绝删除：$Tag" }
    Invoke-RollbackDocker $Docker @('image', 'rm', $Tag) | Out-Null
    return $id
}

function Test-RollbackPathWithinRoot {
    param([string]$Root, [string]$Candidate)
    $separators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd($separators)
    $normalizedCandidate = [IO.Path]::GetFullPath($Candidate)
    $comparison = if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
        [StringComparison]::OrdinalIgnoreCase
    } else { [StringComparison]::Ordinal }
    return $normalizedCandidate.Equals($normalizedRoot, $comparison) -or
        $normalizedCandidate.StartsWith($normalizedRoot + [IO.Path]::DirectorySeparatorChar, $comparison)
}

function Assert-RollbackSafePath {
    param([string]$Path, [string]$RepositoryRoot, [switch]$AllowMissingLeaf)
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (Test-RollbackPathWithinRoot $RepositoryRoot $fullPath) { throw '临时或证据路径不得位于仓库内。' }
    $root = [IO.Path]::GetPathRoot($fullPath)
    $parts = [IO.Path]::GetRelativePath($root, $fullPath).Split(
        [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar),
        [StringSplitOptions]::RemoveEmptyEntries)
    $current = $root
    foreach ($part in $parts) {
        $current = Join-Path $current $part
        if (-not (Test-Path -LiteralPath $current)) { continue }
        $attributes = [IO.File]::GetAttributes($current)
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "路径包含 ReparsePoint：$current" }
    }
    if (-not $AllowMissingLeaf -and -not (Test-Path -LiteralPath $fullPath)) { throw "路径不存在：$fullPath" }
    return $fullPath
}

function Remove-RollbackTemporaryDirectory {
    param([string]$Directory, [string]$RepositoryRoot, [string]$ExpectedRoot)
    $fullDirectory = Assert-RollbackSafePath $Directory $RepositoryRoot
    $fullExpectedRoot = [IO.Path]::GetFullPath($ExpectedRoot)
    if (-not (Test-RollbackPathWithinRoot $fullExpectedRoot $fullDirectory) -or $fullDirectory -eq $fullExpectedRoot) {
        throw '临时目录不在本次任务专属根目录内，拒绝递归删除。'
    }
    Remove-Item -LiteralPath $fullDirectory -Recurse -Force
    if (Test-Path -LiteralPath $fullDirectory) { throw '本次临时目录清理失败。' }
}

$startedAt = [DateTimeOffset]::UtcNow
$runId = $startedAt.ToString('yyyyMMddTHHmmssZ').ToLowerInvariant() + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$repositoryRoot = Get-Deploy04RepositoryRoot -ScriptRoot $PSScriptRoot
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) 'campus-resource-platform\deploy-04\rollback-runs'
$tempDirectory = Join-Path $tempRoot $runId
$checkpointDirectory = Join-Path $tempDirectory 'checkpoint'
$archiveDirectory = Join-Path $tempDirectory 'previous-source'
$gitArchive = Join-Path $tempDirectory 'previous-source.zip'
$envFile = Join-Path $tempDirectory '.env'
$overrideFile = Join-Path $tempDirectory 'rollback.override.yml'
$composeFile = Join-Path $repositoryRoot 'deploy\docker-compose.yml'
$sourceProject = "campus-deploy04-source-$runId"
$rollbackProject = "campus-deploy04-rollback-$runId"
$defaultEvidencePath = Join-Path ([IO.Path]::GetTempPath()) "campus-resource-platform\deploy-04\evidence\rollback-$runId.json"
$stage = 'preflight'
$failure = $null
$exitCode = 1
$sourceSnapshotEstablished = $false
$tempCreated = $false
$sourceAttempted = $false
$rollbackAttempted = $false
$previousImagesBuilt = $false
$composeEnvironmentSet = $false
$composeEnvironmentVariableNames = @(
    'HTTP_PORT','BACKEND_IMAGE','FRONTEND_IMAGE','MYSQL_ROOT_PASSWORD','MYSQL_APP_USERNAME',
    'MYSQL_APP_PASSWORD','REDIS_PASSWORD','JWT_SECRET','APP_UPLOAD_MIN_FREE_SPACE_BYTES',
    'RANK_DOWNLOAD_DELTA_SYNC_ENABLED','RANK_HOT_RANKING_SYNC_ENABLED'
)
$originalComposeEnvironment = [ordered]@{}
$docker = $null
$git = $null
$branch = $null
$commit = $null
$workingTreeStatus = $null
$sourceDigest = $null
$candidateBackendTag = $null
$candidateFrontendTag = $null
$previousBackendTag = "campus-resource-platform-backend:deploy-02-$($previousCommit.Substring(0, 8))-$runId"
$previousFrontendTag = "campus-resource-platform-frontend:deploy-02-$($previousCommit.Substring(0, 8))-$runId"
$sensitiveValues = [Collections.Generic.List[string]]::new()

$evidence = [ordered]@{
    timestamp = $startedAt.ToString('o'); finishedAt = $null; runId = $runId
    branch = $null; commit = $null; sourceDigest = $null; allowDirtyWorkingTree = [bool]$AllowDirtyWorkingTree
    previousCommit = $previousCommit
    projects = [ordered]@{ source=$sourceProject; rollback=$rollbackProject }
    images = [ordered]@{ candidate=[ordered]@{}; previous=[ordered]@{} }
    migration = [ordered]@{ files=@(); assertions='NOT_RUN' }
    checkpoint = [ordered]@{ writeCutAt=$null; schedulersDisabled=$true; servicesStopped='NOT_RUN'; redis=@{}; mysql=@{}; uploads=@{} }
    restore = [ordered]@{ mysql='NOT_RUN'; redis='NOT_RUN'; uploads='NOT_RUN'; preCut='NOT_RUN'; postCutAbsent='NOT_RUN' }
    rollback = [ordered]@{ healthz='NOT_RUN'; liveness='NOT_RUN'; images='NOT_RUN'; mysql='NOT_RUN'; redis='NOT_RUN'; uploadRead='NOT_RUN'; uploadWrite='NOT_RUN' }
    sourceRecheck = 'NOT_RUN'
    cleanup = [ordered]@{ sourceProject='NOT_CREATED'; rollbackProject='NOT_CREATED'; previousImages='NOT_CREATED'; temporaryFiles='NOT_CREATED'; remainingResources=$null }
    failureStage = $null; failure = $null; overallStatus = 'RUNNING'
}

try {
    $git = Assert-Deploy04Command -Name 'git'
    $docker = Assert-Deploy04Command -Name 'docker'
    $branch = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'branch', '--show-current')
    $commit = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'rev-parse', 'HEAD')
    $workingTreeStatus = Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'status', '--porcelain=v1', '--untracked-files=all')
    $sourceDigest = Get-Deploy04SourceSnapshotDigest -GitCommand $git -RepositoryRoot $repositoryRoot
    $sourceSnapshotEstablished = $true
    $evidence.branch = $branch; $evidence.commit = $commit; $evidence.sourceDigest = $sourceDigest
    if ($branch -ne $RequiredBranch) { throw "必须在 $RequiredBranch 分支运行，当前为 $branch" }
    if (-not $AllowDirtyWorkingTree -and -not [string]::IsNullOrWhiteSpace($workingTreeStatus)) { throw '正式回滚演练要求干净工作区。' }
    Invoke-RollbackDocker $docker @('version', '--format', '{{.Server.Version}}') | Out-Null
    Invoke-RollbackDocker $docker @('compose', 'version', '--short') | Out-Null
    Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'cat-file', '-e', "$previousCommit^{commit}") | Out-Null

    Assert-RollbackSafePath $tempDirectory $repositoryRoot -AllowMissingLeaf | Out-Null
    [IO.Directory]::CreateDirectory($checkpointDirectory) | Out-Null
    [IO.Directory]::CreateDirectory($archiveDirectory) | Out-Null
    Assert-RollbackSafePath $tempDirectory $repositoryRoot | Out-Null
    $tempCreated = $true

    $mysqlRootPassword = New-Deploy04Secret -Prefix 'R4!RootAa'
    $mysqlAppPassword = New-Deploy04Secret -Prefix 'R4!AppBb'
    # 生产门禁会拒绝包含常见服务名的口令，前缀仅表达随机用途，不包含 redis/password 等弱口令片段。
    $redisPassword = New-Deploy04Secret -Prefix 'R4!CacheCc'
    $jwtSecret = New-Deploy04Secret -Prefix 'R4!JwtDd'
    foreach ($secret in @($mysqlRootPassword,$mysqlAppPassword,$redisPassword,$jwtSecret)) { $sensitiveValues.Add($secret) }

    $candidateBackendTag = "campus-resource-platform-backend:$($commit.Substring(0, 12))"
    $candidateFrontendTag = "campus-resource-platform-frontend:$($commit.Substring(0, 12))"
    $composeEnvironmentValues = [ordered]@{
        HTTP_PORT='127.0.0.1:'; BACKEND_IMAGE=$candidateBackendTag; FRONTEND_IMAGE=$candidateFrontendTag
        MYSQL_ROOT_PASSWORD=$mysqlRootPassword; MYSQL_APP_USERNAME='campus_app'; MYSQL_APP_PASSWORD=$mysqlAppPassword
        REDIS_PASSWORD=$redisPassword; JWT_SECRET=$jwtSecret; APP_UPLOAD_MIN_FREE_SPACE_BYTES='1'
        RANK_DOWNLOAD_DELTA_SYNC_ENABLED='false'; RANK_HOT_RANKING_SYNC_ENABLED='false'
    }
    # Compose 中进程环境变量优先于 --env-file；显式覆盖并在 finally 恢复，避免用户级旧变量污染隔离演练。
    foreach ($name in $composeEnvironmentVariableNames) {
        $originalComposeEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$composeEnvironmentValues[$name], 'Process')
    }
    $composeEnvironmentSet = $true
    @($composeEnvironmentValues.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) |
        Set-Content -LiteralPath $envFile -Encoding utf8
    @"
services:
  backend:
    image: `$`{BACKEND_IMAGE}
    environment:
      RANK_DOWNLOAD_DELTA_SYNC_ENABLED: "false"
      RANK_HOT_RANKING_SYNC_ENABLED: "false"
  frontend:
    image: `$`{FRONTEND_IMAGE}
"@ | Set-Content -LiteralPath $overrideFile -Encoding utf8NoBOM

    $stage = 'buildCandidateImages'
    Invoke-RollbackDocker $docker @('build', '--label', "org.opencontainers.image.revision=$commit", '-t', $candidateBackendTag, (Join-Path $repositoryRoot 'campus-resource-platform')) | Out-Null
    Invoke-RollbackDocker $docker @('build', '--label', "org.opencontainers.image.revision=$commit", '-t', $candidateFrontendTag, (Join-Path $repositoryRoot 'frontend')) | Out-Null
    $candidateBackendId = Invoke-RollbackDocker $docker @('image', 'inspect', '--format', '{{.Id}}', $candidateBackendTag)
    $candidateFrontendId = Invoke-RollbackDocker $docker @('image', 'inspect', '--format', '{{.Id}}', $candidateFrontendTag)
    $evidence.images.candidate = [ordered]@{ backendTag=$candidateBackendTag; backendId=$candidateBackendId; frontendTag=$candidateFrontendTag; frontendId=$candidateFrontendId; revision=$commit }

    $stage = 'buildPreviousImages'
    Invoke-Deploy04Capture $git @('-C', $repositoryRoot, 'archive', '--format=zip', "--output=$gitArchive", $previousCommit) | Out-Null
    Expand-Archive -LiteralPath $gitArchive -DestinationPath $archiveDirectory
    Invoke-RollbackDocker $docker @('build', '--label', "org.opencontainers.image.revision=$previousCommit", '--label', "$runLabel=$runId", '-t', $previousBackendTag, (Join-Path $archiveDirectory 'campus-resource-platform')) | Out-Null
    Invoke-RollbackDocker $docker @('build', '--label', "org.opencontainers.image.revision=$previousCommit", '--label', "$runLabel=$runId", '-t', $previousFrontendTag, (Join-Path $archiveDirectory 'frontend')) | Out-Null
    $previousImagesBuilt = $true
    $previousBackendId = Invoke-RollbackDocker $docker @('image', 'inspect', '--format', '{{.Id}}', $previousBackendTag)
    $previousFrontendId = Invoke-RollbackDocker $docker @('image', 'inspect', '--format', '{{.Id}}', $previousFrontendTag)
    $evidence.images.previous = [ordered]@{ backendTag=$previousBackendTag; backendId=$previousBackendId; frontendTag=$previousFrontendTag; frontendId=$previousFrontendId; revision=$previousCommit }

    $stage = 'sourceStart'
    $sourceAttempted = $true
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $sourceProject @('up', '-d', '--no-build', '--pull', 'never', 'mysql', 'redis') | Out-Null
    $sourceMysql = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $sourceProject 'mysql'
    $sourceRedis = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $sourceProject 'redis'
    Wait-RollbackHealthy $docker $sourceMysql
    Wait-RollbackHealthy $docker $sourceRedis

    $stage = 'legacyMigration'
    Invoke-RollbackMysql $docker $sourceMysql "DROP DATABASE IF EXISTS $databaseName; CREATE DATABASE $databaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" | Out-Null
    Invoke-RollbackMysqlFile $docker $sourceMysql (Join-Path $repositoryRoot 'deploy\fixtures\deploy-04-legacy-schema.sql') 'legacy-schema.sql' $databaseName
    Invoke-RollbackMysqlFile $docker $sourceMysql (Join-Path $repositoryRoot 'deploy\fixtures\deploy-04-legacy-seed.sql') 'legacy-seed.sql' $databaseName
    foreach ($migrationName in @('20260714_download_delta_sync_idempotency.sql','20260715_user_file_authorization.sql','20260813_resource_active_duplicate_guard.sql')) {
        Invoke-RollbackMysqlFile $docker $sourceMysql (Join-Path $repositoryRoot "sql\migrations\$migrationName") $migrationName $databaseName
        $evidence.migration.files += [ordered]@{ file=$migrationName; status='PASSED' }
    }
    Invoke-RollbackMysqlFile $docker $sourceMysql (Join-Path $repositoryRoot 'deploy\mysql\deploy-04-assertions.sql') 'assertions.sql' $databaseName
    $evidence.migration.assertions = 'PASSED'

    $stage = 'candidateStart'
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $sourceProject @('up', '-d', '--no-build', '--pull', 'never', 'backend', 'frontend') | Out-Null
    $sourceBackend = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $sourceProject 'backend'
    $sourceFrontend = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $sourceProject 'frontend'
    Wait-RollbackHealthy $docker $sourceBackend 240
    Wait-RollbackHealthy $docker $sourceFrontend 120
    Assert-RollbackImage $docker $sourceBackend $candidateBackendTag $candidateBackendId $commit
    Assert-RollbackImage $docker $sourceFrontend $candidateFrontendTag $candidateFrontendId $commit
    $sourcePort = Get-RollbackPublishedPort $docker $sourceFrontend
    Wait-RollbackHttp "http://127.0.0.1:$sourcePort/api/v1/health/readiness"

    $stage = 'createCheckpointMarkers'
    $cutId = [Guid]::NewGuid().ToString('N')
    $postCutId = [Guid]::NewGuid().ToString('N')
    Invoke-RollbackMysql $docker $sourceMysql "CREATE TABLE IF NOT EXISTS deploy04_checkpoint_marker (marker_id VARCHAR(64) PRIMARY KEY, created_at DATETIME NOT NULL); INSERT INTO deploy04_checkpoint_marker VALUES ('$cutId', NOW());" $databaseName | Out-Null
    Invoke-RollbackRedis $docker $sourceRedis @('SET', "crp:deploy04:checkpoint:$runId", $cutId) | Out-Null
    Invoke-RollbackDocker $docker @('exec', $sourceBackend, 'sh', '-c', 'umask 077; printf "%s" "$1" > /data/uploads/deploy04-checkpoint.marker', 'deploy04-marker', $cutId) | Out-Null
    $markerSha = Invoke-RollbackDocker $docker @('exec', $sourceBackend, 'sha256sum', '/data/uploads/deploy04-checkpoint.marker')
    $markerSha = ($markerSha -split '\s+')[0]

    $stage = 'stopWriteAndBackup'
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $sourceProject @('stop', '--timeout', '45', 'frontend', 'backend') | Out-Null
    foreach ($id in @($sourceFrontend,$sourceBackend)) {
        $state = Invoke-RollbackDocker $docker @('inspect', '--format', '{{.State.Status}}', $id)
        if ($state -ne 'exited') { throw "停止写入后应用容器仍在运行：$id" }
    }
    $evidence.checkpoint.servicesStopped = 'PASSED'
    Assert-RollbackNoSyncingKeys $docker $sourceRedis
    $partialFiles = Invoke-RollbackVolumeHelper $docker $candidateBackendTag $runId `
        (Get-RollbackVolume $docker $sourceProject 'uploads_data') $checkpointDirectory `
        'find /volume -type f -name "*.part" -print -quit' -ReadOnlyVolume
    if (-not [string]::IsNullOrWhiteSpace($partialFiles)) { throw '上传卷仍存在 .part 文件，拒绝创建恢复点。' }
    $waitAof = Invoke-RollbackRedis $docker $sourceRedis @('WAITAOF', '1', '0', '5000')
    $waitParts = @($waitAof -split "`r?`n" | Where-Object { $_ -match '^\d+$' })
    if ($waitParts.Count -lt 2 -or [int]$waitParts[0] -lt 1) { throw 'Redis WAITAOF 未确认本地 AOF 写入。' }
    $redisInfo = Invoke-RollbackRedis $docker $sourceRedis @('INFO', 'persistence')
    foreach ($required in @('loading:0','aof_enabled:1','aof_last_write_status:ok','aof_pending_bio_fsync:0')) {
        if ($redisInfo -notmatch [regex]::Escape($required)) { throw "Redis AOF 状态不满足恢复点要求：$required" }
    }
    $evidence.checkpoint.redis.aof = 'PASSED'
    $evidence.checkpoint.writeCutAt = [DateTimeOffset]::UtcNow.ToString('o')

    $dumpInContainer = "/tmp/deploy04-$runId.sql"
    Invoke-RollbackDocker $docker @('exec', $sourceMysql, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --protocol=socket -uroot --single-transaction --routines --triggers --events --hex-blob --set-gtid-purged=OFF --add-drop-database --databases "$1" > "$2"', 'deploy04-dump', $databaseName, $dumpInContainer) | Out-Null
    $dumpPath = Join-Path $checkpointDirectory 'mysql.sql'
    Invoke-RollbackDocker $docker @('cp', "${sourceMysql}:$dumpInContainer", $dumpPath) | Out-Null
    Invoke-RollbackDocker $docker @('exec', $sourceMysql, 'rm', '-f', $dumpInContainer) | Out-Null
    $evidence.checkpoint.mysql = [ordered]@{ sha256=(Get-FileHash $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant(); bytes=(Get-Item $dumpPath).Length }

    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $sourceProject @('stop', '--timeout', '45', 'redis', 'mysql') | Out-Null
    $sourceRedisVolume = Get-RollbackVolume $docker $sourceProject 'redis_data'
    $sourceUploadsVolume = Get-RollbackVolume $docker $sourceProject 'uploads_data'
    $redisArchive = Backup-RollbackVolume $docker $candidateBackendTag $runId $sourceRedisVolume $checkpointDirectory 'redis.tar.gz'
    $uploadsArchive = Backup-RollbackVolume $docker $candidateBackendTag $runId $sourceUploadsVolume $checkpointDirectory 'uploads.tar.gz'
    $evidence.checkpoint.redis.archiveSha256=$redisArchive.sha256; $evidence.checkpoint.redis.bytes=$redisArchive.bytes
    $evidence.checkpoint.uploads=[ordered]@{ archiveSha256=$uploadsArchive.sha256; bytes=$uploadsArchive.bytes; markerSha256=$markerSha }

    $stage = 'postCutMarkers'
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $sourceProject @('up', '-d', '--no-build', '--pull', 'never', 'mysql', 'redis') | Out-Null
    Wait-RollbackHealthy $docker $sourceMysql
    Wait-RollbackHealthy $docker $sourceRedis
    Invoke-RollbackMysql $docker $sourceMysql "INSERT INTO deploy04_checkpoint_marker VALUES ('$postCutId', NOW());" $databaseName | Out-Null
    Invoke-RollbackRedis $docker $sourceRedis @('SET', "crp:deploy04:postcut:$runId", $postCutId) | Out-Null
    Invoke-RollbackVolumeHelper $docker $candidateBackendTag $runId $sourceUploadsVolume $checkpointDirectory `
        'printf "%s" post-cut > /volume/deploy04-postcut.marker' | Out-Null

    $stage = 'restoreEmptyVolumes'
    $rollbackAttempted = $true
    @(
        $envLines = Get-Content -LiteralPath $envFile
        $envLines | ForEach-Object {
            if ($_ -like 'BACKEND_IMAGE=*') { "BACKEND_IMAGE=$previousBackendTag" }
            elseif ($_ -like 'FRONTEND_IMAGE=*') { "FRONTEND_IMAGE=$previousFrontendTag" }
            else { $_ }
        }
    ) | Set-Content -LiteralPath $envFile -Encoding utf8NoBOM
    [Environment]::SetEnvironmentVariable('BACKEND_IMAGE', $previousBackendTag, 'Process')
    [Environment]::SetEnvironmentVariable('FRONTEND_IMAGE', $previousFrontendTag, 'Process')
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $rollbackProject @('up', '-d', '--no-build', '--pull', 'never', 'mysql') | Out-Null
    $rollbackMysql = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $rollbackProject 'mysql'
    Wait-RollbackHealthy $docker $rollbackMysql
    # 只启动 MySQL 时 Compose 不会创建其他服务专用卷；create 仅创建停止状态容器和空卷，不会写入恢复目标。
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $rollbackProject @(
        'create', '--no-build', '--pull', 'never', 'redis', 'backend'
    ) | Out-Null
    $rollbackRedisVolume = Get-RollbackVolume $docker $rollbackProject 'redis_data'
    $rollbackUploadsVolume = Get-RollbackVolume $docker $rollbackProject 'uploads_data'
    Restore-RollbackVolume $docker $candidateBackendTag $runId $rollbackRedisVolume $checkpointDirectory 'redis.tar.gz'
    Restore-RollbackVolume $docker $candidateBackendTag $runId $rollbackUploadsVolume $checkpointDirectory 'uploads.tar.gz'
    Invoke-RollbackMysqlFile $docker $rollbackMysql $dumpPath 'restore.sql'
    $evidence.restore.mysql='PASSED'; $evidence.restore.redis='PASSED'; $evidence.restore.uploads='PASSED'

    $stage = 'startPreviousVersion'
    Invoke-RollbackCompose $docker $composeFile $overrideFile $envFile $rollbackProject @('up', '-d', '--no-build', '--pull', 'never', 'redis', 'backend', 'frontend') | Out-Null
    $rollbackRedis = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $rollbackProject 'redis'
    $rollbackBackend = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $rollbackProject 'backend'
    $rollbackFrontend = Get-RollbackServiceId $docker $composeFile $overrideFile $envFile $rollbackProject 'frontend'
    Wait-RollbackHealthy $docker $rollbackRedis
    Wait-RollbackHealthy $docker $rollbackBackend 240
    Wait-RollbackHealthy $docker $rollbackFrontend 120
    Assert-RollbackImage $docker $rollbackBackend $previousBackendTag $previousBackendId $previousCommit
    Assert-RollbackImage $docker $rollbackFrontend $previousFrontendTag $previousFrontendId $previousCommit
    $evidence.rollback.images='PASSED'
    $rollbackPort = Get-RollbackPublishedPort $docker $rollbackFrontend
    Wait-RollbackHttp "http://127.0.0.1:$rollbackPort/healthz"
    Wait-RollbackHttp "http://127.0.0.1:$rollbackPort/api/v1/health"
    $evidence.rollback.healthz='PASSED'; $evidence.rollback.liveness='PASSED'

    $stage = 'verifyRestore'
    $preDb = Invoke-RollbackMysql $docker $rollbackMysql "SELECT COUNT(*) FROM deploy04_checkpoint_marker WHERE marker_id='$cutId';" $databaseName
    $postDb = Invoke-RollbackMysql $docker $rollbackMysql "SELECT COUNT(*) FROM deploy04_checkpoint_marker WHERE marker_id='$postCutId';" $databaseName
    if ($preDb -ne '1' -or $postDb -ne '0') { throw 'MySQL 恢复点前后 marker 不符合预期。' }
    $preRedis = Invoke-RollbackRedis $docker $rollbackRedis @('EXISTS', "crp:deploy04:checkpoint:$runId")
    $postRedis = Invoke-RollbackRedis $docker $rollbackRedis @('EXISTS', "crp:deploy04:postcut:$runId")
    if ($preRedis -ne '1' -or $postRedis -ne '0') { throw 'Redis 恢复点前后 marker 不符合预期。' }
    $restoredMarkerSha = Invoke-RollbackDocker $docker @('exec', $rollbackBackend, 'sha256sum', '/data/uploads/deploy04-checkpoint.marker')
    $restoredMarkerSha = ($restoredMarkerSha -split '\s+')[0]
    if ($restoredMarkerSha -ne $markerSha) { throw '上传文件 marker 摘要与恢复点不一致。' }
    $postUpload = Invoke-RollbackDocker $docker @('exec', $rollbackBackend, 'sh', '-c', 'if [ -e /data/uploads/deploy04-postcut.marker ]; then echo 1; else echo 0; fi')
    if ($postUpload -ne '0') { throw '上传卷包含恢复点之后的 marker。' }
    Invoke-RollbackDocker $docker @('exec', $rollbackBackend, 'sh', '-c', 'umask 077; printf ok > /data/uploads/deploy04-old-write.tmp; test -s /data/uploads/deploy04-old-write.tmp; rm -f /data/uploads/deploy04-old-write.tmp') | Out-Null
    $evidence.restore.preCut='PASSED'; $evidence.restore.postCutAbsent='PASSED'
    $evidence.rollback.mysql='PASSED'; $evidence.rollback.redis='PASSED'; $evidence.rollback.uploadRead='PASSED'; $evidence.rollback.uploadWrite='PASSED'
    $exitCode = 0
}
catch {
    $safeMessage = Protect-RollbackSensitiveText -Text $_.Exception.Message -SensitiveValues $sensitiveValues.ToArray()
    $failure = "stage=$stage; $safeMessage"
    $evidence.failureStage = $stage
    $evidence.failure = $failure
    if ($stage -eq 'candidateStart' -and $null -ne $docker) {
        try {
            $failedBackend = Invoke-RollbackDocker $docker @(
                'ps', '--all', '--quiet',
                '--filter', "label=com.docker.compose.project=$sourceProject",
                '--filter', 'label=com.docker.compose.service=backend'
            )
            if (-not [string]::IsNullOrWhiteSpace($failedBackend)) {
                $diagnostic = Invoke-RollbackDocker $docker @('logs', '--tail', '120', $failedBackend)
                Write-Warning (Protect-RollbackSensitiveText -Text $diagnostic -SensitiveValues $sensitiveValues.ToArray())
            }
        }
        catch { Write-Warning '无法读取失败后端的脱敏诊断日志。' }
    }
}
finally {
    $finalizationFailures = [Collections.Generic.List[string]]::new()
    if ($sourceAttempted -and $null -ne $docker -and $tempCreated) {
        try { Remove-RollbackProject $docker $composeFile $overrideFile $envFile $sourceProject; $evidence.cleanup.sourceProject='PASSED' }
        catch { $evidence.cleanup.sourceProject='FAILED'; $finalizationFailures.Add('sourceProjectCleanup') }
    }
    if ($rollbackAttempted -and $null -ne $docker -and $tempCreated) {
        try { Remove-RollbackProject $docker $composeFile $overrideFile $envFile $rollbackProject; $evidence.cleanup.rollbackProject='PASSED' }
        catch { $evidence.cleanup.rollbackProject='FAILED'; $finalizationFailures.Add('rollbackProjectCleanup') }
    }
    if ($previousImagesBuilt -and $null -ne $docker) {
        try {
            Remove-RollbackPreviousImage $docker $previousBackendTag $runId | Out-Null
            Remove-RollbackPreviousImage $docker $previousFrontendTag $runId | Out-Null
            $evidence.cleanup.previousImages='PASSED'
        }
        catch { $evidence.cleanup.previousImages='FAILED'; $finalizationFailures.Add('previousImageCleanup') }
    }
    if ($tempCreated) {
        try { Remove-RollbackTemporaryDirectory $tempDirectory $repositoryRoot $tempRoot; $evidence.cleanup.temporaryFiles='PASSED' }
        catch { $evidence.cleanup.temporaryFiles='FAILED'; $finalizationFailures.Add('temporaryCleanup') }
    }
    if ($composeEnvironmentSet) {
        foreach ($name in $composeEnvironmentVariableNames) {
            [Environment]::SetEnvironmentVariable($name, $originalComposeEnvironment[$name], 'Process')
        }
    }
    if ($null -ne $docker) {
        try {
            $remaining = @(
                Invoke-RollbackDocker $docker @('ps','--all','--quiet','--filter',"label=com.docker.compose.project=$sourceProject")
                Invoke-RollbackDocker $docker @('ps','--all','--quiet','--filter',"label=com.docker.compose.project=$rollbackProject")
                Invoke-RollbackDocker $docker @('volume','ls','--quiet','--filter',"label=com.docker.compose.project=$sourceProject")
                Invoke-RollbackDocker $docker @('volume','ls','--quiet','--filter',"label=com.docker.compose.project=$rollbackProject")
                Invoke-RollbackDocker $docker @('ps','--all','--quiet','--filter',"label=$runLabel=$runId")
            ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
            $evidence.cleanup.remainingResources=@($remaining).Count
            if (@($remaining).Count -ne 0) { throw '仍有本次 Docker 资源。' }
        }
        catch { $evidence.cleanup.remainingResources=-1; $finalizationFailures.Add('residualCheck') }
    }
    if ($sourceSnapshotEstablished) {
        try {
            $finalBranch=Invoke-Deploy04Capture $git @('-C',$repositoryRoot,'branch','--show-current')
            $finalCommit=Invoke-Deploy04Capture $git @('-C',$repositoryRoot,'rev-parse','HEAD')
            $finalStatus=Invoke-Deploy04Capture $git @('-C',$repositoryRoot,'status','--porcelain=v1','--untracked-files=all')
            $finalDigest=Get-Deploy04SourceSnapshotDigest -GitCommand $git -RepositoryRoot $repositoryRoot
            Assert-Deploy04SourceUnchanged $branch $finalBranch $commit $finalCommit $workingTreeStatus $finalStatus $sourceDigest $finalDigest
            $evidence.sourceRecheck='PASSED'
        }
        catch { $evidence.sourceRecheck='FAILED'; $finalizationFailures.Add('sourceRecheck') }
    }
    if ($finalizationFailures.Count -gt 0) { $failure = if ($failure) { "$failure; finalization=$($finalizationFailures -join ',')" } else { "finalization=$($finalizationFailures -join ',')" }; $exitCode=1 }
    $evidence.finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    $evidence.failure=$failure
    $evidence.overallStatus=if ($exitCode -eq 0 -and [string]::IsNullOrWhiteSpace($failure)) {'PASSED'} else {'FAILED'}
    if ([string]::IsNullOrWhiteSpace($EvidencePath)) { $EvidencePath=$defaultEvidencePath }
    try {
        $safeEvidencePath=Assert-RollbackSafePath $EvidencePath $repositoryRoot -AllowMissingLeaf
        $evidenceDirectory=Split-Path -Parent $safeEvidencePath
        [IO.Directory]::CreateDirectory($evidenceDirectory)|Out-Null
        Assert-RollbackSafePath $evidenceDirectory $repositoryRoot | Out-Null
        $written=Write-Deploy04Evidence -Path $safeEvidencePath -Evidence $evidence
        Write-Host "[DEPLOY-04] 回滚演练证据：$written"
    }
    catch {
        [Console]::Error.WriteLine('DEPLOY-04 回滚演练证据写入失败。')
        $exitCode=1
    }
}

if ($exitCode -ne 0) {
    [Console]::Error.WriteLine("DEPLOY-04 回滚演练失败：$failure")
    exit $exitCode
}
Write-Host '[DEPLOY-04] 联合恢复与旧镜像回滚演练通过。'
