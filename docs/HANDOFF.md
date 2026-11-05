# 开发接手入口（2026-11-05续写）

**先读[当前接手合同与完整快照](handoff/NEXT-AGENT.md)**。该文已重写为单一当前版本，列明提交/CI、唯一下一项、允许修改面、验收及禁止推断；旧交接保留在Git历史。下面大表和专题内的逐轮记录只作证据背景，不能当当前TODO。

用户已要求继续开发。当前只在**feat/t-plugins**，阶段成果及时推送；不另起分支、不动main/dev/integration、不force、不PR、不发布APK。

最新分工：Codex负责runtime、独立UID/文件/Key边界、broker、Endpoint及生命周期接口和集成审查；本机M3.1 Flash承担接口冻结后的界面实现、文案、定向验证和文档整理。此前Endpoint代次任务收回归Codex；创建表单、编辑表单及b36ade7的[Bot只读记忆页](handoff/T-Flash-BotMemoryRead.md)均已交付、源码审查接受。Flash当前新卡为[记忆管理](handoff/T-Flash-MemoryManage.md)（b59b9a3），用户约一小时后断电，须预留最后10分钟验证/文档/推送；不改公共API/底层。

Flash首卡`ff363bc/5c44d43`及第二卡`5afbd76/0fc0315/16f6ba3`已纳入；接口报告已纠正，现有创建表单加入校验与单次提交流程。Codex审出关闭过渡后mounted守卫被删，Flash在`82343ad`恢复并补特定回归用例；源码已复核，本机Flash自报analyze与相关widget11项通过，云端无Flutter未复跑，设备未验证。见[创建表单任务卡](handoff/T-Flash-CreateForms.md)和[本机报告](research/flash-bot-create-forms.md)。双方写入范围不重叠。

## 当前已完成与未完成

最新：Flash审查修复09bc6bb已快进纳入，Codex复核上下文代次、统一读写准入及memory_pending未知结果分类，R1/R2/R3源码审查接受。Flash自报45+11项widget及analyze/l10n通过，云端无Flutter未复跑。本机下一步可按[API35验收卡](handoff/T-Flash-API35Probe.md)构建独立验收包、仅跑6项；默认无Intl包不是完整Bot。记忆页「手动刷新失败时草稿暂不可复制」作为非阻断后续已记录在原卡，不宣称UI全部边界已验完。

