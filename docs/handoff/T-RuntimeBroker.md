# T-RuntimeBroker（HANDOFF 第 2 项，部分完成，2026-11-04）

## 最新：native 侧待接线的目录契约（2026-11-04）

runtime 侧已完成「native 非密钥目录 → 钉点官方 provider API」的投影与每轮绑定（见 [T-RuntimeHost](T-RuntimeHost.md)）。**native 侧尚未接线**，下一阶段按下述契约补齐，不要另发明协议：

**1. descriptor 要带 `catalog`。** 现有 Kotlin `RuntimeDescriptorSession` / `RuntimeDescriptor.encode()` 只写 `entryPath/pinCommit/port/token/broker`。需新增一个**非密钥目录**字段并原样写进 `<dataDir>/runtime-descriptor.json`；Node 侧 `createRuntimeDescriptor` 已接受 `catalog` 并在写盘前校验（凭据类字段 → `descriptor_catalog_contains_credential`，端点类字段 → `descriptor_catalog_forbidden_field`）。

**2. 目录必须先剥掉端点与 Key。** `BotProviderStore.catalog()` 当前返回的每项含 `baseUrl` 与 `maskedApiKey`，**这两个字段都不允许进 descriptor**：`baseUrl` 会把真实供应商端点带进 guest，`maskedApiKey` 属于 Key 类字段。native 侧需要单独产出一份「guest 目录」，字段只允许：

```text
providers[]: { providerId, name, apiFormat, enabled, models[] }
defaultProviderId, defaultModelId
```

`apiFormat` 只允许四取值 `openai-completions / openai-responses / anthropic-messages / google-generative-ai`（与钉点 `isModelProviderApi` 白名单同名）；`hasApiKey` 若要带，只允许布尔值。

**3. 真实端点与 Key 仍只在 native。** native broker 按 `providerId` + `modelId` 解析真实端点与 Key；guest 侧只有虚拟 origin `https://models.mbot.invalid` 与占位串 `native-broker-placeholder`。

**4. native broker binding 要交给 guest runtime 进程。** 每轮绑定需要 guest 侧的 `{open(id,payload), cancel(id)}` binding（既有 `createNativeModelRoute` 的入参）。在这条链路接上之前，`createBuiltinBotRuntime` 的 `nativeBinding` 为 `null`，此时目录照常投影、默认选择照常生效，但**官方连通性测试不会跑**（`connectivity` 记为 `not_run`），模型请求也没有路由可用。

**5. 切模型走 session 选择。** 切模型写 fork 的 session 模型选择（官方 `models.select` 带 `sessionId`），在飞轮继续用旧模型、下一轮生效；本阶段已实现 `setSessionModel`，并对不在 native 目录内的目标明确拒绝（`model_not_available`）。

## 最新安全复核：Node descriptor与环境交接（2026-11-04）

既有实现有四个可复现缺口：写穿叶软链接、经父目录软链读/写workspace/private根外、writer把额外字段（如apiKey）原样JSON序列化、extra环境绕过allowlist。分别取得修复前失败；现改为私有stage/O_EXCL/O_NOFOLLOW/fsync+rename、真实父路径政策校验、同一FD fstat/owner/权限与读取、固定字段投影，extra与baseEnv用同一白名单。回归不依赖真实Key，不修改vendor快照或正式Android launcher。

这仍是可信宿主私有目录的Node helper，**不是openat能力或并发同UID攻击防护**。Kotlin路径型writer本轮未改；正式Main-held目录、独立UID与模型/文件FD启动链仍须装配。本地最新宿主32项、JUnit32项、native/Node50项全通过且0 skipped（其中descriptor12项），文档31份0问题。24bdeca新CI已实查host41814570291、native41814570242、docs41814570339、scope41814570376均success；host日志32项/0 skipped，native的boundary、SDK/pi-ai与Android组件job均过。本阶段没有新设备仪器运行，详见[NEXT-AGENT](NEXT-AGENT.md)文首。

前阶段5873c98已远程实查host41813024238（28+23+完整图3）、native41813024224（JUnit32/Node46、SDK/pi-ai及Android组件2m29s）、docs41813024207、scope41813024280全部success。289f88f旧schema断言失败保留为历史。

## 最新：descriptor与Bot真实listener共用启动会话（2026-11-04）

从7967b9d接手后发现服务实际忽略Kotlin/Node descriptor的token，自己另生成一份，照旧交接流程Flutter无法鉴权。`startBotService`现在支持可信启动器传入`authToken`（32–64小写hex），拒绝非法显式值后才操作文件和监听，未提供时仍随机生成；响应/错误不回显token。Node broker端口规范及两类token长度也与Kotlin对齐，0/65536/前导零和超长凭据拒绝。

相关真实Bot HTTP测试含成功鉴权、broker角色token拒绝、响应不含token、重启拒绝旧token、非法输入不创建文件；CI host-contracts加入descriptor套件。实际宿主结果：**28 tests / 28 pass / 0 fail / 0 skipped**；actionlint及diff检查退出0。此阶段没有改native/正式启动器或运行Android；Key仍留native，**完整产品/真实provider/真机未验证**。

