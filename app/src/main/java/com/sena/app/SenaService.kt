package com.sena.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.speech.*
import android.speech.tts.*
import org.json.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object Ringer {
    private var r: Ringtone? = null
    val playing get() = r?.isPlaying == true
    fun start(c: Context) {
        stop()
        r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        r?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        r?.play()
        Handler(Looper.getMainLooper()).postDelayed({ stop() }, 60000)
    }
    fun stop() { r?.stop(); r = null }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Ringer.start(c) }
}

class SenaService : Service() {
    private lateinit var tts: TextToSpeech
    private var sr: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val hist = ArrayList<String>()
    private val wake = Regex("\\b(sena|senna|xena|cena|seña)\\b", RegexOption.IGNORE_CASE)
    private val prefs by lazy { getSharedPreferences("sena", MODE_PRIVATE) }
    private var running = false
    private var speaking = false
    private var ttsOk = false
    private var activeUntil = 0L

    private val persona = "You are Sena, a young woman on a voice call with someone who trusts you the way Tony Stark trusts JARVIS: " +
        "composed, quick, quietly witty, with a dry sense of humor, always one step ahead. But you think like a human: real opinions and moods, " +
        "you notice how they feel, think out loud when something is tricky, disagree politely, tease a little when it fits. " +
        "Never sound like a customer service bot. If they sincerely ask whether you are an AI, say yes honestly, then carry on as yourself. " +
        "Reply in the language the caller last spoke. If they speak Bisaya (Cebuano), the transcript comes from a Filipino recognizer and may be garbled: " +
        "infer their meaning and reply in natural Bisaya with lang \"ceb-PH\". " +
        "Your words are spoken aloud: no lists, emoji or markdown, usually one to three sentences. " +
        "Return ONLY JSON: {\"reply\":\"...\",\"lang\":\"BCP-47 tag of the reply, e.g. en-US\"}"

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsOk = true
                tts.setPitch(1.1f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { doneSpeaking() } }
                    override fun onError(id: String?) { main.post { doneSpeaking() } }
                })
                if (running) say("Hello, I am Sena. Say my name when you need me.")
            }
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "sena"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "Sena", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("Sena is listening")
            .setContentText("Say \"Sena\" to talk").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        running = true
        again(500)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        sr?.destroy()
        tts.stop(); tts.shutdown()
        super.onDestroy()
    }

    private fun again(d: Long = 300) { main.postDelayed({ listen() }, d) }

    private fun listen() {
        if (!running || speaking || !SpeechRecognizer.isRecognitionAvailable(this)) return
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) again() else handle(t)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
                again(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 300)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("lang", "en-US")))
    }

    private fun handle(raw: String) {
        val named = wake.containsMatchIn(raw)
        if (!prefs.getBoolean("always", false) && System.currentTimeMillis() > activeUntil && !named) { again(); return }
        val text = raw.replace(wake, "").replace(Regex("^\\s*(hey|hi|ok|okay)\\b[ ,]*", RegexOption.IGNORE_CASE), "").trim()
        if (text.isEmpty()) { say("Yes?"); return }
        local(text)?.let { say(it); return }
        hist.add("Caller: $text")
        val snap = hist.takeLast(30).toList()
        io.execute {
            val (reply, lang) = try { ask(snap) } catch (e: Exception) {
                Pair("Sorry, I could not reach my brain. Check the internet and your key.", "en-US")
            }
            main.post { hist.add("Sena: $reply"); say(reply, lang) }
        }
    }

    private fun say(t: String, lang: String = prefs.getString("lang", "en-US") ?: "en-US") {
        speaking = true
        sr?.destroy(); sr = null
        val ok = tts.setLanguage(Locale.forLanguageTag(lang))
        if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED)
            tts.setLanguage(Locale.forLanguageTag(prefs.getString("lang", "en-US") ?: "en-US"))
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun doneSpeaking() { speaking = false; activeUntil = System.currentTimeMillis() + 20000; again(200) }

    private fun ask(snap: List<String>): Pair<String, String> {
        val key = prefs.getString("key", "") ?: ""
        val model = prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite"
        if (key.isBlank()) return Pair("Please add your Gemini key in the app first.", "en-US")
        val prompt = persona + "\nCurrent date and time: " + Date() + "\n\nConversation so far:\n" + snap.joinToString("\n")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 1))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode == 429) return Pair("I have hit the free limit for now. Give me a minute.", "en-US")
        if (c.responseCode !in 200..299) return Pair("Google rejected the request. Check the key and model name.", "en-US")
        val txt = JSONObject(c.inputStream.bufferedReader().readText())
            .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val j = try { JSONObject(txt.replace("```json", "").replace("```", "").trim()) } catch (e: Exception) { JSONObject().put("reply", txt) }
        return Pair(j.optString("reply", txt), j.optString("lang", "en-US"))
    }

    private fun local(t: String): String? {
        val s = t.lowercase()
        if (Ringer.playing && Regex("\\b(stop|off|enough|okay|ok)\\b").containsMatchIn(s)) { Ringer.stop(); return "Alarm off." }
        val du = Regex("(\\d+)\\s*(second|sec|minute|min|hour|hr)").find(s)
        if (du != null && Regex("timer|alarm|remind|countdown|after|in \\d").containsMatchIn(s)) {
            val u = du.groupValues[2]
            val ms = du.groupValues[1].toLong() * (if (u.startsWith("h")) 3600000L else if (u.startsWith("m")) 60000L else 1000L)
            setAlarm(System.currentTimeMillis() + ms)
            return "Timer set for ${du.value}."
        }
        if (Regex("alarm|wake me").containsMatchIn(s)) {
            val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(s)
            if (m != null && m.groupValues[1].toInt() <= 24) {
                var h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = m.groupValues[3].replace(".", "")
                if (ap.isNotEmpty()) { h %= 12; if (ap == "pm") h += 12 }
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
                setAlarm(cal.timeInMillis)
                return "Alarm set for " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time) + "."
            }
        }
        if (Regex("what('s| is)? the time|what time").containsMatchIn(s)) return "It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        return null
    }

    private fun setAlarm(at: Long) {
        val am = getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(this, (at % 100000).toInt(), Intent(this, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val show = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
    }
}
