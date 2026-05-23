package com.trustableai.koru.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustableai.koru.model.CanVehicleDiagnostics
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.runtime.TrackCatalog
import java.util.Locale

private val KoruColors = darkColorScheme(
    primary = Color(0xFFB7F34A),
    onPrimary = Color(0xFF111904),
    secondary = Color(0xFF7DD3FC),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF050816),
    surface = Color(0xFF0B1224),
    surfaceVariant = Color(0xFF111827),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFB7185),
)

@Composable
fun KoruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KoruColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

@Composable
fun KoruApp(
    viewModel: LiveSessionViewModel,
    onStartRequested: () -> Unit,
    onBindCameraPreview: (PreviewView) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.requestBackendStatus()
    }

    KoruTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("koru-list")
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF050816), Color(0xFF07111F), Color(0xFF050816)),
                        ),
                    )
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Header(state = state)
                }
                item {
                    LiveHud(state.latestFrame, state.decisions.lastOrNull(), state.backendStatus.state)
                }
                item {
                    AimCanTestPanel(state)
                }
                item {
                    SavedSessionPanel(state)
                }
                item {
                    SessionInitialization(
                        state = state,
                        viewModel = viewModel,
                        onStartRequested = onStartRequested,
                    )
                }
                item {
                    CameraPanel(
                        cameraEnabled = state.cameraEnabled,
                        cameraStatus = state.cameraStatus,
                        onCameraToggle = { viewModel.setCameraEnabled(it) },
                        onBindCameraPreview = onBindCameraPreview,
                    )
                }
                item {
                    CoachPanel(
                        state = state,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: SessionUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Koru Edge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Native field-test cockpit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(state.backendStatus.state.name.lowercase(Locale.US), priorityColor(state.decisions.lastOrNull()?.priority))
    }
}

