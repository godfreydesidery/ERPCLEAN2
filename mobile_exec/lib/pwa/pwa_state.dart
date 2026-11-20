import 'dart:convert';

/// What the browser side of the PWA (web/pwa.js) knows, as the app sees it.
///
/// Pure data + decisions, no platform code, so the banner rules are unit-tested
/// (test/pwa_state_test.dart) without a browser.
class PwaState {
  const PwaState({
    this.online = true,
    this.standalone = false,
    this.canPrompt = false,
    this.ios = false,
    this.installed = false,
    this.swActive = false,
    this.updateReady = false,
    this.hintSnoozed = false,
    this.version = '',
  });

  /// Off the web (the Android APK) there is no PWA: every flag stays at its
  /// quiet default and nothing is ever shown.
  static const PwaState inert = PwaState();

  /// The browser reports a network connection. (True does not prove the ERP is
  /// reachable - a failed request still shows the screen's own error + retry.)
  final bool online;

  /// Running as an installed app (home screen / Start menu), not in a tab.
  final bool standalone;

  /// The browser offered its install dialog (Chrome, Edge, Samsung Internet).
  final bool canPrompt;

  /// iPhone / iPad: no install dialog exists; installing is Share -> Add to
  /// Home Screen, so the app explains it instead.
  final bool ios;

  /// Installed during this visit.
  final bool installed;

  /// The service worker controls this page (offline start is possible).
  final bool swActive;

  /// A newer version has been downloaded and is waiting for the user's tap.
  final bool updateReady;

  /// The user said "Not now" to the install hint within the snooze window.
  final bool hintSnoozed;

  /// The running build's version, from the service worker.
  final String version;

  factory PwaState.fromJson(String json) {
    try {
      final m = (jsonDecode(json) as Map).cast<String, dynamic>();
      bool b(String k) => m[k] == true;
      return PwaState(
        online: m['online'] != false,
        standalone: b('standalone'),
        canPrompt: b('canPrompt'),
        ios: b('ios'),
        installed: b('installed'),
        swActive: b('swActive'),
        updateReady: b('updateReady'),
        hintSnoozed: b('hintSnoozed'),
        version: (m['version'] as String?) ?? '',
      );
    } catch (_) {
      return inert;
    }
  }

  /// Already an app on this device: never suggest installing it.
  bool get isInstalled => standalone || installed;

  /// The install action is possible here at all (Settings-style entry point).
  bool get installAvailable => !isInstalled && (canPrompt || ios);

  /// Whether to show the install hint banner now. Quiet when installed, when
  /// snoozed, when offline (an install needs the network) and while an update
  /// banner is up - one banner at a time.
  bool showInstallHint({required bool dismissedThisSession}) =>
      installAvailable &&
      !hintSnoozed &&
      !dismissedThisSession &&
      online &&
      !updateReady;

  /// Whether to offer the waiting update now.
  bool showUpdate({required bool laterThisSession}) =>
      updateReady && !laterThisSession;
}
