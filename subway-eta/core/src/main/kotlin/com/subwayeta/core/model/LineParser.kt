package com.subwayeta.core.model

/**
 * 노선 파일 형식 (assets/lines 폴더의 .txt 파일):
 *
 * ```
 * @id=2
 * @name=2호선
 * @color=#00A84D
 * @circular=true
 * @forward=내선순환
 * @backward=외선순환
 * # id,이름,위도,경도,지상(0/1),기본정차초,다음역까지주행초,급행정차(0/1, 생략 가능)
 * 0201,시청,37.5657,126.9769,0,30,90
 * ```
 *
 * 일반 노선은 마지막 역의 "다음역까지주행초"를 비워 둔다.
 *
 * 운행 패턴은 "!"로 시작한다(시각은 HH:mm, 자정 넘으면 24:30처럼 쓴다).
 *
 * ```
 * # !방향(F/B),출발역id,종착역id,급행(0/1),표시이름(비우면 "종착역행"),첫차,막차,평일 배차,주말 배차
 * !F,0201,0201,0,내선순환,05:30,24:00,05:30-07:00=6;07:00-09:00=2.5;09:00-24:30=5,05:30-24:30=6
 * ```
 */
object LineParser {
    fun parse(text: String): Line {
        val meta = mutableMapOf<String, String>()
        val stations = mutableListOf<Station>()
        val runs = mutableListOf<Double>()
        val services = mutableListOf<ServicePattern>()
        text.lineSequence().forEachIndexed { no, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("!") -> services += parseService(line.drop(1), no + 1)
                line.startsWith("@") -> {
                    val (k, v) = line.drop(1).split("=", limit = 2).also {
                        require(it.size == 2) { "${no + 1}행: '@키=값' 형식이 아님" }
                    }
                    meta[k.trim()] = v.trim()
                }
                else -> {
                    val c = line.split(",").map { it.trim() }
                    require(c.size >= 6) { "${no + 1}행: 열이 부족함 ($line)" }
                    stations += Station(
                        id = c[0],
                        name = c[1],
                        lat = c[2].toDouble(),
                        lon = c[3].toDouble(),
                        aboveGround = c[4] == "1",
                        defaultDwellSec = c[5].toDouble(),
                        expressStop = c.getOrNull(7) == "1",
                    )
                    c.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let { runs += it.toDouble() }
                }
            }
        }
        return Line(
            id = meta["id"] ?: error("@id 없음"),
            name = meta["name"] ?: meta.getValue("id"),
            colorHex = meta["color"] ?: "#888888",
            circular = meta["circular"] == "true",
            forwardName = meta["forward"] ?: "상행",
            backwardName = meta["backward"] ?: "하행",
            stations = stations,
            runToNextSec = runs,
            group = meta["group"] ?: meta["name"] ?: meta.getValue("id"),
            services = services,
        ).also { line ->
            for (s in line.services) {
                require(line.indexOf(s.originId) >= 0 && line.indexOf(s.terminusId) >= 0) {
                    "노선 ${line.id}: 운행 패턴의 역 id가 없음 (${s.originId}, ${s.terminusId})"
                }
            }
        }
    }

    private fun parseService(text: String, lineNo: Int): ServicePattern {
        val c = text.split(",").map { it.trim() }
        require(c.size == 9) { "${lineNo}행: 운행 패턴은 9개 열이어야 함" }
        return ServicePattern(
            direction = if (c[0] == "F") Direction.FORWARD else Direction.BACKWARD,
            originId = c[1],
            terminusId = c[2],
            express = c[3] == "1",
            label = c[4].ifEmpty { null },
            firstDepMin = minutes(c[5]),
            lastDepMin = minutes(c[6]),
            weekday = headways(c[7]),
            weekend = headways(c[8]),
        )
    }

    private fun headways(text: String): List<Headway> = text.split(";").filter { it.isNotBlank() }.map { part ->
        val (range, every) = part.split("=")
        val (a, b) = range.split("-")
        Headway(minutes(a), minutes(b), every.toDouble())
    }

    private fun minutes(hhmm: String): Int {
        val (h, m) = hhmm.split(":").map { it.trim().toInt() }
        return h * 60 + m
    }
}
