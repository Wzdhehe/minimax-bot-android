<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 下一位 Agent 的详细交接（2026-11-04 UTC）

本文件包含多轮接力记录；以最前面的「最新接手快照」为当前状态，编号章节保留历史和详细背景。本文件也是用户要求的详细开发交接。配合[总入口](../HANDOFF.md)、[架构](../architecture.md)、[安全边界](../sandbox.md)、[文件能力](T-RuntimeFiles.md)和[模型 broker](T-RuntimeBroker.md)阅读。后面的新提交、远程 CI 状态仍须实查；本文件不把历史观察自动提升成当前结论。

## 最新接手快照：Linux 续写与启动鉴权修复（2026-11-04 UTC）

本轮从远端 **7967b9d** 快进接手，仍在 `feat/t-plugins`。本节覆盖后文第6/7/8/11节中已过时的「下一步」「正在编译」「源码不存在」描述；Windows环境记录只描述上轮主机。

### 最新：回查旧实现，修复descriptor文件与环境边界

用户要求接手时复核既有质量；本轮没有只沿用「已完成」标签。对Node `runtime-descriptor.mjs` 的读写/环境交接发现并分别在修复前复现四个用例失败：

1. 已存在的descriptor文件若是指向workspace文件的软链接，旧writeFile会写穿并覆盖目标；现使用私有随机stage目录、O_EXCL/O_NOFOLLOW创建文件、写入/fsync后rename替换链接本身，目标项目文件保持原样。
2. 父目录软链接可把descriptor读/写重定向到workspace或private根外，旧检查仅比路径字符串；现对已有父路径解析真实位置，再核allowedRoots和workspace排除，新目录也先核对真实已有祖先。
3. writer直接JSON.stringify调用方传入的对象，即使正常descriptor builder会过滤字段，直接调用writer仍可把额外apiKey字段写进文件；现先经createRuntimeDescriptor校验/白名单投影，序列化得到的固定字段对象。
4. buildRuntimeEnv对baseEnv用allowlist，对extra却只按敏感变量名黑名单过滤；extra.CONFIG_JSON/NODE_EXTRA等普通变量名可携带凭据内容。现extra也只能使用同一allowlist里的名字，保留受允许变量的覆盖能力。

reader改用O_NOFOLLOW打开文件，对**同一个FD**做fstat（普通文件、当前UID、owner-only）与JSON读取，避免检查后按路径重新打开。测试临时目录也统一清理，不留旧descriptor fixture。

**准确边界**：Node实现仍要求私有目录及其祖先由可信宿主持有，不是openat/held-dirfd能力，不能防同UID恶意代码并发替换目录。Android Kotlin writer本轮未改，其路径型API仍需要在正式Main-held目录/FD启动链中装配；不能因Node helper修复就声称整条native/guest交接已隔离。模型Key仍只在native，loopback descriptor不是隔离UID的网络许可。

修复前叶软链/父软链/额外字段三项失败（8/11），extra环境用例也单独复现失败（11/12）。本阶段最终完整host/native结果与新CI见下一条验证记录；此前5873c98的成功CI只证明那个阶段，不代替本次修复。

本阶段本地最终实跑（Kotlin2.4/JDK21、Node24.19.0，均退出0）：

```text
宿主 agent/service/runtime-descriptor/workspace/provider/usage/schedule：
ℹ tests 32
ℹ pass 32
ℹ fail 0
ℹ skipped 0
完整 test-native-model-broker.sh：
OK (32 tests)
# tests 50
# pass 50
# fail 0
# skipped 0
```

descriptor独立12项包含四项新增回归，已纳入上述两套检查。文档扫描31份0问题，git diff --check退出0。本阶段未改native或重新运行Android设备；Key边界仍通过真实native/JVM fixture注入与子进程观察验收，不能称真实provider或真机验收。新push后的CI待实查。

### 已实查的5873c98远程CI（本次路径修复前）

- host **41813024238 success**：常规宿主28、pin23、完整宿主构建图3项全过，0 skipped；已取得日志，完整图测试约36.6秒。旧gateway smoke仍明确是stub模型/session。
- native **41813024224 success**：Kotlin2.1.21/JUnit32、Node46项、真实SDK/pi-ai jobs通过；Android组件test/assemble任务`BUILD SUCCESSFUL in 2m 29s`。仅组件编译，不是完整Bot APK或新设备运行。
- docs **41813024207 success**；scope **41813024280 success**。
- 289f88f的native首次失败历史保留在下面，现已被5873c98的修复后成功run覆盖，不要再把它写成当前阻塞。

### 后续阶段：完整fork宿主的构建图已落地

新增 `runtime-bundle-entry.mjs`、`build-runtime-bundle.mjs` 和 `runtime-bundle.test.mjs`（均AGPL头），不是另写runtime。entry导出钉点真实`createWebuiRuntimeHost`、现有`createBotAgentPort`和`startBotService`；构建不调用它们。脚本复用fork自己的workspace export resolver，将原先仅6个契约输入扩为**实际完整宿主图**，并通过现有pin检查器逐blob验收。构建图内引用的上游workspace模块须真正进bundle，不能悄悄留为external；第三方保持external，不安装也不执行。

本轮实际产物：**2,124个上游源码输入、49个外部包specifiers、13,296,231字节**（esbuild0.28.2）；另包含本仓库适配器及官方Bot服务。输出在 `/tmp/mbot-runtime-host-build`，不提交生成物。输出包含partial bundle、原metafile、manifest（SHA256、源码/依赖/警告清单）。manifest明确`status=build-graph-only`、`androidReady=false`；只校验编译/语法，不import或启动bundle。

真实集成测试初轮2/2通过；加入输出父目录symlink防护后，**最终3/3通过、0 skipped，49.6秒**：实际build/摘要/原session-ports与Bot服务输入、不得剩余workspace包、故意向完整metafile添加`.git/config`必须被真实pin检查器拒绝、构建后checkout仍干净、不得覆盖已有输出、输出父目录symlink不得把产物写回钉点checkout。`bot-host.yml`已加入该测试和ESBUILD_MODULE；pin原23项通过，原gateway smoke也通过，仍明确其模型/会话为桩。pin的日志原来总说「恰好6文件」已修为实际输入数，UPSTREAM-RUNTIME.md及代码注释同步了整树校验语义。

