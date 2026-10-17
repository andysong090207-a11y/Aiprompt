package com.subwayeta.core

import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.Predictor
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.model.Direction
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.LineParser
import com.subwayeta.core.model.Route
import com.subwayeta.core.model.distanceMeters
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** assets/lines 의 모든 노선 파일을 검사한다. */
class LinesDataTest {
    private val lines: Map<String, Line> = File("../app/src/main/assets/lines")
        .listFiles { f -> f.name.endsWith(".txt") }!!
        .associate { it.name to LineParser.parse(it.readText()) }

    private fun Line.idx(name: String) = stations.indexOfFirst { it.name == name }.also { require(it >= 0) { name } }

    @Test
    fun allLinesAreConsistent() {
        assertEquals(
            setOf("line1_gyeongbu.txt", "line1_gyeongin.txt", "line2.txt", "line7.txt", "line9.txt"),
            lines.keys,
        )
        assertEquals(lines.size, lines.values.map { it.id }.toSet().size, "노선 id 중복")
        for ((file, line) in lines) {
            assertEquals(line.stations.size, line.stations.map { it.name }.toSet().size, "$file: 역 이름 중복")
            assertEquals(line.stations.size, line.stations.map { it.id }.toSet().size, "$file: 역 id 중복")
            line.stations.zipWithNext().forEach { (a, b) ->
                val d = distanceMeters(a.lat, a.lon, b.lat, b.lon)
                // 좌표 오타를 잡는다: 인접 역은 200m~10km 안에 있어야 한다.
                assertTrue(d in 200.0..10_000.0, "$file: ${a.name}–${b.name} ${d.toInt()}m")
            }
            // 처음부터 끝까지 가는 경로를 만들 수 있어야 한다.
            val r = Route.plan(line, 0, line.stations.lastIndex, Direction.FORWARD)
            (0 until r.lastK).forEach { assertTrue(r.defaultRunSec(it) in 40.0..600.0, "$file: 구간 $it ${r.defaultRunSec(it)}초") }
        }
    }

    @Test
    fun stationCounts() {
        assertEquals(62, lines.getValue("line1_gyeongin.txt").stations.size)
        assertEquals(77, lines.getValue("line1_gyeongbu.txt").stations.size)
        assertEquals(53, lines.getValue("line7.txt").stations.size)
        assertEquals(38, lines.getValue("line9.txt").stations.size)
    }

    @Test
    fun line1BranchesShareTrunkStationIds() {
        val gi = lines.getValue("line1_gyeongin.txt")
        val gb = lines.getValue("line1_gyeongbu.txt")
        val trunk = gi.stations.take(gi.idx("구로") + 1)
        assertEquals(trunk, gb.stations.take(gb.idx("구로") + 1))
        assertEquals("인천", gi.stations.last().name)
        assertEquals("신창", gb.stations.last().name)
        assertTrue(!gi.stations[gi.idx("서울역")].aboveGround)
        assertTrue(gi.stations[gi.idx("부평")].aboveGround)
    }

    @Test
    fun line9ExpressSkipsLocalStations() {
        val l9 = lines.getValue("line9.txt")
        assertTrue(l9.hasExpress)
        val local = Route.plan(l9, l9.idx("김포공항"), l9.idx("고속터미널"))
        val express = Route.plan(l9, l9.idx("김포공항"), l9.idx("고속터미널"), express = true)
        assertEquals(
            listOf("김포공항", "마곡나루", "가양", "염창", "당산", "여의도", "노량진", "동작", "고속터미널"),
            express.stations.map { it.name },
        )
        assertTrue(local.lastK > express.lastK)

        val stats = TravelStats()
        val t = 1_710_302_400_000L
        val localEta = Predictor.predict(local, stats, Phase.Running(0, t), t).destinationArrivalAt!!
        val expressEta = Predictor.predict(express, stats, Phase.Running(0, t), t).destinationArrivalAt!!
        val savedMin = (localEta - expressEta) / 60_000.0
        assertTrue(savedMin in 5.0..20.0, "급행이 줄이는 시간 ${"%.1f".format(savedMin)}분")

        // 급행이 서지 않는 역은 급행으로 고를 수 없다.
        assertFailsWith<IllegalArgumentException> { Route.plan(l9, l9.idx("공항시장"), l9.idx("여의도"), express = true) }
    }

    @Test
    fun line7AndBackwardTrip() {
        val l7 = lines.getValue("line7.txt")
        val r = Route.plan(l7, l7.idx("고속터미널"), l7.idx("건대입구"))
        assertEquals(Direction.BACKWARD, r.direction)
        assertEquals(listOf("고속터미널", "반포", "논현", "학동", "강남구청", "청담", "뚝섬유원지", "건대입구"), r.stations.map { it.name })
    }
}