下一步不能照旧第11节调用`host.application.miniAppAgent.registerArena`：公开facade不暴露该方法。真实注册在fork `createHostAgentSession().activate()` 使用内部gateway与宿主身份，必须装配它或同一可信生命周期；未完成前不能假装可信Bot caller已接通。descriptor loopback会话也不是isolated UID的可用TCP授权，必须继续既有JNI/Binder/模型FD路线。详见[NEXT-AGENT最新快照](NEXT-AGENT.md)。

已补完整fork宿主构建图（build-runtime-bundle.mjs）：实际2,124上游输入逐blob通过、49外部包引用、真实session-ports和Bot服务进bundle，3项集成测试通过、pin23项与旧gateway smoke通过；不执行runtime，非Android证据。具体复现与动态SQLite/资源缺口见[钉点文档](../UPSTREAM-RUNTIME.md)。

289f88f的native CI41811656655失败日志已取得：JUnit32通过、Node45/46；Key检查分支均过，最后的旧四字段descriptor断言因broker:null失败。现按五字段严格断言并要求该夹具broker=null，保留原Key/env/log/files全部检查；新本地/CI结果见[NEXT-AGENT](NEXT-AGENT.md)。同commit host41811656424、docs41811656733、scope41811656514成功。

下面逐轮记录按当时状态保留，不覆盖本节与最新CI。

> 收尾接手路径、工具链实查与下一项建议见[NEXT-AGENT](NEXT-AGENT.md)；下面历史段落的“待CI”须以上方最新结果为准。

生产Main原子发布结果组件区分PUBLISHED/ALREADY_EXISTS/OUTCOME_UNKNOWN，不把rename后sync失败说成未写入。目录能力抽为RuntimePrivateDirectory，数据库owner复用；**132273a/41765512240的API26/35各17项过**，生产JNI迁移8d65ac0/41766746821也各17项过；双ABI交叉编译/JNI/16KiB检查过，未装配完整FS/launcher，见[T-RuntimeFS](T-RuntimeFS.md)。模型协议、Key保存和正式入口未变，组件close不撤销已发FD。

显式replace供状态/pending更新，共用完整stage与提交结果语义，publication-only内核默认拒绝replace；**30b5249/41767469341的API26/35各21项过**。不能替换leased SQLite文件，不能自动重试unknown。

接着新增生产RuntimeFileCommitHost/Client：Main固定files/<scope>，UID/nonce/peer与replace授权，1MiB二进制pipe、有限admission/close drain；隔离Kotlin验收新增四项（含active close），25项候选待CI，Node文件JNI/完整FS/launcher仍未接。原模型pipe循环抽到共享BoundedPipeReader，仍保留严格UTF8/JSON/provider/model与取消映射；本地宿主JUnit32/Node44、Python25及broker组件回归通过，Node APK最终1m8s。详情见[T-RuntimeFiles](T-RuntimeFiles.md)。

上述文件broker **10be45f/41769936256已API26/35各25项过**，native/broker设备也success。后续新增Node异步文件JNI，仍走同一Main host；新Key断言覆盖三份实际提交文件与file session不入报告/环境。新候选仍25项，设备待新CI（不能套用旧run），两个APK1m47s、Python26/host1过；完整FS/正式launcher仍未接，见T-RuntimeFiles最新节。

Node commit阶段已**a49d79b/41771737494 API26/35各25项通过**。接着新增Main READ/SIZE：显式权限、openat目录cap、只传有界响应pipe、未消费producer的截止/close清理；五新增用例及原25项已构建2m11s，双ABI/Python27过，30项设备待CI。Node读取JNI未接，原模型流/取消与Key断言保留；不能等同完整FS/SQLite撤销/生产launcher。

Main读取已**a96d916/41774204029 API26/35各30项通过**；新Node READ/SIZE JNI另编译1m25s，Python27/host1过，独立30项设备待CI。固定Node必须实际读回三文件并查询大小/拒绝缺失和越界，Key/session/env/log与两PID/WAL断言保留；不是完整node:fs或正式launcher。见T-RuntimeFiles最新节。

Node读取已**585f082/41775322136 API26/35各30项通过**。再将Main stage创建/清理移到同一目录cap的固定openat/unlinkat，新增父目录替换写入用例与普通文件不可被stage helper删除断言；APK35s、双ABI/Python27过，新31项设备待CI。完整FS/原模块恢复/正式launcher仍未完成。

c78faca/41776354659首次API26已31项过，API35在33秒内exit1且没有测试XML（t0），原因未取得；已`gh run rerun --failed`，待实查，不改写成通过。Main另新增普通文件move/remove及四项用例，APK2m25s、双ABI/Python28过，35项设备待新CI；未暴露IPC，后续须显式移动/删除授权。详见T-RuntimeFiles。

最新API实查：c78faca/41776354659失败job重跑已success，API35新增artifact13297459617/t31，首次t0失败记录保留；42fba3a/41777415973 API26/35各35项通过，artifact13298390227/13297479533，同期native/broker设备/scope均success。本轮增加文件MOVE/MOVE_REPLACE/REMOVE控制IPC与Main显式allowMove/allowRemove，覆盖移动还需allowReplace，真实isolated UID矩阵增加单独移动/覆盖授权、拒绝、关闭及unknown测试；本轮Python28/host1/scope4与两个验收APK构建通过（3m10s，71 tasks/15 executed/56 up-to-date）；本轮设备结果待新CI，Node入口尚未接。模型Key边界未放宽，见T-RuntimeFiles最新节。

