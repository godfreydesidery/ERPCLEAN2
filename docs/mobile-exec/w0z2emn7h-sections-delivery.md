## Architecture, Roadmap & Delivery

### 0. What is already decided coming into this section

Flutter, second app in this monorepo, Android-first, shared Dart package extracted from `pos_app/lib/core`. This section turns that into a directory tree, an itemised backend plan with real migration numbers, a phased calendar, a test strategy, a distribution channel, a risk register, and the decisions the owner must sign before anyone writes code.

Four verified facts anchor everything below:

- **The next free Flyway version is `V105`.** 104 versioned migrations exist; the latest applied is `backend/src/main/resources/db/migration/V104__multitenancy_constrain.sql`. (CLAUDE.md says "78 Flyway migrations (latest V78)" and "47 ADRs"; the directories hold 104 and 64. Trust the directory, not the doc.)
- **There is no Flutter lane in CI.** `.github/workflows/` contains exactly `backend-ci.yml` (jobs `fast-check`, `integration-test`, `migration-hygiene`) and `web-ci.yml` (job `build-and-test`). The 14 test files in `pos_app/test/` — including the TLS-wiring guard at `pos_app/test/trusted_ca_test.dart` — **have never been executed by any CI run.** The live POS is therefore the first beneficiary of this project, before the exec app renders a single number.
- **`ModuleBoundaryTest` does not enforce the cross-module rule CLAUDE.md attributes to it.** The shipped file has exactly four rules: controllers↛repositories, `@RestController` placement, services↛controllers, audit-repo isolation. The "modules talk only via `domain.dto`/`domain.enums`" invariant is a *convention*, documented in the class javadoc as "(Added per-module)" and never implemented. `bi.service` demonstrably injects `TrialBalanceQuery`, `ArReconciliationQuery`, `ApReconciliationQuery`, `StockValuationQuery` and `SalesByBranchQuery` from four other modules. **ADR-0037 D-3 (`docs/decisions/0037-bi-analytics-dashboards.md:110-115`) states this accurately and is the governing precedent** for the new mobile composition service: injecting another module's `@Service`/`@Component` query bean and consuming its DTO is allowed; importing another module's `..domain.entity..` or `..repository..` is not.
- **`TenantScopingRulesTest` is a *frozen* ArchUnit rule** banning `findById` / `getReferenceById` on a Spring Data `Repository` from any `..service..` class, with a ~200-call baseline in `src/test/resources/archunit_freeze`. Every new service written for this app must use company-scoped finders. **Do not regenerate the freeze store locally** — the `archunit-freeze-local-vs-CI` trap says local regeneration breaks CI. (`findAllById` is *not* banned; `DashboardServiceImpl.buildSalesByBranch` says so in a comment and relies on it.)

---

### 1. Stack, repo layout, shared package, state, API-client contract

#### 1.1 Stack

| Layer | Choice | Grounding |
|---|---|---|
| Client framework | Flutter, Dart SDK `^3.11.5` | `pos_app/pubspec.yaml:22` — one toolchain across both apps |
| HTTP | `dio ^5.9.2` | `pos_app/pubspec.yaml:37` |
| State | `flutter_riverpod ^3.3.2` — `AsyncNotifier`/`Notifier` only | `pos_app/pubspec.yaml:38`. **Drop `state_notifier ^1.0.0` (`:45`)** — legacy alongside Riverpod 3 |
| Local cache | `hive_ce` + `hive_ce_flutter` | `pos_app/pubspec.yaml:40-41`, already proven by `pos_app/lib/state/catalog_cache.dart` |
| Token store | `flutter_secure_storage` (**new**, exec app only) | replaces `pos_app/lib/core/storage/secure_store.dart`, whose own header at `:14-16` flags the OS-keystore swap as production hardening |
| Biometrics | `local_auth` (**new**) | — |
| Formatting | `intl ^0.20.2` + `gen_l10n` from day one | `pos_app/pubspec.yaml:42` |
| Ids | `uuid ^4.5.3` | `pos_app/pubspec.yaml:43` — powers `X-Request-Id` and `Idempotency-Key` |
| **Not carried over** | `win32 ^6.3.0`, `ffi ^2.2.0`, `shared_preferences ^2.5.5` | `pos_app/pubspec.yaml:39, 46-47` — Windows receipt printer and the POS token store |

#### 1.2 Repo layout

```
d:\My_Works\ERP\ERPCLEAN2\
├─ backend\                                  (unchanged; new module below)
│  └─ src\main\java\com\erp\
│     ├─ api\MobileExecController.java        NEW — flat under com.erp.api per invariant 1
│     ├─ api\DeviceController.java            NEW (phase 5)
│     └─ modules\mobile\                      NEW module — composition-only, ADR-0037 D-3 shape:
│        │                                    injects other modules' query BEANS + DTOs,
│        │                                    never their entities or repositories
│        ├─ domain\dto\   ExecBriefDto, GroupTotalsDto, CompanyLineDto,
│        │                BranchLineDto, HealthBoardDto, StatementSummaryDto,
│        │                TopNDto, StockSummaryDto, ReceivablesSummaryDto   (all NEW)
│        └─ service\      MobileBriefService / MobileBriefServiceImpl
│
├─ web\                                       (unchanged — and it IS the interim mobile view)
│
├─ packages\
│  └─ orbix_erp_client\                       NEW. publish_to: 'none'
│     ├─ pubspec.yaml                         dio, intl, uuid, collection ONLY
│     ├─ lib\
│     │  ├─ orbix_erp_client.dart             single export barrel
│     │  └─ src\
│     │     ├─ api\   api_client.dart · api_response.dart · api_exception.dart
│     │     │         token_manager.dart · request_options.dart
│     │     ├─ api\tls\  erp_tls.dart · trusted_ca.dart · trusted_ca_io.dart
│     │     │            trusted_ca_stub.dart · insecure_tls.dart
│     │     │            insecure_tls_io.dart · insecure_tls_stub.dart
│     │     ├─ core\  json.dart · money.dart · jwt.dart
│     │     ├─ auth\  auth_service.dart · models\auth.dart
│     │     │         step_up_service.dart · models\step_up.dart
│     │     ├─ context\ context_service.dart
│     │     └─ storage\ secure_store.dart     ← NEW ABSTRACT INTERFACE (see §1.3.1)
│     └─ test\   api_exception_test.dart · trusted_ca_test.dart (lifted)
│                json_test.dart · money_test.dart
│
├─ pos_app\                                   becomes a CONSUMER
│     KEEPS: barcode.dart, step_up_policy.dart, erp_root_ca.dart (its client's root),
│            printer/win32 code, and its SharedPreferences SecureStore impl
│
├─ mobile_exec\                               NEW app
│  ├─ pubspec.yaml   orbix_erp_client (path) + flutter_secure_storage, local_auth,
│  │                 hive_ce, firebase_messaging (phase 5)
│  ├─ android\       real release keystore, network_security_config.xml,
│  │                 allowBackup=false, dataExtractionRules
│  ├─ ios\           DEFERRED — see Open Decision #1
│  ├─ lib\
│  │  ├─ main.dart
│  │  ├─ app\            router · theme (all text styles here; lint bans literal fontSize:)
│  │  ├─ core\           secure_store_keystore.dart · ca_import.dart · freshness.dart
│  │  ├─ features\
│  │  │  ├─ pairing\     setup + DIAGNOSING probe (ADR-0061 follow-up #3)
│  │  │  ├─ brief\       S1 Morning Brief
│  │  │  ├─ branches\    S2 league table
│  │  │  ├─ approvals\   S3 inbox · S4 detail + decide sheet
│  │  │  └─ cash\        S5 cash & working capital
│  │  └─ l10n\           app_en.arb (supportedLocales: [en])
│  └─ test\   *_a11y_test.dart · *_scale_test.dart · *_semantics_test.dart
│             live_exec_smoke_test.dart   ← non-root by default (see §4.3)
│
└─ .github\workflows\
   ├─ backend-ci.yml   (existing: fast-check, integration-test, migration-hygiene)
   ├─ web-ci.yml       (existing: build-and-test)
   └─ flutter-ci.yml   NEW — analyze + test across packages/, pos_app/, mobile_exec/
```

**No melos.** Two consumers, `publish_to: 'none'` on both, versions move in lockstep with the backend, and CI is already Maven + npm. `flutter pub get` resolves path dependencies natively. Revisit at four packages.

#### 1.3 The extraction PR — rules, not suggestions

`pos_app` is live at `1.5.1+9` (`pos_app/pubspec.yaml:19`) against a paying client running a cash register. **The extraction is its own PR, a pure move-and-rewire, zero behaviour change, `pos_app` green before a line of `mobile_exec` is written.**

