package com.sena.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var lang: EditText
    private lateinit var always: CheckBox

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("sena", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad) }
        col.addView(TextView(this).apply { text = "Sena"; textSize = 28f })
        key = EditText(this).apply {
            hint = "Gemini API key"; setText(p.getString("key", "")); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = EditText(this).apply { hint = "Model"; setText(p.getString("model", "gemini-2.5-flash-lite")); setSingleLine() }
        lang = EditText(this).apply {
            hint = "Language code (en-US, fil-PH, es-ES)"
            setText(p.getString("lang", Locale.getDefault().toLanguageTag())); setSingleLine()
        }
        always = CheckBox(this).apply { text = "Respond to everything (no need to say Sena)"; isChecked = p.getBoolean("always", false) }
        status = TextView(this).apply { text = "Not running"; setPadding(0, pad, 0, pad) }
        val start = Button(this).apply { text = "Start Sena" }
        val stop = Button(this).apply { text = "Stop Sena" }
        val alarm = Button(this).apply { text = "Stop alarm" }
        for (v in listOf(key, model, lang, always, status, start, stop, alarm)) col.addView(v)
        start.setOnClickListener { begin() }
        stop.setOnClickListener { stopService(Intent(this, SenaService::class.java)); status.text = "Stopped" }
        alarm.setOnClickListener { Ringer.stop() }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun begin() {
        getSharedPreferences("sena", MODE_PRIVATE).edit()
            .putString("key", key.text.toString().trim())
            .putString("model", model.text.toString().trim().ifEmpty { "gemini-2.5-flash-lite" })
            .putString("lang", lang.text.toString().trim().ifEmpty { "en-US" })
            .putBoolean("always", always.isChecked).apply()
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) go() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) go()
        else status.text = "Sena needs the microphone permission."
    }

    private fun go() {
        startForegroundService(Intent(this, SenaService::class.java))
        status.text = "Sena is running. You can leave this screen."
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
