// Amounts are written in full.
//
// The app used to abbreviate every figure ("TZS 3.5M"). The owner reading them
// asked for the full number, because he checks them against a bank statement
// and a supplier invoice. These lock that in — and lock the one exception,
// chart labels, to the compact form it still needs.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/format.dart';

void main() {
  group('groupDigits', () {
    test('separates thousands and keeps the requested decimals', () {
      expect(groupDigits(1234567), '1,234,567');
      expect(groupDigits(1234567.891, decimals: 2), '1,234,567.89');
      expect(groupDigits(-4800), '-4,800');
      expect(groupDigits(0), '0');
      expect(groupDigits(999), '999');
      expect(groupDigits(1000), '1,000');
    });
  });

  group('tzs', () {
    test('writes the amount in full, never abbreviated', () {
      expect(tzs(3500000), 'TZS 3,500,000');
      expect(tzs(1284300500), 'TZS 1,284,300,500');
      expect(tzs(480000), 'TZS 480,000');
      expect(tzs(0), 'TZS 0');
    });

    test('rounds to whole shillings — cents do not circulate', () {
      expect(tzs(1234.56), 'TZS 1,235');
    });

    test('keeps the sign in front of the currency', () {
      expect(tzs(-48000), '-TZS 48,000');
      expect(tzs(48000, sign: true), '+TZS 48,000');
      expect(tzs(48000), 'TZS 48,000');
    });
  });

  group('tzsBare', () {
    test('is the full figure without its currency', () {
      expect(tzsBare(3500000), '3,500,000');
      expect(tzsBare(-4800), '-4,800');
    });
  });

  group('tzsShort', () {
    test('stays abbreviated — a bar label has the width of its bar', () {
      expect(tzsShort(3500000), '3.50M');
      expect(tzsShort(1284300500), '1.28bn');
      expect(tzsShort(48000), '48.0k');
      expect(tzsShort(940), '940');
    });

    test('keeps its sign', () {
      expect(tzsShort(-3500000), '-3.50M');
      expect(tzsShort(3500000, sign: true), '+3.50M');
    });
  });

  group('qty', () {
    test('drops a trailing zero but keeps a real decimal', () {
      expect(qty(240), '240');
      expect(qty(2400), '2,400');
      expect(qty(1.5), '1.50');
    });
  });

  group('pct and pp', () {
    test('a percentage and a percentage-point change read differently', () {
      expect(pct(12.34), '12.3%');
      expect(pct(12.34, sign: true), '+12.3%');
      expect(pp(2.5), '+2.5pp');
      expect(pp(-2.5), '-2.5pp');
    });
  });
}
