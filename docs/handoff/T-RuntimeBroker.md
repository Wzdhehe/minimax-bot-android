# T-RuntimeBroker（HANDOFF 第 2 项，部分完成，2026-11-03）

## 本次续写：native provider broker

**后续同分支推进：供应商测试也改为 native-only**。原 `provider-broker.mjs` 的 `resolveProvider -> apiKey -> Node fetch` 方案已删除，不能再把此文件当作允许 native 向 Node 交 Key 的接口。新 `createProviderBroker({ modelBroker })` 只调用 native 流，识别四种协议的文本输出后立即取消，以减少测试请求消耗；仅 role/usage 元数据不算成功，超时取消，不自动重试，错误白名单不带原异常。`startBotService({ modelBroker })` 自动装配该 probe，不再接收旧 providerBroker 参数。HTTP `/api/providers/test` 的用户端协议仍只收 IDs、返回状态。

该后续阶段本地：probe/服务/Agent 相关 **12 passed**；完整 CI 同款宿主集合 **18 passed**；同一 Kotlin 脚本 **JUnit 6 + 跨进程 5 passed**，新增「鉴权设置 HTTP → Node probe → 真实 Kotlin broker → 模拟上游」并重新核对文件/日志无 Key。原生桥与 provider 仍为模拟环境，不能写成 Android 真机已装配。

在远程 `feat/t-plugins@b7ca158` 上直接续写，未新建分支。用户先要求完成此项后停，随后明确改为继续按开发文档推进、及时推送阶段成果。协议范围由用户交由实现者判断：本阶段保持现有 `providerId/modelId/messages` 线协议，支持四种协议的**文本消息**与原始 JSON SSE，不扩展 tools/temperature 等高级参数；后续正式 runtime 接入时需要共同扩展两端。

- `model/NativeModelBroker.kt`：main-process 组件，随机 native 会话 token、仅绑定 `127.0.0.1`，实现 `POST /v1/model/stream`。Host/Origin/auth、重复 header、body 大小、字段与消息结构均校验；不接受 Key/URL/请求头覆盖，没有文件、凭据读取或命令执行路由。最多 4 个并发 worker + 8 个排队连接，读取超时、SSE 帧大小和上游 call 总时长有限制。
- `BotProviderStore.resolveForModelBroker`：在 native 内复查 provider enabled 和 model allowlist，然后用现有 AndroidKeyStore/AES-GCM 解密得到 native-only snapshot。锁在网络请求前释放，不让长流锁住设置。没有新增 MethodChannel 读 Key 方法。
- `NativeProvider.kt`：四类请求映射（OpenAI completions/responses、Anthropic messages、Google streamGenerateContent）；Base URL 必须 HTTPS、无 userinfo/query/fragment。Key 仅注入对应上游请求头，不放 URL/body。Anthropic 必填 `max_tokens` 使用本阶段固定 4096，system 消息独立映射；Google systemInstruction/user/model 映射。暂不支持多模态/工具消息/高级参数，明确拒绝，不静默丢弃。
- `ProviderStream.kt`：OkHttp 4.12.0，关闭重定向、重试、cookie、磁盘缓存及自动认证；无日志拦截器。完整重组 SSE 数据帧，支持 CRLF/多行 data；遇到回显 Key（含 JSON unicode 转义、TCP 分片）拒绝整帧，provider 错误正文/headers/Throwable 不透传，只回固定错误码。这里不是对恶意供应商任意编码或跨事件变换的通用信息流证明。
- 下游 peer 关闭由独立 monitor 观察，直接 `Call.cancel()` 关闭上游 socket；不依赖后续 token 才观察取消。正常完成、错误、broker.close 都关闭 call/response/socket。Key 仅留 native 内存；已经开始的流使用该次 native snapshot，删除配置不会自动撤销已开始的请求，宿主后续需接显式取消与授权生命周期。
- `bot/NativeProviderBroker.kt`：可信主进程启动器可创建/关闭的 Keystore-backed 包装，提供的 session 只有 native endpoint/token，toString 不含凭据。**正式 Android 启动器尚未调用此组件，仍未把 descriptor 交给真实 runtime，不能说手机端模型已跑通。**
- 新增 OkHttp/Okio 许可登记及测试依赖；测试专用 org.json/MockWebServer 不进入 APK。新增 `.github/workflows/native-model-broker.yml`，固定编译器与 jar 校验值，运行同一可复现脚本。

### 本次实际验证

当前环境实查存在 OpenJDK 21 **运行时**、`/workspace/toolchains/kotlinc/bin/kotlinc` 2.4.0、API36 android.jar 和 Flutter embedding。不能沿用前一位“路径不存在”的结论，也不将仅有 android.jar 写成完整 SDK。

```bash
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps \
KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc \
bash dsh-go/tooling/test-native-model-broker.sh
```

