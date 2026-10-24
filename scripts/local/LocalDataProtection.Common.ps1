#requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-CrpExistingDirectory {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Label 不能为空。"
    }
    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    $item = Get-Item -LiteralPath $resolved.Path -Force
    if (-not $item.PSIsContainer) {
        throw "$Label 必须是目录：$($item.FullName)"
    }
    return [IO.Path]::GetFullPath($item.FullName).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
}

function Assert-CrpNotVolumeRoot {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    $root = [IO.Path]::GetPathRoot($Path)
    if ([string]::IsNullOrWhiteSpace($root) -or $root.StartsWith('\\')) {
        throw "$Label 暂不支持 UNC 或无法识别的路径：$Path"
    }
    $normalizedRoot = $root.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    if ([string]::Equals($Path, $normalizedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label 不得直接使用卷根目录：$Path"
    }
}

function Test-CrpPathInsideOrEqual {
    param(
        [Parameter(Mandatory)] [string] $Candidate,
        [Parameter(Mandatory)] [string] $Container
    )

    $relative = [IO.Path]::GetRelativePath($Container, $Candidate)
    if ($relative -eq '.') {
        return $true
    }
    if ([IO.Path]::IsPathRooted($relative)) {
        return $false
    }
    return $relative -ne '..' -and -not $relative.StartsWith("..$([IO.Path]::DirectorySeparatorChar)") `
        -and -not $relative.StartsWith("..$([IO.Path]::AltDirectorySeparatorChar)")
}

function Assert-CrpPathsSeparated {
    param(
        [Parameter(Mandatory)] [string] $First,
        [Parameter(Mandatory)] [string] $FirstLabel,
        [Parameter(Mandatory)] [string] $Second,
        [Parameter(Mandatory)] [string] $SecondLabel
    )

    if ((Test-CrpPathInsideOrEqual -Candidate $First -Container $Second) `
            -or (Test-CrpPathInsideOrEqual -Candidate $Second -Container $First)) {
        throw "$FirstLabel 与 $SecondLabel 不得相同或互为父子目录。"
    }
}

function Assert-CrpNoReparsePoints {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label,
        [switch] $Recursive
    )

    $current = Get-Item -LiteralPath $Path -Force
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label 或其祖先包含 ReparsePoint，拒绝继续：$($current.FullName)"
        }
        $current = $current.Parent
    }

    if (-not $Recursive) {
        return
    }

    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($Path)
    while ($pending.Count -gt 0) {
        $directory = $pending.Dequeue()
        foreach ($child in Get-ChildItem -LiteralPath $directory -Force) {
            if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "$Label 包含 ReparsePoint，拒绝继续：$($child.FullName)"
            }
            if ($child.PSIsContainer) {
                $pending.Enqueue($child.FullName)
            }
            elseif (-not ($child -is [IO.FileInfo])) {
                throw "$Label 包含不支持的特殊文件：$($child.FullName)"
            }
        }
    }
}

function ConvertTo-CrpPortableRelativePath {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $FullPath
    )

    $relative = [IO.Path]::GetRelativePath($Root, $FullPath)
    if ($relative -eq '.' -or [IO.Path]::IsPathRooted($relative) -or $relative -eq '..' `
            -or $relative.StartsWith("..$([IO.Path]::DirectorySeparatorChar)") `
            -or $relative.StartsWith("..$([IO.Path]::AltDirectorySeparatorChar)")) {
        throw "文件路径越出受控根目录：$FullPath"
    }
    return $relative.Replace('\', '/')
}

