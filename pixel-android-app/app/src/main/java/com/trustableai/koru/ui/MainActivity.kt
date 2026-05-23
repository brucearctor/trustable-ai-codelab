package com.trustableai.koru.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.trustableai.koru.R
import com.trustableai.koru.camera.CameraLaneAnalyzer
import com.trustableai.koru.camera.VisionFeatureStore
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.runtime.KoruSessionBus
import com.trustableai.koru.runtime.LiveSessionConfig
import com.trustableai.koru.service.BluetoothRuntimePermissions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val tag = "KoruMainActivity"
    private val viewModel: LiveSessionViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService
    private var cameraPreview: PreviewView? = null
    private var cameraBoundPreview: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingLocationConfig: LiveSessionConfig? = null
    private var pendingBluetoothConfig: LiveSessionConfig? = null
    @Volatile private var lastCameraStatusUpdateMs = 0L

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setCameraStatus(getString(R.string.camera_lane_ready))
                bindCameraLaneIfReady()
            } else {
                viewModel.setCameraStatus(getString(R.string.camera_lane_denied))
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val config = pendingLocationConfig
            pendingLocationConfig = null
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (granted && config != null) {
                Log.d(tag, "Location permission granted; starting native live session")
                viewModel.startSession(config)
            } else if (!granted) {
                if (config?.telemetrySource == TelemetrySourceKind.AIM_CAN_USB) {
                    KoruSessionBus.tryEmitStatus(
                        LiveBackendStatus(
                            backend = RuntimeBackend.DETERMINISTIC,
                            state = LiveBackendState.DEGRADED,
                            detail = "Location permission denied. AiM CAN USB can still run, but phone GPS/IMU fallback is unavailable.",
                            usesOnDeviceModel = false,
                            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                        ),
                    )
                    viewModel.startSession(config)
                } else {
                    KoruSessionBus.tryEmitStatus(
                        LiveBackendStatus(
                            backend = RuntimeBackend.DETERMINISTIC,
                            state = LiveBackendState.ERROR,
                            detail = "Phone IMU + GPS requires precise location permission.",
                            usesOnDeviceModel = false,
                            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                        ),
                    )
                }
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val config = pendingBluetoothConfig
            pendingBluetoothConfig = null
            val granted = requiredBluetoothPermissions().all { permission -> grants[permission] == true }
            if (granted && config != null) {
                Log.d(tag, "Bluetooth permissions granted; starting hardware telemetry session")
                startLiveSessionWithPermissions(config, allowBluetoothPrompt = false)
            } else if (!granted) {
                if (config != null &&
                    config.telemetrySource in setOf(TelemetrySourceKind.RACEBOX_OBD_FUSION, TelemetrySourceKind.AIM_CAN_USB)
                ) {
                    KoruSessionBus.tryEmitStatus(
                        LiveBackendStatus(
                            backend = RuntimeBackend.DETERMINISTIC,
                            state = LiveBackendState.DEGRADED,
                            detail = "Bluetooth permission denied. RaceBox fallback is unavailable; starting with remaining real-data sources.",
                            usesOnDeviceModel = false,
                            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                        ),
                    )
                    startLiveSessionWithPermissions(config, allowBluetoothPrompt = false)
                } else {
                    KoruSessionBus.tryEmitStatus(
                        LiveBackendStatus(
                            backend = RuntimeBackend.DETERMINISTIC,
                            state = LiveBackendState.ERROR,
                            detail = "RaceBox and OBDLink telemetry require Bluetooth scan/connect permission.",
                            usesOnDeviceModel = false,
                            supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                        ),
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            KoruApp(
                viewModel = viewModel,
                onStartRequested = { startLiveSessionWithPermissions() },
                onBindCameraPreview = { previewView ->
                    cameraPreview = previewView
                    bindCameraLaneIfReady()
                },
            )
        }
        ensureCameraPermission()

        // Observe camera toggle.
        lifecycleScope.launch {
            viewModel.uiState
                .map { it.cameraEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        bindCameraLaneIfReady()
                    } else {
                        unbindCamera()
                    }
                }
        }
    }

    override fun onDestroy() {
        VisionFeatureStore.clear()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startLiveSessionWithPermissions(
        config: LiveSessionConfig = viewModel.currentConfig(),
        allowBluetoothPrompt: Boolean = true,
    ) {
        if (allowBluetoothPrompt && requiresBluetooth(config) && !BluetoothRuntimePermissions.hasBluetoothPermissions(this)) {
            pendingBluetoothConfig = config
            KoruSessionBus.tryEmitStatus(
                LiveBackendStatus(
                    backend = RuntimeBackend.DETERMINISTIC,
                    state = LiveBackendState.STARTING,
                    detail = "Requesting Bluetooth permission for RaceBox and OBDLink telemetry.",
                    usesOnDeviceModel = false,
                    supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                ),
            )
            bluetoothPermissionLauncher.launch(requiredBluetoothPermissions())
            return
        }
        if (requiresFineLocation(config) && !hasFineLocationPermission()) {
            pendingLocationConfig = config
            KoruSessionBus.tryEmitStatus(
                LiveBackendStatus(
                    backend = RuntimeBackend.DETERMINISTIC,
                    state = LiveBackendState.STARTING,
                    detail = "Requesting location permission for phone IMU + GPS telemetry.",
                    usesOnDeviceModel = false,
                    supportedPaths = listOf(CoachingPath.HOT, CoachingPath.FEEDFORWARD, CoachingPath.EDGE),
                ),
            )
            locationPermissionLauncher.launch(requiredLocationPermissions())
            return
        }
        viewModel.startSession(config)
    }

    private fun requiresFineLocation(config: LiveSessionConfig): Boolean {
        return config.sessionMode == SessionMode.DEVICE_TEST ||
            (config.sessionMode == SessionMode.TELEMETRY &&
                config.telemetrySource in setOf(
                    TelemetrySourceKind.PHONE_IMU_GPS,
                    TelemetrySourceKind.RACEBOX_OBD_FUSION,
                    TelemetrySourceKind.AIM_CAN_USB,
                    TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH,
                ))
    }

    private fun requiresBluetooth(config: LiveSessionConfig): Boolean {
        return config.sessionMode == SessionMode.TELEMETRY &&
            config.telemetrySource in setOf(
                TelemetrySourceKind.RACEBOX_BLE,
                TelemetrySourceKind.OBD_BLUETOOTH,
                TelemetrySourceKind.RACEBOX_OBD_FUSION,
                TelemetrySourceKind.AIM_CAN_USB,
                TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH,
            )
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requiredLocationPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun ensureCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> {
                viewModel.setCameraStatus(getString(R.string.camera_lane_ready))
                bindCameraLaneIfReady()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                viewModel.setCameraStatus(getString(R.string.camera_lane_waiting))
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> {
                viewModel.setCameraStatus(getString(R.string.camera_lane_waiting))
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun bindCameraLaneIfReady() {
        if (!viewModel.uiState.value.cameraEnabled) return
        val previewView = cameraPreview ?: return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (cameraBoundPreview === previewView) return
        cameraBoundPreview = previewView

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzerUseCase ->
                    analyzerUseCase.setAnalyzer(
                        cameraExecutor,
                        CameraLaneAnalyzer { snapshot ->
                            VisionFeatureStore.update(snapshot)
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastCameraStatusUpdateMs >= CAMERA_STATUS_UPDATE_INTERVAL_MS) {
                                lastCameraStatusUpdateMs = nowMs
                                runOnUiThread {
                                    viewModel.setCameraStatus(
                                        String.format(
                                            Locale.US,
                                            "Camera lane active | fps %.1f | motion %.2f | balance %.2f",
                                            snapshot.framesPerSecond,
                                            snapshot.motionEnergy,
                                            snapshot.lateralBalance,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (error: Exception) {
                Log.e(tag, "Failed to bind camera lane", error)
                cameraBoundPreview = null
                viewModel.setCameraStatus("Camera lane error: ${error.message ?: "unknown"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        cameraBoundPreview = null
        viewModel.setCameraStatus("Camera disabled")
        Log.d(tag, "Camera unbound (toggle off)")
    }

    companion object {
        private const val CAMERA_STATUS_UPDATE_INTERVAL_MS = 250L
    }
}
