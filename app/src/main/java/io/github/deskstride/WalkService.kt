package io.github.deskstride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat

/**
 * Foreground service: does nothing by itself, it keeps the process (and therefore [Walk] and Bluetooth)
 * alive while connected to the treadmill, even with the screen off or the app swiped from recents.
 */
class WalkService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        running = true
        return START_NOT_STICKY // if the system kills it the connection is gone anyway: no restart
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "walk"
        @Volatile private var running = false

        // From the background (e.g. "connect" from the watch with the app closed) Android may refuse to start it:
        // the connection still goes ahead, just without protection.
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WalkService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WalkService::class.java)) }

        fun refresh(ctx: Context) {
            if (running) ctx.getSystemService(NotificationManager::class.java).notify(ID, notification(ctx))
        }

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
            val t = Walk.tracker
            val (title, text) = if (t.running) {
                val s = (System.currentTimeMillis() - t.startMs) / 1000
                val head = ctx.getString(if (t.paused) R.string.phase_paused else R.string.notif_in_progress)
                "$head · ${Fmt.duration(s)} · ${Fmt.km(t.distanceM)} km" to
                    "${Fmt.kmh(Walk.lastPacket?.actualKmh ?: 0.0)} km/h · " + ctx.getString(R.string.steps_count, Fmt.int(t.steps))
            } else {
                Walk.statusText to ctx.getString(R.string.notif_tap_to_open)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
