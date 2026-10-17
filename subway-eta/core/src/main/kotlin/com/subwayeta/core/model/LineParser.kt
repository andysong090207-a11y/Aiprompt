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
 * # id,이름,위도,경도,지상(0/1),기본정차초,다음역까지주행초
 * 0201,시청,37.5657,126.9769,0,30,90
 * ```
 *
 * 일반 노선은 마지막 역의 "다음역까지주행초"를 비워 둔다.
 */
object LineParser {
    fun parse(text: String): Line {
        val meta = mutableMapOf<String, String>()
        val stations = mutableListOf<Station>()
        val runs = mutableListOf<Double>()
        text.lineSequence().forEachIndexed { no, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
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
        )
    }
}
