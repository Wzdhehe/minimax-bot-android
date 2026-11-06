<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeFS：完整文件适配前的契约与原子发布组件（2026-11-04）

## 当前：SQLite 固定文件元数据接线（2026-11-06）

后续已增加host验过的JNI候选：固定8FD及可信owner参数，显式有限数字结果、一次进程单次调用、永久取消；Kotlin解码不把复制候选称为发布成功。真实JVM加载原生库并完成复制/独立进程只读复验及拒绝路径通过。再后续私有 `SqliteBackupService`/Binder候选已写并以Android36 API精确编译，源代码检查实际Binder身份和独立UID、保留预取消、Main死亡及完成时自退役；session纯JVM7项加入后native回归88/88、Node56/56。**Service未在设备运行，Main客户端/实际授予与退役确认/原生库打包/发布/snapshot FS仍未接**，不能据此解除本卡missing-snapshot拒绝；边界和复跑见 [native README](../../dsh-go/runtime/native-sqlite/README.md)。本机原双ABI只读卡已结项，df0eeac不含新JNI源文件。

日间后续已新增 [native SQLite backup kernel](../../dsh-go/runtime/native-sqlite/README.md)：独立只读 source、固定read snapshot后的页数预算、真实sqlite3_backup与完整性检查、取消/超时和无原文错误结果。真实8FD宿主用例验证另一进程在选定快照后提交并长大，备份仍为原版本；负例验证只读准入/非空目标拒绝、超限、busy、取消、校验失败及事务回收。保持原SQLite编译定义（omit progress callback），通过joined watchdog调用sqlite3_interrupt。它**尚未接Android/JNI/Binder/备份端口或持久发布**，不是下述missing-snapshot拒绝已解除；native owner、source/destination授予、关闭/fsync/发布/只读回授和upstream snapshot FS仍待实现，详情以新README为准。

新 `fd_backup` 组合层以8个普通FD而非路径/目录FD接入：检查owner与可信参数一致，拒绝非私有/多链接/重复inode、错误access/append以及非空目标；先关复制连接，sync私有snapshot文件，再独立只读重开校验。它只适用于新的单次可信worker，拒绝owner自身PID及已注册VFS的进程；Main仍须认证真实调用者/UID/PID、绑定当前nonce/generation、确认worker退役并持久发布，**形状检查不是源文件授权，函数OK不是退出或发布证明**。宿主对真实文件、分离进程、错误授予和同步/复验故障验证通过，调用方FD保留；Android和生产owner/IPC未验证/未接，不得把备份可写FD发给Agent再声称关闭Main副本可以撤销。

`02fb530` 增加 addon `fdVfsMetadata(slot)`：0..3 仅主库/WAL/SHM/journal，4仅VFS虚拟目录；返回存在、mode、size，不返回目录FD、路径、任意文件内容或inode。主库与侧文件用**已持有FD的fstat**，不另开/关闭别名，避免意外释放本进程POSIX锁；侧文件零长度按原VFS视图为不存在。目录mode0700描述虚拟命名空间，不是实查物理目录的证明，Main原有目录/文件授予检查仍必须执行。原生注册与查询在同一mutex内。

`runtime-sqlite-fs.mjs` 仅替换钉住的旧 `persistence/db.ts` 与 v2 `infra/db/client.ts` 的 `node:fs` 导入。主库逻辑目录和四文件映射到slot；recursive mkdir只验证已授予的私有虚拟目录，chmod只验证原mode等于0700/0600，**不调用fchmod也不把数据权限放宽**。元数据缺失/异常、mode不私有、已关闭均失败；不得退回宿主同名路径。其他路径的原宿主行为保留，这不是通用FS实现或安全沙箱；系统隔离仍由UID及native能力负责。

metadata 属于可信 `nativeSqlite` 装配参数，不进 descriptor、HTTP或设置；FS view 退役后同一Node进程不能将该逻辑根绑定到新代次。正常复启必须创建新的执行单元，不能用旧FS引用借用新授权。

