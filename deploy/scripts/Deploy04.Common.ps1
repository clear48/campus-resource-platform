Set-StrictMode -Version Latest

function Get-Deploy04RepositoryRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptRoot
    )

    $repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $ScriptRoot '..\..'))
    if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot '.git'))) {
        throw "无法从脚本目录解析 Git 仓库根目录：$repositoryRoot"
    }
    return $repositoryRoot
}

function Assert-Deploy04Command {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "缺少必需命令：$Name"
    }
    return $command.Source
}

function Invoke-Deploy04Capture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$ArgumentList = @()
    )

    $output = @(& $FilePath @ArgumentList 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $message = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "命令执行失败（退出码 $LASTEXITCODE）：$FilePath`n$message"
    }
    return (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
}

function Invoke-Deploy04CaptureRaw {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    # 源码摘要必须保留 diff 行尾空白，不能使用普通捕获函数的 Trim。
    $output = @(& $FilePath @ArgumentList 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败（退出码 $LASTEXITCODE）：$FilePath"
    }
    return (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine)
}

function Invoke-Deploy04Step {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$ArgumentList = @(),

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$StepResults
    )

    Write-Host "[DEPLOY-04] 开始：$Name"
    $StepResults[$Name] = 'RUNNING'
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @ArgumentList
        if ($LASTEXITCODE -ne 0) {
            throw "步骤失败（退出码 $LASTEXITCODE）：$Name"
        }
        $StepResults[$Name] = 'PASSED'
        Write-Host "[DEPLOY-04] 通过：$Name"
    }
    catch {
        $StepResults[$Name] = 'FAILED'
        throw
    }
    finally {
        Pop-Location
    }
}

function New-Deploy04Secret {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prefix
    )

    # 使用系统密码学随机数；固定前缀确保满足生产配置对字符种类的校验。
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    $randomPart = ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    return "$Prefix$randomPart"
}

function Assert-Deploy04SourceUnchanged {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExpectedBranch,
        [Parameter(Mandatory = $true)]
        [string]$ActualBranch,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedCommit,
        [Parameter(Mandatory = $true)]
        [string]$ActualCommit,
        [AllowEmptyString()]
        [string]$ExpectedWorkingTreeStatus,
        [AllowEmptyString()]
        [string]$ActualWorkingTreeStatus,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedSourceDigest,
        [Parameter(Mandatory = $true)]
        [string]$ActualSourceDigest
    )

    if ($ActualBranch -ne $ExpectedBranch) {
        throw "构建期间分支发生变化：期望 '$ExpectedBranch'，实际 '$ActualBranch'。"
    }
    if ($ActualCommit -ne $ExpectedCommit) {
        throw "构建期间 HEAD 发生变化：期望 '$ExpectedCommit'，实际 '$ActualCommit'。"
    }
    if ($ActualWorkingTreeStatus -cne $ExpectedWorkingTreeStatus) {
        throw '构建期间工作区 tracked/untracked 状态发生变化，拒绝把镜像标记为原提交候选。'
    }
    if ($ActualSourceDigest -cne $ExpectedSourceDigest) {
        throw '构建期间源码内容摘要发生变化，拒绝把镜像标记为原提交候选。'
    }
}

function Get-Deploy04Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Resolve-Deploy04SafeUntrackedFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot,
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    $directorySeparators = [char[]]@(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $normalizedRoot = [System.IO.Path]::GetFullPath($RepositoryRoot).TrimEnd($directorySeparators)
    $normalizedTarget = [System.IO.Path]::GetFullPath((Join-Path $normalizedRoot $RelativePath))
    $pathComparison = if ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [System.Runtime.InteropServices.OSPlatform]::Windows)) {
        [System.StringComparison]::OrdinalIgnoreCase
    }
    else {
        [System.StringComparison]::Ordinal
    }
    $rootPrefix = $normalizedRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $normalizedTarget.StartsWith($rootPrefix, $pathComparison)) {
        throw "未跟踪文件解析到仓库外部：$RelativePath"
    }

    # 从仓库根逐层检查，避免目录 junction 或符号链接把读取重定向到仓库外。
    $rootAttributes = [System.IO.File]::GetAttributes($normalizedRoot)
    if (($rootAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw '仓库根目录是 ReparsePoint，拒绝生成未跟踪文件摘要。'
    }
    $relativeTarget = [System.IO.Path]::GetRelativePath($normalizedRoot, $normalizedTarget)
    $pathParts = $relativeTarget.Split($directorySeparators, [System.StringSplitOptions]::RemoveEmptyEntries)
    $currentPath = $normalizedRoot
    for ($index = 0; $index -lt $pathParts.Length; $index++) {
        $currentPath = Join-Path $currentPath $pathParts[$index]
        if (-not (Test-Path -LiteralPath $currentPath)) {
            throw "生成源码摘要时未跟踪文件发生变化：$RelativePath"
        }
        $attributes = [System.IO.File]::GetAttributes($currentPath)
        if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "未跟踪路径包含 ReparsePoint：$RelativePath"
        }
        $isTarget = $index -eq ($pathParts.Length - 1)
        if (-not $isTarget -and ($attributes -band [System.IO.FileAttributes]::Directory) -eq 0) {
            throw "未跟踪文件的父路径不是目录：$RelativePath"
        }
        if ($isTarget) {
            if (($attributes -band [System.IO.FileAttributes]::Directory) -ne 0 -or
                    ($attributes -band [System.IO.FileAttributes]::Device) -ne 0) {
                throw "未跟踪目标不是普通文件：$RelativePath"
            }
        }
    }
    return $normalizedTarget
}

