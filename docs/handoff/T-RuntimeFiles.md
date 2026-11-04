<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeFiles：Main授予的有界文件提交能力（2026-11-04）

**Node读取/size已585f082在API26/35各30项通过；stage FD改造c78faca已两版本31项过（API35首次0结果失败、重跑通过）。Main move/remove已42fba3a两版本35项通过；本轮新增显式权限IPC，设备待新CI。不是完整node:fs、官方Bot已接通或SQLite FD撤销。** 不修改vendor，不回退同UID Agent/shell。

## 有界追加与同目录写者协调（本轮，40项设备待新CI）

为后续Store/MemoryStore日志增加Main append组件，并通过APPEND二进制pipe与固定Node JNI接入验收。Main必须显式allowAppend（默认false），不从allowReplace或PUBLISH推导，Agent仍不能选根/flags/目录。append只保留原前缀并追加完整二进制，缺失时创建，空追加可创建空文件；包括原文件的**总大小最多1MiB wire policy**，超限返回TOO_LARGE且不改原文件。注入publication-only kernel不能暗中获得替换支持。

**这里是有界整文件原子追加，复杂度O(文件大小)，每次发布新inode；不是O_APPEND、通用FileHandle、无界JSONL或原模块已移植。** 适合此阶段小文件候选；不能替换leased SQLite物理文件，不能拿它模拟原JSONL的同步句柄/部分写/truncate契约。未知结果不自动重试，否则可能重复日志；原调用方operationId/恢复仍待实现。

新Main-only RuntimeDirectoryMutationLock按持有目录FD的(dev,ino)协调所有RuntimeAtomicPublisher实例，publish/replace/move/remove/append均加入同一目录gate。append在同一gate内读取→合并→完整stage/fsync→原子替换→目录sync，避免两个owner丢更新；不同目录不共享gate。lease按live owner计数，最后close移除entry，目录真实FD仍不外发。只协调这些Main API调用，**不是排斥其他进程/绕过该API直接native写者的文件锁**，正式launcher必须统一所有写者。目录路径改名后仍按持有cap/inode操作，不重新按路径选锁。

五新增仪器用例（35→40）：二进制/空/旧FD/总预算/UI/关闭；两个owner并发24记录无丢失且关闭一个不影响另一个；阻塞append的内核窗口后同目录replace须等待、另一个目录可继续；实际append后结果丢失及sync故障unknown且不重试；拒绝symlink/目录/宽权限/越界并在父目录替换后仍只追加原cap。原真实isolated UID矩阵扩展APPEND、覆盖权不含追加、nonce/close/transport unknown；Node两新PID必须实际追加二进制/空文件/总量超限不改数据/越界，再清理临时文件，原三文件/模型/Key/WAL断言保留。

Python报告器新增固定RuntimeFileAppendTest/lbN与file_append有限phase，Python29通过；actionlint1.7.12检查修改的Node候选workflow通过（外部shellcheck/pyflakes未运行）；钉点Node JNI/SQLite已重准备，精确APK host1/scope4通过。host1首次命令工作目录带了重复dsh-go前缀而找不到文件，纠正为`cd dsh-go && node --test tooling/android-node-probe/probe-bundle.test.mjs`后通过；不把错误入口算作测试执行。同一恢复工具链offline两个验收APK已成功：

```text
BUILD SUCCESSFUL in 2m 34s
71 actionable tasks: 21 executed, 50 up-to-date
```

40项设备CI结果待新commit，不提前记通过；本地没有adb/真机。Node候选workflow显式加入新coordinator文件触发路径，不改full-ICU producer五输入。

## c1b9aee设备结果（显式move/remove IPC）

run41779079402 **success**，API实查：

```text
API26 id13298915795: node-candidate-results-api-26-t35-f0-e0-s0-passed-l0
API35 id13299245445: node-candidate-results-api-35-t35-f0-e0-s0-passed-l0
```

真实isolated UID新增权限组合/移动/删除/拒绝与unknown及旧35项通过；同期native41779079439、broker设备41779079401、scope41779079436均success。该版本尚无Node移动/删除入口（后续c8e702d），也没有本轮append。

