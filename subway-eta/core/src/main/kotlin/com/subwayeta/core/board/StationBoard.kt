package com.subwayeta.core.board

import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.model.Direction
import com.subwayeta.core.model.Headway
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.Route
import com.subwayeta.core.model.ServicePattern
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/** 정보판의 열차 한 대. 시각은 epoch 밀리초. */
data class BoardArrival(
    val line: Line,
    val pattern: ServicePattern,
    /** "인천행", "급행 중앙보훈병원행", "내선순환" 같은 표시 이름. */
    val label: String,
    val arriveAt: Long,
    val departAt: Long,
    /** 이 열차를 타면 지나갈 경로(이 역부터 종착역까지). */
    val route: Route,
)

data class BoardSection(
    val direction: Direction,
    /** "인천 방면·신창 방면" */
    val title: String,
    /** "다음 역 구일" */
    val nextStation: String?,
    val arrivals: List<BoardArrival>,
)

/**
 * 한 역의 도착 정보판. 실시간 정보 없이 운행 패턴(첫차, 막차, 시간대별 배차)과
 * 학습된 구간·정차 시간으로 각 열차가 이 역에 언제 오는지 계산한다.
 *
 * 하루 운행은 새벽 4시에 바뀐다고 보고, 자정 이후 열차는 전날 운행으로 계산한다.
 */
object StationBoard {
    const val SERVICE_DAY_START_HOUR = 4
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** [lines]는 같은 그룹(예: 1호선 두 파일)의 노선들. 이 역이 있는 노선만 쓴다. */
    fun compute(
        lines: List<Line>,
        stationId: String,
        stats: TravelStats,
        now: Long,
        perDirection: Int = 3,
        zone: ZoneId = ZoneId.of("Asia/Seoul"),
    ): List<BoardSection> {
        // 오늘 운행이 끝났으면(새벽) 다음 운행일 첫차까지 보여 준다.
        val days = listOf(serviceDay(now, zone), serviceDay(now + DAY_MS, zone))
        val byDirection = Direction.entries.associateWith { mutableListOf<BoardArrival>() }
        val titles = Direction.entries.associateWith { linkedSetOf<String>() }
        val nextNames = Direction.entries.associateWith { linkedSetOf<String>() }

        for (line in lines) {
            val idx = line.indexOf(stationId)
            if (idx < 0) continue
            for (dir in Direction.entries) {
                neighbor(line, idx, dir)?.let {
                    titles.getValue(dir) += line.directionName(dir)
                    nextNames.getValue(dir) += line.stations[it].name
                }
            }
            for (pattern in line.services) {
                val full = patternRoute(line, pattern) ?: continue
                val k = full.stationIdx.indexOf(idx)
                if (k < 0 || (k == full.lastK && !pattern.isLoop)) continue
                val offsets = offsetsMs(full, k, stats, now)
                val label = pattern.label ?: "${line.stations[line.indexOf(pattern.terminusId)].name}행"
                val shownLabel = if (pattern.express) "급행 $label" else label
                val ride = rideRoute(line, pattern, full, k)
                // 이 역을 아직 떠나지 않은 열차 중 가장 이른 것부터 perDirection대.
                var found = 0
                val deps = days.asSequence().flatMap { (dayStart, weekend) ->
                    departures(pattern, if (weekend) pattern.weekend else pattern.weekday).map { dayStart to it }
                }
                for ((dayStart, depMin) in deps) {
                    val originDep = dayStart + (depMin * 60_000).roundToLong()
                    val departAt = originDep + offsets.second
                    if (departAt < now) continue
                    byDirection.getValue(pattern.direction) += BoardArrival(
                        line = line,
                        pattern = pattern,
                        label = shownLabel,
                        arriveAt = originDep + offsets.first,
                        departAt = departAt,
                        route = ride,
                    )
                    if (++found >= perDirection) break
                }
            }
        }
        return Direction.entries.mapNotNull { dir ->
            val title = titles.getValue(dir)
            if (title.isEmpty()) return@mapNotNull null
            BoardSection(
                direction = dir,
                title = title.joinToString("·"),
                nextStation = nextNames.getValue(dir).joinToString("·").ifEmpty { null },
                arrivals = byDirection.getValue(dir).sortedBy { it.arriveAt }.take(perDirection),
            )
        }
    }

    /** (서비스일 0시의 epoch 밀리초, 주말 여부) */
    fun serviceDay(now: Long, zone: ZoneId): Pair<Long, Boolean> {
        val shifted = Instant.ofEpochMilli(now).atZone(zone).minusHours(SERVICE_DAY_START_HOUR.toLong())
        val date = shifted.toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return start to weekend
    }

    /** 운행 패턴 전체 경로(출발역 → 종착역). 순환 열차는 한 바퀴. */
    fun patternRoute(line: Line, p: ServicePattern): Route? {
        val o = line.indexOf(p.originId)
        var t = line.indexOf(p.terminusId)
        if (p.isLoop) {
            if (!line.circular) return null
            val n = line.stations.size
            t = Math.floorMod(o + if (p.direction == Direction.FORWARD) -1 else 1, n)
        }
        return runCatching { Route.plan(line, o, t, p.direction, p.express) }.getOrNull()
    }

    /** 이 역(k)에서 타면 가는 경로. 순환 열차는 한 바퀴 돌아 바로 앞 역까지. */
    private fun rideRoute(line: Line, p: ServicePattern, full: Route, k: Int): Route {
        val from = full.stationIdx[k]
        val to = if (p.isLoop) {
            val n = line.stations.size
            Math.floorMod(from + if (p.direction == Direction.FORWARD) -1 else 1, n)
        } else {
            full.stationIdx.last()
        }
        return Route.plan(line, from, to, p.direction, p.express)
    }

    /** 출발역 출발 시각 기준 k번째 역 (도착, 출발)까지 걸리는 밀리초. */
    private fun offsetsMs(route: Route, k: Int, stats: TravelStats, now: Long): Pair<Long, Long> {
        if (k == 0) return 0L to 0L
        var t = 0.0
        for (i in 0 until k) {
            t += stats.run(route, i, now).meanSec
            if (i + 1 < k) t += stats.dwell(route, i + 1, now).meanSec
        }
        val arrive = (t * 1000).roundToLong()
        return arrive to arrive + (stats.dwell(route, k, now).meanSec * 1000).roundToLong()
    }

    /** 출발역 출발 시각(서비스일 0시 기준 분)을 차례로 만든다. */
    fun departures(p: ServicePattern, headways: List<Headway>): Sequence<Double> = sequence {
        for (h in headways.sortedBy { it.startMin }) {
            var t = maxOf(h.startMin, p.firstDepMin).toDouble()
            while (t < h.endMin && t <= p.lastDepMin) {
                yield(t)
                t += h.minutes
            }
        }
    }

    private fun neighbor(line: Line, idx: Int, dir: Direction): Int? {
        val n = line.stations.size
        val j = idx + if (dir == Direction.FORWARD) 1 else -1
        return when {
            j in 0 until n -> j
            line.circular -> Math.floorMod(j, n)
            else -> null
        }
    }
}
