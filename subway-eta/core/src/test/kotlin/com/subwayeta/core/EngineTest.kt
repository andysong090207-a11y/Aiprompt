package com.subwayeta.core

import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.Predictor
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.engine.TripTracker
import com.subwayeta.core.model.Direction
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.LineParser
import com.subwayeta.core.model.Route
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineTest {
    private val line2: Line = LineParser.parse(File("../app/src/main/assets/lines/line2.txt").readText())

    // 2024-03-13(수) 13:00 KST, 비혼잡 시간대
    private val t0 = 1_710_302_400_000L

    private fun route(from: String, to: String, dir: Direction? = null) =
        Route.plan(line2, line2.stations.indexOfFirst { it.name == from }, line2.stations.indexOfFirst { it.name == to }, dir)

    @Test
    fun parsesLine2() {
        assertEquals(43, line2.stations.size)
        assertTrue(line2.circular)
        assertEquals("내선순환", line2.forwardName)
    }

    @Test
    fun plansShortestDirectionOnCircularLine() {
        val r = route("시청", "충정로")
        assertEquals(Direction.BACKWARD, r.direction)
        assertEquals(listOf("시청", "충정로"), r.stations.map { it.name })

        val r2 = route("강남", "신도림")
        assertEquals(Direction.FORWARD, r2.direction)
        assertEquals("교대", r2.station(1).name)

        val wrap = route("충정로", "을지로입구", Direction.FORWARD)
        assertEquals(listOf("충정로", "시청", "을지로입구"), wrap.stations.map { it.name })
        assertEquals(100.0, wrap.defaultRunSec(0))
    }

    @Test
    fun predictsArrivalToTheSecond() {
        val r = route("강남", "서초") // 강남 -(90)-> 교대(dwell 35) -(70)-> 서초
        val stats = TravelStats()
        val p = Predictor.predict(r, stats, Phase.Running(0, t0), t0 + 10_000)
        assertEquals(t0 + 90_000, p.nextArrivalAt)
        assertEquals(t0 + 90_000 + 35_000 + 70_000, p.destinationArrivalAt)
        assertEquals(35.0, p.stations[1].dwellSec)

        val atStation = Predictor.predict(r, stats, Phase.AtStation(1, t0), t0 + 5_000)
        assertEquals(t0 + 35_000, atStation.departureAt)
        assertEquals(t0 + 35_000 + 70_000, atStation.destinationArrivalAt)
    }

    @Test
    fun overdueArrivalIsPushedToNearFuture() {
        val r = route("강남", "서초")
        val p = Predictor.predict(r, TravelStats(), Phase.Running(0, t0), t0 + 120_000)
        assertTrue(p.overdue)
        assertEquals(t0 + 120_000 + Predictor.OVERDUE_MARGIN_MS, p.nextArrivalAt)
    }

    @Test
    fun tracksStationsAndLearns() {
        val r = route("강남", "사당")
        val stats = TravelStats()
        val tr = TripTracker(r, stats, t0)
        tr.onDeparted(t0)
        tr.onArrived(t0 + 84_000) // 교대 (예상 90초)
        assertEquals(Phase.AtStation(1, t0 + 84_000), tr.phase)
        tr.onDeparted(t0 + 84_000 + 31_000)
        val learnedRun = stats.run(r, 0, t0).meanSec
        assertTrue(learnedRun in 84.0..90.0, "학습된 구간 시간 $learnedRun")
        val learnedDwell = stats.dwell(r, 1, t0).meanSec
        assertTrue(learnedDwell in 31.0..35.0, "학습된 정차 시간 $learnedDwell")
    }

    @Test
    fun earlyStopInTunnelIsTreatedAsSignalHold() {
        val r = route("강남", "서초")
        val tr = TripTracker(r, TravelStats(), t0)
        tr.onDeparted(t0)
        tr.onArrived(t0 + 30_000) // 90초 구간을 30초만 달리고 멈춤
        val held = assertIs<Phase.Running>(tr.phase)
        assertNotNull(held.holdingSince)
        tr.onDeparted(t0 + 50_000)
        assertEquals(20_000, assertIs<Phase.Running>(tr.phase).holdMs)
        // 신호 대기 20초만큼 도착 예측이 늦어진다.
        val p = Predictor.predict(r, TravelStats(), tr.phase, t0 + 55_000)
        assertEquals(t0 + 110_000, p.nextArrivalAt)
        tr.onArrived(t0 + 112_000)
        assertEquals(1, assertIs<Phase.AtStation>(tr.phase).k)
    }

    @Test
    fun missedStopIsRecoveredByTiming() {
        val r = route("강남", "방배") // 강남 90 교대 35 70 서초 25 90 방배
        val tr = TripTracker(r, TravelStats(), t0)
        tr.onDeparted(t0)
        tr.onArrived(t0 + 196_000) // 교대 정차를 놓치고 서초에서야 감지 (90+35+70=195)
        assertEquals(2, assertIs<Phase.AtStation>(tr.phase).k)
    }

    @Test
    fun finishesAtDestination() {
        val r = route("강남", "교대")
        val tr = TripTracker(r, TravelStats(), t0)
        tr.onDeparted(t0)
        tr.onArrived(t0 + 88_000)
        assertEquals(Phase.Finished(t0 + 88_000), tr.phase)
    }

    @Test
    fun gpsSnapsToNearbyStationWhenStopped() {
        val r = route("왕십리", "강변")
        val tr = TripTracker(r, TravelStats(), t0)
        val ttukseom = r.stations.indexOfFirst { it.name == "뚝섬" }
        assertTrue(tr.onLocation(37.5473, 127.0475, 20.0, t0, stationary = true))
        assertEquals(ttukseom, assertIs<Phase.AtStation>(tr.phase).k)
        // 부정확한 위치나 움직이는 중에는 보정하지 않는다.
        assertTrue(!tr.onLocation(37.5446, 127.0559, 200.0, t0, stationary = true))
        assertTrue(!tr.onLocation(37.5446, 127.0559, 10.0, t0, stationary = false))
    }

    @Test
    fun statsRoundTrip() {
        val r = route("강남", "교대")
        val s = TravelStats()
        repeat(5) { s.recordRun(r, 0, t0, 80.0 + it) }
        val copy = TravelStats().apply { load(s.serialize()) }
        assertEquals(s.run(r, 0, t0), copy.run(r, 0, t0))
        // 말도 안 되는 값은 버린다.
        assertTrue(!s.recordRun(r, 0, t0, 5.0))
    }
}
