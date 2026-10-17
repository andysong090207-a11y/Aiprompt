package com.subwayeta.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.subwayeta.core.radio.RadioObservation
import java.util.concurrent.Executor

/**
 * 주기적으로 기지국 정보와 Wi-Fi 스캔 결과를 모아 [RadioObservation]으로 넘긴다.
 *
 * - 기지국: 연결 없이도 주변 셀 ID를 읽을 수 있다(정확한 위치 권한 필요).
 * - Wi-Fi: 안드로이드는 앱의 스캔 요청을 2분에 4회로 제한하므로 30초마다 요청하고,
 *   그 사이에는 시스템·다른 앱이 스캔한 최근 결과를 같이 쓴다.
 */
class RadioScanner(
    private val context: Context,
    private val handler: Handler,
    private val epochOffsetMs: () -> Long,
    private val onObservation: (RadioObservation) -> Unit,
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var running = false
    private var lastWifiScanRequest = 0L
    @Volatile private var latestCells: Set<String> = emptySet()

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            scanOnce()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running || !hasPermission()) return
        running = true
        handler.post(loop)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(loop)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun scanOnce() {
        if (!hasPermission()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastWifiScanRequest >= WIFI_SCAN_REQUEST_MS) {
            lastWifiScanRequest = nowElapsed
            @Suppress("DEPRECATION")
            runCatching { wifi?.startScan() }
        }
        val tm = telephony ?: return deliver(nowElapsed)
        try {
            tm.requestCellInfoUpdate(Executor { handler.post(it) }, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    latestCells = cellInfo.mapNotNullTo(HashSet(), ::cellKey)
                    deliver(nowElapsed)
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    deliver(nowElapsed)
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "기지국 정보 권한 없음", e)
            deliver(nowElapsed)
        }
    }

    @SuppressLint("MissingPermission")
    private fun deliver(nowElapsed: Long) {
        val wifiMap = try {
            wifi?.scanResults.orEmpty()
                // ScanResult.timestamp는 부팅 후 마이크로초
                .filter { nowElapsed - it.timestamp / 1000 <= WIFI_MAX_AGE_MS }
                .mapNotNull { r ->
                    @Suppress("DEPRECATION")
                    r.BSSID?.lowercase()?.let { it to (r.SSID ?: "") }
                }
                .toMap()
        } catch (e: SecurityException) {
            emptyMap()
        }
        onObservation(RadioObservation(nowElapsed + epochOffsetMs(), latestCells, wifiMap))
    }

    companion object {
        private const val TAG = "RadioScanner"
        const val INTERVAL_MS = 10_000L
        private const val WIFI_SCAN_REQUEST_MS = 30_000L
        private const val WIFI_MAX_AGE_MS = 45_000L

        /** 기지국 하나를 나타내는 문자열. 이웃 셀처럼 ID가 없으면 주파수+PCI로 대신한다. */
        fun cellKey(info: CellInfo): String? = when (info) {
            is CellInfoLte -> info.cellIdentity.let { id ->
                when {
                    id.ci != CellInfo.UNAVAILABLE -> "lte:${id.mccString}${id.mncString}:${id.ci}"
                    id.pci != CellInfo.UNAVAILABLE -> "ltepci:${id.earfcn}:${id.pci}"
                    else -> null
                }
            }
            is CellInfoNr -> (info.cellIdentity as CellIdentityNr).let { id ->
                when {
                    id.nci != CellInfo.UNAVAILABLE_LONG -> "nr:${id.nci}"
                    id.pci != CellInfo.UNAVAILABLE -> "nrpci:${id.nrarfcn}:${id.pci}"
                    else -> null
                }
            }
            is CellInfoWcdma -> info.cellIdentity.cid.takeIf { it != CellInfo.UNAVAILABLE }?.let { "wcdma:$it" }
            is CellInfoGsm -> info.cellIdentity.cid.takeIf { it != CellInfo.UNAVAILABLE }?.let { "gsm:$it" }
            else -> null
        }
    }
}
