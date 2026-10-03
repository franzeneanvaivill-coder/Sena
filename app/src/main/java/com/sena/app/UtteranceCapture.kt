package com.sena.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Reads the microphone (16 kHz mono PCM16) and cuts it into utterances with a simple
 * energy-based voice activity detector. Replaces Android's SpeechRecognizer from Stage 1,
 * because SpeechRecognizer never exposes the raw audio that voice recognition needs.
 */
class UtteranceCapture {
    companion object {
        const val RATE = 16000
        const val FRAME = 320            // 20 ms
        private const val PREROLL = 15   // keep 300 ms before speech starts
        private const val HANG = 35      // 700 ms of silence ends an utterance
        private const val MAX_FRAMES = 750 // 15 s hard limit
        private const val MIN_VOICED = 12  // ignore clicks shorter than 240 ms
        private const val CALIB = 25
    }

    private var rec: AudioRecord? = null
    private var noise = 0.0
    private var calib = 0

    @Volatile
    var failed = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME * 2 * 10)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        rec = r
        failed = false
        noise = 0.0
        calib = 0
        return true
    }

    fun stop() {
        rec?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        rec = null
    }

    /** Drop whatever is buffered (used after Sena speaks, so she doesn't hear herself). */
    fun flush() {
        val r = rec ?: return
        try {
            r.stop()
            r.startRecording()
        } catch (_: Exception) {}
    }

    /** Blocks until one utterance is captured. Returns null on timeout, cancel or mic failure. */
    fun next(timeoutMs: Long, cancelled: () -> Boolean): ShortArray? {
        val r = rec ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val frames = ArrayList<ShortArray>()
        var inSpeech = false
        var loud = 0
        var quiet = 0
        var voiced = 0

        while (!cancelled()) {
            if (!inSpeech && SystemClock.elapsedRealtime() > deadline) return null
            val f = ShortArray(FRAME)
            val rms = readFrame(r, f)
            if (rms < 0.0) {
                failed = true
                return null
            }
            if (calib < CALIB) {
                noise = (noise * calib + rms) / (calib + 1)
                calib++
                continue
            }
            val thr = maxOf(noise * 3.0, 350.0)
            if (!inSpeech) {
                if (rms < thr) noise = noise * 0.97 + rms * 0.03
                frames.add(f)
                if (frames.size > PREROLL) frames.removeAt(0)
                loud = if (rms > thr) loud + 1 else 0
                if (loud >= 4) {
                    inSpeech = true
                    quiet = 0
                    voiced = loud
                }
            } else {
                frames.add(f)
                if (rms > thr * 0.7) {
                    quiet = 0
                    voiced++
                } else {
                    quiet++
                }
                if (quiet >= HANG || frames.size >= MAX_FRAMES) {
                    if (voiced >= MIN_VOICED) return join(frames)
                    frames.clear()
                    inSpeech = false
                    loud = 0
                    quiet = 0
                    voiced = 0
                }
            }
        }
        return null
    }

    private fun readFrame(r: AudioRecord, out: ShortArray): Double {
        var n = 0
        while (n < FRAME) {
            val k = r.read(out, n, FRAME - n)
            if (k <= 0) return -1.0
            n += k
        }
        var s = 0.0
        for (v in out) s += v.toDouble() * v.toDouble()
        return sqrt(s / FRAME)
    }

    private fun join(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.size * FRAME)
        var p = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, p, FRAME)
            p += FRAME
        }
        return out
    }
}
