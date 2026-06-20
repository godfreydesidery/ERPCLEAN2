import 'dart:convert';

/// Extracts the `sub` claim (the numeric user id) from a JWT access token.
/// The signature is NOT verified — the server already validated it; this is only
/// to read the caller's own id locally (e.g. to match their open POS session).
/// Returns null if the token is absent or malformed.
String? jwtSub(String? token) {
  final claims = jwtClaims(token);
  return claims?['sub']?.toString();
}

/// Decodes a JWT payload to a claims map (unverified). Null on any error.
Map<String, dynamic>? jwtClaims(String? token) {
  if (token == null) return null;
  final parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    var seg = parts[1].replaceAll('-', '+').replaceAll('_', '/');
    while (seg.length % 4 != 0) {
      seg += '=';
    }
    final decoded = utf8.decode(base64.decode(seg));
    final json = jsonDecode(decoded);
    return json is Map ? json.cast<String, dynamic>() : null;
  } catch (_) {
    return null;
  }
}
