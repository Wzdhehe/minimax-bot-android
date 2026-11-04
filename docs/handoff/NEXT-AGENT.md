<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 下一位 Agent 的详细交接（2026-11-04 UTC）

本文件是用户要求额度恢复前交接的收尾快照。配合[总入口](../HANDOFF.md)、[架构](../architecture.md)、[安全边界](../sandbox.md)、[文件能力](T-RuntimeFiles.md)和[模型 broker](T-RuntimeBroker.md)阅读。后面的新提交、远程 CI 状态仍须实查；本文件不把历史观察自动提升成当前结论。

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

**最新实际HEAD不是固定860563e**：收尾的本文件、研究文档和进度更新还会有文档提交，最终SHA以远端ref和交付回复为准。Native生产代码最近阶段是c8e506e，验收夹具最近阶段是78d49f5；之后的工具/文档提交不证明新的Android能力。

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
| `isolation/RuntimeAtomicPublisher.kt` | 完整stage→fsync→原子发布/替换→目录sync；同目录move/remove；有界整文件append。现factory仍按可信relativeSegments开目录，**还没有从已持有child cap建立publisher的正式factory** |
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

先read-only查full-ICU run，再继续文件能力。不要先重写Nyral/agent-table，也不要重复重建所有UI。

### A. Main持有的child cap接到真实文件能力

建议先完成“publisher可从Main-held目录cap建立独立所有权”的最小阶段，再扩展子scope broker。这是**建议的下一步，不是已经存在的API或冻结协议**。

现有缺口：openChild能安全打开子目录，但生产publisher仍只接受context+relativeSegments；若按File路径重开会丢掉held-cap对父路径替换的防护。新factory须明确是复制还是转移cap所有权，失败/close不误关借用的parent，沿用同目录mutation gate、worker检查和结果语义。

之后由可信Main选Bot/项目/消息/记忆子scope，并为真实UID显式授权操作；Agent不能选择任意根、扩大scope或把cwd当许可。目录FD不外发。需覆盖权限组合、越界/symlink/父路径替换、并发、关闭/drain、unknown与Key边界，并保留现有44项；新增测试必须核对新CI的commit和计数。

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

## 7. full-ICU正在进行，不要中断或误报成功

截至本轮收尾实查：**0bf4043/run41757171111**仍in_progress；compile step从2026-11-04 01:55:50 UTC开始。预检/cache-restore step为success，但不证明cache命中或保存。GNU timeout编译预算300分钟，外层step330/job360分钟；约06:55 UTC后可能进入超时收尾，实际以Actions为准。

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
| **不存在** | `/tmp/mbot-native-deps`、`/tmp/mbot-gradle-cache`、`/tmp/mbot-mcode-reference`；旧文档说恢复过不代表现在仍有 |
| 参考源码 | `/tmp/mbot-nyral-reference`、`/tmp/mbot-agent-table-reference`存在，只读调研，不能当已安装可运行依赖 |
| 磁盘/设备 | 收尾约workspace861MiB、tmp1.9GiB可用；无本地adb/KVM/物理真机，勿下载数GB模拟器到已紧张磁盘 |

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

若需重跑native宿主JUnit32/Node44，先按`.github/workflows/native-model-broker.yml`取齐并校验固定6个jar，再设MBOT_NATIVE_DEPS运行test-native-model-broker.sh；当前旧/tmp jar目录不存在。真实pi-ai打包需要clean的97818b2890b4acae1c6bd1200a226ff506680628源码；旧/tmp checkout不存在，不能只依赖残留.build声称重新验证。

## 10. 本轮收尾与外部参考

Flash的检查器原交付38测试/9坏链接已复现，修复后39测试；新增读取目录失败回归、受管临时fixtures、根Python忽略与只读文档CI。原报告保留并追加主agent验收，不把“外部说过”当本地实跑。CI不发消息、不上传诊断环境或凭据、不构建Android。