**新发现的具体打包缺口**：49项是esbuild输出里发现的外部引用，绝不是完整依赖lock。`better-sqlite3`通过`createRequire(variable)`载入，实际可不在该列表；computed imports、原生二进制、资源和import.meta.url相对路径仍需打包审计。原node:fs/child_process/网络调用仍存在，禁止以此编译成功为由同UID启动Agent。正式caller、隔离I/O、rootfs与启动器仍按下面顺序继续，未因本阶段自动完成。

### 本轮CI发现并修复的旧Key测试断言

289f88f的host run41811656424、文档41811656733、scope41811656514均success；native run41811656655整体failure，其中Android组件编译和实际SDK/pi-ai jobs success，native-boundary里的JUnit32通过、Node45/46通过。**已取得失败日志**：Key防泄露用例最后仍严格要求旧四字段descriptor，但上轮已加`broker:null`。不是Key泄露分支失败；也不能把整体CI写成通过。

修正为仍严格要求五字段`broker/entryPath/pinCommit/port/token`，并断言此FD/proxy夹具的broker必须为null。保留JVM检查runtime descriptor、env、日志和Agent可读文件不含native生成的Key等所有断言。没有改变生产权限或放宽Key检查。重新恢复6个固定jar并逐SHA256校验，已用本环境Kotlin2.4/JDK21实跑完整native脚本，**JUnit32/32、Node46/46通过，0 skipped、退出0**（Node约20.3秒）。CI使用Kotlin2.1.21，5873c98的修复后41813024224也已全success（32/46）；本轮后续descriptor路径硬化会触发新的CI，不能借用此run。不要重跑旧失败commit期待断言改变。

### 本阶段实际修改

- `runtime/minimax-bot-host/service.mjs` 的 `startBotService` 接受可信启动器的 `authToken`。此前服务总是另生成 token，与 Kotlin descriptor 的 token 不同，照旧交接顺序启动会导致 Flutter 鉴权失败。现在显式 token 先校验后操作文件/监听，未提供时仍随机生成；错误只返回固定文本，不回显输入。
- Node descriptor 的服务/broker token 长度与 Kotlin 对齐为32–64位小写十六进制；broker 端口拒绝0、超过65535和非规范前导零。没有改变密钥保存、模型请求或 Android 启动代码。
- 使用**实际内置 Bot HTTP 服务**测试 descriptor token 鉴权、broker token 不可冒用 Bot token、重启后旧凭据拒绝，以及错误 token 不产生目录。不是只比较两个对象。
- host CI 的常规契约测试加入 `runtime-descriptor.test.mjs`。同一文件原先虽由模型检查脚本覆盖，但未纳入此 job。

### 本轮实跑证据与边界

```text
node --test dsh-go/runtime/minimax-bot-host/{agent-port,service,runtime-descriptor,workspace,provider-broker,usage-quota,schedule}.test.mjs
ℹ tests 28
ℹ suites 0
ℹ pass 28
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
```

`/tmp/mbot-actionlint -shellcheck= -pyflakes= .github/workflows/bot-host.yml` 与 `git diff --check` 退出0。Linux 上 pin 检查器原有23项也已实跑全过；Windows的21/23结果仍保留，不把平台差异隐藏为本轮失败。上述鉴权验证是**宿主测试，不是完整 Android 启动或真实 provider 证据**。本阶段未修改 native，未重新构建/运行 Android；真机、ARM64、完整 APK均**未验证**。

API另实查：held-cap run41794661470 对应 **dccb0fc3b33aa8665fdaf14776b608ea606db42b**（原表曾误写d558d1d），两个API job success；descriptor run41794831403 对应0f88f0f，两API success；host41794831396 success。这些是历史提交的CI，不能用来代替本阶段待触发的CI。

### 继续开发的准确顺序

1. **fork 真实宿主装配/可信 Bot caller**（完整构建图已通过，下一步不是重复打包）。仍用钉住的 `createWebuiRuntimeHost`，不重写17个会话端口。注意公开的 `host.application.miniAppAgent` 只暴露 run/models/reply/answer 等应用能力，**不暴露 registerArena**。钉点的 `process-local-application.test.ts` 还特意断言这一点。不能照旧第11节文字去调用不存在的方法或伪造 caller。真实注册发生在 `service/miniapp/process/host-connector/host-connector-agent.ts` 的 `createHostAgentSession().activate()`，依赖内部真实 gateway、宿主进程代数和 allowlist；release负责撤销。下一步须找到/实现同一真实gateway的可信装配接缝，复用上游生命周期，然后跑原宿主/原Bot的拒绝伪造、停用和重启测试。
2. **实际 runtime bundle / 依赖与隔离 I/O**。整树blob pin只解决「允许哪些上游字节打包」，不证明打包成功或Android能跑。fork的上游工作区解析器位于 `scripts/lib/workspace-sources-plugin.mjs`，包清单由 `release/extraction.json` 提供。当前rootfs仍只装DSH；mcode/Bot/host未装。尤其不能直接修改DSH的PRoot启动argv执行完整Agent：那仍是App同UID，违反安全边界。native model/files/SQLite能力必须接到真实运行时，再装正式独立UID启动器。
3. **native模型映射与会话生命周期**。descriptor中的loopback broker会话不等于独立UID可直连网络。既有API26/35证据已表明隔离进程不能这样使用TCP；必须沿既有JNI→Binder→Main→模型FD路径装配。Key只在native，不能将fork默认BYOK配置文件作为捷径。Bot HTTP token和native模型会话token也不能混用。
4. 原文件模块恢复（FileHandle/大日志/SQLite完整lease）、可信Bot MCP与定时任务归属、插件宿主安装政策完成后，才开放市场变更。UI/任务/插件页已有代码先复用。项目绑定/资料库/附件/群聊/审批等仍见parity，不能因为已有上游源码就勾选完成。
5. 正式APK、真实provider、ARM64/物理设备与手机/平板布局、后台/通知/profile验收。没有设备写未验证。

Mini App容器仍不做；`createWebuiRuntimeHost` 的 `miniAppSurface` 可省略，钉点已按 `OPEN_UNAVAILABLE` 处理。无需为了绕过此项伪造成功的presenter，也不新增Flutter Mini App视图。此结论取自factory选项与实际源码，不是新产品决定。

### ICU 与本轮环境