- Windows Node验收准备候选已接出：同pin/NDK27d、原Linux编译命令不变，Windows直接clang++.exe固定Android26 x86_64目标，UTF-8资产和正斜杠header inventory。Linux实际native准备、Python45/45、host6/6通过；Windows构建和设备未验证。本机执行卡见[T-Flash-API35Probe](handoff/T-Flash-API35Probe.md)，先修记忆管理审查项，再有时间执行6项定向设备测试。JDK21是已有验证基线；此前将默认17直接视为不满足构建的表述过强，源码target17，17是否可用须实际构建确认，不改依赖/target绕过。
- Flash记忆管理6d08ad7已快进纳入用于审查，尚未接受：发现写流程切Bot/API未失效、写/读准入遗漏与memory_pending(HTTP400)误判明确拒绝三项，修复卡已追加到[T-Flash-MemoryManage](handoff/T-Flash-MemoryManage.md)。本机报告未提交（按最新反馈），不要求补交；仅记录Flash自报35+11项widget/analyze，云端无Flutter未复跑。Windows模拟器API35/x86_64可用，但当前默认JDK17不满足验收目标JDK21，且准备脚本硬编码Linux NDK，不能直接认定只缺APK。
- 每代`BotConnectionGrants`已加入：有界授予等待、匹配真实隔离UID、截止、close立即撤销私有等待，迟到回执不恢复授权；现有验收链已加Binder死亡关闭gate/桥和新的设备用例，设备未执行。native回归JUnit69/Node56通过，见[授予撤销交接](handoff/T-BotConnectionGrants.md)；不是正式launcher或完整运行生命周期。
- `BotSocketConnection`新增Android Main socketpair适配候选，已组合进Node验收工程：owner UID检查、同步授予回执、临时FD关闭、半关闭与停止唤醒；真实Bot脚本改用descriptor携带的Main实际端口。新增3项设备测试，仅编译/待设备执行；正式launcher、Binder死亡撤销、Flutter Endpoint组装仍缺。宿主精确脚本6/6、Python39/39通过，详见[Android桥适配交接](handoff/T-BotSocketConnection.md)。该项覆盖下条桥本体阶段尚无connector的旧状态。
- Main侧新增`BotLoopbackBridge`候选：仅127.0.0.1、显式activate、连接数与跨代worker上限、双向背压/半关闭、停止及迟到连接关闭。JVM真实socket与native回归已过，尚未接Android socketpair connector、真实Bot/Flutter和正式launcher；见[桥接交接](handoff/T-BotLoopbackBridge.md)。
- Codex已完成Endpoint代次保护：publish返回native所有权Lease，旧运行退出不能清除新运行；相同URL/token仍区分代次，无效发布不覆盖现有连接。Flutter字段不变，启动器就绪/死亡检测尚未接入。定向JUnit10项，完整native回归JUnit56/Node56通过；见[Endpoint记录](handoff/T-LocalEndpointLease.md)。
- 最新验收脚本在Intl可用时已调用真实官方Bot服务，经native文件能力启动并通过运行中授予的socket处理匿名403与授权状态200；缺Intl时明确只跑基础HTTP夹具。宿主精确脚本6/6、Python39/39，本地验收APK/仪器APK构建通过；**新Android设备用例未执行**。Endpoint代次保护由Codex负责，不用等待rootfs，不另起PRoot runner。
- 真实官方Bot服务增加受限启动候选：不打开主应用工作区，以已授予的状态/记忆能力运行；附件、上传和自定义图片明确返回不可用，默认工作区模式不变。宿主用真实Bot、已连接FD和假文件broker验证HTTP/SSE与鉴权，并从内存模块启动服务；验收APK已打包该模块，**尚未在Android加载/启动真实服务**，也未接产品启动器。
- Bot记忆的临时准备文件采用`bot-memory-stage-<摘要>.bin`命名，native候选在获取独占锁后、授予guest文件能力前清理这一命名空间及严格匹配的原子发布临时文件。宿主验证已接受事务在删除临时文件后仍可从pending恢复，清理会保留持久文件并拒绝软硬链接等异常；已写Android跨两次进程的「清理后恢复」联合验收，但设备未执行。旧版摘要孤儿无法安全辨认，不可批量删。
- 新增Bot数据的native排他候选：可信Main通过`openBotState`在专用目录申请内核协作锁，同一目录的另一独立会话会得到`runtime_data_busy`；关闭或对端死亡后先排空在途操作再释放。Linux实测跨进程竞争和进程被杀后释放，Android验收工程已接入并编译，**设备尚未执行，产品启动器尚未接入**。它依赖所有可信写者遵循协议，不约束绕开该入口的同UID写者或SQLite；详细边界见[NEXT-AGENT](handoff/NEXT-AGENT.md)。
- 官方Bot的团队/单Bot长期记忆已有受控文件适配候选，保留上游事务日志、备份和重启恢复；与状态存储共用同一JS文件会话。宿主故障注入覆盖写入已发生但回执丢失、恢复后不重复日志；真实Bot JSON服务可在没有物理Bot数据目录的宿主替身上重启恢复。**Android验收脚本已接入并编译，设备尚未执行；产品启动器尚未接入**。大文件、临时残留清理和native排他候选的设备验证仍需解决。
- 新增可在Bot状态与后续记忆适配器间共享的文件会话：写入有界串行，未知结果会让所有共享方拒绝后续读写；在途读若会话失效也不返回旧值。它不是跨进程锁，正式启动器仍须保证独占与退出回收。宿主106项通过，Android验收脚本已重新打包编译、设备未验证。
- 官方Bot的`Store`现有可选受控持久化接缝：状态/聊天/定时任务配置/用量账本可经已绑定文件能力读写，不暴露主应用路径；聊天文件映射到固定前缀与摘要命名。写入结果未知时停用该次会话的后续写入，避免重试造成覆盖或重复记账；损坏数据和读取错误不再当空账本。真实Store和MemoryStore与其适配器已接入Android**验收脚本**的文件broker并编译，设备仍未执行；附件和完整runtime文件系统尚未适配。[NEXT-AGENT](handoff/NEXT-AGENT.md)记录1MiB限制与后续任务。
- 新增可信Bot连接接收循环`startBotFdIngress`并导出到runtime bundle：顺序接收FD、关闭时立即停止，迟到的FD到达后关闭；验收APK已调用JNI cancel来唤醒底层队列等待，并编写明确断言。宿主用真实官方Bot验证鉴权、创建及SSE，全套宿主90项通过；**Android候选仅编译、设备未验证，产品启动器尚未接入**。详见[NEXT-AGENT](handoff/NEXT-AGENT.md)。
- 验收工程已接出运行中动态交付Bot socket的候选链：Main经owner UID检查的Binder把socket交入有界队列；隔离Node经受限JNI异步取出，交生产FD transport。设备测试编写了Node启动后顺序交付两次、启动前拒绝并关闭、另一个isolated UID伪造交付时拒绝并关闭、原模型/Key边界等断言；**只完成本地构建，Android执行未验证，尚非产品launcher/真实Bot**。宿主精确脚本3项和Python33项通过；详见[NEXT-AGENT](handoff/NEXT-AGENT.md)。
- **GitHub Actions计费阻塞**：76f3dcc触发的41943645851等5个workflow全部在作业开始前失败、步骤数为0；check-run annotation原文为“The job was not started because recent account payments have failed or your spending limit needs to be increased. Please check the 'Billing & plans' section in your settings”。本地测试不能冒充Android设备CI。账户限制解除后对最新提交重新运行相关workflow并查看artifact。**2026-11-05 实查：阻塞持续，`71365da`…`96905e6`（8个提交、105文件、+11344行）触发的每一个run——41981683344 / 41982566651 / 41983281162 / 41984495268 / 41985118453等，覆盖全部六个工作流——annotation文本逐个一致，即任务根本没启动，这批代码一次都没被检验过。Windows侧本地替代检查已跑：文档检查器36份0问题、probe Python 39项全过(6跳为Linux-only)、host Node 48项34过(14错全是symlink权限/POSIX权限位EPERM)、`runtime-host-probe`因设计只支持Linux x64+Node ABI137跑不了；详见[NEXT-AGENT](handoff/NEXT-AGENT.md)第4节。**

