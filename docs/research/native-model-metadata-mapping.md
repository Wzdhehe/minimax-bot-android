<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# native 模型 metadata → fork runtime 映射（2026-11-04 实查）

[NEXT-AGENT 第5节](../handoff/NEXT-AGENT.md)要求先实查真实字段/调用点并写映射表，再做最小 metadata 投影与默认选择。本文只记录**读源码得到的事实**与由此得出的映射，**不含实现**，也未安装任何新包、未改 pin、未写任何配置。

fork 源码为钉点 `97818b2890b4acae1c6bd1200a226ff506680628`（本地只读 checkout，位于 `D:/scratch/minimax-code`，仓库外）。

## 1. native 侧真实字段

来源：`dsh-go/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/bot/BotProviderStore.kt` 与 `model/NativeProvider.kt`。

- `catalog()` 返回 `providers` / `defaultProviderId` / `defaultModelId`（BotProviderStore.kt:72-83）。每个 provider 含 `providerId`、`name`、`baseUrl`、`apiFormat`、`enabled`、`models`、`hasApiKey`、`maskedApiKey`。
- `apiFormat` 的合法取值由 `NativeProvider.prepare()` 决定，只有四种：`openai-completions`、`openai-responses`、`anthropic-messages`、`google-generative-ai`；其它一律 `ModelBrokerCode.INVALID_PROVIDER`（NativeProvider.kt:44-86）。
- Key 只在 native：`NativeProvider` 持 `apiKey` 私有字段，`toString()` 为 `NativeProvider(REDACTED)`，响应体经 `guardResponse()` 检查不得回显 key（NativeProvider.kt:24-30、88-99）。Dart/Node 侧只能看到 `hasApiKey`/`maskedApiKey`。

## 2. fork 侧真实结构

来源：fork `packages/local-runtime/src/config/types.ts`、`packages/local-runtime/src/config/model-key.ts`、`packages/config/src/byok-config.ts`、`third_party/pi-mono/packages/ai/src/providers/register-builtins.ts`。

- `LocalRuntimeConfig` 有三棵 provider 树（types.ts）：
  - `provider: LocalModelsConfig` → 每个 `LocalProviderConfig` 含 `api`、`name`、`options{apiKey,baseURL,authMode,headers}`、`models`/`catalogModels`、`model_order`；
  - `minimax_api: {apiKey, baseURL, modelContextLimits}`（用户自带 MiniMax key）；
  - `custom_provider: Record<key, LocalCustomProviderConfig>`（用户自建外部供应商）。
- 模型键形状是 `providerId/modelId`（model-key.ts:1-13）。providerId 决定读哪棵树：`minimax_api`（保留 id）、`custom_provider:<key>`、其余落到 `provider` 树。
- **`minimax_api` 是保留 provider id**，且 config 读取时会把手写的 `provider.minimax_api` 条目**直接丢弃**（byok-config.ts:307-320）。因此 native 的 MiniMax BYOK 供应商必须投影到 `minimax_api`，不能写进 `provider.minimax_api`。
- pi-ai 注册的 api id（register-builtins.ts:349-397）：`anthropic-messages`、`openai-completions`、`mistral-conversations`、`openai-responses`、`azure-openai-responses`、`openai-codex-responses`、`google-generative-ai`、`google-vertex`、`bedrock-converse-stream`。
- 配置落在 `<dataDir>/config.yaml`（用户可写目录）；`createWebuiRuntimeHost(options)` **没有** config 覆盖钩子，options 只有 dataDir/appVersion/browserProvider/mcodeTools/factory 等（packages/webui/src/runtime/assembly.ts:189-235），默认 factory 动态 import `@mavis/local-runtime-v2` 的 `createLocalRuntimeHostV2`。

## 3. 由此得到的映射（非猜测，均有源码位置）

| native 字段 | fork 落点 | 依据 |
|---|---|---|
| `apiFormat` 四取值 | 同名 api id，四者**一一对应**，无需转换 | NativeProvider.kt:44-86 vs register-builtins.ts:349-385 |
| `providerId`（外部供应商） | `custom_provider:<providerId>` 树键，模型键 `custom_provider:<providerId>/<modelId>` | model-key.ts:15-45 |
| MiniMax 官方 BYOK 供应商 | `minimax_api` 树（不是 `provider.minimax_api`） | byok-config.ts:307-320 |
| `name` | `LocalCustomProviderConfig.name` / `LocalProviderConfig.name` | types.ts |
| `baseUrl` | `options.baseURL`（**非密钥**） | types.ts:LocalProviderOptions |
| `models[]` | `models`（用户选定）/ `catalogModels`（目录）两个映射 | types.ts:LocalProviderConfig |
| `defaultProviderId`+`defaultModelId` | `defaultModel = "<providerId>/<modelId>"` | types.ts:LocalRuntimeConfig + model-key.ts:formatModelKey |
| `hasApiKey`/`maskedApiKey` | **不投影**；fork 侧只知道有没有 key | Key 只能留在 native |

## 4. 明确尚未确定（不得自行发明协议）

1. native providerId 到 fork 模型键的**确切**构造与 providerKey 命名（含大小写、保留 id 冲突）尚无既定规则；上文只给出形状来源。
2. metadata 刷新的**协议与时序**（何时重投影、是否要版本号、如何通知 runtime）未定。
3. 一个 runtime 下**多个模型 binding 的路由**未定；现有 `NativeModelRequestHost`/`ModelRequestClient` 已绑定 UID/代次/provider/model/peer 生命周期，但不是任意 URL/Key 路由器。
4. 每 Bot 会话到 MCP caller 的**可信绑定**未定；内置服务 `pluginId` 不等于每 Bot `caller_bot_id`。
5. 请求实际走 fork 自带 HTTP 还是经 native broker，尚需在隔离 UID 下验证；PRoot/同 UID 都不是答案。

## 5. 边界（与合同一致）

- 真实 Key 不写 fork BYOK yaml、不进 env/descriptor/log/Agent 可读文件；fork 侧只知道"有没有 key"。
- 非密钥 metadata 投影（供应商名/Base URL/模型清单/默认选择）可以落配置，但投影本身不等于模型请求可用。
- 未接 broker 时必须明确拒绝模型执行，不加假回答、不直连 provider 兜底。
- 本轮未安装 `@minimax-web/minimax-code`：npm 官方 registry 对该精确包名仍返回 404（2026-11-04 复查），搜索只见到旧的 `@fectivnfy112358/minimax-code-web`；按研究文档要求不猜包名、不换同类包。
