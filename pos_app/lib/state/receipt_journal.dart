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

  /// An offline sale has synced (ERP ADR-0068): its journalled receipt now
  /// carries the real invoice number and uid. The OFFLINE mark and the offline
  /// number stay, so a reprint still shows how the sale was made.
  Future<void> markSynced(String offlineNumber,
      {required String? invoiceUid, required String? invoiceNumber}) async {
    if (invoiceNumber == null || invoiceNumber.isEmpty) return;
    final p = await SharedPreferences.getInstance();
    final list = p.getStringList(_key) ?? <String>[];
    var changed = false;
    for (var i = 0; i < list.length; i++) {
      final m = (jsonDecode(list[i]) as Map).cast<String, dynamic>();
      final inv = (m['invoice'] as Map?)?.cast<String, dynamic>();
      if (inv != null && inv['offlineNumber'] == offlineNumber) {
        inv['invoiceNumber'] = invoiceNumber;
        if (invoiceUid != null && invoiceUid.isNotEmpty) inv['uid'] = invoiceUid;
        m['invoice'] = inv;
        list[i] = jsonEncode(m);
        changed = true;
      }
    }
    if (changed) await p.setStringList(_key, list);
  }

  /// Mark a journalled receipt as VOID after its sale is reversed, so an offline
  /// reprint shows the reversed state (not a stale full-value "Sale complete").
  Future<void> markReversed(String invoiceUid) async {
    final p = await SharedPreferences.getInstance();
    final list = p.getStringList(_key) ?? <String>[];
    var changed = false;
    for (var i = 0; i < list.length; i++) {
      final m = (jsonDecode(list[i]) as Map).cast<String, dynamic>();
      final inv = (m['invoice'] as Map?)?.cast<String, dynamic>();
      if (inv != null && inv['uid'] == invoiceUid) {
        inv['status'] = 'VOID';
        m['invoice'] = inv;
        list[i] = jsonEncode(m);
        changed = true;
      }
    }
    if (changed) await p.setStringList(_key, list);
  }
}

final receiptJournalProvider = Provider<ReceiptJournal>((ref) => ReceiptJournal());
