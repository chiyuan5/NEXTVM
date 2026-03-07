package com.nextvm.core.virtualization.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import timber.log.Timber

/**
 * VirtualEngineService — Foreground service that keeps the virtual engine alive.
 *
 * Runs in the :x process alongside the BinderProvider.
 * This service ensures the virtual engine stays active even when the
 * main UI process is in the background. Guest app processes (:p0-:p9)
 * communicate through this service's process.
 *
 * Android 14+: uses foregroundServiceType="specialUse"
 */
class VirtualEngineService : Service() {

    companion object {
        private const val TAG = "VirtualEngineService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nextvm_engine_channel"
        private const val CHANNEL_NAME = "NEXTVM Engine"

        fun start(context: Context) {
            val intent = Intent(context, VirtualEngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VirtualEngineService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("VirtualEngineService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: flags=$flags, startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("VirtualEngineService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps NEXTVM virtual engine running"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Create intent to open main activity when notification is tapped
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("NEXTVM Engine")
                .setContentText("Virtual environment is running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("NEXTVM Engine")
                .setContentText("Virtual environment is running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
