package com.critbox.solanamobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * A short foreground service held while the wallet is in front.
 *
 * Android 16 cuts a background app's network (`blocked=APP_BACKGROUND`), and the
 * game is in the background for the whole wallet round-trip, so a transfer could
 * not fetch a fresh blockhash just before signing. A foreground service lifts that
 * block and also keeps the game's process from being reclaimed while the user is
 * in the wallet. `shortService` needs no extra permission and is capped at about
 * three minutes; [onTimeout] lets it go quietly if a session somehow runs longer.
 *
 * Without POST_NOTIFICATIONS (Godot doesn't ask for it) the notification isn't
 * shown; the service still counts.
 */
class MwaKeepAliveService : Service() {
    companion object {
        private const val TAG = "SolanaMobile"
        private const val CHANNEL = "solana_mobile_wallet"
        private const val NOTIFICATION_ID = 0x50AA

        @Volatile
        private var wanted = false

        /** Starts the service; call while the app is still in the foreground. */
        fun hold(context: Context) {
            if (Build.VERSION.SDK_INT < 34) return
            wanted = true
            try {
                context.startForegroundService(Intent(context, MwaKeepAliveService::class.java))
            } catch (e: Exception) {
                // Not allowed right now (e.g. already in the background): carry on without it.
                wanted = false
                Log.i(TAG, "Keep-alive service not started: ${e.message}")
            }
        }

        /** Lets the service go. Safe to call at any time, even before it has started. */
        fun release(context: Context) {
            if (!wanted) return
            wanted = false
            // The service may not have reached startForeground yet; it checks [wanted]
            // itself once it has, so only stop one that is already running.
            try {
                context.startService(Intent(context, MwaKeepAliveService::class.java).setAction("stop"))
            } catch (e: Exception) {
                Log.i(TAG, "Keep-alive service not stopped: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must always be answered with startForeground().
        enterForeground()
        if (!wanted || intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wallet requests", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Waiting for your wallet")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onTimeout(startId: Int) {
        wanted = false
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        wanted = false
        stopSelf()
    }
}