1. **`SecureStore` must be *turned into* an interface — it is not one today.** `pos_app/lib/core/storage/secure_store.dart` is a **concrete class** with a hard `package:shared_preferences` import, and `TokenManager` takes it by that concrete type (`token_manager.dart:14`). The *seam* is correct — the manager touches the store through exactly three calls (`readSession` `:39`, `saveSession` `:44`, `clear` `:49`) — so the change is mechanical: define `abstract class SecureStore` in the package, move the SharedPreferences implementation to `pos_app` (justified on the Windows till by the missing VS C++ ATL toolchain, `pos_app/README.md:121-122`), and give `mobile_exec` a Keychain / EncryptedSharedPreferences implementation with the ciphertext bound to a hardware key.
2. **Rename `POS_`-prefixed dart-defines to neutral names, keeping the old names as aliases:** `POS_HOST` (`pos_app/lib/core/config/app_config.dart:37-38`), `POS_ALLOW_INSECURE_TLS` (`insecure_tls.dart:18-19`), `POS_ERP_CA_FILE` (`trusted_ca_io.dart:12`), `POS_TLS_HOST` (read at `trusted_ca_test.dart:103`, documented at `:15-17`). Silently breaking the POS release scripts is a real and stupid risk.
3. **Three API-client changes the exec app needs — each defaults to today's POS behaviour** (detailed in §1.5).
4. **`bool.fromEnvironment('ALLOW_INSECURE_TLS')` must be unreachable in `mobile_exec` in every flavour**, asserted by the release CI job. ADR-0061 refused it for a till; a CFO's phone carrying consolidated P&L and approve rights is strictly worse.
5. **Lift `pos_app/test/trusted_ca_test.dart` into the package.** Its `wiring` group (`:66-98`) greps every `lib/**/*.dart` for a `Dio(` construction whose file does not also contain `applyErpTls(`; its own comment at `:70-71` records that this regressed silently once. After extraction it guards both apps.
6. **Add `flutter-ci.yml` in the same PR.** `flutter analyze` + `flutter test` across `packages/orbix_erp_client`, `pos_app`, `mobile_exec`.
7. **A real Android release keystore, from commit one.** `pos_app/android/app/build.gradle.kts:33-38` still signs release with the debug keystore (`// TODO: Add your own signing config for the release build.`). Fix for both apps here.

#### 1.4 State management

Riverpod 3, `Notifier`/`AsyncNotifier` only, no `StateNotifier`, no code generation. Five providers and one hard rule:

| Provider | Kind | Holds |
|---|---|---|
| `sessionProvider` | `AsyncNotifier<Session?>` | token pair + `Me` (`/auth/me`) + accessible companies/branches |
| `scopeProvider` | `Notifier<Scope>` | `{organisationUid, companyUid?, branchUid?}` — the picker's state, **not** a mutable field on `ApiClient` |
| `briefProvider(scope, businessDate)` | `AsyncNotifier<Cached<ExecBrief>>` | family-keyed, so a branch switch is a new provider, not a mutation |
| `inboxProvider(scope)` | `AsyncNotifier<Cached<List<ApprovalCard>>>` | |
| `decideProvider(requestUid)` | `AsyncNotifier<DecideState>` | one-shot, disposed on sheet close |

**The rule:** every provider that renders a figure returns a `Cached<T>` — `{value, serverGeneratedAt, fetchedAt, source: live|cache}` — never a bare `T`. Freshness is in the type, so a screen that forgets to render provenance fails to compile against the shared card widget.

Cache key includes the **permission fingerprint** (sorted permission-code set from `/auth/me`, hashed). `DashboardServiceImpl:185-195` nulls panels by permission; a cache keyed only on company would show finance figures to the next user of a shared office tablet. **Note the root special case:** `/auth/me` returns an **empty** `permissions` array for a root user (`AuthServiceImpl.java:167-171`), so the fingerprint must key off `isRoot` explicitly rather than treating "no permissions" as a value.

#### 1.5 API-client contract

The four headers already exist and work. `pos_app/lib/core/api/api_client.dart:44-62`:

```dart
options.headers['Authorization'] = 'Bearer $token';        // :48
final reqId = (options.extra['xRequestId'] as String?) ?? _uuid.v4();
options.extra['xRequestId'] = reqId;                        // :51-52  durable across retries
options.headers['X-Request-Id'] = reqId;                    // :53
final idem = options.extra['idempotencyKey'] as String?;
if (idem != null) options.headers['Idempotency-Key'] = idem; // :55-56
if (branchUidOverride != null && options.extra['noBranch'] != true) {
  options.headers['X-Branch-Uid'] = branchUidOverride;       // :58-60
}
```

