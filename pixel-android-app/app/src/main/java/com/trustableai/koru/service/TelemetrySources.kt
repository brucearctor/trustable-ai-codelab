package com.trustableai.koru.service

import android.content.Context
import android.os.SystemClock
import com.trustableai.koru.model.CanVehicleDiagnostics
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.Track
import com.trustableai.koru.model.VisionFeatureSnapshot
import com.trustableai.koru.model.bridgeValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

interface TelemetrySource {
    val kind: TelemetrySourceKind

    val frameIntervalNanos: Long
        get() = DEFAULT_FRAME_INTERVAL_NANOS

    suspend fun start()

    suspend fun stop()

    fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame

    companion object {
        const val DEFAULT_FRAME_INTERVAL_NANOS = 100_000_000L
    }
}

class RaceBoxBleSource(
    private val client: RaceBoxDataClient,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : TelemetrySource {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.RACEBOX_BLE
    override val frameIntervalNanos: Long = RACEBOX_FRAME_INTERVAL_NANOS

    private var distanceMeters = 0.0
    private var lastTowMs: Long? = null
    private var lastSpeedMps: Double? = null

    override suspend fun start() {
        client.start()
    }

    override suspend fun stop() {
        client.stop()
    }

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val now = elapsedRealtimeMs()
        val sample = client.latestSample()
        val health = HardwareTelemetryMath.sourceHealth(
            nowElapsedMs = now,
            raceBox = sample,
            raceBoxStatus = client.status(),
            obd = null,
            obdStatus = ObdClientStatus(false, "OBD disabled"),
            obdStaleMs = OBD_STALE_MS,
            obdDiagnosticStaleMs = OBD_DIAGNOSTIC_STALE_MS,
            raceBoxStaleMs = RACEBOX_STALE_MS,
        )
        if (!isUsableRaceBoxSample(sample, now)) {
            return waitingFrame(elapsedSeconds, track, kind, health)
        }
        val liveSample = sample ?: return waitingFrame(elapsedSeconds, track, kind, health)
        updateDistance(liveSample)
        val brake = HardwareTelemetryMath.estimateBrakeFromLongitudinalG(liveSample.gLong)
        val throttle = HardwareTelemetryMath.estimateThrottleFromMotion(
            speedMph = liveSample.speedMph,
            gLat = liveSample.gLat,
            gLong = liveSample.gLong,
            brake = brake,
        )
        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = liveSample.latitude,
            longitude = liveSample.longitude,
            altitude = liveSample.altitudeMeters,
            speedMph = liveSample.speedMph,
            rpm = null,
            throttle = throttle,
            brake = brake,
            steering = null,
            gLat = liveSample.gLat,
            gLong = liveSample.gLong,
            gear = HardwareTelemetryMath.estimateGear(liveSample.speedMph),
            distanceMeters = distanceMeters,
            telemetrySource = kind,
            sourceHealth = health,
        )
    }

    private fun updateDistance(sample: RaceBoxSample) {
        val previousTow = lastTowMs
        val previousSpeed = lastSpeedMps
        if (previousTow != null && previousSpeed != null && sample.iTowMs != previousTow) {
            val deltaMs = gpsTowDeltaMs(previousTow, sample.iTowMs)
            if (deltaMs in 1L..1000L) {
                distanceMeters += ((previousSpeed + sample.speedMps) / 2.0) * (deltaMs / 1000.0)
            }
        }
        lastTowMs = sample.iTowMs
        lastSpeedMps = sample.speedMps
    }
}

class ObdBluetoothSource(
    private val client: ObdDataClient,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : TelemetrySource {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.OBD_BLUETOOTH
    override val frameIntervalNanos: Long = OBD_FRAME_INTERVAL_NANOS

    override suspend fun start() {
        client.start()
    }

    override suspend fun stop() {
        client.stop()
    }

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val now = elapsedRealtimeMs()
        val sample = client.latestSample()
        val freshSpeed = sample.freshDouble(ObdPid.VEHICLE_SPEED, now) { speedMph }
        val freshRpm = sample.freshInt(ObdPid.ENGINE_RPM, now) { rpm }
        val freshThrottle = sample.freshDouble(ObdPid.THROTTLE_POSITION, now) { throttlePercent }
        val health = HardwareTelemetryMath.sourceHealth(
            nowElapsedMs = now,
            raceBox = null,
            raceBoxStatus = RaceBoxClientStatus(false, false, "RaceBox disabled"),
            obd = sample,
            obdStatus = client.status(),
            obdStaleMs = OBD_STALE_MS,
            obdDiagnosticStaleMs = OBD_DIAGNOSTIC_STALE_MS,
            raceBoxStaleMs = RACEBOX_STALE_MS,
        )
        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = track.centerLat,
            longitude = track.centerLon,
            speedMph = freshSpeed ?: 0.0,
            rpm = freshRpm,
            throttle = freshThrottle ?: 0.0,
            brake = 0.0,
            steering = null,
            gLat = 0.0,
            gLong = 0.0,
            gear = freshSpeed?.let(HardwareTelemetryMath::estimateGear),
            coolantTempC = sample.freshDouble(ObdPid.COOLANT_TEMP, now) { coolantTempC },
            oilTempC = sample.freshDouble(ObdPid.ENGINE_OIL_TEMP, now) { oilTempC },
            vehicleDiagnostics = sample?.diagnostics(now, OBD_DIAGNOSTIC_STALE_MS),
            telemetrySource = kind,
            sourceHealth = health,
        )
    }
}