本轮 Linux/Node24 实跑：SQLite binding/FS/starter **29/29**，钉点完整图 **4/4**，真实默认 factory/Bot 冷启动重开和 native SQLite/backup/integrity/metadata 入口 **2/2**。后者metadata/backup仍是宿主能力，底层SQLite与factory是真实的。原C++ WAL/多连接/跨进程锁/崩溃/同步故障回归及新metadata C ABI/V8用例均通过。

更强的FD探针：`python3 dsh-go/tooling/android-node-probe/test-fd-vfs.py --node-probe --runtime-bundle <已prepare的bundle>`，两个独立进程使用真实addon/四FD。无metadata时打开DB前拒绝；有实际FD metadata时通过旧版权限和表初始化，**在 v2 必需的在线备份前明确拒绝**（`LOCAL_RUNTIME_V2_DATABASE_BACKUP_FAILED` / `runtime_sqlite_backup_unavailable`），检查未应用v2 migration、句柄关闭、同代重开拒绝和无路径数据库回退。这个拒绝是验收目标，绝不是full runtime已启动。

FD metadata API 的Android编译/执行后续已由本机 **db7ee66/API35/x86_64** 验收补齐（用户转述，定向8/8、全套58/58，带API直接断言及新adapter来源），见 [设备卡结果](T-Flash-FDMetadataProbe.md)。**仍未验证**：完整FS shim/factory的Android启动、同步/异步FS、native snapshot/备份kernel/worker/owner/IPC/清理与发布、canonical资源/外部模块Android装配、正式launcher、真实模型、产品APK、API26/ARM64/真机。不得删掉上游backup/integrity/preflight或把虚拟目录当物理授权证明。

## 官方Bot Store受控持久化候选（Android验收接线，设备未验证）

`startBotService`可显式使用`fileMode: 'unavailable'`，不创建工作区并要求Store/Memory能力；直接文件相关路由与附件/自定义头像操作在访问filesystem前拒绝。真实Bot和FD socket通过宿主fake文件broker验证，另实测打包为data URL后启动；这为隔离启动准备接缝，不代表Android上已启动真实服务或附件已完成，见[NEXT-AGENT](NEXT-AGENT.md)。

获得Bot数据独占锁后、新guest获得能力之前，native候选只清理严格格式的记忆stage及原子发布stage；扫描/批次有上限，验证私有单链接普通文件后删除并sync，异常项拒绝而不跟随。已接受事务从持久pending记录恢复，宿主分别验证恢复与生产C++清理原语；Android的清理后pending恢复联合用例已编写并编译，但设备未执行。旧`bot-memory-<摘要>.bin`可能是持久数据，不得清理；详细限制和实测见[NEXT-AGENT](NEXT-AGENT.md)。

`RuntimeFileCommitHost.openBotState`现为Bot Store提供专用目录上的非阻塞内核协作锁；每次独立打开目录FD争锁，publisher/reader复制同一已锁FD，在途请求与输出排空后关闭所有副本，竞争返回`runtime_data_busy`。原通用文件broker在另一子树，验收脚本已换到新入口。Linux对实际锁原语的跨进程竞争、进程死亡释放与dup生命周期测试通过，双ABI本地交叉编译及验收APK编译通过；**Android设备尚未运行**。锁不能约束未参与协议的同UID写者、移动/替换目录或SQLite；产品launcher、可信写者统一入口和孤儿stage处理仍须完成，详见[NEXT-AGENT](NEXT-AGENT.md)。

后续增加`bot-memory-filesystem.mjs`，在同一个JS文件会话上适配官方MemoryStore的团队/单Bot记忆、pending、journal、备份和archive；原版备份硬链接变成受控读快照+独占publish复制，要求单一可信runtime写者。宿主验证未知提交重启恢复、operationId日志去重、旧字节备份及无物理Bot目录的HTTP记忆读写；Android验收脚本已接入并编译，**设备尚未执行，产品启动器尚未接入**。1MiB单文件、整文件追加成本、native排他和stage清理候选的设备验证以及旧格式不可辨认的孤儿均未解决，详见[NEXT-AGENT](NEXT-AGENT.md)。

Store与MemoryStore共享`bot-file-lease.mjs`：同一会话有界串行写、复制入队数据，任何结果未知使共享方失效，在途读不再返回旧结果；明确的publish已存在及rename/remove不存在保留原语义。该JS对象本身不提供跨进程排他；native候选见上段，正式launcher仍须接入并验证进程回收。