**Contract for the shared package (six changes, all defaulting to today's POS behaviour):**

| Concern | Today | Shared-package contract |
|---|---|---|
| **Envelope unwrap** | `_send` returns `unwrapData(res.data)` and **discards `meta`** (`api_client.dart:127`) | Add a `skipUnwrap: true` request option returning the full `ApiResponse<T>`, mirroring the web's `SKIP_UNWRAP` http-context token (`web/src/app/core/api/http.interceptors.ts:51-57`). Required because paged list endpoints put paging in envelope `meta` — `GET /api/v1/approvals/requests/inbox` returns `ApiResponse<List<ApprovalRequestDto>>` + `PageMeta` explicitly (`ApprovalRequestController.java:41-44`). Default `false` = today's behaviour. |
| **id-as-string vs count-as-number** | `pos_app/lib/core/json.dart` coerces `Long`-as-JSON-string and `BigDecimal`-as-JSON-number | Lift verbatim. **Every id field in every exec DTO is typed `String` in Dart; every money field is parsed via `Money` keeping the server's exact string in `raw`, never `int`, never `double`, never client arithmetic.** Two documented exceptions to "Longs are strings": `PageMeta.totalElements/page/size/totalPages` are declared `int` and serialise as **JSON numbers** (`PageMeta.java` javadoc says so explicitly), and `TokenResponse.accessTokenExpiresAt` is a `long` epoch-**seconds** that *does* serialise as a **string** via `JacksonConfig.java:26-28`. `pos_app/lib/models/auth.dart:50-58` parses the latter tolerantly via `asIntOr` — keep that, and use the same tolerant coercion for `PageMeta` so a future type change cannot crash the list. |
| **`X-Branch-Uid`** | mutable field on the client (`api_client.dart:37`) — one active branch app-wide | Becomes a **per-request option**: `api.get(path, branchUid: scope.branchUid)`. The exec app is cross-branch and often wants consolidated; a mutable global is a correctness hazard when two providers refresh concurrently. POS passes its single branch on every call and behaves identically. **Also:** `issueSession` re-derives scope from the user's default branch on every refresh (`AuthServiceImpl.java:205-215`), so the app must re-apply `X-Branch-Uid` after every refresh — with a per-request option this is automatic rather than a remembered step. |
| **`Idempotency-Key`** | opt-in via `extra['idempotencyKey']`, generated by `newTxnId()` (`api_client.dart:143`) | Rename to `newIdempotencyKey()`. **Generated once per human decision, persisted with the pending decision, and re-sent unchanged on every retry.** Note the server does **not** honour it on approvals today — see backend item **B7**; until B7 lands the header is inert and the client's safety comes from the ambiguous-POST read-back path, not from the header. |
| **Verbs** | `get` / `post` / `delete` only (`api_client.java:93, 99, 120`) | Add `put` in the package (needed if any exec write ever targets an existing `PUT` endpoint — e.g. `PUT /users/uid/{uid}/password`). B14 is specified as a **POST**, so the MVP does not need it; add it once, in the extraction, rather than later under pressure. |
| **Timeouts** | 15 s connect / 30 s receive, LAN-tuned (`api_client.dart:21-22`) | Constructor parameters. POS keeps its defaults; `mobile_exec` uses 20 s / 60 s with explicit backoff for 3G. |
| **401 handling** | refresh once, replay `RequestOptions` via `_dio.fetch(opts)` (`:71-86`) | Verbatim. Combined with `token_manager.dart:66-68` single-flight and `:79-87` "**only** a 401/403 clears the session" — that second rule is what keeps a flaky 3G link from tripping the reuse-detection chain revoke at `AuthServiceImpl.java:124-130`, which kills the user's web session **and their tills**. |
| **Error parsing** | `_toApiError` accepts a bare `String` because the envelope emits `errors` as `List<String>` (`api_exception.dart:158-172`) | Verbatim. This is the fix for the `pos-generic-400-masks-real-error` trap. Strip the POS-specific `kPosSaleStatusHeader` (`:5`, `:143-145`) and cashier-facing wording (`:89-99`, `:186-215`). **Keep `isAmbiguousWrite` (`:68`)** — approve/reject is exactly the write where an ambiguous outcome must reconcile, never blind-retry. |

---

### 2. Backend work plan

Every item is marked `[code-only]` or `[schema]`. **All `[schema]` items need explicit owner approval before authoring** (standing rule `migration-approval-required`), must be authored **against a populated database** (expand → backfill → constrain, never single-shot `NOT NULL`/`UNIQUE`/`FK`), and must be **boot-tested against a restored customer copy** before shipping — the rule written in blood after the V103 trigger self-match outage crash-looped the live client on 1.8.0.

Migration versions below are **proposed reservations in expected landing order**; the actual number is assigned at authoring time because `scripts/check-migrations.sh` rule 1 rejects duplicate versions and rule 2 rejects editing an applied one (CI job `migration-hygiene`, `backend-ci.yml:88-97`).

Three shipped ArchUnit/seed gates cover every new endpoint the moment it is written, with no Docker: `EndpointAuthorizationTest` (every `com.erp.api` handler must carry `@PreAuthorize`), `PermissionCodesSeededTest` (every code named in a `@PreAuthorize` must exist in `R__seed_permissions.sql`), and `DefaultRoleBundlesSeededTest` (the 12 bundles).

#### 2.1 Phase-1 backend (approvals) — all `[code-only]`

| # | Item | Detail |
|---|---|---|
| **B1** | **Fix `/auth/my-branches` vs branch-switch inconsistency** `[code-only]` | `AuthServiceImpl.java:197-201` filters only `branch.getStatus() == ACTIVE`; the switch check at `JwtRequestContextFilter.java:229-234` additionally requires `revokedAtIsNull AND active = true` on the *assignment*. A revoked assignment is **listed in the picker and 403s on selection** — reads as a broken app. One-line predicate fix. **Ship this first; it is free and it is the first thing the GM will hit.** |
| **B2** | **Add numeric `branchId` (+ `companyId`) to `UserBranchDto`** `[code-only, OPTIONAL — de-scoped from the MVP]` | Correction to an earlier draft: `UserBranchDto.java:10-19` **does** already carry `branchUid`, `branchCode`, `branchName` and `companyUid` — only the *numeric* branch/company ids are absent. Because the exec app addresses everything by uid (invariant 3), it does not need them. The real motivation is the one written down at `pos_app/README.md:117-120`: without a numeric id a client must call `/branches?companyUid`, which is gated on `BRANCH.VIEW`. `BRANCH.VIEW` **is** held by `FINANCE_DIRECTOR` and `BRANCH_MANAGER`, so the exec personas are not blocked. **Do it only if a screen actually needs the numeric id** — e.g. `/bi/sales-by-branch?branchId=`, which takes a numeric id (`BiDashboardController.java:130`). Additive to a record; safe for existing web/POS deserialisers. |
| **B3** | **`GET /api/v1/auth/bootstrap`** — **PROPOSED, does not exist** `[code-only]` | Collapses today's five cold-start round trips into one. Four of the five ship today: `POST /auth/login` (`AuthController.java:36-40`), `GET /auth/me` (`:54-58`), `GET /auth/my-branches` (`:61-65`), `GET /organisations/current` (`OrganisationController.java:79-83`), `GET /companies/accessible?organisationUid=` (`CompanyController.java:50-54`). The last two are **strictly sequential** — `accessible` requires the `organisationUid` that `current` returns — so this is not four parallel requests, it is a chain. Returns `{me, organisation{uid,name}, companies[{uid,name,baseCurrency}], branches[{uid,code,name,companyUid,isDefault}]}`. Gate `isAuthenticated()`, matching the two endpoints it absorbs. On 3G in Dar this is the difference between a 1 s and a 5 s cold start. |
| **B4** | **`GET /api/v1/mobile/exec/approvals/inbox?organisationUid=&page=&size=`** — **PROPOSED, does not exist** `[code-only]` | The single biggest structural gap for the Group-GM persona: `ApprovalDecisionServiceImpl.java:279-289` binds the inbox to `principal.companyId()`, so a GM over 4 companies must header-switch into a branch of each and poll four times. Change is confined to `inbox` (`:277-304`): loop the caller's accessible companies, keeping the per-company `scopeGuard.assertCanActIn` (`:282`) and `approverResolver.resolveRoleCodes(userId, companyId)` role/SoD filter (`:284`) **untouched**. Reuses `ApprovalRequestDto` — but note it carries `companyId` only, **not `companyUid`/`companyName`**, so those are **two additive new record components** (`ApprovalRequestDto.java:20-45`), not existing fields. Perm `APPROVALS.DECIDE` (seeded, `R__seed_permissions.sql:24` — no seed edit). |
| **B5** | **Make the inbox actually paginate and sort** `[code-only]` | `findInboxRequestIds` returns **all** ids unbounded (`:288-289`), `findAllById` maps **every** row through `engine::toDto` (`:295-299`), then an in-memory `subList` pages it (`:300-303`). The `Pageable`'s sort is silently dropped — `?sort=submittedAt,desc` is a **no-op**. `toDto` (`ApprovalEngineImpl.java:233-265`) is itself N+1: one step query, then **two queries per step** (decisions + role name), plus a branch lookup and a user-name lookup per request. Push the page and sort into the repository query and batch the enrichments; add `documentType`/`branchUid`/`minAmount` filters the controller never exposed (`ApprovalRequestController.java:39-44`). |
| **B6** | **Derive `currentStepSequence`, or drop it** `[code-only]` | Declared at `ApprovalRequest.java:63-66`, read at `ApprovalEngineImpl.java:260`, returned at `ApprovalRequestDto.java:33` — and a repo-wide grep for `currentStepSequence` returns **exactly two hits, both declarations, zero writers**. It is always `null`. The client can derive the open step from `steps[]` (lowest-sequence `PENDING`, which is precisely what `findPendingStepsOrdered(...).get(0)` does server-side at `ApprovalDecisionServiceImpl.java:87-91`), but a DTO field that is always null is a trap. Populate it in `toDto` from the same derivation. |
| **B7** | **Retry-safe decide: `expectedStepUid` + `expectedVersion`** `[schema — V105]` | `decideWithRetry` (`ApprovalDecisionServiceImpl.java:76-144`) resolves `pendingSteps.get(0)` at execution time (`:91`), i.e. **whatever step is open when the request arrives**. On a multi-step chain where the same person holds two consecutive roles — normal in a small Tanzanian group — an ambiguous-POST retry approves step 1 *and* step 2. SoD blocks the submitter only, never the same approver on consecutive steps. `@Version` (inherited from `UidEntity.java:31-33`) prevents concurrent writers, not one writer acting twice in sequence — and its retry path (`:135-142`) actively *re-resolves* the open step. Mirror `V98__pos_expense_idempotency.sql`, which reserves the key **inside** the business transaction before the write (its header states the RESERVE-BEFORE-PROCESS rule verbatim). **DDL sketch below.** |

**V105 DDL sketch — PROPOSED** (`V105__approval_decision_idempotency.sql`):

```sql
-- Additive only. New table; no ALTER on a populated table; no NOT NULL backfill.
-- Identity style and idem_key width mirror V98__pos_expense_idempotency.sql (house style:
-- 143 of 153 identity columns in the migration set use GENERATED BY DEFAULT).
CREATE TABLE approval_decision_idempotency (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organisation_id     BIGINT       NOT NULL,   -- see note below
    company_id          BIGINT       NOT NULL,
    idem_key            VARCHAR(80)  NOT NULL,   -- client-generated per human decision
    user_id             BIGINT       NOT NULL,
    approval_request_id BIGINT       NOT NULL,
    step_uid            VARCHAR(26)  NOT NULL,   -- the step the human actually saw
    action              VARCHAR(10)  NOT NULL,
    result_status       VARCHAR(20),             -- filled on completion; NULL = reserved/in-flight
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_appr_idem            UNIQUE (company_id, idem_key),
    CONSTRAINT fk_appr_idem_org        FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    CONSTRAINT fk_appr_idem_request    FOREIGN KEY (approval_request_id) REFERENCES approval_requests(id),
    CONSTRAINT chk_appr_idem_action    CHECK (action IN ('APPROVE','REJECT'))
);
CREATE INDEX ix_appr_idem_request ON approval_decision_idempotency (approval_request_id);
```

> **Correction on the `organisation_id` column.** An earlier draft justified it as "ADR-0062 D-9: every new table carries the tenant". **That is not what D-9 says.** D-9 was **AMENDED 2026-08-14 and cut from the Phase-1 release** (`docs/decisions/0062-organisation-as-tenant-multitenancy.md:190-199`) — it is deferred pending D-4 (row-level security), and the ADR explicitly records that 182 of 205 tables carry `company_id` and are therefore already one join from their organisation. **Carrying `organisation_id` here is a defensible forward-compatibility choice, not a standing rule**, and the owner should be told it is optional. If it is kept, something must populate it — D-9 notes that "nothing populates the columns for new rows until P2-1".

Paired `[code-only]` change: `DecideRequest` (`…/dto/DecideRequest.java:10-13`, today exactly `{action, comment}`) gains **optional** `expectedStepUid` and `expectedVersion`; when present the service refuses with a distinct 409 `"This request changed since you looked at it. Refresh and try again."` if either differs. Note that **`version` is not on `ApprovalRequestDto` today** (`UidEntity` has `@Getter` on it, the DTO does not expose it) — so `expectedVersion` requires one additive DTO component as well, or the client uses `expectedStepUid` alone, which is sufficient and cheaper. Optional keeps the web client and POS untouched — no parity break. `Idempotency-Key` is honoured only when supplied.

> **Two landmines the client must handle regardless of B7.** (a) `POST …/approve` and `POST …/reject` call the *identical* `decisionService.decide(uid, request)` (`ApprovalRequestController.java:64-77`) — **the URL does not decide the action, the body's `action` does.** A client that hardcodes the path per button and lets `action` desync will silently invert a decision. The shared service must always set both and a unit test must assert they agree. (b) An authz failure on decide arrives as **409, not 403** (`ConflictException` at `ApprovalDecisionServiceImpl.java:94-98`), and its message **leaks the required role code** — the client must not render it verbatim. This is also an `error-message-hygiene` violation worth fixing on its own account.

#### 2.2 Phase-3/4 backend (reports) — all `[code-only]`, no schema

| # | Item | Detail |
|---|---|---|
| **B8** | **Branch-read authorisation in the report queries** `[code-only]` — **BLOCKING before any branch picker ships** | Repo-wide, `grep assertMayReadBranch` returns **one file**: `ProductStockReportQuery.java:118` (impl `:390-412`). **Five** call sites resolve a caller-supplied branch with company scoping only and then filter on it: `SalesReportQuery.java:54-79`, `StockReportQuery.java:67-82`, `StockMovementReportQuery.java:127-159`, **`SalesByBranchQueryImpl.java:30-37`** (which asserts only `assertCanActIn` on the *company*), and **`BiDashboardController.java:126-136`**, which accepts a raw numeric `branchId` and passes it straight through. A user assigned only to Arusha reads Dodoma's sales, margins, stock and branch totals by editing a query string, while the `X-Branch-Uid` header path refuses exactly that (`JwtRequestContextFilter.java:229-234`). **`/bi/sales-by-branch` is the endpoint this app's league table is built on, so this is not hypothetical — a phone app makes a branch picker the primary navigation.** Lift the guard into a shared helper and apply it at all five. |
| **B9** | **Never send `branchId` to `/bi/dashboard`; fix the composition instead** `[code-only]` | Precisely: at `DashboardServiceImpl.java:189-195`, `branchId` **is** threaded into `safeCrm` (`:192`) and `safeSalesByBranch` (`:195`), but **not** into `safeFinance` (`:189`), `safeWorkingCapital` (`:190`) or `safeInventory` (`:191`) — while the header at `:200-206` stamps `branchScope.label()` across the whole response. On a phone card reading *"Mwanza Branch — Net Profit 41,200,000 TZS"* that is a wrong number presented as a right one, to the audience that will act on it. Thread `branchId` through those three paths in the new mobile composition — `journal_lines.branch_id` exists (`V10__general_ledger.sql:225`) with index `ix_journal_lines_company_branch` (`:330-331`), and `stock_on_hand` has `ix_stock_on_hand_company_branch` (`V7__stock.sql:78`). **No schema change.** Until it lands, the client labels every unfiltered tile `Group-wide`. |
| **B10** | **`GET /api/v1/mobile/exec/brief`** — **PROPOSED, does not exist** `[code-only]` | The anchor. Budget: **≤ 8 KB gzipped, ≤ 6 SQL statements, p95 ≤ 400 ms.** Three verified composition rules make it possible: (a) **never call `TrialBalanceQuery.compute(companyId)`** — it is unbounded by date (`TrialBalanceQuery.java:58`, "computed from journal_lines GROUP BY account_id") and is executed **three times per `/bi/dashboard` call** (`DashboardServiceImpl.java:262`, inside `ArReconciliationQuery.java:68`, inside `ApReconciliationQuery.java:67`). A date-bounded `computeForPeriod` already exists (`:66`); better still, add a narrow `controlBalances(companyId, Set<String> codes)` returning just `1200`/`2100`/`1300` plus a tie check. (b) **never call `StockValuationQuery.report`** (`DashboardServiceImpl.java:329`) — it materialises a row per product to return three scalars. (c) collapse the **26 trend statements** — `buildTrend` (`:379-391`) issues one fiscal-period query plus one `GROUP BY` per period up to `MAX_TREND_PERIODS` (12), and it runs **twice** (revenue `:364`, net profit `:371`) — into one fiscal-period-joined `GROUP BY`. *(The "~58 + 4N statements per dashboard" figure quoted in an earlier draft is an **estimate**; the three items above are counted from source.)* |
| **B11** | **Statement summaries, top-N, stock totals, receivables, health board** — **all PROPOSED, none exist** `[code-only]` | `/mobile/exec/statements/summary` (section subtotals only — a 200-account P&L is large and six columns wide), `/mobile/exec/top` (fills the ADR-0037 v1 exclusion at `0037-bi-analytics-dashboards.md:108` of "top customers / top products by revenue or outstanding"; replaces the **unbounded** `/reports/sales`, whose main query ends `ORDER BY l.product_code NULLS LAST` with **no `LIMIT`** at `SalesReportQuery.java:174`), `/mobile/exec/stock-summary` (`SUM()` instead of materialising rows), `/mobile/exec/receivables` (top-N plus a branch dimension — `ar_invoices.branch_id` exists, `V11__accounts_receivable.sql:18`; note ADR-0037:108 also excludes "company-wide bucketed AR/AP ageing", so this is genuinely new work), `/mobile/exec/health` (collapses five recon calls into one). Every one reuses existing permission codes — **no seed edit**. Every mobile list capped server-side at `size=50`. |
| **B12** | **Cheap wins** `[code-only]` / `[config-only]` | `server.compression.enabled` in `application.yml` — **there is no `compression` key in any `application*.yml`**; gzip exists only at the Caddy hop (`infra/prod/Caddyfile:28` `encode gzip`, `dist/bundle/Caddyfile:25` `encode gzip zstd`), so the QA stack and anything hitting `:8081` directly ship JSON uncompressed. Strong `ETag` + `If-None-Match` computed **in the service** from `generatedAt` + a content hash — not `ShallowEtagHeaderFilter`, which serialises the whole body anyway. On 3G a 304 is ~200 bytes against 8 KB. Caffeine `@Cacheable` on the brief, 5 min TTL, `?refresh=true` bypass, key `(orgId, companyId, branchId, businessDate, permissionFingerprint)` — and **`generatedAt` must be the cache entry's creation time, not `Instant.now()`**, or the phone renders a fresh timestamp over stale numbers. **Verified: there is no `@Cacheable`, no `CacheManager`, no `ETag`, no `Cache-Control` anywhere in `backend/src/main/java`.** |
| **B13** | **Rate-limit `/auth/*`** `[code-only]` + `[config-only]` | **Verified: no rate limiting exists anywhere in the backend** — no `RateLimit`, no `bucket4j`. The only throttles are per-account lockout (`application.yml:112-115`, `max-failed-attempts: 5` / `lock-minutes: 15`) and the step-up throttle (`StepUpAuthServiceImpl.java:105`, `:371-396`). Per-account lockout does nothing against spraying one password across 500 usernames. A login endpoint a mobile app pushes onto the internet needs per-IP and per-username throttling: an in-memory filter mirroring `StepUpAuthServiceImpl`'s `Throttle` record (`:396`), **and** Caddy `rate_limit` on `/api/v1/auth/*`. Do both. |
| **B14** | **`POST /api/v1/auth/change-password`** (self, current password required) — **PROPOSED, does not exist** `[code-only]` | Today the only password-setting endpoint is `PUT /api/v1/users/uid/{uid}/password` gated on `USER.MANAGE` (`UserController.java:96-101`), so a CFO whose phone was stolen **cannot rotate their own credential** — the first thing they should be able to do. Specify it as POST so no new HTTP verb is needed in the Dart client. |
| **B15** | **Version the health endpoint** `[code-only]` | `HealthController.java:20-26` returns exactly `{status, service, time}` — **no version**. The pairing probe cannot distinguish "reached an ERP of the wrong version" from "reached an ERP", and the fleet update story (§5) has nothing to compare against. Add `version` + `minMobileClient`. Note this endpoint is one of the explicitly public exceptions in `EndpointAuthorizationTest`, so it stays gate-free. |

#### 2.3 Phase-5 backend (push + kill switch)

| # | Item | Detail |
|---|---|---|
| **B16** | **`user_devices` table** `[schema — V106]` | One table serves **both** push tokens and device identity for the kill switch and (later) device-bound approval signatures. `UNIQUE (push_token)` handles a token migrating between users on a refurbished handset — registration re-points the row instead of fanning out to the wrong person. |
| **B17** | **`app_users.sessions_revoked_at`** `[schema — V107]` | **The highest value-per-byte migration in this design.** Revoking a refresh token does not kill the outstanding access token — a thief with an unlocked app keeps working for up to 15 minutes (`application.yml:109`, `access-token-ttl-minutes: 15`). `JwtRequestContextFilter.java:113-115` **already** performs a per-request PK read of the user row (`appUsers.findActiveScope(userId, ACTIVE)`) on every request; compare one nullable column against `jwt.getIssuedAt()` in that same read and access tokens die on the next request at **zero extra query cost**. |
| **B18** | **`DeviceController` + `PushSender`** `[code-only]` | `POST /api/v1/devices` (idempotent upsert on token), `DELETE /api/v1/devices/uid/{uid}`. `PushSender` mirrors `EmailSender.java:27-28` (`@Component @ConditionalOnBean(JavaMailSender.class)`) so an unconfigured deployment yields an absent bean and a silently skipped channel. **No migration is needed to write a PUSH row** — `V21__notifications.sql:78` already has `CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH','WEBHOOK'))` and `NotificationChannel.java` reserves it. `NotificationRaiserImpl` takes `Optional<PushSender>` alongside `Optional<EmailSender>` (`:54`, `:63`, `:71`, dispatch at `:209`). |
| **B19** | **No new outbox handler needed — but two real bugs to fix** `[code-only]` | `ApprovalSubmittedNotificationHandler.java:48-73` already consumes `APPROVAL.SUBMITTED` and raises `APPROVAL_PENDING`; `AudienceResolver.recipients` (`:29-36`) already returns the right recipients. **"An approval is waiting" already computes the correct recipient list — only the last hop is missing.** What must be fixed: (a) the seeded `audience_permission = 'PURCHASE.PO.APPROVE'` (`V21__notifications.sql:226`) is a **phantom code** — it appears **zero times** in `R__seed_permissions.sql`; the real one is `PURCHASE.ORDER.APPROVE` (`R__seed_permissions.sql:167`) — **and it is duplicated in `NotificationTypeSeeder.java`, so it must be fixed in both places** (the migration is applied and immutable, so the fix is a new `V<n>` **or** the Java seeder, depending on which is authoritative at runtime — confirm before authoring). (b) the body renders `"User#" + payload.submittedByUserId()` (`:60`) rather than a name. Add a collapse key so twelve approvals in an hour are not twelve buzzes. |
| **B20** | **Stamp the dead device columns** `[code-only]` | `refresh_tokens` has `device_info`, `user_agent`, `ip_address`, `last_used_at` (`V1__baseline.sql:208-211`, each annotated `-- P2:`) and a repo-wide grep for their setters finds **only the declarations in `RefreshToken.java:125/133/141/149`** — nothing calls them. Device labelling, last-seen and per-device revoke are therefore free of schema. Needs `HttpServletRequest` threaded into `refresh` (`AuthController.java:42-45` does not take one; `login` at `:37-39` already does and passes `getRemoteAddr()`). |
| **B21** | **Refresh rotation grace** `[code-only, needs an ADR]` | When a presented token was rotated < 30 s ago **and** carries the same `X-Device-Id` as that token's `device_info`, return a plain 401 **without** the chain revoke; otherwise keep today's behaviour — `AuthServiceImpl.java:124-130` calls `loginAttempts.revokeAllTokens(token.getUserId(), now)` on any reuse of a consumed token. Turns "the CFO's flaky 3G logged everyone out of the tills" into one silent re-login while preserving cross-device theft detection. This is a **security-policy change** — write it up. |

**V106 DDL sketch — PROPOSED** (`V106__user_devices.sql`):

```sql
CREATE TABLE user_devices (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uid             VARCHAR(26)  NOT NULL,          -- UidEntity contract
    organisation_id BIGINT       NOT NULL,          -- optional; see the D-9 note under V105
    user_id         BIGINT       NOT NULL,
    platform        VARCHAR(10)  NOT NULL,          -- ANDROID | IOS
    device_id       VARCHAR(40),                    -- client ULID, survives token rotation
    push_token      VARCHAR(255),                   -- FCM (APNs via FCM); NULL until granted
    public_key_der  BYTEA,                          -- reserved: device-bound approval signatures
    app_version     VARCHAR(20),
    device_label    VARCHAR(60),                    -- "Samsung A54" — a revoke list must be readable
    locale          VARCHAR(10),
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,   -- UidEntity @Version
    CONSTRAINT uq_user_device_uid       UNIQUE (uid),
    CONSTRAINT uq_user_device_token     UNIQUE (push_token),
    CONSTRAINT fk_user_device_user      FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_user_device_org       FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    CONSTRAINT chk_user_device_platform CHECK (platform IN ('ANDROID','IOS')),
    CONSTRAINT chk_user_device_status   CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_user_devices_user ON user_devices (user_id, status);
```

**V107 DDL sketch — PROPOSED** (`V107__app_user_sessions_revoked_at.sql`) — the textbook additive shape on a populated table:

```sql
-- Nullable, no default, no backfill, no constraint: safe on a live table of any size.
ALTER TABLE app_users ADD COLUMN sessions_revoked_at TIMESTAMPTZ NULL;
COMMENT ON COLUMN app_users.sessions_revoked_at IS
  'Access tokens issued at or before this instant are rejected by JwtRequestContextFilter. '
  'Set by a self or admin "sign out everywhere". NULL = never revoked.';
```

#### 2.4 The seed edit — `R__seed_permissions.sql`

`[schema-class change — repeatable seed, still needs owner approval]`. `R__` upserts and self-heals, so it is **not** a new `V<n>`, but the standing rule covers it.

Three things in one edit:

1. **A `GENERAL_MANAGER` / `OWNER` role bundle.** `R__seed_permissions.sql:309-321` seeds exactly 12 operational roles — SALESPERSON, CASHIER, FIELD_SALES_AGENT, STOREKEEPER, ACCOUNTANT, SALES_MANAGER, BRANCH_MANAGER, PROCUREMENT_OFFICER, PROCUREMENT_MANAGER, HR_PAYROLL_MANAGER, FINANCE_DIRECTOR, PRODUCTION_MANAGER. **There is no GENERAL_MANAGER, CEO or OWNER.** (A 13th role, `ORG_ADMIN`, is created in `V1` and absorbs every non-`platform` permission via a `CROSS JOIN` at `:277-294` — but it is a tenant-admin role, not an operational bundle, and **it does not solve the routing problem**: approval routing matches the *role code frozen onto the policy step*, so an `ORG_ADMIN` who holds no role matching a policy step still gets an **empty inbox by construction** — `resolveRoleCodes` reads `user_role` rows and `inbox` returns `Page.empty` at `ApprovalDecisionServiceImpl.java:284-287`, **before** any root bypass.)
2. **Close the two verified holes in the bundles that do map to the audience** (both re-checked grant-by-grant against the seed):
   - `FINANCE_DIRECTOR` (grants at `:813-889`) **lacks** `SALES.INVOICE.VIEW`, `INVENTORY.VALUATION.VIEW`, `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW` — while holding `BI.OPS.VIEW` (`:889`). So the stock tile renders and **every drill-down 403s**, which reads as a broken app.
   - `BRANCH_MANAGER` (grants at `:660-701`) **lacks** `GL.VIEW`, `AR.STATEMENT.VIEW` (holds only `AR.VIEW`), `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW`, `BUDGETING.REPORT.VIEW` — so **cannot open AR ageing**, the number they most want.
3. **Any new codes** the PROPOSED endpoints gate on. Prefer reusing existing codes; the design above deliberately does so for everything except a possible `EXEC.BRIEF.VIEW`.

**Note:** `DefaultRoleBundlesSeededTest` asserts "all 12 shipped operational roles" against the seed file (javadoc `:22`, list `:47`). Adding a 13th role **fails that test until it is updated in the same commit** — the gate working as designed. `RolePermissionClosureTest` may also react to new grants.

---

### 3. Phased roadmap

Estimates are for a **small team: one full-time Flutter/backend developer plus part-time review**, in **working weeks**, backend and client running in parallel with the backend leading by about a week. They include test-writing and CI, not owner review latency. **They are estimates, not measurements.**

| Phase | Weeks | Backend | Client | Ships to the owner as |
|---|---|---|---|---|
| **0 · Extract + CI** | **1.5** | — | `packages/orbix_erp_client` extracted; `pos_app` green, zero behaviour change; `flutter-ci.yml` live; real Android release keystore for both apps | *(nothing user-visible — but 14 POS tests start running in CI for the first time)* |
| **1 · MVP — Approve from the phone** | **3** | B1, B3, B4, B5, B6 (B2 only if a screen needs it) | Pairing with a **diagnosing** probe; login; biometric gate; Keychain/Keystore token store; cross-company approvals inbox; detail; approve/reject with confirm sheet, mandatory rejection reason, `Idempotency-Key` + durable `X-Request-Id`, ambiguous-POST read-back, neutral 409 sheet; freshness UI | **Signed APK, sideloaded, pointed at QA at day ~7 (read-only inbox) and at prod at end of phase** |
| **2 · Harden the decide path** | **1.5** | **B7 (V105)** + the two landmines | `expectedStepUid` (and `expectedVersion` if the DTO field lands) wired; step strip ("Procurement ✓ · **You** · Finance"); "See the document" on-demand PO/SO line load; a11y + text-scale gates in CI | *v0.2 APK — the version he uses daily* |
| **3 · Numbers, single company** | **2.5** | **B8 (blocking)**, B9, B11, B12, B15 | Morning Brief; branch league table (`/bi/sales-by-branch`, sorted total-desc in `DashboardServiceImpl.buildSalesByBranch` — **not** in SQL, so the ordering is a service-layer guarantee, not a query one); cash & working capital; four-tier Hive cache; ETag | *v0.3 — brief + branches + cash* |
| **4 · Group brief** | **2.5** | **B10**, B13, B14 | Group totals across companies with `mixedCurrency` + `excludedCompanyUids` honesty flags; offline reads for closed days | *v0.4 — the 7 a.m. screen* |
| **5 · Push + kill switch** | **2.5** | **B16 (V106)**, **B17 (V107)**, B18–B21 | `firebase_messaging`; `POST_NOTIFICATIONS` runtime request; register on login / re-register on token refresh / **`DELETE /devices` before clearing the session** on logout; sessions screen; sign-out-everywhere | *v1.0 — hardened* |

**Total ≈ 13.5 weeks to a hardened v1. First useful build in the owner's hand at ~week 3; a genuinely daily-use build at ~week 4.5.**

Add **+4 to +6 weeks** if iOS is in scope — **verified: `pos_app/` has no `ios/` directory** (its platform folders are `android`, `windows`, `web`), so iOS is greenfield, plus an Apple Developer Program account under a legal entity, an APNs `.p8`, ATS configuration, and a publicly-certificated demo server for App Review. See Open Decision #1.

#### The MVP, defined crisply

> **v0.1 = "The GM never opens a laptop to release a purchase order again."**
>
> **In:** pair the phone to one ERP server (QR-carried host + CA fingerprint, human-verified, then a probe that distinguishes *unreachable* / *untrusted* / *not an ERP* / *wrong version*); log in; biometric unlock on cold start and after 2 minutes backgrounded; refresh token encrypted under a hardware key; **one approvals inbox spanning every company the user can act in**; a card carrying amount, supplier, branch, who raised it, and how long it has waited; a detail screen; approve or send back behind a confirm sheet, with a **mandatory reason on rejection**; correct handling of the 409 "already decided" (neutral, not red) and of an ambiguous POST (read back, never blind-retry).
>
> **Out:** any financial figure; any chart; push; offline queueing of decisions; iOS.
>
> **Why this is the smallest genuinely useful thing:** the persona's stated complaint is *"an approval that quietly sits somewhere I never look"* — and `ApprovalRequestDto` already carries `branchName`, `branchCode`, `amount`, `currency`, `summary`, `submittedByName` and `submittedAt` as read-time enrichments resolved in `ApprovalEngineImpl.toDto` (`:233-265`), so the card needs **no second fetch**. Everything in the MVP but B4 is client work against endpoints that already ship — and B4 itself adds only two record components (`companyUid`, `companyName`) to an existing DTO.
>
> **Deliberately not in the MVP:** offline approve. `decide()` resolves whatever step is open at execution time (`:87-91`), the audit aspect stamps server `Instant.now()` (`:101`, `:126-131` — so a decision made at 09:00 and flushed at 14:00 records as 14:00), and a 409 raised four hours later has nowhere honest to land. **Offline the Approve button is absent with an explanation — not present-but-disabled.**

---

### 4. Testing strategy

#### 4.1 Flutter — five layers, all gated in `flutter-ci.yml`

| Layer | What | Where |
|---|---|---|
| **Unit** | `json.dart` coercion (Long-as-string, BigDecimal-as-number, **`PageMeta` counts as plain numbers**), `money.dart` byte-exactness, `api_exception.dart` parsing `errors` as `List<String>` **and** as a bare string, `token_manager.dart` single-flight (`:66-68`) + "only 401/403 clears the session" (`:79-87`) | `packages/orbix_erp_client/test/` — lifted from `pos_app/test/api_exception_test.dart` |
| **Wiring guard** | The source-level grep for any `Dio(` construction whose file does not call `applyErpTls(` | `pos_app/test/trusted_ca_test.dart` `wiring` group `:66-98` — **lift verbatim into the package**; its comment at `:70-71` records that this regressed silently once, and every other test still passes in the broken state |
| **A11y guidelines** | `meetsGuideline(textContrastGuideline)`, `androidTapTargetGuideline`, `labeledTapTargetGuideline` — every screen, in each of its **four states**: loading / empty / error / populated | `mobile_exec/test/*_a11y_test.dart` |
| **Text scale** | Pump each screen at `textScaleFactor` 1.0 / 1.5 / 2.0 and `expect(tester.takeException(), isNull)` — RenderFlex overflow *throws*, so this catches the real failure mode. Goldens at 2.0× for **Brief** and **Approval detail** only | `mobile_exec/test/*_scale_test.dart` |
| **Semantics** | Assert the exact reader string of every figure — `matchesSemantics(label: 'Sales yesterday, 48.2 million Tanzanian shillings, up 12 percent versus Thursday, as of 07:12')`. TalkBack reads `M` as "em" and `TZS` as three letters; no guideline matcher catches that | `mobile_exec/test/*_semantics_test.dart` |

> **Learn from the web's scar, do not copy it.** `web/src/testing/a11y.helper.ts:50-60` **skips the axe scan when `process.env.CI`** because axe-core starves under the parallel vitest+jsdom runner (the `a11y-axe-ci-starvation` memory; the helper's own comment recommends a separate low-concurrency job). Flutter's guideline matchers are pure Dart — no browser, no async engine, no global lock. **Do not port the skip-in-CI escape hatch. Gate for real.**

