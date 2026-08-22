# DECISION BRIEF — Executive Mobile App Platform

## 1. THE DECISION

**Build it in Flutter as a second app in this monorepo (`mobile_exec/`), consuming a new shared Dart package `packages/orbix_erp_client/` extracted from `pos_app/lib/core` — Android-first via the existing `dist/` APK channel, with the real-domain + Let's Encrypt work (ADR-0061 follow-up #1) running as a parallel, funded track rather than a follow-up.**

---

## 2. SCORED COMPARISON

Weights reflect what actually decides this project: a one-developer team, a self-hosted per-client estate, and an audience whose defining complaint is *"an approval that quietly sits somewhere I never look."* Scores 1–5.

| # | Criterion | Wt | Flutter | RN/Expo | PWA | Why these scores |
|---|---|---|---|---|---|---|
| A | **Long-term maintenance by a small team** | 20 | **4** | 2 | **5** | PWA is one artifact, one version, one deploy (`dist/Dockerfile.build` already bakes the SPA into the jar) — unbeatable. Flutter keeps the mobile estate at **one** toolchain and shares a package with a live POS. RN scores 2 because it means Flutter *and* React Native side by side, two SDKs, two release pipelines, and zero shared code between two apps that speak the same envelope. There is no React anywhere in this repo. |
| B | **Private-CA TLS + per-client server addressing, on the estate that exists TODAY** | 15 | **4** | 4 | 2 | Prod is `tls internal` on a bare IP; the default `dist/` install is **plain HTTP** (`dist/bundle/.env.example:63,76`). Flutter reaches all three environments in-process via `SecurityContext.setTrustedCertificatesBytes` — app-scoped trust, one binary, runtime CA import (real work, but tractable). RN gets OS-trust-store honouring free but needs an admin device-config dance. PWA scores 2 because a service worker **refuses to register on a cert-error origin** and cannot register at all on `http://…:8080` — so on today's estate a PWA degrades to a responsive web page. |
| C | **Time to first usable version in the owner's hand** | 15 | **4** | 4 | **5** | PWA: ~4 days to a Home Screen icon on the production build. Flutter: ~5 days to a signed APK doing login + approvals inbox against QA (`http://16.170.11.41` — no TLS wall), because the transport layer is already written and already proven against this backend. RN: ~3–5 days to an Expo dev-client QR, then a React ramp of 1–2 weeks. |
| D | **Push notifications on the real estate** | 12 | **4** | 4 | 2 | The backend is ~80% ready either way (`NotificationChannel.PUSH` reserved; `V21:78` CHECK already permits it; `ApprovalSubmittedNotificationHandler` already resolves recipients). FCM covers Android natively and iOS via APNs — one credential. Web Push needs no Firebase (a genuine PWA win) but on iOS works **only if the user completed Add-to-Home-Screen**, silently absent otherwise. For an app whose reason to exist is "tell me an approval is waiting", silently-absent is disqualifying. |
| E | **Reuse of already-shipped client code** | 10 | **5** | 3 | **5** | ~1,230 lines of `pos_app/lib/core` lift straight over — and they are not boilerplate, they are scars: single-flight refresh (`token_manager.dart:66-68`), "only 401/403 clears a session" (`:79-87`), `errors[]`-as-`List<String>` parsing (`api_exception.dart:160-172`), Long-as-string coercion (`json.dart`), byte-exact money (`money.dart`). PWA reuses the *whole* Angular stack — interceptors, `SessionStore`, guards — which is worth more by volume. RN reuses the TypeScript DTO models but re-writes every one of those five scars. |
| F | **Biometrics + refresh token at rest** | 10 | **5** | 5 | 2 | Both native options bind the refresh token to a hardware key with `biometryCurrentSet` / StrongBox. PWA has no Keychain equivalent — and WebAuthn, the correct primitive, **requires a registrable domain as its RP ID; `16.192.117.45` cannot be one.** No biometrics until DNS lands. |
| G | **Executive UX: offline reads, freshness, screenshot control, feel** | 10 | **5** | 4 | 2 | Android `FLAG_SECURE` has no browser equivalent — a consolidated P&L on a personal phone can be screenshotted into a device backup. No iOS Background Sync. Flutter's render predictability on the mid-range Androids this audience carries is the best of the three. |
| H | **Store distribution** | 8 | 4 | 4 | **5** | PWA deletes the problem entirely: no Apple Developer account, no 4.3 duplicate-app risk, no release keystore, no `ios/` folder. Flutter/RN both ship the same shape: **one binary, runtime server config**, sideloaded APK → Play internal → stores once a domain is standard. |

