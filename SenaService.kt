package com.sena.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        private const val ACTIVE_WINDOW_MS = 45_000L

        @Volatile
        var running = false

        /** Asleep until the caller says "Sena, activate". Every answer then needs her name. */
        @Volatile
        var awake = true

        private val NAME_RE = Regex("\\b(sena|senna|seena|sina|sinna|partner|pardner)\\b")
        private val WAKE_RE = Regex("(?<!de)activat|aktibat|aktibar|wake up|gising")
        private val SLEEP_RE = Regex("deactivat|go to sleep|sleep mode|stand ?by|matulog na")
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
    private val pool = Executors.newFixedThreadPool(3)
    private var live: LiveSession? = null

    @Volatile
    private var pendingSleep = false
    private var player: LivePlayer? = null
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
        awake = !Store.startAsleep(this)
        SenaLog.add(if (awake) "Awake. Say \"Sena\" or \"Partner\" before each question." else "Asleep. Say \"Sena, activate\" to wake me.")

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

        if (Store.liveMode(this)) startLive()
        worker = Thread({ loop() }, "sena-loop").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        speakLatch?.countDown()
        pool.shutdownNow()
        try { live?.close() } catch (_: Exception) {}
        live = null
        try { player?.release() } catch (_: Exception) {}
        player = null
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
                if (live?.ready != true) cap.flush()
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

        val lv = live
        val liveReady = lv != null && lv.ready
        // She is still talking: do not treat her own voice (or an interruption) as a new question.
        if (liveReady && player?.isBusy() == true) return

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

        if (liveReady && lv != null) {
            if (lv.sendUtterance(pcm)) return
            SenaLog.add("Instant mode could not send; using the classic path.")
        }

        val active = System.currentTimeMillis() - lastReplyAt < ACTIVE_WINDOW_MS
        val res = try {
            Gemini.ask(key, pcm, buildContext(active), Store.smartBrain(this), Store.webSearch(this))
        } catch (e: Gemini.GeminiException) {
            SenaLog.add("Gemini error: ${e.message}")
            return
        } catch (e: Exception) {
            SenaLog.add("Network problem: ${e.message}")
            return
        }

        val said = res.heard.lowercase()
        val named = NAME_RE.containsMatchIn(said)
        if (!awake) {
            if (named && WAKE_RE.containsMatchIn(said)) {
                awake = true
                SenaLog.add("You: ${res.heard}")
                SenaLog.add("Awake.")
                speak(
                    if (res.lang.startsWith("ceb")) "Gising na ko. Unsa'y akong matabang?" else "I'm awake. What do you need?",
                    res.lang
                )
            } else {
                SenaLog.add("(asleep) ${res.heard}")
            }
            return
        }
        if (!named) {
            SenaLog.add("(no name, ignored) ${res.heard}")
            return
        }
        if (SLEEP_RE.containsMatchIn(said)) {
            SenaLog.add("You: ${res.heard}")
            speak(if (res.lang.startsWith("ceb")) "Sige, matulog na ko." else "Going to sleep.", res.lang)
            awake = false
            SenaLog.add("Asleep. Say \"Sena, activate\" to wake me.")
            return
        }
        if (!res.addressed && !named) {
            SenaLog.add("(not for me) ${res.heard}")
            return
        }
        SenaLog.add("You: ${res.heard}" + if (res.confident) "" else "  (not sure I heard that right)")
        applyIntent(res)
        val reply = res.reply.trim()
        if (reply.isEmpty()) return
        SenaLog.add("Sena: ${plain(reply)}")

        history.add(res.heard to plain(reply))
        while (history.size > 10) history.removeAt(0)
        speak(reply, res.lang)
        lastReplyAt = System.currentTimeMillis()
    }

    private fun startLive() {
        val key = Store.apiKey(this)
        if (key.isBlank()) return
        val p = LivePlayer()
        p.start()
        player = p
        live = LiveSession(key, object : LiveEvents {
            override fun onLog(msg: String) { SenaLog.add(msg) }
            override fun onAudio(pcm24k: ByteArray) { if (awake) p.enqueue(pcm24k) }
            override fun isAwake(): Boolean = awake
            override fun onTurnComplete() {
                if (pendingSleep) {
                    pendingSleep = false
                    awake = false
                    SenaLog.add("Asleep. Say \"Sena, activate\" to wake me.")
                }
            }
            override fun onInterrupted() { p.flush() }
            override fun onTool(name: String, args: JSONObject): String = runTool(name, args)
        }).also { it.start() }
    }

    private fun runTool(name: String, args: JSONObject): String {
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        return when (name) {
            "get_current_time" ->
                SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(Date()) +
                    " (" + TimeZone.getDefault().id + ")"
            "set_alarm" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, args.optInt("hour", 0).coerceIn(0, 23))
                cal.set(Calendar.MINUTE, args.optInt("minute", 0).coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
                AlarmReceiver.schedule(this, cal.timeInMillis)
                val s = "Alarm set for " + fmt.format(Date(cal.timeInMillis))
                SenaLog.add(s)
                s
            }
            "set_timer" -> {
                val m = args.optInt("minutes", 0)
                if (m <= 0) {
                    "error: minutes must be positive"
                } else {
                    val at = System.currentTimeMillis() + m * 60_000L
                    AlarmReceiver.schedule(this, at)
                    val s = "Timer set for " + fmt.format(Date(at))
                    SenaLog.add(s)
                    s
                }
            }
            "activate_sena" -> {
                val said = (live?.currentInput() ?: "").lowercase()
                if (said.isNotBlank() && !(NAME_RE.containsMatchIn(said) && WAKE_RE.containsMatchIn(said))) {
                    SenaLog.add("(ignored a wake-up I could not confirm: $said)")
                    "denied: the caller did not say your name with activate. Stay silent."
                } else {
                    awake = true
                    SenaLog.add("Awake.")
                    "Awake. Greet the caller in one short sentence."
                }
            }
            "go_to_sleep" -> {
                pendingSleep = true
                "OK. Say one very short goodbye; after that you are asleep."
            }
            "cancel_alarm" -> {
                AlarmReceiver.cancel(this)
                SenaLog.add("Alarm off.")
                "Alarm cancelled."
            }
            else -> "error: unknown tool $name"
        }
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
        if (now - lastReplyAt > 600_000L) history.clear()
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

    /** Removes voice tags such as <short pause> so they are never read aloud or shown. */
    private fun plain(s: String): String =
        s.replace(Regex("<[^>]{1,20}>"), "").replace(Regex("\\s+"), " ").trim()

    private fun chunks(text: String): List<String> {
        val s = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return if (s.size <= 3) s else listOf(s[0], s[1], s.drop(2).joinToString(" "))
    }

    /** Natural voice from Gemini; falls back to the phone's own voice if anything goes wrong. */
    private fun speak(text: String, lang: String) {
        val key = Store.apiKey(this)
        val parts = chunks(text)
        // Fetch all sentences in parallel, play them in order: the first one starts sooner.
        val futures = parts.map { part ->
            pool.submit(Callable<ByteArray?> {
                try {
                    Gemini.speech(key, part)
                } catch (e: Exception) {
                    SenaLog.add("Voice error: ${e.message}")
                    null
                }
            })
        }
        for ((i, f) in futures.withIndex()) {
            val pcm = try { f.get(40, TimeUnit.SECONDS) } catch (e: Exception) { null }
            if (stopping) return
            if (pcm == null || pcm.size < 2) {
                speakPhone(plain(parts.drop(i).joinToString(" ")), lang)
                break
            }
            play(pcm)
        }
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
    }

    private fun play(pcm: ByteArray) {
        val frames = pcm.size / 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Gemini.TTS_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val end = SystemClock.elapsedRealtime() + frames * 1000L / Gemini.TTS_RATE + 1500L
            while (!stopping && track.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < end) {
                Thread.sleep(40)
            }
        } finally {
            track.release()
        }
    }

    private fun speakPhone(text: String, lang: String) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val tag = lang.ifBlank { Store.lang(this) }
        val r = t.setLanguage(Locale.forLanguageTag(tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            SenaLog.add("No phone voice installed for $tag, using the default.")
            t.setLanguage(Locale.getDefault())
        }
        val latch = CountDownLatch(1)
        speakLatch = latch
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sena")
        latch.await(30, TimeUnit.SECONDS)
    }
}