#### 4.2 Live smoke test against a real backend — the existing pattern

`pos_app/test/live_pos_smoke_test.dart` is the template and it is a good one:

```dart
final host = Platform.environment['POS_LIVE_HOST'];              // :27
if (host == null || host.isEmpty) {
  test('live POS smoke (skipped — set POS_LIVE_HOST to run)', () {}, skip: true);
  return;                                                        // :31-34
}
...
TestWidgetsFlutterBinding.ensureInitialized();
// flutter_test installs an HttpOverrides that 400s every request; clear it
// so this live test makes real network calls.
HttpOverrides.global = null;                                     // :48-51
```

Three properties to copy exactly: **env-gated so CI stays green without a backend**, **`HttpOverrides.global = null`** (without it every request 400s and the failure is baffling), and a **mutating variant kept separate** — `pos_app/test/live_pos_e2e_test.dart:32-34` warns "It MUTATES the backend… Run against a throwaway/dev database."

`mobile_exec/test/live_exec_smoke_test.dart` mirrors it with **one deliberate difference**, spelled out in §4.3.

#### 4.3 Testing as a NON-ROOT executive — the trap, and the three gates

**The trap, stated precisely:** `app_user.is_root` short-circuits `PermissionResolver` to allowed, and `/auth/me` returns an **empty `permissions` array for root** (`AuthServiceImpl.java:167-171`, whose own comment says "Root bypasses scoping, so it carries no enumerated set"). So a root-only test run masks every RBAC gap *and* every client-side gate that depends on the permission set. `pos_app/test/live_pos_smoke_test.dart:28-29` defaults to `rootadmin` / `RootPass12345` — convenient for the POS, **wrong for this app**.