后续Node move/rename/remove JNI已加入验收，仍只用Main显式cap；隔离Kotlin映射新结果，JNI异常在mutation调用发生后为unknown。两新PID必需完整移动/冲突/覆盖/删除与非法输入断言，原Key/模型/WAL不删；钉点JNI/SQLite与Python28/host1通过，两个验收APK1m11s（71 tasks/17 executed/54 up-to-date），新35项设备待新CI，不能引用前一提交同计数。正式FS/生命周期未接。

本轮再补Main有界append与目录inode gate，所有publisher mutations协调；APPEND显式权限/1MiB总量/pipe与Node JNI已接验收，新增五项（共40项）候选，Python29/host1/scope4/actionlint过、两个验收APK2m34s（71 tasks/21 executed/50 up-to-date），设备待CI。追加是O(文件大小)完整新inode原子替换，不是O_APPEND/FileHandle/大JSONL；未知不重试，不能替换leased SQLite文件。全写者必须统一经过Main API；不是排斥旁路进程的文件锁。详见T-RuntimeFiles最新节。

最新实查：**c1b9aee/41779079402已API26/35各35项通过**，artifact13298915795/13299245445；native41779079439、broker设备41779079401、scope41779079436均success。这证明显式移动/删除IPC，不能用于证明后续Node分支或append。

Node mutation **c8e702d/41779493946 API26/35各35项已通过**，artifact13299151086/13298294234，原Key/模型/WAL不变。本轮再加Main-only子目录cap（固定openat/mkdirat、0700/owner/NOFOLLOW、8级上限），四新增用例共44项候选，Python30/scope4及双ABI JNI/16KiB过，两个验收APK1m33s（71 tasks/16 executed/55 up-to-date），设备待新CI。没有Agent目录FD/目录IPC/Node路径入口；正式FS/launcher仍缺，见T-RuntimeFiles。

最新append CI：888aa77/41780482419 API35 artifact13299282270/t40通过，API26 artifact13298689447/t40-f1-native_data-lc89失败，位置为transport故障夹具的replace，非整阶段通过。XML下载受限，无完整堆栈；源码显示fake不消费body时六次快速调用可遇四writer的pre-IPC backpressure。只修夹具为按真实wire消费body/EOF并断言4次transact+2次body，保留生产限额/unknown语义、不sleep或重试，两个验收APK1m32s（71 tasks/10 executed/61 up-to-date），原因解释待新44项CI验证，详见T-RuntimeFiles。暂停其他新功能先解决此失败。

最新设备复核：c8e506e/41781117518 API26/35各44项通过（artifact13299313672/13298644649）；transport夹具修正78d49f5/41781774323两个job也success，各44项通过（13300155196/13299303575）。原888aa77/API26首次失败保留，后续可继续新功能，不再把待CI当当前状态。原模型/Key/两PID/WAL断言仍全部保留；只有组件/固定脚本证据，正式Bot APK/真实provider/ARM64真机未验证。

> e03b997/run41686820725的SDK route矩阵已API实查API26/35 success；以下待CI是实现当轮状态。正式resolver/启动器仍未接；FD VFS 的6605508/41700450899已定位Main含目录FD的IPC发送失败，SQLite未执行，不能称持久化已解决。后续46c1c07/run41703671145的四普通文件FD方案已API26/35通过（含WAL、两新PID与既有Key/模型断言）；仍无完整产品runtime，详见 [T-Persistence](T-Persistence.md)。

Main持久数据库文件owner本阶段已加入生产源码，验收Node直接使用；固定四数据FD且不截断已有文件，隔离UID必须拒绝调用owner。两个APK43s构建、Python22过；44c0190/run41760010243的API26/35已各7项通过（hardlink按平台/owner分支边界记录）；完整lease/进程撤销、model resolver/启动器仍缺，见[T-NativeDataFiles](T-NativeDataFiles.md)。

## SDK路由接JNI binding

新增 `native-model-route.mjs` 将provider/model/apiFormat和可信binding组合为SDK配置。SDK使用 `https://models.mbot.invalid` 虚拟源与固定placeholder，真实base URL与Key只由native按ID选取，避免Anthropic版本路径与真实base混用。route生成随机request ID，传JSON到binding.open，abort传同ID到binding.cancel；诊断归一化，取消后的成功FD仍交外层ownedChannel关闭，不能在中途throw丢失FD。

实际SDK9/完整钉点pi-ai8现在都经过该route factory与真实继承FD，四种虚拟URL/工具/取消通过；新增route3项验证payload无SDK头、native异常不透传、cancel关联/晚到FD关闭。完整脚本 **JUnit32 + Node44** 通过。native descriptor/session不在route字段中；apiKey字段只是固定非秘密placeholder。

验收Node APK内嵌精确的SDK fetch/route模块，固定脚本经route→JNI→Main原broker发tool-shaped请求，检查SSE工具事件与DONE；原84KB/SQLite/Key断言保留。最后Node两个APK **BUILD SUCCESSFUL in 43s**；host固定脚本1/input拒绝8通过。host使用stdin传大脚本以避Linux exec每参数128KiB限制，Android仍in-process node::Start固定APK脚本，不添加外部代码入口。**新增SDK路由设备执行待CI，正式model resolver仍未装配**。

