// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { closeSync, fstatSync, openSync } from 'node:fs';
import { setImmediate as nextTurn } from 'node:timers/promises';
import { test } from 'node:test';
import { startBotFdIngress } from './bot-fd-ingress.mjs';

function controlledSource() {
  const requests = [], waiting = [];
  return {
    next(signal) {
      return new Promise((resolve, reject) => {
        const request = { signal, resolve, reject };
        const listener = waiting.shift();
        if (listener) listener(request); else requests.push(request);
      });
    },
    request() { return requests.length ? Promise.resolve(requests.shift()) : new Promise((resolve) => waiting.push(resolve)); },
  };
}

function assertClosed(fd) {
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
}

test('forwards sequential grants and closes a late FD when stopped', { timeout: 5000 }, async () => {
  const source = controlledSource();
  const accepted = [];
  const ingress = startBotFdIngress({ next: source.next, transport: { accept(fd) { accepted.push(fd); closeSync(fd); } } });
  const first = await source.request();
  const fd1 = openSync('/dev/null', 'r');
  first.resolve(fd1);
  const second = await source.request();
  assertClosed(fd1);
  const fd2 = openSync('/dev/null', 'r');
  second.resolve(fd2);
  const third = await source.request();
  assertClosed(fd2);
  assert.equal(accepted.length, 2);
  const fd3 = openSync('/dev/null', 'r');
  await ingress.close();
  assert.equal(third.signal.aborted, true);
  await ingress.close();
  third.resolve(fd3);
  await nextTurn();
  assertClosed(fd3);
  assert.equal(accepted.length, 2);
});

test('an external abort wakes a source that does not cooperate and closes its late grant', { timeout: 5000 }, async () => {
  const source = controlledSource();
  const controller = new AbortController();
  const ingress = startBotFdIngress({ next: source.next, signal: controller.signal, transport: { accept() { assert.fail('unexpected acceptance'); } } });
  const pending = await source.request();
  const fd = openSync('/dev/null', 'r');
  controller.abort();
  await ingress.done;
  assert.equal(pending.signal.aborted, true);
  pending.resolve(fd);
  await nextTurn();
  assertClosed(fd);
});

test('closing concurrently with a resolved grant closes it without accepting', { timeout: 5000 }, async () => {
  const source = controlledSource();
  let accepted = false;
  const ingress = startBotFdIngress({ next: source.next, transport: { accept(fd) { accepted = true; closeSync(fd); } } });
  const pending = await source.request();
  const fd = openSync('/dev/null', 'r');
  pending.resolve(fd);
  const stopped = ingress.close();
  await stopped;
  await nextTurn();
  assert.equal(accepted, false);
  assertClosed(fd);
});

test('a running ingress accepts a sequence of grants without restarting', { timeout: 5000 }, async () => {
  const count = 500;
  let calls = 0, accepted = 0, complete;
  const finished = new Promise((resolve) => { complete = resolve; });
  const ingress = startBotFdIngress({
    next() { return calls++ < count ? openSync('/dev/null', 'r') : new Promise(() => {}); },
    transport: { accept(fd) { closeSync(fd); if (++accepted === count) complete(); } },
  });
  await finished;
  await ingress.close();
  assert.equal(accepted, count);
});

test('source and transport failures are sanitized and stop further grants', async () => {
  let calls = 0;
  const sourceFailure = startBotFdIngress({ next() { calls++; throw new Error('private source detail'); }, transport: { accept() {} } });
  await assert.rejects(sourceFailure.done, (error) => error.code === 'bot_transport_unavailable' && !error.message.includes('private'));
  assert.equal(calls, 1);
  await assert.rejects(sourceFailure.close(), (error) => error.code === 'bot_transport_unavailable');

  const fd = openSync('/dev/null', 'r');
  const transportFailure = startBotFdIngress({ next: () => fd, transport: { accept(value) { closeSync(value); throw new Error('private transport detail'); } } });
  await assert.rejects(transportFailure.done, (error) => error.code === 'bot_transport_unavailable' && !error.message.includes('private'));
  assertClosed(fd);
});

test('already-aborted and invalid sources never ask for a connection', async () => {
  const controller = new AbortController();
  controller.abort();
  let called = false;
  const ingress = startBotFdIngress({ next() { called = true; }, signal: controller.signal, transport: { accept() {} } });
  await ingress.done;
  assert.equal(called, false);
  const closedEarly = startBotFdIngress({ next() { called = true; }, transport: { accept() {} } });
  await closedEarly.close();
  assert.equal(called, false);
  assert.throws(() => startBotFdIngress({ next() {}, transport: {}, signal: controller.signal }), TypeError);
  assert.throws(() => startBotFdIngress({ next() {}, transport: { accept() {} }, signal: {} }), TypeError);
});
