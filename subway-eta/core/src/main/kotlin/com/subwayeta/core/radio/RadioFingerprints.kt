package com.subwayeta.core.radio

import com.subwayeta.core.model.Station

/**
 * 한 번 스캔한 무선 신호.
 * [cells]는 "lte:450:12345678" 같은 기지국 식별자, [wifi]는 BSSID → SSID.
 */
data class RadioObservation(
    val atMillis: Long,
    val cells: Set<String>,
    val wifi: Map<String, String>,
) {
    val isEmpty: Boolean get() = cells.isEmpty() && wifi.isEmpty()
}

data class RadioMatch(val station: Station, val score: Double, val nameMatched: Boolean)

/**
 * 역마다 보이는 기지국·Wi-Fi를 학습해 두었다가, 새로 스캔한 신호가 어느 역과 닮았는지 점수를 매긴다.
 *
 * - 여러 역에서 공통으로 보이는 신호(통신사 공용 Wi-Fi 등)는 가중치를 낮춘다(역문서빈도 방식).
 * - SSID에 역 이름이 들어 있으면(예: "GS25_강남역점") 학습 전이라도 가산점을 준다.
 * - 스레드 안전하다.
 */
class RadioFingerprints {
    private class Fp(var n: Int = 0, val counts: HashMap<String, Int> = HashMap())

    private val byStation = HashMap<String, Fp>()

    @Synchronized
    fun learn(stationId: String, obs: RadioObservation) {
        if (obs.isEmpty) return
        val fp = byStation.getOrPut(stationId) { Fp() }
        fp.n++
        for (k in keys(obs)) fp.counts.merge(k, 1, Int::plus)
        if (fp.counts.size > MAX_KEYS_PER_STATION) {
            // 드물게 보인 신호부터 버린다.
            fp.counts.entries.sortedBy { it.value }.take(fp.counts.size - MAX_KEYS_PER_STATION)
                .forEach { fp.counts.remove(it.key) }
        }
    }

    @Synchronized
    fun observationCount(stationId: String): Int = byStation[stationId]?.n ?: 0

    @Synchronized
    fun learnedStations(): Int = byStation.size

    /** [candidates] 각각과 얼마나 닮았는지 0~1(이름 가산점 포함 최대 1.35) 점수. */
    @Synchronized
    fun match(candidates: List<Station>, obs: RadioObservation): List<RadioMatch> {
        val keys = keys(obs)
        val ssids = obs.wifi.values.map(::normalize)
        val weights = keys.associateWith { k -> 1.0 / maxOf(1, byStation.values.count { k in it.counts }) }
        val total = weights.values.sum()
        return candidates.map { st ->
            val fp = byStation[st.id]
            val learned = if (fp == null || fp.n == 0 || total == 0.0) 0.0 else {
                keys.sumOf { k -> weights.getValue(k) * minOf(1.0, (fp.counts[k] ?: 0).toDouble() / fp.n) } / total
            }
            val named = ssids.any { nameMatches(it, st.name) }
            RadioMatch(st, learned + if (named) NAME_BONUS else 0.0, named)
        }.sortedByDescending { it.score }
    }

    @Synchronized
    fun serialize(): String = buildString {
        for ((id, fp) in byStation) {
            append(id).append('\t').append(fp.n).append('\t')
            fp.counts.entries.joinTo(this, ",") { "${it.key}=${it.value}" }
            append('\n')
        }
    }

    @Synchronized
    fun load(text: String) {
        byStation.clear()
        for (line in text.lineSequence()) {
            val c = line.split('\t')
            if (c.size != 3) continue
            val n = c[1].toIntOrNull() ?: continue
            val counts = HashMap<String, Int>()
            for (kv in c[2].split(',')) {
                val i = kv.lastIndexOf('=')
                if (i > 0) kv.substring(i + 1).toIntOrNull()?.let { counts[kv.substring(0, i)] = it }
            }
            byStation[c[0]] = Fp(n, counts)
        }
    }

    companion object {
        const val MAX_KEYS_PER_STATION = 300
        const val NAME_BONUS = 0.35

        private fun keys(obs: RadioObservation): Set<String> =
            obs.cells.mapTo(HashSet()) { "c:$it" } + obs.wifi.keys.map { "w:${it.lowercase()}" }

        private fun normalize(s: String) = s.lowercase().filterNot { it.isWhitespace() || it == '_' || it == '-' }

        /** "강남" → "강남역"이 들어 있거나, 세 글자 이상 이름이 그대로 들어 있으면 일치. */
        fun nameMatches(normalizedSsid: String, stationName: String): Boolean {
            val name = normalize(stationName)
            if (name.length < 2) return false
            if (name.endsWith("역")) return normalizedSsid.contains(name)
            return normalizedSsid.contains(name + "역") || (name.length >= 3 && normalizedSsid.contains(name))
        }
    }
}