**Weighted totals: Flutter 86 · React Native 72 · PWA 72.**

The two runners-up tie for opposite reasons — PWA is cheaper than everything and blocked by the estate; RN is unblocked and costs a second mobile toolchain. Flutter is the only option that is both unblocked today *and* leaves the team with one mobile language.

**The one-sentence version for the owner:** *you already own a working Flutter client that talks to this exact server over this exact awkward certificate; the second app inherits that and costs you no new skill, no new build tool, and no new person.*

---

## 3. THE THREE STRONGEST ARGUMENTS AGAINST — AND MY REBUTTALS

**Objection 1 — "The reusable code here is 66,539 lines of TypeScript, not 1,294 lines of Dart. You are optimising for 3% of a codebase."**

Correct on the arithmetic, wrong on which 3%. Those 1,294 lines are not generic plumbing; they are the exact set of mistakes this project has already paid for in production. `pos-generic-400-masks-real-error` was a live client running blind on hidden HTTP 400s for weeks — the fix is `api_exception.dart:160-172`. `wire-serialization-number-vs-string` is a runtime-crash class the Dart client already immunised against. And `AuthServiceImpl.java:119-147` chain-revokes **every session that user holds, including the tills**, on a replayed refresh token — the mitigation is two lines in `token_manager.dart` that took a real incident to learn. A React Native app re-learns all five, in a language nobody here has shipped, while a live Flutter POS keeps running beside it. The TypeScript volume is real reuse — for a *web* app. The exec app is not a web app; the moment it needs push, hardware-bound tokens or screenshot control, none of those 66,539 lines help.

**Objection 2 — "The PWA is 3.5 weeks, zero new artifacts, zero new version numbers, and reuses `http.interceptors.ts` and `session.store.ts` as they are. You are spending eight weeks and a third release train to show numbers."**

