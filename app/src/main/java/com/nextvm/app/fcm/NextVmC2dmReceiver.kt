package com.nextvm.app.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * NextVmC2dmReceiver — Legacy GCM/C2DM broadcast receiver for FCM messages.
 *
 * GMS delivers FCM push notifications to registered apps via:
 * 1. FirebaseMessagingService intent (modern, requires Firebase SDK)
 * 2. com.google.android.c2dm.intent.RECEIVE broadcast (legacy, no SDK needed)
 *
 * This receiver handles approach #2, enabling NEXTVM to receive FCM messages
 * from the host GMS without requiring the full Firebase SDK.
 *
 * Declared in manifest with:
 *   <receiver android:name=".fcm.NextVmC2dmReceiver" android:exported="false"
 *       android:permission="com.google.android.c2dm.permission.SEND">
 *       <intent-filter>
 *           <action android:name="com.google.android.c2dm.intent.RECEIVE" />
 *       </intent-filter>
 *   </receiver>
 */
class NextVmC2dmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "C2dmReceiver"
        const val ACTION_C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_C2DM_RECEIVE) return

        Timber.tag(TAG).d("C2DM broadcast received")

        // Forward to NextVmFcmService for routing
        val serviceIntent = Intent(context, NextVmFcmService::class.java).apply {
            action = "com.google.firebase.MESSAGING_EVENT"
            intent.extras?.let { putExtras(it) }
        }

        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start FCM service")
        }
    }
}
