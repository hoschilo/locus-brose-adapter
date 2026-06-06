package com.ebikelocus.broseadapter

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import locus.api.android.ActionBasics
import locus.api.android.features.sensorAdapter.AdapterApi
import locus.api.android.features.sensorAdapter.LocusBindContext
import locus.api.android.features.sensorAdapter.LocusVariable
import locus.api.android.features.sensorAdapter.parser.AdapterWrite
import locus.api.android.features.sensorAdapter.parser.LocusParserAdapterService
import locus.api.android.features.sensorAdapter.parser.SensorValueBatch
import locus.api.android.features.sensorAdapter.parser.SensorValueBatchBuilder
import locus.api.android.utils.LocusUtils
import java.util.Timer
import java.util.TimerTask

private const val TAG = "BroseAdapterService"

/**
 * Locus Maps Sensor Adapter for Brose e-bike drive units.
 *
 * Locus handles the BLE connection to the Brose motor directly.
 * This adapter parses the raw TEL and SEC frames and returns
 * typed sensor values to Locus: Speed, Cadence, Power, Battery, AssistMode, Range.
 *
 * Brose BLE Service UUID: 31be2300-d927-11e9-8a34-2a2ae2dbcce4
 *
 * Frame types:
 *  - TEL (31be23a6-...): 13-byte telemetry, ~7x/sec, contains speed/cadence/battery/assist
 *  - SEC (31be32ba-...): 4-packet protobuf, triggered by live-data-request every 2s,
 *                        contains motor power, rider power, estimated range
 *  - CMD (31be3634-...): write-only, used to send live-data-request
 */
class BroseAdapterService : LocusParserAdapterService() {

    // Per-deviceId SEC packet reassembly state
    private val secPackets = mutableMapOf<String, MutableMap<Int, ByteArray>>()

    // Per-deviceId last known bike state (TEL and SEC complement each other)
    private val currentData = mutableMapOf<String, BikeData>()

    // Per-deviceId timer for periodic live-data-request (every 2s)
    private val liveDataTimers = mutableMapOf<String, Timer>()

    // null = not yet sampled (avoids spurious reset on service start while recording already active)
    private var lastRecordingState: Boolean? = null
    private var recordingCheckTimer: Timer? = null
    @Volatile private var locusPackageName: String? = null

