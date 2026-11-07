<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 移除上游品牌素材（Flutter 验证已完成对照）

基线：`40c922a`；分支：`chore/remove-dsh-brand-assets`；工作目录：`/workspace/mbot-wt/remove-brand`。
干净对照树：`/workspace/mbot-wt/remove-brand-baseline`（detached `40c922a`）。
本分支不触碰 `feat/t-plugins` 的 ARM64 构建，不修改 Node 源树、`/workspace/toolchains/bin`、原生输入、包名或安装包；不 push、不 PR。

## 已做

- 删除品牌 SVG 4 个；宠物包 44 个文件和原始素材 72 个文件；鲸尾资源 5 个及原图 4 个；Star 贴纸 1 个；展示图/社群二维码 76 个。
- 删除原品牌路径 Dart 生成物、3 个依赖原图的生成器及宠物包生成器专用测试，共 **211 个 tracked 文件删除**。
- 60 张 Android launcher PNG 与 monochrome vector 改为原创几何 M。保留所有资源名、adaptive-icon XML、Manifest 和 frost/ink/night alias；沿用普通渐变背景。`make_neutral_icons.py` 可重建。
- 界面品牌使用同一几何 M；鲸尾动画接口名称保留以减少布局改动，实际内容是程序绘制圆点；Star 图改为现有 `AppIcons.favoriteActive`（中性 Phosphor 星形），文案中英同步去掉吉祥物口吻。
- 内置宠物列表清空，默认不显示；已存旧 ID 和不支持的选择迁移为 none，不加载已删资源。通用渲染/协议源码保留。
- Dart/Kotlin 原依赖宠物图片的测试改读不打包的数字布局夹具，Dart 生成纯色矩形。
- 精确重生成 **201** 个受影响 golden PNG（见 `T-Remove-Brand-Goldens.md` / `T-Remove-Brand-Golden-Selection.json`）；adaptive `wide_panel_dark` 另因冷启动 atlas 相位差再稳定一次。
- 中英 README 去掉被删图片引用；ASSETS / THIRD_PARTY_NOTICES 保留来源及原权利边界。

## SDK

独立安装（未替换 `/home/box/flutter-sdk`）：

- `/workspace/toolchains/flutter-3.47.9` — Flutter **3.47.9** / Dart **3.13.7**（framework `c197ca5199`）
- `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts`（NotoSansCJK Light/Regular/Bold）

## 实测（静态）

```sh
python3 -m unittest discover -s dsh-go/app/tool/tests -p 'test_brand_assets.py'   # 8 OK
python3 dsh-go/app/tool/l10n_merge.py --check                                       # 49 parts, 5554 keys
python3 tooling/docs-links/check_links.py --root . README.md docs                   # 0 issues
git diff --check
```

## Flutter analyze / test（需与最新 /tmp 日志对照）

在两棵树的 `dsh-go/app`：

```sh
export FLUTTER=/workspace/toolchains/flutter-3.47.9/bin/flutter
export DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts
$FLUTTER analyze
$FLUTTER test --concurrency=2
```

先前对照（可用，最终以本次 `/tmp/flutter-*-{analyze,test}.log` 为准）：

| Tree | analyze | success | error | failure | skipped |
|---|---|---:|---:|---:|---:|
| baseline `40c922a` | No issues | 8982 | 28 | 3 | 287 |
| branch after golden refresh | No issues | 8990 | 22 | 3 | 287 |

既有失败（两边共有，非本分支引入）主要包括：`share_code/replay_viewer_sync_test`（缺 `site/share/viewer.js`）、`launcher_icon_geometry_test`（包名期望 `.mbot`）、`header_schedule_golden_test`（`dsh/runtime/events` MissingPlugin）、`app_icons_test`（既有 `Icons.*`）、`third_party_licenses_test`、以及若干无关 settings/providers goldens。

## 仍不能宣称

1. 未运行 Gradle / 完整 engine / APK / 真机测试。
2. Git 历史未清理；不能据此宣称全仓/历史美术权利清理完毕。
3. 个别未重跑到的 golden PNG 若因既有失败未执行，可能仍含旧图——以 `T-Remove-Brand-Goldens.md` 清单为准。
