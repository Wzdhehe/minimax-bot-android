<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeHost：真实 gateway 的内置 Bot 宿主身份

2026-11-04，续写 `feat/t-plugins`。本阶段是可信宿主装配组件，**不是 Android runtime 已启动或每 Bot MCP 身份全部完成**。总体状态见 [HANDOFF](../HANDOFF.md)、[NEXT-AGENT](NEXT-AGENT.md)。


## 继续开发：Bot HTTP通过已连接FD（宿主验证）

`createBotFdTransport`接收可信启动器显式交付的已连接socket FD，检查socket类型、限制64个活动连接、禁止重复FD、按所有权关闭；它把连接送入真实官方Bot的HTTP server，guest本身不listen。`server.mjs`只新增可选`listenServer` host hook，原TCP方式和全部鉴权、路径/Origin/Host检查保留，变更记录见[vendor来源](../../dsh-go/runtime/vendor/UPSTREAM.md)。关闭transport立即销毁连接，活跃SSE可能看到中断，调用方须处理。

本机Linux宿主：实际跨进程继承Unix连接，真实Bot验证匿名、错误Origin和错误Host均403，带token状态读取/创建Bot成功，SSE收到hello与state.changed，重复FD不杀旧连接；未打开Bot TCP监听（父进程预先占住逻辑端口）。host-contracts 84/84、官方gateway smoke、完整pin 2124输入/780资源图及真实fork/Bot重开1项通过。**没有Android侧传输/正式launcher/Flutter接线，也没有完整隔离文件系统或真实provider**。下一步须复用现有独立UID/UID握手与Binder FD通道，在API26/35实际验双向socket及取消/关闭，然后才接真正产品入口。

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

## 后续阶段：native 非密钥目录投影与受控绑定（2026-11-04）

本阶段让 fork runtime 看见 native 侧配置的供应商与模型（**非密钥**），并让每一轮模型请求都经 native broker；真实 Key 仍只留在 native。交付方式是**复用钉点官方 API**，不自写投影层、配置写入、目录构建与默认选择。

复用的官方能力（钉点 commit `97818b2890b4acae1c6bd1200a226ff506680628` 未改，`pin` 未动）：

| 需要的能力 | 复用的官方 API | 位置（钉点） |
|---|---|---|
| provider 条目写入（配置锁、原子写、apiKey/apiFormat 校验、保留键、模型合并） | `application.modelProviders.create / update` | `packages/local-runtime-v2/src/application/session/process-local-application-contract.ts:459-500` |
| provider 键名规则（保留键、slug、冲突后缀） | `generateProviderKey` | `service/model-system/management/provider-key.ts:22-47` |
| 目录视图与默认模型选择 | `application.models.list / select` | 同上 `:430-458` |
| 连通性测试记账 | `application.modelProviders.testProvider` | 同上 `:504` |

新增薄层（`dsh-go/runtime/minimax-bot-host/`）：

| 文件 | 职责 |
|---|---|
| `runtime-catalog-policy.mjs` | 目录字段策略：凭据类字段与端点类字段一律拒绝（非密钥 `hasApiKey` 布尔量放行）。descriptor 与投影层共用同一份规则，避免两处漂移 |
| `model-catalog-projection.mjs` | `projectNativeCatalog`（纯投影）+ `applyNativeCatalog`（官方调用序列：create/update → 连通性 → `models.select`） |
| `native-model-binding.mjs` | 按轮把模型键解析成 native route，复用既有 `createNativeModelRoute` 与 `createModelFetchScope`；不实现任何 HTTP/SSE |
| `native-model-runtime.mjs` | **不依赖钉点 gateway** 的装配层：投影 → 建绑定 → 装一次 scoped fetch → 每轮解析模型键 |

改动：`runtime-descriptor.mjs` 携带非密钥目录（`catalog` 字段，冻结，写盘前校验）、`runtime-host-session.mjs` 在 boot 后装配并返回 `providerBinding` 与 `setSessionModel`、`runtime-bundle-entry.mjs` 透传 `catalog/probe/nativeBinding`（**本阶段之后改为只收 descriptor，见下节**）、`agent-port.mjs` 每轮经 `binding.runForModelKey` 解析并执行。