用户新给的[mcode-agent-table调研](../research/mcode-agent-table.md)：对未来群聊发言权/终止条件、提议→审批→执行→核验有帮助；不替代runtime/FS/身份，也不是插件市场。不能直接搬其permission='full' SDK执行thread、Node私钥/env或普通JSONL Store。Rabbit Hole是Mini App，仍不适配。本轮只静态检查，未运行测试/CLI/真实模型。

[Nyral调研](../research/nyral.md)：Kotlin自研引擎/SQLite链更直接，可参考epoch、pending事务与轻量交互，但没有证明mcode/mbot兼容；不搬其同UID shell、宽存储、手机操控、系统闹钟。两份参考均固定commit/MIT，未复制业务代码或素材。

## 11. 设备端 runtime 接线：已查清的三处硬缺口（本轮实查，2026-11-04）

本轮按用户指示把重点从"组件验收"转到"把 Bot App 做完"。读官方 Bot 源码、钉死的 mcode fork 与 fork 的 webui 服务端后，**设备端 runtime 接线有三处硬缺口**，都还没有代码：

1. **rootfs 里没有 Bot 与 mcode。** `dsh-go/runtime/build-runtime.sh` 只打包 Ubuntu Base + Node v24.22.1 + `@deepseek-ai/dsh` 引擎 + proot（产物 `runtime/out/<abi>/assets/runtime/rootfs-<abi>.tar.xz` + `manifest.json`，`manifest.launch.argv` 才是启动命令模板）。**mcode fork、官方 Bot（`runtime/vendor/minimax-bot`）、host adapter（`runtime/minimax-bot-host`）都没有进 guest**。引擎切换 = 往 rootfs 里装这三样 + 改 `launch.argv`。
2. **descriptor 只有 Node 一半。** `runtime/minimax-bot-host/runtime-descriptor.mjs` 一直宣称"由 Android 宿主写入 0600 的 `runtime-descriptor.json`，runtime 读回"，但**Kotlin 侧此前完全没有实现**。本轮补上 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeDescriptor.kt`：写入 app 私有目录（即 guest 的 HOME，PRoot 侧 `env["HOME"]=context.filesDir`），`O_EXCL|O_NOFOLLOW` + rename，落地后校验是 owner-only 普通文件；Node 侧契约同步扩展为可选 `broker: {baseUrl, token}`（native model broker 会话，guest 发模型请求要用）。设备用例在 `app/android/app/src/androidTest/.../runtime/RuntimeDescriptorTest.kt`。
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

尚未定的一处：宿主需要的 Mini App presenter（`miniAppSurface` 选项在 upstream 是 host-owned port，浏览器实现是 `server/miniapp/presenter.ts`）。手机端没有浏览器视图，要决定是给个 no-op presenter（`open` 动作报 `OPEN_UNAVAILABLE`）还是接 Flutter 侧视图。

### 卡点：当前 pin 不允许把 fork 的 runtime 打进 bundle

`upstream-runtime-pin-check.mjs` 第 304 行明确：**"the bundle consumed an upstream file that is not pinned" 是 error**。也就是说 bundle 里只能出现 `UPSTREAM_RUNTIME_BUNDLE_SHA256` 列出的那几个文件（目前就是 4 个契约文件：`agent/gateway.ts`、`agent/contracts.ts`、`agent/model-selection.ts`、`process-local-miniapp-agent-application.ts`、`shared/miniapp-surface.ts`）。

而**插件市场与 Mini App 进程目录不在这个集合里**：它们在 `packages/webui/src/runtime/assembly.ts`、`packages/local-runtime-v2/**`、`packages/agent-*/**`。所以：

- 只按当前 pin 打包 → 只能用那 4 个契约文件 → **我们得自己实现那 17 个端口，插件市场与 Mini App 进程不成立**。
- 要让插件市场成立 → 必须把 fork 的 runtime 打进 guest → **必须扩展 pin 的可打包文件集合**。pin 文件自己写了："Bumping the pin is a deliberate act: update the constants, update docs/UPSTREAM-RUNTIME.md, then re-run the acceptance commands."

可行的扩展方式（下一位可评估）：**用已有的整树 pin 代替手工逐文件指纹**。`UPSTREAM_RUNTIME_TREE = 5d79407ffcd54715567b49e0fac46c8ccc6e2ec8` 已经钉住整棵树；把"bundle 的每个 input 必须在该树的对应 blob 里"作为校验，就不必手工维护成百上千个文件指纹，同时仍然能证明"进 bundle 的字节就是钉住的那棵树的字节"。这属于改**验收工具**，不是放宽安全边界：checkout 仍是树级钉住，bundle 输入仍逐个按 blob 校验。



## 12. 本轮环境（D 盘，Windows）与两处平台限制

本机是全新 Windows 主机，按用户要求把环境装在 D 盘：`D:\android-sdk`（cmdline-tools 19.0 / platform-36 / build-tools 36.0.0 / NDK 27.3.13750724 / CMake 3.22.1 / emulator 37.2.13 / system-images API26+API35 x86_64）、`D:\toolchains\flutter`（3.47.9，Dart 3.13.7，sha256 与官方 releases 一致）、`D:\toolchains\gradle-9.3.1`、`D:\android-setup\gradle-home`。为让 android-emulator 插件找到 D 盘的字节，在 `C:\Users\mjc39\AppData\Local\Android\Sdk` 下建了 `cmdline-tools/emulator/ndk/cmake/system-images/build-tools\36.0.0` 目录联接（字节仍在 D 盘）。API35 AVD 名 `mbot-api35`（API26 那台已删，老版本矩阵由 CI 覆盖）。

**平台限制一：钉死的验收脚本假设 Linux 宿主。** `prepare-probe.py` / `prepare-sqlite.py` / `check-native-files.py` / `test-fd-vfs.py` / `smoke-compiler-cache.py` 都硬编码 `ndk/toolchains/llvm/prebuilt/linux-x86_64`。Windows 上该目录不存在（本机是 `windows-x86_64`），且 NDK 的 `x86_64-linux-android26-clang++` 在 Windows 是 `.cmd`/`.exe` 包装，Python `subprocess` 直接 exec 会报 `WinError 193`。本轮用目录联接 `linux-x86_64 → windows-x86_64` 绕过前者，**没有改这些脚本**（`smoke-compiler-cache.py` 属钉死 producer 的五个输入，改了会让旧 producer 失去复用资格）；后者仍需改脚本才能本地跑，因此**本地 probe APK 未构建成功**，设备验证仍以 CI 为准。

**平台限制二：Windows 的权限模型让 descriptor 的权限断言失真。** `runtime-descriptor.test.mjs` 在 Windows 上 HEAD 原版即 3/6 失败（`chmod` 不改变 mode，所有"读回 descriptor"的用例都被 `descriptor_permissions` 分支拦下），Linux 上全绿。本轮新增 1 项后是 4/7，同因，**不是回归**。

**网络：** 代理节点时通时不通。`git push`/`git ls-remote` 稳定可用；`api.github.com` 常 TLS 握手超时，节点恢复后 CI 查询正常。**不要**用第三方 GitHub API 镜像：私有仓库的 token 会一并发给第三方。

## 可直接给下一位的简短提示

```text
接手Wzdhehe/minimax-bot-android现有feat/t-plugins，不另起分支。
先读AGENTS.md、docs/HANDOFF.md、docs/handoff/NEXT-AGENT.md与T-RuntimeFiles/T-RuntimeBroker。
产品是Bot App：mbot业务在前，mcode runtime做底座（插件市场兼容靠mcode）。
runtime接线有三处硬缺口（NEXT-AGENT第11节）：rootfs没装mcode/Bot/host、descriptor的Kotlin半边本轮已补、设备侧无人启动Bot服务。
下一步先定"mbot同进程createBotAgentPort"还是"走webui本机WebSocket当MiniApp"，再写guest入口。
现Native/Node文件组件在78d49f5的API26/35各44项通过；正式App仍DSH引擎，完整Bot/runtime未接。
目录FD留Main；不加同UID任意shell、宽存储、明文Key、MiniApp容器或远控。
阶段验证后更新交接并及时push；不动main/dev、不force、不PR、不发布APK。
工具链按本文件实查（D盘Windows环境+两处平台限制）；没有真机的内容写未验证，不编造。
```
