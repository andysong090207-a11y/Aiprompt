@file:OptIn(ExperimentalMaterial3Api::class)

package com.subwayeta.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subwayeta.app.tracking.SensorAvailability
import com.subwayeta.app.tracking.TripUiState
import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.Prediction
import com.subwayeta.core.engine.Predictor
import com.subwayeta.core.engine.StationEta
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.model.Direction
import com.subwayeta.core.model.Line
import com.subwayeta.core.model.Route
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class StartRequest(val lineId: String, val fromIdx: Int, val toIdx: Int, val direction: Direction?, val express: Boolean)

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatClock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormat)

/** 남은 시간을 "1분 03초" / "45초"로. 0초 밑으로는 내려가지 않는다. */
fun formatCountdown(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return if (s >= 60) "${s / 60}분 ${"%02d".format(s % 60)}초" else "${s}초"
}

private fun lineColor(line: Line): Color =
    runCatching { Color(android.graphics.Color.parseColor(line.colorHex)) }.getOrDefault(Color.Gray)

@Composable
fun AppScreen(
    lines: List<Line>,
    sensors: SensorAvailability,
    state: TripUiState?,
    onStart: (StartRequest) -> Unit,
    onStop: () -> Unit,
    onSetStation: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Scaffold { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
        if (state == null) {
            SetupScreen(lines, sensors, onStart, modifier)
        } else {
            TripScreen(state, onStop, onSetStation, onClear, modifier)
        }
    }
}

@Composable
private fun SetupScreen(lines: List<Line>, sensors: SensorAvailability, onStart: (StartRequest) -> Unit, modifier: Modifier) {
    var lineIdx by rememberSaveable { mutableIntStateOf(0) }
    val line = lines[lineIdx.coerceIn(lines.indices)]
    var express by rememberSaveable(lineIdx) { mutableStateOf(false) }
    val names = line.stations.map { if (express && it.expressStop) "${it.name} (급행)" else it.name }
    var from by rememberSaveable(lineIdx) { mutableIntStateOf(0) }
    var to by rememberSaveable(lineIdx) { mutableIntStateOf(minOf(5, names.lastIndex)) }
    // 0: 자동(가까운 쪽), 1: FORWARD, 2: BACKWARD
    var dirChoice by rememberSaveable(lineIdx) { mutableIntStateOf(0) }
    val direction = when (dirChoice) {
        1 -> Direction.FORWARD
        2 -> Direction.BACKWARD
        else -> null
    }
    val route = remember(line, from, to, direction, express) {
        runCatching { Route.plan(line, from, to, direction, express) }.getOrNull()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("지하철 도착 예측", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "인터넷 없이 가속도·자기장·자이로 센서로 열차의 출발과 정차를 감지하고, " +
                "구간별로 학습한 시간으로 도착 시각을 초 단위까지 예측합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (lines.size > 1) Picker("노선", lines.map { it.name }, lineIdx) { lineIdx = it }
        Picker("출발역", names, from) { from = it }
        Picker("도착역", names, to) { to = it }
        if (line.circular) {
            Text("방향", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = dirChoice == 0, onClick = { dirChoice = 0 }, label = { Text("가까운 쪽") })
                FilterChip(selected = dirChoice == 1, onClick = { dirChoice = 1 }, label = { Text(line.forwardName) })
                FilterChip(selected = dirChoice == 2, onClick = { dirChoice = 2 }, label = { Text(line.backwardName) })
            }
        }
        if (line.hasExpress) {
            Text("열차 종류", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !express, onClick = { express = false }, label = { Text("일반") })
                FilterChip(selected = express, onClick = { express = true }, label = { Text("급행") })
            }
        }
        if (route != null) {
            val preview = remember(route) { Predictor.predict(route, TravelStats(), Phase.AtStation(0, 0L), 0L) }
            val totalSec = (((preview.destinationArrivalAt ?: 0L) - (preview.departureAt ?: 0L)) / 1000.0).roundToInt()
            Text(
                "${line.directionName(route.direction)}${if (route.express) " 급행" else ""} · ${route.lastK}개 역 · " +
                    "기본값 기준 약 ${totalSec / 60}분 ${totalSec % 60}초",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val reason = when {
                from == to -> "출발역과 도착역을 다르게 고르세요."
                express -> "급행은 급행 정차역(이름 옆에 '급행' 표시)끼리만 고를 수 있습니다."
                else -> "이 방향으로는 갈 수 없는 경로입니다."
            }
            Text(reason, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { onStart(StartRequest(line.id, from, to, direction, express)) },
            enabled = route != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("승강장에서 시작") }
        Text(
            "열차에 타기 전 승강장에서 시작하면 첫 구간부터 정확합니다. 열차 안에서 시작했다면 " +
                "추적 화면의 \"현재 역 고치기\"로 방금 지난 역을 알려 주세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        SensorCard(sensors)
    }
}

@Composable
private fun SensorCard(s: SensorAvailability) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("이 폰의 센서", fontWeight = FontWeight.Bold)
            fun mark(b: Boolean) = if (b) "있음" else "없음"
            Text("가속도계: ${mark(s.accelerometer)} (필수)")
            Text("자기장 센서: ${mark(s.magnetometer)} (모터 자기장으로 주행 판단)")
            Text("자이로스코프: ${mark(s.gyroscope)} (폰 회전 오검출 방지)")
            Text("걸음 감지: ${mark(s.stepDetector)} (걷기 오검출 방지)")
            Text("기압계: ${mark(s.barometer)} (사용하지 않음)")
        }
    }
}

