// The conversion between what an owner counts and what the server stores.
//
// Every one of these is a number that reaches the ledger. A pack factor read
// the wrong way round does not fail loudly — it posts a plausible quantity that
// is wrong by a factor of twenty-four, which is precisely the daily-adjustment
// problem this work exists to end.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/services/catalog_service.dart';
import 'package:orbix_hq/services/operations_service.dart';

const _piece = ProductItem(
  uid: 'P1',
  code: 'OIL-1L',
  name: 'Cooking Oil 1L',
  unit: 'PCS',
  baseUnitUid: 'U-PCS',
);

TxUnit _pack(String code, double factor) =>
    TxUnit(uid: 'U-$code', code: code, name: code, factor: factor);

void main() {
  group('TxUnit', () {
    test('the base unit converts one for one', () {
      final base = TxUnit.base(_piece);
      expect(base.isBase, isTrue);
      expect(base.factor, 1);
      expect(base.toBase(10), 10);
      expect(base.labelIn('PCS'), 'PCS');
    });

    test('a pack converts by its factor and says so', () {
      final carton = _pack('CTN', 24);
      expect(carton.isBase, isFalse);
      expect(carton.toBase(10), 240);
      expect(carton.labelIn('PCS'), 'CTN (24 PCS)');
    });

    test('a whole-number factor is not written with a decimal', () {
      expect(_pack('CTN', 24).factorLabel, '24');
      expect(_pack('HALF', 0.5).factorLabel, '0.5');
    });

    test('from a pack DTO, the factor is the pack size', () {
      final unit = TxUnit.pack(const PackSize(
        uid: 'BP1',
        unitUid: 'U-CTN',
        unitCode: 'CTN',
        unitName: 'Carton',
        factorToBase: 24,
      ));
      expect(unit.uid, 'U-CTN');
      expect(unit.toBase(3), 72);
    });
  });

  group('describeInPacks', () {
    final units = [TxUnit.base(_piece), _pack('CTN', 24), _pack('OUT', 6)];

    test('reads a quantity back in cartons and pieces', () {
      expect(describeInPacks(244, units, 'PCS'), '10 CTN + 4 PCS');
    });

    test('drops the remainder when it divides exactly', () {
      expect(describeInPacks(240, units, 'PCS'), '10 CTN');
    });

    test('uses the next pack down before falling back to pieces', () {
      expect(describeInPacks(30, units, 'PCS'), '1 CTN + 1 OUT');
    });

    test('says nothing when there is nothing to add to the base figure', () {
      // Below the smallest pack, "4 PCS" is all there is to say.
      expect(describeInPacks(4, units, 'PCS'), isNull);
      // No packs configured at all.
      expect(describeInPacks(240, [TxUnit.base(_piece)], 'PCS'), isNull);
    });

    test('negative stock keeps its sign', () {
      // Below zero is a real, flagged state in this ERP, not an impossible one.
      expect(describeInPacks(-48, units, 'PCS'), '-2 CTN');
    });
  });

  group('ReceiptLine', () {
    ReceiptLine line(String unit, double factor, double qty, double cost) =>
        ReceiptLine(
          productUid: 'P1',
          productName: 'Cooking Oil 1L',
          baseUnit: 'PCS',
          unitUid: 'U-$unit',
          unit: unit,
          factorToBase: factor,
          qty: qty,
          unitCost: cost,
        );

    test('receiving in cartons adds the piece count to stock', () {
      final l = line('CTN', 24, 10, 48000);
      expect(l.qtyInBase, 240);
      expect(l.isBaseUnit, isFalse);
    });

    test('the line total is cost per delivered unit, not per piece', () {
      // The server reads unitCostAmount as the cost of one unitUid.
      expect(line('CTN', 24, 10, 48000).total, 480000);
    });

    test('receiving in the base unit changes nothing', () {
      final l = line('PCS', 1, 10, 2000);
      expect(l.qtyInBase, 10);
      expect(l.isBaseUnit, isTrue);
    });
  });
}
