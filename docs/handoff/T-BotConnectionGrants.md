<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 每代Bot连接授予的截止与撤销

状态：生产组件和验收组合已实现，宿主回归通过；Android编译结果见下文。不是正式launcher，不自动publish Flutter Endpoint；设备执行未验证。

## 解决的问题

上一阶段[BotSocketConnection](T-BotSocketConnection.md)的验收调用方直接对回执future等10秒，停止时没有立即撤销私有等待。本阶段新增纯Kotlin生产`runtime/BotConnectionGrants.kt`，接入同一Android验收链。只处理每代授予准入，不持有FD、provider Key或Bot token。

每代从可信Binder握手得到expectedUid，默认最多4个等待、允许1—16，默认10秒且最大10秒。这里的uid必须是Messenger系统提供的sendingUid，不是Bundle里的数字；生产组件无法自行证明调用者提供的uid来自何处。现有验收调用方用已经验证不同于Main的identity.uid构造，并从真实Reply.uid映射回执。

`awaitAccepted(offer)`只在native桥worker中调用；offer发送一次IPC、及时返回future，不接收HTTP字段。不重试，只有匹配UID且accepted的回执才成功。过载在调用offer之前拒绝。失败仅给出bot_grant_closed/busy/timeout/denied/unavailable，不回显原始异常或cause。

## 并发与关闭合同

- 回执future完成不等于授予成功：最后必须持锁检查本代仍开放、等待仍登记，和close建立明确顺序。close先赢的连接不能随后授权。
- close持锁标记永久关闭、移除所有等待，随后只完成组件私有future；不在锁中执行外部代码，不等外部offer返回，也不调用可能同步执行用户handler的外部future.cancel。这样迟到/不合作的回调不能阻塞停止。
- 等待者退出时取消自己得到的外部future；验收的ProbeConnection把取消反传到原回执future，并删除请求ID映射，迟到消息被忽略。取消future不表示Binder消息未送达或远端FD已撤销。
- offer本身若阻塞在外部同步调用里，close仍返回，但调用线程须待offer返回后才能完成收尾；到时仍拒绝并取消回执，BotSocketConnection关闭两端本地副本。组件不强杀外部线程；桥已有跨代共享32个worker的上限。正式IPC发送仍须遵守及时返回合同。
- 截止覆盖调用offer和回执等待。回执错误/未知交付不会自动重试。close后的新请求在offer前拒绝，旧gate不能影响新gate。

它不是身份握手、Binder协议、进程终止器或整个runtime生命周期管理器。单独关闭gate不会中断已建立HTTP流；可信owner必须同时关闭桥和该代其他能力。

## Android验收接线

`IsolatedNodeModelTest`从identity.uid构造gate，在启动Node前linkToDeath；死亡回调关闭gate和桥、清掉connector引用。正常完成两次HTTP后也先关闭gate，再关闭桥，finally始终关闭并unlink。文件/模型host仍用原来的各自死亡/排空路径，不改变其协议。

`ProbeConnection.offerBotReceipt`只是验收Messenger到生产Receipt的映射：不从payload取uid，取消反传并清理offers。ProbeConnection.close增加幂等性，供提前结束进程的用例安全收尾；没有变成产品启动器。

`BotSocketConnectionTest`新增设备用例：先用真实isolated Binder得到UID，Main打开一个socketpair并模拟永不回复的授予future，结束隔离服务，要求死亡回调唤醒等待（截止10秒、死亡后等待者最多2秒），未确认连接两端关闭、另一端EOF、迟到回执不能成功。它还明确核对内部拒绝码必须为`bot_grant_closed`，防止普通deadline被外层通用IOException掩盖而冒充死亡撤销。**这是已编写的验收，不是已执行结果**；模拟回执没有真正在该隔离进程接收FD。已有完整model用例承担真实Messenger FD授予与响应路径，但本轮同样未执行设备。

## 本轮实际验证

```bash
KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc MBOT_NATIVE_DEPS=/tmp/mbot-native-deps bash dsh-go/tooling/test-native-model-broker.sh
```

```text
JUnit version 4.13.2
.....................................................................
Time: 7.9

OK (69 tests)
```

```text
# tests 56
# suites 0
# pass 56
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms 29854.287305
```

退出0。新增7项JUnit验证UID/接受状态、close唤醒/永久拒绝、外部offer迟到且关闭不等待、容量释放、截止取消且不重试、外部错误不泄漏、旧gate不影响新gate。用真实future/线程同步，没有Android/Binder模拟结论。

Android验收APK/仪器APK最终编译已实际通过（包含死亡用例必须返回closed而非timeout的最终断言）：

```bash
# 仓库根目录；本轮实际临时工具链，后续须实查，不假定路径仍在
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk ANDROID_SDK_ROOT=/tmp/mbot-android-sdk /tmp/mbot-gradle/gradle-9.3.1/bin/gradle --offline --daemon --max-workers=2 -p dsh-go/tooling/android-node-probe assembleDebug assembleDebugAndroidTest
```

```text
BUILD SUCCESSFUL in 2m 55s
71 actionable tasks: 8 executed, 63 up-to-date
```

退出0，另逐字节核对Android构建拷贝的gate与生产源码相同：`PASS: Android copied grant gate matches final production source`。本轮无Android设备执行。用户表示本机有模拟器，已请本机agent只读回报API/ABI、adb、工具链/WSL、分支/工作树和断电前可用时间；尚未收到环境信息，不能先写设备已通过或把云端路径当本机共享目录。

设备、真实provider、ARM64、Flutter产品APK、真实Bot在Main桥后的手机运行均未验证。CI账户计费阻塞尚无解除证据，不用旧设备run的结果冒充本轮验证。本地准备的Node仍为无Intl/SQLite的release，基础夹具通过不等于真实Bot；后续本机验收卡必须区分。

## 后续顺序

1. 恢复CI后在API26/35执行最新验收，特别是Binder死亡撤销、现有真实回执/外来UID拒绝、socketpair半关闭和full-ICU的真实Bot分支。
2. 生产Main owner仍缺：把实际独立UID进程启动、descriptor只读交接、ready确认、桥、gate、Endpoint代次Lease与文件/模型能力放到同一生命周期。只有真正就绪才publish，死亡/停止立即撤销并等待文件能力排空，旧ready不得发布为新运行。
3. 新gate不能在旧owner里换一个UID重新使用。新代使用新gate/桥/随机token；不复用迟到回执。它也不撤销Dart手上旧descriptor的副本，须靠真实桥关闭让旧服务失效。

本机Flash当前卡为[T-Flash-MemoryManage](T-Flash-MemoryManage.md)，用户约一小时后断电，必须预留验证和推送时间；不要转派native缺口给它，不和本卡改动同文件。
