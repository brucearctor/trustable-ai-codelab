package com.trustableai.koru.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * BLE GATT CAN client for the Dauntless OBD adapter.
 *
 * Connects to a paired BLE device whose name matches [DAUNTLESS_DEVICE_HINTS],
 * discovers GATT services, finds the write and notify characteristics, sends
 * the SLCAN initialization sequence, and feeds the resulting byte stream
 * through [AimCanSlcanParser] → [AimCanDecoder]. This lets all existing
 * CAN-frame decoding, fallback-chain logic, and telemetry sources work
 * unmodified with the Dauntless hardware.
 *
 * The adapter uses BLE (Bluetooth Low Energy) with GATT, not classic SPP/RFCOMM.
 * Common service patterns include Nordic UART Service (NUS) or custom vendor
 * services. The client discovers services dynamically and identifies the
 * write/notify characteristic pair.
 *
 * ## Debug loopback mode
 *
 * When a simulation file exists at the app's external files directory,
 * the client reads CR-delimited SLCAN frames from it at realistic pacing
 * (~4ms per frame) instead of opening a BLE connection.
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
    private val elm327Buffer = StringBuilder()
    private var elm327DecodeErrors = 0
    private val recentFrameTimesMs = mutableMapOf<Int, MutableList<Long>>()
    private var scope: CoroutineScope? = null
    private var activeGatt: BluetoothGatt? = null
    private var reconnectCount = 0
    private var frameRatesHz: Map<Int, Double> = emptyMap()
    @Volatile private var elm327Detected = false
    @Volatile private var lastRxText = ""

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
        closeGatt()
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

    // ----- real BLE GATT connection lifecycle -----

    private suspend fun connectionLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                status = AimCanClientStatus(
                    connected = false,
                    detail = "Scanning for Dauntless CAN BLE adapter",
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                connectAndStream()
            } catch (error: Exception) {
                reconnectCount += 1
                Log.w(TAG, "BLE connection error (attempt #$reconnectCount): ${error.message}", error)
                status = AimCanClientStatus(
                    connected = false,
                    detail = "Dauntless BLE error: ${error.message ?: error.javaClass.simpleName}",
                    usbDeviceName = status.usbDeviceName,
                    reconnectCount = reconnectCount,
                    decodeErrors = parser.decodeErrors,
                )
                closeGatt()
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndStream() {
        if (!BluetoothRuntimePermissions.hasBluetoothConnect(appContext)) {
            throw DauntlessUnavailable("Bluetooth connect permission missing")
        }
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            throw DauntlessUnavailable("Bluetooth adapter unavailable or disabled")
        }

        // Find the bonded Dauntless device.
        val device = adapter.bondedDevices.firstOrNull { bonded ->
            isDauntlessDevice(bonded)
        } ?: throw DauntlessUnavailable(
            "No paired Dauntless adapter found. Pair the device in Android Bluetooth settings first."
        )

        val deviceLabel = deviceLabel(device)
        Log.i(TAG, "Found bonded Dauntless device: $deviceLabel (${device.address})")

        status = AimCanClientStatus(
            connected = false,
            detail = "Connecting BLE GATT to $deviceLabel",
            usbDeviceName = deviceLabel,
            reconnectCount = reconnectCount,
            decodeErrors = parser.decodeErrors,
        )

        // Connect GATT and discover services.
        val gattResult = connectGattAndDiscover(device)
        activeGatt = gattResult.gatt

        Log.i(TAG, "GATT connected, services discovered. Services: ${
            gattResult.gatt.services?.joinToString { it.uuid.toString() } ?: "none"
        }")

        // Find write and notify characteristics.
        val charPair = findCharacteristics(gattResult.gatt)
            ?: throw DauntlessUnavailable(
                "Could not find write/notify characteristics on Dauntless. " +
                "Services: ${gattResult.gatt.services?.joinToString { it.uuid.toString() }}"
            )

        Log.i(TAG, "Found characteristics - Write: ${charPair.write.uuid}, Notify: ${charPair.notify.uuid}")

        // Enable notifications on the notify characteristic.
        enableNotifications(gattResult.gatt, charPair.notify)

        status = AimCanClientStatus(
            connected = true,
            detail = "Dauntless BLE connected to $deviceLabel; detecting protocol",
            usbDeviceName = deviceLabel,
            reconnectCount = reconnectCount,
            decodeErrors = parser.decodeErrors,
        )

        // Detect adapter protocol (ELM327 vs SLCAN) and initialize accordingly.
        detectAndInitProtocol(gattResult.gatt, charPair.write)

        // Stream data from notifications until disconnected.
        // For ELM327 mode, also run active OBD polling in parallel.
        if (elm327Detected) {
            Log.i(TAG, "Starting ELM327 OBD-II polling loop")
            scope?.launch {
                obdPollingLoop(gattResult.gatt, charPair.write)
            }
        }
        streamFromGatt(gattResult, deviceLabel)
    }

    /**
     * Connects to the device via GATT and discovers services.
     * Uses suspendCancellableCoroutine to bridge the callback-based API.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectGattAndDiscover(device: BluetoothDevice): GattConnection {
        return suspendCancellableCoroutine<GattConnection> { continuation ->
            var gattRef: BluetoothGatt? = null
            val callback = object : BluetoothGattCallback() {
                private var resumed = false

                override fun onConnectionStateChange(gatt: BluetoothGatt, btStatus: Int, newState: Int) {
                    Log.d(TAG, "onConnectionStateChange: status=$btStatus newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT connected, discovering services...")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(
                                GattConnection(gatt, this, disconnected = true)
                            )
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, btStatus: Int) {
                    Log.i(TAG, "onServicesDiscovered: status=$btStatus, serviceCount=${gatt.services?.size ?: 0}")
                    if (btStatus == BluetoothGatt.GATT_SUCCESS && !resumed) {
                        resumed = true
                        continuation.resume(GattConnection(gatt, this))
                    } else if (!resumed) {
                        resumed = true
                        continuation.resume(
                            GattConnection(gatt, this, disconnected = true)
                        )
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    Log.d(TAG, "BLE notify: ${value.size} bytes from ${characteristic.uuid}")
                    handleIncomingData(value)
                }

                @Suppress("DEPRECATION")
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    // Pre-API 33 callback.
                    @Suppress("DEPRECATION")
                    val data = characteristic.value ?: return
                    Log.d(TAG, "BLE notify (legacy): ${data.size} bytes from ${characteristic.uuid}")
                    handleIncomingData(data)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    val ok = status == BluetoothGatt.GATT_SUCCESS
                    Log.i(TAG, "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status success=$ok")
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: android.bluetooth.BluetoothGattDescriptor,
                    status: Int,
                ) {
                    val ok = status == BluetoothGatt.GATT_SUCCESS
                    Log.i(TAG, "onDescriptorWrite: uuid=${descriptor.uuid} charUuid=${descriptor.characteristic.uuid} status=$status success=$ok")
                }
            }

            gattRef = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, callback)
            }

            if (gattRef == null) {
                throw DauntlessUnavailable("connectGatt returned null")
            }

            continuation.invokeOnCancellation {
                runCatching { gattRef?.close() }
            }
        }.also { result ->
            if (result.disconnected) {
                throw DauntlessUnavailable("Failed to connect BLE GATT to Dauntless")
            }
        }
    }

    /**
     * Finds the write and notify characteristic pair on the GATT server.
     * Checks for:
     * 1. Nordic UART Service (NUS)
     * 2. Common custom OBD BLE services (0xFFF0, 0xFFE0)
     * 3. Any service with a writable + notifiable characteristic pair
     */
    private fun findCharacteristics(gatt: BluetoothGatt): CharacteristicPair? {
        val services = gatt.services ?: return null

        // Log all discovered services and characteristics for debugging.
        for (service in services) {
            Log.d(TAG, "  Service: ${service.uuid}")
            for (char in service.characteristics) {
                val props = char.properties
                val propNames = buildList {
                    if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
                    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                    if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                }
                Log.d(TAG, "    Char: ${char.uuid} [${propNames.joinToString(",")}]")
            }
        }

        // Try well-known UART-like service UUIDs in priority order.
        for (serviceUuid in KNOWN_UART_SERVICES) {
            val service = services.firstOrNull { it.uuid == serviceUuid } ?: continue
            val writeChar = service.characteristics.firstOrNull { char ->
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            }
            val notifyChar = service.characteristics.firstOrNull { char ->
                (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            }
            if (writeChar != null && notifyChar != null) {
                Log.i(TAG, "Found UART service ${serviceUuid}: write=${writeChar.uuid}, notify=${notifyChar.uuid}")
                return CharacteristicPair(writeChar, notifyChar)
            }
        }

        // Fallback: search all services for any write+notify pair.
        for (service in services) {
            // Skip standard BT SIG services (GAP, GATT, Device Info, etc.)
            val uuidStr = service.uuid.toString().uppercase()
            if (uuidStr.startsWith("00001800") || uuidStr.startsWith("00001801") ||
                uuidStr.startsWith("0000180A")) continue

            val writeChar = service.characteristics.firstOrNull { char ->
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            }
            val notifyChar = service.characteristics.firstOrNull { char ->
                (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            }
            if (writeChar != null && notifyChar != null) {
                Log.i(TAG, "Found custom UART-like service ${service.uuid}: write=${writeChar.uuid}, notify=${notifyChar.uuid}")
                return CharacteristicPair(writeChar, notifyChar)
            }
        }

        return null
    }

    /**
     * Enables BLE notifications on the given characteristic by writing
     * to the Client Characteristic Configuration Descriptor (CCCD).
     */
    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val hasIndicate = (characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val value = if (hasIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            // Give the CCCD write time to complete.
            delay(250)
            Log.i(TAG, "Notifications enabled on ${characteristic.uuid}")
        } else {
            Log.w(TAG, "No CCCD found on ${characteristic.uuid}, notifications may not work")
        }
    }

    /**
     * SLCAN baud rate codes to try, in priority order.
     * S6=500kbps (standard OBD-II), S5=250kbps, S4=125kbps, S8=1Mbps (CAN-FD).
     */
    private val BAUD_SCAN_ORDER = listOf(
        "S6" to "500kbps",
        "S5" to "250kbps",
        "S4" to "125kbps",
        "S8" to "1Mbps",
    )

    /**
     * Detects the adapter protocol (ELM327 or SLCAN) and runs the appropriate
     * initialization sequence.
     *
     * Protocol detection: sends "ATI\r" and checks if the response contains
     * "ELM" (ELM327-compatible) or a known OBD-II prompt (">"). If so, uses
     * ELM327 AT initialization. Otherwise falls back to SLCAN baud scanning.
     */
    @SuppressLint("MissingPermission")
    private suspend fun detectAndInitProtocol(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        Log.i(TAG, "=== Protocol detection: sending ATI probe ===")
        lastRxText = ""

        // Send ATI (identity) command — ELM327 adapters respond with version string.
        writeSlcanCommand(gatt, writeChar, "ATI\r", "ATI")
        delay(ELM327_PROBE_WAIT_MS)

        // Check accumulated responses for ELM327 signatures.
        val probe = lastRxText.uppercase()
        val isElm = probe.contains("ELM") || probe.contains("OBD") ||
            probe.contains(">") || probe.contains("AT") ||
            probe.contains("7E8") // Already receiving OBD responses
        
        if (isElm) {
            Log.i(TAG, "✅ ELM327 protocol detected (probe response: ${lastRxText.take(60)})")
            elm327Detected = true
            sendElm327Init(gatt, writeChar)
        } else {
            Log.i(TAG, "SLCAN protocol assumed (probe response: ${lastRxText.take(60)})")
            elm327Detected = false
            sendSlcanInit(gatt, writeChar)
        }
    }

    /**
     * Initializes an ELM327-compatible adapter with standard AT commands.
     * Sets up the adapter for continuous OBD-II polling.
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendElm327Init(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        Log.i(TAG, "▶ ELM327 initialization sequence")

        // ATZ — full reset. Wait longer for this one.
        writeSlcanCommand(gatt, writeChar, "ATZ\r", "ATZ")
        delay(ELM327_RESET_WAIT_MS)

        // ATE0 — echo off (reduces BLE traffic)
        writeSlcanCommand(gatt, writeChar, "ATE0\r", "ATE0")
        delay(ELM327_CMD_DELAY_MS)

        // ATL0 — linefeeds off
        writeSlcanCommand(gatt, writeChar, "ATL0\r", "ATL0")
        delay(ELM327_CMD_DELAY_MS)

        // ATS1 — spaces on (parser expects space-separated hex tokens)
        writeSlcanCommand(gatt, writeChar, "ATS1\r", "ATS1")
        delay(ELM327_CMD_DELAY_MS)

        // ATH1 — headers on (so we get CAN IDs like 7E8)
        writeSlcanCommand(gatt, writeChar, "ATH1\r", "ATH1")
        delay(ELM327_CMD_DELAY_MS)

        // ATSP0 — auto-detect protocol
        writeSlcanCommand(gatt, writeChar, "ATSP0\r", "ATSP0")
        delay(ELM327_CMD_DELAY_MS)

        // ATAT1 — adaptive timing on
        writeSlcanCommand(gatt, writeChar, "ATAT1\r", "ATAT1")
        delay(ELM327_CMD_DELAY_MS)

        Log.i(TAG, "✅ ELM327 initialization complete")
        status = status.copy(detail = "Dauntless ELM327 OBD-II connected")
    }

    /**
     * OBD-II PIDs to poll cyclically in ELM327 mode.
     * Each entry is the Mode 01 PID command to send.
     */
    private val OBD_POLL_PIDS = listOf(
        "010C" to "RPM",
        "010D" to "Speed",
        "0105" to "CoolantTemp",
        "0111" to "ThrottlePos",
        "0142" to "BatteryV",
        "0146" to "AmbientTemp",
    )

    /**
     * Actively polls OBD-II PIDs in a loop. Each PID query is sent, then
     * we wait for the response before sending the next. This ensures we
     * get fresh data for all channels, not just whatever the adapter
     * decides to stream autonomously.
     */
    @SuppressLint("MissingPermission")
    private suspend fun obdPollingLoop(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        Log.i(TAG, "OBD polling loop started with ${OBD_POLL_PIDS.size} PIDs")
        var pollCycle = 0L

        while (currentCoroutineContext().isActive) {
            for ((pidCmd, pidLabel) in OBD_POLL_PIDS) {
                if (!currentCoroutineContext().isActive) break

                writeSlcanCommand(gatt, writeChar, "$pidCmd\r", pidCmd)
                // Wait for response before sending next PID.
                delay(OBD_POLL_INTERVAL_MS)
            }
            pollCycle++
            if (pollCycle % 50 == 0L) {
                Log.d(TAG, "OBD poll cycle #$pollCycle complete")
            }
        }
        Log.i(TAG, "OBD polling loop stopped after $pollCycle cycles")
    }

    /**
     * Sends the SLCAN initialization sequence with baud rate auto-detection.
     * Tries each baud rate in [BAUD_SCAN_ORDER], opens the channel, and waits
     * up to [BAUD_PROBE_MS] for CAN frames. Locks in the first rate that produces data.
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendSlcanInit(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        // Reset adapter with a bare CR first.
        writeSlcanCommand(gatt, writeChar, "\r", "<CR>")
        delay(SLCAN_CMD_DELAY_MS)

        for ((baudCmd, baudLabel) in BAUD_SCAN_ORDER) {
            Log.i(TAG, "▶ Trying baud rate $baudLabel ($baudCmd)...")

            // Close any open channel.
            writeSlcanCommand(gatt, writeChar, "C\r", "C")
            delay(SLCAN_CMD_DELAY_MS)

            // Set baud rate.
            writeSlcanCommand(gatt, writeChar, "$baudCmd\r", baudCmd)
            delay(SLCAN_CMD_DELAY_MS)

            // Open channel.
            writeSlcanCommand(gatt, writeChar, "O\r", "O")
            delay(SLCAN_CMD_DELAY_MS)

            // Snapshot the current notification count, then wait for frames.
            val countBefore = bleNotifyCount
            val probeStart = elapsedRealtimeMs()

            // Wait up to BAUD_PROBE_MS, checking every 200ms for new notifications.
            var gotFrames = false
            while (elapsedRealtimeMs() - probeStart < BAUD_PROBE_MS) {
                delay(200)
                // Any new notifications beyond the SLCAN ack responses mean real CAN data.
                if (bleNotifyCount > countBefore + 2) {
                    gotFrames = true
                    break
                }
            }

            if (gotFrames) {
                Log.i(TAG, "✅ CAN frames detected at $baudLabel ($baudCmd) — locking in")
                status = status.copy(
                    detail = "Dauntless CAN BLE connected at $baudLabel",
                )
                return
            }
            Log.w(TAG, "✗ No CAN frames at $baudLabel after ${BAUD_PROBE_MS}ms, trying next...")
        }

        // Exhausted all rates — fall back to 500kbps and leave channel open.
        Log.w(TAG, "⚠ No CAN frames detected at any baud rate. Falling back to 500kbps (S6)")
        writeSlcanCommand(gatt, writeChar, "C\r", "C")
        delay(SLCAN_CMD_DELAY_MS)
        writeSlcanCommand(gatt, writeChar, "S6\r", "S6")
        delay(SLCAN_CMD_DELAY_MS)
        writeSlcanCommand(gatt, writeChar, "O\r", "O")
        Log.i(TAG, "SLCAN initialization complete (fallback S6)")
    }

    /** Writes a single SLCAN command and logs the result. */
    @SuppressLint("MissingPermission")
    private fun writeSlcanCommand(
        gatt: BluetoothGatt,
        writeChar: BluetoothGattCharacteristic,
        cmd: String,
        label: String,
    ) {
        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        Log.d(TAG, "Sending SLCAN command: $label (${bytes.size} bytes)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                writeChar,
                bytes,
                if (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            )
            Log.i(TAG, "SLCAN write '$label' result=$result (0=SUCCESS)")
        } else {
            @Suppress("DEPRECATION")
            writeChar.value = bytes
            writeChar.writeType =
                if (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            @Suppress("DEPRECATION")
            val legacyResult = gatt.writeCharacteristic(writeChar)
            Log.i(TAG, "SLCAN write '$label' legacyResult=$legacyResult")
        }
    }

    /**
     * Handles incoming BLE notification data — called from the GATT callback.
     * Thread-safe via the parser's internal synchronization.
     */
    private var bleNotifyCount = 0L
    private var bleByteCount = 0L

    private fun handleIncomingData(data: ByteArray) {
        bleNotifyCount++
        bleByteCount += data.size
        val now = elapsedRealtimeMs()
        // Accumulate raw ASCII text for protocol detection.
        val asciiText = data.toString(Charsets.US_ASCII)
        lastRxText += asciiText
        // Prevent unbounded growth — keep only last 512 chars.
        if (lastRxText.length > 512) {
            lastRxText = lastRxText.takeLast(256)
        }
        // Log raw ASCII for first 20 notifications to help debug parsing.
        if (bleNotifyCount <= 20) {
            val ascii = asciiText.replace("\r", "\\r").replace("\n", "\\n")
            Log.i(TAG, "BLE RX #$bleNotifyCount: ${data.size}B ascii=[$ascii] hex=${data.joinToString("") { "%02X".format(it) }}")
        } else if (bleNotifyCount % 200 == 0L) {
            Log.d(TAG, "BLE RX total: $bleNotifyCount notifications, ${bleByteCount}B, slcanErrors=${parser.decodeErrors} elm327Errors=$elm327DecodeErrors")
        }

        if (elm327Detected) {
            // --- ELM327 OBD-II mode ("7E8 04 41 0C 0A DC\r") ---
            val obdSamples = elm327AppendAndParse(data, now)
            if (obdSamples.isNotEmpty()) {
                var sample = latest
                obdSamples.forEach { obdUpdate ->
                    sample = obdUpdate
                    latest = sample
                }
                updateStatus(sample)
            }
        } else {
            // --- SLCAN mode (AiM CAN2 framing: "t4208...") ---
            val slcanFrames = parser.append(data, now)
            if (slcanFrames.isNotEmpty()) {
                Log.i(TAG, "SLCAN: Parsed ${slcanFrames.size} CAN frames: ${slcanFrames.joinToString { "0x%03X".format(it.id) }}")
                var sample = latest
                slcanFrames.forEach { frame ->
                    updateFrameRate(frame.id, frame.receivedAtElapsedMs)
                    sample = AimCanDecoder.applyFrame(
                        previous = sample?.copy(frameRatesHz = frameRatesHz),
                        frame = frame,
                        decodeErrors = parser.decodeErrors,
                    ).copy(frameRatesHz = frameRatesHz)
                    latest = sample
                }
                updateStatus(sample)
            }
        }
    }

    private fun updateStatus(sample: AimCanSample?) {
        if (sample != null) {
            status = AimCanClientStatus(
                connected = true,
                detail = sample.statusText()
                    ?: "Dauntless CAN BLE connected; waiting for recognized CAN frames",
                usbDeviceName = status.usbDeviceName,
                reconnectCount = reconnectCount,
                decodeErrors = parser.decodeErrors + elm327DecodeErrors,
            )
        }
    }

    /**
     * Parses ELM327-format OBD-II responses from raw BLE notification bytes.
     * The adapter sends ASCII lines like "7E8 04 41 0C 0A DC\r" where:
     *   - 7E8 = CAN ID (standard ECU response)
     *   - 04  = data byte count
     *   - 41  = OBD Mode 01 positive response
     *   - 0C  = PID
     *   - 0A DC = PID data bytes
     */
    private fun elm327AppendAndParse(data: ByteArray, nowElapsedMs: Long): List<AimCanSample> {
        val results = mutableListOf<AimCanSample>()
        val ascii = data.toString(Charsets.US_ASCII)

        ascii.forEach { char ->
            when (char) {
                '\r', '\n', '>' -> {
                    // '>' is the ELM327 prompt — treat it as a line terminator.
                    val line = elm327Buffer.toString().trim()
                    elm327Buffer.clear()
                    if (line.isNotEmpty()) {
                        parseElm327Line(line, nowElapsedMs)?.let(results::add)
                    }
                }
                else -> {
                    elm327Buffer.append(char)
                    if (elm327Buffer.length > 128) {
                        elm327Buffer.clear()
                        elm327DecodeErrors++
                    }
                }
            }
        }
        return results
    }

    /**
     * Parses a single ELM327 ASCII line into an AimCanSample update.
     * Expects format: "7E8 04 41 0C 0A DC" (CAN_ID LEN MODE PID DATA...)
     */
    private fun parseElm327Line(line: String, nowElapsedMs: Long): AimCanSample? {
        // Debug: log every line the parser sees.
        Log.d(TAG, "ELM327 parse line: [$line] (${line.length} chars)")
        // Skip known control responses.
        if (line == ">" || line.startsWith("STOPPED") || line.startsWith("OK") ||
            line.startsWith("ELM") || line.startsWith("AT") || line.length < 5
        ) {
            return null
        }

        val tokens = line.split(' ')
        if (tokens.size < 4) return null

        // Token[0] = CAN ID (e.g. "7E8"), Token[1] = byte count
        val canId = tokens[0].toIntOrNull(16) ?: return null
        // We only care about standard ECU responses (0x7E8-0x7EF)
        if (canId !in 0x7E8..0x7EF) return null

        // Token[2] = OBD mode response (0x41 = response to Mode 01)
        val mode = tokens[2].toIntOrNull(16) ?: return null
        if (mode != 0x41) return null

        // Token[3] = PID
        val pid = tokens[3].toIntOrNull(16) ?: return null

        // Remaining tokens = data bytes
        val dataBytes = tokens.drop(4).mapNotNull { it.toIntOrNull(16) }

        val base = latest ?: AimCanSample(receivedAtElapsedMs = nowElapsedMs)
        val updated = applyObdPid(base, pid, dataBytes, nowElapsedMs, line)
        if (updated != null) {
            Log.i(TAG, "ELM327: PID 0x${"%02X".format(pid)} → ${obdPidName(pid)} from [$line]")
            return updated
        }
        return null
    }

    /**
     * Maps standard OBD-II Mode 01 PIDs to AimCanSample fields.
     * Returns null if the PID is unrecognized or data is insufficient.
     */
    private fun applyObdPid(
        base: AimCanSample,
        pid: Int,
        data: List<Int>,
        nowElapsedMs: Long,
        raw: String,
    ): AimCanSample? {
        // Use a synthetic frame ID range (0xBD0 + pid) to track freshness.
        val synthId = OBD_PID_FRAME_BASE + pid

        // Map OBD PID → AimCan frame ID the UI's freshness checks expect.
        // Without this, hasFreshAimCanVehicleChannels() never sees fresh data.
        val canonicalFrameId = obdPidToAimCanFrameId(pid)

        var updates = base.channelUpdatedAtElapsedMs + (synthId to nowElapsedMs)
        if (canonicalFrameId != null) {
            updates = updates + (canonicalFrameId to nowElapsedMs)
        }
        val rawSamples = base.rawCanSamplesById + (synthId to raw)
        val common = base.copy(
            receivedAtElapsedMs = nowElapsedMs,
            channelUpdatedAtElapsedMs = updates,
            rawCanSample = raw,
            rawCanSamplesById = rawSamples,
        )

        return when (pid) {
            // 0x0C: Engine RPM. Formula: ((A*256)+B)/4
            0x0C -> {
                if (data.size < 2) return null
                val rpm = ((data[0] * 256) + data[1]) / 4
                common.copy(rpm = rpm)
            }
            // 0x0D: Vehicle speed (km/h → mph)
            0x0D -> {
                if (data.isEmpty()) return null
                val speedKmh = data[0]
                val speedMph = speedKmh * 0.621371
                common.copy(gpsSpeedMph = speedMph, ecuSpeedMph = speedMph)
            }
            // 0x05: Engine coolant temperature (°C, offset by -40)
            0x05 -> {
                if (data.isEmpty()) return null
                val tempC = data[0] - 40.0
                common.copy(waterTempC = tempC)
            }
            // 0x11: Throttle position (%)
            0x11 -> {
                if (data.isEmpty()) return null
                val throttle = data[0] * 100.0 / 255.0
                common.copy(pedalPositionPercent = throttle)
            }
            // 0x5C: Engine oil temperature (°C, offset by -40)
            0x5C -> {
                if (data.isEmpty()) return null
                val tempC = data[0] - 40.0
                common.copy(engineOilTempC = tempC)
            }
            // 0x42: Control module voltage (V). Formula: ((A*256)+B)/1000
            0x42 -> {
                if (data.size < 2) return null
                val voltage = ((data[0] * 256) + data[1]) / 1000.0
                common.copy(batteryVoltage = voltage)
            }
            // 0x46: Ambient air temperature (°C, offset by -40)
            0x46 -> {
                if (data.isEmpty()) return null
                val tempC = data[0] - 40.0
                common.copy(outsideTempC = tempC)
            }
            else -> null // Unrecognized PID — skip silently
        }
    }

    private fun obdPidName(pid: Int): String = when (pid) {
        0x0C -> "RPM"
        0x0D -> "VehicleSpeed"
        0x05 -> "CoolantTemp"
        0x11 -> "ThrottlePos"
        0x5C -> "OilTemp"
        0x42 -> "BatteryVoltage"
        0x46 -> "AmbientTemp"
        else -> "Unknown(0x${"%02X".format(pid)})"
    }

    /**
     * Maps OBD-II Mode 01 PIDs to the canonical AimCanFrameId that the
     * UI freshness gate ([hasFreshAimCanVehicleChannels]) expects.
     *
     * Returns null for PIDs that don't map to any AimCan channel.
     */
    private fun obdPidToAimCanFrameId(pid: Int): Int? = when (pid) {
        0x0C -> AimCanFrameIds.CORE       // RPM → CORE
        0x0D -> AimCanFrameIds.ECU        // Speed → ECU (ecuSpeedMph)
        0x05 -> AimCanFrameIds.CORE       // Coolant temp → CORE (waterTempC)
        0x11 -> AimCanFrameIds.CONTROLS   // Throttle → CONTROLS (pedalPositionPercent)
        0x5C -> AimCanFrameIds.ECU        // Oil temp → ECU (engineOilTempC)
        0x42 -> AimCanFrameIds.CORE       // Voltage → CORE (batteryVoltage)
        0x46 -> AimCanFrameIds.CORE       // Ambient temp → CORE (outsideTempC)
        else -> null
    }

    /**
     * Blocks the coroutine while BLE notifications stream data.
     * Returns when the GATT connection is lost.
     */
    private suspend fun streamFromGatt(connection: GattConnection, deviceName: String) {
        status = AimCanClientStatus(
            connected = true,
            detail = "Dauntless CAN BLE streaming from $deviceName",
            usbDeviceName = deviceName,
            reconnectCount = reconnectCount,
            decodeErrors = parser.decodeErrors,
        )

        // BLE notifications are delivered via the GATT callback. We just
        // need to keep the coroutine alive and poll for disconnection.
        while (currentCoroutineContext().isActive) {
            if (connection.disconnected) {
                throw DauntlessUnavailable("BLE GATT disconnected from $deviceName")
            }
            delay(500)
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

    @SuppressLint("MissingPermission")
    private suspend fun closeGatt() {
        withContext(Dispatchers.IO) {
            runCatching {
                activeGatt?.disconnect()
                activeGatt?.close()
            }
            activeGatt = null
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
        return "Dauntless CAN BLE $mode ${parts.joinToString(", ").ifBlank { "recognized frames" }}"
    }

    /** Result of GATT connection + service discovery. */
    private data class GattConnection(
        val gatt: BluetoothGatt,
        val callback: BluetoothGattCallback,
        @Volatile var disconnected: Boolean = false,
    )

    /** Pair of write/notify characteristics found on the GATT server. */
    private data class CharacteristicPair(
        val write: BluetoothGattCharacteristic,
        val notify: BluetoothGattCharacteristic,
    )

    private class DauntlessUnavailable(message: String) : Exception(message)

    private companion object {
        private const val TAG = "DauntlessBT"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val FRAME_RATE_WINDOW_MS = 2_000L
        private const val LOOPBACK_FRAME_DELAY_MS = 4L
        private const val SLCAN_CMD_DELAY_MS = 150L
        private const val BAUD_PROBE_MS = 2_000L
        private const val ELM327_PROBE_WAIT_MS = 500L
        private const val ELM327_RESET_WAIT_MS = 1_500L
        private const val ELM327_CMD_DELAY_MS = 100L
        private const val OBD_POLL_INTERVAL_MS = 100L
        /** Synthetic frame-ID base for OBD-II PIDs (0xBD0 + PID). */
        private const val OBD_PID_FRAME_BASE = 0xBD0

        /** Filename for the debug loopback sim data. */
        private const val LOOPBACK_FILENAME = "dauntless-sim.slcan"

        /** Client Characteristic Configuration Descriptor UUID. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Well-known UART-like BLE service UUIDs to try, in priority order.
         * - Nordic UART Service (NUS)
         * - Common OBD BLE custom services (0xFFF0, 0xFFE0)
         */
        private val KNOWN_UART_SERVICES = listOf(
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), // Nordic UART Service
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"), // Common OBD custom service
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"), // Common ELM327 BLE service
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"), // RN4870/RN4871 transparent UART
        )

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
