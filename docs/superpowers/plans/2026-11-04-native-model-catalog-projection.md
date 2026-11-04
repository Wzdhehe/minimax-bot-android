<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Native 模型目录投影与受控绑定 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 fork runtime 看见 Android native 侧配置的供应商与模型（非密钥），并让每一次模型请求都经 native broker 转发，Key 不出 native；切换模型在下一轮生效。

**Architecture:** 不自造投影/配置/目录/选择层，全部复用官方 `local-runtime-v2` 的 `application.modelProviders`（create/update/testProvider/listUser）与 `application.models`（list/select）。本项目只新增两个薄层：`model-catalog-projection.mjs`（native 目录 → 官方 API 调用序列）与 `native-model-binding.mjs`（该轮模型键 → 既有 `createNativeModelRoute` + `createModelFetchScope` 绑定）。既有 `native-model-route.mjs`/`model-sdk-fetch.mjs`/`model-fetch-scope.mjs` 已实现虚拟 origin、非密钥 placeholder、四格式 SSE 还原与 fail-closed，本计划只做装配。

**Tech Stack:** Node 24 ESM（`node --test`）、官方 `@mavis/local-runtime-v2` 门面、既有 native broker（Binder/FD）。

**设计文档:** `docs/superpowers/specs/2026-11-04-native-model-catalog-projection-design.md`

## 执行结果（2026-11-04 已完成 Task 1–7）

提交链（本分支连续）：`49de131` 投影层 → `445576e` 每轮绑定层 → `b0974c3` descriptor 携带目录 → `77dce60` agent port 按轮绑定 → `55be6f3` 官方门面装配 → `044600c` 真实 factory 验收 → 文档收尾。远程验收 run `41840651106` 整体 success。

与计划相比的偏差（按实测修正，不猜测）：

1. **新增 `runtime-catalog-policy.mjs`**：目录字段策略（凭据类/端点类字段拒绝，非密钥 `hasApiKey` 布尔量放行）由 descriptor 与投影层**共用一份**，避免两处规则漂移。计划只在 Task 3 写了 descriptor 侧校验。
2. **投影层同时拒绝端点类字段**：设计文档 §5.1 要求「必须已剔除 baseUrl，本层再校验一次」，计划 Task 1 的实现漏了这一步，已补（`native_catalog_forbidden_field`）。
3. **官方调用序列放在 `model-catalog-projection.mjs` 的 `applyNativeCatalog`，并另抽 `native-model-runtime.mjs`（不依赖钉点 gateway）**：计划把调用序列写在 `runtime-host-session.mjs`，那样只能由 CI 验证；抽出后可在本机用假门面真跑，CI 再验真实 fork 集成。
4. **官方 `models` 入参是 `[{modelId}]` 对象数组，不是字符串数组**：计划 Task 4 写错，已按 `UserModelInputView` 修正。
5. **官方 `models.select()` 不要求连通性测试通过**（实测）：其两个前置只要 provider 已配置、模型在列表内、`apiKey`/`baseUrl` 非空。设计文档 §5.3「先测通再选」的前提只对 `saveAndUse` 成立（409 TEST_REQUIRED）。已据此调整行为并记入[映射笔记](../../research/native-model-metadata-mapping.md)第 4c 节。
6. **每轮模型键来自 `application.models.list({sessionId})` 里 `selected` 的那项**（fork 自己的会话视图），而不是 plan 假设的其它来源。
7. **额外提供 `setSessionModel`**：用官方带 `sessionId` 的 `select` 写会话模型选择，实现「切模型在下一轮生效、在飞轮继续用旧模型」，并对不在 native 目录内的目标明确拒绝。
8. **CI 清单新增三份测试文件**（`bot-host.yml` 的 `host-contracts` 步骤）：`model-catalog-projection` / `native-model-binding` / `native-model-runtime`。
9. **本机未能执行的验收**：真实 fork factory 与真实 host probe 只能在 CI（Linux + esbuild + better-sqlite3 原生件）跑，本机不得声称通过；`runtime-descriptor` 原有 7 项在本机因 Windows 权限位/符号链接限制失败，已在改动前 baseline 复跑确认失败项完全相同。

## Global Constraints

