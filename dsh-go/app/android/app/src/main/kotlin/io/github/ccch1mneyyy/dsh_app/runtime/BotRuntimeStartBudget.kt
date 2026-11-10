// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * Host-side start budget for the isolated Bot becoming ready.
 *
 * This is our own timeout, not the system FGS timer, and does not change
 * process isolation: IsolatedBotService stays isolatedProcess=true and Node
 * never runs in the main or same-UID process.
 *
 * Call sites (must stay in sync; no remaining raw 90 / 90_000_000_000L):
 * - BotRuntimeExecution.start() schedules fail("bot_runtime_start_timeout")
 *   after [START_TIMEOUT_SECONDS] if the run is still STARTING. The timer is
 *   armed at the beginning of start() so a hang before START is still covered.
 *   The 10s bind/identity waits and the 25s close wait are unchanged.
 * - BotRuntimeManager.descriptorWhenSettled() waits up to
 *   [DESCRIPTOR_WAIT_NANOS] while phase == "starting" so the UI does not
 *   declare a still-starting service absent before the runtime budget elapses.
 *
 * Why 180s, not 90s: the previous 90s value followed an API35 product run
 * where first-install seeding took 62s. On a no-KVM software emulator after
 * the FGS start-order fix, IsolatedBotService was still making progress
 * (libmbot_node_runtime.so loaded, backup worker just spawned, no READY or
 * FAILED) when the 90s timer fired. 180s keeps a finite budget with margin
 * for that path. Do not raise this to "forever".
 */
internal object BotRuntimeStartBudget {
    /** Seconds after start() until bot_runtime_start_timeout if still STARTING. */
    const val START_TIMEOUT_SECONDS: Long = 180

    /** Nanoseconds the UI may wait for a descriptor while phase == starting. */
    const val DESCRIPTOR_WAIT_NANOS: Long = START_TIMEOUT_SECONDS * 1_000_000_000L
}
