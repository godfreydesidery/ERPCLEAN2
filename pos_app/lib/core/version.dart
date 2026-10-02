/// The app version shown on screen, e.g. `1.5.4+12`.
///
/// Stamped at build time by `dist/build-pos.ps1`
/// (`--dart-define=POS_VERSION=<pubspec version>`), so the till can say which
/// build it is without a plugin. A developer `flutter run` shows `dev`. Before
/// this, the only way to tell what a till ran was the exe's Windows properties —
/// which nobody at a counter finds when a supplier asks.
const String kAppVersion =
    String.fromEnvironment('POS_VERSION', defaultValue: 'dev');
