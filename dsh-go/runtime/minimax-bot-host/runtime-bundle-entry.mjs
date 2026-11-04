// SPDX-License-Identifier: AGPL-3.0-only
// Build-only trusted host exports. This module deliberately does not boot an
// Agent, open a listener, register a caller, or change the Android launcher.
export { createWebuiRuntimeHost } from '@mcode-runtime/assembly';
export { createBotAgentPort } from './agent-port.mjs';
export { startBotService } from './service.mjs';
