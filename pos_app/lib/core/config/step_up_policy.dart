/// **The single place** that decides which till actions need a manager
/// standing at the terminal, and which permission each of them asks for.
///
/// Kept out of the screens on purpose. Scattering `if (needsManager)` through
/// the register, the session drawer and the receipt sheet is how a policy ends
/// up applied in two places out of three; here it is one table that can be read
/// in ten seconds and changed in one edit.
///
/// ## Choosing the permission code
///
/// A step-up is only a control if the person asking cannot satisfy it
/// themselves. Every code below is one the **CASHIER bundle does not hold**
/// (verified against `R__seed_permissions.sql`):
///
/// | code | held by |
/// |---|---|
/// | `SALES.INVOICE.VOID`      | SALES_MANAGER, BRANCH_MANAGER |
/// | `SALES.DISCOUNT.OVERRIDE` | SALES_MANAGER, BRANCH_MANAGER |
/// | `POS.SESSION.RECONCILE`   | supervisors only (explicitly withheld from CASHIER) |
///
/// `POS.SALE.VOID` was the obvious-looking choice for a reversal and is the
/// wrong one: the CASHIER bundle holds it, so a cashier could approve their own
/// refund by retyping their own password. It is not used here.
///
/// Every code must exist in `R__seed_permissions.sql`. The server refuses an
/// unknown code outright — which reads to the operator as "that user is not
/// allowed to approve this action", i.e. a typo would look like a policy
/// decision. Add nothing to this table that has not been grepped for first.
///
/// ## The code must match the server's, both ways
///
/// A step-up is a two-sided contract, and only one side is in this file:
///
/// * the **approver's** code — what this table names — is re-checked server-side
///   after the approval travels on the business request (`saleReverse` →
///   `PosSaleServiceImpl.REVERSAL_AUTHORITY_PERMISSION`, `discountOverride` →
///   `DiscountAuthorisationGuard.DISCOUNT_OVERRIDE_PERMISSION`, `xRead` /
///   `zReadPrint` → the approver check behind
///   `POST /pos/sessions/uid/{uid}/x-read|z-read/authorised`). Naming a
///   different code here than the server re-checks means the manager types the
///   right password, is told "authorised", and the action is then refused — a
///   parity trap that reads to everyone at the till as a broken password.
/// * the **operator's** own gate on the endpoint is a separate thing and stays
///   separate: a cashier reaches `/pos/sales/uid/{uid}/reverse` on
///   `POS.SALE.VOID` and supplies an approver; a supervisor reaches it on
///   `SALES.INVOICE.VOID` and is the approver.
///
/// ## What the client can and cannot enforce
///
/// Nothing here is a security control on its own. This app can be bypassed with
/// curl. Everything in this table therefore has a server-side counterpart that
/// re-verifies the approval against a real, active, **different** user who
/// genuinely holds the code — the client's job is to collect it and send it, not
/// to be trusted about it.
library;

/// A till action that can be put behind a manager approval.
enum GatedAction {
  /// Mid-shift drawer report, for an operator who cannot read it themselves.
  /// See [kStepUpPolicy] for who is asked and who is not.
  xRead,

  /// Printing the end-of-shift Z-read (including a reprint after the fact).
  zReadPrint,

  /// Reversing / refunding a finalised sale.
  saleReverse,

  /// A line discount the server has refused as above the company's ceiling.
  discountOverride,

  /// Giving up on an unfinished sale whose outcome is still unknown.
  abandonUnfinishedSale,
}

/// How one [GatedAction] is treated.
class StepUpRule {
  const StepUpRule({
    required this.requiresApproval,
    required this.permissionCode,
    required this.title,
    required this.purpose,
  });

  /// When false the action runs on the cashier's own permissions alone.
  final bool requiresApproval;

  /// The permission the approving user must genuinely hold. Must be seeded.
  final String permissionCode;

  /// Dialog title.
  final String title;

  /// One line telling the manager what they are approving.
  final String purpose;
}

