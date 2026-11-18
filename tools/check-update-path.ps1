# Run this after every release (after pushing and deploying the Worker). It checks the update path the way an OLD
# launcher would use it, and exits with an error if any installed launcher could be left without the update.
#
#   powershell -File tools/check-update-path.ps1
#   powershell -File tools/check-update-path.ps1 -OldVersion 1.6.28
param([string]$OldVersion = "1.0.0")
$ErrorActionPreference = "Continue"
$repoRoot = Split-Path -Parent $PSScriptRoot

$appInfo = Get-Content -Raw (Join-Path $repoRoot "src/main/java/com/vclient/AppInfo.java")
$expected = [regex]::Match($appInfo, 'VERSION = "([^"]+)"').Groups[1].Value
$backend = [regex]::Match($appInfo, 'DEFAULT_BACKEND_URL = "([^"]+)"').Groups[1].Value
$client = Get-Content -Raw (Join-Path $repoRoot "src/main/java/com/vclient/BackendClient.java")
$sites = [regex]::Matches($client, '"(https://[^"]+/V-client)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique

$failures = @()
Write-Host "Expected release: $expected (as built into AppInfo)"

# 1. The Worker, asked as an old launcher: this is the path that rescues launchers whose website address is dead.
try {
    $answer = Invoke-RestMethod -Uri "$backend/api/launcher/update?platform=windows&current=$OldVersion" -TimeoutSec 20
    $backendVersion = if ($answer.latest) { $answer.latest.version } else { "(none)" }
    if ($backendVersion -eq $expected -and $answer.updateAvailable) { Write-Host "OK    backend $backend offers $backendVersion to a $OldVersion launcher" }
    else { $failures += "backend says latest=$backendVersion, updateAvailable=$($answer.updateAvailable) (expected $expected): deploy the Worker with 'cd cloudflare; npx wrangler deploy'" }
} catch { $failures += "backend $backend is unreachable: $($_.Exception.Message)" }

# 2. Every website mirror a launcher may use. At least one must be current; dead old mirrors only warn.
$goodSites = 0
foreach ($site in $sites) {
    try {
        $info = Invoke-RestMethod -Uri "$site/update.json?t=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" -TimeoutSec 20
        if ($info.version -eq $expected) {
            $goodSites++
            Write-Host "OK    $site serves $($info.version)"
            $head = Invoke-WebRequest -Uri "$site$($info.url)" -Method Head -TimeoutSec 30 -UseBasicParsing
            if ([int64]$head.Headers["Content-Length"] -ne [int64]$info.size) { $failures += "$site installer size does not match update.json" }
        } else { Write-Host "WARN  $site serves $($info.version), not $expected" }
    } catch { Write-Host "WARN  $site is not serving updates ($($_.Exception.Message))" }
}
if ($goodSites -eq 0) { $failures += "no website mirror serves $expected yet (GitHub Pages can take a few minutes after a push)" }

if ($failures.Count -gt 0) {
    Write-Host ""
    foreach ($failure in $failures) { Write-Host "FAIL  $failure" -ForegroundColor Red }
    Write-Host "Some installed launchers might not receive this release. Fix the items above before announcing it." -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "Update path OK: old launchers will be offered $expected."
