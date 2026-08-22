## Authentication & Security

*(Scope: how an executive's phone proves who it is, to which server, and what it is allowed to see once it has. Report content is the reports workstream. **Platform note:** Flutter is the settled choice, but the directories this section refers to — `mobile_exec/` and the shared `packages/orbix_erp_client/` — **do not exist in the repo yet**. Every reference to them below is a target layout, not a shipped one; the only Flutter client on disk today is `pos_app/`.)*

---

## 1. The auth model in plain English

**Three separate questions, three separate answers.** The first is *"which server is this?"* — because every client runs their own OrbixERP box, there is no single address to build into the app. The phone is **paired** to one company's server once, at setup, by scanning a QR code an administrator generates. That pairing stores the server address and the server's certificate fingerprint, so the phone will refuse to talk to anything else pretending to be that server. The second question is *"who is this person?"* — answered by a username and password typed once, against that company's own server, exactly as on the web. The third is *"is this still the same person, on the same phone, right now?"* — answered by Face ID or a fingerprint each time the app opens.

**The phone never stores a password.** After the first sign-in the server hands back two things: a short-lived pass valid for 15 minutes, and a longer-lived renewal ticket valid for 7 days. The short pass lives only in memory and disappears when the app closes. The renewal ticket is locked inside the phone's hardware security chip, unlockable only by the owner's face or fingerprint — a thief with the handset cannot read it out, and it does not follow the executive's iCloud backup onto their iPad. Every time the app renews, the old ticket is destroyed and a new one issued, so a stolen copy of an old ticket is not just useless, it *raises the alarm*: presenting an already-used ticket tells the server the credential has been copied, and it terminates every session that user holds anywhere.

**Approving money is treated as a separate act from being signed in.** Being signed in gets an owner the numbers. Releasing a 42-million-shilling purchase order additionally requires a fresh face/fingerprint check at the moment of the decision, tied by the server to *that specific document and that specific amount* — so a phone left unlocked on a desk, or an approval screen left open in a pocket, cannot release anything. This is the one place we deliberately make the app slower than it could be. Two things this design does *not* claim: it does not protect an executive who is standing next to someone forcing them to unlock the phone, and it does not protect a phone whose operating system has been compromised. Both are handled by policy and detection, not by cryptography, and both are named honestly in the threat table at the end.

---

## 2. The shipped contract (what exists today, verbatim)

Everything in this subsection was read out of the repo and is already in production. The mobile app must conform to it; anything beyond it is marked **PROPOSED**.

### 2.1 Endpoints

| Endpoint | Gate | Body → Response |
|---|---|---|
| `POST /api/v1/auth/login` | `permitAll` — `SecurityConfig.java:45-47` | `LoginRequest{username,password}` → `TokenResponse` — `AuthController.java:36-40` |
| `POST /api/v1/auth/refresh` | `permitAll` | `RefreshRequest{refreshToken}` → full `TokenResponse` (new access **and** new refresh) — `AuthController.java:42-45` |
| `POST /api/v1/auth/logout` | `permitAll` (takes no bearer) | `RefreshRequest` → `204`; revokes **only the presented refresh token** (`AuthServiceImpl.java:150-153`) — the outstanding access token stays valid to its TTL — `AuthController.java:47-51` |
| `GET /api/v1/auth/me` | `isAuthenticated()` | → `MeResponse{uid,username,displayName,isRoot,activeCompanyUid,activeBranchUid,permissions[]}` — `AuthController.java:54-58` |
| `GET /api/v1/auth/my-branches` | `isAuthenticated()` | → `List<UserBranchDto>` — `AuthController.java:61-65` |
| `POST /api/v1/auth/verify-authority` | `isAuthenticated()` | supervisor override; **mints no tokens** — `StepUpController.java:41-46` |
| `GET /api/v1/health` | `permitAll` | liveness probe used by the pairing screen — `HealthController.java:20-26` |

`TokenResponse` is `{accessToken, accessTokenExpiresAt, refreshToken, user}` where `user` is `AuthUser{uid, username, displayName, isRoot, activeCompanyUid, activeBranchUid, hasBranch}`. **Login already returns the active company and branch uids** — the client does not need `/auth/me` for those, only for `permissions[]`.

Path prefix `/api/**` is `authenticated()` (`SecurityConfig.java:48`) and every handler in `com.erp.api` additionally carries a `@PreAuthorize` — enforced at build time by `backend/src/test/java/com/erp/architecture/EndpointAuthorizationTest.java`. **Correction to the usual shorthand: the gate expression is `@perm.has('CODE')` or `@perm.scoped(#uid,'targetType','CODE')`, not SpEL `hasPermission(...)`** — Spring has no 1-arg `hasPermission`, so ADR-0002 uses a `@perm` bean reference. The exemption list is **five** handlers, not four: `AuthController#login`, `#refresh`, `#logout`, `HealthController#health`, and `ApiNotFoundController#apiCatchAll` (`EndpointAuthorizationTest.java:26-34`).

### 2.2 Token facts the client must encode

- **Access JWT**: RS256, `access-token-ttl-minutes: 15` (`backend/src/main/resources/application.yml:109`). Claims, minted in `JwtService.issueAccessToken` (`JwtService.java:34-46`): `iss` = `props.issuer()` (`erp-api`, `application.yml:111`), `sub` = userId, `username`, `isRoot`, and `companyId`/`branchId` **as strings**, omitted when null. **Permissions are deliberately not in the token** (`JwtService.java:13-16`) — the client must call `/auth/me`.
- **`TokenResponse.accessTokenExpiresAt` is epoch SECONDS and arrives as a JSON string**, because `JacksonConfig.java:27-28` (`com/erp/platform/common/config/JacksonConfig.java`) registers `ToStringSerializer` for `Long.class` *and* `Long.TYPE`, and the field is a primitive `long`. The POS already parses this tolerantly (`pos_app/lib/models/auth.dart:52-55`, via `asIntOr`); the shared client package must carry that same coercion or the app crashes on first login.
- **Refresh token**: 32 random bytes, URL-safe base64 (`Tokens.java:22-26`), stored **SHA-256-hex-hashed** server-side (`Tokens.java:29-37`) — a database leak yields nothing usable. `refresh-token-ttl-days: 7` (`application.yml:110`), and the expiry is recomputed as `now + 7d` **on every issue** (`AuthServiceImpl.java:218`, inside `issueSession` at `:205-237`), so rotation is already an infinite sliding window.
- **Signing mode**: `signing-mode: ${ERP_JWT_SIGNING_MODE:dev-in-memory}` (`application.yml:106`). Dev rotates the key each restart, invalidating every access token. **Every box serving the mobile app must run `ERP_JWT_SIGNING_MODE=file`.** Release gate, not a preference.
- **Per-request revalidation**: `JwtRequestContextFilter` re-reads the user row on *every* authenticated request via `appUsers.findActiveScope(...)` and 401s if the account is no longer `ACTIVE` (`JwtRequestContextFilter.java:113-121`). The projection is `AppUserRepository.ActiveUserScope` — currently `{organisationId, root}` only (`AppUserRepository.java:166-180`). This is the hook the kill switch rides on (§6.2).
- **Branch scope is header-only.** `X-Branch-Uid` is resolved and validated in `resolvePrincipal` (`JwtRequestContextFilter.java:183-241`). Three checks run in order: the branch must be `isUsableForSession()` (`:201-203`); **the tenant check runs first and applies to root** (`:215-222`, ADR-0062 P3-1); then the assignment must be live — `revokedAt IS NULL AND active = true` (`:229-234`). **Note the guard is `if (!root && …)` at `:229` — a root caller skips the assignment check entirely** and is contained only by the tenant check above it.
- **`refresh` re-derives scope from the user's *default* branch** — `issueSession` reads `findByUserIdAndIsDefaultTrue` (`AuthServiceImpl.java:209`), so the client must re-apply its branch header after every refresh (the POS does this client-side: `pos_app/lib/core/api/api_client.dart:37,58-60`).

### 2.3 Login failure paths (all must render as friendly text — [[error-message-hygiene]])

Unknown user → dummy-bcrypt for constant time, then generic 401 (`AuthServiceImpl.java:87-95`). Inactive account → generic 401 (`:97-100`). Locked → `"Account is locked. Try again later or contact an administrator."` (`:101-104`). Suspended organisation → `"This account is not available at the moment. Please contact your administrator."` (`assertTenantIsOpen`, `:254-274`; root exempt at `:264-266`, and a user with a null `organisationId` is let through at `:255-257`). Lockout is 5 failures / 15 min (`application.yml:113-115`), persisted in a `REQUIRES_NEW` transaction so it survives the thrown exception (`LoginAttemptService.java:52-72`).

### 2.4 One live inconsistency to fix before the app ships

`AuthServiceImpl.myBranches()` filters only on `ub.getBranch().getStatus() == ACTIVE` (`AuthServiceImpl.java:198-201`), but the switch check in the filter also requires `revokedAt IS NULL AND active = true` (`JwtRequestContextFilter.java:229-234`). **A revoked or deactivated assignment is therefore listed in the branch switcher and 403s on selection** — which reads to a GM as a broken app. `[code-only]`, one predicate. (Mitigating fact recorded in the filter's own comment at `:224-228`: zero revoked or inactive rows exist on either estate today, so nobody is currently hitting it.)

---

## 3. Enrolment — pairing a phone to *their* company's server

### 3.1 Why this is hard here

Four facts from the estate, not from theory:

- Production terminates TLS on a **self-signed internal CA on a bare IP/EC2 name** — `infra/prod/Caddyfile:27` is `tls internal`, and the header at `:8-13` says so explicitly.
- **A default `dist/` client install is plain HTTP** — `dist/bundle/.env.example:63,76,78`: `ERP_HTTP_PORT=8080`, `ERP_TLS_ENABLED=false`, `ERP_PUBLIC_HOST=` (empty).
- **Every install mints its own root**, which we cannot know at build time (`pos_app/lib/core/api/erp_root_ca.dart:19-20`, and ADR-0061's context section). One root *is* compiled in — `kProdCaddyRootCa`, our own prod box, valid to 2036-04-29 — and that one entry would work unchanged on a phone. It is useless for a *client's* install, which is the whole audience here.
- **The POS's three operator-supplied CA sources are all desktop-only and inert on a phone**: `POS_ERP_CA_FILE` reads `Platform.environment` (`trusted_ca_io.dart:83`), and `erp-ca.pem` / `certs/` resolve relative to `Platform.resolvedExecutable` (`trusted_ca_io.dart:89-92`, `:117-125`) — inside a signed, read-only app bundle on iOS/Android.

So trust must become **runtime data, not build data**. One binary, per-install import.

### 3.2 Track A — the answer that deletes the problem (fund in parallel)

A real hostname + Let's Encrypt for every install (ADR-0061 follow-up #1, verbatim: *"Real domain + Let's Encrypt on prod … Two-line change to `infra/prod/Caddyfile` once DNS points at the box"*). Cheapest form: a wildcard we control, `<client>.erp.otapp.net` → the client's IP; Caddy issues via HTTP-01 once the `tls internal` line is deleted (`Caddyfile:11-13` documents exactly this). The phone then validates against built-in public roots, and the pinned-root path is never consulted — `trusted_ca_io.dart:47-50` keeps `SecurityContext(withTrustedRoots: true)` specifically so this migration is a no-op on the client. **This also unlocks iOS ATS, the App Store path, and WebAuthn (an IP address is not a valid RP ID).** Until it lands, Track B ships.

### 3.3 Track B — QR pairing, trust-on-first-pair with a human-verified fingerprint

**Prerequisite (one hour, on the critical path):** ADR-0061 follow-up #2 — *"Have the `dist/` installer emit its Caddy root into a tills-facing folder, so a client install is POS-connectable without a manual `docker exec`."* Not done today.

**Step by step** (everything below the shipped `/health` probe is **PROPOSED**):

1. **Admin, on the web app** → Administration → *Pair a mobile device*. Selects the executive's user account, clicks *Generate pairing code*.
2. **Server** (**PROPOSED** `POST /api/v1/devices/enrolment-codes`, gated `DEVICE.ENROL` — **not seeded, see §8**) mints a one-time code, 10-minute TTL, bound to `{organisationId, userId}`, and returns it together with the public host and the DER of the box's own Caddy root, read from the mounted CA path.
3. **Web renders a QR** containing exactly (**PROPOSED** shape):
   ```json
   {"v":1,"host":"https://erp.acme.co.tz","caPem":"<base64 DER>",
    "caSha256":"AB:CD:…","org":"acme","code":"7F3K-9QX2","exp":1755500000}
   ```
   and prints `caSha256` beside it in large type.
4. **Executive scans it.** The app shows the host and the fingerprint **in large type and asks the admin to read the server-side value aloud against it**. This is the whole security of TOFU — a fingerprint nobody compares is decoration. Cancel is the default action; Confirm is deliberate.
5. **The app probes `GET /api/v1/health`** (`HealthController.java:20-26`, public, shipped) over TLS **with the pinned root already installed**, *before* showing a login screen. The probe must diagnose four distinct outcomes, not one: (a) host unreachable, (b) reached but certificate not trusted, (c) reached something that is not an OrbixERP, (d) an OrbixERP of an incompatible version. This is ADR-0061 follow-up #3, and it is disqualifying for an App Store submission to leave it as the POS's bare `catch (_)` (`pos_app/lib/features/auth/setup_screen.dart:89`).
6. **Trust is persisted**: PEM written to the app's documents directory (`path_provider`), fingerprint stored alongside and re-verified on every launch so a tampered preferences file cannot silently repoint the app. `SecurityContext(withTrustedRoots: true)` plus the enrolled root — never *instead of* the public roots.
7. **Login screen** appears, pre-filled with the username the code was bound to. The executive types their password.
8. **On successful login the app registers the device**: **PROPOSED** `POST /api/v1/devices` with `{enrolmentCode, deviceLabel, platform, osVersion, appVersion, publicKeyDer?}`. The server consumes the code (single-use), creates the `user_devices` row (§6.3, **[schema]**), and returns a `deviceUid`. From then on the app sends `X-Device-Id: <deviceUid>` on every request.

**Two engineering rules on the extraction:** `_sharedContext()` in `trusted_ca_io.dart:23-24,40-42` caches behind a `_contextBuilt` flag and is built once at startup — it must become **rebuildable without an app restart**, because pairing happens after launch. And `bool.fromEnvironment('POS_ALLOW_INSECURE_TLS')` (`pos_app/lib/core/api/insecure_tls.dart:18-19`) **must be unreachable in the executive app in every flavour**, with a CI assertion on the release job. ADR-0061 refused it for a cash till ("it must not reach a till"); a phone carrying consolidated P&L and approve rights is strictly worse.

---

## 4. Sign-in layers — what each one does and does not protect

Five layers. Each is honest about its blind spot; stacking them is the design.

| Layer | What it actually is | Protects against | Does **NOT** protect against |
|---|---|---|---|
| **L1 · Password → server JWT** | The only thing the *server* ever verifies about identity. `POST /auth/login`, bcrypt cost 12 (`SecurityConfig.java:76-79`), 5-attempt lockout (`application.yml:113-115`). | Someone who has the phone but not the credential; a stolen phone with no cached session. | Password reuse and phishing (no MFA by default); **credential stuffing across accounts** — per-account lockout does nothing against one password sprayed across 500 usernames, and there is **no rate limiting anywhere in the backend** today except that lockout and the step-up throttle. Fix in §7. |
| **L2 · Biometric unlock of the stored refresh token** | A **cryptographic** gate, not a UI gate. The refresh token is encrypted under a hardware key created with `setUserAuthenticationRequired(true)` + StrongBox (Android) / `kSecAccessControlBiometryCurrentSet` + `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (iOS). | An opportunist with a momentarily-unlocked phone — the common case. A thief with a locked phone, offline: the token is unextractable. iCloud/backup exfiltration (`ThisDeviceOnly` is mandatory). | **The server. It never sees this and cannot verify it.** It is not an authentication factor to the ERP. Useless against malware on a rooted/jailbroken OS, which can prompt-and-harvest. **Weaker than a PIN under coercion** — a face can be pointed at a phone. A biometric prompt that merely hides a Flutter route while the token sits readable in preferences is theatre; `adb backup` walks straight past it. |
| **L3 · Device PIN fallback** | **Preferred:** let the OS do it — Android `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG \| AUTH_DEVICE_CREDENTIAL)`, iOS `.userPresence`. The device passcode then unlocks the same hardware key, with the OS's own rate limiting and wipe policy, and there is no app-level PIN store to attack. | Biometric failure (wet hands, mask, sensor). Enrolment-change lockout. | A 6-digit app-local PIN protects nothing on its own — 10⁶ is seconds offline. **If a separate app PIN is a business requirement** (shared company handset), derive with Argon2id (64 MB, t=3) against a Keystore-held salt, count failures locally, and **wipe the token store after 5**. Never the only thing between the file and the token. A PIN is a *quick resume gate*; it is never sufficient for an approval. |
| **L4 · TOTP MFA (optional, RFC 6238)** | Six digits from a Secure-Enclave-held secret, generated in-app so there is no second app to install. **PROPOSED**, needs **[schema S3]**. Nothing in the backend implements TOTP today — grep for it returns nothing. | Stolen password alone. Phishing of a static credential. Works **offline**, which matters when poor connectivity is the stated problem. No telco dependency on a self-hosted box, no per-message cost, immune to SIM swap. | Real-time relay phishing. A compromised device. **Where it is required: device enrolment (always) and high-value step-up (always). Where it is NOT required: ordinary daily login on an already-enrolled device** — an executive asked for a code twice a day disables it, and a disabled control protects nothing. |
| **L4-bis · SMS OTP** | **Rejected as an authentication factor.** | — | SIM-swap fraud is an active vector in Tanzania; delivery across Vodacom/Airtel/Tigo/Halotel is best-effort; it needs an aggregator contract and TCRA sender-ID registration; and it makes a **self-hosted, sometimes-air-gapped** server depend on an outbound internet path it may not have. SMS may later be a *notification* channel — never a credential. |
| **L5 · Step-up re-auth before a high-value approval** | A fresh password-or-biometric-or-TOTP check at the moment of decision, exchanged for a short-lived server ticket **bound to the document and the amount**. **PROPOSED**, `[code-only]` in v1. | A phone handed over or left unlocked with the approvals screen open. Replay of an approval intent against a *different, larger* document. Establishes that a live session never *implies* approval authority. | Coercion at the moment of decision. A fully compromised OS. |

### 4.1 Why `verify-authority` cannot serve L5

`POST /auth/verify-authority` (`StepUpController.java:41-46`) is a **supervisor-override-at-someone-else's-terminal** primitive. Its own class comment says it must never mint or revoke session tokens (`StepUpController.java:13-25`), and `AuthorityVerificationDto`'s javadoc states it *"carries NO token material of any kind"*. Decisively: `StepUpAuthServiceImpl.java:175-190` refuses self-approval **unconditionally** — *"NOBODY MAY APPROVE THEMSELVES"*, `authoriser.getId().equals(caller.userId())` → `refuse(…, "SELF_APPROVAL", …)`. On an executive's own phone the approver *is* the authenticated caller, so it returns `authorised:false / SELF_APPROVAL` every single time. Relaxing that rule would gut the till control it exists for (the comment names the exact case: a cashier holding `POS.SESSION.RECONCILE` could approve their own reconcile). It is the wrong question: `verify-authority` asks *"is someone else allowed?"*; the exec app needs *"is this still you, right now, on this device?"*

### 4.2 L5 spec — **PROPOSED** (no such endpoint exists today)

**v1 (no schema, ~2 days).** `POST /api/v1/auth/reauth` — **PROPOSED**, `isAuthenticated()`, body `{password}` or `{totp}`, verified against the **caller's own** account, plus `{resourceUid, amount, currency}`. Returns `{ticket, expiresAt}`: opaque, single-use, **3-minute TTL**, held in an in-memory map with the same lifetime discipline as `StepUpAuthServiceImpl`'s throttle map (`ConcurrentHashMap<Long, Throttle>`, `StepUpAuthServiceImpl.java:105`, with an opportunistic prune at `:88`) — in-memory *by design: no schema change*. The ticket is bound to `userId + deviceId + resourceUid + amount`.

`ApprovalRequestController.approve` / `reject` (`ApprovalRequestController.java:64-77`) then require an `X-Approval-Ticket` header **only when the session carries an `X-Device-Id`** — so the web client and the POS are untouched, no parity break, no migration. Binding to resource *and* amount is what stops a ticket minted for a TZS 40,000 stationery PO being replayed against a TZS 400,000,000 payment.

**v2 (the one worth building) — device-bound signing keys, WebAuthn semantics without a browser.** At enrolment the app generates a P-256 keypair in Secure Enclave / StrongBox with `userAuthenticationRequired`; the public key is registered in `user_devices.public_key_der` (**[schema S4]** = the same `user_devices` table push needs, §6.3). To approve, the server issues a nonce; the app signs `{nonce, approvalUid, amount, currency, timestamp}`; the server verifies against the registered key. **This is the only design in which the biometric acquires server-side meaning** — the OS will not produce that signature without user presence — and it yields a non-repudiable, per-document, per-device artefact. Cheapest home for the signature is `audit_log` detail JSON (`[code-only]`; the aspect already writes append-only audit per CLAUDE.md invariant 7); a column on `approval_request` is **[schema S7]**.

**Whatever the mechanism: the biometric alone must never be the only thing between "open app" and "approve a payment". A device-local gesture is not evidence to a server.**

### 4.3 A landmine the client must not step on

`POST /approvals/requests/uid/{uid}/approve` (`ApprovalRequestController.java:64-69`) and `…/reject` (`:72-77`) **both call the identical `decisionService.decide(uid, request)` and carry the identical `@PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')")`.** The verb comes solely from `DecideRequest.action` (a `@NotNull DecisionAction`; the record is just `{action, comment}`). A client that hardcodes the path per button and lets `action` desync **silently inverts the decision**. Assert `action` matches the path in the client, and add a server-side guard as part of the step-up work.

---

## 5. Token lifetimes, sliding refresh, idle timeout, reuse detection

### 5.1 The server ceiling vs. the client policy

Server facts: access 15 min, refresh 7 days **absolute from each issue**, re-stamped on every rotation (`AuthServiceImpl.java:218`). Consequence, stated plainly for the owner: **an executive who opens the app twice a day never re-authenticates, ever, and a stolen-and-unlocked phone is a permanent session.** That is too generous for a bearer credential in a pocket. **The client tightens what the server permits — none of the table below needs a backend change.**

| Control | Policy | Rationale |
|---|---|---|
| Access token | **Memory only, never persisted.** 15 minutes makes persistence pointless. | Nothing to steal from disk. |
| Refresh token | Encrypted under the biometric-bound hardware key; ciphertext via `flutter_secure_storage` with `AndroidOptions(encryptedSharedPreferences: true)` and `IOSOptions(accessibility: first_unlock_this_device)`. | §5.3. |
| App unlock | Biometric / device-credential on cold start **and after 2 minutes in background**. | The realistic threat is a handed-over or briefly-unattended phone. |
| Silent refresh | Allowed while the refresh token lives; **single-flight only**, and session cleared **only on 401/403, never on a transport error**. | Both rules already exist in `pos_app/lib/core/api/token_manager.dart:66-68` and `:79-87`. Anything else trips the chain revoke in §5.2 and logs the user out of the web app *and the tills*. |
| Client max idle | **12 hours** with no successful API call → discard the refresh token locally, require a password login. | Caps the 7-day server ceiling to something defensible for a phone. |
| Forced full re-auth | **Every 14 days**, plus immediately on biometric-enrolment change, OS restore to a new device, or a changed stored device id. | Bounds the "permanent session" property. `biometryCurrentSet` / Android key invalidation on enrolment change is the control that matters most here: **adding a coerced finger or a second face destroys the key**, forcing a password login. Turn it on. |
| Approval | Always a fresh step-up ticket (§4.2, PROPOSED), regardless of session age. | A live session must never *imply* approval authority. |
| Branch header | Re-applied after every refresh. | `refresh` re-derives scope from the **default** branch (`AuthServiceImpl.java:209`). |

### 5.2 Refresh-token reuse detection — exact behaviour, and the mobile landmine

From `AuthServiceImpl.refresh()` (`:118-147`):

| Presented token | Server behaviour |
|---|---|
| Hash not found | 401 `"Invalid refresh token."` (`:121-122`) |
| `isConsumed()` — i.e. `rotatedAt != null` **or** `revokedAt != null` (`RefreshToken.java:83-85`) | **`loginAttempts.revokeAllTokens(userId, now)` (`:128`) in a `REQUIRES_NEW` transaction that commits despite the exception (`LoginAttemptService.java:43-46`)**, then 401 `"Refresh token already used. Please sign in again."` |
| Expired but unconsumed | 401 `"Refresh token expired. Please sign in again."` (`:131-133`) — **no chain revoke** |
| User no longer active | Generic 401 (`:135-139`) |

**Read the second row carefully: `revokeAllForUser` kills every session that user holds, on every client — phone, web browser, and every POS till they are signed into.** That is correct anti-theft policy and a serious reliability hazard for a phone. Two ordinary mobile events trigger it: two concurrent refreshes (app + a background handler), and an app killed after the server rotated but before the client persisted the successor.

**Client mitigations (mandatory, no backend change):**
- Single-flight refresh (`token_manager.dart:66-68`), one isolate only. **Never run a refresh from a background push handler.**
- Persist the new pair **atomically before** using the new access token.
- Clear the session only on 401/403, never on a transport error (`token_manager.dart:79-87` — note the doc comment at `:64-65` overstates this as "on failure the session is cleared"; the implementation is the correct one).
- On `"Refresh token already used"`, present a silent re-login prompt, **not** an error toast — the user cannot act on that message and will call support. Also treat it as a possible theft signal: wipe the offline cache hard (the offline workstream's T1/T2 tiers).

**Server mitigation — PROPOSED, and it should be an ADR because it is a security-policy change.** *Rotation grace:* when a presented token was rotated **< 30 s ago** *and* the request carries the **same `X-Device-Id`** as that token's `device_info`, return a plain 401 **without** revoking the chain; otherwise keep today's full revoke. This turns "the CFO's flaky 3G logged everyone out of the tills" into one silent re-login, while preserving cross-device theft detection. `[code-only]`, and it depends on §6.1 stamping `device_info`.

### 5.3 Token storage — do **not** copy the POS

`pos_app/lib/core/storage/secure_store.dart:28-45` writes the access token, expiry, refresh token and user profile into `SharedPreferences` — a plaintext XML/plist file. The file says so itself at `:14-16`: *"HARDENING (production): swap this implementation for an OS keystore (DPAPI / Keychain / Android Keystore) behind the same interface once the platform's secure-storage toolchain is provisioned on the till image."*

**The reason the POS dropped `flutter_secure_storage` is Windows-specific and void here** — `pos_app/README.md:121-122` records it as the missing Visual Studio C++ ATL component on the desktop till image. On iOS and Android the plugin is a thin wrapper over Keychain and Keystore/EncryptedSharedPreferences with no such dependency. A CFO's 7-day refresh token in plaintext preferences (Android `shared_prefs/*.xml`; iOS `NSUserDefaults` plist, which lands in unencrypted device backups) would be the single worst thing in the product.

The interface (`saveSession`/`readSession`/`clear`) is already the correct seam — **keep the interface in the shared client package, swap the implementation per app.** POS keeps SharedPreferences on Windows with its documented justification; the executive app supplies the Keychain/Keystore implementation. Additionally: `allowBackup="false"` + `dataExtractionRules` on Android so the token store never leaves in an `adb backup` or a device transfer; server host + pinned CA in ordinary preferences is fine (ADR-0061: *"A root certificate is a public key. There is nothing secret here"*) but the fingerprint is stored alongside and verified every launch.

---

## 6. Device management

**Today there is none.** `RefreshTokenRepository.revokeAllForUser` (`RefreshTokenRepository.java:19`) exists but is reachable from exactly one call site — `LoginAttemptService.java:45`, the token-reuse path (verified by repo-wide grep). No session list, no per-device revoke, no admin "sign this user out everywhere", and **no self-service password change** — `UserController.java:96-98` gates `PUT /users/uid/{uid}/password` on `USER.MANAGE`, and there is no `/auth/change-password` anywhere in `com.erp.api`, so a CFO whose phone was stolen cannot rotate their own password.

**The good news: most of the state already exists as dead code.** `refresh_tokens` carries `device_info`, `user_agent`, `ip_address`, `last_used_at` (`backend/src/main/resources/db/migration/V1__baseline.sql:208-211`, each already commented `-- P2: …`; entity `RefreshToken.java:53,57,61,65`), and a repo-wide grep for `setDeviceInfo|setUserAgent|setIpAddress|setLastUsedAt` finds **only the four setter declarations themselves (`RefreshToken.java:125,133,141,149`) — nothing writes them.** Device labelling, last-seen and per-device revoke are therefore `[code-only]`.

### 6.1 Registry and self-service — `[code-only]`

1. **Stamp the dead columns.** `AuthServiceImpl.issueSession` (`:205-237`) and `refresh` (`:118-147`) write `device_info` (from `X-Device-Id` + a human label), `user_agent`, `ip_address`; `refresh` stamps `last_used_at`. Needs `HttpServletRequest` threaded into `refresh` — `AuthController.java:42-45` does not take one today, while `login` at `:36-40` already does and passes `getRemoteAddr()`.
2. **Accept `X-Device-Id`** on login and refresh. Client-generated ULID, survives token rotation, no schema.
3. **PROPOSED** `GET /api/v1/auth/sessions` (self) — device label, platform, IP, last-seen, "this device".
4. **PROPOSED** `DELETE /api/v1/auth/sessions/{uid}` (self) and `POST /api/v1/auth/sessions/revoke-all` (self) — the latter reuses `RefreshTokenRepository.revokeAllForUser` directly.
5. **PROPOSED** `POST /api/v1/auth/change-password` (self, current password required). Reuses `AppUser.changePassword` (`AppUser.java:172`), which `UserServiceImpl.java:303` already calls on the admin path. The first thing an executive should be able to do after losing a phone; today impossible without an administrator.
6. **PROPOSED** admin `POST /api/v1/users/uid/{uid}/sessions/revoke-all` — trivial code; the *permission code* is the catch (§8).

> **Caveat on (3)/(4):** `RefreshToken` is deliberately **not** a `UidEntity` — its javadoc at `:13-14` states it is looked up by `tokenHash`, *"never addressed externally by uid"*. Returning an addressable per-session identifier either breaks the id/uid rule (CLAUDE.md invariant 3) or needs **[schema S1]** `refresh_tokens.uid VARCHAR(26)`. **Recommendation: skip S1.** Key the session list on `user_devices.uid` (S4 below), which we need anyway and which is the more useful unit — a person thinks in phones, not in tokens.

### 6.2 The lost-phone kill switch — and the 15-minute hole

Revoking a refresh token **does not kill the outstanding access token**: a thief holding an unlocked app keeps working for up to 15 minutes after the kill switch is pulled. The same is true of `POST /auth/logout`, which revokes only the presented refresh token.

**The clean fix is nearly free.** `JwtRequestContextFilter.java:113-121` **already** performs a per-request primary-key read of the user row on every single request, via the `AppUserRepository.ActiveUserScope` projection (`AppUserRepository.java:166-180`). Add one nullable column, add one field to that projection's JPQL (`@Query` at `:178-180`), and compare it to `jwt.getIssuedAt()` inside the same read — access tokens then die on the **next** request, at **zero extra query cost**. The precedent is already in the file: `getRoot()` was added to this exact projection for the same reason (`AppUserRepository.java:168-175`, ADR-0062 P3-13 — *"Demoting a compromised root has to take effect on the next request, not when their access token happens to expire"*).

**[schema S2]** — recommend approving this one first:
```sql
-- V105 (next free version; latest applied is V104__multitenancy_constrain.sql — verified)
ALTER TABLE app_users ADD COLUMN sessions_revoked_at TIMESTAMPTZ NULL;
```
Additive, nullable, no backfill, no constraint on a populated table — safe under the frozen/additive-only rule. Still requires explicit owner approval under [[migration-approval-required]] and a **boot test against a restored customer copy** per the [[trigger-self-match-outage-2026-08-15]] rule.

*No-migration fallback:* an in-memory revoked-user set in the filter. Works today (single node) but does not survive a restart or a second instance. Not good enough for a kill switch.

### 6.3 Device registry — **[schema S4]**, shared with push

No `DeviceController` and no `user_devices` table exist today (verified). This is the same table the push workstream needs. One migration, two features. **V105/V106** depending on ordering with S2 — batch them into one approved migration.

```sql
CREATE TABLE user_devices (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid             VARCHAR(26)  NOT NULL,
    organisation_id BIGINT       NOT NULL,          -- org-as-tenant, ADR-0062 D-9
    user_id         BIGINT       NOT NULL,
    platform        VARCHAR(10)  NOT NULL,          -- ANDROID | IOS
    label           VARCHAR(60),                    -- "Bakari's Samsung A54" — a revoke list must be readable
    os_version      VARCHAR(30),
    app_version     VARCHAR(20),
    push_token      VARCHAR(255),                   -- FCM registration token (APNs via FCM)
    public_key_der  BYTEA,                          -- device-bound approval signatures (§4.2 v2)
    locale          VARCHAR(10),
    enrolled_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    enrolled_by     BIGINT,
    last_seen_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoked_by      BIGINT,
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_device_uid       UNIQUE (uid),
    CONSTRAINT uq_user_device_push_tok  UNIQUE (push_token),
    CONSTRAINT fk_user_device_user      FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT chk_user_device_platform CHECK (platform IN ('ANDROID','IOS')),
    CONSTRAINT chk_user_device_status   CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_user_devices_user ON user_devices (user_id, status);
```

`UNIQUE (push_token)` handles a token migrating between users on a shared or refurbished handset. `status` follows the soft-delete convention (CLAUDE.md invariant 9). `organisation_id` per ADR-0062 D-9 (`docs/decisions/0062-organisation-as-tenant-multitenancy.md:190`, *"`organisation_id` on aggregate roots"*). `version` matches the `@Version` optimistic-locking convention. Note the baseline uses `GENERATED BY DEFAULT AS IDENTITY` on `refresh_tokens`; match whichever form the surrounding migrations use at authoring time.

**PROPOSED endpoints** (flat `DeviceController` under `com.erp.api`, one per resource, per the controller convention; every handler needs a `@PreAuthorize` or `EndpointAuthorizationTest` fails the build):

| Endpoint | Gate | Purpose |
|---|---|---|
| `POST /api/v1/devices` | `isAuthenticated()` + a valid enrolment code | Register/upsert this device. Idempotent on push token. |
| `GET /api/v1/devices` | `isAuthenticated()` (self only) | The executive's own device list. |
| `DELETE /api/v1/devices/uid/{uid}` | `isAuthenticated()` (self) | Sign out that phone. Revokes its refresh tokens **and** stamps `sessions_revoked_at` when it is the last one. Must also be called on logout, **before** clearing the session. |
| `POST /api/v1/devices/enrolment-codes` | `DEVICE.ENROL` **(NOT SEEDED — §8)** | Admin mints a pairing code. |
| `GET /api/v1/users/uid/{uid}/devices` | `USER.MANAGE` | **Admin view of active devices** — label, platform, IP, last-seen. |
| `POST /api/v1/users/uid/{uid}/devices/revoke-all` | `USER.MANAGE` | Admin kill switch: revoke every device + set `app_users.sessions_revoked_at = now()`. |

**[schema S5]** `device_enrolment_code` — only if pairing codes must survive an API restart. In-memory is fine for v1 (10-minute TTL, and a restart just means re-scanning). **Keep it `[code-only]`.**

**[schema S3]** TOTP state — as a `user_mfa` table (`totp_secret_enc`, `totp_enabled_at`, `totp_last_step` for intra-window replay, `totp_recovery_codes_hash`), **not** columns on `app_users`, so the hot per-request projection `findActiveScope` used at `JwtRequestContextFilter.java:113-121` stays narrow (it is two fields today). Only needed when L4 ships.

---

## 7. Hardening

**Screenshots — be honest with the owner about the asymmetry.** Android: `FLAG_SECURE` on the activity is one line and blocks screenshots *and* blanks the app-switcher thumbnail. **iOS has no equivalent — you cannot prevent a screenshot.** All you can do is (a) blur on `applicationWillResignActive`, which covers the multitasking snapshot — the thing that actually persists to disk and into backups — and (b) observe `userDidTakeScreenshotNotification` / `capturedDidChangeNotification` and log the fact *after*. Note `FLAG_SECURE` also blocks legitimate screen sharing, which is exactly what an executive does on a Teams call with their accountant. **Make it per-screen or a company setting, not a global switch**: on for P&L, bank balances, payroll; arguably off for the approvals inbox list.

**Root / jailbreak — a signal, not a gate.** Any Dart-level check is defeated by Magisk Zygisk. So: detect (freeRASP or equivalent) and on a positive → (a) refuse to persist the refresh token at all, forcing a password login every launch; (b) refuse to register a device-bound key; (c) emit an audit event. **Do not hard-block** — a false positive on a custom ROM locks the GM out at 22:00 and the support call lands on us. Play Integrity / App Attest are the real answers but both need a server round trip to Google/Apple, which a self-hosted box in Tanzania cannot be assumed to have. Note and defer.

**Push payloads.** Send only `{typeKey, notificationUid, sourceUid, linkRoute, title, body}` — **never amounts, customer names, supplier names or branch financials.** A push payload transits Google/Apple servers and renders on a lock screen. `link_route` (`V21__notifications.sql:64`, surfaced as `NotificationDto.linkRoute:19`) is already the deep-link target; the app fetches the real content over TLS after the tap. Keep titles generic enough that the one-Firebase-project decision does not leak client business data.

**Clipboard.** `enableInteractiveSelection: false` on money and account-number fields; clear the clipboard on background; set `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+ so the OS clipboard-preview toast does not display a copied balance to the room. On password/TOTP fields: `obscureText: true`, `autocorrect: false`, `enableSuggestions: false`, `TextInputType.visiblePassword` — otherwise the keyboard learns them.

**Certificate trust.** `badCertificateCallback` is `(cert, host, port) => false`, never a passthrough. Android ships a `network_security_config.xml` with `cleartextTrafficPermitted="false"` so a downgrade to `http://` is impossible even by configuration — Dart's `HttpClient` does not read the OS trust store (ADR-0061 rejected the Windows-cert-store option for exactly this reason), so both layers are needed for defence in depth. All Dio construction goes through `applyErpTls` (`pos_app/lib/core/api/erp_tls.dart:15-18`), and **lift the guard test at `pos_app/test/trusted_ca_test.dart:66-98` into the shared package** — it `listSync`-walks the source tree for a `Dio(` construction in a file that never calls `applyErpTls(` (`:76`, `:87-90`), and its own comment at `:68-71` records that this *"regressed once already during development, silently"*. **One more, easy to miss: with a private CA there is no Certificate Transparency and no CAA to fall back on, so the pinned root is the *only* transport integrity control** — that raises the cost of getting §3 wrong from "degraded" to "total".

**Rate limiting — a live gap, verified.** A repo-wide grep for throttle/rate-limit/bucket4j across `backend/src/main/java` returns **exactly one file**: `StepUpAuthServiceImpl` (an in-memory `ConcurrentHashMap<Long, Throttle>` at `:105`, counted against the *caller*). `DiscountAuthorisationGuard` only *mentions* being rate-limited in prose (`:53`) — it carries no throttle of its own; it inherits step-up's. `POST /auth/login` is `permitAll` (`SecurityConfig.java:45-47`) and a mobile app pushes it onto the internet. **Do both:** an in-memory per-IP + per-username filter mirroring the step-up `Throttle` record `[code-only]`, **and** Caddy's `rate_limit` on `/api/v1/auth/*` in `infra/prod/Caddyfile` `[config-only]`.

**Logs and crash reporting.** Server-side, `errors[]` in the `ApiResponse` envelope carries **user-safe strings only** (CLAUDE.md invariant 2, gated by `MessageHygieneTest`) — technical text goes to logs, where `JwtRequestContextFilter.java:136-141` already stamps `userId`/`username`/`companyId`/`branchId`/`organisationId` into MDC. Client-side: **no crash reporter that uploads by default.** If one ships, it is opt-in per install, scrubbed of every money field, entity uid, username, branch name and host, with breadcrumbs whitelisted rather than blacklisted, and it is disclosed to the client — because it is, alongside FCM, one of only two outbound paths from an otherwise self-hosted product. Never log tokens, `X-Device-Id`, or the response body of `/auth/*` or any report endpoint. Audit stays server-side, where the aspect already writes it and `audit_log` is append-only (CLAUDE.md invariant 7).

---

## 8. RBAC on mobile — the `EXECUTIVE_MOBILE` bundle

### 8.1 The trap this section exists to avoid

`MeResponse.permissions` is **empty for root by design** — `AuthServiceImpl.java:167-171`: `user.isRoot() ? List.of() : …`, with the comment *"Root bypasses scoping, so it carries no enumerated set — the client keys off isRoot"*. **Building and testing the app as root therefore masks every single RBAC gap below.** Combined with [[phantom-permission-codes]] — gating on a never-seeded code silently breaks all non-root users and is invisible to CI — the rule is absolute: **every mobile screen is smoke-tested as a non-root `FINANCE_DIRECTOR` and a non-root `BRANCH_MANAGER` before it ships.**

### 8.2 The audience has no role bundle at all

`R__seed_permissions.sql:310-321` seeds exactly **12** system role bundles: SALESPERSON, CASHIER, FIELD_SALES_AGENT, STOREKEEPER, ACCOUNTANT, SALES_MANAGER, BRANCH_MANAGER, PROCUREMENT_OFFICER, PROCUREMENT_MANAGER, HR_PAYROLL_MANAGER, FINANCE_DIRECTOR, PRODUCTION_MANAGER. **There is no GENERAL_MANAGER, CEO or OWNER.**

There *is* a thirteenth role, `ORG_ADMIN` — the row is created in `V1__baseline.sql` and this file fills its grants by **`CROSS JOIN` over the whole permissions table except `module = 'platform'`** (`R__seed_permissions.sql:277-294`). Two consequences worth putting in front of the owner: it is the only bundle that automatically absorbs a new permission code (`:299-306` states this explicitly — *"A NEW permission code does NOT auto-flow into any bundle (only ORG_ADMIN absorbs it)"*), and it therefore holds every write code in the product. **`ORG_ADMIN` is the wrong default for a read-and-approve phone** — it is the "everything" role, not the executive role. That is the case for a purpose-built bundle rather than reusing it.

Verified gaps in the two bundles that *do* map to the audience (grepped `(role,perm)` pairs against `R__seed_permissions.sql`):

| Role | Missing codes | Consequence on the phone |
|---|---|---|
| `FINANCE_DIRECTOR` | `SALES.INVOICE.VIEW`, `INVENTORY.VALUATION.VIEW`, **`STOCK.VIEW`**, `POS.SESSION.VIEW`, **`POS.EXPENSE.VIEW`**, `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW`, `CRM.PIPELINE.VIEW` | The CFO **cannot open the Sales Report or any stock report** — they hold no stock code at all. They *do* hold `BI.OPS.VIEW`, so the BI stock tile renders but every drill-down 403s. Reads as a broken app. |
| `BRANCH_MANAGER` | `GL.VIEW`, `AR.STATEMENT.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW`, `BUDGETING.REPORT.VIEW`, `VAT.VIEW`, `FA.VIEW`, `COSTING.VIEW`, `CRM.PIPELINE.VIEW`, `BI.CRM.VIEW`, `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW` | Cannot open the trial balance; **cannot open AR ageing at all** — all three `/api/v1/ar/statement`, `/ar/ageing` and `/ar/ageing/by-customer` are gated `AR.STATEMENT.VIEW` (`ArStatementController.java:40,56,72`) and BRANCH_MANAGER holds only `AR.VIEW`, which reaches `/ar/balance` (`:85`) and nothing else; no cash flow, no budget variance. |

### 8.3 Proposed `EXECUTIVE_MOBILE` bundle

Seeded exactly as the existing 12 are — a `roles` row plus `(role_code, perm_code)` grants in `R__seed_permissions.sql`. **It is a repeatable seed, so it is not a new `V<n>`** — but it is a seed change and therefore needs **explicit owner approval** under [[migration-approval-required]]. Note the file's own `ON CONFLICT` caveat at `:322-325`: V102 replaced the global `uq_role_code` with two partial indexes, so the insert must use the same inferrable predicate the existing block uses — copy it, do not invent one.

Every code below was grepped against the seed. ✅ = the permission code is defined in the catalogue.

| Group | Codes | Defined in the seed? |
|---|---|---|
| Approvals | `APPROVALS.DECIDE`, `APPROVALS.REQUEST.VIEW`, `APPROVALS.POLICY.VIEW` | ✅ all three |
| Dashboards | `BI.VIEW`, `BI.FINANCE.VIEW`, `BI.OPS.VIEW`, `BI.CRM.VIEW` | ✅ |
| Statements | `REPORT.PL.VIEW`, `REPORT.BS.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.EXPORT` | ✅ |
| Ledger / GL | `GL.VIEW` *(optional: `REPORT.LEDGER.VIEW`, `COSTING.VIEW`)* | ✅ |
| Working capital | `AR.VIEW`, `AR.STATEMENT.VIEW`, `AP.VIEW`, `CASH.VIEW` | ✅ |
| Operations | `SALES.INVOICE.VIEW`, `INVENTORY.VALUATION.VIEW`, `STOCK.VIEW`, `POS.SESSION.VIEW`, `POS.EXPENSE.VIEW` | ✅ |
| Specialist (optional per client) | `BUDGETING.REPORT.VIEW`, `VAT.VIEW`, `WHT.VIEW`, `FA.VIEW`, `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW`, `CRM.PIPELINE.VIEW` | ✅ — but note **`PROJECTS.COSTING.VIEW` is defined and granted to no bundle at all** (1 occurrence in the file: its definition). Same for `BI.EXPORT`. Granting them here is a first grant, not a copy. |
| Baseline chrome | `NOTIFICATION.VIEW`, `NOTIFICATION.PREFERENCE.MANAGE`, `BRANCH.VIEW`, `DOCUMENT.VIEW`, `DOCUMENT.RENDER` | ✅ (each already in 12 bundles) |
| **Mobile-specific — NOT SEEDED** | **`EXEC.BRIEF.VIEW`** (the consolidated group brief, reports workstream N1) | ❌ **0 hits anywhere in the repo — PHANTOM until added** |
| **Device management — NOT SEEDED** | **`DEVICE.ENROL`**, **`DEVICE.REVOKE`** | ❌ **0 hits each — PHANTOM until added** |

**Explicitly excluded from the bundle:** `BI.EXPORT`, `USER.MANAGE`, `ROLE.MANAGE`, and every `*.POST` / `*.MANAGE` write code. This is a read-and-approve audience; the only write surfaces are `APPROVALS.DECIDE`, device self-management, and self password change. Note also the grant-ceiling control from [[rbac-privilege-escalation-2026-07-09]] — `AuthorityCeiling` (`backend/src/main/java/com/erp/platform/security/AuthorityCeiling.java`, ADR-0059, *amended 2026-08-14 by ADR-0062*) — an `EXECUTIVE_MOBILE` holder must not be able to self-elevate. Two seed-integrity tests will also see this bundle: `DefaultRoleBundlesSeededTest` and `RolePermissionClosureTest` (ADR-0047); run both.

**Alternative worth considering for `DEVICE.*`: fold both into `USER.MANAGE` and add no new codes at all.** Fewer codes is fewer phantom risks, and "who may pair a phone to an account" is the same authority as "who may set that account's password" (which `USER.MANAGE` already is). Owner call; the more codes we invent, the more the [[route-guard-endpoint-permission-parity]] trap has to bite.

### 8.4 The bundle alone does not fill the inbox

**Approval routing is by role code frozen onto each policy step** — `ApprovalPolicyStepDto{id, uid, sequence, approverRoleCode}`. Creating `EXECUTIVE_MOBILE` and assigning it changes nothing until a policy step *names* it. Two consequences to state to the owner before the app ships:

1. **A root account with no role grants gets an empty inbox by construction.** `ApprovalDecisionServiceImpl.inbox` (`:279-304`) calls `approverResolver.resolveRoleCodes(userId, companyId)` at `:284` and returns `Page.empty(pageable)` at `:285-287` when that list is empty — **before any root bypass**. **The owner's account must hold a real role that appears in policy steps.**
2. Either the existing policies are edited to add an `EXECUTIVE_MOBILE` (or `FINANCE_DIRECTOR`) final step, or the executive is granted the role code the policies already name. **This is a data/configuration decision per client, not code.**

Two more parity rules for whoever builds this: `EndpointAuthorizationTest` fails the build on any new `com.erp.api` handler without a `@PreAuthorize`, and the Flutter client's own permission gates must name **exactly** the code the server checks — a client gate naming a code the server does not check (or vice versa) surfaces to the executive as "the app is broken", which is precisely the trap documented at length in `pos_app/lib/core/config/step_up_policy.dart:25-46` (*"a parity trap that reads to everyone at the till as a broken password"*).

---

## 9. Threat model

| # | Threat | Likelihood | Mitigation | Residual risk |
|---|---|---|---|---|
| T1 | **Opportunist picks up a briefly-unlocked phone** | **High** — the realistic everyday case | Biometric/device-credential on cold start and after 2 min background; step-up re-auth bound to document+amount before any approval (§4.2, PROPOSED) | They can read cached figures if they act inside the 2-minute window. Accepted. |
| T2 | **Handset stolen while locked, attacker offline** | Medium | Refresh token encrypted under a hardware key (StrongBox / Secure Enclave), `ThisDeviceOnly`; access token never persisted; `allowBackup=false` | None material — the token is unextractable without the OS being broken. |
| T3 | **Executive loses phone, notices hours later** | Medium | Self-service `DELETE /devices/uid/{uid}` + `revoke-all` from another device (§6.1, PROPOSED); admin revoke via `USER.MANAGE`; `app_users.sessions_revoked_at` **[schema S2]** kills the outstanding access token on the very next request | **Without S2, a 15-minute window** in which a live access token still works after revocation — and `POST /auth/logout` has the same hole today. This is why S2 is the first migration to approve. |
| T4 | **Refresh token replayed by a thief (or by our own flaky network)** | Medium | Reuse detection revokes the entire chain and forces a fresh password login (`AuthServiceImpl.java:124-130`) | **Availability, not confidentiality:** a false positive signs the user out of the web app and every POS till (`revokeAllForUser` is per-user, not per-device). Mitigated client-side (single-flight, atomic persist, never clear on transport error) and server-side by the PROPOSED 30-second same-device rotation grace (§5.2). |
| T5 | **Password phished or reused from a breach** | Medium-High | Per-account lockout (5/15 min, `application.yml:113-115`); **PROPOSED** per-IP + per-username rate limiting at both the app and Caddy (§7); optional TOTP at enrolment and step-up (**PROPOSED**, nothing implemented today) | No MFA on daily login by design — an executive asked for a code twice a day disables it. Accepted, and revisited if a real incident occurs. |
| T6 | **Credential-stuffing / password spray against the public login endpoint** | Medium — rises the day an APK exists | **Currently unmitigated: verified by grep, the only throttle in `backend/src/main/java` is `StepUpAuthServiceImpl:105`.** Fix is `[code-only]` + `[config-only]` and should ship with phase 1. | Until fixed, one password across 500 usernames locks nobody out. **Open gap — flag to the owner.** |
| T7 | **Network attacker impersonates the ERP server (private CA, no CT, no CAA)** | Low-Medium | Pinned root installed at pairing + human-verified fingerprint; `withTrustedRoots: true` so a future Let's Encrypt migration is a no-op; `badCertificateCallback => false`; no insecure-TLS flag in any flavour; Android cleartext blocked by config | TOFU: the very first pairing is trust-on-first-use. Mitigated only by the fingerprint being read aloud — **if that step is skipped in practice, the control is gone.** Track A (real certificates) removes the risk entirely. Also inherited from ADR-0061: if the `erp-prod-caddy-data` volume is ever lost, Caddy mints a new root and every paired phone stops connecting until re-paired. |
| T8 | **Malware on a rooted/jailbroken handset** | Low | Detect and degrade — refuse to persist the token, refuse to register a device key, audit the event; never hard-block (false positives lock out the GM at night) | **Real and unmitigable in-app.** A compromised OS can prompt-and-harvest. Detection is advisory. Accepted and documented. |
| T9 | **Coercion — someone forces the executive to unlock and approve** | Low | Step-up bound to document + amount makes each approval a distinct deliberate act; `biometryCurrentSet` destroys the key if a coerced finger/face is enrolled | **Biometric is weaker than a knowledge factor under coercion.** No technical fix. Policy answer: multi-step approval chains for the largest bands, so one coerced person cannot release alone. |
| T10 | **Screenshot of a consolidated P&L leaves the device** (backup, chat, cloud gallery) | Medium | Android `FLAG_SECURE` per sensitive screen; iOS blur on resign-active + post-hoc screenshot logging | **iOS cannot prevent screenshots.** Detection only. State this to the owner rather than implying parity. |
| T11 | **Sensitive data leaks via push notification on a lock screen** | Medium | Payload carries only `{typeKey, uid, linkRoute, generic title}` — `link_route` already exists (`V21__notifications.sql:64`); content fetched over TLS after the tap; generic titles by policy | Push metadata (that an approval exists, and when) transits Google/Apple. Unavoidable with FCM; disclose it. |
| T12 | **A shared/handed-down handset shows figures to the wrong person** | Low-Medium | Offline cache keyed on a **permission fingerprint** (hashed sorted permission set from `MeResponse.permissions`) so a user switch cannot serve another person's cached panels; wipe cache on logout; `UNIQUE (push_token)` re-points a device row instead of fanning out to the wrong user | Requires the cache-key discipline to be implemented correctly — **and note the fingerprint is empty for root** (§8.1), so root sessions must bypass the cache entirely rather than share a null key. Cover with a test. |
| T13 | **An executive sees data from a company or branch they are not entitled to** | Medium | `ScopeGuard.assertCanActIn` per company (`ScopeGuard.java:708`); `X-Branch-Uid` verified against a live `user_branch` row (`JwtRequestContextFilter.java:229-234`), behind a tenant check that applies even to root (`:215-222`) | **Two holes, both verified.** (a) The branch-assignment check is skipped for root (`if (!root && …)`, `:229`). (b) Outside auth: only `ProductStockReportQuery` carries a per-branch read guard (private `assertMayReadBranch`, `:390`, called `:118`); its siblings `StockReportQuery.report` (`:67-73`) and `StockMovementReportQuery` (`:128-152`) resolve `branchUid` **company-scoped only** — `assertCanActIn(companyId)` plus `resolveNamedRef(..., companyId, ...)`, with no assignment check. A third sibling was reported in the reports workstream and is **(UNVERIFIED)** here. **A phone app invites a branch picker as primary navigation, which makes this materially worse** — lift that guard into a shared helper *before* any branch picker ships. |
| T14 | **Root/superuser testing masks every RBAC gap** | **High — it has happened in this repo before** | Mandatory non-root smoke test as `FINANCE_DIRECTOR` and `BRANCH_MANAGER` for every screen; seed `EXECUTIVE_MOBILE` and close the §8.2 holes; never gate on an unseeded code | Human process. Make it a release checklist item, not a convention. |
| T15 | **Dev JWT signing key in production** — a restart invalidates every access token | Low | `ERP_JWT_SIGNING_MODE=file` verified on every box serving the app; the default is `dev-in-memory` (`application.yml:106`) and `application.yml:103-105` calls it a gating item | Recoverable (the client refreshes silently), but it is a release gate. Check it, do not assume it. |

---

## 10. Backend work summary

| # | Item | Kind | Phase |
|---|---|---|---|
| 1 | Stamp `device_info` / `user_agent` / `ip_address` / `last_used_at`; thread `HttpServletRequest` into `refresh` (`AuthController.java:42-45` takes none today) | `[code-only]` | 2 |
| 2 | Accept `X-Device-Id` on login + refresh | `[code-only]` | 1 |
| 3 | Fix `myBranches` to filter `revokedAt IS NULL AND active = true` (§2.4) | `[code-only]` | 1 |
| 4 | Rate-limit `/auth/login` — in-app filter **and** Caddy `rate_limit` | `[code-only]` + `[config-only]` | 1 |
| 5 | **PROPOSED** `GET /auth/bootstrap` (or additive `MeResponse` fields: `organisationUid`, `activeCompanyName`, `activeBranchName`, accessible companies). **Honest sizing:** login already returns `activeCompanyUid`/`activeBranchUid`/`hasBranch` inside `TokenResponse.user`, so today's cold start is **three to four** calls (`login` → `/auth/me` for permissions → `/auth/my-branches` → `/branches?companyUid` for a numeric branch id), not five. Collapsing to one still pays on a 3G handset. | `[code-only]`, additive | 1 |
| 6 | **PROPOSED** numeric `branchId` + `companyId` on `UserBranchDto` — it carries `Long id` today, but that is the *assignment* id, not the branch's. Documented as a backend follow-up in `pos_app/README.md:117-120` | `[code-only]`, additive | 1 |
| 7 | **PROPOSED** `POST /auth/change-password` (self); reuses `AppUser.changePassword` (`AppUser.java:172`) | `[code-only]` | 2 |
| 8 | **PROPOSED** `POST /auth/reauth` + `X-Approval-Ticket` enforcement on approve/reject, device-sessions only | `[code-only]` | 2 |
| 9 | Guard that `DecideRequest.action` matches the invoked path (§4.3) | `[code-only]` | 2 |
| 10 | **PROPOSED** rotation grace (30 s, same device) before chain revoke — **needs an ADR** | `[code-only]` | 3 |
| 11 | `EXECUTIVE_MOBILE` bundle + close the FINANCE_DIRECTOR / BRANCH_MANAGER holes + seed `EXEC.BRIEF.VIEW`, `DEVICE.ENROL`, `DEVICE.REVOKE` | `R__seed_permissions.sql` — repeatable, **still needs owner approval**; re-run `DefaultRoleBundlesSeededTest` + `RolePermissionClosureTest` | 3 |
| 12 | **S2** `app_users.sessions_revoked_at TIMESTAMPTZ NULL` + one field on the `ActiveUserScope` projection (`AppUserRepository.java:178-180`) | **[schema]** — approve first | 4 |
| 13 | **S4** `user_devices` table + `DeviceController` | **[schema]** — batch with S2 as one V105 | 5 |
| 14 | **S3** `user_mfa` (TOTP) | **[schema]** — only when L4 ships | 6+ |
| 15 | **S1** `refresh_tokens.uid` | **[schema]** — **recommend skipping**; key sessions on `user_devices.uid` | — |

**Two decisions needed from the owner before code:** (a) is `DEVICE.ENROL` / `DEVICE.REVOKE` worth two new permission codes, or do we fold both into `USER.MANAGE`? (b) does TOTP ship in v1, or only device-bound biometrics? Both are cheap now and expensive to retrofit.