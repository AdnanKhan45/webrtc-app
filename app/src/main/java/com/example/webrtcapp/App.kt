package com.example.webrtcapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.getSystemService
import com.example.webrtcapp.push.PushKeys
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.example.webrtcapp.webrtc.RtcDependencies


class App : Application() {

    lateinit var rtcDeps: RtcDependencies
        private set

    override fun onCreate() {
        super.onCreate()

        rtcDeps = RtcDependencies(applicationContext)

        // 1) Firebase init (safe if already initialized)
        try { FirebaseApp.initializeApp(this) } catch (_: Throwable) { /* no-op */ }

        // 2) Ensure FCM auto-init stays on (so token refresh events arrive)
        try { FirebaseMessaging.getInstance().isAutoInitEnabled = true } catch (_: Throwable) {}

        // 3) Create notification channels used by our ringing + ongoing-call flows
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm: NotificationManager = getSystemService() ?: return

        // ---- Ringing channel (loud, full-screen) ----
        val ringChannel = NotificationChannel(
            PushKeys.CHANNEL_RING,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH // shows heads-up
        ).apply {
            description = "Incoming call alerts (fullscreen + ringtone)"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableLights(true)
            enableVibration(true)
            // Use the system ringtone stream so users can control it
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            if (Build.VERSION.SDK_INT >= 29) {
                setAllowBubbles(true)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                // Hint to the system that this is a "call" surface
                setBypassDnd(true) // user can still override in settings
            }
        }

        // ---- Ongoing call channel (quiet, foreground service) ----
        val ongoingChannel = NotificationChannel(
            PushKeys.CHANNEL_ONGOING,
            "Ongoing Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status while you are in an active call"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
            setSound(null, null) // silent
            enableVibration(false)
        }

        nm.createNotificationChannel(ringChannel)
        nm.createNotificationChannel(ongoingChannel)
    }
}