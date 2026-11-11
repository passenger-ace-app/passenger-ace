package app.passengerace

import org.junit.Assert.assertEquals
import org.junit.Test

/** 표본 주행 전체를 가짜 시계로 돌린다. 소리 길이는 줄 8초, 말 6초, 내비 4초로 둔 값이다. */
class ScenarioTest {
    private val route = SampleRoute(listOf(25.7, 55.7, 113.6, 158.6, 190.1), 203, 22, Turn.RIGHT)
    private var clock = 0L
    private val n = Narrator(listOf(Story("a", 1000), Story("b", 1000))) { clock }
    private val policy = AlertPolicy(route.branch)
    private var soundEnd: Long? = null
    private var navEnd: Long? = null
    private var inLine = false
    private var turn = Turn.NONE
    private val lines = mutableListOf<Int>()
    private val clips = mutableListOf<String>()
    private val popups = mutableListOf<Alert>()
    private val cutAt = mutableListOf<Int>()
    private var navs = 0
    private var wakeAt: Long? = null
    private val soundAt = mutableListOf<Long>()

    private fun exec(acts: List<Act>) {
        for (a in acts) when (a) {
            is Act.WakeIn -> wakeAt = clock + a.ms
            is Act.PlayLine -> {
                soundAt += clock
                lines += a.line
                inLine = true
                soundEnd = clock + 8_000
            }
            is Act.PlayClip -> {
                soundAt += clock
                clips += a.name
                inLine = false
                soundEnd = clock + 6_000
            }
            is Act.Popup -> popups += a.alert
            Act.StopSound -> {
                if (inLine) cutAt += lines.last()
                soundEnd = null
            }
            Act.RequestFocus -> exec(n.focusGained())
            Act.AlertCut -> policy.cut()
            else -> Unit
        }
    }

    private fun navStart() {
        navs++
        exec(n.focusLostTransient())
        navEnd = clock + 4_000
    }

    private fun drive(extra: Map<Long, () -> Unit> = emptyMap()) {
        n.talkLess = true
        exec(n.play())
        n.talkLess = false
        val navMs = route.navAt.map { (it * 1000).toLong() }
        for (t in 0L..((route.branchAt + 2) * 1000L) step 100) {
            clock = t
            if (t in navMs) navStart()
            extra[t]?.invoke()
            if (navEnd == t) {
                navEnd = null
                exec(n.focusGained())
            }
            if (wakeAt == t) {
                wakeAt = null
                exec(n.holdOver())
            }
            if (soundEnd == t) {
                soundEnd = null
                exec(if (inLine) n.lineFinished() else n.clipFinished())
            }
            if (t % 1000 == 0L && t <= route.branchAt * 1000L) {
                val distance = route.tick((t / 1000).toInt()).distanceM
                if (policy.check(distance, turn, n.canAlert)) exec(n.startAlert(Alert.PLAIN))
            }
        }
    }

    /** 끊긴 줄마다 다음에 나온 줄이 같은 줄인지 본다. */
    private fun assertEveryCutResumesSameLine() {
        var from = 0
        for (cut in cutAt) {
            val i = lines.indexOf(cut).let { first -> (first until lines.size).first { lines[it] == cut && it >= from } }
            assertEquals(cut, lines[i + 1])
            from = i + 1
        }
    }

    @Test
    fun wholeRouteWithSameSideSignal() {
        drive(mapOf(120_000L to { turn = Turn.RIGHT }))
        assertEquals(5, navs)
        assertEquals(listOf("alert_plain"), clips)
        assertEquals(listOf(Alert.PLAIN), popups)
        assertEquals(6, cutAt.size)
        assertEveryCutResumesSameLine()
    }

    @Test
    fun wholeRouteWithoutSignalRepeatsOnce() {
        drive()
        assertEquals(listOf("alert_plain", "alert_plain"), clips)
    }

    @Test
    fun navDuringAlertMeansAlertAgainAfterNav() {
        drive(mapOf(96_000L to { navStart() }))
        assertEquals(listOf("alert_plain", "alert_plain", "alert_plain"), clips) // 끊긴 것, 내비 뒤 다시, 1500m 반복
        assertEquals(6, navs)
    }

    // 안내 간격이 30초(25.7→55.7), 31.5초(158.6→190.1)인 두 곳에서는 그 사이에 잇지 않는다.
    @Test
    fun navSoonMeansWaitThroughTheGap() {
        n.nextNavIn = { route.navAt.map { (it * 1000).toLong() - clock }.filter { it > 0 }.minOrNull() }
        drive(mapOf(120_000L to { turn = Turn.RIGHT }))
        assertEquals(5, navs)
        assertEquals(emptyList<Long>(), soundAt.filter { it in 29_700L until 55_700L || it in 162_600L until 190_100L })
        assertEquals(4, cutAt.size) // 기다리지 않을 때는 6번
        assertEveryCutResumesSameLine()
    }

    @Test
    fun turnedOffStaysSilentForTheRestOfTheRoute() {
        drive(mapOf(20_000L to { exec(n.pause()) }))
        assertEquals(emptyList<String>(), clips)
        assertEquals(3, lines.size) // 0초, 8초, 16초에 시작한 줄뿐이다
    }
}
