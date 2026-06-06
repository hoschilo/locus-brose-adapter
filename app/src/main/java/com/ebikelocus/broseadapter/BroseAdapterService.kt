package com.ebikelocus.broseadapter

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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

class BroseAdapterService : LocusParserAdapterService() {

    private val secPackets = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val currentData = mutableMapOf<String, BikeData>()
    private val liveDataTimers = mutableMapOf<String, Timer>()

    private var lastRecordingState: Boolean? = null
    private var recordingCheckTimer: Timer? = null
    @Volatile private var locusPackageName: String? = null

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra("bytes") ?: return
            currentData.keys.forEach { deviceId ->
                writeData(deviceId, listOf(AdapterWrite(CHAR_COMMAND, bytes)))
            }
            Log.d(TAG, "cmdReceiver: dispatched ${bytes.toHex()} to ${currentData.size} device(s)")
        }
    }

    override fun init(deviceId: String, deviceTypeId: String, bindContext: LocusBindContext): Int {
        locusPackageName = bindContext.locusPackageName
        Log.d(TAG, "init: deviceId=$deviceId type=$deviceTypeId locusPackage=${bindContext.locusPackageName}")
        liveDataTimers[deviceId]?.cancel()
        secPackets[deviceId] = mutableMapOf()
        currentData[deviceId] = BikeData()

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
        return when {
            source.equals(CHAR_TELEMETRY, ignoreCase = true) -> parseTel(deviceId, bytes)
            source.equals(CHAR_SECONDARY, ignoreCase = true) -> parseSec(deviceId, bytes)
            else -> null
        }
    }

    private fun parseTel(deviceId: String, raw: ByteArray): SensorValueBatch? {
        Log.d(TAG, "TEL raw (${raw.size}b): ${raw.toHex()}")
        if (raw.size < 13) return null
        val data    = currentData[deviceId] ?: BikeData()
        val updated = BroseProtocol.parseMessage(raw, data)
        currentData[deviceId] = updated
        Log.d(TAG, "TEL parsed: speed=${updated.speed} cadence=${updated.cadence} batt=${updated.batteryPercent} assist=${updated.assistMode}")
        DiagnosticStore.writeTel(this, raw.toHex(), updated)

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
        val seq     = raw[0].toInt() and 0xFF
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
        if (seq != 0x83) return null

        val combined = buildCombined(packets)
        Log.d(TAG, "SEC combined (${combined.size}b): ${combined.toHex()}")
        val sec      = BroseProtocol.parseSecFull(combined)
        Log.d(TAG, "SEC parsed: motorPwr=${sec.motorPowerW} riderPwr=${sec.riderPowerW} range=${sec.estimatedRangeKm} batt=${sec.batteryPercent} temp=${sec.motorTempC}")

        val last = currentData[deviceId] ?: BikeData()
        val updated = last.copy(
            motorPower          = sec.motorPowerW,
            riderPower          = sec.riderPowerW,
            estimatedRange      = if (sec.estimatedRangeKm >= 0) sec.estimatedRangeKm else last.estimatedRange,
            batteryPercent      = if (sec.batteryPercent >= 0) sec.batteryPercent else last.batteryPercent,
            motorTempC          = if (sec.motorTempC != Int.MIN_VALUE) sec.motorTempC else last.motorTempC,
            pedalTorqueNm       = sec.pedalTorqueNm,
            lightOn             = sec.lightOn,
            tripDistanceM       = if (sec.tripDistanceM >= 0) sec.tripDistanceM else last.tripDistanceM,
            batteryWhAbsolute   = if (sec.batteryWhAbsolute >= 0) sec.batteryWhAbsolute else last.batteryWhAbsolute,
            batteryVoltageV     = if (!sec.batteryVoltageV.isNaN()) sec.batteryVoltageV else last.batteryVoltageV,
            batteryTempC        = if (sec.batteryTempC != Int.MIN_VALUE) sec.batteryTempC else last.batteryTempC,
            currentScaling      = if (sec.currentScaling >= 0) sec.currentScaling else last.currentScaling,
            supportProfileScale = if (sec.supportProfileScale >= 0) sec.supportProfileScale else last.supportProfileScale
        )
        currentData[deviceId] = updated
        DiagnosticStore.writeSec(this, combined.toHex(), updated)

        val builder = SensorValueBatchBuilder(System.currentTimeMillis())
        builder.put(LocusVariable.Speed, last.speed / 3.6f)
        builder.put(LocusVariable.Cadence, last.cadence)
        builder.put(LocusVariable.AssistMode, assistModeLabel(last.assistMode))
        if (updated.batteryPercent >= 0) builder.put(LocusVariable.BicycleBattery, updated.batteryPercent)
        if (updated.estimatedRange >= 0) builder.put(LocusVariable.Range, updated.estimatedRange * 1000f)
        builder.put(LocusVariable.Power, updated.riderPower.coerceAtLeast(0))

        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()
        recordingCheckTimer = Timer("recordingCheck", true).also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { checkRecordingState() }
            }, 2000L, 2000L)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cmdReceiver, IntentFilter(CMD_ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cmdReceiver, IntentFilter(CMD_ACTION))
        }
        Log.d(TAG, "onCreate: cmdReceiver registered")
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
        try { unregisterReceiver(cmdReceiver) } catch (_: Exception) {}
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
            Handler(Looper.getMainLooper()).postDelayed({
                try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
            }, 600L)
        } catch (e: Exception) {
            Log.w(TAG, "closeStaleGatt failed: $e")
        }
    }

    private fun assistModeLabel(mode: String) = when (mode) {
        "OFF" -> "OFF"; "ECO" -> "ECO"; "TOUR" -> "TOUR"
        "SPORT" -> "SPORT"; "BOOST" -> "BOOST"; else -> mode
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
        const val CMD_ACTION = "com.ebikelocus.broseadapter.CMD"
        private const val CHAR_TELEMETRY = "31be23a6-d927-11e9-8a34-2a2ae2dbcce4"
        private const val CHAR_SECONDARY = "31be32ba-d927-11e9-8a34-2a2ae2dbcce4"
        private const val CHAR_COMMAND   = "31be3634-d927-11e9-8a34-2a2ae2dbcce4"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }
