import 'dart:typed_data';

import 'secret_cipher.dart';

/// The web build has no key store this app trusts: offline sales are refused.
SecretCipher platformCipher() => const UnavailableCipher();

/// A cipher that always refuses, so nothing secret is ever written in the clear.
class UnavailableCipher implements SecretCipher {
  const UnavailableCipher();

  static const _msg =
      'This device has no secure key store, so it cannot keep offline sales.';

  @override
  String get name => 'none';

  @override
  Future<Uint8List> seal(Uint8List plain) async => throw SecretCipherException(_msg);

  @override
  Future<Uint8List> open(Uint8List sealed) async => throw SecretCipherException(_msg);
}
