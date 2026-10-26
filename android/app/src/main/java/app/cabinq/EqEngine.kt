package app.cabinq

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * System-wide processing built on Android's DynamicsProcessing effect:
 *
 *   input gain → pre-EQ (the drawn curve) → multiband compressor (SMOOTH) → limiter (GLUE or ceiling)
 *
 * The interface draws a parametric curve; it is re-sampled here onto [BANDS] log-spaced bands
 * (20 Hz – 20 kHz) for the pre-EQ stage. SMOOTH uses 7 multiband-compressor bands: three
 * active regions (mud, harsh, ess) with neutral bands between them. The limiter stage is either a
 * safety ceiling or, with GLUE on, a bus compressor whose make-up never pushes full scale past the ceiling.
 *
 * The effect is attached to the global output mix (session 0) where the device allows it,
 * and to every player session that is announced through the standard audio-effect broadcast.
 */
object EqEngine {
    private const val TAG = "CabinQ"
    private const val PREFS = "cabinq"
    const val BANDS = 96
    private const val MBC = 7

    private val effects = HashMap<Int, DynamicsProcessing>()

    private val cutoffs = FloatArray(BANDS) { i -> (20.0 * Math.pow(1000.0, (i + 1.0) / BANDS)).toFloat() }
    private val gains = FloatArray(BANDS)
    private var post = 0f
    private var limCeil = -1f
    private var limRel = 80f
    private var limOn = true

    private class Region(var lo: Float, var hi: Float, var thr: Float, var ratio: Float, var att: Float, var rel: Float)
    private var smoothOn = false
    private val regions = arrayOf(
        Region(200f, 500f, 0f, 1f, 20f, 150f),
        Region(2000f, 5000f, 0f, 1f, 5f, 80f),
        Region(6000f, 10000f, 0f, 1f, 1f, 40f)
    )

    private var glueOn = false
    private var glueThr = -18f
    private var glueRatio = 2f
    private var glueAtt = 10f
    private var glueRel = 200f
    private var glueMakeup = 0f

    /** Most recent player session (0 when none); the scope listens there first. */
    @Volatile var lastPlayer = 0
        private set
    var onSessionsChanged: (() -> Unit)? = null

    @Volatile var enabled = false
        private set
    @Volatile var globalOk = false
        private set

    @Synchronized
    fun start(ctx: Context) {
        load(ctx)
        enabled = true
        globalOk = attach(0)
        Log.i(TAG, "engine started, global=$globalOk")
    }

    @Synchronized
    fun stop() {
        effects.values.forEach { runCatching { it.enabled = false; it.release() } }
        effects.clear()
        enabled = false
        globalOk = false
        lastPlayer = 0
        onSessionsChanged?.invoke()
    }

