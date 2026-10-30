# VoiceBrainLive Releases

Distribution uses the public repository `naylinhtike1356-cyber/VoiceBrainLive-`.
Anyone can download releases without a GitHub account or token.

## Install and update

Download `VoiceBrainLive.apk` from the latest GitHub release.
In Android, approve installation from your browser when prompted.

Inside VoiceBrainLive, open Settings > App updates, tap Check for updates,
then Download & Install. No token entry or GitHub login is required.
Public asset URLs are restricted to the configured repository. Private pilot
and isolated test packages do not receive original-package updates.

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

Increment versionCode and versionName in `app/build.gradle.kts`, run unit tests,
and build `:app:assembleRelease` with the local signing configuration. Release
builds contain no private AI-service API keys; users configure them in Settings.

Publish a stable GitHub release with these exact asset names:

- `VoiceBrainLive.apk`: the signed release APK.
- `update.json`: an object with integer `versionCode`, string `versionName`,
  string `packageName`, 64-character hexadecimal `sha256`, and integer byte `size`.

Generate metadata from the actual signed APK. Upload both assets to a draft
release before publishing it. Do not replace assets on an already published
release; publish a higher version instead. The updater ignores prereleases.

Run `powershell -File scripts/prepare-release.ps1` after `:app:assembleRelease`
to prepare `.publication/release/VoiceBrainLive.apk` and `update.json` from the
build output metadata and the actual APK hash. Publish both files together.

## Agent audio

Notifications wake the existing interactive Gemini agent and show its card.
Legacy speech interfaces route to native agent audio; Android TTS engines are
not initialized. If Android blocks a background launch, a notification opens
the agent. If the provider fails, an error is shown without TTS fallback.
User-created Clock alarms remain independent of network voice availability.
