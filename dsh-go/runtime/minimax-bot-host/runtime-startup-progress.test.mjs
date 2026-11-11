// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { RUNTIME_STARTUP_PHASES, reportStartupProgress } from './runtime-startup-progress.mjs';

test('only fixed startup phases reach an observer, never a supplied diagnostic string', () => {
  const events = [];
  for (const phase of RUNTIME_STARTUP_PHASES) reportStartupProgress((value) => events.push(value), phase);
  for (const phase of ['unknown', 'runtime_start\nprivate fixture', 'https://example.invalid/private', null]) {
    reportStartupProgress((value) => events.push(value), phase);
  }
  assert.deepEqual(events, [...RUNTIME_STARTUP_PHASES]);
  assert.ok(!events.includes('bot_ready'));
  assert.ok(Object.isFrozen(RUNTIME_STARTUP_PHASES));
});

test('missing or failing observers cannot decide startup success', () => {
  reportStartupProgress(null, 'assets_loading');
  reportStartupProgress(undefined, 'assets_loading');
  reportStartupProgress(() => { throw Error('diagnostic fixture'); }, 'assets_loading');
});

test('JS and the native logging gate use the same ordered phase contract', () => {
  const kotlin = readFileSync(new URL('../../app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/BotRuntimeStartupProgress.kt', import.meta.url), 'utf8');
  const rows = [...kotlin.split('val PHASES = listOf(')[1].matchAll(/"([a-z_]+)"/gu)].map((match) => match[1]);
  assert.deepEqual(rows, [...RUNTIME_STARTUP_PHASES]);
});
