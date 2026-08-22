# OrbixHQ — Android builds

`OrbixHQ-1.2.0-qa.apk` is the current QA build, built against **QA**
(`http://16.170.11.41`). The server address is only a default — it can be
changed in the app on the sign-in screen, so one binary works against any
install.

| Build | Version | Server baked in |
|---|---|---|
| `OrbixHQ-1.2.0-qa.apk` | 1.2.0+3 | `http://16.170.11.41` |
| `OrbixHQ-1.1.0-qa.apk` | 1.1.0+2 | `http://16.170.11.41` |
| `OrbixHQ-1.0.0-qa.apk` | 1.0.0+1 | `http://16.170.11.41` |

**1.2.0** answers the client's three pendings: amounts are written in full
(`TZS 3,500,000`, not `TZS 3.5M`) everywhere except chart bar labels, where a
full figure will not fit above a bar; a report can be previewed on screen
before it is sent anywhere; and Operations gains a Products screen showing
description, unit, pack sizes, buying price, selling price, margin and stock
on hand.

**1.1.0** adds pack sizes (cartons, boxes, outers with a price per unit),
receiving in the unit goods actually arrived in, unit labels on every quantity
in the stock and valuation reports, and real report sharing — PDF/CSV/text out
to WhatsApp, email or anywhere else through the phone's own share sheet. It
also fixes two writes that returned 400 on every attempt in 1.0.0: receive
goods (no `unitUid` on the line) and create item (wrong `type` and base-unit
fields).

## Building

    flutter build apk --release --dart-define=HQ_HOST=http://16.170.11.41   # QA
    flutter build apk --release --dart-define=HQ_HOST=https://erp.example.com

Without `HQ_HOST` the build defaults to `http://localhost:8081`, which is only
useful on an emulator against a local backend.

**Check the host actually landed.** `String.fromEnvironment` is const-folded
into the AOT snapshot, so a forgotten `--dart-define` produces a perfectly good
APK pointed at localhost:

    python -c "import zipfile; b=zipfile.ZipFile('build/app/outputs/flutter-apk/app-release.apk').read('lib/arm64-v8a/libapp.so'); print(b'16.170.11.41' in b)"

## Two things that will bite

**INTERNET permission.** Flutter puts `android.permission.INTERNET` in the
*debug* manifest only. It is declared in
`android/app/src/main/AndroidManifest.xml` — if that line is ever lost, the
release build installs and runs but cannot reach the server at all, and every
screen shows "Cannot reach the server". Verify with
`aapt2 dump badging <apk> | grep uses-permission`.

**Cleartext HTTP.** QA and the default `dist/` install serve plain HTTP, which
Android 9+ blocks. `android:usesCleartextTraffic="true"` is set for that
reason. Once clients are on HTTPS with a real certificate, drop the flag (or
narrow it with a network-security-config) rather than leaving it on.

## Signing

These builds are **debug-signed**. Android warns on install and they must not
be treated as a distribution build. A real release keystore is required before
the app goes to a client or a store — see the TODO in
`android/app/build.gradle.kts`.
