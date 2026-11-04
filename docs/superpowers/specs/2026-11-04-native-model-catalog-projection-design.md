<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 设计：native 模型目录投影与受控绑定（2026-11-04，v2 复用优先）

状态：用户已批准交付边界（投影+默认选择+绑定装配）、生效时机（重启 runtime 后生效）、投影不含真实 baseUrl、provider 键名沿用 native providerId。
**v2 依据用户指示「参考已开源的 @minimax-web/minimax-code，很多东西不要重复造轮子」重写**：改为复用官方 `local-runtime-v2` 的 provider 管理与选择 API，不再自写投影层、配置写入、目录构建与默认选择。

参考来源（只读）：

- 开源仓 `MiniMax-AI/minimax-code`（public，TUI 0.7.2 线）——与我们钉的 fork `97818b2890b4acae1c6bd1200a226ff506680628` **同源同包名**（`packages/config`、`packages/local-runtime`、`packages/local-runtime-v2`），fork 只多了 `packages/webui`。
- npm `@minimax-web/minimax-code` 2026-11-04 复查仍 **404**；开源仓发布的 CLI 包是 `@minimax-ai/code`。WebUI 包的确切来源仍待用户提供，不猜同类包。
- 本机 git 协议访问 github.com 不通（clone 失败），取证走 `gh api`；只读参考目录 `D:/scratch/minimax-code`（fork 钉点）。

## 1. 目标

fork runtime 看见用户在 native 侧配置的供应商与模型，且**每次模型请求都经 native broker**：

- 真实 Key 只留在 native（Keystore），guest / Dart / descriptor / env / log / Agent 可读文件都不接触；
- guest 不直连模型供应商；
- 用户配置的模型出现在 fork 目录里并成为默认模型；
- 切换模型**在下一轮生效**，在飞那一轮继续用旧模型、不被打断。

## 2. 非目标

真实 provider 调用、ARM64/真机、产品 APK（各自另证）；每 Bot 可信 MCP caller 绑定（合同 §6.3）；设置改动热更新（本阶段 boot-only）；不改 fork 源码与 pin；不新增 vendor 改动；不放开同 UID shell/PRoot。

## 3. 复用清单（本设计不自造的部分）

| 需要的能力 | 直接复用 | 位置 |
|---|---|---|
| 供应商条目写入（配置锁、原子写、BYOK 迁移、apiKey/apiFormat 校验、保留键、模型合并） | `LocalModelProviderService.createUserProvider / updateUserProvider` | `packages/local-runtime-v2/src/service/model-system/management/service.ts:158-183`、`service-custom-provider-operations.ts:50-120` |
| provider 键名规则（保留键、slug、冲突后缀） | `generateProviderKey` + `RESERVED_PROVIDER_KEYS` | `management/provider-key.ts:14-47` |
| 模型目录构建与 provider 视图 | `ModelProviderApplication.list()`、`LocalModelProviderService.listEffectiveProviders()` | `application/session/model-provider-application.ts:36`、`management/service.ts:70-90` |
| 默认模型 / 会话模型选择 | `ModelProviderApplication.select()`、`deps.setDefaultModel` | `application/session/model-provider-application.ts:96+` |
| 连通性测试 / 模型发现（官方记账） | `testProvider / testModel / discoverModels` | `management/service.ts:229-240` |
| 运行时服务可达性 | `RuntimeServices.modelProvider = { application, providers }` | `packages/local-runtime-v2/src/services.ts:540-542` |
| 模型请求路由（虚拟 origin + 非密钥 placeholder + 四格式 SSE 还原） | 既有 `createNativeModelRoute` / `createNativeModelFetch` | `dsh-go/runtime/minimax-bot-host/native-model-route.mjs`、`model-sdk-fetch.mjs` |
| fetch 作用域（装一次、无绑定 fail closed、无网络兜底） | 既有 `createModelFetchScope` | `dsh-go/runtime/minimax-bot-host/model-fetch-scope.mjs` |
| native 侧 Key 保管与真实请求 | 既有 `NativeProvider` / `NativeModelRequestHost` / `ModelRequestClient` | `app/.../model/NativeProvider.kt`、`bot/NativeModelRequestHost.kt`、`isolation/ModelRequestClient.kt` |
| 目录/文件能力 | 既有 `RuntimePrivateDirectory` / `RuntimeAtomicPublisher.openForHeldDirectory` | `app/.../isolation/` |

**我们只新增两个薄层**：

1. `model-catalog-projection.mjs`：把 native 非密钥目录翻译成**官方 API 的调用序列**（create/update + models）。不拼 YAML、不生成键名、不建目录、不选默认模型。
2. `native-model-binding.mjs`：按轮把「该轮模型键」解析成 route，并用既有 scope 包住该轮模型调用。不实现任何 HTTP/SSE 逻辑。

## 4. 事实基线（读源码所得）

| 事实 | 位置 |
|---|---|
| native 目录（非密钥）：`providerId/name/baseUrl/apiFormat/enabled/models/hasApiKey/maskedApiKey` + `defaultProviderId/defaultModelId` | `app/.../bot/BotProviderStore.kt:72-83` |
| native 四种 `apiFormat`：`openai-completions`、`openai-responses`、`anthropic-messages`、`google-generative-ai`；与官方注册的 api id **同名** | `model/NativeProvider.kt:44-86` vs `third_party/pi-mono/.../register-builtins.ts:349-397` |
| 官方 `createUserProvider` 入参必填 `baseUrl`、`apiKey`；`apiKey` 只要求非空且非 masked（占位串可通过） | `management/service-input.ts:11-24` |
| 官方 `apiFormat` 必须命中 `isModelProviderApi` 白名单 | `management/service-input.ts:34-47` |
| 官方 `createUserProvider` **拒绝 `saveAndUse`**（409 TEST_REQUIRED：先测通再启用） | `management/service-custom-provider-operations.ts:62-68` |
| 官方 `updateUserProvider` 的 apiKey 三态：不给=保留、空=清除、masked=拒绝 | `management/service-input.ts:26-32` |
| 官方 `generateProviderKey`：ASCII 简单名→小写 kebab；**非 ASCII→随机 `provider-<hex>`**；命中保留键/已存在则加 `-2/-3` | `management/provider-key.ts:22-47` |
| 官方 provider 条目结构：`{name, kind:'custom', enabled:true, api, options:{apiKey,baseURL,authMode:'api-key'}, models}` | `management/service-custom-provider-operations.ts:85-105` |
| 目录来源：`config.provider` 树 + BYOK 树（`custom_provider`）；providerId 形如 `custom_provider:<key>`；显示名 `name ?? key` | `catalog/catalog.ts:24`、`catalog/list-models.ts:388` |
| `minimax_api` 树的模型不单独列出（builtin minimax 目录覆盖） | `management/service.ts:73-74`、`catalog/list-models.ts:383` |
| 官方配置写接口 `updateLocalConfigFile`（锁+原子写+`resetConfig()`），根键需在 `extraMutableRoots` 白名单；危险根键仅 `__proto__/prototype/constructor` | `packages/local-runtime/src/config/update.ts:55-110`、`index.ts:84` |
| guest 配置唯一所有者：`<dataDir>/config.yaml`；`getConfig()` 只有 getter | `packages/config/src/config.ts:1897` |
| 官方测试/发现会真的发请求 → 在 guest 内必须经我们的 scoped fetch | `management/service.ts:229-240` |

## 5. 组件设计

### 5.1 `model-catalog-projection.mjs`（新增，薄层）

输入：native 经 descriptor 传来的**非密钥目录**（形状见 §4 第 1 行；**必须已剔除 baseUrl 与任何 Key 字段**，本层再校验一次，出现 key/apiKey/secret 类字段即整体拒绝）。

