# 品牌与吉祥物素材 / Brand and mascot assets

MiniMax Bot 已从当前源码树移除上游 DSH Go / DSH-TUI 非 AGPL 美术素材：

- 品牌 SVG、生成的 Dart 鱼尾/气泡路径和原版 Android 启动图标；
- Deepy 小鲸鱼、鲸娘的源图与宠物包，以及求 Star 的鲸娘贴纸；
- 鲸尾活动图（包括 DeepSeek 官方图标）及其源图；
- 上游 README 的展示图、桌宠动图、社交预览图和社群二维码；
- 依赖这些原图的生成脚本。上游展示网站不在当前源码树中。

启动图标和界面品牌标记已替换为本分支原创的几何字母 **M**（不使用字体或上游图片），四种图标变体保留原资源名及 adaptive-icon XML。可运行 `python3 app/tool/make_neutral_icons.py` 重建。活动指示器改为代码绘制的圆点，Star 提示改用现有 AppIcons 的中性 Phosphor 星形。应用不提供内置桌宠；旧宠物偏好迁移为「不显示」。通用宠物协议/渲染器源码保留用于学习和测试，测试只用数字几何夹具与纯色矩形，不打包原图。

源码继续遵守 [AGPL-3.0](LICENSE) 及各子目录自己的许可证。删除素材不改变上游权利：旧版本中的 DSH Go / DSH-TUI 品牌和吉祥物不属于 AGPL 授权，DeepSeek 商标仍归其权利人；**Git 历史未清除**。第三方代码、字体、图标及运行时通知继续见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## English

The current MiniMax Bot source tree removes the upstream non-AGPL DSH Go / DSH-TUI brand masters and generated glyphs, original launcher artwork, both mascot source/packed images, Star sticker, whale activity sprites (including the DeepSeek icon), README showcase/social/QR images, and generators depending on them. The upstream showcase website is not present in this checkout.

Original geometric M placeholders replace launcher and in-app marks, procedural dots replace activity artwork, and a neutral Phosphor star from the existing AppIcons set replaces the sticker. There are no built-in pets; obsolete saved selections migrate to none. Generic renderer/protocol code and test-only numeric/rectangle fixtures remain. Existing source and third-party licenses are preserved; removing files does not grant rights to upstream artwork or trademarks. Git history has not been rewritten.

## 验证边界 / Verification boundary

已使用独立安装的 Flutter 3.47.9 / Dart 3.13.7 对干净基线与本分支进行 analyze / test 对照，并精确重生成 201 个受品牌/宠物移除影响的 golden PNG，抽查为 M、圆点、纯色测试矩形和中性星形。未批量覆盖无关的既有失败；不能据此宣称全仓/历史已完成美术权利清理或可立即公开发布。具体结果和路径清单见根目录 `docs/handoff/T-Remove-Brand-Assets.md` 与 `docs/handoff/T-Remove-Brand-Goldens.md`。

A separate Flutter 3.47.9 / Dart 3.13.7 installation was used to compare clean-baseline and branch analysis/tests. Exactly 201 affected golden PNGs were regenerated; sampled renders contain neutral M marks, dots, solid test rectangles and a star. Unrelated pre-existing failures were not hidden by a blanket golden update. This is not full-history or screenshot-rights clearance; see the root handoff and golden inventory for exact outcomes and limits.