- 只续写 `feat/t-plugins`；不动 `main`/`dev/integration`；不 force push；不建 PR；不发布 APK。
- 新文件加 `// SPDX-License-Identifier: AGPL-3.0-only` 头（文档用 HTML 注释形式）。
- 真实 Key 绝不进入：descriptor、config.yaml、env、log、报告、Agent 可读文件、投影载荷。guest 内只允许虚拟 origin `https://models.mbot.invalid` 与占位串 `native-broker-placeholder`。
- 目录 FD 留 Main；不新增跨 UID 文件句柄外发；不加同 UID shell/PRoot 跑完整 Agent。
- 不改 fork 源码与 pin（`97818b2890b4acae1c6bd1200a226ff506680628`）；不新增 vendor 改动。
- 错误一律用有限稳定 code，不回显异常正文、不回显路径/凭据。
- 本机可跑：`node --test`（Node 24 在 PATH）。**真实 fork factory 验收只能在 CI（Linux）跑**——本机 git 协议不通 github、且 real-host 依赖是 Linux 二进制；本地不得声称该验收通过。
- 每任务结束提交一次，路径显式 `git add --`。

---

### Task 1: 目录投影薄层

**Files:**
- Create: `dsh-go/runtime/minimax-bot-host/model-catalog-projection.mjs`
- Test: `dsh-go/runtime/minimax-bot-host/model-catalog-projection.test.mjs`

**Interfaces:**
- Consumes: native 非密钥目录 `{ providers: [{ providerId, name, apiFormat, enabled, models: string[] }], defaultProviderId: string|null, defaultModelId: string|null }`。
- Produces:
  ```js
  export const PROJECTED_API_FORMATS; // Readonly<string[]>，四种 apiFormat
  export function projectNativeCatalog(catalog) -> {
    upserts: Array<{ providerId: string, displayName: string, apiFormat: string, models: string[] }>,
    defaultSelection: { providerId: string, modelId: string } | null,
    rejected: Array<{ providerId: string, reason: string }>,
  }
  ```
  失败模式：输入含凭据类字段 → `throw new TypeError('native_catalog_contains_credential')`（整体拒绝，不做部分投影）。

- [ ] **Step 1: 写失败测试**

```js
// dsh-go/runtime/minimax-bot-host/model-catalog-projection.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { projectNativeCatalog, PROJECTED_API_FORMATS } from './model-catalog-projection.mjs';

test('projects enabled providers with a deterministic display name', () => {
  const result = projectNativeCatalog({
    providers: [{ providerId: 'minimax', name: 'MiniMax', apiFormat: 'openai-completions', enabled: true, models: ['MiniMax-M3.1-Flash'] }],
    defaultProviderId: 'minimax', defaultModelId: 'MiniMax-M3.1-Flash',
  });
  assert.deepEqual(result.upserts, [{ providerId: 'minimax', displayName: 'minimax', apiFormat: 'openai-completions', models: ['MiniMax-M3.1-Flash'] }]);
  assert.deepEqual(result.defaultSelection, { providerId: 'minimax', modelId: 'MiniMax-M3.1-Flash' });
  assert.deepEqual(result.rejected, []);
});

test('sanitizes the display name so the upstream key stays deterministic', () => {
  const result = projectNativeCatalog({ providers: [{ providerId: 'my.provider', name: 'x', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }], defaultProviderId: null, defaultModelId: null });
  assert.equal(result.upserts[0].displayName, 'my-provider');
});

test('rejects an unsupported api format instead of dropping it silently', () => {
  const result = projectNativeCatalog({ providers: [{ providerId: 'p1', name: 'p', apiFormat: 'weird-format', enabled: true, models: ['m1'] }], defaultProviderId: null, defaultModelId: null });
  assert.deepEqual(result.upserts, []);
  assert.deepEqual(result.rejected, [{ providerId: 'p1', reason: 'unsupported_api_format' }]);
});

test('skips disabled providers and providers without models', () => {
  const result = projectNativeCatalog({
    providers: [
      { providerId: 'off', name: 'o', apiFormat: 'openai-completions', enabled: false, models: ['m'] },
      { providerId: 'empty', name: 'e', apiFormat: 'openai-completions', enabled: true, models: [] },
    ], defaultProviderId: null, defaultModelId: null,
  });
  assert.deepEqual(result.upserts, []);
  assert.deepEqual(result.rejected, []);
});

test('omits the default selection when the pair is incomplete or unknown', () => {
  const result = projectNativeCatalog({ providers: [], defaultProviderId: 'p1', defaultModelId: 'm1' });
  assert.equal(result.defaultSelection, null);
});

test('refuses a catalog that carries credential-like fields', () => {
  assert.throws(() => projectNativeCatalog({ providers: [{ providerId: 'p1', apiKey: 'sk-live' }], defaultProviderId: null, defaultModelId: null }), /native_catalog_contains_credential/);
});

test('exposes the four supported api formats', () => {
  assert.deepEqual([...PROJECTED_API_FORMATS].sort(), ['anthropic-messages', 'google-generative-ai', 'openai-completions', 'openai-responses']);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test model-catalog-projection.test.mjs`
