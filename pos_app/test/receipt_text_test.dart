import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/receipt/receipt_text.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/sale.dart';

Receipt _receipt({bool withDiscount = false}) {
  final inv = SalesInvoice(
    id: '1',
    uid: 'invuid',
    invoiceNumber: 'INV-0001',
    status: InvoiceStatus.finalised,
    customerId: 'C1',
    customerName: 'Walk-in Customer',
    agentName: null,
    currency: 'TZS',
    netTotalAmount: 3000,
    vatTotalAmount: 540,
    grossTotalAmount: 3540,
    taxSummary: null,
    finalisedAt: DateTime(2026, 7, 6, 14, 30),
    notes: null,
  );
  final lines = <InvoiceLine>[
    InvoiceLine(
      lineNo: 1,
      productCode: 'P1',
      productName: 'Sugar 1kg',
      unitName: 'pcs',
      quantity: 2,
      unitPriceAmount: 1000,
      lineDiscountAmount: withDiscount ? 100 : 0,
      netAmount: 2000,
      vatAmount: 360,
      grossAmount: 2360,
      vatRate: 0.18,
    ),
    InvoiceLine(
      lineNo: 2,
      productCode: 'P2',
      productName:
          'A very long product description that exceeds the paper width nicely',
      unitName: 'pcs',
      quantity: 1,
      unitPriceAmount: 1000,
      lineDiscountAmount: 0,
      netAmount: 1000,
      vatAmount: 180,
      grossAmount: 1180,
      vatRate: 0.18,
    ),
  ];
  final payments = <InvoicePayment>[
    InvoicePayment(
        tenderType: TenderType.cash,
        amount: 4000,
        changeAmount: 460,
        reference: null),
  ];
  return Receipt(
    invoice: inv,
    lines: lines,
    payments: payments,
    clientTxnId: 'txn',
    tenderedAmount: 4000,
  );
}

String _text({
  int width = kCols58mm,
  bool gift = false,
  bool reversed = false,
  bool withDiscount = false,
}) =>
    buildReceiptText(
      receipt: _receipt(withDiscount: withDiscount),
      companyName: 'Tembo Group',
      branchName: 'Dar HQ',
      cashierName: 'Amina Mwanga',
      width: width,
      gift: gift,
      reversed: reversed,
    );

