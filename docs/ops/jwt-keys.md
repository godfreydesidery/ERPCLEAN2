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

3. **Auditability.** A file-mode key has a known creation date and can be rotated on a
   planned schedule with a deliberate token-invalidation window.

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

Both files are gitignored.  The script prints the `.env` entries to add.

### Requirements

- `openssl` must be available on the host (standard on Linux/macOS).

---

## Wiring into the prod stack

`infra/prod/docker-compose.yml` binds `./jwt-keys` into the API container at
`/run/secrets/jwt` (read-only).  The `.env` file tells the app where to find the keys:

```ini
ERP_JWT_SIGNING_MODE=file
ERP_JWT_PRIVATE_KEY=/run/secrets/jwt/private.pem
ERP_JWT_PUBLIC_KEY=/run/secrets/jwt/public.pem
```

The spring property bindings (`erp.jwt.signing-mode`, `erp.jwt.private-key-location`,
`erp.jwt.public-key-location`) in `application.yml` pick these up at startup.

---

## Key rotation procedure

Token invalidation is the user-visible consequence of rotation.  Plan a maintenance
window or use short-lived access tokens (the default is 15 min) so the impact window
is bounded.

1. Stop the API: `docker stop erp-prod-api`
2. Back up the current key pair:
   ```sh
   cp infra/prod/jwt-keys/private.pem infra/prod/jwt-keys/private.pem.bak
   cp infra/prod/jwt-keys/public.pem  infra/prod/jwt-keys/public.pem.bak
   ```
3. Generate the new pair (the script has a 5-second abort window):
   ```sh
   ./infra/prod/generate-jwt-keys.sh
   ```
4. Restart the API: `docker start erp-prod-api`
5. Verify startup: `docker logs -f erp-prod-api`
6. All existing tokens are now invalid — users must log in again.
7. Delete the `.bak` files once you confirm the new key is working.

---

## Security notes

- `private.pem` can forge tokens for any user on this deployment.  Treat it as a
  top-tier secret: back it up in a secret store (e.g. Vault, AWS Secrets Manager),
  not on the same host as the database backup.
- The file is chmod 600 and mounted read-only into the container.
- For production deployments on cloud infrastructure, consider injecting the PEM
  content via a secrets manager and writing it to a tmpfs at deploy time, rather than
  a bind mount from the filesystem.  This is an ops ADR item (flag to security-engineer
  before migrating to managed cloud).