    override fun init(deviceId: String, deviceTypeId: String, bindContext: LocusBindContext): Int {
        locusPackageName = bindContext.locusPackageName
        Log.d(TAG, "init: deviceId=$deviceId type=$deviceTypeId locusPackage=${bindContext.locusPackageName}")
        // Cancel any existing timer for this deviceId — handles reconnect without prior shutdown()
        liveDataTimers[deviceId]?.cancel()
        secPackets[deviceId] = mutableMapOf()
        currentData[deviceId] = BikeData()

        // Check if recording already active — schedule reset via timer so it runs after the
        // BLE stack is settled, with no live-data competing in the write queue.
        var needsReset = false
        if (lastRecordingState == null) {
            try {
                val lv = LocusUtils.createLocusVersion(this, bindContext.locusPackageName)
                val container = lv?.let { ActionBasics.getUpdateContainer(this, it) }
                val isRecording = container?.isTrackRecRecording ?: false
                lastRecordingState = isRecording
                if (isRecording) {
                    Log.d(TAG, "init: recording active — will reset trip distance in 1s for $deviceId")
                    needsReset = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "init: failed to check recording state: $e")
                lastRecordingState = false
            }
        }

        val timer = Timer("livedata-$deviceId", true)
        if (needsReset) {
            // Send reset at 1s (BLE settled, no prior CMD writes competing)
            // Resume live-data only at 3s (gives motor 2s to process reset)
            timer.schedule(object : TimerTask() {
                override fun run() {
                    Log.d(TAG, "init: sending trip reset for $deviceId")
                    writeData(deviceId, listOf(AdapterWrite(CHAR_COMMAND, BroseProtocol.RESET_TRIP_DISTANCE)))
                }
            }, 1000L)
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { sendLiveDataRequest(deviceId) }
            }, 3000L, 1500L)
        } else {
            sendLiveDataRequest(deviceId)
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { sendLiveDataRequest(deviceId) }
            }, 500L, 1500L)
        }
        liveDataTimers[deviceId] = timer

        return AdapterApi.INIT_OK
    }

    private fun sendLiveDataRequest(deviceId: String) {
        writeData(deviceId, listOf(AdapterWrite(CHAR_COMMAND, BroseProtocol.LIVE_DATA_REQUEST)))
        Log.d(TAG, "sendLiveDataRequest: $deviceId")
    }

    override fun parseData(deviceId: String, source: String, bytes: ByteArray): SensorValueBatch? {
        DiagnosticStore.writeSource(this, source)
        return when {
            source.equals(CHAR_TELEMETRY, ignoreCase = true) -> parseTel(deviceId, bytes)
            source.equals(CHAR_SECONDARY, ignoreCase = true) -> parseSec(deviceId, bytes)
            else -> null
        }
    }

    private fun parseTel(deviceId: String, raw: ByteArray): SensorValueBatch? {
        Log.d(TAG, "TEL raw (${raw.size}b): ${raw.toHex()}")
        if (raw.size < 13) return null
        val data = currentData[deviceId] ?: BikeData()
        val updated = BroseProtocol.parseMessage(raw, data)
        currentData[deviceId] = updated
        Log.d(TAG, "TEL parsed: speed=${updated.speed} cadence=${updated.cadence} batt=${updated.batteryPercent} assist=${updated.assistMode}")
        DiagnosticStore.writeTel(this, raw.toHex(), updated.speed, updated.cadence, updated.batteryPercent, updated.assistMode)

        return SensorValueBatchBuilder(System.currentTimeMillis())
            .put(LocusVariable.Speed, updated.speed / 3.6f)
            .put(LocusVariable.Cadence, updated.cadence)
            .put(LocusVariable.BicycleBattery, updated.batteryPercent)
            .put(LocusVariable.AssistMode, assistModeLabel(updated.assistMode))
            .build()
    }

    private fun parseSec(deviceId: String, raw: ByteArray): SensorValueBatch? {
        Log.d(TAG, "SEC raw (${raw.size}b): ${raw.toHex()}")
        if (raw.size < 2) return null
        val seq = raw[0].toInt() and 0xFF
        val payload = raw.copyOfRange(1, raw.size)
        val packets = secPackets.getOrPut(deviceId) { mutableMapOf() }

        // SEC framing: seq=0x00 is first packet (skip 2-byte length prefix), 0x83 is last
        if (seq == 0) {
            packets.clear()
            packets[0] = payload.copyOfRange(2.coerceAtMost(payload.size), payload.size)
        } else {
            packets[seq] = payload
        }
        Log.d(TAG, "SEC seq=0x${seq.toString(16)} packets=${packets.size}")
        if (seq != 0x83) return null  // not yet complete

        val combined    = buildCombined(packets)
        Log.d(TAG, "SEC combined (${combined.size}b): ${combined.toHex()}")
        val motorPower  = BroseProtocol.extractPowerFromProtobuf(combined)
        val riderPower  = BroseProtocol.extractRiderPower(combined)
        val estRange    = BroseProtocol.extractEstimatedRange(combined)
        val batt        = BroseProtocol.extractBatteryFromProtobuf(combined)
        Log.d(TAG, "SEC parsed: motorPower=$motorPower riderPower=$riderPower estRange=$estRange batt=$batt")
        DiagnosticStore.writeSec(this, combined.toHex(), motorPower, estRange, batt)

        val last     = currentData[deviceId] ?: BikeData()
        val isMoving = last.speed > 0.5f
        val builder  = SensorValueBatchBuilder(System.currentTimeMillis())

        // Piggyback last known TEL state — keeps Locus updated when TEL is silent (OFF mode)
        builder.put(LocusVariable.Speed, last.speed / 3.6f)
        builder.put(LocusVariable.Cadence, last.cadence)
        builder.put(LocusVariable.AssistMode, assistModeLabel(last.assistMode))

        if (batt >= 0)   builder.put(LocusVariable.BicycleBattery, batt)
        if (estRange >= 0) builder.put(LocusVariable.Range, estRange * 1000f) // km → m

        builder.put(LocusVariable.Power, riderPower.coerceAtLeast(0))

        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()
        recordingCheckTimer = Timer("recordingCheck", true).also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { checkRecordingState() }
            }, 2000L, 2000L)
        }
    }

    private fun checkRecordingState() {
        try {
            val pkg = locusPackageName ?: run {
                Log.d(TAG, "checkRecording: no locusPackageName yet")
                return
            }
            val lv = LocusUtils.createLocusVersion(this, pkg) ?: run {
                Log.w(TAG, "checkRecording: createLocusVersion returned null for $pkg")
                return
            }
            val container = ActionBasics.getUpdateContainer(this, lv) ?: run {
                Log.w(TAG, "checkRecording: getUpdateContainer returned null")
                return
            }
            val isRecording = container.isTrackRecRecording
            val prev = lastRecordingState
            lastRecordingState = isRecording
            Log.d(TAG, "checkRecording: isRecording=$isRecording prev=$prev")
            if (prev == false && isRecording) {
                Log.d(TAG, "Tour started — resetting trip distance on ${currentData.keys}")
                currentData.keys.forEach { deviceId ->
                    // Pause live-data stream so motor is idle when reset arrives
                    liveDataTimers[deviceId]?.cancel()
                    writeData(deviceId, listOf(AdapterWrite(CHAR_COMMAND, BroseProtocol.RESET_TRIP_DISTANCE)))
                    val timer = Timer("livedata-$deviceId", true)
                    timer.scheduleAtFixedRate(object : TimerTask() {
                        override fun run() { sendLiveDataRequest(deviceId) }
                    }, 800L, 1500L)
                    liveDataTimers[deviceId] = timer
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkRecording: exception $e")
        }
    }

    override fun getIntentForSettings(deviceId: String): Intent? = null

    override fun shutdown(deviceId: String) {
        liveDataTimers[deviceId]?.cancel()
        liveDataTimers.remove(deviceId)
        secPackets.remove(deviceId)
        currentData.remove(deviceId)
        Log.d(TAG, "shutdown: $deviceId")
        closeStaleGatt(deviceId)
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingCheckTimer?.cancel()
        recordingCheckTimer = null
        liveDataTimers.values.forEach { it.cancel() }
        liveDataTimers.clear()
        Log.d(TAG, "onDestroy: all timers cancelled")
    }

    // Android BLE GATT connections can linger after ungraceful disconnect (e.g. motor turned off
    // out of range). Connecting + immediately closing forces the stack to clean up the stale state,
    // avoiding the "device not found" symptom that requires a BT toggle to fix.
    private fun closeStaleGatt(deviceId: String) {
        if (!BluetoothAdapter.checkBluetoothAddress(deviceId)) return
        try {
            val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter ?: return
            val device = btAdapter.getRemoteDevice(deviceId)
            val gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    gatt.disconnect()
                    gatt.close()
                    Log.d(TAG, "closeStaleGatt: GATT closed for $deviceId (state=$newState)")
                }
            })
            // Fallback: close regardless after 600ms in case callback never fires
            Handler(Looper.getMainLooper()).postDelayed({
                try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
            }, 600L)
        } catch (e: Exception) {
            Log.w(TAG, "closeStaleGatt failed: $e")
        }
    }

    private fun assistModeLabel(mode: String) = when (mode) {
        "OFF"   -> "OFF"
        "ECO"   -> "ECO"
        "TOUR"  -> "TOUR"
        "SPORT" -> "SPORT"
        "BOOST" -> "BOOST"
        else    -> mode
    }

    private fun buildCombined(packets: Map<Int, ByteArray>): ByteArray {
        val keys   = packets.keys.sorted().filter { it != 0x83 } + listOf(0x83)
        val chunks = keys.mapNotNull { packets[it] }
        return ByteArray(chunks.sumOf { it.size }).also { buf ->
            var off = 0
            chunks.forEach { chunk -> System.arraycopy(chunk, 0, buf, off, chunk.size); off += chunk.size }
        }
    }

    companion object {
        private const val CHAR_TELEMETRY = "31be23a6-d927-11e9-8a34-2a2ae2dbcce4"
        private const val CHAR_SECONDARY = "31be32ba-d927-11e9-8a34-2a2ae2dbcce4"
        private const val CHAR_COMMAND   = "31be3634-d927-11e9-8a34-2a2ae2dbcce4"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }
