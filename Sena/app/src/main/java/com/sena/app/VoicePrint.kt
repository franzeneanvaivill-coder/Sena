package com.sena.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight on-device voiceprint. No neural model, no downloads.
 *
 * Per utterance: MFCC statistics (mean + std of 19 cepstra over the louder half of the frames)
 * plus median and spread of pitch. A profile stores the mean and spread of those 40 numbers
 * over the training phrases; scoring is a weighted, spread-normalised distance (lower = more like you).
 *
 * This is a convenience lock, not a security feature: it separates clearly different voices well,
 * but similar-sounding voices can pass.
 */
object VoicePrint {
    const val RATE = 16000
    private const val FL = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val NMEL = 26
    private const val NCEP = 19
    private const val F0_N = 412
    private const val LAG_MIN = 40
    private const val LAG_MAX = 228
    const val DIM = NCEP * 2 + 2

    private val window = DoubleArray(FL) { 0.54 - 0.46 * cos(2.0 * PI * it / (FL - 1)) }
    private val bank: Array<DoubleArray> = buildBank()
    private val dct: Array<DoubleArray> = Array(NCEP) { k ->
        DoubleArray(NMEL) { m -> sqrt(2.0 / NMEL) * cos(PI * (k + 1) * (m + 0.5) / NMEL) }
    }

    internal val floorV = DoubleArray(DIM) {
        when {
            it < NCEP -> 0.3
            it < 2 * NCEP -> 0.15
            it == 2 * NCEP -> 0.04
            else -> 0.03
        }
    }
    internal val relV = DoubleArray(DIM) { if (it < 2 * NCEP) 0.03 else 0.0 }
    internal val weight = DoubleArray(DIM) {
        when {
            it < NCEP -> 1.0
            it < 2 * NCEP -> 0.5
            it == 2 * NCEP -> 4.0
            else -> 1.0
        }
    }

    class Profile(val mu: DoubleArray, val sigma: DoubleArray, val n: Int) {
        /** Weighted RMS z-score. Your own voice is ~1, clearly different voices are 2.5+. */
        fun distance(f: DoubleArray): Double {
            var s = 0.0
            var w = 0.0
            for (d in 0 until VoicePrint.DIM) {
                val z = (f[d] - mu[d]) / sigma[d]
                s += VoicePrint.weight[d] * z * z
                w += VoicePrint.weight[d]
            }
            return sqrt(s / w)
        }

        fun toJson(): String {
            val o = JSONObject()
            o.put("n", n)
            val a = JSONArray()
            val b = JSONArray()
            for (d in 0 until VoicePrint.DIM) {
                a.put(mu[d])
                b.put(sigma[d])
            }
            o.put("mu", a)
            o.put("sigma", b)
            return o.toString()
        }

