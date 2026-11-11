package app.passengerace

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.animation.StateListAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

// 시안 I2의 색이다(앱 칸 1682x1210, 밀도 160이라 dp=px).
const val BG = 0xFF101820.toInt()
const val FACE = 0xFF202E3C.toInt()
const val INK = 0xFFF4F7FA.toInt()
const val SUB = 0xFFA9B8C7.toInt() // 보조 글자. 외부 점검 두 곳 공통 의견으로 밝혔다
const val PLAY = 0xFF49B5FF.toInt()
const val WAIT = 0xFFF2B544.toInt()

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

/** 시안은 Pretendard 500·700만 쓴다. */
fun Context.text(s: CharSequence, size: Int, color: Int = INK, weight: Int = 500, line: Float = 1.5f, spacing: Float = -0.01f) = TextView(this).apply {
    text = s
    setTextColor(color)
    textSize = size.toFloat()
    typeface = resources.getFont(if (weight >= 600) R.font.pretendard_bold else R.font.pretendard_medium)
    includeFontPadding = false
    isFallbackLineSpacing = false // 대체 글꼴의 줄 높이가 더해지면 시안보다 벌어진다
    lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size * line, resources.displayMetrics).roundToInt()
    letterSpacing = spacing
    // 시안(CSS)처럼 줄 간격의 남는 높이를 글자 위아래에 반씩 둔다. 안드로이드는 마지막 줄 아래를 빼서 한 줄 글자가 시안보다 낮아진다.
    val fm = paint.fontMetricsInt
    val half = (lineHeight - (fm.descent - fm.ascent)) / 2
    firstBaselineToTopHeight = -fm.ascent + half
    lastBaselineToBottomHeight = fm.descent + half
}

/** 제목 96. 한글은 글자 사이에서도 줄이 바뀌므로, 칸보다 길면 가운데 띄어쓰기에서 직접 끊는다. */
fun Context.title(s: String, room: Int) = text(s, 96, weight = 700, line = 1.25f, spacing = -0.025f).apply {
    val cut = s.indices.filter { s[it] == ' ' }.minByOrNull { abs(it - s.length / 2) }
    if (cut != null && paint.measureText(s) > dp(room)) text = s.replaceRange(cut, cut + 1, "\n")
}

fun Context.box(fill: Int, radius: Int) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
}

fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun Context.column(gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    this.gravity = gravity
}

fun Context.lp(w: Int, h: Int, top: Int = 0, start: Int = 0, weight: Float = 0f) =
    LinearLayout.LayoutParams(if (w > 0) dp(w) else w, if (h > 0) dp(h) else h, weight).apply {
        topMargin = dp(top)
        marginStart = dp(start)
    }

/** Pleos 규칙: 눌리면 95%로 줄어든다. */
fun <V : View> V.tap(action: () -> Unit) = apply {
    isClickable = true
    setOnClickListener { action() }
    fun scale(to: Float) = AnimatorSet().apply {
        playTogether(ObjectAnimator.ofFloat(this@tap, View.SCALE_X, to), ObjectAnimator.ofFloat(this@tap, View.SCALE_Y, to))
        duration = 80
    }
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed), scale(0.95f))
        addState(intArrayOf(), scale(1f))
    }
}

/** 화면 종류가 바뀔 때 오른쪽 묶음이 220ms 동안 떠오른다. elapsed는 바뀐 뒤 지난 시간이라 차 신호로 다시 그려도 끊기지 않고 이어진다. */
fun View.enter(elapsed: Long) {
    if (!ValueAnimator.areAnimatorsEnabled() || elapsed >= 220) return
    ObjectAnimator.ofPropertyValuesHolder(this,
        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, context.dp(12).toFloat(), 0f)).apply {
        duration = 220
        interpolator = DecelerateInterpolator()
        start()
        currentPlayTime = elapsed
    }
}

/** 듣기 표시 뒤의 원. elapsed로 박자를 이어 가고, 화면에서 제거되면 반복과 리스너를 해제한다. */
fun View.breathe(elapsed: Long): ValueAnimator? {
    alpha = 0f
    scaleX = 1.25f
    scaleY = 1.25f
    if (!ValueAnimator.areAnimatorsEnabled()) return null
    val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            val fraction = it.animatedValue as Float
            scaleX = 1f + 0.25f * fraction
            scaleY = scaleX
            alpha = 0.35f * (1f - fraction)
        }
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            pulse.cancel()
            pulse.removeAllUpdateListeners()
            removeOnAttachStateChangeListener(this)
        }
    })
    pulse.start()
    pulse.currentPlayTime = elapsed % 1200
    return pulse
}

/** 표지 그림(tools/make_assets.py가 cover_<이야기 id>.webp로 만든다). 연재 회차는 표지 하나를 같이 쓴다. 없으면 null이다. */
fun Context.cover(id: String): Int? {
    val name = "cover_" + id.replace(Regex("-\\d+$"), "").replace('-', '_')
    @Suppress("DiscouragedApi") // 파일 이름이 이야기 id라 표를 따로 두지 않는다
    return resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
}