- 为运行中动态交付连接增加有界`GrantedConnectionQueue`：最多64项，实际默认8项；满/关闭时关闭未接收的连接，退出关闭剩余连接，取出后所有权转交消费者。单元测试覆盖顺序、超时、关闭唤醒、拒收及竞争；验收工程已接Binder/JNI，但**尚未接产品启动器或设备验证**。具体下一步见[NEXT-AGENT](handoff/NEXT-AGENT.md)。
- Android独立Node验收新增双向Bot socket FD用例：Main在启动时传入socketpair并发HTTP，isolated Node用生产FD transport和简单HTTP夹具回复，断言无监听、请求/响应与Key边界。宿主精确脚本3项、Python33项和验收APK编译通过；**fd80f3b / run 41864552047整体success，API26/35各t49/f0/e0/s0；真实Bot业务的Android运行仍未验证**。
- 新增Bot的已连接FD服务接缝：真实官方Bot HTTP/鉴权/创建/ SSE可消费可信启动器交来的Unix连接，不在guest开启监听；保留原TCP模式。宿主实际跨进程FD测试及84项host回归通过，**Android动态FD候选已编译但设备未验证；Flutter桥/完整产品尚未接入或验证**，停止时活动SSE可能收到连接中断。
- 已实现隔离descriptor只读FD交接组件：Main原子写入、校验owner和权限后提供只读FD，isolated Node按可信owner UID、大小、文件类型和字段白名单验证并关闭；支持已被替换的旧inode。Kotlin允许broker为null，不伪造isolated无法使用的loopback模型broker。8df623b / run 41861262297整体success，API26/35 artifact均t49/f0/e0/s0；正式launcher仍未接入。
- Android隔离Node验收已增加生产`native-model-binding`的真实JNI/Binder模型链：无scope先拒绝，两个binding先后经过相同授权模型/流，关闭一个后另一个继续；仪器用例核对工具事件、生成Key只在native注入且不进入报告/文件。Linux实际FD夹具2项、Python32、SQLite VFS宿主验证和两个验收APK编译已通过；**a3c2056 / 41856965389 API26/35模拟器均success，artifact各t49/f0/e0/s0；真实provider/产品launcher仍未完成**。
- 本轮修复多个runtime共享fetch时的路由冲突：共享AsyncLocalStorage、按target引用计数与在飞operation收尾，任一runtime关闭不能让另一runtime退回原始fetch；未绑定请求拒绝。此路由仍不是进程/UID隔离，孤儿异步任务及真实launcher的进程终止须另行处理。
- 本轮修复缺失native binding时的绕过：Bot的run/answer现在返回`native_binding_unavailable`，不会调用上游gateway；带绑定的turn仍走scope。真实gateway生命周期新增负例。目录仍可展示；native broker接线与正式启动器尚未完成。

- d31b379：通过原RuntimeServices精确构建接缝注册内置服务身份，不造第二gateway、不写17ports；8真实gateway/facade生命周期cases过，session执行为桩。
- f937a3e：780 canonical资源逐blob/inventory校验，构建4项；真实原factory/gateway/session/SQLite + 官方Bot JSON冷启动/重开/鉴权/创建/持久化读回1项（无桩factory）过。**host41824727445、native41824727414、docs41824727413、scope41824727426全success**，已读日志。
- 3d686ee：修Android full-ICU最后metadata报告，要求真实full配置、读ICU/Unicode头、Ninja前预检；Python32/真实头parser smoke过。新producer **41826964330的build/upload已success**，artifact13318613900为45,047,372字节，authority检查已本地通过；**API26/35 verify也均success，日志均启动49测试后BUILD SUCCESSFUL，Intl/Key验证已过**。后续a2c17d5/run41831847047已完成新ICU与当前SQLite/文件/模型/Key组合的API26/35模拟器验证，详见下方。
- 旧41757171111 attempt2已实际完成5885/5885和110.72MiB库strip，失败在原versions.py只允许intl=none；cache3211 hits（55.85%）且保存成功。旧失败run不能复用，也不要rerun旧commit期待新修复。
- 目录接线阶段（f23775b / 8a52f85 / 9c715b5）：host 41847283169、native 41847283225 / 41850609903、scope 41847283180 / 41850609904、docs 41850610022 / 41852384364、设备 41850609902 全部 success。`Isolated Android Node candidate`（run 41847283176）API26 success（49/0/0/0）；API35 首次因runner模拟器SDK包下载损坏失败（0测试），**重跑已实查success**，结果artifact API35 t49/f0/e0/s0。是模拟器证据，不是物理真机。
- **没有模型turn、可用Android Bot APK、真实provider、ARM64/物理真机或profile证据。** 默认App仍DSH；native 非密钥目录投影、每轮绑定与descriptor接线已落地，但 **broker binding 进 guest 进程、隔离模型/文件FD、正式启动器、每Bot可信MCP/自主任务仍缺**，市场写入关闭。

