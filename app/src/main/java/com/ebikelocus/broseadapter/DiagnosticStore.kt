package com.ebikelocus.broseadapter

import android.content.Context

object DiagnosticStore {

    private const val PREFS = "brose_diag"

    data class Snapshot(
        val speed: Float = -1f,
        val cadence: Int = -1,
        val riderPower: Int = -1,
        val motorPower: Int = -1,
        val batteryPercent: Int = -1,
        val estimatedRange: Int = -1,
        val odometerKm: Float = -1f,
        val assistMode: String = "---",
        val motorTempC: Int = Int.MIN_VALUE,
        val pedalTorqueNm: Int = -1,
        val lightOn: Boolean = false,
        val tripDistanceM: Int = -1,
        val batteryWhAbsolute: Int = -1,
        val batteryVoltageV: Float = Float.NaN,
        val batteryTempC: Int = Int.MIN_VALUE,
        val currentScaling: Int = -1,
        val supportProfileScale: Int = -1,
        val telAgeMs: Long = -1L,
        val secAgeMs: Long = -1L,
        val telRawHex: String = "",
        val secRawHex: String = ""
    )

    fun writeTel(ctx: Context, rawHex: String, data: BikeData) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tel_raw", rawHex)
            .putFloat("speed", data.speed)
            .putInt("cadence", data.cadence)
            .putInt("batt_pct", data.batteryPercent)
            .putString("assist_mode", data.assistMode)
            .putFloat("odometer_km", data.odometerKm)
            .putLong("tel_ts", System.currentTimeMillis())
            .apply()
    }

    fun writeSec(ctx: Context, secHex: String, data: BikeData) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("sec_raw", secHex)
            .putInt("motor_power", data.motorPower)
            .putInt("rider_power", data.riderPower)
            .putInt("est_range", data.estimatedRange)
            .putInt("batt_pct", data.batteryPercent)
            .putInt("motor_temp_c", data.motorTempC)
            .putInt("pedal_torque_nm", data.pedalTorqueNm)
            .putBoolean("light_on", data.lightOn)
            .putInt("trip_dist_m", data.tripDistanceM)
            .putInt("batt_wh_abs", data.batteryWhAbsolute)
            .putFloat("batt_voltage_v", data.batteryVoltageV)
            .putInt("batt_temp_c", data.batteryTempC)
            .putInt("current_scaling", data.currentScaling)
            .putInt("support_profile_scale", data.supportProfileScale)
            .putLong("sec_ts", System.currentTimeMillis())
            .apply()
    }

    fun readSnapshot(ctx: Context): Snapshot {
        val p   = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val telTs = p.getLong("tel_ts", 0L)
        val secTs = p.getLong("sec_ts", 0L)
        return Snapshot(
            speed               = p.getFloat("speed", -1f),
            cadence             = p.getInt("cadence", -1),
            riderPower          = p.getInt("rider_power", -1),
            motorPower          = p.getInt("motor_power", -1),
            batteryPercent      = p.getInt("batt_pct", -1),
            estimatedRange      = p.getInt("est_range", -1),
            odometerKm          = p.getFloat("odometer_km", -1f),
            assistMode          = p.getString("assist_mode", "---") ?: "---",
            motorTempC          = p.getInt("motor_temp_c", Int.MIN_VALUE),
            pedalTorqueNm       = p.getInt("pedal_torque_nm", -1),
            lightOn             = p.getBoolean("light_on", false),
            tripDistanceM       = p.getInt("trip_dist_m", -1),
            batteryWhAbsolute   = p.getInt("batt_wh_abs", -1),
            batteryVoltageV     = p.getFloat("batt_voltage_v", Float.NaN),
            batteryTempC        = p.getInt("batt_temp_c", Int.MIN_VALUE),
            currentScaling      = p.getInt("current_scaling", -1),
            supportProfileScale = p.getInt("support_profile_scale", -1),
            telAgeMs            = if (telTs == 0L) -1L else now - telTs,
            secAgeMs            = if (secTs == 0L) -1L else now - secTs,
            telRawHex           = p.getString("tel_raw", "") ?: "",
            secRawHex           = p.getString("sec_raw", "") ?: ""
        )
    }

    fun formatDiag(snap: Snapshot): String = buildString {
        val telAge = if (snap.telAgeMs < 0) "never" else "${snap.telAgeMs / 1000}s ago"
        val secAge = if (snap.secAgeMs < 0) "never" else "${snap.secAgeMs / 1000}s ago"
        appendLine("=== TEL ($telAge) ===")
        appendLine("raw:     ${snap.telRawHex.ifEmpty { "-" }}")
        appendLine("speed:   ${snap.speed.fmt()} km/h")
        appendLine("cadence: ${snap.cadence.fmt()} rpm")
        appendLine("batt:    ${snap.batteryPercent.fmt()} %")
        appendLine("assist:  ${snap.assistMode}")
        appendLine("odo:     ${snap.odometerKm.fmt(1)} km")
        appendLine()
        appendLine("=== SEC ($secAge) ===")
        appendLine("raw:     ${snap.secRawHex.ifEmpty { "-" }}")
        appendLine("riderPwr:  ${snap.riderPower.fmt()} W")
        appendLine("motorPwr:  ${snap.motorPower.fmt()} W")
        appendLine("range:     ${snap.estimatedRange.fmt()} km")
        appendLine("motorTemp: ${snap.motorTempC.fmtTemp()} °C")
        appendLine("torque:    ${snap.pedalTorqueNm.fmt()} Nm")
        appendLine("light:     ${snap.lightOn}")
        appendLine("trip:      ${snap.tripDistanceM.fmt()} m")
        appendLine("battWh:    ${snap.batteryWhAbsolute.fmt()} Wh")
        appendLine("battV:     ${snap.batteryVoltageV.fmt(1)} V")
        appendLine("battTemp:  ${snap.batteryTempC.fmtTemp()} °C")
        appendLine("scaling:   ${snap.currentScaling.fmt()} %")
        appendLine("profScale: ${snap.supportProfileScale.fmt()} %")
    }

    private fun Int.fmt()        = if (this == Int.MIN_VALUE || this < 0) "-" else "$this"
    private fun Int.fmtTemp()    = if (this == Int.MIN_VALUE) "-" else "$this"
    private fun Float.fmt(dec: Int = 0) = if (isNaN() || this < 0) "-" else "%.${dec}f".format(this)
}
