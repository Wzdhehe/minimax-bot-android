<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# mcode-agent-table：对手机多 Bot 的用途

2026-11-04用户提供，静态源码检查固定到MiniMax官方仓库提交[`f230386ba0e405f2470d4f42863df3a5c0399297`](https://github.com/MiniMax-AI/mcode-agent-table/tree/f230386ba0e405f2470d4f42863df3a5c0399297)。[MIT许可证](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/LICENSE)，package版本0.1.0、Node要求>=22.19。本轮未安装依赖、未运行测试/CLI/真实模型或Android，未复制代码与素材。

## 结论

**有帮助，主要对应后续群聊、Bot互派任务、审批和任务核验；不是Android runtime移植方案，也不是新的mcode插件市场。** 当前先完成本机执行/持久化/可信Bot身份，不为了引用新项目再重写引擎或并排建立第二套任务数据库。

BYOB Table Protocol由共享Table Core维护账本；每个参与者仍带自己的Bot、模型、记忆与权限。它的多主人语义比本产品当前“单个手机用户管理多个Bot”更广。可以借鉴协议与状态机，不能把多主人签名流程或网络部署直接当作本机Bot注册已完成。

## 已检查的源码与可借鉴点

| 文件（均为上述固定提交） | 实现 | 对本项目的价值与边界 |
|---|---|---|
| [protocol.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/protocol.ts) | seat/owner/table、能力卡、任务状态、强制termination条件 | 群聊循环要有轮数/预算/条目上限；任务不能把模型“说完成”当完成 |
| [policy.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/policy.ts) | broadcast、debate、pipeline、hub_spoke、delegation与发言权 | 可对照Chief/群聊回复模式与互派队列；先确认官方mbot已有能力，避免两套调度竞争 |
| [table.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/table.ts) | proposal→owner approval→executor receipt→其他seat verify；禁止self verify、watchdog stalled、账本重放 | 可借鉴任务去重/不确定状态/核验与UI展示；Main仍须绑定真实Bot身份和执行权限，不信任Agent自己填owner/seat |
| [crypto.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/crypto.ts) | Ed25519签名、nonce、canonical JSON | 对未来可信审批有参考价值；样例私钥是Node内DER字符串，不可据此把本项目的Key/签名私钥发到Agent或写其可读文件 |
| [client.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/client.ts) | 席位SDK、稳定prefix+新增tail、披露检查 | 可减少群聊重复上下文；披露检查在客户端，不是Android文件/网络沙箱或权限边界 |
| [store.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/store.ts) | 直接node:fs同步JSON/JSONL读写、token hash落盘 | 仍依赖我们未完成的受控FS；不能用这个Store绕过Main broker，不能声称其普通写文件已满足我们的fsync/unknown/恢复契约 |
| [adapters/mcode.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/adapters/mcode.ts) | code-sdk每席位讨论thread；批准后另建permission='full'执行thread，支持env/dataDir/cwd | 不能直接接手机生产入口；需改成现有native-only模型broker、可信Agent port、受控文件和权限生命周期，不开启同UID CLI/shell |
| [mcp.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/mcp.ts) / [server.ts](https://github.com/MiniMax-AI/mcode-agent-table/blob/f230386ba0e405f2470d4f42863df3a5c0399297/src/server.ts) | 12个BYOB工具、HTTP/SSE；审批elicitation不支持时拒绝 | 可参考交互契约，不直接新增远程服务、stdio子进程或新MCP权限入口 |

## 明确不引入的部分

- Rabbit Hole是Mini App，可视化程序仍不适配。它的会议界面仅可作交互参考，不作为插件安装市场。
- 不运行演示中的MINIMAX_BENCH_KEY环境变量、owner私钥文件与普通CLI执行链。本轮没有取得/提交任何真实Key。
- Table协议的签名/能力卡不代替Android独立UID、Main provider broker、停止/FD撤销和正式Bot身份注册。
- 不因README说有缓存友好投影就声称手机token命中率或性能已验证；上游自己也将缓存测量列为后续项。

## 建议复用顺序（尚未实现）

1. 完成当前runtime与原Store/MemoryStore恢复，建立Main绑定的Bot/会话身份。
2. 将group/chat映射到table、Bot映射到seat，先对照原mbot的群聊/Chief/任务语义，明确哪些只是UI投影。
3. 先抽取或借鉴纯状态机/发言轮次和终止条件；新增拒绝伪造owner、重放nonce、自核验、执行未批准任务的验收，不先移植网络/CLI演示。
4. 只在现有Agent port及native权限边界上适配任务执行，保留官方mbot数据源；任何MIT源码复制保留上游版权/许可与固定提交，本仓库新文件加AGPL头。
