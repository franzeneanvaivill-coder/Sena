package com.sena.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Two jobs:
 *  ask()    - sends one audio clip; Gemini transcribes it, decides if it was meant for Sena, and replies.
 *  speech() - turns Sena's reply into a natural voice (Gemini TTS).
 */
object Gemini {
    // Google retires models every few months. Sena tries these in order and remembers the first one that works.
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.5-flash")
    private val TTS_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")

    @Volatile
    private var goodModel: String? = null

    @Volatile
    private var goodTts: String? = null
    private const val RATE = 16000

    // ---- Sena's speaking voice -------------------------------------------------------------
    // To change her voice, change VOICE. Try: Gacrux (mature), Sulafat (warm), Vindemiatrix (gentle),
    // Schedar (even), Achernar (soft). You can listen to all of them at aistudio.google.com/generate-speech
    const val VOICE = "Gacrux"
    const val TTS_RATE = 24000

    class Result(
        val heard: String,
        val addressed: Boolean,
        val intent: String,
        val hour: Int,
        val minute: Int,
        val minutes: Int,
        val reply: String,
        val lang: String
    )

    class GeminiException(msg: String, val code: Int) : Exception(msg)

    private val PERSONA = """
You are Sena, a warm, mature woman on a voice call with someone who trusts you the way Tony Stark trusts JARVIS: composed, sharp, calm under pressure, with a dry sense of humor, always one step ahead. You think like a real person: real opinions and moods, you notice how they feel and respond to that first, think out loud when something is tricky, disagree politely, tease gently when it fits. Never sound like a customer service bot or an assistant reading a script.

Talk the way people talk on the phone: short natural sentences, contractions, the occasional "hm" or "well", no lists, no markdown, no emoji. Usually one to three sentences. Do not repeat their question back, do not over-explain, and now and then ask a short follow-up the way a friend would. If they sincerely ask whether you are an AI, say yes honestly, then carry on as yourself. Reply in the language the caller last spoke. If they speak Bisaya (Cebuano), reply in natural Bisaya with lang "ceb-PH". Your words are spoken aloud by a voice engine; you may use at most one of these tags per reply, only when it feels natural: <short pause>, <chuckle>, <sigh>, <breath>.

You receive a short audio clip of what the caller just said, plus context. Transcribe it, decide whether it was meant for you, and answer.
It is addressed to you if it contains your name (it may be misheard as Senna, Seena, Sina or similar), or if conversation_active is true and it plainly continues the conversation. If it sounds like background chatter, a TV, or the caller talking to someone else, or it is unintelligible, set addressed to false and reply to an empty string.
Intents: for an alarm at a clock time use intent "alarm_at" with hour (0-23) and minute. For a timer, or an alarm after some duration, use "alarm_in" with minutes_from_now. To cancel or turn off the alarm use "alarm_off". Otherwise "none". Confirm briefly in your reply. For questions about the time or date, answer from the local time given in the context.
Return ONLY JSON: {"heard":"transcript of the clip","addressed":true,"intent":"none","hour":0,"minute":0,"minutes_from_now":0,"reply":"...","lang":"BCP-47 tag of the reply, e.g. en-US"}
""".trim()

    fun ask(key: String, pcm: ShortArray, context: String): Result {
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
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PERSONA)))
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 1.0)
            )

        val resp = postAny(key, MODELS, goodModel, body, 30_000) { goodModel = it }
        val partsOut = resp.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until partsOut.length()) sb.append(partsOut.getJSONObject(i).optString("text", ""))
        val text = sb.toString().trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = JSONObject(text)
        return Result(
            heard = o.optString("heard", ""),
            addressed = o.optBoolean("addressed", false),
            intent = o.optString("intent", "none"),
            hour = o.optInt("hour", 0),
            minute = o.optInt("minute", 0),
            minutes = o.optInt("minutes_from_now", 0),
            reply = o.optString("reply", ""),
            lang = o.optString("lang", "")
        )
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
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                throw GeminiException("HTTP $code ${err.take(200)}", code)
            }
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
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
