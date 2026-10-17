package com.subwayeta.app.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.subwayeta.core.engine.TripSnapshot
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.LineParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
)

/** 서비스와 화면이 공유하는 상태. 프로세스 안에서만 유지된다. */
object TripRepository {
    private val _state = MutableStateFlow<TripUiState?>(null)
    val state: StateFlow<TripUiState?> = _state

    @Volatile
    private var lines: List<Line>? = null

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
}
