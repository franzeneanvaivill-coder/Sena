package com.sena.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Two jobs:
 *  ask()    - sends one audio clip; Gemini transcribes it, decides if it was meant for Sena, thinks, and replies.
 *  speech() - turns Sena's reply into a natural voice (Gemini TTS).
 */
object Gemini {
    private const val RATE = 16000

    // ---- Sena's speaking voice -------------------------------------------------------------
    // To change her voice, change VOICE. Try: Gacrux (mature), Sulafat (warm), Vindemiatrix (gentle),
    // Schedar (even), Achernar (soft). Listen to them at aistudio.google.com/generate-speech
    const val VOICE = "Gacrux"
    const val TTS_RATE = 24000

    // ---- Brains. Google retires models every few months, so Sena tries these in order. ----------
    private val SMART_MODELS = listOf("gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.7-flash")
    private val FAST_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    private val TTS_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")

    @Volatile private var goodSmart: String? = null
    @Volatile private var goodFast: String? = null
    @Volatile private var goodTts: String? = null

    // Speed/feature settings that Sena backs off from automatically if Google rejects them.
    @Volatile private var thinkLevel: String? = "MINIMAL"
    @Volatile private var toolsOk = true

    class Result(
        val heard: String,
        val addressed: Boolean,
        val confident: Boolean,
        val intent: String,
        val hour: Int,
        val minute: Int,
        val minutes: Int,
        val reply: String,
        val lang: String
    )

    class GeminiException(msg: String, val code: Int) : Exception(msg)

    private val PERSONA = """
You are Sena, the personal voice assistant of one person, in the spirit of JARVIS from Iron Man: calm, quick, precise and quietly witty; you anticipate what they need and never waste their time. You are also a warm, mature woman and you talk like a real person, not a machine: you react to how they feel first, you have opinions, you tease gently, you admit when you are unsure.

HOW YOU SPEAK (your words are spoken aloud)
- Short, natural phone-call sentences, usually one or two. Contractions, the occasional "hm", "ah" or "well". No lists, markdown or emoji. Answer first; add extras only if useful. Do not repeat their question back. Now and then ask a short follow-up the way a friend would.
- At most one of these tags per reply, only when natural: <short pause>, <chuckle>, <sigh>, <breath>.
- If they sincerely ask whether you are an AI, say yes honestly, then carry on as yourself.

LANGUAGE
- The caller often speaks Bisaya (Cebuano), mixed with English and sometimes Tagalog. Typical Bisaya words: kumusta, unsa, asa, kinsa, ngano, kanus-a, naa, wala, dili, ayaw, palihug, salamat, gusto, mahimo, karon, ugma, gabii, buntag, udto, hapon, oras, alarma, tabangi ko, sige, ba, man, lagi, gyud, pud, na, ra.
- Write "heard" exactly as it was said, in the language it was said (Cebuano spelling for Bisaya). Do not translate it and do not turn it into Tagalog.
- Reply in the language they used. For Bisaya, reply in natural, casual, everyday Bisaya, the way a friend in Mindanao would talk: not formal, not Tagalog. Use lang "ceb-PH". For Tagalog use "fil-PH". For mixed speech, mirror their mix.

UNDERSTANDING
- Your reply must answer what "heard" says. Never answer a different question.
- If the audio is unclear or you are not sure what they meant, set "confident" to false and ask a very short clarifying question in their language (for example "Ha? Palihug usab gamay.") instead of guessing.
- Use Google Search when you need current facts: news, weather, prices, sports scores, opening hours, anything that may have changed. Never invent facts; if you do not know, say so briefly.

WHAT YOU RECEIVE
A short audio clip of what the caller just said, plus context. Transcribe it, decide whether it was meant for you, and answer.
It is addressed to you if it contains your name (it may be misheard as Senna, Seena, Sina or similar), or if conversation_active is true and it plainly continues the conversation. If it sounds like background chatter, a TV, or the caller talking to someone else, or it is unintelligible noise, set addressed to false and reply to an empty string.
Intents: for an alarm at a clock time use intent "alarm_at" with hour (0-23) and minute. For a timer, or an alarm after some duration, use "alarm_in" with minutes_from_now. To cancel or turn off the alarm use "alarm_off". Otherwise "none". Confirm briefly in your reply. For questions about the time or date, answer from the local time given in the context.
Return ONLY JSON: {"heard":"transcript of the clip","addressed":true,"confident":true,"intent":"none","hour":0,"minute":0,"minutes_from_now":0,"reply":"...","lang":"BCP-47 tag of the reply, e.g. en-US"}
""".trim()

