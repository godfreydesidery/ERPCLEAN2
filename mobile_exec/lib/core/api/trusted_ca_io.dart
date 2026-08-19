import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';

import 'erp_root_ca.dart';

/// Environment variable naming PEM file(s) of extra roots to trust. Accepts
/// several paths separated by the platform list separator (`;` on Windows,
/// `:` elsewhere). Additive to every other source.
const String kCaFileEnvVar = 'POS_ERP_CA_FILE';

/// PEM file an operator can drop beside `pos_app.exe` to trust a root that was
/// not compiled in — the no-rebuild path when a server's CA is replaced.
const String kCaFileName = 'erp-ca.pem';

/// Directory beside `pos_app.exe`; every `*.pem` / `*.crt` in it is trusted.
/// This is how one till talks to SEVERAL ERP servers with different private
/// CAs — one file per server, no rebuild, no ordering rules.
const String kCaDirName = 'certs';

SecurityContext? _context;
bool _contextBuilt = false;

/// Non-web: install an [HttpClient] whose trust store is the public roots PLUS
/// the ERP's own. Certificate validation stays fully enabled.
void applyPinnedRootCa(Dio dio) {
  final ctx = _sharedContext();
  // Nothing extra to trust: leave Dio's default adapter alone so behaviour is
  // exactly stock.
  if (ctx == null) return;
  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: () => HttpClient(context: ctx),
  );
}

/// Built once and shared by every Dio instance — a [SecurityContext] parses and
/// holds certificates, so rebuilding it per request would be wasted work.
SecurityContext? _sharedContext() {
  if (_contextBuilt) return _context;
  _contextBuilt = true;

  final pems = <String>[...kErpTrustedRootCas, ..._operatorSuppliedPems()];
  if (pems.isEmpty) return _context = null;

  // withTrustedRoots keeps the built-in public CAs. That matters: when prod
  // finally moves to a real domain + Let's Encrypt certificate, this build keeps
  // working untouched instead of suddenly trusting only our private root.
  final ctx = SecurityContext(withTrustedRoots: true);
  var added = 0;
  for (final pem in pems) {
    try {
      ctx.setTrustedCertificatesBytes(utf8.encode(pem));
      added++;
    } on TlsException {
      // Already present, or not a parseable certificate. Neither is worth
      // failing startup over — the remaining roots and the public ones still
      // apply, and the worst case is the stock "cannot reach host" we already
      // handle.
    }
  }
  return _context = added == 0 ? null : ctx;
}

List<String> _operatorSuppliedPems() {
  final out = <String>[];
  for (final path in _candidatePaths()) {
    try {
      final file = File(path);
      if (file.existsSync()) out.add(file.readAsStringSync());
    } on FileSystemException {
      // Unreadable (permissions, a directory, a half-written file): skip it. A
      // till must still start.
    }
  }
  return out;
}

/// Read synchronously and eagerly: the Dio instances are built during startup,
/// so an async lookup here would race the first request.
Iterable<String> _candidatePaths() sync* {
  for (final path in Platform.environment[kCaFileEnvVar]?.split(_listSep) ??
      const <String>[]) {
    final trimmed = path.trim();
    if (trimmed.isNotEmpty) yield trimmed;
  }

  final dir = _executableDir();
  if (dir == null) return;
  yield '$dir${Platform.pathSeparator}$kCaFileName';
  yield* _pemsInDir('$dir${Platform.pathSeparator}$kCaDirName');
}

/// Every certificate file in the drop-in directory, sorted so the set of roots
/// does not depend on filesystem enumeration order.
Iterable<String> _pemsInDir(String path) {
  try {
    final dir = Directory(path);
    if (!dir.existsSync()) return const <String>[];
    final files = dir
        .listSync(followLinks: false)
        .whereType<File>()
        .map((f) => f.path)
        .where((p) {
          final lower = p.toLowerCase();
          return lower.endsWith('.pem') || lower.endsWith('.crt');
        })
        .toList()
      ..sort();
    return files;
  } on FileSystemException {
    return const <String>[];
  }
}

String? _executableDir() {
  try {
    return File(Platform.resolvedExecutable).parent.path;
  } on Object {
    // resolvedExecutable is unavailable in some embedders (and under `flutter
    // test` it points at the test runner). The compiled-in roots still apply.
    return null;
  }
}

String get _listSep => Platform.isWindows ? ';' : ':';
