@file:OptIn(ExperimentalMaterial3Api::class)

package com.subwayeta.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subwayeta.app.tracking.Favorite
import com.subwayeta.app.tracking.LineGroup
import com.subwayeta.app.tracking.SensorAvailability
import com.subwayeta.app.tracking.TripUiState
import com.subwayeta.core.board.BoardArrival
import com.subwayeta.core.board.BoardSection
import com.subwayeta.core.board.StationBoard
import com.subwayeta.core.engine.Phase
import com.subwayeta.core.engine.Prediction
import com.subwayeta.core.engine.StationEta
import com.subwayeta.core.engine.TravelStats
import com.subwayeta.core.model.Route
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 남은 시간이 이 밀리초 이하이면 빨간 글씨로 표시한다. */
const val URGENT_MS = 5_000L

fun formatClock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormat)

/** 남은 시간을 "1분 03초" / "45초"로. 0초 밑으로는 내려가지 않는다. */
fun formatCountdown(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return if (s >= 60) "${s / 60}분 ${"%02d".format(s % 60)}초" else "${s}초"
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** 0.5초마다 바뀌는 현재 시각. 초가 바뀌는 순간을 놓치지 않도록 반 초 단위로 갱신한다. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500L - now % 500L)
        }
    }
    return now
}

@Composable
private fun urgentColor(remainingMs: Long): Color =
    if (remainingMs <= URGENT_MS) MaterialTheme.colorScheme.error else Color.Unspecified

@Composable
fun AppScreen(
    groups: List<LineGroup>,
    sensors: SensorAvailability,
    stats: TravelStats,
    state: TripUiState?,
    favorites: List<Favorite>,
    onToggleFavorite: (Favorite) -> Unit,
    onMoveFavoriteUp: (Favorite) -> Unit,
    onBoard: (BoardArrival) -> Unit,
    onStop: () -> Unit,
    onSetStation: (Int) -> Unit,
    onSetAlight: (Int) -> Unit,
    onClear: () -> Unit,
) {
    // 지금 보고 있는 역: "그룹\t역id"
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val now = rememberNow()
    Scaffold { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
        val target = selected?.split('\t')?.let { (g, id) -> groups.firstOrNull { it.name == g }?.let { it to id } }
        when {
            state != null -> TripScreen(state, now, onStop, onSetStation, onSetAlight, onClear, modifier)
            target != null -> {
                BackHandler { selected = null }
                val fav = Favorite(target.first.name, target.second)
                BoardScreen(
                    group = target.first,
                    stationId = target.second,
                    stats = stats,
                    now = now,
                    isFavorite = favorites.any { it.key == fav.key },
                    onToggleFavorite = { onToggleFavorite(fav) },
                    onBoard = onBoard,
                    onBack = { selected = null },
                    modifier = modifier,
                )
            }
            else -> HomeScreen(
                groups, sensors, stats, now, favorites,
                onOpen = { f -> selected = f.key },
                onRemoveFavorite = onToggleFavorite,
                onMoveFavoriteUp = onMoveFavoriteUp,
                modifier = modifier,
            )
        }
    }
}

// ---------------- 첫 화면: 즐겨찾기 + 역 찾기 ----------------