既有证据：3d99bed的大请求Node run41682351200、broker run41682351421已API26/35全部success。d7d65ad的数据FD能力run41683691783也全部success（见T-SQLite）；不等于持久化/WAL或完整runtime。

## 当前：大请求pipe协议（2026-11-03，设备待CI）

保留OPEN inline的64KiB上限；增加OPEN_FD，最大1MiB JSON经匿名pipe传送，Binder仅带nonce/id/声明长度/FD，wire144KiB上限不放大。Client全进程最多4个writer，线程可回收；结果/错误/取消都关闭pipe端点。先做字符长度/UTF8字节限制，不因大字符串先分配无界bytes。

Host在UID/nonce/admission后才分配最多1MiB缓冲；只接受FIFO，非阻塞poll，默认绝对5秒读截止、每次最多100ms观察关闭/取消；必须恰好读满声明长度且EOF，超长/截断/非法UTF8拒绝。沿用JSON解析前预算和单provider/model绑定，之后才创建模型FD。取消早于/正在读body均拒绝，opening系统调用的取消仍按晚到FD关闭处理，不宣称立即中断connect。

验收APK新增/扩展实际隔离端84KB内容逐字校验、超1MiB拒绝、取消在先、停滞读取截止和读取中取消；Node JNI上限同步1MiB，固定Node脚本也发84KB请求并检查native注入次数。没有放宽存储/网络/命令权限，Key/endpoint/token接口不变。

最后实际构建：

```text
BUILD SUCCESSFUL in 2m 53s
73 actionable tasks: 12 executed, 61 up-to-date
BUILD SUCCESSFUL in 2m 39s
69 actionable tasks: 11 executed, 58 up-to-date
```

分别是broker目标testDebugUnitTest/两个APK和Node目标两个APK。前者XML32项通过；host固定脚本1、input拒绝8通过。**本阶段大请求设备执行待CI**。此前d545dc7的Binder矩阵41665743434、4f92405动态Node矩阵41667564473已API实查success；后者本次日志读取为空，不伪造日志/计数。

## 历史：64KiB Binder与JNI阶段

> 后续：Node Promise/JNI动态调用已在验收target实现并编译，设备待CI，见T-AndroidNode当前节。生产启动器未装配，大请求FD仍未做。SQLite API26/35基线已有run41663798958 success实证，旧“API35运行中”不再代表当前。

## 当前：动态模型请求Binder组件（2026-11-03，设备待CI）

`bot/NativeModelRequestHost.kt` 与 `isolation/ModelRequestClient.kt` 新增隔离端按请求申请模型FD的同步Binder协议。仅可信启动器创建host并传capability；host固定**实测隔离UID + native选择的provider/model +随机会话 + peer Binder生命周期**，调用者不能更换路由/Key/URL/headers。native仍复查provider enabled/model白名单，返回的仍是原生产Unix relay FD，原native端点/token不发送。

Binder先检查UID、wire大小、nonce、request ID、原生JSON预算和路由。最多4个正在open的调用；取消早于open会记录有界30秒tombstone（最多32），open途中取消则关闭晚到FD；FD返回后由接收端close来取消流。owner关闭/peer死亡会关闭该runtime所有channels。closeChannels必须只属于该runtime，不能误传全局共享资源清理。

**首版body上限64KiB**（wire144KiB），超过明确拒绝。此限制小于broker HTTP/SDK的1MiB：大请求FD传输仍待开发，不往Binder塞近1MiB字符串。调用必须离开UI线程；opening期间取消只是标记/晚到FD关闭，不能宣称已中断NativeModelChannels内部正在连接的系统调用。**JNI异步open/cancel→Node Promise仍未实现，正式启动器未接。**

新增仅验收APK的反向调用用例：隔离端实际请求main Binder，读取native模型FD；检查路由/nonce/UID拒绝、取消在先、超限、close、native Key/会话不出现在响应。现有所有Key与FD用例保留。真实Gradle命令沿用下文，`testDebugUnitTest assembleDebug assembleDebugAndroidTest`原始结尾：

```text
BUILD SUCCESSFUL in 2m 31s
73 actionable tasks: 18 executed, 55 up-to-date
```

XML32 JVM项全过，仪器用例编译通过；**新增Binder设备执行待API26/35 CI，本地无设备**。SQLite阶段bc07b1c的API26已成功，API35仍在运行，不能提前写全部通过。full-ICU run41630373256仍build。

## 当前：四类钉点pi-ai真实provider验证（2026-11-03）

新增 `createModelFetchScope`：显式构建AsyncLocalStorage dispatcher，不在import/构造时改全局；可信启动器须安装global fetch，并在 `scope.run(boundFetch, () => provider(...))` 内创建流。解决Google SDK只用全局fetch且pi-ai忽略options.fetch的问题；并发/嵌套调用不串binding，未绑定请求固定400、没有网络fallback。只是路由，不是安全边界；native仍须授权，生产启动器尚未装配。

独立probe `prepare-pi.mjs` 验证权威commit/tree/clean Git checkout/原contract摘要，核对实际SDK和esbuild版本，再将四类provider原文件打包，未改上游源码；忽略的.build记录所有source input SHA。pi-ai从实际工具事件得到lookup参数/path和toolUse终态，四种协议各含停滞流取消，共 **8 passed**。Google通过真实@google/genai1.52.0；其他依赖与上一阶段一致，补partial-json0.1.7/esbuild0.28.2。source仍钉97818b2，没有升钉。