> `mobile_exec/test/live_exec_smoke_test.dart` **defaults to a non-root user and refuses to run as root**: it calls `/auth/me`, and if `isRoot == true` it **fails with "run this suite as a non-root executive role"** rather than passing vacuously.

Three gates, in cost order:

1. **Static, free, already shipping.** `backend/src/test/java/com/erp/architecture/PermissionCodesSeededTest.java` classpath-scans `com.erp.api` for `@PreAuthorize`, extracts the code from `@perm.has('CODE')` and the *third* quoted arg of `@perm.scoped(expr,'entity','CODE')`, and cross-checks against `R__seed_permissions.sql` — **this is the phantom-permission-code guard and it runs in surefire with no Docker.** `EndpointAuthorizationTest` fails the build on any new `com.erp.api` handler without a `@PreAuthorize` (public exceptions: auth login/refresh/logout, health). `DefaultRoleBundlesSeededTest` guards the bundles and **will go red when `GENERAL_MANAGER` is added — update it in the same commit.** **Every new endpoint in §2 is covered by these three the moment it is written.**
2. **HTTP integration, per persona.** New `MobileExecHttpIT` follows `backend/src/test/java/com/erp/api/RbacEnforcementHttpIT.java`: extends `PostgresIntegrationTest`, drives requests through the **real Spring Security filter chain** via MockMvc (bearer validation → `JwtRequestContextFilter` → `@PreAuthorize` SpEL) rather than calling services with a hand-set `RequestContext`, and mints tokens directly via `JwtService` to avoid bcrypt latency. Seed with `com.erp.support.IamTestData` / `TenantFixtures`. **Matrix:** for each of `GENERAL_MANAGER` (new), `FINANCE_DIRECTOR`, `BRANCH_MANAGER`, `SALES_MANAGER`, assert the expected **200 or 403** on every new endpoint — and assert **403 where it should be 403**, because a test that only checks the happy path is how the FINANCE_DIRECTOR drill-down hole survived.
3. **Cross-tenant and cross-branch.** Extend the existing `TwoOrganisationIsolationIT` / `TenancyParityHarnessIT` pattern to the new mobile endpoints: a group brief for org A must never include a company of org B, and **B8 needs an IT per call site asserting that a user assigned only to Arusha gets 403 for Dodoma's `branchUid` — including `/bi/sales-by-branch?branchId=`, which today has no such check.** `ApprovalsEngineIT.java:354-382` (`inbox_excludesRequestsFromUnassignedBranches`) already covers the inbox's cross-branch behaviour; extend it for B4's cross-company loop.

