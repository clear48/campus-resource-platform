[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path (Join-Path $PSScriptRoot '..') '..'))
$targetScript = Join-Path $PSScriptRoot 'Test-Deploy08PublicAcceptance.ps1'
$temporaryBase = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) (
            'campus-resource-platform/deploy-08/script-tests')))
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $temporaryBase ([Guid]::NewGuid().ToString('N'))))
$passedCount = 0
$failedCount = 0

function Assert-Deploy08TestCondition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-Deploy08ScriptProcess {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$EvidencePath
    )

    # 使用独立进程验证参数绑定和真实退出码，同时避免测试函数污染被测脚本作用域。
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Get-Command pwsh -ErrorAction Stop).Source
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @(
            '-NoLogo',
            '-NoProfile',
            '-NonInteractive',
            '-File', $targetScript,
            '-BaseUri', $BaseUri,
            '-EvidencePath', $EvidencePath)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $standardOutput = $process.StandardOutput.ReadToEndAsync()
        $standardError = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill($true)
            throw '被测脚本在 30 秒内未退出。'
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $standardOutput.GetAwaiter().GetResult()
            Error = $standardError.GetAwaiter().GetResult()
        }
    }
    finally {
        $process.Dispose()
    }
}

function Invoke-Deploy08TestCase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    try {
        & $Action
        $script:passedCount++
        Write-Host "[PASS] $Name"
    }
    catch {
        $script:failedCount++
        Write-Host "[FAIL] $Name"
        Write-Host $_.Exception.Message
    }
}

