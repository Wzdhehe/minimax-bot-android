<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!-- Copyright (C) 2026 Wzdhehe -->

# 上游 Runtime 钉点

本项目的产品 runtime 跟 [mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code) fork（MIT）。
官方 [MiniMax-AI/minimax-code](https://github.com/MiniMax-AI/minimax-code) 是上游，**更新由团队自行合并进 fork**，
本项目不直接钉官方仓库。

本文件是钉点的**人类可读**记录。机器可读的真值源是
[`dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs`](../dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs)，
二者必须逐字段一致，由 `upstream-runtime-pin-check.mjs` 强制校验。

## 钉点

<!-- pin:begin -->

```json
{
  "repository": "mcode-webui/minimax-code",
  "repositoryUrl": "https://github.com/mcode-webui/minimax-code",
  "upstream": "MiniMax-AI/minimax-code",
  "upstreamUrl": "https://github.com/MiniMax-AI/minimax-code",
  "commit": "97818b2890b4acae1c6bd1200a226ff506680628",
  "commitDate": "2026-10-31T15:27:15Z",
  "license": "MIT"
}
```

<!-- pin:end -->

| 项 | 值 |
|---|---|
| 仓库 | `mcode-webui/minimax-code`（fork，MIT） |
| 钉点 commit | `97818b2890b4acae1c6bd1200a226ff506680628`（短 `97818b2`） |
| 钉点日期 | `2026-10-31T15:27:15Z`（commit committer date，UTC） |
| commit 标题 | `style(webui): refine button states and layout alignment` |
| tree | `5d79407ffcd54715567b49e0fac46c8ccc6e2ec8` |
| 上游 | `MiniMax-AI/minimax-code`（官方，MIT） |
| 许可 | 上游树为 MIT；本仓库为 AGPL-3.0（见 `dsh-go/LICENSE`） |

## 宿主绑定了哪些上游文件

宿主适配层 `dsh-go/runtime/minimax-bot-host/agent-port.mjs` 是上游 process-local agent 契约之上的薄层。
`run-upstream-gateway-smoke.sh` 把下列真实上游源码打进 esbuild bundle，再用 vendored Bot 服务跑通：

| 上游路径 | 用途 |
|---|---|
| `packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts` | 真实上游 Agent 网关（`createMiniAppAgentGateway`） |
| `packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts` | 真实上游进程内 Agent 应用（`createProcessLocalMiniAppAgentApplication`） |
| `packages/shared/src/miniapp-surface.ts` | 网关依赖的共享契约 |

钉点若移动或重命名其中任何一个，宿主契约即失效 —— 检查器会在缺少文件时直接失败。

## 钉点如何被强制

三层，缺一不可：

1. **文档 ↔ 钉点文件**：`upstream-runtime-pin-check.mjs` 解析本文件 `pin:begin`/`pin:end` 之间的 JSON 块，
   与 `upstream-runtime-pin.mjs` 的常量逐字段比对，不一致即失败。
2. **钉点 ↔ 实际 checkout**：smoke 脚本把 `WEBUI_SOURCE` 指向的 WebUI 树解析成 commit
   （git checkout 读 HEAD；非 git 的导出目录必须显式声明 `WEBUI_SOURCE_COMMIT`），与钉点比对。
   并检查上表三个契约文件存在。
3. **CI**：`.github/workflows/bot-host.yml` 的 `upstream-runtime-pin` job 从钉点文件读出仓库与 commit，
   用该 commit checkout fork，再跑检查器与 smoke。

任何一层不一致 → 命令非零退出。

## 升钉（换 commit）

**升钉必须重跑验收命令**，否则检查器会因三方不一致而失败。

```bash
# 1. 改钉点文件与本文档的 pin 块（repository/upstream/license 一般不变）
#    dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs
#    docs/UPSTREAM-RUNTIME.md 的 pin:begin/pin:end JSON 块与上表
# 2. 准备该 commit 的 WebUI checkout（需自带 node_modules/.bin/esbuild，或用 ESBUILD_BIN 指定）
# 3. 重跑验收
cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs
WEBUI_SOURCE=/path/to/minimax-code@<new-commit> bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
# 4. 单独校验钉点三方一致
cd runtime/minimax-bot-host
node upstream-runtime-pin-check.mjs --source /path/to/minimax-code@<new-commit>
```

升钉后还要复核：本文件引用的上游路径是否仍存在、契约是否变更、MIT 许可与第三方声明是否仍适用。

## 验收命令

```bash
cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs
WEBUI_SOURCE=/path/to/minimax-code@97818b2 bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
```

## 边界与未验证项

- 本钉点只覆盖**宿主契约层**的 smoke：模型与会话执行是测试桩，**不证明**完整 runtime 可用、
  不证明插件市场可用、不证明 Android 可运行。
- 钉点树本身**尚未**打包进 APK；`dsh-go/` 内当前仍是 DSH runtime。
- WebUI 的完整构建与 Android 移植可行性另见 `docs/architecture.md` 的 T-Runtime 卡与 `docs/research.md`。
- smoke 需要 Node（CI 用 Node 24；仓库要求 `>=22.19 <23 || >=24.2 <27`）。
