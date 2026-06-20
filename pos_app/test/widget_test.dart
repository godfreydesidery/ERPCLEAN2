import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/money.dart';
import 'package:pos_app/models/enums.dart';

void main() {
  group('money', () {
    test('parses MoneyDto with string amount', () {
      final m = Money.fromJson({'amount': '1234.50', 'currency': 'TZS'});
      expect(m.amount, 1234.50);
      expect(m.currency, 'TZS');
    });

    test('formats with grouping', () {
      expect(formatAmount(1234.5), '1,234.50');
      expect(formatAmount(1000000, decimals: 0), '1,000,000');
    });
  });

  group('enums', () {
    test('tender type round-trips wire token', () {
      expect(TenderType.fromWire('MOBILE_MONEY'), TenderType.mobileMoney);
      expect(TenderType.cash.wire, 'CASH');
    });

    test('restricted kind exposes age label', () {
      expect(RestrictedKind.fromWire('AGE_18').ageLabel, '18+');
      expect(RestrictedKind.none.isRestricted, false);
    });
  });
}
