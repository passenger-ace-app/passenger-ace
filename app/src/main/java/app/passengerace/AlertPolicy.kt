package app.passengerace

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo

/** 놓치면 한참 돌아가는 분기(나들목, 갈림길)만 미리 알린다. 일반 교차로 회전은 내비에 맡긴다. */
fun bigBranchSide(type: TurnType): Turn? = when (type) {
    TurnType.TURN_EXIT_IC_R, TurnType.TURN_ENTER_IC_R, TurnType.TURN_KEEP_R -> Turn.RIGHT
    TurnType.TURN_EXIT_IC_L, TurnType.TURN_ENTER_IC_L, TurnType.TURN_KEEP_L -> Turn.LEFT
    else -> null
}

/** 에뮬레이터 실측: 첫 안내만 지금 위치에서의 거리이고, 나머지는 앞 안내에서 다음 안내까지라 앞에서부터 더한다. */
fun rightBranchDistance(list: List<TBTInfo>): Int? {
    var sum = 0.0
    for (t in list) {
        sum += t.distance
        if (bigBranchSide(t.type) == Turn.RIGHT) return sum.toInt()
    }
    return null
}

// 임시 값이다.
const val ALERT_FIRST_M = 2400
const val ALERT_REPEAT_M = 1500
const val ALERT_QUIET_M = 1000

/** 분기 하나마다 하나씩 만든다. */
class AlertPolicy(private val branch: Turn) {
    private var firstAt: Int? = null
    private var repeated = false
    private var sideOn = false
    private var cut = false

    fun check(distanceM: Int, turn: Turn, canSpeak: Boolean): Boolean {
        val first = firstAt
        if (first != null && turn == branch) sideOn = true
        if (!canSpeak || distanceM <= ALERT_QUIET_M) return false
        if (cut) { // 끊긴 알림은 말할 수 있게 된 첫 때에 한 번 다시 한다. 그새 같은 쪽 깜빡이를 켰으면 하지 않는다.
            cut = false
            if (sideOn) return false
            firstAt = distanceM
            return true
        }
        if (first == null) {
            if (distanceM > ALERT_FIRST_M) return false
            firstAt = distanceM
            return true
        }
        // 첫 알림이 늦게 나갔다면 곧바로 또 말하지 않는다.
        if (repeated || sideOn || distanceM > ALERT_REPEAT_M || first <= ALERT_REPEAT_M) return false
        repeated = true
        return true
    }

    /** 내비 음성 등에 알림이 끊겼을 때 부른다. */
    fun cut() {
        cut = true
    }
}