## 接手第一项（必须按顺序）

1. **组合矩阵已跑通（2026-11-04 20:54 实查）**：a2c17d5只把android-node-probe.yml的既有PINNED_SOURCE_RUN_ID设为41826964330，未动五个producer权威输入、未放宽任何校验。**41831847047整体success**：API26/35两job均success，Node probe counts **tests=49 failures=0 errors=0 skipped=0**；结果artifact 13324805400(api-26-t49-f0-e0-s0-passed-l0)、13322759984(api-35-t49-f0-e0-s0-passed-l0)。本轮prepare用的是钉住的full-ICU源包，`prepare-probe.py`核对config.gypi后拒绝非ICU源并把`requiresIntl`置真，设备侧`IsolatedNodeModelTest`因此**实际执行**Intl断言（zh-CN locale、Asia/Shanghai时区）——这比「同样49项」是更强的分支证据。仍**没有**真实provider/模型turn、产品APK、ARM64或真机结论。
2. **已完成（2026-11-04）**：native 非密钥目录投影与每轮受控绑定已落地。**不复用自写投影层，改用钉点官方 `application.modelProviders.create/update` + `application.models.list/select`**（钉点 commit 与 pin 未动）。新增 `runtime-catalog-policy.mjs`（目录字段策略）、`model-catalog-projection.mjs`（纯投影 + 官方调用序列）、`native-model-binding.mjs`（按轮模型键 → native route）、`native-model-runtime.mjs`（不依赖 gateway 的装配层）；`runtime-descriptor.mjs` 携带非密钥 `catalog`，`runtime-host-session.mjs` boot 后装配并返回 `providerBinding`/`setSessionModel`，`agent-port.mjs` 每轮经绑定执行。目录含凭据类或端点类字段一律整体拒绝；guest 内只有虚拟 origin 与占位 key；每轮开始解析一次模型键 → 切换在下一轮生效、在飞轮用旧模型；目录外模型该轮明确拒绝。**实测校正**：官方 `models.select` 不要求连通性测试通过（只要求 provider 已配置、模型在列表内、`apiKey`/`baseUrl` 非空），详见[映射笔记](research/native-model-metadata-mapping.md)第 4c 节。远程验收：真实 fork 部分 **run 41840651106（commit 044600c）整体 success**（真实钉点 gateway/facade cases 9/9、真实 factory 的 `config.yaml` 只有占位 key 与虚拟 origin 且 `defaultModel` 已生效）；最终提交 **9e32d04** 的 host 41842253311 / native 41842253222 / scope 41842253245 全部 success（native-boundary Node 段 52/52）。过程如实记录：descriptor 加 `catalog` 字段后 native-model-broker 的 native-boundary job 曾连续三次失败，真因是既有测试断言 descriptor 的精确键集合，已在 9e32d04 更新并**加强**为断言目录不含 `apiKey`/`baseUrl`。本机可跑部分 40 项全过（`runtime-descriptor` 原有 7 项在本机失败是 Windows 权限位/符号链接限制，与本阶段无关，已在 baseline 复跑确认）。
3. **唯一下一项（目录接线已补齐，2026-11-04，commit f23775b / 8a52f85；host 41847283169、native 41847283225 / 41850609903、scope 41847283180 / 41850609904、设备 41850609902 全部 success）**：native 侧「产出剥离后的非密钥目录 → descriptor 携带 → guest 读回 → 钉点官方 provider API 投影」这一半**已经落地**：新增 `bot/GuestProviderCatalog.kt`（**允许列表**，只写 providerId/name/apiFormat/enabled/models，构造上就写不出 baseUrl/Key）与 `runtime/RuntimeDescriptorPayload.kt`（编码时按**字段名**挡凭据/端点字段，读起来像密钥的 model id 不误拒；`RuntimeDescriptorSession` 与 descriptor 编码从含 `android.*` 的文件拆到纯 Kotlin 文件，调用方不变），`BotProviderStore.guestCatalogJson()` 是 native 侧唯一目录出口（给设置页的 `catalog()` 含 `baseUrl`/`maskedApiKey`，不动、不得进 descriptor）；`createBuiltinBotRuntime` 改为**只收 descriptor**，目录由新增的 `catalogFromDescriptor` 重新校验后取出，真实 factory 探针已走完整「写入→读回→投影」链路，并加了「带 baseUrl 的手写 descriptor 即使 owner-only 也被整体拒绝」负例。**剩下的一半是 native broker binding 进 guest runtime 进程**，它依赖尚不存在的 Android 独立 UID 启动器，在此之前 `nativeBinding` 仍为 `null`、官方连通性测试仍记 `not_run`、模型请求仍无路由。之后才是每 Bot 可信 MCP caller 绑定、真实 provider 验收、ARM64/真机与产品 APK。
4. 用户提供的发行包 **@minimax-web/minimax-code** 仍待确认：2026-11-04复查npm官方registry依旧404，搜索只见旧的`@fectivnfy112358/minimax-code-web`；按[查证任务](research/webui-published-package.md)继续等准确包页/安装命令，不猜同类包替代。本轮未安装/切pin。
5. native I/O与独立UID启动器没验收前，**禁止用同UID DSH/PRoot跑完整Agent**。不重新搭页面、不换Nyral引擎、不适配MiniApp、不开放安装来「绕过卡点」。

