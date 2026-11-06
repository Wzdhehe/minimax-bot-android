<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Flash-SqliteWindows：Windows 准备 SQLite 与 API35 组合验收

2026-11-06，Codex 派给用户电脑上的 agent。两项依次完成，各自报告，不扩大范围。Codex 同时负责 runtime 文件系统/迁移备份核心；无需等云端 rootfs。

## 同步与写入范围

- 仓库 `Wzdhehe/minimax-bot-android`，仅 `feat/t-plugins`；派卡前 HEAD `f5fd12e`，以 fetch 后的最新远端为准。
- 先读根及 `dsh-go/AGENTS.md`、[当前交接](NEXT-AGENT.md)、[此前 Windows 验收卡](T-Flash-API35Probe.md)和本卡。旧卡中的「断电不再派活」由本卡覆盖。
- 开工报告工作树和未推提交；干净时 `fetch` + `merge --ff-only`。不 reset/stash/覆盖他人的工作。只可 rebase 自己尚未推送的提交；有重叠先回报。
- **仅可提交** `dsh-go/tooling/android-node-probe/prepare-sqlite.py`、同目录已有 `test_sqlite_inputs.py`（确有必要才改）、新增 `test_sqlite_hosts.py`。新增源文件加 AGPL-3.0 头。
- 报告仍只留用户电脑 `docs/research/flash-sqlite-windows.md`，不提交；APK、归档、日志均不入库。不改本任务卡、共享交接文档、CI、pin/producer 权威文件、原生 C++/Kotlin/JS、Node 准备脚本、依赖和 Flutter。
- 每个可验证代码阶段及时 commit/push；不动 main/dev/integration、不 force、不 PR、不发布 Release。推送前同步最新远端，不覆盖 Codex 的并行成果。

## A：SQLite 准备脚本兼容 Windows（独立交付）

当前 `prepare-sqlite.py` 硬编码 Linux NDK；头文件清单用 `str(relative_path)`，Windows 会产生反斜杠；文本读写未显式 UTF-8。修这些宿主兼容问题，不改变 SQLite/Node/NDK/esbuild 版本、ABI、Android API、编译定义、VFS 或验收语义。

1. 仿照只读参考 `prepare-probe.py::ndk_host_tools` 的策略：Linux 保留现有 Android26/x86_64 clang 命令；Windows 调用 NDK `clang.exe` / `clang++.exe` 并显式 `--target=x86_64-linux-android26`。不走 `.cmd`、`shell=True`，不改系统 PATH，不接受调用者自选 Android target；未知宿主明确拒绝。
2. Windows `--esbuild` 传真实 `esbuild.exe`（钉住 0.28.2），不要依赖 npm 的 `.cmd`。不能为方便删版本检查。
3. 头 inventory 与 `node-headers.json` 按正斜杠相对路径比较，仍核验完整文件集合和每项摘要。所有读取/写入源码、JSON、wrapper、许可文本显式 UTF-8，保留原逻辑和输入校验。
4. 在 Linux/Windows 均能跑的宿主测试里覆盖：命令/固定 target、带空格路径作为独立 argv、缺编译器拒绝、未知宿主拒绝、头 inventory 正斜杠、摘要篡改仍在编译前失败。保持既有输入安全测试；不要只断言实现里某一行字符串。
5. 本机真实准备 Node 基础输入后，实际执行 SQLite 准备，必须产出 Android ELF `.so`，不得用 Windows DLL 或宿主 Linux 预编译件替代。记录实际命令和工具版本。Linux 真编译由 Codex 后续复核，你只能报告本机实际验证。

相关命令，路径按本机实际替换：

```text
python -m unittest discover -s dsh-go/tooling/android-node-probe -p "test_sqlite*.py" -v
python dsh-go/tooling/android-node-probe/prepare-probe.py --ndk D:/android-sdk/ndk/27.3.13750724
python dsh-go/tooling/android-node-probe/prepare-sqlite.py --ndk D:/android-sdk/ndk/27.3.13750724 --esbuild <真实 esbuild.exe>
```