- full-ICU producer **41757171111 attempt1 已失败**：progress artifact13302870059，名为 `node-full-icu-progress-budget_exhausted-cache-success`；cache保存success，但没有完整库、verify跳过。本轮已执行 `gh run rerun 41757171111 --failed`，**attempt2 API实查in_progress**，仍不能称full-ICU成功。后续检查cache恢复与最终产物，再决定验收；未改producer的五个权威输入。
- 已恢复干净 `/tmp/mbot-mcode-reference`，HEAD97818b2890b4acae1c6bd1200a226ff506680628；不是后文所写「当前不存在」。Node24.19.0、Python3.12.14；JDK/Gradle/SDK/NDK/Flutter仍按第8节Linux路径实查。没有本地adb/KVM/物理设备。
- `/tmp/mbot-native-deps` 本轮已从Gradle缓存/官方Maven恢复6个固定jar且逐SHA256验证；旧「不存在」描述为历史。只删除本轮工具安装后可再下载的归档释放空间，保留SDK/JDK/NDK安装；磁盘仍有限，先df再下载。
- 继承GH_TOKEN无效，保存的gh登录可用：`env -u GH_TOKEN -u GITHUB_TOKEN gh ...`；不输出凭据。

## 1. 先认清产品状态

**还没有完整可日用的 MiniMax Bot Android App，不应宣称完成50%。** 没有可靠的全量百分比统计。当前最大的已验证进展是独立UID里的Node、native模型/文件broker与持久化组件；正式默认入口仍为DSH。不能把44项模拟器测试、宿主SDK测试或验收APK当成完整Bot产品已接通。

已定目标：Android本机多Bot，参考Grok布局；用户设置Key/Base URL/模型；手机固定竖屏、平板自适应并允许横屏。保留DSH Flutter外壳，runtime跟mcode-webui/minimax-code钉点，复用官方mbot业务。不做远控、手机操控、云运行环境、通用Agent工作台或Mini App容器。MiniMax Code插件市场是单独需求，变更入口仍关闭。

自动任务在**每个Bot设置内**，Bot经mbot MCP与UI操作同一数据源。进程被杀不执行/不补跑；不要照Nyral另加系统闹钟和开机重排。总工作区为应用私有`filesDir/workspace`，项目作为子目录，cwd不是授权范围。

## 2. Git 与接手方式

- 仓库：`Wzdhehe/minimax-bot-android`，私有；只续写现有`feat/t-plugins`，不另起分支。
- `main`必须仍为`e32fbd7383cd9bdc268aa7df2ec9150e8bc4e48e`。
- `dev/integration`必须仍为`891aefa71c5aea952ec8aa2502c2ada854854042`。
- 不force push、不重写已推送历史、不建PR、不发布APK/tag/release。每个可验证阶段更新文档并及时commit/push；只暂存明确路径，保护其他人的工作。
- 用户已授权持续开发和阶段推送，不要因普通可逆开发重复问确认。新的不明确产品决定不能伪装成已定需求。
- 新文件加AGPL-3.0头；vendor快照尽量不改，MIT引用保留版权和许可。

有现存checkout先检查状态，不能直接覆盖或reset。没有checkout才clone，不要从main开始重做：

```bash
git status --short --branch
env -u GH_TOKEN -u GITHUB_TOKEN git fetch origin feat/t-plugins
# 仅在已经位于feat/t-plugins、工作树干净且可快进时：
git merge --ff-only origin/feat/t-plugins
env -u GH_TOKEN -u GITHUB_TOKEN git ls-remote --heads origin main dev/integration feat/t-plugins
```

当前保存的gh CLI登录可用，但继承GH_TOKEN曾无效；`env -u GH_TOKEN -u GITHUB_TOKEN gh ...`和同样的git前缀可使用保存登录。不要输出token、环境全集或读取凭据文件。不得设置关闭TLS校验来解决网络问题。

## 3. 最近提交，哪些是产品代码、哪些只是工具

| 提交 | 内容 | 状态 |
|---|---|---|
| c1b9aee | Main显式MOVE/MOVE_REPLACE/REMOVE Binder权限与真实isolated UID矩阵 | API26/35各35项通过 |
| c8e702d | Node异步move/rename/remove→JNI→隔离Kotlin→Main | 两新PID真实文件操作/原Key和WAL断言，各35项通过 |
| 888aa77 | Main目录inode写者协调、1MiB有界整文件append、APPEND权限/Node JNI、五新增用例 | 首次API35 t40过；API26 t40 f1，失败历史保留 |
| c8e506e | Main-held子目录cap、固定openat/mkdirat、四新增用例 | 两版本各44项通过，仍无Agent目录入口 |
| 78d49f5 | transport故障夹具先消费pipe/EOF，再模拟丢回复；断言实际4次transact/2次body | 两版本各44项通过；没有放宽生产4-writer限额 |
| 3ed9daf | 用户提供的Nyral源码对照 | 文档，不是代码移植或Nyral构建成功 |
| 9d0dadd | 给M3.1 Flash的文档检查器任务卡、44项证据 | 文档 |
| 78c8293 | Flash交付检查器、38项测试、README/真实扫描报告 | 已fetch、快进接收；改动仅tooling/docs-links的4文件 |
| 860563e | 主agent验收修正、39项测试、坏链接修复、Python忽略、文档CI | 本地通过；Actions41784617417已success |

**本轮代码阶段**：289f88f为descriptor/Bot listener鉴权；5873c98为完整宿主构建图与Key测试旧schema断言修复；从7967b9d快进续写。最终文档收尾SHA以远端ref为准。更早的表格为历史，Main-held factory和descriptor设备证据见第4节及文首，后续宿主构建不证明新的Android运行能力。

## 4. 已实查的CI证据，不要重做已经通过的基线

| 阶段 | run | 设备/证据 |
|---|---|---|
| Main move/remove IPC | 41779079402，c1b9aee | API26 artifact13298915795、API35 13299245445，各t35/f0/e0/s0 |
| Node move/rename/remove | 41779493946，c8e702d | API26 13299151086、API35 13298294234，各t35/f0/e0/s0 |
| append首次 | 41780482419，888aa77 | API35 13299282270/t40-f0；API26 13298689447/t40-f1-native_data-lc89；整体failure |
| Main子目录+append | 41781117518，c8e506e | API26 13299313672、API35 13298644649，各t44/f0/e0/s0；success |
| 夹具修正后的完整回归 | 41781774323，78d49f5 | API26 13300155196、API35 13299303575，各t44/f0/e0/s0；success |
| 模型native/Android broker回归 | 41781117515 / 41781117566，c8e506e | 两run均success；不能说新增真实provider验收 |
| 文档CI | 41784617417，860563e | Documentation relative links success；这是文档检查，不构建APK |
| held-cap publisher 首轮 | 41790384428，7ebc42f | **failure**：artifact `t49-f1-e0-s0-closed_data-lh83`；新测试先关了 publisher 又用已关的 publisher，实现正确、测试时序写错 |
| held-cap publisher 修一次 | 41792577317，7348fb6 | **failure**：artifact `t49-f1-e0-s0-assertion-lh86`；第二个 publisher 对已存在文件用 publishIfAbsent，正确返回 ALREADY_EXISTS，断言写错 |
| held-cap publisher 通过 | 41794661470，dccb0fc | API26 artifact 13305711005、API35 13305117892，各 `t49-f0-e0-s0-passed-l0`；44 原有 + 5 新增全过 |
| descriptor 设备测试 | 41794831403，0f88f0f | API26 artifact 13305641051、API35 13305471825，各 19 项 0 失败；含新增 3 项 RuntimeDescriptorTest |

