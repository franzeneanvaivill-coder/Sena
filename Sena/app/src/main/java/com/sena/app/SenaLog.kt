package com.sena.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-memory log shown on the main screen. */
object SenaLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun add(msg: String) {
        val text: String
        synchronized(this) {
            lines.add(fmt.format(Date()) + "  " + msg)
            while (lines.size > 60) lines.removeAt(0)
            text = lines.joinToString("\n")
        }
        listener?.invoke(text)
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")
}
