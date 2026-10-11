# VoiceBrainLive

Android voice assistant application (Android 8.0/API 26 or newer).

## Download

Signed APKs are distributed through this private repository's
[GitHub releases](https://github.com/naylinhtike1356-cyber/VoiceBrainLive-/releases/latest).
Sign in with an account that has repository access.

## Build

Use Android Studio's bundled JDK 21 and install Android SDK 36.
Run `gradlew.bat :app:assembleDebug` on Windows, or
`./gradlew :app:assembleDebug` on other systems.

AI-service credentials are user-configured in Settings. Developer credentials
may be supplied locally in `local.properties` for debug builds only; do not
commit this file. Release builds do not include private AI-service keys.

For signed releases and in-app updates, see [RELEASE_GUIDE.md](RELEASE_GUIDE.md).
The release signing key is intentionally not included in this repository.

## Tests

Run `gradlew.bat :app:testDebugUnitTest`. Update-manifest checks can be run with
`gradlew.bat :app:testDebugUnitTest --tests com.example.voicebrainlive.updates.ReleaseManifestTest`.