详解：[T-RuntimeHost](handoff/T-RuntimeHost.md)、[T-AndroidNode](handoff/T-AndroidNode.md)、[T-RuntimeBroker](handoff/T-RuntimeBroker.md)。工具链/复现命令及待决定的具体接口都在当前接手合同，文档没定清的不能自行包装为已完成。

## 累积组件证据索引（历史记录，当前任务以上文为准）

| 能力 | 已取得的证据 | 尚未完成 |
|---|---|---|
| native-only模型broker | 四类请求体/工具、流/取消；JUnit32、Node44、实际SDK9、钉点pi-ai8；7ce84ec的native/host/scope CI通过 | 真实provider、正式配置同步/启动器 |
| 隔离Node + Keystore + SQLite | bc07b1c/run41663798958：API26/35各1项仪器测试通过，含内存事务/回滚/中文/JSON/serialize及Key边界 | WAL/干净重启另见下行；ARM64、物理真机未验证 |
| 动态模型请求 | d545dc7/run41665743434 Binder矩阵成功；4f92405/run41667564473 Node Promise→JNI→Binder→native模型FD在API26/35成功 | 正式model resolver/scope/生命周期装配 |
| 1MiB大请求FD | 64KiB内保持inline；更大JSON走有界pipe，精确长度/EOF/UTF8/截止/取消。broker目标最终2m53s、Node目标2m39s构建成功，host1+输入拒绝8过 | 3d99bed的Node41682351200/broker41682351421已API26/35通过 |
| 数据文件FD能力（当前研究） | 单个明确授予FD的读/写同步/截断/锁/RW映射五用例已编译，broker目标1m23s成功；按私有路径open必须继续拒绝 | d7d65ad/run41683691783已API26/35通过；不是VFS/持久化已实现 |
| **SDK→JNI路由（当前阶段）** | 虚拟SDK URL/placeholder→可信binding；JUnit32+Node44、SDK9/pi-ai8通过，Node APK43s构建成功 | e03b997/run41686820725已API26/35通过；正式resolver未装配 |
| FD SQLite VFS Android候选 | **46c1c07/run41703671145 API26/35 success**，artifact各t1/f0/e0/s0；WAL/并行只读/重开/integrity、两次不同PID读回、既有模型/Key断言通过；宿主另有writer/reader争用、crash恢复、sync故障注入 | 临时验收四普通文件FD；不是正式native数据lease、完整FS或产品runtime；ARM64/真机/Android crash恢复未验证 |
| Main持久数据库文件owner | 生产RuntimeDatabaseFiles固定四文件、NOFOLLOW/普通文件/单链接/私有权限/owner/worker校验；两个APK43s构建、Python22过；验收Node改用同一生产源码 | **44c0190/41760010243 API26/35各7项全过**；含原模型/Key/WAL两PID。hardlink按平台/owner边界记录，非完整lease/撤销 |
| Main独占文件发布探测 | 新独立JNI在Main用私有目录FD+严格basename做renameat2(NOREPLACE)，4用例已编译；APK2m7s、Python23/host1过 | **7406bb3/41761635832 API26/35各11项通过**，含原七项；仍仅验收，不是完整FS/通用link替代；见T-RuntimeFS |
| Main原子发布结果语义 | **132273a/41765512240 API26/35各17项通过**；生产JNI迁移**8d65ac0/41766746821也各17项通过**，双ABI编译/JNI/16KiB过；见T-RuntimeFS | 不是完整FS/launcher；不能用close撤销SQLite FD |
| Main原子替换 | **30b5249/41767469341 API26/35各21项通过**，含四新增替换/旧FD/并发/unknown和旧回归；同期native/broker设备均success | 未绑定原JSONL/MemoryStore，不得替换leased SQLite inode，unknown须上层恢复；见T-RuntimeFS |
| Main范围/操作文件broker | **10be45f/41769936256 API26/35各25项通过**；真实UID/nonce/范围/覆盖权限、84KiB二进制、active close/drain及原模型pipe/Key/WAL回归；native与broker设备也success | 不等于完整FS或SQLite撤销；该基线仍是隔离Kotlin矩阵，见T-RuntimeFiles |
| Node文件commit JNI | **a49d79b/41771737494 API26/35各25项通过**；offset view快照、实际文件提交/replace/空文件/非法buffers、两PID与扩展Key检查 | JNI仍验收，未接原FS/正式launcher；见T-RuntimeFiles |
| Main有界读取/size | **a96d916/41774204029 API26/35各30项通过**；Main openat、普通私有文件检查、有界响应pipe、输出截止/close、父目录替换下cap不逃逸与旧回归 | 非完整stat/FS，不能代替SQLite撤销；见T-RuntimeFiles |
| Node读取JNI | **585f082/41775322136 API26/35各30项通过**，84KiB/原快照/空文件/大小/缺失与路径拒绝、两PID/Key/WAL保留 | 仍验收，不是完整原FS/生产launcher；见T-RuntimeFiles |
| stage目录FD改造 | c78faca/41776354659的API26 **31项过**；Main固定openat/unlinkat、父目录替换及stage拒绝断言，双ABI过 | API35首次step33秒退出、t0/f0/e0/s0，原因未知；失败job重跑后31项通过（artifact13297459617），首次失败仍保留；见T-RuntimeFiles |
| Main move/remove | 同目录私有普通文件移动/替换/删除，冲突与unknown保留，拒绝symlink/目录/宽权限；四新增用例、APK2m25s、双ABI/Python28过 | **42fba3a/41777415973 API26/35各35项通过**；显式move/remove IPC已**c1b9aee/41779079402 API26/35各35项通过**；Node后续候选另见下行，不能视作完整FS；见T-RuntimeFiles |
| Node move/rename/remove | 新固定异步JNI→隔离Kotlin→Main cap；两新PID必需移动/冲突/替换/删除/拒绝与原Key/WAL断言，钉点JNI/SQLite与Python28/host1通过 | **c8e702d/41779493946 API26/35各35项通过**；不是完整FS/正式launcher，见T-RuntimeFiles |
| 有界append/目录协调 | Main按目录FD inode协调publish/replace/move/remove/append；新增APPEND显式权限与Node JNI，五新增设备用例、原Key/WAL回归保留，两个验收APK2m34s、Python29/host1/scope4/actionlint过 | 888aa77首次API26 t40 f1保留；后续**c8e506e与78d49f5各自API26/35均44项通过**（含追加、目录与夹具修正）；O(文件大小)整文件原子追加/总量1MiB，不是O_APPEND/FileHandle/大JSONL或跨进程锁，见T-RuntimeFiles |
| Main子目录cap | 固定openat/mkdirat、私有0700/owner/NOFOLLOW、8级上限；四新增用例、两个验收APK1m33s、Python30/scope4及双ABI JNI/16KiB过 | **c8e506e/41781117518及78d49f5/41781774323均API26/35各44项通过**；只Main API，未给Agent目录FD/IPC/Node，不是完整FS，见T-RuntimeFiles |
| **设备端 runtime 接线（当前阶段）** | 已查清三处硬缺口并补其一：Kotlin 侧 descriptor 写入（0600/O_EXCL/O_NOFOLLOW/rename/owner-only 校验）+ Node 契约同步扩展 broker 会话；**设备已验**：41794831403 API26/35 success（各19项、含新增3项descriptor用例、0失败），41794661470 node矩阵 API26/35 各49项0失败（44+5 held-cap），host 41794831396 success。**pin 已扩展为按整树 blob 校验**（b0d570c），fork runtime 现在可以进 bundle | rootfs 仍只装 DSH 引擎，mcode fork/官方 Bot/host adapter 未进 guest；设备侧无人启动 Bot 服务；**产品 APK 未产出**（Windows 上卡在 Kotlin 增量缓存，见 NEXT-AGENT 第13节）；见 NEXT-AGENT 第11节 |
| full-ICU Node | 3d686ee/run41826964330的build、API26/35 verify全部success；成功归档artifact13318613900可按原authority复用。旧41757171111失败已定位/修复，不rerun旧commit | 下一项接consumer既有PINNED_SOURCE_RUN_ID，验新ICU+当前SQLite/FS/模型/Key组合；产品runtime、ARM64/真机未验证 |

