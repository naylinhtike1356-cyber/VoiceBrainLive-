param(
    [string]$DistDirectory = "web_dist",
    [switch]$SkipRemoteVerification = $false,
    [string]$ProductionBaseUrl = ""
)

$ErrorActionPreference = "Stop"

# Smart Salary owns this project and domain. Never publish VoiceBrainLive there.
if ([string]::IsNullOrWhiteSpace($ProductionBaseUrl)) {
    throw 'Publication blocked: provide -ProductionBaseUrl for a dedicated VoiceBrainLive site. The Smart Salary domain must not be used.'
}
$productionUri = $null
if (-not [Uri]::TryCreate($ProductionBaseUrl, [UriKind]::Absolute, [ref]$productionUri) -or
    $productionUri.Scheme -ne 'https' -or $productionUri.UserInfo -or
    $productionUri.AbsolutePath -ne '/' -or $productionUri.Query -or $productionUri.Fragment -or
    $productionUri.DnsSafeHost -in @('webdist-puce.vercel.app', 'webdist-naylin.vercel.app')) {
    throw 'Publication blocked: use the HTTPS origin of a dedicated VoiceBrainLive project.'
}
$ProductionBaseUrl = $productionUri.GetLeftPart([UriPartial]::Authority)
if ($ProductionBaseUrl -ne 'https://voicebrainlive.vercel.app') {
    throw 'Publication blocked: the production origin must match the dedicated VoiceBrainLive project.'
}
if (-not [IO.Path]::IsPathRooted($DistDirectory)) {
    $DistDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) $DistDirectory
}
$linkPath = Join-Path $DistDirectory '.vercel/project.json'
if (-not (Test-Path -LiteralPath $linkPath)) {
    throw 'Publication blocked: explicitly link the dedicated VoiceBrainLive Vercel project first.'
}
$linkedProject = Get-Content -LiteralPath $linkPath -Raw | ConvertFrom-Json
if ($linkedProject.projectId -ne 'prj_Peu8KbiOZ77xityQRgw7bIRrKs5K' -or
    $linkedProject.orgId -ne 'team_DL04XbV2Wiyt4AsfEPU1SPVb') {
    throw 'Publication blocked: link the verified VoiceBrainLive Vercel project before deploying.'
}

Write-Host "==> Starting release publication pipeline..." -ForegroundColor Cyan

# Step 1: Verify Assets
& "$PSScriptRoot/verify-release-assets.ps1" -DistDirectory $DistDirectory

$releaseJsonPath = Join-Path $DistDirectory "release.json"
$manifest = Get-Content -LiteralPath $releaseJsonPath -Raw | ConvertFrom-Json

Write-Host "==> Publishing release for $($manifest.app) v$($manifest.androidVersionName) (Build $($manifest.androidVersionCode))..." -ForegroundColor Cyan

# Step 2: Deploy to Vercel
if (Test-Path -LiteralPath (Join-Path $DistDirectory ".vercel/project.json")) {
    Write-Host "==> Found Vercel project configuration. Deploying to Vercel production..." -ForegroundColor Yellow
    try {
        Push-Location $DistDirectory
        $prevAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $deployOutput = @(vercel --prod --yes 2>&1)
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $prevAction
        if ($exitCode -ne 0) { throw "Vercel deployment failed: $($deployOutput -join "`n")" }
        Write-Host "Vercel output: $($deployOutput -join "`n")" -ForegroundColor Gray
    } finally {
        Pop-Location
    }
}

# Step 3: Remote Verification
$feedUrl = "$ProductionBaseUrl/release.json"
$apkUrl = "$ProductionBaseUrl/$($manifest.apk)"

if (-not $SkipRemoteVerification) {
    Write-Host "==> Verifying live public update feed: $feedUrl..." -ForegroundColor Cyan
    $response = Invoke-RestMethod -Uri $feedUrl -Method Get -TimeoutSec 15 -MaximumRedirection 0
    if ($response.androidVersionCode -ne $manifest.androidVersionCode -or
        $response.apk -ne $manifest.apk -or $response.apkSha256 -ne $manifest.apkSha256 -or
        $response.apkBytes -ne $manifest.apkBytes) {
        throw 'Live update feed does not match the prepared release. Check the deployment and feed before distributing it.'
    }
    Write-Host "[OK] Live public feed matches the current release!" -ForegroundColor Green
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " [SUCCESS] RELEASE PUBLICATION SUMMARY" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " App: $($manifest.app)"
Write-Host " Version: $($manifest.androidVersionName) (Build $($manifest.androidVersionCode))"
Write-Host " APK Basename: $($manifest.apk)"
Write-Host " Size: $($manifest.apkBytes) bytes (~$([math]::Round($manifest.apkBytes / 1MB, 2)) MB)"
Write-Host " SHA-256: $($manifest.apkSha256)"
Write-Host " Production Update Feed: $feedUrl"
Write-Host " Direct APK URL: $apkUrl"
Write-Host "==========================================================" -ForegroundColor Green
