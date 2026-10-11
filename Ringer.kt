package com.sena.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

object Ringer {
    private const val CH = "sena_alarm"
    private const val NID = 2
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var ringing = false
        private set

    fun start(c: Context) {
        val app = c.applicationContext
        stop(app)
        ringing = true

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            SenaLog.add("Alarm sound failed: ${e.message}")
        }

        try {
            @Suppress("DEPRECATION")
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            vibrator = v
        } catch (_: Exception) {}

        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Sena alarm", NotificationManager.IMPORTANCE_HIGH)
        )
        val stopPi = PendingIntent.getBroadcast(
            app, 3,
            Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            app, 4, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(app, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Sena alarm")
            .setContentText("Tap Stop to silence it")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, android.R.drawable.ic_media_pause), "Stop", stopPi
                ).build()
            )
            .build()
        try { nm.notify(NID, n) } catch (_: Exception) {}

        handler.postDelayed({ stop(app) }, 3 * 60 * 1000L)
    }

    fun stop(c: Context) {
        handler.removeCallbacksAndMessages(null)
        ringing = false
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        try {
            c.applicationContext.getSystemService(NotificationManager::class.java).cancel(NID)
        } catch (_: Exception) {}
    }
}
