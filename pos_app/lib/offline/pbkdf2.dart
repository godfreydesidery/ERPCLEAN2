import 'dart:isolate';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// PBKDF2-HMAC-SHA256 (RFC 8018 §5.2), in pure Dart — the slow key derivation
/// behind offline sign-in verifiers (ERP ADR-0068 D-7). No native code, so it
/// runs the same on the Windows and Android tills.
Uint8List pbkdf2Sha256(List<int> secret, List<int> salt, int iterations, {int length = 32}) {
  if (iterations < 1) throw ArgumentError.value(iterations, 'iterations');
  final hmac = Hmac(sha256, secret);
  final out = BytesBuilder(copy: false);
  var block = 1;
  while (out.length < length) {
    final first = Uint8List(salt.length + 4)
      ..setAll(0, salt)
      ..[salt.length] = (block >> 24) & 0xFF
      ..[salt.length + 1] = (block >> 16) & 0xFF
      ..[salt.length + 2] = (block >> 8) & 0xFF
      ..[salt.length + 3] = block & 0xFF;
    var u = hmac.convert(first).bytes;
    final t = Uint8List.fromList(u);
    for (var i = 1; i < iterations; i++) {
      u = hmac.convert(u).bytes;
      for (var j = 0; j < t.length; j++) {
        t[j] ^= u[j];
      }
    }
    out.add(t);
    block++;
  }
  return Uint8List.fromList(out.takeBytes().sublist(0, length));
}

/// [pbkdf2Sha256] off the UI thread when it is costly, so signing in never
/// freezes the till.
Future<Uint8List> pbkdf2Sha256Async(List<int> secret, List<int> salt, int iterations,
    {int length = 32}) {
  if (iterations < 5000) return Future.value(pbkdf2Sha256(secret, salt, iterations, length: length));
  final s = List<int>.of(secret);
  final sa = List<int>.of(salt);
  return Isolate.run(() => pbkdf2Sha256(s, sa, iterations, length: length));
}

/// Compares two byte strings in time independent of where they differ.
bool constantTimeEquals(List<int> a, List<int> b) {
  if (a.length != b.length) return false;
  var diff = 0;
  for (var i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff == 0;
}