API26首次失败定位transport夹具的replace（lc89），只取得有限artifact/check信息，未取得完整XML堆栈。源码显示原fake立即回false/异常、不消费pipe，六次快速binary调用可先撞到4-writer的**transact前**UNAVAILABLE；修正夹具按真实wire消费声明长度/EOF并计数，不sleep/不重试。后续44项通过与首次失败都保留，不把第一次失败涂成通过，也不把推断写成已读堆栈。

此前c78faca的API35首次t0无XML失败也保留；只重跑失败job后31项通过。所有“同样有35/44项”都须核对commit/run，不能互相代替。

常用只读查询：

```bash
env -u GH_TOKEN -u GITHUB_TOKEN gh run view 41781774323 --json status,conclusion,jobs
env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/Wzdhehe/minimax-bot-android/actions/runs/41781774323/artifacts --jq '.artifacts[]|[.id,.name]|@tsv'
```

某些Azure-backed日志/XML下载在本环境403；不要把签名URL输出给用户，不为下载日志绕过网络策略。可先看jobs、check annotations和只含有限状态的artifact名称。未下载到就是未读到，不能编堆栈。

## 5. 核心代码定位与目前的契约

生产Kotlin前缀：`dsh-go/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/`。

| 路径 | 实际职责与不能误解的地方 |
|---|---|
| `model/`、`bot/BotProviderStore.kt`、`bot/NativeModelChannel.kt`、`bot/NativeModelRequestHost.kt` | Keystore/native-only provider、四协议payload/工具、SSE/取消与FD/Binder请求。Key不交Dart/Node；正式模型resolver和启动器仍未接 |
| `isolation/RuntimePrivateDirectory.kt` | Main-owned私有目录cap；openChild逐级固定openat/mkdirat，0700/NOFOLLOW/owner/worker、累计8级。child有独立FD，关parent不撤销child；File字段仅信息，不用于重新解析已移动父路径 |
| `isolation/NativeRuntimePublication.kt`及App `src/main/cpp/runtime_publication.cpp` | 固定发布/替换/普通文件删除、Main openRead、保留stage helpers和child directory；无任意flags、shell或Agent目录FD |
| `isolation/RuntimeDirectoryMutationLock.kt` | 按持有目录FD的(dev,ino)协调Main publisher，live owner引用计数。只协调参与该API的Main写者，不是排斥其他进程/旁路native代码的文件锁 |
| `isolation/RuntimeAtomicPublisher.kt` | 完整stage→fsync→原子发布/替换→目录sync；同目录move/remove；有界整文件append。`openForHeldDirectory`已从Main-held cap复制独立所有权、共享同inode gate；49项设备基线已验，不再是待开发factory |
| `isolation/RuntimeFileCommitHost.kt`、`RuntimeFileCommitClient.kt` | 真实UID+随机nonce+peer死亡绑定；单个严格ASCII basename。Main显式授予read/replace/move/remove/append；覆盖移动需move且replace。每host admission/输出、每客户端writer有限；大body和read结果走匿名pipe |
| `isolation/RuntimeFileSnapshot.kt`、`BoundedPipeReader.kt`、`BoundedPipeWriter.kt` | Main openat私有普通文件；有界读取、精确长度+EOF、poll截止/取消、未消费输出释放；不向Agent交存储文件/目录FD |
| `isolation/RuntimeDatabaseFiles.kt` | Main固定SQLite四普通文件FD（DB/WAL/SHM/journal）；close只关本地副本，不是撤销已发FD或完整数据库lease |

Node验证代码在`dsh-go/tooling/android-node-probe/`：`src/main/cpp/node_probe.cpp`是linked binding/libuv任务；`NodeProbeRuntime`实际定义在`src/main/kotlin/.../nodeprobe/NodeIsolationProbeService.kt`，不是独立NodeProbeRuntime.kt。固定脚本为`src/main/js/node-fd-probe.cjs`，Main断言为`IsolatedNodeModelTest.kt`。

测试直接编译选定生产源码；它是**独立验收工程，不是第二个产品App**。两个新PID都必须实际执行模型/文件/SQLite分支，报告字段不能缺省绕过。fake Key只在Main/native生成并注入假provider；descriptor/env/log、报告、Agent可读数据文件与会话凭据均有边界断言。没有真实MiniMax请求证据。

关键结果语义：

- `PUBLISHED`要求该次发布/目录sync返回成功；`ALREADY_EXISTS`不覆盖，不能当新文件已写入。
- native调用进入后丢结果或提交后sync失败为`OUTCOME_UNKNOWN`；不能自动重试append、换payload覆盖或声称未写入。
- 当前append是**总量1MiB、O(原文件大小)、发布新inode**，不是O_APPEND/FileHandle或无限JSONL；文件大小/pipe上限不能悄悄放大。
- 不得replace任何leased SQLite物理inode；旧读FD仍能读原inode正是已验证语义，不是“已撤销”。
- 目录始终留Main。此前尝试把目录FD经Binder传给isolated UID失败；不能回退为同UID执行来省事。

## 6. 下一项从哪里开始（按可验证小阶段推进）

本节为旧阶段顺序。held-cap factory现已落地并有49项设备证据；请先按文首最新快照继续，不要重做本节A。不要先重写Nyral/agent-table，也不要重复重建所有UI。

### A. held-cap factory已完成，不要重复实现

`RuntimePrivateDirectory.duplicate()` 与 `RuntimeAtomicPublisher.openForHeldDirectory(...)` 已在Main复制独立目录FD所有权，不按可变File路径重开；失败/close不误关借用的parent，共用同目录mutation gate、worker检查和结果语义。41794661470在API26/35各49项通过（44原有+5新增）。原本建议的factory阶段已完成。

下一层Bot/项目/记忆子scope授权及Node目录IPC仍缺：由可信Main选scope并给真实UID显式授权，Agent不能选择任意根或扩大scope。目录FD仍不外发，cwd仍不是许可。继续时保留49项设备基线，新增用例必须看新CI的commit/计数，不能借用旧run。

