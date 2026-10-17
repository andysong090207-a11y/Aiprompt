package com.subwayeta.core.engine

import com.subwayeta.core.model.Route
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.sqrt

/** 평균과 표준편차(초). */
data class Estimate(val meanSec: Double, val sigmaSec: Double)

/** 지수가중 이동평균으로 평균·분산을 추적한다. 처음 몇 번은 단순 평균에 가깝게 동작한다. */
class RunningStat(var mean: Double = 0.0, var variance: Double = 0.0, var n: Int = 0) {
    fun add(x: Double) {
        n++
        if (n == 1) {
            mean = x
            variance = 0.0
            return
        }
        val alpha = max(1.0 / n, MIN_ALPHA)
        val d = x - mean
        mean += alpha * d
        variance = (1 - alpha) * (variance + alpha * d * d)
    }

    companion object {
        /** 최근 값을 얼마나 빨리 반영할지. 0.15면 대략 최근 10~15회가 주로 반영된다. */
        const val MIN_ALPHA = 0.15
    }
}

/**
 * 구간 주행 시간과 역별 정차 시간을 실측으로 학습해 저장한다.
 * 출퇴근 시간대(peak)와 그 외(off)를 따로 학습하고, 데이터가 적으면 전체 값 → 기본값 순으로 쓴다.
 * 추적 서비스와 도착 정보판 화면이 같이 쓰므로 스레드 안전하다.
 */
class TravelStats(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    private val stats = HashMap<String, RunningStat>()

    @Synchronized
    fun run(route: Route, k: Int, atMillis: Long): Estimate =
        estimate(runKey(route, k), atMillis, route.defaultRunSec(k), RUN_PRIOR_FRACTION)

    @Synchronized
    fun dwell(route: Route, k: Int, atMillis: Long): Estimate =
        estimate(dwellKey(route, k), atMillis, route.station(k).defaultDwellSec, DWELL_PRIOR_FRACTION)

    /** 이상치(센서 오인식)는 버린다. 반영하면 true. */
    @Synchronized
    fun recordRun(route: Route, k: Int, atMillis: Long, sec: Double): Boolean =
        record(runKey(route, k), atMillis, sec, route.defaultRunSec(k), 0.4, 2.5)

    @Synchronized
    fun recordDwell(route: Route, k: Int, atMillis: Long, sec: Double): Boolean =
        record(dwellKey(route, k), atMillis, sec, route.station(k).defaultDwellSec, 0.2, 6.0)

    @Synchronized
    fun sampleCount(): Int = stats.values.sumOf { it.n }

    private fun record(key: String, at: Long, sec: Double, default: Double, minF: Double, maxF: Double): Boolean {
        if (sec < default * minF || sec > default * maxF) return false
        stats.getOrPut("$key|${bucket(at)}") { RunningStat() }.add(sec)
        stats.getOrPut(key) { RunningStat() }.add(sec)
        return true
    }

    private fun estimate(key: String, at: Long, default: Double, priorFraction: Double): Estimate {
        val s = stats["$key|${bucket(at)}"]?.takeIf { it.n >= MIN_BUCKET_SAMPLES } ?: stats[key]
        val priorSigma = default * priorFraction
        if (s == null || s.n == 0) return Estimate(default, priorSigma)
        // 기본값을 PRIOR_WEIGHT회 관측한 것처럼 섞는다.
        val w = s.n / (s.n + PRIOR_WEIGHT)
        val mean = w * s.mean + (1 - w) * default
        val sigma = w * sqrt(s.variance) + (1 - w) * priorSigma
        return Estimate(mean, max(sigma, MIN_SIGMA_SEC))
    }

    private fun bucket(atMillis: Long): String {
        val t = Instant.ofEpochMilli(atMillis).atZone(zone)
        val weekday = t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY
        val peak = weekday && (t.hour in 7..8 || t.hour in 18..19)
        return if (peak) "peak" else "off"
    }

    private fun runKey(route: Route, k: Int) =
        "run:${route.line.id}:${route.station(k).id}>${route.station(k + 1).id}"

    private fun dwellKey(route: Route, k: Int) =
        "dwell:${route.line.id}:${route.station(k).id}:${route.direction}"

    /** 탭으로 구분한 텍스트로 저장/복원한다. */
    @Synchronized
    fun serialize(): String = buildString {
        for ((k, s) in stats) append(k).append('\t').append(s.mean).append('\t').append(s.variance).append('\t').append(s.n).append('\n')
    }

    @Synchronized
    fun load(text: String) {
        stats.clear()
        for (line in text.lineSequence()) {
            val c = line.split('\t')
            if (c.size != 4) continue
            val mean = c[1].toDoubleOrNull() ?: continue
            val variance = c[2].toDoubleOrNull() ?: continue
            val n = c[3].toIntOrNull() ?: continue
            stats[c[0]] = RunningStat(mean, variance, n)
        }
    }

    companion object {
        const val MIN_BUCKET_SAMPLES = 3
        const val PRIOR_WEIGHT = 2.0
        const val RUN_PRIOR_FRACTION = 0.15
        const val DWELL_PRIOR_FRACTION = 0.35
        const val MIN_SIGMA_SEC = 1.5
    }
}
