package com.sena.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foreground service. Loop: capture an utterance -> (optional) voiceprint check on the phone ->
 * send only matching audio to Gemini -> act on the intent -> speak the reply.
 */
class SenaService : Service() {
    companion object {
        const val ACTION_STOP = "com.sena.app.STOP_SERVICE"
        private const val CH = "sena_listen"
        private const val NOTIF = 1
        private const val ACTIVE_WINDOW_MS = 20_000L

        @Volatile
        var running = false
    }

    private var worker: Thread? = null

    @Volatile
    private var stopping = false
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var speakLatch: CountDownLatch? = null
    private val history = ArrayList<Pair<String, String>>()
    private var lastReplyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Sena listening", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF, n)
        }
        running = true
        stopping = false

        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) SenaLog.add("Text-to-speech engine failed to start.")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { speakLatch?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speakLatch?.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { speakLatch?.countDown() }
        })

        worker = Thread({ loop() }, "sena-loop").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        speakLatch?.countDown()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        SenaLog.add("Stopped.")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 6, Intent(this, SenaService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sena")
            .setContentText("Listening")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop
                ).build()
            )
            .build()
    }

    private fun loop() {
        val cap = UtteranceCapture()
        if (!cap.start()) {
            SenaLog.add("Could not open the microphone (is another app using it?).")
            stopSelf()
            return
        }
        SenaLog.add("Listening. Say \"Sena\".")
        try {
            while (!stopping) {
                val pcm = cap.next(60_000L) { stopping }
                if (stopping) break
                if (pcm == null) {
                    if (cap.failed) {
                        SenaLog.add("Microphone stopped responding.")
                        stopSelf()
                        break
                    }
                    continue
                }
                try {
                    handle(pcm)
                } catch (e: Exception) {
                    SenaLog.add("Error: ${e.message}")
                }
                cap.flush()
            }
        } finally {
            cap.stop()
        }
    }

    private fun handle(pcm: ShortArray) {
        val key = Store.apiKey(this)
        if (key.isBlank()) {
            SenaLog.add("No Gemini key set.")
            return
        }

        val lock = Store.voiceLock(this)
        val profile = if (lock) Store.profile(this) else null
        // An alarm ringing is loud; only react to it if the voice lock can tell it apart from you.
        if (Ringer.ringing && profile == null) return

        if (lock && profile == null) {
            SenaLog.add("Voice lock is on but there is no voiceprint yet: listening to everyone.")
        }
        if (profile != null) {
            val f = VoicePrint.features(pcm)
            if (f == null) {
                SenaLog.add("Voice check: clip too short or unvoiced, ignored.")
                return
            }
            val d = profile.distance(f)
            val limit = Store.threshold(this)
            val ok = d <= limit
            SenaLog.add(String.format(Locale.US, "Voice check %.2f (limit %.2f): %s", d, limit, if (ok) "you" else "not you"))
            if (!ok) return
        }

        val active = System.currentTimeMillis() - lastReplyAt < ACTIVE_WINDOW_MS
        val res = try {
            Gemini.ask(key, pcm, buildContext(active))
        } catch (e: Gemini.GeminiException) {
            SenaLog.add("Gemini error: ${e.message}")
            return
        } catch (e: Exception) {
            SenaLog.add("Network problem: ${e.message}")
            return
        }

        if (!res.addressed) {
            SenaLog.add("(not for me) ${res.heard}")
            return
        }
        SenaLog.add("You: ${res.heard}")
        applyIntent(res)
        val reply = res.reply.trim()
        if (reply.isEmpty()) return
        SenaLog.add("Sena: $reply")

        history.add(res.heard to reply)
        while (history.size > 6) history.removeAt(0)
        speak(reply, res.lang)
        lastReplyAt = System.currentTimeMillis()
    }

    private fun applyIntent(res: Gemini.Result) {
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        when (res.intent) {
            "alarm_at" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, res.hour.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, res.minute.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
                AlarmReceiver.schedule(this, cal.timeInMillis)
                SenaLog.add("Alarm set for " + fmt.format(Date(cal.timeInMillis)))
            }
            "alarm_in" -> if (res.minutes > 0) {
                val at = System.currentTimeMillis() + res.minutes * 60_000L
                AlarmReceiver.schedule(this, at)
                SenaLog.add("Alarm set for " + fmt.format(Date(at)))
            }
            "alarm_off" -> {
                AlarmReceiver.cancel(this)
                SenaLog.add("Alarm off.")
            }
        }
    }

    private fun buildContext(active: Boolean): String {
        val now = System.currentTimeMillis()
        if (now - lastReplyAt > 300_000L) history.clear()
        val sb = StringBuilder()
        sb.append("Current local time: ")
            .append(SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(Date(now)))
            .append(" (").append(TimeZone.getDefault().id).append(").\n")
        sb.append("Caller's default language: ").append(Store.lang(this)).append(".\n")
        sb.append("conversation_active: ").append(active).append("\n")
        if (history.isNotEmpty()) {
            sb.append("Recent exchange:\n")
            for ((h, r) in history) sb.append("Caller: ").append(h).append("\nSena: ").append(r).append("\n")
        }
        sb.append("The caller's latest audio clip follows.")
        return sb.toString()
    }

    private fun speak(text: String, lang: String) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val tag = lang.ifBlank { Store.lang(this) }
        val r = t.setLanguage(Locale.forLanguageTag(tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            SenaLog.add("No voice installed for $tag, using the default.")
            t.setLanguage(Locale.getDefault())
        }
        val latch = CountDownLatch(1)
        speakLatch = latch
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sena")
        latch.await(30, TimeUnit.SECONDS)
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
    }
}
