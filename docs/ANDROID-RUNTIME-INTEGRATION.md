<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Android runtime 装配前置核对（2026-11-03）

这是继续 HANDOFF 第 1/2 项的源码核对与待验收计划，**不是“在 isolated UID 上能跑”的结论**。没有设备数据的条目必须保持未验证。当前产品仍没有启动真正的 mcode runtime。

## 已核对的固定上游

全部 mcode 引用均取自机器钉点 `97818b2890b4acae1c6bd1200a226ff506680628`，不改钉点，不按仓库最新 main 猜测。

| 源码 | 实查事实 | 对装配的影响 |
|---|---|---|
| [根 package.json](https://github.com/mcode-webui/minimax-code/blob/97818b2890b4acae1c6bd1200a226ff506680628/package.json) | engines 为 `>=22.19 <23 || >=24.2 <27`；pnpm9.12.0 用于源码构建 | 现有 Node24 宿主检查满足声明；不能使用 Node18。宿主构建可用 pnpm，不等于允许在手机内 Ubuntu+pnpm 安装 |
| [local-runtime-v2/package.json](https://github.com/mcode-webui/minimax-code/blob/97818b2890b4acae1c6bd1200a226ff506680628/packages/local-runtime-v2/package.json) | 直接依赖 better-sqlite3 **12.11.1** 与 drizzle-orm | JS 打包不会消除 SQLite 原生扩展；需要目标 Android/ABI/Node 的对应构建 |
| [infra/db/client.ts](https://github.com/mcode-webui/minimax-code/blob/97818b2890b4acae1c6bd1200a226ff506680628/packages/local-runtime-v2/src/infra/db/client.ts) | `dataDir/v2/sqlite/runtime-state.sqlite`；WAL；`createRequire` 加载 better-sqlite3，允许 sqlite3ModulePath override | 需要在目标身份下验证实际 SQLite 打开、WAL/事务/备份/重启，不只是读到一个 JS 文件或传入目录 FD |
| [package-webui-npm.mjs](https://github.com/mcode-webui/minimax-code/blob/97818b2890b4acae1c6bd1200a226ff506680628/scripts/package-webui-npm.mjs) | server/runtime/mcode-tools/host-runner 作为 node22 ESM bundle；better-sqlite3 与 node-pty **external** | 现成桌面 npm 包不是纯 JS Android 包。node-pty 的具体装载时机还需继续审阅，不能说关闭 UI 就消除了依赖 |
| [webui/package.json](https://github.com/mcode-webui/minimax-code/blob/97818b2890b4acae1c6bd1200a226ff506680628/packages/webui/package.json) | node-pty **1.1.0** | 产品没有远控/传统终端入口，也不能因此自动开放同 UID shell；Android 裁剪/禁用与构建必须明确处理 |
| [agent-tools/package.json](https://github.com/mcode-webui/minimax-code/blob/97818b2890b4acae1c6bd1200a226ff506680628/packages/agent-tools/package.json) | @vscode/ripgrep **1.18.0**、shell-quote 等依赖 | 需要逐项核对目标二进制与执行入口；“存在依赖”不等于权限已开放或 Android 已兼容 |

另外实查 [nodejs-mobile/nodejs-mobile 发行 API](https://api.github.com/repos/nodejs-mobile/nodejs-mobile/releases)：返回的最新稳定 Android 包为 **v18.20.4（2024-10-07）**，前 20 条结果中没有满足上述 engines 的发行。该结论只针对这次查询到的仓库发行列表，**不是所有社区 fork 的穷尽调查**；没有采用或下载它来冒充当前 runtime。后续候选必须能证明 Node 22 的受维护版本（>=22.19）或受支持的 24/25/26 版本、对应 Android ABI 和构建来源。

## 进程与凭据不能混淆

- `NativeProviderBroker` 在可信主进程持有 Keystore 解密的 Key，只有 HTTPS 上游请求头用它。模型/供应商连接测试都走此组件，Node 已没有读 Key resolver。
- Node host 的 native endpoint/token 与交给 runtime 的 proxy session endpoint/token 是不同能力，均不是 provider Key；native capability 不能直接发给任意 Agent/插件。
- `AgentIsolationClient` 目前只装配文件占位 IPC。它的会话 nonce 只用于此控制通道，不是 native 模型 token。READY 不表示 Node 已加载，也不表示模型可访问。
- isolatedProcess 不继承主 App 网络权限，不能假定本机 TCP 可达。应先在真实设备证明 Binder/预连接 FD 等受控模型传输，而不是启动主 UID 任意代码来绕过限制。目录 FD、APK 资源、.so 加载、SELinux 与 SQLite 路径的组合也都待测，不能靠宿主 JVM 推导成功。
- 当前文本线协议是 `/v1/model/stream` 的 `{providerId,modelId,messages}`，而 mcode 的具体 provider 会构造各自模型 API 请求。**不能仅把其 Base URL 改成 broker 地址就声称接通**；必须有双方一致的 provider/工具协议适配，且不能写明文 Key config/env。

## 分阶段验收关口

1. **Android 组件验收工程**：`dsh-go/tooling/android-broker-probe` 直接复用生产源码，编译 Keystore/服务与 instrumentation 测试，避开产品 Flutter/NDK 构建依赖。不创建第二套 App 逻辑，不作为产品发布。
2. **实际设备隔离探针**：运行 `AgentIsolationClientTest` 与 `ProviderStorageTest`，观察真实 sendingUid/服务绑定/读写/私有文件拒绝/Keystore；补网络拒绝、宿主死亡、超时和关闭的仪器用例。Android35 run41600336727 已运行基础三项并通过；后续a094a85/run41622547971的API26/35十项均已通过，包括直接文件/网络拒绝、Unix FD、取消和IPC生命周期，物理真机仍未验证。
3. **受控字节通道**（native 已实现单请求 Unix socketpair relay，当前a094a85/run41622547971的API26/35十项均过；旧TCP FD版本API35六项通过、API26两项传递失败；已有宿主 Node FD 消费器，Android Node/JNI桥仍未实现）：对 isolated 与主进程之间的预连接 FD/Binder 验证请求关联、大小限制、背压、取消、死亡清理与权限；不增加凭据读取或任意 shell 操作。
4. **目标 Node + 原生扩展**：用可追踪来源、匹配 ABI/Node 的 Android 构建，验证 .so/.node 动态加载、SQLite/WAL、运行时只读 bundle 与可写数据路径。禁止把 Linux glibc 产物当作 Android 二进制。
5. **完整 mcode 端口**：provider/model 映射、真实模型与工具调用适配、身份/会话归属、取消、重启与关闭 drain；此后才谈正式 Bot 启动器切换和插件策略开放。

不跳过中间关口，也不把 host 模拟模型、JVM 单测或组件测试 APK 当作产品 Android 功能验收。

## 更新：Node24共享库验证工程

另找到RockinChaos/nodejs-mobile的24.22.2共享库：源码/包/二进制摘要已固定，JNI胶水和仪器APK已本地编译，设备执行待API26/35 CI。它没有Intl，不能直接启动官方Bot。此为隔离进程验证候选，不改变产品runtime选择/启动器；详见 [T-AndroidNode](handoff/T-AndroidNode.md)。后续Node加载与SQLite/持久化仍分别验收。

## Android Node24 源码候选（Termux，已核对，未采用/构建）

继续实查发现较新的维护方案：Termux `nodejs-lts`，固定源码提交 **22bd884a0428cef49cc99230ad94bd76ee5456a6**（2026-11-01）。这补充上面 nodejs-mobile 发行列表的有限调查；不能据其 Node18 发行推断整个 Android 没有新 Node。

| 固定引用 | 实查事实 | 仍需做的事情 |
|---|---|---|
| [build.sh](https://github.com/termux/termux-packages/blob/22bd884a0428cef49cc99230ad94bd76ee5456a6/packages/nodejs-lts/build.sh) | Node **24.22.2**，符合当前 mcode engines；源码 SHA256 `8fe2ec1723bb9f8d0b683907c82708debbeda64af21cb2aa214c4241eed0a061`；MIT；明确 `--dest-os=android`，含 arm64/x64 交叉构建分支 | 该 recipe 面向 Termux 前缀和 CLI，不是已验证的 APK/JNI/libnode；不能直接 dlopen 一个 CLI executable 或声明它能在 isolated UID 执行 |
| 同一 recipe | libc++、OpenSSL、c-ares、ICU、SQLite、zlib 依赖；libuv 使用内部构建以避 Android dlopen 符号问题；有小于API28的 aligned_alloc 兼容条件 | 需钉全部源码/patch/ABI/API、Android linker 与16KiB页兼容、许可证、reproducible artifact；小于28条件不是26测试证明。better-sqlite3仍要单独对匹配Node/ABI编译 |
| [lib-os.js.patch](https://github.com/termux/termux-packages/blob/22bd884a0428cef49cc99230ad94bd76ee5456a6/packages/nodejs-lts/lib-os.js.patch) / [lib-child_process.js.patch](https://github.com/termux/termux-packages/blob/22bd884a0428cef49cc99230ad94bd76ee5456a6/packages/nodejs-lts/lib-child_process.js.patch) | tmpdir fallback 与 Android shell 改成 `@TERMUX_PREFIX@` 目录 | 必须重审这些默认路径，不把 Termux 的私有目录或 shell 模式搬到产品；模型/插件无任意 main UID shell |

本轮只下载/读取公开 recipe/patch 文本，未安装 Termux、未下载其 Node 二进制、未编译 Node、未修改 mcode runtime pin。无 clang/lld/NDK 在本地；组件Gradle工具链不等于Node交叉编译环境。

还有独立的**存储关口**：isolated UID 不能直接打开主 App 的私有路径。传一个目录 FD 不自动授予在目录内按路径创建 SQLite/WAL/JSON 文件的权限。即使模型字节通道和 `.so` 加载成功，也必须验证 runtime 的数据路径/SQLite VFS/持久化方案；不能把 cwd、目录 FD 或 PRoot 当作权限授予。尚未选择未经验证的内存快照、文件系统 shim 或数据库 RPC 方案，也不得因此把含 Agent 执行能力的 Node 放回主 UID。
