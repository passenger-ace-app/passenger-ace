package app.passengerace

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleRouteTest {
    private val route = SampleRoute(listOf(25.7, 55.7, 113.6, 158.6, 190.1), 203, 22, Turn.RIGHT)

    @Test
    fun distanceFallsToZeroAtBranch() {
        assertEquals(203 * 22, route.tick(0).distanceM)
        assertEquals(22, route.tick(202).distanceM)
        assertEquals(0, route.tick(203).distanceM)
    }

    @Test
    fun everyNavVoiceFallsOnExactlyOneTick() {
        val fired = (0..203).flatMap { route.tick(it).nav }
        assertEquals(listOf(0, 1, 2, 3, 4), fired)
        assertEquals(listOf(0), route.tick(25).nav)
    }
}