`request-contracts.json`是这四类真实pi-ai产生的固定假请求体；pi-ai测试与其逐字段比较，native JVM子进程通过生产preparePayload读取四类body并确认native认证/endpoint、tools和Key不进body/url/toString。不是仅两端独立mock后假定兼容。

实际命令与结果：

```bash
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/model-sdk-probe
node dsh-go/tooling/model-sdk-probe/prepare-pi.mjs /tmp/mbot-mcode-reference
npm test --prefix dsh-go/tooling/model-sdk-probe
npm run test:pi --prefix dsh-go/tooling/model-sdk-probe
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc bash dsh-go/tooling/test-native-model-broker.sh
```

**SDK9 / pi-ai8 / JUnit32 / Node41**，零失败/跳过。新增的native类只为host测试，没有生产代码或manifest变化；本阶段不重复声称Android执行。上一提交00ce2b5的native（41658367231，含SDK与Android组件编译）、host（41658367202）、scope（41658367234）已实查success。新CI增加钉点provider打包/运行，结果仍须观察。

仍缺：生产动态请求Binder/JNI桥、native授权生命周期、正式model resolver/scope装配、持久化/better-sqlite3及完整runtime；物理真机与真实provider **未验证**。Anthropic header-dependent beta/affinity未开放。full-ICU源构建独立进行中，不因这组host测试通过而算完成。

## 当前：SDK fetch / SSE / FD adapter（2026-11-03）

新增 `model-sdk-fetch.mjs`：绑定providerId/modelId/apiFormat和精确SDK URL；仅POST/JSON对象，读取期间1MiB限制；SDK headers全部丢弃，不调用网络fetch。只通过 `openChannel({providerId,modelId,request},{signal})` 获取owned FD，复用真实生产FD reader；晚到FD在取消后关闭，不因等待bridge或不合作的request body cancel而阻塞截止。native必须再次授权，**生产动态Binder/JNI callback仍未实现**。

SSE按provider重建：Anthropic/Responses从受限type恢复event行，只有completions追加[DONE]。64KiB按字节队列，最多额外一个已有大小限制的native事件；下游慢则await，取消中断回调等待并关闭FD。总截止包含body/open/消费。桥错误返回固定400，防SDK按5xx/网络错误自动重试；流已开始时body只报稳定错误。

真实测试：

```bash
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc bash dsh-go/tooling/test-native-model-broker.sh
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/model-sdk-probe
npm test --prefix dsh-go/tooling/model-sdk-probe
```

组件结果 **JUnit32 + Node37**；额外SDK **9 tests / 9 pass / 0 fail / 0 skipped**。后者不是手写SDK替身：固定上游package.json一致的OpenAI6.26.0/Anthropic0.91.1，真实子进程Unix FD，通过SDK异步迭代器验证工具事件、早停关闭和失败不重试。依赖只在独立host probe/CI，不进APK。初轮Anthropic因测试baseURL多带/v1导致路由拒绝；按其真实SDK追加规则修正，native端未放宽URL策略。

**边界**：未运行完整pi-ai/mcode、未进行这阶段Android执行或真实provider调用；Google钉点忽略options.fetch，仍缺接入。Anthropic beta/affinity等SDK头不透传，高级header策略待native明确。没有新增任意shell/网络或凭据接口。CI增加SDK job；远程结果需继续观察，不能用本地通过替代。

## 当前：SDK请求体与工具协议（2026-11-03）

native与Node proxy接受且只接受两个互斥body：`{providerId, modelId, messages}` 或 `{providerId, modelId, request}`。后者是provider实际HTTP JSON，不是SDK内部config。NativeModelPayload按四种格式检查顶层字段、model/stream和必要类型；保留tool schema/call/result和钉点SDK缓存、思考、采样字段。复用原endpoint/auth逻辑；native复查enabled/model白名单，不允许调用者覆盖Key/headers/URL。嵌套工具数据中名为headers的字段只是数据。不是完整provider schema校验，未知vendor字段拒绝，未接工具执行器。

恢复执行环境后实际验证：

```bash
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc bash dsh-go/tooling/test-native-model-broker.sh
node --test dsh-go/runtime/minimax-bot-host/provider-broker.test.mjs dsh-go/runtime/minimax-bot-host/service.test.mjs
(cd dsh-go && python3 -m unittest discover -s app/test_scope -v)
```

原始测试结尾：

```text
OK (32 tests)
# tests 31
# pass 31
# fail 0
ℹ tests 10
ℹ pass 10
ℹ fail 0
Ran 4 tests in 0.012s
OK
```

Android组件沿用下文JDK21/SDK36/Gradle9.3.1命令，重建后 **BUILD SUCCESSFUL in 5m 29s**；XML32 tests/0 failures/0 errors/0 skipped，两个仪器APK编译通过。新增工具请求经Node代理→真实Kotlin→OkHttp→MockWebServer，验证tool_calls JSON和终态、拒绝headers覆盖，并重复检查Key不进descriptor/env/log/Agent文件。**本阶段无本地设备、完整runtime或真实provider运行。**

既有Android基线见下文a094a85十项矩阵；另04d7e97/run41627233229在API26/35运行真实Node/JNI固定脚本，见T-AndroidNode。旧“Node未验证”是当时记录，不再代表当前。正式启动器仍未接。