/** 640x640 표지. 그림이 없는 이야기는 옅은 면에 제목과 지은이(연재면 회차)만 쓴 글자 표지다. */
fun Context.coverView(m: StoryMeta): View {
    val art = cover(m.id) ?: return column().apply {
        background = box(FACE, 20)
        setPadding(dp(48), dp(48), dp(48), dp(48))
        contentDescription = "${m.title} 글자 표지"
        addView(text(m.title, 88, weight = 700, line = 1.2f, spacing = -0.025f).keepWords(dp(544)), lp(MATCH, 0, weight = 1f))
        addView(text(if (m.part > 0) "${m.source}\n연재 ${m.part}회" else m.source, 36, SUB), lp(WRAP, WRAP))
    }
    return ImageView(this).apply {
        setImageResource(art)
        contentDescription = "${m.title} 표지"
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = box(FACE, 20)
        clipToOutline = true
    }
}

/** 한글은 글자 사이에서도 줄이 바뀌므로, 칸(room px)보다 길면 띄어쓰기에서 직접 줄을 바꾼다. */
fun TextView.keepWords(room: Int) = apply { text = wrapWords(text.toString(), room) }

private fun TextView.wrapWords(s: String, room: Int) = s.split(' ').fold("") { acc, w ->
    when {
        acc.isEmpty() -> w
        paint.measureText("${acc.substringAfterLast('\n')} $w") > room -> "$acc\n$w"
        else -> "$acc $w"
    }
}

/** 답 글: 시안처럼 문장마다 줄을 바꾸고, 칸보다 긴 문장은 줄 길이가 고르게 바꾼다(끝줄에 한 낱말만 남지 않게). */
fun TextView.sentences(room: Int) = apply { text = text.split(". ").joinToString(".\n") { balanced(it, room) } }

private fun TextView.balanced(s: String, room: Int): String {
    val lines = wrapWords(s, room).count { it == '\n' } + 1
    var r = (paint.measureText(s) / lines).toInt()
    while (lines > 1 && r < room) {
        val t = wrapWords(s, r)
        if (t.count { it == '\n' } + 1 == lines) return t
        r += 8
    }
    return wrapWords(s, room)
}

enum class Shape(val box: Float) { PLAY(40f), PAUSE(40f), NEXT(40f), WAVE(36f), CHECK(40f), MIC(40f) }

/** 시안의 SVG 기호를 그린다. 모서리 곡선은 단순하게 줄였다. */
class Glyph(context: Context, shape: Shape, color: Int) : View(context) {
    private val box = shape.box
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path().apply {
        fun bar(l: Float, t: Float, r: Float, b: Float, rx: Float) = addRoundRect(RectF(l, t, r, b), rx, rx, Path.Direction.CW)
        when (shape) {
            Shape.PLAY -> {
                moveTo(11f, 5.5f); lineTo(35.3f, 20f); lineTo(11f, 34.5f); close()
            }
            Shape.PAUSE -> {
                bar(8f, 5f, 16f, 35f, 2f)
                bar(24f, 5f, 32f, 35f, 2f)
            }
            Shape.NEXT -> {
                moveTo(6f, 7.5f); lineTo(26.3f, 20f); lineTo(6f, 32.5f); close()
                bar(29f, 6f, 34f, 34f, 2f)
            }
            Shape.WAVE -> {
                bar(3f, 13f, 8f, 23f, 2.5f)
                bar(11f, 6f, 16f, 30f, 2.5f)
                bar(19f, 10f, 24f, 26f, 2.5f)
                bar(27f, 3f, 32f, 33f, 2.5f)
            }
            Shape.MIC -> {
                // 시안 i-mic: 몸통 14x24, 받침 호(굵기 4), 다리 4x8.
                bar(13f, 2f, 27f, 26f, 7f)
                addPath(Path().apply {
                    // 가운데 (20,18), 반지름 13인 아래 반원을 굵기 4로: 바깥 반지름 15, 안 반지름 11. 끝은 둥글게.
                    arcTo(RectF(5f, 3f, 35f, 33f), 0f, 180f, true)
                    arcTo(RectF(9f, 7f, 31f, 29f), 180f, -180f)
                    close()
                    addCircle(7f, 18f, 2f, Path.Direction.CW)
                    addCircle(33f, 18f, 2f, Path.Direction.CW)
                })
                bar(18f, 30f, 22f, 38f, 2f)
            }
            Shape.CHECK -> {
                // 채운 원에서 체크 모양을 뚫는다.
                fillType = Path.FillType.EVEN_ODD
                addCircle(20f, 20f, 18f, Path.Direction.CW)
                moveTo(17f, 28.5f); lineTo(8.5f, 20f); lineTo(11.5f, 17f); lineTo(17f, 22.5f); lineTo(28.5f, 11f); lineTo(31.5f, 14f); close()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / box
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
    }
}