正式入口仍DSH；上述都是组件/独立验收APK/假provider，**没有完整mcode/官方Bot产品运行、完整Flutter Bot APK或真实模型验收**。插件变更仍关闭。持久化、可信Bot身份/MCP、工具权限及生产启动器仍缺。不得因组件通过就宣称App已完成。

详见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)、[T-AndroidNode](handoff/T-AndroidNode.md)、[T-SQLite](handoff/T-SQLite.md)。较早的逐轮状态只作历史。

## 从哪里继续

**最新续写分支：`feat/t-plugins`**。这是连续叠加的功能分支链，不要从仍未更新的 main 续写，也不要分别重复 cherry-pick 前面各分支：

```text
dev/integration 891aefa
  → feat/t-sandbox a1f9738
  → feat/t-settings 1e4b97e
  → feat/t-providers 4c88198
  → feat/t-usage ce00692
  → feat/t-schedule 5e1860e
  → feat/ui-motion f04ce36
  → feat/t-plugins f0d93cb（插件代码；后续收尾文档提交见 git log）
```

`main` 保持 `e32fbd7383cd9bdc268aa7df2ec9150e8bc4e48e`；`dev/integration` 保持 `891aefa71c5aea952ec8aa2502c2ada854854042`。没有合并到这两个分支，没有 force push、PR 或 APK 发布。用户已授权持续开发及阶段 push，后续继续在功能分支提交并及时 push，不再为普通可逆修改重复询问。

