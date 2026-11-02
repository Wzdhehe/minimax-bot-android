package io.github.ccch1mneyyy.dsh_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotDeviceClassTest {
    @Test fun phoneDoesNotBecomeTabletWhenRotated() {
        assertFalse(BotDeviceClass.isTablet(360))
        assertFalse(BotDeviceClass.isTablet(599))
    }

    @Test fun tabletsCanFollowSystemOrientation() {
        assertTrue(BotDeviceClass.isTablet(600))
        assertTrue(BotDeviceClass.isTablet(840))
    }
}
