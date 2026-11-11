package app.passengerace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 차에서 읽는 값. */
interface CarSignals {
    val gear: Gear
    val turn: Turn
    var onChange: (() -> Unit)?
}

// shortcut: adb 브로드캐스트로 값을 받는 시험용 구현이다, 기어는 StoryService가 차 속성(GEAR_SELECTION)으로도 채운다. 깜빡이는 Vehicle SDK(TurnSignal)로 바꾼다.
object SampleSignals : CarSignals {
    override var gear = Gear.P
        internal set
    override var turn = Turn.NONE
        internal set
    override var onChange: (() -> Unit)? = null
    var onRoute: (() -> Unit)? = null
    var onArrive: (() -> Unit)? = null // 에뮬레이터 내비를 움직일 수 없어 도착 화면을 띄워 보는 시험용 입구
    var onHear: ((String?) -> Unit)? = null // 인식된 글 대신 넣는 시험용 입구(옆자리 안내)
    var onTip: ((String, Boolean) -> Unit)? = null // 차 신호 대신 먼저 알림 신호를 넣는 시험용 입구
    var onNight: ((Boolean) -> Unit)? = null // 밤 신호(NIGHT_MODE)는 Virtual Console에 없어 넣는 시험용 입구
}

class SampleSignalsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "app.passengerace.GEAR" -> SampleSignals.gear = Gear.valueOf(intent.getStringExtra("gear") ?: return)
            "app.passengerace.TURN" -> SampleSignals.turn = Turn.valueOf(intent.getStringExtra("turn") ?: return)
            "app.passengerace.ROUTE" -> {
                SampleSignals.onRoute?.invoke()
                return
            }
            "app.passengerace.ARRIVE" -> {
                SampleSignals.onArrive?.invoke()
                return
            }
            "app.passengerace.HEAR" -> {
                SampleSignals.onHear?.invoke(intent.getStringExtra("text"))
                return
            }
            "app.passengerace.NIGHT" -> {
                SampleSignals.onNight?.invoke(intent.getBooleanExtra("on", true))
                return
            }
            "app.passengerace.TIP" -> {
                SampleSignals.onTip?.invoke(intent.getStringExtra("id") ?: return, intent.getBooleanExtra("on", true))
                return
            }
            else -> return
        }
        SampleSignals.onChange?.invoke()
    }
}