开始先看 `git status --short --branch`，保护其他开发者的未提交工作，再 fetch/checkout 最新功能分支。当前执行环境保存的 gh CLI 凭据有效，但继承的 GH_TOKEN 无效；必要时使用 `env -u GH_TOKEN -u GITHUB_TOKEN gh ...` / `git ...`，不要输出任何密钥。

## 产品决定

- Android 本机多 Bot，改造 DSH Go 外壳，MiniMax Code WebUI fork 的 runtime，复用官方 mbot 业务；不是通用 coding Agent 工作台，不做云环境、远控、手机操控。
- 设置输入 Key / Base URL / 模型。保存的Key留在native；broker/隔离Node FD已有组件验证，正式启动器与WebUI配置同步尚未装配。
- 手机固定竖屏；平板允许横屏和自适应展开。参考 Grok 布局，不复制其商标图案。
- **自动任务在各 Bot 资料/设置页**，与 Bot 经 mbot MCP 创建的任务共用数据源；全局设置七组，无自动任务组、无电脑行。进程被杀后不执行、不补跑。
- **单一总工作区 `filesDir/workspace`，项目放子目录，多个 Bot 可绑定同项目协作**。仅总根和路径守卫已落地，项目管理/目录绑定待开发。cwd 不等于授权范围，PRoot 不等于沙箱。
- 页面要流畅、有过渡/轻量骨架，尊重减少动态效果/DSH 性能降级；不宣称未测的真机 FPS。
- Mini App 是另一个可视化程序类型，暂不适配。mcode 插件市场单独做，不能把能从 GitHub 下载等同于在 Android 上可安装执行。

## 外部 agent 任务验收：文档链接检查器

M3.1 Flash已提交78c8293（仅tooling/docs-links内4文件），本工作区已fetch并快进接收。38项测试与9处失效链接已独立复现；主agent修复history文档的9处链接、加入根Python缓存忽略规则。补上“目录读取失败被os.walk吞掉而误报成功”的回归，现39项通过；symlink测试外部目标也改为受管临时兄弟目录，避免共享/tmp固定文件。已新增只读、Python3.12的docs-links workflow；本地README/docs/检查器文档扫描29份0问题，**860563e的文档CI 41784617417已success**。原交付报告和主验收补记均见[REPORT](../tooling/docs-links/REPORT.md)。

## 新增参考：Nyral

用户提供aixtin/nyral，已只读检查固定提交72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328（MIT），见[源码对照](research/nyral.md)。自研Kotlin引擎/原生SQLite更直接，但未发现mbot/mcode runtime或官方插件市场适配，不能因此放弃既定兼容要求。可借鉴会话epoch、pending事务与轻量交互；不搬宽存储、同UID shell、手机操控、系统闹钟链。本轮没有复制代码或构建/运行Nyral，当前脚本还有crashToken构建输入要求；原有安全边界不变。

## 新增参考：mcode-agent-table

用户提供的MiniMax官方BYOB项目已固定到f230386ba0e405f2470d4f42863df3a5c0399297（MIT）并静态检查，见[源码结论](research/mcode-agent-table.md)。对群聊拓扑/发言权、任务审批/执行/核验及账本恢复有用，暂作后续协作参考，不替换当前runtime/FS/身份路线，不导入Rabbit Hole Mini App。MCode适配器的full执行thread、Node私钥/env和直接FS不能照搬；本轮没有安装或运行其测试/CLI/模型。

## 已做的事情与边界

按顺序阅读各专题 handoff，避免只看提交标题：