真实官方Store和MemoryStore与生产adapter已嵌入Android固定验收脚本、连接`mbot_files_bridge`并共用同一JS lease；计划在两个独立isolated进程通过Main文件broker检验聊天、定时任务配置、用量、记忆、journal及备份恢复，以及随机Key不入报告/文件。Node候选的验收APK和仪器APK本地构建通过（`BUILD SUCCESSFUL in 58s`）；带Intl的精确脚本在两个宿主进程以磁盘假能力实跑通过（4/4），无Intl分支明确跳过；Python验收工具33/33。**Android设备/模拟器尚未执行**，GitHub计费问题及复现见[NEXT-AGENT](NEXT-AGENT.md)。

真实`Store`现可经宿主注入`bot-store-persistence.mjs`，将state、上游规范化chat JSON和usage ledger映射到已绑定文件能力的固定/摘要basename，保留原Store业务及默认filesystem分支；定时任务配置随state持久化。最多1MiB，写入经最多32项的串行队列，未知结果污染会话并拒绝后续操作，重启回读，不重试或伪称失败未落盘。已知超限虽未发布，也要重载，因为Store可能在保存前已修改内存。`Store.readUsage`现在仅ENOENT为空，其他读错误上抛。宿主使用假文件能力与真实Bot Store/HTTP验证冷重开、聊天隔离、计划配置、未知结果及损坏/上限；不能证明Android文件链路运行成功。媒体、mcode FS、native独占候选的设备和产品接入及超1MiB增长均未解决；完整验收与下一步见[NEXT-AGENT](NEXT-AGENT.md)。

**尚未实现完整runtime FS。** 当前WAL/两进程读取、Main四文件owner已有各自设备证据，不能代替本卡。Mini App可视化程序仍不适配；下述官方Bot的miniapp/node目录是复用其业务后端，不新增Mini App浏览器。

## Main授予的文件提交broker（后续）

Main范围/操作/UID/nonce/peer与pipe提交组件正在接入验收，见[T-RuntimeFiles](T-RuntimeFiles.md)。Node文件JNI/完整FS仍缺；下面只证明Main原子内核与组件。

## 原子替换（30b5249已API26/35通过）

**30b5249/run41767469341 success**，artifact实际元数据：

```text
API26 id13293327695: node-candidate-results-api-26-t21-f0-e0-s0-passed-l0
API35 id13293706961: node-candidate-results-api-35-t21-f0-e0-s0-passed-l0
```

同期native broker41767469344与broker设备41767469369均success。下文“新增/预期21项”是实现当轮记录，设备基线现已取得；新文件IPC候选另查T-RuntimeFiles。

真实调用方store.mjs的state.json和memory.mjs的pending/文档更新使用rename替换；备份/初始JSONL则必须目标不存在。现生产`RuntimeAtomicPublisher.replace`与`publishIfAbsent`显式分开，共用同一有界stage写入/fsync/目录sync和结果未知处理；production JNI以固定入口选择renameat2 flags=0或NOREPLACE，未开放任意flags/syscall。publication-only注入内核默认以ENOSYS拒绝replace，不把不支持操作悄悄降为覆盖。

replace可创建新目标或替换完整旧文件，成功仍返回PUBLISHED；native结果丢失/rename后sync失败返回OUTCOME_UNKNOWN。**替换的未知结果必须由上层revision/operationId恢复协调，不能自动用不同payload重试**。本组件不是CAS/事务或完整JSONL append恢复。

新增四项设备用例：创建/完整替换/空文件/私有权限与旧FD继续读原inode；实际replace后异常及目录sync故障均unknown；publication-only内核拒绝且原内容保持；两个owner并发替换时读者只见完整旧/新文档。原17项保留，预期21项，但新设备结果未取得前不算通过。

**不得用于替换正在授予FD的SQLite物理文件**：它们的inode在lease期间必须不变；当前只是可信Main内部方法，无Agent IPC。完整FS的根/操作授权、只读备份区和可替换状态区仍需绑定。原子替换不撤销旧FD（用例故意验证），不证明断电/Android crash恢复。x86_64/arm64-v8a的生产CMake/JNI/16KiB交叉编译已再次通过，两个验收APK构建原始结果：

