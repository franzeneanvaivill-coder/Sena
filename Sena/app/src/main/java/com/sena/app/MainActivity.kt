package com.sena.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var keyBox: EditText
    private lateinit var langBox: EditText
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var trainBtn: Button
    private lateinit var forgetBtn: Button
    private lateinit var voiceInfo: TextView
    private lateinit var lockSwitch: Switch
    private lateinit var strictLabel: TextView
    private lateinit var strictBar: SeekBar
    private lateinit var logView: TextView

    @Volatile
    private var enrolling = false
    private var pending: String? = null
    @Volatile
    private var trainPrompt = ""

    private val phrases = listOf(
        "Sena, what time is it?",
        "Hey Sena, set an alarm for six thirty in the morning.",
        "The quick brown fox jumps over the lazy dog.",
        "Sena, tell me something interesting about space.",
        "I think it might rain later, so maybe I'll stay in.",
        "Good morning Sena, how did you sleep?",
        "Please remind me to call my family this evening.",
        "Sena, turn off the alarm."
    )

    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = s
        textSize = sp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun button(s: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Store.prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(text("Sena", 28f, true))
        status = text("", 14f)
        col.addView(status)

        col.addView(text("Gemini API key (aistudio.google.com/apikey)", 13f))
        keyBox = EditText(this).apply {
            setText(Store.apiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        col.addView(keyBox)

        col.addView(text("Language code (e.g. en-US, fil-PH, ceb-PH)", 13f))
        langBox = EditText(this).apply {
            setText(Store.lang(this@MainActivity))
            setSingleLine()
        }
        col.addView(langBox)

        startBtn = button("Start Sena") { onStartStop() }
        col.addView(startBtn)
        col.addView(button("Stop alarm") {
            sendBroadcast(
                Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP)
            )
        })

        col.addView(text("Voice lock", 20f, true))
        voiceInfo = text("", 13f)
        col.addView(voiceInfo)
        trainBtn = button("Train my voice") { onTrain() }
        col.addView(trainBtn)
        lockSwitch = Switch(this).apply {
            text = "Only obey my voice"
            setOnCheckedChangeListener { v, checked ->
                if (v.isPressed) p.edit().putBoolean("voice_lock", checked).apply()
            }
        }
        col.addView(lockSwitch)
        strictLabel = text("", 13f)
        col.addView(strictLabel)
        strictBar = SeekBar(this).apply {
            max = 100
            progress = Store.sensitivity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) p.edit().putInt("sensitivity", v).apply()
                    updateStrictLabel()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(strictBar)
        forgetBtn = button("Forget my voice") {
            Store.clearProfile(this)
            refreshUi()
        }
        col.addView(forgetBtn)

        col.addView(text("Activity", 20f, true))
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            text = SenaLog.snapshot()
        }
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col) })
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        SenaLog.listener = { s -> runOnUiThread { logView.text = s } }
        logView.text = SenaLog.snapshot()
        ui.post(refresh)
    }

    override fun onPause() {
        saveFields()
        SenaLog.listener = null
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun saveFields() {
        Store.prefs(this).edit()
            .putString("api_key", keyBox.text.toString().trim())
            .putString("lang", langBox.text.toString().trim().ifEmpty { "en-US" })
            .apply()
    }

    private fun updateStrictLabel() {
        strictLabel.text = String.format(
            Locale.US,
            "Strictness: left = strict, right = lenient (limit %.2f). Watch the scores in the activity log and adjust.",
            Store.threshold(this)
        )
    }

    private fun refreshUi() {
        val on = SenaService.running
        startBtn.text = if (on) "Stop Sena" else "Start Sena"
        val profile = Store.profile(this)
        voiceInfo.text = when {
            enrolling -> trainPrompt
            profile != null -> "Voiceprint saved (${profile.n} phrases)."
            else -> "No voiceprint yet. Train it once, in a quiet room."
        }
        status.text = when {
            enrolling -> "Training your voice..."
            on -> "Listening."
            else -> "Stopped."
        }
        trainBtn.text = if (enrolling) "Cancel training" else if (profile != null) "Retrain my voice" else "Train my voice"
        lockSwitch.isEnabled = profile != null && !enrolling
        lockSwitch.isChecked = profile != null && Store.voiceLock(this)
        forgetBtn.visibility = if (profile != null && !enrolling) View.VISIBLE else View.GONE
        updateStrictLabel()
    }

    // ---- permissions ----

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms(then: String) {
        val need = ArrayList<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) {
            proceed(then)
        } else {
            pending = then
            requestPermissions(need.toTypedArray(), 7)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val then = pending
        pending = null
        if (code == 7 && then != null && hasMic()) proceed(then)
    }

    private fun proceed(what: String) {
        if (what == "start") launchService() else startTraining()
    }

    // ---- start / stop ----

    private fun onStartStop() {
        saveFields()
        if (SenaService.running) {
            stopService(Intent(this, SenaService::class.java))
        } else {
            if (Store.apiKey(this).isBlank()) {
                SenaLog.add("Paste your Gemini key first.")
                return
            }
            requestPerms("start")
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, SenaService::class.java))
        val p = Store.prefs(this)
        if (!p.getBoolean("asked_battery", false)) {
            p.edit().putBoolean("asked_battery", true).apply()
            try {
                val pm = getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            } catch (_: Exception) {}
        }
    }

    // ---- voice training ----

    private fun onTrain() {
        if (enrolling) {
            enrolling = false
        } else {
            saveFields()
            requestPerms("train")
        }
    }

    private fun startTraining() {
        if (enrolling) return
        enrolling = true
        trainPrompt = "Getting ready..."
        refreshUi()
        if (SenaService.running) stopService(Intent(this, SenaService::class.java))

        Thread {
            // give the service a moment to release the microphone
            var waited = 0
            while (SenaService.running && waited < 30) {
                Thread.sleep(100)
                waited++
            }
            Thread.sleep(400)

            val cap = UtteranceCapture()
            val feats = ArrayList<DoubleArray>()
            var done = false
            if (!cap.start()) {
                SenaLog.add("Could not open the microphone for training.")
            } else {
                try {
                    var i = 0
                    var fails = 0
                    while (enrolling && i < phrases.size && fails < 8) {
                        trainPrompt = "Say (${i + 1}/${phrases.size}):  \"${phrases[i]}\""
                        val pcm = cap.next(20_000L) { !enrolling }
                        if (pcm == null) {
                            if (cap.failed) break
                            fails++
                            continue
                        }
                        val f = VoicePrint.features(pcm)
                        if (f == null) {
                            trainPrompt = "Too short or too quiet. Try again:  \"${phrases[i]}\""
                            fails++
                            continue
                        }
                        feats.add(f)
                        i++
                    }
                    done = enrolling && feats.size == phrases.size
                } finally {
                    cap.stop()
                }
            }

            if (done) {
                Store.saveProfile(this, VoicePrint.buildProfile(feats))
                Store.prefs(this).edit().putBoolean("voice_lock", true).apply()
                SenaLog.add("Voiceprint saved. Voice lock is on.")
            } else {
                SenaLog.add("Training cancelled or incomplete. Nothing was saved.")
            }
            enrolling = false
            runOnUiThread { refreshUi() }
        }.start()
    }
}
