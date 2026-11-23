# Orbix App (PWA) — Windows EC2 / IIS deployment

How to put **Orbix App** — OrbixHQ as an installable web app — on
**https://app.orbixsystems.app**, served by IIS on the Windows Server EC2
instance at Elastic IP **51.20.154.248**, next to the ERP that already runs there
on port **8081**.

> **The ERP service on :8081 is not changed.** This guide adds a *separate* IIS
> website with its own hostname binding. IIS forwards `/api/*` to the ERP over the
> loopback interface (`127.0.0.1:8081`), which is the path the ERP already listens on.
> Nothing in this guide stops, restarts, reconfigures or re-binds the ERP. The POS
> tills and Android OrbixHQ apps keep using `http://51.20.154.248:8081` exactly as
> before.

---

## 1. What gets deployed

| | |
|---|---|
| Artefact | `dist\orbixapp\OrbixApp-<version>.zip` (built by `dist\build-pwa.cmd`) |
| Contents | static files only — `index.html`, `main.dart.js`, CanvasKit engine, icons, `manifest.webmanifest`, `sw.js` (service worker), `offline.html`, `web.config` |
| Server-side code | none. The app talks to the ERP API that is already running. |
| Secrets in the build | none. No passwords, keys or server addresses are compiled in; the app calls `/api/v1` on its own origin. |

### How a request flows

```
iPhone / Android / Windows browser
        │  https://app.orbixsystems.app
        ▼
Cloudflare (DNS + TLS, proxied)  ──►  EC2 51.20.154.248 :443
                                           │
                                     IIS site "Orbix App"  (host binding app.orbixsystems.app)
                                           ├─ /api/*  ──► http://127.0.0.1:8081/api/*   (ERP, unchanged)
                                           ├─ static files from C:\inetpub\orbix-app\current
                                           └─ any other path ──► index.html  (app routes)
```

The browser therefore sees **one HTTPS origin**:
- no mixed content (an HTTPS page cannot call `http://…:8081`)
- no CORS
- iOS gets the HTTPS it requires before it treats the site as an installable app

---

## 2. Prerequisites (one-time)

| Item | Requirement |
|---|---|
| Windows Server | 2016 or later (IIS 10). |
| IIS role | Web Server with Static Content, Default Document, HTTP Errors, Static + Dynamic Compression, Management Console. |
| IIS modules | **URL Rewrite 2.1** and **Application Request Routing (ARR) 3.0** — both free Microsoft downloads (iis.net). |
| Ports | 80 and 443 free on the server (checked in step 3.1), open in the EC2 security group and Windows Firewall. |
| DNS | Cloudflare manages `orbixsystems.app`. |
| Certificate | A Cloudflare Origin Certificate (recommended, step 5) **or** a Let's Encrypt certificate (step 5, option B). |
| ERP | Already running on `:8081` on the same machine (`curl http://127.0.0.1:8081/api/v1/auth/me` answers 401). |

---

## 3. Prepare the server (one-time)

Run in an **elevated** PowerShell on the EC2 instance.

### 3.1 Look before touching anything

```powershell
# What already listens on 80/443? Must be nothing, or IIS itself.
Get-NetTCPConnection -LocalPort 80,443 -State Listen -ErrorAction SilentlyContinue |
    Select-Object LocalPort, OwningProcess, @{n='Process';e={(Get-Process -Id $_.OwningProcess).Name}}

# The ERP is up on loopback (expect an HTTP 401 - that is fine, it means "answering").
try { Invoke-WebRequest http://127.0.0.1:8081/api/v1/auth/me -UseBasicParsing } catch { $_.Exception.Response.StatusCode }
```

If something other than IIS (`System` / `w3wp`) already holds 443, **stop
here** and decide with the owner. Do not stop or reconfigure that service as
part of this deployment.

### 3.2 Install IIS (skip what is already installed)

```powershell
Install-WindowsFeature Web-Server, Web-Static-Content, Web-Default-Doc, Web-Http-Errors, `
    Web-Stat-Compression, Web-Dyn-Compression, Web-Mgmt-Console -IncludeManagementTools
