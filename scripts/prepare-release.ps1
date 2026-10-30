param(
    [string]$ApkDirectory = "app/build/outputs/apk/release",
    [string]$OutputDirectory = ".publication/release"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath ".release-signing/signing.properties")) {
    throw "The existing private release signing configuration is required; do not publish an unsigned APK."
}
$metadata = Get-Content -LiteralPath (Join-Path $ApkDirectory "output-metadata.json") -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne "com.example.voicebrainlive" -or $metadata.variantName -ne "release") {
    throw "Only the original-package release variant can be published."
}
$entries = @($metadata.elements)
if ($entries.Count -ne 1) { throw "Expected a single universal APK." }
$entry = $entries[0]
$apk = Get-Item -LiteralPath (Join-Path $ApkDirectory $entry.outputFile)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $OutputDirectory "VoiceBrainLive.apk")
$manifest = [ordered]@{
    versionCode = [long]$entry.versionCode
    versionName = [string]$entry.versionName
    packageName = [string]$metadata.applicationId
    sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    size = $apk.Length
    releaseNotes = "Token မလိုသော public updates နှင့် native Agent အသံ။"
}
$json = $manifest | ConvertTo-Json
[System.IO.File]::WriteAllText((Join-Path (Resolve-Path $OutputDirectory) "update.json"), $json, [System.Text.UTF8Encoding]::new($false))
Write-Output $json
