<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeHost：真实 gateway 的内置 Bot 宿主身份

2026-11-04，续写 `feat/t-plugins`。本阶段是可信宿主装配组件，**不是 Android runtime 已启动或每 Bot MCP 身份全部完成**。总体状态见 [HANDOFF](../HANDOFF.md)、[NEXT-AGENT](NEXT-AGENT.md)。

## 问题与实现

钉点 `createWebuiRuntimeHost` 返回的 `host.application.miniAppAgent` 是应用 facade，没有 `registerArena`。另造一个 gateway 或手写17个 session ports 会脱离真实 runtime。现在通过 esbuild 的**一个精确 import 边**接入原 gateway 的创建：

- importer：`packages/local-runtime-v2/src/services.ts`。
- 原 specifier：`./service/miniapp/index.js`。
- 本地 adapter：`runtime-miniapp-index.mjs`，保留原 index 的全部 export，只包装 `createMiniAppAgentGateway`。
- wrapper仍调用原始、逐blob验证的 gateway constructor；上游 RuntimeServices 后续仍给这个对象绑定自己的真实 session ports。

上游源码未改、commit/tree pin未变。构建 manifest 的 `hostAdaptations` 明列这一条解析适配；这是显式的本地宿主行为变化，不能把「上游源文件字节未改」说成没有适配行为。构建测试检查真实 RuntimeServices 的 import graph 确实指向 adapter；接缝移动/消失即构建失败。

`runtime-host-session.mjs` 用 AsyncLocalStorage 将捕获限制在一次可信宿主创建中：dataDir必须匹配，只能捕获一个gateway；并发创建互不混用；构造结束后不再接受捕获。宿主成功返回后才注册固定内置服务ID `builtin:minimax-bot` 与随机UUID代次，再把原 facade交给原 `createBotAgentPort`。ID里的冒号在钉点两种插件manifest的name校验中均不允许，正常安装的插件包不能以同名替换内置arena。当前策略仅授予 `session.run`、并发2，队列/归属检查复用上游。

`runtime-bundle-entry.mjs` 新导出 `createBuiltinBotRuntime({dataDir, appVersion, signal})`：只用钉点真实factory，不接收请求提供的pluginId/runId或自选gateway。返回可信控制器使用的 `{agent, close}`；定义/构建此factory不会执行它，也不会启动HTTP/进程。Mini App presenter仍省略，不新增可视化Mini App容器。

## 生命周期

- close先关本地Agent入口，再撤销原arena（取消排队/在途turn），再调用原宿主close。
- 并发/重入close共用一个Promise，等待同一清理完成，失败仍可await观察；关闭失败也不重新开放Agent入口。
- 同时包装所取得宿主的apiHost.close，控制器走这个close也会撤销arena。
- 已abort的启动不建宿主；启动中abort会在factory返回后关闭取得的宿主，不返回可用Agent。**不宣称可中断一个不支持AbortSignal的上游factory**；进程级截止/终止仍属正式启动器。
- 关闭/abort后旧port不能因后续新代次注册而恢复使用。

以上是可信宿主生命周期，不是同进程JavaScript的系统级安全隔离。Main的Key、文件scope和执行权限仍须由独立UID/native broker强制。

## 代码定位

| 文件（以 dsh-go/runtime/minimax-bot-host 为根） | 职责 |
|---|---|
| `runtime-build-plugin.mjs` | 精确构建接缝和manifest说明 |
| `runtime-miniapp-index.mjs` | 保留原export并接原构造器 |
| `runtime-host-session.mjs` | 创建scope、内置服务arena、取消/关闭、旧port拒绝 |
| `runtime-bundle-entry.mjs` | 绑定真实WebUI factory的可信控制器入口 |
| `runtime-host-session-cases.mjs` | 8个真实gateway/facade用例；模型和session执行明确为桩 |
| `runtime-host-session.test.mjs` | 钉点校验、打包上述用例并用干净env在子进程执行 |

## 验证与发现

```bash
WEBUI_SOURCE=/tmp/mbot-mcode-reference \
ESBUILD_MODULE=/workspace/minimax-bot-android/dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js \
  node --test dsh-go/runtime/minimax-bot-host/runtime-host-session.test.mjs
```

本地真实gateway/facade的8项全部通过、0 skipped（外层是1项打包运行检查）。覆盖冒用caller/foreign session拒绝、并发scope、在途/排队取消、共享close、启动前/中abort、缺失/多重/错根gateway拒绝、清理失败仍撤销、旧port失效。foreign session遵循原错误 `SESSION_NOT_FOUND`，不泄露他人会话归属。测试子进程走环境白名单，避免继承NODE_TEST_CONTEXT造成没有TAP结果的假成功；外层要求实际用例数非零、fail/skipped均0。

完整宿主图另由 `runtime-bundle.test.mjs` 检查；增加精确adapter import/manifest断言后本轮最终3项全部通过、0 skipped（约80秒），远程CI以[NEXT-AGENT](NEXT-AGENT.md)最新记录为准。不是用此8项桩session替代完整runtime启动。

已在临时Linux宿主做独立的**真实factory启动探测**：按钉点pnpm-lock取40个直接依赖版本，npm安装禁用lifecycle scripts；额外核验官方release SHA256 `99c43785639d5d3690c396ba245ee680ac8b469a46b19233a3546f0eb7f8e312` 的better-sqlite3 12.11.1 Linux/x64/Node137预编译件，仅供宿主探测，不进入Android产物或仓库。探测使用全新私有临时HOME/dataDir，无用户配置、API Key或真实模型请求。先复现SQLite bindings缺失，装入后越过数据库迁移，随后复现 `Local Runtime V2 built-in Agent assets are missing`。因此单纯ESM bundle不能启动；随后临时复用原`copyLocalRuntimeAssets`复制资源，**旧control bundle的真实factory已实跑`HOST_READY true function`、`HOST_CLOSED`并退出0**，默认无模型Key/无turn。它验证缺口确为资源，不等于新Bot包装入口已完整验收；正在把资源纳入正式pin检查及重跑新入口。此临时安装**不是已交付的依赖lock/Android依赖兼容保证**。

## 剩余主线

1. 将原始assets/资源查找及动态加载变成受pin约束的构建产物，实际启动/关闭完整factory，并使用真实gateway/session实现验证；不能仅把本节桩当产品完成。
2. 正式native model resolver、JNI/Binder/模型FD和文件/SQLite适配。loopback URL/token不证明隔离UID能联网，600主UID文件不证明隔离guest可读。
3. 内置服务ID不等于每个Bot的可信caller_bot_id/MCP绑定；仍须将真实Agent会话绑定到Bot并验自主同源定时任务/通知。
4. Android独立UID启动器/正式Flutter transport和rootfs（如果仍需要它）；不得用DSH同UID/PRoot执行完整Agent来省事。
5. 插件宿主政策仍强制关闭变更；完整APK、真实provider、ARM64/真机、布局/后台/profile仍未验证。
