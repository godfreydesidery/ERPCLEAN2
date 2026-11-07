# ERPCLEAN2 — JWT Signing Key Runbook

## Two signing modes

| Mode | Env var value | Key source | Behaviour |
|---|---|---|---|
| `dev-in-memory` | `ERP_JWT_SIGNING_MODE=dev-in-memory` (default) | Generated at JVM start | New RSA key every restart — all tokens invalidated on restart.  Fine for dev because the ephemeral key is never shared across instances. |
| `file` | `ERP_JWT_SIGNING_MODE=file` | PEM files on disk | Stable key persists across restarts and works with multiple API replicas sharing the same key files. **This is the PROD default.** |

The dev profile (`application-dev.yml`) never sets `ERP_JWT_SIGNING_MODE`, so it
defaults to `dev-in-memory`.  The prod compose (`infra/prod/docker-compose.yml`)
hard-wires `ERP_JWT_SIGNING_MODE=file` — it cannot accidentally boot in dev-in-memory
mode even if `.env` omits it.

---

## Why file mode is required for production

1. **Token invalidation on restart.** `dev-in-memory` generates a fresh RSA key every
   JVM start.  A rolling restart or a crash-recovery in production logs every user out
   simultaneously.  With `file` mode the key survives restarts.

2. **Multi-instance coherence.** If two API containers share the same PEM files (e.g.
   via a shared mount or a secret store), tokens issued by instance A are accepted by
   instance B.  With `dev-in-memory` each instance has its own key and tokens are
   rejected across instances.

3. **Auditability and rotation.** A file-mode key has a known creation date and can be
   rotated on a planned schedule, with overlap (see below).

---

## Generating a key pair

Run once per environment (dev-in-memory is used for local dev — you only need this for
QA prod-shaped deploys and production):

```sh
chmod +x infra/prod/generate-jwt-keys.sh
./infra/prod/generate-jwt-keys.sh
```

This creates:

```
infra/prod/jwt-keys/
  private.pem   (chmod 600 — keep secret)
  public.pem    (chmod 644)
```

Both files are gitignored.  The script prints the `.env` entries to add.  It overwrites
existing files after a 5-second abort window, so for a **rotation** follow the procedure
below instead (it preserves the old public key first).

Formats: private key = unencrypted PKCS#8 (`BEGIN PRIVATE KEY`); public keys = SPKI
(`BEGIN PUBLIC KEY`).  `openssl` must be available on the host.

---

## Wiring into the prod stack

`infra/prod/docker-compose.yml` binds `./jwt-keys` into the API container at
`/run/secrets/jwt` (read-only).  The `.env` file tells the app where to find the keys:

```ini
ERP_JWT_SIGNING_MODE=file
ERP_JWT_PRIVATE_KEY=/run/secrets/jwt/private.pem
ERP_JWT_PUBLIC_KEY=/run/secrets/jwt/public.pem
# Optional, rotation overlap only (comma-separated public-key paths):
# ERP_JWT_PREVIOUS_PUBLIC_KEY_PATHS=/run/secrets/jwt/previous-public.pem
```

The spring property bindings (`erp.jwt.signing-mode`, `erp.jwt.private-key-location`,
`erp.jwt.public-key-location`, `erp.jwt.previous-public-key-paths`) in `application.yml`
pick these up at startup.  Paths are plain file paths, so Docker secrets
(`/run/secrets/...`) and Kubernetes secret volumes work unchanged.  No cloud-KMS client
is bundled; if you use one, have it materialise the PEMs onto a (tmpfs) path.

---

## Key ids (`kid`)

Every access token carries a `kid` header: the **RFC 7638 SHA-256 JWK thumbprint of the
public key, base64url** (43 chars).  It is derived from the key itself, so every replica
computes the same value and nothing needs configuring.  The startup log prints the current
`kid` and the `kid`s of any previous keys (never key material).

Verification picks the key by `kid`:

| Token | Verified against |
|---|---|
| no `kid` (issued before key ids existed) | current key |
| `kid` = current | current key |
| `kid` = a configured previous key | that previous key |
| any other `kid` | refused, same generic 401 as any invalid token |