准备会改写被忽略的 build/；重复准备 SQLite 前须重新准备 base Node 脚本（占位符只允许替换一次），不手工改生成文件。坏缓存可换新本地路径重新下载并验证；不放宽 hash。

## B：full-ICU + SQLite 的 API35 实测（无源码改动）

先完成 A，再按下述来源授权下载。下载失败不影响 A 独立交付。云端上次在 artifact 存储重定向遇到 Forbidden，并非构建或设备失败。

1. 官方已审核 producer run `41826964330`；artifact 名 `node-full-icu-verification-input`，ID `13318613900`，外层大小 45,047,372 字节（不是内层 zip 的 hash）。**使用前必须重新运行现成 `verify-source-run.py`**，设置 `GITHUB_REPOSITORY=Wzdhehe/minimax-bot-android`、`GITHUB_REF_NAME=feat/t-plugins`；它会检查同仓同分支、成功 producer 和五个权威输入逐字节一致。失败则停在这一小步，报告，不修改验证器或使用未授权产物。
2. 通过后用 `gh run download 41826964330 --repo Wzdhehe/minimax-bot-android --name node-full-icu-verification-input --dir <新的本地目录>`。不得打印 token、带签名下载 URL 或代理认证信息，不关闭 TLS 验证。
3. 内层 `node-full-icu.zip` 算 SHA256，传给现成 `prepare-probe.py --ndk <NDK27d> --archive <内层zip> --source-build-sha <实际摘要>`；再跑 A 的 SQLite 准备。检查 `build/verified-assets/node-validation.json` 的 `requiresIntl`、`requiresSqlite`、`requiresFdVfs` **全为 true**，否则不进入「组合已验证」结论。
4. 使用已有 JDK17（此前本机实跑通过）或已有21，记录版本，不为此改 Java target/依赖。构建命令沿用：

```text
gradle --no-daemon --max-workers=2 -p dsh-go/tooling/android-node-probe assembleDebug assembleDebugAndroidTest -Pkotlin.incremental=false
```

5. 仅使用 `adb -s emulator-5554` 的 `mbot-api35`，先核实 API35/x86_64/boot 完成，再安装两个验收 APK。不能清数据、wipe AVD、卸载其他 App、修 AVD 配置或操作其他设备；签名不兼容导致需要卸载时先回报。
6. 先跑 `BotSocketConnectionTest`、`IsolatedNodeModelTest` 和新增 `RuntimeCoordinatorProbeTest` 三类；再对**这个独立验收包**跑完整 instrumentation suite。以 `pm list instrumentation` 的实际 runner 为准，不编包名/数量；记录逐项结果、汇总及终态。失败保留日志回报，不能改设备用例或生产代码凑通过。
7. 首两类覆盖此前6项；新增 coordinator 两项此前只编译没跑过。full-ICU 才会走真实官方 Bot 服务分支，SQLite metadata 为 true 才能称持久化/VFS分支实际执行。请读当前断言说明实际走了哪些分支，不能只凭测试总数做结论。

## 回报与边界

回报同步基线、最终 SHA、精确改动文件、实际测试/准备/构建/设备命令及原始汇总；APK 只报本地路径、大小和 SHA256，不提交或发布。失败说明卡在哪一步；没有执行的一律「未验证」，不自动扩展任务。

即使全部通过，也仅是 API35/x86_64 独立验证包：**不是完整 mcode runtime、真实 provider/model turn、ARM64/物理真机或可用产品 APK**。不需要任何真实用户 Key。报告留本机，共享开发进度由 Codex 收到结果后更新。

## 审查 R1（2026-11-06，d68e48f 后）

Codex 已将你的 A 阶段提交与原生 FD metadata 提交正常合并（`d2d6c16`，无 force），未改你负责的三个路径。Linux 实跑 `python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_sqlite*.py' -v`：15 项中14过、1 error。

失败是 `test_the_compiler_runs_with_a_fixed_target_and_no_shell`：`fixture()` 只创建 Windows clang.exe，但该测试调用 `prepare()` 时没有 mock `platform.system()`，在 Linux 实际选择 Linux 编译器，报 `Missing pinned NDK compiler or Android x86_64 runtime`。Windows 本机通过不能证明此测试跨平台。

