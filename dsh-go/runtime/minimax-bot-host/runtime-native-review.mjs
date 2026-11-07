// SPDX-License-Identifier: AGPL-3.0-only
import { AsyncLocalStorage } from 'node:async_hooks';

const construction = new AsyncLocalStorage();

/** Trusted host composition only; no HTTP/descriptor field selects policy. */
export function withNativeByokHost(operation) {
  return construction.run(true, operation);
}

/** Native-only BYOK uses mcode's existing CLI review policy: custom models
 * use their provider, managed models retain their review requirement. Keep
 * the actual WebUI owner, all wiring and every other execution policy cell.
 * No first-party moderation response is fabricated or network fallback added.
 */
export function nativeReviewOptions(options) {
  if (!construction.getStore()) return options;
  const policy = options?.ownerPolicy;
  if (policy?.kind !== 'webui' || policy.execution?.reviewPolicy !== 'neither') {
    throw Object.assign(new Error('runtime_review_policy_mismatch'), { code: 'runtime_review_policy_mismatch' });
  }
  return { ...options, ownerPolicy: { ...policy, execution: { ...policy.execution, reviewPolicy: 'cli' } } };
}
