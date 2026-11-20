import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/session.dart';
import 'main.dart';
import 'pwa/pwa_controller.dart';
import 'pwa/pwa_frame.dart';

/// Entry point of the Orbix App PWA (`flutter build web -t lib/main_web.dart`,
/// via dist/build-pwa.ps1).
///
/// The SAME app as the Android OrbixHQ (OrbixHqApp from main.dart), wrapped in
/// [PwaFrame] for the offline / update / install banners and the big-screen
/// width cap. main.dart is untouched, so the APK builds exactly as before.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await seedSameOriginServer();
  final session = await Session.create();
  runApp(
    PwaFrame(
      controller: PwaController(),
      child: OrbixHqApp(session: session),
    ),
  );
}

/// The PWA is served from the same origin as the ERP API (IIS proxies /api/ to
/// the ERP), so the server address IS the page's origin - no address question
/// on first launch, no mixed content, no CORS. Only fills it in when nothing
/// was saved; the 7-tap server editor still works for support.
@visibleForTesting
Future<void> seedSameOriginServer({Uri? base}) async {
  const key = 'hq.base_host'; // HqConfig._kHost
  final prefs = await SharedPreferences.getInstance();
  final saved = prefs.getString(key);
  if (saved != null && saved.trim().isNotEmpty) return;
  final origin = (base ?? Uri.base).origin;
  await prefs.setString(key, origin);
}
