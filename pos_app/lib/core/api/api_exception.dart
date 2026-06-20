import 'package:dio/dio.dart';

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

  /// Builds from a non-2xx [Response], extracting `errors[]` from the envelope
  /// and choosing a friendly message by status code.
  factory ApiException.fromResponse(Response response, {Object? cause}) {
    final status = response.statusCode;
    final errors = _extractErrors(response.data);
    return ApiException(
      statusCode: status,
      errors: errors,
      message: _messageFor(status, errors),
      cause: cause,
    );
  }

  static List<ApiError> _extractErrors(dynamic data) {
    if (data is Map && data['errors'] is List) {
      return (data['errors'] as List)
          .whereType<Map>()
          .map((m) => ApiError.fromJson(m.cast<String, dynamic>()))
          .toList();
    }
    return const [];
  }

  static String _messageFor(int? status, List<ApiError> errors) {
    // Prefer the server's first message when it's a client-actionable 4xx, but
    // selection is still driven by status, not by the text itself.
    final serverMsg = errors.isNotEmpty ? errors.first.message : null;
    switch (status) {
      case 400:
        return serverMsg ?? 'The request was rejected. Please check the entry.';
      case 401:
        return 'Your session has expired. Please sign in again.';
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
