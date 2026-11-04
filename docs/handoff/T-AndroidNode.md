<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Android Node 加载关口（2026-11-03，正在开发）

> 环境恢复后的新run实查：修正后的full-ICU producer **0bf4043/run41757171111** 已创建build job，toolchain/preflight/cache-restore step均success，当前compile仍in_progress；这不证明cache命中、cache已保存或完整库成功。尚无producer artifact/ICU设备结果，默认候选仍无Intl。下文“无job”是首次ec2f1b2记录，不能代表这个新run。

> 2026-11-04最新：**46c1c07/run41703671145的四普通文件FD候选已API26/35通过**，每台t1/f0/e0/s0，含两次不同PID、WAL/Key/模型。full-ICU重跑41680254279在compile330分钟超时失败、verify skipped，无产物；不能继续写运行中。详见T-Persistence及HANDOFF。

## 缓存workflow配置修复

ec2f1b2/run41756717823在创建job前失败，`gh run view --verbose`提示workflow file issue。临时工具actionlint1.7.12（release SHA256 `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8` 验证）本地明确复现：job级env不允许runner上下文。将CCACHE_DIR的runner.temp移到compile step的env，restore/save依然使用同一runner.temp路径；其他job级env仅github/常量。修后source/probe两个workflow的actionlint表达式检查通过（外部shellcheck/pyflakes未启用，bash -n另过），Python21项过。

**这一轮未编译、未保存cache，更不能当full-ICU成功**。新push后先确认job实际创建与cache/timeout预检步骤通过，再观察长编译。工具只在/tmp、不提交；下次CI配置变更宜先跑actionlint再push，不能仅凭YAML解析断言GitHub上下文合法。

## 2026-11-04：源码编译缓存与预算（CI待实跑）

41680254279的check明确 `Compile pinned x86_64 Node with full ICU has timed out after 330 minutes`；build开始17:02、compile17:04到22:34，verify skipped，无归档。不能只再次增加超时。实际读取并核对摘要的上游scripts/build.py，以及同commit的prepare.py：prepare每次拒绝已有source目录，因此直接缓存out目录不构成受支持的续编。

现在在忽略目录生成的recipe除full-icu外，只将四类host/target CC/CXX命令用shlex.join包装ccache；不改编译flags、源树或Ninja并发2。source pin新增compilerCache字段，旧未声明归档拒绝。CI安装ccache、content compiler check、独立3G cache；按OS/arch/source pin隔离namespace，每次run/attempt保存新key并restore最近同namespace。只缓存编译结果，不缓存可执行源码树或把残余out当可信源。

GNU timeout在300分钟对build process group发TERM、30秒后可KILL，给330分钟step/360分钟job留保存时间；退出124标budget_exhausted，137仅标terminated（不能断言是超时或OOM）。即使编译失败/超时，也always保存已完成cache并上传有限progress状态；cache保存/诊断服务失败不阻止已成功产物上传，但必须查看实际cache save outcome。source产物仍只在真正成功时上传，verify不能将预算退出当成功。

本地真实smoke已执行：临时下载ccache4.14.2 musl-static，SHA256 `b20ff6415151226ac5a10dfd6839700c694bd67d1871c8fddb266d366ff25bfb` 与release API digest一致；工具只在/tmp，不提交、不进入APK。宿主C++与实际NDK27d/API26/x86_64各编译两次，hits2/misses2，缓存object逐字节匹配。固定父/子构建fixture验证GNU timeout返回124，整个子进程组停止。实际输出：

```text
PASS: real host + NDK API26/x86_64 compiler cache hits=2 misses=2; objects match
PASS: GNU timeout returns 124 and stops the fixture build process group including its child
```

复现脚本 `smoke-compiler-cache.py --ndk /path/to/ndk27d --ccache /path/to/ccache`；CI还在长编译前跑这个smoke与recipe2项检查。完整Python检查**21项通过**；接受精确pin的上游build.py并验证生成recipe可解析；workflow YAML/bash语法过。**GitHub cache实际保存/恢复、完整Node编译及ICU/SQLite组合设备未验证，不能根据这个小smoke宣称已解决330分钟问题。** 首次可能只完成warm cache，读source progress artifact name和cache save outcome后再决定重跑。

source artifact authority现在逐字节校验producer workflow、source pin、build-source.py及预检helper/test_source_recipe.py；这五个都是producer执行输入。后续更改它们会拒绝旧producer，不要改校验绕过。其他Android SQLite代码改动不改变这组producer inputs。

## full-ICU产物复用入口（待端到端验收）

Node源码构建耗时很长，候选验收workflow现在支持复用已成功的同仓库source producer，组合**当前**JNI/SQLite/VFS/模型/Key/Intl测试，而不重跑Node编译。`android-node-probe.yml` 的 `PINNED_SOURCE_RUN_ID` 当前为空，默认仍用固定release；待41680254279的build job真正success后，可只改该值并push到feat/t-plugins触发当前验收。若GitHub已登记workflow_dispatch，也可提供 `source_run_id`；**没有实际运行dispatch，不保证分支workflow可被dispatch，不为此移动main**。

复用前 `verify-source-run.py` 必须通过：run ID仅整数、同repo/同feat/t-plugins、producer workflow路径/事件/commit正确、build job（含上传）success，且producer commit的源码pin、build-source.py、source workflow、预检helper和recipe测试与当前逐字节一致。随后download-artifact按固定name取私有产物；归档SHA交prepare-probe继续校验source manifest/config/ABI/ICU及recipe。没有任意artifact URL或静默回退。仅申请Actions只读权限，不触碰release/APK发布。

17项Python检查通过（新增5项覆盖source authority/非法ID不触网/fork等拒绝/未完成producer拒绝/recipe或workflow变更拒绝）。**source产物下载/组合设备验收仍未验证**，producer41680254279已在330分钟编译超时失败，无产物；过期/缺失artifact应失败，不能假造SHA。原source job的旧verify仅验它当时commit的固定脚本，不能代替当前SQLite/WAL组合测试。

## 历史：五FD VFS候选（IPC失败，四FD候选见顶部）

Main给临时非敏感数据文件/目录的五个FD；JNI传到Node，SQLite addon注册受控VFS，先关输入别名再开文件DB。固定脚本增加WAL、并行只读连接、写入、关闭重开和完整性；Main检查实际文件与无Key。最后APK3m42s成功，JNI/SQLite重编及host1/input8通过；**执行待CI，不是正式持久化/跨进程重启已完成**。原模型链路用例保留。详见T-Persistence。

## SDK route设备证据与持久化原型

e03b997/run41686820725的API26/35均success，包含SDK route→JNI→Main broker的tool-shaped fetch及原大请求/SQLite/Key用例。日志未实读，不伪造计数。最新FD VFS仅宿主原型/NDK object编译，**未接Android**，见T-Persistence；full-ICU重跑仍待结论。

## 当前：SDK route → JNI（设备待CI）

新增纯JS native-model-route，绑定SDK虚拟URL/placeholder/provider/model，将模型JSON和request ID交linked binding，取消关联同ID并保留晚到FD关闭。实际SDK9/pi-ai8、完整JUnit32+Node44通过；验收APK固定脚本新增tool-shaped SDK fetch，JNI→Main broker后恢复SSE工具事件/DONE，Node两个APK43s成功、host1/input8过。设备待新CI，不代表正式model resolver已接。

3d99bed的1MiB pipe在API26/35的Node41682351200与broker41682351421均success；数据文件FD能力d7d65ad/run41683691783也success。旧待CI段落仅作当轮记录。

## 当前补强：1MiB请求pipe（设备待CI）

Native client/host增加有界请求pipe，JNI允许1MiB；固定Node脚本实际发送84KB请求，Main transport逐字校验，native Key注入总计5次（3个prefd + 2个动态请求）。最后JNI/SQLite重编成功，Node两个APK **BUILD SUCCESSFUL in 2m39s**；host1+input8过。相关协议/取消/截止见T-RuntimeBroker当前节。本阶段设备CI待观察，不使用4f92405的64KiB基线代替。

full-ICU重跑run **41680254279** 正在build，源recipe不变；首轮41630373256已超时取消，以下是当轮记录。

