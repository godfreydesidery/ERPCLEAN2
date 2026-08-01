import 'package:dio/dio.dart';

/// Web fallback: the browser owns the trust store and there is no dart:io
/// [SecurityContext] to extend, so a self-signed ERP has to be trusted through
/// the browser itself. No-op. (The desktop till always compiles the io
/// implementation.)
void applyPinnedRootCa(Dio dio) {}
