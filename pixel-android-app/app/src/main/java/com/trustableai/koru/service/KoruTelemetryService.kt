package com.trustableai.koru.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trustableai.koru.R
import com.trustableai.koru.audio.CoachAudioDispatcher
import com.trustableai.koru.camera.VisionFeatureStore
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RecordedSessionArtifact
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.bridgeValue
import com.trustableai.koru.runtime.EdgeRuntimeManager
import com.trustableai.koru.runtime.KoruRealtimeEngine
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import com.trustableai.koru.runtime.LiveAudioPolicy
import com.trustableai.koru.runtime.ModelAssetManager
import com.trustableai.koru.runtime.PhraseCatalog
import com.trustableai.koru.runtime.RecordedSessionRecorder
import com.trustableai.koru.runtime.TrackCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KoruTelemetryService : Service() {
    private val tag = "KoruTelemetryService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fusionEngine = TelemetryFusionEngine()
    private var activeTelemetrySource: TelemetrySource = SyntheticTrackSource()

    private lateinit var audioDispatcher: CoachAudioDispatcher
    private lateinit var phraseCatalog: PhraseCatalog
    private lateinit var modelAssetManager: ModelAssetManager
    private lateinit var runtimeManager: EdgeRuntimeManager
    private lateinit var sessionRecorder: RecordedSessionRecorder
    private lateinit var engine: KoruRealtimeEngine

    private var sessionJob: Job? = null
    private var foregroundStarted = false
    private var audioEnabled = true
    private var activeCoachId = "superaj"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        audioDispatcher = CoachAudioDispatcher(this)
        phraseCatalog = PhraseCatalog(this)
        modelAssetManager = ModelAssetManager(this)
        runtimeManager = EdgeRuntimeManager(this, modelAssetManager, phraseCatalog, activeCoachId)
        sessionRecorder = RecordedSessionRecorder(this)
        engine = createRealtimeEngine(TrackCatalog.defaultTrack, emptyList())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionJson = intent.getStringExtra(EXTRA_SESSION_CONFIG) ?: "{}"
                serviceScope.launch { startSession(LiveSessionConfig.fromJson(sessionJson)) }
            }

            ACTION_STOP -> {
                serviceScope.launch { stopSession() }
            }

            ACTION_SET_COACH -> {
                activeCoachId = intent.getStringExtra(EXTRA_COACH_ID) ?: activeCoachId
                engine.setActiveCoach(activeCoachId)
                runtimeManager.updateCoach(activeCoachId)
            }

            ACTION_SET_AUDIO -> {
                audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, audioEnabled)
                audioDispatcher.setEnabled(audioEnabled)
            }

            ACTION_REQUEST_STATUS -> {
                KoruSessionBus.tryEmitStatus(KoruSessionBus.status.value)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        engine.close()
        runtimeManager.close()
        audioDispatcher.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startSession(config: LiveSessionConfig) {
        stopActiveLoop()
        sessionRecorder.discard()
        ensureForeground(
            content = when (config.sessionMode) {
                SessionMode.CAMERA_DIRECT -> "Running direct camera feedback on device"
                SessionMode.DEVICE_TEST -> "Running device camera plus GPS test on device"
                SessionMode.TELEMETRY -> getString(R.string.notification_content)
            },
            requiresLocationType = requiresLocationForegroundType(config) &&
                PhoneImuGpsSource.hasFineLocationPermission(applicationContext),
        )

        activeCoachId = config.coachId
        audioEnabled = config.audioEnabled
        audioDispatcher.setEnabled(audioEnabled)
        phraseCatalog.ensureLoaded()
        runtimeManager.updateCoach(activeCoachId)
        val track = if (config.sessionMode == SessionMode.DEVICE_TEST) null else TrackCatalog.fromName(config.trackName)
        val sessionLabel = if (config.sessionMode == SessionMode.DEVICE_TEST) "Device GPS Test" else (track?.name ?: config.trackName)
        engine = createRealtimeEngine(track, config.sessionGoals)
        engine.setActiveCoach(activeCoachId)
        val telemetrySelection = when (config.sessionMode) {
            SessionMode.CAMERA_DIRECT -> null
            SessionMode.DEVICE_TEST -> TelemetrySourceFactory.select(
                applicationContext,
                com.trustableai.koru.model.TelemetrySourceKind.PHONE_IMU_GPS,
            )
            SessionMode.TELEMETRY -> TelemetrySourceFactory.select(
                applicationContext,
                config.telemetrySource,
                config.obdTransportPreference,
            )
        }
        activeTelemetrySource = telemetrySelection?.source ?: SyntheticTrackSource()
        if ((config.sessionMode == SessionMode.TELEMETRY || config.sessionMode == SessionMode.DEVICE_TEST) && telemetrySelection != null) {
            Log.d(
                tag,
                "Telemetry session requested=${telemetrySelection.requested.bridgeValue()} active=${telemetrySelection.active.bridgeValue()} fallback=${telemetrySelection.isFallback}",
            )
        }

        val startingStatus = LiveBackendStatus(
            backend = RuntimeBackend.DETERMINISTIC,
            state = LiveBackendState.STARTING,
            detail = when (config.sessionMode) {
                SessionMode.CAMERA_DIRECT -> "Starting direct camera reasoning session on device"
                SessionMode.DEVICE_TEST -> telemetrySelection?.detail
                    ?: "Starting device GPS plus camera test path on device"
                SessionMode.TELEMETRY -> telemetrySelection?.detail
                    ?: "Starting telemetry service with live camera fusion enabled"
            },
            usesOnDeviceModel = false,
            supportedPaths = when (config.sessionMode) {
                SessionMode.CAMERA_DIRECT -> listOf(CoachingPath.EDGE)
                SessionMode.DEVICE_TEST -> listOf(CoachingPath.HOT, CoachingPath.EDGE)
                SessionMode.TELEMETRY -> listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE)
            },
        )
        KoruSessionBus.tryEmitStatus(startingStatus)

        val selectedBackend = runtimeManager.warmupPreferredBackend()
        KoruSessionBus.tryEmitStatus(
            when (config.sessionMode) {
                SessionMode.CAMERA_DIRECT -> selectedBackend.copy(
                    state = LiveBackendState.STARTING,
                        detail = "Edge runtime warmed. Waiting for camera lane frames",
                        supportedPaths = listOf(CoachingPath.EDGE),
                    )
                SessionMode.DEVICE_TEST -> {
                    val sourceDetail = telemetrySelection?.detail
                        ?: "Using phone GPS and IMU with live camera fusion."
                    selectedBackend.copy(
                        state = when {
                            telemetrySelection?.isFallback == true &&
                                selectedBackend.state == LiveBackendState.READY -> LiveBackendState.DEGRADED
                            telemetrySelection?.isFallback == true &&
                                selectedBackend.state == LiveBackendState.STARTING -> LiveBackendState.DEGRADED
                            else -> selectedBackend.state
                        },
                        detail = "${selectedBackend.detail} $sourceDetail Track feedforward is disabled in this test lane.",
                        supportedPaths = listOf(CoachingPath.HOT, CoachingPath.EDGE),
                    )
                }
                SessionMode.TELEMETRY -> {
                    val sourceDetail = telemetrySelection?.detail
                        ?: "Telemetry source not selected. Using fused camera vision with telemetry frames."
                    selectedBackend.copy(
                        state = when {
                            telemetrySelection?.isFallback == true &&
                                selectedBackend.state == LiveBackendState.READY -> LiveBackendState.DEGRADED
                            telemetrySelection?.isFallback == true &&
                                selectedBackend.state == LiveBackendState.STARTING -> LiveBackendState.DEGRADED
                            else -> selectedBackend.state
                        },
                        detail = "${selectedBackend.detail} $sourceDetail",
                    )
                }
            },
        )

        sessionRecorder.start(config.sessionMode, sessionLabel, activeCoachId, config.sessionGoals)
        updateNotification(
            when (config.sessionMode) {
                SessionMode.CAMERA_DIRECT -> "Direct camera feedback active (${selectedBackend.backend.bridgeValue()})"
                SessionMode.DEVICE_TEST -> "Device GPS + camera test active (${selectedBackend.backend.bridgeValue()})"
                SessionMode.TELEMETRY -> {
                    val activeSourceLabel = telemetrySelection?.active?.bridgeValue() ?: "synthetic"
                    "Telemetry ${activeSourceLabel} + camera fusion active (${selectedBackend.backend.bridgeValue()})"
                }
            },
        )
        sessionJob = serviceScope.launch {
            when (config.sessionMode) {
                SessionMode.CAMERA_DIRECT -> runCameraDirectLoop(selectedBackend)
                SessionMode.DEVICE_TEST -> runTelemetryLoop(
                    track,
                    telemetrySelection ?: TelemetrySourceFactory.select(
                        applicationContext,
                        com.trustableai.koru.model.TelemetrySourceKind.PHONE_IMU_GPS,
                    ),
                )
                SessionMode.TELEMETRY -> runTelemetryLoop(
                    track,
                    telemetrySelection ?: TelemetrySourceFactory.select(
                        applicationContext,
                        config.telemetrySource,
                        config.obdTransportPreference,
                    ),
                )
            }
        }
    }

    private suspend fun stopSession() {
        stopActiveLoop()
        completeRecordedSession()
        KoruSessionBus.tryEmitStatus(
            LiveBackendStatus(
                backend = RuntimeBackend.DETERMINISTIC,
                state = LiveBackendState.IDLE,
                detail = "Android live session stopped",
                usesOnDeviceModel = false,
                supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
            ),
        )
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private suspend fun stopActiveLoop() {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        engine.close()
        activeTelemetrySource.stop()
    }

    private suspend fun runTelemetryLoop(
        track: com.trustableai.koru.model.Track?,
        telemetrySelection: TelemetrySourceSelection,
    ) {
        activeTelemetrySource = telemetrySelection.source
        activeTelemetrySource.start()
        Log.d(tag, "Telemetry loop started source=${telemetrySelection.active.bridgeValue()} cameraFusion=true")
        var step = 0
        val activeTrack = track ?: TrackCatalog.defaultTrack
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        var nextTickNanos = startedAtNanos
        var lastHealthStatusAtMs = 0L
        while (currentCoroutineContext().isActive) {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (nowNanos < nextTickNanos) {
                delay(((nextTickNanos - nowNanos) / 1_000_000L).coerceAtLeast(1L))
                continue
            }
            val elapsedSeconds = (nowNanos - startedAtNanos) / 1_000_000_000.0
            val rawFrame = activeTelemetrySource.nextFrame(step, activeTrack, elapsedSeconds)
            val frame = fusionEngine.fuse(rawFrame, VisionFeatureStore.latest.value)
            maybeEmitHardwareStatus(frame, telemetrySelection, nowNanos / 1_000_000L, lastHealthStatusAtMs)
                ?.let { lastHealthStatusAtMs = it }
            emitFrameAndDecisions(frame)
            step += 1
            val frameIntervalNanos = activeTelemetrySource.frameIntervalNanos
            nextTickNanos += frameIntervalNanos
            val afterFrameNanos = SystemClock.elapsedRealtimeNanos()
            if (afterFrameNanos - nextTickNanos > frameIntervalNanos * 2L) {
                nextTickNanos = afterFrameNanos + frameIntervalNanos
            }
        }
    }

    private fun maybeEmitHardwareStatus(
        frame: TelemetryFrame,
        telemetrySelection: TelemetrySourceSelection,
        nowMs: Long,
        lastHealthStatusAtMs: Long,
    ): Long? {
        val health = frame.sourceHealth ?: return null
        if (nowMs - lastHealthStatusAtMs < HARDWARE_STATUS_INTERVAL_MS) return null
        val currentStatus = KoruSessionBus.status.value
        val fallbackStage = health.fallbackStage
        val degraded = when (telemetrySelection.active) {
            TelemetrySourceKind.RACEBOX_OBD_FUSION ->
                fallbackStage != null && fallbackStage != "full"
            TelemetrySourceKind.PHONE_IMU_GPS ->
                fallbackStage == "no_live_data" || health.motionConnected == false
            TelemetrySourceKind.AIM_CAN_USB ->
                fallbackStage != null && fallbackStage != "aim_can_full"
            TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH ->
                fallbackStage != null && fallbackStage != "aim_can_full"
            else ->
                telemetrySelection.isFallback
        }
        val nextState = when {
            degraded -> LiveBackendState.DEGRADED
            currentStatus.state == LiveBackendState.DEGRADED -> LiveBackendState.READY
            else -> currentStatus.state
        }
        val stageDetail = fallbackStage?.let { " stage=$it" }.orEmpty()
        val degradedDetail = health.degradedReason?.let { " reason=$it" }.orEmpty()
        KoruSessionBus.tryEmitStatus(
            currentStatus.copy(
                state = nextState,
                detail = "${currentStatus.backend.bridgeValue()} active. ${health.status}$stageDetail$degradedDetail",
            ),
        )
        return nowMs
    }

    private suspend fun runCameraDirectLoop(runtimeStatus: LiveBackendStatus) {
        KoruSessionBus.tryEmitStatus(
            runtimeStatus.copy(
                state = LiveBackendState.STARTING,
                detail = "Waiting for camera lane frames before starting reasoning",
                supportedPaths = listOf(CoachingPath.EDGE),
            ),
        )

        val startedAtMs = System.currentTimeMillis()
        var lastVisionTimestampMs = -1L
        var readyEmitted = false

        while (currentCoroutineContext().isActive) {
            val snapshot = VisionFeatureStore.latest.value
            if (snapshot == null || snapshot.timestampMs == lastVisionTimestampMs) {
                delay(50L)
                continue
            }

            if (!readyEmitted) {
                readyEmitted = true
                KoruSessionBus.tryEmitStatus(
                    runtimeStatus.copy(
                        state = LiveBackendState.READY,
                        detail = "Direct camera reasoning active on device",
                        supportedPaths = listOf(CoachingPath.EDGE),
                    ),
                )
            }

            lastVisionTimestampMs = snapshot.timestampMs
            val elapsedSeconds = (System.currentTimeMillis() - startedAtMs) / 1000.0
            val frame = TelemetryFrame(
                timeSeconds = elapsedSeconds,
                latitude = 0.0,
                longitude = 0.0,
                speedMph = 0.0,
                throttle = 0.0,
                brake = 0.0,
                gLat = 0.0,
                gLong = 0.0,
                sourceMode = SessionMode.CAMERA_DIRECT,
                vision = snapshot,
            )
            emitFrameAndDecisions(frame)
            delay(50L)
        }
    }

    private suspend fun emitFrameAndDecisions(frame: TelemetryFrame) {
        sessionRecorder.recordFrame(frame)
        KoruSessionBus.tryEmitFrame(frame)
        engine.processFrame(frame).forEach { decision ->
            Log.d(
                tag,
                "Live decision source=${frame.telemetrySource?.bridgeValue() ?: "none"} path=${decision.path.bridgeValue()} backend=${decision.backend.bridgeValue()} text=${decision.text}",
            )
            sessionRecorder.recordDecision(decision)
            KoruSessionBus.tryEmitDecision(decision)
            if (audioEnabled) {
                val gate = LiveAudioPolicy.shouldSpeak(frame, decision)
                if (gate.allowSpeak) {
                    audioDispatcher.speak(
                        decision.text,
                        "${decision.path.bridgeValue()}-${decision.timestampMs}",
                        decision.priority,
                        decision.action,
                        decision.id,
                        sessionRecorder::recordAudioEvent,
                    )
                } else {
                    Log.d(
                        tag,
                        "Audio suppressed source=${frame.telemetrySource?.bridgeValue() ?: "none"} path=${decision.path.bridgeValue()} reason=${gate.reason}",
                    )
                }
            }
        }
    }

    private fun completeRecordedSession(): RecordedSessionArtifact? {
        val artifact = sessionRecorder.finish() ?: return null
        KoruSessionBus.tryEmitSavedSession(artifact)
        return artifact
    }

    private fun createRealtimeEngine(
        track: com.trustableai.koru.model.Track?,
        sessionGoals: List<com.trustableai.koru.model.SessionGoal>,
    ): KoruRealtimeEngine {
        return KoruRealtimeEngine(
            track = track,
            phraseCatalog = phraseCatalog,
            sessionGoals = sessionGoals,
            reasonerProvider = { runtimeManager.currentReasoner() },
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        if (!foregroundStarted) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun ensureForeground(content: String, requiresLocationType: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(content),
            foregroundServiceTypes(requiresLocationType),
        )
        foregroundStarted = true
    }

    private fun foregroundServiceTypes(requiresLocationType: Boolean): Int {
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            (if (requiresLocationType) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
    }

    private fun requiresLocationForegroundType(config: LiveSessionConfig): Boolean {
        return config.sessionMode == SessionMode.DEVICE_TEST ||
            (config.sessionMode == SessionMode.TELEMETRY &&
                config.telemetrySource in setOf(
                    TelemetrySourceKind.PHONE_IMU_GPS,
                    TelemetrySourceKind.RACEBOX_OBD_FUSION,
                    TelemetrySourceKind.AIM_CAN_USB,
                    TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH,
                ))
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "koru_live_session"
        private const val NOTIFICATION_ID = 7301
        private const val HARDWARE_STATUS_INTERVAL_MS = 1000L

        private const val ACTION_START = "com.trustableai.koru.action.START"
        private const val ACTION_STOP = "com.trustableai.koru.action.STOP"
        private const val ACTION_SET_COACH = "com.trustableai.koru.action.SET_COACH"
        private const val ACTION_SET_AUDIO = "com.trustableai.koru.action.SET_AUDIO"
        private const val ACTION_REQUEST_STATUS = "com.trustableai.koru.action.REQUEST_STATUS"

        private const val EXTRA_SESSION_CONFIG = "session_config"
        private const val EXTRA_COACH_ID = "coach_id"
        private const val EXTRA_AUDIO_ENABLED = "audio_enabled"

        fun startIntent(context: Context, sessionConfig: String): Intent {
            return Intent(context, KoruTelemetryService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_CONFIG, sessionConfig)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, KoruTelemetryService::class.java).setAction(ACTION_STOP)
        }

        fun setCoachIntent(context: Context, coachId: String): Intent {
            return Intent(context, KoruTelemetryService::class.java)
                .setAction(ACTION_SET_COACH)
                .putExtra(EXTRA_COACH_ID, coachId)
        }

        fun setAudioIntent(context: Context, enabled: Boolean): Intent {
            return Intent(context, KoruTelemetryService::class.java)
                .setAction(ACTION_SET_AUDIO)
                .putExtra(EXTRA_AUDIO_ENABLED, enabled)
        }

        fun requestStatusIntent(context: Context): Intent {
            return Intent(context, KoruTelemetryService::class.java).setAction(ACTION_REQUEST_STATUS)
        }
    }
}
