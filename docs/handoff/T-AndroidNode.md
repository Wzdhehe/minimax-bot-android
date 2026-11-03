<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Android Node 加载关口（2026-11-03，正在开发）

**真实执行已通过**：`04d7e97` 的run **41627233229 success**，API26 job125887515761和API35 job125887516140均通过仪器用例。真实隔离进程加载Node24.22.2，版本/UID、私有文件拒绝、Keystore→native broker→Unix FD→Node SSE、取消、Key回显拒绝及descriptor/env/log不含Key检查均已执行。仍是固定脚本/假provider；未启动完整mcode/官方Bot，ICU、better-sqlite3、持久化、物理真机和ARM64待做。后文“设备待CI”是本次实现前的记录，以此覆盖。

当前默认产品仍为 DSH，尚未在 Android 启动 mcode。上一关口：`a094a85` 的 API26/35模拟器各十项通过（run41622547971），native Key/Unix模型FD/IPC已取得组件证据。物理真机、better-sqlite3和完整runtime仍未验证。

## 当前继续：full ICU 源码构建与验证（尚未完成）

新增 `node-source-build.json` / `build-source.py` / `android-node-source.yml`。固定mobile提交、官方Node提交与tree、重构mobile tree、构建脚本SHA256、NDK27d、API26/x86_64；在忽略目录复制原脚本，仅把Android的`--with-intl=none`改为full-icu，保留原仓库与子模块洁净。单ABI两路编译，CI允许较长构建；产物只作七天private工作流输入，不发布release/APK。下游通过build job的archive SHA再次验证，校验来源/ABI/config，才编译JNI并在API26/35跑中文Intl/Asia-Shanghai时区与原Key边界用例。

本地实跑：真实固定recipe摘要匹配，确认只替换Android Intl选项；包摘要拒绝、错误recipe拒绝、Zip逃逸拒绝、无ICU包拒绝 **4 passed**；精确打包脚本host **1 passed**。默认候选路径重准备/编译成功，Gradle **BUILD SUCCESSFUL in 1m 1s**。这些不是full ICU构建成功；本地主机没有host clang/ninja与足够完整构建空间，完整Node源码编译交给独立CI，结果待观察。

注意原先tree表述已修正：官方f7f273a的Git tree是5f62a9f；release `node-source.json.tree` 的be3625a是应用mobile patches后的树。源码构建分别核对两者，不能混用。

## 已核对的 Node24 候选

- `RockinChaos/nodejs-mobile@223eec9554b5d344b86d1b411f0255df8cc47f27`，release `v24.22.2`。
- 官方Node源钉点 `f7f273a9bb6445f6f7fb6349105e7532972045fb`，官方Git tree `5f62a9f6a645a5391090ed384056e4e06b80c73c`；mobile patches重构后的tree为 `be3625af068f11bf8288ecf450a36d990cb2294c`（release build-info的tree字段）；fork按 `patches/series` 重构，不替换mcode钉点。
- 下载包 `nodejs-mobile-v24.22.2-android.zip`，SHA256 **cde938a9e45ebd3f53d11fbb5f3875df92b4069431677dd5827b68acfaa01847** 已在本地核对。
- 包内x86_64 `libnode.so` SHA256 **c1c48bf4d6aa2a327986f83a84cc8a7fbf9d401c718081f5eb266e600bbf4dec**；同时有arm64/arm库，但未验证手机。
- ELF实查SONAME libnode.so；依赖libm/libdl/liblog/libc/libc++_shared；LOAD段16KiB对齐；导出`node::Start`。这些是静态检查，不等于运行或真实16KiB页验证。
- fork的构建workflow使用NDK r27d；脚本支持AndroidAPI>=24，`--shared --openssl-no-asm --with-intl=none`。**没有Intl**，而官方mbot `i18n.mjs` 顶层直接调用Intl.DateTimeFormat，不能据此把候选直接接进正式Bot。需要后续重建带ICU的版本或明确兼容层并验证。
- release ZIP含上游完整LICENSE及LICENSE-nodejs-mobile，核心MIT；不得遗漏捆绑第三方许可。二进制只放忽略的build/临时目录，不提交APK/库/签名文件。

源码与构建引用：

