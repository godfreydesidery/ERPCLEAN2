import 'pwa_bridge.dart';

/// Non-web builds: no PWA.
PwaBridge createBridge() => _NoPwa();

class _NoPwa implements PwaBridge {
  @override
  String? stateJson() => null;

  @override
  void subscribe(void Function(String json) onChange) {}

  @override
  Future<String> promptInstall() async => 'unavailable';

  @override
  void snoozeInstallHint() {}

  @override
  void applyUpdate() {}
}
