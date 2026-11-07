import 'package:flutter/widgets.dart';

/// Original geometric M placeholder, not derived from upstream artwork.
/// Coordinates match tool/make_neutral_icons.py and Android's 108-unit grid.
abstract final class NeutralMark {
  static const box = Rect.fromLTWH(34, 36, 40, 36);
  static final path = Path()
    ..moveTo(34, 72)
    ..lineTo(34, 36)
    ..lineTo(42, 36)
    ..lineTo(54, 52)
    ..lineTo(66, 36)
    ..lineTo(74, 36)
    ..lineTo(74, 72)
    ..lineTo(66, 72)
    ..lineTo(66, 50)
    ..lineTo(54, 66)
    ..lineTo(42, 50)
    ..lineTo(42, 72)
    ..close();
}
