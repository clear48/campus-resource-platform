[CmdletBinding()]
param(
    [string]$RequiredBranch = 'deploy',
    [switch]$AllowDirtyWorkingTree,
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Deploy04.Common.ps1')

$repositoryRoot = Get-Deploy04RepositoryRoot -ScriptRoot $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'campus-resource-platform'
$frontendDirectory = Join-Path $repositoryRoot 'frontend'
$composeFile = Join-Path $repositoryRoot 'deploy\docker-compose.yml'
$runId = [Guid]::NewGuid().ToString('N')
$containerName = "crp-deploy04-mysql-$($runId.Substring(0, 12))"
$containerLabelKey = 'com.campus-resource-platform.deploy04.run-id'
$mysqlImage = 'mysql:8.4.11@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d'
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "crp-deploy04-$runId"
$mysqlEnvironmentFile = Join-Path $temporaryDirectory 'mysql.env'
$composeEnvironmentFile = Join-Path $temporaryDirectory 'compose.env'
$createdContainerId = $null
$environmentVariableNames = @('MYSQL_TEST_URL', 'MYSQL_TEST_USERNAME', 'MYSQL_TEST_PASSWORD')
$originalEnvironment = @{}
$steps = [ordered]@{
    backendTests = 'PENDING'
    backendPackage = 'PENDING'
    frontendInstall = 'PENDING'
    frontendTests = 'PENDING'
    frontendBuild = 'PENDING'
    composeConfig = 'PENDING'
    composeBuild = 'PENDING'
    sourceRecheck = 'PENDING'
}
$versions = [ordered]@{}
$images = [ordered]@{
    backend = [ordered]@{ tag = $null; id = $null }
    frontend = [ordered]@{ tag = $null; id = $null }
}
$branch = $null
$commit = $null
$shortCommit = $null
$sourceSnapshotDigest = $null
$overallStatus = 'FAILED'
$failureReason = $null
$startedAt = [DateTimeOffset]::UtcNow
$cleanup = [ordered]@{
    container = [ordered]@{ status = 'PENDING'; name = $containerName; id = $null; message = $null }
    temporaryFiles = [ordered]@{ status = 'PENDING'; directory = $temporaryDirectory; message = $null }
}

try {
    $gitCommand = Assert-Deploy04Command -Name 'git'
    $dockerCommand = Assert-Deploy04Command -Name 'docker'
    $javaCommand = Assert-Deploy04Command -Name 'java'
    $nodeCommand = Assert-Deploy04Command -Name 'node'
    $npmCommand = Assert-Deploy04Command -Name 'npm'

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
        throw '工作区存在未提交修改；提交后重试，或仅在开发验证时显式传入 -AllowDirtyWorkingTree。'
    }

    # 版本检查同时验证 Docker Engine 可访问，避免只检测到客户端命令。
    $versions.docker = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('version', '--format', '{{.Client.Version}} (engine {{.Server.Version}})')
    $versions.compose = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('compose', 'version', '--short')
    $versions.java = Invoke-Deploy04Capture -FilePath $javaCommand -ArgumentList @('-version')
    $versions.node = Invoke-Deploy04Capture -FilePath $nodeCommand -ArgumentList @('--version')
    $versions.npm = Invoke-Deploy04Capture -FilePath $npmCommand -ArgumentList @('--version')

    [System.IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null
    $mysqlRootPassword = New-Deploy04Secret -Prefix 'Db9!RootAa'
    $mysqlEnvironment = @(
        "MYSQL_ROOT_PASSWORD=$mysqlRootPassword"
        'TZ=Asia/Shanghai'
    )
    $mysqlEnvironment | Set-Content -LiteralPath $mysqlEnvironmentFile -Encoding utf8

    Write-Host '[DEPLOY-04] 启动一次性隔离 MySQL 8.4.11（随机绑定 127.0.0.1 端口）'
    $createdContainerId = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'run', '--detach',
        '--name', $containerName,
        '--label', "$containerLabelKey=$runId",
        '--publish', '127.0.0.1::3306/tcp',
        '--env-file', $mysqlEnvironmentFile,
        $mysqlImage,
        '--character-set-server=utf8mb4',
        '--collation-server=utf8mb4_0900_ai_ci'
    )
    $cleanup.container.id = $createdContainerId

    # 官方 MySQL 镜像没有容器 HEALTHCHECK，直接在容器内轮询 mysqladmin。
    $mysqlReady = $false
    $readyDeadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    while ([DateTimeOffset]::UtcNow -lt $readyDeadline) {
        & $dockerCommand exec $createdContainerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqladmin ping -h 127.0.0.1 -uroot --silent' *> $null
        if ($LASTEXITCODE -eq 0) {
            $mysqlReady = $true
            break
        }
        $containerRunning = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('inspect', '--format', '{{.State.Running}}', $createdContainerId)
        if ($containerRunning -ne 'true') {
            # 保留退出日志到异常中用于定位镜像或主机问题，finally 仍只删除本次带标签的容器。
            $mysqlStartupLogs = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('logs', '--tail', '80', $createdContainerId)
            throw "一次性 MySQL 容器在完成初始化前退出。`n$mysqlStartupLogs"
        }
        Start-Sleep -Seconds 2
    }
    if (-not $mysqlReady) {
        throw '等待一次性 MySQL 就绪超时。'
    }

    $mysqlHostPort = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @(
        'inspect', '--format', '{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostPort}}', $createdContainerId
    )
    if ($mysqlHostPort -notmatch '^\d+$') {
        throw '无法解析一次性 MySQL 的宿主机随机端口。'
    }

    foreach ($variableName in $environmentVariableNames) {
        $originalEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
    }
    [Environment]::SetEnvironmentVariable(
        'MYSQL_TEST_URL',
        "jdbc:mysql://127.0.0.1:$mysqlHostPort/campus_resource_platform_audit_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        'Process'
    )
    [Environment]::SetEnvironmentVariable('MYSQL_TEST_USERNAME', 'root', 'Process')
    [Environment]::SetEnvironmentVariable('MYSQL_TEST_PASSWORD', $mysqlRootPassword, 'Process')

    $mavenWrapper = Join-Path $backendDirectory 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "缺少 Maven Wrapper：$mavenWrapper"
    }
    Invoke-Deploy04Step -Name 'backendTests' -WorkingDirectory $backendDirectory -FilePath $mavenWrapper -ArgumentList @('test') -StepResults $steps
    Invoke-Deploy04Step -Name 'backendPackage' -WorkingDirectory $backendDirectory -FilePath $mavenWrapper -ArgumentList @('-DskipTests', 'package') -StepResults $steps
    Invoke-Deploy04Step -Name 'frontendInstall' -WorkingDirectory $frontendDirectory -FilePath $npmCommand -ArgumentList @('ci') -StepResults $steps
    Invoke-Deploy04Step -Name 'frontendTests' -WorkingDirectory $frontendDirectory -FilePath $npmCommand -ArgumentList @('run', 'test:unit') -StepResults $steps
    Invoke-Deploy04Step -Name 'frontendBuild' -WorkingDirectory $frontendDirectory -FilePath $npmCommand -ArgumentList @('run', 'build') -StepResults $steps

    $backendTag = "campus-resource-platform-backend:$shortCommit"
    $frontendTag = "campus-resource-platform-frontend:$shortCommit"
    $images.backend.tag = $backendTag
    $images.frontend.tag = $frontendTag
    $mysqlAppPassword = New-Deploy04Secret -Prefix 'Db8!AppBb'
    $redisPassword = New-Deploy04Secret -Prefix 'Rd7!CacheCc'
    $jwtSecret = New-Deploy04Secret -Prefix 'Jw6!TokenDd'
    @(
        "COMPOSE_PROJECT_NAME=campus-deploy04-build-$shortCommit"
        'HTTP_PORT=18080'
        "BACKEND_IMAGE=$backendTag"
        "FRONTEND_IMAGE=$frontendTag"
        "MYSQL_ROOT_PASSWORD=$mysqlRootPassword"
        'MYSQL_APP_USERNAME=campus_app'
        "MYSQL_APP_PASSWORD=$mysqlAppPassword"
        "REDIS_PASSWORD=$redisPassword"
        "JWT_SECRET=$jwtSecret"
        'APP_UPLOAD_MIN_FREE_SPACE_BYTES=5368709120'
    ) | Set-Content -LiteralPath $composeEnvironmentFile -Encoding utf8

    # config 只做静默校验，禁止把含 Secret 的展开配置打印到控制台。
    Invoke-Deploy04Step -Name 'composeConfig' -WorkingDirectory $repositoryRoot -FilePath $dockerCommand -ArgumentList @(
        'compose', '--env-file', $composeEnvironmentFile, '--file', $composeFile, 'config', '--quiet'
    ) -StepResults $steps
    Invoke-Deploy04Step -Name 'composeBuild' -WorkingDirectory $repositoryRoot -FilePath $dockerCommand -ArgumentList @(
        'compose', '--env-file', $composeEnvironmentFile, '--file', $composeFile, 'build'
    ) -StepResults $steps

    $images.backend.id = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('image', 'inspect', '--format', '{{.Id}}', $backendTag)
    $images.frontend.id = Invoke-Deploy04Capture -FilePath $dockerCommand -ArgumentList @('image', 'inspect', '--format', '{{.Id}}', $frontendTag)

    # 构建结束后再次核对源码快照，避免共享工作区变化被错误标记为初始 commit 的镜像。
    $steps.sourceRecheck = 'RUNNING'
    try {
        Push-Location -LiteralPath $repositoryRoot
        try {
            $finalBranch = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('branch', '--show-current')
            $finalCommit = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('rev-parse', 'HEAD')
            $finalWorkingTreeStatus = Invoke-Deploy04Capture -FilePath $gitCommand -ArgumentList @('status', '--porcelain')
        }
        finally {
            Pop-Location
        }
        $finalSourceSnapshotDigest = Get-Deploy04SourceSnapshotDigest -GitCommand $gitCommand -RepositoryRoot $repositoryRoot
        Assert-Deploy04SourceUnchanged -ExpectedBranch $branch -ActualBranch $finalBranch `
            -ExpectedCommit $commit -ActualCommit $finalCommit `
            -ExpectedWorkingTreeStatus $workingTreeStatus -ActualWorkingTreeStatus $finalWorkingTreeStatus `
            -ExpectedSourceDigest $sourceSnapshotDigest -ActualSourceDigest $finalSourceSnapshotDigest
        $steps.sourceRecheck = 'PASSED'
    }
    catch {
        $steps.sourceRecheck = 'FAILED'
        throw
    }
    $overallStatus = 'PASSED'
}
catch {
    $failureReason = $_.Exception.Message
    Write-Error $failureReason
}
finally {
    foreach ($variableName in $environmentVariableNames) {
        if ($originalEnvironment.ContainsKey($variableName)) {
            [Environment]::SetEnvironmentVariable($variableName, $originalEnvironment[$variableName], 'Process')
        }
    }

    try {
        if ([string]::IsNullOrWhiteSpace($createdContainerId)) {
            $cleanup.container = [ordered]@{ status = 'NOT_CREATED'; name = $containerName; id = $null; message = $null }
        }
        else {
            $cleanup.container = Remove-Deploy04OwnedContainer -DockerCommand $dockerCommand `
                -ContainerId $createdContainerId -ContainerName $containerName `
                -LabelKey $containerLabelKey -ExpectedRunId $runId
        }
    }
    catch {
        # finally 内不再抛出二次异常，确保失败证据仍能落盘。
        $cleanup.container = [ordered]@{
            status = 'FAILED'; name = $containerName; id = $createdContainerId
            message = '容器清理发生未预期错误，请按 name/id 人工核对。'
        }
    }

    try {
        $cleanup.temporaryFiles = Remove-Deploy04TemporaryArtifacts `
            -Files @($mysqlEnvironmentFile, $composeEnvironmentFile) -Directory $temporaryDirectory
    }
    catch {
        $cleanup.temporaryFiles = [ordered]@{
            status = 'FAILED'; directory = $temporaryDirectory
            message = '临时文件清理发生未预期错误，请人工核对临时目录。'
        }
    }

    if ($cleanup.container.status -eq 'FAILED' -or $cleanup.temporaryFiles.status -eq 'FAILED') {
        $overallStatus = 'FAILED'
        if ($null -eq $failureReason) {
            $failureReason = '本次容器或临时 Secret/env 文件无法确认清理完成。'
        }
        Write-Warning "DEPLOY-04 清理未完成：containerName=$containerName, containerId=$createdContainerId, temporaryDirectory=$temporaryDirectory"
    }

    if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
        $evidenceDirectory = Join-Path ([System.IO.Path]::GetTempPath()) 'campus-resource-platform\deploy-04\evidence'
        $evidenceFileName = "build-$($startedAt.ToString('yyyyMMddTHHmmssZ'))-$shortCommit.json"
        $EvidencePath = Join-Path $evidenceDirectory $evidenceFileName
    }
    $evidence = [ordered]@{
        timestamp = $startedAt.ToString('o')
        finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
        status = $overallStatus
        branch = $branch
        commit = $commit
        sourceSnapshotDigest = $sourceSnapshotDigest
        allowDirtyWorkingTree = [bool]$AllowDirtyWorkingTree
        versions = $versions
        steps = $steps
        images = $images
        cleanup = $cleanup
        failure = if ($null -eq $failureReason) { $null } else { '执行失败，详细原因见当前控制台输出。' }
    }
    $writtenEvidencePath = Write-Deploy04Evidence -Path $EvidencePath -Evidence $evidence
    Write-Host "[DEPLOY-04] 证据文件：$writtenEvidencePath"
}

if ($overallStatus -ne 'PASSED') {
    exit 1
}
Write-Host '[DEPLOY-04] 本地测试、构建和候选镜像验证全部通过。'
