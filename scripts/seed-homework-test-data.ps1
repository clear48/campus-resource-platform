#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('Preview', 'Seed', 'Resume', 'Verify')]
    [string]$Mode = 'Preview',

    [string]$ApiBaseUrl = 'http://127.0.0.1:8080/api/v1',

    [string]$SourceRoot = 'C:\Users\Lenovo\Desktop\HomeWork',

    [ValidateRange(30, 40)]
    [int]$TargetCount = 36,

    [string]$RunId = ('seed-{0}' -f (Get-Date -Format 'yyyyMMdd-HHmmss')),

    [ValidateRange(1, 8)]
    [int]$UserCount = 4,

    [int]$RandomSeed = 20260908,

    [string]$StateDirectory = (Join-Path $PSScriptRoot '..\campus-resource-platform\data\seed-runs'),

    [ValidateRange(5, 300)]
    [int]$RequestTimeoutSeconds = 60,

    [switch]$IncludeArchives,

    [switch]$AcknowledgeCurrentDatabaseAsTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:StatusCode = @{
    PENDING_REVIEW = 0
    APPROVED       = 1
    REJECTED       = 2
    OFFLINE        = 3
}

function ConvertTo-SafeRunId {
    param([Parameter(Mandatory)][string]$Value)
    $safeValue = ($Value.ToLowerInvariant() -replace '[^a-z0-9]+', '-').Trim('-')
    if ([string]::IsNullOrWhiteSpace($safeValue)) {
        throw 'RunId 必须至少包含一个英文字母或数字。'
    }
    # 短 RunId 原样保留；长 RunId 使用可读前缀和哈希，避免同一小时前缀碰撞。
    if ($safeValue.Length -le 16) { return $safeValue }
    $hashBytes = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Value))
    $hashSuffix = [Convert]::ToHexString($hashBytes).ToLowerInvariant().Substring(0, 8)
    return '{0}-{1}' -f $safeValue.Substring(0, 7), $hashSuffix
}

function Get-SourceRootHash {
    param([Parameter(Mandatory)][string]$Root)
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/').ToLowerInvariant()
    $hashBytes = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalizedRoot))
    return [Convert]::ToHexString($hashBytes).ToLowerInvariant()
}

function Limit-Text {
    param([AllowNull()][string]$Value, [int]$MaxLength)
    if ($null -eq $Value) { return '' }
    if ($Value.Length -le $MaxLength) { return $Value }
    return $Value.Substring(0, $MaxLength)
}

function Get-AuthorizationHeader {
    param([AllowNull()][string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { return @{} }
    return @{ Authorization = "Bearer $Token" }
}

function Save-Manifest {
    param([Parameter(Mandatory)]$Manifest, [Parameter(Mandatory)][string]$Path)
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $temporaryPath = "$Path.tmp"
    $Manifest.updatedAt = (Get-Date).ToString('o')
    $Manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporaryPath -Encoding utf8NoBOM
    Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
}

function Get-HttpStatusCode {
    param($ErrorRecord)
    try { return [int]$ErrorRecord.Exception.Response.StatusCode }
    catch { return 0 }
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)][ValidateSet('GET', 'POST', 'DELETE')][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [AllowNull()][string]$Token,
        [AllowNull()]$Body,
        [switch]$Idempotent
    )

    $uri = if ($Path.StartsWith('http')) { $Path } else { "$($ApiBaseUrl.TrimEnd('/'))/$($Path.TrimStart('/'))" }
    $attempts = if ($Idempotent) { 4 } else { 1 }
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        try {
            $parameters = @{
                Uri         = $uri
                Method      = $Method
                Headers     = (Get-AuthorizationHeader $Token)
                TimeoutSec  = $RequestTimeoutSeconds
                ErrorAction = 'Stop'
            }
            if ($null -ne $Body) {
                $parameters.ContentType = 'application/json; charset=utf-8'
                $parameters.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
            }
            $response = Invoke-RestMethod @parameters
            if ($null -eq $response -or [int]$response.code -ne 0) {
                $message = if ($null -ne $response) { $response.message } else { '响应为空' }
                throw "接口业务失败：$message"
            }
            return $response.data
        }
        catch {
            $statusCode = Get-HttpStatusCode $_
            $retryable = $Idempotent -and ($statusCode -eq 0 -or $statusCode -eq 429 -or $statusCode -ge 500)
            if (-not $retryable -or $attempt -eq $attempts) { throw }
            Start-Sleep -Seconds ([Math]::Min(8, [Math]::Pow(2, $attempt)))
        }
    }
}

