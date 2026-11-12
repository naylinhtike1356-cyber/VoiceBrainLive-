param(
    [string]$Variant = "release",
    [string]$ApkDirectory = "",
    [string]$DistDirectory = "web_dist",
    [string[]]$ReleaseNotes = @(
        "In-App update system with background check and automated integrity verification",
        "Streaming download with SHA-256 validation and safe package installer integration",
        "Material 3 Update status, progress tracking, and unknown sources permission flow"
    )
)

$ErrorActionPreference = "Stop"

if (-not $ApkDirectory) {
    $ApkDirectory = "app/build/outputs/apk/$Variant"
}

Write-Host "==> Preparing release from $ApkDirectory to $DistDirectory..." -ForegroundColor Cyan

$signingFile = ".release-signing/signing.properties"
if ($Variant -eq "release") {
    if (-not (Test-Path -LiteralPath $signingFile)) {
        throw "The existing private release signing configuration ($signingFile) is required for release variant; do not publish an unsigned APK."
    }
}

$metadataPath = Join-Path $ApkDirectory "output-metadata.json"
if (-not (Test-Path -LiteralPath $metadataPath)) {
    throw "Build metadata not found at $metadataPath. Please build the APK first (e.g. gradlew.bat :app:assembleRelease or :app:assembleDebug)."
}

$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne "com.example.voicebrainlive") {
    throw "Invalid application ID: expected 'com.example.voicebrainlive' but found '$($metadata.applicationId)'"
}

$entries = @($metadata.elements)
if ($entries.Count -lt 1) {
    throw "Expected at least one output APK element in metadata."
}
$entry = $entries[0]

$apkSourcePath = Join-Path $ApkDirectory $entry.outputFile
if (-not (Test-Path -LiteralPath $apkSourcePath)) {
    throw "APK file not found at $apkSourcePath"
}

$apkItem = Get-Item -LiteralPath $apkSourcePath
$apkBytes = $apkItem.Length
if ($apkBytes -le 0 -or $apkBytes -gt (250 * 1024 * 1024)) {
    throw "Invalid APK size: $apkBytes bytes (must be between 1 byte and 250 MB)"
}

$hashResult = Get-FileHash -LiteralPath $apkItem.FullName -Algorithm SHA256
$apkSha256 = $hashResult.Hash.ToLowerInvariant()

$versionCode = [long]$entry.versionCode
$versionName = [string]$entry.versionName
$buildType = [string]$metadata.variantName
$appName = "VoiceBrainLive"
$versionedApkName = "$appName-v$versionName-$versionCode.apk"

# Check previous published release in $DistDirectory
$previousReleaseJsonPath = Join-Path $DistDirectory "release.json"
if (Test-Path -LiteralPath $previousReleaseJsonPath) {
    try {
        $prevJson = Get-Content -LiteralPath $previousReleaseJsonPath -Raw | ConvertFrom-Json
        $prevCode = [long]($prevJson.androidVersionCode)
        $prevSha = [string]($prevJson.apkSha256)
        if (-not $prevCode) {
            $prevCode = [long]($prevJson.versionCode)
            $prevSha = [string]($prevJson.sha256)
        }

        if ($versionCode -lt $prevCode) {
            throw "Version progression error: new versionCode ($versionCode) is lower than previous published versionCode ($prevCode)"
        }
        if ($versionCode -eq $prevCode -and $apkSha256 -ne $prevSha.ToLowerInvariant()) {
            throw "Cannot overwrite already-published version code $versionCode with changed APK content! You must increment versionCode for new changes."
        }
    } catch [System.Management.Automation.RuntimeException] {
        throw $_
    }
}

# Ensure destination directory exists
if (-not (Test-Path -LiteralPath $DistDirectory)) {
    New-Item -ItemType Directory -Path $DistDirectory -Force | Out-Null
}

# Copy versioned immutable APK and default VoiceBrainLive.apk
$destVersionedApk = Join-Path $DistDirectory $versionedApkName
$destDefaultApk = Join-Path $DistDirectory "$appName.apk"

Copy-Item -LiteralPath $apkItem.FullName -Destination $destVersionedApk -Force
Copy-Item -LiteralPath $apkItem.FullName -Destination $destDefaultApk -Force

# Generate release.json matching the exact metadata contract
$builtAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$releaseManifest = [ordered]@{
    app = $appName
    version = "v$versionName"
    androidVersionName = $versionName
    androidVersionCode = $versionCode
    buildType = $buildType
    apk = $versionedApkName
    apkBytes = $apkBytes
    apkSha256 = $apkSha256
    builtAt = $builtAt
    notes = $ReleaseNotes
}

$releaseJson = $releaseManifest | ConvertTo-Json -Depth 5
$releaseJsonPath = Join-Path $DistDirectory "release.json"
[System.IO.File]::WriteAllText((Resolve-Path $DistDirectory).Path + "/release.json", $releaseJson, [System.Text.UTF8Encoding]::new($false))

# Also generate update.json for backward compatibility
$legacyManifest = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    packageName = "com.example.voicebrainlive"
    sha256 = $apkSha256
    size = $apkBytes
    releaseNotes = ($ReleaseNotes -join "`n• ")
}
$legacyJson = $legacyManifest | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText((Resolve-Path $DistDirectory).Path + "/update.json", $legacyJson, [System.Text.UTF8Encoding]::new($false))

Write-Host "✅ Release prepared successfully:" -ForegroundColor Green
Write-Host "   App: $appName v$versionName (Build $versionCode)"
Write-Host "   APK: $versionedApkName ($apkBytes bytes)"
Write-Host "   SHA-256: $apkSha256"
Write-Host "   Metadata: $releaseJsonPath"