```

### 3.3 Install URL Rewrite and ARR

Download and run, in this order:
1. URL Rewrite 2.1 (x64): https://www.iis.net/downloads/microsoft/url-rewrite
2. Application Request Routing 3.0 (x64): https://www.iis.net/downloads/microsoft/application-request-routing

### 3.4 Turn on ARR's proxy (server level)

The proxy only forwards traffic for sites that have a rewrite rule pointing at it.
In this setup that is only the Orbix App site's `/api/` rule.

```powershell
Import-Module WebAdministration
Set-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' -Filter 'system.webServer/proxy' -Name enabled -Value True
# Reports can take a while; allow 2 minutes (Cloudflare itself stops at 100 s).
Set-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' -Filter 'system.webServer/proxy' -Name timeout -Value '00:02:00'
# Keep Location/redirect headers from the ERP pointing at the public host.
Set-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' -Filter 'system.webServer/proxy' -Name reverseRewriteHostInResponseHeaders -Value True
```

### 3.5 Firewall and EC2 security group

```powershell
New-NetFirewallRule -DisplayName 'Orbix App HTTP/HTTPS' -Direction Inbound -Protocol TCP `
    -LocalPort 80,443 -Action Allow
```

In the AWS console → EC2 → the instance's security group → inbound rules:
- allow TCP 443 (and 80) from `0.0.0.0/0`
- **better:** allow them only from Cloudflare's IP ranges (https://www.cloudflare.com/ips/), since all traffic arrives through Cloudflare

Leave the existing 8081 rule as it is; the tills use it.

### 3.6 Create the site

```powershell
Import-Module WebAdministration
$root = 'C:\inetpub\orbix-app'
New-Item -ItemType Directory -Force "$root\releases" | Out-Null

# Static site: no .NET in the pool.
New-WebAppPool -Name 'OrbixApp'
Set-ItemProperty 'IIS:\AppPools\OrbixApp' -Name managedRuntimeVersion -Value ''

# Placeholder folder until the first release is unpacked (step 4).
New-Item -ItemType Directory -Force "$root\releases\empty" | Out-Null
New-Website -Name 'Orbix App' -PhysicalPath "$root\releases\empty" -ApplicationPool 'OrbixApp' `
    -HostHeader 'app.orbixsystems.app' -Port 80