已实现并有断言的边界：

- 目录出现凭据类字段 → **整体拒绝**（`native_catalog_contains_credential` / `descriptor_catalog_contains_credential`）；出现真实端点字段 → 拒绝（`native_catalog_forbidden_field` / `descriptor_catalog_forbidden_field`）。不做部分投影。
- guest 内只有虚拟 origin `https://models.mbot.invalid` 与占位串 `native-broker-placeholder`；真实 baseUrl 与 Key 不进 descriptor / config.yaml / env / log。
- 显示名用「净化后的 native providerId」，让官方 `generateProviderKey` 产出**确定性**键名；不用用户友好名（官方对非 ASCII 名生成随机 `provider-<hex>`，跨重启模型键会变）。
- 每轮**开始时**解析一次该轮模型键（取 `application.models.list({sessionId})` 里 `selected` 的那项），轮内不重解析 → 切换模型在下一轮生效，在飞轮继续用旧模型。
- 模型键不在 native 目录内 → 该轮明确拒绝（`model_not_available`），不直连、不给假回答、不回退。
- provider 的模型在官方 update 里**只能合并、不能删除**：native 已删掉的模型会残留在 fork 目录里可见，但每轮绑定会明确拒绝它。这是已知限制，不是静默放行。
- 无目录时 guest 行为与本阶段之前完全一致（不调用 provider API、不装 scoped fetch、不改全局 fetch）。

**实测校正**：官方 `models.select()` 的两个前置（`assertModelAvailable` + `assertModelSelectable`）只要求 provider 已配置、`enabled !== false`、模型在列表内、`apiKey` 与 `baseUrl` 非空——**不要求连通性测试通过**。因此「占位 key + 虚拟 origin」的 provider 可以直接被官方选中；设计文档里「先测通再选」的前提只对 `saveAndUse` 那条路径成立（409 TEST_REQUIRED）。详见[映射笔记](../research/native-model-metadata-mapping.md)第 4c 节。

### 本阶段验证

本机（Windows）可跑部分：`model-catalog-projection` 16 项、`native-model-binding` 10 项、`native-model-runtime` 5 项、`agent-port` 9 项（含原有 2 项）全过。`runtime-descriptor` 原有 7 项在本机失败，**原因与本阶段无关**：Windows 不支持 POSIX 权限位语义（`descriptor_permissions`）与符号链接（`EPERM`）；已在改动前的 baseline 上复跑确认失败项完全相同。

真实 fork factory（Linux，只能 CI 跑）：`runtime-host-session-cases.mjs` 新增一例，走**真实钉点 gateway/facade**，用假 provider 门面断言投影调用序列、`providerBinding`、每轮绑定与切模型拒绝。

**远程验收（最终）：commit 9e32d04 的 host 41842253311、native 41842253222、scope 41842253245 全部 success；docs 41842002392（a62ef0b）success。**

过程如实记录：descriptor 携带目录后（commit b0974c3 起），native-model-broker 的 native-boundary job 连续三次 **failure**（run 41839610040 / 41839956006 / 41840362270），真因是 `native-model-broker.test.mjs` 里断言 descriptor **精确键集合**为 `['broker','entryPath','pinCommit','port','token']`，新增的 `catalog` 字段使其失配——不是产品逻辑回归，但确实打断了 CI。修复在 commit `9e32d04`：该断言改为包含 `catalog`，并**加强**为同时断言目录只含 provider/apiFormat/models、且序列化后的 descriptor 不含 `apiKey`、`baseUrl`。修后 native-boundary 的 Node 段 **tests 52 / pass 52 / fail 0 / skipped 0**，其中 `native-held key never reaches runtime descriptor, environment, logs or Agent-readable files` 为 `ok 31`；该 job 的 `provider-sdk-streams` 与 `android-component-compilation` 也均 success。

**真实 fork factory 验收：run 41840651106（commit 044600c）整体 success**（该提交未触及 `dsh-go/runtime/minimax-bot-host/**`，故未触发 native-broker workflow）。

