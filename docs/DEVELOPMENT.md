<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 开发与复现入口

更新：2026-11-10。现状与证据以 [HANDOFF](HANDOFF.md) 为准，后续工作以 [NEXT-AGENT](handoff/NEXT-AGENT.md) 为准。**正式入口已是官方Bot，API35/x86_64已有真实MiniMax文本产品验收**；根目录app是废弃Java原型，旧DSH rootfs不是本产品构建前提。[整理前的开发记录](https://github.com/Wzdhehe/minimax-bot-android/blob/e9d0bebd5fc2487b87d3b5a77b32c2107a0fe54e/docs/DEVELOPMENT.md)保留在Git历史。

## 环境与版本

| 项 | 当前已用/要求 |
|---|---|
| 上游checkout | 干净的mcode-webui/minimax-code@97818b2890b4acae1c6bd1200a226ff506680628；[权威pin](UPSTREAM-RUNTIME.md) |
| Android Node | 24.22.2，必须已授权full-ICU源码产物；无Intl的发行候选不能代替 |
| JS构建工具 | esbuild0.28.2；model-sdk-probe与mobile-runtime-bundle各自锁文件，不随意npm update |
| SQLite | better-sqlite3 12.11.1 / SQLite3.53.2；源码pin、完整defines、目标ABI和头清单核验 |
| Android | minSdk26 / targetSdk36；NDK27.3.13750724；CMake3.22.1 |
| Java/Gradle | 源码目标Java17；Windows JDK17.0.10 + Gradle9.3.1实际构建通过；CI具体JDK按workflow，不要求为过构建降target或依赖 |
| Flutter | Windows已用3.47.9；先查本机flutter --version，与项目SDK/锁文件一致，不盲升依赖 |
| Widget字体 | Linux示例DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts；Windows D:/toolchains/test-fonts；须实际存在对应Noto CJK字体 |

已用位置只是线索：云端repo=/workspace/minimax-bot-android、上游=/workspace/scratch/mbot-mcode-reference、工具曾在/workspace/toolchains；Windows repo=D:/mbotapp、上游=D:/scratch/minimax-code、SDK=D:/android-sdk、NDK位于其ndk/27.3.13750724、Flutter=D:/toolchains/flutter。/tmp及云机可重置，D盘不属于云端，**先探测再用**。不打印完整env或凭据。

## 获取源码与依赖

在repo根检查git status/branch，fetch并merge --ff-only origin/feat/t-plugins。保护main/dev/integration，不reset/stash覆盖他人，不另起分支、不PR/force。修改dsh-go前读其AGENTS/CONTRIBUTING。

需要JS构建或SDK测试时：

```bash
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/model-sdk-probe
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/mobile-runtime-bundle
```

可复用已有正确node_modules，不为每次UI打包重装。依赖目录不入库；若云端临时用symlink指向/tmp依赖，结束前unlink该symlink，不能把它提交。新机器的Flutter依赖先按项目锁文件恢复；已有.dart_tool时定向测试用--no-pub，避免无关lockfile变化。

## 首次准备x64 Android原生输入

1. 先读 [Node专题](handoff/T-AndroidNode.md)及 `verify-source-run.py`。此前成功x64 producer为同仓同分支run **41826964330**；内层node-full-icu.zip为45,047,224字节，SHA256 `cd8823cf323e0c7216902a9a2dfb4a50542a99f85ffe21e4a2fa99e3436e9747`。这不是无Intl发行归档的SHA，也不是API Key。
2. 下载/使用前以当前权威输入实查授权，不只信这串摘要：

```bash
GITHUB_REPOSITORY=Wzdhehe/minimax-bot-android GITHUB_REF_NAME=feat/t-plugins \
  python3 dsh-go/tooling/android-node-probe/verify-source-run.py 41826964330
```

验证器核对同仓/同分支/原工作流/build成功以及五个输入的精确字节：android-node-source.yml、node-source-build.json、build-source.py、smoke-compiler-cache.py、test_source_recipe.py（完整路径读脚本INPUTS）。若artifact过期、网络/授权失败或源码真变了，报告原因，重新生产并授权，不能跳检查或传任意--source-build-sha。

Windows core.autocrlf=true可能令这五个工作树文件成为CRLF而原producer blob为LF。本机已用**git cat-file blob导出当前HEAD原始字节到独立临时树，在该树运行同一个未改验证器**通过；不得改验证器、SHA或用字符串替换伪造输入。先确认五个Git blob自producer以来确实未变。

3. 使用已授权的本地归档，依次准备（变量均须指向实际路径）：

```bash
python3 dsh-go/tooling/android-node-probe/prepare-probe.py \
  --ndk "$MBOT_NDK" --archive "$MBOT_NODE_ARCHIVE" --source-build-sha "$MBOT_NODE_SHA"
python3 dsh-go/tooling/android-node-probe/prepare-sqlite.py \
  --ndk "$MBOT_NDK" --esbuild "$MBOT_ESBUILD_EXECUTABLE" --archive "$MBOT_SQLITE_ARCHIVE"
python3 dsh-go/tooling/android-node-probe/prepare-backup.py \
  --ndk "$MBOT_NDK" --archive "$MBOT_SQLITE_ARCHIVE"
```

注意：prepare-sqlite的--esbuild是目标宿主的**可执行文件**，Windows例为model-sdk-probe/node_modules/@esbuild/win32-x64/esbuild.exe，不能传.cmd。下面bundle构建的--esbuild-module才是esbuild/lib/main.js。

SQLite归档是钉住的12.11.1 better-sqlite3.tgz，SHA256 `ebf0ed75a7a59dbcb3b24bbd014ef49d9f15bc328e4adcbf516f2a8fadfa2835`。下载截断应更换新本地归档并核SHA，不能因“文件已存在”忽略损坏。要求生成requiresIntl/requiresSqlite/requiresFdVfs三项为true，**只能由准备脚本得出，不手改**。

## 日常产品构建：只重建变化层

只有Flutter/UI变化且所有原生/JS输入闸门通过时可直接flutter build。改了host JS、SDK适配、构建接缝/资源时，必须重建partial/mobile并prepare-product；改了VFS/backup/JNI等准备输入时还要先重跑对应native准备，不能复用陈旧库。通常不必重编Node本体。

repo根、干净钉点checkout、全新输出子目录：

```bash
mkdir -p "$MBOT_STAGE"
node dsh-go/runtime/minimax-bot-host/build-runtime-bundle.mjs \
  --source "$MBOT_WEBUI_SOURCE" \
  --esbuild-module dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js \
  --out-dir "$MBOT_STAGE/partial"
node dsh-go/tooling/mobile-runtime-bundle/build.mjs "$MBOT_WEBUI_SOURCE" \
  "$MBOT_STAGE/partial" dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js "$MBOT_STAGE/mobile"
python3 dsh-go/tooling/mobile-runtime-bundle/prepare-product.py --abi x86_64 \
  --ndk "$MBOT_NDK" --mobile-bundle "$MBOT_STAGE/mobile" --source-build-sha "$MBOT_NODE_SHA"
(cd dsh-go/app && flutter build apk --debug --target-platform android-x64 --flavor x64)
```

- partial/mobile须不存在；MBOT_STAGE选repo外、独有的新后缀。prepare-product默认输出dsh-go/tooling/mobile-runtime-bundle/build/product/x86_64；已有目录按本机已用流程改名为独有stale目录，再准备新产物，不覆盖/改manifest。输出过多后只清自己确认不用的临时目录，不删用户数据、工具链、golden或别人工作。
- prepare-product检查来源/ABI/Node头、full-ICU、SQLite/backup输入、bundle/资源/锁摘要；Gradle再次verify exact inputs。任何拒绝都需处理真实原因，不注释闸门。
- APK：dsh-go/app/build/app/outputs/flutter-apk/app-x64-debug.apk。原生库未压缩留APK中；无assets/runtime/* PRoot rootfs。不改useLegacyPackaging为true。
- Windows已成功的PowerShell示例见 [原构建小卡](handoff/T-Flash-ProviderStartupRecovery.md)，用全新stage后缀；语法按本机shell改，不把POSIX变量写成PowerShell变量。
- 产品Gradle实际Kotlin增量缓存出错时可按本机已用方式禁用kotlin.incremental，记录精确错误；不是降低源码target、删缓存遍历整盘的理由。

ARM64独立编译、来源收据和给Ubuntu agent的零源码改动任务见[ARM64执行手册](handoff/ARM64-RUNBOOK.md)。新增verify-arm64-source-run.py仅授权同仓同分支的ARM64候选，outerArtifactSha256是GitHub外层ZIP摘要，不能当内层Node归档SHA。

**ARM64产品准备（2026-11-10 B2已接通，仍非设备验收）**：prepare-probe.py仍固定x64。先用prepare-arm64-node.py从已提交审阅记录的候选写入`android-node-probe/build/abi/arm64-v8a/`，再`prepare-sqlite.py --abi arm64-v8a`（只读该ABI目录，缺输入就拒绝，不回退x64），并跑已支持双ABI的prepare-backup.py。然后：

```bash
python3 dsh-go/tooling/mobile-runtime-bundle/prepare-product.py --abi arm64-v8a \
  --ndk "$MBOT_NDK" --mobile-bundle "$MBOT_STAGE/mobile" \
  --source-build-sha "$MBOT_ARM64_ARCHIVE_SHA"
(cd dsh-go/app && flutter build apk --debug --target-platform android-arm64 --flavor arm64)
```

`--source-build-sha`必须是已提交审阅记录中的内层候选归档SHA，不能传x64 SHA或任意摘要。闸门失败要查真实输入，不注释或手改标志。APK在`dsh-go/app/build/app/outputs/flutter-apk/app-arm64-debug.apk`，不入库。编出ARM64 APK不等于设备通过；无用户指定的ARM64真机就写未验证，不能拿x64模拟器补勾。Linux/ARM64的npm预编译addon不是Android/Bionic库。命令与实测摘要见[ARM64手册](handoff/ARM64-RUNBOOK.md)。


## 按修改面验证，不重复全仓

虚拟文件系统/附件改动先真实构建mobile bundle，再跑其产物，不用未编译源码替代打包验证：

```bash
MBOT_MOBILE_FS_BUNDLE="$MBOT_STAGE/mobile/filesystem.mjs" \
ESBUILD_MODULE="$PWD/dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js" \
node --test dsh-go/tooling/mobile-runtime-bundle/test-filesystem.mjs \
  dsh-go/tooling/mobile-runtime-bundle/test-uploads.mjs
```

11-11此组合6项全过；`bot-host.yml`现有上游构建job已加实际mobile构建与此检查。这里只是宿主JS/文件/真实官方HTTP服务；未编Android JNI、未跑设备。修改mobile-filesystem必须重建mobile与prepare-product才能进入APK，Node本体和未变的SQLite/backup无需因此重编。

常见Node/model改动，从repo根：

```bash
WEBUI_SOURCE="$MBOT_WEBUI_SOURCE" \
ESBUILD_MODULE="$PWD/dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js" \
node --test dsh-go/runtime/minimax-bot-host/runtime-host-session.test.mjs \
  dsh-go/runtime/minimax-bot-host/runtime-native-review.test.mjs \
  dsh-go/runtime/minimax-bot-host/native-probe-client.test.mjs \
  dsh-go/runtime/minimax-bot-host/agent-port.test.mjs \
  dsh-go/runtime/minimax-bot-host/model-sdk-fetch.test.mjs \
  dsh-go/runtime/minimax-bot-host/native-model-binding.test.mjs \
  dsh-go/runtime/minimax-bot-host/runtime-descriptor.test.mjs \
  dsh-go/runtime/minimax-bot-host/bot-runtime-start.test.mjs
```

必须看skip数。未设置上游/esbuild会跳过真实gateway包装，不能写“原runtime通过”。涉及目录/FD/服务再加对应model-catalog-projection、native-model-route、model-fd-client、service、restricted-service-bundle等文件，不必每次跑全部组件。

Kotlin/native修改用：

```bash
MBOT_NATIVE_DEPS=/path/to/pinned-jars KOTLINC=/path/to/kotlinc \
  bash dsh-go/tooling/test-native-model-broker.sh
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_*hosts.py'
python3 dsh-go/tooling/android-node-probe/test-sqlite-backup.py --java-home /path/to/complete-jdk
```

先按脚本/对应workflow准备真实依赖；最后一条是Linux JDK+JNI宿主SQLite/worker测试，不是Android执行。android-broker-probe/node-probe可做组件编译/仪器测试；已通过的23/58套件不需要为纯UI/文档变化重跑，不能代替产品端到端。

App从dsh-go/app运行（字体变量用实际存在路径）：

```bash
DSH_TEST_FONT_DIR=/path/to/test-fonts flutter test --no-pub \
  test/bots/bot_app_test.dart test/bots/bot_creation_attachments_test.dart \
  test/bots/bot_settings_hub_test.dart
flutter analyze --no-pub lib/bot/bot_app.dart lib/bot/bot_home.dart lib/bot/bot_settings_hub.dart
python3 tool/l10n_merge.py --check
```

改文案须改lib/l10n/parts中英ARB，再运行python3 tool/l10n_merge.py（需要Flutter在PATH）；也可--no-gen后flutter gen-l10n。不手改合并ARB或gen目录。golden有Windows/Linux光栅化历史差异，发生失败须与准确基线对照，不能删golden、跳过新增失败或把所有失败都归因字体。

## 完整host产品模型回合夹具

`dsh-go/tooling/mobile-runtime-bundle/check-model-catalog.py`直接使用完整mobile bundle、原factory/Bot/SDK、真实host FD SQLite及socketpair模拟native：

```bash
python3 dsh-go/tooling/mobile-runtime-bundle/check-model-catalog.py \
  --node /path/to/POSIX-node24.22.2/bin/node --bundle /path/to/mobile \
  --addon /path/to/host-fd-sqlite.node \
  --wrapper dsh-go/tooling/android-node-probe/build/sqlite-probe/wrapper.cjs \
  --directory /path/to/private-fixture --scenarios anthropic anthropic-fail
```

默认scenarios验目录空/默认/停用/无默认/加强参数；上述两个另验真正模型通路成功及native拒绝不重提。使用Node24.22.2对应headers现编**host** FD addon，不能拿Android .so给宿主加载、也不能把host addon进APK。相关构建线索在test-fd-vfs.py及prepare-sqlite.py；test-fd-vfs.py --node-probe要求先准备匹配headers/wrapper。之前云端/tmp中的临时compile.py不是持久构建入口，文件丢失就从仓库源码重建，不假称有产物。这个夹具不需真实Key，也不证明Android/Binder/真实MiniMax。诊断只输出固定PASS/稳定码，不外发fixture全部JSON。

## 指定设备操作与交付

只用用户指定的序列号，先只读确认API/ABI/boot；install -r同应用ID，不wipe/清数据/降级卸载、不触碰其它设备。记录构建HEAD和tracked状态、APK大小/SHA、ready和真实Main/guest UID；peer或grants_sent不当作ready。真实Key只能由用户在App里配，不能进adb input、脚本/env/日志。旧版本无关失败不代表新包失败，反之也不能复用旧绿灯。

已结项 [真实provider卡](handoff/T-Flash-RealProviderTurn.md) 和旧58/23组件不用重派；下一项只按NEXT-AGENT拆卡。报告/APK留本机，不提交；共享进度写HANDOFF。CI此前曾因计费零步骤启动，当前状态须实查；push不等于CI成功。

文档改动只需：

```bash
python3 tooling/docs-links/check_links.py --root . README.md docs
```

检查器只覆盖支持的相对文件链接，不检查锚点或远程可达性，详见[说明](../tooling/docs-links/README.md)。最后明确暂存自己的路径、commit、正常push到feat/t-plugins并实查远端SHA/保护分支。不要git add . / -A，不提交二进制、node_modules、临时报告或任何密钥。
