import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'pwa_bridge.dart';

/// Web builds: talks to `window.orbixPwa` (web/pwa.js). Every call tolerates
/// pwa.js being absent (e.g. `flutter run -d chrome` with a stripped index).
PwaBridge createBridge() => _WebPwa();

class _WebPwa implements PwaBridge {
  JSObject? get _api {
    final v = globalContext['orbixPwa'];
    return v.isA<JSObject>() ? v as JSObject : null;
  }

  @override
  String? stateJson() {
    final api = _api;
    if (api == null) return null;
    final v = api.callMethod('stateJson'.toJS);
    return v.isA<JSString>() ? (v as JSString).toDart : null;
  }

  @override
  void subscribe(void Function(String json) onChange) {
    _api?.callMethod(
      'subscribe'.toJS,
      ((JSString json) => onChange(json.toDart)).toJS,
    );
  }

  @override
  Future<String> promptInstall() async {
    final api = _api;
    if (api == null) return 'unavailable';
    final p = api.callMethod('promptInstall'.toJS);
    if (!p.isA<JSPromise>()) return 'unavailable';
    final r = await (p as JSPromise<JSAny?>).toDart;
    return r.isA<JSString>() ? (r as JSString).toDart : 'dismissed';
  }

  @override
  void snoozeInstallHint() => _api?.callMethod('snoozeInstallHint'.toJS);

  @override
  void applyUpdate() => _api?.callMethod('applyUpdate'.toJS);
}
