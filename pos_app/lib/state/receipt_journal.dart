import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/sale.dart';

/// A local journal of completed receipts (IN-8). Lets the cashier reprint a
/// recent receipt **without re-posting** (G-8) and even while offline — the
/// finalised `SalesInvoice` is the receipt of record, captured at sale time.
/// Most-recent-first, capped.
class ReceiptJournal {
  static const _key = 'pos.receipt_journal';
  static const _cap = 50;

  Future<void> add(Receipt r) async {
    final p = await SharedPreferences.getInstance();
    final list = p.getStringList(_key) ?? <String>[];
    list.insert(0, jsonEncode(r.toJson()));
    if (list.length > _cap) list.removeRange(_cap, list.length);
    await p.setStringList(_key, list);
  }

  Future<List<Receipt>> recent() async {
    final p = await SharedPreferences.getInstance();
    final list = p.getStringList(_key) ?? <String>[];
    return list.map((s) {
      return Receipt.fromJson((jsonDecode(s) as Map).cast<String, dynamic>());
    }).toList();
  }
}

final receiptJournalProvider = Provider<ReceiptJournal>((ref) => ReceiptJournal());
