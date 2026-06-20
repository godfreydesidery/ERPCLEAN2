import 'package:intl/intl.dart';

/// A money value as the ERP returns it: `{ "amount": "1234.56", "currency": "TZS" }`.
///
/// The ERP is authoritative for money (PRIN-2); client arithmetic on [amount] is
/// only ever a **preview/aid**. We keep the server's exact string in [raw] so a
/// receipt can echo it byte-for-figure, and expose [amount] (a double) purely for
/// preview math and formatting.
class Money {
  const Money(this.amount, this.currency, {this.raw});

  /// Numeric value for preview math/formatting. Not authoritative.
  final double amount;

  /// ISO-style currency code, e.g. `TZS`.
  final String currency;

  /// The exact amount string the server sent (when parsed from JSON).
  final String? raw;

  static const Money zeroTzs = Money(0, 'TZS');

  factory Money.zero(String currency) => Money(0, currency);

  /// Parses a `MoneyDto` (`{amount, currency}`). Tolerates `amount` arriving as a
  /// JSON string (the ERP serialises money amounts as strings) or a number.
  factory Money.fromJson(Map<String, dynamic> json) {
    final rawAmount = json['amount'];
    final str = rawAmount?.toString() ?? '0';
    return Money(
      double.tryParse(str) ?? 0,
      (json['currency'] ?? '').toString(),
      raw: rawAmount is String ? rawAmount : null,
    );
  }

  /// Serialises back to a `MoneyDto`. Amount is sent as a string to match the
  /// ERP's money contract.
  Map<String, dynamic> toJson() => {
        'amount': raw ?? amount.toStringAsFixed(2),
        'currency': currency,
      };

  Money copyWith({double? amount, String? currency}) =>
      Money(amount ?? this.amount, currency ?? this.currency);

  @override
  String toString() => formatMoney(this);
}

final Map<String, NumberFormat> _fmtCache = {};

NumberFormat _grouped(int decimals) {
  final key = 'g$decimals';
  return _fmtCache.putIfAbsent(
    key,
    () => NumberFormat.decimalPatternDigits(decimalDigits: decimals),
  );
}

/// `1234.5` -> `1,234.50`. Decimals default to 2; pass 0 for whole numbers,
/// or 3 for quantities/weights.
String formatAmount(num value, {int decimals = 2}) =>
    _grouped(decimals).format(value);

/// `Money(1234.5,'TZS')` -> `TZS 1,234.50`.
String formatMoney(Money m, {int decimals = 2, bool showCurrency = true}) {
  final n = formatAmount(m.amount, decimals: decimals);
  return showCurrency ? '${m.currency} $n' : n;
}

/// Formats a raw amount + currency code -> `TZS 1,234.50`.
String formatMoneyParts(num value, String currency, {int decimals = 2}) =>
    '$currency ${formatAmount(value, decimals: decimals)}';
