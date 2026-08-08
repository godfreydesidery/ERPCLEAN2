import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/receipt/receipt_text.dart';
import 'package:pos_app/features/receipt/report_text.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';

const _ctx = ReportContext(
  companyName: 'Tembo Group',
  branchName: 'Dar HQ',
  cashierName: 'Amina Mwanga',
  sessionNumber: 'POS-0007',
  currency: 'TZS',
  companyDetailLines: ['Plot 12 Nyerere Road', 'TIN: 123-456-789'],
);

List<TenderSubtotal> _tenders() => [
      TenderSubtotal(tenderType: TenderType.cash, amount: 20000),
      TenderSubtotal(tenderType: TenderType.mobileMoney, amount: 12020),
    ];

/// Zero-filled, one row per type — as the server sends it.
List<PayoutSubtotal> _payouts() => [
      PayoutSubtotal(
          payoutType: PosPayoutType.refund, amount: 1500, count: 2),
      PayoutSubtotal(payoutType: PosPayoutType.paidOut, amount: 500, count: 1),
    ];

XRead _x({List<PayoutSubtotal>? payouts}) => XRead(
      sessionUid: 'S1',
      openedAt: DateTime(2026, 8, 8, 8, 12),
      openingFloatAmount: 20000,
      totalSalesAmount: 32020,
      cashTenderAmount: 20000,
      totalPayoutsNetAmount: 2000,
      expectedCashAmount: 38000,
      invoiceCount: 14,
      tenderSubtotals: _tenders(),
      payoutSubtotals: payouts ?? _payouts(),
    );

ZRead _z({double variance = -500, List<PayoutSubtotal>? payouts}) => ZRead(
      sessionUid: 'S1',
      openedAt: DateTime(2026, 8, 8, 8, 12),
      closedAt: DateTime(2026, 8, 8, 17, 5),
      reconciledAt: DateTime(2026, 8, 8, 17, 20),
      openingFloatAmount: 20000,
      totalSalesAmount: 32020,
      cashTenderAmount: 20000,
      totalPayoutsNetAmount: 2000,
      expectedCashAmount: 38000,
      countedCashAmount: 38000 + variance,
      varianceAmount: variance,
      invoiceCount: 14,
      tenderSubtotals: _tenders(),
      payoutSubtotals: payouts ?? _payouts(),
    );

void main() {
  group('X-read layout', () {
    test('every line fits both paper widths', () {
      for (final width in [kCols58mm, kCols80mm]) {
        final text = buildXReadText(x: _x(), ctx: _ctx, width: width);
        for (final line in text.split('\n')) {
          expect(line.length, lessThanOrEqualTo(width),
              reason: 'overflowing line: "$line"');
        }
      }
    });

    test('carries the till identity and the drawer figures', () {
      final t = buildXReadText(
          x: _x(), ctx: _ctx, width: kCols58mm, now: DateTime(2026, 8, 8, 13, 40));
      expect(t, contains('Tembo Group'));
      expect(t, contains('TIN: 123-456-789'));
      expect(t, contains('Dar HQ'));
      expect(t, contains('POS-0007'));
      expect(t, contains('Amina Mwanga'));
      expect(t, contains('2026-08-08 13:40')); // printed-at stamp
      expect(t, contains('32,020.00')); // sales
      expect(t, contains('38,000.00')); // expected cash
      expect(t, contains('14')); // invoice count
    });

    test('states that it neither closes nor resets the shift', () {
      final t = buildXReadText(x: _x(), ctx: _ctx, width: kCols58mm);
      expect(t, contains('X-READ'));
      expect(t.toLowerCase(), contains('does not close the shift'));
      expect(t, contains('NOT A FISCAL RECEIPT'));
    });
  });

  group('payout breakdown', () {
    test('prints one row per type and they sum to the payouts total', () {
      final t = buildXReadText(x: _x(), ctx: _ctx, width: kCols80mm);
      expect(t, contains('Refund (2)'));
      expect(t, contains('Paid out (1)'));
      // 1,500 + 500 == the 2,000 net total on the line above them.
      expect(t, contains('-2,000.00'));
      expect(t, contains('-1,500.00'));
      expect(t, contains('-500.00'));
    });

    test('prints a zero-filled type rather than dropping it', () {
      final t = buildXReadText(
        x: _x(payouts: [
          PayoutSubtotal(
              payoutType: PosPayoutType.refund, amount: 0, count: 0),
          PayoutSubtotal(
              payoutType: PosPayoutType.paidOut, amount: 2000, count: 3),
        ]),
        ctx: _ctx,
        width: kCols80mm,
      );
      // Without a count suffix when nothing of that type happened — but present,
      // so the reader can tell "none" from "the report forgot to say".
      expect(t, contains('Refund'));
      expect(t, contains('Paid out (3)'));
    });

    test('an older server sending no breakdown still prints the total', () {
      final t = buildXReadText(
          x: _x(payouts: const []), ctx: _ctx, width: kCols58mm);
      expect(t, contains('Payouts'));
      expect(t, contains('-2,000.00'));
    });
  });

  group('Z-read layout', () {
    test('every line fits both paper widths', () {
      for (final width in [kCols58mm, kCols80mm]) {
        final text = buildZReadText(z: _z(), ctx: _ctx, width: width);
        for (final line in text.split('\n')) {
          expect(line.length, lessThanOrEqualTo(width),
              reason: 'overflowing line: "$line"');
        }
      }
    });

    test('shows closed/reconciled stamps, counted cash and the variance', () {
      final t = buildZReadText(z: _z(), ctx: _ctx, width: kCols80mm);
      expect(t, contains('Z-READ'));
      expect(t, contains('END OF SHIFT'));
      expect(t, contains('2026-08-08 17:05')); // closed
      expect(t, contains('2026-08-08 17:20')); // reconciled
      expect(t, contains('37,500.00')); // counted
      expect(t, contains('Cashier ___'));
      expect(t, contains('Manager ___'));
    });

    test('spells the variance out so a signed number cannot be misread', () {
      expect(buildZReadText(z: _z(variance: -500), ctx: _ctx, width: kCols80mm),
          contains('SHORT by 500.00'));
      expect(buildZReadText(z: _z(variance: 500), ctx: _ctx, width: kCols80mm),
          contains('OVER by 500.00'));
      expect(buildZReadText(z: _z(variance: 0), ctx: _ctx, width: kCols80mm),
          contains('balances exactly'));
    });

    test('an original is not stamped as a reprint; a reprint is', () {
      expect(buildZReadText(z: _z(), ctx: _ctx, width: kCols58mm),
          isNot(contains('REPRINT')));
      expect(
          buildZReadText(z: _z(), ctx: _ctx, width: kCols58mm, reprint: true),
          contains('*** REPRINT ***'));
    });
  });

  group('encodeReportBytes', () {
    test('escpos mode initialises and cuts', () {
      final b = encodeReportBytes('hello', mode: 'escpos');
      expect(b.sublist(0, 2), [0x1B, 0x40]);
      expect(b.sublist(b.length - 4), [0x1D, 0x56, 0x42, 0x00]);
    });

    test('plain mode ends with a form feed', () {
      expect(encodeReportBytes('hello', mode: 'plain').last, 0x0C);
    });

    test('never kicks the cash drawer', () {
      // A report is read, not paid out. Popping the till on a report turns a
      // cash-control document into a cash-control problem.
      const kick = [0x1B, 0x70, 0x00, 0x19, 0xFA];
      final b = encodeReportBytes(
          buildZReadText(z: _z(), ctx: _ctx, width: kCols58mm), mode: 'escpos');
      expect(_containsSeq(b, kick), isFalse);
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
