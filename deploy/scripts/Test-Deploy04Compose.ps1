[CmdletBinding()]
param(
    [string]$RequiredBranch = 'deploy',
    [switch]$AllowDirtyWorkingTree,
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Deploy04.Common.ps1')

function Invoke-Deploy04ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$ExpectedPattern
    )

    $output = @(& $FilePath @ArgumentList 2>&1)
    if ($LASTEXITCODE -eq 0) {
        throw '预期命令失败，但命令返回了 0。'
    }
    $message = (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    if (-not [string]::IsNullOrWhiteSpace($ExpectedPattern) -and $message -notmatch $ExpectedPattern) {
        throw '命令虽然失败，但没有返回预期的受控失败类型。'
    }
    return $message
}

function Protect-Deploy04SensitiveText {
    param(
        [AllowNull()]
        [string]$Text,
        [object[]]$SensitiveValues = @()
    )

    $sanitized = $Text
    foreach ($sensitiveValue in $SensitiveValues) {
        if ($null -ne $sensitiveValue -and -not [string]::IsNullOrEmpty([string]$sensitiveValue)) {
            $sanitized = $sanitized.Replace([string]$sensitiveValue, '<redacted>')
        }
    }
    return $sanitized
}

function Wait-Deploy04HttpStatus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [Parameter(Mandatory = $true)]
        [int]$ExpectedStatus,
        [int]$TimeoutSeconds = 90
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastStatus = 0
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -Method Get -SkipHttpErrorCheck -TimeoutSec 10
            $lastStatus = [int]$response.StatusCode
            if ($lastStatus -eq $ExpectedStatus) {
                return [ordered]@{ status = $lastStatus; body = [string]$response.Content }
            }
        }
        catch {
            $lastStatus = 0
        }
        Start-Sleep -Seconds 2
    }
    throw "HTTP 状态等待超时：uri=$Uri, expected=$ExpectedStatus, actual=$lastStatus"
}

function Wait-Deploy04ContainerHealth {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [Parameter(Mandatory = $true)]
        [string]$ContainerId,
        [int]$TimeoutSeconds = 120
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $status = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
            'inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}', $ContainerId
        )
        if ($status -eq 'healthy' -or $status -eq 'running') {
            return $status
        }
        if ($status -eq 'exited' -or $status -eq 'dead') {
            throw "容器未能恢复运行：containerId=$ContainerId, status=$status"
        }
        Start-Sleep -Seconds 2
    }
    throw "等待容器健康超时：containerId=$ContainerId"
}

function Get-Deploy04ComposeServiceIds {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [Parameter(Mandatory = $true)]
        [string[]]$ComposeArguments
    )

    $ids = [ordered]@{}
    foreach ($service in @('mysql', 'redis', 'backend', 'frontend')) {
        $containerId = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList ($ComposeArguments + @('ps', '--quiet', $service))
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw "Compose 服务没有容器：$service"
        }
        $ids[$service] = $containerId
    }
    return $ids
}

function Assert-Deploy04RunningImages {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$ServiceIds,
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$Images,
        [Parameter(Mandatory = $true)]
        [ValidateSet('initialRunningId', 'restartedRunningId')]
        [string]$EvidenceField
    )

    foreach ($service in @('backend', 'frontend')) {
        # image inspect .Id 与 container inspect .Image 都是本地 immutable image content ID，属于同一层级。
        $runningImageId = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
            'inspect', '--format', '{{.Image}}', $ServiceIds[$service]
        )
        $configuredTag = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
            'inspect', '--format', '{{.Config.Image}}', $ServiceIds[$service]
        )
        if ($runningImageId -ne $Images[$service].expectedId -or $configuredTag -ne $Images[$service].tag) {
            throw "$service 容器运行镜像与当前提交候选镜像不一致。"
        }
        $Images[$service][$EvidenceField] = $runningImageId
    }
}

