<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeFiles：Main授予的有界文件提交能力（2026-11-04）

**Main受控读取/size已a96d916在API26/35各30项通过；新增Node读取JNI已编译，独立30项候选待CI。不是完整node:fs、官方Bot已接通或SQLite FD撤销。** 原子发布/替换内核及21项设备基线见[T-RuntimeFS](T-RuntimeFS.md)。不修改vendor，不回退同UID Agent/shell。

## Node读取JNI（最新，设备待CI）

验收linked binding追加read/size，复用同一最多4任务池，经隔离Kotlin client→Main已授权READ/SIZE。read仅把有界pipe内容转为独立Uint8Array；size转为安全整数范围的Number。Java异常、未知回复归一化；查询无写入副作用，错误不伪装提交unknown。最大1MiB、稳定missing/invalid/denied/closed/too_large/unavailable；无Root/Key/nonce/真实文件FD交给JS。

Main为原Node两PID验收的state scope显式allowRead。固定JS现在必须读回84KiB二进制与原始view快照/空文件、查询两个大小，确认missing和路径拒绝。Main断言新增fileReads字段及原目录/内容/Key/env/descriptor/log/session不泄露，不能通过跳过新branch来保持30项计数。原模型/WAL/输入/输出drain与父目录替换测试保留。

重准备JNI/SQLite成功，Python27/精确APK host1通过；两个验收APK实际构建：

```text
BUILD SUCCESSFUL in 1m 25s
71 actionable tasks: 17 executed, 54 up-to-date
```

这是验收JNI，不是完整生产Node生命周期或node:fs。须查新commit的30项结果，不能套下面a96d916的同计数旧run。原JSONL/MemoryStore、append/FileHandle/目录管理/backup、model resolver/launcher仍缺。

## a96d916设备结果（Main读取/size）

**a96d916/run41774204029 success**，实查artifact：

```text
API26 id13296805584: node-candidate-results-api-26-t30-f0-e0-s0-passed-l0
API35 id13296248786: node-candidate-results-api-35-t30-f0-e0-s0-passed-l0
```

Main→isolated FIFO读回、拒绝/大小、未消费输出的close/deadline释放，以及新五项和旧25项均通过。本run没有Node READ/SIZE JNI；后文pending是当轮实现记录。

## 有界读取与文件大小（a96d916已设备通过）

v1追加READ/SIZE，Main factory必须显式`allowRead=true`，默认关闭，且不能由请求扩大权限。仍只在files/<Main scope>内接受单个严格basename，不读stage、Key prefs、workspace或SQLite目录。SIZE仅返回已授权普通文件的长度，不是通用stat/realpath，不返回inode/UID/真实路径；目录/特殊对象拒绝。

Main `RuntimeFileSnapshot`通过生产JNI `openat`以**持有的Main目录FD**相对打开，固定O_RDONLY/CLOEXEC/NOFOLLOW/NONBLOCK，绝不按父目录路径重新解析；目录cap的withDescriptor锁防止close期间FD被回收复用。文件必须REG/Main owner/无group-world权限/nlink<=1；允许已经打开后被原子替换而unlink的旧inode，保证完整旧/新版本，不把旧FD说成被撤销。新增missing/too_large稳定code，异常无cause/路径/正文。Main可读上限由可信policy给出；wire仍最多1MiB。

READ只向隔离端传**匿名pipe读端**与长度，不传原文件或目录FD。Main快照producer全进程最多4个，有界executor，nonblocking poll与绝对截止/close检查；不消费的接收方不能永远卡住writer。Main先设置接收pipe的NONBLOCK，接收端仅在尚未设置时才改flags。client按声明长度/EOF读取并拒绝截断/多余，不返回部分成功。close/drain同时等待已admit调用与outgoing producer；已经交付/缓存的授权字节不能“收回”。普通磁盘read/fsync本身卡死仍不是可即时打断的保证。

新增五项设备测试（原25项保留，预期30但不能提前算过）：四个Main snapshot测试覆盖二进制/空/缺失、symlink/private mode/目录/上限、**目录被改名并由外部symlink替代后仍只读原cap**、并发原子替换下只见完整版本；一个跨实际isolated UID的未消费输出测试分别验证close与自然deadline释放sender资源。原Kotlin矩阵扩展84KiB读回/size/空/权限与缺失拒绝。硬链接nlink拒绝为实现检查，本轮没有构造成功hardlink的新增证据。

Node文件**读取**JNI尚未接，当前Node用例仍只提交文件。生产native x86_64/arm64-v8a API26、JNI导出及16KiB检查通过；Python27/actionlint通过。两个验收APK实际构建：

```text
BUILD SUCCESSFUL in 2m 11s
71 actionable tasks: 20 executed, 51 up-to-date
```

不提供append/FileHandle/目录管理/通用rename；已有publisher stage创建仍要求可信Main不在lease中替换其父目录。不能把新openat读取测试当作所有写入路径的父目录竞态证明。正式FS、原模块恢复和launcher另做。

## a49d79b设备结果（Node提交已通过）

