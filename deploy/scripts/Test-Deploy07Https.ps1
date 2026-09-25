[CmdletBinding()]
param(
    [switch]$StaticOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Deploy07 {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-Deploy07Capture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )
    $output = @(& $FilePath @ArgumentList 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $message = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "命令执行失败（退出码 $LASTEXITCODE）：$FilePath`n$message"
    }
    return (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
}

function Get-Deploy07PublishedPort {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][int]$ContainerPort
    )
    $mapping = Invoke-Deploy07Capture -FilePath $DockerCommand -ArgumentList @(
        'port', $ContainerId, "$ContainerPort/tcp"
    )
    $firstMapping = @($mapping -split "`r?`n")[0]
    Assert-Deploy07 ($firstMapping -match ':(\d+)$') "无法解析容器端口 $ContainerPort 的随机宿主机端口。"
    return [int]$Matches[1]
}

function Invoke-Deploy07Http {
    param(
        [Parameter(Mandatory = $true)][uri]$Uri,
        [Parameter(Mandatory = $true)][string]$HostHeader
    )
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    # 证书由本测试临时自签，仅在 loopback 隔离端口上关闭链验证。
    $handler.ServerCertificateCustomValidationCallback =
        [System.Net.Http.HttpClientHandler]::DangerousAcceptAnyServerCertificateValidator
    $client = [System.Net.Http.HttpClient]::new($handler)
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $Uri)
        $request.Headers.Host = $HostHeader
        $response = $null
        try {
            $response = $client.Send($request)
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return [ordered]@{
                status = [int]$response.StatusCode
                location = if ($null -eq $response.Headers.Location) { $null } else { $response.Headers.Location.ToString() }
                body = $body
            }
        }
        finally {
            $request.Dispose()
            if ($null -ne $response) { $response.Dispose() }
        }
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Wait-Deploy07Healthy {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)][string]$ContainerId
    )
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $health = Invoke-Deploy07Capture -FilePath $DockerCommand -ArgumentList @(
            'inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}', $ContainerId
        )
        if ($health -eq 'healthy') { return }
        if ($health -eq 'unhealthy') { throw 'frontend 容器进入 unhealthy。' }
        Start-Sleep -Seconds 1
    }
    throw '等待 frontend 健康超时。'
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$baseCompose = Join-Path $repositoryRoot 'deploy\docker-compose.yml'
$acmeCompose = Join-Path $repositoryRoot 'deploy\docker-compose.acme.yml'
$httpsCompose = Join-Path $repositoryRoot 'deploy\docker-compose.https.yml'
$httpsConfig = Join-Path $repositoryRoot 'deploy\nginx\https.conf'

# 静态门禁即使 Docker 不可用也必须执行，优先发现配置遗漏和私钥误入仓库。
$httpsText = Get-Content -Raw -LiteralPath $httpsConfig
Assert-Deploy07 ($httpsText -match 'listen 8443 ssl default_server;') 'HTTPS 配置缺少未知 Host 默认服务。'
Assert-Deploy07 ($httpsText -match 'listen 127\.0\.0\.1:8081;') 'HTTPS 配置缺少 loopback 健康端口。'
Assert-Deploy07 ($httpsText -match 'X-Forwarded-For \$remote_addr;') 'HTTPS API 未覆盖 X-Forwarded-For。'
Assert-Deploy07 ($httpsText -notmatch 'proxy_add_x_forwarded_for') 'HTTPS API 禁止使用 proxy_add_x_forwarded_for。'
Assert-Deploy07 ($httpsText -match 'proxy_request_buffering off;' -and $httpsText -match 'proxy_buffering off;') 'HTTPS API 缺少流式转发配置。'

