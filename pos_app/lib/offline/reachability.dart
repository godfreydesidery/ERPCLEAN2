import 'package:dio/dio.dart';

import '../core/api/erp_tls.dart';

/// A cheap "is the ERP there?" check: how long the server took to answer, or
/// null when it did not answer at all.
typedef ReachabilityProbe = Future<Duration?> Function();

/// The probe's own time limit. A server that cannot answer `/health` in this
/// long is, for the till, not there.
const Duration kProbeTimeout = Duration(seconds: 4);

/// Asks the public `GET /api/v1/health` (no sign-in, no database work) on a bare
/// client — never the authenticated one, so a probe can neither trigger a token
/// refresh nor report itself as another failure.
Future<Duration?> probeErp(String apiBase, {Duration timeout = kProbeTimeout}) async {
  final dio = Dio(BaseOptions(
    baseUrl: apiBase,
    connectTimeout: timeout,
    sendTimeout: timeout,
    receiveTimeout: timeout,
  ));
  applyErpTls(dio);
  final watch = Stopwatch()..start();
  try {
    final res = await dio.get<dynamic>('/health');
    final code = res.statusCode ?? 0;
    return code >= 200 && code < 300 ? watch.elapsed : null;
  } catch (_) {
    return null;
  } finally {
    dio.close(force: true);
  }
}