请只在 `test_sqlite_hosts.py` 修复：这个用例明确验证 Windows argv 时，将宿主检测固定为 Windows；若另加 Linux 完整调用用例，应提供对应 fixture 和 Linux 参数断言。不要 skip Linux，不要放宽生产编译器检查或删原输入安全断言。单独提交修复，B 阶段可以继续，回报实际输出。Codex 之后再在 Linux 复跑。此项是测试可移植性问题，尚未发现 production 脚本需要为此改动。

## 交付结果补记（用户转述，2026-11-06）

A `d68e48f`、R1 `8cdc523` 已纳入。Codex实查R1仅修测试宿主选择并增加Linux完整argv用例，真实Linux复跑 `test_sqlite*.py` **16项OK**；生产检查未放宽，R1接受。以下B来自用户转述本机agent的实跑报告，云端没有读取D盘原始日志。补报确认：**B构建HEAD为`d68e48f0f491914cb77835ffa1585e14dbc19292`，已跟踪源码无未提交改动**；只有被忽略的构建产物和不参与构建的本地报告。不能用报告最终8cdc523代替B构建版本。

Windows工具为JDK17.0.10、Gradle9.3.1、NDK27d；本机A实际生成Android26/x86_64 ELF共享库（2,159,336字节，SHA256 `f44e9bf3ec9d6f97a4e1b3ad20b98c5e3c3d1bcc3f3ef81a266cdd33af6a13a7`）。不是Windows DLL/宿主Linux件。SQLite源归档经TLS中断后换本地路径下载，摘要仍匹配原pin，未放宽校验。

B授权初次拒绝源于本机autocrlf将权威输入变为CRLF。未改验证器：以`git cat-file blob`导出提交原字节到临时同布局树，再运行原验证器，得到 `Verified same-repository source build, branch, producer and exact build inputs`，退出0。下载producer41826964330后，内层zip45,047,224字节、SHA256 `cd8823cf323e0c7216902a9a2dfb4a50542a99f85ffe21e4a2fa99e3436e9747`；外层artifact45,047,372字节。准备后requiresIntl/requiresSqlite/requiresFdVfs全true，SQLite3.53.2；构建 `BUILD SUCCESSFUL in 2m 7s`（71 tasks，23 executed）。没有改producer权威输入或校验器。

两个APK仅留本机：主包76,378,488字节，SHA256 `8383bb695f81e718244d7f76efbf7ceaf569f94c6f9f9f585a97e21f455d4e51`；仪器包1,110,372字节，SHA256 `637967a69828e00579683040ee1fb326008cf95874847eb220fe5e8a1677eb45`。只安装至emulator-5554/API35/x86_64，未清数据/wipe/卸载其他App/修AVD；未提交发布二进制。

```text
三类定向：Time: 4.45 / OK (8 tests) / INSTRUMENTATION_CODE: -1
完整独立验收包：Time: 8.97 / OK (58 tests) / INSTRUMENTATION_CODE: -1
全部逐项code 0；0失败、0错误、0跳过
```

回报说明实际Intl/官方Bot服务分支（ready首字节B，匿名403、授权200含bots）、BotStore/Memory/journal/backup/pending恢复，以及实际SQLite加载/数据、四FD WAL/重开/integrity均断言通过。coordinator两项此前只编译，现由本机实际执行；其ready仍是**合成组件事件**，不能证明生产guest就绪/正式launcher。

两个APK写出时间分别为本机10:33:05.415、10:33:22.720（+0800），之后10:37:40才快进到1d2f0af，10:39:27提交R1。产物自带 `third_party/better-sqlite3/build-inputs.json` 的adapter摘要也与d68工作树字节匹配，与后续版本不匹配。因此该包明确**不含02fb530、456956d、1d2f0af**，新增FD metadata接口既未编进此包，也没有由本次设备运行验证。

范围仍仅API35/x86_64独立验收包。API26/ARM64/物理真机、完整mcode runtime、真实provider/model turn、当前新metadata/shim/kernel、产品APK均不能据此写成通过。新metadata设备验收必须重编并增加直接接口断言，不能仅重复旧8/58项就宣称覆盖新能力。
