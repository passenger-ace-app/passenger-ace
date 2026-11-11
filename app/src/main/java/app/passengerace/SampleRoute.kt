package app.passengerace

/** 표본 주행 시간표. 시험과 시연용 값이다. */
class SampleRoute(val navAt: List<Double>, val branchAt: Int, val speedMps: Int, val branch: Turn) {
    data class Tick(val distanceM: Int, val nav: List<Int>)

    fun tick(sec: Int) = Tick((branchAt - sec) * speedMps, navAt.indices.filter { navAt[it].toInt() == sec })
}
