package com.trustableai.koru.ui

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionGoalSource
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.runtime.CameraDirectSessionController
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import com.trustableai.koru.runtime.TrackCatalog
import com.trustableai.koru.service.KoruTelemetryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Represents the BLE connection lifecycle for the Dauntless adapter.
 * Shown as a status pill in the app header for at-a-glance feedback.
 */
enum class BleConnectionState {
    /** No BLE connection attempted yet. */
    IDLE,
    /** Scanning for a paired Dauntless device. */
    SCANNING,
    /** GATT connected, running baud rate auto-scan. */
    INITIALIZING,
    /** Baud rate locked, actively streaming CAN/OBD data. */
    STREAMING,
    /** Connection lost or adapter unavailable. */
    DISCONNECTED,
    /** An error occurred (permission, adapter off, etc.). */
    ERROR,
}

data class CoachOption(
    val id: String,
    val name: String,
    val style: String,
)

data class GoalOption(
    val focus: SessionGoalFocus,
    val label: String,
    val description: String,
    val actions: List<CoachAction>,
)

data class SessionUiState(
    val backendStatus: LiveBackendStatus = LiveBackendStatus(
        backend = RuntimeBackend.DETERMINISTIC,
        state = LiveBackendState.IDLE,
        detail = "Android session idle",
        usesOnDeviceModel = false,
        supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
    ),
    val latestFrame: TelemetryFrame? = null,
    val decisions: List<CoachingDecision> = emptyList(),
    val savedSession: RecordedSessionArtifact? = null,
    val activeCoachId: String = "superaj",
    val audioEnabled: Boolean = true,
    val trackName: String = TrackCatalog.defaultTrack.name,
    val sessionMode: SessionMode = SessionMode.TELEMETRY,
    val telemetrySource: TelemetrySourceKind = TelemetrySourceKind.AIM_CAN_USB,
    val obdTransportPreference: ObdTransportPreference = ObdTransportPreference.AUTO,
    val selectedGoalFocuses: Set<SessionGoalFocus> = emptySet(),
    val customGoalDescription: String = "",
    val cameraEnabled: Boolean = true,
    val cameraStatus: String = "Camera lane waiting for permission",
    val activeSessionMode: SessionMode? = null,
    val bleConnectionState: BleConnectionState = BleConnectionState.IDLE,
) {
    val isSessionActive: Boolean
        get() = activeSessionMode != null ||
            backendStatus.state == LiveBackendState.STARTING ||
            backendStatus.state == LiveBackendState.READY ||
            backendStatus.state == LiveBackendState.DEGRADED
}

class LiveSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences("koru_session_prefs", Context.MODE_PRIVATE)
    private val cameraDirectController = CameraDirectSessionController(appContext)
    private val _uiState = MutableStateFlow(SessionUiState(
        telemetrySource = prefs.getString("telemetry_source", null)
            ?.let { runCatching { TelemetrySourceKind.valueOf(it) }.getOrNull() }
            ?: TelemetrySourceKind.AIM_CAN_USB,
        obdTransportPreference = prefs.getString("obd_transport", null)
            ?.let { runCatching { ObdTransportPreference.valueOf(it) }.getOrNull() }
            ?: ObdTransportPreference.AUTO,
        cameraEnabled = prefs.getBoolean("camera_enabled", true),
        trackName = prefs.getString("track_name", null) ?: TrackCatalog.defaultTrack.name,
    ))
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    val coachOptions = listOf(
        CoachOption("superaj", "Super AJ", "Adaptive"),
        CoachOption("aj", "AJ", "Direct"),
        CoachOption("rachel", "Rachel", "Technical"),
        CoachOption("tony", "Tony", "Motivational"),
        CoachOption("garmin", "Garmin", "Data"),
    )

    val goalOptions = listOf(
        GoalOption(
            focus = SessionGoalFocus.BRAKING,
            label = "Braking",
            description = "Hard initial pressure and clean release.",
            actions = listOf(CoachAction.THRESHOLD, CoachAction.SPIKE_BRAKE, CoachAction.TRAIL_BRAKE),
        ),
        GoalOption(
            focus = SessionGoalFocus.THROTTLE,
            label = "Throttle",
            description = "Commit on exits without rushing the apex.",
            actions = listOf(CoachAction.HUSTLE, CoachAction.COMMIT, CoachAction.EARLY_THROTTLE),
        ),
        GoalOption(
            focus = SessionGoalFocus.VISION,
            label = "Vision",
            description = "Eyes up and looking through the next reference.",
            actions = listOf(CoachAction.PUSH, CoachAction.COAST),
        ),
        GoalOption(
            focus = SessionGoalFocus.LINES,
            label = "Lines",
            description = "Turn-in, apex, and exit placement.",
            actions = listOf(CoachAction.TURN_IN, CoachAction.APEX),
        ),
        GoalOption(
            focus = SessionGoalFocus.SMOOTHNESS,
            label = "Smoothness",
            description = "Calm inputs when the car is loaded.",
            actions = listOf(CoachAction.COGNITIVE_OVERLOAD, CoachAction.LIFT_MID_CORNER),
        ),
        GoalOption(
            focus = SessionGoalFocus.CUSTOM,
            label = "Custom",
            description = "One operator-defined focus for this session.",
            actions = listOf(CoachAction.COGNITIVE_OVERLOAD),
        ),
    )

    init {
        viewModelScope.launch {
            KoruSessionBus.status.collect { status ->
                _uiState.update {
                    it.copy(
                        backendStatus = status,
                        activeSessionMode = if (
                            status.state == LiveBackendState.IDLE ||
                            status.state == LiveBackendState.ERROR ||
                            status.state == LiveBackendState.UNAVAILABLE
                        ) {
                            null
                        } else {
                            it.activeSessionMode
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.latestTelemetry.collect { frame ->
                _uiState.update { it.copy(latestFrame = frame) }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.decisionHistory.collect { decisions ->
                _uiState.update { it.copy(decisions = decisions) }
            }
        }
        viewModelScope.launch {
            KoruSessionBus.latestSavedSession.collect { session ->
                _uiState.update { it.copy(savedSession = session) }
            }
        }
    }

    fun currentConfig(): LiveSessionConfig {
        val state = _uiState.value
        return LiveSessionConfig(
            coachId = state.activeCoachId,
            audioEnabled = state.audioEnabled,
            trackName = if (state.sessionMode == SessionMode.DEVICE_TEST) {
                "Device GPS Test"
            } else {
                state.trackName
            },
            sessionMode = state.sessionMode,
            telemetrySource = state.telemetrySource,
            obdTransportPreference = state.obdTransportPreference,
            sessionGoals = sessionGoals(state),
            sourceUrl = null,
        )
    }

    fun startSession(config: LiveSessionConfig = currentConfig()) {
        KoruSessionBus.resetLiveState()
        _uiState.update { it.copy(activeSessionMode = config.sessionMode) }
        when (config.sessionMode) {
            SessionMode.CAMERA_DIRECT -> cameraDirectController.start(config)
            SessionMode.DEVICE_TEST, SessionMode.TELEMETRY ->
                ContextCompat.startForegroundService(appContext, KoruTelemetryService.startIntent(appContext, config.toJson()))
        }
    }

    fun stopSession() {
        when (_uiState.value.activeSessionMode) {
            SessionMode.CAMERA_DIRECT -> cameraDirectController.stop()
            SessionMode.DEVICE_TEST, SessionMode.TELEMETRY ->
                appContext.startService(KoruTelemetryService.stopIntent(appContext))
            null -> Unit
        }
        _uiState.update { it.copy(activeSessionMode = null) }
    }

    fun setActiveCoach(coachId: String) {
        _uiState.update { it.copy(activeCoachId = coachId) }
        cameraDirectController.setActiveCoach(coachId)
        if (_uiState.value.activeSessionMode == SessionMode.TELEMETRY || _uiState.value.activeSessionMode == SessionMode.DEVICE_TEST) {
            appContext.startService(KoruTelemetryService.setCoachIntent(appContext, coachId))
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        _uiState.update { it.copy(audioEnabled = enabled) }
        cameraDirectController.setAudioEnabled(enabled)
        if (_uiState.value.activeSessionMode == SessionMode.TELEMETRY || _uiState.value.activeSessionMode == SessionMode.DEVICE_TEST) {
            appContext.startService(KoruTelemetryService.setAudioIntent(appContext, enabled))
        }
    }

    fun setSessionMode(mode: SessionMode) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(sessionMode = mode) }
    }

    fun setTelemetrySource(source: TelemetrySourceKind) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(telemetrySource = source) }
        prefs.edit().putString("telemetry_source", source.name).apply()
    }

    fun setObdTransportPreference(preference: ObdTransportPreference) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(obdTransportPreference = preference) }
        prefs.edit().putString("obd_transport", preference.name).apply()
    }

    fun setTrackName(trackName: String) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(trackName = trackName) }
        prefs.edit().putString("track_name", trackName).apply()
    }

    fun setBleConnectionState(state: BleConnectionState) {
        _uiState.update { it.copy(bleConnectionState = state) }
    }

    fun toggleGoal(focus: SessionGoalFocus) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { state ->
            val selected = state.selectedGoalFocuses
            val next = when {
                focus in selected -> selected - focus
                selected.size >= 3 -> selected
                else -> selected + focus
            }
            state.copy(selectedGoalFocuses = next)
        }
    }

    fun setCustomGoalDescription(description: String) {
        if (_uiState.value.isSessionActive) return
        _uiState.update { it.copy(customGoalDescription = description.take(120)) }
    }

    fun setCameraStatus(status: String) {
        _uiState.update { it.copy(cameraStatus = status) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        _uiState.update { it.copy(cameraEnabled = enabled) }
        prefs.edit().putBoolean("camera_enabled", enabled).apply()
    }

    fun requestBackendStatus() {
        KoruSessionBus.tryEmitStatus(KoruSessionBus.status.value)
    }

    fun sessionGoals(): List<SessionGoal> = sessionGoals(_uiState.value)

    override fun onCleared() {
        cameraDirectController.shutdown()
        super.onCleared()
    }

    private fun sessionGoals(state: SessionUiState): List<SessionGoal> {
        return goalOptions
            .filter { it.focus in state.selectedGoalFocuses }
            .mapIndexed { index, option ->
                val description = if (option.focus == SessionGoalFocus.CUSTOM) {
                    state.customGoalDescription.ifBlank { "Custom session focus" }
                } else {
                    option.description
                }
                SessionGoal(
                    id = "native-goal-${index + 1}-${option.focus.name.lowercase()}",
                    focus = option.focus,
                    description = description,
                    source = SessionGoalSource.PRE_RACE_CHAT,
                    prioritizedActions = option.actions,
                )
            }
            .take(3)
    }
}
