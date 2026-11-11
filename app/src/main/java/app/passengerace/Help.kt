package app.passengerace

import org.json.JSONArray
import org.json.JSONObject

/** 옆자리 안내 답 하나(help.json). action이 "defrost"면 답 뒤에 김 서림 제거를 켤지 묻는다. */
class HelpAnswer(val id: String, val q: String, val a: String, val src: String, val keys: List<String>, val action: String?)

/** 띄어쓰기는 사람마다, 인식기마다 달라서 빼고 맞춘다. */
private fun String.squash() = filterNot(Char::isWhitespace)

/** 준비한 답과 들은 말을 맞춘다. 안드로이드 코드 없음. tips는 먼저 알림 id → 말할 글. */
class HelpBook(val answers: List<HelpAnswer>, val tips: Map<String, String>, private val yes: List<String>, private val no: List<String>) {
    /** 들어맞은 열쇠말 길이의 합이 가장 큰 답. "2차 사고"가 "사고"보다 길어서 이긴다. 없으면 null. */
    fun match(heard: String): HelpAnswer? {
        val t = heard.squash()
        return answers.map { a -> a to a.keys.map { it.squash() }.filter { it.isNotEmpty() && it in t }.sumOf { it.length } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    /** "켜지 마"에 "켜"가 들어 있어서 아니오를 먼저 본다. 못 들었거나 둘 다 아니면 아니오다. */
    fun yes(heard: String?): Boolean {
        val t = heard?.squash() ?: return false
        if (no.any { it.squash() in t }) return false
        return yes.any { it.squash() in t }
    }

    companion object {
        fun parse(json: String): HelpBook {
            val o = JSONObject(json)
            fun JSONArray.strings() = (0 until length()).map(::getString)
            val answers = o.getJSONArray("answers").let { arr ->
                (0 until arr.length()).map(arr::getJSONObject).map {
                    HelpAnswer(it.getString("id"), it.getString("q"), it.getString("a"), it.getString("src"),
                        it.getJSONArray("keys").strings(), it.optString("action").ifEmpty { null })
                }
            }
            val tips = o.getJSONArray("tips").let { arr -> (0 until arr.length()).map(arr::getJSONObject).associate { it.getString("id") to it.getString("a") } }
            return HelpBook(answers, tips, o.getJSONArray("yes").strings(), o.getJSONArray("no").strings())
        }
    }
}

/** 차 신호를 보는 먼저 알림 id. */
const val TIP_TPMS = "tip_tpms"
const val TIP_ENGINE_HOT = "tip_engine_hot"
const val TIP_FUEL = "tip_fuel"
const val TIP_BATTERY = "tip_battery"
const val TIP_RAIN = "tip_rain"
const val TIP_REST = "tip_rest"
const val TIP_NIGHT_LIGHTS = "tip_night_lights"
const val TIP_RAIN_LIGHTS = "tip_rain_lights"
const val TIP_TURN_SIGNAL = "tip_turn_signal"
const val TIP_WINDOW_PARKED = "tip_window_parked"
const val FUEL_LOW_PERCENT = 10
const val BATTERY_LOW_PERCENT = 15
const val REST_AFTER_MIN = 120 // 주행 시간 값은 분이다(Virtual Console 목록의 TRIP_DRIVING_TIME 0x21410450 단위 "min"). 실제 차에서 단위 확인 전
const val NEW_DRIVE_PARKED_MS = 30 * 60_000L
private val DRIVE_ONLY = setOf(TIP_RAIN, TIP_REST)
const val TURN_SIGNAL_LONG_MS = 60_000L
const val TURN_NEAR_M = 300

/** 밤이거나 비가 오는데(cause) D에서 전조등이 꺼져 있다. 스위치가 자동이면 차가 알아서 켜므로 말하지 않는다. */
fun lightsNeeded(cause: Boolean, gear: Gear, lowBeamOn: Boolean, autoSwitch: Boolean) =
    cause && gear == Gear.D && !lowBeamOn && !autoSwitch

/** D에서 깜빡이를 1분 넘게 켜 두었다. 내비의 다음 회전·분기가 300m 안이면 일부러 켠 것으로 본다. */
fun turnSignalForgotten(onSince: Long?, now: Long, gear: Gear, nextTurnM: Int?) =
    onSince != null && gear == Gear.D && now - onSince > TURN_SIGNAL_LONG_MS && (nextTurnM == null || nextTurnM > TURN_NEAR_M)

/**
 * 먼저 알림을 언제 말할지 정한다. 안드로이드 코드 없음.
 * 한 번 주행(처음 D부터 도착 또는 P로 30분 이상까지)에 같은 알림은 한 번만, 겹치면 온 차례로 하나씩.
 */
class TipPolicy(private val now: () -> Long) {
    private val last = mutableMapOf<String, Boolean>()
    private val said = mutableSetOf<String>()
    private val waiting = ArrayDeque<String>()
    private var inDrive = false
    private var driving = false
    private var parkedAt: Long? = null

    /** 차 신호가 올 때마다 부른다. 처음 값은 기준으로만 삼고, 꺼짐에서 켜짐으로 바뀔 때만 줄에 넣는다. */
    fun level(id: String, on: Boolean) {
        val was = last.put(id, on)
        if (was != false || !on || id in said) return
        if (id in DRIVE_ONLY && !driving) return // 와이퍼·주행 시간은 D일 때만
        said += id
        waiting += id
    }

    fun gear(g: Gear) {
        if (g == Gear.P) {
            parkedAt = parkedAt ?: now()
        } else {
            if (parkedAt?.let { now() - it >= NEW_DRIVE_PARKED_MS } == true) inDrive = false
            parkedAt = null
        }
        if (g == Gear.D && !inDrive) {
            inDrive = true
            said.clear()
        }
        driving = g == Gear.D
    }

    fun arrived() {
        inDrive = false
    }

    /** free는 내비 안내·큰 갈림길 미리 알림·옆자리 안내가 말하고 있지 않을 때다. 그때만 하나를 꺼낸다. 기다리는 사이 풀린 알림(경고등 꺼짐 등)은 나오기 직전에 버린다. */
    fun next(free: Boolean): String? {
        if (!free) return null
        while (true) {
            val id = waiting.removeFirstOrNull() ?: return null
            if (last[id] == true) return id
            said -= id // 말하지 못했으니 이번 주행에 다시 켜지면 알린다
        }
    }
}
