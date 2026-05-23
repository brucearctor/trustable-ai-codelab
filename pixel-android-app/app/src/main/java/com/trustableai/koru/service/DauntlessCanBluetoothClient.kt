package com.trustableai.koru.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Bluetooth RFCOMM CAN client for the Dauntless adapter.
 *
 * Connects to a paired or discovered Bluetooth device whose name matches
 * [DAUNTLESS_DEVICE_HINTS], opens an SPP/RFCOMM socket, sends the same SLCAN
 * initialization sequence used by the AiM CAN USB path, and feeds the resulting
 * byte stream through [AimCanSlcanParser] → [AimCanDecoder]. This lets all
 * existing CAN-frame decoding, fallback-chain logic, and telemetry sources
 * work unmodified with the Dauntless hardware.
 *
 * ## Debug loopback mode
 *
 * When a simulation file exists at [LOOPBACK_FILE_PATH], the client reads
 * CR-delimited SLCAN frames from it at realistic pacing (~4ms per frame)
 * instead of opening a Bluetooth socket. This exercises the full decode
 * pipeline without requiring physical Dauntless hardware.
 *
 * Push sim data via: `adb push sim.slcan /sdcard/koru-debug/dauntless-sim.slcan`
 * Remove the file to return to real Bluetooth mode.
 */