@Composable
private fun HomeScreen(
    groups: List<LineGroup>,
    sensors: SensorAvailability,
    stats: TravelStats,
    now: Long,
    favorites: List<Favorite>,
    onOpen: (Favorite) -> Unit,
    onRemoveFavorite: (Favorite) -> Unit,
    onMoveFavoriteUp: (Favorite) -> Unit,
    modifier: Modifier,
) {
    var groupIdx by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("지하철 도착 예측", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("즐겨찾기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (favorites.isNotEmpty()) TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집") }
        }
        if (favorites.isEmpty()) {
            Text("역 화면에서 ☆를 누르면 여기에 추가됩니다.", style = MaterialTheme.typography.bodySmall)
        }
        favorites.forEach { fav ->
            val group = groups.firstOrNull { it.name == fav.group } ?: return@forEach
            val station = group.station(fav.stationId) ?: return@forEach
            FavoriteCard(group, station.name, fav.stationId, stats, now, editing,
                onOpen = { onOpen(fav) }, onRemove = { onRemoveFavorite(fav) }, onUp = { onMoveFavoriteUp(fav) })
        }

        HorizontalDivider()
        Text("역 찾기", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { i, g ->
                FilterChip(selected = i == groupIdx, onClick = { groupIdx = i }, label = { Text(g.name) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("역 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val q = query.trim()
        // 검색어가 있으면 모든 노선에서, 없으면 고른 노선의 역을 보여 준다.
        val results = if (q.isEmpty()) {
            groups.getOrNull(groupIdx)?.let { g -> g.stations.map { g to it } }.orEmpty()
        } else {
            groups.flatMap { g -> g.stations.filter { it.name.contains(q) }.map { g to it } }
        }
        Card(Modifier.fillMaxWidth()) {
            Column {
                results.forEachIndexed { i, (g, s) ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(Favorite(g.name, s.id)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LineBadge(g)
                        Spacer(Modifier.width(8.dp))
                        Text(s.name, modifier = Modifier.weight(1f))
                        if (s.expressStop) Text("급행", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (results.isEmpty()) Text("찾는 역이 없습니다.", Modifier.padding(16.dp))
            }
        }
        SensorCard(sensors)
    }
}

@Composable
private fun LineBadge(group: LineGroup) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(parseColor(group.colorHex))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(group.name, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FavoriteCard(
    group: LineGroup,
    stationName: String,
    stationId: String,
    stats: TravelStats,
    now: Long,
    editing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onUp: () -> Unit,
) {
    val sections = remember(now / 1000, stationId) { StationBoard.compute(group.lines, stationId, stats, now, perDirection = 1) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(group)
                Spacer(Modifier.width(8.dp))
                Text(stationName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (editing) {
                    TextButton(onClick = onUp) { Text("위로") }
                    TextButton(onClick = onRemove) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                }
            }
            sections.forEach { sec ->
                val a = sec.arrivals.firstOrNull()
                Row {
                    Text(a?.label ?: sec.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (a == null) {
                        Text("운행 없음", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        CountdownText(a, now, MaterialTheme.typography.bodyMedium.fontSize)
                    }
                }
            }
        }
    }
}

// ---------------- 역 도착 정보판 ----------------

@Composable
private fun BoardScreen(
    group: LineGroup,
    stationId: String,
    stats: TravelStats,
    now: Long,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBoard: (BoardArrival) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val station = group.station(stationId)
    val sections: List<BoardSection> = remember(now / 500, stationId) {
        StationBoard.compute(group.lines, stationId, stats, now)
    }
    var confirm by remember { mutableStateOf<BoardArrival?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← 처음으로") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(group)
            Spacer(Modifier.width(8.dp))
            Text(station?.name ?: stationId, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleFavorite) {
                Text(if (isFavorite) "★" else "☆", fontSize = 28.sp, color = if (isFavorite) Color(0xFFFFB300) else Color.Unspecified)
            }
        }
        Text(
            "현재 ${formatClock(now)} · 운행 패턴과 학습한 구간 시간으로 계산한 추정 시각입니다(실시간 아님).",
            style = MaterialTheme.typography.bodySmall,
        )
        sections.forEach { sec ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        sec.title + (sec.nextStation?.let { " · 다음 역 $it" } ?: ""),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (sec.arrivals.isEmpty()) {
                        Text("오늘 남은 열차가 없습니다.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    sec.arrivals.forEach { a ->
                        HorizontalDivider()
                        ArrivalRow(a, now, onClick = { confirm = a })
                    }
                }
            }
        }
        Text("열차를 누르면 탑승 후 추적을 시작합니다. 기지국·Wi-Fi 신호로 현재 역을 보정합니다.", style = MaterialTheme.typography.bodySmall)
    }

    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${a.label} 탑승") },
            text = { Text("${formatClock(a.arriveAt)} 도착 예정 열차입니다. 탑승하면 센서와 무선 신호로 다음 역 도착 시각을 추적합니다.") },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    onBoard(a)
                }) { Text("탑승 시작") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ArrivalRow(a: BoardArrival, now: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${formatClock(a.arriveAt)} 도착", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        CountdownText(a, now, 24.sp)
    }
}

/** "3분 12초 후" / 정차 중이면 "정차 중 · 출발 8초". 5초 이하는 빨간색. */
@Composable
private fun CountdownText(a: BoardArrival, now: Long, size: androidx.compose.ui.unit.TextUnit) {
    val toArrive = a.arriveAt - now
    val (text, remaining) = if (toArrive > 0) {
        formatCountdown(toArrive) to toArrive
    } else {
        "정차 중 · 출발 ${formatCountdown(a.departAt - now)}" to (a.departAt - now)
    }
    Text(text, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = urgentColor(remaining))
}

// ---------------- 탑승 중 화면 ----------------

@Composable
private fun TripScreen(
    state: TripUiState,
    now: Long,
    onStop: () -> Unit,
    onSetStation: (Int) -> Unit,
    onSetAlight: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val snap = state.snapshot
    val route = snap.route
    val p = snap.prediction
    var picking by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "${route.line.group} ${route.line.directionName(route.direction)}${if (route.express) " 급행" else ""}",
            color = parseColor(route.line.colorHex),
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${route.station(0).name}에서 탑승 · ${route.station(route.lastK).name}까지",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        TargetCard(state, now)
        StatusCard(p, route, now)

        Text("다음 역 (누르면 하차 알림)", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                p.stations.forEachIndexed { i, eta ->
                    if (i > 0) HorizontalDivider()
                    StationRow(eta, now, alight = eta.k == state.alightK, onClick = {
                        if (state.running && !eta.isCurrent) onSetAlight(eta.k)
                    })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text("현재 역 고치기") }
                Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("종료") }
            } else {
                Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("처음으로") }
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

/** 하차역을 정했으면 그 역, 아니면 다음 역의 도착 예정 시각을 크게 보여 준다. */
@Composable
private fun TargetCard(state: TripUiState, now: Long) {
    val p = state.snapshot.prediction
    val alight = state.alightK
    val eta = if (alight != null) p.stations.firstOrNull { it.k == alight } else p.stations.firstOrNull { !it.isCurrent }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            when {
                !state.running -> {
                    val name = (alight?.let { state.snapshot.route.station(it) } ?: state.snapshot.route.station(state.snapshot.route.lastK)).name
                    Text("$name 도착")
                }
                eta == null -> Text("도착")
                alight != null -> Text("하차역 ${eta.station.name} 도착 예정")
                else -> Text("다음 역 ${eta.station.name} 도착 예정")
            }
            val at = eta?.arriveAt
            Text(
                at?.let { formatClock(it) } ?: "--:--:--",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            if (state.running && at != null) {
                Text(
                    "${formatCountdown(at - now)} 후 · 오차 ±${(eta?.sigmaSec ?: 0.0).roundToInt()}초",
                    color = urgentColor(at - now),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(p: Prediction, route: Route, now: Long) {
    var remaining = Long.MAX_VALUE
    val (title, detail) = when (val phase = p.phase) {
        is Phase.AtStation -> {
            val name = route.station(phase.k).name
            val dwell = p.stations.firstOrNull()?.dwellSec?.roundToInt() ?: 0
            val dep = p.departureAt
            when {
                phase.arrivedAt == null && phase.k == 0 -> "$name 승강장" to "열차가 출발하면 자동으로 감지합니다"
                p.overdue || dep == null -> "$name 정차 중" to "곧 출발 (예상 정차 ${dwell}초)"
                else -> {
                    remaining = dep - now
                    "$name 정차 중" to "출발까지 ${formatCountdown(dep - now)} · ${formatClock(dep)} (예상 정차 ${dwell}초)"
                }
            }
        }
        is Phase.Running -> {
            val from = route.station(phase.fromK).name
            val to = route.station(phase.fromK + 1).name
            val next = p.nextArrivalAt
            val holdingSince = phase.holdingSince
            when {
                holdingSince != null ->
                    "$from → $to 사이 정차" to "신호 대기 ${formatCountdown(now - holdingSince)} (도착 예측에 반영 중)"
                p.overdue || next == null -> "$from → $to 운행 중" to "곧 도착"
                else -> {
                    remaining = next - now
                    "$from → $to 운행 중" to "$to 도착까지 ${formatCountdown(next - now)} · ${formatClock(next)}"
                }
            }
        }
        is Phase.Finished -> "종착역 도착" to formatClock(phase.arrivedAt)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, color = urgentColor(remaining))
        }
    }
}

@Composable
private fun StationRow(eta: StationEta, now: Long, alight: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                eta.station.name + when {
                    eta.isCurrent -> " (현재)"
                    alight -> " · 하차"
                    else -> ""
                },
                fontWeight = if (eta.isCurrent || alight) FontWeight.Bold else FontWeight.Normal,
                color = if (alight) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            if (!eta.isCurrent && eta.departAt != null) {
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
                Text(
                    "${formatCountdown(it - now)} 후 ±${eta.sigmaSec.roundToInt()}초",
                    style = MaterialTheme.typography.bodySmall,
                    color = urgentColor(it - now),
                )
            }
        }
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
private fun DebugCard(state: TripUiState) {
    val snap = state.snapshot
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("센서·무선 신호", fontWeight = FontWeight.Bold)
            val f = snap.features
            Text("움직임: ${snap.motionState}${if (f?.walking == true) " (걷는 중)" else ""} · GPS ${if (state.gpsActive) "켜짐" else "꺼짐"} · 구간 학습 ${snap.learnedSamples}회")
            if (f != null) {
                Text(
                    "진동 %.3f · 자기장 %.2f · 가속 %.2f · 점수 %.2f/%.2f".format(f.vibration, f.magStd, f.sustained, f.score, f.stillScore),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val r = snap.radio
            if (r == null) {
                Text("무선 신호: 스캔 대기 중", style = MaterialTheme.typography.bodySmall)
            } else {
                val best = r.best?.let { " · 가장 닮은 역 ${it.station.name} ${(it.score * 100).roundToInt()}%${if (it.nameMatched) " (Wi-Fi 이름)" else ""}" } ?: ""
                Text(
                    "기지국 ${r.cells}개 · Wi-Fi ${r.wifi}개$best · 신호를 학습한 역 ${r.learnedStations}곳",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            snap.logs.takeLast(8).asReversed().forEach {
                Text("${formatClock(it.atMillis)} ${it.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
