// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:math' as math;

import 'package:flutter/material.dart';

/// A small entrance for panes switched inside one route (phone list/chat or
/// tablet selection). The stable identity avoids restarting on streaming text.
class MbotPaneTransition extends StatelessWidget {
  const MbotPaneTransition(
      {super.key, required this.identity, required this.child});
  final Object identity;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (MediaQuery.disableAnimationsOf(context)) return child;
    return TweenAnimationBuilder<double>(
      key: ValueKey(identity),
      tween: Tween(begin: 0, end: 1),
      duration: const Duration(milliseconds: 160),
      curve: Curves.easeOutCubic,
      child: child,
      builder: (_, value, child) => Opacity(
        opacity: value,
        child: Transform.translate(
            offset: Offset(0, 8 * (1 - value)), child: child),
      ),
    );
  }
}

/// Initial loading only. Callers keep existing data visible during refreshes.
/// Material routes retain the host theme's transitions and predictive back.
class MbotLoadTransition extends StatelessWidget {
  const MbotLoadTransition(
      {super.key, required this.loading, required this.child});
  final bool loading;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final content = loading
        ? const SingleChildScrollView(
            key: ValueKey('mbot.loading'), child: MbotSkeleton())
        : KeyedSubtree(key: const ValueKey('mbot.loaded'), child: child);
    if (MediaQuery.disableAnimationsOf(context)) return content;
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 160),
      switchInCurve: Curves.easeOutCubic,
      switchOutCurve: Curves.easeInCubic,
      // No size animation: loading must not move controls while tapping.
      transitionBuilder: (child, animation) => FadeTransition(
        opacity: animation,
        child: child,
      ),
      child: content,
    );
  }
}

/// One ticker and a repaint-only canvas per loading region, with no blur,
/// shaders, per-row controllers or animated widget-tree rebuilds.
class MbotSkeleton extends StatefulWidget {
  const MbotSkeleton({super.key, this.rows = 5});
  final int rows;
  @override
  State<MbotSkeleton> createState() => _MbotSkeletonState();
}

class _MbotSkeletonState extends State<MbotSkeleton>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  late final _pulse = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 2400));
  bool _visible = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    _visible = lifecycle == null || lifecycle == AppLifecycleState.resumed;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _visible = state == AppLifecycleState.resumed;
    _sync();
  }

  void _sync() {
    final animate = _visible &&
        TickerMode.valuesOf(context).enabled &&
        !MediaQuery.disableAnimationsOf(context);
    if (animate && !_pulse.isAnimating) {
      _pulse.repeat();
    } else if (!animate) {
      _pulse.stop();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pulse.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
        child: RepaintBoundary(
          child: SizedBox(
            width: double.infinity,
            height: widget.rows * 76.0 + 32,
            child: CustomPaint(
                painter: _SkeletonPainter(_pulse,
                    Theme.of(context).colorScheme.onSurface, widget.rows)),
          ),
        ),
      );
}

class _SkeletonPainter extends CustomPainter {
  _SkeletonPainter(this.pulse, this.color, this.rows) : super(repaint: pulse);
  final Animation<double> pulse;
  final Color color;
  final int rows;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color.withValues(
          alpha: 0.065 + (1 - math.cos(pulse.value * math.pi * 2)) * 0.025);
    final width = math.max(0.0, size.width - 104);
    for (var i = 0; i < rows; i++) {
      final y = 32.0 + i * 76;
      canvas.drawCircle(Offset(40, y + 16), 22, paint);
      canvas.drawRRect(
          RRect.fromRectAndRadius(
              Rect.fromLTWH(80, y, width * (i.isEven ? 0.66 : 0.5), 14),
              const Radius.circular(7)),
          paint);
      canvas.drawRRect(
          RRect.fromRectAndRadius(Rect.fromLTWH(80, y + 27, width * 0.88, 10),
              const Radius.circular(5)),
          paint);
    }
  }

  @override
  bool shouldRepaint(_SkeletonPainter oldDelegate) =>
      oldDelegate.color != color ||
      oldDelegate.rows != rows ||
      oldDelegate.pulse != pulse;
}
