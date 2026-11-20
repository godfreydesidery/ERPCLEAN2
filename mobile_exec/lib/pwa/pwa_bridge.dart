import 'pwa_bridge_stub.dart'
    if (dart.library.js_interop) 'pwa_bridge_web.dart'
    as impl;

/// The app's line to web/pwa.js. On Android (no js_interop) this is a stub
/// that reports [PwaState.inert] and does nothing - the APK is unaffected.
abstract class PwaBridge {
  /// The current state as JSON (see PwaState.fromJson), or null when there is
  /// no PWA layer (APK, tests, or pwa.js missing).
  String? stateJson();

  /// Calls [onChange] with the new state JSON whenever it changes.
  void subscribe(void Function(String json) onChange);

  /// Shows the browser's install dialog: 'accepted', 'dismissed' or
  /// 'unavailable'.
  Future<String> promptInstall();

  /// "Not now" on the install hint (remembered for 30 days).
  void snoozeInstallHint();

  /// Activates the waiting version and reloads.
  void applyUpdate();

  factory PwaBridge() => impl.createBridge();
}