@Composable
private fun Picker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrElse(selected) { "" }, modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onSelect(i)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun TripScreen(
    state: TripUiState,
    onStop: () -> Unit,
    onSetStation: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val snap = state.snapshot
    val route = snap.route
    val p = snap.prediction
    var picking by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "${route.line.name} ${route.line.directionName(route.direction)}${if (route.express) " 급행" else ""}",
            color = lineColor(route.line),
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${route.station(0).name} → ${route.station(route.lastK).name}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        DestinationCard(p, route)
        StatusCard(p, route)

        Text("남은 역", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                p.stations.forEachIndexed { i, eta ->
                    if (i > 0) HorizontalDivider()
                    StationRow(eta, p.now, isDestination = eta.k == route.lastK)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text("현재 역 고치기") }
                Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("종료") }
            } else {
                Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("새 경로") }
            }
        }
        DebugCard(state)
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("지금 정차 중이거나 방금 떠난 역") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    route.stations.forEachIndexed { k, s ->
                        TextButton(onClick = {
                            onSetStation(k)
                            picking = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(s.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("닫기") } },
        )
    }
}

@Composable
private fun DestinationCard(p: Prediction, route: Route) {
    val eta = p.destinationArrivalAt
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            val finished = p.phase is Phase.Finished
            Text(if (finished) "${route.station(route.lastK).name} 도착" else "${route.station(route.lastK).name} 도착 예정")
            Text(
                eta?.let { formatClock(it) } ?: "--:--:--",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            if (!finished && eta != null) {
                Text("${formatCountdown(eta - p.now)} 후 · 오차 ±${p.destinationSigmaSec.roundToInt()}초")
            }
        }
    }
}

@Composable
private fun StatusCard(p: Prediction, route: Route) {
    val (title, detail) = when (val phase = p.phase) {
        is Phase.AtStation -> {
            val name = route.station(phase.k).name
            val dwell = p.stations.firstOrNull()?.dwellSec?.roundToInt() ?: 0
            val dep = p.departureAt
            when {
                phase.arrivedAt == null && phase.k == 0 -> "$name 에서 열차 출발 대기" to "출발하면 자동으로 감지합니다"
                p.overdue || dep == null -> "$name 정차 중" to "곧 출발 (예상 정차 ${dwell}초)"
                else -> "$name 정차 중" to "출발까지 ${formatCountdown(dep - p.now)} · ${formatClock(dep)} (예상 정차 ${dwell}초)"
            }
        }
        is Phase.Running -> {
            val from = route.station(phase.fromK).name
            val to = route.station(phase.fromK + 1).name
            val next = p.nextArrivalAt
            val holdingSince = phase.holdingSince
            when {
                holdingSince != null ->
                    "$from → $to 사이 정차" to "신호 대기 ${formatCountdown(p.now - holdingSince)} (도착 예측에 반영 중)"
                p.overdue || next == null -> "$from → $to 운행 중" to "곧 도착"
                else -> "$from → $to 운행 중" to "$to 도착까지 ${formatCountdown(next - p.now)} · ${formatClock(next)}"
            }
        }
        is Phase.Finished -> "도착했습니다" to formatClock(phase.arrivedAt)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail)
        }
    }
}

@Composable
private fun StationRow(eta: StationEta, now: Long, isDestination: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                eta.station.name + if (eta.isCurrent) " (현재)" else "",
                fontWeight = if (eta.isCurrent || isDestination) FontWeight.Bold else FontWeight.Normal,
            )
            if (!isDestination && !eta.isCurrent) {
                Text("정차 약 ${eta.dwellSec.roundToInt()}초", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (eta.isCurrent) {
                eta.departAt?.let {
                    Text(if (it >= now) "출발 ${formatClock(it)}" else "출발 ${formatClock(it)} 지남", fontFamily = FontFamily.Monospace)
                }
            } else eta.arriveAt?.let {
                Text(formatClock(it), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("${formatCountdown(it - now)} 후 ±${eta.sigmaSec.roundToInt()}초", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DebugCard(state: TripUiState) {
    val snap = state.snapshot
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("센서 상태", fontWeight = FontWeight.Bold)
            val f = snap.features
            Text("움직임: ${snap.motionState}${if (f?.walking == true) " (걷는 중)" else ""} · GPS ${if (state.gpsActive) "켜짐" else "꺼짐"} · 학습 ${snap.learnedSamples}회")
            if (f != null) {
                Text(
                    "진동 %.3f · 자기장 %.2f · 가속 %.2f · 점수 %.2f/%.2f".format(f.vibration, f.magStd, f.sustained, f.score, f.stillScore),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            snap.logs.takeLast(8).asReversed().forEach {
                Text("${formatClock(it.atMillis)} ${it.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