## Node move/rename/remove JNI（本轮，设备待新CI）

验收linked binding追加三个固定异步方法：move(source,target)独占移动，rename(source,target)覆盖移动，remove(name)仅删普通文件。仍复用最多4个libuv任务与隔离Kotlin client→Main授予的文件cap；不传根、任意flags、目录FD、Key或nonce给JS。source/target仅字符串与有限长度/arity，Main再做完整basename/身份/操作权限检查。JNI进入mutation后结果丢失仍返回outcome_unknown，不能错误沿用查询的unavailable；remove只回removed或unknown，有限已知拒绝用code。

固定APK脚本在**两次新PID**各创建临时文件→独占移动→读回/原名缺失→冲突保留双方→rename替换→读回→remove→缺失，并拒绝越界/同名/类型/arity/保留前缀；Main新增必需fileMutations字段与六项拒绝断言，精确目录最终仍仅原三文件。原六次模型认证注入、SSE/取消、Key/session/descriptor/env/log/实际文件与WAL两进程检查保留。只是固定脚本验收，不提供正式node:fs或生产launcher；不把通用link别名为move。

当前已按钉点重准备Node JNI与SQLite addon，Python28/精确APK host1与git diff --check通过；同一恢复工具链、`/home/agent/.gradle`缓存，offline两个验收APK成功：

```text
BUILD SUCCESSFUL in 1m 11s
71 actionable tasks: 17 executed, 54 up-to-date
```

无本地adb/真机。设备仍须查本轮新commit/run，不能使用下面c1b9aee或42fba3a的35项证明Node新分支执行。

## move/remove显式IPC授权（c1b9aee设备通过，以下为当轮实现记录）

v1追加MOVE/MOVE_REPLACE/REMOVE，不携带文件正文或目录FD。Main默认不授予移动/删除；MOVE要求allowMove，MOVE_REPLACE同时要求allowMove与allowReplace，REMOVE只由allowRemove授权。读取权、覆盖权、发布权均不能推导删除权。Main仍先核对真实isolated UID、nonce、控制Parcel预算、严格basename/保留前缀/不同源目标/无尾随字段，再admit操作；close之后拒绝，已进入mutation仍允许完成并drain。

client对新mutation沿用保守结果：transact开始后false、异常、未知/多余回复为OUTCOME_UNKNOWN；Main已知拒绝才抛有限code。删除有REMOVED或OUTCOME_UNKNOWN，没有自动重试。重用同一结果解析器，原byte-array提交仍经有界pipe。

原真实isolated UID矩阵扩展成功移动、独占冲突保留source、覆盖、删除/缺失；额外两个同scope grant分别只允许移动、只允许覆盖，证明覆盖移动必须同时有两种授权，并保持普通移动可用。publish-only不能移动/删除；越过client的非法源/目标、REMOVE尾随字段、伪nonce、close均拒绝。transport false/异常/未知回复覆盖三种mutation，原模型/Key/两PID/WAL与35项计数保留。新增检查不等于Node已接入，更不等于完整fs或正式launcher。

本轮实际Python28、精确APK JS host1、scope4与git diff --check通过；恢复环境后首次offline因插件缓存metadata不完整失败，改用系统proxy/CA解析依赖后两个验收APK构建成功：

```text
BUILD SUCCESSFUL in 3m 10s
71 actionable tasks: 15 executed, 56 up-to-date
```

实际工具：Temurin21.0.13+9 `/tmp/mbot-jdk21`、Gradle9.3.1 `/tmp/mbot-gradle/gradle-9.3.1/bin/gradle`、SDK `/tmp/mbot-android-sdk`，缓存`/home/agent/.gradle`。除原任务参数外加`-Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`，不再用旧/tmp缓存。SDK仍提示未安装platform-tools，assemble成功，不伪造许可或设备执行；没有本地adb/物理真机。当前新增IPC须等新commit的CI，不能引用42fba3a当它已过。

## 42fba3a设备结果（Main move/remove组件）

run41777415973 **success**，API实查：

```text
API26 id13298390227: node-candidate-results-api-26-t35-f0-e0-s0-passed-l0
API35 id13297479533: node-candidate-results-api-35-t35-f0-e0-s0-passed-l0
```