class RaceBoxObdFusionSource(
    private val raceBoxClient: RaceBoxDataClient,
    private val obdClient: ObdDataClient,
    private val phoneFallbackSource: TelemetrySource? = null,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : TelemetrySource {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.RACEBOX_OBD_FUSION
    override val frameIntervalNanos: Long = RACEBOX_FRAME_INTERVAL_NANOS

    private var distanceMeters = 0.0
    private var lastTowMs: Long? = null
    private var lastSpeedMps: Double? = null

    override suspend fun start() {
        raceBoxClient.start()
        obdClient.start()
        phoneFallbackSource?.start()
    }

    override suspend fun stop() {
        raceBoxClient.stop()
        obdClient.stop()
        phoneFallbackSource?.stop()
    }

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val now = elapsedRealtimeMs()
        val raceBox = raceBoxClient.latestSample()
        val obd = obdClient.latestSample()
        val obdThrottle = obd.freshDouble(ObdPid.THROTTLE_POSITION, now) { throttlePercent }
        val obdRpm = obd.freshInt(ObdPid.ENGINE_RPM, now) { rpm }
        val health = HardwareTelemetryMath.sourceHealth(
            nowElapsedMs = now,
            raceBox = raceBox,
            raceBoxStatus = raceBoxClient.status(),
            obd = obd,
            obdStatus = obdClient.status(),
            obdStaleMs = OBD_STALE_MS,
            obdDiagnosticStaleMs = OBD_DIAGNOSTIC_STALE_MS,
            raceBoxStaleMs = RACEBOX_STALE_MS,
        )
        if (!isUsableRaceBoxSample(raceBox, now)) {
            return phoneFallbackFrame(
                step = step,
                track = track,
                elapsedSeconds = elapsedSeconds,
                health = health,
                obd = obd,
                nowElapsedMs = now,
            )
        }
        val liveRaceBox = raceBox ?: return waitingFrame(elapsedSeconds, track, kind, health)

        updateDistance(liveRaceBox)
        val brake = HardwareTelemetryMath.estimateBrakeFromLongitudinalG(liveRaceBox.gLong)
        val throttle = obdThrottle
            ?: HardwareTelemetryMath.estimateThrottleFromMotion(
                speedMph = liveRaceBox.speedMph,
                gLat = liveRaceBox.gLat,
                gLong = liveRaceBox.gLong,
                brake = brake,
            )

        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = liveRaceBox.latitude,
            longitude = liveRaceBox.longitude,
            altitude = liveRaceBox.altitudeMeters,
            speedMph = liveRaceBox.speedMph,
            rpm = obdRpm,
            throttle = throttle,
            brake = brake,
            steering = null,
            gLat = liveRaceBox.gLat,
            gLong = liveRaceBox.gLong,
            gear = HardwareTelemetryMath.estimateGear(liveRaceBox.speedMph),
            distanceMeters = distanceMeters,
            coolantTempC = obd.freshDouble(ObdPid.COOLANT_TEMP, now) { coolantTempC },
            oilTempC = obd.freshDouble(ObdPid.ENGINE_OIL_TEMP, now) { oilTempC },
            vehicleDiagnostics = obd?.diagnostics(now, OBD_DIAGNOSTIC_STALE_MS),
            telemetrySource = kind,
            sourceHealth = health.withFallbackStage(
                fallbackStage = if (health.obdStale) "racebox_only" else "full",
                motionSource = "racebox",
                motionConnected = true,
                motionFixGood = liveRaceBox.fixOk,
                motionSampleAgeMs = now - liveRaceBox.receivedAtElapsedMs,
                degradedReason = if (health.obdStale) "obd_unavailable_or_stale" else null,
            ),
        )
    }

    private fun phoneFallbackFrame(
        step: Int,
        track: Track,
        elapsedSeconds: Double,
        health: TelemetrySourceHealth,
        obd: ObdSample?,
        nowElapsedMs: Long,
    ): TelemetryFrame {
        val phoneFrame = phoneFallbackSource?.nextFrame(step, track, elapsedSeconds)
        if (phoneFrame != null && isUsablePhoneFrame(phoneFrame)) {
            val obdThrottle = obd.freshDouble(ObdPid.THROTTLE_POSITION, nowElapsedMs) { throttlePercent }
            val stage = if (health.obdStale) "phone_only" else "phone_obd_fusion"
            val degradedReason = if (health.obdStale) {
                "racebox_unavailable_obd_unavailable_or_stale"
            } else {
                "racebox_unavailable_using_phone_motion"
            }
            return phoneFrame.copy(
                rpm = obd.freshInt(ObdPid.ENGINE_RPM, nowElapsedMs) { rpm },
                throttle = obdThrottle ?: phoneFrame.throttle,
                coolantTempC = obd.freshDouble(ObdPid.COOLANT_TEMP, nowElapsedMs) { coolantTempC },
                oilTempC = obd.freshDouble(ObdPid.ENGINE_OIL_TEMP, nowElapsedMs) { oilTempC },
                vehicleDiagnostics = obd?.diagnostics(nowElapsedMs, OBD_DIAGNOSTIC_STALE_MS),
                telemetrySource = kind,
                sourceHealth = health.withFallbackStage(
                    fallbackStage = stage,
                    motionSource = "phone",
                    motionConnected = true,
                    motionFixGood = true,
                    motionSampleAgeMs = phoneFrame.sourceHealth?.motionSampleAgeMs,
                    phoneMotionConnected = true,
                    phoneMotionFixGood = true,
                    phoneMotionSampleAgeMs = phoneFrame.sourceHealth?.motionSampleAgeMs,
                    degradedReason = degradedReason,
                ),
            )
        }

        return waitingFrame(
            elapsedSeconds = elapsedSeconds,
            track = track,
            kind = kind,
            health = health.withFallbackStage(
                fallbackStage = "no_live_data",
                motionSource = null,
                motionConnected = false,
                motionFixGood = false,
                degradedReason = "racebox_and_phone_motion_unavailable",
            ),
        )
    }

    private fun updateDistance(sample: RaceBoxSample) {
        val previousTow = lastTowMs
        val previousSpeed = lastSpeedMps
        if (previousTow != null && previousSpeed != null && sample.iTowMs != previousTow) {
            val deltaMs = gpsTowDeltaMs(previousTow, sample.iTowMs)
            if (deltaMs in 1L..1000L) {
                distanceMeters += ((previousSpeed + sample.speedMps) / 2.0) * (deltaMs / 1000.0)
            }
        }
        lastTowMs = sample.iTowMs
        lastSpeedMps = sample.speedMps
    }
}