function Assert-CrpSafeRelativePath {
    param([Parameter(Mandatory)] [string] $RelativePath)

    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) `
            -or $RelativePath -match '^[A-Za-z]:' -or $RelativePath.Contains('\')) {
        throw "清单包含非法相对路径：$RelativePath"
    }
    $segments = $RelativePath.Split('/', [StringSplitOptions]::None)
    if ($segments.Count -eq 0 -or $segments.Where({ $_ -eq '' -or $_ -eq '.' -or $_ -eq '..' }).Count -gt 0) {
        throw "清单包含非法相对路径：$RelativePath"
    }
}

function Get-CrpFileInventory {
    param([Parameter(Mandatory)] [string] $Root)

    $records = [Collections.Generic.List[object]]::new()
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($Root)
    while ($pending.Count -gt 0) {
        $directory = $pending.Dequeue()
        foreach ($child in Get-ChildItem -LiteralPath $directory -Force | Sort-Object FullName) {
            if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "目录包含 ReparsePoint，拒绝生成清单：$($child.FullName)"
            }
            if ($child.PSIsContainer) {
                $pending.Enqueue($child.FullName)
                continue
            }
            $relativePath = ConvertTo-CrpPortableRelativePath -Root $Root -FullPath $child.FullName
            $records.Add([pscustomobject][ordered]@{
                path   = $relativePath
                bytes  = [long]$child.Length
                sha256 = (Get-FileHash -LiteralPath $child.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            })
        }
    }
    return @($records | Sort-Object path)
}

function Assert-CrpInventoryMatches {
    param(
        [Parameter(Mandatory)] [object[]] $Expected,
        [Parameter(Mandatory)] [object[]] $Actual,
        [Parameter(Mandatory)] [string] $Label
    )

    if ($Expected.Count -ne $Actual.Count) {
        throw "$Label 文件数量不一致：expected=$($Expected.Count), actual=$($Actual.Count)"
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        $expectedRecord = $Expected[$index]
        $actualRecord = $Actual[$index]
        Assert-CrpSafeRelativePath -RelativePath ([string]$expectedRecord.path)
        if (-not $seen.Add([string]$expectedRecord.path)) {
            throw "$Label 清单包含重复路径：$($expectedRecord.path)"
        }
        if ([string]$expectedRecord.path -cne [string]$actualRecord.path `
                -or [long]$expectedRecord.bytes -ne [long]$actualRecord.bytes `
                -or [string]$expectedRecord.sha256 -cne [string]$actualRecord.sha256) {
            throw "$Label 文件校验失败：$($expectedRecord.path)"
        }
    }
}

function Invoke-CrpProcess {
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [string[]] $PrefixArguments = @(),
        [string[]] $Arguments = @(),
        [hashtable] $Environment = @{},
        [Parameter(Mandatory)] [string] $Stage
    )

    $resolvedCommand = Get-Command $FilePath -ErrorAction Stop
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $resolvedCommand.Source
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @($PrefixArguments) + @($Arguments)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    foreach ($entry in $Environment.GetEnumerator()) {
        $startInfo.Environment[[string]$entry.Key] = [string]$entry.Value
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "$Stage 无法启动外部命令。"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $errorType = if ([string]::IsNullOrWhiteSpace($stderr)) { 'NO_DIAGNOSTIC' } else { 'EXTERNAL_COMMAND_ERROR' }
            throw "$Stage 失败：exitCode=$($process.ExitCode), errorType=$errorType"
        }
        return [pscustomobject]@{ stdout = $stdout; stderr = $stderr; exitCode = $process.ExitCode }
    }
    finally {
        $process.Dispose()
    }
}

function Get-CrpArtifactDescriptor {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Name
    )

    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or $item.Length -le 0) {
        throw "备份产物为空或不是普通文件：$Name"
    }
    return [pscustomobject][ordered]@{
        name   = $Name
        bytes  = [long]$item.Length
        sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Enter-CrpExclusiveLock {
    param([Parameter(Mandatory)] [string] $BackupRoot)

    $lockPath = Join-Path $BackupRoot '.local-data-protection.lock'
    try {
        return [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    }
    catch {
        throw '已有本地备份或恢复任务正在运行。'
    }
}

function Test-CrpTcpPortOpen {
    param(
        [Parameter(Mandatory)] [string] $HostName,
        [Parameter(Mandatory)] [int] $Port,
        [int] $TimeoutMilliseconds = 500
    )

    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        return $task.Wait($TimeoutMilliseconds) -and $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

