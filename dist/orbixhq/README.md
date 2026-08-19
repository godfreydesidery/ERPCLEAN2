# OrbixHQ — Android builds

`OrbixHQ-1.0.0-qa.apk` is built against **QA** (`http://16.170.11.41`). The
server address is only a default — it can be changed in the app on the sign-in
screen, so one binary works against any install.

## Building

    flutter build apk --release --dart-define=HQ_HOST=http://16.170.11.41   # QA
    flutter build apk --release --dart-define=HQ_HOST=https://erp.example.com

Without `HQ_HOST` the build defaults to `http://localhost:8081`, which is only
useful on an emulator against a local backend.

## Two things that will bite

**INTERNET permission.** Flutter puts `android.permission.INTERNET` in the
*debug* manifest only. It is now declared in
`android/app/src/main/AndroidManifest.xml` — if that line is ever lost, the
release build installs and runs but cannot reach the server at all, and every
screen shows "Cannot reach the server".

**Cleartext HTTP.** QA and the default `dist/` install serve plain HTTP, which
Android 9+ blocks. `android:usesCleartextTraffic="true"` is set for that
reason. Once clients are on HTTPS with a real certificate, drop the flag (or
narrow it with a network-security-config) rather than leaving it on.

## Signing

These builds are **debug-signed**. Android warns on install and they must not
be treated as a distribution build. A real release keystore is required before
the app goes to a client or a store — see the TODO in
`android/app/build.gradle.kts`.