Expected: FAIL，`Cannot find module './model-catalog-projection.mjs'`

- [ ] **Step 3: 最小实现**

```js
// SPDX-License-Identifier: AGPL-3.0-only
// Translate the native non-secret catalog into the call sequence the pinned
// upstream provider API expects. This module never assembles config YAML,
// never invents provider keys and never sees a credential.
const API_FORMATS = Object.freeze(['openai-completions', 'openai-responses', 'anthropic-messages', 'google-generative-ai']);
const CREDENTIAL_FIELD = /(api[-_.]?key|secret|token|credential|password|passwd|authorization)/i;
const PROVIDER_ID = /^[A-Za-z0-9._-]{1,128}$/;
const MODEL_ID = /^[A-Za-z0-9._:-]{1,256}$/;

export const PROJECTED_API_FORMATS = API_FORMATS;

function assertNoCredential(value) {
  if (!value || typeof value !== 'object') return;
  for (const [key, entry] of Object.entries(value)) {
    if (CREDENTIAL_FIELD.test(key)) throw new TypeError('native_catalog_contains_credential');
    assertNoCredential(entry);
  }
}

function displayNameFor(providerId) {
  return providerId.replace(/[^A-Za-z0-9 _-]/g, '-');
}

export function projectNativeCatalog(catalog) {
  assertNoCredential(catalog);
  const providers = Array.isArray(catalog?.providers) ? catalog.providers : [];
  const upserts = [];
  const rejected = [];
  for (const provider of providers) {
    const providerId = typeof provider?.providerId === 'string' ? provider.providerId : '';
    if (!PROVIDER_ID.test(providerId)) { rejected.push({ providerId, reason: 'invalid_provider_id' }); continue; }
    if (!API_FORMATS.includes(provider.apiFormat)) { rejected.push({ providerId, reason: 'unsupported_api_format' }); continue; }
    if (provider.enabled === false) continue;
    const models = (Array.isArray(provider.models) ? provider.models : []).filter((model) => typeof model === 'string' && MODEL_ID.test(model));
    if (!models.length) continue;
    upserts.push({ providerId, displayName: displayNameFor(providerId), apiFormat: provider.apiFormat, models });
  }
  const defaultProviderId = typeof catalog?.defaultProviderId === 'string' ? catalog.defaultProviderId : null;
  const defaultModelId = typeof catalog?.defaultModelId === 'string' ? catalog.defaultModelId : null;
  const target = defaultProviderId && defaultModelId ? upserts.find((entry) => entry.providerId === defaultProviderId) : null;
  const defaultSelection = target && target.models.includes(defaultModelId) ? { providerId: defaultProviderId, modelId: defaultModelId } : null;
  return { upserts, defaultSelection, rejected };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test model-catalog-projection.test.mjs`
Expected: PASS（7 项）

- [ ] **Step 5: 提交**

```bash
git add -- dsh-go/runtime/minimax-bot-host/model-catalog-projection.mjs dsh-go/runtime/minimax-bot-host/model-catalog-projection.test.mjs
git commit -m "Add the native catalog projection for the upstream provider API"
```

---

### Task 2: 按轮绑定薄层

**Files:**
- Create: `dsh-go/runtime/minimax-bot-host/native-model-binding.mjs`
- Test: `dsh-go/runtime/minimax-bot-host/native-model-binding.test.mjs`

**Interfaces:**
- Consumes: `byForkProviderId` 映射 `{ [forkProviderId]: { nativeProviderId, apiFormat, models: string[] } }`（fork 侧 providerId → native 侧信息，由 Task 4 装配时填入）；`routeFactory`（默认 `createNativeModelRoute`）；`scope`（默认 `createModelFetchScope()`）。
- Produces:
  ```js
  export class NativeBindingError extends Error { constructor(code) { super(code); this.code = code } }
  export function createNativeModelBinding({ byForkProviderId, routeFactory, scope }) -> {
    install(target: object): void,
    routeFor(modelKey: string): object,
    runForModelKey(modelKey: string, operation: () => unknown): unknown,
    close(): void,
  }
  ```
  错误 code：`invalid_model_key`、`model_not_available`、`binding_not_installed`。

- [ ] **Step 1: 写失败测试**

