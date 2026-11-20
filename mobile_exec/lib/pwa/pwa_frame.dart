import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../app/theme.dart';
import 'pwa_controller.dart';

/// The widest the app is drawn. OrbixHQ is laid out for a phone; on a tablet
/// in landscape or a desktop window it is centred at this width (like a
/// phone/tablet-sized app window) instead of stretching tiles and lists
/// across a 1920-px screen.
const double kPwaMaxAppWidth = 860;

// The app theme makes buttons full-width (Size.fromHeight(52)) for forms; in a
// banner row that is an infinite width. Banner buttons size to their label.
final ButtonStyle _bannerFilled = FilledButton.styleFrom(
  minimumSize: const Size(64, 40),
  padding: const EdgeInsets.symmetric(horizontal: 16),
);
final ButtonStyle _bannerText = TextButton.styleFrom(
  minimumSize: const Size(48, 40),
  padding: const EdgeInsets.symmetric(horizontal: 12),
);

/// Web-only frame around the whole app: the offline / update / install
/// banners on top, and the width cap for big screens.
///
/// It sits OUTSIDE MaterialApp (lib/main_web.dart), so the shared app code and
/// the Android build are untouched. The banners push the app down rather than
/// covering its app bar, and the app is told its real, smaller size.
class PwaFrame extends StatelessWidget {
  const PwaFrame({super.key, required this.controller, required this.child});

  final PwaController controller;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return MediaQuery.fromView(
      view: View.of(context),
      child: Directionality(
        textDirection: TextDirection.ltr,
        child: Theme(
          data: buildHqTheme(),
          child: ListenableBuilder(
            listenable: controller,
            builder: (context, _) => _layout(context),
          ),
        ),
      ),
    );
  }

  Widget _layout(BuildContext context) {
    final mq = MediaQuery.of(context);
    final banners = <Widget>[
      if (controller.showOffline) const _OfflineBanner(),
      if (controller.showUpdate) _UpdateBanner(controller: controller),
      if (!controller.showUpdate && controller.showInstallHint)
        _InstallBanner(controller: controller),
    ];
    final wide = mq.size.width > kPwaMaxAppWidth;

    return ColoredBox(
      color: wide ? const Color(0xFFE3E9EE) : HqColors.bg,
      child: Column(
        children: [
          // Two separate semantics containers: MaterialApp's routes block
          // the semantics of anything painted before them in the SAME
          // container, which silently hid the banners from screen readers.
          if (banners.isNotEmpty)
            Semantics(
              container: true,
              explicitChildNodes: true,
              child: Material(
                color: HqColors.panel,
                elevation: 2,
                child: SafeArea(
                  bottom: false,
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(
                        maxWidth: kPwaMaxAppWidth,
                      ),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: banners,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          Expanded(
            child: LayoutBuilder(
              builder: (context, c) {
                final w = math.min(c.maxWidth, kPwaMaxAppWidth);
                final topGone = banners.isNotEmpty;
                final data = mq.copyWith(
                  size: Size(w, c.maxHeight),
                  padding: topGone ? mq.padding.copyWith(top: 0) : mq.padding,
                  viewPadding: topGone
                      ? mq.viewPadding.copyWith(top: 0)
                      : mq.viewPadding,
                );
                return Center(
                  child: DecoratedBox(
                    decoration: wide
                        ? const BoxDecoration(
                            boxShadow: [
                              BoxShadow(
                                color: Color(0x1F0F172A),
                                blurRadius: 24,
                              ),
                            ],
                          )
                        : const BoxDecoration(),
                    child: SizedBox(
                      width: w,
                      height: c.maxHeight,
                      child: Semantics(
                        container: true,
                        child: MediaQuery(data: data, child: child),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _BannerShell extends StatelessWidget {
  const _BannerShell({
    required this.icon,
    required this.tint,
    required this.soft,
    required this.message,
    this.detail,
    this.actions = const [],
  });

  final IconData icon;
  final Color tint;
  final Color soft;
  final String message;
  final String? detail;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          message,
          style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: HqColors.ink,
            height: 1.3,
          ),
        ),
        if (detail != null) ...[
          const SizedBox(height: 2),
          Text(detail!, style: HqText.label),
        ],
      ],
    );
    final buttons = Wrap(spacing: 4, runSpacing: 4, children: actions);

    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: soft,
        border: const Border(bottom: BorderSide(color: HqColors.line)),
      ),
      padding: const EdgeInsets.fromLTRB(16, 10, 8, 8),
      child: LayoutBuilder(
        builder: (context, c) {
          // On a phone the buttons go under the text, so the message is not
          // squeezed into a narrow column beside them.
          final stacked = c.maxWidth < 520 && actions.isNotEmpty;
          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 1),
                child: Icon(icon, color: tint, size: 22),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: stacked
                    ? Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          text,
                          const SizedBox(height: 4),
                          Align(
                            alignment: Alignment.centerRight,
                            child: buttons,
                          ),
                        ],
                      )
                    : text,
              ),
              if (!stacked && actions.isNotEmpty) ...[
                const SizedBox(width: 8),
                buttons,
              ],
            ],
          );
        },
      ),
    );
  }
}

class _OfflineBanner extends StatelessWidget {
  const _OfflineBanner();

  @override
  Widget build(BuildContext context) {
    return Semantics(
      liveRegion: true,
      child: const _BannerShell(
        icon: Icons.cloud_off_rounded,
        tint: HqColors.warn,
        soft: HqColors.warnSoft,
        message: 'You are offline',
        detail:
            'Figures on screen may be out of date. Nothing can be saved '
            'until you reconnect.',
      ),
    );
  }
}

class _UpdateBanner extends StatelessWidget {
  const _UpdateBanner({required this.controller});

  final PwaController controller;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      liveRegion: true,
      child: _BannerShell(
        icon: Icons.system_update_rounded,
        tint: HqColors.brand,
        soft: HqColors.brandSoft,
        message: 'A new version of Orbix App is ready',
        detail: 'Finish what you are doing first - updating reloads the app.',
        actions: [
          TextButton(
            style: _bannerText,
            onPressed: controller.updateLater,
            child: const Text('Later'),
          ),
          FilledButton(
            style: _bannerFilled,
            onPressed: controller.updateNow,
            child: const Text('Update now'),
          ),
        ],
      ),
    );
  }
}

class _InstallBanner extends StatelessWidget {
  const _InstallBanner({required this.controller});

  final PwaController controller;

  @override
  Widget build(BuildContext context) {
    final ios = controller.state.ios && !controller.state.canPrompt;
    return _BannerShell(
      icon: Icons.install_mobile_rounded,
      tint: HqColors.brand,
      soft: HqColors.panel,
      message: 'Install Orbix App',
      detail: ios && controller.iosHelpOpen
          ? 'In Safari, tap the Share button (the square with an arrow), '
                'then "Add to Home Screen", then "Add".'
          : 'Open it from your home screen like any other app.',
      actions: [
        TextButton(
          style: _bannerText,
          onPressed: controller.notNow,
          child: const Text('Not now'),
        ),
        FilledButton(
          style: _bannerFilled,
          // iPhone: the first tap explains Share -> Add to Home Screen; "Got
          // it" then puts the hint away like "Not now".
          onPressed: ios && controller.iosHelpOpen
              ? controller.notNow
              : controller.install,
          child: Text(
            ios
                ? (controller.iosHelpOpen ? 'Got it' : 'Show me how')
                : 'Install',
          ),
        ),
      ],
    );
  }
}
