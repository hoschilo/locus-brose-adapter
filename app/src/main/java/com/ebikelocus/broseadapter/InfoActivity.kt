package com.ebikelocus.broseadapter

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class InfoActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvCadence: TextView
    private lateinit var tvAssist: TextView
    private lateinit var tvRiderPower: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvOdometer: TextView
    private lateinit var tvTrip: TextView
    private lateinit var tvMotorTemp: TextView
    private lateinit var tvTorque: TextView
    private lateinit var tvLight: TextView
    private lateinit var tvBattWh: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvBattTemp: TextView
    private lateinit var tvProfScale: TextView
    private lateinit var btnOff: Button
    private lateinit var btnEco: Button
    private lateinit var btnTour: Button
    private lateinit var btnSport: Button
    private lateinit var btnBoost: Button
    private lateinit var tvScalingLabel: TextView
    private lateinit var seekScaling: SeekBar
    private lateinit var layoutDiagContent: LinearLayout
    private lateinit var tvDiagToggle: TextView
    private lateinit var tvDiag: TextView

    private var diagExpanded = false
    private var isSeekBarTracking = false
    private var seekBarLocked = false
    private val seekBarUnlock = Runnable { seekBarLocked = false }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)
        title = "Brose Adapter"

        tvStatus      = findViewById(R.id.tvStatus)
        tvSpeed       = findViewById(R.id.tvSpeed)
        tvCadence     = findViewById(R.id.tvCadence)
        tvAssist      = findViewById(R.id.tvAssist)
        tvRiderPower  = findViewById(R.id.tvRiderPower)
        tvBattery     = findViewById(R.id.tvBattery)
        tvRange       = findViewById(R.id.tvRange)
        tvOdometer    = findViewById(R.id.tvOdometer)
        tvTrip        = findViewById(R.id.tvTrip)
        tvMotorTemp   = findViewById(R.id.tvMotorTemp)
        tvTorque      = findViewById(R.id.tvTorque)
        tvLight       = findViewById(R.id.tvLight)
        tvBattWh      = findViewById(R.id.tvBattWh)
        tvVoltage     = findViewById(R.id.tvVoltage)
        tvBattTemp    = findViewById(R.id.tvBattTemp)
        tvProfScale   = findViewById(R.id.tvProfScale)
        btnOff        = findViewById(R.id.btnOff)
        btnEco        = findViewById(R.id.btnEco)
        btnTour       = findViewById(R.id.btnTour)
        btnSport      = findViewById(R.id.btnSport)
        btnBoost      = findViewById(R.id.btnBoost)
        tvScalingLabel   = findViewById(R.id.tvScalingLabel)
        seekScaling      = findViewById(R.id.seekScaling)
        layoutDiagContent = findViewById(R.id.layoutDiagContent)
        tvDiagToggle     = findViewById(R.id.tvDiagToggle)
        tvDiag           = findViewById(R.id.tvDiag)

        setupAssistButtons()
        setupSeekBar()
        setupDiagToggle()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    private fun setupAssistButtons() {
        val modes = listOf(
            btnOff to 0, btnEco to 1, btnTour to 2, btnSport to 3, btnBoost to 4
        )
        modes.forEach { (btn, profile) ->
            btn.setOnClickListener {
                sendCmd(BroseProtocol.buildSupportProfileCmd(profile))
            }
        }
    }

    private fun setupSeekBar() {
        seekScaling.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) tvScalingLabel.text = "Current Scaling: $value%"
            }
            override fun onStartTrackingTouch(bar: SeekBar) { isSeekBarTracking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                isSeekBarTracking = false
                seekBarLocked = true
                handler.removeCallbacks(seekBarUnlock)
                handler.postDelayed(seekBarUnlock, 4000) // hold for 4s after command
                sendCmd(BroseProtocol.buildCurrentScalingCmd(bar.progress))
            }
        })
    }

    private fun setupDiagToggle() {
        findViewById<LinearLayout>(R.id.layoutDiagHeader).setOnClickListener {
            diagExpanded = !diagExpanded
            layoutDiagContent.visibility = if (diagExpanded) View.VISIBLE else View.GONE
            tvDiagToggle.text = if (diagExpanded) "▲" else "▼"
            if (diagExpanded) {
                val snap = DiagnosticStore.readSnapshot(this)
                tvDiag.text = DiagnosticStore.formatDiag(snap)
            }
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("brose-diag", tvDiag.text))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUi() {
        val snap = DiagnosticStore.readSnapshot(this)
        val connected = snap.telAgeMs in 0..5000

        tvStatus.text      = if (connected) "Connected" else "Not connected"
        tvStatus.setTextColor(if (connected) Color.parseColor("#2E7D32") else Color.parseColor("#9E9E9E"))

        tvSpeed.text      = snap.speed.fmtF(1, " km/h")
        tvCadence.text    = snap.cadence.fmtI(" rpm")
        tvAssist.text     = if (snap.assistMode == "---") "—" else snap.assistMode
        tvRiderPower.text = snap.riderPower.fmtI(" W")
        tvBattery.text    = snap.batteryPercent.fmtI(" %")
        tvRange.text      = snap.estimatedRange.fmtI(" km")
        tvOdometer.text   = snap.odometerKm.fmtF(1, " km")
        tvTrip.text       = if (snap.tripDistanceM >= 0) "${"%.2f".format(snap.tripDistanceM / 1000f)} km" else "— km"
        tvMotorTemp.text  = snap.motorTempC.fmtTemp(" °C")
        tvTorque.text     = snap.pedalTorqueNm.fmtI(" Nm")
        tvLight.text      = if (snap.secAgeMs in 0..10000) (if (snap.lightOn) "ON" else "OFF") else "—"
        tvBattWh.text     = snap.batteryWhAbsolute.fmtI(" Wh")
        tvVoltage.text    = snap.batteryVoltageV.fmtFloat(1, " V")
        tvBattTemp.text   = snap.batteryTempC.fmtTemp(" °C")
        tvProfScale.text  = snap.supportProfileScale.fmtI(" %")

        updateAssistButtons(snap.assistMode)

        if (!isSeekBarTracking && !seekBarLocked && snap.currentScaling in 0..100) {
            seekScaling.progress = snap.currentScaling
            tvScalingLabel.text  = "Current Scaling: ${snap.currentScaling}%"
        }

        if (diagExpanded) {
            tvDiag.text = DiagnosticStore.formatDiag(snap)
        }
    }

    private fun updateAssistButtons(mode: String) {
        val map = mapOf(
            "OFF" to btnOff, "ECO" to btnEco, "TOUR" to btnTour,
            "SPORT" to btnSport, "BOOST" to btnBoost
        )
        map.forEach { (label, btn) ->
            val active = label == mode
            btn.backgroundTintList = ColorStateList.valueOf(
                if (active) Color.parseColor("#1565C0") else Color.parseColor("#E0E0E0")
            )
            btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#424242"))
        }
    }

    private fun sendCmd(bytes: ByteArray) {
        val intent = Intent(BroseAdapterService.CMD_ACTION).setPackage(packageName)
        intent.putExtra("bytes", bytes)
        sendBroadcast(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    // Formatting helpers
    private fun Int.fmtI(suffix: String)    = if (this < 0) "—${suffix.trimStart()}" else "$this$suffix"
    private fun Int.fmtTemp(suffix: String) = if (this == Int.MIN_VALUE) "—${suffix.trimStart()}" else "$this$suffix"
    private fun Float.fmtF(dec: Int, suffix: String) = if (isNaN() || this < 0) "—${suffix.trimStart()}" else "${"%.${dec}f".format(this)}$suffix"
    private fun Float.fmtFloat(dec: Int, suffix: String) = if (isNaN()) "—${suffix.trimStart()}" else "${"%.${dec}f".format(this)}$suffix"
}