The PWA's cost is deferred, not avoided. On the estate as it stands today — default install `ERP_TLS_ENABLED=false` on port 8080, prod on a bare IP behind Caddy's internal CA — a PWA is a responsive web page. No service worker (Chrome refuses registration on a cert-error origin; clicking through the interstitial does not count). No install, so on iOS no push and no eviction protection. No WebAuthn, because an IP address is not a valid RP ID. Every capability that distinguishes "an app" from "the website he can already open on his phone" is gated behind DNS + a real certificate on **every client install**. That work is genuinely owed (it is ADR-0061 follow-up #1 and I have budgeted it below) — but it is a per-client operations programme, not a sprint, and I will not put the owner's first usable version behind it. Meanwhile the honest half of this objection is free and I am taking it: **the existing Angular app in a mobile browser is the interim**, at zero new cost, while the Flutter app is built.

**Objection 3 — "There is no `ios/` directory in `pos_app`. If the owner carries an iPhone, your week-one delivery evaporates and this becomes a 4–6 week project before he sees anything."**

True, and it is the first question to put to him before a line is written. But it does not change the decision, because iOS is the long pole under *every* option: Apple Developer account under a legal entity, an APNs `.p8`, ATS configuration, a publicly-certificated demo server for App Review (Apple 2.1 rejects "reviewer cannot get past screen one"), a 5.1.1 account-deletion path. RN shortens none of that. The PWA's iOS path is *worse*, not better: a manual six-tap Add-to-Home-Screen that cannot be prompted for or reliably detected, and without which he gets no push at all. The mitigation is the same either way — **Android APK first**, which is also how the pilot should run regardless because it has no gatekeepers. If the answer is "he carries an iPhone," the plan does not change; the calendar does, and I say so up front instead of discovering it in week six.

---

## 4. WHAT WOULD CHANGE MY MIND

Concrete, checkable triggers. Any one of the first three flips the recommendation.

| # | Trigger | Flips to |
|---|---|---|
| **T1** | **A real hostname + Let's Encrypt certificate is committed and delivered for every client install *before* the client build starts** — e.g. the wildcard we own, `<client>.erp.otapp.net` → the client's IP, Caddy issues via HTTP-01. This single change unlocks service workers, WebAuthn biometrics, Web Push (no Firebase, no ownership question), and deletes the entire private-CA problem. | **PWA** — re-open and probably win it. Flutter's biggest structural advantage is that it works *despite* the certificate situation; fix the certificate and that advantage is worth nothing. |
| **T2** | **All executives on the list carry iPhones AND T1 is done.** iOS Flutter is greenfield + a Developer Program account + App Review; iOS Web Push (16.4+, installed to Home Screen) plus WebAuthn would then cover the requirement with no store at all. | **PWA.** |
| **T3** | **A second front-end developer is hired who writes React**, or the scope grows past ~10 screens with real data entry. The "one mobile toolchain" argument is a small-team argument; it dissolves the moment there are two front-end people. | **React Native** becomes defensible. |
| **T4** | **OrbixPOS is frozen or retired.** The shared-package case dies with it, and the choice re-opens on TypeScript-reuse grounds. | RN or PWA. |
| **T5** | **The `packages/orbix_erp_client` extraction causes any regression on the live till.** | **Stop sharing. Fork the core** — copy `pos_app/lib/core` into `mobile_exec/` and accept the duplication. A shared package over a working cash register is a convenience, never a commitment. |
| **T6** | **The owner rejects sideloading** ("if it's not in the store it isn't an app") and wants a public listing before the domain work lands. | Decision holds, but iOS moves onto the critical path and the calendar goes to 12+ weeks. Say so before starting, not after. |
| **T7** | **Push turns out to require a per-client Firebase project** (client refuses to have their notification titles transit our Google account). | Decision holds, but push slips out of v1 and the app must be honest that it is pull-only — which materially weakens the product and should be escalated to the owner as a decision, with an ADR. |

**One thing that would NOT change my mind:** a measurement showing the backend is ~60% of the total effort. It is — no cross-company brief (`ScopeGuard.java:675` pins every report to one `companyId`), no cross-company inbox (`ApprovalDecisionServiceImpl.java:282-289`), `/bi/dashboard` stamping a branch label on company-wide figures (`DashboardServiceImpl.java:189-191`), the missing `assertMayReadBranch` guard in three report queries, and no `GENERAL_MANAGER`/`OWNER` role bundle in `R__seed_permissions.sql`. All of that is identical under every client choice. It is an argument about sequencing, not about platform.

---

## 5. RECOMMENDED REPO LAYOUT + SHARED-PACKAGE PLAN

### 5.1 Layout

```
d:\My_Works\ERP\ERPCLEAN2\
├─ backend\                         (unchanged)
├─ web\                             (unchanged — and it is the interim mobile view)
├─ packages\
│  └─ orbix_erp_client\             NEW. publish_to: 'none'. Flutter-free where possible.
│     ├─ pubspec.yaml               dio, intl, uuid, collection  — NOT win32/ffi/state_notifier
│     ├─ lib\
│     │  ├─ orbix_erp_client.dart   single export barrel
│     │  └─ src\
│     │     ├─ api\  api_client.dart · api_response.dart · api_exception.dart · token_manager.dart
│     │     ├─ api\tls\  erp_tls.dart · trusted_ca.dart · trusted_ca_io.dart · trusted_ca_stub.dart
│     │     │            insecure_tls.dart · insecure_tls_io.dart · insecure_tls_stub.dart
│     │     ├─ core\  json.dart · money.dart · jwt.dart
│     │     ├─ auth\  auth_service.dart · models\auth.dart · step_up_service.dart · models\step_up.dart
│     │     ├─ context\  context_service.dart
│     │     └─ storage\  secure_store.dart      ← INTERFACE ONLY
│     └─ test\                      incl. the lifted Dio-wiring guard test
├─ pos_app\                         becomes a CONSUMER. Keeps: barcode.dart, step_up_policy.dart,
│                                   printer/win32 code, its SharedPreferences SecureStore impl,
│                                   erp_root_ca.dart (its own client's compiled-in root).
└─ mobile_exec\                     NEW app. Consumer. Keychain/Keystore SecureStore impl,
                                    runtime CA import, local_auth, firebase_messaging, hive_ce cache.
```

Each app declares:

```yaml
dependencies:
  orbix_erp_client:
    path: ../packages/orbix_erp_client
```

**No melos.** Two consumers, `publish_to: 'none'`, versions move in lockstep with the backend, and CI is already Maven + npm. `flutter pub get` resolves path dependencies natively. Revisit at four packages.

### 5.2 The extraction PR — rules, not suggestions

`pos_app` is live at `1.5.1+9` against a paying client running a cash register. The extraction is **its own PR, a pure move-and-rewire, zero behaviour change, `pos_app` green before a line of `mobile_exec` is written.**

1. **`SecureStore` goes into the package as an interface only.** `pos_app` keeps its `SharedPreferences` implementation — justified on the Windows till by the missing VS C++ ATL toolchain (`pos_app/README.md:120-122`) and documented there. `mobile_exec` supplies a Keychain / EncryptedSharedPreferences implementation with the ciphertext bound to a hardware key (`userAuthenticationRequired` + `biometryCurrentSet`). The seam is already correct: `TokenManager` touches the store through exactly three calls.
2. **Rename the `POS_`-prefixed dart-defines to neutral names, keeping the old names as aliases** — `POS_HOST`, `POS_ALLOW_INSECURE_TLS`, `POS_ERP_CA_FILE`, `POS_TLS_HOST`. Silently breaking the POS release scripts is a real risk and a stupid one to take.
3. **Three changes the exec app needs that touch POS code — each must default to today's POS behaviour:**
   - `branchUidOverride` stops being mutable client-wide state (`api_client.dart:37`) and becomes a per-request option; the POS passes its single branch on every call.
   - `_send` stops discarding the envelope `meta` (`api_client.dart:127`); add a `SKIP_UNWRAP` equivalent to match the web contract (`http.interceptors.ts`).
   - Timeouts become constructor parameters, defaulting to the POS's LAN-tuned 15 s / 30 s; `mobile_exec` passes longer values with explicit backoff.
4. **`bool.fromEnvironment('ALLOW_INSECURE_TLS')` must be unreachable in `mobile_exec` in every flavour**, with a CI assertion on the release job. ADR-0061 refused it for a till; a CFO's phone carrying consolidated P&L and approve rights is strictly worse.
5. **Lift `pos_app/test/trusted_ca_test.dart` into the package.** It greps every `lib/**/*.dart` for a `Dio(` construction that does not call `applyErpTls(` — its own comment records that this regressed silently once. After extraction it guards both apps.
6. **Add `.github/workflows/flutter-ci.yml`.** Today `.github/workflows/` contains only `backend-ci.yml` and `web-ci.yml`; `pos_app/test/` has 14 test files **no CI run has ever executed**, including that TLS guard. `flutter analyze` + `flutter test` across the package and both apps. The live POS is the first beneficiary of this decision.
7. **A real Android release keystore, from commit one.** `pos_app/android/app/build.gradle.kts:35-37` still signs release with the debug keystore (`// TODO: Add your own signing config`). Fix it for both apps in this PR.

### 5.3 Delivery sequence (client track; backend N1–N10 runs in parallel and leads by a week)

| Phase | Days | Outcome |
|---|---|---|
| **0 · Extract the package** | 3 | `pos_app` green, zero behaviour change, Flutter CI lane live, release keystore in place. |
| **1 · Skeleton on his phone** | 5 | Setup/pair with a *diagnosing* probe (ADR-0061 follow-up #3 — distinguish unreachable / untrusted / not-an-ERP), login, `/auth/me`, `/auth/my-branches`, **read-only approvals inbox**. `ApprovalRequestDto` is already card-shaped, so this needs **no backend change**. APK handed over, pointed at QA. |
| **2 · Decide + harden** | 5 | Approve/reject with confirm sheet, mandatory rejection reason, biometric gate, Keychain/Keystore store, freshness UI, neutral 409 handling, `Idempotency-Key` + `X-Request-Id`. **This is the version he uses daily.** |
| **3 · Brief + branches** | 10 | Per-company brief, `/bi/sales-by-branch` league table, cash & working capital, a11y + text-scale gates in CI. |
| **4 · Group brief + offline** | 10 | Wire N1/N2 when the backend lands; four-tier cache; ETag. |
| **5 · Push + kill switch** | 10 | V105 `user_devices` (owner approval), `PushSender`, FCM, `app_user.sessions_revoked_at`. |

**~5 working days to something in his hand. ~2 weeks to genuinely useful. ~8 weeks to a hardened v1 with push.**

### 5.4 Two things to fund in parallel, not defer

- **ADR-0061 follow-up #1 — a real hostname + Let's Encrypt for every install** (the `<client>.erp.otapp.net` wildcard we control is the cheapest form). It deletes the QR-CA-pairing feature from the plan, unlocks the App Store path, and is the trigger condition **T1** above. If it lands early, come back and re-argue PWA — I would rather lose this decision to a fixed certificate than win it and maintain a workaround.
- **ADR-0061 follow-up #2 — have `dist/install.sh` emit its Caddy root** into an operator-reachable folder. Today a client admin cannot obtain the file without `docker exec`. It is on the critical path for phase 1 and it is a one-hour change.

### 5.5 Two decisions that need the owner, before code

1. **iPhone or Android?** Ask before costing anything. Android-only keeps the eight-week number; iOS makes it twelve-plus and adds a Developer Program account and a publicly-certificated demo server.
2. **Whose Firebase project?** One project owned by us (every client's push titles transit our Google account, generic titles only, needs an ADR) or per-client (reintroduces per-client configuration and kills the one-binary property). This gates phase 5 and should be settled in week 1, not week 7.