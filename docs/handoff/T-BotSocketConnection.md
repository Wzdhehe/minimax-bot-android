<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Android Main socketpair适配与桥接验收候选

## 本阶段范围

接续[T-BotLoopbackBridge](T-BotLoopbackBridge.md)，新增生产`runtime/BotSocketConnection.kt`，并将其与字节桥组合到已有Android Node验收工程。仍不是正式launcher，不自动发布Flutter Endpoint，不恢复同UID PRoot执行。

Main绑定127.0.0.1临时端口 → 写descriptor携带该端口 → 已有独立UID Node启动并发出私有ready字节 → Main activate桥 → 客户端连接触发socketpair创建 → 可信Main经现有owner-only Messenger授予guest端点副本 → 核对隔离UID回执后桥开始转发 → 官方Bot负责HTTP鉴权。guest仍无TCP监听。Intl缺失时仅运行明确的基础HTTP夹具，不冒充官方Bot。

## 生产适配器合同

- `BotSocketConnection.open(context, grant)`在分配前检查当前UID是App owner；不接受任意FD、路径、HTTP地址或用户代码，不接触provider Key。
- 每次新建socketpair；`grant`只能经可信IPC传递guest端点副本，返回前须核对目标身份和接受回执，等待必须有界并能因对端死亡失败。**适配器本身不实现Binder协议或检查回执UID**；此次验收调用方用已有ProbeConnection请求ID映射，并核对`Reply.uid == identity.uid`和`code == ok`，最多等待10秒。正式launcher必须实现同等或更强合同，不能传入只调用send即返回的callback。
- 无论授予成功/失败，Main手里的guest端点原件在finally关闭；失败关闭Main端点（先SHUT_RDWR），仅抛稳定`bot_connection_unavailable`，不附原始异常或cause。结果不明不会自动重试；远端已收到的副本须靠EOF/该代进程撤销回收，不声称超时等于未接受。
- 正常请求EOF仅SHUT_WR，保留反向响应；会话关闭先SHUT_RDWR唤醒两方向IO，再关闭stream与PFD。Main写socket有10秒SO_SNDTIMEO；读方向无空闲超时，以支持静默SSE；并发/worker限制仍由字节桥提供。
- 仅native可信方可创建/激活；loopback可达不等于认证。桥不解析内容，真实服务的Bot token、Host、Origin验证不得移除。这里只携带Bot会话凭据，provider Key始终留在native broker。

## 验收改动

`IsolatedNodeModelTest`现以Main TCP socket请求桥，不再直接手写socketpair读写；connector等待现有isolated服务的owner-only授予回执，ready之前不activate。保留另一个isolated UID伪造授予后FD被关闭的负例。

descriptor使用实际桥端口；guest返回读到的非密钥端口，Main明确断言它等于本代、不同于替换文件的新一代端口，保留旧只读FD不能串代的验证。固定脚本服务与Host检查从descriptor取端口，宿主精确脚本夹具用49231而非旧43117。此报告字段是验收专用，未改产品descriptor或Flutter协议。

isolated身份探针新增直接调用生产适配器被`bot_connection_owner_only`拒绝的断言。新增3项Android设备用例（目前仅编译，未执行）：

1. 真实socketpair + Main桥，84KiB二进制请求、客户端半关闭后仍完整接收响应，Main的guest端点原件确实关闭。
2. 请求未EOF时能接收模拟流事件；关闭桥能令客户端与模拟guest阻塞读都收到EOF，重复关闭安全。它不是官方Bot SSE业务测试。
3. 授予回调先复制FD后抛错；Main原件关闭、另一端收到EOF，异常细节不泄漏。

## 本轮实际验证

先跑精确脚本时为5/6：检查器发现本地`build/verified-assets/node-fd-probe.cjs`过期，错误`prepared probe asset is stale`。未绕过校验；使用正式生成器更新忽略的资产后重跑：

