// SPDX-License-Identifier: AGPL-3.0-only
// Preserve every upstream export. Only the trusted constructor is wrapped;
// the original gateway, its admission checks and all session ports are reused.
export * from '@mcode-runtime/miniapp-index';
export { createMiniAppAgentGateway } from './runtime-host-session.mjs';