**a49d79b/run41771737494 success**，实查artifact：

```text
API26 id13295562153: node-candidate-results-api-26-t25-f0-e0-s0-passed-l0
API35 id13295207316: node-candidate-results-api-35-t25-f0-e0-s0-passed-l0
```

含新增Node实际文件提交、view快照/非法buffer、两PID与扩展Key断言；该版本还没有READ/SIZE，不能用于证明本轮读取候选。

## Node文件提交JNI（a49d79b已设备通过）

验收APK新增固定linked binding `mbot_files_bridge` 的publish/replace；从JS异步Promise→最多4个libuv任务→隔离Kotlin RuntimeFileCommitClient→原生产Main host。JNI只接basename与Uint8Array，不接根、Key、nonce、URL或命令；Main分配的capability/session只留在隔离Kotlin，不进argv/env/JS descriptor。结果与稳定错误有限映射，Java异常正文从不回JS；JNI调用已发生但结果丢失则unknown。

native在异步排队前复制view的精确offset/长度，1MiB上限；拒绝SharedArrayBuffer/resizable/detached buffer及错误类型，避免后台读取可变共享内存。验收仍是固定APK脚本，不把此JNI描述为正式生产Node启动器；线程池/退出行为需要未来launcher一并装配。

原Node/Key/WAL用例现在每个新PID必须执行文件branch：84KiB offset view提交后立即改原数组，Main必须读回修改前快照；创建/重复不覆盖/替换/空文件；第二PID仍见同一三文件且发布返回already_exists。Main检查精确内容、无额外stage/非法文件，并扩展Key检查到新文件，Node报告不得含file session、native model session、Key；原descriptor/env/log/SQLite文件断言全部保留。六种非法view/路径必须拒绝。

仪器测试数仍25，**不能仅凭旧10be45f的t25就说新Node branch已过**，须核对新commit/run。C++初轮因两个NODE_MODULE_LINKED宏在同namespace生成同名_module而编译失败，已按实际钉点node.h将第二个注册隔到namespace，未更改模块名或上游header；重准备JNI/SQLite成功。Python26/精确APK host1/actionlint通过；最终两个验收APK原始结尾：

```text
BUILD SUCCESSFUL in 1m 47s
71 actionable tasks: 17 executed, 54 up-to-date
```

本地无adb/KVM，不伪造Node文件设备执行或ARM64/真机结果。完整FS、原JSONL/MemoryStore、read/stat/list/append、Bot/MCP身份、model resolver、正式launcher仍缺。

## 10be45f设备基线（Kotlin文件broker）

**10be45f/run41769936256 success**，实查artifact：

```text
API26 id13295035591: node-candidate-results-api-26-t25-f0-e0-s0-passed-l0
API35 id13294617445: node-candidate-results-api-35-t25-f0-e0-s0-passed-l0
```

四项新broker用例与旧21项均通过；包括active close/drain、84KiB二进制、身份/权限/输入拒绝与原Key/模型/SQLite回归。同期native41769936291、broker设备41769936352均success。该版本尚未接Node文件JNI，旧pending文字为实现过程记录。

## 授权边界

可信Main launcher先观察隔离服务的真实UID/Binder，然后调用`RuntimeFileCommitHost.open(context, scopeId, isolatedUid, peer, allowReplace)`。只能在Main worker创建；同Main UID、无效scope或预算拒绝。根固定为：

```text
context.noBackupFilesDir/minimax-bot-runtime/files/<Main选择的scopeId>/
```

即使scopeId为v2也不会覆盖`v2/sqlite`，Key prefs/Keystore及workspace均不在此子树。scopeId不是Agent参数，目录FD/真实根路径不传隔离端。客户端只提交严格basename；不支持子目录/绝对路径/穿越/保留stage前缀。Main默认仅授予目标不存在发布，必须显式allowReplace才可替换；备份区不能因为Agent选了REPLACE就获得覆盖权限。

每个host绑定真实Binder callingUid、随机64hex会话和peer死亡。不信任请求中的UID、root或scope，也没有这些协议字段；session仅交隔离Kotlin客户端，不进Node环境/argv。10be45f验收只接隔离Kotlin矩阵；新增Node文件JNI见顶部，仍没有正式launcher分配这些host。

## v1协议与结果

`RuntimeFileCommit.v1` descriptor；两个transaction PUBLISH/REPLACE。请求为session、basename、声明字节数、一个pipe读端FD。控制Parcel最多8KiB；正文最大1MiB，一律经匿名pipe传输，不把文件塞进Binder。文件是二进制字节，不套模型UTF8/JSON解码规则。

UID/session/操作/名称/长度/admission检查均在分配正文缓冲及文件创建前。每host最多4个已admit调用；客户端进程最多4个pipe writer及有界executor。FIFO类型检查、nonblocking poll、声明长度恰好读满并要求EOF，截断/多余字节/非pipe拒绝；默认绝对5秒读取截止，每次最多100ms观察close。读取截止不代表能中断存储fsync。

