import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/core/api/token_manager.dart';
import 'package:pos_app/core/storage/secure_store.dart';
import 'package:pos_app/models/auth.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Answers each request with whatever [route] returns for its path. Returning
/// a [DioException] makes the request fail at the transport level.
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.route);
  final Object Function(RequestOptions o) route;
  final List<String> calls = [];

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<Uint8List>? body,
      Future<void>? cancel) async {
    calls.add(o.path);
    final r = route(o);
    if (r is DioException) throw r;
    final (status, data) = r as (int, Object?);
    return ResponseBody.fromString(jsonEncode(data), status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

Map<String, dynamic> _bundleJson(String access, String refresh) => {
      'accessToken': access,
      'accessTokenExpiresAt':
          DateTime.now().add(const Duration(minutes: 15)).millisecondsSinceEpoch ~/
              1000,
      'refreshToken': refresh,
      'user': {
        'uid': 'U1',
        'username': 'cashier',
        'displayName': 'Cashier',
        'isRoot': false,
        'activeCompanyUid': 'C1',
        'activeBranchUid': 'B1',
        'hasBranch': true,
      },
    };

Map<String, dynamic> _envelope(Object? data, [List<String> errors = const []]) =>
    {'data': data, 'errors': errors, 'meta': null};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _FakeAdapter adapter;
  late TokenManager tokens;
  late ApiClient api;
  late int authLost;

  /// [onRefresh] decides what `/auth/refresh` answers; every other path
  /// answers 401 to the first token and 200 to the renewed one.
  Future<void> setUp0(Object Function() onRefresh) async {
    SharedPreferences.setMockInitialValues({});
    adapter = _FakeAdapter((o) {
      if (o.path == '/auth/refresh') return onRefresh();
      final bearer = o.headers['Authorization'];
      if (bearer == 'Bearer A2') return (200, _envelope({'ok': true}));
      return (401, _envelope(null));
    });
    tokens = TokenManager(
        apiBase: 'http://erp.test/api/v1',
        store: SecureStore(),
        httpAdapter: adapter);
    api = ApiClient(
        apiBase: 'http://erp.test/api/v1', tokens: tokens, httpAdapter: adapter);
    authLost = 0;
    api.onAuthLost = () => authLost++;
    await tokens.setSession(TokenBundle.fromJson(_bundleJson('A1', 'R1')));
  }

  test('a renewed session retries the request and stays signed in', () async {
    await setUp0(() => (200, _envelope(_bundleJson('A2', 'R2'))));

    final data = await api.get('/pos/sessions/mine');

    expect(data, {'ok': true});
    expect(authLost, 0);
    expect(tokens.bundle?.refreshToken, 'R2');
  });

  test('a refused renewal signs the cashier out and clears the session',
      () async {
    await setUp0(() =>
        (401, _envelope(null, ['Refresh token already used. Please sign in again.'])));

    await expectLater(api.get('/pos/sessions/mine'),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'status', 401)));

    expect(authLost, 1);
    expect(tokens.hasSession, isFalse);
  });

  test('a renewal that cannot reach the ERP is NOT a sign-out', () async {
    await setUp0(() => DioException.connectionError(
        requestOptions: RequestOptions(path: '/auth/refresh'),
        reason: 'connection refused'));

    await expectLater(
        api.get('/pos/sessions/mine'),
        throwsA(isA<ApiException>()
            .having((e) => e.isNetwork, 'isNetwork', isTrue)
            .having((e) => e.statusCode, 'status', isNull)));

    expect(authLost, 0, reason: 'a dead line must not read as "session ended"');
    expect(tokens.hasSession, isTrue);
    expect(tokens.bundle?.refreshToken, 'R1',
        reason: 'the unused refresh token is kept for the next attempt');
  });

  test('a renewal that times out is NOT a sign-out', () async {
    await setUp0(() => DioException.receiveTimeout(
        timeout: const Duration(seconds: 20),
        requestOptions: RequestOptions(path: '/auth/refresh')));

    await expectLater(api.get('/pos/sessions/mine'),
        throwsA(isA<ApiException>().having((e) => e.isTimeout, 'isTimeout', isTrue)));

    expect(authLost, 0);
    expect(tokens.hasSession, isTrue);
  });

  test('a server error on renewal is NOT a sign-out', () async {
    await setUp0(() => (503, _envelope(null)));

    await expectLater(api.get('/pos/sessions/mine'),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'status', 503)));

    expect(authLost, 0);
    expect(tokens.hasSession, isTrue);
  });

  test('after the ERP comes back, the kept session renews normally', () async {
    var up = false;
    await setUp0(() => up
        ? (200, _envelope(_bundleJson('A2', 'R2')))
        : DioException.connectionError(
            requestOptions: RequestOptions(path: '/auth/refresh'),
            reason: 'connection refused'));

    await expectLater(api.get('/pos/sessions/mine'), throwsA(isA<ApiException>()));
    up = true;
    final data = await api.get('/pos/sessions/mine');

    expect(data, {'ok': true});
    expect(authLost, 0);
  });
}
