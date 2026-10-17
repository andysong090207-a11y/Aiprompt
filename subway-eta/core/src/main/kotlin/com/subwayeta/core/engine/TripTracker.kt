package com.subwayeta.core.engine

import com.subwayeta.core.model.Route
import com.subwayeta.core.model.distanceMeters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed interface Phase {
    /** 경로상 k번째 역에 정차 중. [arrivedAt]이 null이면 도착 시각을 모름(출발역에서 시작). */
    data class AtStation(val k: Int, val arrivedAt: Long?) : Phase

    /**
     * k번째 역을 떠나 k+1번째 역으로 가는 중.
     * [holdMs]는 역 사이에서 신호 대기 등으로 멈춰 있던 누적 시간, [holdingSince]는 지금 멈춰 있으면 그 시작 시각.
     * [departureEstimated]가 true면 열차 안에서 앱을 켜서 실제 출발 시각을 모른다.
     */
    data class Running(
        val fromK: Int,
        val departedAt: Long,
        val departureEstimated: Boolean = false,
        val holdMs: Long = 0,
        val holdingSince: Long? = null,
    ) : Phase

    data class Finished(val arrivedAt: Long) : Phase
}

/** 사람이 읽을 수 있는 추적 기록 한 줄. */
data class TrackLog(val atMillis: Long, val message: String)

/**
 * 센서 이벤트(출발/정차)와 간헐적인 GPS 위치로 경로상 현재 위치를 추적한다.
 *
 * - 정차가 감지되면 지금까지 달린 시간과 예상 시간을 비교해 역 도착인지, 역 사이 신호 대기인지,
 *   혹은 앞 역 정차를 놓쳐서 두 정거장을 왔는지를 판단한다.
 * - 정상적으로 측정된 구간 주행 시간과 정차 시간은 [TravelStats]에 학습시킨다.
 */
class TripTracker(
    val route: Route,
    private val stats: TravelStats,
    startAt: Long,
    private val onLog: (TrackLog) -> Unit = {},
    private val onLearned: () -> Unit = {},
) {
    var phase: Phase = Phase.AtStation(0, null)
        private set
    private val startedAt = startAt

    fun onInitialMoving(t: Long) {
        val p = phase
        if (p is Phase.AtStation && p.arrivedAt == null) {
            phase = Phase.Running(p.k, t, departureEstimated = true)
            log(t, "열차 안에서 시작: ${route.station(p.k).name} 출발 직후로 가정")
        }
    }

    fun onDeparted(t: Long) {
        when (val p = phase) {
            is Phase.AtStation -> {
                if (p.k >= route.lastK) return
                if (p.arrivedAt != null) {
                    val sec = (t - p.arrivedAt) / 1000.0
                    if (stats.recordDwell(route, p.k, t, sec)) onLearned()
                    log(t, "${route.station(p.k).name} 출발 (정차 ${fmt(sec)}초)")
                } else {
                    log(t, "${route.station(p.k).name} 출발")
                }
                phase = Phase.Running(p.k, t)
            }
            is Phase.Running -> if (p.holdingSince != null) {
                val held = t - p.holdingSince
                phase = p.copy(holdMs = p.holdMs + held, holdingSince = null)
                log(t, "역 사이 정차 ${fmt(held / 1000.0)}초 후 다시 출발")
            }
            is Phase.Finished -> Unit
        }
    }

    fun onArrived(t: Long) {
        val p = phase as? Phase.Running ?: return
        if (p.holdingSince != null) return
        val elapsed = (t - p.departedAt - p.holdMs) / 1000.0
        val firstRun = stats.run(route, p.fromK, t).meanSec

        if (!p.departureEstimated && elapsed < MIN_RUN_FRACTION * firstRun) {
            phase = p.copy(holdingSince = t)
            log(t, "역 사이에서 멈춤 (${fmt(elapsed)}초 주행, 예상 ${fmt(firstRun)}초) → 신호 대기로 판단")
            return
        }

        // 정차를 놓쳤을 가능성까지 고려해 1~3 정거장 중 가장 잘 맞는 것을 고른다.
        var bestM = 1
        if (!p.departureEstimated) {
            var bestErr = Double.MAX_VALUE
            var cum = 0.0
            for (m in 1..min(MAX_SKIP, route.lastK - p.fromK)) {
                cum += stats.run(route, p.fromK + m - 1, t).meanSec
                if (m > 1) cum += stats.dwell(route, p.fromK + m - 1, t).meanSec
                val err = abs(elapsed - cum) / cum
                if (err < bestErr) {
                    bestErr = err
                    bestM = m
                }
            }
        }
        val k = p.fromK + bestM
        if (bestM == 1 && p.holdMs == 0L && !p.departureEstimated) {
            if (stats.recordRun(route, p.fromK, t, elapsed)) onLearned()
        }
        val skipped = if (bestM > 1) " (정차 ${bestM - 1}회 놓친 것으로 판단)" else ""
        log(t, "${route.station(k).name} 도착 (주행 ${fmt(elapsed)}초)$skipped")
        phase = if (k >= route.lastK) Phase.Finished(t) else Phase.AtStation(k, t)
    }

    /**
     * GPS 위치로 보정한다. 멈춰 있을 때 역 반경 안이면 그 역에 있다고 본다.
     * 보정했으면 true.
     */
    fun onLocation(lat: Double, lon: Double, accuracyM: Double, t: Long, stationary: Boolean): Boolean {
        if (!stationary || accuracyM > MAX_GPS_ACCURACY_M) return false
        val currentK = when (val p = phase) {
            is Phase.AtStation -> p.k
            is Phase.Running -> p.fromK
            is Phase.Finished -> return false
        }
        val radius = max(MIN_SNAP_RADIUS_M, accuracyM * 1.5)
        val near = (max(0, currentK - 1)..route.lastK)
            .map { it to distanceMeters(lat, lon, route.station(it).lat, route.station(it).lon) }
            .filter { it.second <= radius }
            .minByOrNull { it.second } ?: return false
        val j = near.first
        val p = phase
        if (p is Phase.AtStation && p.k == j) return false
        log(t, "GPS 보정: ${route.station(j).name} (${near.second.toInt()}m, 정확도 ${accuracyM.toInt()}m)")
        phase = if (j >= route.lastK) Phase.Finished(t) else Phase.AtStation(j, if (p is Phase.Running) t else null)
        return true
    }

    /** 사용자가 직접 "지금 이 역"을 고쳤을 때. */
    fun setCurrentStation(k: Int, t: Long, moving: Boolean) {
        require(k in 0..route.lastK)
        log(t, "수동 보정: ${route.station(k).name}${if (moving) " 출발 후" else ""}")
        phase = when {
            k >= route.lastK -> Phase.Finished(t)
            moving -> Phase.Running(k, t, departureEstimated = true)
            else -> Phase.AtStation(k, null)
        }
    }

    fun ageMillis(now: Long) = now - startedAt

    private fun log(t: Long, msg: String) = onLog(TrackLog(t, msg))

    private fun fmt(sec: Double) = "%.1f".format(sec)

    companion object {
        /** 예상 주행 시간의 이 비율보다 일찍 멈추면 역이 아니라 신호 대기로 본다. */
        const val MIN_RUN_FRACTION = 0.45
        const val MAX_SKIP = 3
        const val MAX_GPS_ACCURACY_M = 80.0
        const val MIN_SNAP_RADIUS_M = 150.0
    }
}