function Get-Deploy04PublishedHttpPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [Parameter(Mandatory = $true)]
        [string]$FrontendContainerId
    )

    $published = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
        'port', $FrontendContainerId, '8080/tcp'
    )
    if ($published -notmatch '^127\.0\.0\.1:(\d+)$') {
        throw "前端动态端口没有只绑定到 127.0.0.1：$published"
    }
    return [int]$Matches[1]
}

function Get-Deploy04ProjectResources {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [Parameter(Mandatory = $true)]
        [string]$ProjectName
    )

    $resourceTypes = [ordered]@{
        containers = [ordered]@{
            list = @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName")
            all = @('ps', '--all', '--format', '{{.ID}}|{{.Names}}')
        }
        networks = [ordered]@{
            list = @('network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName")
            all = @('network', 'ls', '--format', '{{.ID}}|{{.Name}}')
        }
        volumes = [ordered]@{
            list = @('volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$ProjectName")
            all = @('volume', 'ls', '--format', '{{.Name}}|{{.Name}}')
        }
    }
    $snapshot = [ordered]@{}
    foreach ($entry in $resourceTypes.GetEnumerator()) {
        $allResources = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList $entry.Value.all
        foreach ($line in @($allResources -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            $parts = $line -split '\|', 2
            if ($parts.Count -eq 2 -and
                    ($parts[1].StartsWith("$ProjectName-") -or $parts[1].StartsWith("$ProjectName`_"))) {
                $candidateId = $parts[0]
                if ($entry.Key -eq 'containers') {
                    $candidateLabel = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
                        'inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', $candidateId
                    )
                }
                elseif ($entry.Key -eq 'networks') {
                    $candidateLabel = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
                        'network', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}', $candidateId
                    )
                }
                else {
                    $candidateLabel = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
                        'volume', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}', $candidateId
                    )
                }
                if ($candidateLabel -ne $ProjectName) {
                    throw "发现名称属于本次 project 但标签不匹配的 Docker $($entry.Key)，拒绝清理：$candidateId"
                }
            }
        }

        $rawIds = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList $entry.Value.list
        $ids = @($rawIds -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $items = @()
        foreach ($id in $ids) {
            if ($entry.Key -eq 'containers') {
                $label = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
                    'inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', $id
                )
            }
            elseif ($entry.Key -eq 'networks') {
                $label = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
                    'network', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}', $id
                )
            }
            elseif ($entry.Key -eq 'volumes') {
                $label = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
                    'volume', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}', $id
                )
            }
            if ($label -ne $ProjectName) {
                throw "Docker $($entry.Key) 的 Compose project 标签不匹配，拒绝清理：$id"
            }
            $items += $id
        }
        $snapshot[$entry.Key] = $items
    }
    return $snapshot
}

function Assert-Deploy04NoPublishedPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [Parameter(Mandatory = $true)]
        [string]$ContainerId,
        [Parameter(Mandatory = $true)]
        [string]$Service
    )

    $published = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @('port', $ContainerId)
    if (-not [string]::IsNullOrWhiteSpace($published)) {
        throw "$Service 存在不应发布到宿主机的端口。"
    }
}

