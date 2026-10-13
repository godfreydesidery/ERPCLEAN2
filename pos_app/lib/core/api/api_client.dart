import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import 'api_exception.dart';
import 'api_response.dart';
import 'erp_tls.dart';
import 'token_manager.dart';

const _uuid = Uuid();

/// The authenticated ERP client. Wraps Dio with the four POS headers and the
/// transparent-refresh-on-401 retry, and unwraps the `ApiResponse` envelope so
/// callers get the `data` payload directly. Every method throws [ApiException]
/// (status-driven) on failure — callers branch on status, never on text.
class ApiClient {
  ApiClient({
    required String apiBase,
    required TokenManager tokens,
    @visibleForTesting HttpClientAdapter? httpAdapter,
  })  : _tokens = tokens,
        _dio = Dio(BaseOptions(
          baseUrl: apiBase,
          contentType: 'application/json',
          connectTimeout: const Duration(seconds: 15),
          receiveTimeout: const Duration(seconds: 30),
          // We map non-2xx ourselves; let Dio surface them as errors.
        )) {
    applyErpTls(_dio);
    if (httpAdapter != null) _dio.httpClientAdapter = httpAdapter;
    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: _onRequest,
      onError: _onError,
    ));
  }

  final Dio _dio;
  final TokenManager _tokens;

  /// When set (a non-default branch is active), sent as `X-Branch-Uid` so the
  /// caller acts in that branch without re-login (§02).
  String? branchUidOverride;

  /// Invoked when a 401 could not be refreshed away — the app routes to login.
  void Function()? onAuthLost;

  // ---------------------------------------------------------------- interceptors

  Future<void> _onRequest(
      RequestOptions options, RequestInterceptorHandler handler) async {
    if (options.extra['noAuth'] != true) {
      final token = await _tokens.accessTokenForRequest();
      if (token != null) options.headers['Authorization'] = 'Bearer $token';
    }
    // Durable correlation id — reused across retries so a double-post is findable.
    final reqId = (options.extra['xRequestId'] as String?) ?? _uuid.v4();
    options.extra['xRequestId'] = reqId;
    options.headers['X-Request-Id'] = reqId;

    final idem = options.extra['idempotencyKey'] as String?;
    if (idem != null) options.headers['Idempotency-Key'] = idem;

    if (branchUidOverride != null && options.extra['noBranch'] != true) {
      options.headers['X-Branch-Uid'] = branchUidOverride;
    }
    handler.next(options);
  }

  Future<void> _onError(
      DioException err, ErrorInterceptorHandler handler) async {
    final res = err.response;
    final opts = err.requestOptions;
    final isRefresh = opts.path.contains('/auth/refresh');
    final alreadyRetried = opts.extra['retried'] == true;

    if (res?.statusCode == 401 && !isRefresh && !alreadyRetried) {
      final refresh = await _tokens.refreshNow();
      switch (refresh.outcome) {
        case RefreshOutcome.refreshed:
          opts.extra['retried'] = true;
          final token = _tokens.bundle?.accessToken;
          if (token != null) opts.headers['Authorization'] = 'Bearer $token';
          try {
            final clone = await _dio.fetch(opts);
            return handler.resolve(clone);
          } on DioException catch (e) {
            return handler.next(e);
          }
        case RefreshOutcome.rejected:
          onAuthLost?.call();
        case RefreshOutcome.unavailable:
          // The session could not be renewed because the ERP did not answer —
          // NOT because it refused. Signing the cashier out here was a false
          // "Your session ended" whenever the server was restarting or slow.
          // Keep the session and report what actually happened, so the caller
          // reads "can't reach the ERP" (and the sale path, seeing no answer,
          // reconciles with the same idempotency key — a 401 never ran it).
          final f = refresh.failure!;
          return handler.next(DioException(
            requestOptions: opts,
            response: f.response,
            type: f.type,
            error: f.error,
            message: f.message,
          ));
      }
    }
    handler.next(err);
  }

  // ---------------------------------------------------------------- verbs

  /// GET, returning the unwrapped `data`.
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    return _send(() => _dio.get(path, queryParameters: _clean(query)));
  }

  /// POST, returning the unwrapped `data`. Pass [idempotencyKey] for the sale
  /// path and [xRequestId] to correlate retries of one logical operation.
  Future<dynamic> post(
    String path, {
    Object? body,
    Map<String, dynamic>? query,
    String? idempotencyKey,
    String? xRequestId,
    bool noAuth = false,
  }) async {
    final extra = <String, dynamic>{};
    if (idempotencyKey != null) extra['idempotencyKey'] = idempotencyKey;
    if (xRequestId != null) extra['xRequestId'] = xRequestId;
    if (noAuth) extra['noAuth'] = true;
    return _send(() => _dio.post(
          path,
          data: body,
          queryParameters: _clean(query),
          options: Options(extra: extra),
        ));
  }

  /// DELETE; returns the unwrapped `data` (usually null/204).
  Future<dynamic> delete(String path, {Object? body}) async {
    return _send(() => _dio.delete(path, data: body));
  }

  Future<dynamic> _send(Future<Response> Function() call) async {
    try {
      final res = await call();
      return unwrapData(res.data);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  Map<String, dynamic>? _clean(Map<String, dynamic>? q) {
    if (q == null) return null;
    final out = <String, dynamic>{};
    q.forEach((k, v) {
      if (v != null) out[k] = v;
    });
    return out;
  }

  /// A fresh idempotency/transaction id for one logical sale.
  static String newTxnId() => _uuid.v4();
}