稳定结果：published、already_exists（仅PUBLISH）、outcome_unknown；稳定拒绝仅RuntimeDataCode白名单，平台异常、正文、私有根、会话不回显。客户端在transact开始后遇到false/异常/未知或畸形回复，保守返回OUTCOME_UNKNOWN，不能说没有写入。已明确的Main拒绝才抛稳定RuntimeDataFailure。没有自动重试，也没有以replace降级publish。

Main内部仍由RuntimeAtomicPublisher先同步完整stage，再选独占发布/原子替换，最后同步目录。OUTCOME_UNKNOWN的恢复/operationId/revision协调仍由未来真实调用方完成；没有CAS、append或跨文件事务。

## close与生命周期

close立即关闭新admission，正在读pipe的调用观察close后拒绝；已经进入文件提交的调用可能完成，不能宣称rollback。待全部已admit调用退出后才关闭Main目录副本；whenRetired可观察实际drain，closeAsync发起关闭并等待同一事实，返回future视图不把内部completion交出。peer死亡自动走同一路径；失败只用稳定code。

**此host不向Agent授予文件/目录FD**，不能据此说先前SQLite四FD已被撤销。SQLite执行单元/子进程/FD转发与完整lease问题保持独立；不能对已leased DB inode调用replace。

## 共享pipe读取与验收

原NativeModelRequestHost的有界pipe循环抽为`BoundedPipeReader.kt`；模型仍额外执行严格UTF8、JSON预算、provider/model绑定与原取消code映射，未改模型wire或Key处理。新文件路径复用二进制循环，避免另一套长度/EOF/截止实现。

新增四项仪器用例：

1. Main实际绑定isolated UID/peer，两个不同scope+只允许发布/允许替换权限，84KiB非UTF8二进制pipe与空文件；Main自身拿正确nonce仍拒绝、伪造nonce/路径/越过客户端校验的穿越/非pipe/截断/多余/超限/停滞/已close拒绝；Main校验原备份、精确新内容、目录无多余文件。peer死亡后不额外调用close，等待真正retirement，再确认数据保留。
2. client面对transport false/异常/私有未知回复均unknown，不暴露异常正文。
3. factory无效scope/同UID，以及client输入/超限/UI线程，在IO或IPC前拒绝。
4. 隔离端发起未写完的pipe，Main通过内部shutdown计数确认已admit，再closeAsync；必须在3秒内结束（读取自然截止为10秒），客户端收到CLOSED、目标不存在、inFlight=0且peer仍活着。此Main计数不进Agent协议；close与commit开始在同一锁下排序。

测试连接helper从原Node用例原样抽出并保留Binder死亡等待；原模型/Key/WAL两新PID及原子提交21项保留，新候选预期25项，实际计数必须查CI。report-results只加固定RuntimeFileCommitHostTest/行号lcN及有限matrix阶段，不输出原文。Python25项与actionlint过；本地宿主JUnit32/Node44全过，broker组件JVM XML实查32项零失败/错误/跳过。SDK9/pi-ai8此轮未在本地重跑，CI按触碰范围会运行。

当前真实工具命令与原始构建结尾（路径须按接手环境调整）：

```bash
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk \
/tmp/mbot-gradle/gradle-9.3.1/bin/gradle --offline --no-daemon \
  -g /tmp/mbot-gradle-cache -p dsh-go/tooling/android-node-probe \
  -Dorg.gradle.jvmargs=-Xmx1g assembleDebug assembleDebugAndroidTest
# broker同一工具，-p改android-broker-probe，任务加testDebugUnitTest
MBOT_NATIVE_DEPS=/tmp/mbot-native-deps KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc \
  bash dsh-go/tooling/test-native-model-broker.sh
```

```text
# Node目标，最终含drain测试
BUILD SUCCESSFUL in 1m 8s
71 actionable tasks: 13 executed, 58 up-to-date
# broker目标（在最终Main文件close排序/计数改动前，模型shared reader相同）
BUILD SUCCESSFUL in 3m 4s
73 actionable tasks: 13 executed, 60 up-to-date
OK (32 tests)
# tests 44
# pass 44
# fail 0
# skipped 0
```

没有本地adb/KVM/真机，以上是编译与宿主检查，不是设备通过；ARM64执行、完整Bot APK、真实模型、存储fsync卡死/断电未验证。新增`.kotlin`/`.cxx`生成物忽略，不提交缓存或库。

## 后续

1. Main读取a96d916已API26/35各30项过；先查新增Node READ/SIZE那一commit的独立30项，不能混用旧run。
2. 原publisher stage创建/清理仍按可信父目录路径，下一步在扩展目录操作前迁到Main目录FD下的固定openat/unlinkat，补父目录被替换时的写入测试；不开放任意flags/路径或目录FD给Agent。
3. 再扩展真正受控FS所需的read/stat/list/mkdir/append/FileHandle与全写者协调，适配原JSONL/MemoryStore并运行原恢复测试；不要全局把link改成rename。
4. full-ICU/正式launcher/model resolver、可信Bot身份与SQLite lease另见HANDOFF，仍未完成。