## full-ICU首轮超时，延长后重跑

run41630373256 / build125898095957已cancelled，verify skipped。实读check-run annotation：`The job has exceeded the maximum execution time of 4h0m0s`。job11:47:41开始、15:49:11结束，未生成可用archive。gh run --log本次返回空，**没有声称读到编译器失败或编译进度**。现在只将job上限改360分钟、编译step330分钟，留诊断/收尾余量；recipe/钉点/ICU断言不变，须观察新run。不是full-ICU已构建通过。

Node动态请求基线则已通过：4f92405/run41667564473 success，API26/35均成功；覆盖动态Promise/JNI/Binder/模型FD、取消在先以及SQLite/Key断言。此次日志读取同样为空，结论来自run/job API，不编造XML计数或新日志。d545dc7/run41665743434的Binder API26/35也已success。

## 当前：Node Promise → JNI → Binder动态申请（设备待CI）

验收JNI新增linked binding `mbot_model_bridge`，open返回Promise，最多四个libuv worker请求，64KiB body/128字ID上限。worker附加JavaVM并调用隔离侧ModelRequestClient；native主进程按观测UID/单provider/model/会话再授权，返回owned Unix FD，Node现有reader消费。cancel调用有界Binder控制协议，取消早于open被拒；JNI不把Java异常/平台文本发给JS，只用broker_unavailable。Java会话capability留在隔离Kotlin侧，Node只拿providerId和结果FD。

固定APK脚本保留原prefd/取消/Key回显用例，再实际调用open Promise和取消在先。Main先用私有IDENTITY握手观察UID，创建NativeModelRequestHost并绑定peer死亡；报告增加dynamic流，仪器测试断言4次native Key注入、Key/native token/RPC session不在Node报告里。SQLite内存断言仍保留。仍是独立验收APK，不是正式启动器/完整SDK/runtime装配。

实际准备：NDK27d重编JNI和固定SQLiteaddon成功。最初C++结果局部变量与FD参数同名导致编译拒绝，改局部名后成功，没有放宽检查。

```text
BUILD SUCCESSFUL in 1m 46s
69 actionable tasks: 20 executed, 49 up-to-date
```

host固定脚本1 passed（不执行JNI动态分支）/输入拒绝8 passed；新的Android动态Node执行**待API26/35 CI**。64KiB以上request-FD协议、正式model resolver/scope与生命周期仍待做。

SQLite基线已有实际设备证据：bc07b1c/run41663798958 success，API26/35均1 test/0 failures/errors/skipped，且两job的固定SQLite源码编译步骤成功；这是隔离Node内存数据库断言，不是WAL/持久化。full-ICU run41630373256截至推送仍build，尚未通过。

**真实执行已通过**：`04d7e97` 的run **41627233229 success**，API26 job125887515761和API35 job125887516140均通过仪器用例。真实隔离进程加载Node24.22.2，版本/UID、私有文件拒绝、Keystore→native broker→Unix FD→Node SSE、取消、Key回显拒绝及descriptor/env/log不含Key检查均已执行。仍是固定脚本/假provider；未启动完整mcode/官方Bot，ICU、better-sqlite3、持久化、物理真机和ARM64待做。后文“设备待CI”是本次实现前的记录，以此覆盖。

当前默认产品仍为 DSH，尚未在 Android 启动 mcode。上一关口：`a094a85` 的 API26/35模拟器各十项通过（run41622547971），native Key/Unix模型FD/IPC已取得组件证据。物理真机、better-sqlite3和完整runtime仍未验证。

## SQLite候选（新增，设备执行待CI）

NDK27d已将better-sqlite3 12.11.1源码编译为Android x86_64 addon，APK固定脚本新增内存数据库验证；最后两个APK构建43s成功、host1+输入拒绝8项过。**Android SQLite执行仍待本阶段CI**，不是持久化/WAL已解决。详情见 [T-SQLite](T-SQLite.md)。旧段“better-sqlite3未做”只描述当时。

## 当前继续：full ICU 源码构建与验证（尚未完成）

