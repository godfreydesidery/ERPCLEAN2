## 1 · Naming

### Before the names — four facts from the repo that constrain the choice

**A. The "indigo `#4f46e5`" in the brief is not what ships — and the family is already *four* blues, not three.**

| Where | Token | Value | Status |
|---|---|---|---|
| `pos_app/lib/app/theme.dart:24` | `AppColors.brand` | **`#1B6FD1`** (dark `#155BAC` :25) | shipped, OrbixPOS |
| `web/src/styles.scss:22` | `--erp-primary` (aliased `--orbix-primary` :57) | **`#2563eb`** | shipped, web client |
| `web/public/favicon.svg:2` | icon field | **`#0d6efd`** (Bootstrap default blue) | shipped, web favicon |
| `pos-ui-prototype/styles.css:11` | `--brand` | `#4f46e5` | **unshipped** HTML mock only |

The brief's indigo appears in exactly one file, and that file ships to nobody. Note also that the web client's *favicon* (`#0d6efd`) does not match the web client's own CSS token (`#2563eb`) — the family's colour drift is already internal to one app. The new app's colour must therefore be chosen against `#1B6FD1` and `#2563eb`, not against indigo, and this is the moment to stop the drift rather than add to it.

**B. The family mark already exists and is trivially extensible.** `web/public/favicon.svg` is a 64×64 rounded square (`rx="14"`, a 22% radius) on a flat field, with one white bold letter centred (`fill="#ffffff"`, `font-weight="700"`, lines 3–4). That is the entire visual system: *same silhouette, same white glyph, different field colour, different glyph.* Every icon direction below inherits it deliberately. (The SVG's own `aria-label` is `"ORBIX ERP"` — see fact D.)

**C. `com.orbix.*` does not exist anywhere in this repo.** A grep for `com\.orbix` across `backend/src`, `web/src`, `pos_app/`, `docs/` and `dist/bundle/` returns nothing. What ships today is `namespace = "net.otapp.pos_app"` (`pos_app/android/app/build.gradle.kts:9`) and `applicationId = "net.otapp.pos_app"` (`:24`), both still carrying Flutter's stock `// TODO: Specify your own unique Application ID` comment; `android:label="pos_app"` (`pos_app/android/app/src/main/AndroidManifest.xml:3`); the release build is still signed with the debug keystore (`build.gradle.kts:37`, under its own `// TODO: Add your own signing config`); and there is **no `ios/` directory** in `pos_app/`. No `.apk` or `.aab` exists anywhere in `dist/` or `pos_app/dist/`. **So the mobile namespace is greenfield and free to set correctly, once — see the bundle-id warning in §1.5.**

**D. The product name "OrbixPOS" is in Dart source but has never reached a platform manifest — and the web client *does* have a product name.** `OrbixPOS` appears at `pos_app/lib/app/app.dart:17` (`title: 'OrbixPOS'`, the `MaterialApp` title) and as the class `OrbixPosApp` (`:11`), plus the release artefact names (`dist/OrbixPOS-1.5.1+9-windows.zip`, `pos_app/dist/OrbixPOS-1.3.0-win-x64.zip`). It appears in **no** Android manifest, Gradle id, or pubspec value (`pos_app/pubspec.yaml:1` is `name: pos_app`). Separately, the Angular client **is** named: `web/src/index.html:5` is `<title>ORBIX ERP</title>`. This matters for the objection in §1.3.

---

### 1.1 The eight candidates

Each entry: positioning · family fit · store display name · bundle id · tagline · colour + icon. All bundle ids below are **(PROPOSED)** — none exists in the repo (fact C).

---

**1 · OrbixHQ**
*The head office in your pocket — the group's numbers and your signature, wherever you are.*
Family fit: same `Orbix` + short all-caps suffix rhythm as `OrbixERP` and `OrbixPOS`; it is the only candidate that matches their **length and shape**, so a screenshot of three icons reads as one product line rather than a product plus two spin-offs.
Store name: **OrbixHQ** · Bundle: `com.orbix.hq` *(PROPOSED — see §1.5)* · Tagline: **"Your group, at a glance."**
Colour/icon: field **deep navy `#0B2E5C`** — a darkened relative of the POS pay-key navy `#00296B` (`theme.dart:29`), so it is provably in-palette but far darker than the POS blue at icon size. Glyph: white **"H"** in the same weight as the ERP "O", or a two-bar "roof" mark. On a home screen: POS = mid blue, HQ = near-black navy. White-on-`#0B2E5C` computes to **≈13.5:1**, comfortably above the 4.5:1 WCAG 2.1 AA text threshold CLAUDE.md commits to.

---

**2 · OrbixBoard**
*The boardroom view — what the directors ask for, on the phone.*
Family fit: "Board" is the audience (owners, directors) **and** the artefact (a dashboard). Both readings are correct, which is rare.
Store name: **OrbixBoard** · Bundle: `com.orbix.board` *(PROPOSED)* · Tagline: **"The board meeting that fits in your hand."**
Colour/icon: field **slate-navy `#1E293B`** — near-identical to the shipped `--erp-sidebar #1f2937` (`web/src/styles.scss:43`), so it reads as the app-shell chrome colour. White glyph ≈14.6:1. Glyph: three white horizontal bars of decreasing length — reads as a league table.
Watch-out: "board" collides with kanban boards (Trello/Jira mental model) and, at 10 characters, is joint-longest but one.

---

**3 · OrbixPulse**
*The vital signs of the business, checked in ten seconds.*
Family fit: sits beside a transactional app (POS) as its opposite — POS records, Pulse reads. Good pairing story.
Store name: **OrbixPulse** · Bundle: `com.orbix.pulse` *(PROPOSED)* · Tagline: **"Know before you're told."**
Colour/icon: field **teal `#0F766E`** — the largest hue jump in the set while staying in the cool half of the palette; white glyph ≈5.5:1, passes AA but is the tightest in the set. Glyph: a single white ECG spike. Distinct at a glance; arguably *too* distinct — it stops reading as a sibling.
Watch-out: "Pulse" is one of the most reused names in enterprise software. The `Orbix` prefix carries all the distinctiveness. *(Crowdedness is an external-market observation — UNVERIFIED against any register; see §1.4.)*

---

**4 · OrbixExec**
*Built for the people who sign, not the people who key in.*
Family fit: perfectly literal. It also matches the working directory name `mobile_exec/` used in the platform section of this plan — **note that `mobile_exec/` does not exist in the repo** *(PROPOSED, plan-internal only)*.
Store name: **OrbixExec** · Bundle: `com.orbix.exec` *(PROPOSED)* · Tagline: **"For the desk you're never at."**
Colour/icon: field **graphite `#111827`** (the shipped `--erp-sidebar-deep`, `styles.scss:44`) with a **gold `#B45309`** glyph (the shipped `--erp-warn`, `styles.scss:30`, matching `theme.dart:43`).
Watch-outs, two, and the second is new: (a) "Exec" reads to a technician as *execute*, and it labels the user rather than the job, which ages badly once branch managers are issued logins — which the brief already anticipates. (b) **The gold-on-graphite pairing computes to ≈3.5:1.** That clears WCAG 2.1 AA **1.4.11 non-text contrast (3:1)** for a pure icon glyph, but fails **1.4.3 (4.5:1)** the moment the same lockup carries a wordmark or is reused as a header. It is the only colour pair in this set that is not safe to reuse beyond the icon.

---

**5 · OrbixBridge**
*The captain's bridge — see the whole fleet, give the order.*
Family fit: strong "command" metaphor covering both halves of the app (read consolidated reports, act on approvals) in one word.
Store name: **OrbixBridge** · Bundle: `com.orbix.bridge` *(PROPOSED)* · Tagline: **"See everything. Decide anything."**
Colour/icon: field **midnight `#0C1E3A`** (white glyph ≈16:1), glyph a white arch/span. Handsome, and clearly the same family.
Watch-out: in software, "bridge" means *integration middleware*. A prospect hearing "OrbixBridge" will reasonably assume it connects OrbixERP to something else. That is the wrong first thought for a reporting app, and a tagline does not fix it.

---

**6 · OrbixDeck**
*The flight deck of the group — every dial in one screen.*
Family fit: short (9 chars), same cadence as OrbixPOS, and "deck" carries both the cockpit and the board-deck meaning.
Store name: **OrbixDeck** · Bundle: `com.orbix.deck` *(PROPOSED)* · Tagline: **"Every branch, one screen."**
Colour/icon: field **steel `#1F3A5F`** (white glyph ≈11.4:1), glyph three white dials/dots on a horizontal rule.
Watch-out: "deck" is also *slide deck*, which invites "so it makes presentations?" Second-order confusion, but real in a sales conversation.

---

**7 · OrbixLens**
*Look at any branch, any company, any day.*
Store name: **OrbixLens** · Bundle: `com.orbix.lens` *(PROPOSED)* · Tagline: **"Focus on any branch."**
Colour/icon: field **indigo `#4338CA`** (white glyph ≈7.8:1), glyph a white ring with an offset highlight. The one candidate that could legitimately use an indigo — it is `#4f46e5`'s darker sibling and would finally give that unshipped prototype colour (`pos-ui-prototype/styles.css:11`) a home.
Watch-out: "Lens" is a shipped Kubernetes product and a crowded observability term *(external, UNVERIFIED)*; and a lens implies *cross-company drill-down*, which the backend does not have today — see the verified constraint in §1.3 reason 3. Naming the app after the feature you don't have is a trap.

---

**8 · OrbixSign**
*The approvals app — the last gate before money leaves the group.*
Family fit: names the *only write surface* the app is scoped to. That surface is real and verified: `POST /api/v1/approvals/requests/uid/{uid}/approve` and `.../reject` (`backend/src/main/java/com/erp/api/ApprovalRequestController.java:64` and `:72`, base path at `:29`), both gated `@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')` (`:65`, `:73`) — a code that is seeded (`R__seed_permissions.sql:24`).
Store name: **OrbixSign** · Bundle: `com.orbix.sign` *(PROPOSED)* · Tagline: **"Nothing waits for you unseen."**
Colour/icon: field **emerald `#065F46`** (white glyph ≈7.8:1), glyph a white tick.
Watch-out — and it is disqualifying: **this app does not do e-signatures.** "Sign" plants DocuSign/Adobe Sign expectations, sits in a heavily-trademarked category, and boxes the product into one of its tabs. Included as the honest foil, not as a contender.

---

### 1.2 Comparison

Scored 1–5. **Says-what-it-is** = would a Tanzanian group owner guess the purpose from the icon label alone. **Family** = reads as a sibling of OrbixERP/OrbixPOS. **Label** = fits an Android/iOS home-screen label without truncation (≈10–12 chars). **Clean** = free of category collisions and crowded-mark risk. **TZ read** = how it lands in Tanzanian business English / Swahili. All six columns are **editorial judgement**, not measured.

| # | Name | Chars | Says what it is | Family | Label | Clean | TZ read | Room to grow | **Total** |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **OrbixHQ** | 7 | 5 | 5 | 5 | 4 | 5 | 5 | **29** |
| 2 | **OrbixBoard** | 10 | 5 | 4 | 3 | 3 | 5 | 4 | **24** |
| 6 | OrbixDeck | 9 | 3 | 4 | 4 | 3 | 4 | 4 | 22 |
| 4 | OrbixExec | 9 | 4 | 4 | 4 | 3 | 4 | 2 | 21 |
| 3 | OrbixPulse | 10 | 4 | 3 | 3 | 2 | 4 | 4 | 20 |
| 8 | OrbixSign | 9 | 4 | 4 | 4 | 1 | 4 | 1 | 18 |
| 5 | OrbixBridge | 11 | 2 | 4 | 2 | 2 | 4 | 3 | 17 |
| 7 | OrbixLens | 9 | 2 | 3 | 4 | 2 | 3 | 3 | 17 |

*(Total is a straight sum of the six judgement columns; Chars is context, not scored. Rows are ordered by total.)*

---

### 1.3 Recommendation

## → **OrbixHQ** · bundle id per §1.5 · *"Your group, at a glance."*

Five reasons, in order of weight:

1. **It matches the family's shape, not just its prefix.** `OrbixERP` (8 chars), `OrbixPOS` (8), `OrbixHQ` (7). Two-to-three character all-caps suffix, no lowercase word. Put the three icons in a row in a proposal deck and they are obviously one product line. `OrbixBoard`, `OrbixPulse` and `OrbixBridge` break that rhythm and read as add-ons.
2. **"HQ" is exactly what the app is, in the audience's own vocabulary.** The audience is a group owner across multiple companies and branches who wants the head-office view without being at head office. "HQ" is universal Tanzanian business English — no translation, no explanation, no jargon.
3. **It names the *vantage point*, not a feature or a user type — and that matters, because the backend cannot yet deliver the feature two rivals promise.** Verified: there is **no consolidated / group-level report endpoint in the codebase** (no match for `consolidat` anywhere under `backend/src/main/java`, across all 140 controllers in `backend/src/main/java/com/erp/api/`). Every report takes a single company: `ScopeGuard.canActIn` ends at `return principal.root() || companyId.equals(principal.companyId());` (`backend/src/main/java/com/erp/platform/security/ScopeGuard.java:675`), with root itself now fenced to its own organisation by the `isForeignTenant` check immediately above (`:671`, ADR-0062). The comment at `ScopeGuard.java:657-659` states the shape plainly: *"89 controllers take `@RequestParam Long companyId` straight from the caller, and every one of them reaches here."* So a non-root director is pinned to one active company, and cross-company aggregation is unbuilt work, not a wiring exercise. Branch-level GL slicing is separately and explicitly fenced: ADR-0037 lists **"Branch-scoped GL KPIs"** under its non-goals — *"no GL aggregate filters `branch_id` … needs new branch-predicated repo methods **and** a branch-authorization decision — its own ADR."* `OrbixLens` names precisely that missing capability; `OrbixSign` dies when the second tab ships; `OrbixExec` gets awkward the day a branch manager is issued a login. `OrbixHQ` survives all three roadmap outcomes.
4. **It is the shortest label on the list.** 7 characters never truncates on any launcher, at any font scale — and WCAG 2.1 AA SC 1.4.4 requires text to remain usable at 200% resize, where long labels are the first casualty.
5. **The navy field is the safest sibling colour.** `#0B2E5C` is a direct darkening of the shipped POS pay-key navy `#00296B` (`pos_app/lib/app/theme.dart:29`), so it is provably in-palette, while sitting far enough from POS `#1B6FD1` and web `#2563eb` / `#0d6efd` to separate at icon size. White-on-`#0B2E5C` ≈13.5:1.

**The one honest objection, corrected:** "HQ" could be misread as *the admin console for head office* — and the Angular client is exactly that, **and it already carries a product name**: `web/src/index.html:5` is `<title>ORBIX ERP</title>`, echoed by `web/public/favicon.svg:1` (`aria-label="ORBIX ERP"`). So there **is** something to collide with; the earlier claim that the web client is unnamed was wrong. The collision is nonetheless survivable, because the two names differ in the suffix, which is the part users read: *ORBIX ERP* (the desk system) vs *OrbixHQ* (the phone). Mitigation is a store subtitle that draws the line explicitly — *"Reports and approvals for owners and directors"* — plus **a house-style decision to normalise the desk client's title to `OrbixERP` (one word, matching `OrbixPOS` and the `dist/build-release.sh` usage, which already writes "OrbixERP") rather than leaving `ORBIX ERP` spaced and shouted.** That is a one-line change to `web/src/index.html:5` and should ride the same ADR.

### Runner-up: **OrbixBoard**

Take it if the owner reacts to "HQ" as too corporate-American, or if the framing "what the directors see" matters more than "where the numbers come from". It is the only other candidate whose two meanings — the board of directors, and a dashboard — are *both* accurate, and its `#1E293B` field is the closest thing in the set to a shipped token (`--erp-sidebar #1f2937`). Its costs are real but small: 10 characters, and a kanban-board second reading.

**Rejected outright: OrbixSign** — the name promises e-signatures the product does not have, in a trademark-dense category, and it permanently shrinks the product to one surface.

---

### 1.4 Risk note

**Trademark / genericness.** *(Everything in this paragraph is an external-register matter and is UNVERIFIED against any register — no search has been run.)* "Orbix" is the distinctive element and it already carries two products; every candidate here is `Orbix` + a descriptive suffix, which is the strongest structure available — the suffix stays descriptive (weak alone, low-conflict) while the composite is protectable through the house mark. `HQ`, `Board`, `Deck` and `Exec` are descriptive and effectively unregistrable alone; `Pulse`, `Lens` and `Sign` are crowded in software and I would not want the family's second app leaning on one. Two actions before any store submission, neither of which blocks development: (a) a knock-out search for "Orbix" in Nice class 9/42 at BRELA (Tanzania) and on the Madrid/EUIPO/USPTO registers — run once for the *family*, not per app; (b) note that `dist/bundle/LICENSE.txt:2` still carries the banner **"TEMPLATE — NOT YET A LICENCE. REPLACE BEFORE SHIPPING TO ANY CLIENT."** (repeated verbatim in every `dist/release/orbixerp-*/LICENSE.txt`) — the same lawyer pass should cover the trade-mark position. Do not let a naming decision wait on it; do not let a public store listing go out ahead of it.

**Swahili / Tanzanian reading.** *(Editorial judgement, not repo-verifiable.)* None of the eight collides with a Swahili word or an unfortunate homophone: *HQ* is read letter-by-letter and is standard local business usage; *bodi* (board), *pulse/pulsi*, *deki* (deck) and *daraja* (bridge) all sit neutrally. "Orbix" has no Swahili meaning and no awkward phonetics — the `-x` ending is read as in English. **Two things deliberately not proposed:** a Swahili-language name (e.g. *Mkuu*, "chief/head") — the chart of accounts, document types, supplier names and TRA correspondence are all in English, and a Swahili shell over English figures reads as *less* coherent; and **OrbixPilot**, because "pilot" in a client conversation means *trial version*.

**One more, for the owner specifically:** everything above assumes a single app name for every client install. Do not let this become `OrbixHQ – Client A` / `OrbixHQ – Client B`. Apple's App Store Review Guideline 4.3 (Design – Spam) rejects that pattern *(external, UNVERIFIED against Apple's current text)*, and it is the same one-binary-runtime-config constraint the plan's platform section already commits to.

