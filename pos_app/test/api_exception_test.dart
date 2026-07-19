import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_exception.dart';

Response _response(int status, Object? data) => Response(
      requestOptions: RequestOptions(path: '/auth/login'),
      statusCode: status,
      data: data,
    );

void main() {
  // The ERP `ApiResponse<T>` envelope emits `errors` as a List<String> of
  // user-safe messages (backend ApiResponse.java — `errors` is List<String>).
  // These tests use that REAL wire shape; a `{code,message}` object shape is
  // also accepted defensively.
  group('ApiException surfaces the server message (real List<String> shape)', () {
    test('400 sale rejection surfaces the exact server reason (not the generic '
        'fallback)', () {
      // Regression for the 2026-07-19 prod incident: a POS sale 400 whose real
      // reason was masked because the client parsed errors as objects only.
      final e = ApiException.fromResponse(_response(400, {
        'data': null,
        'errors': ['Product has no price configured for this company.'],
        'meta': null,
      }));
      expect(e.isValidation, isTrue);
      expect(e.message, 'Product has no price configured for this company.');
    });

    test('400 with multiple field errors joins every reason', () {
      final e = ApiException.fromResponse(_response(400, {
        'errors': ['customerId: must not be null', 'lines: must not be empty'],
      }));
      expect(e.message,
          'customerId: must not be null; lines: must not be empty');
    });

    test('login-path 401 (bad credentials) surfaces the server string', () {
      final e = ApiException.fromResponse(_response(401, {
        'errors': ['Invalid username or password.'],
      }));
      expect(e.isUnauthorized, isTrue);
      expect(e.message, 'Invalid username or password.');
    });

    test('409 conflict surfaces the server string', () {
      final e = ApiException.fromResponse(_response(409, {
        'errors': ['This POS session is not OPEN.'],
      }));
      expect(e.isConflict, isTrue);
      expect(e.message, 'This POS session is not OPEN.');
    });
  });

  group('Defensive: object {code,message} shape still works', () {
    test('401 object-shape error surfaces the message', () {
      final e = ApiException.fromResponse(_response(401, {
        'errors': [
          {'code': 'AUTH_LOCKED', 'message': 'Account is locked. Try again later.'}
        ],
      }));
      expect(e.message, 'Account is locked. Try again later.');
    });
  });

  group('Fallbacks when the server sent no usable message', () {
    test('400 with empty errors[] falls back to the generic 400 message', () {
      final e = ApiException.fromResponse(_response(400, {'errors': []}));
      expect(e.message, 'The request was rejected. Please check the entry.');
    });

    test('400 with a blank error string falls back (blank treated as absent)',
        () {
      final e = ApiException.fromResponse(_response(400, {
        'errors': ['   '],
      }));
      expect(e.message, 'The request was rejected. Please check the entry.');
    });

    test('bodyless mid-session 401 still reads as session expiry', () {
      final e = ApiException.fromResponse(_response(401, null));
      expect(e.message, 'Your session has expired. Please sign in again.');
    });

    test('401 with empty errors[] falls back to session expiry', () {
      final e = ApiException.fromResponse(_response(401, {'errors': []}));
      expect(e.message, 'Your session has expired. Please sign in again.');
    });

    test('403 is always the fixed permission message, not a server string', () {
      final e = ApiException.fromResponse(_response(403, {
        'errors': ['You do not have permission to perform this action.'],
      }));
      expect(e.isForbidden, isTrue);
      expect(e.message, 'You do not have permission for this action.');
    });
  });
}
