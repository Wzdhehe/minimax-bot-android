<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Nyral 实查：可借鉴的移动端实现与适用边界

2026-11-04，用户提供 [aixtin/nyral](https://github.com/aixtin/nyral)。本次只读检查固定到提交 [`72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328`](https://github.com/aixtin/nyral/tree/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328)，不是只读 README 后推断功能。上游代码许可证为 [MIT](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/LICENSE)。本轮没有复制上游业务代码、图片、模型或 APK。

## 结论

Nyral 的对话执行链更直接：原生 Kotlin 自研 LocalEngine/ToolExecutor，直接访问 Android SQLite 和模型 API，没有本项目正在移植的 Node/mcode/mbot 链。因此它对“另写一个轻量 Android Agent”有参考价值，**不能据此断言改造它能更快满足官方 mbot 业务与 mcode 插件兼容**。检查的源码中未发现官方 mcode/mbot runtime 和官方插件市场适配。MCP 客户端不是 MiniMax Code 插件市场。

保持现有 DSH Flutter 外壳与既定官方 runtime 方向，按功能借鉴原生交互和持久化经验；不因为它省掉 Node 就无声改成自研 Agent。完整 App、真实模型、真机性能与 Nyral 自身构建均未在本轮验证。

## 源码比较

| 方面 | Nyral 实现与依据 | 本项目的处理 |
|---|---|---|
| 执行引擎 | [LocalEngine.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/LocalEngine.kt#L545)：chatLock 串行 chat、原生模型请求、ToolExecutor 分发 | 可研究精简移动端状态管理；不能替代 mcode/mbot 的 Agent/MCP/插件协议。单一 chatLock 也不证明多 Bot 并发已解决 |
| 密钥 | [Secrets.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/Secrets.kt#L13) 使用 AndroidKeyStore AES-GCM；LocalEngine 内直接取 ApiConfig key 发请求 | 已有 native-only broker 继续保留。静态加密与“模型进程拿不到 Key”是两种不同要求，不能直接搬为同一隔离方案 |
| 记忆持久化 | [MemoryDb.consumePending](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/MemoryDb.kt#L468)：同一 SQLite 事务写记忆、删除已消费 pending，失败整体回滚 | 可借鉴恢复验收方式；本项目须保留原 MemoryStore/JSONL 的备份、pending、operationId 语义，不能直接换成另一套表结构。按相同 content 去重也不能直接当作任务幂等 ID |
| 会话状态 | [ChatSessionState.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/io/github/aixtin/nyral/ChatSessionState.kt)：请求 epoch、取消/切换后丢弃迟到回调 | 正式 Bot UI 接线时按此类代际契约验收；已有 DSH generation/迟到历史保护不重复另写一套 |
| 流式呈现 | [Typewriter.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/Typewriter.kt)、[Ui.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/Ui.kt#L127)：共享 Choreographer、空闲停止、轻量 fade/scale | 借鉴“集中驱动、空闲不转”的原则，Flutter 继续用现有渲染体系，不直接搬 Android View 动画。Typewriter 的 scale 被 clamp 到至少 1，不能把它当成 scale=0 时关闭动画的实现；我们的减少动态效果契约仍须保留 |
| 工作区 | [WorkDir.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/WorkDir.kt#L15)：公共 Download/Nyral_work，MediaStore 与宽文件访问兜底 | 不照搬。继续单一应用私有总工作区、项目子目录、Main 文件 cap；不加 MANAGE_EXTERNAL_STORAGE |
| 工具/隔离 | [ScriptEngine.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/ScriptEngine.kt)、[ProcGuard.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/ProcGuard.kt#L80)、[Manifest](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/AndroidManifest.xml#L20)：Rhino ClassShutter、sh/su、无障碍与宽存储权限 | 不采用同 UID 任意 shell/手机操控，也不把语言级 ClassShutter 当成本项目的进程隔离证明 |
| 定时任务 | [TimerScheduler.kt](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/src/main/java/all/TimerScheduler.kt#L11)：AlarmManager.setAlarmClock、Receiver/Service、开机重排 | 与已定的 mbot/mcode 进程内调度不同。继续每 Bot 设置入口、MCP 与 UI 共用数据源，进程被杀不补跑；不另加系统闹钟/开机调度链 |

## 接下来怎么用这份参考

1. 先完成当前 T-RuntimeFiles 的设备回归与原模块恢复，不为换参考项目再重做已验证的 broker。
2. 正式会话接线时补“切 Bot/取消/旧请求晚到/旧 FD 晚到”的状态与资源释放测试，参考 epoch 的可检验契约。
3. 记忆恢复验收覆盖“写记录与消费 pending 同成同败、重启不丢不重复”；采用上游真实格式和幂等标识，不直接复制 Nyral schema。
4. 附件预处理、工具折叠、轻量弹层可继续对照源码与当前 Flutter 实现逐项比较。没有测量前不称哪一方真机更流畅，也不替换已有等价实现。

## 验证与许可边界

只做固定提交的静态源码/Manifest/构建脚本检查，没有运行其脚本、模型、Shell、SSH、APK 或真机。当前 [app/build.gradle.kts](https://github.com/aixtin/nyral/blob/72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328/app/build.gradle.kts#L40) 在 defaultConfig 要求 local.properties 的 crashToken 非空，不能仅凭 README 宣称当前快照已“一条命令构建成功”；本轮未提供或获取该凭据。

如后续复制具体 MIT 代码，须保留上游版权和许可，并记录固定提交与文件，在本仓库新文件加入 AGPL 头；不自动搬其品牌、模型权重或第三方依赖的许可证结论。本文件是针对本项目需求的适用性对照，不是对 Nyral 的完整安全审计。
