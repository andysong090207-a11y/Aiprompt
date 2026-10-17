package com.subwayeta.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subwayeta.app.tracking.SensorAvailability
import com.subwayeta.app.tracking.TrackingService
import com.subwayeta.app.tracking.TripRepository

class MainActivity : ComponentActivity() {
    private var pendingStart: StartRequest? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingStart ?: return@registerForActivityResult
        pendingStart = null
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startTrip(request)
        } else {
            Toast.makeText(this, "정확한 위치 권한이 필요합니다. 지상 구간에서 현재 역을 보정하는 데만 씁니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val lines = TripRepository.lines(this)
        val sensors = SensorAvailability.of(this)
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                val state by TripRepository.state.collectAsStateWithLifecycle()
                AppScreen(
                    lines = lines,
                    sensors = sensors,
                    state = state,
                    onStart = ::requestStart,
                    onStop = { TrackingService.stop(this) },
                    onSetStation = { k -> TrackingService.setCurrentStation(this, k) },
                    onClear = { TripRepository.clear() },
                )
            }
        }
    }

    private fun requestStart(request: StartRequest) {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ).filterNot(::granted)
        if (needed.isEmpty()) {
            startTrip(request)
        } else {
            pendingStart = request
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startTrip(r: StartRequest) = TrackingService.start(this, r.lineId, r.fromIdx, r.toIdx, r.direction, r.express)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