```text
BUILD SUCCESSFUL in 2m 40s
71 actionable tasks: 16 executed, 55 up-to-date
```

## 生产native构建接入（8d65ac0已API26/35通过）

**8d65ac0/run41766746821 success**；API26 artifact13293341597、API35 artifact13292863477，名字均`t17-f0-e0-s0-passed-l0`。App同源CMake/JNI类在验收设备中实际执行成功；ARM64仍仅交叉编译，完整Flutter App未构建。同期native41766746732/broker设备41766746813均success。

原验收`file_publication_probe.cpp`已移到App `src/main/cpp/runtime_publication.cpp`，同一renameat2算法注册生产`NativeRuntimePublication.publishNoReplace`。App与Node验收目标均指向同一CMakeLists，NDK27.3.13750724/CMake3.22.1，静态C++ runtime避免增加共享STL冲突，库LOAD为16KiB对齐；R8显式保留JNI类/方法。原probe类与独立probe库删除，prepare会删除旧的生成库。

`RuntimeAtomicPublisher.open`默认绑定生产内核；先做Main UID/worker/预算检查，再加载JNI，加载失败只给稳定UNAVAILABLE，无降级路径。故障测试仍可由可信Main注入内核/sync。现有六项组件/四项内核用例改跑生产类；主用例走默认open。原隔离Node握手新增断言：isolated UID调用默认publisher必须OWNER_ONLY，不能创建目录或加载Main内核后冒充owner。

已实际用`check-native-files.py`从同一生产CMake构建x86_64/arm64-v8a API26库，验证ELF ABI/JNI_OnLoad导出/16KiB LOAD/无共享STL依赖。SDK CMake归档按Google repository摘要核对，本机版本是`3.22.1-g37088a8`，检查允许该固定SDK构建后缀。这**仅证明两个ABI的本机交叉编译**，不是ARM64/真机执行或完整Flutter App构建。Node workflow新增同一双ABI检查；x86_64设备回归仍待新CI。

```bash
python3 dsh-go/tooling/android-node-probe/check-native-files.py \
  --ndk /tmp/mbot-ndk27d --cmake /tmp/mbot-android-sdk/cmake/3.22.1/bin/cmake
```

```text
PASS: production native file kernel x86_64 API26, JNI export and 16KiB LOAD alignment; not device execution
PASS: production native file kernel arm64-v8a API26, JNI export and 16KiB LOAD alignment; not device execution
```

没有新增IPC/Agent接口、生产Node启动器或完整FS；数据库FD lease/撤销、JSONL/MemoryStore调用点与崩溃stage清理仍缺。下面132273a的17项通过是**迁移生产JNI之前**的基线，不能据此提前认定本轮生产JNI设备通过。

生产CMake接入后的两个验收APK已实际构建；仍非完整Flutter APK：

```text
BUILD SUCCESSFUL in 1m 41s
71 actionable tasks: 17 executed, 54 up-to-date
```

prepare-probe/prepare-sqlite按钉点重新执行成功，Python24项与actionlint通过；原broker设备矩阵41765512050现也已实查success。本机仍无adb/模拟器，不把交叉编译或打包冒充设备执行。

## Main原子发布组件（132273a已通过API26/35）

**132273a/run41765512240 success**，API实查artifact：

```text
API26 id13291988246: node-candidate-results-api-26-t17-f0-e0-s0-passed-l0
API35 id13292912205: node-candidate-results-api-35-t17-f0-e0-s0-passed-l0
```

新增六项与原十一项均通过，包括数据库目录重构回归。同期native broker CI41765512039通过；broker设备矩阵41765512050结论另查。

生产`RuntimeAtomicPublisher`只在Main worker使用，根固定在noBackupFilesDir/minimax-bot-runtime，子目录由可信Main策略选择。严格basename、最多8级、默认1MiB/最大8MiB；创建0600且O_EXCL/NOFOLLOW的随机stage，完整写入并fsync，然后调用可信注入的独占发布内核，最后同步目录。所有异常只暴露稳定code，诊断无路径/内容/cause。没有新增Binder/MethodChannel/Agent文件入口。

