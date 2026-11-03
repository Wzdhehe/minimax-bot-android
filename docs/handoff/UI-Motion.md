# UI 动画与消息流性能交接（2026-11-03）

分支 `feat/ui-motion`，基于 `feat/t-schedule@5e1860e`；包含此前 Sandbox / Settings / Providers / Usage / Schedule 的分支链。不要从未更新的 main 开始续写。未改 main / dev/integration，未建 PR。

## 用户要求与实现

用户要求各页面流畅、有质感、有过渡动画，加载可用骨架屏，参考 DSH Go 并控制性能。沿用 DSH 的 `AppPageTransitionsBuilder`，保留其预测返回；生产 `DshApp` 已把系统减少动态效果、低性能/温控策略映射到 `MediaQuery.disableAnimations`。联调入口也使用同一主题（关闭玻璃），没有另外替换路由机制。

- 新共享组件 `packages/mbot_settings/lib/src/ui/loading_surface.dart`：2.4 秒轻呼吸骨架；一个区域一个 controller，CustomPainter 直接监听 repaint，RepaintBoundary 隔离重绘；没有逐行 ticker、ShaderMask、模糊和 saveLayer。减少动态效果、TickerMode 不可见、App 非 resumed 时停止，离开加载态后销毁。
- 初次加载到内容 160 ms 淡入；刷新保留已有内容，不反复切成骨架。接到 Bot 列表、会话历史、Bot 资料、设置初始化、供应商、账号额度、定时任务、后台状态的初次加载入口。部分系统状态小字段仍沿用原控件，不把所有短操作强制变成整页骨架。
- 同一路由内的手机列表/会话、平板会话切换为 160 ms 淡入 + 8 dp 小位移；按会话身份触发，token 到来不重播；系统减少动态效果立即切换。
- 全局设置统一宽度、卡片间距、圆角、图标与表面色；Bot 对话表面色改为主题颜色，避免浅色模式强制深色气泡。供应商/任务列表改为 builder 按需构建，Bot 和历史原本已使用 builder。
- `BotController` 直接合并授权 SSE 的 `message.upsert` / `chat.summary`，最多每 32 ms 通知一次，不再每批 token 全量拉状态和历史；仅 metadata / reconnect / reset 等事件合并后从服务校准。切换会话的代次保护保留，历史/状态 HTTP 请求期间的实时消息/摘要合并覆盖晚到旧快照，防止回复回退。选中历史和其临时覆盖集合上限 500 条；这不是新增历史翻页功能。

## 实跑验证

- 设置包原有 18 项测试通过；新增 4 个 motion widget 测试通过：减少动态效果、TickerMode、App 暂停/恢复、加载完无持久 ticker，以及流式文本不重启 pane 入场。完整首次运行 21 项通过，随后追加的第 4 项和供应商交互再跑 6 项通过。
- App 六组相关测试共 12 项通过；最后的会话/主题和 lazy list 改动后，又跑会话布局、Bot 资料/任务创建、流式竞态共 7 项通过。390 和 1024 dp 都无布局异常。
- 100 个连续消息事件测试：最多 2 次 listener 通知，无新增 state/history 请求；晚到旧历史保留较早记录且不覆盖最新 token。另测旧 state 不覆盖新的 chat summary，metadata 事件仍触发真实读取。
- 设置包 `dart analyze`、App `flutter analyze --no-pub lib/bot lib/dev/minimax_bot_main.dart test/bots/bot_stream_performance_test.dart` 无问题。CI 新增消息流测试。
- 临时 widget 渲染检查 390×844 的实际设置入口、Bot 设置、骨架区域；预览只用假 Bot/本地内存偏好。最初图中方框来自测试字体未指定，使用仓库 `withTestFonts` 修正后中文正确，调试横幅也移除。临时脚本/图片不入仓库。这不是 APK 截图。
- App 第一次重跑因缺少默认路径下 CJK 字体而未加载测试，设置 `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts` 后通过；这不是产品字体报错。

## 未验证与后续

没有 Android 设备/完整 SDK，未测真机帧耗时、60/120 FPS、长会话内存曲线、输入法与预测返回手势；不能用 widget 测试代替这些验收。没有完成生产 runtime/UID/broker 装配，之前卡的所有缺口继续保留，见各自 handoff。

后续在 Android profile 模式记录滚动/输入/流式输出的 frame/raster timings，覆盖低端机、系统减少动态效果和后台恢复。产品加载不得承诺固定耗时；主题/语言的完整用户设置页、插件页仍待开发。供应商弹出菜单对极大模型清单的搜索和虚拟化仍可改进。