- [源码记录](https://github.com/RockinChaos/nodejs-mobile/blob/223eec9554b5d344b86d1b411f0255df8cc47f27/node-source.json)
- [构建脚本](https://github.com/RockinChaos/nodejs-mobile/blob/223eec9554b5d344b86d1b411f0255df8cc47f27/scripts/build.py)
- [workflow](https://github.com/RockinChaos/nodejs-mobile/blob/223eec9554b5d344b86d1b411f0255df8cc47f27/.github/workflows/build-mobile.yml)
- [release](https://github.com/RockinChaos/nodejs-mobile/releases/tag/v24.22.2)

## 本阶段已实现（设备执行待CI）

新增 `dsh-go/tooling/android-node-probe` 与独立 `android-node-probe.yml`。不是产品启动器。固定版本/摘要在验收工程 `node-runtime-pin.json`；有界提取源码header/指定ABI/完整许可，NDK编译JNI，Gradle缺少库/许可时fail closed。固定脚本由生产FD/SSE模块生成data URL，不在隔离端依赖App私有JS文件。private isolated服务校验owner UID，一进程一次node::Start，不接受外部JS/命令。

真实仪器用例已写：主进程Keystore保存随机假Key，经真实native broker给Node三个模型FD（正常、停滞、回显Key），独立结果FD回传版本/UID/文件拒绝/环境与事件。回传不进日志；测试不在断言信息中打印Key、env或正文。没有真实账号调用。

### 本地实际验证

- 从Google repository XML确认NDK r27d为27.3.13750724，下载ZIP SHA1 `22105e410cf29afcf163760cc95522b9fb981121`吻合。选择提取clang/x86_64 headers/libs到 `/tmp/mbot-ndk27d`，不是完整多ABI NDK。首次裁剪漏libunwind.a，补全x86_64 runtime后编译通过；没有改源代码去绕链接。
- `python3 .../prepare-probe.py --ndk /tmp/mbot-ndk27d --archive /tmp/mbot-node24-mobile.zip`：`Prepared pinned Node 24.22.2 x86_64 and JNI verification glue`。JNI_OnLoad导出、依赖与16KiB ELF LOAD对齐已静态复核。
- 精确打包脚本宿主测试 **1 passed**（真实继承Unix FD、流/取消/错误、无stdout/环境凭据传递）。首次失败为父net.Socket预读导致FD中丢响应字节；改测试在继承后才释放替身响应。真实native PFD没有用户态预读，未因此修改生产逻辑。
- Node验收工程 `assembleDebug assembleDebugAndroidTest`：**BUILD SUCCESSFUL in 1m 34s**，69 tasks executed，两个APK编译打包。首次在JNI尚未准备好时的Gradle失败由输入守卫正常拒绝，未视为代码通过。
- 无本地KVM/adb，没有本地运行设备。新API26/35 workflow待观察，**Android Node仍未验证**；上述均不是full Flutter Bot APK，不证明Intl、better-sqlite3或持久化。

### 验收流程（设备项仍待完成）

1. 新建独立、无launcher的 `tooling/android-node-probe` 验收工程，固定版本/摘要，包内源/header和license有界提取；用NDK r27d编译小JNI胶水，仅x86_64模拟器。复用生产model/Keystore/NativeModelChannels源码，不维护第二套实现。
2. 仅测试APK中的private isolatedProcess服务加载libnode/JNI，owner UID校验，固定打包脚本，一进程只初始化一次。没有主UID任意JS/shell入口，不切换产品启动器。
3. 固定Node脚本消费生产Unix模型FD（真实 `streamNativeModelFd` 模块），分别验证正常SSE、停滞取消、假Key回显拒绝；结果经单独受控结果FD回主进程，只日志测试名/计数。假Key在主进程Keystore生成/保存，不传Node、环境、参数或Agent文件；不用真实账号。
4. 确认实际Node版本/UID/文件拒绝，记录Intl、SQLite原生依赖和数据目录能力。JNI加载不证明better-sqlite3、WAL或完整mcode。
5. API26/35模拟器执行、编译/宿主检查通过后阶段push和更新本卡/HANDOFF；未验证项照录。暂不增加全量Flutter/NDK产品构建承诺。

## 后续仍独立的关口

isolated UID没有主App数据路径权限；目录FD/cwd不自动授予SQLite/WAL/JSON创建权限。必须继续设计验证持久化，不能回退main UID执行Agent。

已从钉点mcode实查 `packages/local-runtime-v2/src/service/model-system/resolution/local-model-resolver.ts` 的 `resolveModelFetch`：BYOK支持 `options.fetchImpl`，返回resolved model的fetch字段。可作为以后受控FD fetch adapter入口；native协议现增加provider-shaped request，保留工具消息/选项；SDK fetch adapter/三协议真实SDK宿主组件已过（T-RuntimeBroker），动态Binder/JNI桥与正式装配仍待做。钉点third_party/pi-mono/packages/ai/src/providers/google.ts不使用options.fetch，不可假定四类一样直接注入fetch。不能只改Base URL或塞dummy Key就宣称mcode已打通。
