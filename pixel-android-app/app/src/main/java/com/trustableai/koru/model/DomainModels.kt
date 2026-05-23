package com.trustableai.koru.model

enum class CoachAction {
    THRESHOLD,
    TRAIL_BRAKE,
    BRAKE,
    WAIT,
    TURN_IN,
    COMMIT,
    ROTATE,
    APEX,
    THROTTLE,
    PUSH,
    FULL_THROTTLE,
    STABILIZE,
    MAINTAIN,
    COAST,
    HESITATION,
    OVERSTEER_RECOVERY,
    EARLY_THROTTLE,
    LIFT_MID_CORNER,
    SPIKE_BRAKE,
    COGNITIVE_OVERLOAD,
    HUSTLE,
}

enum class CornerPhase {
    STRAIGHT,
    BRAKE_ZONE,
    TURN_IN,
    MID_CORNER,
    APEX,
    EXIT,
    ACCELERATION,
}

enum class CoachingPath {
    HOT,
    COLD,
    FEEDFORWARD,
    EDGE,
}

enum class RuntimeBackend {
    BROWSER,
    AICORE,
    LITERTLM,
    DETERMINISTIC,
}

enum class RuntimeAccelerator {
    NONE,
    MEDIAPIPE_LITERT,
    AICORE,
    UNKNOWN,
}

enum class LiveBackendState {
    IDLE,
    STARTING,
    READY,
    DEGRADED,
    UNAVAILABLE,
    ERROR,
}

enum class SkillLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
}

enum class SessionGoalFocus {
    BRAKING,
    THROTTLE,
    VISION,
    LINES,
    SMOOTHNESS,
    CUSTOM,
}

enum class SessionGoalSource {
    PRE_RACE_CHAT,
    AUTO_GENERATED,
    COACH_ASSIGNED,
}

enum class SessionMode {
    TELEMETRY,
    DEVICE_TEST,
    CAMERA_DIRECT,
}

enum class TelemetrySourceKind {
    SYNTHETIC,
    PHONE_IMU_GPS,
    RACEBOX_BLE,
    OBD_BLUETOOTH,
    RACEBOX_OBD_FUSION,
    AIM_CAN_USB,
    DAUNTLESS_CAN_BLUETOOTH,
}

enum class ObdTransportPreference {
    AUTO,
    BLUETOOTH,
    USB,
}

data class SensorFramePayload(
    val schemaVersion: Int = 1,
    val domain: String = "racing",
    val sensorKind: String,
    val timeSeconds: Double,
    val values: Map<String, Double>,
    val labels: Map<String, String> = emptyMap(),
)

data class TelemetryFrame(
    val timeSeconds: Double,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speedMph: Double,
    val rpm: Int? = null,
    val throttle: Double,
    val brake: Double,
    val steering: Double? = null,
    val gLat: Double,
    val gLong: Double,
    val gear: Int? = null,
    val distanceMeters: Double? = null,
    val coolantTempC: Double? = null,
    val oilTempC: Double? = null,
    val vehicleDiagnostics: VehicleDiagnostics? = null,
    val canVehicleDiagnostics: CanVehicleDiagnostics? = null,
    val sourceMode: SessionMode = SessionMode.TELEMETRY,
    val telemetrySource: TelemetrySourceKind? = null,
    val sourceHealth: TelemetrySourceHealth? = null,
    val vision: VisionFeatureSnapshot? = null,
)

data class VehicleDiagnostics(
    val engineLoadPercent: Double? = null,
    val mafGramsPerSecond: Double? = null,
    val intakeTempC: Double? = null,
    val timingAdvanceDegrees: Double? = null,
    val shortFuelTrim1Percent: Double? = null,
    val longFuelTrim1Percent: Double? = null,
    val shortFuelTrim2Percent: Double? = null,
    val longFuelTrim2Percent: Double? = null,
    val o2Bank1Sensor1Volts: Double? = null,
    val o2Bank2Sensor1Volts: Double? = null,
)

