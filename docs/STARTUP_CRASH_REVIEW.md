# Startup Crash Review

## Confirmed release defect

The signed 1.1.0 APK was installed on an isolated API 36.1 emulator.
Launching MainActivity redirected to OnboardingActivity, then crashed the app
process while MainActivity was being destroyed. Logcat reported:

```text
Unable to destroy activity ... MainActivity
lateinit property audioRecorder has not been initialized
MainActivity.onDestroy -> MainActivityViewModel.clearCallbacks
```

MainActivity can finish before `initClientWithCache()` on first launch. Its
cleanup and the ViewModel's `onCleared()` must therefore tolerate an
uninitialized audio recorder. Guarding callback cleanup fixes that lifecycle
error rather than suppressing the process's uncaught exception handler.

## Related lifecycle hardening

- Do not create the lazy TTS helper simply to shut it down.
- Cancel pending activity callbacks when the activity is destroyed.
- Report background initialization failures in the UI instead of allowing an
  unhandled launch coroutine failure to terminate the process.
- Do not start wake-word/background microphone services without microphone
  permission. Catch denied background-service starts during voice handoff.

## Verification scope

The full debug unit-test run passed 245 tests across 42 suites, with zero
failures, errors, or skipped tests. All four new startup regressions passed.

StartupLaunchTest covers first-run redirect cleanup, repeated cleanup of an
uninitialized ViewModel, onboarding creation, and main lifecycle creation on
a landscape tablet-size configuration. It uses an invalid placeholder API key
and no active network, so developer credentials are not exercised.

The main lifecycle test deliberately does not drain an infinite Compose
animation queue. It is not a substitute for rendering the signed release and
testing actual user interactions on a device.

The signed 1.1.1 release build also passed. Its certificate SHA-256 digest
matches 1.1.0, so signing identity is unchanged. Developer Gemini, OpenAI,
YouTube and Serper API-key fields are empty in the release BuildConfig.

On the isolated API 36.1 emulator, configured to 1280 x 800 at 160 dpi,
1.1.1 survived the first-run MainActivity destruction and remained alive
with OnboardingActivity resumed. The original uninitialized-recorder crash
did not recur. This is process/lifecycle verification, not a full UI pass:
the emulator itself showed System UI ANRs and a black display with no focused
window, also when opening Android system Settings. Interactive main/settings
screen and screenshot verification could not be completed reliably there.

The user's tablet crash log and model are not yet available; the reproduced
defect may not be the tablet's only issue. Samsung's malicious-app installation
warning is a separate security classification and is not resolved by these
lifecycle changes. Do not disable device protection or change package/signing
identity to work around that warning.
