<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Flash-BackupJNIBuild：JNI双ABI与worker组件编译（只读）

2026-11-06。前卡 [备份核心双ABI](T-Flash-BackupCrossCompile.md) 已结项；它的df0eeac不含JNI。本卡核验后续真实新增源码，**零仓库源码修改/提交，不做任何设备操作，不安装/发布APK**。Codex负责Main客户端/owner/发布与生产打包，勿自行扩展。

## 开始与范围

读AGENTS、dsh-go/AGENTS和[native README](../../dsh-go/runtime/native-sqlite/README.md)。保护本地工作；fetch/快进feat/t-plugins，至少含 **bfe4a90**（Service候选）和7479c62（JNI）。报告精确HEAD及已跟踪文件是否干净；有未提交源码则停在报告阶段，不reset/stash/覆盖。main/dev/integration不动，不force/PR；报告/产物/临时编排只留D盘，不入库。

## A：两个Android26 JNI库候选

复用前卡同一Windows NDK27d、原归档摘要/安全extract、全部36个SQLite定义和源字节快照办法。来源文件是前卡Core/头/VFS/SQLite，再增加 **runtime/native-sqlite/jni_backup.cpp**；记录每个实际编译输入SHA256（CRLF与Git LF须区分），独立快照后编译，结尾复核输入未漂移。不改ROOT/PIN，不更换定义，不需要Node/V8/ICU或宿主JDK头。

仍用固定targets x86_64-linux-android26 / aarch64-linux-android26，真实clang.exe/clang++.exe、C99/C++20/PIC/hidden/NDEBUG/exceptions/RTTI；每ABI重编SQLite/Core/VFS/**JNI**五个对象，沿用no-undefined/no-gc-sections/16KiB max+common page/NDK libc++_shared+libc/libm/libdl。linker参数经驱动写`-Wl,--no-gc-sections`。NDK sysroot提供jni.h，不能拿宿主Linux/JDK头绕过目标问题。

库名及SONAME **libmbot_sqlite_backup.so**（与Kotlin加载名一致，区别于上一卡的candidate库）。不strip；核验ELF machine/Android26来源/依赖/全部LOAD≥0x4000，以及普通符号表中的三个Core符号。新增必须在**动态符号表**中存在的两个JNI导出：

```text
Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_run
Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_cancel
```

不改Core/JNI可见性来凑过，不改方法签名，不加入libnode/宿主glibc/-lpthread。编译/链接失败只回报必要完整错误和命令，不修源码/删检查。产物只留D盘临时目录，**不复制到app/jniLibs/assets、不修改CMake/Gradle/依赖、不伪称产品已打包**。

## B：现有Android broker组件APK编译（不安装/执行）

确认 tooling/android-broker-probe 的既有source includes仍覆盖isolation/**，因此新Service/Session/JNI声明/Reply及其纯测试会纳入编译；不为构建改source includes或manifest。使用已验证的SDK/NDK27d/CMake/Gradle组合，实查并报告JDK。已有JDK17可先用并记录；失败不得改Java target/依赖凑过。

```text
gradle --no-daemon --max-workers=2 -p dsh-go/tooling/android-broker-probe assembleDebug assembleDebugAndroidTest -Pkotlin.incremental=false
```

这只验Android源代码/组件打包编译。该工程没有注册/运行新worker，也没有自动包含A产出的JNI库；**不得手工注入来造“已集成”**。不运行connectedAndroidTest、不adb安装、不清数据、不触碰模拟器。组件APK不是产品APK，assemble不代表任何测试已执行。

## 回报

精确HEAD/源码状态；工具版本；A的实际输入SHA/命令/每ABI库大小+SHA256/ELF依赖对齐/普通Core符号+动态JNI导出；B的原样构建终态/APK大小摘要；未执行项。报告只留本机docs/research/flash-backup-jni-build.md，不提交，不回显env/账户/Key/签名URL。不要再复制上一卡整份背景，重点是新JNI和Service编译的实际证据。

**边界**：A是目标编译，B是组件编译。两者不能推出Binder身份/取消/死亡、独立UID执行、Main退役等待/发布、完整mcode FS/runtime、ARM64设备/16KiB设备、真实模型或产品APK已通过。PEER是身份准入，不是native/runtime ready。JNI和worker的设备执行另卡验收。