```

The site binds **only** to the host name `app.orbixsystems.app`. Other
sites, including the Default Web Site, are not changed: IIS routes by host
name, and a host-specific binding wins over a catch-all one.

The HTTPS binding is added in step 5, once the certificate is in place.

---

## 4. Build and deploy a release

### 4.1 Build (maintainer machine)

Double-click `dist\build-pwa.cmd`, or:

```powershell
.\dist\build-pwa.ps1            # runs flutter test, builds, stamps sw.js, zips
```

Output: `dist\orbixapp\OrbixApp-<version>.zip` plus a `.json` record beside it
(version, commit, SHA-256). The script refuses to overwrite an existing zip of
the same version unless you pass `-Force`. Bump `version:` in
`mobile_exec/pubspec.yaml` (and `lib/app/version.dart`) for a new release.

### 4.2 Deploy (server)

Each release goes into its own folder; the site is pointed at it. Rolling back
means pointing it at the previous folder.

**With the script (recommended).** Copy `dist\deploy-pwa-iis.ps1` **and**
`dist\deploy-pwa-iis.cmd` to `C:\deploy\` on the server once (`Unblock-File`
them if Windows marks them as downloaded). For each release, copy
`OrbixApp-<version>.zip` **and** its `OrbixApp-<version>.json` into
`C:\deploy\`, then **double-click `deploy-pwa-iis.cmd`**: it asks for
administrator rights and offers 1 Deploy / 2 List / 3 Roll back, and keeps the
window open to read the result. Or, in an Administrator PowerShell:

```powershell
C:\deploy\deploy-pwa-iis.ps1               # newest zip in C:\deploy
C:\deploy\deploy-pwa-iis.ps1 -List         # releases on the server, * = live
C:\deploy\deploy-pwa-iis.ps1 -Rollback     # back to the previous release
```

It checks the zip against the SHA-256 in the `.json`, unpacks it into
`releases\<version>`, refuses a zip without `index.html`, points the site at
it, keeps the newest five releases, and confirms `/sw.js` now serves the new
version. Nothing in it changes per release.

**By hand,** the same thing:

```powershell
Import-Module WebAdministration
$root = 'C:\inetpub\orbix-app'
$ver  = '1.4.3-10'                                  # match the zip name
Expand-Archive "C:\deploy\OrbixApp-$ver.zip" -DestinationPath "$root\releases\$ver" -Force
Set-ItemProperty 'IIS:\Sites\Orbix App' -Name physicalPath -Value "$root\releases\$ver"
```

You do not need to restart IIS or recycle the pool for static files. Users get
the new version through the in-app **"A new version of Orbix App is ready —
Update now"** banner the next time the app checks for updates. It checks on
launch, when the app is brought back to the foreground, when it comes back
online, and every 30 minutes. The new version never installs over a session on
its own.

**Rollback:** point `physicalPath` back at the previous release folder. Its
`sw.js` differs from the current one, so browsers treat it as a new version and
show the same update banner.

---

## 5. HTTPS / SSL

iOS only treats a site as an installable web app when it is served over
HTTPS. Service workers (offline mode, install) also require HTTPS on every
platform.

### Option A — recommended: Cloudflare proxy + Cloudflare Origin Certificate

1. Cloudflare dashboard → `orbixsystems.app` → **SSL/TLS → Origin Server →
   Create Certificate**. Choose RSA, hostnames `app.orbixsystems.app` (or
   `*.orbixsystems.app`) and a validity of 15 years. Save the **certificate** as
   `origin.pem` and the **private key** as `origin.key`. The key is shown only
   once, and it is a secret: never commit it or email it.
2. Convert to PFX. On any machine with Git for Windows, in Git Bash:
   ```bash
   openssl pkcs12 -export -out orbix-app-origin.pfx -inkey origin.key -in origin.pem
   ```
   (You are asked for a PFX password.)
3. Import on the server and bind it to the site with SNI:
   ```powershell
   $pfxPass = Read-Host 'PFX password' -AsSecureString
   $cert = Import-PfxCertificate -FilePath C:\deploy\orbix-app-origin.pfx `
       -CertStoreLocation Cert:\LocalMachine\My -Password $pfxPass
   New-WebBinding -Name 'Orbix App' -Protocol https -Port 443 -HostHeader 'app.orbixsystems.app' -SslFlags 1
   (Get-WebBinding -Name 'Orbix App' -Protocol https).AddSslCertificate($cert.Thumbprint, 'My')
   Remove-Item C:\deploy\orbix-app-origin.pfx, C:\deploy\origin.key -ErrorAction SilentlyContinue
   ```
4. Cloudflare → **SSL/TLS → Overview → Full (strict)**. Never use
   "Flexible": the site redirects HTTP to HTTPS, so Flexible mode causes a
   redirect loop.

The certificate phones see is Cloudflare's edge certificate, which is publicly
trusted, so iPhones, Android and Windows accept it with no setup. The origin
certificate is trusted by Cloudflare only. **Keep the DNS record proxied (orange
cloud).** If it is switched to "DNS only", browsers would see the origin
certificate and reject it; use option B in that case.

### Option B — DNS only (grey cloud) + Let's Encrypt

