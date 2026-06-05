# Security Review — Slice 2 (Authentication)

> Reviewer: security-engineer · Date: 2026-06-05 · Scope: login / refresh / logout, JWT, lockout,
> bootstrap. Verdict: **PASS for dev/QA**, with 2 production-gating items and 2 low findings.

## Threat model (summary)
- **Assets:** user credentials, refresh tokens, the JWT signing key, the root admin.
- **Trust boundary:** the `/api/v1/auth/*` endpoints (unauthenticated) and the bearer-token filter.
- **Primary threats:** credential stuffing / brute force, user enumeration, token theft/replay,
  signing-key compromise, weak bootstrap admin.

## Controls verified (good)
- **[OK] Password storage** — bcrypt cost 12 (`SecurityConfig`). Meets FR-IAM-08.
- **[OK] Generic auth errors** — every credential failure returns the same "Invalid username or
  password." (`AuthenticationException.invalidCredentials()`), so the response body does not reveal
  whether the username exists. Verified in `login_unknownUser_throwsGeneric` /
  `login_wrongPassword_throwsGeneric`.
- **[OK] Lockout persists across the rollback** — failed-attempt bookkeeping runs in a
  `REQUIRES_NEW` transaction (`LoginAttemptService`), so the counter/lock commit even though login
  throws. Without this the lock never engaged. Verified live (6th attempt with correct password is
  refused) and in `login_fiveFailures_locksAccount`.
- **[OK] Refresh tokens hashed at rest** — only SHA-256 of the token is stored (`Tokens.hash`); a DB
  leak does not expose usable tokens.
- **[OK] Single-use rotation + theft response** — a consumed token presented again revokes the whole
  user's token chain (`revokeAllTokens`, also `REQUIRES_NEW` so it commits). Verified live and in
  `refresh_reuseOfConsumedToken_revokesWholeChain`. Follows RFC 6819 refresh-token-theft guidance.
- **[OK] Stateless, CSRF-exempt correctly** — token-based API, no session cookie, so CSRF is N/A;
  disabling it is correct, not a gap.
- **[OK] No secret logging** — no log statements emit passwords or raw tokens (grep clean).
- **[OK] Bootstrap fails closed** — a missing/short/placeholder root password aborts startup
  (`BootstrapRunner.validateAdminPassword`, min 12 + placeholder list + policy).

## Findings

### [MEDIUM → prod-gating] G1 — JWT signing key is ephemeral (dev-in-memory)
The default `signing-mode=dev-in-memory` generates a new RSA key each start, so every restart
invalidates all tokens. Acceptable for dev/QA; **must not ship to production**. Before any prod
deploy, switch to `signing-mode=file` with a stable RS256 key from a secret store (devops owns the
key provisioning; `RsaKeyProvider` already supports file mode). **Blocks production, not this slice.**

### [LOW → prod-gating] G2 — Access-token revocation/denylist not yet implemented
Logout revokes the refresh token, but the already-issued access token stays valid until its 15-min
expiry (ARCHITECTURE §4 notes a JTI denylist as the intended mechanism). Window is small and bounded;
acceptable for now. Add the short-lived denylist (in-memory dev → Redis at scale) before a security
-sensitive production launch. Tracked.

### [LOW] G3 — Timing-based user enumeration
For an unknown username, login throws before any bcrypt comparison; for a known username it runs a
~cost-12 bcrypt verify. The response-time difference can let an attacker distinguish valid usernames.
Lockout limits exploitation but doesn't remove the signal. **Remediation (cheap):** when the user is
not found, still perform one dummy `passwordEncoder.matches` against a constant fake hash to equalise
timing. Recommend doing this in Slice 3 hardening. Not a blocker.

### [INFORMATIONAL] G4 — Lockout is account-based, not IP-based
5-fails-per-account can be abused for targeted account DoS (lock a known user out). Acceptable for
v1; revisit with IP-aware throttling if abuse appears. No action now.

## Production go/no-go checklist (for later)
- [ ] G1 stable RS256 key from secret store (`signing-mode=file`).
- [ ] G2 access-token denylist on logout.
- [ ] G3 constant-time unknown-user path.
- [ ] TLS terminated in front (no plaintext JWT on the wire).
- [ ] Bootstrap disabled (or password rotated) after first run.

**Slice 2 verdict: PASS.** The auth path is sound for dev/QA. G1 and G2 are explicitly deferred
production items, already flagged to devops; G3 is cheap hardening for Slice 3.
