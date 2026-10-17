package com.subwayeta.core.engine

import com.subwayeta.core.model.Route
import com.subwayeta.core.model.Station
import kotlin.math.max
import kotlin.math.sqrt

/** 경로상 한 역의 예측. 시각은 epoch 밀리초. */
data class StationEta(
    val k: Int,
    val station: Station,
    /** 도착 예정 시각. 이미 도착해 정차 중이면 실제 도착 시각(모르면 null). */
    val arriveAt: Long?,
    /** 출발 예정 시각. 도착역은 null. */
    val departAt: Long?,
    /** 예상 정차 시간(초). 도착역은 0. */
    val dwellSec: Double,
    /** 도착 시각의 불확실성(1σ, 초). */
    val sigmaSec: Double,
    val isCurrent: Boolean,
)

data class Prediction(
    val now: Long,
    val phase: Phase,
    /** 지금 정차 중인 역(또는 막 떠난 역)부터 도착역까지. */
    val stations: List<StationEta>,
    /** 정차 중이면 출발 예정 시각. */
    val departureAt: Long?,
    /** 다음 역 도착 예정 시각. */
    val nextArrivalAt: Long?,
    val destinationArrivalAt: Long?,
    val destinationSigmaSec: Double,
    /** 예정 시각이 지났는데 아직 이벤트가 없음(곧 도착/출발할 것으로 봄). */
    val overdue: Boolean,
)

/**
 * 현재 위치와 학습된 구간/정차 시간으로 남은 역들의 도착·출발 시각을 밀리초 단위로 계산한다.
 * 불확실성은 구간마다 독립이라고 보고 분산을 더한다.
 */
object Predictor {
    /** 예정 시각이 지났을 때 "곧"으로 표시할 최소 여유(밀리초). */
    const val OVERDUE_MARGIN_MS = 2_000L

    fun predict(route: Route, stats: TravelStats, phase: Phase, now: Long): Prediction {
        val out = mutableListOf<StationEta>()
        var overdue = false
        var t: Long
        var variance = 0.0
        val startK: Int
        var departureAt: Long? = null

        when (phase) {
            is Phase.Finished -> {
                val s = route.station(route.lastK)
                out += StationEta(route.lastK, s, phase.arrivedAt, null, 0.0, 0.0, true)
                return Prediction(now, phase, out, null, null, phase.arrivedAt, 0.0, false)
            }
            is Phase.AtStation -> {
                val dwell = stats.dwell(route, phase.k, now)
                var dep = (phase.arrivedAt ?: now) + ms(dwell.meanSec)
                if (dep < now) {
                    overdue = true
                    dep = now + OVERDUE_MARGIN_MS
                }
                departureAt = dep
                variance += dwell.sigmaSec * dwell.sigmaSec
                out += StationEta(phase.k, route.station(phase.k), phase.arrivedAt, dep, dwell.meanSec, 0.0, true)
                t = dep
                startK = phase.k
            }
            is Phase.Running -> {
                val holding = phase.holdingSince?.let { now - it } ?: 0L
                out += StationEta(phase.fromK, route.station(phase.fromK), null, phase.departedAt, 0.0, 0.0, true)
                t = phase.departedAt + phase.holdMs + holding
                startK = phase.fromK
            }
        }

        var nextArrival: Long? = null
        for (k in startK + 1..route.lastK) {
            val run = stats.run(route, k - 1, now)
            t += ms(run.meanSec)
            variance += run.sigmaSec * run.sigmaSec
            if (k == startK + 1 && t < now + OVERDUE_MARGIN_MS && phase is Phase.Running) {
                // 예상보다 늦어지는 중: 아직 도착 안 했으니 "곧 도착"으로 밀어 둔다.
                overdue = t < now
                t = now + OVERDUE_MARGIN_MS
            }
            if (nextArrival == null) nextArrival = t
            val arrive = t
            val sigma = sqrt(variance)
            if (k == route.lastK) {
                out += StationEta(k, route.station(k), arrive, null, 0.0, sigma, false)
            } else {
                val dwell = stats.dwell(route, k, now)
                t += ms(dwell.meanSec)
                variance += dwell.sigmaSec * dwell.sigmaSec
                out += StationEta(k, route.station(k), arrive, t, dwell.meanSec, sigma, false)
            }
        }
        val dest = out.last()
        return Prediction(
            now = now,
            phase = phase,
            stations = out,
            departureAt = departureAt,
            nextArrivalAt = nextArrival,
            destinationArrivalAt = dest.arriveAt,
            destinationSigmaSec = dest.sigmaSec,
            overdue = overdue,
        )
    }

    private fun ms(sec: Double): Long = max(0L, Math.round(sec * 1000))
}
