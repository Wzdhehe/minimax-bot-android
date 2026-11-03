// SPDX-License-Identifier: AGPL-3.0-only
// Projection of the pinned WebUI getUsageQuota contract. The composition root
// may supply its UsageQuotaClient.getUsageQuota callback; no API key is used.
export async function readUsageQuota(getUsageQuota, forceRefresh = false) {
  if (!getUsageQuota) return { signedIn: false };
  const value = await getUsageQuota({ forceRefresh });
  if (!value?.signedIn) return { signedIn: false };
  const result = { signedIn: true };
  for (const field of ['planName', 'subscriptionType', 'tokenPlanTier']) {
    if (typeof value[field] === 'string') result[field] = value[field].slice(0, 128);
  }
  if (typeof value.hasTokenPlan === 'boolean') result.hasTokenPlan = value.hasTokenPlan;
  if (typeof value.creditBalance === 'string' && /^\d+(?:\.\d+)?$/.test(value.creditBalance)) result.creditBalance = value.creditBalance;
  if (value.quota) {
    const window = (raw) => {
      const clean = { unlimited: raw?.unlimited === true };
      for (const field of ['usedPercent', 'totalPercent']) {
        if (Number.isFinite(raw?.[field]) && raw[field] >= 0 && raw[field] <= 100) clean[field] = raw[field];
      }
      if (Number.isFinite(raw?.resetAtMs) && raw.resetAtMs >= 0 && raw.resetAtMs <= 8640000000000000) clean.resetAtMs = raw.resetAtMs;
      return clean;
    };
    result.quota = { fiveHour: window(value.quota.fiveHour), weekly: window(value.quota.weekly) };
  }
  return result;
}