class AimCanUsbSource(
    private val canClient: AimCanDataClient,
    private val raceBoxClient: RaceBoxDataClient? = null,
    private val phoneFallbackSource: TelemetrySource? = null,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    internal val transportLabel: String = "AiM CAN USB",
) : TelemetrySource {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.AIM_CAN_USB
    override val frameIntervalNanos: Long = AIM_CAN_FRAME_INTERVAL_NANOS

    private var distanceMeters = 0.0
    private var lastDistanceElapsedSeconds: Double? = null

    override suspend fun start() {
        canClient.start()
        raceBoxClient?.start()
        phoneFallbackSource?.start()
    }

    override suspend fun stop() {
        canClient.stop()
        raceBoxClient?.stop()
        phoneFallbackSource?.stop()
    }

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val now = elapsedRealtimeMs()
        val can = canClient.latestSample()
        val health = aimCanHealth(
            nowElapsedMs = now,
            can = can,
            canStatus = canClient.status(),
            raceBox = raceBoxClient?.latestSample(),
            raceBoxStatus = raceBoxClient?.status(),
            transportLabel = transportLabel,
        )

        if (isAimCanFull(can, now)) {
            val liveCan = can ?: return noLiveDataFrame(elapsedSeconds, track, health, "aim_can_unavailable")
            val speedMph = liveCan.primaryFreshSpeedMph(now) ?: 0.0
            return liveCan.toTelemetryFrame(
                elapsedSeconds = elapsedSeconds,
                track = track,
                speedMph = speedMph,
                distanceMeters = updateDistance(elapsedSeconds, speedMph),
                health = health.copy(
                    motionSource = "aim_can",
                    motionConnected = true,
                    motionFixGood = true,
                    motionSampleAgeMs = liveCan.ageMs(AimCanFrameIds.GPS_POSITION, now),
                    fallbackStage = "aim_can_full",
                    degradedReason = null,
                    signUnverified = true,
                ),
            )
        }

        val vehicleFresh = hasFreshAimCanVehicleChannels(can, now)
        val raceBox = raceBoxClient?.latestSample()
        if (isUsableRaceBoxSample(raceBox, now)) {
            val liveRaceBox = raceBox ?: return noLiveDataFrame(elapsedSeconds, track, health, "racebox_unavailable")
            val speedMph = liveRaceBox.speedMph
            val stage = if (vehicleFresh) "aim_can_racebox_motion" else "racebox_only"
            return raceBoxFrame(
                sample = liveRaceBox,
                can = can,
                nowElapsedMs = now,
                elapsedSeconds = elapsedSeconds,
                speedMph = speedMph,
                health = health.copy(
                    motionSource = "racebox",
                    motionConnected = true,
                    motionFixGood = liveRaceBox.fixOk,
                    motionSampleAgeMs = now - liveRaceBox.receivedAtElapsedMs,
                    fallbackStage = stage,
                    degradedReason = if (vehicleFresh) {
                        "aim_can_motion_or_gps_stale_using_racebox_motion"
                    } else {
                        "aim_can_unavailable_using_racebox_motion"
                    },
                    signUnverified = vehicleFresh,
                ),
            )
        }

        val phoneFrame = phoneFallbackSource?.nextFrame(step, track, elapsedSeconds)
        if (phoneFrame != null && isUsablePhoneFrame(phoneFrame)) {
            val stage = if (vehicleFresh) "aim_can_phone_motion" else "phone_only"
            return phoneFrame.enrichWithCan(
                can = can,
                nowElapsedMs = now,
                health = health.copy(
                    motionSource = "phone",
                    motionConnected = true,
                    motionFixGood = true,
                    motionSampleAgeMs = phoneFrame.sourceHealth?.motionSampleAgeMs,
                    phoneMotionConnected = true,
                    phoneMotionFixGood = true,
                    phoneMotionSampleAgeMs = phoneFrame.sourceHealth?.phoneMotionSampleAgeMs
                        ?: phoneFrame.sourceHealth?.motionSampleAgeMs,
                    fallbackStage = stage,
                    degradedReason = if (vehicleFresh) {
                        "aim_can_motion_or_gps_stale_using_phone_motion"
                    } else {
                        "aim_can_and_racebox_unavailable_using_phone_motion"
                    },
                    signUnverified = vehicleFresh,
                ),
            )
        }

        return noLiveDataFrame(
            elapsedSeconds = elapsedSeconds,
            track = track,
            health = health,
            reason = "aim_can_racebox_and_phone_motion_unavailable",
        )
    }

    private fun raceBoxFrame(
        sample: RaceBoxSample,
        can: AimCanSample?,
        nowElapsedMs: Long,
        elapsedSeconds: Double,
        speedMph: Double,
        health: TelemetrySourceHealth,
    ): TelemetryFrame {
        val brake = can.freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) {
            normalizedBrakePercent()
        } ?: HardwareTelemetryMath.estimateBrakeFromLongitudinalG(sample.gLong)
        val throttle = can.freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) {
            pedalPositionPercent
        } ?: HardwareTelemetryMath.estimateThrottleFromMotion(
            speedMph = speedMph,
            gLat = sample.gLat,
            gLong = sample.gLong,
            brake = brake,
        )
        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = sample.latitude,
            longitude = sample.longitude,
            altitude = sample.altitudeMeters,
            speedMph = speedMph,
            rpm = can.freshInt(AimCanFrameIds.CORE, nowElapsedMs) { rpm },
            throttle = throttle,
            brake = brake,
            steering = can.freshDouble(AimCanFrameIds.MOTION, nowElapsedMs) { steeringAngleDeg },
            gLat = sample.gLat,
            gLong = sample.gLong,
            gear = can.freshInt(AimCanFrameIds.CORE, nowElapsedMs) { usableGear() }
                ?: HardwareTelemetryMath.estimateGear(speedMph),
            distanceMeters = updateDistance(elapsedSeconds, speedMph),
            coolantTempC = can.freshDouble(AimCanFrameIds.CORE, nowElapsedMs) { waterTempC },
            oilTempC = can.freshOilTempC(nowElapsedMs),
            canVehicleDiagnostics = can?.diagnostics(nowElapsedMs),
            telemetrySource = kind,
            sourceHealth = health,
        )
    }

    private fun AimCanSample.toTelemetryFrame(
        elapsedSeconds: Double,
        track: Track,
        speedMph: Double,
        distanceMeters: Double,
        health: TelemetrySourceHealth,
    ): TelemetryFrame {
        val now = elapsedRealtimeMs()
        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = gpsLatitude ?: track.centerLat,
            longitude = gpsLongitude ?: track.centerLon,
            altitude = null,
            speedMph = speedMph,
            rpm = freshInt(AimCanFrameIds.CORE, now) { rpm },
            throttle = freshDouble(AimCanFrameIds.CONTROLS, now) { pedalPositionPercent } ?: 0.0,
            brake = freshDouble(AimCanFrameIds.CONTROLS, now) { normalizedBrakePercent() } ?: 0.0,
            steering = freshDouble(AimCanFrameIds.MOTION, now) { steeringAngleDeg },
            gLat = freshDouble(AimCanFrameIds.MOTION, now) { lateralG } ?: 0.0,
            gLong = freshDouble(AimCanFrameIds.MOTION, now) { inlineG } ?: 0.0,
            gear = freshInt(AimCanFrameIds.CORE, now) { usableGear() },
            distanceMeters = distanceMeters,
            coolantTempC = freshDouble(AimCanFrameIds.CORE, now) { waterTempC },
            oilTempC = freshOilTempC(now),
            canVehicleDiagnostics = diagnostics(now),
            telemetrySource = kind,
            sourceHealth = health,
        )
    }

    private fun TelemetryFrame.enrichWithCan(
        can: AimCanSample?,
        nowElapsedMs: Long,
        health: TelemetrySourceHealth,
    ): TelemetryFrame {
        val canBrake = can.freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { normalizedBrakePercent() }
        return copy(
            rpm = can.freshInt(AimCanFrameIds.CORE, nowElapsedMs) { rpm } ?: rpm,
            throttle = can.freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { pedalPositionPercent } ?: throttle,
            brake = canBrake ?: brake,
            steering = can.freshDouble(AimCanFrameIds.MOTION, nowElapsedMs) { steeringAngleDeg } ?: steering,
            coolantTempC = can.freshDouble(AimCanFrameIds.CORE, nowElapsedMs) { waterTempC } ?: coolantTempC,
            oilTempC = can.freshOilTempC(nowElapsedMs) ?: oilTempC,
            canVehicleDiagnostics = can?.diagnostics(nowElapsedMs),
            telemetrySource = kind,
            sourceHealth = health,
        )
    }

    private fun noLiveDataFrame(
        elapsedSeconds: Double,
        track: Track,
        health: TelemetrySourceHealth,
        reason: String,
    ): TelemetryFrame {
        return waitingFrame(
            elapsedSeconds = elapsedSeconds,
            track = track,
            kind = kind,
            health = health.copy(
                motionSource = null,
                motionConnected = false,
                motionFixGood = false,
                fallbackStage = "no_live_data",
                degradedReason = reason,
            ),
        )
    }

    private fun updateDistance(elapsedSeconds: Double, speedMph: Double): Double {
        val previousElapsed = lastDistanceElapsedSeconds
        if (previousElapsed != null) {
            val deltaSeconds = elapsedSeconds - previousElapsed
            if (deltaSeconds in 0.0..1.0) {
                distanceMeters += speedMph * MPH_TO_METERS_PER_SECOND * deltaSeconds
            }
        }
        lastDistanceElapsedSeconds = elapsedSeconds
        return distanceMeters
    }
}