$repositoryRoot = Get-Deploy04RepositoryRoot -ScriptRoot $PSScriptRoot
$composeFile = Join-Path $repositoryRoot 'deploy\docker-compose.yml'
$runId = [Guid]::NewGuid().ToString('N')
$projectName = "campus-deploy04-$($runId.Substring(0, 12))"
$httpPort = $null
$baseUri = $null
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "crp-deploy04-compose-$runId"
$composeEnvironmentFile = Join-Path $temporaryDirectory 'compose.env'
$composeEnvironmentVariableNames = @(
    'COMPOSE_PROJECT_NAME', 'HTTP_PORT', 'BACKEND_IMAGE', 'FRONTEND_IMAGE',
    'MYSQL_ROOT_PASSWORD', 'MYSQL_APP_USERNAME', 'MYSQL_APP_PASSWORD',
    'REDIS_PASSWORD', 'JWT_SECRET', 'APP_UPLOAD_MIN_FREE_SPACE_BYTES',
    'RANK_DOWNLOAD_DELTA_SYNC_ENABLED', 'RANK_HOT_RANKING_SYNC_ENABLED'
)
$originalComposeEnvironment = @{}
$composeAttempted = $false
$storagePermissionNeedsRestore = $false
$originalUploadMode = $null
$serviceIds = $null
$branch = $null
$commit = $null
$shortCommit = $null
$workingTreeStatus = $null
$sourceSnapshotDigest = $null
$mysqlRootPassword = $null
$mysqlAppPassword = $null
$redisPassword = $null
$jwtSecret = $null
$overallStatus = 'FAILED'
$failureReason = $null
$startedAt = [DateTimeOffset]::UtcNow
$steps = [ordered]@{
    preflight = 'PENDING'
    composeUp = 'PENDING'
    health = 'PENDING'
    portIsolation = 'PENDING'
    redisAuthentication = 'PENDING'
    mysqlLeastPrivilege = 'PENDING'
    redisFailureRecovery = 'PENDING'
    mysqlFailureRecovery = 'PENDING'
    storageFailureRecovery = 'PENDING'
    persistenceWrite = 'PENDING'
    downWithoutVolumes = 'PENDING'
    composeRestart = 'PENDING'
    persistenceVerify = 'PENDING'
    sourceRecheck = 'PENDING'
}
$httpEvidence = [ordered]@{}
$permissionEvidence = [ordered]@{}
$persistenceEvidence = [ordered]@{}
$serviceHealth = [ordered]@{}
$images = [ordered]@{
    backend = [ordered]@{ tag = $null; expectedId = $null; initialRunningId = $null; restartedRunningId = $null }
    frontend = [ordered]@{ tag = $null; expectedId = $null; initialRunningId = $null; restartedRunningId = $null }
}
$cleanup = [ordered]@{
    compose = [ordered]@{ status = 'PENDING'; resourcesBefore = $null; residual = $null; message = $null }
    temporaryFiles = [ordered]@{ status = 'PENDING'; directory = $temporaryDirectory; message = $null }
}

