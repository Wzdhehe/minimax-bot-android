<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 移除上游品牌素材（独立工作分支，未完成 Flutter 验证）

基线：`40c922a`；分支：`chore/remove-dsh-brand-assets`；工作目录：`/workspace/mbot-wt/remove-brand`。
本分支不触碰 `feat/t-plugins` 的 ARM64 构建，不修改 Node 源树、编译工具链、原生输入、包名或安装包；不 push、不 PR。

## 已做

- 删除品牌 SVG 4 个；宠物包 44 个文件和原始素材 72 个文件；鲸尾资源 5 个及原图 4 个；Star 贴纸 1 个；展示图/社群二维码 76 个。
- 删除原品牌路径 Dart 生成物、3 个依赖原图的生成器及宠物包生成器专用测试，共 **211 个 tracked 文件删除**。
- 60 张 Android launcher PNG 与 monochrome vector 改为原创几何 M。保留所有资源名、adaptive-icon XML、Manifest 和 frost/ink/night alias；沿用普通渐变背景。`make_neutral_icons.py` 可重建。
- 界面品牌使用同一几何 M；鲸尾动画接口名称保留以减少布局改动，实际内容是程序绘制圆点；Star 图改为 Material 星形，文案中英同步去掉吉祥物口吻。
- 内置宠物列表清空，默认不显示；已存旧 ID 和不支持的选择迁移为 none，不加载已删资源。通用渲染/协议源码保留。
- Dart/Kotlin 原依赖宠物图片的测试改读不打包的数字布局夹具，Dart 生成纯色矩形；不是将原图移到测试目录。保留功能性帧时长、几何数值和测试边界，移除原作者/素材授权/品牌元数据。
- 中英 README 去掉被删图片引用，ASSETS 与 THIRD_PARTY_NOTICES 保留来源及原权利边界。

## 实测

工作目录为仓库根（Flutter 命令另标 app）：

```sh
python3 -m unittest discover -s dsh-go/app/tool/tests -p 'test_brand_assets.py'
# 8 tests OK：删除路径、pubspec 路径、源码无旧图加载、无内置宠物、M 前景、README、数字夹具、Android 引用解析。
python3 dsh-go/app/tool/l10n_merge.py --check
# 49 parts, 5554 keys
python3 tooling/docs-links/check_links.py --root . README.md docs
# exit 0（仅该检查器支持的本地链接）
git diff --check
# exit 0
```

现有工具 `/home/box/flutter-sdk/flutter/bin/flutter` 是 Flutter 3.35.7 / Dart 3.9.2；项目要求 Dart **^3.13.5**。没有降低 SDK/依赖，也没有升级或替换共享工具链。

以下命令在修改树 `dsh-go/app` 和另一个干净的 `/workspace/mbot-wt/remove-brand-baseline`（detached `40c922a`）均已运行，均 **exit 1，依赖解析阶段被同一 SDK 下限阻挡**：

```sh
/home/box/flutter-sdk/flutter/bin/flutter analyze
/home/box/flutter-sdk/flutter/bin/flutter test
```

**没有执行到 analyzer 或任何 widget test：通过/失败用例数不可得，不是测试全绿。** `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts` 不存在；机器有 `/usr/share/fonts/opentype/noto`，但上述失败发生在字体加载前。

`PATH=/home/box/flutter-sdk/flutter/bin:$PATH python3 dsh-go/app/tool/l10n_merge.py` 成功生成 l10n，再用 `dart format --line-length 120` 保持现有代码风格。格式化能解析更改文件，不等于 Flutter 编译通过。

## 必须续做，不能宣称可直接开源发布

1. 用匹配项目 SDK/锁文件的 Flutter 重跑完整 analyze/test。源码和 Flutter/Kotlin 测试改动尚未通过编译验证。
2. **现有 golden PNG 未重生成，可能仍嵌入旧版标记、鲸尾或桌宠。** 需要在兼容 SDK 下精确更新受影响的 brand / icon / launch / star / pet / share / chat / landscape 等 goldens，并审阅差异；不能因本机 SDK 不可用就批量覆盖、删测试或隐藏失败。
3. 没有运行 Gradle、完整 engine/APK 构建、安装或真机测试。当前已安装包和正在编译的 ARM64 包不会因本分支自动变更。
4. Git 历史未清理；上游署名、AGPL、第三方 notices 保留。数字夹具不包含图片；不是对全仓/历史/截图做完法律审计的声明。
