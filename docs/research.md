# 开源调研与架构选择

调研日期：2026-11-02。针对上游公开文档和仓库的调研；版本、功能和许可证后续可能变化。引用上游代码之前应重新核对对应版本和依赖的许可证。

## MiniMax Bot

- [MiniMax-Code-MiniApps / minimax-bot](https://github.com/MiniMax-AI/MiniMax-Code-MiniApps/tree/main/plugins/minimax-ai/minimax-bot)：MiniMax 官方 Code 插件。支持持久 Bot、角色、独立会话、群聊、互相派发任务、后台 helper、记忆和计划任务。插件依赖 MiniMax Code 的 Agent host 和 MCP，本身并非独立 Android Agent。插件目录的许可证为 MIT。查看的仓库提交：`0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8`。
- [DeepSeek Bot](https://github.com/FeiZhuLulu/DeepSeek-Bot)：MiniMax Bot 文档注明其团队、群聊、Chief 和记忆设计受到此项目启发，上游注明 Apache-2.0。可进一步研究其任务分派和记忆设计。
- [OpenMausBot](https://github.com/milind-soni/OpenMausBot)：Grok Bot 风格的开源多 Bot 项目，采用 Apache-2.0。其虚拟机、应用和服务架构可以作为功能参考，不符合本项目无远程电脑的约束。

## CLI 和手机端 Agent

- [MiniMax Code](https://github.com/MiniMax-AI/minimax-code)：官方开源终端 coding agent，支持 TUI、无头 `mcode exec`、ACP 和 TypeScript SDK，提供文件、命令、插件等能力。仓库指出开源覆盖 CLI 而不含桌面应用源码；第一方代码默认 MIT，细节需查各文件及第三方声明。它依赖 Node.js 和相关原生工具，不能假定普通 Linux 构建可直接在 Android 上运行。
- [DSH Go](https://github.com/ccch1mneyyy/dsh-Go)：DeepSeek Harness 的第三方 Android 客户端，本机模式将 Ubuntu rootfs、Node.js 和 DSH 封装到手机，默认使用 PRoot，模型 API 仍由用户配置；也有远控模式，但本项目不采用。项目代码主要为 AGPL-3.0，`sdk/` 和 `examples/` 为 MIT，名称及素材另有限制。查看的提交：`c1c0abb00907efdf48dcb443581686b67597c5a2`。其打包脚本会固定并校验运行时构件，值得参考；如直接移植代码必须遵守各组件许可证。
- [Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)：另一种将 DSH 和 Node 打包进 Android 的方式，可以对照研究 Bionic 运行时、设备架构、安装包大小和工具链兼容性。
- [mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code)：用户确认的 WebUI 仓库（当前账号曾向其提交 WebUI PR），调研快照 `97818b2`。它直接在 Node 进程中承载 `@mavis/local-runtime-v2`，通过仅限本机的 WebSocket 和有界消息协议供界面使用，并在冷启动时将未完成任务标记为中断。源码在同一仓库，WebUI 是 MIT；原生依赖和第三方组件需分别核对。已在 Linux 上完成构建并用隔离测试数据启动，本机首页返回 HTTP 200；这不能证明其 Android 移植可行。
- [mcode-webui/minimax-code-web](https://github.com/mcode-webui/minimax-code-web)：另一份相关 WebUI 代码，默认使用 ACP 本地子进程，说明两种客户端均可接入相同引擎。它不是用户所指的仓库。

## 本项目目前的决策和差距

> **历史原型路径（已弃用）：** 下文「首版采用 Android 原生 UI…」描述的是根目录 `app/` Java 原型的早期决策，**不是**当前目标实现。
>
> **当前已拍板方向：** 接受 AGPL 开源，保留 DSH Go 外壳（`dsh-go/`）、只换核心，可直接改 `dsh-go/`，根下 `packages/` 已有模块可继续用，分发 APK 须提供源码；runtime 跟 mcode-webui/minimax-code fork（MIT，`97818b2`），插件管理复用其插件市场、plugin-management 与 plugin-system；官方 MiniMax-AI/minimax-code 为上游，更新自行合并。插件 Android 安装策略与 F.7 档位仍未定。官方 MiniMax Bot 尚未接入产品路径。工作计划见 [architecture.md](architecture.md)；交接见根 README 与 [DEVELOPMENT.md](DEVELOPMENT.md)。

首版采用 Android 原生 UI 和独立的轻量本机工具循环，直接连接用户配置的兼容 API；它是可验证的 Agent 原型，**不是**将 MiniMax Code 或 DSH Go 完整移植。工作区文件读写由应用进行路径限制。与应用共用 UID 的 shell 不构成独立沙箱，命令工具已关闭，待独立隔离和受控工具通道完成后再启用。密钥通过 Android Keystore 加密，消息与工作区当前没有额外应用层加密。

对照 WebUI 后，本项目只做本机多 Bot，不做手机界面操控；将直接嵌入运行时作为优先评估方向；本地 ACP 适配器仅为备选实验，尚未接入 App。官方 mcode 在隔离环境的实测会话中报告了 `auto` 权限模式；若将 ACP 用于实际任务，必须主动设置并确认交互审批模式，不能假定默认会询问。官方 mcode 自定义 Provider 设置会将密钥明文保存到配置文件，必须解决其与手机 Keystore 存储的衔接，不能假设仅设置环境变量就能避免落盘。

产品 runtime 已定为 mcode-webui/minimax-code fork（`97818b2`）：应，评估 Node 原生依赖与工具包在 Android（含能否沿用 DSH Go 的 PRoot 兼容层；PRoot 不是安全边界）上的可行性；实现有界安装与完整性校验、进程生命周期、Agent 会话流和审批桥接，再接入 Bot、记忆、群聊和互派任务。进程恢复、后台限制、文件范围、网络、资源占用、许可证与真机兼容性需要单独验证。

[MiniMax 官方模型接口文档](https://platform.minimax.io/docs/guides/text-generation)给出了 OpenAI 兼容 Base URL、模型 ID 和 Chat Completions 请求示例。自定义 URL 的模型需要自行确认兼容性。[Grok Bot 移动端文档](https://docs.x.ai/grok-bot/mobile)描述了对标产品的 Bot、任务、审批与同步功能；本项目不依赖其云电脑。

## 可参考的开源 Android Bot App

- [DSH Go](https://github.com/ccch1mneyyy/dsh-Go)：可在 Android 设备本机运行 DSH，并提供 DS Bot 的原生 Bot 页面和群聊；支持自定义 OpenAI / Anthropic 兼容模型渠道。DS Bot 是 DSH 插件，与官方 MiniMax Bot 的宿主协议不同。DSH Go 主体为 AGPL-3.0，品牌及吉祥物另有限制。本仓库导入的 `dsh-go/` 是产品外壳，核心换为官方 MiniMax Bot + mcode-webui fork runtime。
- [DS Bot App](https://github.com/QingJ01/DS-Bot-App)：Apache-2.0 的 Android 多 Bot 和群聊 App，使用电脑或服务器上的 DSH 执行，手机需配对并保持主机在线；可借鉴界面和交互，但不满足本项目本机执行约束。
- [AnythingLLM Mobile](https://github.com/Mintplex-Labs/anythingllm-mobile)：GPL-3.0-or-later 的 Android/iOS 本机 AI 助手，提供本地模型及自定义兼容接口；其与本项目所需多 Bot、群聊和插件协议的功能重合度仍需验证。

## Nyral（2026-11-04 用户补充）

已实查 [aixtin/nyral](https://github.com/aixtin/nyral) 的固定提交 `72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328`。原生 Kotlin 自研引擎/SQLite 链更直接，但不是官方 mbot/mcode runtime 或插件市场的兼容实现；不替换既定产品方向。会话 epoch、pending 事务与轻量 UI 值得借鉴，宽存储、同 UID Shell、手机操控及系统闹钟链不照搬。逐项源码依据、构建未验证项和后续复用顺序见 [Nyral 调研](research/nyral.md)。

## mcode-agent-table（2026-11-04 用户补充）

MiniMax官方BYOB Table Protocol参考实现已静态检查到 `f230386ba0e405f2470d4f42863df3a5c0399297`（MIT）。对群聊发言权、协作任务、审批/核验与账本恢复有帮助；不替代Android runtime/文件隔离，也不是插件市场。MCode适配器的full执行thread、Node私钥/环境变量与直接FS不能直接照搬，Rabbit Hole Mini App仍不适配。源码依据与建议复用顺序见[调研](research/mcode-agent-table.md)。本轮未运行其测试/CLI/模型。
