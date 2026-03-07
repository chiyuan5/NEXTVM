package com.nextvm.app.fcm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.nextvm.core.virtualization.engine.VirtualEngine
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * NextVmFcmService — FCM message entry point for virtual app push notifications.
 *
 * This Service receives FCM / Firebase MESSAGING_EVENT intents from the host GMS,
 * then routes each message to the correct virtual app instance via GmsFcmProxy.
 *
 * Delivery flow:
 * Host GMS  →  com.google.firebase.MESSAGING_EVENT intent
 *           →  NextVmFcmService.onStartCommand()
 *           →  VirtualEngine.gmsManager.routeFcmMessage(data)
 *           →  Identifies the target virtual instance (by token / senderID)
 *           →  Delivers the FCM message to the guest app's callback
 *
 * Declaration in AndroidManifest.xml:
 *   <service android:name=".fcm.NextVmFcmService" android:exported="false">
 *       <intent-filter>
 *           <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *       </intent-filter>
 *   </service>
 */
@AndroidEntryPoint
class NextVmFcmService : Service() {

    companion object {
        private const val TAG = "NvmFcmSvc"
    }

    @Inject
    lateinit var engine: VirtualEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            handleFcmIntent(intent)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handleFcmIntent(intent: Intent) {
        try {
            val action = intent.action ?: return
            val extras = intent.extras ?: Bundle()

            Timber.tag(TAG).d("FCM intent received: action=$action")

            // Route the message through the virtual GMS manager
            val gmsManager = engine.getGmsManager()

            // Determine target instance
            val targetInstanceId = gmsManager.routeFcmMessage(extras)

            if (targetInstanceId == null) {
                Timber.tag(TAG).w("Could not route FCM — no matching virtual instance")
                return
            }

            // Build delivery intent for the virtual app
            val fcmMessage = buildFcmMessage(extras)
            val deliveryIntent = gmsManager.fcmProxy.buildDeliveryIntent(targetInstanceId, fcmMessage)

            // Deliver to the guest app's virtual Service
            // The virtual broadcast manager will route this to the guest's
            // FirebaseMessagingService.onMessageReceived() equivalent
            engine.deliverFcmToInstance(targetInstanceId, deliveryIntent)

            Timber.tag(TAG).i("FCM delivered to instance: $targetInstanceId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling FCM intent")
        }
    }

    private fun buildFcmMessage(
        extras: Bundle
    ): com.nextvm.core.services.gms.GmsFcmProxy.FcmMessage {
        val data = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            val value = extras.getString(key)
            if (value != null && !key.startsWith("google.") && !key.startsWith("gcm.")) {
                data[key] = value
            }
        }

        val notifTitle = extras.getString("gcm.notification.title")
        val notifBody = extras.getString("gcm.notification.body")
        val notification = if (notifTitle != null || notifBody != null) {
            com.nextvm.core.services.gms.GmsFcmProxy.NotificationPayload(
                title = notifTitle,
                body = notifBody,
                icon = extras.getString("gcm.notification.icon"),
                clickAction = extras.getString("gcm.notification.click_action")
            )
        } else null

        return com.nextvm.core.services.gms.GmsFcmProxy.FcmMessage(
            from = extras.getString("from") ?: "",
            to = extras.getString("google.to") ?: "",
            messageId = extras.getString("google.message_id") ?: "",
            data = data,
            notification = notification
        )
    }
}
