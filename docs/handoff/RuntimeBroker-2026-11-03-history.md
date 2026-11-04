<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 2026-11-03 过程记录（非当前状态）

保留当轮失败、修正和观察记录；当前状态以 ../HANDOFF.md 及专题卡顶部为准。

# 最新接手入口（2026-11-03）

**当前源码构建项**：full-ICU Node已补固定输入、仅Android recipe变更、来源/摘要/config拒绝检查和独立CI构建→API26/35验证流程；尚未完成源码编译。host脚本1项、输入拒绝4项过，现有候选APK再编译 **BUILD SUCCESSFUL in 1m 1s**。既有04d7e97的两台真实Node验证已过，不要混同于full-ICU已过。详情见T-AndroidNode。

**最新已验证：Android Node24隔离加载**。04d7e97/run **41627233229 success**，API26/35均执行通过真实Node/JNI/Unix模型FD/取消/Key边界用例。它只运行固定验证脚本和假provider，不是完整mcode。当前主要关口：重建带Intl/ICU的Node、better-sqlite3与隔离数据持久化、mcode模型/工具适配。见 [T-AndroidNode](T-AndroidNode.md)。

**当前继续项：Android Node**。新增独立Node24验证target/JNI/private isolated服务，真实生产FD模块生成固定脚本；host脚本1项通过，NDK27d JNI编译通过，Gradle两个验收APK **BUILD SUCCESSFUL in 1m 34s**。API26/35实际Node执行待新CI；候选无Intl，尚不能启动完整mbot。源码/版本/命令/限制见 [T-AndroidNode](T-AndroidNode.md)。下文a094a85基础十项矩阵仍是已观察的设备基线。

**已验证的当前基线**：`a094a85` 的 Android矩阵 run **41622547971 success**。API26 job125871636255、API35 job125871636043均成功；runner解析XML输出每台 **10 tests / 0 failures / 0 errors / 0 skipped**。实际覆盖Keystore/nativeHTTP、独立UID直接文件/网络拒绝、Unix FD流/取消/容量/截止、IPC启动取消解绑与竞争租约/并发关联。物理真机/硬件Keystore/Android Node/完整runtime仍未验证。后文“待观察”和旧失败是演进记录，以此项覆盖。


**最新待验修正**：17e7629的Unix矩阵两版本各有两项失败；26日志定位为接收端setopt被拒。现把socket option设置留在主进程，隔离端只读/关闭，并保留完整SSE/Key/cancel断言。另补IPC启动取消解绑、可等待本地close、竞争租约/并发关联用例，总计十项设备用例待新CI；本地组件 Gradle **BUILD SUCCESSFUL in 1m 11s**，JVM27项/仪器编译通过。此前的八项矩阵不能写成通过。

**诊断已定位**：`09faafd`/run41603434315 的26失败有 `binderAlive=true` 和 isolated→app `tcp_socket read write` SELinux拒绝证据。新版17e7629只传Unix socketpair，run41604321437待观察。Android Node源码候选现找到Termux24.22.2（已钉recipe提交、未采用/构建），详见 [runtime计划](../ANDROID-RUNTIME-INTEGRATION.md)。数据路径/SQLite/WAL仍是独立关口。

**当前候选传输**：native 主进程保留 TCP，向隔离端只传 Unix socketpair 端点；`NativeModelChannels` 最多4通道，每通道单 poll worker/32KiB缓冲，支持容量拒绝、取消、截止与owner关闭。新增仪器用例共八项已编译，API26/35新矩阵待观察。宿主 **JVM27 + Node30 全过**（含真实 Unix FD），组件 Gradle **BUILD SUCCESSFUL in 1m 24s**。不能提前说26兼容已解决。

**最新 FD 结果与后续**：Node FD 消费器、共有SSE消费和回调取消已实现；宿主脚本 **JVM27 + Node29 全过**，供应商/服务10、scope4通过。Android矩阵 `41602011149`：**API35六项成功，API26两项TCP FD传递失败（DeadObjectException）**，总体失败，仍须解决26兼容。API35才有直接文件/网络拒绝与FD流/取消实证，不等于已装入Node。下一步先诊断/修复26传输，再做Android Node/JNI与原生依赖。

**最新 FD 通道**：可信 native 可创建单个已认证模型请求的预连接 FD；只移交 FD，不移交 native endpoint/token/Key。新增测试专用隔离探针，验证直接文件/网络拒绝、通过 Binder 接收 FD 的真实 broker SSE、关闭 FD 取消停滞 provider。六项仪器测试已编译，API26/35 CI 待观察。组件 Gradle **BUILD SUCCESSFUL in 1m 30s**，JVM27项通过。Node FD 消费、runtime 加载、工具协议和正式生命周期仍未接；见 [T-RuntimeBroker 首节](T-RuntimeBroker.md)。

