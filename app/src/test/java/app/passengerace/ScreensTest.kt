package app.passengerace

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Pleos 홈의 앱 칸(에뮬레이터 실측). 차가 상태 줄을 따로 그려서 이 칸이 앱 전체다.
private const val WIDTH = 1682
private const val FULL = 1210
private const val MIN_TOUCH = 112
private const val MIN_MAIN_BUTTON = 120
private const val MIN_GAP = 24
private const val MIN_TEXT = 36f // 보조 글자 36 (외부 점검 두 곳 공통)

/** 화면을 실제 측정으로 잰다. 글꼴은 Robolectric 묶음이라 Pleos 서체와 폭이 다를 수 있다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1682dp-h1210dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    private lateinit var rig: Rig
    private val report = StringBuilder()

    @Before
    fun setUp() {
        rig = Rig()
    }

    private fun saved(story: String, line: Int, heard: Set<String> = emptySet(), less: Boolean = false) {
        rig.prefs.edit().putBoolean("started", true).putString("story", story).putInt("line", line)
            .putStringSet("heard", heard).putBoolean("talkLess", less).commit()
    }

    private fun open(): Activity {
        rig.startService()
        return rig.openActivity()
    }

    private fun Activity.root() = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun View.all(): List<View> =
        listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())

    private fun Activity.text(label: String) = root().all().filterIsInstance<TextView>().first { it.text == label }

    private fun Activity.tapDesc(desc: String) {
        root().all().first { it.contentDescription == desc }.performClick()
        rig.idle()
    }

    private fun Activity.tap(label: String) {
        var v: View = root().all().filterIsInstance<TextView>().first { it.text.contains(label) }
        while (!v.isClickable) v = v.parent as View
        v.performClick()
        rig.idle()
    }

    private fun oneLine(s: CharSequence) = s.toString().replace('\n', ' ')

    private fun Activity.says(label: String) = root().all().filterIsInstance<TextView>().any { oneLine(it.text).contains(oneLine(label)) }

    private fun lay(root: View, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, WIDTH, height)
    }

    private fun View.inButton(): Boolean = isClickable || (parent as? View)?.inButton() == true

    private fun View.box(root: View): Rect {
        val r = Rect(scrollX, scrollY, scrollX + width, scrollY + height) // 바꿔 주는 함수가 자기 넘김 값까지 빼므로 되돌린다
        (root as ViewGroup).offsetDescendantRectToMyCoords(this, r)
        return r
    }

    /** 넘기는 칸 밖으로 넘어가 보이지 않는 것. */
    private fun View.hidden(root: View): Boolean {
        val scroll = generateSequence(parent as? View) { it.parent as? View }.firstOrNull { it is ScrollView } ?: return false
        return !scroll.box(root).contains(box(root))
    }

    private fun audit(name: String, root: View, height: Int, lines: Int = 3) {
        lay(root, height)
        val views = root.all().filter { !it.hidden(root) }
        val screen = Rect(0, 0, WIDTH, height)
        for (v in views) {
            if (v === root) continue
            val who = "$name@$height ${(v as? TextView)?.text ?: v.javaClass.simpleName}"
            assertTrue("$who 화면 밖 ${v.box(root)}", screen.contains(v.box(root)))
            assertTrue("$who 부모 밖 ${v.box(root)}", (v.parent as View).box(root).contains(v.box(root)))
            if (v !is TextView || v.text.isEmpty()) continue
            val text = v.layout
            val room = v.width - v.totalPaddingLeft - v.totalPaddingRight
            val widest = (0 until v.lineCount).maxOf { text.getLineMax(it) }
            assertEquals("$who 말줄임", 0, (0 until v.lineCount).sumOf { text.getEllipsisCount(it) })
            assertTrue("$who 폭 넘침 $widest > $room", widest <= room)
            assertTrue("$who 높이 넘침 ${text.height} > ${v.height}", text.height <= v.height)
            if (v.inButton()) assertEquals("$who 버튼 글자가 저절로 줄바꿈", v.text.lines().size, v.lineCount) // 긴 작품명은 띄어쓰기에서 직접 끊는다
            assertTrue("$who 문단 ${lines}줄 넘음", v.lineCount <= lines)
            assertTrue("$who 글자 크기 ${v.textSize}", v.textSize >= MIN_TEXT)
            report.appendLine("$name\t$height\t${v.text.toString().replace('\n', '/')}\t${v.textSize.toInt()}px\t${v.lineCount}줄\t폭 ${widest.toInt()}/$room\t높이 ${text.height}/${v.height}")
        }
        val touch = views.filter { it.isClickable && it !== root }
        for (b in touch) {
            val who = "$name@$height ${b.all().filterIsInstance<TextView>().firstOrNull()?.text}"
            assertTrue("$who 터치 ${b.width}x${b.height}", b.width >= MIN_TOUCH && b.height >= MIN_TOUCH)
            if (b.all().any { it is TextView && it.currentTextColor == BG }) assertTrue("$who 주 버튼 높이 ${b.height}", b.height >= MIN_MAIN_BUTTON)
            report.appendLine("$name\t$height\t터치\t${b.all().filterIsInstance<TextView>().firstOrNull()?.text}\t${b.width}x${b.height}")
        }
        for (a in touch) for (b in touch) {
            if (a === b) continue
            val x = maxOf(a.box(root).left - b.box(root).right, b.box(root).left - a.box(root).right)
            val y = maxOf(a.box(root).top - b.box(root).bottom, b.box(root).top - a.box(root).bottom)
            assertTrue("$name@$height 터치 간격 ${maxOf(x, y)}", maxOf(x, y) >= MIN_GAP)
        }
    }

    private fun shoot(name: String, root: View): Bitmap {
        lay(root, FULL)
        val bitmap = Bitmap.createBitmap(WIDTH, FULL, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "화면-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "측정-$name.txt").writeText(report.toString())
        return bitmap
    }

    private fun check(name: String, activity: Activity): Bitmap {
        val root = activity.root()
        audit(name, root, FULL)
        return shoot(name, root)
    }

    @Test
    fun screen1First() {
        val a = open()
        assertTrue(a.says("옛날이야기 들어 보기"))
        val shot = check("1", a)
        assertEquals("상태 줄 없이 바탕부터", BG, shot.getPixel(5, 5))
        val root = a.root()
        assertEquals("표지 640, x64, 세로 가운데", Rect(64, 285, 704, 925), root.all().first { it is ImageView }.box(root))
        val title = a.text("조수석 에이스")
        assertEquals("제목 줄 x784·y392", Rect(784, 392, 784, 392), title.box(root).run { Rect(left, top, left, top) })
        assertEquals(1, title.lineCount)
        val sub = root.all().filterIsInstance<TextView>().first { it.text.startsWith("길을 놓치기") }
        assertEquals(54, sub.layout.getLineBaseline(1) - sub.layout.getLineBaseline(0)) // 보조 36px의 1.5배
    }

    @Test
    fun screen2Again() {
        saved("midas", 3)
        val a = open()
        assertTrue(a.says("지난번에 듣던 이야기"))
        assertTrue(a.says("말수 줄이기"))
        check("2", a)
        val root = a.root()
        assertEquals("제목은 한 줄", 1, a.text("미다스 왕의 손").lineCount)
        val list = a.text("이야기 목록")
        assertEquals("글자 버튼의 글자가 묶음 x784에서 시작", 784, list.box(root).left + list.totalPaddingLeft)
    }

    @Test
    fun storyEndGoesOnToNextStoryOnListeningScreen() {
        saved("hamlet", 20)
        val a = open()
        a.tap("이야기 듣기")
        rig.idle(8_000) // "아까 그 이야기" 말
        assertTrue(a.says("재생 중"))
        rig.idle(8_000) // 마지막 줄 → "next" 말
        assertTrue("화면 2로 튕기지 않는다", a.says("재생 중") && !a.says("이야기 목록"))
        assertTrue(a.says("크리스마스 선물"))
        assertTrue(a.says("오 헨리 · 3분 01초 · 말수 보통"))
        rig.idle(8_000)
        assertEquals("LINE christmas-gift 0", rig.aceLog.last { it.startsWith("LINE") })
    }

    @Test
    fun firstButtonAsksNotificationThenPlays() {
        org.robolectric.Shadows.shadowOf(rig.app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val a = open()
        a.tap("옛날이야기 들어 보기")
        assertEquals(android.Manifest.permission.POST_NOTIFICATIONS, org.robolectric.Shadows.shadowOf(a).lastRequestedPermission.requestedPermissions.single())
        assertTrue("답하기 전에는 틀지 않는다", !rig.svc.n.on)
        a.onRequestPermissionsResult(1, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), intArrayOf(-1))
        rig.idle()
        assertTrue(rig.svc.n.on)
    }

    @Test
    fun stopAndStartAgainRebindsAndShowsCurrentState() {
        val a = open()
        a.tap("옛날이야기 들어 보기")
        rig.activity.pause().stop()
        rig.idle(8_000)
        rig.activity.start().resume()
        rig.idle()
        assertTrue(a.says("재생 중"))
        a.tapDesc("멈춤")
        assertTrue(a.says("이야기 듣기"))
    }

    @Test
    fun screen2TalkLessLabelFits() {
        saved("midas", 3, less = true)
        val a = open()
        check("2-적게", a)
        assertEquals(1, a.text("말수 되돌리기").lineCount)
    }

    @Test
    fun screen2Listening() {
        val a = open()
        a.tap("옛날이야기 들어 보기")
        assertTrue(a.says("재생 중"))
        assertTrue("처음 줄이라 0:00", a.says("0:00"))
        val total = rig.svc.catalog[0].ms.sum()
        assertTrue("전체 길이", a.says("%d:%02d".format(total / 60_000, total / 1000 % 60)))
        check("3", a)
        val root = a.root()
        val pause = root.all().first { it.contentDescription == "멈춤" }.box(root)
        assertEquals("멈춤 원 128이 묶음 가운데", 1137 to 1265, pause.left to pause.right)
        assertEquals("멈춤 원 y723(글자 줄 높이 반올림 1 허용)", 723f, pause.top.toFloat(), 1f)
        a.tapDesc("다음 이야기")
        assertEquals("three-questions", rig.svc.catalog[rig.svc.n.index].id)
    }

    @Test
    fun screen2ListeningWhenTalkLess() {
        saved("three-questions", 0, less = true)
        val a = open()
        a.tap("이야기 듣기")
        assertTrue(a.says("톨스토이 · 3분 51초 · 말수 적게"))
        check("3-적게", a)
    }

    private fun Activity.rowOf(label: String): View {
        var v: View = text(label)
        while (!v.isClickable) v = v.parent as View
        return v
    }

    @Test
    fun screen5ListSerialOnTop() {
        saved("monte-cristo-1", 3)
        val a = open()
        a.tap("이야기 목록")
        assertTrue(a.says("이어 듣기"))
        assertTrue(a.root().all().filterIsInstance<TextView>().none { it.text == "연재" })
        assertTrue(a.says("단편"))
        assertTrue(a.says("뒤마 · 연재 1회"))
        val heard = rig.svc.catalog[20].ms.take(3).sum()
        assertTrue(a.says("지난번에 1회 ${length(heard)}까지 들었어요"))
        assertTrue(a.says("2분 38초"))
        assertTrue("길이·말수 줄은 목록에서 뺐다", !a.says("이야기 길이") && !a.says("지금 말수") && !a.says("다음 이야기 듣기"))
        assertTrue(a.says("이야기 듣기"))
        check("5", a)
        val root = a.root()
        assertEquals("고른 연재 줄은 x752부터 1618까지, 높이 152", Triple(752, 1618, 152), a.rowOf("몬테크리스토 백작").box(root).run { Triple(left, right, height()) })
        assertTrue("연재도 그림 표지", root.all().any { it.contentDescription == "몬테크리스토 백작 표지" })
        assertEquals("단편 줄 112", 112, a.rowOf("미다스 왕의 손").height)
        val play = a.text("이야기 듣기")
        assertTrue("주 버튼은 아래에 고정(y1048~1168)", (play.parent as View).box(root).run { top == 1048 && bottom == 1168 })
    }

    @Test
    fun screen5ListFreshSaysSerialAndShowsNextRowPeek() {
        saved("midas", 3)
        val a = open()
        a.tap("이야기 목록")
        assertTrue("한 번도 안 들은 연재는 머리글이 '연재'", a.text("연재").isShown && !a.says("이어 듣기"))
        check("5-처음", a)
        val root = a.root()
        val scroll = root.all().first { it is ScrollView } as ScrollView
        assertEquals(0, scroll.scrollY)
        assertTrue("스크롤 표시가 늘 보인다", scroll.isVerticalScrollBarEnabled && !scroll.isScrollbarFadingEnabled)
        val view = scroll.box(root)
        val rows = (scroll.getChildAt(0) as ViewGroup).let { g -> (0 until g.childCount).map(g::getChildAt) }.filter { it.isClickable }
        val peek = rows.map { it.box(root) }.first { it.bottom > view.bottom }
        assertTrue("맨 아래 줄이 반쯤 보인다 $peek / $view", peek.top < view.bottom - 32)
        assertEquals("줄은 x752~1618 그대로", 752 to 1618, peek.left to peek.right)
    }

    @Test
    fun screen5ListScrollsToLongTitles() {
        saved("heungbu", 0)
        val a = open()
        a.tap("이야기 목록")
        val scroll = a.root().all().first { it is ScrollView } as ScrollView
        assertTrue("고른 줄(흥부와 놀부)이 보이게 내려가 있다", scroll.scrollY > 0)
        val long = a.root().all().filterIsInstance<TextView>().first { it.text.startsWith("사람에게는") }
        assertEquals("긴 작품명은 띄어쓰기에서 두 줄로 끊는다", 2, long.text.lines().size)
        check("5-끝", a)
    }

    @Test
    fun screen5SerialRowResumesWhereCut() {
        saved("monte-cristo-2", 5)
        val a = open()
        a.tap("이야기 목록")
        a.tap("미다스 왕의 손")
        assertTrue(a.says("지난번에 2회"))
        a.tap("몬테크리스토 백작")
        assertEquals("monte-cristo-2", rig.svc.catalog[rig.svc.n.index].id)
        assertEquals(5, rig.svc.n.line)
    }

    @Test
    fun screen9RowPicksStoryAndCloseReturns() {
        saved("midas", 3)
        val a = open()
        a.tap("이야기 목록")
        a.tap("햄릿")
        assertEquals("hamlet", rig.svc.catalog[rig.svc.n.index].id)
        assertEquals(0, rig.svc.n.line)
        a.tap("닫기")
        assertTrue(a.says("이야기 목록"))
    }

    @Test
    fun screen4ArrivalCountsAlertsAndCloses() {
        saved("three-questions", 0, heard = setOf("midas"))
        val a = open()
        rig.svc.routeStarted()
        a.tap("이야기 듣기")
        rig.svc.act { startAlert(Alert.PLAIN) }
        rig.idle()
        rig.svc.arrive()
        rig.idle()
        assertTrue(a.says("도착했어요"))
        assertTrue(a.says("오늘 길을 1번 미리 알렸고, 놓치지 않았어요."))
        assertTrue("듣던 이야기가 남아 있으면 이어서", a.says("다음에 탈 때 세 가지 질문을 이어서 들려 드려요."))
        check("4", a)
        val root = a.root()
        assertEquals("제목 줄 x784·y392", Rect(784, 392, 784, 392), a.text("도착했어요").box(root).run { Rect(left, top, left, top) })
        a.tap("닫기")
        assertTrue("듣는 중 화면으로 돌아감(알림 중이라 '안내 뒤 이어 가요')", a.root().all().any { it.contentDescription == "멈춤" })
    }

    @Test
    fun screen4NoKeptLineAfterRerouteOrNoAlert() {
        val a = open()
        rig.signal("app.passengerace.ARRIVE")
        rig.idle()
        assertTrue(a.says("다음에 탈 때는 미다스 왕의 손을 들려 드려요."))
        assertTrue("미리 알린 적이 없으면 숫자 줄 없음", !a.says("놓치지 않았어요"))
        check("4-알림없음", a)
        a.tap("닫기")
        assertTrue(a.says("옛날이야기 들어 보기"))
    }

    @Test
    fun screen2SerialShowsLetterCoverAndPart() {
        saved("monte-cristo-1", 3)
        val a = open()
        assertTrue(a.says("뒤마 · 연재 1회 · ${length(rig.svc.catalog[20].ms.sum())} · 말수 보통"))
        check("2-연재", a)
    }

    @Test
    fun everyStoryHasPictureCover() {
        open()
        for (m in rig.svc.catalog) assertTrue(m.id, rig.app.cover(m.id) != null)
    }

    @Test
    fun screen3AskButtonOpens8() {
        saved("midas", 3)
        val a = open()
        a.tap("이야기 듣기")
        rig.idle(8_000)
        assertTrue(a.says("물어보기"))
        val ask = a.text("물어보기").parent as View
        assertEquals("물어보기 높이 112", 112, ask.height)
        check("3", a)
        a.tap("물어보기")
        assertEquals(HelpStep.LISTENING, rig.svc.help.step)
        assertTrue(a.says("말씀하세요") && a.says("듣고 있어요") && a.says("“앞유리에 김이 서려요”"))
        check("8", a)
        a.tap("질문 취소")
        assertEquals(HelpStep.OFF, rig.svc.help.step)
        assertTrue(a.says("재생 중"))
    }

    @Test
    fun screen9AnswerAndBackTo3() {
        saved("midas", 3)
        val a = open()
        rig.svc.ask("나갈 길을 놓쳤어요")
        rig.idle()
        assertTrue(a.says("“나갈 길을 놓쳤어요”") && a.says("고속도로에서는 후진과 유턴이 금지예요.") && a.says("출처 · 도로교통법 제62조"))
        check("9", a)
        a.tap("이야기 이어 듣기")
        assertEquals(HelpStep.OFF, rig.svc.help.step)
        assertTrue("들은 말은 화면에서도 사라진다", !a.says("나갈 길을 놓쳤어요"))
    }

    @Test
    fun screen9EveryAnswerFits() {
        saved("midas", 3)
        val a = open()
        for (ans in rig.svc.book.answers) {
            rig.svc.ask(ans.q)
            rig.idle()
            assertEquals(ans.id, rig.svc.help.answer?.id)
            audit("9-${ans.id}", a.root(), FULL, lines = 4) // 긴 답(견인 안내 등)은 원문을 줄이지 않아서 4줄까지 둔다
            val shown = a.root().all().filterIsInstance<TextView>().first { it.text.toString().replace('\n', ' ') == ans.a }
            assertTrue("${ans.id} 끝줄에 한 낱말만 남지 않게: ${shown.text}", shown.text.split('\n').all { ' ' in it })
        }
    }

    @Test
    fun screen9NoAnswerIsOneLine() {
        saved("midas", 3)
        val a = open()
        rig.svc.ask("근처 맛집 알려 줘")
        rig.idle()
        assertTrue(a.says("준비한 답이 없어요") && !a.says("출처"))
        check("9-없음", a)
    }

    @Test
    fun screen9_2DefrostButtons() {
        saved("midas", 3)
        val a = open()
        rig.svc.ask("앞유리에 김이 서려요")
        rig.idle(8_000)
        assertEquals(HelpStep.ASK_DEFROST, rig.svc.help.step)
        assertTrue(a.says("김 서림 제거 켜기") && a.says("괜찮아요") && a.says("출처 · 국토교통부 2021"))
        check("9-2", a)
        a.tap("괜찮아요")
        assertTrue(rig.aceLog.contains("ASIDE ok"))
    }

    @Test
    fun gearDDoesNotRedrawScreen() {
        saved("midas", 3)
        val a = open()
        val before = a.root()
        rig.signal("app.passengerace.GEAR") { putExtra("gear", "D") }
        rig.idle()
        assertEquals(Gear.D, rig.svc.signals.gear)
        assertSame(before, a.root())
    }
}
