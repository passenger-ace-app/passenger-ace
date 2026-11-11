package app.passengerace

enum class Turn { NONE, LEFT, RIGHT }
enum class Gear { P, R, N, D }
enum class Mode { OFF, PLAYING, WAITING }

// 내비 신호(TBTInfo)에는 차선과 놓쳤을 때 추가 시간이 없어서 할 일만 말한다. 표본 경로도 같은 말이다.
enum class Alert(val clip: String, val top: String, val sub: String) {
    PLAIN("alert_plain", "미리 오른쪽으로", "붙어 두세요"),
}

/** serial이면 연재 회차다. 연재 회차는 목록 순서대로 이어 둔다. */
data class Story(val id: String, val lines: Int, val serial: Boolean = false)

sealed interface Act {
    data class PlayLine(val storyId: String, val line: Int) : Act
    data class PlayClip(val name: String) : Act
    data class Popup(val alert: Alert) : Act
    data class Status(val storyId: String, val mode: Mode) : Act
    data object StopSound : Act
    data object RequestFocus : Act
    data object AbandonFocus : Act
    data object AlertCut : Act
    data class WakeIn(val ms: Long) : Act
}

const val RESUME_AFTER_MS = 30_000L
const val HOLD_IF_NAV_WITHIN_MS = 40_000L
const val HOLD_GRACE_MS = 5_000L // 다음 안내가 조금 늦어도 한 문장만 나오다 끊기지 않게

/** 재생 상태 기계. 안드로이드는 여기서 나온 Act만 실행한다. */
class Narrator(val stories: List<Story>, private val now: () -> Long) {
    private enum class Voice { NONE, LINE, CLIP, ALERT }

    var index = 0
        private set
    var line = 0
        private set
    var on = false
        private set
    var heard = setOf<String>()
        private set
    var talkLess = false
    /** 연재를 끊은 곳(회차 id, 줄). 단편으로 옮겨 가도 남아서 목록의 "이어 듣기"가 쓴다. 다 들었으면 null. */
    val serialAt get() = if (story.serial) story.id to line else serialMark
    private var serialMark: Pair<String, Int>? = null
    /** 다음 내비 안내까지 남은 ms. 모르면 null이고, 그러면 안내마다 바로 잇는다. */
    var nextNavIn: () -> Long? = { null }

    private var heldUntil: Long? = null
    private val held get() = heldUntil?.let { now() < it } == true
    private var lostTransient = false
    private var hasFocus = false
    private var inFocusStack = false
    private var voice = Voice.NONE
    private var stoppedAt: Long? = null
    private var clipDue: String? = null
    private var ending = false // 다 들어서 "end" 말 뒤에 멈춘다

    private val acts = mutableListOf<Act>()
    private var shown: Act.Status? = null

    private val story get() = stories[index]

    val mode: Mode
        get() = when {
            !on -> Mode.OFF
            voice == Voice.ALERT || !hasFocus || held -> Mode.WAITING
            else -> Mode.PLAYING
        }

    val canAlert get() = on && hasFocus && voice != Voice.ALERT

    fun restore(storyId: String?, savedLine: Int, savedHeard: Set<String>, less: Boolean, serial: Pair<String, Int>? = null) {
        val i = stories.indexOfFirst { it.id == storyId }
        if (i >= 0) {
            index = i
            line = savedLine.takeIf { it < stories[i].lines } ?: 0
        }
        heard = savedHeard
        talkLess = less
        serialMark = serial?.takeIf { (id, at) -> stories.any { it.id == id && it.serial && at < it.lines } }
    }

    fun toggle() = if (on) pause() else play()

    fun play() = step {
        if (on) return@step // 안내 중에 재생을 눌러도 바로 잇지 않는다
        on = true
        clipDue = if (line == 0 && stoppedAt == null) "start" else "resume"
        if (hasFocus) go() else askFocus()
    }

    fun pause() = step {
        if (!on) return@step
        on = false
        ending = false
        clipDue = null
        silence()
        dropFocus()
    }

    fun next() = step {
        heldUntil = null
        ending = false
        moveTo(following(true) ?: following(false) ?: index)
        if (!on) {
            on = true
            if (hasFocus) go() else askFocus()
        }
    }

    /** at은 연재를 끊은 줄에서 다시 듣게 할 때 쓴다. */
    fun choose(i: Int, at: Int = 0) = step { if (i != index) moveTo(i, at) }