**One structural constraint on every new service:** `TenantScopingRulesTest` is a *frozen* rule banning `findById`/`getReferenceById` from `..service..` classes. Write company-scoped finders (`findByCompanyIdAndId(...)`) from the start; `findAllById` is permitted. **Never regenerate the freeze store locally** — per the `archunit-freeze-local-vs-CI` memory, local regeneration is a lambda-attribution artifact that breaks CI.

#### 4.4 Migration testing

Every `[schema]` item ships with a keep-data test modelled on `backend/src/test/java/com/erp/support/MigrationKeepDataIT.java`, whose header states the exact failure this catches:

> *"the seed runs on a DB that already has companies (a keep-data upgrade)… The normal test path applies all migrations to an EMPTY database (no companies at migration time), so those seeds insert zero rows and the overflow was invisible."*

It migrates to version *n-1* in an isolated schema, inserts realistic rows, then migrates to head. **Plus the standing rule from the V103 outage: boot the app against a restored customer copy before the migration ships.** `bash scripts/check-migrations.sh` and `BASE=origin/develop bash scripts/check-migrations.sh` run before merge (CI job `migration-hygiene`, `backend-ci.yml:88-97`).

#### 4.5 Manual, once per release

TalkBack on a mid-range Android — the device class this audience actually carries — with system font size at maximum, running **the whole approve flow end to end**. Automated checks find missing labels; only a human finds a label that is technically present and useless.

---

### 5. Distribution and the update story

#### 5.1 How each phase reaches the phone

