package app.cabinq

import android.media.audiofx.Visualizer
import android.util.Log

/**
 * Listens to what the phone is playing so the interface can draw it (spectrum + level).
 * Uses Android's Visualizer, which needs the "microphone" permission, but nothing is recorded:
 * it only reads the sound that is already going to the speaker, 20 times a second, in 8-bit resolution.
 *
 * Prefers the most recent player session (Spotify etc. announce theirs); otherwise the global mix.
 * Must be used from the main thread.
 */
object Scope {
    private const val TAG = "CabinQ"
    private var vis: Visualizer? = null
    private var session = -1
    private val pr = Visualizer.MeasurementPeakRms()

    /** Receives one JSON frame: {s:session, sr:Hz, d:[dB per bin ×2, rounded], pk:dB, rms:dB} */
    var sink: ((String) -> Unit)? = null

    fun running() = vis != null

    /** Returns "ok" or "fail". */
    fun start(): String {
        val want = EqEngine.lastPlayer
        if (vis != null && session == want) return "ok"
        stop()
        val order = if (want != 0) listOf(want, 0) else listOf(0)
        for (s in order) {
            try {
                val v = Visualizer(s)
                v.setEnabled(false)
                val range = Visualizer.getCaptureSizeRange()
                v.setCaptureSize(minOf(1024, range[1]))
                v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
                v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, rate: Int) {
                        if (vz == null || w == null) return
                        wave(vz, w, rate)
                    }
                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate(), true, false)
                v.setEnabled(true)
                vis = v; session = s
                Log.i(TAG, "scope on session $s")
                return "ok"
            } catch (t: Throwable) {
                Log.w(TAG, "scope failed on session $s", t)
            }
        }
        return "fail"
    }

    fun stop() {
        vis?.let { runCatching { it.setEnabled(false); it.release() } }
        vis = null; session = -1
    }

    /** Re-target when a player opens or closes its session. */
    fun retarget() { if (vis != null && session != EqEngine.lastPlayer) start() }

    /** Sends the raw waveform (8-bit, centred on 128); the page runs its own windowed FFT, which is far cleaner
     *  than the Visualizer's fixed-point 8-bit spectrum. */
    private fun wave(v: Visualizer, w: ByteArray, rateHz: Int) {
        val sb = StringBuilder(w.size * 4 + 64)
        sb.append("{\"s\":").append(session).append(",\"sr\":").append(rateHz / 1000).append(",\"w\":[")
        for (i in w.indices) { if (i > 0) sb.append(','); sb.append(w[i].toInt() and 0xff) }
        var pk = -96.0; var rms = -96.0
        runCatching { if (v.getMeasurementPeakRms(pr) == Visualizer.SUCCESS) { pk = pr.mPeak / 100.0; rms = pr.mRms / 100.0 } }
        sb.append("],\"pk\":").append(pk).append(",\"rms\":").append(rms).append('}')
        sink?.invoke(sb.toString())
    }

    @Suppress("unused")
    private fun frame(v: Visualizer, fft: ByteArray, rateHz: Int) {
        val n = fft.size              // capture size; bins = n/2
        val bins = n / 2
        val sb = StringBuilder(bins * 4 + 64)
        sb.append("{\"s\":").append(session).append(",\"sr\":").append(rateHz / 1000).append(",\"d\":[")
        // fft[0] = DC (real), fft[1] = Nyquist (real), then (re, im) pairs for bins 1..bins-1
        for (k in 1 until bins) {
            val re = fft[2 * k].toInt(); val im = fft[2 * k + 1].toInt()
            val m = Math.sqrt((re * re + im * im).toDouble())
            val db = if (m < 1e-6) -999 else Math.round(2 * 20 * Math.log10(m / 128.0)).toInt()
            if (k > 1) sb.append(',')
            sb.append(maxOf(db, -240))
        }
        var pk = -96.0; var rms = -96.0
        runCatching { if (v.getMeasurementPeakRms(pr) == Visualizer.SUCCESS) { pk = pr.mPeak / 100.0; rms = pr.mRms / 100.0 } }
        sb.append("],\"pk\":").append(pk).append(",\"rms\":").append(rms).append('}')
        sink?.invoke(sb.toString())
    }
}
