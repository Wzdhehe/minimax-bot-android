<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-RuntimeFiles：Main授予的有界文件提交能力（2026-11-04）

**目前是生产Kotlin组件与独立验收接入，设备待CI。不是完整node:fs、官方Bot已接通或SQLite FD撤销。** 原子发布/替换内核及21项设备基线见[T-RuntimeFS](T-RuntimeFS.md)。不修改vendor，不回退同UID Agent/shell。

## 授权边界

可信Main launcher先观察隔离服务的真实UID/Binder，然后调用`RuntimeFileCommitHost.open(context, scopeId, isolatedUid, peer, allowReplace)`。只能在Main worker创建；同Main UID、无效scope或预算拒绝。根固定为：

```text
context.noBackupFilesDir/minimax-bot-runtime/files/<Main选择的scopeId>/
```

即使scopeId为v2也不会覆盖`v2/sqlite`，Key prefs/Keystore及workspace均不在此子树。scopeId不是Agent参数，目录FD/真实根路径不传隔离端。客户端只提交严格basename；不支持子目录/绝对路径/穿越/保留stage前缀。Main默认仅授予目标不存在发布，必须显式allowReplace才可替换；备份区不能因为Agent选了REPLACE就获得覆盖权限。

每个host绑定真实Binder callingUid、随机64hex会话和peer死亡。不信任请求中的UID、root或scope，也没有这些协议字段；session仅交隔离Kotlin客户端，不进Node环境/argv。当前验收只接隔离Kotlin固定矩阵，**Node JNI文件binding尚未接入**，也没有正式launcher分配这些host。

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

1. 查新API26/35候选25项及原broker设备矩阵，尤其共享模型pipe循环的读取/取消回归。
2. 给固定隔离Node接文件commit JNI binding，先走同一生产host，保证Key/nonce仍不出现在JS环境/报告/文件；不是直接运行完整Bot。
3. 再扩展真正受控FS所需的read/stat/list/mkdir/append/FileHandle与全写者协调，适配原JSONL/MemoryStore并运行原恢复测试；不要全局把link改成rename。
4. full-ICU/正式launcher/model resolver、可信Bot身份与SQLite lease另见HANDOFF，仍未完成。
