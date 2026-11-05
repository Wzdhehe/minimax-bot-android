<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机agent任务：Bot Endpoint运行代次保护

状态：已派任务，尚未实现/验收。2026-11-05。此卡覆盖本机留言的runner/PRoot/端口轮询建议，本轮只做下面一项。

## 要解决的问题

`BotRuntimeEndpoint.publish(baseUrl, token)`和无条件`clear()`没有区分服务代次。A运行被B替换后，A迟到的死亡/停止回调若调用clear，会误清B。当前没有正式launcher调用它，趁接线前明确所有权。

## 修改范围

- `dsh-go/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/BotRuntimeEndpoint.kt`
- `dsh-go/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/bot/BotRuntimeEndpointTest.kt`
- 本卡：只在文末追加实现、提交、实际命令和测试输出；不要改共享HANDOFF/NEXT-AGENT。

不修改runner、launcher、MainActivity、MethodChannel、Dart/UI、descriptor、native broker、CI、rootfs或依赖。主agent在推进`tooling/android-node-probe`的真实Bot服务验收，不要碰该目录。

## 实现合同

1. publish返回只给可信native宿主持有的、绑定当次发布的所有权句柄，例如Closeable lease。关闭该句柄时，仅当当前Endpoint仍属于该句柄才清除。A关闭不能清B；重复关闭无副作用。
2. 线程安全；参数仍先校验再改变状态。`descriptor()`维持当前`baseUrl/token`形状，不把句柄、代次信息或provider Key发给Flutter/guest。
3. 不提供给迟到回调使用的无条件clear接口；如测试需要清理，优先关闭本测试持有的句柄，不增加远程重置操作。没有正式launcher调用方，不需要为了兼容不存在的调用方保留不安全接口。
4. 句柄/Endpoint的toString、错误和测试失败输出均不打印token。仅Bot会话凭据允许存在此处，provider Key仍只在native broker/Keystore。

## 必须验证

- A发布→B发布→A关闭，B仍存在；B关闭才为空。
- 同一句柄重复关闭；旧句柄关闭与新publish并发，不能清除新的发布。
- 无效URL/token不会覆盖已有有效Endpoint。
- 原Endpoint参数及descriptor契约测试继续通过。

并发测试使用有界Latch/Barrier和超时，禁止靠sleep猜顺序；用本机实际存在的JUnit/Gradle工具运行定向测试，原样记录输出，不跑已有数千个失败的全量golden测试来代替本项。

## 提交规则

先确认工作树，`git fetch origin feat/t-plugins`后快进到最新远端；不丢本机未提交工作。仍用feat/t-plugins。只暂存明确路径，一项一个提交。推送前再fetch；如远端前进，只在自己尚未推送的提交上rebase，不改已推历史、不force。冲突涉及卡外文件时停止并报告，不覆盖主agent修改。报告实际完整SHA、测试结果及远端确认。

可额外用**独立提交**只恢复`dsh-go/runtime/build-runtime.sh`的100755执行位（该改动已获授权）；不在该提交改脚本文本、pin或引擎布局。不删除/忽略上游golden资产，不建PR、不动main/dev/integration、不发布APK。

## 本轮暂不实施

- 不另起PRoot Bot进程：进程不同不等于UID不同。
- 不把Linux glibc的sqlite.node放入Android bionic Node。
- 不以descriptor已写入或guest端口探测作为Endpoint就绪证据。Main侧桥、isolated进程与FD能力的就绪/退出协议由主agent后续接线。
- 不等待rootfs来做本项；也不直接将DsBotApi批量替换为MiniMaxBotApi，两者模型和接口不同。

## 本机agent回填

待实现、待实际验证。
