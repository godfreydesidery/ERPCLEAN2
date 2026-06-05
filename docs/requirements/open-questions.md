# Open Questions Log

Each entry: why it matters · who decides · does it block build.

## IAM

- **OQ-IAM-01** — ✅ **RESOLVED 2026-06-05.** Branch assignment is **decoupled** from roles — a user
  may be assigned to a branch without a role; they can only *act* where a role also covers them.
  See FR-IAM-24; BR-6 relaxed.

- **OQ-IAM-02** — ✅ **RESOLVED 2026-06-05.** When no branch assignment remains, **login succeeds**
  into a read-only "no branch assigned — contact admin" state. See FR-IAM-19.

- **OQ-IAM-03** — Does a user need an explicit **default company** field, given they can span
  companies (D5)? Lean: yes — default company + default branch (branch implies its company, so a
  single `default_branch` may suffice if the branch carries its company). Architect to resolve the
  minimal-field form.
  - *Decider:* architect (data-model). *Blocks build:* no (modelling detail).

- **OQ-IAM-04** — Time zone of record: store IAM timestamps in UTC and display per branch/company
  tz? Assumed yes (NFR §7). *Decider:* architect. *Blocks build:* no.