新增 `node-source-build.json` / `build-source.py` / `android-node-source.yml`。固定mobile提交、官方Node提交与tree、重构mobile tree、构建脚本SHA256、NDK27d、API26/x86_64；在忽略目录复制原脚本，仅把Android的`--with-intl=none`改为full-icu，保留原仓库与子模块洁净。单ABI两路编译，CI允许较长构建；产物只作七天private工作流输入，不发布release/APK。下游通过build job的archive SHA再次验证，校验来源/ABI/config，才编译JNI并在API26/35跑中文Intl/Asia-Shanghai时区与原Key边界用例。

本地实跑：真实固定recipe摘要匹配，确认只替换Android Intl选项；包摘要拒绝、错误recipe拒绝、Zip逃逸拒绝、无ICU包拒绝 **4 passed**；精确打包脚本host **1 passed**。默认候选路径重准备/编译成功，Gradle **BUILD SUCCESSFUL in 1m 1s**。这些不是full ICU构建成功；本地主机没有host clang/ninja与足够完整构建空间，完整Node源码编译交给独立CI，结果待观察。

注意原先tree表述已修正：官方f7f273a的Git tree是5f62a9f；release `node-source.json.tree` 的be3625a是应用mobile patches后的树。源码构建分别核对两者，不能混用。

## 已核对的 Node24 候选

- `RockinChaos/nodejs-mobile@223eec9554b5d344b86d1b411f0255df8cc47f27`，release `v24.22.2`。
- 官方Node源钉点 `f7f273a9bb6445f6f7fb6349105e7532972045fb`，官方Git tree `5f62a9f6a645a5391090ed384056e4e06b80c73c`；mobile patches重构后的tree为 `be3625af068f11bf8288ecf450a36d990cb2294c`（release build-info的tree字段）；fork按 `patches/series` 重构，不替换mcode钉点。
- 下载包 `nodejs-mobile-v24.22.2-android.zip`，SHA256 **cde938a9e45ebd3f53d11fbb5f3875df92b4069431677dd5827b68acfaa01847** 已在本地核对。
- 包内x86_64 `libnode.so` SHA256 **c1c48bf4d6aa2a327986f83a84cc8a7fbf9d401c718081f5eb266e600bbf4dec**；同时有arm64/arm库，但未验证手机。
- ELF实查SONAME libnode.so；依赖libm/libdl/liblog/libc/libc++_shared；LOAD段16KiB对齐；导出`node::Start`。这些是静态检查，不等于运行或真实16KiB页验证。
- fork的构建workflow使用NDK r27d；脚本支持AndroidAPI>=24，`--shared --openssl-no-asm --with-intl=none`。**没有Intl**，而官方mbot `i18n.mjs` 顶层直接调用Intl.DateTimeFormat，不能据此把候选直接接进正式Bot。需要后续重建带ICU的版本或明确兼容层并验证。
- release ZIP含上游完整LICENSE及LICENSE-nodejs-mobile，核心MIT；不得遗漏捆绑第三方许可。二进制只放忽略的build/临时目录，不提交APK/库/签名文件。

源码与构建引用：

