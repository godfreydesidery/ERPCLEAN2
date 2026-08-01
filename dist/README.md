# Client distribution package

How a client-installable release of OrbixERP is built and handed over. **Maintainer-facing —
nothing in this file reaches a client.**

The client receives compose files, two scripts, four guides and a set of Docker image
tarballs. No `backend/`, no `web/`, no `pom.xml`, no source.

---

## Releasing an update

OrbixERP ships continuously, so this is the common case — and it is the same command as a
first release. There is no separate "update build":

```powershell
.\dist\build-release.ps1 -Version 1.2.0
```

Hand over the new zip. The client double-clicks `Setup.cmd` in it, points it at their existing
installation, and the wizard recognises the version already there and switches to update mode:
backup first, abort if the backup fails, then load, refresh, restart, and report the rollback
file. All setup questions are skipped — their database, organisation and network settings are
already configured.

The wizard does not reimplement any of that; it calls `orbixerp.ps1 update`, so the graphical
and typed routes cannot drift into behaving differently.

**Version numbers must move forward and must be unique.** The client's installer compares the
bundle's `VERSION` against their `.env`; matching versions report "nothing to update", and a
fresh install over a working system is always refused.

---

## Cutting a release

```powershell
# Windows (the usual case here)
.\dist\build-release.ps1 -Version 1.0.0
```
```bash
# Linux / macOS / git-bash
bash dist/build-release.sh --version 1.0.0
```

Add `-Arch amd64` / `--arch amd64` to build one architecture instead of both.

Output lands in `dist/release/`:

```
orbixerp-1.0.0-amd64/        the bundle
orbixerp-1.0.0-amd64.zip     hand this over
orbixerp-1.0.0-arm64/
orbixerp-1.0.0-arm64.zip
```

Send the client **one** `.zip` — the one matching their processor. On Windows they unzip it
and **double-click `Install.cmd`**; on Linux/macOS they run `./install.sh`. Nothing else is
required of them, and no internet connection is needed at any point.

### The Windows entry points