结果明确区分`PUBLISHED`（rename与目录sync均返回成功）、`ALREADY_EXISTS`（不覆盖已有目标，不承诺其内容或持久性）、`OUTCOME_UNKNOWN`（native调用进入后结果丢失，或rename后目录sync失败）。未知不能改报拒绝或确定提交；重试不能覆盖已存在目标。已知内核errno拒绝为稳定UNAVAILABLE，清理本次拥有的stage，不删除目标。进程崩溃遗留stage的恢复/清理仍未实现。

`RuntimePrivateDirectory`抽出原数据库owner的目录检查/祖先目录同步：目录能力始终留在Main。原数据库固定四文件、权限/链接/inode检查不变；需要原七项设备回归，不能仅凭重构编译成功认定没有回归。目录原始FD关闭失败时，不把dup交出并泄漏。

新增六个设备用例，当轮注入原真实JNI探测：发布/重复不覆盖、native实际发布后抛异常、rename后sync异常、输入/预算/UI拒绝、已知ENOSYS拒绝清理与空文件、两个独立owner并发一winner。原十一项保留；本轮已真实17项通过。Python报告器新增固定`RuntimeAtomicPublisherTest`/`laN`与invalid_data，不打印注入故障正文或私有路径。

本轮实际运行Python24项、精确APK JS host1项、scope4项，均通过；actionlint1.7.12检查Node候选/source两workflow通过（禁用外部shellcheck/pyflakes），git diff --check通过。组件最终构建命令与原始结尾：

```bash
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk \
/tmp/mbot-gradle/gradle-9.3.1/bin/gradle --offline --no-daemon \
  -g /tmp/mbot-gradle-cache -p dsh-go/tooling/android-node-probe \
  -Dorg.gradle.jvmargs=-Xmx1g assembleDebug assembleDebugAndroidTest
```

```text
BUILD SUCCESSFUL in 1m 21s
69 actionable tasks: 12 executed, 57 up-to-date
```

**132273a时的边界**：组件逻辑位于生产源码，native内核由验收APK注入；生产JNI迁移见最新节。尚未绑定启动器、runtime FS协议、原JSONL/MemoryStore或Agent身份。close仅关Main本地目录FD，不是Agent撤销或SQLite lease。物理真机/ARM64/断电均未验证。

## 原子发布设备结果（最新）

**7406bb3/run41761635832已API26/35全部success**；实际artifact元数据：

```text
API26 id13291626069: node-candidate-results-api-26-t11-f0-e0-s0-passed-l0
API35 id13291012496: node-candidate-results-api-35-t11-f0-e0-s0-passed-l0
```

四个Main出版探测+此前七项全通过，证明当前模拟器中renameat2(NOREPLACE)可用，已有目标保留、并发恰好一winner、完整目标和输入拒绝断言执行成功。目录只在Main持有，没有权限放宽或Agent入口。这仍不是完整FS/一般link语义，也不是物理真机/ARM64/断电证明。

另在宿主临时用精确Kotlin native入口和同一C++源码编译JNI库，验证JVM注册、独占发布、EEXIST原内容保留和穿越拒绝；这是宿主辅助证据，设备证据以上述run为准。

## 实查到的调用方

mcode源码是UPSTREAM-RUNTIME.md的完整commit `97818b2890b4acae1c6bd1200a226ff506680628`；官方mbot保持vendor快照不改。