@Composable
private fun LiveHud(
    frame: TelemetryFrame?,
    latestDecision: CoachingDecision?,
    backendState: LiveBackendState,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live-hud"),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF07111F),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SignalLight(latestDecision?.priority, backendState)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = latestDecision?.text ?: "Coach standing by",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = latestDecision?.let {
                            "P${it.priority} ${it.path.name} ${it.action?.name ?: "cue"} ${it.cornerPhase.name}"
                        } ?: "Waiting for first telemetry frame",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricTile("Speed", frame?.speedMph?.let { "%.0f mph".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
                MetricTile("Brake", frame?.brake?.let { "%.0f%%".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
                MetricTile("Throttle", frame?.throttle?.let { "%.0f%%".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
            }
            LinearProgressIndicator(
                progress = { ((frame?.speedMph ?: 0.0) / 120.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = priorityColor(latestDecision?.priority),
                trackColor = Color(0xFF172033),
            )
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101827))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AimCanTestPanel(state: SessionUiState) {
    val frame = state.latestFrame
    val health = frame?.sourceHealth
    val diagnostics = frame?.canVehicleDiagnostics
    val isDauntless = state.telemetrySource == TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH ||
        frame?.telemetrySource == TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH
    val isAimCan = state.telemetrySource == TelemetrySourceKind.AIM_CAN_USB ||
        frame?.telemetrySource == TelemetrySourceKind.AIM_CAN_USB
    if (!isDauntless && !isAimCan) return

    var showEngineering by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("aim-can-test-panel"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF0B1224)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(
                title = if (isDauntless) "Dauntless OBD" else "AiM CAN USB",
                meta = health?.fallbackStage ?: "idle",
            )
            // Row 0: CAN adapter status — labeled with type
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = when {
                            health?.canConnected == true && health.obdStale == false -> Color(0xFF1B5E20)
                            health?.canConnected == true -> Color(0xFF795548)
                            else -> Color(0xFF880E4F)
                        },
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (isDauntless) "⚡ Dauntless BLE" else "🔌 AiM CAN USB",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        health?.canConnected == true && health.obdStale == false -> "● LIVE"
                        health?.canConnected == true -> "● STALE"
                        else -> "○ DISCONNECTED"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            // Row 0b: Phone GPS/IMU status
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = when {
                            health?.phoneMotionFixGood == true -> Color(0xFF1B5E20)
                            health?.phoneMotionConnected == true -> Color(0xFF795548)
                            else -> Color(0xFF424242)
                        },
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "📍 Phone GPS/IMU",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        health?.phoneMotionFixGood == true -> "● FIX"
                        health?.phoneMotionConnected == true -> "● NO FIX"
                        else -> "○ OFF"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                health?.phoneMotionSampleAgeMs?.let { age ->
                    Text(
                        text = "${age}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            // Row 1: Connection status — always shown
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Link", if (health?.canConnected == true) "live" else "waiting", Modifier.weight(1f))
                MetricTile("RPM", frame?.rpm?.toString() ?: "--", Modifier.weight(1f))
                MetricTile(
                    "Throttle",
                    diagnostics?.pedalPositionPercent?.let { "%.0f%%".format(Locale.US, it) }
                        ?: frame?.throttle?.let { "%.0f%%".format(Locale.US, it) }
                        ?: "--",
                    Modifier.weight(1f),
                )
            }
            // Row 2: Temps + battery — available from OBD-II
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    "Coolant",
                    diagnostics?.waterTempC?.let { "%.0f\u00B0C".format(Locale.US, it) }
                        ?: frame?.coolantTempC?.let { "%.0f\u00B0C".format(Locale.US, it) }
                        ?: "--",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Oil",
                    diagnostics?.oilFilterTempC?.let { "%.0f\u00B0C".format(Locale.US, it) }
                        ?: frame?.oilTempC?.let { "%.0f\u00B0C".format(Locale.US, it) }
                        ?: "--",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Battery",
                    diagnostics?.batteryVoltage?.let { "%.1fV".format(Locale.US, it) } ?: "--",
                    Modifier.weight(1f),
                )
            }
            // Row 3: G-forces (from RaceBox/IMU) + speed comparison
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    "Lat G",
                    frame?.gLat?.let { "%.2f".format(Locale.US, it) } ?: "--",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Long G",
                    frame?.gLong?.let { "%.2f".format(Locale.US, it) } ?: "--",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "ECU Spd",
                    diagnostics?.ecuSpeedMph?.let { "%.0f".format(Locale.US, it) }
                        ?: frame?.speedMph?.let { "%.0f".format(Locale.US, it) }
                        ?: "--",
                    Modifier.weight(1f),
                )
            }
            // Row 4: RaceBox status — always shown when using Dauntless or AiM CAN
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    "RaceBox",
                    when {
                        health?.raceBoxConnected == true && health.raceBoxFixGood == true -> "🟢 Fix"
                        health?.raceBoxConnected == true -> "🟡 No Fix"
                        else -> "🔴 Off"
                    },
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Sats",
                    health?.raceBoxSatellites?.toString() ?: "--",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Motion",
                    health?.motionSource ?: "--",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Age",
                    health?.raceBoxSampleAgeMs?.let { "${it}ms" } ?: "--",
                    Modifier.weight(1f),
                )
            }
            // AiM-only rows (brake pressure, wheel speeds, steering, gear, etc.)
            if (isAimCan) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile(
                        "Brake PSI",
                        diagnostics?.brakePressureCalibratedPsi?.let { "%.1f cal".format(Locale.US, it) }
                            ?: diagnostics?.brakePressurePsi?.let { "%.1f".format(Locale.US, it) }
                            ?: "--",
                        Modifier.weight(1f),
                    )
                    MetricTile(
                        "Steer",
                        diagnostics?.steeringAngleDeg?.let { "%.1f\u00B0".format(Locale.US, it) } ?: "--",
                        Modifier.weight(1f),
                    )
                    MetricTile(
                        "Gear",
                        diagnostics?.gearRaw?.toString() ?: "--",
                        Modifier.weight(1f),
                    )
                }
            }
            // Collapsible engineering section
            TextButton(
                onClick = { showEngineering = !showEngineering },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (showEngineering) "▾ Hide Engineering" else "▸ Engineering Data",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = showEngineering) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = canDecodedDiagnosticsText(diagnostics, frame),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CanFrameFreshnessGrid(health)
                    Text(
                        text = listOfNotNull(
                            health?.usbDeviceName?.let { "USB: $it" },
                            health?.rawCanSample?.let { "Raw: $it" },
                            diagnostics?.let { controlsRawText(it) },
                            rawSamplesText(health),
                            health?.degradedReason?.let { "Reason: $it" },
                            if (health?.signUnverified == true) "Signed channels need first-drive sign validation" else null,
                        ).joinToString("\n").ifBlank { "Connect adapter and start the source." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun canDecodedDiagnosticsText(diagnostics: CanVehicleDiagnostics?, frame: TelemetryFrame?): String {
    if (diagnostics == null) return "Decoded channels: waiting for mapped AiM CAN frames."
    val wheelAverage = listOfNotNull(
        diagnostics.wheelSpeedFrontLeftMph,
        diagnostics.wheelSpeedFrontRightMph,
        diagnostics.wheelSpeedRearLeftMph,
        diagnostics.wheelSpeedRearRightMph,
    ).takeIf { it.isNotEmpty() }?.average()
    return listOf(
        "Speed GPS/ECU/Wheel: ${fmt1(diagnostics.gpsSpeedMph)} / ${fmt1(diagnostics.ecuSpeedMph)} / ${fmt1(wheelAverage)} mph",
        "Temps water/oil filter/engine/outside: ${fmt1(diagnostics.waterTempC)} / ${fmt1(diagnostics.oilFilterTempC)} / ${fmt1(diagnostics.engineOilTempC)} / ${fmt1(diagnostics.outsideTempC)} C",
        "Pressures water/oil/brake: ${fmt1(diagnostics.waterPressurePsi)} / ${fmt1(diagnostics.oilPressurePsi)} / ${fmt1(diagnostics.brakePressurePsi)} psi",
        "Brake calibrated/offset: ${fmt1(diagnostics.brakePressureCalibratedPsi)} / raw ${diagnostics.brakePressureZeroOffsetRaw?.toString() ?: "--"} (${fmt1(diagnostics.brakePressureZeroOffsetPsi)} psi)",
        "Controls pedal/brake switch/DSC: ${fmt1(diagnostics.pedalPositionPercent)}% / ${diagnostics.brakeSwitchApplied?.toString() ?: "--"} / ${diagnostics.dscRegActive?.toString() ?: "--"}",
        "Angles/rates steer/yaw/pitch/roll: ${fmt1(diagnostics.steeringAngleDeg)} deg / ${fmt1(diagnostics.yawRateDegPerSec)} / ${fmt1(diagnostics.pitchRateDegPerSec)} / ${fmt1(diagnostics.rollRateDegPerSec)} deg/s",
        "G lat/long/vertical: ${fmt1(frame?.gLat ?: diagnostics.lateralG)} / ${fmt1(frame?.gLong ?: diagnostics.inlineG)} / ${fmt1(diagnostics.verticalG)}",
        "Wheel FL/FR/RL/RR: ${fmt1(diagnostics.wheelSpeedFrontLeftMph)} / ${fmt1(diagnostics.wheelSpeedFrontRightMph)} / ${fmt1(diagnostics.wheelSpeedRearLeftMph)} / ${fmt1(diagnostics.wheelSpeedRearRightMph)} mph",
        "Fuel/Battery/Gear raw: ${fmt1(diagnostics.fuelLevelGal)} gal / ${fmt1(diagnostics.batteryVoltage)} V / ${diagnostics.gearRaw?.toString() ?: "--"}",
    ).joinToString("\n")
}

private fun controlsRawText(diagnostics: CanVehicleDiagnostics): String? {
    return listOfNotNull(
        diagnostics.brakePressureRaw?.let { "brakeRaw=$it" },
        diagnostics.brakePressurePsi?.let { "brakePsi=${fmt1(it)}" },
        diagnostics.brakePressureZeroOffsetRaw?.let { "brakeZeroRaw=$it" },
        diagnostics.brakePressureCalibratedPsi?.let { "brakeCalPsi=${fmt1(it)}" },
        diagnostics.pedalPositionRaw?.let { "pedalRaw=$it" },
        diagnostics.brakeSwitchRaw?.let { "brakeSwitchRaw=$it" },
    ).joinToString(prefix = "0x422 raw: ", separator = ", ").takeIf { it != "0x422 raw: " }
}

private fun rawSamplesText(health: com.trustableai.koru.model.TelemetrySourceHealth?): String? {
    val samples = health?.rawCanSamplesById.orEmpty()
    if (samples.isEmpty()) return null
    val ids = samples.keys.sorted()
    val selected = (CAN_TEST_FRAME_IDS.filter { it in samples } + ids.filter { it !in CAN_TEST_FRAME_IDS }).take(12)
    return buildString {
        append("Observed CAN IDs: ")
        append(ids.joinToString(", "))
        append('\n')
        append("Raw by ID: ")
        append(selected.joinToString(" | ") { id -> "$id=${samples[id]}" })
    }
}

private fun fmt1(value: Double?): String {
    return value?.let { "%.1f".format(Locale.US, it) } ?: "--"
}

@Composable
private fun CanFrameFreshnessGrid(health: com.trustableai.koru.model.TelemetrySourceHealth?) {
    val observedIds = (
        CAN_TEST_FRAME_IDS +
            health?.canFrameRatesHz.orEmpty().keys +
            health?.canFrameAgesMs.orEmpty().keys +
            health?.rawCanSamplesById.orEmpty().keys
        ).distinct().sorted()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        observedIds.chunked(4).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowIds.forEach { frameId ->
                    val stale = health?.canFrameStale?.get(frameId)
                    val age = health?.canFrameAgesMs?.get(frameId)
                    val rate = health?.canFrameRatesHz?.get(frameId)
                    val color = when (stale) {
                        false -> Color(0xFFB7F34A)
                        true -> Color(0xFFFB7185)
                        null -> Color(0xFF64748B)
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = color.copy(alpha = 0.12f),
                        contentColor = color,
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(frameId, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = age?.let { "${it}ms" } ?: "--",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = rate?.let { "%.0fHz".format(Locale.US, it) } ?: "--",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CAN_TEST_FRAME_IDS = listOf("0x420", "0x421", "0x422", "0x423", "0x424", "0x450", "0x451", "0x452")

@Composable
private fun SignalLight(priority: Int?, backendState: LiveBackendState) {
    val color = when {
        priority == 0 || priority == 1 -> Color(0xFFEF4444)
        priority == 2 || priority == 3 -> Color(0xFFF59E0B)
        backendState == LiveBackendState.READY || backendState == LiveBackendState.DEGRADED -> Color(0xFFB7F34A)
        else -> Color(0xFF64748B)
    }
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.22f)),
        )
    }
}

@Composable
private fun SessionInitialization(
    state: SessionUiState,
    viewModel: LiveSessionViewModel,
    onStartRequested: () -> Unit,
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session-initialization"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF0B1224)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Session", state.trackName)
            // Primary source selector — just the two main options
            if (state.sessionMode == SessionMode.TELEMETRY) {
                OptionRow(
                    label = "Source",
                    options = listOf(
                        TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH to "Dauntless BLE",
                        TelemetrySourceKind.AIM_CAN_USB to "AiM CAN USB",
                    ),
                    selected = state.telemetrySource,
                    enabled = !state.isSessionActive,
                    onSelected = viewModel::setTelemetrySource,
                )
            }
            // Track picker
            OptionRow(
                label = "Track",
                options = listOf(
                    TrackCatalog.sonomaRaceway.name to "Sonoma",
                    TrackCatalog.thunderhillEast.name to "Thunderhill",
                ),
                selected = state.trackName,
                enabled = !state.isSessionActive && state.sessionMode != SessionMode.DEVICE_TEST,
                onSelected = viewModel::setTrackName,
            )
            // Start/stop + audio row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.audioEnabled,
                        onCheckedChange = viewModel::setAudioEnabled,
                        modifier = Modifier.testTag("audio-toggle"),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Audio")
                }
                if (state.isSessionActive) {
                    OutlinedButton(
                        onClick = viewModel::stopSession,
                        modifier = Modifier.testTag("stop-session"),
                    ) {
                        Text("Stop Session")
                    }
                } else {
                    Button(
                        onClick = onStartRequested,
                        modifier = Modifier.testTag("start-session"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text("Start Session")
                    }
                }
            }
            Text(
                text = state.backendStatus.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Collapsible advanced section
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (showAdvanced) "▾ Hide Advanced" else "▸ Advanced Options",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OptionRow(
                        label = "Mode",
                        options = listOf(
                            SessionMode.TELEMETRY to "Telemetry + Camera",
                            SessionMode.DEVICE_TEST to "Device Test",
                            SessionMode.CAMERA_DIRECT to "Camera Feedback",
                        ),
                        selected = state.sessionMode,
                        enabled = !state.isSessionActive,
                        onSelected = viewModel::setSessionMode,
                    )
                    if (state.sessionMode == SessionMode.TELEMETRY) {
                        OptionRow(
                            label = "All Sources",
                            options = listOf(
                                TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH to "Dauntless BLE",
                                TelemetrySourceKind.AIM_CAN_USB to "AiM CAN USB",
                                TelemetrySourceKind.RACEBOX_OBD_FUSION to "RaceBox + OBD",
                                TelemetrySourceKind.PHONE_IMU_GPS to "Phone IMU",
                                TelemetrySourceKind.SYNTHETIC to "Synthetic",
                                TelemetrySourceKind.RACEBOX_BLE to "RaceBox BLE",
                                TelemetrySourceKind.OBD_BLUETOOTH to "OBD BT",
                            ),
                            selected = state.telemetrySource,
                            enabled = !state.isSessionActive,
                            onSelected = viewModel::setTelemetrySource,
                        )
                        if (state.telemetrySource == TelemetrySourceKind.RACEBOX_OBD_FUSION) {
                            OptionRow(
                                label = "OBD",
                                options = listOf(
                                    ObdTransportPreference.AUTO to "Auto",
                                    ObdTransportPreference.BLUETOOTH to "Bluetooth MX+",
                                    ObdTransportPreference.USB to "USB EX",
                                ),
                                selected = state.obdTransportPreference,
                                enabled = !state.isSessionActive,
                                onSelected = viewModel::setObdTransportPreference,
                            )
                        }
                    }
                    GoalSelector(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun GoalSelector(state: SessionUiState, viewModel: LiveSessionViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Session Goals", style = MaterialTheme.typography.labelLarge)
        viewModel.goalOptions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    FilterChip(
                        selected = option.focus in state.selectedGoalFocuses,
                        onClick = { viewModel.toggleGoal(option.focus) },
                        enabled = !state.isSessionActive,
                        label = {
                            Column {
                                Text(option.label, maxLines = 1)
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("goal-${option.focus.name.lowercase(Locale.US)}"),
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (SessionGoalFocus.CUSTOM in state.selectedGoalFocuses) {
            OutlinedTextField(
                value = state.customGoalDescription,
                onValueChange = viewModel::setCustomGoalDescription,
                enabled = !state.isSessionActive,
                label = { Text("Custom focus") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun CameraPanel(
    cameraEnabled: Boolean,
    cameraStatus: String,
    onCameraToggle: (Boolean) -> Unit,
    onBindCameraPreview: (PreviewView) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("camera-panel"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF0B1224)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Camera Lane", if (cameraEnabled) "CameraX" else "Off")
                Switch(
                    checked = cameraEnabled,
                    onCheckedChange = onCameraToggle,
                    modifier = Modifier.testTag("camera-toggle"),
                )
            }
            if (cameraEnabled) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                    factory = { context ->
                        PreviewView(context).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            onBindCameraPreview(this)
                        }
                    },
                    update = { previewView -> onBindCameraPreview(previewView) },
                )
            }
            Text(
                text = cameraStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoachPanel(state: SessionUiState, viewModel: LiveSessionViewModel) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("coach-panel"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF0B1224)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Coach", viewModel.coachOptions.firstOrNull { it.id == state.activeCoachId }?.name ?: state.activeCoachId)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                viewModel.coachOptions.take(3).forEach { coach ->
                    AssistChip(
                        onClick = { viewModel.setActiveCoach(coach.id) },
                        label = { Text(coach.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                viewModel.coachOptions.drop(3).forEach { coach ->
                    AssistChip(
                        onClick = { viewModel.setActiveCoach(coach.id) },
                        label = { Text(coach.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            state.decisions.takeLast(5).reversed().forEach { decision ->
                DecisionRow(decision)
            }
            if (state.decisions.isEmpty()) {
                Text(
                    text = "No coaching decisions yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DecisionRow(decision: CoachingDecision) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101827))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusPill("P${decision.priority}", priorityColor(decision.priority))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = decision.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${decision.path.name} ${decision.action?.name ?: "cue"} ${decision.cornerPhase.name} ${decision.latencyMs ?: "--"}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SavedSessionPanel(state: SessionUiState) {
    state.savedSession?.let { session ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("saved-session"),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0F1A2D),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Saved Session", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${session.summary.frameCount} frames, ${session.summary.decisionCount} decisions, schema v${session.schemaVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.canDumpPath?.let { path ->
                    Text(
                        "CAN dump: $path",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                session.artifactPath?.let { path ->
                    Text(
                        "Session JSON: $path",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, meta: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text = meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> OptionRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("option-${label.lowercase(Locale.US)}-${text.lowercase(Locale.US).replace(" ", "-")}"),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.18f),
        contentColor = color,
    ) {
        Text(
            text = text.uppercase(Locale.US),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun priorityColor(priority: Int?): Color {
    return when (priority) {
        0 -> Color(0xFFEF4444)
        1 -> Color(0xFFF97316)
        2 -> Color(0xFF7DD3FC)
        3 -> Color(0xFF94A3B8)
        else -> Color(0xFFB7F34A)
    }
}
