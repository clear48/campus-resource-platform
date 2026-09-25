[CmdletBinding()]
param(
    [uri]$BaseUri = 'https://campusshare.online',
    [string]$EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$startedAt = [DateTimeOffset]::UtcNow
$runId = $startedAt.ToString('yyyyMMddTHHmmssZ').ToLowerInvariant() + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $PSScriptRoot '..') '..'))
$checks = [Collections.Generic.List[object]]::new()
$acceptanceItems = [Collections.Generic.List[object]]::new()
$failure = $null

function Assert-Deploy08PowerShellVersion {
    if ($PSVersionTable.PSVersion.Major -lt 7) {
        throw 'DEPLOY-08 公网预检要求 PowerShell 7 或更高版本。'
    }
}

function Test-Deploy08PathWithinRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Candidate
    )

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

function Assert-Deploy08NoReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Directory)

    $current = [IO.Path]::GetPathRoot($Directory)
    $relativeParts = [IO.Path]::GetRelativePath($current, $Directory).Split(
        [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar),
        [StringSplitOptions]::RemoveEmptyEntries)
    foreach ($part in $relativeParts) {
        $current = Join-Path $current $part
        if (-not (Test-Path -LiteralPath $current)) {
            break
        }
        $attributes = [IO.File]::GetAttributes($current)
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "证据路径包含符号链接或 ReparsePoint：$current"
        }
    }
}

function Assert-Deploy08SafeEvidencePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if ([IO.Path]::GetExtension($fullPath) -ne '.json') {
        throw '证据路径必须使用 .json 扩展名。'
    }
    if (Test-Deploy08PathWithinRoot -Root $repositoryRoot -Candidate $fullPath) {
        throw '证据文件必须位于 Git 仓库外。'
    }
    if (Test-Path -LiteralPath $fullPath) {
        throw '证据文件已经存在；为防止覆盖历史证据，必须使用新路径。'
    }

    $parent = Split-Path -Parent $fullPath
    # 先核对所有已存在祖先再创建目录，避免拒绝路径前已沿链接在目标位置产生写入。
    Assert-Deploy08NoReparsePoint -Directory $parent
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    Assert-Deploy08NoReparsePoint -Directory $parent
    return $fullPath
}

function Assert-Deploy08BaseUri {
    if (-not $BaseUri.IsAbsoluteUri -or $BaseUri.Scheme -ne 'https') {
        throw 'BaseUri 必须是绝对 HTTPS 地址。'
    }
    if ($BaseUri.Host -cne 'campusshare.online') {
        throw '该生产预检只允许访问 campusshare.online。'
    }
    if (-not $BaseUri.IsDefaultPort) {
        throw 'BaseUri 只允许使用生产 HTTPS 默认端口 443。'
    }
    if (-not [string]::IsNullOrEmpty($BaseUri.UserInfo) -or
        -not [string]::IsNullOrEmpty($BaseUri.Query) -or
        -not [string]::IsNullOrEmpty($BaseUri.Fragment)) {
        throw 'BaseUri 不得包含用户信息、查询参数或片段。'
    }
    if ($BaseUri.AbsolutePath -notin @('', '/')) {
        throw 'BaseUri 必须指向站点根路径。'
    }
}

