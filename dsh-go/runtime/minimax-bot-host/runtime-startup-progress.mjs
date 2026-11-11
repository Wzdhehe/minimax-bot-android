// SPDX-License-Identifier: AGPL-3.0-only
// Trusted diagnostic vocabulary only. No request data or exception details.
export const RUNTIME_STARTUP_PHASES = Object.freeze([
  'assets_loading', 'assets_loaded', 'filesystem_loading', 'filesystem_installed',
  'runtime_importing', 'runtime_imported', 'runtime_factory_starting', 'runtime_factory_started',
  'native_catalog_starting', 'native_catalog_projected', 'bot_service_starting', 'bot_service_started',
  'ingress_started',
]);

export function reportStartupProgress(observer, phase) {
  if (typeof observer !== 'function' || !RUNTIME_STARTUP_PHASES.includes(phase)) return;
  // Diagnostics never decide whether startup/cleanup succeeded.
  try { observer(phase); } catch {}
}