输出：对官方 API 的调用序列，例如

```js
[
  { op: 'upsertProvider', providerId: 'minimax', apiFormat: 'openai-completions',
    name: 'minimax', models: ['MiniMax-M3.1-Flash'] },
]
```

规则：

1. providerId 沿用 native 值；**显示名用「净化后的 providerId」**（仅保留 `[A-Za-z0-9 _-]`，其余字符换 `-`）→ 官方 `generateProviderKey` 得到**确定性**键名。不用用户友好名，避免官方对非 ASCII 名生成随机键、导致跨重启的模型键不稳定；友好名只保留在 native 侧。
2. `apiFormat` 必须是四种之一，否则该 provider 拒绝并计数（不静默丢弃）。
3. `enabled === false` 或 `models` 为空的 provider 不投影。
4. 默认模型：`defaultProviderId` 与 `defaultModelId` 必须同时存在、provider 已投影、model 在其列表内；否则不设默认（fork 回落自身默认），不报错也不伪造。

### 5.2 `native-model-binding.mjs`（新增，薄层）

- 复用 `createNativeModelRoute`：`baseUrl` 为虚拟 origin（anthropic→origin；google→origin+/v1beta；其余→origin+/v1），`apiKey` 为既有占位串 `native-broker-placeholder`（与 route 内既有值一致）。
- 复用 `createModelFetchScope`：**安装一次** `fetch`；每个模型调用在 `scope.run(route.fetch, op)` 内执行；无绑定时一律 fail closed。
- **按轮解析**：轮开始时从 fork 会话视图取该轮生效模型键（形如 `custom_provider:<key>/<model>`）→ 解析出 providerId/modelId → 反查 native 目录得到 `apiFormat` → 构造 route。**轮内不重新解析**。
- 模型键不在 native 目录内 → 该轮模型执行**明确拒绝**（稳定 code），不回退、不直连、不给假回答。

### 5.3 装配顺序（guest 启动与每轮）

```
1. guest 启动：安装 scope.fetch（一次）
2. boot runtime → 取 RuntimeServices（既有接缝）
3. 投影：对每个 native provider 调用官方 providers.createUserProvider/updateUserProvider
     - create：name=净化 providerId、baseUrl=虚拟 origin、apiKey=占位串、apiFormat、models
     - update：不传 apiKey（官方三态=保留）
4. 官方连通性测试：providers.testProvider(providerId, modelId)
     → 官方自己发请求 → 经我们的 scoped fetch → native broker → 真实供应商 → 官方记账测试状态
5. 默认模型：modelProvider.application.select({ providerId, modelId })（不带 sessionId → 设为默认）
6. 每轮：解析该轮模型键 → scope.run(route.fetch, 该轮模型调用)
```

第 4 步是必须的：官方 `createUserProvider` 拒绝 `saveAndUse`，且选择模型需要供应商已测通；我们不绕过这条规则，而是让官方测试经我们的 fetch 走 native，测试状态由官方自己记账。

### 5.4 切模型语义（轮边界，用户已明确）

- 切换请求写进 fork 的 session 模型选择（官方 `select` 带 sessionId）；
- **本轮不生效**：在飞轮继续用旧模型，不打断、不重解析；
- 下一轮开始才用新模型，并在该轮产出一次「模型已切换」事件（交由 Bot 服务 / Flutter 侧展示；本阶段只保证事件可用，产品 UI 文案另做）；
- 切换目标不在 native 目录内 → 该轮明确拒绝 + 可展示的失败原因。

## 6. 安全不变量

1. Key 不出现在 descriptor、config.yaml、env、log、报告、Agent 可读文件、投影载荷。
2. guest 内不出现真实供应商端点（只有虚拟 origin + 占位 key）。
3. 未绑定模型调用 → fetch fail closed，无原始/网络兜底。
4. 模型请求的 provider/model 必须在该轮 native 目录内。
5. 目录 FD 留 Main；不新增跨 UID 文件句柄外发。
6. 不改 fork 源码/pin；不新增 vendor 改动。

