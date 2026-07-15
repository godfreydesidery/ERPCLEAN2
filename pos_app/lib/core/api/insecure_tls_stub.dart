import 'package:dio/dio.dart';

/// Web fallback: the browser owns TLS, so there is no dart:io HttpClient to
/// override. No-op. (The desktop till always compiles the io implementation.)
void applyInsecureTls(Dio dio) {}