Tokens are **only ever signed with the current key**.  Previous keys are public keys and
are used for verification only.

---

## Key rotation procedure (with overlap, no logouts)

Access tokens last 15 minutes.  Refresh tokens are opaque DB-hashed values, unaffected by
rotation, so clients simply refresh onto the new key.

`ERP_JWT_PREVIOUS_PUBLIC_KEY_PATHS` (property `erp.jwt.previous-public-key-paths`) is a
comma-separated list of PEM public-key paths *inside the container*.  Unset/empty = no
overlap.

1. Keep the old public key under a new name and generate the new pair in a scratch dir
   (do not use `generate-jwt-keys.sh` here, it writes over `jwt-keys/`):
   ```sh
   cd infra/prod/jwt-keys
   cp public.pem previous-public.pem
   mkdir new
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out new/private.pem
   openssl pkey -pubout -in new/private.pem -out new/public.pem
   chmod 600 new/private.pem && chmod 644 new/public.pem
   ```
2. Back up the old `private.pem` to the secret store if you have not, then move the new
   pair into place: `mv new/private.pem private.pem && mv new/public.pem public.pem && rmdir new`.
3. Add to `.env`:
   ```ini
   ERP_JWT_PREVIOUS_PUBLIC_KEY_PATHS=/run/secrets/jwt/previous-public.pem
   ```
4. Restart the API (all replicas must see the same files).  Check the log line
   `JWT signing key ready: ... kid=<new>, previousKeys=[<old>]`.
5. Wait at least the access-token lifetime: 15 minutes (allow 30 to be safe).
6. Remove the previous key: delete `previous-public.pem`, remove the env line, restart.
   Tokens signed by the old key are refused from here on.

Tokens without a `kid` (issued before this feature) keep verifying against the current key,
so the *first* deploy of key ids logs nobody out.  They stop verifying only when the key
actually changes and no overlap is configured (a kid-less token cannot be matched to an
old key; tokens issued after this feature carry a `kid` and are covered by the overlap).

---

## Emergency rotation (suspected leak)

Do **not** configure an overlap: the leaked key must stop being accepted immediately.

1. Generate a new pair as above and replace `private.pem` / `public.pem`.
2. Leave `ERP_JWT_PREVIOUS_PUBLIC_KEY_PATHS` empty and delete any old public-key files.
3. Restart all API replicas together.
4. Every access token is now invalid, so **everyone signs in again**.  Refresh tokens are
   stored separately (hashed); if the leak could also have exposed them or the database,
   revoke them too.
5. Review the audit log for the exposure window and record the incident.

---

## Startup failures

Under `file` mode the API refuses to boot if a key file is missing, unreadable, not PEM,
unparsable, or if the private and public key are not a pair.  Each case logs one actionable
ERROR line naming the file and the env var (`ERP_JWT_PRIVATE_KEY`, `ERP_JWT_PUBLIC_KEY`,
`ERP_JWT_PREVIOUS_PUBLIC_KEY_PATHS`).  Nothing is ever returned over HTTP, and key material
is never logged.  Fix the path or permissions (the API process user needs read access) and
restart.

---

## Security notes

- `private.pem` can forge tokens for any user of **every tenant on the instance**: a leak is
  a cross-tenant compromise.  Treat it as a top-tier secret: back it up in a secret store
  (e.g. Vault, AWS Secrets Manager), not on the same host as the database backup.
- The file is chmod 600 and mounted read-only into the container.  It lives only in
  `infra/prod/jwt-keys/` on the app host (plus the secret-store backup).  Only the deploy
  operator and the API process user may read it; it must never be in git, database backups,
  logs or tickets.  Public keys are not secret.
- For production deployments on cloud infrastructure, consider injecting the PEM
  content via a secrets manager and writing it to a tmpfs at deploy time, rather than
  a bind mount from the filesystem.  This is an ops ADR item (flag to security-engineer
  before migrating to managed cloud).