结果：JUnit **6 passed** + Node→真实 Kotlin broker 跨进程 **4 passed**，无 skip。Key 防泄露用例的假 Key 在 native JVM 中随机生成，Node/runtime 从未拿到它；native 进程检查实际 runtime descriptor、环境快照、stdout/stderr、host 日志与 Agent 可读项目文件，并证明该 Key 曾真正注入模拟上游请求头。还验证 Key 回显/转义、异常、HTTP 错误正文和重定向不会泄露。只替换 Keystore resolver 与远端 provider，HTTP server/SSE/OkHttp/cancel 使用生产 Kotlin 源码。

首次取消测试失败是 MockWebServer 的 `throttleBody` 连请求体也限速，尚未进入响应阶段；改成只延迟响应体，并等待上游响应头后再取消，验证阻塞读取在 2 秒断言窗口内返回。一个初次 Node 断言错误是 native 在建立流前拒绝 transport 时，既有 host 协议统一映射 `broker_rejected`；修正测试匹配实际协议，不扩散 provider 异常。

相关回归：`model-broker.test.mjs` / `runtime-descriptor.test.mjs` / `provider-broker.test.mjs` / `service.test.mjs` **19 passed**；`cd dsh-go && python3 -m unittest discover -s app/test_scope -v` **4 passed**。第一次从仓库根运行 scope 命令路径错误，纠正工作目录后通过。

对 API36 + Flutter embedding + OkHttp/Okio 独立 kotlinc 编译 model 全部源码、BotProviderStore、BotSettingsPlugin、NativeProviderBroker 通过。不是 Gradle、不是 APK。扩展了 `ProviderStorageTest` 真机源码，用 Keystore 解密得到 native 请求、复查 model allowlist；**未验证（无 Android 设备/模拟器，未执行该仪器测试）**。

### 仍需继续

1. 原生组件的正式生命周期接入、可信 descriptor 私有传递、runtime 包与 Android Node 执行/隔离装配；不得为了打通流程而开启同 UID 任意 shell。
2. 真实 provider/model 配置映射和 runtime 消息/工具调用协议，不得把 Key 写进 YAML/env，也不能把现有 Node probe 的 resolver 当作允许向 Node 交 Key 的方案。
3. AndroidKeyStore、完整 Gradle/APK、真机 SSE/断网/取消/宿主销毁/独立 UID 与权限：**未验证**。JVM 测试不证明 Android 系统隔离，模拟 provider 不是真实 MiniMax API。
4. `fad4f3d` 新增 native CI **41586158979 success**、scope **41586158832 success** 已实查。旧 host CI **41586158827 failure** 是 agent-port 测试删临时 messages 目录遇到 ENOTEMPTY：上游 dispose 等待的 writeChain 是快照，后续收尾写仍可能入队。只给测试目录删除增加有限 retry，不重试模型请求，也不宣称生产 shutdown/drain 已修好；正式生命周期接入时仍需可等待的 turn/任务收敛。

## 先前已落地（host/Node 侧，历史）

- `dsh-go/runtime/minimax-bot-host/model-broker.mjs`：host 侧模型代理。runtime/插件 → host loopback 代理（runtime token）→ native broker（loopback，host token）→ provider。host 不持有 provider Key；错误只用稳定错误码，不拷贝上游正文；runtime 断开时向 broker 传递取消。
- `runtime-descriptor.mjs`：可信 runtime descriptor（入口路径、钉点 commit、loopback 端口、会话 token），写入宿主私有目录并要求 0600；拒绝工作区内、symlink、全局可读或私有根之外的 descriptor；不经 argv/环境变量传递。`buildRuntimeEnv` 用白名单过滤环境变量，provider Key 类变量不会传给 runtime。
- `native-broker-double.helper.mjs`：**仅测试用**的 native broker 与上游 provider 替身，实现与真实 native broker 相同的线协议（`POST /v1/model/stream`，Bearer 会话 token，SSE `data:` 帧，`[DONE]` 结束）。
- 测试：`model-broker.test.mjs`、`runtime-descriptor.test.mjs`。测试中的 Key 均为假值。

## 验证

- `cd dsh-go/runtime/minimax-bot-host && node --test`：**46 passed / 0 failed**（原 35 个 + 新增 11 个）。

## b7ca158 时的未完成项（历史，native 代码现已补齐，设备仍未验证）

1. native Kotlin broker 在该基线尚未实现；本次进展及剩余装配以本文开头为准。
2. 宿主真实打包/启动钉点 runtime 并写入 descriptor；配置/模型同步。
3. 真实模型流与取消的端到端验证（目前只有 host 侧替身测试）。
4. 没有 Gradle 完整构建、没有真机，均未验证。

## 说明

本轮用 mcode 自动开发时，第一次运行在 10:03 后无文件输出、约 50 分钟未按 `--timeout` 退出，被取消；补跑一轮也未产出。以上文件为已写出并测试通过的部分，按用户要求先提交，后续交给其他工具继续。
