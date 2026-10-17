package com.subwayeta.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.subwayeta.app.R
import com.subwayeta.app.ui.MainActivity
import com.subwayeta.app.ui.formatClock
import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.engine.TripSession
import com.subwayeta.core.engine.TripSnapshot
import com.subwayeta.core.model.Direction
import com.subwayeta.core.model.Route
import com.subwayeta.core.motion.MotionEvent
import com.subwayeta.core.motion.MotionFeatures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 이동 중 센서를 계속 읽어야 하므로 포그라운드 서비스로 동작한다.
 * 센서 값은 별도 스레드에서 [TripSession]에 넣고, 1초마다 예측을 계산해 화면과 알림에 내보낸다.
 */
class TrackingService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()

    private var sensorThread: HandlerThread? = null
    private var session: TripSession? = null
    private var stats: TravelStats? = null
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var logWriter: BufferedWriter? = null
    private var logLines = 0

    /** SensorEvent.timestamp(부팅 후 경과 나노초)를 epoch 밀리초로 바꾸는 오프셋. */
    private var bootToEpochMs = 0L
    private var gpsActive = false
    private var passiveActive = false
    private var alertedApproach = false
    private var lastNotificationText = ""

    private val gpsListener = LocationListener { handleLocation(it) }
    private val passiveListener = LocationListener { handleLocation(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIP, "이동 중 도착 예측", NotificationManager.IMPORTANCE_LOW),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "하차 알림", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTrip(intent)
            ACTION_STOP -> {
                stopTrip(clearState = true)
                stopSelf()
            }
            ACTION_SET_STATION -> {
                val k = intent.getIntExtra(EXTRA_STATION_K, -1)
                synchronized(lock) { session?.takeIf { k in 0..it.route.lastK }?.setCurrentStation(k, now()) }
                tick()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTrip(clearState = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun startTrip(intent: Intent) {
        stopTrip(clearState = false)
        val line = TripRepository.lines(this).firstOrNull { it.id == intent.getStringExtra(EXTRA_LINE) }
        if (line == null) {
            stopSelf()
            return
        }
        val direction = intent.getStringExtra(EXTRA_DIRECTION)?.let { Direction.valueOf(it) }
        val route = Route.plan(line, intent.getIntExtra(EXTRA_FROM, 0), intent.getIntExtra(EXTRA_TO, 1), direction)

        try {
            startForeground(NOTIFICATION_ID, tripNotification("${route.station(route.lastK).name} 도착 예측 준비 중", null, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 없이 포그라운드 서비스를 시작할 수 없음", e)
            stopSelf()
            return
        }

        bootToEpochMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val st = stats ?: loadStats().also { stats = it }
        alertedApproach = false
        lastNotificationText = ""
        openLog(route)
        synchronized(lock) {
            session = TripSession(
                route = route,
                stats = st,
                startAt = now(),
                onLearned = ::saveStats,
                onEvent = ::logEvent,
            ).also { it.featuresListener = ::logFeatures }
        }

        val thread = HandlerThread("sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        for ((type, period) in listOf(
            Sensor.TYPE_ACCELEROMETER to SAMPLING_US,
            Sensor.TYPE_MAGNETIC_FIELD to SAMPLING_US,
            Sensor.TYPE_GYROSCOPE to SAMPLING_US,
            Sensor.TYPE_STEP_DETECTOR to SensorManager.SENSOR_DELAY_NORMAL,
        )) {
            sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(this, it, period, 0, handler) }
        }

        startPassiveLocation()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubwayEta:trip")
            .apply { acquire(MAX_TRIP_MS) }

        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }

    private fun stopTrip(clearState: Boolean) {
        tickerJob?.cancel()
        tickerJob = null
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        setGps(false)
        if (passiveActive) {
            locationManager.removeUpdates(passiveListener)
            passiveActive = false
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        synchronized(lock) { session = null }
        closeLog()
        if (clearState) TripRepository.publish(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun tick() {
        val now = now()
        val (snap, wantsGps) = synchronized(lock) {
            val s = session ?: return
            s.snapshot(now) to s.wantsGps(now)
        }
        setGps(wantsGps)
        val finished = snap.prediction.phase is Phase.Finished
        TripRepository.publish(TripUiState(snap, gpsActive, running = !finished))
        handleAlerts(snap)
        if (finished) {
            stopTrip(clearState = false)
            stopSelf()
        } else {
            updateNotification(snap)
        }
    }

    private fun handleAlerts(snap: TripSnapshot) {
        val route = snap.route
        val dest = route.station(route.lastK).name
        when (val phase = snap.prediction.phase) {
            is Phase.Running -> if (!alertedApproach && phase.fromK == route.lastK - 1) {
                alertedApproach = true
                val eta = snap.prediction.destinationArrivalAt?.let { " (${formatClock(it)} 도착 예정)" } ?: ""
                alert("다음 역 $dest 에서 내리세요$eta")
            }
            is Phase.Finished -> alert("$dest 도착 · ${formatClock(phase.arrivedAt)}")
            is Phase.AtStation -> Unit
        }
    }

    private fun alert(text: String) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        if (canNotify()) notificationManager.notify(ALERT_NOTIFICATION_ID, n)
    }

    private fun updateNotification(snap: TripSnapshot) {
        val p = snap.prediction
        val route = snap.route
        val eta = p.destinationArrivalAt
        val title = "${route.station(route.lastK).name} ${eta?.let { formatClock(it) } ?: "--:--:--"} 도착 예정"
        val text = when (val phase = p.phase) {
            is Phase.AtStation -> "${route.station(phase.k).name} 정차 중 · 출발 ${p.departureAt?.let { formatClock(it) } ?: "-"}"
            is Phase.Running -> "${route.station(phase.fromK).name} → ${route.station(phase.fromK + 1).name} · " +
                "도착 ${p.nextArrivalAt?.let { formatClock(it) } ?: "-"}"
            is Phase.Finished -> "도착"
        }
        val key = "$title|$text"
        if (key == lastNotificationText || !canNotify()) return
        lastNotificationText = key
        notificationManager.notify(NOTIFICATION_ID, tripNotification(title, text, eta))
    }

    private fun tripNotification(title: String, text: String?, countdownTo: Long?): Notification =
        NotificationCompat.Builder(this, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "종료", PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (countdownTo != null) {
                    // 시스템이 알림 안에서 초 단위로 남은 시간을 세어 준다.
                    setWhen(countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                }
            }
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canNotify() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---- 센서 ----

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1_000_000 + bootToEpochMs
        val v = event.values
        synchronized(lock) {
            val s = session ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> s.onAccelerometer(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                Sensor.TYPE_MAGNETIC_FIELD -> s.onMagnetometer(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                Sensor.TYPE_GYROSCOPE -> s.onGyroscope(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                Sensor.TYPE_STEP_DETECTOR -> s.onStep(t)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- 위치 ----

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 다른 앱이 받은 위치를 공짜로 얻는다(배터리 추가 소모 없음). */
    @SuppressLint("MissingPermission")
    private fun startPassiveLocation() {
        if (!hasLocationPermission() || passiveActive) return
        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 2_000L, 0f, passiveListener, Looper.getMainLooper())
        passiveActive = true
    }

    /** GPS는 시작 직후와 지상 구간에서만 켠다. 지하에서는 잡히지 않고 배터리만 쓴다. */
    @SuppressLint("MissingPermission")
    private fun setGps(on: Boolean) {
        if (on == gpsActive) return
        if (on) {
            if (!hasLocationPermission() || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener, Looper.getMainLooper())
        } else {
            locationManager.removeUpdates(gpsListener)
        }
        gpsActive = on
    }

    private fun handleLocation(location: Location) {
        if (!location.hasAccuracy()) return
        val t = location.elapsedRealtimeNanos / 1_000_000 + bootToEpochMs
        if (now() - t > MAX_LOCATION_AGE_MS) return
        val corrected = synchronized(lock) {
            session?.onLocation(location.latitude, location.longitude, location.accuracy.toDouble(), t) ?: false
        }
        if (corrected) tick()
    }

    // ---- 학습값 저장, 기록 ----

    private fun statsFile() = File(filesDir, "travel_stats.tsv")

    private fun loadStats() = TravelStats().apply {
        runCatching { statsFile().takeIf { it.exists() }?.readText()?.let(::load) }
            .onFailure { Log.w(TAG, "학습값을 읽지 못함", it) }
    }

    /** 센서 스레드에서 lock을 잡은 채로 불린다. 파일이 작아서 바로 쓴다. */
    private fun saveStats() {
        val s = stats ?: return
        runCatching {
            val tmp = File(filesDir, "travel_stats.tsv.tmp")
            tmp.writeText(s.serialize())
            tmp.renameTo(statsFile())
        }.onFailure { Log.w(TAG, "학습값 저장 실패", it) }
    }

    /**
     * 임계값 튜닝용 기록. adb pull /sdcard/Android/data/com.subwayeta.app/files/logs 로 꺼낼 수 있다.
     */
    private fun openLog(route: Route) {
        runCatching {
            val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            logWriter = File(dir, "trip-$name.csv").bufferedWriter().apply {
                write("# ${route.line.name} ${route.stations.joinToString(">") { it.name }}\n")
                write("t_ms,vibration,mag_std,sustained,score,still_score,walking\n")
            }
        }.onFailure { Log.w(TAG, "기록 파일을 열지 못함", it) }
    }

    private fun logFeatures(f: MotionFeatures) {
        val w = logWriter ?: return
        runCatching {
            w.write("${f.windowStart},${"%.4f".format(Locale.US, f.vibration)},${"%.4f".format(Locale.US, f.magStd)}," +
                "${"%.4f".format(Locale.US, f.sustained)},${"%.3f".format(Locale.US, f.score)},${"%.3f".format(Locale.US, f.stillScore)},${if (f.walking) 1 else 0}\n")
            if (++logLines % 30 == 0) w.flush()
        }
    }

    private fun logEvent(e: MotionEvent) {
        runCatching { logWriter?.apply { write("# EVENT ${e.atMillis} $e\n"); flush() } }
    }

    private fun closeLog() {
        runCatching { logWriter?.close() }
        logWriter = null
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_TRIP = "trip"
        private const val CHANNEL_ALERT = "alert"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        /** 50Hz. 진동 분석에 충분하고 HIGH_SAMPLING_RATE_SENSORS 권한(200Hz 초과)이 필요 없다. */
        private const val SAMPLING_US = 20_000
        private const val MAX_TRIP_MS = 3 * 60 * 60 * 1000L
        private const val MAX_LOCATION_AGE_MS = 10_000L

        const val ACTION_START = "com.subwayeta.app.START"
        const val ACTION_STOP = "com.subwayeta.app.STOP"
        const val ACTION_SET_STATION = "com.subwayeta.app.SET_STATION"
        const val EXTRA_LINE = "line"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_STATION_K = "station_k"

        fun start(context: Context, lineId: String, fromIdx: Int, toIdx: Int, direction: Direction?) {
            val i = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LINE, lineId)
                .putExtra(EXTRA_FROM, fromIdx)
                .putExtra(EXTRA_TO, toIdx)
                .putExtra(EXTRA_DIRECTION, direction?.name)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun setCurrentStation(context: Context, k: Int) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_SET_STATION).putExtra(EXTRA_STATION_K, k))
        }
    }
}
