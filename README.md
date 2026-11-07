# MiniMax Bot for Android

基于 [DSH Go](https://github.com/ccch1mneyyy/dsh-Go) 的 Flutter/Android 外壳（AGPL-3.0），内置官方 MiniMax Bot，并使用钉住的 mcode-webui/minimax-code runtime。目标是本机多Bot，参考Grok Bot布局，不需要远程电脑或自建云，不做手机操控或通用MiniApp容器。插件市场仍是独立的未完成目标。

**2026-11-07：已有 API35/x86_64 模拟器上的基本可聊天调试产品包。** `feat/t-plugins@e9d0beb`已完成真实MiniMax连接测试、文本回复、生成中取消及继续对话；独立UID运行，Key只归native。**ARM64手机包、逐字流式设备证据、完整插件/工具/多模态等尚未完成，APK未发布。** 证据来源与确切APK摘要见[当前状态](docs/HANDOFF.md)。

接手开发先读 [AGENTS.md](AGENTS.md) → [HANDOFF](docs/HANDOFF.md) → [下一位任务合同](docs/handoff/NEXT-AGENT.md) → [开发命令](docs/DEVELOPMENT.md)。继续feat/t-plugins，阶段性快进推送，不动main/dev/integration，不force、不开PR、不发布二进制。旧专题卡中的“未启动/下一步接launcher”是历史，不应重复实现。

Runtime上游[权威钉点](docs/UPSTREAM-RUNTIME.md)与许可证不能随手改；官方MiniMax Code为上游，fork是当前绑定源。产品分发须满足AGPL对应源码提供义务，原Bot/MIT美术许可保留。功能目标见[验收清单](docs/feature-parity.md)。

## 仓库里现在有什么

| 路径 | 角色 |
|---|---|
| 根下 `packages/` | 已有独立模块（如工作区守卫、设置页），可继续使用。 |
| `dsh-go/` | **产品主工程**（DSH Go 外壳，AGPL-3.0），可直接修改。含 Flutter/Android 工程、宿主/协议实验与 vendored Bot。 |
| 根目录 `app/` | 早期独立 **Java 原型**（废弃），见下文。 |

调研与差距见 [docs/research.md](docs/research.md)，隔离设计见 [docs/sandbox.md](docs/sandbox.md)。正式成品须处理上游许可证与受限素材；分发 APK 须按 AGPL-3.0 提供对应源码。

## 构建

产品工程是 `dsh-go/app`，不是仓库根目录Java原型。需先准备已授权的full-ICU Android Node、FD SQLite、backup库及完整JS/资源，再构建 `flutter build apk --debug --target-platform android-x64 --flavor x64`；仅Flutter源码不能凭空生成引擎。详细顺序、版本、产物闸门和Windows环境见[DEVELOPMENT](docs/DEVELOPMENT.md)。

当前ARM64装配仍明确拒绝未准入输入，不能以同一命令替换flavor就称手机引擎可用。不要引入旧DSH/PRoot rootfs、恢复同UID任意shell或跳摘要检查来产包。

## 废弃原型（根目录 `app/`）

根目录 Android 应用（`app/`、根 `build.gradle` / `gradlew`）是早期独立 **Java 原型**，**不是目标实现**，仅供实验对照。下面列出的是该原型曾具备的能力，**不要**当成当前产品或 `dsh-go/` 已具备的能力。

### 原型曾具备的功能

- 创建、编辑、删除拥有各自角色设定和独立聊天记录的 Bot。
- 设置 API Key、Base URL 和模型 ID；默认 Base URL 是 `https://api.minimax.io/v1`，默认模型是 `MiniMax-M3.1-Flash`。也可填写完整的 `/chat/completions` URL。服务端必须兼容 OpenAI Chat Completions 工具调用；不同提供商和模型的兼容性需要分别验证。
- 本机 Agent 循环：工作区文件列表、读取和写入；每次写入需要在手机上单独确认。工具调用与模型间的往返最多 8 轮。命令工具在隔离执行环境就绪之前已关闭。
- 消息和工作区保存在应用私有存储中；API Key 使用 Android Keystore AES-GCM 加密，应用备份已关闭。删除应用会删除本机数据。

文件工具限制在应用私有工作区。这是应用逻辑层面的路径限制（canonical 前缀检查），并不是独立于主应用的操作系统沙箱；该原型没有隔离的 Agent 进程。命令执行已关闭，不应将本原型视为可安全运行任意 Agent 代码的环境。

仓库里另有尚未接入应用的 ACP 客户端实验，已经测试本机消息和官方 CLI 的初始化握手；这不代表 Android 移植已完成。该原型没有内置 MiniMax Code CLI 或 DSH Go 的 PRoot/Ubuntu 运行时，也没有后台持久运行、群聊、Bot 互派任务、插件、工作区文件导入导出或设备应用控制。Android 结束应用进程后正在进行的任务会中断；写入已经发生的副作用不会自动回滚。

### 原型构建与本地 Agent 测试

需要 JDK 17、Android SDK 35 和联网的 Gradle 8.9。Android Studio 打开**仓库根目录**后同步并运行，或在安装 SDK 后执行：

```bash
./gradlew :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。该 APK 为调试签名，仅供测试。GitHub Actions 工作流也会为该原型编译调试 APK。

`tests/com/example/minimaxbot/AgentTest.java` 是无需设备的 Agent 测试，覆盖 URL 验证、工具调用往返、写入批准/拒绝与路径逃逸。用 JDK 17 和 `org.json` jar 运行：

```bash
mkdir -p /tmp/minimax-tests
javac -cp "$JSON_JAR" -d /tmp/minimax-tests app/src/main/java/com/example/minimaxbot/{Agent,AcpClient}.java tests/com/example/minimaxbot/{AgentTest,AcpClientTest}.java
java -cp "$JSON_JAR:/tmp/minimax-tests" com.example.minimaxbot.AgentTest
java -cp "$JSON_JAR:/tmp/minimax-tests" com.example.minimaxbot.AcpClientTest
```

## 许可证

本仓库源码以 [GNU AGPL-3.0](LICENSE) 发布（与 `dsh-go/` 的 DSH Go 基线一致）。例外与补充：

- `dsh-go/` 中的品牌、吉祥物等美术素材不在 AGPL-3.0 范围内，见 [dsh-go/ASSETS.md](dsh-go/ASSETS.md)。
- 第三方代码、字体与运行时组件各按其原许可证，见 [dsh-go/THIRD_PARTY_NOTICES.md](dsh-go/THIRD_PARTY_NOTICES.md)。
- 子目录自带 LICENSE 的（如 `packages/mbot-workspace-guard/`、`dsh-go/sdk/plugin-kit/`）以该文件为准。

分发 APK 时须按 AGPL-3.0 第 6 条提供对应源码。