同期native41777415976、broker设备41777415932、scope41777415965均success。这份证据只有Main组件，没有本轮新IPC。

## Main普通文件move/remove（42fba3a设备通过，以下为实现当轮记录）

原Store/MemoryStore/JSONL真正需要rename与rm，不能只靠完整byte-array发布。Main组件增加同目录move（独占或替换）和普通文件remove：严格basename/保留前缀拒绝，Main目录cap与worker，source必须私有REG/Main-owned，symlink/目录/宽权限拒绝。remove内核用fstatat(AT_SYMLINK_NOFOLLOW)检查，unlinkat固定flags=0，不递归、不删目录；publication-only注入内核默认ENOSYS，不隐式授权删除/覆盖。

move成功后source消失、target完整；独占冲突保留双方。remove成功为REMOVED，已知缺失为NOT_FOUND；native结果丢失/操作后目录fsync失败均OUTCOME_UNKNOWN，不自动重试/回滚。这些方法**尚无Agent IPC/Node入口**，需后续Main显式授予移动/删除权限，不能从PUBLISH权限推导source删除权。所有此类mutation不得作用于leased SQLite物理inode。

新增四项设备用例：move冲突/替换/旧读FD/删除/缺失；实际mutation后结果丢失与sync故障；symlink/目录/宽权限/非法名拒绝且外部哨兵不变；publication-only kernel不能覆盖/删除。原31项保留，新候选35项未获结果前不算通过。reporter新增固定RuntimeFileMutationTest/lmN，源位置枚举集中为固定字典，不打印异常正文；Python28及双ABI生产CMake/JNI/16KiB通过。

```text
BUILD SUCCESSFUL in 2m 25s
71 actionable tasks: 16 executed, 55 up-to-date
```

不是完整POSIX rename：同名输入拒绝、目录不支持；通用跨目录、append/FileHandle/多写者协调及原调用方恢复测试另做。

## c78faca设备结果（stage目录FD）

run41776354659首次attempt：API26已success，artifact13297083049 `node-candidate-results-api-26-t31-f0-e0-s0-passed-l0`。API35 **failure**，artifact13297635503 `node-candidate-results-api-35-t0-f0-e0-s0-unclassified-l0`；不能说31项失败，也不能说通过。emulator step05:00:35→05:01:08、没有XML；check126397527852只给exit1/sh failed与计数0，没有足够原因，**不推断是代码、模拟器、Ubuntu迁移或flaky**。

已执行`gh run rerun 41776354659 --failed`，只重跑失败job；attempt2已success，新增artifact13297459617 `node-candidate-results-api-35-t31-f0-e0-s0-passed-l0`，API26沿用首次31项。首次0结果失败仍按上文记录。原signed Azure日志下载受限，不回显签名URL；先看job/check/有限artifact元数据。后续35项也保留所有stage测试，可提供另一个commit的证据，不能涂掉本次失败。

## stage创建/清理改用Main目录FD（最新，设备待CI）

RuntimeAtomicPublisher不再按directory.file路径创建/清理stage。生产JNI增加固定openStage/removeStage：只能保留前缀basename，openat以WRONLY/CREAT/EXCL/NOFOLLOW/CLOEXEC和0600创建，unlinkat仅删stage，UID/目录类型仍检查，永不接受任意flags/根路径。FD只由Main adopt并在写入/fsync后关闭；发布与目录sync也仍对同一目录cap。创建失败不删除别人的stage，清理失败不覆盖已提交或未知结果。

新增用例：先持有两个publisher，再将路径改名并以外部目录symlink替代；独占发布、替换、冲突和注入ENOSYS后的stage清理必须全部留在原目录，外部哨兵与目录成员不变。原内核拒绝用例另断言stage helpers不能覆盖/删除普通已提交文件。原30项保留，新候选31项，需新CI。Main读取旧cap已由前轮测试证明，本轮补的是写入stage路径，不把它说成一般目录重命名/完整FS。

双ABI生产CMake/JNI/16KiB与Python27通过；最终两个APK含stage-helper拒绝断言：

```text
BUILD SUCCESSFUL in 35s
71 actionable tasks: 7 executed, 64 up-to-date
```

