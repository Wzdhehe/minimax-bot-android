<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机Windows：API35定向验收卡

先完成记忆管理卡R1/R2/R3并推送，再做本卡。若断电前不足15分钟，只回报时间/阻塞，不开大下载或留未交接的改动。你在用户本机，有API35/x86_64的emulator-5554；云端和本机不共享文件，不能使用云端/tmp路径。

## 本卡性质与写入范围

只做独立`dsh-go/tooling/android-node-probe`验收工程的准备、构建和6项设备测试，不是产品Flutter App，不是可聊天的Bot首包。不跑上游Flutter全量/golden，不构建旧DSH rootfs，不需要WSL，不改生产源码、Gradle配置、依赖、pin、CI、任务卡或共享交接。报告继续放本机/消息，不提交报告；不提交二进制、构建目录、local.properties或缓存，不发布APK/Release。

开工fetch同步feat/t-plugins，保留自己工作，不reset/stash/覆盖。工具只用现有D盘目录或D盘临时工具目录，不做全局安装/永久改PATH，不修AVD ini、不wipe模拟器、不清用户数据。

## 已实现的Windows准备候选及证据

`prepare-probe.py`已能按host选Windows NDK27d的`windows-x86_64/bin/clang++.exe`，不用.cmd/shell拼接，并固定`--target=x86_64-linux-android26`。Linux路径不变；archive SHA、Node commit/tree/version、binary SHA和NDK revision不放宽，默认release仍无Intl/SQLite。

文本资产明确用UTF-8，header inventory使用正斜杠。云端已实跑Python45/45、宿主精确脚本6/6，且重新用钉住NDK27d完成Linux native准备；这些不证明Windows编译/设备已通过。初次测试因新host预检挡住既有坏archive验证而4失败，已保持原pin/archive检查顺序，45项最终全过，未删弱化原安全断言。

## 工具预检

实际SDK为`D:\android-sdk`，NDK必须`27.3.13750724`，CMake3.22.1、platform36、build-tools36；不要选28.2。Node24与Python>=3.11（hashlib.file_digest需要）须可用，npm来自该Node。Gradle固定9.3.1，先查本机已有安装/缓存，不使用不明版本。

JDK21是云端之前已验证的基线。你当前默认JDK17不等于已证实无法构建：工程target是Java17。先查是否已有Android Studio JBR21或D盘JDK21，实际运行`java -version`和Gradle `--version`核对。没有21时允许先用现有17尝试本卡构建，必须记录版本；若Gradle/AGP拒绝，停止或用D盘临时可信JDK21，不改依赖/编译target绕过。工具下载先核对官方来源/摘要；不在C盘另装整套SDK。

PowerShell只设置当前进程环境（JAVA_HOME按实际选择，不照抄占位符）：

```powershell
$env:ANDROID_HOME = 'D:\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PYTHONUTF8 = '1'
# JAVA_HOME如需要，设到已验证的本机JDK根；把该bin和D:\android-sdk\platform-tools放本进程PATH前面。
node --version
python --version
java -version
adb devices -l
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell getprop ro.product.cpu.abi
adb -s emulator-5554 shell getprop sys.boot_completed
```

确认API35/x86_64和boot_completed=1，所有设备操作显式`-s emulator-5554`，不要误操作物理手机。可隐藏序列号回报，不输出Key/token、环境全集或用户文件。

## 准备与构建（仓库根）

```text
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/model-sdk-probe
python dsh-go/tooling/android-node-probe/prepare-probe.py --ndk D:\android-sdk\ndk\27.3.13750724
```

使用实际Gradle9.3.1可执行路径运行（若有空格PowerShell用`&`和路径字符串，不手拼cmd/shell）：

```text
<gradle9.3.1> --no-daemon --max-workers=2 -p dsh-go/tooling/android-node-probe assembleDebug assembleDebugAndroidTest -Pkotlin.incremental=false
```

任一步失败停止后续，不安装陈旧APK。不得删除hash/NDK检查、改requiresIntl/Sqlite为真、换Linux addon或恢复同UID shell。默认此包`requiresIntl=false/requiresSqlite=false`，只可验证基础HTTP夹具/隔离/FD/model假传输等；**不能**宣称真实Bot/mcode、SQLite/full-ICU或真实模型已过。没有真实账户请求，不需要用户Key。

## 安装与定向运行

先确认这次构建输出时间及APK实际路径（下列为现有工程命名，以Get-ChildItem核对）：

```text
dsh-go/tooling/android-node-probe/build/outputs/apk/debug/isolated-node-verification-debug.apk
dsh-go/tooling/android-node-probe/build/outputs/apk/androidTest/debug/isolated-node-verification-debug-androidTest.apk
```

记录两个APK的Get-FileHash SHA256，**不上传/提交APK**。仅安装上述验收包：

```text
adb -s emulator-5554 install -r <本轮main验收APK实际路径>
adb -s emulator-5554 install -r <本轮androidTest APK实际路径>
adb -s emulator-5554 shell pm list instrumentation
adb -s emulator-5554 shell am instrument -w -r -e class "io.github.ccch1mneyyy.dsh_app.nodeprobe.BotSocketConnectionTest,io.github.ccch1mneyyy.dsh_app.nodeprobe.IsolatedNodeModelTest" io.github.ccch1mneyyy.dsh_app.nodeprobe.test/androidx.test.runner.AndroidJUnitRunner
```

PowerShell把class逗号列表作为单一字符串参数传递；以pm list instrumentation确认runner名称，不猜其它包名。不自动卸载其它App。若签名冲突/安装失败回报，不扩大清理范围。

当前源码预期**6项**：BotSocketConnectionTest四项（二进制/半关闭、流式与关闭、失败授予FD回收、真实Binder死亡撤销），IsolatedNodeModelTest两项（启动前拒绝、实际隔离Node原模型/Key/动态FD/Main桥/外来UID拒绝）。最后一项在本默认包只跑no-Intl夹具，不能冒充官方Bot。死亡撤销必须得到内部bot_grant_closed，普通timeout不算通过。

`am instrument`进程退出码0不保证测试成功；必须核对实际运行6项、0失败/0错误/0跳过及终态`OK (6 tests)`。出现Process crashed/FAILURES/INSTRUMENTATION_FAILED、少跑、全跳过或卡住一律不算通过。只保留本次instrumentation输出到本机临时文件，不抓全系统原始logcat、不输出敏感报文；需要诊断再回报固定阶段/用例名。

## 回报

HEAD、是否有未提交/未推送源码、实际工具版本、每步实际结果/耗时、两APK SHA、instrumentation的6项原始输出/最终计数；失败则报失败阶段和固定错误，不编设备结果。注明仅API35 x86_64模拟器，API26/ARM64/真机/full-ICU/真实provider/产品APK均未验证。按用户要求报告只留本机并贴消息，不提交仓库；Codex负责更新共享交接。