```js
// dsh-go/runtime/minimax-bot-host/native-model-binding.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNativeModelBinding, NativeBindingError } from './native-model-binding.mjs';

function fixture() {
  const calls = [];
  const scope = {
    fetch: () => Promise.resolve(new Response('{}')),
    run(fetchImpl, operation) { calls.push(['run', typeof fetchImpl]); return scope.runValue(operation); },
    runValue(operation) { return operation(); },
  };
  const routeFactory = (input) => { calls.push(['route', input]); return { fetch: async () => new Response('ok'), baseUrl: 'https://models.mbot.invalid/v1' }; };
  const binding = createNativeModelBinding({
    byForkProviderId: { 'custom_provider:minimax': { nativeProviderId: 'minimax', apiFormat: 'openai-completions', models: ['m1'] } },
    routeFactory, scope,
  });
  return { binding, calls };
}

test('resolves a model key to the native route for that provider', () => {
  const { binding, calls } = fixture();
  const route = binding.routeFor('custom_provider:minimax/m1');
  assert.equal(route.baseUrl, 'https://models.mbot.invalid/v1');
  assert.deepEqual(calls[0][1], { providerId: 'minimax', modelId: 'm1', apiFormat: 'openai-completions' });
});

test('refuses a model key that is not in the native catalog', () => {
  const { binding } = fixture();
  assert.throws(() => binding.routeFor('custom_provider:other/m1'), (error) => error instanceof NativeBindingError && error.code === 'model_not_available');
});

test('refuses a malformed model key', () => {
  const { binding } = fixture();
  assert.throws(() => binding.routeFor('nonsense'), (error) => error instanceof NativeBindingError && error.code === 'invalid_model_key');
});

test('refuses a model outside the provider model list', () => {
  const { binding } = fixture();
  assert.throws(() => binding.routeFor('custom_provider:minimax/other'), (error) => error instanceof NativeBindingError && error.code === 'model_not_available');
});

test('runs the operation inside the bound scope', () => {
  const { binding, calls } = fixture();
  const result = binding.runForModelKey('custom_provider:minimax/m1', () => 'done');
  assert.equal(result, 'done');
  assert.equal(calls.some(([kind]) => kind === 'run'), true);
});

test('installs the scoped fetch exactly once on the target', () => {
  const { binding } = fixture();
  const target = {};
  binding.install(target);
  binding.install(target);
  assert.equal(typeof target.fetch, 'function');
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test native-model-binding.test.mjs`
Expected: FAIL，`Cannot find module './native-model-binding.mjs'`

- [ ] **Step 3: 最小实现**

```js
// SPDX-License-Identifier: AGPL-3.0-only
// Bind one model turn to the native model route. The route and the fetch
// scope already exist; this module only resolves the turn's model key and
// keeps the binding lifetime separate from the runtime.
import { createNativeModelRoute } from './native-model-route.mjs';
import { createModelFetchScope } from './model-fetch-scope.mjs';

export class NativeBindingError extends Error {
  constructor(code) { super(code); this.code = code; }
}

export function createNativeModelBinding({ byForkProviderId, routeFactory = createNativeModelRoute, scope = createModelFetchScope() }) {
  if (!byForkProviderId || typeof byForkProviderId !== 'object') throw new TypeError('A fork provider map is required');
  if (typeof routeFactory !== 'function') throw new TypeError('A route factory is required');
  if (!scope || typeof scope.fetch !== 'function' || typeof scope.run !== 'function') throw new TypeError('A fetch scope is required');
  let installed = false;

  function parseModelKey(modelKey) {
    if (typeof modelKey !== 'string') throw new NativeBindingError('invalid_model_key');
    const slash = modelKey.indexOf('/');
    if (slash <= 0 || slash === modelKey.length - 1) throw new NativeBindingError('invalid_model_key');
    return { providerId: modelKey.slice(0, slash), modelId: modelKey.slice(slash + 1) };
  }

  function routeFor(modelKey) {
    const { providerId, modelId } = parseModelKey(modelKey);
    const entry = Object.prototype.hasOwnProperty.call(byForkProviderId, providerId) ? byForkProviderId[providerId] : null;
    if (!entry) throw new NativeBindingError('model_not_available');
    if (!Array.isArray(entry.models) || !entry.models.includes(modelId)) throw new NativeBindingError('model_not_available');
    return routeFactory({ providerId: entry.nativeProviderId, modelId, apiFormat: entry.apiFormat });
  }

  return Object.freeze({
    install(target) {
      if (!target || typeof target !== 'object') throw new TypeError('An install target is required');
      if (installed) return;
      target.fetch = scope.fetch;
      installed = true;
    },
    routeFor,
    runForModelKey(modelKey, operation) {
      if (!installed) throw new NativeBindingError('binding_not_installed');
      if (typeof operation !== 'function') throw new TypeError('An operation is required');
      const route = routeFor(modelKey);
      return scope.run(route.fetch, operation);
    },
    close() { installed = false; },
  });
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test native-model-binding.test.mjs`
Expected: PASS（6 项）