function Invoke-FilePrecheck {
    param([string]$Token, [string]$Md5, [long]$Size)
    $path = 'files/check?fileMd5={0}&fileSize={1}' -f [uri]::EscapeDataString($Md5), $Size
    return Invoke-JsonApi -Method GET -Path $path -Token $Token -Body $null -Idempotent
}

function Invoke-FileUploadSafely {
    param([string]$Token, [string]$FullPath, [string]$Md5, [long]$Size)

    $check = Invoke-FilePrecheck -Token $Token -Md5 $Md5 -Size $Size
    if ($check.secondUpload) { return [long]$check.fileId }

    $stagingDirectory = Join-Path (Split-Path -Parent $script:ManifestPath) ("upload-staging\{0}" -f $Md5.Substring(0, 12))
    New-Item -ItemType Directory -Path $stagingDirectory -Force | Out-Null
    $stagedPath = Join-Path $stagingDirectory ([IO.Path]::GetFileName($FullPath))
    try {
        [IO.File]::Copy($FullPath, $stagedPath, $true)
        [IO.File]::SetAttributes($stagedPath, [IO.FileAttributes]::Normal)
        $stagedFile = Get-Item -LiteralPath $stagedPath
        $stagedMd5 = (Get-FileHash -LiteralPath $stagedPath -Algorithm MD5).Hash.ToLowerInvariant()
        if ([long]$stagedFile.Length -ne $Size -or $stagedMd5 -ne $Md5) {
            throw '上传暂存副本与 manifest 的大小或 MD5 不一致，已停止本次上传。'
        }

        # 上传接口不可盲重试：后端重复授权可能使 file_info.ref_count 虚增。
        $response = Invoke-RestMethod -Uri "$($ApiBaseUrl.TrimEnd('/'))/files" -Method POST `
            -Headers (Get-AuthorizationHeader $Token) -Form @{ file = Get-Item -LiteralPath $stagedPath } `
            -TimeoutSec $RequestTimeoutSeconds -ErrorAction Stop
        if ([int]$response.code -ne 0) { throw "上传业务失败：$($response.message)" }
        return [long]$response.data.fileId
    }
    catch {
        # 若响应丢失但服务端已成功，授权感知预检可以安全恢复 fileId。
        $recheck = Invoke-FilePrecheck -Token $Token -Md5 $Md5 -Size $Size
        if ($recheck.secondUpload) { return [long]$recheck.fileId }
        throw
    }
    finally {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-SeedCandidates {
    param([string]$Root, [int]$Count)

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw "来源目录不存在：$Root"
    }
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $rootBoundary = "$normalizedRoot$([IO.Path]::DirectorySeparatorChar)"
    $allowedExtensions = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    @('.pdf', '.doc', '.docx', '.ppt', '.pptx', '.xls', '.xlsx', '.txt', '.md', '.jpg', '.jpeg', '.png') |
        ForEach-Object { [void]$allowedExtensions.Add($_) }
    if ($IncludeArchives) { @('.zip', '.rar', '.7z') | ForEach-Object { [void]$allowedExtensions.Add($_) } }
    $excludedDirectoryPattern = '(?i)(^|[\\/])(?:node_modules|\.git|\.venv|venv|site-packages|__pycache__|dist|build|_review_build|target|coverage|\.idea|\.vscode|vendor|static|logs?|guilogs|_extracted|extracted_images|_pos_report_assets|tests)([\\/]|$)'
    $suspiciousNamePattern = '(?i)(^|[._-])(?:secret|password|passwd|token|credential|private[-_]?key|id_rsa|\.env)([._-]|$)'

    $eligible = foreach ($file in Get-ChildItem -LiteralPath $normalizedRoot -File -Recurse -Force -ErrorAction SilentlyContinue) {
        $fullPath = [IO.Path]::GetFullPath($file.FullName)
        if (-not $fullPath.StartsWith($rootBoundary, [StringComparison]::OrdinalIgnoreCase)) { continue }
        if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
        if ($fullPath -match $excludedDirectoryPattern -or $file.Name -match $suspiciousNamePattern -or $file.Name.StartsWith('~$')) { continue }
        if (-not $allowedExtensions.Contains($file.Extension)) { continue }
        if ($file.Length -le 0 -or $file.Length -gt 50MB) { continue }
        $relativePath = [IO.Path]::GetRelativePath($normalizedRoot, $fullPath)
        [pscustomobject]@{
            FullPath     = $fullPath
            RelativePath = $relativePath
            Extension    = $file.Extension.ToLowerInvariant()
            Size         = [long]$file.Length
        }
    }

    # 固定随机种子只影响同扩展名内的顺序，同一目录可重复得到相同候选。
    $random = [Random]::new($RandomSeed)
    $groups = @{}
    foreach ($group in ($eligible | Group-Object Extension)) {
        $groups[$group.Name] = @($group.Group | Sort-Object @{ Expression = { $random.Next() } })
    }
    $indexes = @{}
    foreach ($extension in $groups.Keys) { $indexes[$extension] = 0 }
    $extensions = @($groups.Keys | Sort-Object)
    $selected = [Collections.Generic.List[object]]::new()
    $seenContent = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)

    while ($selected.Count -lt $Count) {
        $madeProgress = $false
        foreach ($extension in $extensions) {
            $items = $groups[$extension]
            while ($indexes[$extension] -lt $items.Count) {
                $candidate = $items[$indexes[$extension]]
                $indexes[$extension]++
                $candidate | Add-Member -NotePropertyName Md5 -NotePropertyValue ((Get-FileHash -LiteralPath $candidate.FullPath -Algorithm MD5).Hash.ToLowerInvariant())
                $contentKey = "$($candidate.Md5):$($candidate.Size)"
                if ($seenContent.Add($contentKey)) {
                    $selected.Add($candidate)
                    $madeProgress = $true
                    break
                }
            }
            if ($selected.Count -ge $Count) { break }
        }
        if (-not $madeProgress) { break }
    }
    if ($selected.Count -lt $Count) {
        throw "仅找到 $($selected.Count) 个符合规则且内容唯一的文件，少于目标 $Count。"
    }
    return @($selected)
}

