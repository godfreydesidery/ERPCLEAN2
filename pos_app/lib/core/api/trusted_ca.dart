import 'package:dio/dio.dart';

import 'trusted_ca_stub.dart' if (dart.library.io) 'trusted_ca_io.dart' as impl;

/// Teaches [dio] to trust the ERP's own root certificate authority in addition
/// to the public ones, so a box fronted by a self-signed certificate (Caddy's
/// `tls internal` on prod) is reachable with certificate validation left ON.
///
/// This is the safe counterpart to `applyInsecureTlsIfEnabled`: chain, expiry
/// and hostname checks all still run — the till simply recognises one more
/// issuer, the one we operate. It is always on; there is no build flag, because
/// there is no reason to turn it off.
///
/// The roots come from `erp_root_ca.dart` plus, on desktop, an optional
/// operator-supplied PEM (see `trusted_ca_io.dart`). No-op on web, where the
/// browser owns the trust store.
void applyPinnedRootCa(Dio dio) => impl.applyPinnedRootCa(dio);
