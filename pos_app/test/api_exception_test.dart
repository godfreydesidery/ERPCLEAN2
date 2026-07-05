import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_exception.dart';

Response _response(int status, Object? data) => Response(
      requestOptions: RequestOptions(path: '/auth/login'),
      statusCode: status,
      data: data,
    );

void main() {
  group('ApiException 401 messaging (I7)', () {
    test('login-path 401 (account locked) surfaces the server message', () {
      final e = ApiException.fromResponse(_response(401, {
        'data': null,
        'errors': [
          {'code': 'AUTH_LOCKED', 'message': 'Account is locked. Try again later.'}
        ],
      }));
      expect(e.isUnauthorized, isTrue);
      expect(e.message, 'Account is locked. Try again later.');
    });

    test('login-path 401 (bad credentials) surfaces the server message', () {
      final e = ApiException.fromResponse(_response(401, {
        'errors': [
          {'code': 'AUTH_BAD_CREDENTIALS', 'message': 'Invalid username or password.'}
        ],
      }));
      expect(e.message, 'Invalid username or password.');
    });

    test('bodyless mid-session 401 still reads as session expiry', () {
      final e = ApiException.fromResponse(_response(401, null));
      expect(e.message, 'Your session has expired. Please sign in again.');
    });

    test('401 with empty errors[] falls back to session expiry', () {
      final e = ApiException.fromResponse(_response(401, {'errors': []}));
      expect(e.message, 'Your session has expired. Please sign in again.');
    });
  });
}
