/// The app version shown on the About card.
///
/// Support asks "what version are you on?" and acts on the answer, so a wrong
/// value here is worse than none — this read 1.0.0 while the app in the
/// client's hands was 1.2.0. Flutter cannot read its own version without a
/// platform plugin, so it is stated once, here.
///
/// **Bump this with `version:` in pubspec.yaml.** They are checked against each
/// other by test/version_test.dart, which fails the build if they drift.
const String kAppVersion = '1.2.1';
