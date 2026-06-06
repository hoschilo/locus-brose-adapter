package com.ebikelocus.broseadapter

import java.util.UUID

object BroseProtocol {

    val SERVICE_UUID: UUID   = UUID.fromString("31be2300-d927-11e9-8a34-2a2ae2dbcce4")
    val CHAR_TELEMETRY: UUID = UUID.fromString("31be23a6-d927-11e9-8a34-2a2ae2dbcce4")
    val CHAR_SECONDARY: UUID = UUID.fromString("31be32ba-d927-11e9-8a34-2a2ae2dbcce4")
    val CHAR_COMMAND: UUID   = UUID.fromString("31be3634-d927-11e9-8a34-2a2ae2dbcce4")
    val CCCD_UUID: UUID      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val LIVE_DATA_REQUEST: ByteArray = byteArrayOf(
        0x80.toByte(), 0x06, 0x00, 0x1A, 0x04, 0x08, 0x02, 0x2A, 0x00
    )

    val RESET_TRIP_DISTANCE: ByteArray = byteArrayOf(
        0x80.toByte(), 0x06, 0x00, 0x1A, 0x04, 0x08, 0x02, 0x5A.toByte(), 0x00
    )

    // write_support_profile: field 7, value 0=OFF 1=ECO 2=TOUR 3=SPORT 4=BOOST
    fun buildSupportProfileCmd(profile: Int): ByteArray = byteArrayOf(
        0x80.toByte(), 0x06, 0x00, 0x1A, 0x04, 0x08, 0x02, 0x38, profile.toByte()
    )

    // write_current_scaling: field 9, value 0-100 (percent)
    fun buildCurrentScalingCmd(value: Int): ByteArray = byteArrayOf(
        0x80.toByte(), 0x06, 0x00, 0x1A, 0x04, 0x08, 0x02, 0x48, value.toByte()
    )

    fun parseMessage(raw: ByteArray, current: BikeData): BikeData {
        if (raw.size < 13) return current
        return try {
            val battery    = (raw[12].toInt() and 0xFF).coerceIn(0, 100)
            val odoRaw     = (raw[8].toInt() and 0xFF) or ((raw[9].toInt() and 0xFF) shl 8)
            val odometer   = odoRaw / 10f
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

    // ── All-in-one SEC frame parser ───────────────────────────────────────────

    data class SecValues(
        val riderPowerW: Int = -1,
        val motorPowerW: Int = -1,
        val estimatedRangeKm: Int = -1,
        val motorTempC: Int = Int.MIN_VALUE,
        val pedalTorqueNm: Int = -1,
        val lightOn: Boolean = false,
        val batteryPercent: Int = -1,
        val batteryWhAbsolute: Int = -1,
        val batteryVoltageV: Float = Float.NaN,
        val batteryTempC: Int = Int.MIN_VALUE,
        val tripDistanceM: Int = -1,
        val currentScaling: Int = -1,
        val supportProfileScale: Int = -1
    )

    fun parseSecFull(data: ByteArray): SecValues {
        return try {
            val f4 = getNestedField(data, 4) ?: return SecValues()
            val f5 = getNestedField(f4, 5) ?: return SecValues()
            val f1 = getNestedField(f5, 1) // DriveUnitData
            val f2 = getNestedField(f5, 2) // BatteryData
            val f3 = getNestedField(f5, 3) // HMIData

            // DriveUnitData
            val riderPower  = f1?.let { getVarintField(it, 4).takeIf { v -> v > 0 }?.toInt()?.coerceIn(0, 1000) } ?: -1
            val rangeKm     = f1?.let { getVarintField(it, 10).takeIf { v -> v > 0 }?.toInt() } ?: -1
            val motorTemp   = f1?.let { getVarintField(it, 8).takeIf { v -> v in 0..200 }?.toInt() } ?: Int.MIN_VALUE
            val pedalTorque = f1?.let { getVarintField(it, 5).takeIf { v -> v > 0 }?.toInt() } ?: -1
            val lightOn     = f1?.let { getVarintField(it, 7) == 1L } ?: false

            // BatteryData
            val battCurrentRaw = f2?.let { getVarintField(it, 3) } ?: -1L
            val battVoltageRaw = f2?.let { getVarintField(it, 4) } ?: -1L
            val motorPower = if (battCurrentRaw >= 133 && battVoltageRaw > 0)
                (battCurrentRaw * battVoltageRaw / 5L / 1_000_000L).toInt().coerceIn(0, 600)
            else -1
            val battPercent = f2?.let { getVarintField(it, 1).takeIf { v -> v in 1..100 }?.toInt() } ?: -1
            val battWhAbs   = f2?.let { getVarintField(it, 2).takeIf { v -> v > 0 }?.let { v -> (v / 1000).toInt() } } ?: -1
            val battVoltage = if (battVoltageRaw > 0) battVoltageRaw / 1000f else Float.NaN
            val battTemp    = f2?.let { getVarintField(it, 6).takeIf { v -> v in 0..100 }?.toInt() } ?: Int.MIN_VALUE

            // HMIData
            val tripDistM  = f3?.let { getVarintField(it, 1).takeIf { v -> v >= 0 }?.toInt() } ?: -1
            val curScaling = f3?.let { getVarintField(it, 2).takeIf { v -> v in 0..100 }?.toInt() } ?: -1
            val profScale  = f3?.let { getVarintField(it, 3).takeIf { v -> v in 0..100 }?.toInt() } ?: -1

            SecValues(
                riderPowerW = riderPower, motorPowerW = motorPower, estimatedRangeKm = rangeKm,
                motorTempC = motorTemp, pedalTorqueNm = pedalTorque, lightOn = lightOn,
                batteryPercent = battPercent, batteryWhAbsolute = battWhAbs,
                batteryVoltageV = battVoltage, batteryTempC = battTemp,
                tripDistanceM = tripDistM, currentScaling = curScaling, supportProfileScale = profScale
            )
        } catch (e: Exception) { SecValues() }
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
                    val end   = (pos + len.toInt()).coerceAtMost(data.size)
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

    // Kept for backward compatibility
    fun extractPowerFromProtobuf(data: ByteArray): Int = parseSecFull(data).motorPowerW
    fun extractRiderPower(data: ByteArray): Int        = parseSecFull(data).riderPowerW
    fun extractEstimatedRange(data: ByteArray): Int    = parseSecFull(data).estimatedRangeKm
    fun extractBatteryFromProtobuf(data: ByteArray): Int = parseSecFull(data).batteryPercent
}
