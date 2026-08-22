# OrbixHQ — Android builds

The server address is only a default — it can be changed in the app on the
sign-in screen, so one binary works against any install. It is baked in so that
whoever receives the APK does not have to type an IP address correctly before
the app will do anything.

| Build | Version | Server baked in | For |
|---|---|---|---|
| `OrbixHQ-1.2.1-kilimanjaro.apk` | 1.2.1+4 | `http://51.21.23.170` | Kilimanjaro Supermarket (live) |
| `OrbixHQ-1.2.1-qa.apk` | 1.2.1+4 | `http://16.170.11.41` | QA |
| `OrbixHQ-1.2.0-kilimanjaro.apk` | 1.2.0+3 | `http://51.21.23.170` | superseded |
| `OrbixHQ-1.2.0-qa.apk` | 1.2.0+3 | `http://16.170.11.41` | superseded |
| `OrbixHQ-1.1.0-qa.apk` | 1.1.0+2 | `http://16.170.11.41` | superseded |
| `OrbixHQ-1.0.0-qa.apk` | 1.0.0+1 | `http://16.170.11.41` | superseded |

## Changing the server on a phone (support)

**The address is not on the sign-in screen.** From 1.2.1 it is reached by
**tapping the footer line — "Protected by your company's server" — seven
times**. From the fourth tap a small "3 more" counts down, so you can tell
someone on the phone whether it is registering. The dialog states the address
the phone is currently set to before offering to change it, which is also how
you get someone to read it back to you.

It was a labelled field with a pencil above the username. A manager never needs
it, and a phone pointed at the wrong server looks exactly like a broken app, so
the field invited the support call it could not prevent. Seven taps is the
Android developer-options idiom: nobody arrives by accident, and it survives
being described down a phone line.

The read-only address stays visible under **Settings, About** — that is where
you ask someone to look when you need to know what a phone is pointed at.

**Check the server before shipping a client build.** The app is only as new as
the API behind it: a screen calling an endpoint the customer's server does not
have fails in their hands, not in testing. Kilimanjaro runs OrbixERP 1.8.3
(built 2026-08-15), and 1.2.0's endpoints all landed on `main` before that —
per-unit prices 2026-07-04, direct goods receipt 2026-08-08, the Product List
report 2026-08-11, the pack-factor correction 2026-08-12. This cannot be
probed: the API answers 401 for a route that does not exist exactly as it does
for one that needs a login, so the git dates are the evidence.

**1.2.1** hides the server-address entry behind the seven-tap gesture above,
and fixes the About card, which reported version 1.0.0 on every build since —
support asks for that number and acts on the answer. A test now fails the build
if the stated version and pubspec drift apart again.

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
    flutter build apk --release --dart-define=HQ_HOST=http://51.21.23.170   # Kilimanjaro
    flutter build apk --release --dart-define=HQ_HOST=https://erp.example.com

Pass scheme and host only. `HqConfig` appends `/api/v1` itself, so a host
ending in `/api/v1` yields `/api/v1/api/v1` and every call 401s.

Without `HQ_HOST` the build defaults to `http://localhost:8081`, which is only
useful on an emulator against a local backend.

**Check the host actually landed.** `String.fromEnvironment` is const-folded
into the AOT snapshot, so a forgotten `--dart-define` produces a perfectly good
APK pointed at localhost:

    python -c "import zipfile; b=zipfile.ZipFile('build/app/outputs/flutter-apk/app-release.apk').read('lib/arm64-v8a/libapp.so'); print(b'51.21.23.170' in b)"

On a client build, check the *other* hosts are absent too — a build pointed at
one customer that still carries another's address is worse than one pointed at
localhost.

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
