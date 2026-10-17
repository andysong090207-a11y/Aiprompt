package com.subwayeta.core.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** 지상(고가) 구간 역이면 true. GPS 보정을 이 근처에서만 켠다. */
    val aboveGround: Boolean,
    /** 시간표 기준 기본 정차 시간(초). 실측으로 학습되기 전까지 쓰인다. */
    val defaultDwellSec: Double,
    /** 급행 열차가 서는 역이면 true. */
    val expressStop: Boolean = false,
)

enum class Direction { FORWARD, BACKWARD }

/**
 * 한 노선. [runToNextSec]의 i번째 값은 stations[i] → stations[i+1] 의 기본 주행 시간(초)이고,
 * 순환선이면 마지막 값이 마지막 역 → 첫 역 구간이다. 반대 방향도 같은 값을 쓴다.
 */
class Line(
    val id: String,
    val name: String,
    val colorHex: String,
    val circular: Boolean,
    val forwardName: String,
    val backwardName: String,
    val stations: List<Station>,
    private val runToNextSec: List<Double>,
) {
    init {
        require(stations.size >= 2) { "노선 $id: 역이 2개 이상 필요" }
        val expected = if (circular) stations.size else stations.size - 1
        require(runToNextSec.size == expected) { "노선 $id: 구간 시간 개수 ${runToNextSec.size} != $expected" }
    }

    val hasExpress: Boolean get() = stations.any { it.expressStop }

    fun indexOf(stationId: String): Int = stations.indexOfFirst { it.id == stationId }

    fun directionName(direction: Direction) = if (direction == Direction.FORWARD) forwardName else backwardName

    /** 인접한 두 역 사이 기본 주행 시간(초). */
    fun defaultRunSec(fromIdx: Int, toIdx: Int): Double {
        val n = stations.size
        return when {
            toIdx == fromIdx + 1 -> runToNextSec[fromIdx]
            toIdx == fromIdx - 1 -> runToNextSec[toIdx]
            circular && fromIdx == n - 1 && toIdx == 0 -> runToNextSec[n - 1]
            circular && fromIdx == 0 && toIdx == n - 1 -> runToNextSec[n - 1]
            else -> throw IllegalArgumentException("인접하지 않은 역: $fromIdx → $toIdx")
        }
    }
}

/**
 * 출발역부터 도착역까지 열차가 서는 역 순서. 급행이면 급행 정차역만 들어 있다.
 * [segmentRunSec]의 k번째 값은 k번째 역 → k+1번째 역의 기본 주행 시간(초)이다.
 */
class Route(
    val line: Line,
    val direction: Direction,
    val stationIdx: List<Int>,
    val express: Boolean,
    private val segmentRunSec: List<Double>,
) {
    init {
        require(segmentRunSec.size == stationIdx.size - 1)
    }

    val stations: List<Station> get() = stationIdx.map { line.stations[it] }
    val lastK: Int get() = stationIdx.size - 1

    fun station(k: Int): Station = line.stations[stationIdx[k]]

    /** k번째 역 → k+1번째 역의 기본 주행 시간(초). */
    fun defaultRunSec(k: Int): Double = segmentRunSec[k]

    companion object {
        /** 급행이 통과하는 역 하나마다 줄어드는 시간(초): 감속·가속을 안 하는 만큼. */
        const val EXPRESS_PASS_SAVING_SEC = 15.0

        /**
         * [direction]이 null이면 순환선은 정거장 수가 적은 쪽, 일반 노선은 진행 방향으로 정한다.
         * [express]면 출발역과 도착역이 모두 급행 정차역이어야 한다.
         */
        fun plan(line: Line, fromIdx: Int, toIdx: Int, direction: Direction? = null, express: Boolean = false): Route {
            require(fromIdx != toIdx) { "출발역과 도착역이 같음" }
            if (express) {
                require(line.stations[fromIdx].expressStop && line.stations[toIdx].expressStop) {
                    "급행은 급행 정차역끼리만 고를 수 있음"
                }
            }
            val n = line.stations.size
            val dir = direction ?: if (line.circular) {
                val fwd = Math.floorMod(toIdx - fromIdx, n)
                if (fwd <= n - fwd) Direction.FORWARD else Direction.BACKWARD
            } else {
                if (toIdx > fromIdx) Direction.FORWARD else Direction.BACKWARD
            }
            val step = if (dir == Direction.FORWARD) 1 else -1
            val idx = mutableListOf(fromIdx)
            var i = fromIdx
            while (i != toIdx) {
                i += step
                if (line.circular) i = Math.floorMod(i, n)
                require(i in 0 until n) { "${line.name}: 이 방향으로는 도착역에 갈 수 없음" }
                idx += i
            }
            if (!express) {
                return Route(line, dir, idx, false, idx.zipWithNext { a, b -> line.defaultRunSec(a, b) })
            }
            val stops = mutableListOf(idx.first())
            val runs = mutableListOf<Double>()
            var sum = 0.0
            var passed = 0
            for ((a, b) in idx.zipWithNext()) {
                sum += line.defaultRunSec(a, b)
                if (line.stations[b].expressStop) {
                    stops += b
                    runs += maxOf(sum * 0.6, sum - passed * EXPRESS_PASS_SAVING_SEC)
                    sum = 0.0
                    passed = 0
                } else {
                    passed++
                }
            }
            return Route(line, dir, stops, true, runs)
        }
    }
}

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