下一步：SDK fetch/SSE adapter与动态FD桥；Google钉点忽略options.fetch须另解决。隔离持久化/better-sqlite3和完整runtime仍阻塞。

## 历史阶段（以下按当轮状态记录）

**已验证的当前基线**：`a094a85` 的 Android矩阵 run **41622547971 success**。API26 job125871636255、API35 job125871636043均成功；runner解析XML输出每台 **10 tests / 0 failures / 0 errors / 0 skipped**。实际覆盖Keystore/nativeHTTP、独立UID直接文件/网络拒绝、Unix FD流/取消/容量/截止、IPC启动取消解绑与竞争租约/并发关联。物理真机/硬件Keystore/Android Node/完整runtime仍未验证。后文“待观察”和旧失败是演进记录，以此项覆盖。


## 最新修正：Unix FD 的 socket 选项只能由 owner 设置

`17e7629` run **41604321437 failure**：API26/35均运行八项、各两项隔离读FD用例失败。API26过滤日志进一步确认 `unix_stream_socket setopt` 被拒；FD发送已成功，失败在测试探针对接收FD调用 setsockopt，而不是可以据此宣布Unix传输不可用。API35未收集到相同AVC行，不对其内核原因作超出日志的断言。

已将接收端 timeout option 在**主进程创建socketpair时**设置为60秒，隔离端只读/关闭FD，不再自行setopt。所有正向SSE、Key/token不回显、取消断言保留，未修改SELinux或增加权限。另增加IPC启动取消自动解绑、closeAsync本地清理future与真实竞争租约/并发请求测试；设备测试共十项。本地当前源码 `testDebugUnitTest assembleDebug assembleDebugAndroidTest`：`BUILD SUCCESSFUL in 1m 11s`，JVM27项通过、十项仪器测试编译。新的设备结果待观察。

## 诊断：API26 确认为 TCP socket 权限拒绝

`09faafd` run **41603434315**：API26 job **125807373482 failure**、API35 job **125807374027 success**。失败点仍是两次 TCP FD 发送，`probe_transfer_failed_alive=true`，不是服务真的死亡。runner端过滤后的原始系统证据：

```text
isolated-denial create u:r:isolated_app:s0:c512,c768 u:r:isolated_app:s0:c512,c768 tcp_socket
isolated-denial read write u:r:isolated_app:s0:c512,c768 u:r:untrusted_app:s0:c512,c768 tcp_socket
```

因此新版只传 Unix socketpair，TCP 保持 native；`17e7629` 的新矩阵 **41604321437** 正在观察，尚不提前宣称兼容通过。另补查到 Android Node24 的维护源码候选，具体固定版本/依赖/前缀与持久化关口见 [Android runtime 计划](../ANDROID-RUNTIME-INTEGRATION.md)。

## Unix socketpair 兼容方案（矩阵待观察）

因为 API26 的 direct TCP FD 发送失败，改为 **TCP始终留在主进程，只传 AF_UNIX socketpair 端点**。`NativeModelChannels` 是 native 生命周期 owner，最多4通道，每通道一个非阻塞 poll worker/32KiB缓冲；有界背压、单请求、对端任意输入/EOF取消、10分钟绝对截止、owner.close 全部 shutdown/close。不会把任何对端输入转发到已认证 TCP；无需新权限或同 UID Agent 执行。只传递响应能力而非网络连接能力。具体 SELinux根因仍待诊断 run，不能把兼容方案的合理性当作通过证据。

新增容量/owner关闭、接收端持续持FD时的deadline仪器用例，加之前六项共八项。真实 Gradle `testDebugUnitTest assembleDebug assembleDebugAndroidTest`：**BUILD SUCCESSFUL in 1m 24s**，JVM27项通过；javap确认五项FD/探针方法均编入仪器class（另两项provider/一项fileIPC）。宿主 Node 加实际 Unix FD 继承场景，最终同一脚本 **JUnit27 + Node30 全过**。**新版API26/35设备矩阵尚待观察，物理真机与Android Node/JNI未验证。**

## 最新：Node 预连接 FD 消费与回调取消

新增 `model-fd-client.mjs`：只消费已有连接 FD，不接收 endpoint/token/Key，不发起连接或写请求。解析有界 HTTP header、强制 native framing/SSE/DONE、字节计数64KiB队列，复用 fetch 路径同一 SSE 消费器；完成/错误/abort/绝对截止均 close。消费回调挂起时取消也能释放流（不能撤销回调内部已发生的副作用）。错误只用稳定代码。

真实 Node 子进程通过 stdio FD表继承连接；父进程先认证，只传FD，子进程 env 用白名单。六项测试覆盖分包、回调背压、截断/超大头/错误SSE、取消/挂起/截止、错误内容和无效FD清理。与现有脚本一起实跑：**JUnit27 + Node29 全过**；供应商/服务回归10、scope4通过。这里是宿主 Node24 的互通，**没有 Android Node/JNI 或正式 runtime 证据**；native FD创建请求的可信 Binder/JNI 调用仍未装配。

## 最新：Android FD 矩阵结果（存在 API26 阻塞）

