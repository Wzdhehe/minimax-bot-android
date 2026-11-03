<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-SQLite：Android 原生依赖组件（2026-11-03）

当前是**隔离Node的内存数据库验收候选**，没有选择持久化方案，不是完整mcode/runtime已运行。默认产品仍DSH。必须继续在feat/t-plugins，不动main/dev/integration。

## 已实现与来源

- `tooling/android-node-probe/sqlite-runtime-pin.json` 固定better-sqlite3 **12.11.1**（与钉点local-runtime-v2一致）、npm源码tar SHA256 **ebf0ed75a7a59dbcb3b24bbd014ef49d9f15bc328e4adcbf516f2a8fadfa2835**、NDK27d/Node24.22.2/x86_64/API26/esbuild0.28.2。没有下载或采用Linux预编译addon。
- `prepare-sqlite.py` 限制tar路径/类型/展开预算并拒绝摘要差异，检查Node准备阶段记录的header清单/逐文件摘要和V8 ABI配置。原源码不修改；按上游defines.gypi、Release NDEBUG/O3及C99/C++20编译SQLite/addon，显式链接已验证的libnode，生成只用于验收的 `libmbot_sqlite_probe.so`。
- JS wrapper由同一个已校验tar的lib/index.js打包，采用better-sqlite3官方nativeBinding对象选项，通过process.dlopen加载只读APK原生库；没有往Agent工作区写.node/JS文件。保留MIT LICENSE与SQLite public domain声明，输入/库摘要保存在忽略的build目录，不提交库/APK。
- 固定Node脚本新增内存数据库检查：事务插入、回滚、中文、JSON函数、serialize/内存恢复、journal_mode=memory。通过JNI传应用原生库目录，不是任意Agent路径或代码入口。原native Key/FD/取消/回显检查保留，报告仍私有回传，不打印env/Key/原始响应。
- metadata.requiresSqlite为true时Gradle检查库、声明和wrapper存在；仪器测试按源码记录的SQLite engine版本和固定数据断言。只启用本验收target，未切产品启动器。

## 实际验证

本地在环境恢复后重新取NDK27d，完整Google ZIP SHA1 **22105e410cf29afcf163760cc95522b9fb981121**匹配；选取x86编译器/sysroot/clang runtime。首次裁剪遗漏顶层libclang_rt.builtins-x86_64-android.a，补齐原包clang runtime后JNI与SQLite均编译成功，没有修改源码绕过链接。

```bash
python3 dsh-go/tooling/android-node-probe/prepare-probe.py --ndk /tmp/mbot-ndk27d --archive /tmp/mbot-node24-mobile.zip
python3 dsh-go/tooling/android-node-probe/prepare-sqlite.py --ndk /tmp/mbot-ndk27d --esbuild dsh-go/tooling/model-sdk-probe/node_modules/.bin/esbuild --archive /tmp/mbot-sqlite-reference/better-sqlite3-12.11.1.tgz
node --test dsh-go/tooling/android-node-probe/probe-bundle.test.mjs
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_*.py' -v
```

原始编译结尾：

```text
Prepared pinned Node 24.22.2 x86_64 and JNI verification glue
Prepared pinned better-sqlite3 12.11.1 Android x86_64 verification addon
```

host脚本1项通过（不执行Android SQLite）；输入拒绝8项通过，其中4项新覆盖SQLite tar摘要/逃逸/Node header变化/V8 ABI。ELF实查依赖仅libnode/libm/libdl/libc++_shared/libc，LOAD段0x4000对齐；这不证明真实16KiB设备兼容。

最后两个仪器APK构建：**BUILD SUCCESSFUL in 43s**（69 tasks，8 executed / 61 up-to-date）；前一轮2m28s。最后metadata/engine版本断言更新后已重建。

**本地无adb/设备/KVM，SQLite Android执行未验证**。CI已扩展现有API26/35 Node矩阵，须观察推送后的真实结果，不能根据编译宣称已跑通。既有Node基线不含SQLite断言。full-ICU源构建run41630373256独立进行中，不能把默认无Intl候选的SQLite编译当成组合验证。

## 仍须解决

1. 先取本阶段API26/35实际SQLite执行证据；失败用稳定错误码/过滤诊断，不输出原始报告或凭据。
2. full-ICU与SQLite组合验证；ARM64、物理真机未验证。
3. **隔离UID持久化**：没有主App私有路径权限；内存serialize只是本用例，不是已批准的持久化方案。不自动引入周期快照，不保证WAL/崩溃恢复。mcode数据路径、事务持久性、重启、备份仍是独立关口。
4. native动态模型请求Binder/JNI桥、正式model resolver/scope、可信Bot身份/MCP/工具权限仍未装配。禁止因此回退同UID任意shell、宽存储或Key进入runtime。
