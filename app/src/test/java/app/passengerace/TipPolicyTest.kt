package app.passengerace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipPolicyTest {
    private var clock = 0L
    private val tips = TipPolicy { clock }

    private fun rise(id: String) {
        tips.level(id, false)
        tips.level(id, true)
    }

    @Test
    fun firstValueIsOnlyTheBaseline() {
        tips.level(TIP_TPMS, true) // 앱을 켰을 때 이미 켜져 있던 경고등
        assertNull(tips.next(true))
        rise(TIP_TPMS)
        assertEquals(TIP_TPMS, tips.next(true))
    }

    @Test
    fun sameTipOnlyOncePerDrive() {
        tips.gear(Gear.D)
        rise(TIP_BATTERY)
        assertEquals(TIP_BATTERY, tips.next(true))
        rise(TIP_BATTERY)
        assertNull(tips.next(true))
        tips.gear(Gear.P)
        clock += NEW_DRIVE_PARKED_MS - 1 // 30분이 안 된 휴게소 정차는 같은 주행
        tips.gear(Gear.D)
        rise(TIP_BATTERY)
        assertNull(tips.next(true))
    }

    @Test
    fun parkedThirtyMinutesOrArrivedStartsNewDrive() {
        tips.gear(Gear.D)
        rise(TIP_TPMS)
        tips.next(true)
        tips.gear(Gear.P)
        clock += NEW_DRIVE_PARKED_MS
        tips.gear(Gear.D)
        rise(TIP_TPMS)
        assertEquals(TIP_TPMS, tips.next(true))
        tips.arrived()
        tips.gear(Gear.P)
        tips.gear(Gear.D)
        rise(TIP_TPMS)
        assertEquals(TIP_TPMS, tips.next(true))
    }

    @Test
    fun waitsWhileSomethingMoreImportantSpeaksThenOneAtATimeInOrder() {
        rise(TIP_TPMS)
        rise(TIP_FUEL)
        assertNull(tips.next(false)) // 내비 안내나 큰 갈림길 알림이 말하는 중
        assertEquals(TIP_TPMS, tips.next(true))
        assertEquals(TIP_FUEL, tips.next(true))
        assertNull(tips.next(true))
    }

    @Test
    fun rainAndRestOnlyInDriveWarningsAnyGear() {
        tips.gear(Gear.P)
        rise(TIP_RAIN)
        rise(TIP_REST)
        rise(TIP_ENGINE_HOT)
        assertEquals(TIP_ENGINE_HOT, tips.next(true))
        assertNull(tips.next(true))
        tips.gear(Gear.D)
        rise(TIP_RAIN)
        assertEquals(TIP_RAIN, tips.next(true))
    }

    @Test
    fun stayingOnDoesNotRepeat() {
        rise(TIP_FUEL)
        tips.level(TIP_FUEL, true)
        tips.level(TIP_FUEL, true)
        assertEquals(TIP_FUEL, tips.next(true))
        assertNull(tips.next(true))
    }

    @Test
    fun waitingTipIsDroppedIfItClearedBeforeItsTurn() {
        rise(TIP_TPMS)
        rise(TIP_FUEL)
        tips.level(TIP_TPMS, false) // 기다리는 사이 경고등이 꺼졌다
        assertEquals(TIP_FUEL, tips.next(true))
        assertNull(tips.next(true))
        rise(TIP_TPMS) // 말한 적이 없으니 다시 켜지면 알린다
        assertEquals(TIP_TPMS, tips.next(true))
    }

    @Test
    fun headlightsTipOnlyInDriveWithLightsOffAndNotAuto() {
        assertEquals(true, lightsNeeded(cause = true, gear = Gear.D, lowBeamOn = false, autoSwitch = false))
        assertEquals(false, lightsNeeded(cause = true, gear = Gear.D, lowBeamOn = false, autoSwitch = true))
        assertEquals(false, lightsNeeded(cause = true, gear = Gear.D, lowBeamOn = true, autoSwitch = false))
        assertEquals(false, lightsNeeded(cause = true, gear = Gear.P, lowBeamOn = false, autoSwitch = false))
        assertEquals(false, lightsNeeded(cause = false, gear = Gear.D, lowBeamOn = false, autoSwitch = false))
        // 밤 신호가 켜지고 기어 D가 되면 한 번
        tips.level(TIP_NIGHT_LIGHTS, lightsNeeded(true, Gear.P, false, false))
        tips.level(TIP_NIGHT_LIGHTS, lightsNeeded(true, Gear.D, false, false))
        assertEquals(TIP_NIGHT_LIGHTS, tips.next(true))
    }

    @Test
    fun rainLightsComesBeforeHeavyRain() {
        tips.gear(Gear.D)
        tips.level(TIP_RAIN_LIGHTS, false)
        tips.level(TIP_RAIN, false)
        // 와이퍼를 바로 가장 빠른 단계로 올렸다: 서비스는 전조등 알림을 먼저 넣는다
        tips.level(TIP_RAIN_LIGHTS, lightsNeeded(true, Gear.D, false, false))
        tips.level(TIP_RAIN, true)
        assertEquals(TIP_RAIN_LIGHTS, tips.next(true))
        assertEquals(TIP_RAIN, tips.next(true))
    }

    @Test
    fun turnSignalAfterOneMinuteUnlessTurnIsNear() {
        assertEquals(false, turnSignalForgotten(0, TURN_SIGNAL_LONG_MS, Gear.D, null))
        assertEquals(true, turnSignalForgotten(0, TURN_SIGNAL_LONG_MS + 1, Gear.D, null))
        assertEquals(true, turnSignalForgotten(0, TURN_SIGNAL_LONG_MS + 1, Gear.D, TURN_NEAR_M + 1))
        assertEquals(false, turnSignalForgotten(0, TURN_SIGNAL_LONG_MS + 1, Gear.D, TURN_NEAR_M))
        assertEquals(false, turnSignalForgotten(0, TURN_SIGNAL_LONG_MS + 1, Gear.P, null))
        assertEquals(false, turnSignalForgotten(null, TURN_SIGNAL_LONG_MS + 1, Gear.D, null))
        tips.level(TIP_TURN_SIGNAL, turnSignalForgotten(0, 5_000, Gear.D, null))
        tips.level(TIP_TURN_SIGNAL, turnSignalForgotten(0, 65_000, Gear.D, null))
        assertEquals(TIP_TURN_SIGNAL, tips.next(true))
    }

    @Test
    fun openWindowAfterParkingOncePerDrive() {
        tips.gear(Gear.D)
        tips.level(TIP_WINDOW_PARKED, false) // 서비스는 D가 되면 꺼짐을 넣는다
        tips.gear(Gear.P)
        tips.level(TIP_WINDOW_PARKED, true) // P에서 읽은 창 하나가 열려 있다
        tips.level(TIP_WINDOW_PARKED, true) // 다른 창도
        assertEquals(TIP_WINDOW_PARKED, tips.next(true))
        assertNull(tips.next(true))
    }
}
