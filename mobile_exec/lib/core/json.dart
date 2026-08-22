/// Defensive JSON coercion helpers.
///
/// The ERP serialises `Long` ids as JSON **strings** (id/uid duality) and money
/// amounts as strings inside `MoneyDto`; other `BigDecimal` fields may arrive as
/// a JSON number or string depending on the field. These helpers tolerate both
/// so the models never throw on a shape variation.
library;

/// A nullable string id. Long ids come as strings already, but tolerate numbers.
String? asStr(dynamic v) => v?.toString();

/// A required string (empty when absent).
String asStrOr(dynamic v, [String fallback = '']) => v?.toString() ?? fallback;

/// A nullable double from a number or numeric string.
double? asNum(dynamic v) {
  if (v == null) return null;
  if (v is num) return v.toDouble();
  return double.tryParse(v.toString());
}

/// A required double (defaults to 0).
double asNumOr(dynamic v, [double fallback = 0]) => asNum(v) ?? fallback;

/// A nullable int from a number or numeric string.
int? asInt(dynamic v) {
  if (v == null) return null;
  if (v is num) return v.toInt();
  return int.tryParse(v.toString());
}

int asIntOr(dynamic v, [int fallback = 0]) => asInt(v) ?? fallback;

bool asBool(dynamic v, [bool fallback = false]) {
  if (v is bool) return v;
  if (v is String) return v.toLowerCase() == 'true';
  return fallback;
}

/// A nullable [DateTime] from an ISO-8601 string.
DateTime? asDate(dynamic v) {
  if (v == null) return null;
  return DateTime.tryParse(v.toString());
}

/// Casts a dynamic map to `Map<String, dynamic>` (Dio gives `_InternalLinkedHashMap`).
Map<String, dynamic> asMap(dynamic v) =>
    v is Map ? v.cast<String, dynamic>() : <String, dynamic>{};

/// Maps a dynamic list of maps into typed items.
List<T> asList<T>(dynamic v, T Function(Map<String, dynamic>) item) =>
    v is List
        ? v.whereType<Map>().map((m) => item(m.cast<String, dynamic>())).toList()
        : <T>[];
