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

## 后续阶段：资源pin与真实完整factory验收已落地

新增`runtime-assets.mjs`，复用同一钉点的`copyLocalRuntimeAssets`，将canonical资源纳入构建，当前**780个资源文件**。helper源码及每个资源都对比钉住树的Git blob；复制前检查完整source inventory（含被Git忽略的额外文件），复制后检查完整output inventory和每个字节，manifest列每个来源/blob/大小/SHA256。只允许普通文件，不跟随资源软链接。构建失败清理仅本次新输出目录。

`runtime-bundle.test.mjs`当前**4项通过，0 skipped，约94.8秒**：既有真实full graph/摘要/import接缝、输出不覆盖/父软链防护，再加独立clone中被ignore的额外资源必须导致构建失败且无输出残留。该clone在故意加文件后git status仍干净，证明不是碰巧被dirty-check拦下。

`tooling/runtime-host-probe`现为正式可复现的宿主验收工程：package/npm lock固定依赖，40个直接版本逐个对比原pnpm-lock；独立校验原生SQLite12.11.1 Linux/x64/Node137归档SHA256，仅取固定tar成员，未知已安装二进制拒绝覆盖。`prepare.mjs`核对bundle/resource摘要和source pin后再准备；不安装lifecycle scripts，不把Linux模块当Android依赖，不提交/上传node_modules和生成物。命令与准确边界见[probe说明](../../dsh-go/tooling/runtime-host-probe/README.md)。

新包装入口已在该工程实跑：

```text
PASS: pinned runtime, 40 direct dependency versions, resources and Linux-only SQLite verified; host probe prepared
✔ real pinned runtime boots with native host SQLite and serves the official Bot across a clean reopen (12939.617109ms)
ℹ tests 1
ℹ pass 1
ℹ fail 0
ℹ skipped 0
```

这个用例没有替换factory/gateway/session/model实现，使用全新私有HOME/dataDir，实际启动/关闭**两次**完整factory：已注册caller查询真实SessionSystem/SQLite不存在的session，得到SESSION_NOT_FOUND；原Bot HTTP拒绝匿名请求，创建Bot后重启读回同ID/名称；重复close等待同一Promise，旧Agent port拒绝。测试断言自然退出，挂起/超时失败。没有`agent.run`或真实模型调用。旧Mini App web client不打包、不验收，产品仍用Flutter UI。

首轮正式测试的child检查全部完成并退出0，但diagnostic按startsWith寻找PASS行时被上游彩色日志前缀干扰，返回undefined，引发Node test runner TypeError。只修diagnostic提取为已assert存在的字符串，保留全部runtime断言；修后完整1/1通过。文档扫描要指定probe README文件，不递归扫被忽略的node_modules：本地node_modules是指向/tmp的symlink，检查器会正确拒绝它越出仓库，不能把这种工具使用错误记成坏链接。

探索性`agent.models()`在空provider配置下返回INVALID_ARGUMENTS，未用假模型列表遮盖；本用例证明真实宿主/Bot数据链，并不证明已同步native模型或生成回答。下一阶段先对接Keystore/native目录的**非密钥metadata**及受控模型binding，再接原SDK/JNI/Binder/模型FD；Android filesystem/launcher仍缺。

## 剩余主线

1. canonical assets与真实Linux factory/Bot JSON冷启动重开已验；继续native模型metadata/resolver与FD绑定。其他动态worker/原生依赖资源仍需Android打包审计，不能将Linux宿主成功提升成Android成功。
2. 正式native model resolver、JNI/Binder/模型FD和文件/SQLite适配。loopback URL/token不证明隔离UID能联网，600主UID文件不证明隔离guest可读。
3. 内置服务ID不等于每个Bot的可信caller_bot_id/MCP绑定；仍须将真实Agent会话绑定到Bot并验自主同源定时任务/通知。
4. Android独立UID启动器/正式Flutter transport和rootfs（如果仍需要它）；不得用DSH同UID/PRoot执行完整Agent来省事。
5. 插件宿主政策仍强制关闭变更；完整APK、真实provider、ARM64/真机、布局/后台/profile仍未验证。

## 远程验收：f937a3e

本阶段已推f937a3e0de9319df49d76b0139cb9db786fc4d7b。host41824727445、native41824727414、docs41824727413、scope41824727426全部success；host日志已取得，真实factory/Bot重开用例3.18秒、0 skipped，资源4项与身份8 cases均通过。仍没有模型turn或Android执行；下一主线是native metadata/model resolver/FD。Android full-ICU收尾故障另见[T-AndroidNode最新节](T-AndroidNode.md)。
