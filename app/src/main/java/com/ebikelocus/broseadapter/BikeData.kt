package com.ebikelocus.broseadapter

data class BikeData(
    val speed: Float = 0f,
    val cadence: Int = 0,
    val motorPower: Int = 0,
    val riderPower: Int = 0,
    val batteryPercent: Int = 0,
    val estimatedRange: Int = 0,   // km, from SEC protobuf
    val odometerKm: Float = 0f,    // from TEL frame bytes 8-9
    val assistMode: String = "---"
)
