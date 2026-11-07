// The brand mark and its lockup.
//
// - The original geometric M stays within its declared bounds.
// - The widget's sizes.
// - Lockups at the drawer's and the share card's sizes and at a larger text
//   scale, measured (brand_support.dart); the share card's tagline hangs under
//   the word.
// - One golden sheet per theme: every variant at the sizes the app draws.
//
//   flutter test test/shell/brand_mark_test.dart

import 'package:dsh_app/ui/share/share_card.dart';
import 'package:dsh_app/ui/shell/neutral_mark.dart';
import 'package:dsh_app/ui/shell/brand_mark.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../share/support.dart';
import 'brand_support.dart';
import 'golden_harness.dart';

void main() {
  test('neutral geometric mark stays inside its declared box', () {
    expect(NeutralMark.path.getBounds(), NeutralMark.box);
    expect(BrandMark.aspect, NeutralMark.box.width / NeutralMark.box.height);
    expect(BrandMark.visualCentre, const Offset(0.5, 0.5));
  });

  testWidgets('sizes: the glyph keeps its aspect, the tile is square', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildLightTheme(),
        home: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            BrandMark(key: ValueKey('glyph'), size: 40),
            BrandMark(key: ValueKey('mono'), size: 18, color: Colors.black),
            BrandMark(key: ValueKey('tile'), size: 52, tile: true),
          ],
        ),
      ),
    );
    expect(tester.getSize(find.byKey(const ValueKey('glyph'))), Size(40 * BrandMark.aspect, 40));
    expect(tester.getSize(find.byKey(const ValueKey('mono'))), Size(18 * BrandMark.aspect, 18));
    expect(tester.getSize(find.byKey(const ValueKey('tile'))), const Size(52, 52));
    final shift = BrandMark.opticalShift(40);
    expect(shift, Offset.zero);
    expect(shift.dx.abs(), lessThan(40 * BrandMark.aspect * 0.05));
  });

  group('lockup', () {
    Future<void> pumpLockup(WidgetTester tester, BrandLockup lockup, {double textScale = 1}) async {
      await tester.runAsync(loadGoldenFonts);
      usePhoneSurface(tester);
      await tester.pumpWidget(
        goldenApp(
          brightness: Brightness.light,
          home: Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
              child: Scaffold(body: Center(child: lockup)),
            ),
          ),
        ),
      );
      await tester.pump();
    }

    testWidgets('「DSH」 at the drawer\'s size: centred on the cap height', (tester) async {
      await pumpLockup(tester, const BrandLockup(height: 19));
      await expectBrandLockup(tester, find.byType(BrandLockup));
    });

    testWidgets('「dsh-go」 at the share card\'s size: centred on the x-height', (tester) async {
      await pumpLockup(tester, const BrandLockup(fontSize: 17, label: 'dsh-go'));
      await expectBrandLockup(tester, find.byType(BrandLockup));
    });

    testWidgets('follows the text scale', (tester) async {
      await pumpLockup(tester, const BrandLockup(height: 19), textScale: 1.3);
      await expectBrandLockup(tester, find.byType(BrandLockup));
      final glyph = tester.getSize(find.byType(BrandMark));
      expect(glyph.height, closeTo(19 * 0.95 * 1.3 * BrandLockup.glyphScale, 0.01));
    });

    for (final template in [ShareTemplate.light, ShareTemplate.dark, ShareTemplate.card]) {
      testWidgets('share card brand line (${template.name}): the tagline under the word', (tester) async {
        await tester.runAsync(loadGoldenFonts);
        tester.view.devicePixelRatio = 2;
        tester.view.physicalSize = const Size(ShareCard.width * 2, 2400 * 2);
        addTearDown(tester.view.reset);
        await tester.pumpWidget(
          ProviderScope(
            child: MaterialApp(
              debugShowCheckedModeBanner: false,
              theme: buildLightTheme(),
              locale: const Locale('zh', 'CN'),
              supportedLocales: const [Locale('zh', 'CN')],
              localizationsDelegates: GlobalMaterialLocalizations.delegates,
              home: Align(
                alignment: Alignment.topLeft,
                child: ShareCard(content: sampleReplyContent(), template: template),
              ),
            ),
          ),
        );
        await tester.pump();
        final word = await expectBrandLockup(tester, find.byType(BrandLockup));
        final tagline = tester.getRect(find.text('手机上的 DSH · 扫码获取'));
        expect(tagline.left, closeTo(word.left, 0.01));
        expect(tagline.top, greaterThanOrEqualTo(word.bottom));
      });
    }
  });

  for (final brightness in Brightness.values) {
    final mode = brightness == Brightness.dark ? 'dark' : 'light';
    testWidgets('sheet ($mode)', (tester) async {
      await tester.runAsync(loadGoldenFonts);
      tester.view.devicePixelRatio = 2;
      tester.view.physicalSize = const Size(390 * 2, 300 * 2);
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        goldenApp(
          brightness: brightness,
          home: Builder(
            builder: (context) {
              final colors = DshColors.of(context);
              Widget row(List<Widget> children) => Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                crossAxisAlignment: CrossAxisAlignment.center,
                children: children,
              );
              return AppBackdrop(
                child: Material(
                  type: MaterialType.transparency,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 20),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [
                        row(const [BrandMark(size: 18), BrandMark(size: 26), BrandMark(size: 40), BrandMark(size: 60)]),
                        row([
                          BrandMark(size: 18, color: colors.textDisabled),
                          const BrandMark(size: 26, color: Colors.white),
                          const BrandMark(size: 52, tile: true),
                          const BrandMark(size: 64, tile: true),
                        ]),
                        row(const [BrandLockup(height: 19), BrandLockup(fontSize: 17, label: 'dsh-go')]),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      );
      await tester.pump();
      await expectLater(find.byType(MaterialApp), matchesGoldenFile('goldens/brand_mark_$mode.png'));
    });
  }
}