        companion object {
            fun fromJson(s: String): Profile? = try {
                val o = JSONObject(s)
                val a = o.getJSONArray("mu")
                val b = o.getJSONArray("sigma")
                if (a.length() != VoicePrint.DIM || b.length() != VoicePrint.DIM) {
                    null
                } else {
                    Profile(
                        DoubleArray(VoicePrint.DIM) { a.getDouble(it) },
                        DoubleArray(VoicePrint.DIM) { b.getDouble(it) },
                        o.optInt("n", 0)
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun buildProfile(list: List<DoubleArray>): Profile {
        val n = list.size
        val mu = DoubleArray(DIM)
        for (f in list) for (d in 0 until DIM) mu[d] += f[d] / n
        val sigma = DoubleArray(DIM)
        for (d in 0 until VoicePrint.DIM) {
            var v = 0.0
            for (f in list) {
                val x = f[d] - mu[d]
                v += x * x
            }
            val sd = if (n > 1) sqrt(v / (n - 1)) else 0.0
            sigma[d] = max(sd, floorV[d] + relV[d] * kotlin.math.abs(mu[d]))
        }
        return Profile(mu, sigma, n)
    }

    /** Returns the 40-number feature vector, or null if the clip is too short or has no pitch. */
    fun features(pcm: ShortArray): DoubleArray? {
        val n = pcm.size
        val nf = (n - FL) / HOP + 1
        if (nf < 40) return null
        val x = DoubleArray(n) { pcm[it] / 32768.0 }

        val en = DoubleArray(nf)
        for (i in 0 until nf) {
            var s = 0.0
            val st = i * HOP
            for (k in 0 until FL) {
                val v = x[st + k]
                s += v * v
            }
            en[i] = s
        }
        val sorted = en.sortedArray()
        val med = sorted[nf / 2]
        val thr = max(med, sorted[nf - 1] * 0.02)

        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val lm = DoubleArray(NMEL)
        val ceps = ArrayList<DoubleArray>()
        val f0s = ArrayList<Double>()

        for (i in 0 until nf) {
            if (en[i] < thr) continue
            val st = i * HOP
            val prev = if (st > 0) x[st - 1] else 0.0
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (k in 0 until FL) {
                val p = if (k == 0) prev else x[st + k - 1]
                re[k] = (x[st + k] - 0.97 * p) * window[k]
            }
            fft(re, im)
            for (m in 0 until NMEL) {
                var s = 0.0
                val row = bank[m]
                for (k in 0..NFFT / 2) {
                    val w = row[k]
                    if (w != 0.0) s += w * (re[k] * re[k] + im[k] * im[k])
                }
                lm[m] = ln(max(s, 1e-10))
            }
            val c = DoubleArray(NCEP)
            for (k in 0 until NCEP) {
                var s = 0.0
                for (m in 0 until NMEL) s += dct[k][m] * lm[m]
                c[k] = s
            }
            ceps.add(c)
            pitch(x, st, n)?.let { f0s.add(it) }
        }
        if (ceps.size < 30 || f0s.size < 8) return null

        val nc = ceps.size
        val f = DoubleArray(DIM)
        for (k in 0 until NCEP) {
            var mean = 0.0
            for (c in ceps) mean += c[k]
            mean /= nc
            var v = 0.0
            for (c in ceps) {
                val d = c[k] - mean
                v += d * d
            }
            f[k] = mean
            f[NCEP + k] = sqrt(v / nc)
        }
        val fs = f0s.sorted()
        val fm = if (fs.size % 2 == 1) fs[fs.size / 2] else (fs[fs.size / 2 - 1] + fs[fs.size / 2]) / 2.0
        var fmean = 0.0
        for (v in fs) fmean += v
        fmean /= fs.size
        var fv = 0.0
        for (v in fs) fv += (v - fmean) * (v - fmean)
        f[2 * NCEP] = fm
        f[2 * NCEP + 1] = min(sqrt(fv / fs.size), 0.5)
        return f
    }

    /** log2 of the pitch in Hz for the 40 ms window starting at st, or null if unvoiced. */
    private fun pitch(x: DoubleArray, st: Int, n: Int): Double? {
        if (st + F0_N + LAG_MAX > n) return null
        var r0 = 0.0
        for (i in 0 until F0_N) r0 += x[st + i] * x[st + i]
        if (r0 <= 1e-9) return null
        var eb = 0.0
        for (i in 0 until F0_N) eb += x[st + LAG_MIN + i] * x[st + LAG_MIN + i]
        var best = 0.0
        var bestLag = 0
        for (lag in LAG_MIN..LAG_MAX) {
            if (lag > LAG_MIN) {
                val add = x[st + lag + F0_N - 1]
                val sub = x[st + lag - 1]
                eb += add * add - sub * sub
            }
            var dot = 0.0
            for (i in 0 until F0_N) dot += x[st + i] * x[st + lag + i]
            val d = sqrt(r0 * eb)
            if (d > 0.0) {
                val c = dot / d
                if (c > best) {
                    best = c
                    bestLag = lag
                }
            }
        }
        return if (best > 0.5 && bestLag > 0) ln(RATE.toDouble() / bestLag) / ln(2.0) else null
    }

    private fun mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun imel(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    private fun buildBank(): Array<DoubleArray> {
        val lo = mel(80.0)
        val hi = mel(7600.0)
        val bins = IntArray(NMEL + 2) {
            floor((NFFT + 1) * imel(lo + (hi - lo) * it / (NMEL + 1)) / RATE).toInt()
        }
        val b = Array(NMEL) { DoubleArray(NFFT / 2 + 1) }
        for (m in 1..NMEL) {
            val l = bins[m - 1]
            val c = bins[m]
            val r = bins[m + 1]
            for (k in l until c) if (c > l) b[m - 1][k] = (k - l).toDouble() / (c - l)
            for (k in c until r) if (r > c) b[m - 1][k] = (r - k).toDouble() / (r - c)
        }
        return b
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