| 调用方 | 实际路径/行为 | 移动端仍需满足 |
|---|---|---|
| mcode v2 `infra/db/client.ts` | `v2/sqlite/runtime-state.sqlite`，mkdirSync、WAL/busy_timeout/foreign_keys、online backup、独立只读integrity | 正式模块注入、完整路径/备份；仅四FD原型不等于整个client已运行 |
| mcode v2 `infra/file/jsonl.ts` | readFile/open/write/sync/truncate/stat、同目录临时文件、link发布目标不存在的完整文件、rename重试 | FileHandle语义、确定/不确定提交状态、原子不覆盖发布、所有写者协调 |
| 官方mbot `store.mjs` | state.json、messages目录、usage.jsonl；临时JSON→rename原子替换，串行writeChain | 受控mkdir/read/write/append/rename/rm，完整状态与历史，不改为周期内存快照 |
| 官方mbot `memory.mjs` | memory/*.md、journal.jsonl、.pending.json、backups/archive；先COPYFILE_EXCL到stage，再link独占发布备份，pending恢复后完成文档与日志 | 不可跳过备份或pending恢复，不可用覆盖式copy伪装link语义 |
| 官方mbot `server.mjs` | uploads/avatars二进制流、临时上传rename、stat/realpath；静态assets只读 | 文件/流预算、媒体路径守卫、只读APK资源与可写data分开 |

下一位必须从这些真实调用点设计FS协议，不能仅把WorkspaceBroker的read/write/list三个文本操作冒充完整node:fs；也不能给isolated进程目录FD后假定可访问Main路径。同步FS调用不能阻塞Android UI线程。workspace、runtime-data、只读assets应由可信宿主绑定不同能力，任何能力都不能覆盖Key prefs/Keystore或整个filesDir。

## hardlink不是一个可忽略的兼容错误

Main owner设备用例首次在Os.link构造hardlink时失败，改后只接受EPERM/EACCES这一平台拒绝并验证目标未创建、源内容不变；如果平台允许则实际验证owner的nlink拒绝。即使安全边界因此得到保护，**业务依赖的原子发布仍要适配**。

实读memory.backupScope及jsonl.publishFileIfAbsentWithLabel/publishInitialJsonl：这里使用link是为了完整临时文件的“目标已存在则不覆盖”发布，且之后清理临时文件。不能无条件把所有fs.link替换为rename：一般link保留源、共享inode，rename会移动源；复制也不自动具备独占/原子/崩溃语义。未来应为这类固定调用点提供明确的publish-if-absent能力，并用原调用方跑恢复/冲突测试。

## 当前新增：仅验收APK里的renameat2探测

独立AGPL JNI `file_publication_probe.cpp` 在**Main中**以其已有私有目录FD+两个严格basename调用renameat2(RENAME_NOREPLACE)，只回0或errno。校验目录类型/所有者、拒绝绝对/穿越/斜线；目录FD不交给isolated UID。没有shell、任意根路径、宽权限、Binder/MethodChannel/Agent入口。代码仅在tooling验收APK，未作为生产FS API开放。

四个设备用例：完整stage发布+Main同步目录、已有目标不覆盖且源保留、两worker竞争恰好一个成功且目标为完整内容、穿越/普通文件冒充目录拒绝。先同步stage，再rename，随后同步目录；探测不把移动当通用link，也不宣称已解决存储提交结果不确定问题。返回ENOSYS/EPERM等必须失败报告，不能当“兼容通过”。

新JNI经NDK27d/API26/x86_64编译，两个APK实际构建：

```text
BUILD SUCCESSFUL in 2m 7s
69 actionable tasks: 16 executed, 53 up-to-date
```

Python23项过（新增errno/固定文件名元数据不泄露）；host固定脚本与workflow检查结果见本轮记录。**本probe已在顶部API26/35真实通过；不扩大为所有Android/OEM支持，更不替代生产FS。** 原owner7项与模型/Key/WAL用例保留。

report-results允许固定FilePublicationProbeTest位置（artifact后缀lpN）及0..4095 errno整数，除此之外仍不打印异常正文/路径/env/响应。新probe输出不包含用户数据或凭据。

## 接着做

1. Main原子组件已30b5249/41767469341两版本各21项通过；范围/操作/真实UID文件broker见T-RuntimeFiles，接着查其独立25项候选，再接Node binding及完整FS；不开放泛化syscall。
2. 然后设计Main持有的专用runtime-data FS能力与提交/恢复语义，再适配固定JSONL/MemoryStore调用并跑原实现测试。还需明确读写预算、流、原子替换、exclusive publication、所有写者同步和退出生命周期；目前没有这些生产接口。
3. 完整online backup、JSON/history、uploads/assets与模型配置安全投影都仍缺；不能因数据库owner或本探测通过就切正式入口。
4. 官方server.mjs确实创建HTTP server并listen；isolated UID的UI传输也需独立native桥接（未实现）。只在宿主临时验证过HTTP server可通过connection事件处理已连接Unix FD而不listen，不是Android或完整官方后端已接通。