- [源码记录](https://github.com/RockinChaos/nodejs-mobile/blob/223eec9554b5d344b86d1b411f0255df8cc47f27/node-source.json)
- [构建脚本](https://github.com/RockinChaos/nodejs-mobile/blob/223eec9554b5d344b86d1b411f0255df8cc47f27/scripts/build.py)
- [workflow](https://github.com/RockinChaos/nodejs-mobile/blob/223eec9554b5d344b86d1b411f0255df8cc47f27/.github/workflows/build-mobile.yml)
- [release](https://github.com/RockinChaos/nodejs-mobile/releases/tag/v24.22.2)

## 本阶段已实现（设备执行待CI）

新增 `dsh-go/tooling/android-node-probe` 与独立 `android-node-probe.yml`。不是产品启动器。固定版本/摘要在验收工程 `node-runtime-pin.json`；有界提取源码header/指定ABI/完整许可，NDK编译JNI，Gradle缺少库/许可时fail closed。固定脚本由生产FD/SSE模块生成data URL，不在隔离端依赖App私有JS文件。private isolated服务校验owner UID，一进程一次node::Start，不接受外部JS/命令。

真实仪器用例已写：主进程Keystore保存随机假Key，经真实native broker给Node三个模型FD（正常、停滞、回显Key），独立结果FD回传版本/UID/文件拒绝/环境与事件。回传不进日志；测试不在断言信息中打印Key、env或正文。没有真实账号调用。

### 本地实际验证

- 从Google repository XML确认NDK r27d为27.3.13750724，下载ZIP SHA1 `22105e410cf29afcf163760cc95522b9fb981121`吻合。选择提取clang/x86_64 headers/libs到 `/tmp/mbot-ndk27d`，不是完整多ABI NDK。首次裁剪漏libunwind.a，补全x86_64 runtime后编译通过；没有改源代码去绕链接。
- `python3 .../prepare-probe.py --ndk /tmp/mbot-ndk27d --archive /tmp/mbot-node24-mobile.zip`：`Prepared pinned Node 24.22.2 x86_64 and JNI verification glue`。JNI_OnLoad导出、依赖与16KiB ELF LOAD对齐已静态复核。
- 精确打包脚本宿主测试 **1 passed**（真实继承Unix FD、流/取消/错误、无stdout/环境凭据传递）。首次失败为父net.Socket预读导致FD中丢响应字节；改测试在继承后才释放替身响应。真实native PFD没有用户态预读，未因此修改生产逻辑。
- Node验收工程 `assembleDebug assembleDebugAndroidTest`：**BUILD SUCCESSFUL in 1m 34s**，69 tasks executed，两个APK编译打包。首次在JNI尚未准备好时的Gradle失败由输入守卫正常拒绝，未视为代码通过。
- 无本地KVM/adb，没有本地运行设备。新API26/35 workflow待观察，**Android Node仍未验证**；上述均不是full Flutter Bot APK，不证明Intl、better-sqlite3或持久化。

### 验收流程（设备项仍待完成）

1. 新建独立、无launcher的 `tooling/android-node-probe` 验收工程，固定版本/摘要，包内源/header和license有界提取；用NDK r27d编译小JNI胶水，仅x86_64模拟器。复用生产model/Keystore/NativeModelChannels源码，不维护第二套实现。
2. 仅测试APK中的private isolatedProcess服务加载libnode/JNI，owner UID校验，固定打包脚本，一进程只初始化一次。没有主UID任意JS/shell入口，不切换产品启动器。
3. 固定Node脚本消费生产Unix模型FD（真实 `streamNativeModelFd` 模块），分别验证正常SSE、停滞取消、假Key回显拒绝；结果经单独受控结果FD回主进程，只日志测试名/计数。假Key在主进程Keystore生成/保存，不传Node、环境、参数或Agent文件；不用真实账号。
4. 确认实际Node版本/UID/文件拒绝，记录Intl、SQLite原生依赖和数据目录能力。JNI加载不证明better-sqlite3、WAL或完整mcode。
5. API26/35模拟器执行、编译/宿主检查通过后阶段push和更新本卡/HANDOFF；未验证项照录。暂不增加全量Flutter/NDK产品构建承诺。

## 后续仍独立的关口

isolated UID没有主App数据路径权限；目录FD/cwd不自动授予SQLite/WAL/JSON创建权限。必须继续设计验证持久化，不能回退main UID执行Agent。

已从钉点mcode实查 `packages/local-runtime-v2/src/service/model-system/resolution/local-model-resolver.ts` 的 `resolveModelFetch`：BYOK支持 `options.fetchImpl`，返回resolved model的fetch字段。可作为以后受控FD fetch adapter入口；native协议现增加provider-shaped request，保留工具消息/选项；SDK fetch adapter/三协议真实SDK宿主组件已过（T-RuntimeBroker），动态Binder/JNI桥与正式装配仍待做。钉点google.ts不使用options.fetch；新增AsyncLocalStorage dispatcher已在宿主四类真实pi-ai工具/取消测试通过，但生产隔离启动器还须显式安装/装配，不可只塞options.fetch。不能只改Base URL或塞dummy Key就宣称mcode已打通。
