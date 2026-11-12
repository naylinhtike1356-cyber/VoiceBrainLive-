# VoiceBrainLive Releases

The original app checks the dedicated VoiceBrainLive update feed at
`https://voicebrainlive.vercel.app/release.json`. Older installs, including
`1.6.0`, check GitHub Releases in `naylinhtike1356-cyber/VoiceBrainLive-`.
Keep both channels available so users can update from either version.

## Install and update

Download `VoiceBrainLive.apk` from the latest GitHub release.
In Android, approve installation from your browser when prompted.

Inside VoiceBrainLive, open Settings > App updates, tap Check for updates,
then Download & Install. No token entry or GitHub login is required.
The dedicated feed serves the APK from the same HTTPS origin. Private pilot and
isolated test packages do not receive original-package updates.

The updater compares version codes, validates APK size and SHA-256, then checks
the package name, version, and signing certificate against the installed app.
Android requires your confirmation before installing. The app never uses any
previously saved GitHub update token for public updates.

An existing debug-signed installation cannot be updated with a release-signed
APK. Back up/export its data before replacing it. Uninstalling clears app data.
Subsequent release-signed versions can update in place using the same key.

## Maintain releases

Keep `.release-signing/voicebrainlive.jks` and `signing.properties` private and
securely backed up. Losing this key prevents compatible updates. Never publish
these files, `local.properties`, device recordings, or developer credentials.

Increment `versionCode` and `versionName` in `app/build.gradle.kts`. Run
`:app:compileDebugKotlin` and `:app:testDebugUnitTest`, then build
`:app:assembleRelease` with the original release signing key. Release builds
contain no private AI-service API keys. Users configure them in Settings.

Run `powershell -File scripts/prepare-release.ps1` after building. The script
prepares the versioned APK, `VoiceBrainLive.apk`, `release.json`, and `update.json`
in `web_dist`. Verify the assets with `scripts/verify-release-assets.ps1`.

Commit the verified source and push it to GitHub `main`. Create the matching
stable GitHub release from that commit, attach `VoiceBrainLive.apk` and
`update.json` to the draft, then publish it. Use a higher version for each new
APK. The older app updater ignores prereleases.

Verify that the `web_dist` link and production domain belong to the dedicated
VoiceBrainLive Vercel project. Run `scripts/publish-release.ps1` with
`-ProductionBaseUrl https://voicebrainlive.vercel.app`. Confirm that both
channels return the new version and that their downloaded APKs match the signed
build's SHA-256. Check **Settings → App updates** on an older original install.

## Agent audio

Notifications wake the existing interactive Gemini agent and show its card.
Legacy speech interfaces route to native agent audio; Android TTS engines are
not initialized. If Android blocks a background launch, a notification opens
the agent. If the provider fails, an error is shown without TTS fallback.
User-created Clock alarms remain independent of network voice availability.
