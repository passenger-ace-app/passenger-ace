package app.passengerace

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/** 실제 StoryService를 띄운다. 소리는 시뮬레이터 대역이라 실제 소리가 아니고 navsim도 돌지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi", shadows = [RecordingSession::class])
class StoryServiceTest {
    private lateinit var rig: Rig
    private val callback get() = ReflectionHelpers.getField<MediaSession.Callback>(rig.svc, "sessionCallback")
    private val player get() = ReflectionHelpers.getField<MediaPlayer>(rig.svc, "player")
    private val shownStatus get() = RecordingSession.metadata!!.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    private val shownStory get() = RecordingSession.metadata!!.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    private val state get() = RecordingSession.state!!.state
    private val notes get() = rig.app.getSystemService(NotificationManager::class.java)
    private val log get() = rig.aceLog
    private val duck = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
    private val gain = AudioManager.AUDIOFOCUS_GAIN

    @Before
    fun setUp() {
        ShadowLog.clear()
        rig = Rig()
        rig.startService()
    }

    private fun key(code: Int) {
        val i = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        callback.onMediaButtonEvent(i)
        rig.idle()
    }

    private fun play() {
        rig.svc.act { play() }
        rig.idle()
        rig.service.startCommand(0, 0)
    }

    private fun playQuietly() {
        rig.svc.setTalkLess(true)
        play()
    }

    private fun playToFirstLine() {
        play()
        rig.idle(8_000)
    }

    private fun lastLine() = log.last { it.startsWith("LINE") }
    private fun lastClip() = log.last { it.startsWith("CLIP") }

    @Test
    fun playAsksFocusWithTheRightRequestThenLinesGoInOrder() {
        playToFirstLine()
        val request = rig.audio.lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertTrue(request.willPauseWhenDucked())
        assertTrue(request.acceptsDelayedFocusGain())
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertEquals("CLIP start", lastClip())
        assertEquals("LINE midas 0", lastLine())
        rig.idle(8_000)
        assertEquals("LINE midas 1", lastLine())
        assertEquals("재생 중", shownStatus)
        assertEquals("미다스 왕의 손", shownStory)
        assertEquals(PlaybackState.STATE_PLAYING, state)
    }

    @Test
    fun startsForegroundWithMediaNotification() {
        playToFirstLine()
        assertEquals(StoryService::class.java.name, shadowOf(rig.app).nextStartedService.component!!.className)
        assertNotNull(shadowOf(rig.svc).lastForegroundNotification)
        assertEquals(1, shadowOf(rig.svc).lastForegroundNotificationId)
    }

    @Test
    fun canDuckLossStopsAndGainRestartsSameLineFromItsStart() {
        playToFirstLine()
        rig.idle(11_000) // 1번 줄을 3초 들은 지점
        assertEquals("LINE midas 1", lastLine())
        assertEquals(3_000, player.currentPosition)
        rig.focus(duck)
        rig.idle()
        assertEquals("STOP", log.last { it == "STOP" || it.startsWith("LINE") })
        assertEquals("안내로 잠깐 멈춰도 위젯은 재생 중(⏸가 끄기)", "재생 중", shownStatus)
        assertEquals("안내 끝나면 이어 가요", shownStory)
        assertEquals("안내 끝나면 이어 가요", RecordingSession.metadata!!.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals(PlaybackState.STATE_PLAYING, state)
        assertFalse(player.isPlaying)
        rig.idle(4_000)
        rig.focus(gain)
        rig.idle()
        assertEquals("LINE midas 1", lastLine())
        assertEquals(0, player.currentPosition)
        assertTrue(player.isPlaying)
        assertEquals("재생 중", shownStatus)
        assertEquals("미다스 왕의 손", shownStory)
        assertEquals(0, log.count { it == "CLIP resume" })
    }

    @Test
    fun over30SecondsSaysResumeFirst() {
        playToFirstLine()
        rig.focus(duck)
        rig.idle(31_000)
        rig.focus(gain)
        rig.idle()
        assertEquals("CLIP resume", lastClip())
    }

    @Test
    fun afterTurnOffGainDoesNotStart() {
        playToFirstLine()
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val lines = log.count { it.startsWith("LINE") }
        assertEquals("꺼짐", shownStatus)
        assertEquals(PlaybackState.STATE_PAUSED, state)
        assertNotNull(rig.audio.lastAbandonedAudioFocusRequest)
        rig.focus(gain)
        rig.idle(20_000)
        assertEquals(lines, log.count { it.startsWith("LINE") })
        assertFalse(player.isPlaying)
    }

    @Test
    fun keyAgainResumesCutLine() {
        playToFirstLine()
        rig.idle(11_000)
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals("CLIP resume", lastClip())
        rig.idle(8_000)
        assertEquals("LINE midas 1", lastLine())
    }

    @Test
    fun playPauseKeyDuringNavTurnsOff() {
        playToFirstLine()
        rig.focus(duck)
        rig.idle()
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals("꺼짐", shownStatus)
    }

    @Test
    fun nextKeyGoesToOtherStories() {
        playToFirstLine()
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals("LINE three-questions 0", lastLine())
        assertEquals("세 가지 질문", shownStory)
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals("LINE hamlet 0", lastLine())
    }

    @Test
    fun storyEndSaysNextAndGoesOnAndRemembers() {
        playToFirstLine()
        rig.idle(8_000L * 26)
        assertEquals("LINE midas 25", lastLine())
        assertEquals("CLIP next", lastClip())
        assertEquals("재생 중", shownStatus)
        assertEquals("세 가지 질문", shownStory)
        assertEquals(setOf("midas"), rig.prefs.getStringSet("heard", null))
        assertEquals("three-questions", rig.prefs.getString("story", null))
        rig.idle(8_000)
        assertEquals("LINE three-questions 0", lastLine())
    }

    @Test
    fun offRemovesMediaNotification() {
        playToFirstLine()
        assertTrue(notes.activeNotifications.any { it.id == 1 })
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(notes.activeNotifications.none { it.id == 1 })
        assertTrue(shadowOf(rig.svc).isForegroundStopped)
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals("다시 켜면 포그라운드로", StoryService::class.java.name, shadowOf(rig.app).nextStartedService.component!!.className)
    }

    @Test
    fun arrivalOpensScreenOnceWhenParked() {
        playToFirstLine()
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        rig.svc.arrive()
        assertEquals("달리는 중에는 열지 않는다", null, shadowOf(rig.app).nextStartedActivity)
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "P") }
        assertEquals(MainActivity::class.java.name, shadowOf(rig.app).nextStartedActivity.component!!.className)
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "P") }
        assertEquals("한 번만", null, shadowOf(rig.app).nextStartedActivity)
    }

    /** 표본 주행을 실제 서비스로 돈다. navsim이 하는 소리 권한 요청과 반납은 시험이 대신한다. */
    private fun drive(turnRightAtSec: Int? = null, navDuringAlert: Boolean = false): List<Int> {
        fun sent() = shadowOf(rig.app).broadcastIntents.filter { it.component?.packageName == "app.passengerace.navsim" }
        var handled = 0
        var injected = false
        var back = -1L
        rig.signal("app.passengerace.ROUTE")
        rig.idle()
        for (t in 0L until 210_000L step 100) {
            val inject = navDuringAlert && !injected && log.any { it.startsWith("ALERT at") }
            if (sent().size > handled || inject) {
                if (inject) injected = true else handled = sent().size
                rig.focus(duck)
                back = t + 4_000
            }
            if (t == back) rig.focus(gain)
            if (t == turnRightAtSec?.times(1000L)) rig.signal("app.passengerace.TURN") { putExtra("turn", "RIGHT") }
            rig.idle(100)
        }
        return sent().map { it.getIntExtra("n", -1) }
    }

    @Test
    fun wholeRouteWithSameSideSignal() {
        playQuietly()
        assertEquals(listOf(0, 1, 2, 3, 4), drive(turnRightAtSec = 120))
        assertEquals(listOf("ALERT at 2398m"), log.filter { it.startsWith("ALERT at") })
        assertEquals(1, log.count { it.startsWith("POPUP") })
        assertEquals(5, log.count { it == "FOCUS -3" })
        val events = log.filter { it == "STOP" || it.startsWith("LINE") }
        val cuts = events.indices.filter { events[it] == "STOP" }
        assertEquals(4, cuts.size) // 안내 간격 30초, 31.5초인 두 곳은 기다렸다 이어서 끊기지 않는다(기다리지 않으면 6번)
        assertEquals(2, log.count { it.startsWith("HOLD") })
        for (i in cuts) assertEquals(events[i - 1], events[i + 1])
    }

    @Test
    fun popupIsPostedWithBriefWordsAndClosesItself() {
        play()
        rig.signal("app.passengerace.ROUTE")
        rig.idle(94_500)
        val popup = notes.activeNotifications.first { it.id == 2 }.notification
        assertEquals("미리 오른쪽으로", popup.extras.getCharSequence("android.title").toString())
        assertEquals("붙어 두세요", popup.extras.getCharSequence("android.text").toString())
        assertEquals("CLIP alert_plain", lastClip())
        assertEquals(6_000, popup.timeoutAfter)
        assertEquals("alert", popup.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, notes.getNotificationChannel("alert").importance)
        assertEquals("안내 끝나면 이어 가요", shownStory)
    }

    @Test
    fun wholeRouteWithoutSignalAlertsTwice() {
        playQuietly()
        drive()
        assertEquals(listOf("ALERT at 2398m", "ALERT at 1496m"), log.filter { it.startsWith("ALERT at") })
    }

    @Test
    fun navDuringAlertComesBackAfterNav() {
        playQuietly()
        drive(navDuringAlert = true)
        assertEquals(1, log.count { it == "ALERT cut" })
        assertEquals(3, log.count { it.startsWith("ALERT at") }) // 끊긴 것, 내비 뒤 다시, 1500m 반복
    }

    @Test
    fun offMeansNoAlertOnRoute() {
        playQuietly()
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        drive()
        assertEquals(0, log.count { it.startsWith("ALERT at") })
        assertTrue(notes.activeNotifications.none { it.id == 2 })
    }

    @Test
    fun placeSurvivesServiceRecreation() {
        playToFirstLine()
        rig.idle(19_000) // 2번 줄 듣는 중
        assertEquals("LINE midas 2", lastLine())
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        rig.service.destroy()
        ShadowLog.clear()
        rig.startService()
        assertEquals("SERVICE start story=midas line=2", log.first { it.startsWith("SERVICE") })
        rig.svc.act { play() }
        rig.idle()
        assertEquals("CLIP resume", lastClip())
        rig.idle(8_000)
        assertEquals("LINE midas 2", lastLine())
    }

    @Test
    fun talkLessSurvivesRecreation() {
        rig.svc.setTalkLess(true)
        rig.service.destroy()
        rig.startService()
        assertTrue(rig.svc.n.talkLess)
    }

    @Test
    fun turnSignalGearDoNotStopStory() {
        playToFirstLine()
        val stops = log.count { it == "STOP" }
        rig.signal("app.passengerace.TURN") { putExtra("turn", "LEFT") }
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        rig.idle(1_000)
        assertEquals(stops, log.count { it == "STOP" })
        assertTrue(player.isPlaying)
    }

    @Test
    fun catalogIsTwentyShortsThenSerialParts() {
        val ids = listOf(
            "midas", "three-questions", "hamlet", "christmas-gift", "last-leaf", "necklace", "orpheus", "how-much-land", "twenty-years", "bet",
            "icarus", "pandora", "sun-wind", "heungbu", "tortoise-hare", "emperor-clothes", "match-girl", "bremen", "sun-moon", "gyeonu",
            "monte-cristo-1", "monte-cristo-2", "monte-cristo-3", "monte-cristo-4", "monte-cristo-5", "monte-cristo-6",
        )
        assertEquals(ids, rig.svc.catalog.map { it.id })
        assertEquals((1..6).toList(), rig.svc.catalog.filter { it.part > 0 }.map { it.part })
        assertEquals("뒤마 · 연재 6회", rig.svc.catalog.last().label)
        assertEquals("오 헨리", rig.svc.catalog[3].source)
        assertEquals(listOf("그리스 신화", "그리스 신화", "이솝", "우리 옛이야기", "이솝", "안데르센", "안데르센", "그림 형제", "우리 옛이야기", "우리 옛이야기"),
            rig.svc.catalog.subList(10, 20).map { it.source })
    }

    @Test
    fun lengthIsMinutesAndPaddedSeconds() {
        assertEquals("2분 38초", length(158_999))
        assertEquals("3분 05초", length(185_000))
        assertEquals("40초", length(40_400))
    }

    @Test
    fun serialPlaceSurvivesShortsAndRecreation() {
        rig.svc.act { choose(20) }
        playToFirstLine()
        rig.idle(16_000)
        assertEquals("LINE monte-cristo-1 2", lastLine())
        rig.svc.act { choose(0) }
        assertEquals("monte-cristo-1" to 2, rig.svc.n.serialAt)
        rig.service.destroy()
        rig.startService()
        assertEquals("midas", rig.svc.catalog[rig.svc.n.index].id)
        assertEquals("monte-cristo-1" to 2, rig.svc.n.serialAt)
    }

    @Test
    fun alertsCountedPerRouteAndRerouteClearsOnNewRoute() {
        playQuietly()
        drive()
        assertEquals(2, rig.svc.alerts)
        rig.svc.arrive()
        assertTrue(rig.svc.arrived)
        rig.svc.routeStarted()
        assertEquals(0, rig.svc.alerts)
        assertFalse(rig.svc.arrived)
    }

    @Test
    fun naviRouteRerouteArrivalSignals() {
        val navi = ReflectionHelpers.getField<NaviHelperEventListener>(rig.svc, "naviListener")
        navi.onRouteStarted(RouteStartInfo(600, 3_000, 0, "판교", 0))
        navi.onReRouteEnded()
        navi.onDestinationArrived(DestinationArrivedInfo(0, 0, "판교", ""))
        rig.idle()
        assertTrue(rig.svc.missed)
        assertTrue(rig.svc.arrived)
        navi.onRouteStarted(RouteStartInfo(600, 3_000, 0, "판교", 0))
        rig.idle()
        assertFalse(rig.svc.missed)
    }

    @Test
    fun gearDHandsScreenToNaviOnlyWhileStoryOn() {
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        assertEquals(0, log.count { it == "NAVI foreground" })
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "P") }
        playToFirstLine()
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        assertEquals(1, log.count { it == "NAVI foreground" })
    }
}