- `host-contracts` job：success。三份新测试已进 CI 清单（`model-catalog-projection` / `native-model-binding` / `native-model-runtime`）。
- `Pin and smoke the upstream runtime fork` job：success。
  - 「Verify trusted Bot caller lifecycle against the pinned gateway」：真实钉点 gateway/facade 的 cases **tests 9 / pass 9 / fail 0 / skipped 0**，其中新增用例 `the native catalog is projected through the upstream provider API and every turn is bound` 为 `ok 2`。
  - 「Boot and reopen the actual runtime and Bot JSON service」：真实 factory 冷启动/重开通过，输出 `PASS: real fork factory cold-start/reopen, projected non-secret provider catalog with placeholder credential and virtual origin, ...`。该用例断言的是**真实 fork 写出的** `<dataDir>/config.yaml`：`custom_provider.probe.options.apiKey == native-broker-placeholder`、`options.baseURL == https://models.mbot.invalid/v1`、`api == openai-completions`、模型 `probe-model` 在列表内、`defaultModel == custom_provider:probe/probe-model`，且配置里**没有** `sk-*` 形态串、也没有 `api.minimax.io`。

**本阶段仍未证明**：真实 provider 调用（占位 key 与虚拟 origin 不可能被真实供应商接受）、ARM64/物理真机、产品 APK、每 Bot 可信 MCP caller 绑定、设置热更新（本阶段 boot-only），以及 native 侧把目录写进 descriptor（尚未接线，契约见 [T-RuntimeBroker](T-RuntimeBroker.md)）。

## 后续阶段：native 目录接线闭环（2026-11-04，commit f23775b）

上一阶段把「native 非密钥目录 → 钉点官方 provider API」的投影层做完了，但**目录由谁产出、经什么通道进 guest**还没有落地：descriptor 只是「能带 `catalog`」，native 侧并没有真的产出目录，guest 也没有真的从 descriptor 读。本阶段把这一段补齐，链条是「native 产出剥离后的非密钥目录 → descriptor 携带 → guest 读回 → 真实 factory 投影」，**没有旁路**。

### native 侧：允许列表产出 + 字段名级守卫

新增 `bot/GuestProviderCatalog.kt`：`GuestProviderRow` + `GuestProviderCatalog.encode(providers, defaultProviderId, defaultModelId)`。这是**允许列表**投影，只写 `providerId/name/apiFormat/enabled/models` 五个字段；即便调用方手里的行还带 `baseUrl` 或 Key，**构造上**也写不出去。`BotProviderStore.guestCatalogJson()` 是 native 侧唯一目录出口；给设置页用的 `catalog()`（含 `baseUrl`/`maskedApiKey`）原样不动、不得进 descriptor。

新增 `runtime/RuntimeDescriptorPayload.kt`，把 `RuntimeDescriptorSession` 与 descriptor 编码从含 `android.*` 的 `RuntimeDescriptor.kt` 拆出来（**同 package、同名，调用方不变**），这样**安全相关的序列化可以脱机 JUnit 实跑**；`RuntimeDescriptor.kt` 只剩文件操作（Os/权限/rename）。编码时对目录做**字段名**级校验，规则与 Node 侧 `runtime-catalog-policy.mjs` 一致：凭据类字段名（apiKey/secret/token/…）拒绝、端点类字段名（baseUrl/url/endpoint/host/…）拒绝。只看字段名，所以**读起来像密钥的 model id 不会被误拒**。

### guest 侧：目录只从 descriptor 来

`runtime-bundle-entry.mjs` 的 `createBuiltinBotRuntime` 不再接受 `catalog` 参数，改为接受 **descriptor 对象**：`catalogFromDescriptor(descriptor)`（新增于 `runtime-descriptor.mjs`）会**重新校验** descriptor 后取出目录。手搓的 descriptor 不能放宽边界：目录含凭据/端点字段照样整体拒绝。`createBotRuntimeSession` 仍是下层接缝、继续收 `catalog`，不变。同文件导出 `readRuntimeDescriptor`，guest bootstrap 用它读回自己的 descriptor。

### 真实 factory 探针改为走完整链路

