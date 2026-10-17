package com.subwayeta.core.engine

import com.subwayeta.core.model.Route
import com.subwayeta.core.motion.MotionClassifier
import com.subwayeta.core.motion.MotionConfig
import com.subwayeta.core.motion.MotionEvent
import com.subwayeta.core.motion.MotionFeatures
import com.subwayeta.core.motion.MotionState
import com.subwayeta.core.radio.RadioFingerprints
import com.subwayeta.core.radio.RadioMatch
import com.subwayeta.core.radio.RadioObservation
import kotlin.math.max
import kotlin.math.min

data class RadioStatus(
    val atMillis: Long,
    val cells: Int,
    val wifi: Int,
    val best: RadioMatch?,
    val learnedStations: Int,
)

data class TripSnapshot(
    val route: Route,
    val prediction: Prediction,
    val motionState: MotionState,
    val features: MotionFeatures?,
    val logs: List<TrackLog>,
    val learnedSamples: Int,
    val radio: RadioStatus?,
)

/**
 * 한 번의 이동(출발역 → 도착역)을 담당한다. 센서 값을 넣으면 내부에서 출발/정차를 판단하고,
 * [snapshot]으로 언제든 초 단위 예측을 꺼낼 수 있다. 스레드 안전하지 않으므로 호출하는 쪽에서 동기화한다.
 */
class TripSession(
    val route: Route,
    val stats: TravelStats,
    startAt: Long,
    motionConfig: MotionConfig = MotionConfig(),
    private val onLearned: () -> Unit = {},
    private val onEvent: (MotionEvent) -> Unit = {},
    val fingerprints: RadioFingerprints = RadioFingerprints(),
    private val onRadioLearned: () -> Unit = {},
) {
    private var radioStatus: RadioStatus? = null
    private var pendingRadioK: Int? = null
    private val logs = ArrayDeque<TrackLog>()
    private val classifier = MotionClassifier(motionConfig)
    val tracker = TripTracker(route, stats, startAt, onLog = ::addLog, onLearned = { onLearned() })

    var featuresListener: ((MotionFeatures) -> Unit)?
        get() = classifier.featuresListener
        set(value) { classifier.featuresListener = value }

    init {
        classifier.listener = { ev ->
            when (ev) {
                is MotionEvent.Departed -> tracker.onDeparted(ev.atMillis)
                is MotionEvent.Arrived -> tracker.onArrived(ev.atMillis)
                is MotionEvent.Initial -> if (ev.state == MotionState.MOVING) tracker.onInitialMoving(ev.atMillis)
            }
            onEvent(ev)
        }
    }

    val finished: Boolean get() = tracker.phase is Phase.Finished
    val motionState: MotionState get() = classifier.state

    fun onAccelerometer(t: Long, x: Double, y: Double, z: Double) = classifier.onAccelerometer(t, x, y, z)
    fun onMagnetometer(t: Long, x: Double, y: Double, z: Double) = classifier.onMagnetometer(t, x, y, z)
    fun onGyroscope(t: Long, x: Double, y: Double, z: Double) = classifier.onGyroscope(t, x, y, z)
    fun onStep(t: Long) = classifier.onStep(t)

    fun onLocation(lat: Double, lon: Double, accuracyM: Double, t: Long): Boolean =
        tracker.onLocation(lat, lon, accuracyM, t, stationary = classifier.state == MotionState.STOPPED)

    /**
     * 기지국·Wi-Fi 스캔 결과. 멈춰 있을 때만 쓴다.
     * 1) 앞뒤 몇 역 중 다른 역과 확실히 더 닮았고, 두 번 연속 같은 결과면 그 역으로 보정한다.
     * 2) 아니면 지금 정차 중인 역의 신호로 학습한다.
     * 보정했으면 true.
     */
    fun onRadio(obs: RadioObservation): Boolean {
        val cur = tracker.currentK() ?: return false
        if (classifier.state != MotionState.STOPPED || obs.isEmpty) {
            pendingRadioK = null
            radioStatus = RadioStatus(obs.atMillis, obs.cells.size, obs.wifi.size, null, fingerprints.learnedStations())
            return false
        }
        val range = max(0, cur - 1)..min(route.lastK, cur + RADIO_LOOKAHEAD)
        val matches = fingerprints.match(range.map { route.station(it) }, obs)
        val best = matches.firstOrNull()
        radioStatus = RadioStatus(obs.atMillis, obs.cells.size, obs.wifi.size, best, fingerprints.learnedStations())
        val curScore = matches.firstOrNull { it.station == route.station(cur) }?.score ?: 0.0
        val bestK = best?.let { b -> range.first { route.station(it) == b.station } }
        if (best != null && bestK != null && bestK != cur &&
            best.score >= RADIO_MIN_SCORE && best.score - curScore >= RADIO_MIN_MARGIN
        ) {
            if (pendingRadioK == bestK) {
                pendingRadioK = null
                val why = if (best.nameMatched) "Wi-Fi 이름" else "무선 신호 %.0f%% 일치".format(best.score * 100)
                return tracker.snapTo(bestK, obs.atMillis, "$why 보정")
            }
            pendingRadioK = bestK
            return false
        }
        pendingRadioK = null
        val p = tracker.phase
        if (p is Phase.AtStation) {
            fingerprints.learn(route.station(p.k).id, obs)
            onRadioLearned()
        }
        return false
    }

    fun setCurrentStation(k: Int, t: Long) =
        tracker.setCurrentStation(k, t, moving = classifier.state == MotionState.MOVING)

    /**
     * 지금 GPS를 켤 가치가 있는지. 지하에서는 GPS가 잡히지 않아 배터리만 쓰므로
     * 시작 직후(출발역 확인)와 지상 구간에서만 켠다.
     */
    fun wantsGps(now: Long): Boolean {
        if (finished) return false
        if (tracker.ageMillis(now) < GPS_STARTUP_MS) return true
        val k = when (val p = tracker.phase) {
            is Phase.AtStation -> p.k
            is Phase.Running -> p.fromK
            is Phase.Finished -> return false
        }
        val next = minOf(k + 1, route.lastK)
        return route.station(k).aboveGround || route.station(next).aboveGround
    }

    fun snapshot(now: Long): TripSnapshot = TripSnapshot(
        route = route,
        prediction = Predictor.predict(route, stats, tracker.phase, now),
        motionState = classifier.state,
        features = classifier.latest,
        logs = logs.toList(),
        learnedSamples = stats.sampleCount(),
        radio = radioStatus,
    )

    private fun addLog(l: TrackLog) {
        logs.addLast(l)
        while (logs.size > MAX_LOGS) logs.removeFirst()
    }

    companion object {
        const val GPS_STARTUP_MS = 60_000L
        const val MAX_LOGS = 30
        const val RADIO_LOOKAHEAD = 3
        const val RADIO_MIN_SCORE = 0.45
        const val RADIO_MIN_MARGIN = 0.2
    }
}
