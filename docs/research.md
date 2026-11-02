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

## 本项目目前的决策和差距

首版采用 Android 原生 UI 和独立的轻量本机工具循环，直接连接用户配置的兼容 API；它是可验证的 Agent 原型，**不是**将 MiniMax Code 或 DSH Go 完整移植。工作区文件读写与 shell 是两种不同权限边界，后者运行在应用权限下且必须逐次确认。密钥通过 Android Keystore 加密，消息与工作区当前没有额外应用层加密。

要接近 DSH Go 底层体验，应先选择并固定 Android ARM64 可执行环境，评估 MiniMax Code 的 Node 原生依赖与工具包是否能在 PRoot/Android 下正常工作；实现有界安装与完整性校验、CLI 进程生命周期、Agent 会话流和审批桥接，再接入 Bot、记忆、群聊和互派任务。进程恢复、Android 后台限制、可访问文件范围、网络、资源占用、许可证与真机兼容性需要单独验证。

[MiniMax 官方模型接口文档](https://platform.minimax.io/docs/guides/text-generation)给出了 OpenAI 兼容 Base URL、模型 ID 和 Chat Completions 请求示例。自定义 URL 的模型需要自行确认兼容性。[Grok Bot 移动端文档](https://docs.x.ai/grok-bot/mobile)描述了对标产品的 Bot、任务、审批与同步功能；本项目不依赖其云电脑。
