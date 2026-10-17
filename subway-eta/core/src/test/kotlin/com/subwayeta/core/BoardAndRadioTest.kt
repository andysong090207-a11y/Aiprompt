package com.subwayeta.core

import com.subwayeta.core.board.StationBoard
import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.engine.TripSession
import com.subwayeta.core.model.Direction
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.LineParser
import com.subwayeta.core.model.Route
import com.subwayeta.core.radio.RadioFingerprints
import com.subwayeta.core.radio.RadioObservation
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoardAndRadioTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val lines: List<Line> = File("../app/src/main/assets/lines")
        .listFiles { f -> f.name.endsWith(".txt") }!!
        .sortedBy { it.name }
        .map { LineParser.parse(it.readText()) }

    private fun group(name: String) = lines.filter { it.group == name }
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun everyLineHasServicePatternsAndTheyRoute() {
        for (line in lines) {
            assertTrue(line.services.isNotEmpty(), "${line.id}: 운행 패턴 없음")
            for (p in line.services) assertTrue(StationBoard.patternRoute(line, p) != null, "${line.id}: $p")
        }
        assertEquals(2, group("1호선").size)
    }

    @Test
    fun boardShowsBothDirectionsWithSecondsAndLabels() {
        val l2 = group("2호선")
        val gangnam = l2.single().stations.first { it.name == "강남" }.id
        val now = at(2026, 10, 14, 13, 0, 7) // 수요일 낮
        val board = StationBoard.compute(l2, gangnam, TravelStats(), now)
        assertEquals(2, board.size)
        val inner = board.first { it.direction == Direction.FORWARD }
        assertEquals("내선순환", inner.title)
        assertEquals("교대", inner.nextStation)
        assertEquals(3, inner.arrivals.size)
        assertTrue(inner.arrivals.all { it.label == "내선순환" && it.departAt >= now })
        // 낮 배차 5분: 연속 열차 간격이 5분
        assertEquals(5 * 60_000L, inner.arrivals[1].arriveAt - inner.arrivals[0].arriveAt)
        // 타면 한 바퀴(42정거장) 갈 수 있다.
        assertEquals(42, inner.arrivals[0].route.lastK)
        assertEquals("강남", inner.arrivals[0].route.station(0).name)
    }

    @Test
    fun trunkStationMergesBothLine1Branches() {
        val g1 = group("1호선")
        val seoul = g1.first().stations.first { it.name == "서울역" }.id
        val board = StationBoard.compute(g1, seoul, TravelStats(), at(2026, 10, 14, 13, 0), perDirection = 6)
        val down = board.first { it.direction == Direction.FORWARD }
        assertEquals(setOf("인천 방면", "신창 방면"), down.title.split("·").toSet())
        val labels = down.arrivals.map { it.label }.toSet()
        assertTrue("인천행" in labels && ("천안행" in labels || "병점행" in labels), "$labels")
    }

    @Test
    fun noTrainsBeforeFirstTrainAndExpressOnlyAtExpressStops() {
        val l9 = group("9호선")
        val gongang = l9.single().stations.first { it.name == "공항시장" }.id
        val yeouido = l9.single().stations.first { it.name == "여의도" }.id
        val noon = at(2026, 10, 14, 12, 0)
        val local = StationBoard.compute(l9, gongang, TravelStats(), noon)
        assertTrue(local.flatMap { it.arrivals }.none { it.pattern.express })
        val ex = StationBoard.compute(l9, yeouido, TravelStats(), noon, perDirection = 6)
        assertTrue(ex.flatMap { it.arrivals }.any { it.label.startsWith("급행") })

        // 새벽 3시: 전날 운행은 끝났고 첫차는 아직 → 오늘 첫차가 보여야 한다.
        val night = StationBoard.compute(l9, yeouido, TravelStats(), at(2026, 10, 14, 3, 0))
        val first = night.flatMap { it.arrivals }.minOf { it.arriveAt }
        assertTrue(first > at(2026, 10, 14, 5, 30), "첫차 이전에는 열차가 없어야 함")
    }

    @Test
    fun ssidWithStationNameMatches() {
        assertTrue(RadioFingerprints.nameMatches("gs25강남역점", "강남"))
        assertTrue(RadioFingerprints.nameMatches("seoulstationwifi서울역", "서울역"))
        assertTrue(!RadioFingerprints.nameMatches("seoulmetro", "강남"))
        // 두 글자 이름은 "역"까지 붙어야 일치(오검출 방지)
        assertTrue(!RadioFingerprints.nameMatches("이대앞카페", "이대"))
    }

    @Test
    fun radioFingerprintCorrectsWrongStation() {
        val l2 = group("2호선").single()
        val route = Route.plan(l2, l2.stations.indexOfFirst { it.name == "강남" }, l2.stations.indexOfFirst { it.name == "사당" })
        val fps = RadioFingerprints()
        // 앞선 이동에서 학습된 역별 신호
        fun sig(name: String, extra: Int = 0) = RadioObservation(
            0,
            setOf("lte:450:${name.hashCode()}", "lte:450:common"),
            mapOf("aa:${name.hashCode()}:1" to "Metro_$name", "bb:00:${extra}" to "Public WiFi"),
        )
        repeat(5) { for (s in route.stations) fps.learn(s.id, sig(s.name, it)) }

        val t0 = 1_710_302_400_000L
        val session = TripSession(route, TravelStats(), t0, fingerprints = fps)
        // 센서로 정차 상태 만들기: 정지 신호 10초
        var t = t0
        while (t < t0 + 10_000) { session.onAccelerometer(t, 0.0, 0.0, 9.81); t += 20 }
        assertIs<Phase.AtStation>(session.tracker.phase)
        // 앱은 강남(0)에 있다고 알지만 실제로는 서초(2)의 신호가 잡힌다 → 두 번 연속이면 보정
        val seocho = sig("서초", 9)
        assertTrue(!session.onRadio(seocho.copy(atMillis = t)))
        assertTrue(session.onRadio(seocho.copy(atMillis = t + 10_000)))
        assertEquals(2, (session.tracker.phase as Phase.AtStation).k)
        assertEquals("서초", session.snapshot(t).radio?.best?.station?.name)
    }

    @Test
    fun fingerprintsRoundTrip() {
        val f = RadioFingerprints()
        f.learn("0222", RadioObservation(0, setOf("lte:1"), mapOf("aa:bb" to "x")))
        val g = RadioFingerprints().apply { load(f.serialize()) }
        assertEquals(1, g.observationCount("0222"))
        assertEquals(f.serialize(), g.serialize())
    }
}