function Get-Deploy04SourceSnapshotDigest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$GitCommand,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    Push-Location -LiteralPath $RepositoryRoot
    try {
        # HEAD 到工作树的 binary diff 同时覆盖 staged 与 unstaged 跟踪文件内容。
        $trackedDiff = Invoke-Deploy04CaptureRaw -FilePath $GitCommand -ArgumentList @('diff', '--binary', 'HEAD', '--')
        $trackedDigest = Get-Deploy04Sha256 -Value $trackedDiff
        $untrackedOutput = Invoke-Deploy04Capture -FilePath $GitCommand -ArgumentList @(
            '-c', 'core.quotePath=false', 'ls-files', '--others', '--exclude-standard'
        )
    }
    finally {
        Pop-Location
    }

    $snapshotParts = [System.Collections.Generic.List[string]]::new()
    $snapshotParts.Add("tracked=$trackedDigest")
    $untrackedPaths = @($untrackedOutput -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    [Array]::Sort($untrackedPaths, [System.StringComparer]::Ordinal)
    foreach ($relativePath in $untrackedPaths) {
        $fullPath = Resolve-Deploy04SafeUntrackedFile -RepositoryRoot $RepositoryRoot -RelativePath $relativePath
        $fileDigest = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $normalizedPath = $relativePath.Replace('\', '/')
        $snapshotParts.Add("untracked=$normalizedPath`:$fileDigest")
    }
    return Get-Deploy04Sha256 -Value ($snapshotParts -join "`n")
}

function Remove-Deploy04OwnedContainer {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerCommand,
        [AllowNull()]
        [string]$ContainerId,
        [Parameter(Mandatory = $true)]
        [string]$ContainerName,
        [Parameter(Mandatory = $true)]
        [string]$LabelKey,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedRunId
    )

    if ([string]::IsNullOrWhiteSpace($ContainerId)) {
        return [ordered]@{ status = 'NOT_CREATED'; name = $ContainerName; id = $null; message = $null }
    }

    try {
        $actualRunId = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
            'inspect', '--format', "{{ index .Config.Labels `"$LabelKey`" }}", $ContainerId
        )
        if ($actualRunId -ne $ExpectedRunId) {
            throw '容器标签与本次运行不匹配，已拒绝自动删除。'
        }
        Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @('rm', '--force', $ContainerId) | Out-Null
        $remainingContainerId = Invoke-Deploy04Capture -FilePath $DockerCommand -ArgumentList @(
            'ps', '--all', '--quiet', '--filter', "id=$ContainerId"
        )
        if (-not [string]::IsNullOrWhiteSpace($remainingContainerId)) {
            throw 'Docker 删除命令完成后仍能查询到本次容器。'
        }
        return [ordered]@{ status = 'PASSED'; name = $ContainerName; id = $ContainerId; message = $null }
    }
    catch {
        return [ordered]@{
            status = 'FAILED'
            name = $ContainerName
            id = $ContainerId
            message = '无法确认本次一次性 MySQL 容器已删除，请按 name/id 人工核对。'
        }
    }
}

function Remove-Deploy04TemporaryArtifacts {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Files,
        [Parameter(Mandatory = $true)]
        [string]$Directory
    )

    try {
        foreach ($temporaryFile in $Files) {
            if (Test-Path -LiteralPath $temporaryFile) {
                Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction Stop
            }
        }
        if (Test-Path -LiteralPath $Directory) {
            # 目录由本次 GUID 唯一创建且应已为空；不递归删除可避免误删意外文件。
            Remove-Item -LiteralPath $Directory -Force -ErrorAction Stop
        }
        $remainingPaths = @($Files + $Directory | Where-Object { Test-Path -LiteralPath $_ })
        if ($remainingPaths.Count -gt 0) {
            throw '临时 Secret/env 文件或目录仍然存在。'
        }
        return [ordered]@{ status = 'PASSED'; directory = $Directory; message = $null }
    }
    catch {
        return [ordered]@{
            status = 'FAILED'
            directory = $Directory
            message = '无法确认本次临时 Secret/env 文件已删除，请人工核对临时目录。'
        }
    }
}

function Write-Deploy04Evidence {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [object]$Evidence
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $parentDirectory = Split-Path -Parent $fullPath
    if ([string]::IsNullOrWhiteSpace($parentDirectory)) {
        throw "证据路径必须包含有效目录：$Path"
    }
    [System.IO.Directory]::CreateDirectory($parentDirectory) | Out-Null
    $Evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $fullPath -Encoding utf8
    return $fullPath
}
