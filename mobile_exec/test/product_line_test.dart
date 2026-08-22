// A missing price is a fact, not a zero.
//
// The Product List report returns null for a product that has never been
// costed or never been priced. Showing those as 0.00 would read as "free", and
// a margin computed against an unknown cost is a guess presented as a figure.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/services/stock_service.dart';

ProductLine _line({double? buying, double? selling}) => ProductLine(
      code: 'OIL-1L',
      name: 'Cooking Oil 1L',
      quantityOnHand: 240,
      buyingPrice: buying,
      sellingPrice: selling,
    );

void main() {
  group('ProductLine.margin', () {
    test('is the difference when both prices are known', () {
      expect(_line(buying: 1800, selling: 2500).margin, 700);
    });

    test('is negative when the item sells below cost', () {
      expect(_line(buying: 2500, selling: 1800).margin, -700);
    });

    test('is null when the cost is unknown', () {
      expect(_line(selling: 2500).margin, isNull);
    });

    test('is null when the price is unknown', () {
      expect(_line(buying: 1800).margin, isNull);
    });

    test('is null when neither is known', () {
      expect(_line().margin, isNull);
    });

    test('a zero cost is a real cost, not a missing one', () {
      // Goods received at zero cost are a real case (OQ-PURCH-04 allows it with
      // a note), and the margin against them is the whole selling price.
      expect(_line(buying: 0, selling: 2500).margin, 2500);
    });
  });

  group('ProductLine.fromJson', () {
    test('keeps a null price null rather than defaulting it to zero', () {
      final line = ProductLine.fromJson(const {
        'productCode': 'OIL-1L',
        'productName': 'Cooking Oil 1L',
        'quantityOnHand': 240,
        'buyingPrice': null,
        'sellingPrice': null,
        'costValue': null,
      });
      expect(line.buyingPrice, isNull);
      expect(line.sellingPrice, isNull);
      expect(line.costValue, isNull);
      expect(line.quantityOnHand, 240);
      expect(line.margin, isNull);
    });

    test('falls back to the code when a product has no name', () {
      final line = ProductLine.fromJson(const {'productCode': 'OIL-1L'});
      expect(line.name, 'OIL-1L');
    });

    test('reads the figures the report actually sends', () {
      final line = ProductLine.fromJson(const {
        'productCode': 'OIL-1L',
        'productName': 'Cooking Oil 1L',
        'supplierName': 'Mbasha Holdings Ltd',
        'quantityOnHand': 240,
        'buyingPrice': 1800,
        'costValue': 432000,
        'sellingPrice': 2500,
        'saleValue': 600000,
        'discontinued': true,
      });
      expect(line.supplierName, 'Mbasha Holdings Ltd');
      expect(line.buyingPrice, 1800);
      expect(line.costValue, 432000);
      expect(line.sellingPrice, 2500);
      expect(line.saleValue, 600000);
      expect(line.discontinued, isTrue);
      expect(line.margin, 700);
    });
  });
}
