# MiniMax Bot for Android

> **当前开发方向：基于 DSH Go 改造。** `dsh-go/` 是从 [DSH Go](https://github.com/ccch1mneyyy/dsh-Go) 的 `c1c0abb00907efdf48dcb443581686b67597c5a2` 导入的基线源码，保留 AGPL-3.0 许可证及第三方声明。它仍是上游原始应用，并不代表 MiniMax Bot 移植已完成；远控、手机操控和原品牌素材也仍在基线中，正式成品必须移除或替换。根目录 Android 应用是早期独立原型，仅供实验，不是目标实现。

一个手机本机运行的 Bot / Agent 实验版。以 MiniMax Bot 的长期角色和 DSH Go 的本机运行思路为参考，界面、对话、工具调度和工具执行都在 Android 上运行；**模型推理通过你配置的 OpenAI Chat Completions 兼容 API 完成，需要网络**。不需要远程电脑或自建云环境。

## 当前功能

- 创建、编辑、删除拥有各自角色设定和独立聊天记录的 Bot。
- 设置 API Key、Base URL 和模型 ID；默认 Base URL 是 `https://api.minimax.io/v1`，默认模型是 `MiniMax-M3.1-Flash`。也可填写完整的 `/chat/completions` URL。服务端必须兼容 OpenAI Chat Completions 工具调用；不同提供商和模型的兼容性需要分别验证。
- 本机 Agent 循环：工作区文件列表、读取和写入；每次写入需要在手机上单独确认。工具调用与模型间的往返最多 8 轮。命令工具在隔离执行环境就绪之前已关闭。
- 消息和工作区保存在应用私有存储中；API Key 使用 Android Keystore AES-GCM 加密，应用备份已关闭。删除应用会删除本机数据。

文件工具限制在应用私有工作区。这是应用逻辑层面的路径限制，并不是独立于主应用的操作系统沙箱；当前原型没有隔离的 Agent 进程。命令执行已关闭，不应将本原型视为可安全运行任意 Agent 代码的环境。

仓库里另有尚未接入应用的 ACP 客户端实验，已经测试本机消息和官方 CLI 的初始化握手；这不代表 Android 移植已完成。本项目没有内置 MiniMax Code CLI 或 DSH Go 的 PRoot/Ubuntu 运行时，也没有后台持久运行、群聊、Bot 互派任务、插件、工作区文件导入导出或设备应用控制。Android 结束应用进程后正在进行的任务会中断；写入已经发生的副作用不会自动回滚。应用目前是原型，需要在实际设备及真实 API 上继续验证。请勿将初版视为 Grok Bot 或 DSH Go 的功能等价实现。

## 构建

需要 JDK 17、Android SDK 35 和联网的 Gradle 8.9。Android Studio 打开项目后同步并运行，或在安装 SDK 后执行：

```bash
./gradlew :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。该 APK 为调试签名，仅供测试。GitHub Actions 工作流也会编译调试 APK。

首次打开应用，在「设置」中填入自己的 Base URL、模型 ID 和 API Key。API Key 与对话内容会发送到配置的服务地址，因此仅填写信任的 HTTPS 地址。API 的费用和可用性取决于服务提供方和账号。然后创建 Bot 或打开默认 Bot 开始对话。

## Agent 本地验证

`tests/com/example/minimaxbot/AgentTest.java` 是无需设备的 Agent 测试，覆盖 URL 验证、工具调用往返、写入批准/拒绝与路径逃逸。用 JDK 17 和 `org.json` jar 运行：

```bash
mkdir -p /tmp/minimax-tests
javac -cp "$JSON_JAR" -d /tmp/minimax-tests app/src/main/java/com/example/minimaxbot/{Agent,AcpClient}.java tests/com/example/minimaxbot/{AgentTest,AcpClientTest}.java
java -cp "$JSON_JAR:/tmp/minimax-tests" com.example.minimaxbot.AgentTest
java -cp "$JSON_JAR:/tmp/minimax-tests" com.example.minimaxbot.AcpClientTest
```

调研结论和实际功能差距见 [docs/research.md](docs/research.md)，隔离方案见 [docs/sandbox.md](docs/sandbox.md)。本项目为独立实现，没有复制这些仓库的代码或商标素材。
