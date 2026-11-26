import '../services/sales_service.dart';

/// How the sales report's item list is ordered.
enum SalesSort {
  mostSold('Most sold'),
  highestSales('Highest sales'),
  az('A–Z');

  const SalesSort(this.label);
  final String label;
}

/// Food, drinks or everything. "Drinks" is the categories the owner ticked on
/// this device; "Food" is every other item (an item with no category counts
/// as food, so nothing silently drops out of both).
enum SalesKind {
  all('All'),
  food('Food'),
  drinks('Drinks');

  const SalesKind(this.label);
  final String label;
}

/// What the owner asked the list to show. Pure: the screen keeps one of these
/// and the tests drive the same functions.
class SalesFilter {
  const SalesFilter({
    this.query = '',
    this.kind = SalesKind.all,
    this.drinkCategories = const <String>{},
    this.sort = SalesSort.mostSold,
  });

  final String query;
  final SalesKind kind;

  /// Category names the owner marked as drinks, compared ignoring case.
  final Set<String> drinkCategories;
  final SalesSort sort;

  /// True when the list shows less than the whole report.
  bool get narrows => query.trim().isNotEmpty || kind != SalesKind.all;

  SalesFilter copyWith({
    String? query,
    SalesKind? kind,
    Set<String>? drinkCategories,
    SalesSort? sort,
  }) =>
      SalesFilter(
        query: query ?? this.query,
        kind: kind ?? this.kind,
        drinkCategories: drinkCategories ?? this.drinkCategories,
        sort: sort ?? this.sort,
      );
}

String _key(String s) => s.trim().toLowerCase();

String _label(SalesReportRow r) =>
    r.productName.isEmpty ? r.productCode : r.productName;

/// Whether [row] is a drink under [drinkCategories].
bool isDrink(SalesReportRow row, Set<String> drinkCategories) {
  final c = row.category;
  if (c == null) return false;
  final keys = {for (final d in drinkCategories) _key(d)};
  return keys.contains(_key(c));
}

/// The rows that match [f], in [f]'s order. The report's own rows are never
/// changed — this only chooses and orders what is shown.
List<SalesReportRow> applySalesFilter(
    List<SalesReportRow> rows, SalesFilter f) {
  final q = _key(f.query);
  final out = rows.where((r) {
    if (q.isNotEmpty &&
        !_key(r.productName).contains(q) &&
        !_key(r.productCode).contains(q)) {
      return false;
    }
    return switch (f.kind) {
      SalesKind.all => true,
      SalesKind.drinks => isDrink(r, f.drinkCategories),
      SalesKind.food => !isDrink(r, f.drinkCategories),
    };
  }).toList();

  int byName(SalesReportRow a, SalesReportRow b) =>
      _key(_label(a)).compareTo(_key(_label(b)));
  out.sort(switch (f.sort) {
    SalesSort.mostSold => (a, b) {
        final c = b.qtySold.compareTo(a.qtySold);
        return c != 0 ? c : byName(a, b);
      },
    SalesSort.highestSales => (a, b) {
        final c = b.amount.compareTo(a.amount);
        return c != 0 ? c : byName(a, b);
      },
    SalesSort.az => byName,
  });
  return out;
}

/// Totals of the rows on show — "how many MATCHA did we sell".
class SalesMatches {
  const SalesMatches(this.items, this.qtySold, this.amount);

  factory SalesMatches.of(List<SalesReportRow> rows) => SalesMatches(
        rows.length,
        rows.fold<double>(0, (a, r) => a + r.qtySold),
        rows.fold<double>(0, (a, r) => a + r.amount),
      );

  final int items;
  final double qtySold;
  final double amount;
}

/// Whether the server sends categories at all (an older one does not, and
/// then Food / Drinks cannot be told apart, so the choice is not offered).
bool reportHasCategories(List<SalesReportRow> rows) =>
    rows.any((r) => r.category != null);

/// The distinct categories in [rows], A–Z, first spelling kept.
List<String> categoriesOf(List<SalesReportRow> rows) {
  final seen = <String, String>{};
  for (final r in rows) {
    final c = r.category;
    if (c != null) seen.putIfAbsent(_key(c), () => c);
  }
  final list = seen.values.toList()
    ..sort((a, b) => _key(a).compareTo(_key(b)));
  return list;
}
