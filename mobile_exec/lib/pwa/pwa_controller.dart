import 'package:flutter/foundation.dart';

import 'pwa_bridge.dart';
import 'pwa_state.dart';

/// Holds the PWA state for the banners and owns the per-visit choices
/// ("Later" on an update, "Not now" on the install hint).
class PwaController extends ChangeNotifier {
  PwaController({PwaBridge? bridge}) : _bridge = bridge ?? PwaBridge() {
    final json = _bridge.stateJson();
    if (json != null) _state = PwaState.fromJson(json);
    _bridge.subscribe((json) {
      _state = PwaState.fromJson(json);
      notifyListeners();
    });
  }

  final PwaBridge _bridge;
  PwaState _state = PwaState.inert;
  bool _hintDismissed = false;
  bool _updateLater = false;
  bool _iosHelpOpen = false;

  PwaState get state => _state;
  bool get iosHelpOpen => _iosHelpOpen;

  bool get showOffline => !_state.online;
  bool get showUpdate => _state.showUpdate(laterThisSession: _updateLater);
  bool get showInstallHint =>
      _state.showInstallHint(dismissedThisSession: _hintDismissed);

  Future<void> install() async {
    if (_state.ios && !_state.canPrompt) {
      _iosHelpOpen = !_iosHelpOpen;
      notifyListeners();
      return;
    }
    final outcome = await _bridge.promptInstall();
    if (outcome != 'accepted') {
      // Declined in the browser's own dialog: do not ask again this visit.
      _hintDismissed = true;
      notifyListeners();
    }
  }

  void notNow() {
    _hintDismissed = true;
    _iosHelpOpen = false;
    _bridge.snoozeInstallHint();
    notifyListeners();
  }

  void updateLater() {
    _updateLater = true;
    notifyListeners();
  }

  void updateNow() => _bridge.applyUpdate();
}
