package com.subwayeta.core.motion

import kotlin.math.max
import kotlin.math.sqrt

enum class MotionState { UNKNOWN, STOPPED, MOVING }

sealed interface MotionEvent {
    val atMillis: Long

    /** 정차 → 주행. [atMillis]는 움직이기 시작한 시각(창 시작 시각으로 거슬러 올라간 값). */
    data class Departed(override val atMillis: Long) : MotionEvent

    /** 주행 → 정차. [atMillis]는 멈춘 시각. */
    data class Arrived(override val atMillis: Long) : MotionEvent

    /** 처음으로 상태가 확정됨(앱을 열차 안에서 켰을 때 MOVING일 수 있다). */
    data class Initial(val state: MotionState, override val atMillis: Long) : MotionEvent
}

/** 한 창(기본 1초)에서 계산한 특징값. 튜닝과 화면 표시에 쓴다. */
data class MotionFeatures(
    val windowStart: Long,
    /** 고주파 진동 세기(m/s², RMS). 열차가 달리면 바퀴·레일 진동으로 커진다. */
    val vibration: Double,
    /** 자기장 세기의 표준편차(µT). 전동차 모터·전류가 만드는 변동. */
    val magStd: Double,
    /** 지속 가속도(m/s²). 출발·제동 때 수 초 동안 0.5~1 m/s² 정도가 나온다. */
    val sustained: Double,
    /** 출발 판정용 점수(진동+자기장+지속 가속도). */
    val score: Double,
    /** 정차 판정용 점수(진동+자기장). 지속 가속도는 제동 직후까지 남아 있어 정차 판정에는 쓰지 않는다. */
    val stillScore: Double,
    val walking: Boolean,
)

data class MotionConfig(
    val windowMs: Long = 1000,
    /** 고역 통과 필터 시간 상수(초). 이보다 느린 성분(중력, 손 기울임)은 진동에서 뺀다. */
    val highPassTauSec: Double = 0.3,
    val vibScale: Double = 0.05,
    val magScale: Double = 0.6,
    val sustainedScale: Double = 0.3,
    val vibWeight: Double = 0.5,
    val magWeight: Double = 0.3,
    val sustainedWeight: Double = 0.4,
    val movingScore: Double = 1.0,
    val movingWindows: Int = 3,
    val stoppedScore: Double = 0.5,
    val stoppedWindows: Int = 4,
    /** 정차 중 잡음 바닥을 이 기간(창 개수)의 하위 [floorPercentile]로 추정한다. */
    val floorHistory: Int = 600,
    val floorPercentile: Double = 0.1,
    val defaultVibFloor: Double = 0.02,
    val defaultMagFloor: Double = 0.1,
    val walkingSteps: Int = 3,
    val walkingWindowMs: Long = 5000,
    val sustainedShortWindows: Int = 2,
    val sustainedLongWindows: Int = 20,
    /** 한 창 안에서 폰이 이 각도(라디안) 이상 돌면 지속 가속도 기준을 다시 잡는다. */
    val rotationResetRad: Double = 0.12,
)

/**
 * 가속도계·자기장 센서·걸음 감지기만으로 열차의 출발/정차를 판단한다(기압계 불필요).
 *
 * 1. 매 [MotionConfig.windowMs]마다 진동, 자기장 변동, 지속 가속도를 계산한다.
 * 2. 정차 중 잡음 바닥(floor)을 빼고 정규화해 점수를 만든다. 폰을 손에 들었는지,
 *    주머니에 있는지에 따라 잡음이 달라서 바닥은 최근 기록에서 적응적으로 추정한다.
 * 3. 점수에 히스테리시스를 걸어 상태를 바꾸고, 이벤트 시각은 확정에 쓰인 첫 창의
 *    시작 시각으로 거슬러 올라가 기록한다(창 길이만큼, 즉 ±1초 이내 정밀도).
 * 4. 걷는 중(걸음 감지)에는 상태를 바꾸지 않는다.
 * 5. 폰을 돌리면 중력 방향이 바뀌어 지속 가속도처럼 보이므로, 자이로로 회전을 감지하면 기준을 다시 잡는다.
 */
class MotionClassifier(private val config: MotionConfig = MotionConfig()) {
    var listener: ((MotionEvent) -> Unit)? = null
    var featuresListener: ((MotionFeatures) -> Unit)? = null

    var state: MotionState = MotionState.UNKNOWN
        private set
    var latest: MotionFeatures? = null
        private set

    private var windowStart = -1L
    private var lastAccelT = -1L
    private var lpX = 0.0
    private var lpY = 0.0
    private var lpZ = 0.0
    private var lpReady = false

    private var accCount = 0
    private var hpSq = 0.0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var magCount = 0
    private var magSum = 0.0
    private var magSumSq = 0.0
    private var lastGyroT = -1L
    private var rotation = 0.0

    private val windowMeans = ArrayDeque<DoubleArray>()
    private val vibHistory = ArrayDeque<Double>()
    private val magHistory = ArrayDeque<Double>()
    private val steps = ArrayDeque<Long>()

    private var movingStreak = 0
    private var movingStart = 0L
    private var stoppedStreak = 0
    private var stoppedStart = 0L

