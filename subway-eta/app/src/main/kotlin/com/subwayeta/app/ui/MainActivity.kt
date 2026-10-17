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
import com.subwayeta.core.board.BoardArrival

class MainActivity : ComponentActivity() {
    private var pendingBoard: BoardArrival? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val arrival = pendingBoard ?: return@registerForActivityResult
        pendingBoard = null
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startTrip(arrival)
        } else {
            Toast.makeText(
                this,
                "정확한 위치 권한이 필요합니다. 기지국·Wi-Fi 신호와 GPS로 현재 역을 보정하는 데만 쓰고, 밖으로 보내지 않습니다.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val groups = TripRepository.groups(this)
        val sensors = SensorAvailability.of(this)
        val stats = TripRepository.stats(this)
        TripRepository.loadFavorites(this)
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                val state by TripRepository.state.collectAsStateWithLifecycle()
                val favorites by TripRepository.favorites.collectAsStateWithLifecycle()
                AppScreen(
                    groups = groups,
                    sensors = sensors,
                    stats = stats,
                    state = state,
                    favorites = favorites,
                    onToggleFavorite = { TripRepository.toggleFavorite(this, it) },
                    onMoveFavoriteUp = { TripRepository.moveFavoriteUp(this, it) },
                    onBoard = ::requestBoard,
                    onStop = { TrackingService.stop(this) },
                    onSetStation = { k -> TrackingService.setCurrentStation(this, k) },
                    onSetAlight = { k -> TrackingService.setAlight(this, k) },
                    onClear = { TripRepository.clear() },
                )
            }
        }
    }

    private fun requestBoard(arrival: BoardArrival) {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ).filterNot(::granted)
        if (needed.isEmpty()) {
            startTrip(arrival)
        } else {
            pendingBoard = arrival
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startTrip(a: BoardArrival) {
        val r = a.route
        TrackingService.start(this, r.line.id, r.stationIdx.first(), r.stationIdx.last(), r.direction, r.express)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