---

### 1.5 ⚠ Bundle id — an owner decision, and it is irreversible

The brief specifies `com.orbix.*`. Two things to weigh before that is set in stone, because **an Android `applicationId` and an iOS bundle id can never be changed after the first store publish** — a change is a new listing and a fresh install for every user:

- Reverse-DNS convention says the id should be a domain the owner controls. Today's code uses `net.otapp.pos_app` (`pos_app/android/app/build.gradle.kts:24`, namespace at `:9`), i.e. **otapp.net**. **Whether the owner holds `orbix.com` / `orbix.co.tz` is not determinable from this repo (UNVERIFIED) — it is an owner question.** If he does not, `com.orbix.hq` is a claim on someone else's namespace: harmless technically, sloppy legally, and it will be noticed if a trade-mark dispute ever arises.
- Recommendation: **`net.otapp.orbix.hq`**, with `net.otapp.orbix.pos` and `net.otapp.orbix.erp` reserved for the siblings. Use `com.orbix.hq` only if the owner confirms he holds an `orbix.*` domain.
- **The window is open right now and it closes on first publish.** Verified: no `.apk` or `.aab` exists in `dist/` or `pos_app/dist/` (only Windows zips — `OrbixPOS-1.4.0+6` through `1.5.1+9`); the release build is still on the debug signing config (`build.gradle.kts:37`); and there is no `ios/` directory under `pos_app/`. So renaming `pos_app`'s id in the same pass as the extraction PR costs nothing today. In six months it costs a migration.