    /** 중력이 포함된 원시 가속도(m/s²). */
    fun onAccelerometer(tMillis: Long, x: Double, y: Double, z: Double) {
        if (windowStart < 0 || tMillis - lastAccelT > config.windowMs * 5) {
            // 처음이거나 센서가 오래 끊겼다면 다시 시작한다.
            resetWindow(tMillis)
            windowMeans.clear()
            lpReady = false
        }
        while (tMillis >= windowStart + config.windowMs) {
            closeWindow()
            windowStart += config.windowMs
        }
        if (!lpReady) {
            lpX = x; lpY = y; lpZ = z; lpReady = true
        } else {
            val dt = ((tMillis - lastAccelT).coerceAtLeast(1)) / 1000.0
            val a = dt / (config.highPassTauSec + dt)
            lpX += a * (x - lpX); lpY += a * (y - lpY); lpZ += a * (z - lpZ)
        }
        lastAccelT = tMillis
        val hx = x - lpX
        val hy = y - lpY
        val hz = z - lpZ
        hpSq += hx * hx + hy * hy + hz * hz
        sumX += x; sumY += y; sumZ += z
        accCount++
    }

    /** 자기장(µT). */
    fun onMagnetometer(@Suppress("UNUSED_PARAMETER") tMillis: Long, x: Double, y: Double, z: Double) {
        if (windowStart < 0) return
        val m = sqrt(x * x + y * y + z * z)
        magSum += m
        magSumSq += m * m
        magCount++
    }

    /** 각속도(rad/s). 없어도 동작하지만 폰을 돌릴 때 오검출이 늘어난다. */
    fun onGyroscope(tMillis: Long, x: Double, y: Double, z: Double) {
        if (lastGyroT >= 0) {
            val dt = (tMillis - lastGyroT).coerceIn(0, 200) / 1000.0
            rotation += sqrt(x * x + y * y + z * z) * dt
        }
        lastGyroT = tMillis
    }

    fun onStep(tMillis: Long) {
        steps.addLast(tMillis)
        while (steps.size > 50) steps.removeFirst()
    }

    private fun resetWindow(t: Long) {
        windowStart = t
        lastAccelT = t
        clearAccumulators()
    }

    private fun clearAccumulators() {
        accCount = 0; hpSq = 0.0; sumX = 0.0; sumY = 0.0; sumZ = 0.0
        magCount = 0; magSum = 0.0; magSumSq = 0.0
        rotation = 0.0
    }

    private fun closeWindow() {
        val start = windowStart
        if (accCount < 5) {
            clearAccumulators()
            return
        }
        val vibration = sqrt(hpSq / accCount)
        val hasMag = magCount >= 3
        val magStd = if (hasMag) {
            val mean = magSum / magCount
            sqrt(max(0.0, magSumSq / magCount - mean * mean))
        } else 0.0
        if (rotation > config.rotationResetRad) windowMeans.clear()
        windowMeans.addLast(doubleArrayOf(sumX / accCount, sumY / accCount, sumZ / accCount))
        while (windowMeans.size > config.sustainedLongWindows) windowMeans.removeFirst()
        val sustained = sustainedAcceleration()
        clearAccumulators()

        push(vibHistory, vibration)
        if (hasMag) push(magHistory, magStd)
        val vibFloor = floor(vibHistory, config.defaultVibFloor)
        val magFloor = floor(magHistory, config.defaultMagFloor)

        val stillScore = config.vibWeight * max(0.0, vibration - vibFloor) / config.vibScale +
            config.magWeight * max(0.0, magStd - magFloor) / config.magScale
        val score = stillScore + config.sustainedWeight * sustained / config.sustainedScale

        val end = start + config.windowMs
        while (steps.isNotEmpty() && steps.first() < end - config.walkingWindowMs) steps.removeFirst()
        val walking = steps.count { it <= end } >= config.walkingSteps

        val f = MotionFeatures(start, vibration, magStd, sustained, score, stillScore, walking)
        latest = f
        featuresListener?.invoke(f)

        if (walking) {
            movingStreak = 0
            stoppedStreak = 0
            return
        }
        if (score >= config.movingScore) {
            if (movingStreak++ == 0) movingStart = start
        } else movingStreak = 0
        if (stillScore < config.stoppedScore) {
            if (stoppedStreak++ == 0) stoppedStart = start
        } else stoppedStreak = 0

        if (state != MotionState.MOVING && movingStreak >= config.movingWindows) {
            transition(MotionState.MOVING, movingStart)
        } else if (state != MotionState.STOPPED && stoppedStreak >= config.stoppedWindows) {
            transition(MotionState.STOPPED, stoppedStart)
        }
    }

    private fun transition(to: MotionState, at: Long) {
        val from = state
        state = to
        val event = when {
            from == MotionState.UNKNOWN -> MotionEvent.Initial(to, at)
            to == MotionState.MOVING -> MotionEvent.Departed(at)
            else -> MotionEvent.Arrived(at)
        }
        listener?.invoke(event)
    }

    /** 최근 2초 평균 가속도 벡터와 그 앞 구간 평균의 차이. 중력 방향이 그대로면 열차의 가감속만 남는다. */
    private fun sustainedAcceleration(): Double {
        val shortN = config.sustainedShortWindows
        if (windowMeans.size < shortN + 4) return 0.0
        val recent = windowMeans.toList().takeLast(shortN)
        val older = windowMeans.toList().dropLast(shortN)
        fun mean(l: List<DoubleArray>, i: Int) = l.sumOf { it[i] } / l.size
        val dx = mean(recent, 0) - mean(older, 0)
        val dy = mean(recent, 1) - mean(older, 1)
        val dz = mean(recent, 2) - mean(older, 2)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun push(q: ArrayDeque<Double>, v: Double) {
        q.addLast(v)
        while (q.size > config.floorHistory) q.removeFirst()
    }

    private fun floor(q: ArrayDeque<Double>, default: Double): Double {
        if (q.size < 10) return default
        val sorted = q.sorted()
        return sorted[((sorted.size - 1) * config.floorPercentile).toInt()]
    }
}
