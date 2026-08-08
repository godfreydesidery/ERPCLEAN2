// Wire enums mirroring the backend. Each carries its exact `wire` token (the
// JSON value) and a tolerant `fromWire`. The UI branches on these, never on
// free text.

/// `TenderType` — CASH / MOBILE_MONEY / CHEQUE / CARD (sales enums).
enum TenderType {
  cash('CASH', 'Cash'),
  mobileMoney('MOBILE_MONEY', 'Mobile'),
  cheque('CHEQUE', 'Cheque'),
  card('CARD', 'Card');

  const TenderType(this.wire, this.label);
  final String wire;
  final String label;

  static TenderType fromWire(String? w) =>
      TenderType.values.firstWhere((t) => t.wire == w, orElse: () => cash);
}

/// `PosPayoutType` — REFUND / PAID_OUT. Both subtract from expected cash.
enum PosPayoutType {
  refund('REFUND', 'Refund'),
  paidOut('PAID_OUT', 'Paid out');

  const PosPayoutType(this.wire, this.label);
  final String wire;
  final String label;

  /// Unknown types fall back to PAID_OUT: a payout the client cannot name is
  /// still cash out of the drawer, and dropping it would make a printed
  /// breakdown stop adding up to the payouts total.
  static PosPayoutType fromWire(String? w) =>
      PosPayoutType.values.firstWhere((t) => t.wire == w, orElse: () => paidOut);
}

/// The machine-readable outcome of a refused `POST /pos/sales` (K11).
///
/// Until K11 every refusal was one 409 that meant four different things, so the
/// till armed a "pending sale" for a plain business rejection and blocked the
/// drawer with nothing actually in flight. These three are now distinguishable
/// by HTTP status, by the `X-Pos-Sale-Status` header, and by `data.code`.
enum PosSaleFlowStatus {
  /// 409 — an earlier attempt under the SAME key has not finished. The only
  /// outcome for which a pending sale may be armed and retried.
  inFlight('IN_FLIGHT'),

  /// 422 — a business rule refused it and **nothing was written** (session
  /// closed, short tender, out of stock, below cost, age gate). Arming a
  /// pending sale here is the ghost-till defect.
  rejected('REJECTED'),

  /// 410 — the pending sale is too old to complete. Discard and ring again.
  staleReplay('STALE_REPLAY'),

  /// No usable token came back (an older server, or a non-sale failure).
  unknown('UNKNOWN');

  const PosSaleFlowStatus(this.wire);
  final String wire;

  /// Resolves the outcome from the server's token, falling back to the HTTP
  /// status.
  ///
  /// The status fallback matters for one real case: a server that predates K11
  /// answers a bare 409 for every refusal. Mapping that to [inFlight] keeps the
  /// old, cautious behaviour (never clear a slot we are unsure about) instead
  /// of silently adopting the new, riskier one against an old backend.
  static PosSaleFlowStatus resolve(String? code, int? statusCode) {
    for (final s in PosSaleFlowStatus.values) {
      if (s.wire == code) return s;
    }
    return switch (statusCode) {
      409 => inFlight,
      422 => rejected,
      410 => staleReplay,
      _ => unknown,
    };
  }
}

/// Verdict of `GET /pos/sales/idempotency/{key}` (K11) — the cheap read that
/// answers "did that sale actually post?" without re-running a single business
/// guard, and therefore still answers it long after the shift closed.
enum PosSaleLookupVerdict {
  /// The sale committed. The invoice reference is populated.
  posted('POSTED'),

  /// No marker exists, and the marker commits with the invoice — so nothing was
  /// recorded. Ring again **reusing the same key**: that closes the only race
  /// (an attempt committing at this instant), because the re-ring then finds
  /// the marker and returns the same invoice instead of creating a second sale.
  neverPosted('NEVER_POSTED'),

  /// A marker is reserved but unstamped — an attempt is genuinely in progress.
  unknown('UNKNOWN');

  const PosSaleLookupVerdict(this.wire);
  final String wire;

  static PosSaleLookupVerdict fromWire(String? w) => PosSaleLookupVerdict.values
      .firstWhere((v) => v.wire == w, orElse: () => unknown);
}

/// `PosSessionStatus` — OPEN -> CLOSED -> RECONCILED.
enum PosSessionStatus {
  open('OPEN'),
  closed('CLOSED'),
  reconciled('RECONCILED'),
  unknown('UNKNOWN');

  const PosSessionStatus(this.wire);
  final String wire;

  static PosSessionStatus fromWire(String? w) => PosSessionStatus.values
      .firstWhere((s) => s.wire == w, orElse: () => unknown);

  bool get isOpen => this == open;
  bool get isClosed => this == closed;
  bool get isReconciled => this == reconciled;
}

/// `InvoiceStatus` — DRAFT -> FINALISED -> VOID.
enum InvoiceStatus {
  draft('DRAFT'),
  finalised('FINALISED'),
  voided('VOID'),
  unknown('UNKNOWN');

  const InvoiceStatus(this.wire);
  final String wire;

  static InvoiceStatus fromWire(String? w) =>
      InvoiceStatus.values.firstWhere((s) => s.wire == w, orElse: () => unknown);

  bool get isFinalised => this == finalised;
  bool get isVoid => this == voided;
}

/// `UnitPriceStatus` — why a batch-resolved price is present or absent
/// (`POST /product-prices/resolve`, P2).
enum UnitPriceStatus {
  /// A price was found. `amount`/`currency` are populated.
  resolved('RESOLVED'),

  /// The product has no price row. `amount`/`currency` are null.
  noPrice('NO_PRICE'),

  /// The requested unit is neither the product's base unit nor one of its
  /// configured bulk packs.
  unitNotApplicable('UNIT_NOT_APPLICABLE'),

  unknown('UNKNOWN');

  const UnitPriceStatus(this.wire);
  final String wire;

  static UnitPriceStatus fromWire(String? w) =>
      UnitPriceStatus.values.firstWhere((s) => s.wire == w, orElse: () => unknown);

  bool get isResolved => this == resolved;
}

/// `RestrictedKind` — NONE / AGE_18 / AGE_21 (age-restriction gate, ADR-0044 D-3a).
enum RestrictedKind {
  none('NONE'),
  age18('AGE_18'),
  age21('AGE_21');

  const RestrictedKind(this.wire);
  final String wire;

  static RestrictedKind fromWire(String? w) =>
      RestrictedKind.values.firstWhere((s) => s.wire == w, orElse: () => none);

  bool get isRestricted => this != none;

  /// Minimum-age label for the acknowledgement prompt.
  String get ageLabel => switch (this) {
        age18 => '18+',
        age21 => '21+',
        none => '',
      };
}

/// The three OrbixPOS register verticals. Shared payment/receipt/session spine;
/// only the register changes (prototype README).
enum BusinessMode {
  supermarket('Supermarket', '🛒', 'Fast scanner-first grocery checkout'),
  pharmacy('Pharmacy', '💊', 'Dispensing with patient & Rx capture'),
  restaurant('Restaurant', '🍽', 'Table service with order tickets');

  const BusinessMode(this.label, this.glyph, this.blurb);
  final String label;
  final String glyph;
  final String blurb;
}
