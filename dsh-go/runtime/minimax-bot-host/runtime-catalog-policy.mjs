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