---

### 1.6 Internal names — the ones that go into the repo

**Nothing in this table exists in the repo yet — every row is (PROPOSED).** Verified absent: no `packages/` directory, no `mobile_exec/` directory, no `orbix-hq/` directory, no `EXEC.*` permission code, no deep-link scheme, and no ADR `0063`.

| Thing | Value | Note |
|---|---|---|
| **Monorepo directory** (kebab-case, as asked) | **`orbix-hq/`** | Sits beside `backend/`, `web/`, `pos_app/`, `dist/`, `infra/`, `e2e/` — all six verified present at the repo root. Supersedes the working name `mobile_exec/` used in the plan's platform section (which exists nowhere in the repo). Flutter does not care about the directory name — only the pubspec `name:` must be snake_case — so kebab is free here. Optional tidy-up, low priority: rename `pos_app/` → `orbix-pos/` in the same pass; it touches the POS build scripts, so do it *after* the extraction PR is green, never during. |
| **Flutter package name** (`pubspec.yaml` `name:`) | **`orbix_hq`** | Dart requires `lowercase_with_underscores`; the sibling is `name: pos_app` (`pos_app/pubspec.yaml:1`). Imports read `package:orbix_hq/...`. |
| **Shared package** | **`orbix_erp_client`** at `packages/orbix_erp_client/` | Per the plan's platform section — deliberately *not* named after either app, since both consume it. `publish_to: 'none'`, matching `pos_app/pubspec.yaml:5`. `packages/` does not exist yet. |
| **Android `applicationId` / iOS bundle id** | `net.otapp.orbix.hq` (recommended) or `com.orbix.hq` | See §1.5 — settle before the first signed build, and set a real release signing config at the same time (the POS one is still `signingConfigs.getByName("debug")`). |
| **Android `android:label`** | `OrbixHQ` | Replaces the placeholder pattern `android:label="pos_app"` (`pos_app/android/app/src/main/AndroidManifest.xml:3`). Also set the Flutter `MaterialApp` `title:` to `OrbixHQ`, mirroring `pos_app/lib/app/app.dart:17`. |
| **Release artefact** | `OrbixHQ-<version>-android.apk` in `dist/` | Matches the existing convention `dist/OrbixPOS-1.5.1+9-windows.zip`. |
| **Deep-link scheme** | `orbixhq://` | Targets the `link_route` column the notifications module already stores (`Notification.linkRoute`, `backend/.../notifications/domain/entity/Notification.java:52-53`; column `link_route VARCHAR(200)` at `V21__notifications.sql:64`). The `APPROVAL_PENDING` type already carries the template **`/approvals/{sourceUid}`** (`NotificationTypeSeeder.java:73`; seeded at `V21__notifications.sql:232`). **Sharp edge worth carrying into the approvals section:** that notification type is gated on **`PURCHASE.PO.APPROVE`**, not `APPROVALS.DECIDE` (`NotificationTypeSeeder.java:69`, `V21__notifications.sql:226`) — so a Finance Director holding `APPROVALS.DECIDE` will not necessarily receive it. Client-side deep-link handling and the push track are entirely unbuilt. |
| **Permission-code prefix** | `EXEC.*` — e.g. `EXEC.BRIEF.VIEW` | Verified: **no `EXEC` code exists** in `R__seed_permissions.sql` today. Keep the prefix **functional (`EXEC`), not brand-based (`HQ`)**, so a rename never touches the seed. Any new code must be added to `R__seed_permissions.sql` *and* granted to the intended roles there (the file's existing shape: codes at lines 23–27 for `APPROVALS.*`, grants at 633+), or non-root users break invisibly and CI will not catch it. Note the shipped gating idiom is `@PreAuthorize("@perm.has('CODE')")` / `@perm.scoped(#uid,'<type>','CODE')` (e.g. `ApprovalRequestController.java:40`, `:65`), not the `hasPermission(...)` form CLAUDE.md's prose shows. |
| **ADR** | `docs/decisions/0063-executive-mobile-app.md` | Verified next free number — the highest numbered ADR today is `0062-organisation-as-tenant-multitenancy.md`. Record the app name, the bundle-id namespace decision, the colour token, and the `ORBIX ERP` → `OrbixERP` title normalisation, so this section stops being a document and becomes a decision. |