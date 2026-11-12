// Stock value at cost AND at selling price (1.4.1, asked for by clients who
// saw the cost figure on the dashboard and wanted the sales figure beside it).
//
// The server's totals win; row sums are only a fallback. An item with no
// selling price is left out of the sales value and counted, never read as 0.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/services/stock_service.dart';

Map<String, dynamic> _row(String code, {num? cost, num? sale}) => {
      'productCode': code,
      'productName': code,
      'quantityOnHand': 10,
      'costValue': cost,
      'saleValue': sale,
    };

void main() {
  group('ValuationResult.fromJson', () {
    test('reads cost and sales totals from the server totals', () {
      final r = ValuationResult.fromJson({
        'rows': [_row('A', cost: 1000, sale: 1500)],
        'totals': {
          'totalCostValue': 1000,
          'totalSaleValue': 1500,
          'unvaluedItems': 0,
          'unpricedItems': 0,
        },
        'priceIncludesVat': true,
      });
      expect(r.total, 1000);
      expect(r.saleTotal, 1500);
      expect(r.potentialProfit, 500);
      expect(r.unpricedCount, 0);
      expect(r.priceIncludesVat, isTrue);
    });

    test('carries the unpriced count from the server', () {
      final r = ValuationResult.fromJson({
        'rows': [
          _row('A', cost: 1000, sale: 1500),
          _row('B', cost: 400),
        ],
        'totals': {
          'totalCostValue': 1400,
          'totalSaleValue': 1500,
          'unvaluedItems': 0,
          'unpricedItems': 1,
        },
      });
      expect(r.saleTotal, 1500);
      expect(r.unpricedCount, 1);
      expect(r.rows[1].saleValue, isNull);
    });

    test('falls back to row sums when there is no totals block', () {
      final r = ValuationResult.fromJson({
        'rows': [
          _row('A', cost: 1000, sale: 1500),
          _row('B', sale: 700),
          _row('C', cost: 300),
        ],
      });
      expect(r.total, 1300);
      expect(r.saleTotal, 2200);
      expect(r.unvaluedCount, 1);
      expect(r.unpricedCount, 1);
    });

    test('has no sales value when nothing is priced', () {
      final r = ValuationResult.fromJson({
        'rows': [_row('A', cost: 1000)],
      });
      expect(r.saleTotal, isNull);
      expect(r.potentialProfit, isNull);
    });
  });
}
