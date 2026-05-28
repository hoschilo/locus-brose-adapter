package com.ebikelocus.broseadapter

import java.util.UUID

/**
 * Parses raw BLE frames from Brose drive unit motors.
 *
 * Two frame types:
 *  - TEL (CHAR_TELEMETRY): 13-byte telemetry frame with speed, cadence, battery, assist, odometer
 *  - SEC (CHAR_SECONDARY): multi-packet protobuf frame with motor power, rider power, range
 */
object BroseProtocol {

    val SERVICE_UUID: UUID  = UUID.fromString("31be2300-d927-11e9-8a34-2a2ae2dbcce4")
    val CHAR_TELEMETRY: UUID = UUID.fromString("31be23a6-d927-11e9-8a34-2a2ae2dbcce4")
    val CHAR_SECONDARY: UUID = UUID.fromString("31be32ba-d927-11e9-8a34-2a2ae2dbcce4")
    val CHAR_COMMAND: UUID   = UUID.fromString("31be3634-d927-11e9-8a34-2a2ae2dbcce4")
    val CCCD_UUID: UUID      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Sent to CMD characteristic every 2 seconds to trigger SEC data stream
    val LIVE_DATA_REQUEST: ByteArray = byteArrayOf(
        0x80.toByte(), 0x06, 0x00, 0x1A, 0x04, 0x08, 0x02, 0x2A, 0x00
    )

    fun parseMessage(raw: ByteArray, current: BikeData): BikeData {
        if (raw.size < 13) return current
        return try {
            val battery  = (raw[12].toInt() and 0xFF).coerceIn(0, 100)
            val odoRaw   = (raw[8].toInt() and 0xFF) or ((raw[9].toInt() and 0xFF) shl 8)
            val odometer = odoRaw / 10f
            val assistMode = when (raw[11].toInt() and 0xFF) {
                0x00 -> "OFF"; 0x01 -> "ECO"; 0x02 -> "TOUR"
                0x03 -> "SPORT"; 0x04 -> "BOOST"; else -> current.assistMode
            }
            val speedRaw = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
            val speed    = speedRaw / 100f
            val cadence  = raw[4].toInt() and 0xFF
            current.copy(
                speed          = speed,
                cadence        = cadence,
                batteryPercent = battery,
                odometerKm     = odometer,
                assistMode     = assistMode
            )
        } catch (e: Exception) { current }
    }

    // ── Protobuf helpers ──────────────────────────────────────────────────────

    private fun decodeVarint(data: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var p = pos
        while (p < data.size) {
            val b = data[p].toLong() and 0xFF; p++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
        }
        return Pair(result, p)
    }

    private fun getNestedField(data: ByteArray, targetField: Int): ByteArray? {
        var pos = 0
        while (pos < data.size) {
            val (tagWire, np) = decodeVarint(data, pos); pos = np
            val fn = (tagWire shr 3).toInt(); val wt = (tagWire and 7).toInt()
            when (wt) {
                0 -> { decodeVarint(data, pos).also { pos = it.second } }
                2 -> {
                    val (len, np2) = decodeVarint(data, pos); pos = np2
                    val end = (pos + len.toInt()).coerceAtMost(data.size)
                    val bytes = data.copyOfRange(pos, end); pos = end
                    if (fn == targetField) return bytes
                }
                5 -> pos += 4
                else -> break
            }
        }
        return null
    }

    private fun getVarintField(data: ByteArray, targetField: Int): Long {
        var pos = 0
        while (pos < data.size) {
            val (tagWire, np) = decodeVarint(data, pos); pos = np
            val fn = (tagWire shr 3).toInt(); val wt = (tagWire and 7).toInt()
            when (wt) {
                0 -> { val (v, np2) = decodeVarint(data, pos); pos = np2; if (fn == targetField) return v }
                2 -> { val (len, np2) = decodeVarint(data, pos); pos = np2; pos = (pos + len.toInt()).coerceAtMost(data.size) }
                5 -> pos += 4
                else -> break
            }
        }
        return -1L
    }

    // ── SEC frame extractors (F4→F5→...) ─────────────────────────────────────

    fun extractPowerFromProtobuf(data: ByteArray): Int {
        return try {
            val f4 = getNestedField(data, 4) ?: return -1
            val f5 = getNestedField(f4, 5) ?: return -1
            val f2 = getNestedField(f5, 2) ?: return -1
            val currentRaw = getVarintField(f2, 3)
            val voltageRaw = getVarintField(f2, 4)
            if (currentRaw < 133 || voltageRaw <= 0) return -1
            (currentRaw * voltageRaw / 5L / 1_000_000L).toInt().coerceIn(0, 600)
        } catch (e: Exception) { -1 }
    }

    fun extractRiderPower(data: ByteArray): Int {
        return try {
            val f4 = getNestedField(data, 4) ?: return -1
            val f5 = getNestedField(f4, 5) ?: return -1
            val f1 = getNestedField(f5, 1) ?: return -1
            val power = getVarintField(f1, 4)
            if (power <= 0) return -1
            power.toInt().coerceIn(0, 1000)
        } catch (e: Exception) { -1 }
    }

    fun extractEstimatedRange(data: ByteArray): Int {
        return try {
            val f4 = getNestedField(data, 4) ?: return -1
            val f5 = getNestedField(f4, 5) ?: return -1
            val f1 = getNestedField(f5, 1) ?: return -1
            val range = getVarintField(f1, 10)
            if (range <= 0) return -1
            range.toInt()
        } catch (e: Exception) { -1 }
    }

    fun extractBatteryFromProtobuf(data: ByteArray): Int {
        return try {
            val f4 = getNestedField(data, 4) ?: return -1
            val f5 = getNestedField(f4, 5) ?: return -1
            val f2 = getNestedField(f5, 2) ?: return -1
            val batt = getVarintField(f2, 1)
            if (batt <= 0 || batt > 100) return -1
            batt.toInt()
        } catch (e: Exception) { -1 }
    }
}