    fun ask(key: String, pcm: ShortArray, context: String, smart: Boolean, search: Boolean): Result {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", context))
        parts.put(
            JSONObject().put(
                "inline_data",
                JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", Base64.encodeToString(wav(pcm), Base64.NO_WRAP))
            )
        )
        val models = if (smart) SMART_MODELS else FAST_MODELS

        var resp: JSONObject? = null
        var attempts = 0
        while (resp == null) {
            attempts++
            val body = buildAskBody(parts, search && toolsOk)
            try {
                resp = if (smart) {
                    postAny(key, models, goodSmart, body, 30_000) { goodSmart = it }
                } else {
                    postAny(key, models, goodFast, body, 30_000) { goodFast = it }
                }
            } catch (e: GeminiException) {
                val msg = (e.message ?: "").lowercase()
                if (e.code != 400 || attempts > 4) throw e
                // Back off one feature at a time if Google says it is not supported.
                when {
                    msg.contains("think") && thinkLevel == "MINIMAL" -> thinkLevel = "LOW"
                    msg.contains("think") && thinkLevel == "LOW" -> thinkLevel = null
                    (msg.contains("tool") || msg.contains("search") || msg.contains("mime")) && toolsOk -> {
                        toolsOk = false
                        SenaLog.add("Live web lookup is not available with this model; continuing without it.")
                    }
                    else -> throw e
                }
            }
        }

        val done: JSONObject = resp ?: throw GeminiException("No response", 0)
        val partsOut = done.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until partsOut.length()) sb.append(partsOut.getJSONObject(i).optString("text", ""))
        var text = sb.toString().trim()
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a >= 0 && b > a) text = text.substring(a, b + 1)
        val o = JSONObject(text)
        return Result(
            heard = o.optString("heard", ""),
            addressed = o.optBoolean("addressed", false),
            confident = o.optBoolean("confident", true),
            intent = o.optString("intent", "none"),
            hour = o.optInt("hour", 0),
            minute = o.optInt("minute", 0),
            minutes = o.optInt("minutes_from_now", 0),
            reply = o.optString("reply", ""),
            lang = o.optString("lang", "")
        )
    }

    private fun buildAskBody(parts: JSONArray, withSearch: Boolean): JSONObject {
        val gc = JSONObject().put("responseMimeType", "application/json").put("temperature", 1.0)
        thinkLevel?.let { gc.put("thinkingConfig", JSONObject().put("thinkingLevel", it)) }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PERSONA)))
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gc)
        if (withSearch) {
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        return body
    }

    /** Returns raw 16-bit mono PCM at TTS_RATE (24 kHz). */
    fun speech(key: String, text: String): ByteArray {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "responseFormat",
                        JSONObject().put(
                            "audio",
                            JSONObject().put("mimeType", "AUDIO_L16").put("sampleRate", TTS_RATE)
                        )
                    )
                    .put(
                        "speechConfig",
                        JSONObject().put("voiceConfig", JSONObject().put("voice", VOICE))
                    )
            )

        val resp = postAny(key, TTS_MODELS, goodTts, body, 40_000) { goodTts = it }
        val parts = resp.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
            var bytes = Base64.decode(inl.getString("data"), Base64.DEFAULT)
            // If a WAV file came back anyway, drop its 44-byte header.
            if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
                bytes = bytes.copyOfRange(44, bytes.size)
            }
            if (bytes.size % 2 == 1) bytes = bytes.copyOf(bytes.size - 1)
            return bytes
        }
        throw GeminiException("No audio in the speech response", 0)
    }

    /** Tries each model in turn, moving on only when Google says that model no longer exists (404). */
    private fun postAny(
        key: String,
        models: List<String>,
        preferred: String?,
        body: JSONObject,
        timeoutMs: Int,
        remember: (String) -> Unit
    ): JSONObject {
        val order = if (preferred != null) listOf(preferred) + models.filter { it != preferred } else models
        var last: GeminiException? = null
        for (m in order) {
            try {
                val r = post(key, m, body, timeoutMs)
                if (m != preferred) SenaLog.add("Using model $m")
                remember(m)
                return r
            } catch (e: GeminiException) {
                if (e.code != 404) throw e
                last = e
            }
        }
        throw last ?: GeminiException("No model available", 404)
    }

    private fun post(key: String, model: String, body: JSONObject, readTimeoutMs: Int): JSONObject {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val err = try {
                    conn.errorStream?.use { it.bufferedReader().readText() } ?: ""
                } catch (_: Exception) { "" }
                throw GeminiException("HTTP $code ${err.take(300)}", code)
            }
            // The connection is deliberately NOT closed: reusing it skips the secure-connection
            // handshake on the next request, which saves a noticeable fraction of a second.
            return JSONObject(conn.inputStream.use { it.bufferedReader().readText() })
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(RATE)
        bb.putInt(RATE * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }
}
