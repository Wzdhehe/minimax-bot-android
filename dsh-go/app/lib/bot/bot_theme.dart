// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';

/// A neutral canvas for the colorful first-party Bot artwork. Keep the
/// existing app's typography, locale, accessibility and light/dark preference.
ThemeData mbotTheme(ThemeData base) {
  final dark = base.brightness == Brightness.dark;
  final colors = base.colorScheme.copyWith(
    primary: Color(dark ? 0xfff3f3f3 : 0xff191a1c),
    onPrimary: Color(dark ? 0xff191a1c : 0xfffafafa),
    primaryContainer: Color(dark ? 0xff323437 : 0xffe9e9eb),
    onPrimaryContainer: Color(dark ? 0xfff5f5f5 : 0xff191a1c),
    secondary: Color(dark ? 0xffc4c6ca : 0xff5c5e63),
    surface: Color(dark ? 0xff111213 : 0xfffbfbfc),
    onSurface: Color(dark ? 0xfff4f4f5 : 0xff191a1c),
    onSurfaceVariant: Color(dark ? 0xff92959b : 0xff8b8e94),
    surfaceContainerLowest: Color(dark ? 0xff111213 : 0xffffffff),
    surfaceContainerLow: Color(dark ? 0xff1b1d1f : 0xffffffff),
    surfaceContainer: Color(dark ? 0xff202225 : 0xfff5f5f6),
    surfaceContainerHigh: Color(dark ? 0xff282a2d : 0xffeeeeef),
    surfaceContainerHighest: Color(dark ? 0xff2c2e31 : 0xffe9e9eb),
    outlineVariant: Color(dark ? 0xff303236 : 0xffe8e8eb),
    surfaceTint: Colors.transparent,
  );
  final fieldBorder = OutlineInputBorder(borderRadius: BorderRadius.circular(28), borderSide: BorderSide.none);
  return base.copyWith(
    colorScheme: colors,
    scaffoldBackgroundColor: colors.surface,
    appBarTheme: base.appBarTheme.copyWith(
      backgroundColor: colors.surface,
      foregroundColor: colors.onSurface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
    ),
    cardTheme: base.cardTheme.copyWith(
      color: colors.surfaceContainerLow,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    ),
    inputDecorationTheme: base.inputDecorationTheme.copyWith(
      filled: true,
      fillColor: colors.surfaceContainer,
      border: fieldBorder,
      enabledBorder: fieldBorder,
      focusedBorder: fieldBorder.copyWith(borderSide: BorderSide(color: colors.outlineVariant)),
    ),
  );
}
