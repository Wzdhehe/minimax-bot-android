// SPDX-License-Identifier: AGPL-3.0-only
// The field policy for the non-secret provider catalog that crosses the
// native/guest boundary.
//
// One copy of the rules: the descriptor refuses to write a catalog that
// breaks them and the projection layer refuses to read one. A credential or a
// real upstream endpoint in this catalog would reach the guest runtime, the
// agent and every file the agent can read.

/** Field names that may never cross the boundary. */
const CREDENTIAL_FIELD = /(api[-_.]?key|secret|token|credential|password|passwd|authorization|cookie)/i;

/** Field names that would carry the real upstream endpoint across. */
const ENDPOINT_FIELD = /^(baseurl|base_url|url|endpoint|host|hostname)$/i;

/** Non-secret flags whose name shares a credential-like word. */
const SAFE_FLAG_FIELDS = new Set(['hasApiKey']);

/**
 * The first violation in the catalog, or undefined when the catalog is safe.
 * Returns 'credential' or 'endpoint'.
 */
export function findCatalogViolation(value) {
  if (!value || typeof value !== 'object') return undefined;
  for (const [name, entry] of Object.entries(value)) {
    if (SAFE_FLAG_FIELDS.has(name)) {
      if (typeof entry !== 'boolean') return 'credential';
      continue;
    }
    if (CREDENTIAL_FIELD.test(name)) return 'credential';
    if (ENDPOINT_FIELD.test(name)) return 'endpoint';
    const nested = findCatalogViolation(entry);
    if (nested) return nested;
  }
  return undefined;
}

/** mcode UserModelInputView, restricted to non-secret per-model controls. */
export function validatedModelSettings(provider) {
  if (provider.modelSettings === undefined) return null; // legacy ID-only catalogs
  const fail = () => { throw new TypeError('native_model_settings_invalid'); };
  if (!Array.isArray(provider.models) || !Array.isArray(provider.modelSettings) ||
      provider.modelSettings.length > provider.models.length) fail();
  const fields = new Set(['modelId', 'enabled', 'limit', 'effortOptions', 'modalities', 'reasoning']);
  const entries = new Map();
  for (const row of provider.modelSettings) {
    if (!row || typeof row !== 'object' || Array.isArray(row) ||
        Object.keys(row).some((key) => !fields.has(key)) ||
        !provider.models.includes(row.modelId) || entries.has(row.modelId)) fail();
    const next = { modelId: row.modelId };
    for (const key of ['enabled', 'reasoning']) {
      if (row[key] !== undefined) {
        if (typeof row[key] !== 'boolean') fail();
        next[key] = row[key];
      }
    }
    if (row.limit !== undefined) {
      if (!row.limit || typeof row.limit !== 'object' || Array.isArray(row.limit) ||
          Object.entries(row.limit).some(([key, value]) => !['context', 'output'].includes(key) ||
            !Number.isSafeInteger(value) || value <= 0)) fail();
      next.limit = { ...row.limit };
    }
    if (row.effortOptions !== undefined) {
      if (!Array.isArray(row.effortOptions) || row.effortOptions.length > 32 ||
          row.effortOptions.some((level) => typeof level !== 'string' || !level.trim() || level.length > 64) ||
          (row.reasoning === false && row.effortOptions.length)) fail();
      next.effortOptions = [...new Set(row.effortOptions.map((level) => level.trim()))];
    }
    if (row.modalities !== undefined) {
      if (!row.modalities || typeof row.modalities !== 'object' || Array.isArray(row.modalities)) fail();
      next.modalities = {};
      for (const [key, value] of Object.entries(row.modalities)) {
        if (!['input', 'output'].includes(key) || !Array.isArray(value) || value.length > 5 ||
            value.some((mode) => !['text', 'image', 'pdf', 'video', 'audio'].includes(mode)) ||
            (key === 'output' && value.some((mode) => mode !== 'text'))) fail();
        next.modalities[key] = [...new Set(['text', ...value])];
      }
      next.attachment = next.modalities.input?.some((mode) => mode !== 'text') ?? false;
    }
    entries.set(row.modelId, next);
  }
  return provider.models.map((modelId) => entries.get(modelId) ?? { modelId, enabled: true });
}
