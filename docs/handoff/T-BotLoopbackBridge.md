<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Main侧Bot loopback字节桥候选

状态：下文记录桥本体阶段的实现与验证。后续已有[Android socketpair适配与验收组合](T-BotSocketConnection.md)，仅编译/宿主验证，设备未执行；仍未接入正式launcher，不会自动启动监听或发布Endpoint。下文「尚无适配器/下一步」是本阶段历史边界，不要据此重写后续已提交的候选。

## 固定合同

`runtime/BotLoopbackBridge.kt`只绑定127.0.0.1的临时端口，由可信native提供零参数connector，返回一条独立拥有、已经授予隔离Bot的双向连接。HTTP请求不能选择目标地址、根目录或FD。桥不解析/记录HTTP内容、不读取provider Key；真正的Bot仍负责会话token、Host和Origin鉴权，连接准入不等于认证。

bind仅预留地址，activate之前到来的连接关闭且不调用connector。正式launcher必须用该port写descriptor，在isolated启动/握手确认后activate，再用`BotRuntimeEndpoint.publish`取得自己的代次Lease；不能根据端口已经监听就publish。Binder死亡/停止时应关闭桥和该Lease，并撤销该代次其他能力；本组件尚未完成这些接线。

每代默认4条、可配置1—16条连接；共享线程池跨代最多32个worker、无任务队列。过载或connector错误关闭该连接，不输出原始异常。每方向16KiB缓冲，阻塞IO提供背压，每次写后flush，支持响应在请求EOF前持续返回。一个方向EOF只shutdown对方输出，保留反向响应；两个方向完成或IO错误时收尾。

close停止接收并关闭本代客户端与已取得的上游连接；正在外部IPC中、忽略取消的connector可能迟到返回，返回后关闭，不会重新接纳。close不等待不合作的connector；共享池防止反复重启无限创建卡住的worker。正式Android connector仍须有可靠的截止/进程死亡处理，Connection.close必须解除阻塞读写，shutdownOutput不得关闭输入方向。

## 验证边界

JUnit使用真实JVM TCP socket验证：

- activate前无连接授权；
- 84KiB二进制双向传输与请求半关闭后完整响应；
- 带缓冲上游的响应流不等待客户端EOF；
- 容量拒绝、关闭客户端、迟到连接回收；
- 已建立流被close打断；
- 关闭不可重新激活、重复关闭安全。

测试加入既有`test-native-model-broker.sh`。Android Node验收工程也编译同一生产类，workflow覆盖其路径。它尚无Android PFD适配器，也未通过Main桥请求真实Bot；宿主socket结果不证明Android Binder/FD或设备行为。

当前最终源码（含共享worker上限和带缓冲输出即时flush测试）已实跑：

```bash
KOTLINC=/workspace/toolchains/kotlinc/bin/kotlinc MBOT_NATIVE_DEPS=/tmp/mbot-native-deps bash dsh-go/tooling/test-native-model-broker.sh
```

```text
JUnit version 4.13.2
..............................................................
Time: 7.638

OK (62 tests)
```

```text
1..56
# tests 56
# suites 0
# pass 56
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms 28831.205833
```

退出码0。Android验收APK与仪器测试APK也已使用当前环境实际编译：

```bash
# 工作目录：dsh-go/tooling/android-node-probe
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk ANDROID_SDK_ROOT=/tmp/mbot-android-sdk /tmp/mbot-gradle/gradle-9.3.1/bin/gradle --offline --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest
```

```text
BUILD SUCCESSFUL in 4m 31s
71 actionable tasks: 12 executed, 59 up-to-date
```

设备没有执行。这里构建的是验收工程，不是产品Flutter APK；没有真实provider、ARM64运行或设备证据。临时工具链路径只记录本轮实际环境，后续须自行查找。

## 下一步

实现Main持有的Android socketpair适配器和owner-only授予确认，按此Connection合同管理所有副本及半关闭；在现有Node验收工程用桥替换Main手工socket请求，测试真正隔离Bot的匿名拒绝、授权状态/事件流、停止和代次替换。之后才接正式生命周期与Flutter Endpoint。不要将PRoot进程、Linux glibc addon或guest自行listen作为替代路径。
