import 'package:dio/dio.dart';

/// Response header carrying the POS sale path's machine-readable outcome
/// (`PosSaleController.POS_SALE_STATUS_HEADER`).
const String kPosSaleStatusHeader = 'X-Pos-Sale-Status';

/// One error entry from the `errors[]` of the `ApiResponse` envelope.
class ApiError {
  const ApiError(this.code, this.message);
  final String code;
  final String message;

  factory ApiError.fromJson(Map<String, dynamic> j) =>
      ApiError((j['code'] ?? '').toString(), (j['message'] ?? '').toString());
}

/// The single error type the app branches on. Per the API contract (AS-7, G-9)
/// the UI **branches on [statusCode], never on the human text** in [errors];
/// the text is shown to the cashier but never drives control flow.
///
/// [isAmbiguous] is the critical one for write-safety (PRIN-4): a request that
/// got no HTTP response (timeout / connection drop) may or may not have reached
/// the server, so a `POST /pos/sales` in this state must be reconciled, never
/// blind-retried.
class ApiException implements Exception {
  ApiException({
    required this.statusCode,
    required this.message,
    this.errors = const [],
    this.isNetwork = false,
    this.isTimeout = false,
    this.code,
    this.cause,
  });

  /// HTTP status, or null when no response was received (network/timeout).
  final int? statusCode;

  /// Friendly, status-derived message for the cashier.
  final String message;

  /// Raw server errors (for display/debug only — not for branching).
  final List<ApiError> errors;

  final bool isNetwork;
  final bool isTimeout;

  /// The server's **machine-readable** outcome token for this refusal, when it
  /// sent one: `data.code` in the envelope, else the `X-Pos-Sale-Status`
  /// response header (the sale path sends both — see `PosSaleController`).
  ///
  /// This is what control flow may branch on, alongside [statusCode]. The
  /// human text in [errors]/[message] is for the cashier's eyes only and must
  /// never decide anything: it is written for a person, it gets reworded, and
  /// it is translated.
  final String? code;

  final Object? cause;

  /// True when the outcome is *unknown* — no response came back. The sale path
  /// must reconcile-before-resend rather than assume failure.
  bool get isAmbiguous => statusCode == null && (isTimeout || isNetwork);

  /// Like [isAmbiguous] but also true for a 5xx: a server error AFTER a write
  /// (e.g. a 502/504 once the sale row committed) is an unknown outcome too, so
  /// the idempotent sale path must reconcile-by-resending the same key — never
  /// retry with a fresh key.
  bool get isAmbiguousWrite => isAmbiguous || isServer;

  bool get isUnauthorized => statusCode == 401;
  bool get isForbidden => statusCode == 403;
  bool get isNotFound => statusCode == 404;
  bool get isConflict => statusCode == 409;
  bool get isValidation => statusCode == 400 || statusCode == 422;
  bool get isServer => statusCode != null && statusCode! >= 500;

  /// First server error code, when present — for diagnostics/logging only.
  String? get firstCode => errors.isNotEmpty ? errors.first.code : null;