$gitCommand = (Get-Command git -ErrorAction Stop).Source
Push-Location -LiteralPath $repositoryRoot
try {
    $sensitiveFiles = Invoke-Deploy07Capture -FilePath $gitCommand -ArgumentList @(
        'ls-files', '--cached', '--others', '--exclude-standard', '--', '*.pem', '*.key', '*.p12', '*.pfx'
    )
}
finally {
    Pop-Location
}
Assert-Deploy07 ([string]::IsNullOrWhiteSpace($sensitiveFiles)) "仓库中发现未被规则安全排除的证书/私钥扩展文件：$sensitiveFiles"
Write-Host '[DEPLOY-07] 静态配置与敏感扩展检查通过。'

if ($StaticOnly) {
    Write-Host '[DEPLOY-07] 已按 -StaticOnly 要求跳过 Docker Compose 隔离运行测试。'
    exit 0
}

$docker = Get-Command docker -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $docker) {
    throw 'Docker 命令不可用；默认模式要求完成 Compose 合并和隔离 HTTPS 运行测试，可显式使用 -StaticOnly 仅执行静态检查。'
}
$dockerCommand = $docker.Source
try {
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('info', '--format', '{{.ServerVersion}}') | Out-Null
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('compose', 'version') | Out-Null
}
catch {
    throw 'Docker Engine 或 Compose 不可用；默认模式拒绝把仅静态检查报告为完整通过。'
}