`Setup.cmd`, `Install.cmd` and `OrbixERP.cmd` exist because Windows blocks double-clicked `.ps1`
files outright (execution policy), while `.cmd` files are unrestricted — so each starts
PowerShell with the block lifted for that one script. All three must be **CRLF** (a `.cmd`
with bare LF misbehaves around labels and `goto`) and **ASCII** (a `.cmd` is read under the
machine's OEM code page); `.gitattributes` pins the first, the release build enforces both.

**`setup-wizard.ps1` is the graphical installer** — WinForms, because .NET ships inside
Windows. Nothing to install first, nothing downloaded, works with no internet at all, which
is the whole promise of this bundle. A Java GUI was considered and rejected: it would require
a JRE on the client *before* they could install anything, when the only prerequisite is
supposed to be Docker.

**Install location follows the Bitnami convention**: a dedicated top-level folder (`C:\OrbixERP`),
never Program Files. `Test-SystemLocation` refuses Program Files, Program Files (x86),
`C:\Windows` and bare drive roots outright — with no "install anyway as administrator" escape
hatch, because taking it yields a system needing admin rights forever after, for updates and
even for backups. `Test-VolatileLocation` additionally warns (but permits) Downloads, Desktop
and Temp, since the install folder holds the signing keys and backups. The payoff is that
install, update and backup all work as an ordinary user, and the whole deployment is one
copyable tree.

The wizard creates the install folder, copies the bundle into it (**excluding `images/`** —
those are loaded into Docker and are dead weight afterwards), writes `.env`, generates the
admin and database passwords with the system RNG, generates the RS256 keypair, loads the
images, starts the stack, waits for health, optionally drops a desktop shortcut, and flips
`ERP_BOOTSTRAP_ENABLED` to false once the system is genuinely up.

Long operations run through `Invoke-Tracked`, which spawns the process and polls with
`DoEvents` while streaming output into the log box. A plain synchronous call would freeze the
window for the minutes that loading images and migrating the database take, and a frozen
window reads as a crashed one.

`Install.cmd` remains for text-only, remote or scripted installs.

> **A real `setup.exe`** (Inno Setup or WiX) would give Add/Remove Programs registration and
> a signed installer. It was not done here because it needs a build-time toolchain on the
> maintainer's machine and would embed ~400 MB of image tarballs into an executable. It is a
> reasonable upgrade once the delivery process settles.

---

## Why the build is split in two

| | |
|---|---|
| `Dockerfile.build` | Compiles the Angular bundle and the Spring Boot jar. Runs **once**, natively. Produces `dist/build/app.jar`. |
| `Dockerfile.runtime` | Lands that prebuilt jar on a JRE base. Built **once per architecture**. |

`app.jar` is JVM bytecode plus static web assets — byte-for-byte identical on amd64 and
arm64. Only the JRE base image differs.

The naive alternative, `buildx --platform linux/arm64` over the whole Dockerfile, would run
npm and Maven under QEMU emulation: 30–60 minutes per release, and emulated JVM builds fail
in ways that are miserable to diagnose. Splitting the stages leaves only `apk add openssl
tzdata` and a `COPY` to emulate — measured at roughly two minutes on this machine, most of
it pulling the arm64 base image.

**Verified**: `docker buildx build --platform linux/arm64 --output type=docker,dest=…`
against the default `desktop-linux` driver produces a loadable archive that reports
`Architecture: arm64`. The base runtime image is 73 MB before the jar.

> **On plain Linux Docker Engine** (not Docker Desktop), cross-architecture builds need QEMU
> registered once per boot:
> ```bash
> docker run --privileged --rm tonistiigi/binfmt --install all
> ```
> Docker Desktop does this for you.

> If a native dependency is ever added to the backend (anything shipping a `.so`), this
> assumption breaks and the arm64 image must be built properly. Nothing in the current
> dependency tree does.

---

## What the client bundle contains

```
orbixerp-<version>-<arch>/
├── Setup.cmd + setup-wizard.ps1    Windows: double-click for the graphical wizard
├── Install.cmd                     Windows: same install, plain text window
├── OrbixERP.cmd                         Windows: double-click for a start/stop/backup menu
├── install.sh / install.ps1        one-shot installer
├── orbixerp.sh / orbixerp.ps1                day-2 commands (start/stop/backup/restore/update)
├── .env.example                    every setting, commented; installer copies it to .env
├── docker-compose.yml              base stack: the API only, database-agnostic
├── docker-compose.db-docker.yml    overlay: we provide PostgreSQL
├── docker-compose.db-host.yml      overlay: client's own PostgreSQL
├── docker-compose.tls.yml          optional overlay: HTTPS via Caddy
├── Caddyfile                       config for that overlay
├── images/                         orbixerp-api, postgres, caddy — as .tar.gz
├── docs/                           INSTALL, HOST-DB-SETUP, OPERATIONS, TROUBLESHOOTING
│                                   each as .md AND as generated .txt (see below)
├── backups/  secrets/jwt/          empty, created by the build
├── VERSION  CHECKSUMS.txt  RELEASE-NOTES.md  LICENSE.txt
```

---

## The two database modes

`ERP_DB_MODE` in `.env` selects an overlay; `orbixerp.sh` / `orbixerp.ps1` assemble the `-f` list so
the client never types a compose command.

```
docker → -f docker-compose.yml -f docker-compose.db-docker.yml
host   → -f docker-compose.yml -f docker-compose.db-host.yml
```

**Overlays rather than compose profiles.** A profiled service referenced by another
service's `depends_on` has had changing behaviour across Compose v2 minor releases — whether
the profile is implicitly activated depends on the client's Compose version. Overlays behave
identically on every Compose v2 ever shipped.

The overlay's `environment:` block always wins over the client's `.env` (compose precedence),
so switching modes cannot leave a stale datasource behind.

**The `extra_hosts: host.docker.internal:host-gateway` line in the host overlay is
load-bearing.** That hostname exists automatically on Docker Desktop but not on Linux. With
the mapping declared, `ERP_DB_HOST=host.docker.internal` is the correct default everywhere.

**`ERP_STACK_NAME` (default `erp`) namespaces the compose project, container names, network
and data volume.** It exists for two reasons: clients asking for a training/demo instance
alongside production get one by unzipping into a second folder and changing two settings, and
a developer machine that has run this repo's own `docker-compose.yml` — which also uses the
container name `erp-db` — can install a bundle without a name conflict.

---

## Frontend and API

The Angular bundle is compiled into `src/main/resources/static/` and served by the same
Spring Boot process, so the SPA and the API share one origin and one port.

This is not a preference. `web/src/environments/environment.prod.ts` sets a **relative**
`apiBaseUrl: '/api/v1'`, and the backend has **no CORS configuration at all**. Serving the
SPA from a separate nginx container on a different origin would break every API call. The
single-image arrangement satisfies the constraint with no proxy and nothing to misconfigure.

Caddy exists only for the optional TLS overlay, and passes straight through to the one
upstream.

---

## The .txt copies of the guides

Each guide ships twice: the authored `.md` and a generated `.txt`. A client on Windows can
double-click a `.txt`; a `.md` opens in something unhelpful, or nothing at all.

Both formats are **committed** in `dist/bundle/docs/`, so what a client receives is visible
and reviewable in git rather than appearing only at build time.

**Never hand-edit a `.txt`.** They are generated from the Markdown by `dist/md2txt.js`. The
workflow after changing a guide is:

```bash
bash dist/build-release.sh --refresh-docs     # or: .\dist\build-release.ps1 -RefreshDocs
git add dist/bundle/docs/
```

**A release refuses to build if a committed `.txt` no longer matches its `.md`.** The build
regenerates them and compares; a mismatch names the offending file and stops. Committing
generated files always risks drift, and this gate is what removes the risk — without it,
editing a guide and forgetting to regenerate would hand a client documentation that
contradicts itself.

That converter runs inside `node:20-alpine` rather than on the host, so `build-release.sh`
and `build-release.ps1` share one implementation and cannot emit different text — and cutting
a release needs no node installation.

Two output choices that look wrong until you know the audience:

- **CRLF line endings.** Notepad on Windows Server 2019 and older renders an LF-only file as
  one endless line. On Linux the worst case is a visible `^M` in `less` — ugly, but readable.
  The `.txt` exists for the machine that can't cope, so it targets that machine.
- **ASCII only.** BOM-less UTF-8 is mis-decoded by older Windows editors, and a BOM leaves a
  stray character for Unix tools. Em dashes and arrows are transliterated instead. The script
  **fails the build** if any non-ASCII survives, and warns about characters it has no
  substitution for rather than silently shipping a `?`.

Cross-references are rewritten (`OPERATIONS.md` → `OPERATIONS.txt`) so following a link from
a `.txt` doesn't land on a file the client can't open. Wide tables become labelled blocks
rather than wrapping unreadably; narrow ones are aligned into columns.

`RELEASE-NOTES.md` and `LICENSE.txt` are not converted — the licence is already plain text,
and the release notes are short.

---

## Sub-path hosting is not supported (and what it would take)

`https://client.com/orbix-erp` does **not** work. Three things pin the app to the root of
whatever origin serves it — verified against the built artifact, not assumed:

| | Current value | Why it breaks a sub-path |
|---|---|---|
| `index.html` | `<base href="/">` | every asset is referenced *relatively* (`main-FCAA4F63.js`), so the browser resolves them against `/` and requests `/main-….js` — which a `/orbix-erp/*` proxy rule never matches |
| `environment.prod.ts` | `apiBaseUrl: '/api/v1'` | absolute; every API call goes to `/api/v1`, not `/orbix-erp/api/v1` |
| `application.yml` | no `server.servlet.context-path` | the backend answers at the root |

A Caddy `handle_path /orbix-erp/*` that strips the prefix does not rescue this — it fixes the
first request and breaks every subsequent asset and API call.

**Making it work is an application change, not a packaging one**, in three parts:

1. `<base href>` must become `/orbix-erp/` — either `ng build --base-href` (bakes the path
   into the image, so a different image per client: unacceptable for a distributed product)
   or injected into `index.html` at container start from an env var (the right answer).
2. `apiBaseUrl` must become **relative** (`api/v1`, no leading slash) so it resolves against
   that base.
3. `SERVER_SERVLET_CONTEXT_PATH=/orbix-erp` on the backend — already env-settable, no code
   change needed for this part.

Roughly a day including tests, and it needs a regression pass over the SPA's routing and the
`SKIP_UNWRAP` interceptor paths. **The client-facing answer is a subdomain** —
`https://erp.client.com` — which works today, costs one DNS entry, and is what
`docs/OPERATIONS.md` recommends.

---

## Delivery

Offline tarballs, by decision. No registry, no `docker login`, no per-client credential to
issue, rotate or leak; works on sites with poor or absent connectivity; and the bytes a
client runs are exactly the bytes handed over.

Adding a registry path later is a `.env` change plus a few lines in the installer — the
compose files already reference `${ERP_IMAGE}:${ERP_VERSION}` rather than a hard-coded name.

---

## Versioning

- **Never `latest`.** Both build scripts reject it. A moving tag makes it impossible to know
  what a client is running when they call for support.
- The pom stays `0.0.1-SNAPSHOT`; the release version is a build argument, decoupled from it.
- `CHECKSUMS.txt` records SHA-256 for every file, in `sha256sum -c` format.
- `VERSION` carries the version, architecture, build date and the git commit.

---

## Migrations and rollback

Flyway runs at boot inside the API container with `validate-on-migrate: true`,
`baseline-on-migrate: false`, `out-of-order: false`, `clean-disabled: true`.

- **First boot is slow** — 93 migrations against an empty database. The healthcheck's
  `start_period` is 180s and `erp start` waits up to 15 minutes.
- **In host mode the database must be empty and dedicated.** The installer verifies this and
  refuses otherwise; it recognises an existing installation by `flyway_schema_history` and
  allows an in-place upgrade.
- **Rollback by re-tagging is impossible.** Migrations only run forwards; the old image will
  fail `ddl-auto: validate` against an upgraded schema. `erp update` therefore takes a backup
  first and aborts if that backup fails or is empty. Restoring it is the only route back, and
  the update prints its filename.

---

## Honest note on protecting the code

A Docker image is a tar of filesystem layers. Anyone holding one can `docker save`, untar it,
extract `app.jar`, and decompile it with CFR or Procyon into near-original Java — Spring Boot
compiles with `-parameters`, so field, method and parameter names survive. Comments do not;
almost nothing else is lost.

What is actually in place:

- **`LICENSE.txt` and the commercial contract.** This is the real protection. The file is
  currently a clearly-marked template and **must be replaced with a lawyer-reviewed
  document before the first handover.**
- **No source maps.** `web/angular.json` does not enable `sourceMap` in the production
  configuration, so the client gets minified JS and never the TypeScript. **Do not add
  `sourceMap: true` to that configuration.**
- **No source in the bundle.** Verified by construction — the bundle is assembled from an
  explicit file list.

What was considered and rejected:

- **Obfuscation (ProGuard/Allatori).** Spring, JPA and Jackson are reflection- and
  name-driven. Obfuscating entity, DTO and bean names breaks JSON contracts, `@PreAuthorize`
  SpEL expressions and Hibernate mappings in ways that only surface in production. High cost,
  high breakage, modest benefit.
- **A licence-key/activation gate.** The one technical measure with real teeth, but it is an
  application change rather than a packaging one. Open, if wanted.

---

## Defect fixed here that also affects `infra/prod/`

`infra/prod/Dockerfile` and both prod compose files probe
`http://localhost:8081/actuator/health/readiness`, but actuator listens on the **management**
port `9090` (`management.server.port` in `application.yml`). On 8081 that path 404s —
`SpaWebConfig` explicitly declines to serve `actuator/` paths — so `wget` exits non-zero and
the container reports `(unhealthy)` while serving traffic perfectly. That is the long-standing
false alarm on the production box.

`dist/Dockerfile.runtime` probes 9090. The same fix has been applied to `infra/prod/`.

---

## Release checklist

1. `mvn -B clean test` and `npm test -- --watch=false` are green on the release commit.
2. Bump the version; write `RELEASE-NOTES.md` (the build writes a stub).
3. Build both architectures.
4. **Install the bundle on a clean machine and complete a first-run** before sending it.
   Docker's own errors for a bad bundle are unhelpful; the installer's are only useful if
   they have been exercised.
5. Verify `CHECKSUMS.txt` after transfer.
6. Record which client got which version and architecture.
