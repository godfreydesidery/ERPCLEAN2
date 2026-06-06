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
- **Business registration number** — A general, registrar-agnostic company/business registration
  number for registered businesses (BRELA in Tanzania, or another registrar elsewhere). Recommended,
  not mandatory; the system does not hard-code BRELA.
- **Walk-in / cash customer** — A customer who pays at point of sale, no credit terms; minimal
  identification (often just a name). May be a reusable anonymous counter-sale customer.
- **Credit / account customer** — A customer who buys on account with a balance and (later) credit
  terms; must be more fully identified (a business needs a TIN).
- **Debtor-as-lens** — **Not a party type.** A *finance view* of a customer who currently owes us
  money (a receivable balance). Derived by Finance from customer balances; never a separate master
  record.
- **Creditor-as-lens** — **Not a party type.** A *finance view* of a supplier we currently owe (a
  payable balance). Derived from supplier balances; never a separate master record.

## Multicurrency

- **Monetary amount** — A *value together with the currency it is denominated in* — never a bare
  number. The atomic unit of money in the system; the technical "amount + currency" type lives in
  ADR-0005. An amount without a currency is invalid (BR-CUR-01).
- **Base / reporting currency** — The single currency a **company** reports and values in. One per
  company, configurable (default TZS for TZ deployments, **never hard-coded**), set once at company
  setup; changing it is a controlled operation (BR-CUR-02).
- **Transaction / document currency** — The currency a specific transaction or document is *expressed
  in*. Usually equals the company base in v1; may differ from base (then it is a *foreign* currency).
- **Foreign currency** — Any currency other than the company's base currency. A foreign-currency
  transaction records its base-currency equivalent + the rate used, on the transaction (FR-CUR-09).
  The *capability* exists in v1; foreign-currency *operations* are largely deferred.
- **Exchange rate** — The rate converting a foreign-currency amount into the base currency, recorded
  **with** the transaction and immutable thereafter (BR-CUR-05). v1 does **not** source rates from any
  feed (FR-CUR-10); a needed rate is entered/known with the transaction.
- **ISO 4217** — The international standard for currency codes (`TZS`, `USD`, `EUR`) and minor units.
  Each currency in the master carries its ISO 4217 code (FR-CUR-01).
- **Minor units** — The number of decimal places a currency uses (USD/EUR = 2, TZS/JPY = 0,
  KWD/BHD = 3). Comes from the currency record; the system never assumes "2" (FR-CUR-05, BR-CUR-03).
- **FX gain/loss** — **Deferred.** The gain or loss arising when a foreign-currency balance is settled
  at a different rate than it was billed (*realised*) or re-stated at period end (*unrealised*). Out
  of scope for v1 (FR-CUR-11/12); not precluded by the v1 model.

## Terminology rulings (pick one, stay consistent)
- Use **user** (not "account") for the login identity; "account" only when discussing lockout state.
- Use **company** for the legal entity, **branch** for the location — never interchangeably.
- Use **permission** (not "privilege" or "right") for the atomic access unit.
- Use **party** for the master-data category; name the specific kind (**customer / supplier / sales
  agent**) when you mean one. Never call a debtor or creditor a "party" — they are finance *lenses*.
- Use **app user** for the login identity, **employee** for an HR record (deferred), **party** for an
  external counterparty — three distinct things; never blur them.
- Money is always a **monetary amount** (value + currency), never a bare number; say **base currency**
  for a company's reporting currency and **document / transaction currency** for what a transaction is
  expressed in — never conflate the two.

## Products

- **Product / Item** — a catalogue entry the company produces, buys, or sells; the master definition,
  **not** a stock quantity. "Product" is canonical; "item" is a synonym.
- **Goods** — a tangible product. **Service** — an intangible product. Every product is one or the other.
- **Sellable** — may appear on a customer sale. **Stockable** — inventory quantities are tracked (in
  the future Stock module). Two independent flags; a service is non-stockable, a raw good may be
  non-sellable.
- **Unit of measure (UoM)** — how a product is counted. **Base unit** (piece, kg, litre) — stock is
  held in it. **Bulk pack** (carton, crate) — a larger unit with a **conversion factor** to the base.
- **Barcode** — a scannable product identifier; a product may have several, one **primary**; unique
  within the company.
- **Price list** — a named selling-price set (Retail / Wholesale / Distributor); a product appears on
  one or more, with a currency-aware price per list. **Cost price** — what the product costs the
  company, tracked separately.
- **Composition / Recipe / BOM** — the **components** (other products) and quantities that make up a
  **composed** product (Ugali Meat = 1 Ugali + 1 Meat). v1 is **single-level** and records structure
  only — no stock deduction or cost roll-up yet.
- **Stock-on-hand** — the quantity of a stockable product at a branch. **NOT the Products module** — a
  future Stock concern. Products are definitions; stock-on-hand is a level. Never conflate them.