`ebe339b` 的 run **41602011149 failure**：API35 job **125802739079 success**（六项）；API26 job **125802739439 failure**（六项中两项 FD 发送报 DeadObjectException，另四项通过）。API35 已观察直接私有文件/网络权限/loopback拒绝，预连接 TCP FD 可读 native SSE，关闭 FD 取消假 provider；API26 不能宣称 TCP FD 可用。正在定位传递失败，尚不能仅凭 DeadObjectException 认定具体 SELinux 原因。后续 CI 在模拟器结束前用 `run-device-tests.sh` 过滤记录 isolated SELinux 拒绝的 permission/domain/class（不输出原始 logcat、路径、响应或凭据），并区分 Binder 仍存活时的 transfer failure；原 Gradle 退出码保留。脚本 bash 语法与过滤器样例检查通过，实际诊断待新 run。不得靠增加网络权限/换回主 UID 运行 Agent 绕过。物理真机、硬件 Keystore 和 Node仍未验证。

## 单请求模型 FD 初版（历史实现，API26受阻）

`bot/NativeModelChannel.kt` 由可信主进程创建/连接 native loopback socket，完整写入已认证的单个请求后只移交 dup FD。native endpoint/session token/Key 不进入接收端 Bundle、文件或环境；接收端只能读该请求的 HTTP/SSE 字节，关闭 FD 或写额外字节触发原 broker 的 peer 取消。raw Os.close 避免 Socket 包装的 shutdown 影响重复 FD。`NativeProviderBroker.openModelChannel()` 仅 native 内部可用，未注册到 Flutter/Agent，也未装入正式启动器。调用应离开 UI 线程，发送失败和结束都必须 close FD；broker.close 会取消所有在途连接。

验收工程新增**仅测试 APK 内的** `IsolationBoundaryProbeService`（独立 UID、私有、无 shell），不在产品 Manifest。新三项仪器测试：

- 独立 UID 直接打开主 App 非敏感标记文件失败，INTERNET permission 拒绝，不能新建 loopback 连接；主进程同路径/端口可用。
- 向该 UID 通过 Binder 转移已连接模型 FD，消费真实 NativeModelBroker HTTP/SSE；假 provider 只在主进程看到 header Key，返回流中无 Key/native token。
- isolated 接收端读一个响应字节后关闭 FD，真实 native broker 取消仍停滞的替身 provider call；发送方不保留重复 FD。

加基础三项共六项。CI 扩为 API26/35 独立矩阵；从 XML 只输出数量/用例名，不输出响应或诊断正文，便于 artifact 下载不可用时审查。**设备执行尚待新 run，物理真机未验证**。本地复用生产源码的 `testDebugUnitTest assembleDebug assembleDebugAndroidTest`：`BUILD SUCCESSFUL in 1m 30s`，JVM27项零失败，两个 APK 编译通过。不是 Node FD adapter、mcode 工具协议或完整产品 APK。

## 最新：Android 仪器测试与模拟器 CI 准备

新增 `.github/workflows/android-broker-device.yml`：GitHub ephemeral Ubuntu/KVM、API35 google_apis x86_64，执行 `connectedDebugAndroidTest`，只保存 XML，不发布 APK 或签名文件。使用独立 `.brokerprobe` applicationId；复用生产 localhost-only network security config。

`ProviderStorageTest` 增加 HTTP 流用例：Android 进程生成假 Key，经真实 Keystore 保存/读取，真实 NativeModelBroker HTTP/SSE 接受请求；只有远端 provider transport 被替身替换。确认 header 注入、正常完成、回显 Key 拦截及 catalog/log 不泄露。加原存储测试和 IPC 测试共三项。没有真实模型调用/费用。

本地 `assembleDebug assembleDebugAndroidTest` 再跑原始结尾：

```text
BUILD SUCCESSFUL in 1m 27s
67 actionable tasks: 24 executed, 43 up-to-date
```

**后续实际运行**：`2d7367b` 的 Android35 emulator run **41600336727 success**；从 gh run API 和原始执行日志确认三项仪器测试完成，`BUILD SUCCESSFUL in 5m 20s`。XML artifact 下载403，未读取 XML 内容。这是 Android 软件 Keystore/native HTTP/独立 UID 文件 IPC 的证据，不证明硬件背书、真实 provider、原生 Node 或直接文件/网络访问拒绝。**物理真机未验证。** 无本地 KVM/adb，不编造设备结果。上一阶段 `5af2ed8` 的 native run **41599240875**（含 Android 组件编译）已实查 success；host **41599240883**、scope **41599240858** 同样 success。

## 最新：流式完成、背压、截止时间与解析预算

- native 请求在 JSONTokener **之前**检查深度 64、结构符 16384 的预算（字符串和转义内容不误计）；响应同样在 JSONObject 之前检查，避免深层输入先触发递归溢出。原 1 MiB 请求/256 KiB 事件大小限制保留。
- native 设置绝对 header/body 截止时间 10 秒、每流 10 分钟。截止时直接取消上游 call 并关闭下游 socket，不只依赖 readTimeout，因此也能中断阻塞发送；排队请求也有 header 截止。timer 正常完成后移除，broker.close 清理计时器。
- provider 流只有 `[DONE]` 或对应协议终态才允许以 EOF 完成；只有 partial delta 后断开会返回稳定错误，不再补成正常结束。四协议终态与深 JSON、停滞读取均有测试。
- native/host 新流带 `X-Mbot-Broker-Protocol: 1`，正常结束显式输出 `[DONE]`。客户端对这种标记的流拒绝无 DONE 的断开；旧测试替身未声明标记时保留原关闭兼容。新生产两端均带标记，不能把旧兼容分支当作完整结束校验。
- host 客户端在 DONE/错误/取消/回调异常时 finally 取消 body 并释放 reader，限制未完成事件大小，校验 SSE content type；取消后不再分发同一包里剩余事件。错误码改为封闭白名单，不能把形似错误码的任意原文写进日志。
- host 代理 await 回调与 HTTP drain，避免向慢 runtime 无限堆积输出；有每流截止、连接与入站超时、Host/Origin 校验。dispose 会取消、关闭并等待在途模型请求，而不是等模型自然结束。**这不修复前文官方 Bot engine 的持久化 drain 缺口。**

