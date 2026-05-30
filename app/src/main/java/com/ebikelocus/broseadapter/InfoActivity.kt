package com.ebikelocus.broseadapter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class InfoActivity : Activity() {

    private lateinit var tv: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            tv.text = DiagnosticStore.read(this@InfoActivity)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 16, 32, 32)
        }

        val copyBtn = Button(this).apply {
            text = "Copy to clipboard"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("brose-diag", tv.text))
                Toast.makeText(this@InfoActivity, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(copyBtn)
            addView(ScrollView(this@InfoActivity).apply { addView(tv) })
        }

        setContentView(layout)
        title = "Brose Adapter — Diagnose"
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }
}
