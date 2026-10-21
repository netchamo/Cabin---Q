package app.cabinq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder

/** Foreground service that keeps the system-wide EQ running in the background. */
class EqService : Service() {

    private val sessions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = SessionReceiver.handle(intent)
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        EqEngine.start(this)
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(sessions, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(sessions, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!EqEngine.enabledPref(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessions) }
        EqEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "System EQ", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cabin Q")
            .setContentText("ה-EQ פועל על השמע של הטלפון")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    companion object {
        private const val CHANNEL = "system_eq"
        private const val ID = 1

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, EqService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, EqService::class.java)) }
    }
}
