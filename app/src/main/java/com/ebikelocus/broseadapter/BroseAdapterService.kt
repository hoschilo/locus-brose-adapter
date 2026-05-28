package com.ebikelocus.broseadapter

import android.content.Intent
import android.util.Log
import locus.api.android.features.sensorAdapter.AdapterApi
import locus.api.android.features.sensorAdapter.LocusBindContext
import locus.api.android.features.sensorAdapter.LocusVariable
import locus.api.android.features.sensorAdapter.parser.LocusParserAdapterService
import locus.api.android.features.sensorAdapter.parser.SensorValueBatch
import locus.api.android.features.sensorAdapter.parser.SensorValueBatchBuilder
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

    override fun init(deviceId: String, deviceTypeId: String, bindContext: LocusBindContext): Int {
        Log.d(TAG, "init: deviceId=$deviceId type=$deviceTypeId")
        secPackets[deviceId] = mutableMapOf()
        currentData[deviceId] = BikeData()

        // Send initial live-data-request immediately to start SEC stream
        sendLiveDataRequest(deviceId, bindContext)

        // Repeat every 2 seconds (Brose only sends SEC on request)
        val timer = Timer("livedata-$deviceId", true)
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { sendLiveDataRequest(deviceId, bindContext) }
        }, 2000L, 2000L)
        liveDataTimers[deviceId] = timer

        return AdapterApi.INIT_OK
    }

    private fun sendLiveDataRequest(deviceId: String, bindContext: LocusBindContext) {
        // TODO: verify exact writeData() signature from LocusBindContext once API is published
        // bindContext.writeData(deviceId, CHAR_COMMAND, BroseProtocol.LIVE_DATA_REQUEST)
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
        if (raw.size < 13) return null
        val data = currentData[deviceId] ?: BikeData()
        val updated = BroseProtocol.parseMessage(raw, data)
        currentData[deviceId] = updated

        return SensorValueBatchBuilder(System.currentTimeMillis())
            .put(LocusVariable.Speed, updated.speed / 3.6f)           // km/h → m/s
            .put(LocusVariable.Cadence, updated.cadence)
            .put(LocusVariable.BicycleBattery, updated.batteryPercent)
            .put(LocusVariable.AssistMode, assistModeLabel(updated.assistMode))
            .build()
    }

    private fun parseSec(deviceId: String, raw: ByteArray): SensorValueBatch? {
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
        if (seq != 0x83) return null  // not yet complete

        val combined = buildCombined(packets)
        val motorPower = BroseProtocol.extractPowerFromProtobuf(combined)
        val estRange   = BroseProtocol.extractEstimatedRange(combined)
        val batt       = BroseProtocol.extractBatteryFromProtobuf(combined)

        val isMoving = (currentData[deviceId]?.speed ?: 0f) > 0.5f
        val builder  = SensorValueBatchBuilder(System.currentTimeMillis())
        if (motorPower >= 0 && isMoving) builder.put(LocusVariable.Power, motorPower)
        if (batt >= 0)                   builder.put(LocusVariable.BicycleBattery, batt)
        if (estRange >= 0)               builder.put(LocusVariable.Range, estRange * 1000f) // km → m
        return builder.build()
    }

    override fun getIntentForSettings(deviceId: String): Intent? = null

    override fun shutdown(deviceId: String) {
        liveDataTimers[deviceId]?.cancel()
        liveDataTimers.remove(deviceId)
        secPackets.remove(deviceId)
        currentData.remove(deviceId)
        Log.d(TAG, "shutdown: $deviceId")
    }

    private fun assistModeLabel(mode: String) = when (mode) {
        "ECO"   -> "ECO"
        "TOUR"  -> "TOUR"
        "SPORT" -> "SPORT"
        "BOOST" -> "BOOST"
        else    -> "ECO"  // OFF / not connected → ECO (0 is invalid for Locus)
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
