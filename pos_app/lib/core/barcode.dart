/// Whether [input] looks like a scanned barcode rather than a typed product
/// name/code search.
///
/// A barcode is all-digits and at least 6 characters long (EAN-8/UPC-E are 8,
/// EAN-13/UPC-A are 12–13, and embedded weight/price labels are longer still),
/// so name searches like "oil" or "cement" skip the barcode-lookup round-trip
/// that would otherwise always 404 on the server.
bool looksLikeBarcode(String input) {
  final s = input.trim();
  if (s.length < 6) return false;
  for (final unit in s.codeUnits) {
    if (unit < 0x30 || unit > 0x39) return false; // outside '0'–'9'
  }
  return true;
}
