// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';

import '../l10n/l10n.dart';

part 'bot_avatar_art.dart';

/// First-party mbot artwork, with its original record-driven skin and palette.
/// `isMain` is supplied by the service; no name or list position grants a star.
class MbotAvatar extends StatelessWidget {
  const MbotAvatar({super.key, required this.bot, this.size = 52, this.showMain = true});
  final Map<String, Object?> bot;
  final double size;
  final bool showMain;

  static const skinIds = [
    'fuzzy',
    'bubble',
    'glass',
    'gold',
    'jade',
    'plush',
    'waffle',
    'pixel',
    'smiley',
    'toys',
    'moon',
  ];

  static String skinLabel(BuildContext context, String id) => switch (id) {
    'fuzzy' => context.l10n.mobileBotSkinFuzzy,
    'bubble' => context.l10n.mobileBotSkinBubble,
    'glass' => context.l10n.mobileBotSkinGlass,
    'gold' => context.l10n.mobileBotSkinGold,
    'jade' => context.l10n.mobileBotSkinJade,
    'plush' => context.l10n.mobileBotSkinPlush,
    'waffle' => context.l10n.mobileBotSkinWaffle,
    'pixel' => context.l10n.mobileBotSkinPixel,
    'smiley' => context.l10n.mobileBotSkinSmiley,
    'toys' => context.l10n.mobileBotSkinToys,
    _ => context.l10n.mobileBotSkinMoon,
  };

  @override
  Widget build(BuildContext context) {
    final skin = bot['skin'];
    final color = bot['color'];
    final palette = _mbotPalettes[color] ?? _mbotPalettes['graphite']!;
    final primary = showMain && bot['isMain'] == true;
    final badge = (size * .29).clamp(18.0, 34.0);
    return SizedBox.square(
      dimension: size,
      child: RepaintBoundary(
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned.fill(
              child: ExcludeSemantics(
                child: skin is String && skinIds.contains(skin)
                    ? Image.asset(
                        'assets/mbot/skins/$skin.png',
                        fit: BoxFit.contain,
                        cacheWidth: (size * MediaQuery.devicePixelRatioOf(context)).ceil().clamp(48, 384),
                        errorBuilder: (_, _, _) => CustomPaint(painter: _OctopusPainter(palette, bot['face'])),
                      )
                    : CustomPaint(painter: _OctopusPainter(palette, bot['face'])),
              ),
            ),
            if (primary)
              Positioned(
                right: -1,
                bottom: -1,
                child: Semantics(
                  label: context.l10n.mobileBotMain,
                  child: Container(
                    key: const Key('mbot.avatar.main'),
                    width: badge,
                    height: badge,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: const Color(0xffffb52c),
                      border: Border.all(color: Theme.of(context).colorScheme.surface, width: size > 80 ? 3 : 2),
                    ),
                    child: Icon(Icons.star_rounded, size: badge * .65, color: Colors.white),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// The upstream three-member group composition, with real member appearances.
class MbotGroupAvatar extends StatelessWidget {
  const MbotGroupAvatar({super.key, required this.members, this.size = 52});
  final List<Map<String, Object?>> members;
  final double size;
  @override
  Widget build(BuildContext context) {
    final rows = members.take(3).toList();
    if (rows.isEmpty) return MbotAvatar(bot: const {'color': 'graphite'}, size: size, showMain: false);
    const layouts = [
      [],
      [
        [.08, .08, .84],
      ],
      [
        [0.0, 0.0, .66],
        [.36, .34, .64],
      ],
      [
        [.20, 0.0, .60],
        [0.0, .44, .54],
        [.46, .44, .54],
      ],
    ];
    return SizedBox.square(
      dimension: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          for (var i = 0; i < rows.length; i++)
            Positioned(
              left: layouts[rows.length][i][0] * size,
              top: layouts[rows.length][i][1] * size,
              child: MbotAvatar(bot: rows[i], size: layouts[rows.length][i][2] * size, showMain: false),
            ),
          if (members.length > 3)
            Positioned(
              right: -2,
              bottom: -2,
              child: Text(
                '+${members.length - 3}',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
        ],
      ),
    );
  }
}

class _OctopusPainter extends CustomPainter {
  _OctopusPainter(this.colors, this.face);
  final List<Color> colors;
  final Object? face;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / 28.5, size.height / 29.2);
    canvas.translate(.6, .6);
    final bounds = _mbotOctopusPath.getBounds();
    canvas.drawPath(
      _mbotOctopusPath,
      Paint()
        ..shader = LinearGradient(
          begin: const Alignment(.56, -.96),
          end: const Alignment(-.56, 1),
          colors: colors,
          stops: const [0, .55, 1],
        ).createShader(bounds),
    );
    canvas.drawPath(
      _mbotOctopusPath,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(-.44, .72),
          radius: .6,
          colors: [Colors.white.withValues(alpha: .55), Colors.white.withValues(alpha: 0)],
        ).createShader(bounds),
    );
    canvas.drawPath(
      _mbotOctopusPath,
      Paint()
        ..shader = RadialGradient(
          center: const Alignment(.24, -.76),
          radius: .42,
          colors: [Colors.white.withValues(alpha: .28), Colors.white.withValues(alpha: 0)],
        ).createShader(bounds),
    );
    final white = Paint()..color = Colors.white;
    final stroke = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.35
      ..strokeCap = StrokeCap.round;
    void eye(double x) {
      canvas.drawOval(Rect.fromCenter(center: Offset(x, 11.4), width: 3.5, height: 4.5), white);
      canvas.drawCircle(Offset(x + .25, 11.1), .95, Paint()..color = colors.first);
    }

    void arc(double x, bool happy) => canvas.drawPath(
      Path()
        ..moveTo(x - 1.7, happy ? 12.2 : 11.2)
        ..quadraticBezierTo(x, happy ? 9.7 : 12.9, x + 1.7, happy ? 12.2 : 11.2),
      stroke,
    );
    if (face == 'dots' || face == 'wink') eye(9.5);
    if (face == 'dots') eye(17.4);
    if (face == 'happy') {
      arc(9.5, true);
      arc(17.8, true);
    }
    if (face == 'wink') arc(17.8, true);
    if (face == 'sleepy') {
      arc(9.5, false);
      arc(17.8, false);
      canvas.drawOval(Rect.fromCenter(center: const Offset(13.64, 15.8), width: 1.6, height: 1.3), white);
    }
    if (face == 'shades') {
      final dark = Paint()..color = const Color(0xe614161a);
      for (final x in [6.4, 14.6]) {
        canvas.drawRRect(RRect.fromRectAndRadius(Rect.fromLTWH(x, 9.2, 6.3, 4.2), const Radius.circular(1.6)), dark);
      }
      canvas.drawRect(const Rect.fromLTWH(12.4, 10.2, 2.5, .9), dark);
    }
    if (['dots', 'happy', 'wink', 'shades'].contains(face)) {
      canvas.drawPath(
        Path()
          ..moveTo(12.25, 15.4)
          ..quadraticBezierTo(13.64, 16.7, 15.03, 15.4),
        stroke,
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_OctopusPainter oldDelegate) => oldDelegate.colors != colors || oldDelegate.face != face;
}
