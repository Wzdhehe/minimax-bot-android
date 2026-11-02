# MiniMax Bot for Android

> **产品方向（已拍板）：** 接受 AGPL 开源：产品基于 [DSH Go](https://github.com/ccch1mneyyy/dsh-Go)（AGPL-3.0，导入为 `dsh-go/`，钉 `c1c0abb00907efdf48dcb443581686b67597c5a2`）外壳，核心换为官方 MiniMax Bot + mcode-webui/minimax-code runtime；新代码可直接在 `dsh-go/` 内改，根下 `packages/` 已有独立模块可继续使用；分发 APK 须遵守 AGPL-3.0 源码提供义务。工作计划与考前任务卡见 [docs/architecture.md](docs/architecture.md)（供 MiniMax Code 开发；本团队验收）。**Runtime 跟 [mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code) fork**（MIT，钉 `97818b2`），插件管理复用其现成插件市场（`packages/webui`）、plugin-management 与 `local-runtime-v2` 的 plugin-system；官方 [MiniMax-AI/minimax-code](https://github.com/MiniMax-AI/minimax-code) 作上游，更新由我们自行合并进 fork。尚未接入本项目、未在 Android 上验证。插件在 Android 上的安装策略、以及定时任务「进程死后」验收档位（报告 F.7）**仍未定**。

目标产品是手机本机多 Bot 应用（布局与能力参考 Grok Bot 产品文档与官方 MiniMax Bot），模型调用走用户配置的 API，不需要远程电脑或自建云。**当前还没有可发布的 M Bot APK**：产品工程是 `dsh-go/`（DSH Go 外壳，核心替换尚未完成）；`dsh-go/runtime/vendor/minimax-bot/` 是官方 MIT Bot 源码快照。开发现状见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)；功能目标与验收见 [docs/feature-parity.md](docs/feature-parity.md)。

## 仓库里现在有什么

| 路径 | 角色 |
|---|---|
| 根下 `packages/` | 已有独立模块（如工作区守卫、设置页），可继续使用。 |
| `dsh-go/` | **产品主工程**（DSH Go 外壳，AGPL-3.0），可直接修改。含 Flutter/Android 工程、宿主/协议实验与 vendored Bot。 |
| 根目录 `app/` | 早期独立 **Java 原型**（废弃），见下文。 |

调研与差距见 [docs/research.md](docs/research.md)，隔离设计见 [docs/sandbox.md](docs/sandbox.md)。正式成品须处理上游许可证与受限素材；分发 APK 须按 AGPL-3.0 提供对应源码。

## 构建（如实说明现状）

**当前仓库里可尝试构建的仍是 `dsh-go/` 下的 Flutter/Android 工程**（产品主工程）。需要 Flutter、Android SDK 和 JDK；步骤与已知构建障碍见 `dsh-go/README.md`、`dsh-go/CONTRIBUTING.md`、`dsh-go/AGENTS.md` 与 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)「开发验证」。完整 Android 调试构建在分析时仍未通过（依赖解析/Gradle 环境问题）；**未验证**已产出可用的 M Bot APK。

宿主机上针对 `dsh-go/` 内宿主的协议测试示例（不等于产品已接入）：

```bash
cd dsh-go
node --test runtime/minimax-bot-host/service.test.mjs runtime/minimax-bot-host/agent-port.test.mjs
```

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
