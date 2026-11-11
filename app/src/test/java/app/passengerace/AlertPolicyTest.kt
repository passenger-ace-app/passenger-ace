package app.passengerace

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private val p = AlertPolicy(Turn.RIGHT)

    @Test
    fun firstAlertOnceInsideWindow() {
        assertFalse(p.check(2401, Turn.NONE, true))
        assertTrue(p.check(2400, Turn.NONE, true))
        assertFalse(p.check(2300, Turn.NONE, true))
    }

    @Test
    fun neverAtOrInside1000m() {
        assertFalse(p.check(1000, Turn.NONE, true))
        assertFalse(p.check(10, Turn.NONE, true))
    }

    // 내비나 다른 안내가 말하는 동안 기다린 알림은 말할 수 있게 된 그때 거리로 다시 본다. 너무 가까우면 버린다.
    @Test
    fun waitedAlertIsDroppedWhenBranchIsTooClose() {
        assertFalse(p.check(2400, Turn.NONE, false))
        assertFalse(p.check(1200, Turn.NONE, false))
        assertFalse(p.check(1000, Turn.NONE, true))
        assertFalse(p.check(800, Turn.NONE, true))
    }

    @Test
    fun repeatOnlyOnceBetween1500And1000() {
        assertTrue(p.check(2400, Turn.NONE, true))
        assertFalse(p.check(1501, Turn.NONE, true))
        assertTrue(p.check(1500, Turn.NONE, true))
        assertFalse(p.check(1400, Turn.NONE, true))
        assertFalse(p.check(1001, Turn.NONE, true))
    }

    @Test
    fun repeatNotInside1000m() {
        assertTrue(p.check(2400, Turn.NONE, true))
        assertFalse(p.check(1000, Turn.NONE, true))
    }

    @Test
    fun sameSideSignalStopsRepeatEvenIfTurnedOffAgain() {
        assertTrue(p.check(2400, Turn.NONE, true))
        assertFalse(p.check(2000, Turn.RIGHT, true))
        assertFalse(p.check(1500, Turn.NONE, true))
    }

    @Test
    fun otherSideSignalDoesNotStopRepeat() {
        assertTrue(p.check(2400, Turn.NONE, true))
        assertFalse(p.check(2000, Turn.LEFT, true))
        assertTrue(p.check(1500, Turn.LEFT, true))
    }

    @Test
    fun signalBeforeFirstAlertDoesNotCount() {
        assertFalse(p.check(3000, Turn.RIGHT, true))
        assertTrue(p.check(2400, Turn.NONE, true))
        assertTrue(p.check(1500, Turn.NONE, true))
    }

    @Test
    fun cutAlertIsSaidAgainOnceAfterNavi() {
        assertTrue(p.check(2400, Turn.NONE, true))
        p.cut()
        assertFalse(p.check(2300, Turn.NONE, false)) // 내비가 말하는 중
        assertTrue(p.check(2250, Turn.NONE, true))
        assertFalse(p.check(2200, Turn.NONE, true))
        assertTrue(p.check(1500, Turn.NONE, true))
    }

    @Test
    fun cutAlertNotSaidAgainInside1000m() {
        assertTrue(p.check(2400, Turn.NONE, true))
        p.cut()
        assertFalse(p.check(1200, Turn.NONE, false))
        assertFalse(p.check(1000, Turn.NONE, true))
    }

    @Test
    fun cutAlertNotSaidAgainWhenSameSideSignalIsOn() {
        assertTrue(p.check(2400, Turn.NONE, true))
        p.cut()
        assertFalse(p.check(2300, Turn.RIGHT, true))
        assertFalse(p.check(1500, Turn.NONE, true))
    }

    @Test
    fun lateCutAlertDoesNotRepeat() {
        assertTrue(p.check(2400, Turn.NONE, true))
        assertTrue(p.check(1500, Turn.NONE, true))
        p.cut()
        assertTrue(p.check(1400, Turn.NONE, true))
        assertFalse(p.check(1200, Turn.NONE, true))
    }

    @Test
    fun waitsWhileCannotSpeak() {
        assertFalse(p.check(2300, Turn.NONE, false))
        assertTrue(p.check(2200, Turn.NONE, true))
    }

    @Test
    fun lateFirstAlertDoesNotRepeatRightAway() {
        assertTrue(p.check(1400, Turn.NONE, true))
        assertFalse(p.check(1300, Turn.NONE, true))
    }

    @Test
    fun sampleRouteAlertsOnceWithSignalTwiceWithout() {
        val route = SampleRoute(listOf(25.7, 55.7, 113.6, 158.6, 190.1), 203, 22, Turn.RIGHT)
        fun ticks(turn: Turn): List<Int> {
            val policy = AlertPolicy(route.branch)
            return (0..203).filter { policy.check(route.tick(it).distanceM, turn, true) }
        }
        assertEquals(listOf(94), ticks(Turn.RIGHT))
        assertEquals(listOf(94, 135), ticks(Turn.NONE))
    }

    @Test
    fun onlyInterchangeAndKeepBranchesAreBig() {
        assertEquals(Turn.RIGHT, bigBranchSide(TurnType.TURN_EXIT_IC_R))
        assertEquals(Turn.RIGHT, bigBranchSide(TurnType.TURN_KEEP_R))
        assertEquals(Turn.LEFT, bigBranchSide(TurnType.TURN_ENTER_IC_L))
        assertEquals(null, bigBranchSide(TurnType.TURN_RIGHT))
        assertEquals(null, bigBranchSide(TurnType.TURN_TOLL))
    }

    @Test
    fun rightBranchDistanceAddsLegsFromTheFront() {
        fun t(type: TurnType, m: Double) = TBTInfo(type, m, "")
        val list = listOf(t(TurnType.TURN_LEFT, 80.0), t(TurnType.TURN_RIGHT, 380.0), t(TurnType.TURN_KEEP_L, 730.0),
            t(TurnType.TURN_EXIT_IC_R, 820.0), t(TurnType.TURN_KEEP_R, 200.0))
        assertEquals(2010, rightBranchDistance(list))
        assertEquals(null, rightBranchDistance(list.take(3)))
        assertEquals(null, rightBranchDistance(emptyList()))
    }
}
