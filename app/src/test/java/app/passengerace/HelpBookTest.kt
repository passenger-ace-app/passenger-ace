package app.passengerace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 앱에 들어가는 help.json 그대로 맞춘다. org.json 때문에 Robolectric에서 돈다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HelpBookTest {
    private val book = HelpBook.parse(RuntimeEnvironment.getApplication().assets.open("help.json").bufferedReader().readText())

    // 답마다 서로 다른 말투 둘 이상. 인식기가 띄어쓰기를 다르게 줘도 맞아야 해서 일부는 붙여 쓴다.
    private val said = mapOf(
        "help_01" to listOf("앞유리에 김이 서려요", "유리창이 뿌옇게 됐어", "앞이 흐릿해 습기 때문인가"),
        "help_03" to listOf("앞유리에 성에가 꼈어요", "유리가 얼어서 안 보여"),
        "help_05" to listOf("폭우라 앞이 안 보여요", "비가 너무 많이 와"),
        "help_06" to listOf("안개가 너무 짙어요", "안개 때문에 앞이 안보여"),
        "help_07" to listOf("터널에 들어가요", "곧 터널이야"),
        "help_08" to listOf("타이어 공기압 경고등이 떴어요", "타이어 경고등이 들어왔어", "공기압이 낮대"),
        "help_09" to listOf("엔진 경고등이 켜졌어요", "엔진등에 불 들어왔어"),
        "help_10" to listOf("엔진 온도가 올라갔어요", "엔진 과열이래"),
        "help_11" to listOf("브레이크 경고등이 켜졌어요", "브레이크등에 불이 들어왔어"),
        "help_12" to listOf("달리다가 펑크 난 것 같아요", "타이어가 터진 것 같아"),
        "help_13" to listOf("졸려요", "너무 피곤해 잠이 와"),
        "help_14" to listOf("고속도로에서 차가 멈췄어요", "차가 고장 났어"),
        "help_15" to listOf("사고가 났어요", "앞차를 박았어"),
        "help_16" to listOf("2차 사고가 걱정돼요", "뒤에서 박을까 봐 무서워"),
        "help_17" to listOf("견인차를 부르고 싶어요", "렉카 불러야 해"),
        "help_18" to listOf("빙판길 같아요", "블랙아이스 조심해야 하나"),
        "help_19" to listOf("눈길에서 미끄러워요", "눈이 많이 와"),
        "help_21" to listOf("물이 고인 길이에요", "길에 물웅덩이가 있어"),
        "help_22" to listOf("나갈 길을 놓쳤어요", "출구를 지나쳤어"),
        "help_23" to listOf("사이렌이 가까워져요", "구급차가 뒤에 와"),
        "help_24" to listOf("갓길로 가도 되나요?", "갓길 달려도 돼?"),
        "help_25" to listOf("하이패스 요금이 안 나갔어요", "통행료 미납이래"),
        "help_26" to listOf("어린이보호구역이에요", "스쿨존이야"),
        "help_27" to listOf("밤에 상향등 써도 되나요?", "하이빔 켜도 돼?"),
        "help_28" to listOf("기름이 얼마 안 남았어요", "연료가 없어"),
        "help_30" to listOf("갑자기 몸이 안 좋아요", "어지러워"),
    )

    @Test
    fun everyAnswerHasTwoPhrasingsThatPickIt() {
        assertEquals(book.answers.map { it.id }.toSet(), said.keys)
        for ((id, lines) in said) {
            assertTrue(id, lines.size >= 2)
            for (line in lines) assertEquals(line, id, book.match(line)?.id)
        }
    }

    @Test
    fun theQuestionTextItselfPicksItsAnswer() {
        for (a in book.answers) assertEquals(a.q, a.id, book.match(a.q)?.id)
    }

    @Test
    fun similarAnswersStayApart() {
        assertEquals("help_08", book.match("타이어 공기압이 낮아요")?.id)
        assertEquals("help_12", book.match("타이어 바람이 빠졌어")?.id)
        assertEquals("help_15", book.match("사고 났어")?.id)
        assertEquals("help_16", book.match("이차 사고 날까 봐 걱정이야")?.id)
        assertEquals("help_01", book.match("김 서림이 심해")?.id)
        assertEquals("help_03", book.match("성에 때문에 안 보여")?.id)
    }

    @Test
    fun spacingDoesNotMatter() {
        assertEquals("help_08", book.match("타이어경고등")?.id)
        assertEquals("help_16", book.match("2 차 사고")?.id)
    }

    @Test
    fun unknownQuestionHasNoAnswer() {
        assertNull(book.match("근처 맛집 알려 줘"))
        assertNull(book.match(""))
    }

    @Test
    fun onlyFogAnswerOffersDefrost() {
        assertEquals(listOf("help_01"), book.answers.filter { it.action == "defrost" }.map { it.id })
    }

    @Test
    fun noIsCheckedBeforeYes() {
        for (s in listOf("네", "응 켜 줘", "그래", "좋아요", "부탁해")) assertTrue(s, book.yes(s))
        for (s in listOf("켜지 마", "켜지마", "아니요", "괜찮아요", "됐어", "안 켜도 돼", "싫어", "음")) assertFalse(s, book.yes(s))
        assertFalse(book.yes(null))
    }

    @Test
    fun tipsHaveText() {
        for (id in listOf(TIP_TPMS, TIP_ENGINE_HOT, TIP_FUEL, TIP_BATTERY, TIP_RAIN, TIP_REST, TIP_NIGHT_LIGHTS, TIP_RAIN_LIGHTS, TIP_TURN_SIGNAL, TIP_WINDOW_PARKED)) assertTrue(id, book.tips[id].orEmpty().isNotBlank())
    }

    @Test
    fun voiceAssistantTopicComesPlainOrQuoted() {
        assertEquals("김 서림", HelpReceiver.topicOf("""{"topic":"김 서림"}"""))
        assertEquals("김 서림", HelpReceiver.topicOf("\"{\"topic\":\"김 서림\"}\""))
        assertNull(HelpReceiver.topicOf(null))
        assertNull(HelpReceiver.topicOf("{}"))
    }
}
