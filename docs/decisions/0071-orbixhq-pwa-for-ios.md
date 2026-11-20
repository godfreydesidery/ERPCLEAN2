# 0071 — OrbixHQ as a PWA ("Orbix App") for iPhone users

- **Status:** Proposed (2026-11-20), pending owner review of PR. The direction is the owner's
  (2026-11-20): "there are clients who are using iPhones so we want them to use PWA, since we do not
  want to have an iOS application"; "we already have the domain and will do the necessary
  configuration"; "we should only touch PWA, to avoid breaking others".
- **Builds on / revisits:** the 2026-08-18 platform decision for the executive app (Flutter, not a
  PWA), which named its own trigger for re-opening: *a real domain + a publicly trusted certificate*.
  That trigger is now met for the shared instance (`app.orbixsystems.app`, Cloudflare).
  ADR-0069 D-9 (`X-Client-App: orbixhq` gates the OrbixHQ add-on).
- **Scope:** the shared instance at 51.20.154.248 only. Android keeps the APKs. Self-hosted LAN
  installs are unaffected.

## Context

OrbixHQ (`mobile_exec/`) is a Flutter app shipped as Android APKs per brand. Clients with iPhones
have no way to use it, and the owner does not want a native iOS app: no Apple developer account,
no App Review, no second store channel.

In August the PWA lost to Flutter on a single point, TLS. The estate was bare IPs with self-signed
or plain-HTTP endpoints, and Chrome and Safari refuse service workers there. With a domain behind
Cloudflare that blocker is gone for the shared instance.

## Decision

1. **Same codebase, web target.** Build the *existing* Flutter app for the web, from a separate
   entry point `lib/main_web.dart`. Do not rewrite it in React or anything else. `main.dart`, the
   Android flavors, `build-hq.ps1` and the APKs stay unchanged. The only shared-code change is
   `report_share.dart`: its file writing moved behind a conditional import, and the phone path
   writes the same bytes to the same place.
2. **Same origin.** IIS serves the static build at `https://app.orbixsystems.app` and
   reverse-proxies `/api/*` to the ERP on `127.0.0.1:8081`. That means no CORS, no mixed content,
   and no backend change. The ERP service is not touched. On first launch the app takes its server
   address from the page origin.
3. **Custom service worker, not Flutter's.** Flutter has deprecated its generated worker
   (flutter#156910). `web/sw.js` precaches the app shell into one cache per build, named
   `orbix-app-<version>-<content hash>`. It runtime-caches the CanvasKit variant the browser uses,
   and **never** touches `/api/`, non-GET requests or other origins. A new deploy *waits*; the
   user applies it from an "Update now" banner. There is no auto-reload, so unsaved work is never
   lost.
4. **No third-party hosts at runtime.** CanvasKit, Roboto and pdf.js are served from the site
   itself, so the CSP is `'self'`-only and the app shell works offline.
5. **Brand:** "Orbix App" / "Orbix", Orbix teal with a white "O", the same mark as the ERP web
   favicon. Customer-branded PWAs are out of scope: one host serves one brand.
6. **Same server-side identity as the APK** (`X-Client-App: orbixhq`). Subscription gating applies
   unchanged.

## Consequences

- iPhone users get OrbixHQ with no App Store: Safari → Share → Add to Home Screen.
- Each additional server that should offer the PWA needs its own hostname, certificate and IIS
  site. This ADR does not provide a PWA for LAN installs.
- Offline means the app *opens* and says so. Data is never cached, by design (no stale financial
  figures, no queued transactions).
- In a browser, tokens live in the site's local storage, mitigated by the strict CSP. The CSP keeps
  `'unsafe-eval'` because `package:printing` calls `window.eval`.
- PWA sign-ins appear in the audit log from `127.0.0.1` until the backend honours forwarded
  headers. That is a separate decision.
- The August decision stands for Android (the APKs remain the primary Android channel). This ADR
  adds iOS and desktop through the browser; it does not replace the APKs.

Deployment: `WINDOWS_EC2_DEPLOYMENT.md`. Build: `dist/build-pwa.ps1`. Checks:
`mobile_exec/test/pwa_test.dart` (unit/widget) and `mobile_exec/pwa_test/` (Playwright).
