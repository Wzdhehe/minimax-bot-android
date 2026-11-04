<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-NativeDataFiles：Main持久数据库文件所有权（2026-11-04）

当前为生产源码中的低层文件组件及独立验收接入，**不是完整runtime-data lease/撤销机制，不是正式App启动器已切换**。**44c0190/run41760010243已API26/35各7项通过、0失败/错误/跳过**；仍不是完整lease/撤销。

## 设备结果（最新）

44c0190 / run41760010243：

```text
API26 id13290621046: node-candidate-results-api-26-t7-f0-e0-s0-passed-l0
API35 id13290685317: node-candidate-results-api-35-t7-f0-e0-s0-passed-l0
```

原模型/Key/WAL两PID用例+六owner用例全通过。hardlink用例的保证按下一段边界描述，不能把平台权限拒绝自动描述为owner自身nlink分支在设备上跑过。

## 首次设备结果与hardlink测试边界

a62530d/run41758485389 的API26/35均真实返回`t7-f1-e0-s0-unclassified-ld97`，失败堆栈行103是**测试的Os.link构造fixture**，还没进入owner拒绝断言；其余六项无失败。同期native broker host CI41758485315成功、Android broker41758485383的API26/35成功。不能把这轮写成七项全过，也不能据此说owner接受了hardlink。

修正用例为“平台或owner不能被hardlink绕过”：如果Os.link以EPERM/EACCES拒绝，必须确认目标未创建、原始私有哨兵不变；其他错误仍失败。如果平台允许创建，则先确认nlink=2，再必须由生产owner拒绝且原内容不变。**不启用宽权限、root或shell去绕过平台限制，不使用Assume/skip冒充验证。两个模拟器上owner自身的nlink拒绝分支尚未证实实际触发**，后续不能据七项passed擅自扩大这一点。

仅调整设备测试，生产校验保持不变。两个APK实际输出：`BUILD SUCCESSFUL in 36s`，`69 actionable tasks: 6 executed, 63 up-to-date`；此后44c0190设备结果见顶部。

## 实现

`isolation/RuntimeDatabaseFiles.kt` 只接受可信Context，不接受Agent/UI传入的路径、目录或文件名。必须是Main UID并在worker线程调用open，避免同步创建/fsync阻塞UI。固定位置为：

```text
context.noBackupFilesDir/minimax-bot-runtime/v2/sqlite/
  runtime-state.sqlite
  runtime-state.sqlite-wal
  runtime-state.sqlite-shm
  runtime-state.sqlite-journal
```

v2/sqlite/runtime-state.sqlite来自固定上游DatabaseClient；Key prefs/Keystore不在这棵目录。noBackup位置不代替手动备份实现，全App原有allowBackup=false策略不变。

每级固定目录都校验lstat为Main拥有的目录，新增子目录0700；同步父目录以持久化新祖先目录项。最后文件用O_CREAT/RDWR/CLOEXEC/NOFOLLOW/NONBLOCK打开，**没有O_TRUNC**；只接受Main拥有、无group/world权限、nlink=1且互不重复的普通文件，拒绝符号链接、硬链接、目录/其他类型。同步文件和叶目录后才交出四个PFD副本。Main不打开SQLite连接，不向隔离端提供任何目录FD或真实路径。所有出错路径清理已开的描述符，平台异常/文件路径/内容不进入异常cause或诊断。

close仅关闭本地副本，不删、不截断、不重命名数据文件；重复close安全，关闭后不再发新副本。**已经交出的FD仍可用，不能声称被撤销**。正式launcher需先终止隔离执行单元，再退休owner；Binder身份、单一runtime调度、子进程退出与完整lease仍缺。

## 验收接入

Node验收目标直接复制编译此生产源码，原模型/SQLite两PID用例现在使用该owner，fixture仅覆盖noBackupFilesDir为独立测试根；不伪造数据库内容，不再由测试手写四文件初始化。隔离服务ready阶段尝试调用owner，必须得到OWNER_ONLY且不触碰目录；Main检查这个结果。

另有六项设备用例：关闭重开保留文件内容且只发四普通FD、关闭owner不冒充撤销、DB symlink不触碰外部私有哨兵、目录symlink不创建外部子项、hardlink拒绝且原内容保持、UI线程在文件操作前拒绝。symlink/hardlink测试先经生产owner创建合法私有目录，再替换单个测试文件，避免目录权限错误让拒绝测试假通过。全部仅用专用测试数据，不使用真实Key。

report-results新增此固定测试文件名/有限data错误类别，artifact行号`ldN`代表RuntimeDatabaseFilesTest，原`lN`仍指IsolatedNodeModelTest。不打印任意异常路径/正文。Python检查22项过；首次两个APK构建1m10s成功；最终六项owner用例后两个APK实际输出：

```text
BUILD SUCCESSFUL in 43s
69 actionable tasks: 6 executed, 63 up-to-date
```

六项owner用例已随顶部run执行；这不覆盖完整产品或物理真机。

## 下一步

1. 当前owner版真实计数为API26/35各7项通过。后续新增原子发布探测见T-RuntimeFS，不能根据旧7项推断新probe已运行。
2. 取得实际结果后，再将owner绑定正式launcher的身份/退出生命周期，不把close描述为撤销。固定JS的虚拟state.db仍映射同一FD；整个mcode路径/模块适配尚未装配。
3. 完整FS、online backup/替换、新文件创建、JSON/history、Key文件拒绝与可信Bot/MCP身份仍另做。不得把本组件暴露为Agent任意路径open接口。