/**
 * Telemetry source backed by the Dauntless CAN-over-Bluetooth adapter.
 *
 * Structurally identical to [AimCanUsbSource] — same CAN frame IDs,
 * same fallback chain (RaceBox → phone GPS/IMU) — the only difference
 * is the transport layer: Bluetooth RFCOMM instead of USB serial.
 */
class DauntlessCanBluetoothSource(
    private val canClient: AimCanDataClient,
    private val raceBoxClient: RaceBoxDataClient? = null,
    private val phoneFallbackSource: TelemetrySource? = null,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : TelemetrySource {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH
    override val frameIntervalNanos: Long = AIM_CAN_FRAME_INTERVAL_NANOS

    /**
     * Delegate to the existing AimCanUsbSource for all frame-level logic.
     * The only purpose of this wrapper is to carry the correct [kind].
     */
    private val delegate = AimCanUsbSource(
        canClient = canClient,
        raceBoxClient = raceBoxClient,
        phoneFallbackSource = phoneFallbackSource,
        elapsedRealtimeMs = elapsedRealtimeMs,
        transportLabel = "Dauntless BLE",
    )

    override suspend fun start() = delegate.start()

    override suspend fun stop() = delegate.stop()

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        // Reuse AimCanUsbSource frame assembly, then stamp our own kind.
        val frame = delegate.nextFrame(step, track, elapsedSeconds)
        return frame.copy(telemetrySource = kind)
    }
}

class SyntheticTrackSource : TelemetrySource {
    override val kind: TelemetrySourceKind = TelemetrySourceKind.SYNTHETIC

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        val angle = (step % 360) * (PI / 180.0)
        val phase = step % 120
        val braking = phase in 0..16
        val turnIn = phase in 17..28
        val midCorner = phase in 29..46
        val exit = phase in 47..70
        val accelerate = phase in 71..95

        val brake = when {
            braking -> 78.0 - phase
            turnIn -> 20.0
            else -> 0.0
        }.coerceAtLeast(0.0)

        val throttle = when {
            midCorner -> 8.0
            exit -> 42.0 + (phase - 47) * 1.9
            accelerate -> 78.0 + (phase - 71) * 0.8
            else -> 14.0
        }.coerceIn(0.0, 100.0)

        val gLat = when {
            midCorner -> 1.05
            turnIn -> 0.58
            exit -> 0.42
            else -> 0.12
        } * if (step / 120 % 2 == 0) 1 else -1

        val gLong = when {
            braking -> -1.25
            turnIn -> -0.35
            accelerate -> 0.48
            else -> 0.05
        }

        val speedMph = when {
            braking -> 102.0 - phase * 1.4
            turnIn -> 70.0 - (phase - 17) * 0.8
            midCorner -> 55.0 + sin(angle) * 2.0
            exit -> 58.0 + (phase - 47) * 1.2
            accelerate -> 86.0 + (phase - 71) * 1.3
            else -> 94.0
        }