没有本地adb/真机，设备待新run。崩溃遗留stage清理/JSONL恢复仍缺；没有Agent目录FD或Stored-file FD、任意shell、宽存储权限。

## 585f082设备结果（Node读取/size）

**585f082/run41775322136 success**，实查artifact：

```text
API26 id13297081597: node-candidate-results-api-26-t30-f0-e0-s0-passed-l0
API35 id13297031642: node-candidate-results-api-35-t30-f0-e0-s0-passed-l0
```

Node双PID实际读回、大小/错误、原文件提交/Key/WAL与Main读取全部通过。仍为固定脚本/假provider，不是完整Bot或生产启动器。

## Node读取JNI（585f082已设备通过）

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

可信Main launcher先观察隔离服务的真实UID/Binder，然后调用`RuntimeFileCommitHost.open(context, scopeId, isolatedUid, peer, allowReplace=…, allowRead=…, allowMove=…, allowRemove=…, allowAppend=…)`。只能在Main worker创建；同Main UID、无效scope或预算拒绝。根固定为：

```text
context.noBackupFilesDir/minimax-bot-runtime/files/<Main选择的scopeId>/
```

即使scopeId为v2也不会覆盖`v2/sqlite`，Key prefs/Keystore及workspace均不在此子树。scopeId不是Agent参数，目录FD/真实根路径不传隔离端。客户端只提交严格basename；不支持子目录/绝对路径/穿越/保留stage前缀。Main默认仅授予目标不存在发布，必须显式allowReplace才可替换；allowRead/allowMove/allowRemove/allowAppend也默认false，覆盖移动需要allowMove且allowReplace。备份区不能因为Agent选了mutation opcode就获得额外权限。

每个host绑定真实Binder callingUid、随机64hex会话和peer死亡。不信任请求中的UID、root或scope，也没有这些协议字段；session仅交隔离Kotlin客户端，不进Node环境/argv。10be45f验收只接隔离Kotlin矩阵；新增Node文件JNI见顶部，仍没有正式launcher分配这些host。

## v1协议与结果

`RuntimeFileCommit.v1` descriptor；PUBLISH/REPLACE/APPEND请求为session、basename、声明字节数、一个pipe读端FD。READ/SIZE为session、basename；MOVE/MOVE_REPLACE为session、source basename、target basename；REMOVE为session、basename，控制请求不能有尾随字段。控制Parcel最多8KiB；正文最大1MiB，一律经匿名pipe传输，不把文件塞进Binder。文件是二进制字节，不套模型UTF8/JSON解码规则。

UID/session/操作/名称/长度/admission检查均在分配正文缓冲及文件创建前。每host最多4个已admit调用；客户端进程最多4个pipe writer及有界executor。FIFO类型检查、nonblocking poll、声明长度恰好读满并要求EOF，截断/多余字节/非pipe拒绝；默认绝对5秒读取截止，每次最多100ms观察close。读取截止不代表能中断存储fsync。

稳定结果：published（发布/移动）、removed（删除）、already_exists（仅PUBLISH/MOVE）、outcome_unknown；稳定拒绝仅RuntimeDataCode白名单，平台异常、正文、私有根、会话不回显。客户端在transact开始后遇到false/异常/未知或畸形回复，保守返回OUTCOME_UNKNOWN，不能说没有写入。已明确的Main拒绝才抛稳定RuntimeDataFailure。没有自动重试，也没有以replace降级publish。

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

1. c78faca失败job重跑31项与42fba3a两版本35项已确认；继续查本轮新IPC的35项结果，不混用旧run。
2. 显式移动/删除IPC已实现并补真实isolated UID/权限组合/关闭/结果未知断言；验证后接Node与原模块，不能从PUBLISH推导删除权或把通用fs.link替换为移动。
3. 有界append和Main API目录协调已加入候选；继续扩展真正受控FS所需的stat/list/mkdir/FileHandle/大日志与所有写者约束，适配原JSONL/MemoryStore并运行原恢复测试；不要全局把link改成rename。
4. full-ICU/正式launcher/model resolver、可信Bot身份与SQLite lease另见HANDOFF，仍未完成。
