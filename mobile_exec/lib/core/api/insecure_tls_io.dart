import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';

/// Non-web: install an [HttpClient] that accepts self-signed / untrusted server
/// certificates. Only reached when [applyInsecureTlsIfEnabled] sees the flag set.
void applyInsecureTls(Dio dio) {
  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: () => HttpClient()
      ..badCertificateCallback =
          (X509Certificate cert, String host, int port) => true,
  );
}
