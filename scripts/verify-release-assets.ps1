param(
    [string]$DistDirectory = "web_dist"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Verifying release assets in $DistDirectory..." -ForegroundColor Cyan

$releaseJsonPath = Join-Path $DistDirectory "release.json"
if (-not (Test-Path -LiteralPath $releaseJsonPath)) {
    throw "Verification FAILED: $releaseJsonPath does not exist!"
}

$rawJson = Get-Content -LiteralPath $releaseJsonPath -Raw
$manifest = $rawJson | ConvertFrom-Json

# Check required fields
$requiredFields = @("app", "version", "androidVersionName", "androidVersionCode", "buildType", "apk", "apkBytes", "apkSha256", "builtAt", "notes")
foreach ($field in $requiredFields) {
    if ($null -eq $manifest.$field -or [string]::IsNullOrWhiteSpace($manifest.$field.ToString())) {
        throw "Verification FAILED: release.json is missing required field '$field'"
    }
}

if ($manifest.app -ne "VoiceBrainLive") {
    throw "Verification FAILED: Invalid app name in release.json: expected 'VoiceBrainLive', got '$($manifest.app)'"
}

if ([long]$manifest.androidVersionCode -le 0) {
    throw "Verification FAILED: androidVersionCode must be a positive integer"
}

if ($manifest.apkSha256 -notmatch "^[a-fA-F0-9]{64}$") {
    throw "Verification FAILED: apkSha256 must be a 64-character hexadecimal string, got '$($manifest.apkSha256)'"
}

if ([long]$manifest.apkBytes -le 0 -or [long]$manifest.apkBytes -gt (250 * 1024 * 1024)) {
    throw "Verification FAILED: apkBytes ($($manifest.apkBytes)) is out of acceptable bounds (1..250MB)"
}

# Verify APK filename
$apkBasename = $manifest.apk
if ($apkBasename -match "[/\\]|\.\." -or $apkBasename -notmatch "^[A-Za-z0-9_.-]+\.apk$") {
    throw "Verification FAILED: apk filename '$apkBasename' must be a safe basename ending in .apk without directory traversal"
}

# Verify versioned APK file on disk
$apkPath = Join-Path $DistDirectory $apkBasename
if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "Verification FAILED: Referenced APK file does not exist at $apkPath"
}

$apkItem = Get-Item -LiteralPath $apkPath
if ($apkItem.Length -ne [long]$manifest.apkBytes) {
    throw "Verification FAILED: APK file size mismatch. Expected $($manifest.apkBytes) bytes, found $($apkItem.Length) bytes on disk"
}

$actualSha256 = (Get-FileHash -LiteralPath $apkItem.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $manifest.apkSha256.ToLowerInvariant()) {
    throw "Verification FAILED: APK SHA-256 checksum mismatch! Expected $($manifest.apkSha256), actual computed $actualSha256"
}

# Verify fallback VoiceBrainLive.apk
$defaultApkPath = Join-Path $DistDirectory "VoiceBrainLive.apk"
if (Test-Path -LiteralPath $defaultApkPath) {
    $defaultSha = (Get-FileHash -LiteralPath $defaultApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($defaultSha -ne $actualSha256) {
        throw "Verification FAILED: Default VoiceBrainLive.apk checksum does not match versioned APK"
    }
}

# Verify web assets
$indexPath = Join-Path $DistDirectory "index.html"
if (-not (Test-Path -LiteralPath $indexPath)) {
    throw "Verification FAILED: web_dist/index.html is missing"
}

$vercelPath = Join-Path $DistDirectory "vercel.json"
if (-not (Test-Path -LiteralPath $vercelPath)) {
    throw "Verification FAILED: web_dist/vercel.json is missing"
}

Write-Host "✅ All release assets verified successfully!" -ForegroundColor Green
Write-Host "   App: $($manifest.app) ($($manifest.androidVersionName), Build $($manifest.androidVersionCode))"
Write-Host "   APK: $apkBasename ($($manifest.apkBytes) bytes)"
Write-Host "   SHA-256: $actualSha256"
Write-Host "   Status: READY FOR DEPLOYMENT"
exit 0
