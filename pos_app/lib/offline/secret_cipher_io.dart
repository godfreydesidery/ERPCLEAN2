// The key stores on the native builds: Windows DPAPI through win32 FFI (the
// same package the receipt spooler uses), and the Android Keystore through the
// app's own Kotlin bridge. Compiled on every dart:io target; each entry point
// checks the platform at run time, like receipt_printer_win32.dart.
import 'dart:ffi';
import 'dart:io' show Platform;

import 'package:ffi/ffi.dart';
import 'package:flutter/services.dart';
import 'package:win32/win32.dart';

import 'secret_cipher.dart';
import 'secret_cipher_stub.dart';

SecretCipher platformCipher() {
  if (Platform.isWindows) return const DpapiCipher();
  if (Platform.isAndroid) return const AndroidKeystoreCipher();
  return const UnavailableCipher();
}

/// `CRYPTPROTECT_UI_FORBIDDEN`: never show a prompt (a till has nobody to
/// answer one). Not exported by package:win32.
const int _cryptProtectUiForbidden = 0x1;

/// Windows DPAPI, current-user scope. Encrypts with a key Windows derives from
/// the signed-in account's credentials; another Windows user, or the file
/// copied to another PC, cannot open it.
class DpapiCipher implements SecretCipher {
  const DpapiCipher();

  /// Extra entropy mixed into the key, so another program running as the same
  /// Windows user cannot open the blob with a plain CryptUnprotectData call.
  /// Not a secret in itself — DPAPI's own key is.
  static final Uint8List _entropy =
      Uint8List.fromList('OrbixPOS offline queue v1'.codeUnits);

  @override
  String get name => 'Windows DPAPI';

  @override
  Future<Uint8List> seal(Uint8List plain) async => _run(plain, protect: true);

  @override
  Future<Uint8List> open(Uint8List sealed) async => _run(sealed, protect: false);

  Uint8List _run(Uint8List input, {required bool protect}) {
    if (!Platform.isWindows) {
      throw SecretCipherException('Windows key store is only available on Windows.');
    }
    return using((arena) {
      final dataIn = arena<CRYPT_INTEGER_BLOB>();
      final inBuf = arena<Uint8>(input.isEmpty ? 1 : input.length);
      if (input.isNotEmpty) inBuf.asTypedList(input.length).setAll(0, input);
      dataIn.ref
        ..cbData = input.length
        ..pbData = inBuf;

      final entropy = arena<CRYPT_INTEGER_BLOB>();
      final entBuf = arena<Uint8>(_entropy.length);
      entBuf.asTypedList(_entropy.length).setAll(0, _entropy);
      entropy.ref
        ..cbData = _entropy.length
        ..pbData = entBuf;

      final dataOut = arena<CRYPT_INTEGER_BLOB>();
      final ok = protect
          ? CryptProtectData(dataIn, null, entropy, null, _cryptProtectUiForbidden, dataOut)
          : CryptUnprotectData(dataIn, null, entropy, null, _cryptProtectUiForbidden, dataOut);
      if (!ok.value) {
        throw SecretCipherException(protect
            ? 'Windows could not encrypt the offline sales.'
            : 'The offline sales on this PC could not be opened by this Windows user.');
      }
      try {
        final n = dataOut.ref.cbData;
        return Uint8List.fromList(dataOut.ref.pbData.asTypedList(n));
      } finally {
        // DPAPI allocates the output with LocalAlloc; it is ours to free.
        HLOCAL(dataOut.ref.pbData.cast()).close();
      }
    });
  }
}

/// Android Keystore: AES-256-GCM with a non-exportable key generated inside
/// the Keystore (`MainActivity.kt`, channel `orbix/keystore`). The sealed form
/// is `iv ‖ ciphertext+tag`, produced and checked on the Kotlin side.
class AndroidKeystoreCipher implements SecretCipher {
  const AndroidKeystoreCipher();

  static const _channel = MethodChannel('orbix/keystore');

  @override
  String get name => 'Android Keystore';

  @override
  Future<Uint8List> seal(Uint8List plain) => _call('seal', plain);

  @override
  Future<Uint8List> open(Uint8List sealed) => _call('open', sealed);

  Future<Uint8List> _call(String method, Uint8List bytes) async {
    try {
      final out = await _channel.invokeMethod<Uint8List>(method, {'bytes': bytes});
      if (out == null) throw SecretCipherException('The key store returned nothing.');
      return out;
    } on PlatformException catch (e) {
      throw SecretCipherException(e.message ?? 'The Android key store refused.');
    } on MissingPluginException {
      throw SecretCipherException('This build has no Android key store bridge.');
    }
  }
}