Use **win-acme** (https://www.win-acme.com/). It reads the IIS bindings, issues a
Let's Encrypt certificate for `app.orbixsystems.app`, binds it to the
`Orbix App` site, and installs a scheduled task that renews it every 60 days.
HTTP validation needs port 80 reachable from the internet. With Cloudflare
proxying, use its DNS-01 Cloudflare plugin with an API token limited to
Zone:DNS:Edit for this zone.

### Check

```powershell
Get-WebBinding -Name 'Orbix App'          # expect http :80 and https :443, both host app.orbixsystems.app, https SslFlags=1
```

---

## 6. Cloudflare configuration

| Setting | Value |
|---|---|
| **DNS** | `A` record, name `app`, content `51.20.154.248`, **Proxied** (orange cloud), TTL Auto |
| SSL/TLS mode | **Full (strict)** |
| Edge Certificates → Always Use HTTPS | On |
| Edge Certificates → Minimum TLS version | 1.2 |
| Edge Certificates → HSTS | Optional. The site already sends `Strict-Transport-Security: max-age=31536000`. |
| Speed → **Rocket Loader** | **Off** for this host. It rewrites `<script>` tags and breaks Flutter's loader and the CSP. |
| Scrape Shield → Email Address Obfuscation | Off for this host. It injects a script, which the CSP blocks. |
| Caching → Cache Rules | Add a rule *"Hostname equals app.orbixsystems.app AND (URI path starts with /api/ OR URI path equals /sw.js)" → **Bypass cache***. Everything else is served with `Cache-Control: no-cache`, which Cloudflare does not cache either, so a deploy is visible immediately. |
| Network → WebSockets | Not needed. |

Use a Configuration Rule scoped to `app.orbixsystems.app` for the Rocket
Loader and email-obfuscation settings, so other hosts in the zone are not
affected.

Notes:
- Cloudflare gives up on an origin response after **100 seconds** (error 524).
  A report that runs longer than that through the PWA fails, and the user gets
  the app's retry message.
- Requests reach IIS from Cloudflare's IP addresses. IIS forwards them to the
  ERP from `127.0.0.1`. See section 10 for what that means for audit IPs.

---

## 7. Verify the deployment

From any machine:

```powershell
$h = 'https://app.orbixsystems.app'
(Invoke-WebRequest "$h/" -UseBasicParsing).StatusCode                                   # 200
(Invoke-WebRequest "$h/manifest.webmanifest" -UseBasicParsing).Headers['Content-Type']  # application/manifest+json
(Invoke-WebRequest "$h/sw.js" -UseBasicParsing).Headers['Content-Type']                 # text/javascript; charset=utf-8
(Invoke-WebRequest "$h/canvaskit/canvaskit.wasm" -UseBasicParsing -Method Head).Headers['Content-Type']  # application/wasm
(Invoke-WebRequest "$h/some/app/route" -UseBasicParsing).StatusCode                     # 200 (app shell)
try { Invoke-WebRequest "$h/web.config" -UseBasicParsing } catch { $_.Exception.Response.StatusCode }     # NotFound
try { Invoke-WebRequest "$h/api/v1/auth/me" -UseBasicParsing } catch { $_.Exception.Response.StatusCode } # Unauthorized = proxy reaches the ERP
(Invoke-WebRequest "http://app.orbixsystems.app/" -UseBasicParsing -MaximumRedirection 0 -ErrorAction SilentlyContinue).StatusCode  # 301 (or Cloudflare's redirect)
```

In desktop Chrome or Edge, open `https://app.orbixsystems.app`, then DevTools → **Application**:
- **Manifest:** name "Orbix App", icons shown, no errors
- **Service workers:** `sw.js` activated and running
- **Cache storage:** one `orbix-app-<version>` cache

Lighthouse → "Progressive Web App" should pass the installable checks.

Then sign in with a real account and open a report. That proves the `/api` proxy
end to end. An HTTP 502 means IIS cannot reach `127.0.0.1:8081`: check that the
ERP service is running.

---

## 8. Installing the app

| Platform | Browser | How |
|---|---|---|
| **Android** | Chrome (also Edge, Samsung Internet) | Open `https://app.orbixsystems.app`, tap **Install** on the "Install Orbix App" banner. Or use the browser menu ⋮ → **Install app** / **Add to Home screen**. |
| **iPhone / iPad** | **Safari** (iOS 16.4+ also allows Chrome/Edge through their share menu) | Open the site, tap **Share** (square with an arrow) → **Add to Home Screen** → **Add**. The app's banner offers **Show me how** with the same steps. |
| **Windows** | Edge or Chrome | Click the install icon at the right of the address bar, or **Install** on the banner. The app appears in the Start menu and can be pinned to the taskbar. |
| macOS | Chrome/Edge (install icon); Safari 17+ (File → Add to Dock) | |

After installing, open Orbix App from the home screen, Start menu or dock. It
runs in its own window without browser bars, shows the Orbix "O" icon and the
name **Orbix** (short name) or **Orbix App**.

If a user taps "Not now" on the install banner, it stays away for 30 days. The
browser's own menu always offers installation.

---

## 9. Running locally

```powershell
# 1. ERP API on :8081 (as usual)
cd backend; mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 2. Build the PWA
.\dist\build-pwa.ps1 -SkipTests            # quick; drop -SkipTests for a real release

# 3. Serve it like IIS does (headers, CSP, MIME, /api proxy from web.config)
cd mobile_exec\pwa_test; npm install; node serve.mjs
#    -> http://localhost:4310   (localhost counts as HTTPS for service workers)

# 4. Automated checks (Playwright, Chromium)
npx playwright test
```

For day-to-day UI work, `flutter run -d chrome -t lib/main_web.dart` also works.
In that mode the service worker is a no-op (it is unstamped), and the API must
be reachable from the page's origin.

---

## 10. Limitations and things to know

**Platform behaviour is not identical:**
- **iOS:** there is no install prompt API. Users install via Share → Add to Home
  Screen, which the app explains. The home-screen app has its own storage,
  separate from Safari: signing in inside Safari does not sign the installed app
  in, and deleting the home-screen icon signs the user out. iOS may clear an
  unused web app's cached files under storage pressure; the app then downloads
  them again on next launch, which needs a connection. Updates arrive only while
  the app is open.
- **Firefox desktop:** cannot install web apps. Orbix App still works there as a website.
- **Android:** Chrome may show its own install suggestion as well. The app's
  banner is snoozable and is not shown again once the app is installed.

**Offline is a shell, not offline data.** Offline, the app opens, shows "You are
offline" and the existing "Couldn't load — Try again" states. Sales, stock and
till figures are never cached (no API response is ever stored), and nothing can
be saved offline. That is deliberate: there are no stale financial figures and no
queued transactions. Nothing is auto-retried. The only automatic resend is the
one existing case: a request rejected with 401 is replayed once, after the token
refresh.

**Downloads:** the first visit downloads about 4 MB of app plus one CanvasKit engine
(about 6–7 MB, the variant the browser needs). Later launches load from the
device. A release re-downloads only what changed.

**Sign-in storage:** like the Android app, the session (access + refresh token)
is kept on the device. In a browser that is the site's local storage. The strict
Content-Security-Policy (only this site's own scripts; no inline scripts, no
third-party hosts) is the mitigation. Refresh tokens are rotated and expire
after 7 days.

**CSP allows `unsafe-eval`** because the PDF preview library (`printing`) calls
`window.eval` to load pdf.js. pdf.js itself is served from this site
(`/pdfjs/`), not from a CDN.

**Audit IP addresses:** the ERP sees PWA requests as coming from `127.0.0.1`,
because of the IIS proxy. Lockouts are per user, not per IP, so security is
unaffected, but audit logs show the proxy address for PWA sign-ins. Recording the
real client IP would need a backend setting (`server.forward-headers-strategy`).
That is a separate, backend change and is **not** part of this deployment.

**Subscriptions:** the PWA identifies itself exactly like the Android OrbixHQ
app (`X-Client-App: orbixhq`). Tenants whose plan lacks the OrbixHQ add-on are
refused in the browser just as on Android.

**One server:** the PWA always talks to the server that serves it. The hidden
7-tap server switch from the Android app still exists, but pointing a browser at
another host fails (CORS/mixed content). Each server that should offer the PWA
needs its own hostname and IIS site.

**Desktop layout:** the app is phone-first. On wide screens it is centred at
860 px so tiles and lists stay readable; it does not stretch to 1920 px.

**Not yet verified on real devices.** The automated checks run in Chromium. That
includes an iPhone *user-agent* run for the iOS banner logic, which does not
prove real iOS behaviour. Before announcing the app, install it on a real
iPhone (Safari), an Android phone (Chrome) and a Windows PC (Edge) against the
live site.

---

## 11. Files

| Path | What |
|---|---|
| `mobile_exec/web/` | web shell: `index.html`, `manifest.webmanifest`, `sw.js` (template), `pwa.js`, `offline.html`, `splash.css`, `flutter_bootstrap.js`, `web.config`, icons, self-hosted Roboto and pdf.js |
| `mobile_exec/lib/main_web.dart` | web entry point (wraps the unchanged OrbixHQ app) |
| `mobile_exec/lib/pwa/` | install/update/offline banners and the bridge to `pwa.js` |
| `mobile_exec/tool/pwa_icons.py` | regenerates the Orbix "O" icons into `web/` |
| `mobile_exec/pwa_test/` | IIS-like local server + Playwright checks |
| `dist/build-pwa.ps1`, `dist/build-pwa.cmd` | build + stamp + zip |
| `docs/decisions/0071-orbixhq-pwa-for-ios.md` | why a PWA for iOS, and how it fits the Flutter decision |