        val latitude = track.centerLat + sin(angle) * 0.0018
        val longitude = track.centerLon + cos(angle) * 0.0015
        return TelemetryFrame(
            timeSeconds = elapsedSeconds,
            latitude = latitude,
            longitude = longitude,
            altitude = 38.0 + sin(angle) * 6.0,
            speedMph = speedMph,
            rpm = 4200 + (speedMph * 42).toInt(),
            throttle = throttle,
            brake = brake,
            steering = if (midCorner || turnIn) gLat * 19.0 else 0.0,
            gLat = gLat,
            gLong = gLong,
            gear = when {
                speedMph > 95.0 -> 5
                speedMph > 75.0 -> 4
                speedMph > 55.0 -> 3
                else -> 2
            },
            distanceMeters = elapsedSeconds * (speedMph * 0.44704),
            telemetrySource = kind,
        )
    }
}

class NoLiveDataSource(
    override val kind: TelemetrySourceKind,
    private val detail: String,
) : TelemetrySource {
    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun nextFrame(step: Int, track: Track, elapsedSeconds: Double): TelemetryFrame {
        return waitingFrame(
            elapsedSeconds = elapsedSeconds,
            track = track,
            kind = kind,
            health = TelemetrySourceHealth(
                status = detail,
                motionConnected = false,
                motionFixGood = false,
                fallbackStage = "no_live_data",
                degradedReason = "no_real_telemetry_source_available",
            ),
        )
    }
}

private class UnavailableRaceBoxClient(private val detail: String) : RaceBoxDataClient {
    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun latestSample(): RaceBoxSample? = null

    override fun status(): RaceBoxClientStatus = RaceBoxClientStatus(
        connected = false,
        scanning = false,
        detail = detail,
    )
}

private class UnavailableAimCanClient(private val detail: String) : AimCanDataClient {
    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override fun latestSample(): AimCanSample? = null

    override fun status(): AimCanClientStatus = AimCanClientStatus(
        connected = false,
        detail = detail,
    )
}

data class TelemetrySourceSelection(
    val requested: TelemetrySourceKind,
    val active: TelemetrySourceKind,
    val source: TelemetrySource,
    val detail: String,
    val isFallback: Boolean,
)

object TelemetrySourceFactory {
    fun select(
        context: Context,
        requested: TelemetrySourceKind,
        obdTransportPreference: ObdTransportPreference = ObdTransportPreference.AUTO,
    ): TelemetrySourceSelection {
        return when (requested) {
            TelemetrySourceKind.SYNTHETIC ->
                TelemetrySourceSelection(
                    requested = requested,
                    active = TelemetrySourceKind.SYNTHETIC,
                    source = SyntheticTrackSource(),
                    detail = "Using synthetic telemetry with live camera fusion enabled.",
                    isFallback = false,
                )

            TelemetrySourceKind.PHONE_IMU_GPS ->
                if (PhoneImuGpsSource.hasFineLocationPermission(context)) {
                    TelemetrySourceSelection(
                        requested = requested,
                        active = TelemetrySourceKind.PHONE_IMU_GPS,
                        source = PhoneImuGpsSource(context),
                        detail = "Using phone GPS + IMU telemetry with live camera fusion enabled.",
                        isFallback = false,
                    )
                } else {
                    noLiveDataFallback(
                        requested,
                        "Phone IMU + GPS requires precise location permission. No synthetic fallback is used in live race sessions.",
                    )
                }

            TelemetrySourceKind.RACEBOX_BLE ->
                bluetoothSelection(
                    context = context,
                    requested = requested,
                    source = { RaceBoxBleSource(RaceBoxBleClient(context)) },
                    detail = "Using RaceBox Mini BLE telemetry with live camera fusion enabled.",
                )

            TelemetrySourceKind.OBD_BLUETOOTH ->
                bluetoothSelection(
                    context = context,
                    requested = requested,
                    source = { ObdBluetoothSource(ObdLinkElm327Client(context, ObdTransportPreference.BLUETOOTH)) },
                    detail = "Using OBDLink MX+ Bluetooth telemetry diagnostics with live camera fusion enabled.",
                )

            TelemetrySourceKind.RACEBOX_OBD_FUSION ->
                TelemetrySourceSelection(
                    requested = requested,
                    active = requested,
                    source = RaceBoxObdFusionSource(
                        raceBoxClient = if (BluetoothRuntimePermissions.hasBluetoothPermissions(context)) {
                            RaceBoxBleClient(context)
                        } else {
                            UnavailableRaceBoxClient("Bluetooth permission missing; RaceBox unavailable")
                        },
                        obdClient = ObdLinkElm327Client(context, obdTransportPreference),
                        phoneFallbackSource = if (PhoneImuGpsSource.hasFineLocationPermission(context)) {
                            PhoneImuGpsSource(context)
                        } else {
                            null
                        },
                    ),
                    detail = "Using RaceBox Mini as the primary motion clock with OBDLink enrichment (${obdTransportPreference.bridgeValue()}) and phone GPS/IMU real-data fallback.",
                    isFallback = false,
                )

            TelemetrySourceKind.AIM_CAN_USB ->
                TelemetrySourceSelection(
                    requested = requested,
                    active = requested,
                    source = AimCanUsbSource(
                        canClient = AimCanUsbClient(context),
                        raceBoxClient = if (BluetoothRuntimePermissions.hasBluetoothPermissions(context)) {
                            RaceBoxBleClient(context)
                        } else {
                            UnavailableRaceBoxClient("Bluetooth permission missing; RaceBox fallback unavailable")
                        },
                        phoneFallbackSource = if (PhoneImuGpsSource.hasFineLocationPermission(context)) {
                            PhoneImuGpsSource(context)
                        } else {
                            null
                        },
                    ),
                    detail = "Using AiM CAN USB via RH-02 PRO / CANable as the primary source with RaceBox and phone GPS/IMU real-data fallback.",
                    isFallback = false,
                )

            TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH -> {
                // Always create the real client — it handles loopback mode
                // internally and doesn't need BT permissions for that path.
                val canClient = DauntlessCanBluetoothClient(context)
                TelemetrySourceSelection(
                    requested = requested,
                    active = requested,
                    source = DauntlessCanBluetoothSource(
                        canClient = canClient,
                        raceBoxClient = if (BluetoothRuntimePermissions.hasBluetoothPermissions(context)) {
                            RaceBoxBleClient(context)
                        } else {
                            UnavailableRaceBoxClient("Bluetooth permission missing; RaceBox fallback unavailable")
                        },
                        phoneFallbackSource = if (PhoneImuGpsSource.hasFineLocationPermission(context)) {
                            PhoneImuGpsSource(context)
                        } else {
                            null
                        },
                    ),
                    detail = "Using Dauntless CAN-over-Bluetooth as the primary source with RaceBox and phone GPS/IMU real-data fallback.",
                    isFallback = false,
                )
            }
        }
    }

