package app.passengerace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

const val ASK_ROAD_HELP = "ASK_ROAD_HELP"

/**
 * 차 음성 비서가 assets/schema.json의 ASK_ROAD_HELP(topic)을 부르면 받는다.
 * 답은 앱이 녹음으로 말하므로 비서에게는 silent로 돌려준다. 비서는 7초 안에 답이 없으면 취소한다.
 */
class HelpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ASK_ROAD_HELP) return
        val topic = topicOf(intent.getStringExtra("ACTION_PARAM"))
        // 이 입구는 다른 앱도 부를 수 있다. 비서는 늘 topic을 주므로, topic 없이 오면 마이크를 켜지 않고 거절한다.
        val ask = onAsk?.takeIf { topic != null }
        Log.i("ACE", "GLEO $ASK_ROAD_HELP live=${ask != null}")
        // shortcut: 이야기 서비스가 살아 있을 때만 답한다(이야기를 틀었거나 차 미디어 위젯이 붙어 있을 때), 뒤에서 서비스를 깨우는 길이 확인되면 그때 띄운다.
        ask?.invoke(topic)
        val reply = JSONObject()
            .put("functionName", ASK_ROAD_HELP)
            .put("status", if (ask != null) "success" else "fail")
            .put("message", if (ask != null) "The app is speaking a prepared answer itself." else "The app is not running now.")
            .put("mode", if (ask != null) "silent" else "voice")
        context.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
            .putExtra("packageName", context.packageName)
            .putExtra("response", reply.toString()))
    }

    companion object {
        var onAsk: ((String?) -> Unit)? = null

        /** 문서 예시는 JSON을 따옴표로 한 번 더 감싸서 보낸다. 둘 다 받는다. */
        fun topicOf(param: String?): String? {
            val raw = param?.trim() ?: return null
            val json = runCatching { JSONObject(raw) }.recoverCatching { JSONObject(raw.removeSurrounding("\"")) }.getOrNull()
            return json?.optString("topic")?.ifBlank { null }
        }
    }
}
