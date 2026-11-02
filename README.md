# MiniMax Bot for Android

一个手机本机运行的 Bot / Agent 实验版。以 MiniMax Bot 的长期角色和 DSH Go 的本机运行思路为参考，界面、对话、工具调度和工具执行都在 Android 上运行；**模型推理通过你配置的 OpenAI Chat Completions 兼容 API 完成，需要网络**。不需要远程电脑或自建云环境。

## 当前功能

- 创建、编辑、删除拥有各自角色设定和独立聊天记录的 Bot。
- 设置 API Key、Base URL 和模型 ID；默认 Base URL 是 `https://api.minimax.io/v1`，默认模型是 `MiniMax-M3.1-Flash`。也可填写完整的 `/chat/completions` URL。服务端必须兼容 OpenAI Chat Completions 工具调用；不同提供商和模型的兼容性需要分别验证。
- 本机 Agent 循环：工作区文件列表、读取、写入和 Android shell 命令；每次写文件或执行命令都需要在手机上单独确认。工具调用与模型间的往返最多 8 轮。
- 消息和工作区保存在应用私有存储中；API Key 使用 Android Keystore AES-GCM 加密，应用备份已关闭。删除应用会删除本机数据。

文件工具限制在应用私有工作区。**命令工具不同：确认执行的 shell 命令拥有应用自身的文件和网络权限，可能访问工作区以外的应用私有文件，或启动持续运行的子进程；请先检查命令。** 命令工作目录为该私有工作区，使用 Android 系统 `/system/bin/sh`，不是 Ubuntu、PRoot 或 MiniMax Code。单次前台命令等待最长 30 秒，输出显示给模型的前 50 KB；结束 shell 不保证结束其自行分离的子进程。

本项目没有内置 MiniMax Code CLI 或 DSH Go 的 PRoot/Ubuntu 运行时，也没有后台持久运行、群聊、Bot 互派任务、插件、工作区文件导入导出或设备应用控制。Android 结束应用进程后正在进行的任务会中断；写入已经发生的副作用不会自动回滚。应用目前是原型，需要在实际设备及真实 API 上继续验证。请勿将初版视为 Grok Bot 或 DSH Go 的功能等价实现。

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
javac -cp "$JSON_JAR" -d /tmp/minimax-tests app/src/main/java/com/example/minimaxbot/Agent.java tests/com/example/minimaxbot/AgentTest.java
java -cp "$JSON_JAR:/tmp/minimax-tests" com.example.minimaxbot.AgentTest
```

调研结论和实际功能差距见 [docs/research.md](docs/research.md)。本项目为独立实现，没有复制这些仓库的代码或商标素材。