    private fun bluetoothSelection(
        context: Context,
        requested: TelemetrySourceKind,
        source: () -> TelemetrySource,
        detail: String,
    ): TelemetrySourceSelection {
        if (!BluetoothRuntimePermissions.hasBluetoothPermissions(context)) {
            return noLiveDataFallback(
                requested,
                "Bluetooth scan/connect permissions are required for ${requested.name.lowercase()}. No synthetic fallback is used in live hardware sessions.",
            )
        }
        return TelemetrySourceSelection(
            requested = requested,
            active = requested,
            source = source(),
            detail = detail,
            isFallback = false,
        )
    }

    private fun noLiveDataFallback(requested: TelemetrySourceKind, detail: String): TelemetrySourceSelection {
        return TelemetrySourceSelection(
            requested = requested,
            active = requested,
            source = NoLiveDataSource(requested, detail),
            detail = detail,
            isFallback = true,
        )
    }
}

class TelemetryFusionEngine {
    fun fuse(frame: TelemetryFrame, vision: VisionFeatureSnapshot? = null): TelemetryFrame {
        return frame.copy(
            gLat = frame.gLat.coerceIn(-3.0, 3.0),
            gLong = frame.gLong.coerceIn(-3.0, 3.0),
            throttle = frame.throttle.coerceIn(0.0, 100.0),
            brake = frame.brake.coerceIn(0.0, 100.0),
            speedMph = abs(frame.speedMph),
            telemetrySource = frame.telemetrySource,
            vision = vision,
        )
    }
}

private fun isUsableRaceBoxSample(sample: RaceBoxSample?, nowElapsedMs: Long): Boolean {
    return sample != null &&
        sample.fixOk &&
        nowElapsedMs - sample.receivedAtElapsedMs <= RACEBOX_STALE_MS
}

private fun isUsablePhoneFrame(frame: TelemetryFrame): Boolean {
    if (!isValidGps(frame.latitude, frame.longitude)) return false
    val health = frame.sourceHealth
    if (health?.motionFixGood == false || health?.phoneMotionFixGood == false) return false
    val ageMs = health?.motionSampleAgeMs ?: health?.phoneMotionSampleAgeMs
    return ageMs == null || ageMs <= PHONE_MOTION_STALE_MS
}

private fun isValidGps(lat: Double, lon: Double): Boolean {
    if (lat.isNaN() || lon.isNaN()) return false
    if (lat == 0.0 && lon == 0.0) return false
    if (abs(lat) > 90.0 || abs(lon) > 180.0) return false
    return true
}

private fun TelemetrySourceHealth.withFallbackStage(
    fallbackStage: String,
    motionSource: String?,
    motionConnected: Boolean?,
    motionFixGood: Boolean?,
    motionSampleAgeMs: Long? = null,
    phoneMotionConnected: Boolean? = null,
    phoneMotionFixGood: Boolean? = null,
    phoneMotionSampleAgeMs: Long? = null,
    degradedReason: String?,
): TelemetrySourceHealth {
    return copy(
        motionSource = motionSource,
        motionConnected = motionConnected,
        motionFixGood = motionFixGood,
        motionSampleAgeMs = motionSampleAgeMs,
        fallbackStage = fallbackStage,
        degradedReason = degradedReason,
        phoneMotionConnected = phoneMotionConnected,
        phoneMotionFixGood = phoneMotionFixGood,
        phoneMotionSampleAgeMs = phoneMotionSampleAgeMs,
    )
}

private fun waitingFrame(
    elapsedSeconds: Double,
    track: Track,
    kind: TelemetrySourceKind,
    health: TelemetrySourceHealth,
): TelemetryFrame {
    return TelemetryFrame(
        timeSeconds = elapsedSeconds,
        latitude = track.centerLat,
        longitude = track.centerLon,
        speedMph = 0.0,
        rpm = null,
        throttle = 0.0,
        brake = 0.0,
        steering = null,
        gLat = 0.0,
        gLong = 0.0,
        gear = null,
        telemetrySource = kind,
        sourceHealth = health,
    )
}

private fun gpsTowDeltaMs(previousTowMs: Long, currentTowMs: Long): Long {
    val delta = currentTowMs - previousTowMs
    return if (delta >= 0) delta else delta + GPS_WEEK_MS
}

private fun ObdSample?.freshDouble(
    pid: ObdPid,
    nowElapsedMs: Long,
    value: ObdSample.() -> Double?,
): Double? {
    val sample = this ?: return null
    return sample.value()?.takeIf { sample.isFresh(pid, nowElapsedMs, OBD_STALE_MS) }
}

private fun ObdSample?.freshInt(
    pid: ObdPid,
    nowElapsedMs: Long,
    value: ObdSample.() -> Int?,
): Int? {
    val sample = this ?: return null
    return sample.value()?.takeIf { sample.isFresh(pid, nowElapsedMs, OBD_STALE_MS) }
}