- [ ] **Step 5: 提交**

```bash
git add -- dsh-go/runtime/minimax-bot-host/native-model-binding.mjs dsh-go/runtime/minimax-bot-host/native-model-binding.test.mjs
git commit -m "Bind each model turn to the native model route"
```

---

### Task 3: descriptor 携带非密钥目录

**Files:**
- Modify: `dsh-go/runtime/minimax-bot-host/runtime-descriptor.mjs`（`createRuntimeDescriptor` 现有签名在第 60 行附近）
- Test: `dsh-go/runtime/minimax-bot-host/runtime-descriptor.test.mjs`

**Interfaces:**
- Consumes: Task 1 的目录形状（非密钥）。
- Produces: `createRuntimeDescriptor({ entryPath, pinCommit, port, token, broker = null, catalog = null })` 返回值新增 `catalog`（`null` 或冻结后的目录）；目录含凭据类字段或 `baseUrl` 字段 → 抛 `TypeError('descriptor_catalog_contains_credential')` / `TypeError('descriptor_catalog_forbidden_field')`。

- [ ] **Step 1: 写失败测试**（追加到既有 `runtime-descriptor.test.mjs`）

```js
test('carries a non-secret provider catalog', () => {
  const descriptor = createRuntimeDescriptor({
    entryPath: '/data/app/runtime-descriptor.json', pinCommit: 'a'.repeat(40), port: 4123, token: 'b'.repeat(32),
    catalog: { providers: [{ providerId: 'minimax', name: 'MiniMax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }], defaultProviderId: 'minimax', defaultModelId: 'm1' },
  });
  assert.equal(descriptor.catalog.providers[0].providerId, 'minimax');
  assert.equal(descriptor.catalog.defaultModelId, 'm1');
});

test('refuses a catalog that carries a credential or a real endpoint', () => {
  const base = { entryPath: '/data/app/runtime-descriptor.json', pinCommit: 'a'.repeat(40), port: 4123, token: 'b'.repeat(32) };
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', apiKey: 'sk-live' }] } }), /descriptor_catalog_contains_credential/);
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', baseUrl: 'https://api.minimax.io/v1' }] } }), /descriptor_catalog_forbidden_field/);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test runtime-descriptor.test.mjs`
Expected: FAIL（新用例报错：目录未被接受或未被校验）

- [ ] **Step 3: 最小实现**

在 `runtime-descriptor.mjs` 中新增校验与装配：

```js
/** Names that may never appear inside the catalog projection. */
const CATALOG_CREDENTIAL_NAME = /(api[-_.]?key|secret|token|credential|password|passwd|cookie|authorization)/i;
/** Fields that would leak the real upstream endpoint into the guest. */
const CATALOG_FORBIDDEN_FIELD = /^(baseurl|base_url|url|endpoint)$/i;

function assertCatalogSafe(catalog) {
  const walk = (value) => {
    if (!value || typeof value !== 'object') return;
    for (const [key, entry] of Object.entries(value)) {
      if (CATALOG_CREDENTIAL_NAME.test(key)) throw new TypeError('descriptor_catalog_contains_credential');
      if (CATALOG_FORBIDDEN_FIELD.test(key)) throw new TypeError('descriptor_catalog_forbidden_field');
      walk(entry);
    }
  };
  walk(catalog);
}
```

