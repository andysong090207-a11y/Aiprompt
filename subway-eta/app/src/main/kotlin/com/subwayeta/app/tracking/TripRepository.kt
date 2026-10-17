package com.subwayeta.app.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.engine.TripSnapshot
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.LineParser
import com.subwayeta.core.model.Station
import com.subwayeta.core.radio.RadioFingerprints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class SensorAvailability(
    val accelerometer: Boolean,
    val magnetometer: Boolean,
    val gyroscope: Boolean,
    val stepDetector: Boolean,
    val barometer: Boolean,
) {
    companion object {
        fun of(context: Context): SensorAvailability {
            val sm = context.getSystemService(SensorManager::class.java)
            fun has(type: Int) = sm.getDefaultSensor(type) != null
            return SensorAvailability(
                accelerometer = has(Sensor.TYPE_ACCELEROMETER),
                magnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
                gyroscope = has(Sensor.TYPE_GYROSCOPE),
                stepDetector = has(Sensor.TYPE_STEP_DETECTOR),
                barometer = has(Sensor.TYPE_PRESSURE),
            )
        }
    }
}

data class TripUiState(
    val snapshot: TripSnapshot,
    val gpsActive: Boolean,
    val running: Boolean,
    /** 하차 알림을 받을 역(경로상 순번). null이면 정하지 않음. */
    val alightK: Int?,
)

/** 화면에 보이는 노선 묶음. 1호선처럼 여러 파일로 나뉜 노선을 하나로 보여 준다. */
data class LineGroup(val name: String, val lines: List<Line>) {
    val colorHex: String get() = lines.first().colorHex

    /** 묶음 안의 모든 역(같은 id는 한 번만). */
    val stations: List<Station> = lines.flatMap { it.stations }.distinctBy { it.id }

    fun station(id: String): Station? = stations.firstOrNull { it.id == id }
}

data class Favorite(val group: String, val stationId: String) {
    val key: String get() = "$group\t$stationId"
}

/** 서비스와 화면이 공유하는 상태. 학습값과 즐겨찾기는 파일에 저장한다. */
object TripRepository {
    private const val TAG = "TripRepository"
    private val _state = MutableStateFlow<TripUiState?>(null)
    val state: StateFlow<TripUiState?> = _state

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites

    @Volatile private var lines: List<Line>? = null
    @Volatile private var stats: TravelStats? = null
    @Volatile private var fingerprints: RadioFingerprints? = null
    @Volatile private var favoritesLoaded = false

    internal fun publish(s: TripUiState?) {
        _state.value = s
    }

    fun clear() {
        if (_state.value?.running != true) _state.value = null
    }

    /** assets/lines 폴더의 노선 파일을 모두 읽는다. 파일을 추가하면 노선이 늘어난다. */
    fun lines(context: Context): List<Line> = lines ?: synchronized(this) {
        lines ?: context.assets.list("lines").orEmpty()
            .filter { it.endsWith(".txt") }
            .sorted()
            .map { name -> context.assets.open("lines/$name").bufferedReader().use { LineParser.parse(it.readText()) } }
            .also { lines = it }
    }

    fun groups(context: Context): List<LineGroup> =
        lines(context).groupBy { it.group }.map { (name, ls) -> LineGroup(name, ls) }

    // ---- 학습값 (구간 시간, 무선 신호) ----

    private fun statsFile(c: Context) = File(c.filesDir, "travel_stats.tsv")
    private fun fingerprintFile(c: Context) = File(c.filesDir, "radio_fingerprints.tsv")

    fun stats(context: Context): TravelStats = stats ?: synchronized(this) {
        stats ?: TravelStats().also { s ->
            readText(statsFile(context))?.let(s::load)
            stats = s
        }
    }

    fun fingerprints(context: Context): RadioFingerprints = fingerprints ?: synchronized(this) {
        fingerprints ?: RadioFingerprints().also { f ->
            readText(fingerprintFile(context))?.let(f::load)
            fingerprints = f
        }
    }

    fun saveStats(context: Context) = writeAtomic(statsFile(context), stats(context).serialize())
    fun saveFingerprints(context: Context) = writeAtomic(fingerprintFile(context), fingerprints(context).serialize())

    // ---- 즐겨찾기 ----

    private fun prefs(c: Context) = c.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun loadFavorites(context: Context) {
        if (favoritesLoaded) return
        favoritesLoaded = true
        _favorites.value = prefs(context).getString("list", "").orEmpty().lines()
            .mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { Favorite(it[0], it[1]) } }
    }

    fun isFavorite(f: Favorite) = _favorites.value.any { it.key == f.key }

    fun toggleFavorite(context: Context, f: Favorite) {
        val now = _favorites.value
        _favorites.value = if (now.any { it.key == f.key }) now.filterNot { it.key == f.key } else now + f
        saveFavorites(context)
    }

    /** 즐겨찾기 순서를 바꾼다(위로 한 칸). */
    fun moveFavoriteUp(context: Context, f: Favorite) {
        val list = _favorites.value.toMutableList()
        val i = list.indexOfFirst { it.key == f.key }
        if (i <= 0) return
        list.add(i - 1, list.removeAt(i))
        _favorites.value = list
        saveFavorites(context)
    }

    private fun saveFavorites(context: Context) {
        prefs(context).edit().putString("list", _favorites.value.joinToString("\n") { it.key }).apply()
    }

    private fun readText(f: File): String? =
        runCatching { f.takeIf { it.exists() }?.readText() }.onFailure { Log.w(TAG, "${f.name} 읽기 실패", it) }.getOrNull()

    private fun writeAtomic(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        }.onFailure { Log.w(TAG, "${f.name} 저장 실패", it) }
    }
}
