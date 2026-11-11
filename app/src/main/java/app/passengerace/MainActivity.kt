package app.passengerace

import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

// shortcut: Pleos 앱 칸 1682x1210dp(가로, 에뮬레이터 실측)에 맞춘 고정 배치다, 다른 크기 칸이 생기면 비율로 바꾼다.
class MainActivity : Activity() {
    private var lastScreen: String? = null
    private var enteredAt = 0L // 화면 종류가 바뀐 시각. 차 신호로 같은 화면을 다시 그려도 움직임을 이어 간다
    private var listeningPulse: ValueAnimator? = null
    private var svc: StoryService? = null
    private var listOpen = false
    private var askedNotify = false
    private var afterAsk: (() -> Unit)? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as StoryService.Local).service
            svc = s
            s.onChange = ::render
            render()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StoryService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        listeningPulse?.cancel()
        listeningPulse = null
        svc?.onChange = null
        svc = null
        unbindService(conn)
        super.onStop()
    }

    private fun render() {
        val s = svc ?: return
        listeningPulse?.cancel()
        listeningPulse = null
        if (s.signals.gear == Gear.D) {
            moveTaskToBack(true) // 주행 중에는 내비가 화면을 쓴다
            return
        }
        val screen = when {
            s.help.step != HelpStep.OFF -> "help:${s.help.step}"
            s.arrived -> "arrival"
            listOpen -> "list"
            s.n.on -> "listening"
            s.started -> "again"
            else -> "first"
        }
        if (screen != lastScreen) enteredAt = SystemClock.uptimeMillis()
        lastScreen = screen
        setContentView(when {
            s.help.step != HelpStep.OFF -> helpScreen(s)
            s.arrived -> arrivalScreen(s)
            listOpen -> listScreen(s)
            s.n.on -> listeningScreen(s)
            s.started -> againScreen(s)
            else -> firstScreen(s)
        })
    }

    /** 시안 I2 뼈대: 왼쪽 표지 640(x64, 세로 가운데), 오른쪽 묶음 x784~1618. 글자 버튼의 글자 시작을 맞추려고 묶음을 24(목록은 32) 앞에서 연다. */
    private fun page(cover: StoryMeta, group: LinearLayout, list: Boolean = false, art: View = coverView(cover)) = FrameLayout(this).apply {
        setBackgroundColor(BG)
        addView(art, FrameLayout.LayoutParams(dp(640), dp(640), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(64) })
        group.enter(SystemClock.uptimeMillis() - enteredAt)
        addView(group, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            leftMargin = dp(if (list) 752 else 760)
            rightMargin = dp(if (list) 40 else 64) // 목록은 스크롤 표시를 오른쪽 여백 24 안에 둔다
            topMargin = dp(if (list) 40 else 312) // 1~3은 제목 줄이 y392에 고정, 목록 묶음은 y40~1168
        })
    }

    private fun eyebrow(vararg parts: View) = row().apply {
        parts.forEachIndexed { i, v -> addView(v, if (v is Glyph) lp(48, 48) else lp(WRAP, WRAP, start = if (i > 0) 16 else 0)) }
    }

    private fun go(label: String, shape: Shape?, action: () -> Unit) = row(Gravity.CENTER).apply {
        background = box(INK, 60)
        minimumWidth = dp(400)
        setPadding(dp(56), 0, dp(56), 0)
        if (shape != null) addView(Glyph(context, shape, BG), lp(48, 48))
        addView(text(label, 50, BG, 700, 1.2f, -0.015f), lp(WRAP, WRAP, start = if (shape != null) 16 else 0))
        tap(action)
    }

    /** 시안 .ask: 옅은 면 112, 글자 50/500. 주 버튼보다 약한 보조 버튼이다. */
    private fun soft(label: String, shape: Shape?, action: () -> Unit) = row(Gravity.CENTER).apply {
        background = box(FACE, 56)
        setPadding(dp(40), 0, dp(48), 0)
        if (shape != null) addView(Glyph(context, shape, INK), lp(48, 48))
        addView(text(label, 50, line = 1.2f, spacing = -0.015f), lp(WRAP, WRAP, start = if (shape != null) 16 else 0))
        tap(action)
    }

    private fun textButton(label: String, action: () -> Unit) = text(label, 50, line = 1.2f, spacing = -0.015f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), 0, dp(24), 0)
        tap(action)
    }

    private fun meta(s: StoryService) = s.catalog[s.n.index]
    private fun talkLabel(s: StoryService) = if (s.n.talkLess) "말수 되돌리기" else "말수 줄이기"
    private fun talkNow(s: StoryService) = if (s.n.talkLess) "말수 적게" else "말수 보통"
    private fun info(s: StoryService, m: StoryMeta) = text("${m.label} · ${length(m.ms.sum())} · ${talkNow(s)}", 36, SUB)

    private fun firstScreen(s: StoryService) = page(s.catalog[0], column().apply {
        addView(View(context), lp(MATCH, 72, start = 24))
        addView(title("조수석 에이스", 834), lp(WRAP, WRAP, top = 8, start = 24))
        addView(text("혼자 운전할 때 옛날이야기를 들려줘요.", 50, spacing = -0.015f), lp(WRAP, WRAP, top = 16, start = 24))
        addView(text("길을 놓치기 전에 미리 알려 주고,\n안내가 끝나면 이어 가요.", 36, SUB), lp(WRAP, WRAP, top = 8, start = 24))
        addView(go("옛날이야기 들어 보기", Shape.PLAY) { askThen { s.act { play() } } }, lp(WRAP, 120, top = 48, start = 24))
    })

    private fun againScreen(s: StoryService): View {
        val m = meta(s)
        val where = if (s.n.line > 0) "지난번에 듣던 이야기" else "다음 이야기"
        return page(m, column().apply {
            addView(eyebrow(text(where, 36, SUB)), lp(MATCH, 72, start = 24))
            addView(title(m.title, 834), lp(WRAP, WRAP, top = 8, start = 24))
            addView(info(s, m), lp(WRAP, WRAP, top = 16, start = 24))
            addView(go("이야기 듣기", Shape.PLAY) { s.act { play() } }, lp(WRAP, 120, top = 48, start = 24))
            addView(row().apply {
                addView(textButton("이야기 목록") {
                    listOpen = true
                    render()
                }, lp(WRAP, 112))
                addView(textButton(talkLabel(s)) { s.setTalkLess(!s.n.talkLess) }, lp(WRAP, 112, start = 24))
            }, lp(WRAP, WRAP, top = 24))
        })
    }

    private fun listeningScreen(s: StoryService): View {
        val m = meta(s)
        val waiting = s.n.mode == Mode.WAITING
        val color = if (waiting) WAIT else PLAY
        val done = m.ms.take(s.n.line).sum() // 줄 단위로 움직인다
        val total = m.ms.sum()
        fun clock(ms: Int) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
        return page(m, column().apply {
            addView(eyebrow(Glyph(context, Shape.WAVE, color), text(if (waiting) "안내 뒤 이어 가요" else "재생 중", 50, color, 700, spacing = -0.015f)), lp(MATCH, 72, start = 24))
            addView(title(m.title, 834), lp(WRAP, WRAP, top = 8, start = 24))
            addView(info(s, m), lp(WRAP, WRAP, top = 16, start = 24))
            addView(row().apply {
                background = box(FACE, 4)
                clipToOutline = true
                addView(View(context).apply { setBackgroundColor(PLAY) }, lp(0, MATCH, weight = done.toFloat()))
                addView(View(context), lp(0, MATCH, weight = (total - done).toFloat()))
            }, lp(MATCH, 8, top = 48, start = 24))
            addView(row().apply {
                addView(text(clock(done), 36, line = 1.25f), lp(0, WRAP, weight = 1f))
                addView(text(clock(total), 36, SUB, line = 1.25f), lp(WRAP, WRAP))
            }, lp(MATCH, WRAP, top = 16, start = 24))
            // 시안의 '이전 이야기' 단추는 앱에 그 기능이 없어 두지 않는다. 멈춤 원은 묶음 가운데, 다음은 x521.
            addView(FrameLayout(context).apply {
                addView(FrameLayout(context).apply {
                    background = box(PLAY, 64)
                    contentDescription = "멈춤"
                    addView(Glyph(context, Shape.PAUSE, BG), FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
                    tap { s.act { pause() } }
                }, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER_HORIZONTAL))
                addView(FrameLayout(context).apply {
                    contentDescription = "다음 이야기"
                    addView(Glyph(context, Shape.NEXT, INK), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
                    tap { s.act { next() } }
                }, FrameLayout.LayoutParams(dp(112), dp(112)).apply {
                    leftMargin = dp(521)
                    topMargin = dp(8)
                })
            }, lp(MATCH, 128, top = 24, start = 24))
            addView(soft("물어보기", Shape.MIC) { s.ask() }, lp(WRAP, 112, top = 48, start = 24))
        })
    }

    /** 화면 8(듣고 있어요), 9(답), 9-2(답 + 켤까요). 들은 말은 서비스 메모리에만 있고 답을 다 말하면 지워져 화면 3으로 돌아간다. */
    private fun helpScreen(s: StoryService): View {
        val h = s.help
        if (h.step == HelpStep.LISTENING) {
            val mic = column(Gravity.CENTER).apply {
                clipChildren = false
                background = box(FACE, 20)
                contentDescription = "듣고 있어요"
                addView(FrameLayout(context).apply {
                    clipChildren = false
                    addView(View(context).apply {
                        background = box(PLAY, 100)
                        listeningPulse = breathe(SystemClock.uptimeMillis() - enteredAt)
                    }, FrameLayout.LayoutParams(MATCH, MATCH))
                    addView(View(context).apply { background = box(PLAY, 100) }, FrameLayout.LayoutParams(MATCH, MATCH))
                    addView(Glyph(context, Shape.MIC, BG), FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
                }, lp(200, 200))
                addView(text("듣고 있어요", 50, PLAY, 700, spacing = -0.015f), lp(WRAP, WRAP, top = 40))
            }
            return page(meta(s), column().apply {
                addView(View(context), lp(MATCH, 72, start = 24))
                addView(title("말씀하세요", 834), lp(WRAP, WRAP, top = 8, start = 24))
                addView(text("이렇게 물어보세요", 36, SUB), lp(WRAP, WRAP, top = 40, start = 24))
                addView(text("“앞유리에 김이 서려요”", 50, spacing = -0.015f), lp(WRAP, WRAP, top = 16, start = 24))
                addView(soft("질문 취소", null) { s.stopHelp() }, lp(WRAP, 112, top = 48, start = 24))
            }, art = mic)
        }
        val a = h.answer
        return page(meta(s), column().apply {
            if (h.heard != null) addView(eyebrow(text("“${h.heard}”", 36, SUB).keepWords(dp(834))).apply { minimumHeight = dp(72) }, lp(MATCH, WRAP, start = 24))
            else addView(View(context), lp(MATCH, 72, start = 24))
            addView(text(a?.a ?: "준비한 답이 없어요", 50, spacing = -0.015f).sentences(dp(834)), lp(WRAP, WRAP, top = 16, start = 24))
            if (a != null && a.src.isNotEmpty()) addView(text("출처 · ${a.src}", 36, SUB), lp(WRAP, WRAP, top = 24, start = 24))
            if (h.step == HelpStep.ASK_DEFROST) addView(row().apply {
                addView(go("김 서림 제거 켜기", null) { s.answerDefrost(true) }, lp(WRAP, 120))
                addView(textButton("괜찮아요") { s.answerDefrost(false) }, lp(WRAP, 112, start = 24))
            }, lp(WRAP, WRAP, top = 48, start = 24))
            else addView(go("이야기 이어 듣기", Shape.PLAY) { s.stopHelp() }, lp(WRAP, 120, top = 48, start = 24))
        })
    }

    /** 화면 4. 미리 알린 적이 없거나 재탐색(길을 놓침)이 있었으면 "놓치지 않았어요" 줄을 두지 않는다. */
    private fun arrivalScreen(s: StoryService): View {
        val m = meta(s)
        val kept = s.alerts > 0 && !s.missed
        val later = if (s.n.on || s.n.line > 0) "다음에 탈 때 ${withEul(m.title)} 이어서 들려 드려요." else "다음에 탈 때는 ${withEul(m.title)} 들려 드려요."
        return page(m, column().apply {
            addView(row().apply { addView(Glyph(context, Shape.CHECK, INK), lp(56, 56)) }, lp(MATCH, 72, start = 24))
            addView(title("도착했어요", 834), lp(WRAP, WRAP, top = 8, start = 24))
            if (kept) addView(text("오늘 길을 ${s.alerts}번 미리 알렸고,\n놓치지 않았어요.", 50, spacing = -0.015f), lp(WRAP, WRAP, top = 16, start = 24))
            addView(text(later, 36, SUB), lp(WRAP, WRAP, top = if (kept) 8 else 16, start = 24))
            addView(go("닫기", null) { s.closeArrival() }, lp(WRAP, 120, top = 48, start = 24))
        })
    }

    /** 화면 5. 위는 넘기는 목록(보이는 칸 976), 아래 버튼 두 개는 고정. 말수는 화면 2에서 바꾼다. */
    private fun listScreen(s: StoryService): View {
        val m = meta(s)
        val rows = column()
        var picked: View? = null
        fun add(row: View, top: Int, on: Boolean) {
            rows.addView(row, lp(MATCH, WRAP, top))
            if (on) picked = row
        }
        // shortcut: 연재가 몬테크리스토 하나뿐이라 이어 듣기 줄도 하나다, 연재가 늘면 제목별로 줄을 만든다.
        val first = s.catalog.indexOfFirst { it.part > 0 }
        if (first >= 0) {
            val (i, at) = s.n.serialAt?.let { (id, at) -> s.catalog.indexOfFirst { it.id == id } to at } ?: (first to 0)
            val serial = s.catalog[i]
            val note = if (at > 0) "지난번에 ${serial.part}회 ${length(serial.ms.take(at).sum())}까지 들었어요" else null
            rows.addView(header(if (i == first && at == 0) "연재" else "이어 듣기"), lp(MATCH, 48, start = 32)) // 한 번도 안 들었으면 "연재"
            add(storyRow(serial, i == s.n.index, note) { s.act { choose(i, at) } }, 8, i == s.n.index)
        }
        rows.addView(header("단편"), lp(MATCH, 48, top = if (first >= 0) 16 else 0, start = 32))
        s.catalog.withIndex().filter { it.value.part == 0 }.forEachIndexed { k, (i, story) ->
            val on = i == s.n.index
            if (k > 0) {
                // 줄 사이 24 가운데의 구분선. 고른 줄 옆에는 긋지 않는다.
                rows.addView(View(this).apply { if (!on && i - 1 != s.n.index) setBackgroundColor(FACE) }, lp(MATCH, 2, top = 11, start = 32))
            }
            add(storyRow(story, on, null) { s.act { choose(i) } }, if (k > 0) 11 else 8, on)
        }
        val scroll = ScrollView(this).apply {
            // 오른쪽 여백 24 안에 늘 보이는 8px 스크롤 표시를 둔다(외부 점검 두 곳 공통).
            setPadding(0, 0, dp(24), 0)
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            scrollBarSize = dp(8)
            isScrollbarFadingEnabled = false
            verticalScrollbarTrackDrawable = box(0xFF263542.toInt(), 4)
            verticalScrollbarThumbDrawable = box(0xFF8B9AA5.toInt(), 4)
            addView(rows)
            // 새 화면은 맨 위에서 열리므로, 첫 배치가 끝난 뒤 고른 줄이 보이게 내린다(post는 배치 전에 돌 때가 있다).
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    removeOnLayoutChangeListener(this)
                    picked?.let { scrollTo(0, (it.bottom - height).coerceAtLeast(0)) }
                }
            })
        }
        return page(m, column().apply {
            addView(scroll, lp(MATCH, 976))
            addView(row().apply {
                addView(go("이야기 듣기", Shape.PLAY) {
                    listOpen = false
                    s.act { play() }
                }, lp(WRAP, 120))
                addView(textButton("닫기") {
                    listOpen = false
                    render()
                }, lp(WRAP, 112, start = 24))
            }, lp(WRAP, WRAP, top = 32, start = 32))
        }, list = true)
    }

    private fun header(label: String) = text(label, 36, SUB).apply { gravity = Gravity.CENTER_VERTICAL }

    /** 목록 한 줄: 작품명 50, 출처 36, 오른쪽 끝 실제 길이 36(고정폭 숫자). 고른 줄은 옅은 면과 왼쪽 6 파란 선, 파란 글자. */
    private fun storyRow(m: StoryMeta, on: Boolean, note: String?, action: () -> Unit) = row().apply {
        if (on) {
            background = box(FACE, 16)
            clipToOutline = true
            addView(View(context).apply { setBackgroundColor(PLAY) }, lp(6, MATCH))
        }
        minimumHeight = dp(if (note != null) 152 else 112) // 처음 열면 맨 아래 여섯째 단편 줄이 반쯤 보여 넘길 수 있음을 알린다
        setPadding(0, dp(12), dp(24), dp(12))
        val len = text(length(m.ms.sum()), 36, if (on) PLAY else SUB, if (on) 700 else 500, 1.2f).apply { fontFeatureSettings = "tnum" }
        val room = dp(810 - 24) - len.paint.measureText(len.text.toString()).toInt() // 줄 안쪽 폭 810에서 길이와 그 앞 24를 뺀 칸
        val name = text(m.title, 50, if (on) PLAY else INK, 700, 1.25f, -0.015f).keepWords(room)
        val src = text(m.label, 36, SUB, line = 1.2f)
        // 작품명이 두 줄이거나 그 뒤에 출처가 들어가지 않으면 출처를 아랫줄로 내린다.
        val fits = '\n' !in name.text &&name.paint.measureText(m.title) + dp(24) + src.paint.measureText(m.label) <= room
        val block = if (fits) row() else column()
        block.addView(name)
        block.addView(src, if (fits) lp(WRAP, WRAP, start = 24) else lp(WRAP, WRAP, top = 4))
        addView(column().apply {
            addView(row().apply {
                addView(block, lp(0, WRAP, weight = 1f))
                addView(len, lp(WRAP, WRAP, start = 24))
            }, lp(MATCH, WRAP))
            if (note != null) addView(text(note, 36, SUB, line = 1.3f), lp(WRAP, WRAP, top = 16)) // 시안처럼 두 줄을 152 안에 벌려 둔다
        }, lp(0, WRAP, start = if (on) 26 else 32, weight = 1f))
        tap(action)
    }

    /** 받침이 있으면 "을", 없으면 "를". 이야기 제목은 한글로 끝난다. */
    private fun withEul(word: String) = word + if ((word.last() - '가') % 28 != 0) "을" else "를"

    // 처음 화면의 듣기 버튼에서 묻고, 답한 뒤에 이야기를 튼다. 그래야 허용 창이 화면 2·3 가운데를 가리지 않는다.
    // 거절하면 미리 알림은 목소리로만 나온다.
    private fun askThen(action: () -> Unit) {
        if (askedNotify || Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return action()
        askedNotify = true
        afterAsk = action
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        afterAsk?.invoke()
        afterAsk = null
    }
}
