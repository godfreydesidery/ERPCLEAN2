import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../core/api/api_exception.dart';

/// Loads a future and renders exactly one of: loading, error-with-retry,
/// empty, or the data.
///
/// Error copy comes from [ApiException], which already carries the server's
/// user-safe `errors[]` strings. Nothing here prints an exception type or a
/// stack trace at the user.
class AsyncView<T> extends StatefulWidget {
  const AsyncView({
    super.key,
    required this.load,
    required this.builder,
    this.isEmpty,
    this.emptyTitle = 'Nothing here yet',
    this.emptyDetail,
    this.emptyIcon = Icons.inbox_outlined,
  });

  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data) builder;

  /// Treat a successful-but-empty result as an empty state rather than data.
  final bool Function(T data)? isEmpty;
  final String emptyTitle;
  final String? emptyDetail;
  final IconData emptyIcon;

  @override
  State<AsyncView<T>> createState() => AsyncViewState<T>();
}

class AsyncViewState<T> extends State<AsyncView<T>> {
  late Future<T> _future;
  T? _data;

  @override
  void initState() {
    super.initState();
    _future = widget.load();
  }

  /// The value currently on screen, or null before the first load finishes.
  ///
  /// Lets a screen's app-bar action — share, export — act on exactly what the
  /// user is looking at, without the screen keeping a second copy of it.
  T? get data => _data;

  /// Re-run the load — used by the retry button and pull-to-refresh.
  void reload() => setState(() {
        _data = null;
        _future = widget.load();
      });

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T>(
      future: _future,
      builder: (context, snap) {
        if (snap.connectionState == ConnectionState.waiting) {
          return const _Loading();
        }
        if (snap.hasError) {
          return _ErrorState(error: snap.error!, onRetry: reload);
        }
        final data = snap.data as T;
        _data = data;
        if (widget.isEmpty?.call(data) ?? false) {
          return _EmptyState(
            icon: widget.emptyIcon,
            title: widget.emptyTitle,
            detail: widget.emptyDetail,
            onRetry: reload,
          );
        }
        return RefreshIndicator(
          color: HqColors.brand,
          onRefresh: () async => reload(),
          child: widget.builder(context, data),
        );
      },
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) => const Center(
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: 60),
          child: CircularProgressIndicator(color: HqColors.brand),
        ),
      );
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  ({String title, String detail, IconData icon}) get _copy {
    final e = error;
    if (e is ApiException) {
      if (e.isNetwork) {
        return (
          title: 'Cannot reach the server',
          detail: 'Check the connection, then try again.',
          icon: Icons.wifi_off_rounded,
        );
      }
      if (e.isTimeout) {
        return (
          title: 'The server did not respond',
          detail: 'It may just be slow. Try again.',
          icon: Icons.timer_off_outlined,
        );
      }
      if (e.statusCode == 403) {
        return (
          title: 'You do not have access to this',
          detail: 'Ask an administrator to grant you permission.',
          icon: Icons.lock_outline_rounded,
        );
      }
      return (
        title: 'That did not work',
        detail: e.message,
        icon: Icons.error_outline_rounded,
      );
    }
    return (
      title: 'Something went wrong',
      detail: 'Try again in a moment.',
      icon: Icons.error_outline_rounded,
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = _copy;
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 70, 28, 28),
      children: [
        Icon(c.icon, size: 46, color: HqColors.ink3),
        const SizedBox(height: 16),
        Text(
          c.title,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 17,
            fontWeight: FontWeight.w700,
            color: HqColors.ink,
          ),
        ),
        const SizedBox(height: 6),
        Text(c.detail, textAlign: TextAlign.center, style: HqText.body),
        const SizedBox(height: 22),
        Center(
          child: SizedBox(
            width: 180,
            child: OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('Try again'),
            ),
          ),
        ),
      ],
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.icon,
    required this.title,
    required this.detail,
    required this.onRetry,
  });

  final IconData icon;
  final String title;
  final String? detail;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      color: HqColors.brand,
      onRefresh: () async => onRetry(),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(28, 70, 28, 28),
        children: [
          Icon(icon, size: 46, color: HqColors.ink3),
          const SizedBox(height: 16),
          Text(
            title,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w700,
              color: HqColors.ink,
            ),
          ),
          if (detail != null) ...[
            const SizedBox(height: 6),
            Text(detail!, textAlign: TextAlign.center, style: HqText.body),
          ],
        ],
      ),
    );
  }
}

/// Shown in place of a screen the signed-in user lacks the permission for.
class NoPermission extends StatelessWidget {
  const NoPermission({super.key, required this.code});

  final String code;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 70, 28, 28),
      children: [
        const Icon(Icons.lock_outline_rounded, size: 46, color: HqColors.ink3),
        const SizedBox(height: 16),
        const Text(
          'You do not have access to this',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 17,
            fontWeight: FontWeight.w700,
            color: HqColors.ink,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          'Ask an administrator to grant you $code.',
          textAlign: TextAlign.center,
          style: HqText.body,
        ),
      ],
    );
  }
}
