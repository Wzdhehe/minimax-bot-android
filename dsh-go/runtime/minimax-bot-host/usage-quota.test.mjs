// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readUsageQuota } from './usage-quota.mjs';

test('without an OAuth account capability the quota projection is signed out', async () => {
  assert.deepEqual(await readUsageQuota(undefined), { signedIn: false });
  assert.deepEqual(await readUsageQuota(async () => ({ signedIn: false, accessToken: 'hidden' })), { signedIn: false });
});
test('account projection carries quota and plan fields but no lease or API keys', async () => {
  const value = await readUsageQuota(async ({ forceRefresh }) => {
    assert.equal(forceRefresh, true);
    return { signedIn: true, accessToken: 'secret', apiKey: 'secret', planName: 'M Plan', creditBalance: '123.45',
      quota: { fiveHour: { usedPercent: 17, resetAtMs: 1000, unlimited: false, accessToken: 'secret' }, weekly: { usedPercent: 32, unlimited: false } } };
  }, true);
  assert.equal(value.planName, 'M Plan');
  assert.equal(value.quota.fiveHour.usedPercent, 17);
  assert.equal(value.quota.weekly.usedPercent, 32);
  assert.equal(value.creditBalance, '123.45');
  assert.ok(!JSON.stringify(value).includes('secret'));
});
test('account failures stay failures and invalid percentages are not invented', async () => {
  await assert.rejects(readUsageQuota(async () => { throw new Error('account service unavailable'); }));
  const value = await readUsageQuota(async () => ({ signedIn: true, creditBalance: 'not numeric', quota: { fiveHour: { usedPercent: NaN }, weekly: { usedPercent: 400 } } }));
  assert.equal(value.creditBalance, undefined);
  assert.equal(value.quota.fiveHour.usedPercent, undefined);
  assert.equal(value.quota.weekly.usedPercent, undefined);
});