function Get-Deploy08Uri {
    param(
        [Parameter(Mandatory = $true)][uri]$Origin,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $builder = [UriBuilder]::new($Origin)
    $builder.Path = $Path
    $builder.Query = ''
    $builder.Fragment = ''
    return $builder.Uri
}

function Invoke-Deploy08Request {
    param(
        [Parameter(Mandatory = $true)][uri]$Uri,
        [switch]$NoRedirect
    )

    # 所有请求都禁止自动跳转，确保预检永远不会被 3xx 带到站外或内网地址后误判通过。
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(20)
        [void]$client.DefaultRequestHeaders.UserAgent.ParseAdd('campusshare-deploy08-preflight/1.0')
        $response = $client.GetAsync($Uri).GetAwaiter().GetResult()
        try {
            $content = if ($NoRedirect) { '' } else { $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Headers = [pscustomobject]@{
                    Location = [string]$response.Headers.Location
                    'Content-Type' = [string]$response.Content.Headers.ContentType
                }
                Content = $content
            }
        }
        finally {
            $response.Dispose()
        }
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Add-Deploy08Check {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        & $Action
        $stopwatch.Stop()
        $checks.Add([ordered]@{
                name = $Name
                status = 'PASSED'
                durationMs = $stopwatch.ElapsedMilliseconds
            })
        Write-Host "[DEPLOY-08] 通过：$Name"
    }
    catch {
        $stopwatch.Stop()
        # 不保存原始响应或异常消息，避免代理响应、请求头或环境细节进入证据。
        $checks.Add([ordered]@{
                name = $Name
                status = 'FAILED'
                durationMs = $stopwatch.ElapsedMilliseconds
                errorType = $_.Exception.GetType().FullName
            })
        throw "公网预检失败：$Name"
    }
}

function Assert-Deploy08Status {
    param(
        [Parameter(Mandatory = $true)]$Response,
        [Parameter(Mandatory = $true)][int]$ExpectedStatus
    )
    if ([int]$Response.StatusCode -ne $ExpectedStatus) {
        throw "HTTP 状态不符合预期：expected=$ExpectedStatus actual=$([int]$Response.StatusCode)"
    }
}

function Assert-Deploy08SpaPage {
    param([Parameter(Mandatory = $true)]$Response)

    Assert-Deploy08Status -Response $Response -ExpectedStatus 200
    $contentType = [string]$Response.Headers.'Content-Type'
    if ($contentType -notmatch 'text/html' -or [string]$Response.Content -notmatch '<div\s+id=["'']app["'']') {
        throw '响应不是预期的 Vue SPA 入口。'
    }
}

function Assert-Deploy08ApiHealth {
    param(
        [Parameter(Mandatory = $true)]$Response,
        [switch]$RequireUp
    )

    Assert-Deploy08Status -Response $Response -ExpectedStatus 200
    $payload = $Response.Content | ConvertFrom-Json -Depth 20
    if ($null -eq $payload -or [int]$payload.code -ne 0) {
        throw '健康接口没有返回成功业务码。'
    }
    if ($RequireUp -and [string]$payload.data.status -ne 'UP') {
        throw 'readiness 没有返回 UP。'
    }
}

function Get-Deploy08Location {
    param([Parameter(Mandatory = $true)]$Response)
    $location = $Response.Headers.Location
    if ($location -is [Array]) {
        $location = $location[0]
    }
    return [string]$location
}

function Write-Deploy08Evidence {
    param([Parameter(Mandatory = $true)][string]$Path)

    $failedCount = @($checks | Where-Object { $_.status -eq 'FAILED' }).Count
    $evidence = [ordered]@{
        schemaVersion = 1
        runId = $runId
        startedAt = $startedAt.ToString('o')
        finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
        baseHost = $BaseUri.Host
        phase = 'D08-1A-PREFLIGHT'
        overallStatus = if ($failedCount -eq 0) { 'PREFLIGHT_PASSED_WITH_SKIPS' } else { 'PREFLIGHT_FAILED' }
        checks = $checks
        acceptanceMatrix = $acceptanceItems
        failureType = if ($null -eq $failure) { $null } else { $failure.GetType().FullName }
    }
    $json = $evidence | ConvertTo-Json -Depth 20
    $encoding = [Text.UTF8Encoding]::new($false)
    # 网络阶段可能持续数秒，落盘前再次检查父链，缩小目录被替换后的 TOCTOU 窗口。
    Assert-Deploy08NoReparsePoint -Directory (Split-Path -Parent $Path)
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $writer = [IO.StreamWriter]::new($stream, $encoding)
        try {
            $writer.Write($json)
        }
        finally {
            $writer.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }

    if (-not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
        [IO.File]::SetUnixFileMode($Path, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
    }
}

Assert-Deploy08PowerShellVersion
Assert-Deploy08BaseUri
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path ([IO.Path]::GetTempPath()) "campus-resource-platform/deploy-08/evidence/public-preflight-$runId.json"
}
$resolvedEvidencePath = Assert-Deploy08SafeEvidencePath -Path $EvidencePath

try {
    foreach ($path in @('/', '/login', '/register', '/search', '/upload', '/admin/reviews')) {
        Add-Deploy08Check -Name "SPA 直刷 $path" -Action {
            Assert-Deploy08SpaPage -Response (Invoke-Deploy08Request -Uri (Get-Deploy08Uri -Origin $BaseUri -Path $path))
        }
    }

    Add-Deploy08Check -Name 'Nginx healthz' -Action {
        $response = Invoke-Deploy08Request -Uri (Get-Deploy08Uri -Origin $BaseUri -Path '/healthz')
        Assert-Deploy08Status -Response $response -ExpectedStatus 200
        if ([string]$response.Headers.'Content-Type' -notmatch '^text/plain' -or
            [string]$response.Content.Trim() -cne 'ok') {
            throw 'healthz 响应不符合固定文本契约。'
        }
    }
    Add-Deploy08Check -Name '后端 liveness' -Action {
        Assert-Deploy08ApiHealth -Response (Invoke-Deploy08Request -Uri (
                Get-Deploy08Uri -Origin $BaseUri -Path '/api/v1/health'))
    }
    Add-Deploy08Check -Name '后端 readiness' -Action {
        Assert-Deploy08ApiHealth -Response (Invoke-Deploy08Request -Uri (
                Get-Deploy08Uri -Origin $BaseUri -Path '/api/v1/health/readiness')) -RequireUp
    }

    Add-Deploy08Check -Name 'HTTP 跳转根域 HTTPS' -Action {
        $builder = [UriBuilder]::new($BaseUri)
        $builder.Scheme = 'http'
        $builder.Port = -1
        $builder.Path = '/'
        $response = Invoke-Deploy08Request -Uri $builder.Uri -NoRedirect
        Assert-Deploy08Status -Response $response -ExpectedStatus 301
        if ((Get-Deploy08Location -Response $response) -ne 'https://campusshare.online/') {
            throw 'HTTP 重定向目标不正确。'
        }
    }
    Add-Deploy08Check -Name 'www HTTPS 跳转根域' -Action {
        $builder = [UriBuilder]::new($BaseUri)
        $builder.Host = 'www.campusshare.online'
        $builder.Path = '/'
        $response = Invoke-Deploy08Request -Uri $builder.Uri -NoRedirect
        Assert-Deploy08Status -Response $response -ExpectedStatus 301
        if ((Get-Deploy08Location -Response $response) -ne 'https://campusshare.online/') {
            throw 'www HTTPS 重定向目标不正确。'
        }
    }
}
catch {
    $failure = $_.Exception
}
finally {
    $acceptanceItems.Add([ordered]@{ id = 1; status = 'SKIPPED'; reason = '只完成页面直刷，尚未执行注册和登录业务。' })
    $acceptanceItems.Add([ordered]@{ id = 2; status = 'SKIPPED'; reason = '尚未执行生产上传和待审核资料创建。' })
    $acceptanceItems.Add([ordered]@{ id = 3; status = 'SKIPPED'; reason = '尚未使用管理员凭据读取和审核。' })
    $acceptanceItems.Add([ordered]@{ id = 4; status = 'SKIPPED'; reason = '尚未创建本轮已审核资料供游客检索。' })
    $acceptanceItems.Add([ordered]@{ id = 5; status = 'SKIPPED'; reason = '尚未执行收藏、票据和文件下载。' })
    $acceptanceItems.Add([ordered]@{ id = 6; status = 'SKIPPED'; reason = '尚未执行限流、排行榜和定时任务验收。' })
    $acceptanceItems.Add([ordered]@{ id = 7; status = 'SKIPPED'; reason = '尚未使用普通用户验证管理员接口 403。' })
    $acceptanceItems.Add([ordered]@{ id = 8; status = 'SKIPPED'; reason = '尚未执行接近 50 MB 的浏览器上传。' })
    $acceptanceItems.Add([ordered]@{ id = 9; status = 'SKIPPED'; reason = '尚未执行多用户伪造转发头限流验收。' })
    $acceptanceItems.Add([ordered]@{ id = 10; status = 'SKIPPED'; reason = '尚未执行容器和宿主机重启持久性验收。' })
    $acceptanceItems.Add([ordered]@{ id = 11; status = 'SKIPPED'; reason = '尚未执行生产联合备份与独立恢复。' })
    $acceptanceItems.Add([ordered]@{ id = 12; status = 'SKIPPED'; reason = '尚未执行上一镜像真实业务兼容验证。' })
    Write-Deploy08Evidence -Path $resolvedEvidencePath
    Write-Host "[DEPLOY-08] 脱敏证据：$resolvedEvidencePath"
}

if ($null -ne $failure) {
    Write-Error 'DEPLOY-08 公网只读预检失败；请查看脱敏证据中的失败检查。'
    exit 1
}

Write-Host '[DEPLOY-08] 公网只读预检通过；12 项生产验收尚未完成，未执行项已明确标记为 SKIPPED。'
exit 0
