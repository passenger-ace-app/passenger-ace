package app.passengerace

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/** 옆자리 안내와 먼저 알림을 실제 StoryService로 돌린다. 인식과 차 신호는 시험용 입구로 넣는다. 소리 길이는 전부 8초다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi", shadows = [RecordingSession::class])
class HelpServiceTest {
    private lateinit var rig: Rig
    private val log get() = rig.aceLog
    private val svc get() = rig.svc
    private val notes get() = rig.app.getSystemService(NotificationManager::class.java)
    private val asides get() = log.filter { it.startsWith("ASIDE ") && !it.startsWith("ASIDE focus") }

    @Before
    fun setUp() {
        ShadowLog.clear()
        rig = Rig()
        rig.startService()
    }

    private fun hear(text: String?) {
        rig.signal("app.passengerace.HEAR") { putExtra("text", text) }
        rig.idle()
    }

    private fun tip(id: String) {
        rig.signal("app.passengerace.TIP") { putExtra("id", id).putExtra("on", false) }
        rig.signal("app.passengerace.TIP") { putExtra("id", id).putExtra("on", true) }
        rig.idle()
    }

    private fun clipEnds() = rig.idle(8_000)

    /** 이야기를 틀어 0번 줄까지 가고, 이야기의 소리 권한 처리기를 돌려준다. */
    private fun playStory(): (Int) -> Unit {
        rig.svc.setTalkLess(true)
        rig.svc.act { play() }
        rig.idle()
        val listener = rig.audio.lastAudioFocusRequest.listener
        return { listener.onAudioFocusChange(it); rig.idle() }
    }

    @Test
    fun askListenAnswerThenDefrostNoSaysOk() {
        svc.ask()
        rig.idle()
        assertEquals(HelpStep.LISTENING, svc.help.step)
        assertEquals(listOf("ASIDE listen"), asides)
        clipEnds()
        assertEquals("LISTEN", log.last())
        hear("앞유리에 김이 서려요")
        assertEquals(HelpStep.ANSWER, svc.help.step)
        assertEquals("help_01", svc.help.answer?.id)
        assertEquals("앞유리에 김이 서려요", svc.help.heard)
        assertEquals("ASIDE help_01", asides.last())
        clipEnds()
        assertEquals(HelpStep.ASK_DEFROST, svc.help.step)
        assertEquals("ASIDE ask_defrost", asides.last())
        clipEnds()
        hear("켜지 마")
        assertEquals("ASIDE ok", asides.last())
        clipEnds()
        assertEquals(HelpState(), svc.help) // 들은 말은 안내가 끝나면 지운다
        assertTrue(rig.app.getSharedPreferences("ace", 0).all.values.none { it.toString().contains("김") })
    }

    @Test
    fun defrostYesWithoutCarSaysItCouldNot() {
        svc.ask("김 서림")
        rig.idle()
        clipEnds()
        clipEnds()
        hear("네 켜 줘")
        assertEquals("ASIDE defrost_fail", asides.last()) // 시험 환경에는 차가 없다. 실제 켜기는 에뮬레이터에서 본다
    }

    @Test
    fun defrostButtonsWorkWhileAsking() {
        svc.ask("김이 서려")
        rig.idle()
        clipEnds()
        clipEnds()
        assertEquals(HelpStep.ASK_DEFROST, svc.help.step)
        svc.answerDefrost(false)
        rig.idle()
        assertEquals("ASIDE ok", asides.last())
    }

    @Test
    fun silenceSaysNotHeardAfterSixSeconds() {
        svc.ask()
        rig.idle()
        clipEnds()
        rig.idle(LISTEN_MS - 1)
        assertEquals("LISTEN", log.last())
        rig.idle(1)
        assertEquals("ASIDE not_heard", asides.last())
    }

    @Test
    fun unknownQuestionSaysNoAnswer() {
        svc.ask()
        rig.idle()
        clipEnds()
        hear("근처 맛집 알려 줘")
        assertEquals(HelpStep.ANSWER, svc.help.step)
        assertNull(svc.help.answer)
        assertEquals("ASIDE no_answer", asides.last())
    }

    @Test
    fun storyStopsForAnswerAndResumesSameLine() {
        val story = playStory()
        assertEquals("LINE midas 0", log.last { it.startsWith("LINE") })
        svc.ask("졸려")
        rig.idle()
        val aside = rig.audio.lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, aside.focusGain)
        story(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) // 우리 안내가 잠깐 쓰는 소리 권한을 받으면 이야기는 내비 때처럼 멈춘다
        assertEquals("STOP", log.last { it == "STOP" || it.startsWith("LINE") })
        assertEquals("ASIDE help_13", asides.last())
        clipEnds()
        story(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("LINE midas 0", log.last { it.startsWith("LINE") })
        assertEquals(Mode.PLAYING, svc.n.mode)
    }

    @Test
    fun navCutsAnswerThenAnswerStartsAgain() {
        svc.ask("졸려")
        rig.idle()
        val aside = rig.audio.lastAudioFocusRequest.listener
        aside.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        rig.idle(20_000)
        assertEquals(1, asides.count { it == "ASIDE help_13" }) // 내비가 말하는 동안 끊긴 답은 끝나지 않는다
        aside.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        rig.idle()
        assertEquals(2, asides.count { it == "ASIDE help_13" })
    }

    @Test
    fun tipSpeaksWithHeadsUpCardOfFirstSentence() {
        tip(TIP_TPMS)
        assertEquals("ASIDE tip_tpms", asides.last())
        val card = notes.activeNotifications.first { it.id == 2 }.notification
        assertEquals("타이어 공기압 경고등이 켜졌어요.", card.extras.getCharSequence("android.title").toString())
        assertEquals("공기압이 낮다는 신호예요. 가까운 곳에서 점검하세요.", card.extras.getCharSequence("android.text").toString())
        assertEquals("alert", card.channelId)
        assertEquals(0, svc.alerts) // 도착 화면의 "N번 미리 알렸고"에는 넣지 않는다
    }

    @Test
    fun tipWaitsForNavThenQueuedTipsGoOneByOne() {
        val story = playStory()
        story(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) // 내비 안내 중
        tip(TIP_TPMS)
        tip(TIP_BATTERY)
        assertTrue(asides.isEmpty())
        story(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("ASIDE tip_tpms"), asides)
        clipEnds()
        assertEquals(listOf("ASIDE tip_tpms", "ASIDE tip_battery"), asides)
        clipEnds()
        tip(TIP_TPMS)
        assertEquals("같은 주행에 한 번만", 2, asides.size)
    }

    @Test
    fun tipWaitsWhileAskingAndForBigBranchAlert() {
        svc.ask()
        rig.idle()
        tip(TIP_FUEL)
        assertEquals(listOf("ASIDE listen"), asides)
        svc.stopHelp()
        rig.idle()
        assertEquals("ASIDE tip_fuel", asides.last())

        clipEnds()
        playStory()
        rig.svc.act { startAlert(Alert.PLAIN) }
        rig.idle()
        tip(TIP_TPMS)
        assertEquals("ASIDE tip_fuel", asides.last())
        rig.idle(8_000) // 큰 갈림길 알림이 끝나면
        assertEquals("ASIDE tip_tpms", asides.last())
    }

    @Test
    fun mediaWidgetHasAskActionThatListens() {
        val actions = RecordingSession.state!!.customActions
        assertEquals(listOf("ask" to "물어보기"), actions.map { it.action to it.name.toString() })
        val callback = ReflectionHelpers.getField<MediaSession.Callback>(svc, "sessionCallback")
        callback.onCustomAction("ask", null)
        rig.idle()
        assertEquals(HelpStep.LISTENING, svc.help.step)
    }

    @Test
    fun voiceAssistantCommandAnswersAndRepliesSilent() {
        HelpReceiver().onReceive(rig.app, Intent(ASK_ROAD_HELP).putExtra("ACTION_PARAM", """{"topic":"타이어 공기압 경고등"}"""))
        rig.idle()
        assertEquals("ASIDE help_08", asides.last())
        val reply = shadowOf(rig.app).broadcastIntents.last { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
        val json = JSONObject(reply.getStringExtra("response")!!)
        assertEquals(ASK_ROAD_HELP, json.getString("functionName"))
        assertEquals("success", json.getString("status"))
        assertEquals("silent", json.getString("mode"))
        assertEquals("app.passengerace", reply.getStringExtra("packageName"))
    }

    @Test
    fun voiceAssistantCommandWithoutTopicNeverOpensMic() {
        HelpReceiver().onReceive(rig.app, Intent(ASK_ROAD_HELP))
        rig.idle()
        assertEquals(HelpStep.OFF, svc.help.step)
        val reply = shadowOf(rig.app).broadcastIntents.last { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
        assertEquals("fail", JSONObject(reply.getStringExtra("response")!!).getString("status"))
    }

    @Test
    fun voiceAssistantCommandFailsWhenServiceIsGone() {
        rig.service.destroy()
        HelpReceiver().onReceive(rig.app, Intent(ASK_ROAD_HELP))
        val reply = shadowOf(rig.app).broadcastIntents.last { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
        assertEquals("fail", JSONObject(reply.getStringExtra("response")!!).getString("status"))
    }
}