private fun aimCanHealth(
    nowElapsedMs: Long,
    can: AimCanSample?,
    canStatus: AimCanClientStatus,
    raceBox: RaceBoxSample?,
    raceBoxStatus: RaceBoxClientStatus?,
    transportLabel: String = "AiM CAN USB",
): TelemetrySourceHealth {
    val observedFrameIds = (AimCanFrameIds.all + can?.channelUpdatedAtElapsedMs.orEmpty().keys).toSortedSet()
    val frameAges = can?.channelUpdatedAtElapsedMs.orEmpty()
        .mapKeys { (frameId, _) -> AimCanFrameIds.key(frameId) }
        .mapValues { (_, updatedAt) -> nowElapsedMs - updatedAt }
    val frameStale = observedFrameIds.associate { frameId ->
        AimCanFrameIds.key(frameId) to (can?.isFresh(frameId, nowElapsedMs, aimCanStaleMs(frameId)) != true)
    }
    val frameRates = can?.frameRatesHz.orEmpty()
        .mapKeys { (frameId, _) -> AimCanFrameIds.key(frameId) }
    val rawSamples = can?.rawCanSamplesById.orEmpty()
        .mapKeys { (frameId, _) -> AimCanFrameIds.key(frameId) }
    val raceBoxAge = raceBox?.let { nowElapsedMs - it.receivedAtElapsedMs }
    val raceBoxFresh = raceBoxAge != null && raceBoxAge <= RACEBOX_STALE_MS
    val raceBoxPart = when {
        raceBoxStatus == null -> "RaceBox fallback disabled"
        raceBoxStatus.connected && raceBoxFresh && raceBox?.fixOk == true -> "RaceBox fallback fix OK"
        raceBoxStatus.connected && raceBoxFresh -> "RaceBox fallback waiting for 3D fix"
        raceBoxStatus.connected -> "RaceBox fallback stale sample"
        raceBoxStatus.scanning -> "RaceBox fallback scanning"
        else -> raceBoxStatus.detail
    }
    val canPart = when {
        canStatus.connected && hasFreshAimCanVehicleChannels(can, nowElapsedMs) -> canStatus.detail
        canStatus.connected -> "$transportLabel connected, waiting for fresh CAN frames"
        else -> canStatus.detail
    }
    return TelemetrySourceHealth(
        status = "$canPart | $raceBoxPart",
        raceBoxConnected = raceBoxStatus?.connected == true,
        raceBoxFixGood = raceBox?.fixOk,
        raceBoxFixStatus = raceBox?.fixStatus,
        raceBoxSatellites = raceBox?.satellites,
        raceBoxSampleAgeMs = raceBoxAge,
        canConnected = canStatus.connected,
        canFrameAgesMs = frameAges,
        canFrameStale = frameStale,
        canFrameRatesHz = frameRates,
        canDecodeErrors = can?.decodeErrors ?: canStatus.decodeErrors,
        usbDeviceName = canStatus.usbDeviceName,
        rawCanSample = can?.rawCanSample,
        rawCanSamplesById = rawSamples,
        signUnverified = can != null,
    )
}

private fun isAimCanFull(sample: AimCanSample?, nowElapsedMs: Long): Boolean {
    return sample != null &&
        isValidGps(sample.gpsLatitude ?: 0.0, sample.gpsLongitude ?: 0.0) &&
        sample.isFresh(AimCanFrameIds.GPS_POSITION, nowElapsedMs, AIM_CAN_SLOW_STALE_MS) &&
        sample.isFresh(AimCanFrameIds.CORE, nowElapsedMs, AIM_CAN_SLOW_STALE_MS) &&
        sample.isFresh(AimCanFrameIds.MOTION, nowElapsedMs, AIM_CAN_FAST_STALE_MS) &&
        sample.isFresh(AimCanFrameIds.CONTROLS, nowElapsedMs, AIM_CAN_FAST_STALE_MS)
}

private fun hasFreshAimCanVehicleChannels(sample: AimCanSample?, nowElapsedMs: Long): Boolean {
    return sample != null &&
        (
            sample.isFresh(AimCanFrameIds.CORE, nowElapsedMs, AIM_CAN_SLOW_STALE_MS) ||
                sample.isFresh(AimCanFrameIds.CONTROLS, nowElapsedMs, AIM_CAN_FAST_STALE_MS) ||
                sample.isFresh(AimCanFrameIds.ECU, nowElapsedMs, AIM_CAN_SLOW_STALE_MS) ||
                sample.isFresh(AimCanFrameIds.WHEEL_SPEEDS, nowElapsedMs, AIM_CAN_SLOW_STALE_MS)
            )
}

private fun AimCanSample.primaryFreshSpeedMph(nowElapsedMs: Long): Double? {
    freshDouble(AimCanFrameIds.CORE, nowElapsedMs) { gpsSpeedMph }?.let { return it }
    freshDouble(AimCanFrameIds.ECU, nowElapsedMs) { ecuSpeedMph }?.let { return it }
    if (!isFresh(AimCanFrameIds.WHEEL_SPEEDS, nowElapsedMs, aimCanStaleMs(AimCanFrameIds.WHEEL_SPEEDS))) return null
    val wheelSpeeds = listOfNotNull(
        wheelSpeedFrontLeftMph,
        wheelSpeedFrontRightMph,
        wheelSpeedRearLeftMph,
        wheelSpeedRearRightMph,
    )
    return wheelSpeeds.takeIf { it.isNotEmpty() }?.average()
}

private fun AimCanSample.normalizedBrakePercent(): Double? {
    val pressure = brakePressureCalibratedPsi ?: brakePressurePsi ?: return null
    return (pressure / BRAKE_FULL_SCALE_PSI * 100.0).coerceIn(0.0, 100.0)
}

private fun AimCanSample.usableGear(): Int? {
    return gearRaw?.takeIf { gear -> gear > 0 }
}

private fun AimCanSample?.freshOilTempC(nowElapsedMs: Long): Double? {
    val sample = this ?: return null
    return sample.freshDouble(AimCanFrameIds.PRESSURE_RATES, nowElapsedMs) { oilFilterTempC }
        ?: sample.freshDouble(AimCanFrameIds.ECU, nowElapsedMs) { engineOilTempC }
}