1. [T-Sandbox](handoff/T-Sandbox.md)：路径防护、宿主媒体检查、targetSdk36、去 FileProvider 宽外部路径、凭据备份排除。**不是独立 UID 隔离**。
2. [T-Settings](handoff/T-Settings.md)：设置接入、通知偏好、native 系统跳转。旧八组已被每 Bot 任务决定覆盖。
3. [T-Providers](handoff/T-Providers.md)：四 API 协议多供应商/独立 native 加密保存、metadata UI、宿主 probe。native-only broker已有组件证据，正式模型同步未接。
4. [T-Usage](handoff/T-Usage.md)：只读额度白名单投影、页面和 API provider 不冒充账号登录；真实 OAuth/UsageQuotaClient 未装配。
5. [T-Schedule](handoff/T-Schedule.md)：Bot 资料/任务管理、同源 MCP 宿主测试、进程内 SSE→native 通知桥。调用者身份可信绑定、真机通知、断线/页面外通知尚未完成。
6. [UI-Motion](handoff/UI-Motion.md)：DSH 路由过渡、轻量骨架、会话入场、lazy lists、SSE 直接合并/32ms 通知与晚到历史竞态保护。
8. [T-RuntimeBroker](handoff/T-RuntimeBroker.md)：native Kotlin broker、四协议文本/工具请求体、流/取消、host代理/FD与Key边界；JUnit32+Node31通过，Android组件编译通过，正式runtime未装配。
9. [T-Isolation](handoff/T-Isolation.md)：isolatedProcess 文件占位、主进程 broker、身份/请求关联客户端、纯 JVM 路径/会话测试；Android组件 Gradle构建通过，Android35 模拟器文件 IPC 用例通过；完整 Flutter/Node 构建未完成。
7. [T-Plugins](handoff/T-Plugins.md)：插件页和只读 mcode adapter、GitHub 固定版本预览、协议字段/installed cursor 差异。**生产变更入口强制关闭，当前无正式运行时 descriptor，非已完成安装市场。**

关键现实：默认正式启动器仍是 DSH 基线。`lib/dev/minimax_bot_main.dart` 只是 UI 联调入口。没有成功构建完整 fork APK，没有真实 Android 设备，不能说 App 已写完/模型已跑通/完整沙箱可用。

## 接下来优先做

唯一当前顺序见本文顶部和[NEXT-AGENT第4—6节](handoff/NEXT-AGENT.md)。旧分轮TODO不再重复列在这里，以免接手模型重做已完成的gateway/held-cap/descriptor或重编已成功的Node。

## 历史环境记录（当前路径以NEXT-AGENT第8节实查为准）

环境曾中断，恢复后 `/workspace` 保留，`/tmp` 清空并部分重建。路径须实查。

- Node24.19.0；Kotlin `/workspace/toolchains/kotlinc/bin/kotlinc` 2.4.0；Flutter `/workspace/toolchains/flutter/bin/`。
- 当前组件构建：完整JDK `/tmp/mbot-jdk21`、Gradle9.3.1 `/tmp/mbot-gradle/gradle-9.3.1/bin/gradle`、SDK `/tmp/mbot-android-sdk`（platform36/build-tools36.0.0）；此次恢复改用持久缓存 `/home/agent/.gradle`。JDK/Gradle按发布SHA256验证，SDK/NDK/CMake按Google清单摘要恢复；旧/tmp缓存已清空，不能假定离线构建可用。
- `/tmp/mbot-native-deps`、`/tmp/mbot-gradle-cache`不存在；`/tmp/mbot-mcode-reference`本轮已恢复为干净钉点checkout，pin测试23项通过。缺失jar仍需按CI固定摘要恢复再运行对应测试，勿沿用旧路径判断。`/tmp/mbot-ndk27d`、`/tmp/mbot-node24-mobile.zip`、SQLite归档仍存在，JNI/SQLite准备输出保留；没有adb/设备/KVM，full-ICU源码编译在CI。
- Widget测试需 `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts`；只暂存明确路径，检查pub/analyze产生的无关修改。
- 完整命令见T-RuntimeBroker；组件APK编译不是完整Flutter Bot APK。

## 早期UI阶段验证结果（保留来源，不是本次收尾状态）

- 最后完整设置包测试 **27 passed**；包括 4 个 motion 和 5 个插件测试。插件初轮发现测试 tooltip 定位错误和预览期间加载动画未停导致 settle 超时，均修正后完整通过。
- 最新插件 App 适配/入口 + 设置回归 **3 passed**；此前动画/会话/资料/通知/额度相关 **12 passed**，最终 UI 改动后相关子集 **7 passed**。
- mcode 插件协议 **4 passed**；协议客户端、协议测试和 runtime 测试文件静态分析通过。**本轮未设置 MCODE_WEBUI_ENTRY，没有跑真实 WebUI runtime 测试。**
- App `flutter analyze --no-pub lib/bot test/bots/bot_plugins_test.dart` 与设置包 `dart analyze` 无问题；最后对 cursor/subPath 补拒绝截断的校验并复跑插件测试。
- 已实查成功的 GitHub Actions：T-Schedule host **41554914121** / settings **41554913845** / scope **41554913796**；UI-Motion settings **41556055349** / scope **41556055408**。UI-only 提交没有触发 host workflow，不编造对应 run。
- `feat/t-plugins@f0d93cb9ed8fbc4254fefd476505793ef10c683a` 已推送并通过 ls-remote 确认。scope **41557103632 success**；settings **41557103607** 截至收尾实查仍在准备 Flutter，**尚不能算通过**。下一位运行 `gh run view 41557103607` 查看结论；以上 CI 均不构建 Android APK。后续仅文档提交不改变已测试代码。
