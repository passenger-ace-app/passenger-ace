package app.passengerace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarratorTest {
    private var clock = 0L
    private val n = Narrator(listOf(Story("a", 3), Story("b", 2), Story("c", 2))) { clock }

    private fun List<Act>.sound() = filter { it is Act.PlayLine || it is Act.PlayClip || it == Act.StopSound }

    /** 이야기 a의 0번 줄을 듣는 상태까지 간다. */
    private fun listening() {
        n.talkLess = true
        n.play()
        n.focusGained()
        n.talkLess = false
    }

    @Test
    fun firstPlayAsksFocusThenSaysStartThenLine() {
        assertTrue(Act.RequestFocus in n.play())
        assertEquals(listOf(Act.PlayClip("start")), n.focusGained().sound())
        assertEquals(listOf(Act.PlayLine("a", 0)), n.clipFinished().sound())
    }

    @Test
    fun linesGoInOrder() {
        listening()
        assertEquals(listOf(Act.PlayLine("a", 1)), n.lineFinished().sound())
        assertEquals(listOf(Act.PlayLine("a", 2)), n.lineFinished().sound())
    }

    // 규칙 2: 끊긴 문장 처음부터, 30초 기준.
    @Test
    fun transientLossResumesSameLineSilently() {
        listening()
        n.lineFinished()
        assertEquals(listOf(Act.StopSound), n.focusLostTransient().sound())
        assertEquals(Mode.WAITING, n.mode)
        clock += RESUME_AFTER_MS
        assertEquals(listOf(Act.PlayLine("a", 1)), n.focusGained().sound())
    }

    @Test
    fun longStopSaysResumeThenSameLine() {
        listening()
        n.lineFinished()
        n.focusLostTransient()
        clock += RESUME_AFTER_MS + 1
        assertEquals(listOf(Act.PlayClip("resume")), n.focusGained().sound())
        assertEquals(listOf(Act.PlayLine("a", 1)), n.clipFinished().sound())
    }

    @Test
    fun turnOffThenOnSaysResume() {
        listening()
        n.lineFinished()
        n.pause()
        n.play()
        assertEquals(listOf(Act.PlayClip("resume")), n.focusGained().sound())
        assertEquals(listOf(Act.PlayLine("a", 1)), n.clipFinished().sound())
    }

    @Test
    fun talkLessNeverSpeaks() {
        n.talkLess = true
        n.play()
        assertEquals(listOf(Act.PlayLine("a", 0)), n.focusGained().sound())
        n.focusLostTransient()
        clock += RESUME_AFTER_MS * 2
        assertEquals(listOf(Act.PlayLine("a", 0)), n.focusGained().sound())
        n.pause()
        n.play()
        assertEquals(listOf(Act.PlayLine("a", 0)), n.focusGained().sound())
    }

    // 규칙 9: 말수 적게에도 미리 알림은 나온다.
    @Test
    fun talkLessStillAlerts() {
        n.talkLess = true
        n.play()
        n.focusGained()
        assertTrue(Act.PlayClip("alert_plain") in n.startAlert(Alert.PLAIN))
    }

    // 규칙 3: 끄면 자동으로 다시 말하지 않는다.
    @Test
    fun afterPauseFocusGainDoesNothing() {
        listening()
        val off = n.pause()
        assertTrue(Act.StopSound in off && Act.AbandonFocus in off)
        assertEquals(emptyList<Act>(), n.focusGained())
        assertEquals(Mode.OFF, n.mode)
    }

    @Test
    fun pauseDuringNavStaysOff() {
        listening()
        n.focusLostTransient()
        n.pause()
        assertEquals(emptyList<Act>(), n.focusGained().sound())
        assertFalse(n.on)
    }

    @Test
    fun playDuringNavWaits() {
        listening()
        n.focusLostTransient()
        assertEquals(emptyList<Act>(), n.play())
        assertEquals(listOf(Act.PlayLine("a", 0)), n.focusGained().sound())
    }

    @Test
    fun offMeansNoAlert() {
        listening()
        n.pause()
        assertFalse(n.canAlert)
        assertEquals(emptyList<Act>(), n.startAlert(Alert.PLAIN))
    }

    @Test
    fun permanentLossDoesNotResume() {
        listening()
        val lost = n.focusLost()
        assertTrue(Act.StopSound in lost)
        assertEquals(emptyList<Act>(), n.focusGained().sound())
        assertEquals(Mode.OFF, n.mode)
    }

    @Test
    fun pauseAfterPermanentLossThenPlayAsksFocusAgain() {
        listening()
        n.focusLost()
        assertTrue(Act.RequestFocus in n.play())
    }

    // 규칙 4: 알림은 이야기를 멈추고, 끝나면 말없이 잇는다.
    @Test
    fun alertInterruptsAndResumesSilently() {
        listening()
        n.lineFinished()
        val acts = n.startAlert(Alert.PLAIN)
        assertEquals(listOf(Act.StopSound, Act.PlayClip("alert_plain")), acts.sound())
        assertTrue(Act.Popup(Alert.PLAIN) in acts)
        assertEquals(Mode.WAITING, n.mode)
        clock += RESUME_AFTER_MS * 2
        assertEquals(listOf(Act.PlayLine("a", 1)), n.clipFinished().sound())
    }

    @Test
    fun navDuringAlertCutsIt() {
        listening()
        n.startAlert(Alert.PLAIN)
        val cut = n.focusLostTransient()
        assertTrue(Act.AlertCut in cut && Act.StopSound in cut)
        assertEquals(listOf(Act.PlayLine("a", 0)), n.focusGained().sound())
    }

    @Test
    fun noAlertWhileNavSpeaks() {
        listening()
        n.focusLostTransient()
        assertFalse(n.canAlert)
    }

    // 규칙 7, 8.
    @Test
    fun nextGoesToOtherUnheardStory() {
        listening()
        n.lineFinished()
        assertEquals(listOf(Act.StopSound, Act.PlayLine("b", 0)), n.next().sound())
        assertEquals(0, n.line)
    }

    @Test
    fun nextWhileNavSpeaksWaitsForFocus() {
        listening()
        n.focusLostTransient()
        assertEquals(emptyList<Act>(), n.next().sound())
        assertEquals(listOf(Act.PlayLine("b", 0)), n.focusGained().sound())
    }

    @Test
    fun nextWhileOffStartsStory() {
        listening()
        n.pause()
        assertTrue(Act.RequestFocus in n.next())
        assertEquals(listOf(Act.PlayLine("b", 0)), n.focusGained().sound())
    }

    // 언제 내릴지 모르니 끝나면 "next" 말 뒤에 안 들은 다음 이야기를 잇는다. 말수 적게여도 이 말은 한다.
    @Test
    fun storyEndSaysNextThenPlaysNextUnheard() {
        listening()
        n.talkLess = true
        repeat(2) { n.lineFinished() }
        assertEquals(listOf(Act.PlayClip("next")), n.lineFinished().sound())
        assertEquals(Mode.PLAYING, n.mode)
        assertEquals(setOf("a"), n.heard)
        assertEquals(1, n.index)
        assertEquals(listOf(Act.PlayLine("b", 0)), n.clipFinished().sound())
    }

    @Test
    fun allHeardSaysEndThenStopsAndStartsFromFirstNextTime() {
        n.restore("c", 1, setOf("a", "b"), true)
        n.play()
        n.focusGained()
        assertEquals(listOf(Act.PlayClip("end")), n.lineFinished().sound())
        assertTrue(n.on)
        val stop = n.clipFinished()
        assertEquals(emptyList<Act>(), stop.sound())
        assertTrue(Act.AbandonFocus in stop)
        assertEquals(Mode.OFF, n.mode)
        assertEquals(0, n.index)
        assertEquals(emptySet<String>(), n.heard)
    }

    @Test
    fun navDuringEndWordStillStops() {
        n.restore("c", 1, setOf("a", "b"), true)
        n.play()
        n.focusGained()
        n.lineFinished()
        n.focusLostTransient()
        assertEquals(emptyList<Act>(), n.focusGained().sound())
        assertFalse(n.on)
    }

    @Test
    fun restoreKeepsPlaceAndSaysResume() {
        n.restore("b", 1, setOf("a"), false)
        n.play()
        assertEquals(listOf(Act.PlayClip("resume")), n.focusGained().sound())
        assertEquals(listOf(Act.PlayLine("b", 1)), n.clipFinished().sound())
    }

    @Test
    fun restoreIgnoresLineThatNoLongerExists() {
        n.restore("b", 9, emptySet(), true)
        assertEquals(0, n.line)
    }

    @Test
    fun statusOnlyWhenItChanges() {
        listening()
        assertEquals(emptyList<Act>(), n.lineFinished().filterIsInstance<Act.Status>())
        assertEquals(listOf(Act.Status("a", Mode.WAITING)), n.focusLostTransient().filterIsInstance<Act.Status>())
    }

    @Test
    fun requestFocusIsLastSoStatusIsNotOverwritten() {
        assertEquals(Act.RequestFocus, n.play().last())
    }

    // 규칙: 다음 안내가 40초 안이면 기다렸다가 한 번에 잇는다.
    @Test
    fun navSoonHoldsThenResumes() {
        listening()
        n.lineFinished()
        n.nextNavIn = { 30_000 }
        n.focusLostTransient()
        val back = n.focusGained()
        assertEquals(emptyList<Act>(), back.sound())
        assertTrue(Act.WakeIn(30_000 + HOLD_GRACE_MS) in back)
        assertEquals(Mode.WAITING, n.mode)
        assertEquals(emptyList<Act>(), n.holdOver().sound()) // 일찍 온 깨우기는 무시한다
        clock += 30_000 + HOLD_GRACE_MS
        assertEquals(listOf(Act.PlayClip("resume")), n.holdOver().sound())
    }

    @Test
    fun navFarResumesAtOnce() {
        listening()
        n.lineFinished()
        n.nextNavIn = { HOLD_IF_NAV_WITHIN_MS }
        n.focusLostTransient()
        assertEquals(listOf(Act.PlayLine("a", 1)), n.focusGained().sound())
    }

    @Test
    fun firstPlayNeverHolds() {
        n.nextNavIn = { 1_000 }
        n.play()
        assertEquals(listOf(Act.PlayClip("start")), n.focusGained().sound())
    }

    @Test
    fun nextDuringHoldPlaysAtOnce() {
        listening()
        n.nextNavIn = { 30_000 }
        n.focusLostTransient()
        n.focusGained()
        assertEquals(listOf(Act.PlayLine("b", 0)), n.next().sound())
    }

    private val serial = Narrator(listOf(Story("a", 2), Story("b", 2), Story("s1", 2, true), Story("s2", 2, true))) { clock }

    private fun Narrator.finish() {
        talkLess = true
        play()
        focusGained()
        clipFinished() // 앞 이야기 끝의 "next" 말
        repeat(stories[index].lines) { lineFinished() }
    }

    @Test
    fun shortEndGoesToNextShortNotSerial() {
        serial.choose(1)
        serial.finish()
        assertEquals(0, serial.index)
    }

    @Test
    fun serialPartEndGoesToNextPartThenShorts() {
        serial.choose(2)
        serial.finish()
        assertEquals(3, serial.index)
        assertEquals("s2" to 0, serial.serialAt)
        serial.finish()
        assertEquals(0, serial.index)
        assertEquals("다 들은 연재는 자리가 없다", null, serial.serialAt)
    }

    @Test
    fun leavingSerialKeepsItsPlaceAndChooseResumes() {
        serial.choose(2)
        serial.talkLess = true
        serial.play()
        serial.focusGained()
        serial.lineFinished()
        serial.choose(0)
        assertEquals("s1" to 1, serial.serialAt)
        serial.choose(2, 1)
        assertEquals(2 to 1, serial.index to serial.line)
    }

    @Test
    fun nextFromShortSkipsSerial() {
        serial.choose(1)
        serial.next()
        assertEquals(0, serial.index)
    }

    @Test
    fun pauseDuringHoldThenPlayDoesNotHold() {
        listening()
        n.nextNavIn = { 30_000 }
        n.focusLostTransient()
        n.focusGained()
        n.pause()
        n.play()
        assertEquals(listOf(Act.PlayClip("resume")), n.focusGained().sound())
    }
}
