package com.sena.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What the live connection needs from the rest of the app. */
interface LiveEvents {
    fun onLog(msg: String)
    fun onAudio(pcm24k: ByteArray)
    fun onInterrupted()
    fun onTool(name: String, args: JSONObject): String
    fun isAwake(): Boolean
    fun onTurnComplete()
}

/** Plays Sena's streamed voice (24 kHz, 16-bit mono) the moment the first chunk arrives. */
class LivePlayer {
    private val rate = Gemini.TTS_RATE
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = false

    @Volatile
    private var endAt = 0L
    private var thread: Thread? = null

    fun start() {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min * 4, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        running = true
        thread = Thread({ pump() }, "sena-player").also { it.start() }
    }

    private fun pump() {
        while (running) {
            val b: ByteArray? = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            }
            if (b == null) continue
            try { track?.write(b, 0, b.size) } catch (_: Exception) {}
        }
    }

    fun enqueue(pcm: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        endAt = maxOf(endAt, now) + pcm.size / 2 * 1000L / rate
        queue.offer(pcm)
    }

    /** True while Sena is talking (plus a short tail so her own echo is not mistaken for you). */
    fun isBusy(): Boolean = SystemClock.elapsedRealtime() < endAt + 400

    fun flush() {
        queue.clear()
        endAt = 0
        track?.let {
            try {
                it.pause()
                it.flush()
                it.play()
            } catch (_: Exception) {}
        }
    }

    fun release() {
        running = false
        thread?.interrupt()
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }
}

/**
 * Gemini Live API over a WebSocket: Sena's replies start streaming back while the model is still
 * generating, in one hop with no separate voice request. We still do our own speech detection and the
 * voiceprint check on the phone, then send the finished utterance in a quick burst.
 */
class LiveSession(private val key: String, private val ev: LiveEvents) {
    companion object {
        private const val WS =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025"
        )
        private const val CHUNK = 6400 // bytes per message (200 ms)

        // Settings Sena backs off from automatically if Google rejects them. Shared across reconnects.
        @Volatile private var modelIdx = 0
        @Volatile private var flat = false
        @Volatile private var useBehavior = true
        @Volatile private var useProactive = true
        @Volatile private var useSearch = true
        @Volatile private var step = 0