data class CanVehicleDiagnostics(
    val waterPressurePsi: Double? = null,
    val oilPressurePsi: Double? = null,
    val brakePressureRaw: Int? = null,
    val brakePressurePsi: Double? = null,
    val brakePressureZeroOffsetRaw: Int? = null,
    val brakePressureCalibratedPsi: Double? = null,
    val brakePressureZeroOffsetPsi: Double? = null,
    val pedalPositionRaw: Int? = null,
    val pedalPositionPercent: Double? = null,
    val brakeSwitchRaw: Int? = null,
    val brakeSwitchApplied: Boolean? = null,
    val rollRateDegPerSec: Double? = null,
    val pitchRateDegPerSec: Double? = null,
    val yawRateDegPerSec: Double? = null,
    val steeringAngleDeg: Double? = null,
    val lateralG: Double? = null,
    val inlineG: Double? = null,
    val verticalG: Double? = null,
    val fuelLevelGal: Double? = null,
    val batteryVoltage: Double? = null,
    val wheelSpeedFrontLeftMph: Double? = null,
    val wheelSpeedFrontRightMph: Double? = null,
    val wheelSpeedRearLeftMph: Double? = null,
    val wheelSpeedRearRightMph: Double? = null,
    val ecuSpeedMph: Double? = null,
    val gpsSpeedMph: Double? = null,
    val outsideTempC: Double? = null,
    val waterTempC: Double? = null,
    val engineOilTempC: Double? = null,
    val oilFilterTempC: Double? = null,
    val dscRegActive: Boolean? = null,
    val gearRaw: Int? = null,
    val frameAgesMs: Map<String, Long> = emptyMap(),
    val frameStale: Map<String, Boolean> = emptyMap(),
    val rawFrameSamples: Map<String, String> = emptyMap(),
)

data class TelemetrySourceHealth(
    val status: String,
    val motionSource: String? = null,
    val motionConnected: Boolean? = null,
    val motionFixGood: Boolean? = null,
    val motionSampleAgeMs: Long? = null,
    val fallbackStage: String? = null,
    val degradedReason: String? = null,
    val phoneMotionConnected: Boolean? = null,
    val phoneMotionFixGood: Boolean? = null,
    val phoneMotionSampleAgeMs: Long? = null,
    val raceBoxConnected: Boolean = false,
    val raceBoxFixGood: Boolean? = null,
    val raceBoxFixStatus: Int? = null,
    val raceBoxSatellites: Int? = null,
    val raceBoxSampleAgeMs: Long? = null,
    val obdConnected: Boolean = false,
    val obdSampleAgeMs: Long? = null,
    val obdStale: Boolean = false,
    val obdSpeedDeltaMph: Double? = null,
    val obdTransport: String? = null,
    val obdSupportedPids: List<String> = emptyList(),
    val obdReconnectCount: Int = 0,
    val obdChannelAgesMs: Map<String, Long> = emptyMap(),
    val obdChannelStale: Map<String, Boolean> = emptyMap(),
    val canConnected: Boolean = false,
    val canFrameAgesMs: Map<String, Long> = emptyMap(),
    val canFrameStale: Map<String, Boolean> = emptyMap(),
    val canFrameRatesHz: Map<String, Double> = emptyMap(),
    val canDecodeErrors: Int = 0,
    val usbDeviceName: String? = null,
    val rawCanSample: String? = null,
    val rawCanSamplesById: Map<String, String> = emptyMap(),
    val signUnverified: Boolean = false,
)

data class Corner(
    val id: Int,
    val name: String,
    val entryDist: Double,
    val apexDist: Double,
    val exitDist: Double,
    val lat: Double,
    val lon: Double,
    val advice: String,
    val entryLat: Double? = null,
    val entryLon: Double? = null,
    val targetSpeed: Double? = null,
)

data class Track(
    val name: String,
    val lengthMeters: Double,
    val centerLat: Double,
    val centerLon: Double,
    val corners: List<Corner>,
)

data class DriverState(
    val skillLevel: SkillLevel,
    val cognitiveLoad: Double,
    val inputSmoothness: Double,
    val coastingRatio: Double,
)