try {
    $gitCommand = Assert-Deploy04Command -Name 'git'
    $dockerCommand = Assert-Deploy04Command -Name 'docker'
    Push-Location -LiteralPath $repositoryRoot
    try {
        $branch = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('branch', '--show-current')
        $commit = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('rev-parse', 'HEAD')
        $shortCommit = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('rev-parse', '--short=12', 'HEAD')
        $workingTreeStatus = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('status', '--porcelain')
    }
    finally {
        Pop-Location
    }
    $sourceSnapshotDigest = Get-Deploy04SourceSnapshotDigest -GitCommand $gitCommand -RepositoryRoot $repositoryRoot
    if ($branch -ne $RequiredBranch) {
        throw "当前分支为 '$branch'，要求分支为 '$RequiredBranch'。"
    }
    if (-not $AllowDirtyWorkingTree -and -not [string]::IsNullOrWhiteSpace($workingTreeStatus)) {
        throw '工作区存在未提交修改；正式 Compose 演练要求干净工作区。'
    }

    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('info', '--format', '{{.ServerVersion}}') | Out-Null
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('compose', 'version', '--short') | Out-Null
    $backendTag = "campus-resource-platform-backend:$shortCommit"
    $frontendTag = "campus-resource-platform-frontend:$shortCommit"
    $images.backend.tag = $backendTag
    $images.frontend.tag = $frontendTag
    $images.backend.expectedId = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('image', 'inspect', '--format', '{{.Id}}', $backendTag)
    $images.frontend.expectedId = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('image', 'inspect', '--format', '{{.Id}}', $frontendTag)
    $steps.preflight = 'PASSED'

    [System.IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
    $mysqlRootPassword = New-Deploy04Secret -Prefix 'Db9!RootAa'
    $mysqlAppPassword = New-Deploy04Secret -Prefix 'Db8!AppBb'
    $redisPassword = New-Deploy04Secret -Prefix 'Rd7!CacheCc'
    $jwtSecret = New-Deploy04Secret -Prefix 'Jw6!TokenDd'
    $composeEnvironmentValues = [ordered]@{
        COMPOSE_PROJECT_NAME = $projectName
        # 空宿主端口交给 Docker 原子分配，避免“先探测再释放”之间被其他进程抢占。
        HTTP_PORT = '127.0.0.1:'
        BACKEND_IMAGE = $backendTag
        FRONTEND_IMAGE = $frontendTag
        MYSQL_ROOT_PASSWORD = $mysqlRootPassword
        MYSQL_APP_USERNAME = 'campus_app'
        MYSQL_APP_PASSWORD = $mysqlAppPassword
        REDIS_PASSWORD = $redisPassword
        JWT_SECRET = $jwtSecret
        APP_UPLOAD_MIN_FREE_SPACE_BYTES = '1'
        RANK_DOWNLOAD_DELTA_SYNC_ENABLED = 'false'
        RANK_HOT_RANKING_SYNC_ENABLED = 'false'
    }
    foreach ($variableName in $composeEnvironmentVariableNames) {
        $originalComposeEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
    }
    foreach ($entry in $composeEnvironmentValues.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    @($composeEnvironmentValues.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) |
        Set-Content -LiteralPath $composeEnvironmentFile -Encoding utf8

    $composeArguments = @('compose', '--project-name', $projectName, '--env-file', $composeEnvironmentFile, '--file', $composeFile)
    $preexisting = Get-Deploy04ProjectResources -DockerCommand $dockerCommand -ProjectName $projectName
    if ($preexisting.containers.Count -ne 0 -or $preexisting.networks.Count -ne 0 -or $preexisting.volumes.Count -ne 0) {
        throw '唯一 Compose project 在启动前已存在 Docker 资源。'
    }
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @('config', '--quiet')) | Out-Null
    $steps.composeUp = 'RUNNING'
    $composeAttempted = $true
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @(
        'up', '--detach', '--wait', '--wait-timeout', '240', '--no-build', '--pull', 'never'
    )) | Out-Null
    $serviceIds = Get-Deploy04ComposeServiceIds -DockerCommand $dockerCommand -ComposeArguments $composeArguments
    Assert-Deploy04RunningImages -DockerCommand $dockerCommand -ServiceIds $serviceIds -Images $images -EvidenceField initialRunningId
    $httpPort = Get-Deploy04PublishedHttpPort -DockerCommand $dockerCommand -FrontendContainerId $serviceIds.frontend
    $baseUri = "http://127.0.0.1:$httpPort"
    $serviceHealth = [ordered]@{}
    foreach ($service in $serviceIds.Keys) {
        $serviceHealth[$service] = Wait-Deploy04ContainerHealth -DockerCommand $dockerCommand -ContainerId $serviceIds[$service]
    }
    $steps.composeUp = 'PASSED'

    $steps.health = 'RUNNING'
    $healthz = Wait-Deploy04HttpStatus -Uri "$baseUri/healthz" -ExpectedStatus 200
    $spa = Wait-Deploy04HttpStatus -Uri "$baseUri/deploy04/deep/link" -ExpectedStatus 200
    if ($spa.body -notmatch '<div\s+id="app"') {
        throw 'SPA 深链没有返回前端入口页面。'
    }
    $liveness = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health" -ExpectedStatus 200
    $readiness = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 200
    $httpEvidence.initial = [ordered]@{
        healthz = $healthz.status
        spaDeepLink = $spa.status
        liveness = $liveness.status
        readiness = $readiness.status
    }
    $steps.health = 'PASSED'

    $steps.portIsolation = 'RUNNING'
    foreach ($service in @('mysql', 'redis', 'backend')) {
        Assert-Deploy04NoPublishedPort -DockerCommand $dockerCommand -ContainerId $serviceIds[$service] -Service $service
    }
    $steps.portIsolation = 'PASSED'

    $steps.redisAuthentication = 'RUNNING'
    # redis-cli 对 Redis error reply 可能仍返回 0，因此按 NOAUTH 协议响应判断拒绝结果。
    $redisUnauthenticatedOutput = @(& $dockerCommand exec $serviceIds.redis redis-cli ping 2>&1)
    $redisUnauthenticated = (($redisUnauthenticatedOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    if ($redisUnauthenticated -notmatch 'NOAUTH|Authentication required') {
        throw 'Redis 无密码请求虽失败，但未返回认证错误。'
    }
    $redisPong = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.redis, 'sh', '-c', 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping'
    )
    if ($redisPong -ne 'PONG') {
        throw 'Redis 带密码 PING 未返回 PONG。'
    }
    $permissionEvidence.redis = [ordered]@{ unauthenticatedRejected = $true; authenticatedPong = $true }
    $steps.redisAuthentication = 'PASSED'

    $steps.mysqlLeastPrivilege = 'RUNNING'
    $permissionTable = "deploy04_permission_$($runId.Substring(0, 12))"
    $forbiddenTable = "deploy04_forbidden_$($runId.Substring(0, 12))"
    # 表名由固定前缀和十六进制 run-id 组成，可直接作为 SQL 标识符，避免 shell 解释反引号。
    $rootCreateSql = "CREATE TABLE $permissionTable (id BIGINT PRIMARY KEY, marker VARCHAR(64) NOT NULL)"
    $rootDropSql = "DROP TABLE IF EXISTS $permissionTable, $forbiddenTable"
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot campus_resource_platform -e "{0}"' -f $rootCreateSql)
    ) | Out-Null
    try {
        $grants = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
            'exec', $serviceIds.mysql, 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -u "$MYSQL_USER" -e "SHOW GRANTS FOR CURRENT_USER"'
        )
        foreach ($requiredGrant in @('SELECT', 'INSERT', 'UPDATE')) {
            if ($grants -notmatch "(?i)\b$requiredGrant\b") {
                throw "MySQL 应用账号缺少权限：$requiredGrant"
            }
        }
        foreach ($forbiddenGrant in @('ALL PRIVILEGES', 'CREATE', 'DROP', 'DELETE', 'ALTER', 'GRANT OPTION')) {
            if ($grants -match "(?i)\b$([regex]::Escape($forbiddenGrant))\b") {
                throw "MySQL 应用账号包含禁止权限：$forbiddenGrant"
            }
        }
        $appSql = "INSERT INTO $permissionTable VALUES (1, 'before'); UPDATE $permissionTable SET marker='after' WHERE id=1; SELECT marker FROM $permissionTable WHERE id=1"
        $appResult = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
            'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -u "$MYSQL_USER" campus_resource_platform -e "{0}"' -f $appSql)
        )
        if ($appResult -ne 'after') {
            throw 'MySQL 应用账号 SELECT/INSERT/UPDATE 实际操作未得到预期结果。'
        }
        Invoke-Deploy04ExpectedFailure -FilePath $dockerCommand -ArgumentList @(
            'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" campus_resource_platform -e "CREATE TABLE {0} (id INT)"' -f $forbiddenTable)
        ) -ExpectedPattern '(?is)ERROR\s+1142\b.*CREATE command denied' | Out-Null
        Invoke-Deploy04ExpectedFailure -FilePath $dockerCommand -ArgumentList @(
            'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" campus_resource_platform -e "DROP TABLE {0}"' -f $permissionTable)
        ) -ExpectedPattern '(?is)ERROR\s+1142\b.*DROP command denied' | Out-Null
        $permissionEvidence.mysql = [ordered]@{
            grantsOnlySelectInsertUpdate = $true
            selectInsertUpdateSucceeded = $true
            createRejected = $true
            dropRejected = $true
        }
    }
    finally {
        Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
            'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot campus_resource_platform -e "{0}"' -f $rootDropSql)
        ) | Out-Null
    }
    $steps.mysqlLeastPrivilege = 'PASSED'

    $steps.redisFailureRecovery = 'RUNNING'
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @('stop', 'redis')) | Out-Null
    $redisDown = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 503
    $redisLiveness = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health" -ExpectedStatus 200
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @('start', 'redis')) | Out-Null
    Wait-Deploy04ContainerHealth -DockerCommand $dockerCommand -ContainerId $serviceIds.redis | Out-Null
    $redisRecovered = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 200
    $httpEvidence.redisFailure = [ordered]@{
        readinessUnavailable = $redisDown.status
        livenessDuringFailure = $redisLiveness.status
        readinessRecovered = $redisRecovered.status
    }
    $steps.redisFailureRecovery = 'PASSED'

    $steps.mysqlFailureRecovery = 'RUNNING'
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @('stop', 'mysql')) | Out-Null
    $mysqlDown = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 503
    $mysqlLiveness = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health" -ExpectedStatus 200
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @('start', 'mysql')) | Out-Null
    Wait-Deploy04ContainerHealth -DockerCommand $dockerCommand -ContainerId $serviceIds.mysql | Out-Null
    $mysqlRecovered = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 200
    $httpEvidence.mysqlFailure = [ordered]@{
        readinessUnavailable = $mysqlDown.status
        livenessDuringFailure = $mysqlLiveness.status
        readinessRecovered = $mysqlRecovered.status
    }
    $steps.mysqlFailureRecovery = 'PASSED'

    $steps.storageFailureRecovery = 'RUNNING'
    $originalUploadMode = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', '--user', 'root', $serviceIds.backend, 'stat', '-c', '%a', '/data/uploads'
    )
    $storagePermissionNeedsRestore = $true
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', '--user', 'root', $serviceIds.backend, 'chmod', '0555', '/data/uploads'
    ) | Out-Null
    try {
        $storageDown = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 503
        $storageLiveness = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health" -ExpectedStatus 200
    }
    finally {
        Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
            'exec', '--user', 'root', $serviceIds.backend, 'chmod', $originalUploadMode, '/data/uploads'
        ) | Out-Null
        $storagePermissionNeedsRestore = $false
    }
    $storageRecovered = Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 200
    $httpEvidence.storageFailure = [ordered]@{
        readinessUnavailable = $storageDown.status
        livenessDuringFailure = $storageLiveness.status
        readinessRecovered = $storageRecovered.status
    }
    $steps.storageFailureRecovery = 'PASSED'

    $steps.persistenceWrite = 'RUNNING'
    $markerToken = "marker-$($runId.Substring(0, 16))"
    $persistenceTable = "deploy04_persistence_$($runId.Substring(0, 12))"
    $mysqlMarkerSql = "CREATE TABLE $persistenceTable (id BIGINT PRIMARY KEY, marker VARCHAR(64) NOT NULL); INSERT INTO $persistenceTable VALUES (1, '$markerToken')"
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot campus_resource_platform -e "{0}"' -f $mysqlMarkerSql)
    ) | Out-Null
    $redisMarkerKey = "deploy04:persistence:$($runId.Substring(0, 16))"
    $redisSet = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.redis, 'sh', '-c', ('redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SET "{0}" "{1}"' -f $redisMarkerKey, $markerToken)
    )
    if ($redisSet -ne 'OK') {
        throw 'Redis 持久化标记写入失败。'
    }
    $uploadMarkerFile = "deploy04-$($runId.Substring(0, 16)).marker"
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.backend, 'sh', '-c', ('printf "%s" "{0}" > "/data/uploads/{1}"' -f $markerToken, $uploadMarkerFile)
    ) | Out-Null
    $persistenceEvidence.written = [ordered]@{ mysql = $true; redis = $true; uploads = $true }
    $steps.persistenceWrite = 'PASSED'

    $steps.downWithoutVolumes = 'RUNNING'
    Get-Deploy04ProjectResources -DockerCommand $dockerCommand -ProjectName $projectName | Out-Null
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @('down', '--remove-orphans')) | Out-Null
    $steps.downWithoutVolumes = 'PASSED'

    $steps.composeRestart = 'RUNNING'
    Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @(
        'up', '--detach', '--wait', '--wait-timeout', '240', '--no-build', '--pull', 'never'
    )) | Out-Null
    $serviceIds = Get-Deploy04ComposeServiceIds -DockerCommand $dockerCommand -ComposeArguments $composeArguments
    Assert-Deploy04RunningImages -DockerCommand $dockerCommand -ServiceIds $serviceIds -Images $images -EvidenceField restartedRunningId
    $restartedHttpPort = Get-Deploy04PublishedHttpPort -DockerCommand $dockerCommand -FrontendContainerId $serviceIds.frontend
    if ($restartedHttpPort -ne $httpPort) {
        $httpPort = $restartedHttpPort
        $baseUri = "http://127.0.0.1:$httpPort"
    }
    Wait-Deploy04HttpStatus -Uri "$baseUri/api/v1/health/readiness" -ExpectedStatus 200 | Out-Null
    $steps.composeRestart = 'PASSED'

    $steps.persistenceVerify = 'RUNNING'
    $mysqlMarker = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.mysql, 'sh', '-c', ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -uroot campus_resource_platform -e "SELECT marker FROM {0} WHERE id=1"' -f $persistenceTable)
    )
    $redisMarker = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.redis, 'sh', '-c', ('redis-cli --no-auth-warning -a "$REDIS_PASSWORD" GET "{0}"' -f $redisMarkerKey)
    )
    $uploadMarker = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $serviceIds.backend, 'cat', "/data/uploads/$uploadMarkerFile"
    )
    $persistenceEvidence.verified = [ordered]@{
        mysql = $mysqlMarker -eq $markerToken
        redis = $redisMarker -eq $markerToken
        uploads = $uploadMarker -eq $markerToken
    }
    if ($persistenceEvidence.verified.Values -contains $false) {
        throw 'Compose down/up 后至少一个持久化标记丢失。'
    }
    $steps.persistenceVerify = 'PASSED'

    $steps.sourceRecheck = 'RUNNING'
    Push-Location -LiteralPath $repositoryRoot
    try {
        $finalBranch = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('branch', '--show-current')
        $finalCommit = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('rev-parse', 'HEAD')
        $finalWorkingTreeStatus = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('status', '--porcelain')
    }
    finally {
        Pop-Location
    }
    $finalSourceDigest = Get-Deploy04SourceSnapshotDigest -GitCommand $gitCommand -RepositoryRoot $repositoryRoot
    Assert-Deploy04SourceUnchanged -ExpectedBranch $branch -ActualBranch $finalBranch `
        -ExpectedCommit $commit -ActualCommit $finalCommit `
        -ExpectedWorkingTreeStatus $workingTreeStatus -ActualWorkingTreeStatus $finalWorkingTreeStatus `
        -ExpectedSourceDigest $sourceSnapshotDigest -ActualSourceDigest $finalSourceDigest
    $steps.sourceRecheck = 'PASSED'
    $overallStatus = 'PASSED'
}
catch {
    $failureReason = $_.Exception.Message
    foreach ($stepName in @($steps.Keys)) {
        if ($steps[$stepName] -eq 'RUNNING') {
            $steps[$stepName] = 'FAILED'
        }
    }
    if ($composeAttempted) {
        try {
            # 后端启动日志不会写入证据，仅在当前控制台辅助诊断；应用本身已禁止输出 Secret。
            $backendLogs = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @(
                'logs', '--no-color', '--tail', '120', 'backend'
            ))
            if (-not [string]::IsNullOrWhiteSpace($backendLogs)) {
                $failureReason = "$failureReason`n后端最近日志：`n$backendLogs"
            }
        }
        catch {
            # 日志提取失败不能覆盖原始错误，也不能阻断 finally 清理和证据写入。
        }
    }
    $failureReason = Protect-Deploy04SensitiveText -Text $failureReason -SensitiveValues @(
        $mysqlRootPassword, $mysqlAppPassword, $redisPassword, $jwtSecret
    )
    Write-Error $failureReason
}
finally {
    if ($storagePermissionNeedsRestore -and $null -ne $serviceIds -and -not [string]::IsNullOrWhiteSpace($originalUploadMode)) {
        try {
            Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
                'exec', '--user', 'root', $serviceIds.backend, 'chmod', $originalUploadMode, '/data/uploads'
            ) | Out-Null
            $storagePermissionNeedsRestore = $false
        }
        catch {
            $failureReason = '上传目录权限无法确认恢复。'
            $overallStatus = 'FAILED'
        }
    }

    try {
        if ($composeAttempted) {
            $cleanup.compose.resourcesBefore = Get-Deploy04ProjectResources -DockerCommand $dockerCommand -ProjectName $projectName
            Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList ($composeArguments + @(
                'down', '--volumes', '--remove-orphans'
            )) | Out-Null
            $cleanup.compose.residual = Get-Deploy04ProjectResources -DockerCommand $dockerCommand -ProjectName $projectName
            if ($cleanup.compose.residual.containers.Count -ne 0 -or
                    $cleanup.compose.residual.networks.Count -ne 0 -or
                    $cleanup.compose.residual.volumes.Count -ne 0) {
                throw '隔离 Compose project 清理后仍有 Docker 资源残留。'
            }
            $cleanup.compose.status = 'PASSED'
        }
        else {
            $cleanup.compose.status = 'NOT_CREATED'
        }
    }
    catch {
        $cleanup.compose.status = 'FAILED'
        $cleanup.compose.message = '无法确认隔离 Compose 资源已清理，请按 project 名人工核对。'
        $overallStatus = 'FAILED'
        if ($null -eq $failureReason) {
            $failureReason = '隔离 Compose 资源清理失败。'
        }
        Write-Warning "DEPLOY-04 Compose 清理失败：project=$projectName"
    }

    foreach ($variableName in $composeEnvironmentVariableNames) {
        if ($originalComposeEnvironment.ContainsKey($variableName)) {
            [Environment]::SetEnvironmentVariable($variableName, $originalComposeEnvironment[$variableName], 'Process')
        }
    }

    try {
        $cleanup.temporaryFiles = Remove-Deploy04TemporaryArtifacts `
            -Files @($composeEnvironmentFile) -Directory $temporaryDirectory
    }
    catch {
        $cleanup.temporaryFiles = [ordered]@{
            status = 'FAILED'; directory = $temporaryDirectory
            message = '临时 Secret/env 文件清理发生未预期错误。'
        }
    }
    if ($cleanup.temporaryFiles.status -eq 'FAILED') {
        $overallStatus = 'FAILED'
        if ($null -eq $failureReason) {
            $failureReason = '临时 Secret/env 文件清理失败。'
        }
    }

    if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
        $evidenceDirectory = Join-Path ([System.IO.Path]::GetTempPath()) 'campus-resource-platform\deploy-04\evidence'
        $EvidencePath = Join-Path $evidenceDirectory "compose-$($startedAt.ToString('yyyyMMddTHHmmssZ'))-$shortCommit.json"
    }
    $evidence = [ordered]@{
        timestamp = $startedAt.ToString('o')
        finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
        status = $overallStatus
        branch = $branch
        commit = $commit
        sourceSnapshotDigest = $sourceSnapshotDigest
        allowDirtyWorkingTree = [bool]$AllowDirtyWorkingTree
        project = $projectName
        httpPort = $httpPort
        images = $images
        services = if ($null -eq $serviceIds) { $null } else { $serviceHealth }
        steps = $steps
        http = $httpEvidence
        permissions = $permissionEvidence
        persistence = $persistenceEvidence
        cleanup = $cleanup
        failure = if ($null -eq $failureReason) { $null } else { '执行失败，详细原因见当前控制台输出。' }
    }
    $writtenEvidencePath = Write-Deploy04Evidence -Path $EvidencePath -Evidence $evidence
    Write-Host "[DEPLOY-04] Compose 演练证据：$writtenEvidencePath"
}

if ($overallStatus -ne 'PASSED') {
    exit 1
}
Write-Host '[DEPLOY-04] Compose 隔离运行、故障恢复和持久化演练全部通过。'
