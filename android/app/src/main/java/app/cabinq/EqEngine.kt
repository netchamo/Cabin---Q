package app.cabinq

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * System-wide EQ built on Android's DynamicsProcessing effect.
 *
 * The interface draws a parametric curve; it is sampled into [BANDS] narrow bands
 * (log-spaced, 20 Hz – 20 kHz) and loaded into the effect's pre-EQ stage.
 * A limiter at the end protects against clipping when bands are boosted.
 *
 * The effect is attached to the global output mix (session 0) where the device allows it,
 * and to every player session that is announced through the standard audio-effect broadcast.
 */
object EqEngine {
    private const val TAG = "CabinQ"
    private const val PREFS = "cabinq"
    const val BANDS = 48

    private val effects = HashMap<Int, DynamicsProcessing>()

    private var cutoffs = FloatArray(BANDS) { i -> (20.0 * Math.pow(1000.0, (i + 1.0) / BANDS)).toFloat() }
    private var gains = FloatArray(BANDS)
    private var post = 0f
    private var limCeil = -1f
    private var limRel = 80f
    private var limOn = true

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
                false, 0,     // multiband compressor (not used yet)
                false, 0,     // post-EQ
                true          // limiter
            ).setPreferredFrameDuration(10f).build()
            val dp = DynamicsProcessing(Int.MAX_VALUE, session, cfg)
            applyTo(dp)
            dp.enabled = true
            effects[session] = dp
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
    }

    /** JSON from the interface: {cutoffs:[...], gains:[...], post:dB} */
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
        .toString()

    private fun load(ctx: Context) {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("curve", null) ?: return
        runCatching { parse(JSONObject(s)) }
    }

    private fun parse(o: JSONObject) {
        val c: JSONArray = o.getJSONArray("cutoffs")
        val g: JSONArray = o.getJSONArray("gains")
        val n = minOf(BANDS, c.length(), g.length())
        for (i in 0 until n) {
            cutoffs[i] = c.getDouble(i).toFloat().coerceIn(10f, 22000f)
            gains[i] = g.getDouble(i).toFloat().coerceIn(-30f, 24f)
        }
        post = o.optDouble("post", 0.0).toFloat().coerceIn(-24f, 24f)
        o.optJSONObject("lim")?.let { l ->
            limOn = l.optBoolean("on", true)
            limCeil = l.optDouble("ceil", -1.0).toFloat().coerceIn(-24f, 0f)
            limRel = l.optDouble("rel", 80.0).toFloat().coerceIn(5f, 1000f)
        }
    }

    private fun applyTo(dp: DynamicsProcessing) {
        for (i in 0 until BANDS) {
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]))
        }
        dp.setInputGainAllChannelsTo(post)
        // attack ms, release ms, ratio, threshold dB, post-gain dB.
        // With the limiter switched off in the app, a safety limit just under 0 dBFS stays in place.
        val thr = if (limOn) limCeil else -0.1f
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, limRel, 20f, thr, 0f))
    }
}