function Get-TargetStates {
    param([int]$Count)
    $approved = [Math]::Floor($Count * 2 / 3)
    $pending = [Math]::Floor($Count * 5 / 36)
    $rejected = [Math]::Floor($Count * 4 / 36)
    $offline = $Count - $approved - $pending - $rejected
    return @(
        @('APPROVED') * $approved
        @('PENDING_REVIEW') * $pending
        @('REJECTED') * $rejected
        @('OFFLINE') * $offline
    )
}

function Get-ResourceType {
    param([string]$RelativePath, [string]$Extension)
    if ($RelativePath -match '真题|试卷|考试') { return 3 }
    if ($RelativePath -match '实验') { return 4 }
    if ($RelativePath -match '课程设计') { return 5 }
    if ($Extension -in @('.ppt', '.pptx')) { return 1 }
    if ($Extension -in @('.pdf', '.doc', '.docx', '.txt', '.md')) { return 2 }
    return 99
}

function New-Manifest {
    param([object[]]$Candidates, [string]$SafeRunId)
    $states = Get-TargetStates $Candidates.Count
    $items = for ($index = 0; $index -lt $Candidates.Count; $index++) {
        $candidate = $Candidates[$index]
        $baseName = [IO.Path]::GetFileNameWithoutExtension($candidate.RelativePath)
        $titlePrefix = '[SEED:{0}:{1:D3}] ' -f $SafeRunId, ($index + 1)
        $courseName = Split-Path -Leaf (Split-Path -Parent $candidate.RelativePath)
        if ([string]::IsNullOrWhiteSpace($courseName)) { $courseName = '批量测试课程' }
        [pscustomobject]@{
            index        = $index + 1
            relativePath = $candidate.RelativePath
            md5          = $candidate.Md5
            size         = $candidate.Size
            extension    = $candidate.Extension
            userIndex    = ($index % $UserCount) + 1
            title        = Limit-Text "$titlePrefix$baseName" 150
            courseName   = Limit-Text $courseName 100
            resourceType = Get-ResourceType $candidate.RelativePath $candidate.Extension
            targetState  = $states[$index]
            fileId       = $null
            resourceId   = $null
            currentState = 'NEW'
        }
    }
    return [pscustomobject]@{
        schemaVersion = 1
        runId         = $RunId
        safeRunId     = $SafeRunId
        apiBaseUrl    = $ApiBaseUrl.TrimEnd('/')
        createdAt     = (Get-Date).ToString('o')
        updatedAt     = (Get-Date).ToString('o')
        targetCount   = $Candidates.Count
        userCount     = $UserCount
        sourceRootHash = Get-SourceRootHash $SourceRoot
        randomSeed     = $RandomSeed
        includeArchives = [bool]$IncludeArchives
        behaviorsDone = $false
        behaviorProgress = [pscustomobject]@{
            favoritesDone = 0
            downloadsDone = 0
            searchesDone  = 0
            rankingDone   = $false
        }
        items         = @($items)
    }
}

