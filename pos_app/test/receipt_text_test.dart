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
  List<String> companyDetailLines = const [],
}) =>
    buildReceiptText(
      receipt: _receipt(withDiscount: withDiscount),
      companyName: 'Tembo Group',
      branchName: 'Dar HQ',
      cashierName: 'Amina Mwanga',
      width: width,
      gift: gift,
      reversed: reversed,
      companyDetailLines: companyDetailLines,
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
      expect(t, contains('TEMBO GROUP'));
      expect(t, contains('Dar HQ'));
      expect(t, contains('INV-0001'));
      expect(t, contains('06-07-2026'));
      expect(t, contains('14:30:00'));
      expect(t, contains('Amina Mwanga'));
      expect(t, contains('Walk-in Customer'));
      expect(t, contains('Sugar 1kg'));
      expect(t, contains('TOTAL EXCL OF TAX:'));
      expect(t, contains('TAX A-18%'));
      expect(t, contains('TOTAL TAX:'));
      expect(t, contains('TOTAL INCL OF TAX:'));
      expect(t, contains('TZS 3,540.00'));
      expect(t, contains('CASH'));
      expect(t, contains('CHANGE'));
      expect(t, contains('460.00'));
      expect(t, contains('Thank you!'));
    });
    test('shows a line discount when present', () {
      expect(_text(width: 32, withDiscount: true), contains('less disc'));
    });
  });

  group('buildReceiptText — fiscal header (company detail lines)', () {
    test('renders nothing extra when no detail lines are given (backward compatible)', () {
      final t = _text(width: 32);
      final lines = t.split('\n');
      // Company name immediately followed by the branch name, then a blank line.
      final nameIdx = lines.indexOf(centered('TEMBO GROUP', 32));
      expect(nameIdx, isNonNegative);
      expect(lines[nameIdx + 1], centered('Dar HQ', 32));
    });

    test('renders address/contact/TIN/VRN lines centred, in order, between name and branch', () {
      final t = _text(width: 32, companyDetailLines: const [
        'Plot 12 Nyerere Road',
        'Dar es Salaam',
        'Tel: +255 22 123 4567',
        'Email: info@sam.co.tz',
        'TIN: 123-456-789',
        'VRN: 40-123456-A',
      ]);
      final lines = t.split('\n');
      expect(t, contains('Plot 12 Nyerere Road'));
      expect(t, contains('Dar es Salaam'));
      expect(t, contains('Tel: +255 22 123 4567'));
      expect(t, contains('Email: info@sam.co.tz'));
      expect(t, contains('TIN: 123-456-789'));
      expect(t, contains('VRN: 40-123456-A'));

      final nameIdx = lines.indexWhere((l) => l.contains('TEMBO GROUP'));
      final branchIdx = lines.indexWhere((l) => l.contains('Dar HQ'));
      final tinIdx = lines.indexWhere((l) => l.contains('TIN: 123-456-789'));
      final vrnIdx = lines.indexWhere((l) => l.contains('VRN: 40-123456-A'));
      expect(nameIdx, lessThan(tinIdx));
      expect(tinIdx, lessThan(vrnIdx));
      expect(vrnIdx, lessThan(branchIdx));
    });

    test('omits empty/blank detail lines instead of rendering blank rows', () {
      final t = _text(width: 32, companyDetailLines: const [
        '',
        'Tel: +255 22 123 4567',
        '',
      ]);
      final lines = t.split('\n');
      // No stray blank line between the company name and the Tel line.
      final nameIdx = lines.indexOf(centered('TEMBO GROUP', 32));
      expect(lines[nameIdx + 1], contains('Tel: +255 22 123 4567'));
    });

    test('wraps a long address line without exceeding width', () {
      final t = _text(width: 32, companyDetailLines: const [
        'Plot 12, Nyerere Road, Industrial Area, Dar es Salaam',
      ]);
      for (final line in t.split('\n')) {
        expect(line.length, lessThanOrEqualTo(32));
      }
      expect(t, contains('Plot 12,'));
    });
  });

  group('buildReceiptText — 80mm (48 cols)', () {
    test('every line fits the width and content is present', () {
      final t = _text(width: 48);
      for (final line in t.split('\n')) {
        expect(line.length, lessThanOrEqualTo(48));
      }
      expect(t, contains('TEMBO GROUP'));
      expect(t, contains('TOTAL INCL OF TAX:'));
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

  group('item table and tax letters', () {
    InvoiceLine line(String name, VatStatus s,
            {double rate = 18,
            double qty = 1,
            double gross = 1180,
            double vat = 180}) =>
        InvoiceLine(
          lineNo: 1,
          productCode: 'P',
          productName: name,
          unitName: 'pcs',
          quantity: qty,
          unitPriceAmount: gross / qty,
          lineDiscountAmount: 0,
          netAmount: gross - vat,
          vatAmount: vat,
          grossAmount: gross,
          vatRate: rate,
          vatStatus: s,
        );
    String render(List<InvoiceLine> lines,
        {int width = kCols58mm, String? customerName = 'Walk-in Customer'}) {
      final base = _receipt();
      return buildReceiptText(
        receipt: Receipt(
          invoice: SalesInvoice(
            id: '1',
            uid: 'u',
            invoiceNumber: 'INV-0009',
            status: InvoiceStatus.finalised,
            customerId: '424242',
            customerName: customerName,
            agentName: null,
            currency: 'TZS',
            netTotalAmount: 1000,
            vatTotalAmount: 180,
            grossTotalAmount: 1180,
            taxSummary: null,
            finalisedAt: DateTime(2026, 10, 2, 8, 36, 15),
            notes: null,
          ),
          lines: lines,
          payments: base.payments,
          clientTxnId: 't',
          tenderedAmount: null,
        ),
        companyName: 'Tembo Group',
        branchName: 'Dar HQ',
        cashierName: 'Amina Mwanga',
        width: width,
        gift: false,
      );
    }

    test('maps VAT status to the TRA letters A / C / E', () {
      expect(taxCodeFor(line('x', VatStatus.standard)), 'A');
      expect(taxCodeFor(line('x', VatStatus.zeroRated, rate: 0)), 'C');
      expect(taxCodeFor(line('x', VatStatus.exempt, rate: 0)), 'E');
    });

    test('a journalled line without a VAT status falls back to its rate', () {
      expect(taxCodeFor(line('x', VatStatus.unknown, rate: 0.18)), 'A');
      expect(taxCodeFor(line('x', VatStatus.unknown, rate: 0)), '');
    });

    test('prints the letter at the end of the item row', () {
      final rows = render([
        line('Soda', VatStatus.standard),
        line('Sugar', VatStatus.exempt, rate: 0, vat: 0),
      ]).split('\n');
      expect(rows.firstWhere((l) => l.startsWith('Soda')), endsWith(' A'));
      expect(rows.firstWhere((l) => l.startsWith('Sugar')), endsWith(' E'));
    });

    test('one TAX A line per standard rate, both 18 and 0.18 spellings', () {
      final t = render([
        line('One', VatStatus.standard, rate: 18, vat: 100),
        line('Two', VatStatus.standard, rate: 0.18, vat: 80),
        line('Three', VatStatus.zeroRated, rate: 0, vat: 0),
      ]);
      expect('TAX A-18%'.allMatches(t).length, 1);
      expect(t, contains(leftRight('TAX A-18%', '180.00', kCols58mm)));
    });

    test('a large amount or fractional qty never pushes a row past the edge',
        () {
      for (final w in [kCols58mm, kCols80mm]) {
        final t = render([
          line('Generator 20 kVA diesel', VatStatus.standard,
              gross: 12345678.90, vat: 1883239.15),
          line('Rice loose', VatStatus.standard, qty: 1.255, gross: 3000),
        ], width: w);
        for (final l in t.split('\n')) {
          expect(l.length, lessThanOrEqualTo(w), reason: 'overflow: "$l"');
        }
        expect(t, contains('12,345,678.90'));
        expect(t, contains('1.255'));
      }
    });

    test('a multi-million total on 58 mm keeps its whole label', () {
      final base = _receipt();
      final t = buildReceiptText(
        receipt: Receipt(
          invoice: SalesInvoice(
            id: '1',
            uid: 'u',
            invoiceNumber: 'INV-1',
            status: InvoiceStatus.finalised,
            customerId: '1',
            customerName: null,
            agentName: null,
            currency: 'TZS',
            netTotalAmount: 19364406.78,
            vatTotalAmount: 3485593.22,
            grossTotalAmount: 22850000,
            taxSummary: null,
            finalisedAt: DateTime(2026, 10, 2),
            notes: null,
          ),
          lines: base.lines,
          payments: [
            InvoicePayment(
                tenderType: TenderType.mobileMoney,
                amount: 22850000,
                changeAmount: 0,
                reference: null),
          ],
          clientTxnId: 't',
          tenderedAmount: null,
        ),
        companyName: 'X',
        branchName: '',
        cashierName: '',
        width: kCols58mm,
        gift: false,
      );
      final lines = t.split('\n');
      for (final l in lines) {
        expect(l.length, lessThanOrEqualTo(kCols58mm), reason: 'overflow: "$l"');
      }
      final i = lines.indexOf('TOTAL INCL OF TAX:');
      expect(i, isNonNegative, reason: 'label must not be truncated');
      expect(lines[i + 1].trim(), 'TZS 22,850,000.00');
      expect(t, contains('MOBILE MONEY'));
    });

    test('a nameless customer prints n/a, never the internal customer id', () {
      final t = render([line('Soda', VatStatus.standard)], customerName: null);
      expect(t, contains(leftRight('CUSTOMER NAME:', 'n/a', kCols58mm)));
      expect(t, isNot(contains('424242')));
    });

    test('a long customer name moves to its own line instead of being cut', () {
      const longName = 'Kilimanjaro Wholesale Distributors Limited';
      final t = render([line('Soda', VatStatus.standard)],
          customerName: longName);
      for (final l in t.split('\n')) {
        expect(l.length, lessThanOrEqualTo(kCols58mm));
      }
      expect(t.replaceAll(RegExp(r'\s+'), ' '), contains(longName));
    });

    test('carries no fiscal markings — this is not a TRA legal receipt', () {
      final t = render([line('Soda', VatStatus.standard)], width: kCols80mm)
          .toUpperCase();
      for (final marker in [
        'LEGAL RECEIPT',
        'SERIAL',
        'UIN',
        'Z NUMBER',
        'VERIFICATION',
        'TRA',
      ]) {
        expect(t, isNot(contains(marker)), reason: marker);
      }
    });
  });

  group('labelValue', () {
    test('one flush-right line when it fits', () {
      expect(labelValue('RECEIPT NO:', 'INV-1', 20),
          [leftRight('RECEIPT NO:', 'INV-1', 20)]);
    });
    test('label on its own line, value right-aligned below, when it does not',
        () {
      final out = labelValue('CUSTOMER NAME:', 'A rather long name', 20);
      expect(out.first, 'CUSTOMER NAME:');
      for (final l in out.skip(1)) {
        expect(l.length, 20);
      }
    });
  });

  group('reversal approver', () {
    test('prints the approver under the reversed banner', () {
      final t = buildReceiptText(
        receipt: _receipt(),
        companyName: 'Tembo Group',
        branchName: '',
        cashierName: '',
        width: kCols58mm,
        gift: false,
        reversed: true,
        reversedBy: 'Halima Juma',
      );
      final lines = t.split('\n');
      final banner = lines.indexWhere((l) => l.contains('*** REVERSED ***'));
      expect(lines[banner + 1], contains('Approved by Halima Juma'));
    });
  });

  group('InvoiceLine.vatStatus', () {
    test('round-trips through JSON (the receipt journal)', () {
      final l = InvoiceLine.fromJson(const {
        'lineNo': 1,
        'productName': 'Soda',
        'quantity': 1,
        'vatRate': 0,
        'vatStatus': 'ZERO_RATED',
      });
      expect(l.vatStatus, VatStatus.zeroRated);
      expect(InvoiceLine.fromJson(l.toJson()).vatStatus, VatStatus.zeroRated);
    });
    test('a receipt journalled before the field existed reads as unknown', () {
      final l = InvoiceLine.fromJson(const {'lineNo': 1, 'vatRate': 18});
      expect(l.vatStatus, VatStatus.unknown);
      expect(taxCodeFor(l), 'A');
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