并在 `createRuntimeDescriptor` 内：`broker === null` 分支返回 `catalog: catalog ?? null`；有 broker 的分支同样附带 `catalog`。写盘前调用 `assertCatalogSafe(catalog)`；目录本身用 `Object.freeze` 冻结（浅层 + providers 数组冻结）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test runtime-descriptor.test.mjs`
Expected: PASS（原有用例 + 2 新用例）

- [ ] **Step 5: 提交**

```bash
git add -- dsh-go/runtime/minimax-bot-host/runtime-descriptor.mjs dsh-go/runtime/minimax-bot-host/runtime-descriptor.test.mjs
git commit -m "Carry the non-secret provider catalog in the runtime descriptor"
```

---

### Task 4: 官方门面装配（投影 + 官方测试 + 默认选择）

**Files:**
- Modify: `dsh-go/runtime/minimax-bot-host/runtime-host-session.mjs`（`createBotRuntimeSession`）
- Modify: `dsh-go/runtime/minimax-bot-host/runtime-host-session-cases.mjs`（共用测试用例）
- Test: `dsh-go/runtime/minimax-bot-host/runtime-host-session.test.mjs`

**Interfaces:**
- Consumes: Task 1 的 `projectNativeCatalog`；既有 `assembled.host.application` 门面（`application.modelProviders.create/update/listUser/testProvider`、`application.models.select/list`）。
- Produces: `createBotRuntimeSession({ dataDir, startHost, signal, catalog = null })` 返回值新增 `providerBinding`：`{ forkProviderId: string, nativeProviderId: string, apiFormat: string, models: string[] }[]`（供 Task 5 建立 `byForkProviderId`）。

- [ ] **Step 1: 写失败测试**

在 `runtime-host-session-cases.mjs` 增加一个用例：假的 host 暴露 `application.modelProviders`（记录调用）与 `application.models.select`；断言：

```js
// 期望行为
const result = await createBotRuntimeSession({ dataDir, startHost, catalog: {
  providers: [{ providerId: 'minimax', name: 'MiniMax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
  defaultProviderId: 'minimax', defaultModelId: 'm1',
} });
assert.deepEqual(calls.create, [{ name: 'minimax', baseUrl: 'https://models.mbot.invalid/v1', apiKey: 'native-broker-placeholder', apiFormat: 'openai-completions', models: ['m1'] }]);
assert.deepEqual(calls.testProvider, [{ providerId: 'custom_provider:minimax' }]);
assert.deepEqual(calls.select, [{ providerId: 'custom_provider:minimax', modelId: 'm1' }]);
assert.deepEqual(result.providerBinding, [{ forkProviderId: 'custom_provider:minimax', nativeProviderId: 'minimax', apiFormat: 'openai-completions', models: ['m1'] }]);
```

同时断言：`catalog` 为 `null` 时不发生任何 provider 调用（既有行为不变）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test runtime-host-session.test.mjs`
Expected: FAIL（尚无 provider 装配）

- [ ] **Step 3: 最小实现**

在 `runtime-host-session.mjs`：

```js
import { projectNativeCatalog } from './model-catalog-projection.mjs';

const VIRTUAL_ORIGIN = 'https://models.mbot.invalid';
const PLACEHOLDER_KEY = 'native-broker-placeholder';

function baseUrlFor(apiFormat) {
  if (apiFormat === 'anthropic-messages') return VIRTUAL_ORIGIN;
  if (apiFormat === 'google-generative-ai') return `${VIRTUAL_ORIGIN}/v1beta`;
  return `${VIRTUAL_ORIGIN}/v1`;
}
```

boot 之后（拿到 `assembled.host.application` 且 `scope.gateway` 注册成功之后、返回 `agent` 之前）执行装配：

1. `const projection = projectNativeCatalog(catalog)`（`catalog` 为 `null` 则跳过整段）；
2. 对每个 upsert：
   - `const existing = (await application.modelProviders.listUser()).find((entry) => entry?.name === upsert.displayName)`；
   - 存在 → `await application.modelProviders.update({ providerId: existing.providerId, name: upsert.displayName, baseUrl: baseUrlFor(upsert.apiFormat), apiFormat: upsert.apiFormat, models: upsert.models })`（**不传 apiKey**，官方三态=保留）；
   - 不存在 → `const view = await application.modelProviders.create({ name: upsert.displayName, baseUrl: baseUrlFor(upsert.apiFormat), apiKey: PLACEHOLDER_KEY, apiFormat: upsert.apiFormat, models: upsert.models })`；
   - `forkProviderId = existing?.providerId ?? view.providerId`；
   - `await application.modelProviders.testProvider({ providerId: forkProviderId })`；
   - 记录 `providerBinding.push({ forkProviderId, nativeProviderId: upsert.providerId, apiFormat: upsert.apiFormat, models: upsert.models })`；
3. `projection.defaultSelection` 存在 → `await application.models.select({ providerId: forkProviderIdOf(defaultSelection.providerId), modelId: defaultSelection.modelId })`；
4. 装配失败（官方拒绝）→ 抛出带稳定 code 的错误（`runtime_provider_projection_failed`），**不静默继续**。

注意：`application.models.select` 的 `providerId` 必须是 **fork 侧** providerId（形如 `custom_provider:<key>`），不是 native providerId。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test runtime-host-session.test.mjs runtime-host-session-cases.mjs`
Expected: PASS（原用例 + 新用例）

- [ ] **Step 5: 提交**

```bash
git add -- dsh-go/runtime/minimax-bot-host/runtime-host-session.mjs dsh-go/runtime/minimax-bot-host/runtime-host-session-cases.mjs dsh-go/runtime/minimax-bot-host/runtime-host-session.test.mjs
git commit -m "Project the native provider catalog through the upstream provider API"
```

---

### Task 5: 每轮绑定接入 agent port

**Files:**
- Modify: `dsh-go/runtime/minimax-bot-host/agent-port.mjs`
- Test: `dsh-go/runtime/minimax-bot-host/agent-port.test.mjs`

**Interfaces:**
- Consumes: Task 2 的 `createNativeModelBinding`；Task 4 的 `providerBinding`（装配成 `byForkProviderId`）。
- Produces: `createBotAgentPort({ agent, caller, binding = null, resolveModelKey = null })`：
  - `run(input, options)`：若 `binding` 存在，先取模型键 `input.model ?? await resolveModelKey(input.sessionId)`，再 `binding.runForModelKey(modelKey, () => agent.run(...))`；
  - `answer(input, options)`：同上（`input.model` 可能为空，走 `resolveModelKey`）；
  - 模型键缺失或不在目录内 → 抛 `NativeBindingError('model_not_available')`（该轮明确拒绝，不直连、不给假回答）。

- [ ] **Step 1: 写失败测试**（追加到 `agent-port.test.mjs`）

```js
test('runs the turn inside the bound native route', async () => {
  const order = [];
  const binding = { runForModelKey: (key, operation) => { order.push(['bind', key]); return operation(); }, install() {} };
  const agent = { run: async () => { order.push(['run']); return 'ok'; }, models: async () => [], reply: async () => {}, answer: async () => {} };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding });
  const result = await port.run({ text: 'hi', sessionId: 's1', model: 'custom_provider:minimax/m1' });
  assert.equal(result, 'ok');
  assert.deepEqual(order, [['bind', 'custom_provider:minimax/m1'], ['run']]);
});