private fun AimCanSample.diagnostics(nowElapsedMs: Long): CanVehicleDiagnostics {
    return CanVehicleDiagnostics(
        waterPressurePsi = freshDouble(AimCanFrameIds.PRESSURE_RATES, nowElapsedMs) { waterPressurePsi },
        oilPressurePsi = freshDouble(AimCanFrameIds.PRESSURE_RATES, nowElapsedMs) { oilPressurePsi },
        brakePressureRaw = freshInt(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakePressureRaw },
        brakePressurePsi = freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakePressurePsi },
        brakePressureZeroOffsetRaw = freshInt(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakePressureZeroOffsetRaw },
        brakePressureCalibratedPsi = freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakePressureCalibratedPsi },
        brakePressureZeroOffsetPsi = freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakePressureZeroOffsetPsi },
        pedalPositionRaw = freshInt(AimCanFrameIds.CONTROLS, nowElapsedMs) { pedalPositionRaw },
        pedalPositionPercent = freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { pedalPositionPercent },
        brakeSwitchRaw = freshInt(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakeSwitchRaw },
        brakeSwitchApplied = freshBoolean(AimCanFrameIds.CONTROLS, nowElapsedMs) { brakeSwitchApplied },
        rollRateDegPerSec = freshDouble(AimCanFrameIds.PRESSURE_RATES, nowElapsedMs) { rollRateDegPerSec },
        pitchRateDegPerSec = freshDouble(AimCanFrameIds.CONTROLS, nowElapsedMs) { pitchRateDegPerSec },
        yawRateDegPerSec = freshDouble(AimCanFrameIds.MOTION, nowElapsedMs) { yawRateDegPerSec },
        steeringAngleDeg = freshDouble(AimCanFrameIds.MOTION, nowElapsedMs) { steeringAngleDeg },
        lateralG = freshDouble(AimCanFrameIds.MOTION, nowElapsedMs) { lateralG },
        inlineG = freshDouble(AimCanFrameIds.MOTION, nowElapsedMs) { inlineG },
        verticalG = freshDouble(AimCanFrameIds.AUX, nowElapsedMs) { verticalG },
        fuelLevelGal = freshDouble(AimCanFrameIds.AUX, nowElapsedMs) { fuelLevelGal },
        batteryVoltage = freshDouble(AimCanFrameIds.AUX, nowElapsedMs) { batteryVoltage },
        wheelSpeedFrontLeftMph = freshDouble(AimCanFrameIds.WHEEL_SPEEDS, nowElapsedMs) { wheelSpeedFrontLeftMph },
        wheelSpeedFrontRightMph = freshDouble(AimCanFrameIds.WHEEL_SPEEDS, nowElapsedMs) { wheelSpeedFrontRightMph },
        wheelSpeedRearLeftMph = freshDouble(AimCanFrameIds.WHEEL_SPEEDS, nowElapsedMs) { wheelSpeedRearLeftMph },
        wheelSpeedRearRightMph = freshDouble(AimCanFrameIds.WHEEL_SPEEDS, nowElapsedMs) { wheelSpeedRearRightMph },
        ecuSpeedMph = freshDouble(AimCanFrameIds.ECU, nowElapsedMs) { ecuSpeedMph },
        gpsSpeedMph = freshDouble(AimCanFrameIds.CORE, nowElapsedMs) { gpsSpeedMph },
        outsideTempC = freshDouble(AimCanFrameIds.ECU, nowElapsedMs) { outsideTempC },
        waterTempC = freshDouble(AimCanFrameIds.CORE, nowElapsedMs) { waterTempC },
        engineOilTempC = freshDouble(AimCanFrameIds.ECU, nowElapsedMs) { engineOilTempC },
        oilFilterTempC = freshDouble(AimCanFrameIds.PRESSURE_RATES, nowElapsedMs) { oilFilterTempC },
        dscRegActive = freshBoolean(AimCanFrameIds.ECU, nowElapsedMs) { dscRegActive },
        gearRaw = freshInt(AimCanFrameIds.CORE, nowElapsedMs) { gearRaw },
        frameAgesMs = channelUpdatedAtElapsedMs
            .mapKeys { (frameId, _) -> AimCanFrameIds.key(frameId) }
            .mapValues { (_, updatedAt) -> nowElapsedMs - updatedAt }
            .toMap(),
        frameStale = (AimCanFrameIds.all + channelUpdatedAtElapsedMs.keys).toSortedSet().associate { frameId ->
            AimCanFrameIds.key(frameId) to !isFresh(frameId, nowElapsedMs, aimCanStaleMs(frameId))
        },
        rawFrameSamples = rawCanSamplesById.mapKeys { (frameId, _) -> AimCanFrameIds.key(frameId) },
    )
}

private fun AimCanSample?.freshDouble(
    frameId: Int,
    nowElapsedMs: Long,
    value: AimCanSample.() -> Double?,
): Double? {
    val sample = this ?: return null
    return sample.value()?.takeIf { sample.isFresh(frameId, nowElapsedMs, aimCanStaleMs(frameId)) }
}

private fun AimCanSample?.freshInt(
    frameId: Int,
    nowElapsedMs: Long,
    value: AimCanSample.() -> Int?,
): Int? {
    val sample = this ?: return null
    return sample.value()?.takeIf { sample.isFresh(frameId, nowElapsedMs, aimCanStaleMs(frameId)) }
}

private fun AimCanSample.freshBoolean(
    frameId: Int,
    nowElapsedMs: Long,
    value: AimCanSample.() -> Boolean?,
): Boolean? {
    return value()?.takeIf { isFresh(frameId, nowElapsedMs, aimCanStaleMs(frameId)) }
}

private fun aimCanStaleMs(frameId: Int): Long {
    return when (frameId) {
        AimCanFrameIds.PRESSURE_RATES,
        AimCanFrameIds.CONTROLS,
        AimCanFrameIds.MOTION,
        AimCanFrameIds.AUX -> AIM_CAN_FAST_STALE_MS
        else -> AIM_CAN_SLOW_STALE_MS
    }
}

const val RACEBOX_FRAME_INTERVAL_NANOS = 40_000_000L
const val OBD_FRAME_INTERVAL_NANOS = 125_000_000L
const val AIM_CAN_FRAME_INTERVAL_NANOS = 20_000_000L
const val RACEBOX_STALE_MS = 500L
const val OBD_STALE_MS = 750L
const val OBD_DIAGNOSTIC_STALE_MS = 5_000L
const val AIM_CAN_FAST_STALE_MS = 100L
const val AIM_CAN_SLOW_STALE_MS = 250L
private const val PHONE_MOTION_STALE_MS = 5_000L
private const val GPS_WEEK_MS = 604_800_000L
private const val MPH_TO_METERS_PER_SECOND = 0.44704
private const val BRAKE_FULL_SCALE_PSI = 1_200.0