| Phase | Channel | Mechanics |
|---|---|---|
| **0** | — | nothing user-visible |
| **1 (day ~7)** | **Direct APK, pointed at QA** | QA is `http://…` with no TLS wall, so the private-CA problem does not gate first contact. Hand over by WhatsApp/USB. Requires "install unknown apps"; Play Protect warns on first install. |
| **1 (end) – 4** | **APK via `dist/`** | `dist/build-release.sh` already takes `--version` (`:55`, required at `:63`) and assembles the client bundle (`:14-15`); it currently emits `orbixerp-<version>-<arch>.zip`. Add `OrbixExec-<version>.apk` to the same bundle — a small change to a script that already exists, and it matches how OrbixPOS ships today (`dist/OrbixPOS-1.5.1+9-windows.zip`). Serve it from the ERP box (the API is already the SPA's origin) so the client admin hands out a URL, not a file. |
| **5** | **Play internal testing → closed testing** | Adds auto-update, which is the real reason to go. Register as an **organisation**, not a personal account — personal accounts carry a 12-tester / 14-day closed-testing requirement before production. *(Play policy detail — (UNVERIFIED) against current Play documentation; re-check before committing to a date.)* |
| **Later** | **Play production + App Store** | Only once a real hostname + public certificate is standard for **every** install. |

**iOS:** TestFlight is fine for a 5–20 person pilot but builds expire after 90 days — **not a durable channel**. The **Apple Developer Enterprise Program is not an option**: it is licensed for your own employees only. The correct Apple answer for per-client B2B is **Custom Apps via Apple Business Manager** (private distribution to a named org, no public listing) — which requires the *client* to hold an ABM account, a real ask for a Tanzanian SME. *(Apple programme terms — (UNVERIFIED) against current Apple documentation.)*

**One app, runtime server configuration — non-negotiable.** `dist/bundle/docker-compose.tls.yml:40-45` gives each install its own `orbixerp-caddy-data` volume, with the comment "Persists the internal CA and any issued certificates", and `pos_app/lib/core/api/erp_root_ca.dart:25` states the consequence directly: each install *"mints its OWN internal root, which we cannot know at build time."* Submitting `OrbixExec – ClientA` and `OrbixExec – ClientB` differing only by a baked-in hostname and CA is a textbook Apple 4.3 rejection. **The trust list must be runtime data, not build data.**

#### 5.2 Versioning across a self-hosted fleet

**The problem:** N clients on N backend versions, one app binary, no forced update.

**The contract — three parts:**

1. **The app version is independent of the backend version**, semver `MAJOR.MINOR.PATCH+BUILD` exactly as `pos_app/pubspec.yaml:19` does (`1.5.1+9`). `MAJOR` bumps only on a breaking API expectation.
2. **The server declares compatibility, the app enforces it.** `GET /api/v1/health` (`HealthController.java:20-26`) gains `version` and `minMobileClient` (item **B15**). The pairing probe and every cold start read it:
   - app ≥ `minMobileClient` → proceed
   - app < `minMobileClient` → hard block with *"This server needs a newer OrbixExec. Ask your administrator for the update."* — never a mysterious 404
   - app ahead of the server → **soft** warning; **degrade by capability, not by version number** (probe for the PROPOSED endpoints and fall back to the per-company path when `/mobile/exec/brief` 404s). A fleet where every client upgrades on their own schedule cannot be served by version comparison alone.
3. **Update discovery without a store.** A `GET /api/v1/mobile/latest` — **PROPOSED**, public, `[code-only]` — returns `{version, apkUrl, mandatory}`, populated from the bundle that `dist/build-release.sh` installed on that box. The app checks it once per cold start and shows an "Update available" banner pointing at the APK on **that client's own server**. This keeps a self-hosted fleet self-updating without Play, and it stays useful *after* Play adoption for clients who sideload. *(As a public endpoint it must be added to `EndpointAuthorizationTest`'s allow-list explicitly, alongside auth and health.)*

**Backwards compatibility rule:** every new mobile DTO is additive-only and every panel independently nullable — the graceful-degrade contract the backend already honours (`DashboardServiceImpl.java:185-195`, `safeFinance`/`safeCrm`/`safeSalesByBranch` returning null when the permission is absent or the call failed). **A null panel means "forbidden or failed", not zero — and the client must render null ≠ 0.** Rendering a forbidden panel as `0` is the mobile version of a wrong number.

---

### 6. Risk register

| # | Risk | Impact | Likelihood | Mitigation | Owner decision? |
|---|---|---|---|---|---|
| R1 | **Double-approve on a retried POST.** `decideWithRetry` resolves `pendingSteps.get(0)` at execution time (`:91`) and its own optimistic-lock retry re-resolves it (`:135-142`); no idempotency key, no dedupe on `(request, user, step)`. On a multi-step chain where one person holds two consecutive roles, one human decision releases two steps. SoD blocks the submitter only. | **Critical** — money released without a decision | Medium | **B7 (V105)** + `expectedStepUid`; client re-sends the same `Idempotency-Key`; **never blind-retry an ambiguous POST — read back by uid first**; no offline queueing in v1 | **Yes — V105** |
| R2 | **A branch picker leaks another branch's numbers.** `assertMayReadBranch` exists in exactly one file (`ProductStockReportQuery.java:118`); **five** other call sites resolve a caller-supplied branch company-scoped only — including `/bi/sales-by-branch`, the endpoint the league table is built on. A phone app *invites* a branch picker as primary navigation. | **Critical** — cross-branch data leak, and the app is what makes it reachable | **High** if a picker ships before B8 | **B8 blocks the branch picker.** One IT per call site asserting 403 for an unassigned branch | No |
| R3 | **The private CA cannot reach a consumer phone.** All three operator-supplied CA sources are desktop-only: `POS_ERP_CA_FILE` reads `Platform.environment` (`trusted_ca_io.dart:12`, `:83`); `erp-ca.pem` and `certs/` resolve against `Platform.resolvedExecutable` (`:89-92`, `:117-125`) — a signed read-only bundle on mobile. `_sharedContext()` is built once and cached (`:23-24`), so a CA imported at runtime cannot take effect without a restart. And the default `dist/` install is plain HTTP (`dist/bundle/.env.example:63` `ERP_HTTP_PORT=8080`, `:76` `ERP_TLS_ENABLED=false`), which Android blocks by default on API 28+. | **High** — the app cannot connect at a new client | **High** without action | Fund **ADR-0061 follow-up #1** (`0061-pos-tls-trust-private-ca.md:99-100` — real domain + Let's Encrypt) **in parallel, not as a follow-up**; meanwhile rewrite `_candidatePaths()` onto `path_provider`, make `_sharedContext()` rebuildable, and ship QR-carried CA import with a human-verified fingerprint. Also **follow-up #2** (`:101-102`) — have the `dist/` installer emit its Caddy root somewhere an admin can reach it, today it needs `docker exec` | **Yes — #3** |
| R4 | **The owner's inbox is empty by construction.** Routing is by role code frozen onto the policy step; there is no `GENERAL_MANAGER`/`OWNER` bundle in the 12 seeded roles; `inbox` returns `Page.empty` at `:285-287` before any root bypass. `ORG_ADMIN` does not fix it — it holds the permissions, not a matching *role code*. | **High** — the flagship feature shows nothing | **Certain** without the seed edit | `R__seed_permissions.sql` edit + name the new role in policy steps + `MobileExecHttpIT` as that role | **Yes — #4** |
| R5 | **Refresh-token reuse detection revokes every session the user holds — including the tills.** Two concurrent refreshes, or a kill after the server rotated but before the client persisted, trips `revokeAllTokens` (`AuthServiceImpl.java:124-130`). | High — a CFO's flaky 3G logs the shop out | Medium | Single-flight refresh (`token_manager.dart:66-68`); clear the session **only** on 401/403 (`:79-87`); persist the new pair **atomically before use**; never refresh from a background isolate; **B21** rotation grace | Yes (B21 is a policy change) |
| R6 | **A wrong number presented as a right one.** `/bi/dashboard` stamps a branch label on figures that were never branch-filtered: `branchId` reaches CRM (`:192`) and sales-by-branch (`:195`) but **not** finance (`:189`), working capital (`:190`) or inventory (`:191`), while the header carries `branchScope.label()` (`:200-206`). | **High** — a decision made on a false figure | High if `branchId` is sent | **Never send `branchId` to `/bi/dashboard`.** B9 threads it properly in the mobile composition; until then label every unfiltered tile `Group-wide` | No |
| R7 | **Group totals silently sum mixed currencies.** Every report is single-company (`ScopeGuard.java:675` — `principal.root() \|\| companyId.equals(principal.companyId())`); `baseCurrency` is per company; there is **no `X-Company-Uid` header anywhere in the backend or web**. Policy currency is hardcoded `"TZS"` at create (`ApprovalPolicyServiceImpl.java:76`) and the matcher never converts — a USD 5,000 PO falls in the lowest TZS band and frequently **auto-approves**. | High — a fabricated group number, and a live auto-approve defect | Medium | `mixedCurrency` + `presentationCurrency` + `excludedCompanyUids` on the brief DTO; **surface the policy-currency defect to the owner separately — it is live today and will look worse on a phone showing "approved automatically"** | **Yes — #7** |
| R8 | **The `pos_app` extraction regresses the live till.** `1.5.1+9` runs a paying client's cash register, and `SecureStore` is a concrete class that must become an interface. | **Critical** | Low with discipline, high without | Pure move-and-rewire in its own PR; POS green first; `flutter-ci.yml` in the same PR so 14 previously-unrun tests start gating. **Trigger: any till regression → stop sharing, fork `lib/core` into `mobile_exec` and accept the duplication.** A shared package over a working cash register is a convenience, never a commitment | No |
| R9 | **No kill switch for a lost phone.** `revokeAllTokens` exists but is reachable only from the reuse path; no session list, no per-device revoke; a user cannot rotate their own password (`UserController.java:96-101` requires `USER.MANAGE`). And revoking refresh does not kill the 15-minute access token. | **High** — a pocket-sized permanent session over consolidated financials | Medium | **B17 (V107)** `sessions_revoked_at` compared inside the PK read `JwtRequestContextFilter.java:113-115` already performs — zero extra query cost; **B14** self-service password change; client caps the 7-day server ceiling (`application.yml:110`) at 12 h idle / 14 d absolute | **Yes — V107** |
| R10 | **Push requires Google.** FCM is the one part of a self-hosted product that must reach the public internet, and the notification titles transit *someone's* Google account. | Medium — architectural and privacy disclosure to the client | Certain if push ships | One Firebase project owned by us, **deliberately generic titles** (`{typeKey, notificationUid, sourceUid, linkRoute, title, body}` — never amounts or customer names on a lock screen), documented in an ADR. Per-client Firebase reintroduces per-client configuration and kills the one-binary property | **Yes — #6** |
| R11 | **iOS turns 13.5 weeks into 19+.** **Verified: no `ios/` directory exists in `pos_app`.** Developer Program account under a legal entity; APNs `.p8`; ATS; Apple 2.1 rejects "reviewer cannot get past screen one"; 5.1.1 account-deletion path. | High — schedule, not correctness | Certain if iOS is in scope | **Ask before costing anything.** Android-first regardless; a publicly-certificated demo server seeded with fake data, address and credentials in App Review notes | **Yes — #1** |
| R12 | **Screenshot asymmetry.** Android `FLAG_SECURE` is one line; **iOS has no equivalent — you cannot prevent a screenshot.** And `FLAG_SECURE` blocks legitimate screen sharing, which is exactly what an executive does on a call with their accountant. | Medium | Medium | Per-screen, not global: on for P&L / bank balances / payroll, off for the inbox list. iOS: blur on `applicationWillResignActive` (covers the multitasking snapshot that persists to disk and into backups) + observe the screenshot notification and log it | Yes (per-screen policy) |
| R13 | **Server cost of a "morning refresh".** `/bi/dashboard` computes the **date-unbounded trial balance three times** (`DashboardServiceImpl.java:262`, `ArReconciliationQuery.java:68`, `ApReconciliationQuery.java:67`), materialises every product row for three scalars (`:329`), and issues **26 trend statements** (`:379-391` × 12 periods × 2 metrics). Four companies multiply all of it, and it grows without bound as `journal_lines` grows. | Medium now, **High after two years of POS at a supermarket** | High | **B10** composes from narrow leaf queries and never calls `TrialBalanceQuery.compute` or `StockValuationQuery.report`; B12 Caffeine + ETag + gzip. **Recommend against materialised snapshots** — a second source of truth for figures the CFO reconciles to the shilling. If ever built, only as a **daily-close** snapshot | No |
| R14 | **Six of the eight approval surfaces bypass the engine** and will never appear in the mobile inbox: `PurchaseOrderController.java:211-213`, `PurchaseRequisitionController.java:68-71`, `HrLeaveController.java:60` (`/decide`, not `/approve`), `HrPayrollController.java:86-89`, `HrLoanController.java:59-62`, `BudgetVersionController.java:66-71`. **Verified: the only callers of `approvalEngine.submitForApproval` outside the approvals module are `PoApprovalGate.java:205` (`DOC_TYPE = "PURCHASE_ORDER"`, `:34`), `SalesApprovalGate.java:108` (`DOC_TYPE = "SALES_ORDER"`, `:34`) and `SalesOrderServiceImpl.java:400` (`APPROVAL_DOC_TYPE`, `:77`).** | Medium — "Approvals" promises more than it delivers | Certain | **Scope v1 honestly: the inbox is purchase orders and sales orders.** Routing the other six through the engine is its own project. Say so to the owner in week 1, not week 7 | **Yes — #8** |

