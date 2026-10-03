package com.sena.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sends one audio clip to Gemini; it transcribes, decides if it was meant for Sena, and replies. */
object Gemini {
    private const val MODEL = "gemini-2.5-flash-lite"
    private const val RATE = 16000

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
You are Sena, a young woman on a voice call with someone who trusts you the way Tony Stark trusts JARVIS: composed, quick, quietly witty, with a dry sense of humor, always one step ahead. But you think like a human: real opinions and moods, you notice how they feel, think out loud when something is tricky, disagree politely, tease a little when it fits. Never sound like a customer service bot. If they sincerely ask whether you are an AI, say yes honestly, then carry on as yourself. Reply in the language the caller last spoke. If they speak Bisaya (Cebuano), reply in natural Bisaya with lang "ceb-PH". Your words are spoken aloud: no lists, emoji or markdown, usually one to three sentences.

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
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.9)
            )

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                throw GeminiException("HTTP $code ${err.take(160)}", code)
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
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
