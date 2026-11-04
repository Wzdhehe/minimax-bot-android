<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 当前开发接手合同与完整快照

更新时间：2026-11-04 12:28 UTC。**本文件已重新整理为当前状态，不再向前堆叠互相冲突的历史TODO。** 历史版可看[3d686ee之前的交接](https://github.com/Wzdhehe/minimax-bot-android/blob/3d686ee5b9d0ee81863ab5fea548819da5be519e/docs/handoff/NEXT-AGENT.md)。旧专题里的「当前/下一步」须结合本文件和对应commit/run理解，不能见一句TODO就重做。

用户最后要求：准备收尾，交接要详细，等待原agent额度恢复再继续。本次收尾**不再扩功能**；接手后先完成第4节的CI核对，再按第5节限定任务推进。遇到没有实查的接口，先读源码/写清缺口，不得为展示进度虚构协议或开通入口。

## 1. 先认清交付状态

**已有真实Linux宿主启动证据，但尚无可用的Android Bot APK或模型对话。** 正式App入口仍是DSH；不能将验收工程、宿主Node或上游已有功能描述成手机功能完成，也没有可依据的「全量百分之多少」。

产品决定已固定：

- Android本机多Bot，DSH Flutter壳 + 钉住的mcode-webui/minimax-code runtime + 官方mbot业务；不是远控、手机操控、云运行环境或通用Agent工作台。
- 用户在设置填Key/Base URL/模型；真实Key只在native/Keystore，Dart/runtime/Agent/插件/descriptor/env/log/可读文件都不接收它。
- 手机固定竖屏；平板自适应展开、允许横屏；Grok布局参考，不能复制受限品牌素材。
- 自动任务在各Bot设置，手工与Bot经MCP创建共用mbot数据源；进程被杀不执行/不补跑，不新增系统闹钟/开机调度链。
- 一个私有总工作区filesDir/workspace，项目做子目录，Bot可共享项目；cwd不是授权。项目管理/绑定还未实现。
- Mini App是另一类可视化程序，暂不适配；原Bot仅复用业务，产品用Flutter UI。插件市场独立，生产变更仍强制关闭。
- 交互过渡/骨架/减少动画已有代码，不凭widget测试声称真机FPS。

## 2. Git硬约束与当前代码提交

仓库私有：Wzdhehe/minimax-bot-android。**只续写feat/t-plugins，不新分支。** 允许阶段commit/push，用户已授权，不反复问普通开发许可。

| 分支/阶段 | 精确SHA或含义 |
|---|---|
| main，禁止改 | e32fbd7383cd9bdc268aa7df2ec9150e8bc4e48e |
| dev/integration，禁止改 | 891aefa71c5aea952ec8aa2502c2ada854854042 |
| d31b379 | d31b37908e7b51dd1ae82b2ceecfdded8b0208df，原gateway的内置服务身份及生命周期 |
| f937a3e | f937a3e0de9319df49d76b0139cb9db786fc4d7b，780资源pin + 真实Linux宿主/Bot API重开验收 |
| 最新代码3d686ee | 3d686ee5b9d0ee81863ab5fea548819da5be519e，修Android full-ICU末尾版本报告并前置预检 |
| 纯文档收尾 | 本文件之后另有commit，最终HEAD以远端ref为准；不会改变以上代码证据 |

不force push、不改共享历史、不PR、不tag/release/APK发布。不`git add ./-A`；只暂存明确路径，不覆盖别人未提交的工作。新文件加AGPL头，vendor来源/许可保留。

```bash
git status --short --branch
env -u GH_TOKEN -u GITHUB_TOKEN git fetch origin feat/t-plugins
# 只有已经在feat/t-plugins、工作树干净且能快进时：
git merge --ff-only origin/feat/t-plugins
env -u GH_TOKEN -u GITHUB_TOKEN git ls-remote --heads origin main dev/integration feat/t-plugins
```

当前保存的gh CLI登录有效，但继承GH_TOKEN曾无效；必要时用env -u GH_TOKEN -u GITHUB_TOKEN前缀。不要读/输出凭据文件、token、环境全集、artifact签名URL；只用官方GitHub端点，不把私仓认证发给第三方镜像，不关闭TLS验证。

## 3. 本轮已经做完什么，禁止重复实现

### A. 内置服务身份：d31b379

createWebuiRuntimeHost返回的公开miniAppAgent facade**没有registerArena**。现在通过精确的RuntimeServices import接缝捕获它构建的同一个原gateway，再注册内置服务身份：

- importer固定packages/local-runtime-v2/src/services.ts，specifier为./service/miniapp/index.js。
- runtime-miniapp-index保留原export，只包装原createMiniAppAgentGateway；原RuntimeServices仍给同一对象绑定真实session ports。不是另造gateway，不是重写17端口，也不改上游文件/pin。
- AsyncLocalStorage限定单次启动、同dataDir、唯一gateway；并发构建互不串用；构建结束后不再捕获。
- 固定ID builtin:minimax-bot + 随机UUID代次；冒号不符合两种上游插件name规则，普通安装包不能以同名覆盖内置arena。策略session.run、并发2，其他检查沿用上游。
- close先关入口/撤销arena，再关原host；排队和在途turn取消；并发/重入close共用Promise；关闭失败也不恢复入口，旧port不能被新代次复活。
- 启动中abort要等原factory返回再关闭已取得资源；**不声称可即时中断不支持signal的factory**，进程级截止仍需native生命周期。

runtime-bundle-entry导出的createBuiltinBotRuntime只绑定真实factory，返回可信控制器用的agent/close，**不接收请求提供的caller/pluginId/runId**。manifest明列hostAdaptations；源码pin证明来源字节，不代表没有本地解析适配。

8个真实gateway/facade生命周期cases通过；该组session/model执行明确为桩。外层runner有1个打包检查，不能把1和8混为同一套计数。完整真实factory另见B。

### B. canonical资源及真实宿主：f937a3e

runtime-assets复用钉点copyLocalRuntimeAssets：helper和**780文件**逐Git blob验证，源/目标完整inventory及逐文件SHA256；忽略文件里的额外资源也拒绝，只允许普通文件。失败只清理本次新输出。

runtime-host-probe固定40个直接依赖版本（核原pnpm-lock），transitive由npm lock固定；**不声称npm与原pnpm peer布局完全一致**。npm lifecycle禁用。Linux/x64/Node137 SQLite12.11.1归档SHA256已固定验证，仅取固定tar成员；未知已装二进制拒绝覆盖。Linux模块绝不进入Android资产。

真实factory/gateway/session/SQLite未替换：

1. 全新私有HOME/dataDir冷启动完整fork，原数据库迁移和Agent资源初始化成功。
2. 内置caller经真实SessionSystem/SQLite查不存在的session，得到SESSION_NOT_FOUND；不是无注册/无绑定错误。
3. 原Bot HTTP拒绝匿名请求，鉴权后创建Bot。
4. 关闭并重开完整runtime和Bot服务，读回同Bot ID/名称；共享close、旧Agent port拒绝，自然退出。

真实host本地1/1、0 skipped，约13秒；CI约3.18秒。资源/构建4项全过，含独立clone中git status仍干净的ignored额外资源负例；身份8 cases全过。

**没有agent.run/模型请求/Key/Android执行。** 空provider配置的探索性models()仍INVALID_ARGUMENTS，未用假目录掩盖。旧Mini App网页未打包/未测试；只验Bot JSON API。模型、文件FD和正式launcher仍须装配。

### C. Android full-ICU收尾：3d686ee（新producer及API26/35验证成功）

旧run41757171111 attempt1预算退出但cache保存；attempt2实际完成5885/5885、SOLINK/strip后110.72MiB库和headers，随后原versions.py只允许intl=none而失败。**不是编译超时/磁盘/内存错误**。已读完整日志：3211 hits/5749（55.85%），2538 miss；0.3/3.0GB cache再次保存。artifact13317826081只是failed-cache-success状态，**没有成功归档可复用**。

修复只改build-source.py/test_source_recipe.py两个执行输入：

- 对原versions.py核SHA256 31727e9c467a5d2ea68ffc17e76cbe5603539cd48d32e454b4e2844d8feafb58，生成helper于.build，不改上游checkout。
- 明确要求真实full-ICU且icu_small=false，从真实头读取icu/unicode；不伪装none、不编造CLDR/tz，其他检查保留。
- configure后/Ninja前先验reporter，record时再验；archive记录原/生成helper摘要。
- Node source JSON、workflow、smoke、C/C++flags和cache namespace未改。旧缓存可供编译命中，**artifact producer authority没有放宽**；两执行输入已改，旧失败producer不可当新成功producer。

本地Python32全过；source recipe4项；真实pinned build.py/versions.py摘要核验及生成AST通过；真实Node头 + 已校验mobile header配合**模拟full配置**的parser smoke读到24.22.2/ICU78.3/Unicode17.0、26字段。不是新Android编译或Intl运行证明。

### D. 更早组件，避免重做

- Keystore/native四协议模型broker、流/工具/取消、JNI/Binder/模型FD组件已有证据；不是正式模型resolver完成。
- Main-held child cap的duplicate/openForHeldDirectory已做，node矩阵41794661470 API26/35各49项过。不要再创建第二套factory。
- Kotlin descriptor的41794831403 API26/35各19项过；**是在Main UID读回文件，不是隔离guest读回**。
- 四普通FD SQLite VFS/WAL/重开、Main文件publish/replace/read/size/move/remove/append已有组件证据。目录FD不外发；数据库close不撤销已发FD；append是1MiB/O(文件大小)/换inode，不是FileHandle/O_APPEND/大JSONL。
- Node descriptor已修token一致性、叶/父软链、额外字段落盘、extra环境白名单。同FD读取/owner检查仍依赖可信父目录，**不是同UID对抗沙箱**。
- Flutter Bot UI/设置/任务/插件只读页面和动画已有，别重新搭UI；正式入口仍DSH，生产插件写入关闭。

## 4. 接手第一项：先核对这些CI，不扩新功能

> **2026-11-04 20:54 更新：本节末段的「下一项」已完成。** a2c17d5只把android-node-probe.yml的既有`PINNED_SOURCE_RUN_ID`填为41826964330（未动五个producer权威输入、未放宽任何校验、未编造SHA），**41831847047整体success**：API26/35两job均success，Node probe counts **tests=49 failures=0 errors=0 skipped=0**；结果artifact 13324805400(api-26-t49-f0-e0-s0-passed-l0)、13322759984(api-35-t49-f0-e0-s0-passed-l0)。本轮prepare用的是钉住的full-ICU源包：`prepare-probe.py`核对config.gypi后拒绝非ICU源并把`requiresIntl`置真，设备侧`IsolatedNodeModelTest`在该标志为真时**实际断言**`intlAvailable`、`zh-CN` locale与`Asia/Shanghai`时区，因此这是ICU分支的执行证据，不只是「同样49项」。仍**没有**真实provider/模型turn、产品APK、ARM64或真机结论。

| run / 对应代码 | 收尾时状态与边界 |
|---|---|
| host41824727445 / f937a3e | success，日志已取：资源4、身份8 cases、真实host重开1，0 skipped |
| native41824727414 / f937a3e | success，native/JVM、SDK/pi-ai、Android组件构建；不是完整App |
| docs41824727413、scope41824727426 / f937a3e | 均success |
| source41826964330 / 3d686ee | **整体success，build及verify(26)/(35)全部success**。日志两设备均Starting 49 tests后BUILD SUCCESSFUL；原验证包含Intl/Key断言。artifact13318613900（45,047,372字节），progress13319082930 success-cache-success。旧run未重跑 |
| candidate41826964500 / 3d686ee | **整体及API26/35均success**；结果artifact两者均t49-f0-e0-s0-passed-l0。当前PINNED_SOURCE_RUN_ID仍空，默认release无Intl，因此不是新ICU产物的SQLite组合验证 |
| candidate41831847047 / a2c17d5 | **整体及API26/35均success**；pin=41826964330后prepare的是钉住full-ICU源包，Node probe counts tests=49 failures=0 errors=0 skipped=0；artifact13324805400(api-26)、13322759984(api-35)均t49-f0-e0-s0-passed-l0。Intl断言实际执行（requiresIntl为真时断言zh-CN/Asia-Shanghai）。仍非真实provider/产品APK/真机 |
| docs41826964360、scope41826964345 / 3d686ee | 均success |

```bash
env -u GH_TOKEN -u GITHUB_TOKEN gh run view 41826964330 --json status,conclusion,jobs
env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/Wzdhehe/minimax-bot-android/actions/runs/41826964330/artifacts --jq '.artifacts[]|{id,name,expired}'
env -u GH_TOKEN -u GITHUB_TOKEN gh run view 41826964500 --json status,conclusion,jobs
```

处理顺序不可跳：

1. **上述两run已成功，不再等待或重复编译。** 接手仍须核artifact未过期及当前五输入一致；若未来新run失败，取真实失败step/metadata/annotations，只修已证实故障，不猜OOM、不删校验、不重跑旧commit或开启并行长编译。
2. **已实查source build/upload success，并在本地实跑verify-source-run.py通过五输入authority**。41826964330现在可作为候选producer，但收尾未修改PINNED_SOURCE_RUN_ID、未启动新的组合矩阵；API26/35 verify现也success，下一轮可在原authority仍通过且artifact未过期时复用，不编造archive SHA。
3. 原verify-source-run.py必须核同仓库/分支/工作流/build成功及五执行输入逐字节一致。五输入为source workflow、node-source-build.json、build-source.py、smoke-compiler-cache.py、test_source_recipe.py。不要为复用而放宽。
4. source workflow的verify只证明其ICU/Key路径；它未prepare-sqlite，**不能代替当前SQLite/WAL/文件组合验收**。成功后用android-node-probe.yml既有PINNED_SOURCE_RUN_ID入口复用，再看API26/35当前组合矩阵/计数/实际JS分支。
5. candidate默认release的49项通过，也**不能证明Intl**。零skipped/相同计数不是相同功能路径证明。
6. 记录commit/run/实际结果和未验证项，更新HANDOFF/T-AndroidNode；阶段push，不动main/dev。

**上项已完成**（a2c17d5 / 41831847047，见本节文首更新）。

**本项已完成（2026-11-04，最终提交 9e32d04；真实 fork 验收 run 41840651106 / commit 044600c）**：native 非密钥目录投影与每轮受控绑定已落地，详见第5节与[T-RuntimeHost](T-RuntimeHost.md)。**下一项（目录接线已补齐，2026-11-04，commit f23775b / 8a52f85；host 41847283169、native 41847283225 / 41850609903、scope 41847283180 / 41850609904、设备 41850609902 全部 success）**：按[T-RuntimeBroker](T-RuntimeBroker.md)文首的契约，**descriptor 写入「剥离后」的非密钥目录这一半已经落地**——native 侧 `BotProviderStore.guestCatalogJson()` 产出允许列表目录（只有 providerId/name/apiFormat/enabled/models），`RuntimeDescriptorPayload` 编码时按**字段名**再挡一道凭据/端点，`createBuiltinBotRuntime` 改为只收 descriptor、目录由 `catalogFromDescriptor` 取出后交给钉点官方 provider API 投影，真实 factory 探针已走完整「写入→读回→投影」链路。**剩下的一半是 native broker binding 进 guest runtime 进程**：它依赖 Android 独立 UID 启动器（尚不存在），在此之前 `nativeBinding` 仍为 `null`，官方连通性测试仍记 `not_run`、模型请求仍无路由。再往后是每 Bot 可信 MCP caller 绑定与真实 provider 验收。仍不发明跨端协议，不把真实Key写进BYOK yaml/env/descriptor/log，未接broker时明确拒绝模型执行，不加假回答。

收尾只读取得source/candidate日志和API状态；source XML下载遇HTTP403，未本地解析其JUnit明细，故不编造source的f/e/s计数（49是日志启动数）。candidate的t49/f0/e0/s0来自现有结果artifact名称；后续组合验收还须核实际JS分支，而不只计数。

本项预期修改仅是成功后的既有producer接入及文档；只有未来出现已证实故障才修metadata。不要借机升Node/NDK、换源码、改sandbox、拉新runtime架构、重做页面。

## 5. CI后先核查新发行包，再做native metadata/model resolver

用户新增 **@minimax-web/minimax-code** 已发包信息。先按[发行包查证任务](../research/webui-published-package.md)核准确版本/registry/exports及现成接口；优先复用，不擅自升级。收尾查询官方npm metadata暂404、fork旧清单另有旧包名，均不能证明新包不存在，也不能猜包名替换；已请求用户提供直接链接/安装命令。本轮没有安装/切换。

**本项已实施（2026-11-04）**：runtime 侧已完成「native 非密钥目录 → 钉点官方 provider API」的投影与每轮受控绑定，**不再自写投影层**：复用钉点 `application.modelProviders.create/update/listUser/testProvider` 与 `application.models.list/select`（钉点 commit `97818b2890b4acae1c6bd1200a226ff506680628` 与 pin 未动）。新增 `runtime-catalog-policy.mjs`（目录字段策略）、`model-catalog-projection.mjs`（纯投影 + 官方调用序列）、`native-model-binding.mjs`（每轮模型键 → native route，复用既有 `createNativeModelRoute`/`createModelFetchScope`）、`native-model-runtime.mjs`（不依赖钉点 gateway 的装配层）；`runtime-descriptor.mjs` 携带非密钥 `catalog`，`runtime-host-session.mjs` boot 后装配并返回 `providerBinding`/`setSessionModel`，`agent-port.mjs` 每轮经绑定执行。

已确定的细节（不再是待定项）：

- **providerKey 命名**：显示名用「净化后的 native providerId」（只保留 `[A-Za-z0-9 _-]`，其余换 `-`），官方 `generateProviderKey` 因此产出**确定性**键名；不用用户友好名（官方对非 ASCII 名生成随机 `provider-<hex>`，跨重启模型键会变）。
- **模型键格式**：`custom_provider:<key>/<modelId>`，按**第一个** `/` 切分（`packages/local-runtime/src/config/model-key.ts`）。
- **每轮解析**：轮开始时取 `application.models.list({sessionId})` 里 `selected` 的那项，一轮内不重解析 → 切模型在下一轮生效、在飞轮用旧模型；目录外模型该轮明确拒绝（`model_not_available`）。
- **实测校正**：官方 `models.select()` 只要求 provider 已配置、`enabled !== false`、模型在列表内、`apiKey`/`baseUrl` 非空，**不要求连通性测试通过**；「先测通再选」只对 `saveAndUse` 成立（409 TEST_REQUIRED）。详见[映射笔记](../research/native-model-metadata-mapping.md)第 4c 节。
- **官方 update 的模型只能合并不能删除**：native 已删的模型会残留在 fork 目录可见但被每轮绑定拒绝，这是已知限制。

**仍未做**：native broker binding 进 guest 进程（**依赖 Android 独立 UID 启动器，当前不存在**，因此 `nativeBinding` 仍为 `null`、官方连通性测试仍为 `not_run`）、真实 provider 调用、每 Bot 可信 MCP caller 绑定、ARM64/真机、产品 APK、设置热更新（本阶段 boot-only）。**已做**：native 侧目录产出与 descriptor 携带、guest 从 descriptor 读回并投影（见[T-RuntimeBroker](T-RuntimeBroker.md)与[T-RuntimeHost](T-RuntimeHost.md)最新节）。

先实查真实字段/调用点并写映射表：

- Native BotProviderStore.catalog：providers（providerId/name/baseUrl/apiFormat/enabled/models/hasApiKey/maskedApiKey）、defaultProviderId/defaultModelId；不读readKeyForBroker到Node。
- fork config/src/config.ts、config/src/byok-config.ts；local-runtime/src/config/types.ts、config/model-key.ts、model-provider/list-models.ts。原模型key/schema必须以源码为准，**不得凭名称猜custom_provider/默认模型格式**。
- runtime-bundle-entry、原createWebuiRuntimeHost/default factory：目前没有native catalog/config getter装配。入口不得从请求收任意factory/caller。
- native-model-route、model-sdk-fetch、model-fetch-scope已有virtual HTTPS origin/nonsecret placeholder/scoped fetch组件；scope外无网络fallback。
- NativeModelRequestHost/ModelRequestClient已绑定UID、代次、provider/model和peer生命周期；不是任意URL/Key/模型路由器。

限定交付：先做最小metadata投影/默认选择和已有broker binding装配，每步要用真实fork factory验目录/映射，再用受控假provider验流/取消、Key不进入descriptor/env/log/Agent文件；真实provider需另验。每次只推进一个可验阶段。

**尚未定清楚的细节**：native ID到fork model-key的精确映射、metadata刷新协议/时序、一个runtime下多个模型binding路由、每Bot会话到MCP caller的可信绑定。先读类型/实际调用点；如果超出现有协议，先写具体方案和证据再向用户/主agent说明，**不要自行发明跨端协议并宣称兼容**。未接broker就明确拒绝模型执行，不能加假回答或直连provider fallback。

禁止把真实Key写入fork BYOK yaml、假装换成env更安全、让Flutter持有Key、对Agent暴露native broker endpoint/token或增同UID shell。非密钥SDK placeholder只用于真实broker路由，不能冒充成功模型。

## 6. 后续仍未完成的工程，别误当本轮范围

1. 完整runtime的node:fs/FileHandle/JSONL/恢复/SQLite lease到Main-held scope和FD；现小文件原语不等于完整FS。不得周期快照替代事务/恢复，不得replace leased SQLite inode。
2. Android独立UID正式启动器、私有交接/Flutter transport、生命周期和资源截止。600 Main文件/loopback TCP都不能直接给guest用；PRoot不是沙箱。
3. 每Bot可信MCP身份/自主同源任务、后台通知；内置服务pluginId不是每Bot caller_bot_id。用户请求中的caller_bot_id不能当授权。
4. 插件宿主安装/原生依赖政策和installed cursor协议差异；生产写入仍关闭，下载GitHub包不等于安全执行。不适配Mini App视图。
5. 项目管理/绑定、资料库/附件/群聊/记忆/审批等全量parity；先复用已有Bot/UI，不另起Nyral或agent-table引擎。
6. 完整APK、真实provider、ARM64/物理机、权限/后台/通知/手机竖屏/平板横屏/profile。没设备写未验证。

## 7. 核心路径地图

路径以仓库根为准；Kotlin包前缀为dsh-go/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app。

| 文件/目录 | 实际职责 |
|---|---|
| dsh-go/runtime/minimax-bot-host/runtime-host-session.mjs | scoped原gateway/内置arena/close/abort，非请求API |
| 同目录runtime-build-plugin.mjs、runtime-miniapp-index.mjs | 唯一精确import适配，不能静默fallback |
| 同目录runtime-bundle-entry.mjs、build-runtime-bundle.mjs、runtime-assets.mjs | 真实factory入口、图/字节/780资源构建；定义/构建不执行 |
| dsh-go/tooling/runtime-host-probe | 真实Linux host验收工程；依赖/原生模块不得作为Android产物 |
| Kotlin model/、bot/BotProviderStore.kt、bot/NativeModelRequestHost.kt | native Key/metadata、请求授权/流和动态FD |
| Kotlin isolation/ModelRequestClient.kt、RuntimeFileCommit* | 独立UID控制IPC、逐操作scope及有界pipe |
| Kotlin isolation/RuntimePrivateDirectory.kt、RuntimeAtomicPublisher.kt | Main-held目录/独立dup/shared inode gate/原子语义 |
| Kotlin isolation/RuntimeDatabaseFiles.kt | 四普通SQLite FD owner；close非撤销 |
| Kotlin runtime/RuntimeDescriptor.kt + runtime/RuntimeDescriptorPayload.kt | Main路径型writer与descriptor编码（含非密钥目录，字段名级守卫）；非完成的跨UID交接 |
| dsh-go/tooling/android-node-probe/src/main/cpp/node_probe.cpp | linked binding/libuv JNI验收，不是正式launcher |
| 同工程src/main/kotlin/.../nodeprobe/NodeIsolationProbeService.kt | NodeProbeRuntime在这个文件里，别找不存在的同名单文件 |
| 同工程src/main/js/node-fd-probe.cjs | 固定验收脚本，不是mbot/mcode产品入口 |

进一步背景：[T-RuntimeHost](T-RuntimeHost.md)、[T-RuntimeBroker](T-RuntimeBroker.md)、[T-AndroidNode](T-AndroidNode.md)、[T-RuntimeFiles](T-RuntimeFiles.md)、[T-RuntimeFS](T-RuntimeFS.md)、[T-Persistence](T-Persistence.md)、[T-Plugins](T-Plugins.md)。旧architecture卡的「每卡PR」已被用户不PR要求覆盖；sandbox中的「拟议/未接入」有历史语境，不可抹掉已有组件证据，也不可反向说成产品完成。

## 8. 当前Linux环境（换环境先实查）

不是上一位的Windows/D盘环境，不要沿用其SDK路径或重试Windows .tab锁问题。

| 工具/位置 | 本轮状态 |
|---|---|
| Node/Python | 24.19.0 / 3.12.14 |
| JDK21 | /tmp/mbot-jdk21（系统java仅JRE，构建用完整JDK） |
| Gradle9.3.1 | /tmp/mbot-gradle/gradle-9.3.1/bin/gradle；缓存/home/agent/.gradle |
| SDK/NDK | /tmp/mbot-android-sdk（platform36/build-tools36/CMake3.22.1）；/tmp/mbot-ndk27d为所需ABI/API26子集，不是所有NDK都齐全 |
| Kotlin/Flutter | /workspace/toolchains/kotlinc/bin/kotlinc 2.4.0；/workspace/toolchains/flutter；字体/workspace/toolchains/test-fonts |
| 固定fork源码 | /tmp/mbot-mcode-reference，干净97818b2890b4acae1c6bd1200a226ff506680628 |
| JVM依赖 | /tmp/mbot-native-deps，6个jar均按workflow SHA256恢复验证 |
| SDK/esbuild | dsh-go/tooling/model-sdk-probe/node_modules/.bin/esbuild 与esbuild/lib/main.js（0.28.2） |
| real-host依赖 | /tmp/mbot-runtime-host-probe/node_modules，241MiB；repo内runtime-host-probe/node_modules是忽略的symlink |
| 已构建host bundle | /tmp/mbot-runtime-host-probe/bundle-with-assets，含780资源；.build/host-probe.json保存准备位置；不提交 |
| 归档 | /tmp/mbot-node24-mobile.zip、/tmp/mbot-sqlite-12.11.1.tgz；Linux-host SQLite /tmp/mbot-sqlite-host-node137.tar.gz |
| metadata参考 | /tmp/mbot-node-mobile-build-reference.py、versions-reference.py、/tmp/mbot-node-version-headers仅临时header smoke，不是完整Node源码/编译 |
| 空间/设备 | workspace约860MiB、tmp约1.9GiB空闲；无本地adb/KVM/物理机，长Node编译走CI，不下载数GB环境填盘 |

/tmp可能随恢复清空，绝不根据路径曾存在写验证成功。保留proxy/CA和TLS验证；不读/复制凭据。仅清理自己创建且可重建的缓存，不删未知文件或停止别人的进程。

## 9. 已实跑、可复用的验证命令

仓库根执行；安装/准备遵循workflow固定摘要。按改动选择，不机械重跑所有平台。

```bash
# Python：本轮32项，包括source recipe4项
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_*.py' -v
# 源码/资源构建：4项；模型/session生命周期是另8cases
WEBUI_SOURCE=/tmp/mbot-mcode-reference \
ESBUILD_MODULE=/workspace/minimax-bot-android/dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js \
  node --test dsh-go/runtime/minimax-bot-host/runtime-bundle.test.mjs
WEBUI_SOURCE=/tmp/mbot-mcode-reference \
ESBUILD_MODULE=/workspace/minimax-bot-android/dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js \
  node --test dsh-go/runtime/minimax-bot-host/runtime-host-session.test.mjs
# 真实host已prepare后：1项（内部冷启动/重开两次），缺prepare必须失败而不是skip
npm test --prefix dsh-go/tooling/runtime-host-probe
# Key/流/取消：JUnit32 + Node50，本轮f937a3e的CI已过
KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc \
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps MBOT_JAVA=/tmp/mbot-jdk21/bin/java \
  bash dsh-go/tooling/test-native-model-broker.sh
python3 tooling/docs-links/check_links.py --root . README.md docs tooling/docs-links dsh-go/tooling/runtime-host-probe/README.md
/tmp/mbot-actionlint -shellcheck= -pyflakes= .github/workflows/bot-host.yml
git diff --check
```

不要递归扫描probe整个目录做docs检查（本地node_modules symlink会被正确判越界）。首次真实host准备的npm ci/build/download SHA/prepare命令完整见[probe README](../../dsh-go/tooling/runtime-host-probe/README.md)；npm install必须ignore-scripts，不能把原生install成功当Android兼容。

Android旧本地构建入口：JDK/SDK真实路径+Gradle --offline -g /home/agent/.gradle -p dsh-go/tooling/android-node-probe assembleDebug assembleDebugAndroidTest；缓存不齐可按正常proxy联网解析，不把依赖缺失说成源码错误。修改JNI/JS须先prepare-probe再prepare-sqlite，后者要恢复requiresSqlite标志；**prepare顺序错会漏掉实际SQLite分支**。完整命令以workflow和[T-AndroidNode](T-AndroidNode.md)为准，设备需看真实CI结果。

## 10. 可直接给接手agent的提示

```text
只续写Wzdhehe/minimax-bot-android最新feat/t-plugins，不另起分支。
先读AGENTS.md、docs/HANDOFF.md、docs/handoff/NEXT-AGENT.md当前合同、T-RuntimeHost/T-AndroidNode。
代码阶段d31b379/f937a3e已CI全过：真实Linux mcode factory+官方Bot API可启动/重开，尚无模型turn/Android产品。
3d686ee修full-ICU报告，source41826964330的build/verify26/35均success、authority检查通过；candidate41826964500 API26/35也success但用旧无Intl release。
收尾未填PINNED_SOURCE_RUN_ID；下一项按第4节将其设为41826964330，单独commit/push验当前组合矩阵，不重编长Node，不放宽五输入authority、不编造SHA。
若source成功，复用到当前SQLite/FS/Key组合矩阵；原source verify不是完整SQLite验收。
CI明确后先核用户新包@minimax-web/minimax-code的准确来源与现成接口，再按第5节做native metadata/FD；不猜接口/包名，不写真实Key到BYOK/env。
不重写17ports、不另造gateway、不传请求caller当身份，不开插件写入，不以同UID/PRoot启动完整Agent。
目录FD留Main；SQLite close不撤销已发FD；不增宽存储、远控/操控、MiniApp UI或系统scheduler。
不动main/dev、不force、不PR、不发布APK；每个可验阶段更新文档并push。
当前是Linux，临时工具路径须实查；没跑的写未验证，不编造APK/设备/模型进度。
```
