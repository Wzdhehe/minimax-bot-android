package io.github.ccch1mneyyy.dsh_app

/** Classifies the physical device, not the current rotation or window width. */
internal object BotDeviceClass {
    private const val TABLET_SMALLEST_WIDTH_DP = 600

    fun isTablet(smallestScreenWidthDp: Int): Boolean =
        smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP
}
