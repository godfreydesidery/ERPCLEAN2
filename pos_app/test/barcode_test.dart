import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/barcode.dart';

void main() {
  group('looksLikeBarcode', () {
    test('typed names are not barcodes', () {
      expect(looksLikeBarcode('oil'), isFalse);
      expect(looksLikeBarcode('cement'), isFalse);
      expect(looksLikeBarcode('cooking oil'), isFalse);
    });

    test('short digit strings are not barcodes', () {
      expect(looksLikeBarcode(''), isFalse);
      expect(looksLikeBarcode('123'), isFalse);
      expect(looksLikeBarcode('12345'), isFalse);
    });

    test('long all-digit strings are barcodes', () {
      expect(looksLikeBarcode('123456'), isTrue); // 6 digits (min length)
      expect(looksLikeBarcode('5901234123457'), isTrue); // EAN-13
      expect(looksLikeBarcode('00012345678905'), isTrue); // embedded/UPC-style
    });

    test('surrounding whitespace is trimmed before the check', () {
      expect(looksLikeBarcode('  5901234123457  '), isTrue);
      expect(looksLikeBarcode('   123   '), isFalse);
    });

    test('alphanumeric or punctuated codes are not barcodes', () {
      expect(looksLikeBarcode('ABC123'), isFalse);
      expect(looksLikeBarcode('SKU-000123'), isFalse);
      expect(looksLikeBarcode('123456A'), isFalse);
      expect(looksLikeBarcode('12 34 56'), isFalse);
    });
  });
}
