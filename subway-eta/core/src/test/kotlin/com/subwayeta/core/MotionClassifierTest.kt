package com.subwayeta.core

import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.engine.TripSession
import com.subwayeta.core.model.LineParser
import com.subwayeta.core.model.Route
import com.subwayeta.core.motion.MotionClassifier
import com.subwayeta.core.motion.MotionEvent
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 정차/주행을 흉내 낸 합성 센서 신호로 출발·도착 시각 검출 정밀도를 확인한다. */
class MotionClassifierTest {
    private data class Segment(val durationMs: Long, val moving: Boolean)

    private class Simulator(seed: Long) {
        val rnd = Random(seed)

        /** 50Hz 가속도, 50Hz 자기장 샘플을 생성해 [sink]에 넘긴다. */
        fun run(
            start: Long,
            segments: List<Segment>,
            accel: (Long, Double, Double, Double) -> Unit,
            mag: (Long, Double, Double, Double) -> Unit,
        ) {
            var segStart = start
            for (seg in segments) {
                var t = segStart
                while (t < segStart + seg.durationMs) {
                    val s = (t - segStart) / 1000.0
                    val remaining = (segStart + seg.durationMs - t) / 1000.0
                    var ax = rnd.nextGaussian() * 0.012
                    var ay = rnd.nextGaussian() * 0.012
                    var az = 9.81 + rnd.nextGaussian() * 0.012
                    var b = 45.0 + rnd.nextGaussian() * 0.05
                    if (seg.moving) {
                        // 바퀴 진동 + 출발 가속(15초) + 제동(15초) + 모터 자기장 변동
                        ax += rnd.nextGaussian() * 0.12 + 0.08 * sin(2 * PI * 7 * s)
                        az += rnd.nextGaussian() * 0.12
                        if (s < 15) ay += 0.9
                        if (remaining < 15) ay -= 0.9
                        b += 2.5 * sin(2 * PI * 3.3 * s) + rnd.nextGaussian() * 0.6
                    }
                    accel(t, ax, ay, az)
                    mag(t + 3, 0.0, 0.0, b)
                    t += 20
                }
                segStart += seg.durationMs
            }
        }
    }

    @Test
    fun detectsDepartureAndArrivalWithinOneSecond() {
        val c = MotionClassifier()
        val events = mutableListOf<MotionEvent>()
        c.listener = { events += it }
        val t0 = 1_000_000L
        Simulator(1).run(
            t0,
            listOf(Segment(40_000, false), Segment(90_000, true), Segment(30_000, false), Segment(70_000, true), Segment(20_000, false)),
            c::onAccelerometer,
            c::onMagnetometer,
        )
        assertIs<MotionEvent.Initial>(events[0])
        val departures = events.filterIsInstance<MotionEvent.Departed>().map { it.atMillis }
        val arrivals = events.filterIsInstance<MotionEvent.Arrived>().map { it.atMillis }
        assertEquals(2, departures.size, "출발 감지: $events")
        assertEquals(2, arrivals.size, "도착 감지: $events")
        val trueDep = listOf(t0 + 40_000, t0 + 160_000)
        val trueArr = listOf(t0 + 130_000, t0 + 230_000)
        for (i in 0..1) {
            assertTrue(abs(departures[i] - trueDep[i]) <= 1_000, "출발 오차 ${departures[i] - trueDep[i]}ms")
            assertTrue(abs(arrivals[i] - trueArr[i]) <= 1_000, "도착 오차 ${arrivals[i] - trueArr[i]}ms")
        }
    }

    @Test
    fun walkingDoesNotTriggerDeparture() {
        val c = MotionClassifier()
        val events = mutableListOf<MotionEvent>()
        c.listener = { events += it }
        val t0 = 0L
        val sim = Simulator(2)
        sim.run(t0, listOf(Segment(20_000, false)), c::onAccelerometer, c::onMagnetometer)
        // 걸음: 2Hz로 큰 가속도 + 걸음 이벤트
        var t = t0 + 20_000
        while (t < t0 + 50_000) {
            val s = t / 1000.0
            c.onAccelerometer(t, 1.5 * sin(2 * PI * 2 * s), 0.0, 9.81 + 2.0 * sin(2 * PI * 2 * s))
            c.onMagnetometer(t, 0.0, 0.0, 45.0)
            if (t % 500 == 0L) c.onStep(t)
            t += 20
        }
        assertTrue(events.none { it is MotionEvent.Departed }, "걷기를 출발로 오인: $events")
    }

    @Test
    fun rotatingPhoneAtStationDoesNotTriggerDeparture() {
        val c = MotionClassifier()
        val events = mutableListOf<MotionEvent>()
        c.listener = { events += it }
        val rnd = Random(4)
        var t = 0L
        while (t < 60_000) {
            // 30초에 1초 동안 폰을 20도 기울인다.
            val angle = (((t - 30_000).coerceIn(0, 1_000)) / 1000.0) * 0.35
            val rate = if (t in 30_000 until 31_000) 0.35 else 0.0
            c.onAccelerometer(t, rnd.nextGaussian() * 0.012, 9.81 * sin(angle), 9.81 * kotlin.math.cos(angle) + rnd.nextGaussian() * 0.012)
            c.onGyroscope(t, rate, 0.0, 0.0)
            c.onMagnetometer(t, 0.0, 0.0, 45.0 + rnd.nextGaussian() * 0.05)
            t += 20
        }
        assertTrue(events.none { it is MotionEvent.Departed }, "폰 회전을 출발로 오인: $events")
    }

    @Test
    fun endToEndSessionPredictsAndLearns() {
        val line2 = LineParser.parse(File("../app/src/main/assets/lines/line2.txt").readText())
        val route = Route.plan(line2, line2.stations.indexOfFirst { it.name == "강남" }, line2.stations.indexOfFirst { it.name == "서초" })
        val t0 = 1_710_302_400_000L
        val session = TripSession(route, TravelStats(), t0)
        // 강남 정차 30초 → 85초 주행 → 교대 정차 32초 → 72초 주행 → 서초
        Simulator(3).run(
            t0,
            listOf(Segment(30_000, false), Segment(85_000, true), Segment(32_000, false), Segment(72_000, true), Segment(15_000, false)),
            session::onAccelerometer,
            session::onMagnetometer,
        )
        assertIs<Phase.Finished>(session.tracker.phase)
        val arrived = (session.tracker.phase as Phase.Finished).arrivedAt
        val expected = t0 + 30_000 + 85_000 + 32_000 + 72_000
        assertTrue(abs(arrived - expected) <= 1_000, "서초 도착 오차 ${arrived - expected}ms")
        assertTrue(session.stats.sampleCount() >= 3, "학습 표본 ${session.stats.sampleCount()}")
    }
}