function Login-User {
    param([string]$Username, [string]$Password)
    return Invoke-JsonApi -Method POST -Path 'auth/login' -Token $null -Body @{ username = $Username; password = $Password }
}

function Initialize-SeedUsers {
    param([string]$SafeRunId, [string]$Password, [switch]$RegisterIfMissing)
    $users = @()
    for ($index = 1; $index -le $UserCount; $index++) {
        $username = "seed_${SafeRunId}_$index"
        if ($RegisterIfMissing) {
            try {
                [void](Invoke-JsonApi -Method POST -Path 'auth/register' -Token $null -Body @{
                    username = $username
                    password = $Password
                    nickname = "测试用户-$SafeRunId-$index"
                })
            }
            catch {
                # Resume 时账号已存在是预期情况；随后的登录会验证凭据是否正确。
            }
        }
        $login = Login-User -Username $username -Password $Password
        $users += [pscustomobject]@{ Username = $username; Token = $login.accessToken }
    }
    return $users
}

function Get-AllCategories {
    $pendingParents = [Collections.Generic.Queue[long]]::new()
    $pendingParents.Enqueue(0)
    $result = [Collections.Generic.List[object]]::new()
    $visited = [Collections.Generic.HashSet[long]]::new()
    while ($pendingParents.Count -gt 0) {
        $parentId = $pendingParents.Dequeue()
        if (-not $visited.Add($parentId)) { continue }
        $children = @(Invoke-JsonApi -Method GET -Path "categories?parentId=$parentId" -Token $null -Body $null -Idempotent)
        foreach ($child in $children) {
            $result.Add($child)
            $pendingParents.Enqueue([long]$child.categoryId)
        }
    }
    if ($result.Count -eq 0) { throw '当前数据库没有启用分类，无法创建资料。' }
    return @($result)
}

function Get-MyResourcesByTitle {
    param([string]$Token)
    $map = @{}
    $pageNo = 1
    do {
        $page = Invoke-JsonApi -Method GET -Path "users/me/resources?pageNo=$pageNo&pageSize=100" -Token $Token -Body $null -Idempotent
        foreach ($record in @($page.records)) { $map[[string]$record.title] = $record }
        $pageNo++
    } while ($pageNo -le [int]$page.pages)
    return $map
}

function Set-TargetState {
    param($Item, [string]$AdminToken)
    $current = [string]$Item.currentState
    if ($current -eq $Item.targetState) { return }
    $resourceId = [long]$Item.resourceId
    $reasonTag = "批量测试 $($script:SafeRunId)"
    switch ($Item.targetState) {
        'PENDING_REVIEW' { return }
        'APPROVED' {
            if ($current -ne 'PENDING_REVIEW') { throw "资料 $resourceId 当前状态 $current，不能流转为 APPROVED。" }
            [void](Invoke-JsonApi -Method POST -Path "admin/resources/$resourceId/audit-approvals" -Token $AdminToken -Body @{ auditReason = "$reasonTag：审核通过" })
        }
        'REJECTED' {
            if ($current -ne 'PENDING_REVIEW') { throw "资料 $resourceId 当前状态 $current，不能流转为 REJECTED。" }
            [void](Invoke-JsonApi -Method POST -Path "admin/resources/$resourceId/audit-rejections" -Token $AdminToken -Body @{ rejectReason = "$reasonTag：覆盖拒绝状态" })
        }
        'OFFLINE' {
            if ($current -eq 'PENDING_REVIEW') {
                [void](Invoke-JsonApi -Method POST -Path "admin/resources/$resourceId/audit-approvals" -Token $AdminToken -Body @{ auditReason = "$reasonTag：下架前通过" })
                $current = 'APPROVED'
            }
            if ($current -ne 'APPROVED') { throw "资料 $resourceId 当前状态 $current，不能流转为 OFFLINE。" }
            [void](Invoke-JsonApi -Method POST -Path "admin/resources/$resourceId/offline-records" -Token $AdminToken -Body @{ offlineReason = "$reasonTag：覆盖下架状态" })
        }
    }
    $Item.currentState = $Item.targetState
}

