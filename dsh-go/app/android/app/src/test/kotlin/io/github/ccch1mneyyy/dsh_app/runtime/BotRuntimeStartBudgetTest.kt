// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests pinning the isolated-process start budget. */
class BotRuntimeStartBudgetTest {
    @Test fun startTimeoutIs180Seconds() {
        assertEquals(180L, BotRuntimeStartBudget.START_TIMEOUT_SECONDS)
    }

    @Test fun descriptorWaitMatchesStartTimeout() {
        assertEquals(
            BotRuntimeStartBudget.START_TIMEOUT_SECONDS * 1_000_000_000L,
            BotRuntimeStartBudget.DESCRIPTOR_WAIT_NANOS,
        )
    }

    @Test fun budgetIsFiniteAndLongerThanTheOld90Seconds() {
        assertTrue(BotRuntimeStartBudget.START_TIMEOUT_SECONDS > 90)
        assertTrue(BotRuntimeStartBudget.START_TIMEOUT_SECONDS <= 300)
    }
}