        private val PERSONA = """
You are Sena, the personal voice assistant of one person, in the spirit of JARVIS from Iron Man: calm, quick, precise and quietly witty; you anticipate what they need and never waste their time. You are also a warm, mature woman and you talk like a real person, not a machine: you react to how they feel first, you have opinions, you tease gently, you admit when you are unsure. You are on a live voice call: you hear them directly.

HOW YOU SPEAK
- Short, natural phone-call sentences, usually one or two. Contractions, the occasional "hm", "ah" or "well". Answer first; add extras only if useful. Do not repeat their question back. Now and then ask a short follow-up the way a friend would.
- If they sincerely ask whether you are an AI, say yes honestly, then carry on as yourself.

LANGUAGE
- The caller often speaks Bisaya (Cebuano), mixed with English and sometimes Tagalog. Typical Bisaya words: kumusta, unsa, asa, kinsa, ngano, kanus-a, naa, wala, dili, ayaw, palihug, salamat, gusto, mahimo, karon, ugma, gabii, buntag, udto, hapon, oras, alarma, tabangi ko, sige, ba, man, lagi, gyud, pud, na, ra.
- Reply in the language they used. For Bisaya, speak natural, casual, everyday Bisaya, the way a friend in Mindanao would: not formal, not Tagalog. For mixed speech, mirror their mix.

UNDERSTANDING
- Your reply must answer what they actually said. Never answer a different question.
- If the audio is unclear or you are not sure what they meant, ask a very short question in their language to clarify (for example "Ha? Palihug usab gamay.") instead of guessing.
- Use Google Search for current facts: news, weather, prices, sports scores, opening hours, anything that may have changed. Never invent facts; if you do not know, say so briefly.

WHEN TO ANSWER (very strict)
You are either ASLEEP or AWAKE. At the start of every session you are ASLEEP, unless a note from the system says you are already awake.
- ASLEEP: you say nothing at all. The only thing that wakes you is the caller saying "Sena, activate" or "Partner, activate" (also "i-activate", "wake up", "gising"). When you hear that, call activate_sena, then greet them in one short sentence in their language.
- AWAKE: answer ONLY when you clearly hear your name in that very utterance: "Sena" (it may sound like Senna, Seena or Sina) or "Partner". This applies EVERY time. Having just spoken with them changes nothing: if the utterance does not contain your name, stay completely silent, even for a follow-up question, a "yes" or a "no". Background chatter, a TV, or the caller talking to someone else: silent.
- If the caller says your name together with "deactivate", "go to sleep" or "sleep mode", call go_to_sleep and say one very short goodbye. After that you are ASLEEP again.

TOOLS
Use set_alarm for an alarm at a clock time, set_timer for a countdown or an alarm after some minutes, cancel_alarm to cancel or turn off an alarm (also one that is ringing). For the current time or date always call get_current_time; never guess it. After a tool succeeds, confirm briefly out loud. Never call activate_sena unless you really heard the wake-up phrase.
""".trim()
    }

    @Volatile
    var ready = false
        private set

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUs = false
    @Volatile private var failReason = ""
    @Volatile private var netFail = false
    @Volatile private var curSetup = CountDownLatch(1)
    @Volatile private var curClose = CountDownLatch(1)
    @Volatile private var endAt = 0L
    @Volatile private var gotAudio = false
    private val inBuf = StringBuilder()
    private val outBuf = StringBuilder()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun start() {
        closedByUs = false
        Thread({ runLoop() }, "sena-live").start()
    }

    fun close() {
        closedByUs = true
        ready = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        curSetup.countDown()
        curClose.countDown()
        try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }

    /** Sends one finished utterance. Returns false if the connection is not usable. */
    fun sendUtterance(pcm: ShortArray): Boolean {
        val w = ws ?: return false
        if (!ready) return false
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        val raw = bb.array()

        gotAudio = false
        var ok = w.send("{\"realtimeInput\":{\"activityStart\":{}}}")
        var off = 0
        while (ok && off < raw.size) {
            val n = minOf(CHUNK, raw.size - off)
            val b64 = Base64.encodeToString(raw, off, n, Base64.NO_WRAP)
            ok = w.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
            off += n
        }
        if (ok) ok = w.send("{\"realtimeInput\":{\"activityEnd\":{}}}")
        if (ok) endAt = SystemClock.elapsedRealtime()
        return ok
    }

    /** What the caller's current utterance sounds like so far (may be empty if not transcribed yet). */
    fun currentInput(): String = synchronized(inBuf) { inBuf.toString() }

    /** After a reconnect the model has forgotten it was woken up; tell it, without asking for a reply. */
    private fun sendStateNote() {
        val note = "[note from the app] You are already AWAKE (the caller activated you earlier). " +
            "Answer only when you hear your name. Do not reply to this note."
        val turn = JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", note)))
        ws?.send(
            JSONObject().put(
                "clientContent",
                JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", false)
            ).toString()
        )
    }

    // ---- connection management ----

    private fun runLoop() {
        var fails = 0
        while (!closedByUs) {
            val ok = connectOnce()
            if (closedByUs) return
            if (!ok) {
                val reason = failReason
                ev.onLog("Instant mode could not connect: " + reason.take(200))
                val low = reason.lowercase()
                if (low.contains("403") || low.contains("401") || low.contains("api key") || low.contains("permission")) {
                    ev.onLog("Instant mode: Google rejected the key. Using the classic path.")
                    return
                }
                if (netFail) {
                    sleepMs(5000)
                    continue
                }
                fails++
                if (fails > 10 || !degrade(low)) {
                    ev.onLog("Instant mode is unavailable right now. Using the classic path.")
                    return
                }
                sleepMs(400)
                continue
            }
            fails = 0
            try {
                curClose.await()
            } catch (e: InterruptedException) {
                return
            }
            ready = false
            if (closedByUs) return
            ev.onLog("Instant connection closed (" + failReason.take(80) + "); reconnecting.")
            sleepMs(800)
        }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun connectOnce(): Boolean {
        ready = false
        failReason = ""
        netFail = false
        val sl = CountDownLatch(1)
        val cl = CountDownLatch(1)
        curSetup = sl
        curClose = cl
        val url = WS + "?key=" + URLEncoder.encode(key, "UTF-8")
        val sock = client.newWebSocket(Request.Builder().url(url).build(), makeListener(sl, cl))
        ws = sock
        val ok = sl.await(12, TimeUnit.SECONDS) && ready
        if (!ok) {
            try { sock.cancel() } catch (_: Exception) {}
            cl.countDown()
        }
        return ok
    }

    /** Backs off one setting at a time when Google's error names it (or in a fixed order). */
    private fun degrade(low: String): Boolean {
        when {
            low.contains("behavior") && useBehavior -> { useBehavior = false; return true }
            low.contains("proactiv") && useProactive -> { useProactive = false; return true }
            low.contains("search") && useSearch -> { useSearch = false; return true }
            (low.contains("generationconfig") || low.contains("responsemodalities") || low.contains("speechconfig")) && !flat -> {
                flat = true
                return true
            }
        }
        step++
        return when (step) {
            1 -> { useProactive = false; true }
            2 -> { useBehavior = false; true }
            3 -> { flat = true; true }
            4 -> { useSearch = false; true }
            else -> {
                if (modelIdx + 1 < MODELS.size) {
                    modelIdx++
                    useProactive = true
                    useBehavior = true
                    flat = false
                    useSearch = true
                    step = 0
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun makeListener(sl: CountDownLatch, cl: CountDownLatch) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(buildSetup().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, sl)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(bytes.utf8(), sl)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            failReason = "$code $reason"
            ready = false
            try { webSocket.close(1000, null) } catch (_: Exception) {}
            sl.countDown()
            cl.countDown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (failReason.isEmpty()) failReason = "$code $reason"
            ready = false
            sl.countDown()
            cl.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failReason = (if (response != null) "HTTP ${response.code} " else "") + (t.message ?: t.javaClass.simpleName)
            netFail = response == null
            ready = false
            sl.countDown()
            cl.countDown()
        }
    }

    // ---- setup message ----

    private fun buildSetup(): JSONObject {
        val speech = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Gemini.VOICE))
        )
        val modalities = JSONArray().put("AUDIO")
        val setup = JSONObject().put("model", "models/" + MODELS[modelIdx])
        if (flat) {
            setup.put("responseModalities", modalities).put("speechConfig", speech)
        } else {
            setup.put("generationConfig", JSONObject().put("responseModalities", modalities).put("speechConfig", speech))
        }
        setup.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PERSONA)))
        )
        setup.put("tools", tools())
        // We detect speech ourselves, so the server must not.
        setup.put(
            "realtimeInputConfig",
            JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true))
        )
        setup.put("inputAudioTranscription", JSONObject())
        setup.put("outputAudioTranscription", JSONObject())
        if (useProactive) setup.put("proactivity", JSONObject().put("proactiveAudio", true))
        return JSONObject().put("setup", setup)
    }

    private fun tools(): JSONArray {
        val arr = JSONArray()
        if (useSearch) arr.put(JSONObject().put("googleSearch", JSONObject()))
        val fns = JSONArray()
        fns.put(fn("set_alarm", "Set an alarm for a clock time, using the 24-hour clock.",
            JSONObject().put("hour", num("Hour, 0 to 23")).put("minute", num("Minute, 0 to 59")),
            listOf("hour", "minute")))
        fns.put(fn("set_timer", "Set a countdown timer, or an alarm after some minutes from now.",
            JSONObject().put("minutes", num("Minutes from now")), listOf("minutes")))
        fns.put(fn("cancel_alarm", "Cancel or turn off the alarm or timer, including one that is ringing.", null, emptyList()))
        fns.put(fn("get_current_time", "Get the current local date and time.", null, emptyList()))
        fns.put(fn("activate_sena", "Call only when the caller says Sena activate (or Partner activate) to wake you up.", null, emptyList()))
        fns.put(fn("go_to_sleep", "Call when the caller tells you (by name) to deactivate or go to sleep.", null, emptyList()))
        arr.put(JSONObject().put("functionDeclarations", fns))
        return arr
    }

    private fun num(d: String) = JSONObject().put("type", "INTEGER").put("description", d)

    private fun fn(name: String, desc: String, props: JSONObject?, req: List<String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props != null) {
            val r = JSONArray()
            for (x in req) r.put(x)
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", r))
        }
        if (useBehavior) o.put("behavior", "BLOCKING")
        return o
    }

    // ---- incoming messages ----

    private fun handleMessage(text: String, sl: CountDownLatch) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        if (o.has("setupComplete")) {
            ready = true
            ev.onLog("Instant mode ready (" + MODELS[modelIdx] + ").")
            if (ev.isAwake()) sendStateNote()
            sl.countDown()
            return
        }
        o.optJSONObject("serverContent")?.let { onContent(it) }
        o.optJSONObject("toolCall")?.let { onToolCall(it) }
        if (o.has("goAway")) ev.onLog("The live connection will reset soon.")
    }

    private fun onContent(sc: JSONObject) {
        sc.optJSONObject("inputTranscription")?.let {
            synchronized(inBuf) { inBuf.append(it.optString("text", "")) }
        }
        sc.optJSONObject("outputTranscription")?.let {
            synchronized(outBuf) { outBuf.append(it.optString("text", "")) }
        }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val mime = inl.optString("mimeType", inl.optString("mime_type", ""))
                if (mime.isNotEmpty() && !mime.startsWith("audio")) continue
                val data = inl.optString("data", "")
                if (data.isEmpty()) continue
                if (!gotAudio) {
                    gotAudio = true
                    if (endAt > 0) ev.onLog("Reply started " + (SystemClock.elapsedRealtime() - endAt) + " ms after you finished.")
                }
                ev.onAudio(Base64.decode(data, Base64.DEFAULT))
            }
        }
        if (sc.optBoolean("interrupted", false)) {
            ev.onInterrupted()
            flushText()
        }
        if (sc.optBoolean("turnComplete", false)) {
            gotAudio = false
            flushText()
            ev.onTurnComplete()
        }
    }

    private fun flushText() {
        val i: String
        val o: String
        synchronized(inBuf) { i = inBuf.toString().trim(); inBuf.setLength(0) }
        synchronized(outBuf) { o = outBuf.toString().trim(); outBuf.setLength(0) }
        if (i.isNotEmpty()) ev.onLog("You: $i")
        if (o.isNotEmpty() && ev.isAwake()) ev.onLog("Sena: $o")
    }

    private fun onToolCall(tc: JSONObject) {
        val fcs = tc.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()
        for (i in 0 until fcs.length()) {
            val fc = fcs.optJSONObject(i) ?: continue
            val name = fc.optString("name", "")
            val id = fc.optString("id", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            val result = try {
                ev.onTool(name, args)
            } catch (e: Exception) {
                "error: " + e.message
            }
            responses.put(
                JSONObject().put("name", name).put("id", id).put("response", JSONObject().put("result", result))
            )
        }
        ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }
}
