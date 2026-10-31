// Windows-desktop raw printing via the win32 print-spooler API. Only compiled on
// targets that have dart:io (all native platforms); the web build uses the stub.
// Every entry point still guards on Platform.isWindows so an Android/Linux/macOS
// build links cleanly and simply reports "Windows only" at runtime.
import 'dart:ffi';
import 'dart:io' show Platform;

import 'package:ffi/ffi.dart';
import 'package:win32/win32.dart';

import 'package:flutter/services.dart' show PlatformException;

import 'bt_printer_android.dart';
import 'receipt_printer.dart' show ReceiptPrinterException;

/// Raw printing exists on Windows (the spooler) and on Android (a paired
/// Bluetooth printer — the built-in printer of most Android POS devices). This
/// file is compiled on every target that has `dart:io`, so the answer has to be
/// a runtime one, not a compile-time one.
bool rawPrintingSupported() => Platform.isWindows || Platform.isAndroid;

/// Enumerates installed printers with `EnumPrinters` (level 4 — fast, name-only)
/// over local printers and connections. Returns an empty list off Windows.
List<String> listPrinters() {
  if (Platform.isAndroid) return AndroidBtPrinters.cached;
  if (!Platform.isWindows) return const <String>[];
  const flags = PRINTER_ENUM_LOCAL | PRINTER_ENUM_CONNECTIONS;
  const level = 4;
  return using((arena) {
    final pcbNeeded = arena<Uint32>();
    final pcReturned = arena<Uint32>();

    // First call sizes the buffer (returns false / ERROR_INSUFFICIENT_BUFFER).
    EnumPrinters(flags, null, level, null, 0, pcbNeeded, pcReturned);
    final needed = pcbNeeded.value;
    if (needed == 0) return <String>[];

    final buffer = arena<Uint8>(needed);
    final res =
        EnumPrinters(flags, null, level, buffer, needed, pcbNeeded, pcReturned);
    if (!res.value) return <String>[];

    final count = pcReturned.value;
    final infos = buffer.cast<PRINTER_INFO_4>();
    final names = <String>[];
    for (var i = 0; i < count; i++) {
      final name = (infos + i).ref.pPrinterName;
      if (name.address != 0) {
        final s = name.toDartString();
        if (s.isNotEmpty) names.add(s);
      }
    }
    return names;
  });
}

/// Sends [bytes] to [printerName] with the RAW datatype through the spooler:
/// `OpenPrinter` -> `StartDocPrinter` (DOC_INFO_1, pDatatype = 'RAW') ->
/// `StartPagePrinter` -> `WritePrinter` -> `EndPagePrinter` -> `EndDocPrinter`
/// -> `ClosePrinter`. Throws a user-safe [ReceiptPrinterException] on failure.
Future<void> printRaw(String printerName, List<int> bytes) async {
  if (Platform.isAndroid) return _printAndroid(printerName, bytes);
  if (!Platform.isWindows) {
    throw ReceiptPrinterException(
        'Receipt printing is only available on the Windows and Android apps.');
  }
  if (printerName.trim().isEmpty) {
    throw ReceiptPrinterException('No printer selected.');
  }
  if (bytes.isEmpty) return;

  using((arena) {
    final phPrinter = arena<Pointer>();
    final opened =
        OpenPrinter(printerName.toPcwstr(allocator: arena), phPrinter, null);
    if (!opened.value) {
      throw ReceiptPrinterException(
          'Cannot open printer "$printerName". Is it installed and online?');
    }
    final hPrinter = PRINTER_HANDLE(phPrinter.value);
    try {
      final docInfo = arena<DOC_INFO_1>();
      docInfo.ref.pDocName = 'OrbixPOS Receipt'.toPwstr(allocator: arena);
      docInfo.ref.pDatatype = 'RAW'.toPwstr(allocator: arena);

      final job = StartDocPrinter(hPrinter, 1, docInfo);
      if (job == 0) {
        throw ReceiptPrinterException(
            'Printer "$printerName" did not accept the job '
            '(offline or out of paper?).');
      }
      if (!StartPagePrinter(hPrinter)) {
        EndDocPrinter(hPrinter);
        throw ReceiptPrinterException(
            'Printer "$printerName" did not start the page.');
      }

      final data = arena<Uint8>(bytes.length);
      data.asTypedList(bytes.length).setAll(0, bytes);
      final pcWritten = arena<Uint32>();
      final wrote = WritePrinter(hPrinter, data.cast(), bytes.length, pcWritten);

      EndPagePrinter(hPrinter);
      EndDocPrinter(hPrinter);

      if (!wrote || pcWritten.value < bytes.length) {
        throw ReceiptPrinterException(
            'Failed to send the receipt to "$printerName".');
      }
    } finally {
      ClosePrinter(hPrinter);
    }
  });
}

/// Android: the picker name carries the paired printer's Bluetooth address.
Future<void> _printAndroid(String printerName, List<int> bytes) async {
  final address = AndroidBtPrinters.addressOf(printerName);
  if (address == null) {
    throw ReceiptPrinterException('Choose the printer again in Setup: it is not a paired Bluetooth printer.');
  }
  if (bytes.isEmpty) return;
  try {
    await AndroidBtPrinters.print(address, bytes);
  } on PlatformException catch (e) {
    throw ReceiptPrinterException(e.message ?? 'Could not print to the Bluetooth printer.');
  }
}

/// Re-reads the paired Bluetooth printers on Android; nothing to do on Windows.
Future<void> refreshPrinters() async {
  if (Platform.isAndroid) await AndroidBtPrinters.refresh();
}
