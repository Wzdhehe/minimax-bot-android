# 品牌与吉祥物素材 / Brand and mascot assets

MiniMax Bot 已从当前源码树移除上游 DSH Go / DSH-TUI 非 AGPL 美术素材：

- 品牌 SVG、生成的 Dart 鱼尾/气泡路径和原版 Android 启动图标；
- Deepy 小鲸鱼、鲸娘的源图与宠物包，以及求 Star 的鲸娘贴纸；
- 鲸尾活动图（包括 DeepSeek 官方图标）及其源图；
- 上游 README 的展示图、桌宠动图、社交预览图和社群二维码；
- 依赖这些原图的生成脚本。上游展示网站不在当前源码树中。

启动图标和界面品牌标记已替换为本分支原创的几何字母 **M**（不使用字体或上游图片），四种图标变体保留原资源名及 adaptive-icon XML。可运行 `python3 app/tool/make_neutral_icons.py` 重建。活动指示器改为代码绘制的圆点，Star 提示改用 Material 星形。应用不提供内置桌宠；旧宠物偏好迁移为「不显示」。通用宠物协议/渲染器源码保留用于学习和测试，测试只用数字几何夹具与纯色矩形，不打包原图。

源码继续遵守 [AGPL-3.0](LICENSE) 及各子目录自己的许可证。删除素材不改变上游权利：旧版本中的 DSH Go / DSH-TUI 品牌和吉祥物不属于 AGPL 授权，DeepSeek 商标仍归其权利人；**Git 历史未清除**。第三方代码、字体、图标及运行时通知继续见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## English

The current MiniMax Bot source tree removes the upstream non-AGPL DSH Go / DSH-TUI brand masters and generated glyphs, original launcher artwork, both mascot source/packed images, Star sticker, whale activity sprites (including the DeepSeek icon), README showcase/social/QR images, and generators depending on them. The upstream showcase website is not present in this checkout.

Original geometric M placeholders replace launcher and in-app marks, procedural dots replace activity artwork, and a Material star replaces the sticker. There are no built-in pets; obsolete saved selections migrate to none. Generic renderer/protocol code and test-only numeric/rectangle fixtures remain. Existing source and third-party licenses are preserved; removing files does not grant rights to upstream artwork or trademarks. Git history has not been rewritten.

## 验证边界 / Verification boundary

本分支尚需兼容 Flutter / Dart SDK 进行 widget 与 golden 验证。现有 golden 基线可能含旧版图形，不能据此宣称全仓已完成美术权利清理或可立即公开发布；详细验证记录见根目录 `docs/handoff/T-Remove-Brand-Assets.md`。

Widget and golden verification needs a compatible Flutter / Dart SDK. Existing golden baselines may embed old artwork; this change is not a full-history or screenshot-rights clearance. See the root handoff note for exact verification limits.
