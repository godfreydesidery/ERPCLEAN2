# P3-12 — re-triage of the 207-entry confused-deputy freeze store

*Ran 2026-08-15, against `develop` at Phase 3 batch 2. Companion to
[ADR-0062](../decisions/0062-organisation-as-tenant-multitenancy.md) and MULTITENANCY-PLAN.md §P3-12.*

## Why this had to be redone

`TenantScopingRulesTest` forbids a bare `findById` / `getReferenceById` in a service: the id may have
come from the caller, so the lookup answers "give me row N" instead of "give me row N **if it is
mine**" — the confused-deputy shape. 207 pre-existing call sites are frozen in
`backend/src/test/resources/archunit_freeze/`, each one blessed at the time it was frozen.

Every one of those blessings rested on the same unstated premise: **the worst case is a leak inside
one customer's own install** — embarrassing across companies or branches, but never across
customers, because there was only ever one customer per database. Organisation-as-tenant deletes
that premise. The same call in a shared instance can reach another customer's ledger.

So the store had to be re-read against the new worst case, not re-frozen against the old one.

## Method

Mechanical first pass over all 207 entries: locate the enclosing method from the frozen entry's own
`file:line`, then classify by (a) whether that method carries a scope assertion
(`assertCanActIn` / `assertCanActOn` / `assertSameTenant` / …) and (b) where the id argument comes
from. Then a manual read of the residue.

**Limits, stated plainly.** The provenance step is a heuristic over source text, not dataflow
analysis: it sees the argument expression at the call site, not the full chain behind it. It is
sound for the two large populations below — which are recognisable by shape — and I sampled the
small residue by hand rather than trusting it. It is not a proof, and the number that matters
(zero exploitable sites) rests on the sampling, not on the script.

## Result

| Class | Count | What it is | Tenant-safe? |
|---|---:|---|---|
| **G** — guarded in-method | 119 | The method asserts scope on the same company before or after the lookup | **Yes** — and now tenant-checked too: P3-11 put the organisation comparison inside `canActIn`, so every one of these 119 gained a tenant boundary for free |
| **L** — id from an already-loaded row | ~61 | `toDto` / `enrich*` / `build*Dto` resolving an FK for a display name: `l.getAccountId()`, `rc.getCustomerId()`, `a.getArInvoiceId()` | **Yes, derivatively** — the id is not caller input, it is a column of a row the caller already legitimately holds. If the parent was fetched through a scoped path, its FK is inside the tenant by construction |
| **S** — system / no-request path | 13 | Seeders, `EmailSender`, `LoginAttemptService`, `GLPostingSafeInvoker`, `OutstandingTracker`, resolvers on the posting path | **Yes** — these run with a SYSTEM principal (`userId == null`), which `TenancyScopeEnforcer` exempts by design; there is no caller to confuse |
| **R** — id is a bare parameter | 13 | Helpers taking `companyId` / `branchId` / `userId` / `valueId` directly | **Yes, on inspection** — see below |

Class R was read individually, because it is the only class where the heuristic could hide something:

- Eight are `resolveBaseCurrency(companyId)` / `resolveCompanyName(companyId)` / `resolveDefault(companyId)`
  and similar — helpers invoked *after* the public method's own `assertCanActIn` on that same id.
- `PartyBranchGuard.assertSameCompany(partyCompanyId, branchId)` **is itself the check**: it loads
  the branch precisely to compare its company against the party's, and throws otherwise. A scoped
  finder here would be circular.
- `UserCompanyServiceImpl.ensureMembership` is not reachable from a controller; its two call sites
  (`UserRoleServiceImpl`, `UserBranchServiceImpl`) guard first, plus the startup reconcile.
- The remainder (`resolveUserName`, `deptName`, `enrichRow`, `resolveBranchQuiet`) are display-name
  enrichment fed from loaded rows — class L wearing a parameter.

**No entry in the store was found to be exploitable across a tenant boundary.** That is a better
outcome than expected and it deserves scepticism, so the reason is worth naming: URLs address
entities by **uid**, not id (architecture invariant 3). Caller-supplied *numeric* ids are therefore
rare by construction — the request-facing lookups are `findByUid`, which this rule does not flag
because they are a different shape. What the store mostly contains is internal FK navigation, which
was never the confused-deputy risk.

## One thing that did change, and was not fixed here

`PartyBranchGuard.assertSameCompany` distinguishes "Branch not found" from "belongs to a different
company". Across a tenant boundary that is an **existence oracle**: it confirms that a branch id
exists somewhere in the estate. Severity is low — the ids are internal, not exposed in URLs, and the
message reveals nothing but existence — but it is the exact pattern `TenancyScopeEnforcer` refuses to
emit, and it is now inconsistent with it. Logged as a Phase 3 batch 3 tidy, not fixed in batch 2:
changing the message is a behaviour change on a live error path and does not belong in the same
deploy as `canActIn`.

## What replaces the dead premise

The store's blessings are no longer justified by "it stays inside one install". They are justified by:

1. **P3-11** — the tenant comparison lives inside `ScopeGuard.canActIn` and `canActOn`, under all 698
   call sites, and applies to root. Class G inherits it directly.
2. **Uid-addressed URLs** — caller-supplied numeric ids barely exist, so class L's parents are
   reached through `findByUid` on a scoped path.

Both are properties of the code, not of the deployment. That is the difference that matters: the old
premise could be falsified by shipping a second customer, and these cannot.

**Consequence for future entries.** A *new* frozen entry is no longer a small debt. Freezing one now
requires saying which of G / L / S / R it is, and R needs the trace written down. The store stays at
`allowStoreUpdate=false`; nothing here changes that.