**最新工作区资源限制**：文件读取改为读取期间最多 1MiB +1 探测字节，不再只信 length；目录改为惰性枚举，最多1000条/128KiB文件名字节预算后才排序，超限拒绝而不返回截断列表。拒绝非普通文件。宿主脚本 **JVM27 + Node22 全过**；组件 Gradle构建再过，XML27项零失败/错误/跳过。这不解决路径检查到打开的 TOCTOU/hardlink 问题，设备结果仍另行观察。

**最新设备验证**：`2d7367b` 的 Android35/KVM 模拟器 run **41600336727 success**，日志确认 `Finished 3 tests on emulator-5554 - 15` / `BUILD SUCCESSFUL in 5m 20s`。覆盖真实 Android Keystore 存储、native HTTP 假 provider 注入/回显拦截、独立 UID 文件 IPC 与 broker 穿越拒绝；不证明直接私有文件/网络拒绝（另待探针）、硬件 Keystore 或真实 API。XML artifact 下载在本环境返回403，结论来自 run API 和执行日志，未宣称读过 XML。**物理真机未验证**。本地仪器 APK 编译：`BUILD SUCCESSFUL in 1m 27s`。验收工程复用生产 localhost-only network security config，不放宽 provider HTTPS。上一阶段 `5af2ed8` 的 GitHub native **41599240875**、host **41599240883**、scope **41599240858** 全部 success。

**最新流式补强**：native/host 增加完整结束标记、异常断流处理、背压、绝对截止时间、reader/call 清理及解析前 JSON 预算；proxy.dispose 会取消并收敛模型请求。最终本地 **JVM24 + Node22 全过**，Gradle组件构建再过、XML24项通过；设备测试仍未运行。说明见 [T-RuntimeBroker 首节](T-RuntimeBroker.md)。前一提交 `4c3e808` 的 GitHub **41591483093**：native/Android编译两项均 success。

**最新 Android 构建进展**：新增复用生产源的 `tooling/android-broker-probe` 验收工程，Gradle 9.3.1/AGP9.1/JDK21 下 `testDebugUnitTest assembleDebug assembleDebugAndroidTest` **BUILD SUCCESSFUL**。XML确认 JVM20项全过；Keystore/IPC 两类仪器 APK 已编译打包，但**未在设备执行**；不是完整 Flutter Bot APK，更不是 Node runtime 已移植。存储类从 Flutter 注册文件原样拆出供验收复用。后续依赖/隔离/模型传输关口见 [Android runtime 计划](../ANDROID-RUNTIME-INTEGRATION.md)；实际命令/限制见 [T-RuntimeBroker](T-RuntimeBroker.md)。

**继续推进 IPC 装配**：补 `AgentIsolationClient`、owner UID/会话 gate、请求 ID 关联、超时/断线/close 与保守 Binder payload 预算；仍是文件占位服务，不是 Node/runtime。JUnit 现 **20 passed** + Node/Kotlin **5 passed**，API36 下 isolation 源码独立编译通过、scope 4 passed。设备 Binder/UID/生命周期用例已写但**未验证**。下一步应先解决 isolated UID 的 Node 加载和受控模型 transport，不能假定 loopback 可访问，见 [T-Isolation 最新节](T-Isolation.md)。

该阶段提交 `a743d5f` 的 GitHub native **41588261052 success**、scope **41588261051 success** 已实查。

**本次最新进展**：直接从远程 `feat/t-plugins@b7ca158` 续写 native Kotlin provider broker（未新建分支）；四类文本协议、SSE、socket 取消、native Key 回显防护已实现，JUnit 6 + 跨进程 4 + 宿主回归 19 + scope 4 通过。Key 防泄露测试的假 Key 只在 native JVM 生成，检查 runtime descriptor/env/log/Agent 可读文件。正式启动/配置同步/Android 设备验证仍待完成，见 [T-RuntimeBroker](T-RuntimeBroker.md)。用户最新要求持续按文档开发、阶段 push，覆盖了之前“只做一项就停”的限制。

**进一步进展**：设置页供应商 probe 的 Node 持 Key/直连旧方案已删除，服务只接受 `modelBroker` 流能力，经 native 完成请求；首个文本输出后取消、超时不重试，跨进程验证增加到 **5 passed**（加 JUnit 6、宿主 CI 集合 18）。native 阶段提交 `fad4f3d` 的 GitHub native CI **41586158979 success**、scope **41586158832 success**；旧 host CI 在临时目录删除遇到竞态，测试清理已加有限 retry，生产 shutdown/drain 未因此宣称完成。

`19d487d` 已经远程实查三组 CI **success**：native **41586891156**、host **41586891056**、scope **41586891057**。原失败记录保留作原因说明，不能继续说 host CI 仍是红的。

用户要求额度结束前推送代码、维护文档，方便其他 Agent 接手。本文件优先描述当前状态；`DEVELOPMENT.md` 下面较旧的逐轮记录只作历史，不能据此判断 App 已完成。