### B. 原模块恢复，而非仅有一组文件原语

真实调用点已列于[T-RuntimeFS](T-RuntimeFS.md)。vendor保持快照，不全局把fs.link改为rename：

- 官方`store.mjs`：state.json、messages/*.json、usage.jsonl，临时JSON→rename、串行writeChain。
- 官方`memory.mjs`：memory/bots、archive/backups、journal.jsonl、.pending.json；独占备份发布与operationId恢复不可省略。
- mcode v2 `infra/file/jsonl.ts`：FileHandle write/sync/truncate/stat、独占初始发布与恢复；当前小文件append不等价。
- mcode SQLite client：WAL、busy_timeout、online backup、只读integrity与完整lease/执行单元退出；四FD验收不是全部client已运行。

运行**经适配器的原模块恢复/冲突测试**才可说原模块适配完成。普通宿主node:fs上跑上游测试不能证明Android已接入。

### C. 之后才进入正式产品装配

正式provider/model映射与配置同步、可信Bot/MCP caller/会话归属、runtime lifecycle与Flutter transport；再验收Bot自主任务、通知、群聊/互派/记忆/附件/项目绑定。现在测试里传caller_bot_id不证明身份防伪。原schedule/UI/插件页已有实现，先复用而非重新搭页面。

插件市场变更还关闭，安装策略与Android原生依赖须由宿主强制；下载GitHub包不等于可安全安装执行。完整APK、ARM64/真机、权限/后台/手机竖屏/平板横屏、profile性能仍需要独立验收。

## 7. full-ICU首轮观察（历史；最新attempt见文首）

最早的attempt1现已budget_exhausted，cache保存成功；本轮已重跑失败job，attempt2仍compile中。具体artifact和最新状态见文首；GNU timeout仍为300分钟，不更改producer输入，也不要重复启动并行producer。cache restore step success本身不是cache命中证据。

```bash
env -u GH_TOKEN -u GITHUB_TOKEN gh run view 41757171111 --json status,conclusion,jobs
env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/Wzdhehe/minimax-bot-android/actions/runs/41757171111/artifacts --jq '.artifacts[]|[.id,.name]|@tsv'
```

先看`node-full-icu-progress-<state>-cache-<outcome>`和cache save步骤；`node-full-icu-verification-input`只有成功build/upload后才可用。首轮可能只warm cache；失败时先确认cache保存再决定重跑。现默认Node候选**没有Intl**，不能因为44项通过就说完整runtime可启动。

producer的五个输入从0bf4043后未改：source workflow、node-source-build.json、build-source.py、smoke-compiler-cache.py、test_source_recipe.py。更改它们会使旧producer不再满足复用authority，不要为省时间绕过检查。成功后按[T-AndroidNode](T-AndroidNode.md)复用到当前JNI/SQLite/Key/Intl矩阵；PINNED_SOURCE_RUN_ID还空，下载/组合路径端到端仍未验证。不要把旧41680254279失败run当成功producer。

## 8. 当前工具链与容易踩的坑

以下路径2026-11-04收尾已实查；换环境须再查。`/workspace`通常保留，`/tmp`曾被完全清空。

| 工具/数据 | 当前状态 |
|---|---|
| Python / Node | 3.12.14 / 24.19.0 |
| JDK21 | `/tmp/mbot-jdk21`，含javac，Temurin21.0.13+9；系统java只有JRE，不能替代完整JDK |
| Gradle9.3.1 | `/tmp/mbot-gradle/gradle-9.3.1/bin/gradle`；缓存用`/home/agent/.gradle` |
| Android SDK | `/tmp/mbot-android-sdk`，platform36/build-tools36/CMake3.22.1；platform-tools/adb未安装，assemble曾提示许可但成功，未伪造许可 |
| NDK27d | `/tmp/mbot-ndk27d`，SDK ndk/27.3.13750724为链接；仅恢复所需工具/头与ABI/API26库，不能说完整NDK每个API均齐全 |
| Kotlin / Flutter | `/workspace/toolchains/kotlinc/bin/kotlinc`2.4.0；`/workspace/toolchains/flutter`，测试字体`/workspace/toolchains/test-fonts` |
| Node/SQLite archive | `/tmp/mbot-node24-mobile.zip`、`/tmp/mbot-sqlite-12.11.1.tgz`当前存在，prepare按仓库pin校验 |
| Node准备输出 | probe的build/verified-jni、verified-assets及model-sdk-probe/.build仍在，均忽略/非源码；改JNI/JS才重准备 |
| 本轮恢复 | `/tmp/mbot-native-deps`（6个固定jar逐SHA256校验）、`/tmp/mbot-mcode-reference`（干净钉点checkout）存在；`/tmp/mbot-gradle-cache`仍不存在，使用`/home/agent/.gradle` |
| 参考源码 | `/tmp/mbot-nyral-reference`、`/tmp/mbot-agent-table-reference`存在，只读调研，不能当已安装可运行依赖 |
| 磁盘/设备 | 本轮实查workspace861MiB、tmp2.7GiB可用；无本地adb/KVM/物理真机，勿下载数GB模拟器到已紧张磁盘 |

JDK/Gradle按发布SHA256、SDK/NDK/CMake按Google清单摘要恢复；不要把partial目录当完整安装。proxy/CA保持环境配置。Gradle首次offline因缓存metadata不全失败，online经proxy/系统CA解析后才通过；不是源码错误。

## 9. 可直接复用的验证命令

在仓库根执行：

```bash
python3 -m unittest discover -s tooling/docs-links -p 'test_*.py' -v
python3 tooling/docs-links/check_links.py --root . README.md docs tooling/docs-links
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_*.py' -v
node --test dsh-go/tooling/android-node-probe/probe-bundle.test.mjs
(cd dsh-go && python3 -m unittest discover -s app/test_scope -v)
/tmp/mbot-actionlint -shellcheck= -pyflakes= .github/workflows/docs-links.yml
git diff --check
```

文档工具本轮最后实跑39项（0.205s）/扫描31份文档0问题（退出0），actionlint与git diff --check通过；工具实现workflow41784617417已success，新增这份交接/研究后的纯文档提交CI须按最新HEAD另查；Node准备工具此前30项、scope4、APK脚本host1已通过，本轮仅文档工具/研究没有重复跑Android。后续改动按范围选择检查，不无故重跑长编译。

最近实际Node验收构建命令：

```bash
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk \
  /tmp/mbot-gradle/gradle-9.3.1/bin/gradle --offline --no-daemon \
  -g /home/agent/.gradle -p dsh-go/tooling/android-node-probe \
  -Dorg.gradle.jvmargs=-Xmx1g assembleDebug assembleDebugAndroidTest
```

新JNI/JS须先prepare-probe，**随后prepare-sqlite**，否则asset保留SQLite placeholder/metadata重置false：

```bash
python3 dsh-go/tooling/android-node-probe/prepare-probe.py --ndk /tmp/mbot-ndk27d --archive /tmp/mbot-node24-mobile.zip
python3 dsh-go/tooling/android-node-probe/prepare-sqlite.py --ndk /tmp/mbot-ndk27d \
  --esbuild dsh-go/tooling/model-sdk-probe/node_modules/.bin/esbuild --archive /tmp/mbot-sqlite-12.11.1.tgz
python3 dsh-go/tooling/android-node-probe/check-native-files.py \
  --ndk /tmp/mbot-ndk27d --cmake /tmp/mbot-android-sdk/cmake/3.22.1/bin/cmake
```

本轮已恢复固定6个jar，以下命令实跑JUnit32/Node46通过；换环境须重验依赖：

```bash
KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc \
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps MBOT_JAVA=/tmp/mbot-jdk21/bin/java \
  bash dsh-go/tooling/test-native-model-broker.sh
WEBUI_SOURCE=/tmp/mbot-mcode-reference \
ESBUILD_MODULE=/workspace/minimax-bot-android/dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js \
  node --test dsh-go/runtime/minimax-bot-host/runtime-bundle.test.mjs
```

真实pi-ai打包仍需clean的钉点源码；本轮恢复的`/tmp/mbot-mcode-reference`可用，但不能只靠残留.build声称重新执行了SDK测试。SDK/pi-ai本轮证据来自CI，不是本地重新跑。

## 10. 本轮收尾与外部参考

Flash的检查器原交付38测试/9坏链接已复现，修复后39测试；新增读取目录失败回归、受管临时fixtures、根Python忽略与只读文档CI。原报告保留并追加主agent验收，不把“外部说过”当本地实跑。CI不发消息、不上传诊断环境或凭据、不构建Android。

用户新给的[mcode-agent-table调研](../research/mcode-agent-table.md)：对未来群聊发言权/终止条件、提议→审批→执行→核验有帮助；不替代runtime/FS/身份，也不是插件市场。不能直接搬其permission='full' SDK执行thread、Node私钥/env或普通JSONL Store。Rabbit Hole是Mini App，仍不适配。本轮只静态检查，未运行测试/CLI/真实模型。

[Nyral调研](../research/nyral.md)：Kotlin自研引擎/SQLite链更直接，可参考epoch、pending事务与轻量交互，但没有证明mcode/mbot兼容；不搬其同UID shell、宽存储、手机操控、系统闹钟。两份参考均固定commit/MIT，未复制业务代码或素材。

## 11. 设备端 runtime 接线：已查清的三处硬缺口（本轮实查，2026-11-04）

本轮按用户指示把重点从"组件验收"转到"把 Bot App 做完"。读官方 Bot 源码、钉死的 mcode fork 与 fork 的 webui 服务端后，**设备端 runtime 接线有三处硬缺口**，都还没有代码：

1. **rootfs 里没有 Bot 与 mcode。** `dsh-go/runtime/build-runtime.sh` 只打包 Ubuntu Base + Node v24.22.1 + `@deepseek-ai/dsh` 引擎 + proot（产物 `runtime/out/<abi>/assets/runtime/rootfs-<abi>.tar.xz` + `manifest.json`，`manifest.launch.argv` 才是启动命令模板）。**mcode fork、官方 Bot（`runtime/vendor/minimax-bot`）、host adapter（`runtime/minimax-bot-host`）都没有进 guest**。引擎切换 = 往 rootfs 里装这三样 + 改 `launch.argv`。
2. **descriptor 只有 Node 一半。** `runtime/minimax-bot-host/runtime-descriptor.mjs` 一直宣称"由 Android 宿主写入 0600 的 `runtime-descriptor.json`，runtime 读回"，但**Kotlin 侧此前完全没有实现**。本轮补上 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeDescriptor.kt`：写入 app 私有目录（即 guest 的 HOME，PRoot 侧 `env["HOME"]=context.filesDir`），`O_EXCL|O_NOFOLLOW` + rename，落地后校验是 owner-only 普通文件；Node 侧契约同步扩展为可选 `broker: {baseUrl, token}`（native model broker 会话，guest 发模型请求要用）。设备用例在 `app/android/app/src/androidTest/.../runtime/RuntimeDescriptorTest.kt`。**设备已验**：41794831403 API26/35 success，各 19 项、含新增 3 项 descriptor 用例、0 失败（artifact 13305641051 / 13305471825）；Node host 41794831396 success（Windows 上那 4 项失败是平台差异，见第12节）。
3. **设备侧没有任何东西启动 Bot 服务。** `startBotService`（`runtime/minimax-bot-host/service.mjs`）目前**只有宿主测试在调用**。它是官方 Bot 的 HTTP 服务入口（自带 `authorizeRequest` 要求的 `Bearer <token>` 鉴权、workspace 解析、provider broker），Dart 侧 `MiniMaxBotApi` 本来就支持自定义头，dev 入口 `lib/dev/minimax_bot_main.dart` 只是硬编码 `http://127.0.0.1:8788` 且**不带 token**，所以连真实服务都连不上。

### mbot 跑在 mcode 上的接缝（已读上游源码确认）

fork 钉在 `mcode-webui/minimax-code@97818b2`（MIT）。上游契约别名见 `runtime/minimax-bot-host/upstream-runtime-pin.mjs`：

- `@mcode-test/agent-gateway` → `packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts`
- `@mcode-test/process-local` → `packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts`

宿主适配层是 `createBotAgentPort({agent, caller})`（`runtime/minimax-bot-host/agent-port.mjs`）：它把 mcode 的 agent 契约包成官方 Bot 期待的 `run/models/reply/answer`，并**在创建时固化身份**（`pluginId`/`runId` 来自宿主注册，Bot 请求改不了）。宿主侧参考实现是 `runtime/minimax-bot-host/upstream-gateway-smoke.mjs`（真 gateway + 真 Bot 服务，模型/会话端口打桩）——**它是 host-only 契约冒烟，不是完整 runtime**。

`gateway.bind()` 要实现的端口比冒烟脚本多：`createSession / defaultSessionModel / resolveModel / listModels / listAgents / listUserSessions / readUserSession / readMiniAppSession / readSessionModel / setSessionModel / getOwner / resolveMiniAppRoot / resolveHostSession / runTurn / answerQuestion / replyPermission / subscribePermissionAsks`（签名见 `packages/local-runtime-v2/src/service/miniapp/agent/contracts.ts` 的 `MiniAppAgentSessionPorts`）。其中**模型族要接 native broker**（Main 的 `NativeModelBroker` 在 `127.0.0.1` 起 `POST /v1/model/stream` + 64hex token，guest 侧客户端是 `createModelBrokerClient`），**会话族要绑定可信 Bot 身份**，**权限族要桥到 UI 审批** —— 这三项正是交接里"正式 model resolver / 可信 Bot 身份 / 审批桥"缺口的落点。

另注：fork 的 `packages/webui/src/server` 自带 `http-server / frame-handler / access-policy / credentials`，即**本机 WebSocket + 有界消息协议**宿主，`packages/local-runtime-v2` 就跑在同一 Node 进程里。

### 架构决定（读上游源码后定，别再翻案）

**mbot 走"同进程 `createBotAgentPort`"，但宿主是 fork 自己的 runtime 宿主，不是我们自己实现那 17 个端口。** 依据：

- `packages/webui/src/runtime/assembly.ts` 的 `createWebuiRuntimeHost({ dataDir, appVersion?, miniAppSurface?, ... })` 就是 fork 的宿主工厂，它内部起 `local-runtime-v2`（`createLocalRuntimeHostV2`），并**已经实现了**会话、模型目录、Agent 列表、审批路由这些端口。fork 的数据目录默认 `~/.minimax`（`MINIMAX_DATA_DIR`），在 Android 上映射到 app 私有目录。
- fork 的插件/市场目录也在这个宿主里：`packages/shared/src/miniapp-surface.ts` 的 `MiniAppSurfaceSummary.source` 明确区分 `'official'`（市场账号装的官方包）与 `'local'`（磁盘上的包），`runtime.kind: 'process'` 是 Mini App 进程。**插件市场能用的前提就是这个宿主在跑**。
- `agent-port.mjs` 的注释定死了身份来源："The host must supply a caller resolved from its own live process registration, never from Bot requests." —— 内置 Bot 的 `pluginId`/`runId` 由宿主注册（对应 `gateway.registerArena({ pluginId, processGeneration, policy: { kind: 'allowlist', capabilities: ['session.run'], maxConcurrent } })`），Bot 请求改不了。这就是"可信 Bot 身份"的落点。
- 自己 `gateway.bind()` 那 17 个端口，等于把 fork 已有的会话/模型/审批实现重写一遍，且 Mini App 进程与市场目录落空，插件市场直接不成立。

所以设备端 guest 入口的形状是：**读 descriptor → `createWebuiRuntimeHost` 起 runtime 宿主 → 在同一进程用宿主的 agent 应用注册内置 Bot caller → `createBotAgentPort` 交给 `startBotService({ port, dataDir, filesDir, agent, modelBroker })` → Bot HTTP 服务对 Flutter 暴露（Bearer token 来自 descriptor）**。模型请求走 descriptor 里的 native broker 会话，Key 不进 guest。

本轮已核实：`miniAppSurface`可省略，上游已有OPEN_UNAVAILABLE处理，不需要新增presenter或Mini App容器。上面的入口形状仍是目标架构，**不是已可执行代码**；公开agent facade不提供registerArena，内部gateway注册和独立UID/FD模型路径须按文首缺口完成。Bot服务可传`authToken: descriptor.token`，不能把模型broker token拿来作为Bot HTTP token。

### 原卡点（已解决）：旧pin只允许契约输入

`upstream-runtime-pin-check.mjs` 第 304 行明确：**"the bundle consumed an upstream file that is not pinned" 是 error**。也就是说 bundle 里只能出现 `UPSTREAM_RUNTIME_BUNDLE_SHA256` 列出的那几个文件（旧实现的手工必需清单实际上有6个文件：`agent/gateway.ts`、`agent/contracts.ts`、`agent/model-selection.ts`、`service/miniapp/contracts.ts`、`process-local-miniapp-agent-application.ts`、`shared/miniapp-surface.ts`）。

而**插件市场与 Mini App 进程目录不在这个集合里**：它们在 `packages/webui/src/runtime/assembly.ts`、`packages/local-runtime-v2/**`、`packages/agent-*/**`。所以：

- 只按当前 pin 打包 → 只能用手工清单中的6个文件 → **我们得自己实现那 17 个端口，插件市场与 Mini App 进程不成立**。
- 要让插件市场成立 → 必须把 fork 的 runtime 打进 guest → **必须扩展 pin 的可打包文件集合**。pin 文件自己写了："Bumping the pin is a deliberate act: update the constants, update docs/UPSTREAM-RUNTIME.md, then re-run the acceptance commands."

可行的扩展方式（下一位可评估）：**用已有的整树 pin 代替手工逐文件指纹**。`UPSTREAM_RUNTIME_TREE = 5d79407ffcd54715567b49e0fac46c8ccc6e2ec8` 已经钉住整棵树；把"bundle 的每个 input 必须在该树的对应 blob 里"作为校验，就不必手工维护成百上千个文件指纹，同时仍然能证明"进 bundle 的字节就是钉住的那棵树的字节"。这属于改**验收工具**，不是放宽安全边界：checkout 仍是树级钉住，bundle 输入仍逐个按 blob 校验。

**本轮已实现这一条**（`b0d570c`）：`upstream-runtime-pin-check.mjs` 新增导出函数 `pinnedTreeFileError({ source, tree, path })`，用 `git rev-parse <tree>:<path>` 的 blob id 对比 `git hash-object` 的结果；检查器第 7 步对**不在手工指纹列表里**的上游输入改为按钉住的树逐个校验，通过即放行。性质没有放宽：进 bundle 的每个上游文件都必须与钉住那棵树的 blob 逐字节一致，commit 与 tree 仍是原值。新增 4 条用例（命中树/不在树/字节被改/无 checkout）；本地 Windows 跑 21/23，**失败的 2 条在 HEAD 原版同样失败**（已用原地 HEAD 对照验证：17/19），属平台差异。




## 12. 本轮环境（D 盘，Windows）与两处平台限制

本机是全新 Windows 主机，按用户要求把环境装在 D 盘：`D:\android-sdk`（cmdline-tools 19.0 / platform-36 / build-tools 36.0.0 / NDK 27.3.13750724 / CMake 3.22.1 / emulator 37.2.13 / system-images API26+API35 x86_64）、`D:\toolchains\flutter`（3.47.9，Dart 3.13.7，sha256 与官方 releases 一致）、`D:\toolchains\gradle-9.3.1`、`D:\android-setup\gradle-home`。为让 android-emulator 插件找到 D 盘的字节，在 `C:\Users\mjc39\AppData\Local\Android\Sdk` 下建了 `cmdline-tools/emulator/ndk/cmake/system-images/build-tools\36.0.0` 目录联接（字节仍在 D 盘）。API35 AVD 名 `mbot-api35`（API26 那台已删，老版本矩阵由 CI 覆盖）。

**平台限制一：钉死的验收脚本假设 Linux 宿主。** `prepare-probe.py` / `prepare-sqlite.py` / `check-native-files.py` / `test-fd-vfs.py` / `smoke-compiler-cache.py` 都硬编码 `ndk/toolchains/llvm/prebuilt/linux-x86_64`。Windows 上该目录不存在（本机是 `windows-x86_64`），且 NDK 的 `x86_64-linux-android26-clang++` 在 Windows 是 `.cmd`/`.exe` 包装，Python `subprocess` 直接 exec 会报 `WinError 193`。本轮用目录联接 `linux-x86_64 → windows-x86_64` 绕过前者，**没有改这些脚本**（`smoke-compiler-cache.py` 属钉死 producer 的五个输入，改了会让旧 producer 失去复用资格）；后者仍需改脚本才能本地跑，因此**本地 probe APK 未构建成功**，设备验证仍以 CI 为准。

**平台限制二：Windows 的权限模型让 descriptor 的权限断言失真。** `runtime-descriptor.test.mjs` 在 Windows 上 HEAD 原版即 3/6 失败（`chmod` 不改变 mode，所有"读回 descriptor"的用例都被 `descriptor_permissions` 分支拦下），Linux 上全绿。本轮新增 1 项后是 4/7，同因，**不是回归**。

**网络：** 代理节点时通时不通。`git push`/`git ls-remote` 稳定可用；`api.github.com` 常 TLS 握手超时，节点恢复后 CI 查询正常。**不要**用第三方 GitHub API 镜像：私有仓库的 token 会一并发给第三方。本轮收尾时远端 `git-receive-pack` 一度返回 `Internal Server Error`（读正常、`gh api user` 正常），推送失败但本地提交完好，恢复后重推即可。

### C 盘卫生（本轮按用户要求处理）

所有缓存与临时文件已落到 D 盘，并写进**用户级环境变量**（新终端也生效）：`ANDROID_AVD_HOME=D:\android-setup\avd`、`PUB_CACHE=D:\android-setup\pub-cache`、`TEMP=TMP=D:\android-setup\tmp`、`GRADLE_USER_HOME=D:\android-setup\gradle-home`。AVD `mbot-api35` 已从 `C:\Users\mjc39\.android\avd` 搬到 `D:\android-setup\avd`，C 盘上原有的 pub 缓存已删除。C 盘 SDK 路径下仍有 `cmdline-tools/emulator/ndk/cmake/system-images/build-tools\36.0.0` 这些**目录联接**（字节在 D 盘，只是入口在 C）；`platform-tools` 与 `platforms/android-36` 是用户原有的真实安装。

## 13. 产品 APK 的构建现状（本轮实跑，尚未产出 APK）

目标是把 **Bot App** 交付成可用 APK。环境已就绪（Flutter 3.47.9 / Android SDK / Gradle 9.3.1 / API35 模拟器），`dsh-go/app` 已 `flutter pub get` 成功。`flutter build apk --debug` 实跑 **13m21s 后失败**：

```text
Execution failed for task ':android_file_picker:compileDebugKotlin'.
> Could not close incremental caches in ...\android_file_picker\kotlin\compileDebugKotlin\cacheable\caches-jvm\jvm\kotlin:
  class-fq-name-to-source.tab, source-to-classes.tab, internal-name-to-source.tab