    @Synchronized
    fun attach(session: Int): Boolean {
        if (!enabled) return false
        if (effects.containsKey(session)) return true
        return try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,            // channels
                true, BANDS,  // pre-EQ: the curve
                true, MBC,    // multiband compressor: SMOOTH
                false, 0,     // post-EQ
                true          // limiter: GLUE or ceiling
            ).setPreferredFrameDuration(10f).build()
            val dp = DynamicsProcessing(Int.MAX_VALUE, session, cfg)
            applyTo(dp)
            dp.enabled = true
            effects[session] = dp
            if (session != 0) { lastPlayer = session; onSessionsChanged?.invoke() }
            Log.i(TAG, "attached to session $session")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not attach to session $session", t)
            false
        }
    }

    @Synchronized
    fun detach(session: Int) {
        if (session == 0) return
        effects.remove(session)?.let { runCatching { it.enabled = false; it.release() } }
        if (lastPlayer == session) { lastPlayer = effects.keys.lastOrNull { it != 0 } ?: 0; onSessionsChanged?.invoke() }
    }

    /** JSON from the interface: {cutoffs:[...], gains:[...], post:dB, lim:{...}, smooth:{...}, glue:{...}} */
    @Synchronized
    fun setCurve(json: String, ctx: Context) {
        try {
            parse(JSONObject(json))
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("curve", json).apply()
            effects.values.forEach { runCatching { applyTo(it) } }
        } catch (t: Throwable) {
            Log.w(TAG, "bad curve", t)
        }
    }

    fun setEnabledPref(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
    }

    fun enabledPref(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun statusJson(): String = JSONObject()
        .put("enabled", enabled)
        .put("globalOk", globalOk)
        .put("sessions", synchronized(this) { effects.keys.count { it != 0 } })
        .put("bands", BANDS)
        .put("smooth", true)
        .toString()

    private fun load(ctx: Context) {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("curve", null) ?: return
        runCatching { parse(JSONObject(s)) }
    }

    private fun parse(o: JSONObject) {
        // The page may send any number of points (older pages: 48). Re-sample onto our bands, in log frequency.
        val c: JSONArray = o.getJSONArray("cutoffs")
        val g: JSONArray = o.getJSONArray("gains")
        val n = minOf(c.length(), g.length())
        if (n > 0) {
            val fx = DoubleArray(n) { Math.log(c.getDouble(it).coerceIn(10.0, 24000.0)) }
            val gy = DoubleArray(n) { g.getDouble(it).coerceIn(-30.0, 24.0) }
            for (i in 0 until BANDS) {
                // gain at the band's centre (geometric mean of its edges)
                val lo = if (i == 0) 20.0 else cutoffs[i - 1].toDouble()
                val x = Math.log(Math.sqrt(lo * cutoffs[i]))
                gains[i] = interp(fx, gy, x).toFloat()
            }
        }
        post = o.optDouble("post", 0.0).toFloat().coerceIn(-24f, 24f)
        o.optJSONObject("lim")?.let { l ->
            limOn = l.optBoolean("on", true)
            limCeil = l.optDouble("ceil", -1.0).toFloat().coerceIn(-24f, 0f)
            limRel = l.optDouble("rel", 80.0).toFloat().coerceIn(5f, 1000f)
        }
        val sm = o.optJSONObject("smooth")
        smoothOn = sm?.optBoolean("on", false) ?: false
        sm?.optJSONArray("r")?.let { a ->
            for (i in 0 until minOf(3, a.length())) {
                val r = a.getJSONObject(i); val d = regions[i]
                d.lo = r.optDouble("lo", d.lo.toDouble()).toFloat().coerceIn(30f, 18000f)
                d.hi = r.optDouble("hi", d.hi.toDouble()).toFloat().coerceIn(40f, 19000f)
                d.thr = r.optDouble("thr", 0.0).toFloat().coerceIn(-60f, 0f)
                d.ratio = r.optDouble("ratio", 1.0).toFloat().coerceIn(1f, 20f)
                d.att = r.optDouble("att", d.att.toDouble()).toFloat().coerceIn(0.1f, 500f)
                d.rel = r.optDouble("rel", d.rel.toDouble()).toFloat().coerceIn(5f, 2000f)
            }
        }
        val gl = o.optJSONObject("glue")
        glueOn = gl?.optBoolean("on", false) ?: false
        gl?.let {
            glueThr = it.optDouble("thr", -18.0).toFloat().coerceIn(-50f, 0f)
            glueRatio = it.optDouble("ratio", 2.0).toFloat().coerceIn(1f, 20f)
            glueAtt = it.optDouble("att", 10.0).toFloat().coerceIn(0.1f, 300f)
            glueRel = it.optDouble("rel", 200.0).toFloat().coerceIn(10f, 2000f)
            glueMakeup = it.optDouble("makeup", 0.0).toFloat().coerceIn(0f, 30f)
        }
    }

    private fun interp(x: DoubleArray, y: DoubleArray, v: Double): Double {
        if (v <= x[0]) return y[0]
        if (v >= x[x.size - 1]) return y[y.size - 1]
        var i = 1
        while (i < x.size && x[i] < v) i++
        val t = (v - x[i - 1]) / Math.max(1e-9, x[i] - x[i - 1])
        return y[i - 1] + (y[i] - y[i - 1]) * t
    }

    private fun applyTo(dp: DynamicsProcessing) {
        for (i in 0 until BANDS) {
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]))
        }
        dp.setInputGainAllChannelsTo(post)

        // SMOOTH: band i covers (cutoff[i-1], cutoff[i]]; odd bands are the three regions, even bands stay neutral.
        val edges = FloatArray(MBC)
        var prev = 20f
        for (k in 0 until 3) {
            val r = regions[k]
            val lo = maxOf(r.lo, prev * 1.05f)
            val hi = maxOf(r.hi, lo * 1.1f)
            edges[2 * k] = lo; edges[2 * k + 1] = hi; prev = hi
        }
        edges[MBC - 1] = maxOf(22000f, prev * 1.05f)
        for (i in 0 until MBC) {
            val r = if (i % 2 == 1) regions[i / 2] else null
            val active = smoothOn && r != null && r.ratio > 1.01f
            dp.setMbcBandAllChannelsTo(i, DynamicsProcessing.MbcBand(
                true, edges[i],
                r?.att ?: 10f, r?.rel ?: 100f,
                if (active) r!!.ratio else 1f,
                if (active) r!!.thr else 0f,
                if (active) 6f else 0f,   // knee
                -90f, 1f,                 // no gate / expander
                0f, 0f
            ))
        }

        // Limiter stage. attack ms, release ms, ratio, threshold dB, post-gain dB.
        if (glueOn) {
            // Make-up is capped so a full-scale input still lands at the ceiling.
            val ceil = if (limOn) limCeil else -0.1f
            val safe = ceil - glueThr * (1f - 1f / glueRatio)
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, glueAtt, glueRel, glueRatio, glueThr, minOf(glueMakeup, safe)))
        } else {
            // With the limiter switched off in the app, a safety limit just under 0 dBFS stays in place.
            val thr = if (limOn) limCeil else -0.1f
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, limRel, 20f, thr, 0f))
        }
    }
}
