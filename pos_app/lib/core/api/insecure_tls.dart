import 'package:dio/dio.dart';

import 'insecure_tls_stub.dart'
    if (dart.library.io) 'insecure_tls_io.dart' as impl;

/// Compile-time escape hatch for connecting to an ERP box whose TLS certificate
/// is self-signed — e.g. Caddy's `tls internal` on the prod EC2 host, before a
/// real domain + Let's Encrypt cert is in place.
///
/// OFF by default: a normal `flutter build` stays strict and rejects untrusted
/// certificates (POS NFR-16). Enable it ONLY for testing, at build time:
///
///   flutter run            --dart-define=POS_ALLOW_INSECURE_TLS=true
///   flutter build windows  --dart-define=POS_ALLOW_INSECURE_TLS=true
///
/// NEVER ship a real till with this on: it disables certificate validation
/// entirely and exposes the session to man-in-the-middle interception.
const bool kAllowInsecureTls =
    bool.fromEnvironment('POS_ALLOW_INSECURE_TLS', defaultValue: false);

/// When [kAllowInsecureTls] is set (non-web builds only), swaps [dio]'s adapter
/// for one that accepts any server certificate. No-op otherwise.
void applyInsecureTlsIfEnabled(Dio dio) {
  if (kAllowInsecureTls) impl.applyInsecureTls(dio);
}
