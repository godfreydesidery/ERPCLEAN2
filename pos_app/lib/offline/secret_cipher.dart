import 'dart:typed_data';

import 'secret_cipher_stub.dart'
    if (dart.library.io) 'secret_cipher_io.dart' as impl;

/// Thrown when the device cannot seal or open secret data. The message is
/// user-safe: it is what the cashier reads when an offline sale is refused
/// because it could not be stored securely.
class SecretCipherException implements Exception {
  SecretCipherException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Encrypts the data the owner ruled must never sit on the device in the clear
/// (ADR-0068, resolved question 3): the queue of unsynced offline sales, and
/// any offline sign-in verifiers a later phase adds.
///
/// The key never leaves the device's own key store:
///
/// * **Windows** — DPAPI (`CryptProtectData`), bound to the signed-in Windows
///   account. The blob carries its own integrity check, so a tampered file
///   fails to open instead of decrypting to garbage.
/// * **Android** — an AES-256-GCM key generated inside the Android Keystore
///   (`MainActivity.kt`, channel `orbix/keystore`). The key is not exportable.
///
/// Anywhere else there is no key store this app trusts, and [platformCipher]
/// returns a cipher that refuses — an offline sale is then refused rather than
/// written in the clear. `flutter_secure_storage` is deliberately not used: its
/// Windows plugin needs the Visual Studio ATL component the till image lacks.
abstract class SecretCipher {
  /// Encrypts [plain]. Throws [SecretCipherException] when it cannot.
  Future<Uint8List> seal(Uint8List plain);

  /// Decrypts what [seal] produced. Throws [SecretCipherException] when the data
  /// cannot be opened (wrong device, wrong Windows user, tampered).
  Future<Uint8List> open(Uint8List sealed);

  /// Which key store does the work, for the sync screen ("Windows DPAPI").
  String get name;
}

/// The cipher for the platform the app is running on.
SecretCipher platformCipher() => impl.platformCipher();
