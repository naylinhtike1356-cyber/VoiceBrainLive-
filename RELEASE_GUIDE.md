# VoiceBrainLive Releases

Distribution uses the private repository `naylinhtike1356-cyber/VoiceBrainLive-`.
Only GitHub accounts with access can download its releases.

## Install and update

Download `VoiceBrainLive.apk` from the latest GitHub release while signed in.
In Android, approve installation from your browser when prompted.

Inside VoiceBrainLive, open Settings > App updates. For direct update checks,
enter your own GitHub fine-grained personal access token restricted to this
repository with Contents: Read-only permission. Organization approval, if
applicable, must be completed first. No publisher token is included in the APK.
Alternatively, open the GitHub release page from this screen and use browser
sign-in without storing a token in the app.

The updater compares version codes, validates APK size and SHA-256, then checks
the package name, version, and signing certificate against the installed app.
Android requires your confirmation before installing. Tokens are saved only
when encrypted storage is available, excluded from backups, and never logged.
Clear the token field and check again to delete the saved token.

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
