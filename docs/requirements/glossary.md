# Domain Glossary

One definition per term, used consistently across the team. Add terms as modules are specified.

## IAM

- **Organisation** — The top of the structure; one per deployment. Parent of all companies.
- **Company** — A legal entity within the organisation. Scoping parent of company-bound master data
  and transactions (`company_id`). A deployment may have several.
- **Branch** — A physical location (shop, depot, warehouse) under a company. Smallest unit at which
  stock and a business day exist. Identified by a `code` unique within its company.
- **Default branch (of a company)** — The branch used as a company's fallback context.
- **App user / user** — A login identity. Distinct from an *employee* (an HR record). A user may or
  may not be an employee. Logs in with a **username** (unique org-wide).
- **Branch assignment** — A link making a user "present" at a branch (`user_branch`). A user has
  many; exactly one is the user's **default branch**.
- **Default branch (of a user)** — The single branch a user lands in at login. Must be one of the
  user's current assignments. Auto-promoted to the earliest-assigned branch if removed.
- **Default company (of a user)** — The company a user lands in at login (the company of their
  default branch). Relevant because a user may span companies.
- **Permission** — The atomic access unit, a dot-separated code (`USER.MANAGE`). Org-wide catalogue,
  seeded. Authorisation checks reference permission codes, never role names.
- **Role** — A named, reusable bundle of permissions (`CASHIER`, `BRANCH_MANAGER`). Org-wide.
- **Role assignment (`user_role`)** — Binds a role to a user, scoped to a company and optionally to
  one branch. Branch unset ⇒ all branches of that company the user is assigned to.
- **Super-admin / root** — An identity that transcends company/branch scoping for administration and
  recovery. Bootstrap-created, tightly held, fully audited.
- **Bootstrap** — The env-driven first-run process that creates organisation + first company +
  default branch + root admin on an empty database. No interactive wizard.
- **Branch-override header** — The request header by which an authenticated user switches their
  active branch without re-login. Honoured only for branches the user is assigned to and whose role
  scope covers them.
- **Access token / refresh token** — Short-lived JWT (15 min) used per request; longer-lived refresh
  token (7 days, single-use rotated) used to obtain a new pair.
- **Lockout** — Temporary block after 5 consecutive failed logins (15 minutes); admin can clear.

## Parties

- **Party** — Umbrella term for an external counterparty held as master data. In v1 there is **no**
  single unified party record; "party" denotes the *category* comprising the separate Customer,
  Supplier, Sales Agent, and generic Other records. A party is **not** an app user and **not** an
  employee.
- **Customer** — A person/organisation we sell to. Self-contained record (own identity, contact, tax
  fields). Sub-kinds: cash/walk-in and credit/account.
- **Supplier** — A person/organisation we buy from. Self-contained record. Sub-kinds: goods and
  service.
- **Sales Agent** — A person/organisation that introduces or closes sales for commission.
  Self-contained record with an **agent kind**:
  - **Sales Agent (internal)** — a member of our staff (an app user); the agent record **references**
    an IAM user; commission accrues to that staff identity.
  - **Sales Agent (external)** — an outside broker; a standalone party with its own identity/tax
    fields and no IAM reference.
- **Other / Misc party** — A generic, lightly-typed counterparty record for something that must be
  captured now but is not yet a customer, supplier, or agent. Safety-valve.
- **Party-branch association** — The many-to-many *business relationship* linking a party to the
  branches of its company at which it may be used. A branch sees only its associated parties; a
  party's branches must all belong to its company. (A relationship, not a join table.)
- **TIN** — Taxpayer Identification Number (Tanzania). Mandatory for a business party.
- **VRN** — VAT Registration Number (Tanzania). Captured only for a VAT-registered party.
- **BRELA number** — Registration number from the Business Registrations and Licensing Agency, for
  registered businesses.
- **Walk-in / cash customer** — A customer who pays at point of sale, no credit terms; minimal
  identification (often just a name). May be a reusable anonymous counter-sale customer.
- **Credit / account customer** — A customer who buys on account with a balance and (later) credit
  terms; must be more fully identified (a business needs a TIN).
- **Debtor-as-lens** — **Not a party type.** A *finance view* of a customer who currently owes us
  money (a receivable balance). Derived by Finance from customer balances; never a separate master
  record.
- **Creditor-as-lens** — **Not a party type.** A *finance view* of a supplier we currently owe (a
  payable balance). Derived from supplier balances; never a separate master record.

## Terminology rulings (pick one, stay consistent)
- Use **user** (not "account") for the login identity; "account" only when discussing lockout state.
- Use **company** for the legal entity, **branch** for the location — never interchangeably.
- Use **permission** (not "privilege" or "right") for the atomic access unit.
- Use **party** for the master-data category; name the specific kind (**customer / supplier / sales
  agent**) when you mean one. Never call a debtor or creditor a "party" — they are finance *lenses*.
- Use **app user** for the login identity, **employee** for an HR record (deferred), **party** for an
  external counterparty — three distinct things; never blur them.