void main() {
  group('centered', () {
    test('left-pads to centre for short text', () {
      expect(centered('AB', 6), '  AB');
      expect(centered('abc', 11), '    abc');
    });
    test('never exceeds width and returns long text unchanged', () {
      expect(centered('AB', 6).length, lessThanOrEqualTo(6));
      expect(centered('too-long-string', 6), 'too-long-string');
    });
  });

  group('leftRight', () {
    test('produces an exactly width-wide line, right flush', () {
      final s = leftRight('Net', '3,540.00', 20);
      expect(s.length, 20);
      expect(s.startsWith('Net'), isTrue);
      expect(s.endsWith('3,540.00'), isTrue);
    });
    test('truncates the left when it would collide with the right', () {
      final s = leftRight('a' * 30, '9', 10);
      expect(s.length, 10);
      expect(s.endsWith('9'), isTrue);
    });
  });

  group('rule', () {
    test('is width dashes', () {
      expect(rule(32), '-' * 32);
      expect(rule(48).length, 48);
    });
  });

  group('wrapText', () {
    test('wraps to lines no wider than width', () {
      final lines = wrapText(
          'A very long product description that exceeds the paper width', 32);
      expect(lines.length, greaterThan(1));
      for (final l in lines) {
        expect(l.length, lessThanOrEqualTo(32));
      }
    });
    test('hard-splits a single over-long word', () {
      final lines = wrapText('x' * 40, 32);
      expect(lines.first.length, 32);
      expect(lines.join().replaceAll(' ', '').length, 40);
    });
  });

  group('buildReceiptText — 58mm (32 cols)', () {
    test('every line fits the width', () {
      for (final line in _text(width: 32).split('\n')) {
        expect(line.length, lessThanOrEqualTo(32),
            reason: 'overflowing line: "$line"');
      }
    });
    test('shows header, fields, totals, tender, change and footer', () {
      final t = _text(width: 32);
      expect(t, contains('Tembo Group'));
      expect(t, contains('Dar HQ'));
      expect(t, contains('INV-0001'));
      expect(t, contains('2026-07-06 14:30'));
      expect(t, contains('Amina Mwanga'));
      expect(t, contains('Walk-in Customer'));
      expect(t, contains('Sugar 1kg'));
      expect(t, contains('Net'));
      expect(t, contains('VAT'));
      expect(t, contains('TOTAL TZS'));
      expect(t, contains('3,540.00'));
      expect(t, contains('Cash'));
      expect(t, contains('Change'));
      expect(t, contains('460.00'));
      expect(t, contains('Thank you!'));
    });
    test('shows a line discount when present', () {
      expect(_text(width: 32, withDiscount: true), contains('less disc'));
    });
  });

  group('buildReceiptText — 80mm (48 cols)', () {
    test('every line fits the width and content is present', () {
      final t = _text(width: 48);
      for (final line in t.split('\n')) {
        expect(line.length, lessThanOrEqualTo(48));
      }
      expect(t, contains('Tembo Group'));
      expect(t, contains('TOTAL TZS'));
    });
  });

  group('gift receipt', () {
    test('hides all prices and the totals block, keeps items + qty', () {
      final t = _text(width: 32, gift: true);
      expect(t, contains('gift receipt'));
      expect(t, contains('Sugar 1kg'));
      expect(t, contains('Qty'));
      expect(t, isNot(contains('TOTAL')));
      expect(t, isNot(contains('3,540.00'))); // gross total hidden
      expect(t, isNot(contains('4,000.00'))); // tender line hidden
      expect(t, isNot(contains('460.00'))); // change hidden
    });
  });

  group('reversed banner', () {
    test('appends the reversed marker', () {
      expect(_text(width: 32, reversed: true), contains('*** REVERSED ***'));
    });
  });

  group('encoders', () {
    test('ESC/POS starts with ESC @ and ends with the partial cut', () {
      final b = encodeEscPos('hello');
      expect(b.sublist(0, 2), [0x1B, 0x40]);
      expect(b.sublist(b.length - 4), [0x1D, 0x56, 0x42, 0x00]);
    });
    test('ESC/POS includes the drawer kick only when requested', () {
      const kick = [0x1B, 0x70, 0x00, 0x19, 0xFA];
      expect(_containsSeq(encodeEscPos('x', kickDrawer: true), kick), isTrue);
      expect(_containsSeq(encodeEscPos('x'), kick), isFalse);
    });
    test('plain text ends with a form feed', () {
      final b = encodePlainText('hello');
      expect(b.last, 0x0C);
    });
    test('asciiBytes replaces non-ASCII with "?" and keeps newlines', () {
      final b = asciiBytes('A\né');
      expect(b, [0x41, 0x0A, 0x3F]);
    });
  });

  group('buildReceiptBytes', () {
    test('escpos mode yields an ESC/POS job', () {
      final b = buildReceiptBytes(
        receipt: _receipt(),
        companyName: 'X',
        branchName: '',
        cashierName: '',
        width: 32,
        mode: 'escpos',
        gift: false,
      );
      expect(b.sublist(0, 2), [0x1B, 0x40]);
    });
    test('plain mode yields a form-feed-terminated job', () {
      final b = buildReceiptBytes(
        receipt: _receipt(),
        companyName: 'X',
        branchName: '',
        cashierName: '',
        width: 32,
        mode: 'plain',
        gift: false,
      );
      expect(b.last, 0x0C);
    });
  });
}

bool _containsSeq(List<int> haystack, List<int> needle) {
  for (var i = 0; i + needle.length <= haystack.length; i++) {
    var match = true;
    for (var j = 0; j < needle.length; j++) {
      if (haystack[i + j] != needle[j]) {
        match = false;
        break;
      }
    }
    if (match) return true;
  }
  return false;
}
