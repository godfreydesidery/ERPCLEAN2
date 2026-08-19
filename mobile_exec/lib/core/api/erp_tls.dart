import 'package:dio/dio.dart';

import 'insecure_tls.dart';
import 'trusted_ca.dart';

/// The single place TLS policy is applied to a Dio instance. Every Dio the app
/// creates must go through here, so a new one cannot accidentally ship with a
/// different trust posture from the rest of the till.
///
/// Order matters:
///  1. trust the ERP's own root in addition to the public CAs — validation ON;
///  2. then, only if the build carries `POS_ALLOW_INSECURE_TLS=true`, replace
///     that with the validation-off adapter. The testing escape hatch is last
///     so it genuinely overrides, rather than being silently undone by (1).
void applyErpTls(Dio dio) {
  applyPinnedRootCa(dio);
  applyInsecureTlsIfEnabled(dio);
}