`tooling/runtime-host-probe/runtime-host-child.mjs` 现在是：native 侧用 `writeRuntimeDescriptor` 写 descriptor（含剥离后的目录）→ **guest 侧用 bundle 导出的 `readRuntimeDescriptor` 读回** → 把读回的 descriptor 交给 `createBuiltinBotRuntime` → 断言真实 fork 写出的 `config.yaml` 仍是「占位 key + 虚拟 origin + `defaultModel` 生效」。另加一条负例：手写的、带 `baseUrl` 的 descriptor 即使是 owner-only 普通文件，也被 guest 读回路径整体拒绝（`descriptor_invalid`）。

### 本阶段验证

本机（Windows）可跑：Kotlin `GuestProviderCatalogTest` + `RuntimeDescriptorPayloadTest` **8 项全过**（kotlinc 2.1.21 + JUnit 4.13.2，脱机 JVM 实跑）。Node 侧新增用例 `the guest reads the catalog from the descriptor it validated itself` 通过；host-contracts 合计 73 项 / 64 过 / 9 失败，失败项与改动前 baseline 完全相同（`runtime-descriptor` 7 项 + `workspace` 2 项，Windows 的 POSIX 权限位语义与符号链接限制，与本阶段无关）。设备侧新增 `ProviderStorageTest.theGuestCatalogCarriesTheProviderButNoEndpointAndNoCredential`（真实 Keystore 存储上断言 guest 目录只有 allow-list 五个字段、没有端点与凭据），随 `android-broker-device` workflow 在模拟器上执行。

真实 fork factory（Linux，只能 CI 跑）：真实 factory 用例改为走 descriptor → guest → 投影链路。

**远程验收（commit f23775b / 8a52f85）**：host **41847283169** success——`host-contracts` success；`Pin and smoke the upstream runtime fork` success（pin 23/23、构建 4/4、真实 gateway/facade cases 9/9、**真实 factory 1/1**，0 skipped）。真实 factory 用例的输出已是 `PASS: real fork factory cold-start/reopen, **descriptor read-back** with projected non-secret provider catalog with placeholder credential and virtual origin, ...`，即它确实走了「native 写入 descriptor → guest 读回 → 真实 fork 投影」。native **41847283225**（f23775b）与 **41850609903**（8a52f85）success——`native-boundary` success（**JUnit `OK (40 tests)`**，较上一阶段的 32 项新增 8 项；**Node 段 tests 53 / pass 53 / fail 0 / skipped 0**，新增用例 `the guest reads the catalog from the descriptor it validated itself` 为 `ok 43`），`provider-sdk-streams` success，`android-component-compilation` success。scope **41847283180** / **41850609904** success。设备 **41850609902** success——API26 与 API35 模拟器各 **tests 20 / failures 0 / errors 0 / skipped 0**，其中 `ProviderStorageTest` 3 项（含新增的 `theGuestCatalogCarriesTheProviderButNoEndpointAndNoCredential`）实际执行并通过（结果 artifact 13335566603 / 13336156687）。

## 剩余主线

1. canonical assets与真实Linux factory/Bot JSON冷启动重开已验；继续native模型metadata/resolver与FD绑定。其他动态worker/原生依赖资源仍需Android打包审计，不能将Linux宿主成功提升成Android成功。
2. 正式native model resolver、JNI/Binder/模型FD和文件/SQLite适配。loopback URL/token不证明隔离UID能联网，600主UID文件不证明隔离guest可读。
3. 内置服务ID不等于每个Bot的可信caller_bot_id/MCP绑定；仍须将真实Agent会话绑定到Bot并验自主同源定时任务/通知。
4. Android独立UID启动器/正式Flutter transport和rootfs（如果仍需要它）；不得用DSH同UID/PRoot执行完整Agent来省事。
5. 插件宿主政策仍强制关闭变更；完整APK、真实provider、ARM64/真机、布局/后台/profile仍未验证。

## 远程验收：f937a3e

本阶段已推f937a3e0de9319df49d76b0139cb9db786fc4d7b。host41824727445、native41824727414、docs41824727413、scope41824727426全部success；host日志已取得，真实factory/Bot重开用例3.18秒、0 skipped，资源4项与身份8 cases均通过。仍没有模型turn或Android执行；下一主线是native metadata/model resolver/FD。Android full-ICU收尾故障另见[T-AndroidNode最新节](T-AndroidNode.md)。
