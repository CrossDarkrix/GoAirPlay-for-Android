package com.crossdarkrix.goairplay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var nameInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusLabel: TextView
    private lateinit var logLabel: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogCount = -1

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "GoAirPlay"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "iPhone / Mac から AirPlay で音楽を受ける受信機"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(TextView(this).apply { text = "受信機の名前" })
        nameInput = EditText(this).apply {
            setText("GoAirPlay")
            isSingleLine = true
        }
        root.addView(nameInput)

        toggleButton = Button(this).apply {
            text = "受信を開始"
            setOnClickListener { onToggle() }
        }
        root.addView(toggleButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        statusLabel = TextView(this).apply {
            text = "停止中"
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(statusLabel)

        logLabel = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
        }
        logScroll = ScrollView(this).apply { addView(logLabel) }
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        handler.post(refreshLoop)
    }

    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    private fun refresh() {
        val running = ReceiverService.running
        toggleButton.text = if (running) "受信を停止" else "受信を開始"
        statusLabel.text = if (running) "受信中…（AirPlay 一覧に出ます）" else "停止中"
        nameInput.isEnabled = !running
        val lines = ReceiverService.logLines()
        if (lines.size != lastLogCount) {
            lastLogCount = lines.size
            logLabel.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun onToggle() {
        if (ReceiverService.running) {
            startService(Intent(this, ReceiverService::class.java).apply {
                action = ReceiverService.ACTION_STOP
            })
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val name = nameInput.text.toString().trim().ifEmpty { "GoAirPlay" }
        val intent = Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_START
            putExtra(ReceiverService.EXTRA_NAME, name)
        }
        startForegroundService(intent)
        Toast.makeText(this, "受信を開始しました", Toast.LENGTH_SHORT).show()
        handler.post { refresh() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshLoop)
        super.onDestroy()
    }
}
