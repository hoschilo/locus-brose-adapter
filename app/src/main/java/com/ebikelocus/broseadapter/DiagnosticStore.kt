package com.ebikelocus.broseadapter

import android.content.Context

object DiagnosticStore {

    private const val PREFS = "brose_diag"

    fun writeTel(ctx: Context, rawHex: String, speed: Float, cadence: Int, batt: Int, assist: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tel_raw", rawHex)
            .putFloat("tel_speed", speed)
            .putInt("tel_cadence", cadence)
            .putInt("tel_batt", batt)
            .putString("tel_assist", assist)
            .putLong("tel_ts", System.currentTimeMillis())
            .apply()
    }

    fun writeSec(ctx: Context, combinedHex: String, power: Int, range: Int, batt: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("sec_combined", combinedHex)
            .putInt("sec_power", power)
            .putInt("sec_range", range)
            .putInt("sec_batt", batt)
            .putLong("sec_ts", System.currentTimeMillis())
            .apply()
    }

    fun writeSource(ctx: Context, source: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_source", source)
            .apply()
    }

    fun read(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val telTs  = p.getLong("tel_ts", 0)
        val secTs  = p.getLong("sec_ts", 0)
        val now    = System.currentTimeMillis()

        return buildString {
            appendLine("=== TEL (${age(now, telTs)}) ===")
            appendLine("raw:    ${p.getString("tel_raw", "-")}")
            appendLine("speed:  ${p.getFloat("tel_speed", -1f)} km/h")
            appendLine("cad:    ${p.getInt("tel_cadence", -1)} rpm")
            appendLine("batt:   ${p.getInt("tel_batt", -1)} %")
            appendLine("assist: ${p.getString("tel_assist", "-")}")
            appendLine()
            appendLine("=== SEC (${age(now, secTs)}) ===")
            appendLine("combined: ${p.getString("sec_combined", "-")}")
            appendLine("power:  ${p.getInt("sec_power", -1)} W")
            appendLine("range:  ${p.getInt("sec_range", -1)} km")
            appendLine("batt:   ${p.getInt("sec_batt", -1)} %")
            appendLine()
            appendLine("last source: ${p.getString("last_source", "-")}")
        }
    }

    private fun age(now: Long, ts: Long) =
        if (ts == 0L) "never" else "${(now - ts) / 1000}s ago"
}
