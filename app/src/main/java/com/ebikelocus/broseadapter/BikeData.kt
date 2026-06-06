package com.ebikelocus.broseadapter

data class BikeData(
    val speed: Float = 0f,
    val cadence: Int = 0,
    val motorPower: Int = -1,
    val riderPower: Int = -1,
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
    val supportProfileScale: Int = -1
)