  /// Builds an [ApiException] from a Dio failure, mapping by transport/status.
  factory ApiException.fromDio(DioException e) {
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return ApiException(
          statusCode: null,
          isTimeout: true,
          message: 'The server did not respond in time. '
              'The request may or may not have completed.',
          cause: e,
        );
      case DioExceptionType.connectionError:
      case DioExceptionType.unknown:
        return ApiException(
          statusCode: null,
          isNetwork: true,
          message: 'Cannot reach the ERP. Check the connection and host.',
          cause: e,
        );
      case DioExceptionType.badCertificate:
        return ApiException(
          statusCode: null,
          isNetwork: true,
          message: 'The server certificate was rejected.',
          cause: e,
        );
      case DioExceptionType.cancel:
        return ApiException(
          statusCode: null,
          message: 'The request was cancelled.',
          cause: e,
        );
      case DioExceptionType.badResponse:
        return ApiException.fromResponse(e.response!, cause: e);
    }
  }

  /// Builds from a non-2xx [Response], extracting `errors[]` from the envelope,
  /// the machine-readable [code], and a friendly message chosen by status code.
  factory ApiException.fromResponse(Response response, {Object? cause}) {
    final status = response.statusCode;
    final errors = _extractErrors(response.data);
    return ApiException(
      statusCode: status,
      errors: errors,
      code: _extractCode(response),
      message: _messageFor(status, errors),
      cause: cause,
    );
  }

  /// The refusal's machine-readable token. Prefers `data.code` from the
  /// envelope; falls back to the `X-Pos-Sale-Status` header, which carries the
  /// same value so a client that never parses the body still gets it.
  static String? _extractCode(Response response) {
    final data = response.data;
    if (data is Map && data['data'] is Map) {
      final inner = (data['data'] as Map)['code'];
      final s = inner?.toString().trim();
      if (s != null && s.isNotEmpty) return s;
    }
    final header = response.headers.value(kPosSaleStatusHeader);
    final h = header?.trim();
    return (h == null || h.isEmpty) ? null : h;
  }

  static List<ApiError> _extractErrors(dynamic data) {
    if (data is Map && data['errors'] is List) {
      return (data['errors'] as List)
          .map(_toApiError)
          .whereType<ApiError>()
          .toList();
    }
    return const [];
  }

  /// Maps one entry of the envelope's `errors` array to an [ApiError].
  ///
  /// The ERP `ApiResponse<T>` envelope emits `errors` as a **`List<String>`** of
  /// user-safe messages, so a plain string is the real, common case and must be
  /// carried as the message. A `{code, message}` object is also accepted
  /// defensively (alternate shapes); anything else is ignored. (Previously only
  /// the object shape was accepted, so every real string error was silently
  /// dropped and the UI always fell back to a generic per-status message — e.g.
  /// a 400 "Product has no price configured…" surfaced as "The request was
  /// rejected. Please check the entry.")
  static ApiError? _toApiError(dynamic e) {
    if (e is String) return ApiError('', e);
    if (e is Map) return ApiError.fromJson(e.cast<String, dynamic>());
    return null;
  }

  /// The server's user-safe error text as a single string, or null when there is
  /// none. Multiple messages (e.g. several field-validation errors on one 400)
  /// are joined so the cashier sees every reason, not just the first. A
  /// blank/whitespace-only message is treated as absent.
  static String? _serverMessage(List<ApiError> errors) {
    final msgs = errors
        .map((e) => e.message.trim())
        .where((m) => m.isNotEmpty)
        .toList();
    return msgs.isEmpty ? null : msgs.join('; ');
  }

  static String _messageFor(int? status, List<ApiError> errors) {
    // Prefer the server's user-safe message(s) when present, but selection is
    // still driven by status, not by the text itself. A blank message is treated
    // as absent so the per-status fallback still applies.
    final serverMsg = _serverMessage(errors);
    switch (status) {
      case 400:
        return serverMsg ?? 'The request was rejected. Please check the entry.';
      case 401:
        // On the login path the server explains the real reason (account
        // locked / invalid credentials) in errors[]; surface it. A bodyless
        // 401 (an expired token mid-session) has no serverMsg and still reads
        // as a session-expiry prompt.
        return serverMsg ?? 'Your session has expired. Please sign in again.';
      case 403:
        return 'You do not have permission for this action.';
      case 404:
        return serverMsg ?? 'Not found.';
      case 409:
        return serverMsg ?? 'This conflicts with the current state.';
      case 415:
        return 'Unsupported request format.';
      case 422:
        return serverMsg ?? 'The request could not be processed.';
      default:
        if (status != null && status >= 500) {
          return 'The ERP had a server error. Please try again.';
        }
        return serverMsg ?? 'Something went wrong (HTTP $status).';
    }
  }

  @override
  String toString() => 'ApiException($statusCode): $message';
}