function Add-PublicBehaviors {
    param($Manifest, [object[]]$Users, [string]$AdminToken, [string]$Root)
    if ($Manifest.behaviorsDone) { return }
    $approved = @($Manifest.items | Where-Object targetState -eq 'APPROVED')

    $favoriteCount = [Math]::Min(18, $approved.Count)
    for ($index = [int]$Manifest.behaviorProgress.favoritesDone; $index -lt $favoriteCount; $index++) {
        $user = $Users[$index % $Users.Count]
        [void](Invoke-JsonApi -Method POST -Path "resources/$($approved[$index].resourceId)/favorites" -Token $user.Token -Body $null -Idempotent)
        $Manifest.behaviorProgress.favoritesDone = $index + 1
        Save-Manifest -Manifest $Manifest -Path $script:ManifestPath
    }

    $downloadCount = [Math]::Min(6, $approved.Count)
    $downloadDirectory = Join-Path (Split-Path -Parent $script:ManifestPath) 'downloads'
    New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
    for ($index = [int]$Manifest.behaviorProgress.downloadsDone; $index -lt $downloadCount; $index++) {
        $item = $approved[$index]
        $user = $Users[$index % $Users.Count]
        $records = Invoke-JsonApi -Method GET -Path 'users/me/download-records?pageNo=1&pageSize=100' -Token $user.Token -Body $null -Idempotent
        $alreadyCreated = @($records.records | Where-Object { [long]$_.resourceId -eq [long]$item.resourceId }).Count -gt 0
        if (-not $alreadyCreated) {
            $ticket = Invoke-JsonApi -Method POST -Path "resources/$($item.resourceId)/download-records" -Token $user.Token -Body $null
            $downloadPath = Join-Path $downloadDirectory ("{0:D3}.bin" -f $item.index)
            $apiUri = [uri]$ApiBaseUrl
            $downloadUri = if ([string]$ticket.downloadUrl -match '^https?://') {
                [string]$ticket.downloadUrl
            }
            else {
                '{0}://{1}{2}' -f $apiUri.Scheme, $apiUri.Authority, $ticket.downloadUrl
            }
            Invoke-WebRequest -Uri $downloadUri -Method GET `
                -Headers @{ Authorization = "Bearer $($user.Token)"; 'X-Download-Ticket' = $ticket.downloadTicket } `
                -OutFile $downloadPath -TimeoutSec $RequestTimeoutSeconds | Out-Null
            $downloadMd5 = (Get-FileHash -LiteralPath $downloadPath -Algorithm MD5).Hash.ToLowerInvariant()
            if ($downloadMd5 -ne $item.md5) { throw "资料 $($item.resourceId) 下载正文 MD5 校验失败。" }
        }
        $Manifest.behaviorProgress.downloadsDone = $index + 1
        Save-Manifest -Manifest $Manifest -Path $script:ManifestPath
    }

    $keyword = [uri]::EscapeDataString($Manifest.safeRunId)
    for ($index = [int]$Manifest.behaviorProgress.searchesDone; $index -lt 12; $index++) {
        [void](Invoke-JsonApi -Method GET -Path "search/resources?keyword=$keyword&pageNo=1&pageSize=100" -Token $null -Body $null -Idempotent)
        $Manifest.behaviorProgress.searchesDone = $index + 1
        Save-Manifest -Manifest $Manifest -Path $script:ManifestPath
    }
    if (-not $Manifest.behaviorProgress.rankingDone) {
        [void](Invoke-JsonApi -Method POST -Path 'admin/rankings/resources/hot/rebuild' -Token $AdminToken -Body $null)
        $Manifest.behaviorProgress.rankingDone = $true
    }
    $Manifest.behaviorsDone = [int]$Manifest.behaviorProgress.favoritesDone -eq $favoriteCount `
        -and [int]$Manifest.behaviorProgress.downloadsDone -eq $downloadCount `
        -and [int]$Manifest.behaviorProgress.searchesDone -eq 12 `
        -and [bool]$Manifest.behaviorProgress.rankingDone
}

function Verify-SeedRun {
    param($Manifest, [object[]]$Users)
    $statusNames = @{ 0 = 'PENDING_REVIEW'; 1 = 'APPROVED'; 2 = 'REJECTED'; 3 = 'OFFLINE' }
    $actualById = @{}
    foreach ($user in $Users) {
        foreach ($record in (Get-MyResourcesByTitle -Token $user.Token).Values) {
            $actualById[[string]$record.resourceId] = $record
        }
    }
    foreach ($item in $Manifest.items) {
        if ($null -eq $item.fileId -or $null -eq $item.resourceId) { throw "清单第 $($item.index) 项尚未写入完成。" }
        $actual = $actualById[[string]$item.resourceId]
        if ($null -eq $actual) { throw "无法在上传者列表中找到资料 $($item.resourceId)。" }
        $actualState = $statusNames[[int]$actual.status]
        if ($actualState -ne $item.targetState) { throw "资料 $($item.resourceId) 状态应为 $($item.targetState)，实际为 $actualState。" }
        $owner = $Users[[int]$item.userIndex - 1]
        $fileCheck = Invoke-FilePrecheck -Token $owner.Token -Md5 $item.md5 -Size ([long]$item.size)
        if (-not $fileCheck.secondUpload -or [long]$fileCheck.fileId -ne [long]$item.fileId) {
            throw "资料 $($item.resourceId) 的文件授权或 fileId 与 manifest 不一致。"
        }
        $item.currentState = $actualState
    }

    $counts = [ordered]@{}
    foreach ($state in @('APPROVED', 'PENDING_REVIEW', 'REJECTED', 'OFFLINE')) {
        $counts[$state] = @($Manifest.items | Where-Object targetState -eq $state).Count
    }
    # 不带关键词分页读取公开搜索结果，避免 Verify 自身增加 Redis 热词计数。
    $publicMatches = 0
    $publicPageNo = 1
    do {
        $publicPage = Invoke-JsonApi -Method GET -Path "search/resources?pageNo=$publicPageNo&pageSize=100" -Token $null -Body $null -Idempotent
        $publicMatches += @($publicPage.records | Where-Object { [string]$_.title -like "[[]SEED:$($Manifest.safeRunId):*" }).Count
        $publicPageNo++
    } while ($publicPageNo -le [int]$publicPage.pages)
    if ($publicMatches -ne [int]$counts.APPROVED) {
        throw "公开搜索应命中 $($counts.APPROVED) 条，实际命中 $publicMatches 条。"
    }

    $favoriteTotal = 0
    $downloadTotal = 0
    foreach ($user in $Users) {
        $favoriteTotal += [int](Invoke-JsonApi -Method GET -Path 'users/me/favorites?pageNo=1&pageSize=100' -Token $user.Token -Body $null -Idempotent).total
        $downloadTotal += [int](Invoke-JsonApi -Method GET -Path 'users/me/download-records?pageNo=1&pageSize=100' -Token $user.Token -Body $null -Idempotent).total
    }
    if ($Manifest.behaviorsDone -and ($favoriteTotal -ne 18 -or $downloadTotal -ne 6)) {
        throw "行为数据数量不一致：收藏 $favoriteTotal/18，下载 $downloadTotal/6。"
    }
    [pscustomobject]@{
        RunId          = $Manifest.runId
        ResourceCount  = @($Manifest.items).Count
        Approved       = $counts.APPROVED
        PendingReview  = $counts.PENDING_REVIEW
        Rejected       = $counts.REJECTED
        Offline        = $counts.OFFLINE
        PublicSearch   = $publicMatches
        Favorites      = $favoriteTotal
        Downloads      = $downloadTotal
        BehaviorsDone  = [bool]$Manifest.behaviorsDone
        ManifestPath   = $script:ManifestPath
    }
}

$script:SafeRunId = ConvertTo-SafeRunId $RunId
$parsedApiBaseUrl = [uri]$ApiBaseUrl
if (-not $parsedApiBaseUrl.IsAbsoluteUri -or $parsedApiBaseUrl.Scheme -notin @('http', 'https') -or -not [string]::IsNullOrEmpty($parsedApiBaseUrl.UserInfo)) {
    throw 'ApiBaseUrl 必须是绝对 HTTP(S) 地址，且不能在 URI 中包含用户名或密码。'
}
$normalizedStateDirectory = [IO.Path]::GetFullPath($StateDirectory)
$script:ManifestPath = Join-Path (Join-Path $normalizedStateDirectory $script:SafeRunId) 'manifest.json'

Write-Host "模式: $Mode"
Write-Host "API: $($ApiBaseUrl.TrimEnd('/'))"
Write-Host "RunId: $RunId"
Write-Host "目标资料数: $TargetCount"

if ($Mode -eq 'Preview') {
    $candidates = Get-SeedCandidates -Root $SourceRoot -Count $TargetCount
    $states = Get-TargetStates $TargetCount
    for ($index = 0; $index -lt $candidates.Count; $index++) {
        Write-Host ('{0:D3} [{1}] {2} ({3:N2} MiB)' -f ($index + 1), $states[$index], $candidates[$index].RelativePath, ($candidates[$index].Size / 1MB))
    }
    Write-Host ('预演完成：{0} 个内容唯一文件，共 {1:N2} MiB；未写数据库、Redis和上传目录。' -f $candidates.Count, (($candidates | Measure-Object Size -Sum).Sum / 1MB))
    exit 0
}

if (-not $AcknowledgeCurrentDatabaseAsTest) {
    throw 'Seed、Resume、Verify 模式必须显式传入 -AcknowledgeCurrentDatabaseAsTest，确认当前 API 所连数据库可写入测试数据。'
}
if ([string]::IsNullOrWhiteSpace($env:CRP_SEED_USER_PASSWORD)) {
    throw '缺少环境变量 CRP_SEED_USER_PASSWORD。'
}

$manifest = $null
if (Test-Path -LiteralPath $script:ManifestPath) {
    $manifest = Get-Content -LiteralPath $script:ManifestPath -Raw | ConvertFrom-Json
    if ([string]$manifest.runId -ne $RunId -or [string]$manifest.safeRunId -ne $script:SafeRunId) { throw '已有 manifest 的 RunId 与本次参数不一致。' }
    if ([int]$manifest.targetCount -ne $TargetCount) { throw '已有 manifest 的 targetCount 与本次参数不一致。' }
    if ([int]$manifest.userCount -ne $UserCount) { throw '已有 manifest 的 userCount 与本次参数不一致。' }
    if ([string]$manifest.apiBaseUrl -ne $ApiBaseUrl.TrimEnd('/')) { throw '已有 manifest 的 apiBaseUrl 与本次参数不一致。' }
    if ([string]$manifest.sourceRootHash -ne (Get-SourceRootHash $SourceRoot)) { throw '已有 manifest 的来源目录与本次参数不一致。' }
    if ([int]$manifest.randomSeed -ne $RandomSeed -or [bool]$manifest.includeArchives -ne [bool]$IncludeArchives) { throw '已有 manifest 的候选选择参数与本次参数不一致。' }
}
elseif ($Mode -in @('Resume', 'Verify')) {
    throw "找不到运行清单：$($script:ManifestPath)"
}
else {
    $candidates = Get-SeedCandidates -Root $SourceRoot -Count $TargetCount
    $manifest = New-Manifest -Candidates $candidates -SafeRunId $script:SafeRunId
    Save-Manifest -Manifest $manifest -Path $script:ManifestPath
}

$users = @(Initialize-SeedUsers -SafeRunId $script:SafeRunId -Password $env:CRP_SEED_USER_PASSWORD -RegisterIfMissing:($Mode -in @('Seed', 'Resume')))

if ($Mode -in @('Seed', 'Resume')) {
    if ([string]::IsNullOrWhiteSpace($env:CRP_SEED_ADMIN_USERNAME) -or [string]::IsNullOrWhiteSpace($env:CRP_SEED_ADMIN_PASSWORD)) {
        throw 'Seed/Resume 缺少 CRP_SEED_ADMIN_USERNAME 或 CRP_SEED_ADMIN_PASSWORD。'
    }
    $admin = Login-User -Username $env:CRP_SEED_ADMIN_USERNAME -Password $env:CRP_SEED_ADMIN_PASSWORD
    $categories = @(Get-AllCategories)
    $categoryId = [long]$categories[0].categoryId
    $resourceMaps = @{}
    for ($index = 0; $index -lt $users.Count; $index++) {
        $resourceMaps[$index + 1] = Get-MyResourcesByTitle -Token $users[$index].Token
    }

    foreach ($item in $manifest.items) {
        $user = $users[[int]$item.userIndex - 1]
        if ($null -eq $item.fileId) {
            $fullPath = [IO.Path]::GetFullPath((Join-Path $SourceRoot $item.relativePath))
            $normalizedRoot = [IO.Path]::GetFullPath($SourceRoot).TrimEnd('\', '/')
            if (-not $fullPath.StartsWith("$normalizedRoot$([IO.Path]::DirectorySeparatorChar)", [StringComparison]::OrdinalIgnoreCase)) {
                throw "清单路径越出来源目录：$($item.relativePath)"
            }
            if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw "源文件不存在：$($item.relativePath)" }
            $actualFile = Get-Item -LiteralPath $fullPath
            $actualMd5 = (Get-FileHash -LiteralPath $fullPath -Algorithm MD5).Hash.ToLowerInvariant()
            if ([long]$actualFile.Length -ne [long]$item.size -or $actualMd5 -ne [string]$item.md5) {
                throw "源文件在生成 manifest 后发生变化：$($item.relativePath)"
            }
            $item.fileId = Invoke-FileUploadSafely -Token $user.Token -FullPath $fullPath -Md5 $item.md5 -Size ([long]$item.size)
            Save-Manifest -Manifest $manifest -Path $script:ManifestPath
        }

        if ($null -eq $item.resourceId) {
            $existing = $resourceMaps[[int]$item.userIndex][[string]$item.title]
            if ($null -ne $existing) {
                $item.resourceId = [long]$existing.resourceId
                $item.currentState = (@('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'OFFLINE', 'DELETED'))[[int]$existing.status]
            }
            else {
                $created = Invoke-JsonApi -Method POST -Path 'resources' -Token $user.Token -Body @{
                    fileId       = [long]$item.fileId
                    title        = $item.title
                    description  = Limit-Text "批量测试运行 $($manifest.safeRunId)；来源：$($item.relativePath)" 2000
                    categoryId   = $categoryId
                    courseName   = $item.courseName
                    resourceType = [int]$item.resourceType
                    tags         = @("s-$($manifest.safeRunId)", $item.extension.TrimStart('.'))
                }
                $item.resourceId = [long]$created.resourceId
                $item.currentState = 'PENDING_REVIEW'
                $resourceMaps[[int]$item.userIndex][[string]$item.title] = [pscustomobject]@{ resourceId = $item.resourceId; status = 0; title = $item.title }
            }
            Save-Manifest -Manifest $manifest -Path $script:ManifestPath
        }
    }

    # 创建全部资料后再统一审核，便于中断后按 manifest 恢复。
    foreach ($item in $manifest.items) {
        # 每次恢复都以数据库当前状态为准，覆盖“服务端成功但客户端未收到响应”的情况。
        $record = $resourceMaps[[int]$item.userIndex][[string]$item.title]
        if ($null -eq $record) { throw "无法恢复资料状态：$($item.title)" }
        $item.currentState = (@('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'OFFLINE', 'DELETED'))[[int]$record.status]
        Set-TargetState -Item $item -AdminToken $admin.accessToken
        Save-Manifest -Manifest $manifest -Path $script:ManifestPath
    }
    Add-PublicBehaviors -Manifest $manifest -Users $users -AdminToken $admin.accessToken -Root $SourceRoot
    Save-Manifest -Manifest $manifest -Path $script:ManifestPath
}

$summary = Verify-SeedRun -Manifest $manifest -Users $users
Save-Manifest -Manifest $manifest -Path $script:ManifestPath
$summary | Format-List