```

这是 **Windows 上 Kotlin 增量编译缓存的 `.tab` 映射文件关不掉**，不是源码错误（前面的依赖解析、插件编译、Flutter 资产打包都已过去）。下一位可直接试：`kotlin.incremental=false`（或清掉该插件的 `build/**/kotlin` 缓存目录）后重编。

另一处必须知道：app 的 Android 构建会引入 `dsh-go/runtime/out/<abi>/assets` 与 `jniLibs`（Linux rootfs + proot + Node + DSH 引擎），它们由 `runtime/build-runtime.sh` 产出，而**该脚本是 Linux 宿主专用**（bash + curl + xz + python3 + Ubuntu base 解包）。Windows 上编出来的 APK 因此**没有引擎**：能装、能启动、能看界面，但装不了 runtime。真正的"可用"还要等 rootfs 里有 mcode fork + 官方 Bot + host adapter（第 11 节那三处缺口）。


## 旧Windows接手提示（历史；当前按文首快照）

```text
接手Wzdhehe/minimax-bot-android现有feat/t-plugins，不另起分支。
先读AGENTS.md、docs/HANDOFF.md、docs/handoff/NEXT-AGENT.md与T-RuntimeFiles/T-RuntimeBroker。
产品是Bot App：mbot业务在前，mcode runtime做底座（插件市场兼容靠mcode）。
runtime接线三处缺口（NEXT-AGENT第11节）：descriptor的Kotlin半边本轮已补（设备已验）；
rootfs仍没装mcode/Bot/host、正式启动器仍缺；不能直接用同UID的DSH/PRoot运行完整Agent。
本轮5873c98已实际打包完整宿主图（2124上游输入），但仅partial build，不是Android runtime。
架构已定：guest入口用fork自己的createWebuiRuntimeHost起runtime宿主（它才有插件市场目录），
内置Bot以宿主注册的caller身份接进（身份不来自Bot请求）。
pin已扩展为按整树blob校验（b0d570c），fork runtime现在可以进bundle。
APK现状：flutter build apk --debug 在Windows上13分钟后挂在Kotlin增量缓存.tab（NEXT-AGENT第13节），
可用 kotlin.incremental=false 重试；rootfs缺失，编出来的APK还没有引擎。
目录FD留Main；不加同UID任意shell、宽存储、明文Key、MiniApp容器或远控。
阶段验证后更新交接并及时push；不动main/dev、不force、不PR、不发布APK。
当前Linux环境按文首和第8节实查；第12/13节仅为历史Windows记录。没有真机写未验证。
```
