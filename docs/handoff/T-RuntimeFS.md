<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeFS：完整文件适配前的契约与原子发布组件（2026-11-04）

**尚未实现完整runtime FS。** 当前WAL/两进程读取、Main四文件owner已有各自设备证据，不能代替本卡。Mini App可视化程序仍不适配；下述官方Bot的miniapp/node目录是复用其业务后端，不新增Mini App浏览器。

## Main原子发布组件（新增，设备执行待CI）

生产`RuntimeAtomicPublisher`只在Main worker使用，根固定在noBackupFilesDir/minimax-bot-runtime，子目录由可信Main策略选择。严格basename、最多8级、默认1MiB/最大8MiB；创建0600且O_EXCL/NOFOLLOW的随机stage，完整写入并fsync，然后调用可信注入的独占发布内核，最后同步目录。所有异常只暴露稳定code，诊断无路径/内容/cause。没有新增Binder/MethodChannel/Agent文件入口。

结果明确区分`PUBLISHED`（rename与目录sync均返回成功）、`ALREADY_EXISTS`（不覆盖已有目标，不承诺其内容或持久性）、`OUTCOME_UNKNOWN`（native调用进入后结果丢失，或rename后目录sync失败）。未知不能改报拒绝或确定提交；重试不能覆盖已存在目标。已知内核errno拒绝为稳定UNAVAILABLE，清理本次拥有的stage，不删除目标。进程崩溃遗留stage的恢复/清理仍未实现。

`RuntimePrivateDirectory`抽出原数据库owner的目录检查/祖先目录同步：目录能力始终留在Main。原数据库固定四文件、权限/链接/inode检查不变；需要原七项设备回归，不能仅凭重构编译成功认定没有回归。目录原始FD关闭失败时，不把dup交出并泄漏。

新增六个设备用例，注入原真实JNI探测：发布/重复不覆盖、native实际发布后抛异常、rename后sync异常、输入/预算/UI拒绝、已知ENOSYS拒绝清理与空文件、两个独立owner并发一winner。原十一项保留；预期总17项，**实际计数与结果须以新CI为准**。Python报告器新增固定`RuntimeAtomicPublisherTest`/`laN`与invalid_data，不打印注入故障正文或私有路径。

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

**边界**：组件逻辑位于生产源码，但native内核仍由验收APK注入；尚未绑定生产JNI打包/启动器、runtime FS协议、原JSONL/MemoryStore或Agent身份。close仅关Main本地目录FD，不是Agent撤销或SQLite lease。物理真机/ARM64/断电均未验证。

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

1. Main受限原子发布内核已取得API26/35证据；结果语义组件现已实现并编译，新增六项设备测试待CI。通过后将同一内核纳入生产native构建，再绑定受控FS能力；保留输入/冲突/并发/结果未知测试，不开放泛化syscall。
2. 然后设计Main持有的专用runtime-data FS能力与提交/恢复语义，再适配固定JSONL/MemoryStore调用并跑原实现测试。还需明确读写预算、流、原子替换、exclusive publication、所有写者同步和退出生命周期；目前没有这些生产接口。
3. 完整online backup、JSON/history、uploads/assets与模型配置安全投影都仍缺；不能因数据库owner或本探测通过就切正式入口。
4. 官方server.mjs确实创建HTTP server并listen；isolated UID的UI传输也需独立native桥接（未实现）。只在宿主临时验证过HTTP server可通过connection事件处理已连接Unix FD而不listen，不是Android或完整官方后端已接通。