test('resolves the session model when the caller does not name one', async () => {
  const binding = { runForModelKey: (key) => key };
  const agent = { run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {} };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding, resolveModelKey: async () => 'custom_provider:minimax/m2' });
  assert.equal(await port.run({ text: 'hi', sessionId: 's1' }), 'custom_provider:minimax/m2');
});

test('refuses a turn whose model is not in the native catalog', async () => {
  const binding = { runForModelKey: () => { throw new NativeBindingError('model_not_available'); }, install() {} };
  const agent = { run: async () => 'ok', models: async () => [], reply: async () => {}, answer: async () => {} };
  const port = createBotAgentPort({ agent, caller: { pluginId: 'builtin:minimax-bot', runId: 'r1' }, binding });
  await assert.rejects(() => port.run({ text: 'hi', sessionId: 's1', model: 'custom_provider:other/m1' }), (error) => error.code === 'model_not_available');
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test agent-port.test.mjs`
Expected: FAIL（binding 参数被忽略）

- [ ] **Step 3: 最小实现**

在 `agent-port.mjs` 中给 `run`/`answer` 增加绑定包装；未提供 `binding` 时保持原行为（既有调用方不受影响）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd dsh-go/runtime/minimax-bot-host && node --test agent-port.test.mjs`
Expected: PASS（原用例 + 3 新用例）

- [ ] **Step 5: 提交**

```bash
git add -- dsh-go/runtime/minimax-bot-host/agent-port.mjs dsh-go/runtime/minimax-bot-host/agent-port.test.mjs
git commit -m "Route each bot turn through the native model binding"
```

---

### Task 6: 真实 fork factory 验收（仅 CI）

**Files:**
- Modify: `dsh-go/tooling/runtime-host-probe/runtime-host-child.mjs` 或既有 host 验收用例（按该工程现有结构）
- Test: `dsh-go/tooling/runtime-host-probe/runtime-host.test.mjs`

**Interfaces:**
- Consumes: Task 1–5 的产出；`dsh-go/tooling/runtime-host-probe` 的真实 fork 依赖（Linux）。
- Produces: 一个真实 factory 用例，断言：投影后官方 `application.models.list()` 能列出该 provider/model；`application.models.select()` 后默认模型生效；guest 的 `<dataDir>/config.yaml` 里只有占位 key、没有真实 Key。

- [ ] **Step 1: 写失败测试**

在该工程的 host 验收用例里加一例：用**假 provider catalog**（非密钥、虚拟 origin、占位 key）走 Task 4 装配，然后：

```js
const listed = await application.models.list();
assert.equal(listed.some((entry) => entry.providerId === 'custom_provider:minimax'), true);
assert.equal(await application.models.select({ providerId: 'custom_provider:minimax', modelId: 'm1' }), true);
const config = await readFile(join(dataDir, 'config.yaml'), 'utf8');
assert.equal(config.includes('native-broker-placeholder'), true);
assert.equal(/sk-[A-Za-z0-9]/.test(config), false);
```

- [ ] **Step 2: 本地确认无法执行该验收并如实记录**

Run: `npm test --prefix dsh-go/tooling/runtime-host-probe`（本机：Linux 原生依赖不可用 → 预期失败/跳过）
Expected: 本机**不得**通过；记录失败原因，交由 CI 验证。

- [ ] **Step 3: 提交并推送触发 CI**

```bash
git add -- dsh-go/tooling/runtime-host-probe
git commit -m "Verify the projected provider catalog against the real fork factory"
git push origin feat/t-plugins
```

- [ ] **Step 4: 读取 CI 结论**

Run: `gh run list --limit 5 --json databaseId,name,status,conclusion,headSha`
Expected: 对应 workflow `success`；失败则取真实失败 step/annotation，**不猜原因、不放宽校验**。

- [ ] **Step 5: 记录结果**

把 run id、结论、断言范围写进 `docs/handoff/T-RuntimeHost.md`（本阶段小节），并在 `docs/handoff/NEXT-AGENT.md` 更新当前状态。

---

### Task 7: 交接文档更新与阶段收尾

**Files:**
- Modify: `docs/handoff/T-RuntimeBroker.md`、`docs/handoff/T-RuntimeHost.md`、`docs/HANDOFF.md`、`docs/handoff/NEXT-AGENT.md`
- Modify: `docs/research/native-model-metadata-mapping.md`（补本轮官方门面结论）

**Interfaces:**
- Consumes: Task 1–6 的真实结果。
- Produces: 与代码状态一致的交接记录（含未验证项）。

- [ ] **Step 1: 更新专题文档**

在 `T-RuntimeHost.md` 新增小节：复用了哪些官方 API、投影与绑定的边界、CI run 与结论、**未验证项**（真实 provider、ARM64、真机、产品 APK、每 Bot caller 可信绑定、设置热更新）。

- [ ] **Step 2: 更新总入口与合同**

`docs/HANDOFF.md` 的「接手第一项」与 `docs/handoff/NEXT-AGENT.md` 第 4/5 节改为：本阶段已完成什么、下一项是什么（每 Bot 可信 caller 绑定 / 真实 provider 验收 / 设置热更新）、当前唯一下一步。

- [ ] **Step 3: 本地检查**

Run: `cd D:/mbotapp && python tooling/docs-links/check_links.py --root . README.md docs tooling/docs-links`
Expected: `checked N document(s), found 0 issue(s)`

Run: `git diff --check`
Expected: 无输出、退出码 0

- [ ] **Step 4: 提交并推送**

```bash
git add -- docs/handoff/T-RuntimeBroker.md docs/handoff/T-RuntimeHost.md docs/HANDOFF.md docs/handoff/NEXT-AGENT.md docs/research/native-model-metadata-mapping.md
git commit -m "Record the provider projection and native binding stage"
git push origin feat/t-plugins
```

- [ ] **Step 5: 确认保护分支未动**

Run: `git ls-remote --heads origin main dev/integration feat/t-plugins`
Expected: `main` 仍为 `e32fbd7383cd9bdc268aa7df2ec9150e8bc4e48e`，`dev/integration` 仍为 `891aefa71c5aea952ec8aa2502c2ada854854042`，`feat/t-plugins` 为本阶段新提交。

---

## 自检记录

- **规格覆盖**：目标§1 → Task 4/5；非目标§2 → Global Constraints；复用清单§3 → Task 1/4/5；事实基线§4 → 各任务实现要点；组件§5.1 → Task 1；§5.2 → Task 2；§5.3 → Task 4；§5.4 切模型语义 → Task 5（在飞轮不换、下一轮换由"每轮开始时解析"保证）；安全不变量§6 → Task 2/3 的拒绝断言 + Task 6 的配置断言；错误处理§7 → 各任务的稳定 code；验收§8 A–E → Task 1/2（单测）、Task 6（B/C/D）、E 写入未验证项。
- **占位符**：无 TBD/TODO；每步含可执行命令与代码。
- **类型一致**：`projectNativeCatalog` 返回形状在 Task 1/4 一致；`NativeBindingError.code` 取值在 Task 2/5 一致；`providerBinding` 字段名在 Task 4/5 一致；`createRuntimeDescriptor` 新增 `catalog` 在 Task 3/4 一致。
