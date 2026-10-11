package com.sena.app

import android.content.Context
import android.content.SharedPreferences

object Store {
    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("sena", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = prefs(c).getString("api_key", "") ?: ""
    fun lang(c: Context): String = prefs(c).getString("lang", "en-US") ?: "en-US"
    fun voiceLock(c: Context): Boolean = prefs(c).getBoolean("voice_lock", false)

    /** Start asleep: she ignores everything until you say "Sena, activate". */
    fun startAsleep(c: Context): Boolean = prefs(c).getBoolean("start_asleep", true)

    /** Instant mode: stream audio both ways with the Gemini Live API. */
    fun liveMode(c: Context): Boolean = prefs(c).getBoolean("live_mode", true)

    /** Smarter (slower) brain vs fast brain. */
    fun smartBrain(c: Context): Boolean = prefs(c).getBoolean("smart_brain", true)

    /** Let Gemini search Google for current facts. */
    fun webSearch(c: Context): Boolean = prefs(c).getBoolean("web_search", true)

    /** 0 = strict, 100 = lenient */
    fun sensitivity(c: Context): Int = prefs(c).getInt("sensitivity", 40)

    /** Max voiceprint distance that still counts as "you". Range 1.3 .. 3.5 */
    fun threshold(c: Context): Double = 1.3 + sensitivity(c) / 100.0 * 2.2

    fun profile(c: Context): VoicePrint.Profile? =
        prefs(c).getString("voiceprint", null)?.let { VoicePrint.Profile.fromJson(it) }

    fun saveProfile(c: Context, p: VoicePrint.Profile) {
        prefs(c).edit().putString("voiceprint", p.toJson()).apply()
    }

    fun clearProfile(c: Context) {
        prefs(c).edit().remove("voiceprint").putBoolean("voice_lock", false).apply()
    }
}
