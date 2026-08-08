import '../core/json.dart';

/// Result of `POST /api/v1/auth/verify-authority` — the manager step-up check.
///
/// The endpoint answers HTTP **200** whether or not the credentials were
/// accepted, carrying the verdict in [authorised]. That is deliberate: a refusal
/// must let the manager retype without the sale being unwound, so callers MUST
/// branch on [authorised] — never on the status code, and never on [message].
///
/// It issues **no token** and does not touch the cashier's session: the same
/// user stays signed in, in the same branch, before and after.
class AuthorityVerification {
  const AuthorityVerification({
    required this.authorised,
    required this.permissionCode,
    required this.message,
    this.authoriserUid,
    this.authoriserUsername,
    this.authoriserName,
  });

  final bool authorised;

  /// The permission code that was asked about (echoed by the server).
  final String permissionCode;

  /// Friendly text for the operator. Display only — never branch on it.
  final String message;

  /// Who approved. All three are null when [authorised] is false — the server
  /// deliberately discloses nothing about who does or does not exist.
  final String? authoriserUid;
  final String? authoriserUsername;
  final String? authoriserName;

  /// Best available human name for the "Approved by …" stamp.
  String get approverLabel {
    final name = authoriserName?.trim();
    if (name != null && name.isNotEmpty) return name;
    final username = authoriserUsername?.trim();
    if (username != null && username.isNotEmpty) return username;
    return 'a manager';
  }

  factory AuthorityVerification.fromJson(Map<String, dynamic> j) =>
      AuthorityVerification(
        authorised: asBool(j['authorised']),
        permissionCode: asStrOr(j['permissionCode']),
        message: asStrOr(j['message']),
        authoriserUid: asStr(j['authoriserUid']),
        authoriserUsername: asStr(j['authoriserUsername']),
        authoriserName: asStr(j['authoriserName']),
      );
}
