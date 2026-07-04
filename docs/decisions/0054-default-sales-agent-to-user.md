# ADR-0054: Default the sales agent to the signed-in user (`GET /agents/mine`)

- **Status:** Accepted (2026-07-04) — implemented in PR #205 (code-only, **no schema change**).
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-5 (`docs/DEFERRED-ITEMS.md`). **Effort:** S. **Migration:** none — `agents.app_user_id` already exists (V2).
- **Related:** ADR-0006 (parties — `Agent extends PartyBase`, `AgentKind.INTERNAL`, the `app_user_id` link), ADR-0029 (sales depth — SO/invoice/quotation agent attribution), ADR-0002 (RBAC — permission-gated reads, `AGENT.VIEW`), ADR-0043 (schema freeze / durable DB — additive-only).

## Context

An internal sales agent is an `Agent` (`AgentKind.INTERNAL`) linked to an `app_user` via
`agents.app_user_id` (present since V2). When a salesperson raises a sales order / invoice / POS sale,
the line should be attributed to *their* agent. The backend **already** auto-defaults this: on SO / SI
/ POS create, when the request carries no `agentUid`, `resolveAgentId` falls back to the caller's
ACTIVE INTERNAL agent for the company (`findInternalAgentIdByCompanyAndUser(companyId, userId)`).

The gap was purely in the UI: the create forms did not *show* that default, so the salesperson could
not see (or override) who the sale would be attributed to before saving — they had to trust an
invisible server-side fallback. D-5 closes that by giving the form a way to read "my agent" so it can
pre-select `agent = self` (still editable). No behaviour change on the write path.

### The determinant facts (verified 2026-07-04 against shipped code)

1. **The write-side default already works and is authoritative.** `SalesOrderServiceImpl.resolveAgentId`
   (and the SI/POS equivalents) already resolve the caller's internal agent when `agentUid` is blank.
   D-5 must **not** change this — it only mirrors the same choice into the form so it is visible.

2. **`agents.app_user_id` already links user → agent (V2).** No schema is needed to answer "which agent
   is this user?". D-5 is code-only.

3. **"No agent" is a normal outcome, not an error.** Root (and any caller with no resolvable user) is
   never an internal agent — `BR-PARTY-10` rejects assigning root as an agent's `app_user_id` at
   create. Many legitimate users (buyers, admins) simply have no linked internal agent. So a "my agent"
   read must return **empty / 200**, never a 404.

4. **There is no DB uniqueness on `app_user_id`.** A user could in principle back more than one internal
   agent, so the lookup must be deterministic (a `findFirst … OrderBy … Asc`), not a `findBy` that
   throws on multiples.

## Decision

### D-5.1 — New read endpoint `GET /agents/mine?companyUid=…`

Returns the caller's own ACTIVE INTERNAL `AgentDto`, or `null` (200 OK) when the caller is root or has
no linked internal agent. The SO / SI / POS create forms call it once and, when it returns an agent,
pre-select `agent = self` — **editable**, so a supervisor can still attribute the sale to a different
agent. The forms fall back to leaving the field empty (the server still auto-defaults on save, D-5's
determinant §1).

### D-5.2 — Reuse `AGENT.VIEW`; no new permission code

`GET /agents/mine` is gated by the existing `AGENT.VIEW` permission — it exposes only the same agent
data the list/GET endpoints already expose, so no new permission code is seeded (no
`R__seed_permissions.sql` change, and no route-guard parity to add).

### D-5.3 — Strictly scoped to `RequestContext.userId()`; empty for root / no-agent

`AgentServiceImpl.myAgent(companyUid)`:

- resolves and scope-guards the company (`assertCanActIn`);
- returns `Optional.empty()` when the principal is null, has no `userId`, or **is root** (root is never
  an internal agent — D-5 determinant §3) — so a root operator opening the form sees no false
  self-attribution;
- otherwise returns
  `findFirstByCompanyIdAndAppUserIdAndAgentKindAndStatusOrderByIdAsc(companyId, userId, INTERNAL, ACTIVE)`
  mapped to `AgentDto`.

The result is **strictly the caller's own** agent — it derives the user from `RequestContext.userId()`,
never from a caller-supplied id, so it cannot be used to read another user's agent (no confused-deputy
surface).

### D-5.4 — No migration

`agents.app_user_id` already exists (V2); the endpoint and DTO are pure code. D-5 adds nothing to the
frozen schema (ADR-0043).

## Consequences

- **Positive:** the salesperson *sees* who the sale will be attributed to and can confirm or override it
  before saving, instead of relying on an invisible server-side default. The write-path behaviour is
  unchanged and remains authoritative — the form pre-select and the save-time fallback resolve to the
  same agent.
- **Safe by construction:** the endpoint is self-only (derived from `RequestContext.userId()`), reuses
  `AGENT.VIEW`, and returns empty-200 for root / no-agent — no new permission, no 404-as-normal-flow,
  no cross-user read.
- **Deterministic under the missing uniqueness constraint:** the ordered `findFirst` picks the
  lowest-id ACTIVE internal agent rather than throwing when a user backs more than one.
- **Contract additions:** `AgentService.myAgent` + `AgentController` `GET /agents/mine`; the web
  `agent.service.ts` `myAgent(companyUid)`; the SO / invoice create forms call it to pre-select the
  agent.

## Alternatives considered

- **Add a new `AGENT.SELF.VIEW` permission** — rejected: the endpoint returns a subset of what
  `AGENT.VIEW` already grants (only the caller's own agent). A new code adds seeding + route-guard
  parity work for no security gain.
- **Return 404 when the caller has no agent** — rejected: "no linked internal agent" is a normal state
  (root, buyers, admins). 404 would make the create form treat a routine case as an error. Empty-200 is
  correct.
- **Filter the existing `GET /agents` list client-side to find "mine"** — rejected: it leaks the full
  agent list to the form for one row, and the client cannot reliably map user→agent without the
  `app_user_id` link the server owns. A dedicated self-scoped endpoint is cleaner and tighter.
- **Persist a "default agent" per user in a new column/table** — rejected: unnecessary. The
  `app_user_id` link (V2) already answers the question; a plain query beats a migration on a frozen
  schema.
- **Do the pre-select only on the server (no endpoint) by returning the agent on a form-bootstrap
  DTO** — rejected: the sales create forms have no single bootstrap call to hang it on, and a small,
  reusable `GET /agents/mine` serves SO, SI, and POS uniformly.
