# T-Isolation 交接（独立 UID + broker 最小实证）

基线：`feat/t-plugins`。本卡只做**代码层**最小实证：Android `isolatedProcess` 服务 + 主进程 broker + 可在宿主机 JVM 上跑通的路径守卫单测。

## 改了什么

新增 `dsh-go/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation/`：

- `WorkspacePathPolicy.kt`：**纯 Kotlin/JVM**（无 Android 依赖）。路径守卫，语义对齐 Node 侧 `packages/mbot-workspace-guard/src/index.js`：拒绝空/空白、NUL、绝对路径、`..` 段、反斜杠、盘符；joined 路径做 canonical 化（解析 symlink）后必须仍在 canonical 根内，且**分隔符敏感**（`workspace` 与 `workspace-evil` 不混淆）；`assertInside` 是内部二次校验入口（复检用），Agent 侧只暴露严格相对路径入口。
- `BrokerProtocol.kt`：**纯 Kotlin**。broker 操作白名单 `read_file` / `write_file` / `list_dir`；**没有任何取密钥的操作**，`BrokerOp.fromWire` 对未知名字返回 null。请求/响应都是普通数据类。
- `WorkspaceBroker.kt`：**纯 Kotlin/JVM**。主进程 broker 的全部逻辑：路径解析、读文本、写文本（限 1 MiB）、列目录；打开前复检路径，父目录必须先在工作区内创建，创建后再复检。无 exec/shell 面，无凭据面。
- `BrokerCodec.kt`：Android 侧 Bundle 编解码，只读取已知 key，多余字段（含可能的 key）直接丢弃。
- `AgentBrokerService.kt`：主进程 broker Service，绑定 `filesDir/workspace`，在独立 HandlerThread 上应答。
- `AgentIsolationService.kt`：**`android:isolatedProcess="true"`** 的 Agent 执行端占位。独立 UID、无 App 权限、无网络、读不到 App 的 `filesDir`；只能通过 init 时拿到的 broker Messenger 请求工作区文件。
- `AndroidManifest.xml`：注册上述两个 Service。isolated 服务 `exported=false` + `isolatedProcess=true`；broker 服务 `exported=false`。**未新增任何权限**，没有 `MANAGE_EXTERNAL_STORAGE`，没有宽存储权限。

新增单测 `dsh-go/app/android/app/src/test/kotlin/.../isolation/`：`WorkspacePathPolicyTest.kt`、`WorkspaceBrokerTest.kt`（JUnit4，与项目一致）。

## API Key 边界

- broker 协议里根本没有凭据操作；`BrokerOp.fromWire("read_key"/"get_api_key"/"reveal_api_key")` 返回 null，请求被拒。
- `BrokerCodec.decodeRequest` 只读白名单 key，请求携带的多余字段不会传给 broker。
- isolated 端没有网络权限，无法自己调模型 API；本卡里 isolated 端只做"向 broker 要文件"的占位。
- 未把任何 Key 写进代码、测试、文档或日志。

## 实际跑过的命令与结果

本环境**没有** `/workspace/toolchains/kotlinc`、没有 `/tmp/mbot-api36`、没有 `/usr/lib/jvm/java-21-openjdk-amd64`（HANDOFF 记的工具链在此容器不存在）。因此现取：Adoptium JDK 21、kotlinc 2.1.21、API 36 platform 的 `android.jar`、junit 4.13.2 + hamcrest 1.3。

```bash
export JAVA_HOME=/tmp/toolchain/jdk; export PATH=$JAVA_HOME/bin:$PATH
K=/tmp/toolchain/kotlinc/bin/kotlinc
AND=/tmp/mbot-api36/android-36/android-36/android.jar
cd dsh-go/app/android/app/src

# 1) 编译新增 Kotlin（主源码含 Android 侧文件 + 单测源码）
$K -nowarn -cp "$AND:/tmp/toolchain/junit-4.13.2.jar" -d /tmp/mbot-iso-classes \
  main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation \
  test/kotlin/io/github/ccch1mneyyy/dsh_app/isolation
# 结果：编译通过，0 error（首轮曾因漏 import android.os.Looper、android.jar 路径多一层目录而失败，已修）

# 2) 纯 JVM 测试实跑
java -cp "/tmp/mbot-iso-classes:$AND:/tmp/toolchain/junit-4.13.2.jar:/tmp/toolchain/hamcrest-core-1.3.jar:/tmp/toolchain/kotlinc/lib/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore \
  io.github.ccch1mneyyy.dsh_app.isolation.WorkspacePathPolicyTest \
  io.github.ccch1mneyyy.dsh_app.isolation.WorkspaceBrokerTest
# 结果：OK (10 tests)

# 3) 原有 Node 宿主回归
cd dsh-go && node --test runtime/minimax-bot-host/*.test.mjs
# 结果：tests 35 / pass 35 / fail 0
```

单测覆盖：`WorkspacePathPolicyTest`（拒绝空/空白/NUL/绝对路径/`..`/反斜杠/盘符、工作区内解析、symlink 逃逸→OUTSIDE、`workspace` vs `workspace-evil`、根不存在→ROOT）与 `WorkspaceBrokerTest`（工作区读写与列目录、穿越/绝对路径拒绝、symlink 逃逸拒绝、缺失文件不创建、协议无凭据操作）。

## 未验证项（必须如实标注）

- **没有真机/模拟器/adb**：isolated 进程是否真的拿到独立 UID、是否真的无网络/无存储权限、host app 能否 `bindService` 到 `exported=false` 的 isolated 服务、跨进程 Messenger 是否连通——**全部未验证**。上述是按 Android 文档与平台行为推导的设计，**不是**设备上的观察结果。
- **没有完整 Android 构建**：Gradle/AGP 9.1.0 + Flutter 插件链未运行，本卡新增源码只做过独立 kotlinc 编译，不是 Gradle 产物；AGP 的 manifest 合并、lint、`isolatedProcess` 的合并结果未验证。
- **Node/runtime 原生依赖与宿主生命周期**：本卡**没有**做调研实现，只登记结论方向（见下"待办"），不编造未验证结论。
- **TOCTOU**：broker 的复检只是缩小竞态窗口，不是原子打开；并发目录替换、hardlink 内容策略、SAF URI 仍未处理。
- **未接 UI**：没有 `AgentIsolationClient`，本轮没有 UI 入口、也没有真实 Agent runtime 接入；isolated 端目前只做"向 broker 要文件"的占位。
- 隔离端与 broker 的消息协议目前是**单向最小闭环**（init → task → broker request/response → result），没有做并发、鉴权（进程身份校验）、超时与断线处理。

## 下一步（未做）

1. 调研并写结论：Node 原生依赖（.node 插件、node_modules native addon）、PRoot、`/data/data` 目录访问在 isolated UID 下**哪些能跑、哪些不能**；宿主进程被杀后 isolated 服务的存活与回收行为。必须实机验证后写，**不得先写结论**。
2. 加 `AgentIsolationClient`：主进程绑定 broker 与 isolated 服务、注入 broker Messenger、做调用者身份校验（谁可以发 task）、超时与断线。
3. 真机仪器用例：验证 isolated 进程 UID 与权限（`/proc/self/status`、`getuid()`）、无网络、读不到 `filesDir`；以及 broker 只能读工作区。
4. 真实 runtime 接入后，把模型调用也放进 broker（Key 只在主进程），isolated 端不得拿到 Key。
