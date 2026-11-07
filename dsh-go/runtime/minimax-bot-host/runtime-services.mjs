// SPDX-License-Identifier: AGPL-3.0-only
export * from '@mcode-runtime/services';
import { createRuntimeServices as upstreamServices } from '@mcode-runtime/services';
import { nativeReviewOptions } from './runtime-native-review.mjs';

export function createRuntimeServices(options) {
  return upstreamServices(nativeReviewOptions(options));
}