本地最终脚本：**JUnit 24 passed + Node 22 passed**（模型流 10、native 跨进程 6、descriptor 6）；供应商 probe/服务相关 10 passed、scope4 passed。Host 校验用原生 HTTP 请求验证，fetch 会覆盖 Host，不能拿 fetch 的覆盖行为误判服务没有拒绝。Android 验收工程再跑 `testDebugUnitTest assembleDebug assembleDebugAndroidTest`：**BUILD SUCCESSFUL in 1m 28s**；XML **24 tests / 0 failures / 0 errors / 0 skipped**。仪器 APK 编译通过，仍未在设备执行。

前一 Android 组件 CI 初始化修正提交 `4c3e808` 的 run **41591483093** 已实查：native-boundary 与 android-component-compilation **均 success**。不是仅本地推断。最新流式改动需继续观察新 run。

## 最新：Android 组件 Gradle 验证通过，设备仍未验证

新增 `dsh-go/tooling/android-broker-probe` **验收工程（不是第二个产品 App）**：构建时用 AGP variant API 同步选择的生产 Kotlin 文件与测试到 build 目录，不复制维护业务实现，不依赖 Flutter/NDK。为去掉存储类对 Flutter 注册文件的编译耦合，将原 `BotSettingsPlugin.kt` 内的 `BotSettingsStore` 原样移到同包新文件；对比原 class 代码体完全相同，旧加密/迁移行为不变。

实跑工具链：新安装在 `/tmp/mbot-jdk21` 的 **Temurin 21.0.13+9**、`/tmp/mbot-gradle/gradle-9.3.1/bin/gradle`、SDK platform36/BuildTools36.0.0；Gradle cache 在 `/tmp/mbot-gradle-cache`。原系统 JRE21/独立 kotlinc 仍在，但本次 Gradle 使用的是完整 JDK。命令实际执行：

```bash
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk \
/tmp/mbot-gradle/gradle-9.3.1/bin/gradle --no-daemon \
  -g /tmp/mbot-gradle-cache -p dsh-go/tooling/android-broker-probe \
  -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 \
  -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

本机首次需修正 AGP9.1 禁止 SourceSet Provider 的旧写法，改为 generated-source variant API；Task 的 include 名称也用显式 receiver 避免 CopySpec 属性遮蔽。修正后原始结尾：

```text
> Task :assembleDebugAndroidTest

BUILD SUCCESSFUL in 2m 28s
72 actionable tasks: 72 executed
```

XML 实查：`NativeModelBrokerTest 6`、`WorkspacePathPolicyTest 5`、`WorkspaceBrokerTest 5`、`IsolationSessionGateTest 4`，共 **20 tests / 0 failures / 0 errors / 0 skipped**。`ProviderStorageTest` 与 `AgentIsolationClientTest` 仪器 APK **编译/打包通过，但未执行**。aapt2 实查验收 APK：独立 `.brokerprobe` applicationId，min26/target36，仅 INTERNET；服务 exported=false，isolatedProcess=true，allowBackup=false。没有将 APK、debug signing key、缓存或本机 SDK 路径提交仓库，也没有发布 APK。

SDK 因缺 platform-tools 提示许可未接受；没有执行连接设备任务、没有伪造许可文件，compile/assemble 在该提示下正常完成。组件构建不需要 NDK；**完整 Flutter Bot APK/Node/.node Android 构建仍未完成，真机 Keystore/UID/Binder/流式取消仍未验证**。CI 新增同款 Android 组件编译 job；`cc6647b` 首轮 run **41591133622** 在 setup-android 默认安装已移除的 `tools` 包时失败，未进入 Android 代码编译。工作流改为只安装 platform-tools，随后显式安装 API36/build-tools36；须观察新 run，不把本地通过等同于 CI 成功。

runtime 具体依赖与安全装配关口已按钉点源码核对，见 [ANDROID-RUNTIME-INTEGRATION.md](../ANDROID-RUNTIME-INTEGRATION.md)。Node18 mobile 包不满足该钉点 engines；不能把桌面 better-sqlite3/node-pty 拷入 APK 当作完成移植。

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

后续已补 file-only 的 `AgentIsolationClient` 与身份/请求关联装配，见 [T-Isolation 最新节](T-Isolation.md)。它未接 Node，也没有把 native Key/模型 descriptor 交给 isolated 端；不能把它当作模型 IPC transport 已完成。供应商 probe 后续提交 `19d487d` 的 GitHub native/host/scope 三组 CI 已实查全部 success，编号见总 HANDOFF。

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