/// The policy table.
///
/// **A rule here is only reached by someone who cannot do the thing alone.**
/// That is the whole shape of the X-read entry below, and it is worth spelling
/// out because the flag reads as if it were unconditional:
///
/// * An operator who holds `POS.SESSION.VIEW` reads their own X-read with **no
///   prompt at all** — the drawer calls the plain `GET` exactly as before. A
///   mid-shift X-read is a cashier's own self-check ("does my drawer agree with
///   the till before I go on break?"); it posts nothing, resets nothing, and
///   making a manager walk over for someone already allowed is a workflow
///   regression whose predictable result is that nobody checks their drawer.
/// * An operator who does **not** hold it used to see no X-read row whatsoever
///   (permission-denied rows are hidden), which shop owners read as the till
///   losing the feature after they deliberately withdrew the permission. For
///   them the row stays, and a manager's password at the terminal opens the
///   report for that ONE view — nothing is minted, nothing is cached, and the
///   next report asks again.
///
/// The **Z-read** is the shift's closing statement, the document a variance is
/// argued from, and reprintable after the fact — printing it takes a manager
/// regardless, and its reprint reaches the report through the same two doors.
///
/// Both drawer reports therefore ask for the SAME approver code, so a manager
/// who can approve one can approve the other and the prompt never has to
/// explain a distinction the shop floor cannot see.
const Map<GatedAction, StepUpRule> kStepUpPolicy = {
  GatedAction.xRead: StepUpRule(
    requiresApproval: true,
    // Must equal the approver code the server re-checks on
    // POST /pos/sessions/uid/{uid}/x-read/authorised. `POS.SESSION.VIEW` would
    // be the wrong choice here even though it is what the plain GET wants: the
    // CASHIER bundle can hold it, so a cashier could approve their own report
    // by retyping their own password.
    permissionCode: 'POS.SESSION.RECONCILE',
    title: 'Manager approval — X-read',
    purpose: 'Show the mid-shift drawer report for this session.',
  ),
  GatedAction.zReadPrint: StepUpRule(
    requiresApproval: true,
    // Deliberately the same code as [GatedAction.xRead] — see the note above the
    // table. Also what the server re-checks on
    // POST /pos/sessions/uid/{uid}/z-read/authorised.
    permissionCode: 'POS.SESSION.RECONCILE',
    title: 'Manager approval — Z-read',
    purpose: 'Print the end-of-shift Z-read for this session.',
  ),
  GatedAction.saleReverse: StepUpRule(
    requiresApproval: true,
    // Must equal PosSaleServiceImpl.REVERSAL_AUTHORITY_PERMISSION on the server: the reversal
    // carries the approver's uid and the server re-resolves it against this same code, so a drift
    // shows up at the till as "the manager was approved and the refund was refused anyway".
    permissionCode: 'SALES.INVOICE.VOID',
    title: 'Manager approval — refund',
    purpose: 'Reverse this sale and return the money to the customer.',
  ),
  GatedAction.discountOverride: StepUpRule(
    requiresApproval: true,
    // Must equal DiscountAuthorisationGuard.DISCOUNT_OVERRIDE_PERMISSION on the server: the guard
    // re-checks this same code after the step-up, so a drift shows up at the till as "your own
    // manager may not approve this".
    permissionCode: 'SALES.DISCOUNT.OVERRIDE',
    title: 'Manager approval — discount',
    purpose: 'Allow a discount larger than a cashier may give alone.',
  ),
  GatedAction.abandonUnfinishedSale: StepUpRule(
    requiresApproval: true,
    permissionCode: 'SALES.INVOICE.VOID',
    title: 'Manager approval — leave a sale unresolved',
    purpose: 'Stop asking about a sale whose outcome is still unknown.',
  ),
};

/// The rule for [action]. Every action has one — the map is exhaustive.
StepUpRule stepUpRuleFor(GatedAction action) => kStepUpPolicy[action]!;