function Assert-Deploy08RejectedBeforeEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$ExpectedError,
        [Parameter(Mandatory = $true)][string]$CaseName
    )

    $evidencePath = Join-Path $temporaryRoot "$CaseName.json"
    $result = Invoke-Deploy08ScriptProcess -BaseUri $BaseUri -EvidencePath $evidencePath
    $combinedOutput = $result.Output + "`n" + $result.Error

    Assert-Deploy08TestCondition -Condition ($result.ExitCode -ne 0) `
        -Message "非法 BaseUri 应返回非零退出码：$BaseUri"
    Assert-Deploy08TestCondition -Condition ($combinedOutput.Contains($ExpectedError)) `
        -Message "错误输出未包含预期信息：$ExpectedError"
    Assert-Deploy08TestCondition -Condition (-not (Test-Path -LiteralPath $evidencePath)) `
        -Message "BaseUri 预检失败前不应创建证据文件：$evidencePath"
}

[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
try {
    Invoke-Deploy08TestCase -Name '拒绝非 HTTPS BaseUri' -Action {
        Assert-Deploy08RejectedBeforeEvidence -BaseUri 'http://campusshare.online' `
            -ExpectedError 'BaseUri 必须是绝对 HTTPS 地址。' -CaseName 'non-https'
    }
    Invoke-Deploy08TestCase -Name '拒绝非生产 Host' -Action {
        Assert-Deploy08RejectedBeforeEvidence -BaseUri 'https://example.com' `
            -ExpectedError '该生产预检只允许访问 campusshare.online。' -CaseName 'wrong-host'
    }
    Invoke-Deploy08TestCase -Name '拒绝非生产 HTTPS 端口' -Action {
        Assert-Deploy08RejectedBeforeEvidence -BaseUri 'https://campusshare.online:8443/' `
            -ExpectedError 'BaseUri 只允许使用生产 HTTPS 默认端口 443。' -CaseName 'wrong-port'
    }
    Invoke-Deploy08TestCase -Name '拒绝带 Query 的 BaseUri' -Action {
        Assert-Deploy08RejectedBeforeEvidence -BaseUri 'https://campusshare.online/?probe=1' `
            -ExpectedError 'BaseUri 不得包含用户信息、查询参数或片段。' -CaseName 'query'
    }
    Invoke-Deploy08TestCase -Name '拒绝带 UserInfo 的 BaseUri' -Action {
        Assert-Deploy08RejectedBeforeEvidence -BaseUri 'https://tester@campusshare.online/' `
            -ExpectedError 'BaseUri 不得包含用户信息、查询参数或片段。' -CaseName 'userinfo'
    }
    Invoke-Deploy08TestCase -Name '拒绝带业务路径的 BaseUri' -Action {
        Assert-Deploy08RejectedBeforeEvidence -BaseUri 'https://campusshare.online/admin' `
            -ExpectedError 'BaseUri 必须指向站点根路径。' -CaseName 'path'
    }
    Invoke-Deploy08TestCase -Name '拒绝仓库内证据路径' -Action {
        $repositoryEvidence = Join-Path $repositoryRoot (
            'deploy/deploy08-forbidden-evidence-' + [Guid]::NewGuid().ToString('N') + '.json')
        $result = Invoke-Deploy08ScriptProcess -BaseUri 'https://campusshare.online' `
            -EvidencePath $repositoryEvidence
        $combinedOutput = $result.Output + "`n" + $result.Error

        Assert-Deploy08TestCondition -Condition ($result.ExitCode -ne 0) `
            -Message '仓库内证据路径应返回非零退出码。'
        Assert-Deploy08TestCondition `
            -Condition ($combinedOutput.Contains('证据文件必须位于 Git 仓库外。')) `
            -Message '仓库内证据路径未返回预期错误。'
        Assert-Deploy08TestCondition -Condition (-not (Test-Path -LiteralPath $repositoryEvidence)) `
            -Message '路径预检失败时不应在仓库内创建证据文件。'
    }
    Invoke-Deploy08TestCase -Name '拒绝并保留已有证据文件' -Action {
        $existingEvidence = Join-Path $temporaryRoot 'existing.json'
        $sentinel = 'existing-evidence-must-not-be-overwritten'
        [IO.File]::WriteAllText($existingEvidence, $sentinel, [Text.UTF8Encoding]::new($false))

        $result = Invoke-Deploy08ScriptProcess -BaseUri 'https://campusshare.online' `
            -EvidencePath $existingEvidence
        $combinedOutput = $result.Output + "`n" + $result.Error

        Assert-Deploy08TestCondition -Condition ($result.ExitCode -ne 0) `
            -Message '已有证据文件应返回非零退出码。'
        Assert-Deploy08TestCondition `
            -Condition ($combinedOutput.Contains('证据文件已经存在；为防止覆盖历史证据，必须使用新路径。')) `
            -Message '已有证据文件未返回预期错误。'
        Assert-Deploy08TestCondition `
            -Condition ([IO.File]::ReadAllText($existingEvidence) -ceq $sentinel) `
            -Message '已有证据文件内容被修改。'
    }
    Invoke-Deploy08TestCase -Name '所有拒绝校验均先于只读网络阶段' -Action {
        $source = [IO.File]::ReadAllText($targetScript)
        $baseUriGuard = $source.LastIndexOf("`nAssert-Deploy08BaseUri", [StringComparison]::Ordinal)
        $evidenceGuard = $source.LastIndexOf("`n`$resolvedEvidencePath = Assert-Deploy08SafeEvidencePath", [StringComparison]::Ordinal)
        $networkPhase = $source.LastIndexOf("`ntry {`n    foreach (`$path", [StringComparison]::Ordinal)

        Assert-Deploy08TestCondition -Condition ($baseUriGuard -ge 0) `
            -Message '未找到 BaseUri 顶层预检调用。'
        Assert-Deploy08TestCondition -Condition ($evidenceGuard -gt $baseUriGuard) `
            -Message '证据路径顶层预检必须位于 BaseUri 预检之后。'
        Assert-Deploy08TestCondition -Condition ($networkPhase -gt $evidenceGuard) `
            -Message '网络检查阶段必须位于 URI 和证据路径预检之后。'
        Assert-Deploy08TestCondition `
            -Condition ($source -notmatch '(?im)Method\s*=\s*[''"](?:POST|PUT|PATCH|DELETE)[''"]') `
            -Message '公网预检脚本不得声明生产写 HTTP 方法。'
        Assert-Deploy08TestCondition `
            -Condition ($source -notmatch '(?i)\.(?:PostAsync|PutAsync|PatchAsync|DeleteAsync)\s*\(') `
            -Message '公网预检脚本不得通过 HttpClient 调用生产写方法。'
        Assert-Deploy08TestCondition `
            -Condition ($source.Contains('AllowAutoRedirect = $false')) `
            -Message '所有公网请求必须禁止自动跟随重定向。'
        Assert-Deploy08TestCondition `
            -Condition ($source.Contains("[string]`$response.Content.Trim() -cne 'ok'")) `
            -Message 'healthz 必须严格校验固定 ok 文本契约。'
        Assert-Deploy08TestCondition `
            -Condition (($source | Select-String -Pattern "status = 'SKIPPED'" -AllMatches).Matches.Count -eq 12) `
            -Message '12 项未执行的生产验收必须全部显式标记为 SKIPPED。'
        Assert-Deploy08TestCondition `
            -Condition ($source.Contains("'PREFLIGHT_PASSED_WITH_SKIPS'")) `
            -Message '只读预检通过不得标记为 DEPLOY-08 整体通过。'
        $writeFunction = $source.IndexOf('function Write-Deploy08Evidence', [StringComparison]::Ordinal)
        $lastLinkCheck = $source.IndexOf(
            'Assert-Deploy08NoReparsePoint -Directory (Split-Path -Parent $Path)',
            $writeFunction,
            [StringComparison]::Ordinal)
        $createNew = $source.IndexOf('[IO.FileMode]::CreateNew', $writeFunction, [StringComparison]::Ordinal)
        Assert-Deploy08TestCondition `
            -Condition ($writeFunction -ge 0 -and $lastLinkCheck -gt $writeFunction -and $createNew -gt $lastLinkCheck) `
            -Message '证据文件必须在 CreateNew 落盘前再次核对父目录链接。'
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $resolvedParent = [IO.Path]::GetFullPath((Split-Path -Parent $temporaryRoot))
        $leafName = Split-Path -Leaf $temporaryRoot
        $attributes = [IO.File]::GetAttributes($temporaryRoot)
        if ($resolvedParent -cne $temporaryBase -or $leafName -notmatch '^[0-9a-f]{32}$' -or
            ($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw '测试临时目录不在本任务专属根目录内，拒绝递归清理。'
        }
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

Write-Host "DEPLOY-08 脚本测试完成：passed=$passedCount failed=$failedCount"
if ($failedCount -ne 0) {
    exit 1
}
exit 0
