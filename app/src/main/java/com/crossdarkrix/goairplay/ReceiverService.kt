package com.crossdarkrix.goairplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Owns the Go receiver process: holds the Wi-Fi multicast lock (so the
 * Go side's mDNS traffic passes the Wi-Fi driver's multicast filter),
 * runs libgoairplay.so with -pcm-stdout, and pumps its PCM output to
 * an AudioTrack.
 */
class ReceiverService : Service() {

    companion object {
        const val ACTION_START = "com.crossdarkrix.goairplay.START"
        const val ACTION_STOP = "com.crossdarkrix.goairplay.STOP"
        const val EXTRA_NAME = "name"

        @Volatile
        var running: Boolean = false
            private set

        private val logLock = Any()
        private val logBuffer = ArrayList<String>()

        fun logLines(): List<String> = synchronized(logLock) { ArrayList(logBuffer) }

        fun appendLog(line: String) {
            synchronized(logLock) {
                logBuffer.add(line)
                while (logBuffer.size > 300) logBuffer.removeAt(0)
            }
        }
    }

    private var process: Process? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var engineThread: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!running) {
                    val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "GoAirPlay"
                    startReceiver(name)
                }
            }
            ACTION_STOP -> stopReceiver()
        }
        return START_STICKY
    }

    private fun startReceiver(name: String) {
        running = true
        stopping = false
        startForeground(1, buildNotification(name))

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("goairplay").apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "goairplay:receiver").apply {
            setReferenceCounted(false)
            acquire()
        }
        appendLog("multicast lock acquired")

        engineThread = Thread { runEngine(name) }.apply { start() }
    }

    private fun runEngine(name: String) {
        val bin = File(applicationInfo.nativeLibraryDir, "libgoairplay.so")
        if (!bin.exists()) {
            appendLog("receiver binary not found: ${bin.path}")
            cleanup()
            return
        }
        try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "-name", name,
                "-pcm-stdout",
                "-data-dir", filesDir.absolutePath
            )
            pb.redirectErrorStream(false)
            val proc = pb.start()
            process = proc

            Thread {
                try {
                    BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { appendLog(it) }
                } catch (_: Exception) {
                }
            }.start()

            val minBuf = AudioTrack.getMinBufferSize(
                44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            appendLog("audio track started")
            try {
                val input = proc.inputStream
                val buf = ByteArray(16384)
                while (!stopping) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        var off = 0
                        while (off < n) {
                            val w = track.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                            if (w < 0) break
                            off += w
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopping) appendLog("audio pump stopped: ${e.message}")
            }
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
            val code = proc.waitFor()
            appendLog("receiver process exited ($code)")
        } catch (e: Exception) {
            appendLog("engine error: ${e.message}")
        }
        cleanup()
    }

    private fun cleanup() {
        running = false
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopReceiver() {
        stopping = true
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        // engine thread performs cleanup when the process dies
        Thread {
            Thread.sleep(600)
            if (running) cleanup()
        }.start()
    }

    override fun onDestroy() {
        stopping = true
        try { process?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildNotification(name: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "goairplay"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "GoAirPlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("GoAirPlay")
            .setContentText("受信中: $name")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