class DauntlessCanBluetoothClient(
    context: Context,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) : AimCanDataClient {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val loopbackFile = File(appContext.getExternalFilesDir(null), LOOPBACK_FILENAME)
    private val parser = AimCanSlcanParser()
    private val recentFrameTimesMs = mutableMapOf<Int, MutableList<Long>>()
    private var scope: CoroutineScope? = null
    private var activeSocket: BluetoothSocket? = null
    private var reconnectCount = 0
    private var frameRatesHz: Map<Int, Double> = emptyMap()

    @Volatile private var latest: AimCanSample? = null
    @Volatile private var status = AimCanClientStatus(
        connected = false,
        detail = "Dauntless CAN Bluetooth idle",
    )

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { clientScope ->
            clientScope.launch {
                if (hasLoopbackFile()) {
                    loopbackLoop()
                } else {
                    connectionLoop()
                }
            }
        }
    }

    override suspend fun stop() {
        scope?.cancel()
        scope = null
        closeSocket()
        status = AimCanClientStatus(connected = false, detail = "Dauntless CAN Bluetooth stopped")
    }

    override fun latestSample(): AimCanSample? = latest

    override fun status(): AimCanClientStatus = status

    // ----- loopback (debug sim file) -----

    private fun hasLoopbackFile(): Boolean {
        val exists = loopbackFile.exists() && loopbackFile.length() > 0
        if (exists) {
            Log.i(TAG, "Debug loopback file found at ${loopbackFile.absolutePath} (${loopbackFile.length()} bytes)")
        }
        return exists
    }

    /**
     * Reads SLCAN frames from the loopback file at realistic CAN bus pacing.
     * Loops the file continuously until [stop] is called, so a short sim file
     * will replay indefinitely — useful for sustained testing.
     */
    private suspend fun loopbackLoop() {
        val raw = withContext(Dispatchers.IO) { loopbackFile.readBytes() }
        // SLCAN files use CR (\r) as the delimiter.
        val frameLines = String(raw, Charsets.US_ASCII)
            .split('\r')
            .map { it.trim() }
            .filter { it.startsWith("t") || it.startsWith("T") }

        if (frameLines.isEmpty()) {
            status = AimCanClientStatus(
                connected = false,
                detail = "Loopback file at ${loopbackFile.absolutePath} contains no SLCAN frames",
            )
            return
        }

        Log.i(TAG, "Loopback mode: replaying ${frameLines.size} SLCAN frames from ${loopbackFile.absolutePath}")
        status = AimCanClientStatus(
            connected = true,
            detail = "Dauntless CAN loopback: ${frameLines.size} frames loaded",
        )

        var loopIteration = 0
        while (currentCoroutineContext().isActive) {
            loopIteration++
            Log.d(TAG, "Loopback replay pass #$loopIteration")
            for (line in frameLines) {
                if (!currentCoroutineContext().isActive) break
                val bytes = "$line\r".toByteArray(Charsets.US_ASCII)
                val now = elapsedRealtimeMs()
                val frames = parser.append(bytes, now)
                var sample = latest
                frames.forEach { frame ->
                    updateFrameRate(frame.id, frame.receivedAtElapsedMs)
                    sample = AimCanDecoder.applyFrame(
                        previous = sample?.copy(frameRatesHz = frameRatesHz),
                        frame = frame,
                        decodeErrors = parser.decodeErrors,
                    ).copy(frameRatesHz = frameRatesHz)
                    latest = sample
                }
                status = AimCanClientStatus(
                    connected = true,
                    detail = sample?.statusText("loopback")
                        ?: "Dauntless CAN loopback; decoding frames",
                    usbDeviceName = "loopback-sim",
                    reconnectCount = 0,
                    decodeErrors = parser.decodeErrors,
                )
                // Pace at ~4ms per frame line (≈250 lines/sec, realistic for CAN 50Hz × 8 IDs).
                delay(LOOPBACK_FRAME_DELAY_MS)
            }
            // Brief pause between replay loops.
            delay(500L)
        }
    }

    // ----- real Bluetooth connection lifecycle -----

    private suspend fun connectionLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                status = AimCanClientStatus(
                    connected = false,
                    detail = "Scanning for Dauntless CAN Bluetooth adapter",
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                val opened = openSocket()
                activeSocket = opened.socket
                val deviceLabel = opened.deviceName
                status = AimCanClientStatus(
                    connected = true,
                    detail = "Dauntless CAN Bluetooth connected to $deviceLabel; initializing SLCAN",
                    usbDeviceName = deviceLabel,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                initializeSlcan(opened.socket)
                readLoop(opened.socket, deviceLabel)
            } catch (error: Exception) {
                reconnectCount += 1
                status = AimCanClientStatus(
                    connected = false,
                    detail = "Dauntless BT error: ${error.message ?: error.javaClass.simpleName}",
                    usbDeviceName = status.usbDeviceName,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                closeSocket()
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openSocket(): OpenedDauntlessSocket {
        return withContext(Dispatchers.IO) {
            if (!BluetoothRuntimePermissions.hasBluetoothConnect(appContext)) {
                throw DauntlessUnavailable("Bluetooth connect permission missing")
            }
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                throw DauntlessUnavailable("Bluetooth adapter unavailable or disabled")
            }

            // Look through bonded (paired) devices first.
            val device = adapter.bondedDevices.firstOrNull { bonded ->
                isDauntlessDevice(bonded)
            } ?: throw DauntlessUnavailable(
                "No paired Dauntless adapter found. Pair the device in Android Bluetooth settings first."
            )

            adapter.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            val label = deviceLabel(device)
            OpenedDauntlessSocket(socket = socket, deviceName = label)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDauntlessDevice(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        return DAUNTLESS_DEVICE_HINTS.any { hint -> hint in name }
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String {
        val name = runCatching { device.name.orEmpty() }.getOrDefault("")
        return name.ifBlank {
            device.address ?: "Dauntless"
        }
    }

    private fun initializeSlcan(socket: BluetoothSocket) {
        runCatching {
            socket.outputStream.write(
                "\rC\rS8\rO\r".toByteArray(Charsets.US_ASCII),
            )
            socket.outputStream.flush()
        }
    }

    private suspend fun readLoop(socket: BluetoothSocket, deviceName: String) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(512)
            val input: InputStream = socket.inputStream
            var sample = latest
            while (currentCoroutineContext().isActive) {
                val read = input.read(buffer)
                if (read <= 0) {
                    // Stream closed by remote end.
                    throw DauntlessUnavailable("Bluetooth stream closed by Dauntless device")
                }
                val now = elapsedRealtimeMs()
                val frames = parser.append(buffer.copyOf(read), now)
                frames.forEach { frame ->
                    updateFrameRate(frame.id, frame.receivedAtElapsedMs)
                    sample = AimCanDecoder.applyFrame(
                        previous = sample?.copy(frameRatesHz = frameRatesHz),
                        frame = frame,
                        decodeErrors = parser.decodeErrors,
                    ).copy(frameRatesHz = frameRatesHz)
                    latest = sample
                }
                status = AimCanClientStatus(
                    connected = true,
                    detail = sample?.statusText()
                        ?: "Dauntless CAN BT connected; waiting for recognized CAN frames",
                    usbDeviceName = deviceName,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
            }
        }
    }

    private fun updateFrameRate(frameId: Int, nowElapsedMs: Long) {
        val samples = recentFrameTimesMs.getOrPut(frameId) { mutableListOf() }
        samples += nowElapsedMs
        val cutoff = nowElapsedMs - FRAME_RATE_WINDOW_MS
        samples.removeAll { time -> time < cutoff }
        frameRatesHz = recentFrameTimesMs.mapValues { (_, times) ->
            if (times.size < 2) {
                0.0
            } else {
                val spanSeconds = max(1L, times.last() - times.first()) / 1000.0
                (times.size - 1) / spanSeconds
            }
        }
    }

    private suspend fun closeSocket() {
        withContext(Dispatchers.IO) {
            runCatching { activeSocket?.close() }
            activeSocket = null
        }
    }

    private fun AimCanSample.statusText(mode: String = "live"): String {
        val parts = buildList {
            rpm?.let { add("${it}rpm") }
            gpsSpeedMph?.let { add("${"%.1f".format(Locale.US, it)}mph GPS") }
            pedalPositionPercent?.let { add("${"%.1f".format(Locale.US, it)}% pedal") }
            brakePressurePsi?.let { add("${"%.1f".format(Locale.US, it)}psi brake") }
            batteryVoltage?.let { add("${"%.1f".format(Locale.US, it)}V") }
        }
        return "Dauntless CAN BT $mode ${parts.joinToString(", ").ifBlank { "recognized frames" }}"
    }

    private data class OpenedDauntlessSocket(
        val socket: BluetoothSocket,
        val deviceName: String,
    )

    private class DauntlessUnavailable(message: String) : Exception(message)

    private companion object {
        private const val TAG = "DauntlessBT"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val FRAME_RATE_WINDOW_MS = 2_000L
        private const val LOOPBACK_FRAME_DELAY_MS = 4L

        /**
         * Filename for the debug loopback sim data. Placed in the app's own
         * external files directory (no permissions needed). Push via:
         *   adb push sim.slcan /sdcard/Android/data/com.trustableai.koru.debug/files/dauntless-sim.slcan
         */
        private const val LOOPBACK_FILENAME = "dauntless-sim.slcan"

        /**
         * Device name substrings used to identify a Dauntless CAN adapter
         * among the user's paired Bluetooth devices.
         */
        private val DAUNTLESS_DEVICE_HINTS = listOf(
            "dauntless",
            "dtls",
            "canbus",
            "can-bt",
        )
    }
}