## 7. 错误处理

| 情形 | 结果 |
|---|---|
| 投影条目非法（坏 id、未知 apiFormat、含 key 字段） | 拒绝该条目并计数；全部非法 → 投影失败，runtime 不启动 |
| 官方 create/update 拒绝 | 该 provider 不生效；记录稳定原因码；不静默继续 |
| 官方连通性测试失败 | 该 provider 不被选中；目录里仍可见但不可选；不自动重试 |
| 该轮模型键不在目录内 | 该轮模型执行明确拒绝，不回退 |
| native broker 不可用 | 该轮失败，**不自动重试**（避免重复计费/重复记录） |

## 8. 验收矩阵

**A. 薄层单测**（`model-catalog-projection.test.mjs`、`native-model-binding.test.mjs`）
四种 apiFormat 映射；净化规则与确定性键名；非法 id / 未知 apiFormat / 含 key 字段拒绝；禁用与空模型不投影；默认模型缺失不设默认；按轮解析与目录比对；未绑定 fail closed。

**B. 真实 fork factory**（Linux 宿主 `runtime-host-probe`，CI 跑）
投影后官方 `ModelProviderApplication.list()` 列出该 provider/model；`select()` 后默认模型生效并标 selected；guest 的 `config.yaml` 中**只有占位 key**，没有真实 Key。

**C. 受控假 provider**（native broker stub）
官方 `testProvider` 经我们的 fetch 打通；流式、取消、未知结果三类语义；Key 不出现在 descriptor/env/log/Agent 可读文件；未绑定 fetch fail closed；目录外模型被拒。

**D. 切模型语义**
在飞轮不换模型；下一轮换到新模型并产出一次切换成功事件。

**E. 本阶段不证明**
真实 provider 调用、ARM64/真机、产品 APK、每 Bot caller 可信绑定。

## 9. 改动面

- 新增：`dsh-go/runtime/minimax-bot-host/model-catalog-projection.mjs` + `.test.mjs`、`native-model-binding.mjs` + `.test.mjs`。
- 改：`runtime-descriptor.mjs`（携带非密钥目录）、`runtime-host-session.mjs`（安装 scope、投影、官方测试、默认选择、按轮绑定、切模型语义）。`runtime-bundle-entry.mjs` 是否需要改，实现时按接缝实际位置定。
- 文档：`docs/handoff/T-RuntimeBroker.md`、`T-RuntimeHost.md`、`HANDOFF.md`、`handoff/NEXT-AGENT.md`、`docs/research/native-model-metadata-mapping.md`（更正为「复用官方 API」并记录开源仓事实）。

## 10. 风险与未决

1. 官方 `assertModelSelectable` 的确切前置条件未读源码确认 → 以 C 步实测确定「create → test → select」顺序，不绕过。
2. 官方 `createUserProvider` 的 `kind:'custom'` 与 `authMode:'api-key'` 对我们的虚拟 origin 是否合适 → 实测后必要时用 `updateUserProvider` 调整。
3. 每 Bot caller 可信绑定仍缺（合同 §6.3），本阶段不解决。
4. 真机 / ARM64 另证。

## 11. 复现命令

```bash
node --test dsh-go/runtime/minimax-bot-host/model-catalog-projection.test.mjs \
  dsh-go/runtime/minimax-bot-host/native-model-binding.test.mjs
node --test dsh-go/runtime/minimax-bot-host/runtime-host-session.test.mjs \
  dsh-go/runtime/minimax-bot-host/native-model-route.test.mjs \
  dsh-go/runtime/minimax-bot-host/model-fetch-scope.test.mjs
npm test --prefix dsh-go/tooling/runtime-host-probe     # 真实 fork factory（Linux 宿主）
python3 tooling/docs-links/check_links.py --root . README.md docs tooling/docs-links
git diff --check
```
