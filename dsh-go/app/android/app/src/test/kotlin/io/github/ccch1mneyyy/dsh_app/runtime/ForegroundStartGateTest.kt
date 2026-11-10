// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests for the Stage D start-order gate. */
class ForegroundStartGateTest {
    /** Collects the scheduled idle tasks so a test can run them by hand. */
    private class Idle {
        private val tasks = mutableListOf<() -> Unit>()
        /** Every task handed to the scheduler since the start. */
        var scheduled = 0
        fun schedule(task: () -> Unit) { tasks.add(task); scheduled++ }
        fun runAt(index: Int) { tasks[index].invoke() }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeAt(0).invoke() }
        /** Tasks that were scheduled but have not run yet. */
        val pending: Int get() = tasks.size
    }

    private class Fixture {
        val idle = Idle()
        var launches = 0
        var failure: Exception? = null
        val failures = mutableListOf<Exception>()
        val gate = ForegroundStartGate(
            whenIdle = { task -> idle.schedule(task) },
            launch = { launches++; failure?.let { throw it } },
            onLaunchFailed = { failures.add(it) },
        )
    }

    @Test fun launchWaitsForIdleNotTheCaller() {
        val fixture = Fixture()
        assertTrue(fixture.gate.request())
        assertEquals("no_launch_before_idle", 0, fixture.launches)
        assertTrue(fixture.gate.pending)
        assertEquals("one_idle_task", 1, fixture.idle.scheduled)
        fixture.idle.runAll()
        assertEquals("launched_once", 1, fixture.launches)
        assertFalse(fixture.gate.pending)
    }

    @Test fun repeatedRequestsCoalesceIntoOneLaunch() {
        val fixture = Fixture()
        assertTrue(fixture.gate.request())
        assertFalse("second_request_is_coalesced", fixture.gate.request())
        assertEquals("one_idle_task", 1, fixture.idle.scheduled)
        fixture.idle.runAll()
        assertEquals("launched_once", 1, fixture.launches)
        assertFalse(fixture.gate.pending)
    }

    @Test fun requestAfterLaunchSchedulesAgain() {
        val fixture = Fixture()
        assertTrue(fixture.gate.request())
        fixture.idle.runAll()
        assertEquals("launched_once", 1, fixture.launches)
        assertTrue("gate_reopens", fixture.gate.request())
        assertEquals("second_idle_task", 2, fixture.idle.scheduled)
        assertEquals("second_task_is_pending", 1, fixture.idle.pending)
        fixture.idle.runAll()
        assertEquals("launched_twice", 2, fixture.launches)
    }

    @Test fun cancelBeforeIdleSuppressesLaunch() {
        val fixture = Fixture()
        assertTrue(fixture.gate.request())
        fixture.gate.cancel()
        assertFalse(fixture.gate.pending)
        fixture.idle.runAll()
        assertEquals("no_launch", 0, fixture.launches)
        assertFalse(fixture.gate.pending)
    }

    @Test fun launchFailureIsReportedAndGateReopens() {
        val fixture = Fixture()
        val error = IllegalStateException("start_unavailable")
        fixture.failure = error
        assertTrue(fixture.gate.request())
        fixture.idle.runAll()
        assertEquals("launch_attempted", 1, fixture.launches)
        assertEquals("failure_reported_once", 1, fixture.failures.size)
        assertSame(error, fixture.failures[0])
        assertFalse(fixture.gate.pending)
        fixture.failure = null
        assertTrue("gate_reopens_after_failure", fixture.gate.request())
        fixture.idle.runAll()
        assertEquals("launched_again", 2, fixture.launches)
        assertEquals("no_extra_failure", 1, fixture.failures.size)
    }

    @Test fun cancelBetweenRequestsLetsOnlyTheNewerIdleTaskLaunch() {
        val fixture = Fixture()
        assertTrue(fixture.gate.request())
        fixture.gate.cancel()
        assertTrue(fixture.gate.request())
        assertEquals("two_idle_tasks", 2, fixture.idle.scheduled)
        fixture.idle.runAt(0)
        assertEquals("cancelled_task_does_not_launch", 0, fixture.launches)
        assertTrue("newer_request_still_pending", fixture.gate.pending)
        fixture.idle.runAt(1)
        assertEquals("newer_task_launches_once", 1, fixture.launches)
        assertFalse(fixture.gate.pending)
    }
}