---

### 7. Open decisions — the owner must answer before build starts

Each carries a recommended default. **"Yes to all" is a complete, coherent, buildable answer.**

1. **iPhone or Android?**
   *Recommended default:* **Android-first, iOS deferred to a funded phase 6.** Keeps the plan at ≈13.5 weeks. `pos_app` has no `ios/` directory, so iOS is greenfield plus a Developer Program account plus a publicly-certificated demo server for App Review — +4 to +6 weeks. If the answer is "he carries an iPhone", the plan does not change, the calendar does, and we say so now rather than discovering it in week six.

2. **`V105` — `approval_decision_idempotency` (new table, DDL in §2.1). Approve?**
   *Recommended default:* **Yes.** Without it a retried approve on a flaky link can release two steps of a chain. Additive, new table only, no `ALTER` on a populated table. Ships with a `MigrationKeepDataIT` sibling and a boot test against a restored customer copy. **Sub-decision:** whether to carry `organisation_id` — ADR-0062 D-9 was amended and cut, so this is a forward-compatibility choice, not a rule, and something must populate it.

3. **Fund a real hostname + Let's Encrypt for every install, in parallel with the app.**
   *Recommended default:* **Yes — a `<client>.erp.otapp.net` wildcard we control**, pointed at each client's IP, Caddy issuing via HTTP-01. This deletes the QR-CA-pairing feature, unlocks the App Store path, satisfies Android cleartext policy and iOS ATS, and means one binary serves every client. It is ADR-0061 follow-up #1 (`:99-100`), already owed. **If it lands early, come back and re-argue PWA** — Flutter's biggest structural advantage is that it works *despite* the certificate situation.

4. **Edit `R__seed_permissions.sql`: add a `GENERAL_MANAGER` bundle and close the FINANCE_DIRECTOR / BRANCH_MANAGER holes** (both verified grant-by-grant in §2.4).
   *Recommended default:* **Yes.** Without it the owner's inbox is empty by construction and the CFO's drill-downs 403. Repeatable seed, upserts and self-heals, no new `V<n>` — but it is a seed change and the standing rule covers it. `DefaultRoleBundlesSeededTest` is updated in the same commit.

5. **Scope of v1's numbers: per-company brief in phase 3, group brief in phase 4.**
   *Recommended default:* **Yes.** There is no organisation-level aggregate anywhere in the backend — `ScopeGuard.java:675` pins a caller to a single active `companyId`, and there is **no `X-Company-Uid` header in the backend or the web client** (verified by grep). *(The "54 report endpoints" figure from an earlier draft is **(UNVERIFIED)** — the mechanism is verified, the count is not.)* Group totals are real backend work (B10), not a client feature. Shipping approvals first means the owner has something useful for ten weeks while it is built.

6. **Push: one Firebase project owned by us, with deliberately generic notification titles.**
   *Recommended default:* **Yes**, written up as an ADR. Per-client Firebase means per-client app configuration, which reintroduces one-binary-per-client and kills the store path. The disclosure to the client — "notification titles transit our Google account; amounts and customer names never leave your server" — is explicit and honest. If the client refuses, push slips out of v1 and the app must be honest that it is pull-only.

7. **Three live defects to fix or accept, surfaced by this review, independent of the app.**
   *Recommended default:* **fix all three.** (a) Approval **policy currency is hardcoded `"TZS"`** at create (`ApprovalPolicyServiceImpl.java:76`) and the matcher never converts — a USD 5,000 PO is matched against TZS bands, falls in the lowest, and frequently **auto-approves**. (b) `effectiveFrom`/`effectiveTo` on approval policies are **dead fields** — columns exist (`V18__approvals_engine.sql:19-20`), the entity declares them (`ApprovalPolicy.java:71`, `:76`), the DTO returns them (`ApprovalPolicyDto.java:24-25`), but neither create nor update accepts them and no query filters on them, so a manager cannot set or rely on a validity window. (c) The notification catalogue's `audience_permission` for `APPROVAL_PENDING` is the **phantom code `PURCHASE.PO.APPROVE`** (`V21__notifications.sql:226` and `NotificationTypeSeeder.java`), which exists nowhere in the permission seed — so that notification's audience resolves to nobody. All three look far worse on a phone.

8. **Approvals v1 covers purchase orders and sales orders only.**
   *Recommended default:* **Yes, and say so plainly in the app** ("Purchase and sales approvals"). Only two document types are wired end-to-end (see R14 for the three verified `submitForApproval` call sites); the web util `web/src/app/features/admin/approvals/document-type.util.ts:21-38` maps **15** codes, of which **13 are never produced** — its own header at `:9-13` admits they are "forward-mapped". Six other approval surfaces run their own state machines and will not appear. Routing them through the engine is a separate project.

9. **Sideloaded APK for the pilot, Play internal testing from phase 5.**
   *Recommended default:* **Yes.** Zero gatekeepers, shippable immediately, and it matches how OrbixPOS already ships (`dist/OrbixPOS-1.5.1+9-windows.zip`). If the answer is "if it's not in the store it isn't an app", the decision holds but iOS moves onto the critical path and the calendar goes past 19 weeks — worth saying before starting.

10. **English only for v1; Swahili deferred.**
    *Recommended default:* **Yes**, with `gen_l10n` and an `app_en.arb` wired from day one so retrofitting later costs an hour instead of a week. The chart of accounts, document types, supplier names and TRA correspondence are all English; half-translated screens scan worse than untranslated ones. **Number and date formatting is done properly regardless** — pinned to comma-thousands/dot-decimal and `Africa/Dar_es_Salaam`, never the device locale, because the owner compares the phone against his accountant's report character-for-character.