data class SessionGoal(
    val id: String,
    val focus: SessionGoalFocus,
    val description: String,
    val source: SessionGoalSource,
    val prioritizedActions: List<CoachAction> = emptyList(),
)

data class VisionFeatureSnapshot(
    val timestampMs: Long,
    val averageLuma: Double,
    val motionEnergy: Double,
    val lateralBalance: Double,
    val verticalBalance: Double,
    val centerContrast: Double,
    val framesPerSecond: Double,
)

data class EdgeReasoningWindow(
    val triggerId: String,
    val actionHint: CoachAction,
    val priority: Int,
    val suggestedText: String,
    val phase: CornerPhase,
    val skillLevel: SkillLevel,
    val cornerName: String?,
    val features: Map<String, Double>,
    val causeHint: String? = null,
)

data class ReasonerDecision(
    val speak: Boolean,
    val action: CoachAction,
    val priority: Int,
    val phraseId: String,
    val confidence: Double,
    val text: String,
)

data class CoachingDecision(
    val path: CoachingPath,
    val action: CoachAction? = null,
    val text: String,
    val priority: Int,
    val cornerPhase: CornerPhase,
    val timestampMs: Long,
    val backend: RuntimeBackend,
    val latencyMs: Long? = null,
    val confidence: Double? = null,
    val phraseId: String? = null,
    val id: String = "${path.name.lowercase()}-${action?.name ?: "cue"}-$timestampMs",
)

enum class AudioDispatchStatus {
    CLIP_STARTED,
    TTS_QUEUED,
    TTS_STARTED,
    TTS_UNAVAILABLE,
    DISABLED,
}

data class AudioDispatchEvent(
    val decisionId: String,
    val utteranceId: String,
    val action: CoachAction?,
    val priority: Int,
    val requestedAtMs: Long,
    val dispatchLatencyMs: Long,
    val ttsStartLatencyMs: Long? = null,
    val status: AudioDispatchStatus,
    val fallbackReason: String? = null,
)

data class LiveBackendStatus(
    val backend: RuntimeBackend,
    val state: LiveBackendState,
    val detail: String,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val model: String? = null,
    val usesOnDeviceModel: Boolean,
    val supportedPaths: List<CoachingPath>,
    val accelerator: RuntimeAccelerator = RuntimeAccelerator.NONE,
)

data class RecordedSessionSummary(
    val sessionId: String,
    val mode: SessionMode,
    val trackName: String,
    val coachId: String,
    val frameCount: Int,
    val decisionCount: Int,
    val durationSeconds: Double,
)

data class RecordedSessionArtifact(
    val schemaVersion: Int = 2,
    val id: String,
    val mode: SessionMode,
    val trackName: String,
    val coachId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val summary: RecordedSessionSummary,
    val sessionGoals: List<SessionGoal> = emptyList(),
    val frames: List<TelemetryFrame>,
    val decisions: List<CoachingDecision>,
    val audioEvents: List<AudioDispatchEvent> = emptyList(),
    val artifactPath: String? = null,
    val canDumpPath: String? = null,
)

data class ModelInstallStatus(
    val version: String,
    val isPresent: Boolean,
    val checksumVerified: Boolean,
    val downloadAllowedOnCurrentNetwork: Boolean,
    val filePath: String,
    val fileName: String,
    val supportsNativeAndroid: Boolean,
    val issue: String? = null,
)

fun CoachingPath.bridgeValue(): String = name.lowercase()

fun RuntimeBackend.bridgeValue(): String = name.lowercase()

fun RuntimeAccelerator.bridgeValue(): String = name.lowercase()

fun LiveBackendState.bridgeValue(): String = name.lowercase()

fun SessionMode.bridgeValue(): String = name.lowercase()

fun TelemetrySourceKind.bridgeValue(): String = name.lowercase()

fun ObdTransportPreference.bridgeValue(): String = name.lowercase()

fun SessionGoalFocus.bridgeValue(): String = name.lowercase()

fun SessionGoalSource.bridgeValue(): String = name.lowercase()