$runId = [Guid]::NewGuid().ToString('N')
$projectName = "campus-deploy07-$($runId.Substring(0, 12))"
$networkName = "$projectName-network"
$tlsVolume = "$projectName-tls"
$mockContainerName = "$projectName-backend"
$ownershipLabel = 'com.campusshare.deploy07-test'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "campus-resource-platform\deploy-07\$runId"
[System.IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
$acmeRoot = Join-Path $temporaryRoot 'acme'
$challengeDirectory = Join-Path $acmeRoot '.well-known\acme-challenge'
[System.IO.Directory]::CreateDirectory($challengeDirectory) | Out-Null
Set-Content -LiteralPath (Join-Path $challengeDirectory 'probe') -Value 'deploy07-acme-ok' -NoNewline -Encoding utf8

$environmentFile = Join-Path $temporaryRoot 'compose.env'
$networkOverride = Join-Path $temporaryRoot 'network.yml'
$mockConfig = Join-Path $temporaryRoot 'backend.conf'
$opensslConfig = Join-Path $temporaryRoot 'openssl.cnf'
$certificatePath = Join-Path $temporaryRoot 'fullchain.pem'
$privateKeyPath = Join-Path $temporaryRoot 'privkey.pem'
$composeAttempted = $false
$networkCreated = $false
$volumeCreated = $false
$mockContainerId = $null
$frontendContainerId = $null
$overallSucceeded = $false

try {
    $frontendImage = if ([string]::IsNullOrWhiteSpace($env:FRONTEND_IMAGE)) {
        'campus-resource-platform-frontend:deploy-02'
    }
    else {
        $env:FRONTEND_IMAGE
    }
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('image', 'inspect', $frontendImage) | Out-Null

    # 测试 Secret 只写系统临时目录，完整 Compose 展开结果也不输出到控制台。
    $randomSecret = "Deploy07_$runId"
    @(
        "COMPOSE_PROJECT_NAME=$projectName"
        'HTTP_PORT=127.0.0.1:'
        'HTTPS_PORT=127.0.0.1:'
        "ACME_WEBROOT_DIR=$($acmeRoot.Replace('\', '/'))"
        "TLS_RUNTIME_VOLUME=$tlsVolume"
        "FRONTEND_IMAGE=$frontendImage"
        'BACKEND_IMAGE=unused-for-deploy07-test'
        "MYSQL_ROOT_PASSWORD=root_$randomSecret"
        'MYSQL_APP_USERNAME=campus_app'
        "MYSQL_APP_PASSWORD=mysql_$randomSecret"
        "REDIS_PASSWORD=redis_$randomSecret"
        "JWT_SECRET=jwt_$randomSecret$randomSecret"
    ) | Set-Content -LiteralPath $environmentFile -Encoding utf8

    @"
networks:
  application:
    external: true
    name: $networkName
"@ | Set-Content -LiteralPath $networkOverride -Encoding utf8

    @'
server {
    listen 8080;
    server_name _;
    location / { return 200 "mock backend\n"; }
}
'@ | Set-Content -LiteralPath $mockConfig -Encoding utf8
    @'
[req]
distinguished_name = req_distinguished_name

[req_distinguished_name]
'@ | Set-Content -LiteralPath $opensslConfig -Encoding ascii

    $baseArguments = @('compose', '--env-file', $environmentFile, '-p', $projectName, '-f', $baseCompose)
    $acmeArguments = $baseArguments + @('-f', $acmeCompose, '-f', $networkOverride)
    $httpsArguments = $baseArguments + @('-f', $httpsCompose, '-f', $networkOverride)
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($acmeArguments + @('config', '--quiet')) | Out-Null
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($httpsArguments + @('config', '--quiet')) | Out-Null

    $mergedJson = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($httpsArguments + @('config', '--format', 'json'))
    $merged = $mergedJson | ConvertFrom-Json
    $publishedTargets = @($merged.services.frontend.ports | ForEach-Object { [int]$_.target })
    Assert-Deploy07 ($publishedTargets -contains 8080) 'HTTPS Compose 合并后丢失基础 HTTP 80→8080 映射。'
    Assert-Deploy07 ($publishedTargets -contains 8443) 'HTTPS Compose 合并后缺少 443→8443 映射。'
    Assert-Deploy07 (($merged.services.frontend.healthcheck.test -join ' ') -match '127\.0\.0\.1:8081/healthz') 'HTTPS healthcheck 未完整覆盖到 8081。'

    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
        'volume', 'create', '--label', "$ownershipLabel=$runId",
        '--label', "com.campusshare.project=$projectName",
        '--label', 'com.campusshare.purpose=tls-runtime', $tlsVolume
    ) | Out-Null
    $volumeCreated = $true
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
        'network', 'create', '--label', "$ownershipLabel=$runId", $networkName
    ) | Out-Null
    $networkCreated = $true

    $openssl = Get-Command openssl -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $openssl) { throw '缺少 openssl，无法生成仅位于系统临时目录的自签 SAN 证书。' }
    Invoke-Deploy07Capture -FilePath $openssl.Source -ArgumentList @(
        'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '2',
        '-keyout', $privateKeyPath, '-out', $certificatePath,
        '-config', $opensslConfig,
        '-subj', '/CN=campusshare.online',
        '-addext', 'subjectAltName=DNS:campusshare.online,DNS:www.campusshare.online'
    ) | Out-Null

    # 模拟证书脚本的卷内布局，证书材料始终不进入仓库。
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
        'run', '--rm', '--user', '0:0', '--entrypoint', '/bin/sh',
        '-v', "${tlsVolume}:/tls", '-v', "${temporaryRoot}:/incoming:ro", $frontendImage,
        '-eu', '-c', 'mkdir -p /tls/releases/release-a /tls/releases/release-b; for release in release-a release-b; do cp /incoming/fullchain.pem "/tls/releases/$release/fullchain.pem"; cp /incoming/privkey.pem "/tls/releases/$release/privkey.pem"; chmod 0500 "/tls/releases/$release"; chmod 0444 "/tls/releases/$release/fullchain.pem"; chmod 0400 "/tls/releases/$release/privkey.pem"; done; chown -R 101:101 /tls/releases; chmod 0500 /tls/releases; ln -s releases/release-a /tls/current; ln -s releases/release-b /tls/current.next; mv -fT /tls/current.next /tls/current; test "$(readlink /tls/current)" = releases/release-b; test ! -e /tls/releases/release-a/current.next; test ! -L /tls/releases/release-a/current.next'
    ) | Out-Null

    $mockContainerId = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
        'run', '--detach', '--name', $mockContainerName, '--label', "$ownershipLabel=$runId",
        '--network', $networkName, '--network-alias', 'backend',
        '-v', "${mockConfig}:/etc/nginx/conf.d/default.conf:ro", $frontendImage
    )

    # 阶段一：基础 Compose + ACME override，确认现有 HTTP 行为和 challenge 可用。
    $composeAttempted = $true
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($acmeArguments + @(
        'up', '--detach', '--no-deps', '--no-build', '--pull', 'never', 'frontend'
    )) | Out-Null
    $frontendContainerId = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($acmeArguments + @('ps', '-q', 'frontend'))
    Wait-Deploy07Healthy -DockerCommand $dockerCommand -ContainerId $frontendContainerId
    $httpPort = Get-Deploy07PublishedPort -DockerCommand $dockerCommand -ContainerId $frontendContainerId -ContainerPort 8080
    $challenge = Invoke-Deploy07Http -Uri "http://127.0.0.1:$httpPort/.well-known/acme-challenge/probe" -HostHeader 'campusshare.online'
    Assert-Deploy07 ($challenge.status -eq 200 -and $challenge.body -eq 'deploy07-acme-ok') 'ACME HTTP-01 challenge 未返回预期内容。'

    # 阶段二：在同一隔离 project 上切换 HTTPS override，验证跳转、TLS、Host 和健康边界。
    Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($httpsArguments + @(
        'up', '--detach', '--no-deps', '--force-recreate', '--no-build', '--pull', 'never', 'frontend'
    )) | Out-Null
    $frontendContainerId = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($httpsArguments + @('ps', '-q', 'frontend'))
    Wait-Deploy07Healthy -DockerCommand $dockerCommand -ContainerId $frontendContainerId
    $httpPort = Get-Deploy07PublishedPort -DockerCommand $dockerCommand -ContainerId $frontendContainerId -ContainerPort 8080
    $httpsPort = Get-Deploy07PublishedPort -DockerCommand $dockerCommand -ContainerId $frontendContainerId -ContainerPort 8443

    $httpRoot = Invoke-Deploy07Http -Uri "http://127.0.0.1:$httpPort/deploy07/path?q=1" -HostHeader 'campusshare.online'
    Assert-Deploy07 ($httpRoot.status -eq 301 -and $httpRoot.location -eq 'https://campusshare.online/deploy07/path?q=1') 'HTTP 根域名跳转未保留路径和查询参数。'
    $httpWww = Invoke-Deploy07Http -Uri "http://127.0.0.1:$httpPort/www/path?q=2" -HostHeader 'www.campusshare.online'
    Assert-Deploy07 ($httpWww.status -eq 301 -and $httpWww.location -eq 'https://campusshare.online/www/path?q=2') 'HTTP www 跳转不正确。'
    $httpsRoot = Invoke-Deploy07Http -Uri "https://127.0.0.1:$httpsPort/" -HostHeader 'campusshare.online'
    Assert-Deploy07 ($httpsRoot.status -eq 200 -and $httpsRoot.body -match '<div\s+id="app"') 'HTTPS 根域名未返回 SPA。'
    $httpsWww = Invoke-Deploy07Http -Uri "https://127.0.0.1:$httpsPort/deep?q=3" -HostHeader 'www.campusshare.online'
    Assert-Deploy07 ($httpsWww.status -eq 301 -and $httpsWww.location -eq 'https://campusshare.online/deep?q=3') 'HTTPS www 未跳转根域名。'

    $unknownHostRejected = $false
    try {
        $unknown = Invoke-Deploy07Http -Uri "https://127.0.0.1:$httpsPort/" -HostHeader 'unknown.invalid'
        $unknownHostRejected = $unknown.status -eq 444
    }
    catch {
        # Nginx 444 会主动断开连接，HttpClient 抛出传输异常即代表未知 Host 被拒绝。
        $unknownHostRejected = $true
    }
    Assert-Deploy07 $unknownHostRejected 'HTTPS 未拒绝未知 Host。'

    $internalHealth = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
        'exec', $frontendContainerId, 'wget', '-qO-', 'http://127.0.0.1:8081/healthz'
    )
    Assert-Deploy07 ($internalHealth -eq 'ok') '8081 容器内部健康检查失败。'
    $tlsMountReadWrite = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
        'inspect', '--format', '{{range .Mounts}}{{if eq .Destination "/etc/nginx/tls"}}{{.RW}}{{end}}{{end}}', $frontendContainerId
    )
    Assert-Deploy07 ($tlsMountReadWrite -eq 'false') 'TLS 卷未以只读方式挂载。'

    $overallSucceeded = $true
    Write-Host '[DEPLOY-07] ACME 与 HTTPS 隔离 Compose 演练全部通过。'
}
finally {
    # 只清理由本次随机 run-id 和专属标签确认归属的资源，不触碰现有项目资源。
    if ($composeAttempted) {
        try {
            Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList ($httpsArguments + @(
                'rm', '--stop', '--force', 'frontend'
            )) | Out-Null

            # Compose 会为未启动的数据服务预创建命名卷；仅按随机 project 标签和名称双重确认后删除。
            $projectVolumeOutput = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
                'volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$projectName"
            )
            $projectVolumes = @($projectVolumeOutput -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            foreach ($projectVolume in $projectVolumes) {
                $projectLabel = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
                    'volume', 'inspect', '--format', '{{ index .Labels "com.docker.compose.project" }}', $projectVolume
                )
                Assert-Deploy07 ($projectLabel -eq $projectName) 'Compose 测试卷项目标签不匹配，拒绝自动删除。'
                Assert-Deploy07 ($projectVolume.StartsWith("${projectName}_", [System.StringComparison]::Ordinal)) 'Compose 测试卷名称前缀不匹配，拒绝自动删除。'
                Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('volume', 'rm', $projectVolume) | Out-Null
            }
        }
        catch {
            $overallSucceeded = $false
            Write-Warning '无法确认隔离 frontend 容器已清理。'
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($mockContainerId)) {
        try {
            $label = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
                'inspect', '--format', "{{ index .Config.Labels `"$ownershipLabel`" }}", $mockContainerId
            )
            if ($label -eq $runId) {
                Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('rm', '--force', $mockContainerId) | Out-Null
            }
        }
        catch {
            $overallSucceeded = $false
            Write-Warning "无法确认测试 mock 容器已清理：$mockContainerName"
        }
    }
    if ($networkCreated) {
        try {
            $networkLabel = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
                'network', 'inspect', '--format', "{{ index .Labels `"$ownershipLabel`" }}", $networkName
            )
            Assert-Deploy07 ($networkLabel -eq $runId) '隔离网络标签不匹配，拒绝自动删除。'
            Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('network', 'rm', $networkName) | Out-Null
        }
        catch {
            $overallSucceeded = $false
            Write-Warning "无法确认隔离网络已清理：$networkName"
        }
    }
    if ($volumeCreated) {
        try {
            $volumeLabel = Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @(
                'volume', 'inspect', '--format', "{{ index .Labels `"$ownershipLabel`" }}", $tlsVolume
            )
            Assert-Deploy07 ($volumeLabel -eq $runId) '隔离 TLS 卷标签不匹配，拒绝自动删除。'
            Invoke-Deploy07Capture -FilePath $dockerCommand -ArgumentList @('volume', 'rm', $tlsVolume) | Out-Null
        }
        catch {
            $overallSucceeded = $false
            Write-Warning "无法确认隔离 TLS 卷已清理：$tlsVolume"
        }
    }

    $normalizedTemp = [System.IO.Path]::GetFullPath($temporaryRoot)
    $normalizedSystemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($normalizedTemp.StartsWith($normalizedSystemTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $normalizedTemp) -eq $runId -and
            (Test-Path -LiteralPath $normalizedTemp)) {
        Remove-Item -LiteralPath $normalizedTemp -Recurse -Force
    }
}

if (-not $overallSucceeded) {
    exit 1
}