    fun focusGained() = step {
        if (!inFocusStack) return@step // 끈 뒤에 늦게 온 신호
        hasFocus = true
        heldUntil = null
        // 규칙: 안내로 끊긴 뒤 다음 안내가 40초 안이면 30초 듣다 또 끊기지 않게 그 안내 뒤에 한 번에 잇는다.
        val wait = if (lostTransient) nextNavIn() else null
        lostTransient = false
        if (on && voice == Voice.NONE && wait != null && wait < HOLD_IF_NAV_WITHIN_MS) {
            heldUntil = now() + wait + HOLD_GRACE_MS
            acts += Act.WakeIn(wait + HOLD_GRACE_MS)
        }
        go()
    }

    /** 기다림 시간이 지나면 부른다. 그 사이 새로 기다리기로 했으면 go()가 막는다. */
    fun holdOver() = step { go() }

    fun focusLostTransient() = step {
        hasFocus = false
        lostTransient = true
        silence()
    }

    fun focusLost() = step {
        on = false
        ending = false
        clipDue = null
        silence()
        dropFocus()
    }

    fun lineFinished() = step {
        if (voice != Voice.LINE) return@step
        voice = Voice.NONE
        if (++line < story.lines) return@step go()
        heard = heard + story.id
        if (story.serial) serialMark = null // 다음 회가 있으면 serialAt이 그 회 처음을 가리킨다
        // 언제 내릴지 모르니 "next" 말 뒤에 다음 이야기를 잇는다. 다 들었으면 "end" 말 뒤에 멈추고 다음엔 처음부터.
        val to = following(true)
        index = to ?: 0
        line = 0
        stoppedAt = null
        if (to == null) {
            heard = emptySet()
            ending = true
        }
        clipDue = if (to == null) "end" else "next"
        go()
    }

    fun clipFinished() = step {
        if (voice != Voice.CLIP && voice != Voice.ALERT) return@step
        voice = Voice.NONE
        go()
    }

    fun startAlert(alert: Alert) = step {
        if (!canAlert) return@step
        if (voice != Voice.NONE) acts += Act.StopSound
        voice = Voice.ALERT
        acts += Act.PlayClip(alert.clip)
        acts += Act.Popup(alert)
    }

    private fun go() {
        if (!hasFocus || voice != Voice.NONE) return
        if (!on || held) return
        if (ending && clipDue == null) { // "end" 말이 끝났거나 안내에 끊겼다
            on = false
            ending = false
            dropFocus()
            return
        }
        val clip = when {
            clipDue == "next" || clipDue == "end" -> clipDue // 말수 적게여도 이야기 경계는 알린다
            talkLess -> null
            clipDue != null -> clipDue
            stoppedAt?.let { now() - it > RESUME_AFTER_MS } == true -> "resume"
            else -> null
        }
        clipDue = null
        stoppedAt = null
        if (clip != null) {
            voice = Voice.CLIP
            acts += Act.PlayClip(clip)
        } else {
            voice = Voice.LINE
            acts += Act.PlayLine(story.id, line)
        }
    }

    private fun silence() {
        if (voice == Voice.NONE) return
        if (voice == Voice.ALERT) acts += Act.AlertCut
        stoppedAt = stoppedAt ?: now()
        voice = Voice.NONE
        acts += Act.StopSound
    }

    private fun moveTo(i: Int, at: Int = 0) {
        if (story.serial) serialMark = story.id to line
        ending = false
        index = i
        line = at.takeIf { it < stories[i].lines } ?: 0
        stoppedAt = null
        clipDue = null
        if ((voice == Voice.LINE || voice == Voice.CLIP)) {
            voice = Voice.NONE
            acts += Act.StopSound
        }
        go()
    }

    /** 연재는 다음 회로, 단편과 연재 마지막 회 뒤에는 다음 단편으로 간다. fresh면 안 들은 단편만 고른다. */
    private fun following(fresh: Boolean): Int? {
        if (story.serial && stories.getOrNull(index + 1)?.serial == true) return index + 1
        return (1 until stories.size).map { (index + it) % stories.size }
            .firstOrNull { !stories[it].serial && (!fresh || stories[it].id !in heard) }
    }

    private fun askFocus() {
        if (inFocusStack) return
        inFocusStack = true
        acts += Act.RequestFocus
    }

    private fun dropFocus() {
        if (!inFocusStack) return
        inFocusStack = false
        hasFocus = false
        lostTransient = false
        heldUntil = null
        acts += Act.AbandonFocus
    }

    // 권한을 바로 받으면 안드로이드가 focusGained를 곧장 부르므로, 요청을 맨 뒤에 둬서 상태 글자가 뒤집히지 않게 한다.
    private fun step(block: () -> Unit): List<Act> {
        block()
        val status = Act.Status(story.id, mode)
        if (status != shown) {
            shown = status
            acts += status
        }
        return acts.sortedBy { it == Act.RequestFocus }.also { acts.clear() }
    }
}
