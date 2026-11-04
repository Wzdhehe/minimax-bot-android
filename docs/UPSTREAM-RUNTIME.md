<!-- SPDX-License-Identifier: AGPL-3.0-only -->
<!-- Copyright (C) 2026 Wzdhehe -->

# 上游 Runtime 钉点

本项目的产品 runtime 跟 [mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code) fork（MIT）。
官方 [MiniMax-AI/minimax-code](https://github.com/MiniMax-AI/minimax-code) 是上游，**更新由团队自行合并进 fork**，
本项目不直接钉官方仓库。

本文件是钉点的**人类可读**记录。机器可读的真值源是
[`dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs`](../dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs)，
下面 `pin:begin` / `pin:end` 之间的 JSON 块必须与它**逐字段相等**，由 `upstream-runtime-pin-check.mjs` 强制校验。
**JSON 块是权威的**；下面的表格只是给人看的摘要，改表格不会改变钉点，改 JSON 块才会。

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
  "tree": "5d79407ffcd54715567b49e0fac46c8ccc6e2ec8",
  "commitSubject": "style(webui): refine button states and layout alignment",
  "license": "MIT",
  "contractFiles": [
    "packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts",
    "packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts",
    "packages/shared/src/miniapp-surface.ts"
  ],
  "bundleSha256": {
    "packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts": "e94ce21aa3b8ac944f2a947dd9017fede03aa4fe759776b6f7eae807e4d6b9ca",
    "packages/local-runtime-v2/src/service/miniapp/agent/contracts.ts": "91f28385853617ab334eee2e149a8c18e10d14ef89e5851c6a95eb09145e9590",
    "packages/local-runtime-v2/src/service/miniapp/agent/model-selection.ts": "ee777ee25dadec09a67cafbfa340a8330e8fa4ae80c80d3aaa71ca7c7fd25258",
    "packages/local-runtime-v2/src/service/miniapp/contracts.ts": "810f72467717c4cd6fbbf0caefbcaa15192924f9366a869db841780139704c99",
    "packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts": "d4dc6af1f4a81b7987afd5b8699e2c535375ad49473e8d04a6cb7daecc24ceab",
    "packages/shared/src/miniapp-surface.ts": "ece0a8588bb4854083a8de004388cb6cb6c27c4784aa6c7831fb01dd75b7e888"
  }
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

`contractFiles` 是宿主直接绑定的 3 个文件；`bundleSha256` 是**真正进入 bundle 的全部 6 个上游文件**
（含上表 3 个 + 它们传递引入的 `miniapp/contracts.ts`、`agent/contracts.ts`、`agent/model-selection.ts`），
按字节 sha256 记录。esbuild 直接读 checkout 的工作树，所以「commit 相同」并不等于「跑的字节相同」——
指纹才是绑定字节的那一层。

## 钉点如何被强制

检查器 `upstream-runtime-pin-check.mjs` 逐层核对，任一层不满足即非零退出：

1. **钉点文件自洽**：契约清单非空；每个契约文件都有钉定指纹；alias 映射与契约清单完全一致。
2. **文档 ↔ 钉点文件**：本文档 JSON 块与钉点文件**逐字段深比较**（缺字段、多字段、值不同都报错；
   重复 key 也报错，因为 `JSON.parse` 会静默取最后一个）。
3. **checkout 身份**：checkout 的 `HEAD` 必须是钉点 commit。
4. **checkout 内容**：`HEAD^{tree}` 必须等于钉点 tree。
5. **checkout 工作树洁净**：`git status --porcelain` 必须为空 —— 否则 smoke 跑的就不是被钉定的那份代码。
6. **文件字节**：6 个钉定上游文件的 sha256 必须与指纹一致。
7. **bundle 输入**：6个手工指纹文件必须全部进入bundle且匹配SHA256；其余每个上游输入必须逐一匹配钉住整树中同路径的Git blob（不存在、字节改变均失败）。不再要求完整runtime的数千个文件都手填到指纹清单。

另外，smoke 用的 esbuild 版本必须与钉点上游 `package.json` 里 `devDependencies.esbuild` 一致，
否则拒绝打包。

### 这套机制保证什么、不保证什么

**保证**：在钉点 commit 上、checkout 干净、且 bundle 输入的手工指纹/树blob全部校验通过时，smoke 执行的每个上游字节都是评审过的那个版本。

**不保证**：
- **不保证钉点本身是对的**。钉点文件就是真值源；把它连同本文档一起改成别的 commit，本地检查器发现不了
  （三方一致 ≠ 那个 commit 正确）。真正的兜底是 **CI 会用钉点去 checkout 并重跑 smoke**：
  契约文件缺失、指纹不符、bundle 不一致或 smoke 失败，CI 都会红。
- **不保证上游 runtime 可用**。smoke 里模型与会话是测试桩；上游真实的 `session-ports.ts` 等实现
  不由契约smoke执行。新完整宿主bundle检查会打包真实session-ports等实现，但也只做编译/语法/依赖检查，未启动runtime。smoke证明的是**适配层契约形状**，不是上游runtime行为。
- **不保证本地跑一次检查器就等于升钉合规**。升钉后必须**重跑验收命令**（见下）。

## 升钉（换 commit）

**升钉必须重跑验收命令**，否则 CI 会红。流程：

```bash
# 1. 改钉点文件与本文档的 JSON 块（用钉点文件生成，别手写）
#    dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs
#    docs/UPSTREAM-RUNTIME.md 的 pin:begin/pin:end JSON 块与上方表格
#    bundleSha256 覆盖必需契约输入，其余完整runtime输入按新commit的tree逐个校验
# 2. 准备该 commit 的 WebUI checkout（需自带 node_modules/.bin/esbuild，或用 ESBUILD_BIN 指定）
# 3. 重跑验收
cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs
WEBUI_SOURCE=/path/to/minimax-code@<new-commit> bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
# 4. 单独校验钉点三方一致
cd runtime/minimax-bot-host
node upstream-runtime-pin-check.mjs --source /path/to/minimax-code@<new-commit>
```

非 git 的导出目录（tarball 等）没有 `HEAD`，必须显式声明 commit；
但**声明只是身份，字节由指纹绑定**：

```bash
WEBUI_SOURCE_COMMIT=97818b2890b4acae1c6bd1200a226ff506680628 \
  bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
```

升钉后还要复核：上游路径是否仍存在、契约是否变更、MIT 许可与第三方声明是否仍适用。

## 验收命令

```bash
cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs
WEBUI_SOURCE=/path/to/minimax-code@97818b2 bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
```

钉点机制自身的测试（卡内验收命令之外的额外校验）：

```bash
cd dsh-go && node --test runtime/minimax-bot-host/upstream-runtime-pin.test.mjs
```

## 完整宿主构建图检查（不启动runtime）

`build-runtime-bundle.mjs` 复用钉点自己的workspace源码解析器，将真实`createWebuiRuntimeHost`及本仓库Bot适配/官方Bot服务打包到一个**partial** ESM。构建前后核对干净checkout、commit、tree和每个上游输入字节；未解析的workspace依赖会失败，第三方包保持external。输出还会经`node --check`校验语法，**不会import/执行bundle**。输出目录须不存在且位于上游checkout之外，失败清理仅属于本次构建的新输出。

```bash
# 在本仓库根；ESBUILD_MODULE是esbuild包的JS API，不是bin/esbuild。
node dsh-go/runtime/minimax-bot-host/build-runtime-bundle.mjs \
  --source /path/to/minimax-code@97818b2 \
  --esbuild-module /path/to/esbuild/lib/main.js \
  --out-dir /tmp/mbot-runtime-host-new
WEBUI_SOURCE=/path/to/minimax-code@97818b2 \
ESBUILD_MODULE=/path/to/esbuild/lib/main.js \
  node --test dsh-go/runtime/minimax-bot-host/runtime-bundle.test.mjs
```

输出`runtime-host.partial.mjs`、`metafile.json`、`manifest.json`；manifest包含bundle摘要、进入bundle的上游文件、Node内建依赖和external包specifiers。`status=build-graph-only`、`androidReady=false`。**这不是完整第三方依赖lock**：例如SQLite的`createRequire(variable)`加载better-sqlite3，未必出现在esbuild的external清单；computed import、资源路径和原生依赖仍需单独审计/打包。源码里的文件/child_process/网络调用仍存在，禁止通过DSH同UID/PRoot运行此产物来绕过隔离适配。正式Android启动器、可信caller、native模型与文件映射尚未装配。

构建现在还复用钉点`copyLocalRuntimeAssets`打包canonical Agent/model等资源（当前780文件）：copy helper与每个文件校验Git blob；源/目标完整inventory检查，忽略文件中的额外资源也拒绝；manifest增加`assetBuilder`和`assets`（源路径、blob、bytes、SHA256）。图/资源检查4项通过。它仍不import/启动runtime；真实Linux宿主启动另由[runtime-host-probe](../dsh-go/tooling/runtime-host-probe/README.md)固定依赖并验收（本地已通过），不是Android证明。其他worker入口、原生依赖和动态加载仍需单独适配。

新增内置Bot宿主装配通过一个精确的esbuild import解析接缝接原gateway构造器，仍让原RuntimeServices绑定真实session ports。manifest的`hostAdaptations`明列原importer/specifier和本地adapter；不是改上游文件字节，也不以文件pin声称运行行为毫无适配。真实gateway的8项生命周期用例见[T-RuntimeHost](handoff/T-RuntimeHost.md)。完整factory资源/依赖启动验收与Android隔离装配仍需分别完成。

## 边界与未验证项

- 契约smoke只覆盖**宿主契约层**；新增完整宿主构建图只覆盖编译和语法，**不证明**完整 runtime 可用、不证明插件市场可用、不证明 Android 可运行。
- 钉点树本身**尚未**打包进 APK；`dsh-go/` 内当前仍是 DSH runtime。
- WebUI 的完整构建与 Android 移植可行性另见 `docs/architecture.md` 的 T-Runtime 卡与 `docs/research.md`。
- Node 版本：上游 WebUI 仓库要求 `>=22.19 <23 || >=24.2 <27`；本仓库的宿主测试与 CI 用 Node 24。
