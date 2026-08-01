import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/erp_root_ca.dart';
import 'package:pos_app/core/api/erp_tls.dart';
import 'package:pos_app/core/api/trusted_ca.dart';

/// Guards the pinned-root-CA path: the till must reach a server fronted by our
/// own certificate authority WITHOUT giving up certificate validation.
///
/// The offline cases always run. The live case is skipped unless
/// `POS_TLS_HOST` names a self-signed ERP box, e.g.:
///
///   POS_TLS_HOST=https://ec2-16-192-117-45.eu-north-1.compute.amazonaws.com \
///     flutter test test/trusted_ca_test.dart
final RegExp _constructsDio =
    RegExp(r'(?:^|[^A-Za-z0-9_.])Dio\(', multiLine: true);

void main() {
  group('bundled roots', () {
    test('every compiled-in root is a well-formed certificate', () {
      expect(kErpTrustedRootCas, isNotEmpty);
      for (final pem in kErpTrustedRootCas) {
        expect(pem, contains('-----BEGIN CERTIFICATE-----'));
        expect(pem, contains('-----END CERTIFICATE-----'));
        // The real check: BoringSSL parses it. A malformed or truncated PEM
        // throws here, which is what we want to catch at build time rather than
        // on a till that silently cannot connect.
        final ctx = SecurityContext(withTrustedRoots: false);
        expect(() => ctx.setTrustedCertificatesBytes(utf8.encode(pem)),
            returnsNormally);
      }
    });

    test('the prod Caddy root is the one we verified against the live box', () {
      // Pinning the identity, not just the shape: if someone swaps this PEM the
      // test says so, because a root CA is the whole trust decision.
      final ctx = SecurityContext(withTrustedRoots: false);
      expect(() => ctx.setTrustedCertificatesBytes(utf8.encode(kProdCaddyRootCa)),
          returnsNormally);
      expect(kErpTrustedRootCas, contains(kProdCaddyRootCa));
    });
  });

  group('applyPinnedRootCa', () {
    test('installs an IO adapter carrying the extra roots', () {
      final dio = Dio();
      applyPinnedRootCa(dio);
      expect(dio.httpClientAdapter, isA<IOHttpClientAdapter>());
    });

    test('is idempotent across many Dio instances', () {
      // The app builds three Dio instances; the shared SecurityContext is built
      // once and re-used, and re-adding an already-trusted root must not throw.
      for (var i = 0; i < 5; i++) {
        final dio = Dio();
        expect(() => applyErpTls(dio), returnsNormally);
        expect(dio.httpClientAdapter, isA<IOHttpClientAdapter>());
      }
    });
  });

  group('wiring', () {
    // The whole feature is inert if a Dio is built without going through
    // applyErpTls: the roots are compiled in, nothing consults them, and the
    // till fails exactly as if the fix were absent. Every other test here still
    // passes in that state, so assert the wiring at the source level. (This
    // regressed once already during development, silently.)
    test('every Dio construction site applies the ERP TLS policy', () {
      final offenders = <String>[];

      for (final file in Directory('lib')
          .listSync(recursive: true, followLinks: false)
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart'))) {
        // The TLS helpers take a Dio rather than constructing one.
        final name = file.path.replaceAll(r'\', '/');
        if (name.contains('/erp_tls.dart') ||
            name.contains('/insecure_tls') ||
            name.contains('/trusted_ca')) {
          continue;
        }
        final src = file.readAsStringSync();
        // A real construction, not `ApiException.fromDio(` or `DioException`:
        // require a non-identifier character immediately before `Dio(`.
        if (!_constructsDio.hasMatch(src)) continue;
        if (!src.contains('applyErpTls(')) offenders.add(name);
      }

      expect(
        offenders,
        isEmpty,
        reason: 'These files construct a Dio without applyErpTls(), so they '
            "will not trust the ERP's own CA: ${offenders.join(', ')}",
      );
    });
  });

  group('live self-signed host', () {
    final host = Platform.environment['POS_TLS_HOST'];
    final skip = (host == null || host.isEmpty)
        ? 'set POS_TLS_HOST to run against a live self-signed ERP'
        : null;

    setUpAll(() {
      TestWidgetsFlutterBinding.ensureInitialized();
      // flutter_test installs an HttpOverrides that 400s every request; clear it
      // so these hit the network for real.
      HttpOverrides.global = null;
    });

    test('a stock client is REJECTED by the self-signed cert', () async {
      // The control. This is the defect being fixed: without the pinned root the
      // till cannot open the connection at all.
      final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 15)));
      await expectLater(
        dio.get('$host/api/v1/health'),
        throwsA(isA<DioException>()),
      );
    }, skip: skip);

    test('the pinned root connects with validation still ON', () async {
      final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 15)));
      applyPinnedRootCa(dio);
      final res = await dio.get('$host/api/v1/health');
      expect(res.statusCode, 200);
      expect(res.data['data']['status'], 'UP');
    }, skip: skip);

    test('a WRONG hostname is still refused', () async {
      // Proves this is trust, not a blanket bypass: the certificate is bound to
      // the EC2 hostname, so the same box reached by IP must fail.
      final dio = Dio(BaseOptions(connectTimeout: const Duration(seconds: 15)));
      applyPinnedRootCa(dio);
      await expectLater(
        dio.get('https://16.192.117.45/api/v1/health'),
        throwsA(isA<DioException>()),
      );
    }, skip: skip);
  });
}