```bash
python3 dsh-go/tooling/android-node-probe/prepare-probe.py --bundle-only
node --test dsh-go/tooling/android-node-probe/probe-bundle.test.mjs
```

```text
✔ the exact APK script consumes inherited Unix FDs and reports privately without filesystem modules (4285.214553ms)
✔ the packaged scoped-model block routes two turns over actual inherited FDs (3450.171791ms)
✔ the packaged real Bot serves granted FDs without listening (1620.225326ms)
✔ the packaged no-Intl fallback serves granted FDs without listening (1602.571851ms)
✔ the packaged real Bot service module loads without filesystem-relative imports (1018.849937ms)
✔ the exact packaged Bot Store block survives two host processes using a fake file capability (5202.673145ms)
ℹ tests 6
ℹ suites 0
ℹ pass 6
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 17519.353043
```

```bash
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_*.py'
```

```text
.......................................
----------------------------------------------------------------------
Ran 39 tests in 6.921s

OK
```

两项最终命令均退出0。上述宿主夹具不执行Android PFD/Binder；文件broker为测试替身，不能证明设备链或真实provider已通过。

Android验收APK/测试APK在关闭stream的最终源码落定后重新构建，实际命令（工作目录`dsh-go/tooling/android-node-probe`）：

```bash
JAVA_HOME=/tmp/mbot-jdk21 ANDROID_HOME=/tmp/mbot-android-sdk ANDROID_SDK_ROOT=/tmp/mbot-android-sdk /tmp/mbot-gradle/gradle-9.3.1/bin/gradle --offline --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest
```

```text
BUILD SUCCESSFUL in 2m 25s
71 actionable tasks: 9 executed, 62 up-to-date
```

退出0。随后逐字节核对本轮生产adapter与Gradle拷贝输入、APK内固定脚本与刚生成的资产，两项均一致：

```text
PASS: Android copied production adapter matches final source
PASS: APK script matches freshly generated source bundle
```

前一轮构建虽已成功（4m 11s），中途stream关闭收尾源码尚在修改，故没有用它作为最终源码证据。上述是最终重构建结果；临时工具链路径不保证后续环境存在。

新设备用例、Main桥到真实Bot的设备连通、Flutter产品APK、ARM64/真机与真实provider均**未验证**。当前本地准备的Node仍无Intl/SQLite（requiresIntl=false、requiresSqlite=false），不能用它验官方Bot分支；完整设备验证须使用现有full-ICU producer钉点并按原authority流程验证，不修改producer权威输入/放宽校验。

本轮实查桥阶段d402c29的`Isolated Android Node candidate` run42036567096：API26/35各job步骤数0，check-run127276144282的annotation仍为：

```text
The job was not started because recent account payments have failed or your spending limit needs to be increased. Please check the 'Billing & plans' section in your settings
```

所以没有新设备CI结论。不能将作业未启动当作测试通过，亦不能仅按failure状态诊断源码错误。

## 下一位具体顺序

1. CI账户限制解除后，在API26/35执行最新验收工程（含上述3项），核对实际测试报告与Intl分支，不能把编译、旧49项结果或宿主测试当设备证据。
2. 继续可信Main运行代次的组装：隔离进程死亡/停止立刻关闭桥、Endpoint Lease和文件/模型能力，撤销等待中的授予，测试迟到回执不能复活旧运行。当前只有验收代码组合，尚无生产ServiceConnection/启动状态机。
3. 正式launcher需使用本代端口/token完成私有只读descriptor交接，在真正隔离服务就绪后才publish Endpoint；不以bind成功、文件写成功或任意端口轮询当就绪。再接Flutter现有客户端，测试真实官方Bot事件流、取消/停止与代次替换。

不要让本机Flash处理底层缺口；其编辑表单已审查接受，当前任务由[T-Flash-BotMemoryRead](T-Flash-BotMemoryRead.md)限定。不要因缺设备或产物改成同UID shell、guest监听、Linux glibc addon或宽存储权限。
