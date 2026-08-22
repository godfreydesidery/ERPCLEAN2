// The version on the About card must be the version that was built.
//
// It read 1.0.0 while the app in the client's hands was 1.2.0. Support asks
// "what version are you on?" and acts on the answer, so this is the one string
// in the app that must never be stale. Flutter cannot read its own version
// without a platform plugin, so the value is hand-written — and checked here
// against pubspec.yaml, which is what the build actually stamps into the APK.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/version.dart';

void main() {
  test('kAppVersion matches the version pubspec builds', () {
    final pubspec = File('pubspec.yaml').readAsStringSync();
    final match =
        RegExp(r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)', multiLine: true)
            .firstMatch(pubspec);

    expect(match, isNotNull, reason: 'no version: line in pubspec.yaml');
    expect(
      kAppVersion,
      match!.group(1),
      reason: 'lib/app/version.dart and pubspec.yaml have drifted — '
          'the About card would show the wrong version to support',
    );
  });
}
