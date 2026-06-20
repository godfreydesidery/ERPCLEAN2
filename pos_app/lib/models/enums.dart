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
